// @engine quickjs
"use strict";

/** 点击执行与快速确认：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const machineModule = require("../state-machine.js");
const transition = require("../../chess/transition.js");
const rulesModule = require("../../chess/rules.js");
const safety = require("../observation-safety.js");
const legal = require("../../chess/legal-moves.js");

function create(driverState, driverActions) {
    /**
     * 取本次尝试的预算（计数 +1）→ { over, reason, moveCount, positionCount }（实现见 attempt-budget.js）。
     * 计数点放在 canClick 通过之后：只有真的把两次点击发出去才算一次「完整尝试」。
     */
    function takeAttemptBudget(ticket) {
        return driverState.attemptBudget.take(ticket);
    }

    /** 确认真实合法变化（落子/应手）后才清空预算。 */
    function clearAttemptBudget(why) {
        driverState.attemptBudget.clear(why);
    }

    function executeTicket(effect) {
        const ticket = effect.ticket;
        const gate = driverState.machine.canClick(ticket);
        const royal = driverState.royalDanger || safety.inspect(driverState.lastRawChars || driverState.lastBoard);
        if (royal && royal.unsafe) {gate.ok=false;gate.reason=royal.reason;}
        // A current, complete circle verified reading also checks king safety before input.
        const raw = driverState.lastRawChars;
        const circles = driverState.lastResult && driverState.lastResult.glyphStats && driverState.lastResult.glyphStats.circleEvidence;
        if (gate.ok && raw && raw.indexOf("?") < 0 && circles && circles.active) {
            const move = ticket.move;
            const verdict = legal.isLegalMove(raw, move.fromCol, move.fromRow, move.toCol, move.toRow, driverState.machine.mySide());
            if (!verdict.legal) {gate.ok=false;gate.reason="当前图像盘面不允许落子：" + verdict.reason;}
        }
        if (!gate.ok) {
            // 到了这一步还被拦下：按「没发出去」处理，绝不点
            driverActions.note("出手前核对不通过：" + gate.reason);
            return driverActions.pump(
                driverState.machine.dispatch({
                    type: "CLICK_RESULT",
                    ticket: ticket,
                    status: "REJECTED",
                    clickCount: 0,
                    reason: gate.reason
                })
            );
        }
        // ---- §11：重试前必须确认「起点子还在」----
        // 局面已经变了（子被吃/已走/记错了）时再点起点，只会点到空点或对方子，
        // 白烧一次尝试额度（预算就是被这种空转吃掉的）。
        const srcIndex = rulesModule.iccsSquare(ticket.moveICCS, 0);
        const trustedBoard =
            driverState.lastReliable && driverState.lastReliable.board
                ? driverState.lastReliable.board
                : driverState.lastBoard;
        if (srcIndex != null && trustedBoard) {
            const here = trustedBoard[srcIndex];
            const mine =
                driverState.machine.mySide() === "w" ? rulesModule.isRedPiece(here) : rulesModule.isBlackPiece(here);
            if (!mine) {
                driverState.stats.sourceGoneRefusals += 1;
                driverActions.note(
                    "重试前核对：起点格(" +
                        ticket.move.fromCol +
                        "," +
                        ticket.move.fromRow +
                        ")现在是 " +
                        (here && here !== "?" ? here : "空/未知") +
                        "，不再是我方子 → **不发点击**（局面已变，重算）"
                );
                return driverActions.pump(
                    driverState.machine.dispatch({
                        type: "CLICK_RESULT",
                        ticket: ticket,
                        status: "REJECTED",
                        clickCount: 0,
                        reason: "起点子已不在原位（局面已变），未发送点击"
                    })
                );
            }
        }
        // ---- 尝试预算（§11：同一局面/同一手不许无限重试）----
        const budget = takeAttemptBudget(ticket);
        if (budget.over) {
            driverActions.note("同局面落子未确认：" + budget.reason + " → 暂停（不再盲点），请看截图与实际通道");
            driverActions.note(
                "  预算明细：本手第 " +
                    budget.moveCount +
                    " 次 / 本局面共 " +
                    budget.positionCount +
                    " 次（上限 " +
                    driverState.cfg.attemptLimitPerMove +
                    " / " +
                    driverState.cfg.attemptLimitPerPosition +
                    "）"
            );
            /**
             * ★★ 2026-09-20 真机 now37（用户报「炮为什么去吃对面的将」）：游戏**反复拒绝**同一个
             * 着法，多半不是点击通道的问题，而是**我们这张盘不对** —— 现场是漏认了 (4,1) 那枚黑士，
             * 中路只剩一个炮架 ⇒ 引擎算出「红炮隔一子吃将」合法（评分 29999）⇒ 连试 12 次全被拒，
             * 白烧 ~100 秒。所以这里不只暂停：**作废可靠基准**，逼下一轮走恢复流程
             * （重新定位 + 全盘重读 + 按观测量重建基准），把「盘面漏子」这条路堵上。
             */
            driverActions.note("  ⇒ 同时作废可靠基准：被游戏反复拒绝 ≈ 我们的盘面漏子了（下一轮重新全盘确认）");
            driverState.lastReliable = null;
            driverActions.clearBaselineBorrow();
            driverActions.trackClear("attempt-budget");
            driverState.needFreshBaseline = true;
            driverState.stats.budgetPauses += 1;
            return driverActions.pump(driverState.machine.dispatch({ type: "PAUSE", reason: budget.reason }));
        }
        // ---- 点击前的前台守卫（真机血泪）----
        // 脚本宿主 RunIntentActivity 是**透明窗口**：用 am start 起脚本后它常常仍是「有焦点的前台」，
        // 游戏只是画在它下面。这时注入的点击会被我们自己吃掉 —— 截图一切正常、棋子纹丝不动、
        // 确认阶段永远 UNCHANGED（K40 实测：一手棋连试 4 次全 UNCHANGED，`dumpsys input` 的
        // FocusedApplications 就是本应用）。所以出手前必须确认前台不是自己；是就自动切一次
        // （最近任务快速切换，不重启游戏），切不回来就不点，别把票据白烧掉。
        if (typeof driverState.opts.foregroundGuard === "function") {
            let guard = null;
            try {
                guard = driverState.opts.foregroundGuard();
            } catch (error) {
                guard = null;
            }
            if (guard && guard.packageChanged) {
                driverActions.note(
                    "前台守卫：点击前前台是" +
                        (guard.ownPackage ? "本应用（脚本宿主）" : guard.package || "其它") +
                        " → 已用最近任务切回上一步应用" +
                        (guard.ok ? "" : "（仍不是目标，先不点）")
                );
            }
            if (guard && guard.ok === false) {
                driverActions.note(
                    "前台不是游戏（当前 " + (guard.package || "未知") + "）：**不发点击**，等下一轮再试"
                );
                return driverActions.pump(
                    driverState.machine.dispatch({
                        type: "CLICK_RESULT",
                        ticket: ticket,
                        status: "CANCELLED",
                        clickCount: 0,
                        reason: "前台不是游戏（" + (guard.package || "未知") + "），未发送点击"
                    })
                );
            }
        }
        // 在执行器发送两次手势之前发布已核对的票据，避免整手执行完才首次显示箭头。
        driverActions.notifyMovePlan(ticket);
        // 出手前作废起点和落点的旧补齐记录；盘面基准仍保留完整的落子前事实。
        // ⚠️ 必须在**点击之前**丢（而不是等确认成功再丢）：否则确认阶段那几帧里，
        // observeOnce 会把旧子补回起点 → 观测看起来「起点还占着、落点也有了」→
        // 被当成动画帧（UNRELIABLE）等下去，而这条记录永远等不到「确认成功」去作废 → 死锁。
        const fromIndex = transition.squareIndex(ticket.moveICCS, 0);
        const destIndex = transition.squareIndex(ticket.moveICCS, 1);
        // 吃子时落点的旧记录属于被吃棋子，不能在确认帧里补回或用它修订落子前基准。
        driverActions.dropPatchesAt([fromIndex, destIndex].filter(function (index) { return index >= 0; }),
            "我方要走 " + ticket.moveICCS + "，起点和落点的旧记录作废");
        // ★ 记下「这一手在飞」：确认期间观察层补齐时，起点按空、落点按走过去的那个子
        //   （不做就会把起点补回旧子 → 幻影子进基准；见 activeOwnMove 的声明注释）
        driverState.activeOwnMove = {
            iccs: ticket.moveICCS,
            fromIndex: fromIndex,
            destIndex: destIndex
        };
        // 出手 = 新的一手：之前那些「刚落下的子」的记录不能再当事实用（局面马上要变）
        driverState.moveEpoch += 1;
        const outcome = driverState.executor.execute(ticket, {
            grid: driverState.grid,
            // 点棋优先用最近一次观测的**实测框中心**（几何版本必须与票据一致，否则退回交点）：
            // 定位的系统性偏移会让「按交点点的击」落在棋子外沿 → 只选中、不落子。
            centers:
                driverState.lastCenters && driverState.lastCentersVersion === ticket.geometryVersion
                    ? driverState.lastCenters
                    : null,
            screen: driverState.opts.screen ? driverState.opts.screen() : null,
            cancel: function () {
                return !driverState.running || driverState.machine.state() === machineModule.STATE.PAUSED;
            }
        });
        // 已知事实补齐表**不在这里清空**：我方新落点的子会被再画一次环，而对方上一手的子
        // 可能还带着环；那些格子一旦能自己认出来（或变成空格/别的子），observeOnce 会自动
        // 把它从表里去掉，所以留着不会补错。
        driverActions.note(
            "点击：" +
                ticket.moveICCS +
                " → " +
                outcome.status +
                "（" +
                outcome.reason +
                "）" +
                "，点击 " +
                outcome.clickCount +
                " 次"
        );
        if (outcome.clicks && outcome.clicks.length > 0) {
            // 每次手势单独记：真实手势名（原地 swipe≠tap、无障碍 press≠click）、格号、用的是
            // 实测框中心还是网格交点、交点坐标、接口返回、起止时刻 —— 通道对照实验就靠这几行。
            for (let i = 0; i < outcome.clicks.length; i++) {
                const click = outcome.clicks[i];
                driverActions.note(
                    "  手势" +
                        (i + 1) +
                        " " +
                        (click.kind || "?") +
                        "：" +
                        (click.method || "未知手势") +
                        " 格(" +
                        click.col +
                        "," +
                        click.row +
                        ") 屏幕(" +
                        click.x +
                        "," +
                        click.y +
                        ")" +
                        (click.usedCenter ? " 实测框中心" : " 网格交点") +
                        (click.latticeX != null ? "（交点 " + click.latticeX + "," + click.latticeY + "）" : "") +
                        " 几何v" +
                        click.geometryVersion +
                        " ok=" +
                        (click.ok ? "是" : "否") +
                        " 耗时" +
                        click.elapsedMs +
                        "ms" +
                        (click.command ? " 命令「" + click.command + "」" : "") +
                        (click.error ? " 错误：" + click.error : "")
                );
                if (i > 0) {
                    const previous = outcome.clicks[i - 1];
                    const gap = click.startedAtMono - previous.finishedAtMono;
                    driverActions.note(
                        "  手势间隔 " +
                            gap +
                            "ms" +
                            (gap < 0 ? "⚠️ 第二次手势在前一次完成前就发出了（手势可能被取消）" : "")
                    );
                }
            }
        }
        return driverActions.pump(
            driverState.machine.dispatch({
                type: "CLICK_RESULT",
                ticket: ticket,
                status: outcome.status,
                clickCount: outcome.clickCount,
                reason: outcome.reason
            })
        );
    }

    /**
     * ROI 快速确认（2026-09-21 提速·方案第 3 条）：点击后**只认起点/终点两个格**。
     *
     * 这步棋是否落地，只看两件事：起点空了 + 终点是我方那颗子。两格都用**硬证据**：
     *   · 终点：单格 OCR 读到字形且与票据预期同（比兵种、忽略大小写）；有像素阵营证据时必须同色；
     *   · 起点：单格 OCR 读不到字**且**墨迹计数为 0（棋子还在飞 = 读不到字但有墨迹 ⇒ 拿不出结论，重试）。
     * 全部成立才返回结果；任一条拿不出来返回 null（调用方照旧走全盘确认）。**不猜。**
     */
    function tryFastConfirmRoI(ticket, expectedBoard) {
        if (driverState.cfg.fastConfirmRoI !== true) {
            return null;
        }
        if (typeof driverState.opts.ocrCellsNow !== "function" || !ticket || !expectedBoard) {
            return null;
        }
        const fromIdx = transition.squareIndex(ticket.moveICCS, 0);
        const destIdx = transition.squareIndex(ticket.moveICCS, 1);
        if (fromIdx < 0 || destIdx < 0) {
            return null;
        }
        const want = expectedBoard[destIdx];
        if (!want || want === "?") {
            return null;
        }
        const wantSide = want === want.toUpperCase() ? "RED" : "BLACK";
        const attempts = Math.max(
            1,
            Math.min(
                4,
                driverState.cfg.fastConfirmAttempts == null
                    ? 2
                    : Math.round(Number(driverState.cfg.fastConfirmAttempts))
            )
        );
        for (let i = 0; i < attempts; i++) {
            driverState.clock(
                i === 0
                    ? driverState.cfg.fastConfirmSettleMs == null
                        ? 300
                        : Number(driverState.cfg.fastConfirmSettleMs)
                    : driverState.cfg.fastConfirmGapMs == null
                      ? 350
                      : Number(driverState.cfg.fastConfirmGapMs)
            );
            let cells = null;
            try {
                cells = driverState.opts.ocrCellsNow([fromIdx, destIdx]);
            } catch (error) {
                return null; // 证据通道异常：直接回全盘确认
            }
            if (!cells || cells.length < 2 || !cells[0] || !cells[1]) {
                return null;
            }
            const from = cells[0];
            const dest = cells[1];
            if (from.unavailable || dest.unavailable) {
                return null; // OCR/几何不可用：回全盘（那边有自己的兜底）
            }
            const fromEmpty =
                !from.ok &&
                from.noText === true &&
                (!from.ink || (Number(from.ink.red) || 0) + (Number(from.ink.black) || 0) === 0);
            const destPiece =
                dest.ok === true &&
                dest.cls != null &&
                String(dest.cls).toLowerCase() === String(want).toLowerCase() &&
                (dest.side == null || dest.side === wantSide);
            if (fromEmpty && destPiece) {
                return {
                    ok: true,
                    destChar: want,
                    fromIndex: fromIdx,
                    destIndex: destIdx,
                    attempts: i + 1
                };
            }
        }
        return null;
    }

    return {
        takeAttemptBudget: takeAttemptBudget,
        clearAttemptBudget: clearAttemptBudget,
        executeTicket: executeTicket,
        tryFastConfirmRoI: tryFastConfirmRoI
    };
}

module.exports = { create: create };
