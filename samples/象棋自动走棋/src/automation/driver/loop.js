// @engine quickjs
"use strict";

/** 逐轮调度：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const machineModule = require("../state-machine.js");
const fenModule = require("../../chess/fen.js");
const pieceCapAudit = require("../piece-cap-audit.js");
const endgamePolicy = require("../endgame-policy.js");

function create(driverState, driverActions) {
    const endgameGuard = endgamePolicy.createGuard();
    /**
     * 一轮：观察 → 喂状态机 → 执行动作。
     * @returns { ok, state, reason, board, side, unknownCells }
     */
    function tick() {
        if (!driverState.running) {
            return { ok: false, reason: "没有在运行" };
        }
        if (driverState.machine.state() === machineModule.STATE.PAUSED) {
            return { ok: true, state: machineModule.STATE.PAUSED, reason: driverState.machine.reason() };
        }
        driverState.stats.ticks += 1;
        driverState.stage("drv:tick");
        // 观察模式只读棋盘，不进入轮次推导/算棋/落子链；模式变更会重新建驱动。
        if (driverState.cfg.observeOnly === true && !driverState.clickAllowed) {
            if (!driverState.grid) driverActions.calibrate();
            const observation = driverActions.observeOnce({});
            if (observation.pending) return {ok:true, observing:true, state:driverState.machine.state(),
                reason:observation.reason, elapsedMs:observation.elapsedMs};
            return { ok: observation.ok, state: driverState.machine.state(), board: observation.chars,
                unknownCells: observation.unknownCells, side: null,
                reason: observation.ok ? "观察中（不算棋、不点击）" : observation.reason };
        }

        // 先非阻塞地把 worker 的算棋结果收回来（不截图、不观察，每轮只花一次 getResult()）。
        // 还在算就直接返回：既不挡界面，也不会在思考期间反复去截屏干扰棋盘。
        const thinking = driverActions.pollCompute();
        driverState.stage("drv:poll");
        if (thinking && thinking.pending) {
            return {
                ok: true,
                state: driverState.machine.state(),
                reason: "算棋中（worker 线程，已 " + Math.round(thinking.elapsedMs) + "ms）",
                thinking: true
            };
        }

        // 预判（对面视角）是非阻塞的增值信息：收回来只更新预判列表，不影响本轮决策。
        driverActions.pollPredict();
        driverActions.pollPrethink();

        driverActions.pump(driverState.machine.dispatch({ type: "TICK", nowMono: driverState.nowMono() }));

        // 没有可用几何（还没定位成功 / 恢复中）就**每一轮都重试定位**：
        // RECOVERING 不是终点，只在恢复时定位一次的话，用户把棋盘切上来之后
        // 会一直停在 RECOVERING（真机踩过：脚本先在自己的界面上启动，定位失败后就再也不试了）。
        if (!driverState.grid) {
            driverActions.calibrate();
        }

        // 定位失败后不再用旧坐标识别/修补基准；按状态机的重试间隔再次定位。
        if (driverState.machine.state() === "RECOVERING" && !driverState.machine.geometryTrusted()) {
            driverActions.pump(driverState.machine.dispatch({
                type: "SNAPSHOT", snapshot: { stable: false, geometryTrusted: false }
            }));
            return { ok: false, state: driverState.machine.state(), reason:
                driverState.machine.geometryTrusted() ? "定位已恢复，等待新观测" : "等待可信棋盘坐标" };
        }

        // ★ 对方应手快路要的「基准快照」必须在 observeOnce **之前**抓：
        //   observeOnce 里的补齐/纠错（真机 2026-09-21 21:39：对方卒 (3,6)→(3,7)，fills 先撤「起点有卒」
        //   的已知事实、把基准置空，又把落点当幻影连清 3 帧）会就地改 lastReliable ——
        //   等快路回头枚举「基准的合法着法」时那一手已经不在基准里，应手又被放跑、白等 45 秒。
        //   快路一律用这份快照推理；采纳时用「快照 + 这一手」重建基准。
        const replyFastBase = driverState.lastReliable
            ? { board: driverState.lastReliable.board.slice(), side: driverState.lastReliable.side }
            : null;
        driverState.stage("drv:observe:begin");
        const observed = driverActions.observeOnce({});
        if (observed.pending) return {ok:true, observing:true, state:driverState.machine.state(),
            reason:observed.reason, elapsedMs:observed.elapsedMs};
        driverState.stage(observed.ok ? "drv:observe:end" : "drv:observe:fail");
        driverState.lastObsSummary = observed.ok
            ? {
                  obs: driverState.stats.observations,
                  unknown: observed.unknownCells,
                  covered: observed.coveredFills || 0,
                  key:
                      observed.unknownCells === 0 && observed.chars
                          ? driverActions.positionKeyOfBoard(observed.chars)
                          : null
              }
            : null;
        if (!observed.ok) {
            endgameGuard.clear();
            driverState.newOpeningStreak = 0;
            if (observed.geometryChanged) driverActions.pump(driverState.machine.dispatch({
                type:"GEOMETRY",trusted:false,reason:observed.reason || "观察窗口或棋盘几何已失效"
            }));
            // 失败不能静默：否则界面停在 WAIT_STABLE，日志里什么都看不到（真机踩过）
            const failures = driverState.stats.unrecognized;
            if (failures <= 2 || failures % 10 === 0) {
                driverActions.note("本轮没看清（第 " + failures + " 次）：" + observed.reason);
            }
            driverActions.pump(
                driverState.machine.dispatch({
                    type: "SNAPSHOT",
                    snapshot: { board: driverState.lastBoard, side: null, fen: null, unknownCells: 90, stable: false }
                })
            );
            return { ok: false, reason: observed.reason, stopRequired:observed.stopRequired === true,
                state: driverState.machine.state() };
        }

        // 换局时新棋盘会比旧残局多十几枚子。旧会话的「子数只减不增」规则会把它们
        // 当幻影删除，因此必须看未经旧事实改写的 rawChars，并在行动前先切断旧会话。
        if (driverActions.looksLikeNewOpening(observed.rawChars, replyFastBase && replyFastBase.board)) {
            driverState.newOpeningStreak += 1;
            if (driverState.newOpeningStreak < 2) {
                driverActions.note("疑似已换新局：原始盘面接近开局，等待下一帧确认（暂不走棋）");
                return {
                    ok: true,
                    state: driverState.machine.state(),
                    reason: "疑似新局，等待第二帧",
                    board: observed.rawChars
                };
            }
            driverState.stats.newGamesDetected += 1;
            driverActions.note("连续两帧确认新局：旧残局的幻影/轮次基准作废，重新定位并识别");
            driverActions.pump(driverState.machine.dispatch({ type: "STOP" }));
            driverActions.resetSession();
            driverActions.pump(driverState.machine.dispatch({ type: "START" }));
            driverActions.pump(
                driverState.machine.dispatch({ type: "READY", modelVersion: driverState.cfg.modelVersion })
            );
            return { ok: true, state: driverState.machine.state(), reason: "已识别换局，正在重建棋盘" };
        }
        driverState.newOpeningStreak = 0;

        // ★ 恢复之后：**按观测量建基准**（用户口径 2026-09-20：用视觉重新对局面）——
        //   不再拿旧基准 + 已知事实去凑原来那张盘面（那正是「一枚在飞的子被删掉」之后卡死的根源）。
        //   两个约束：① 未知格 ≤ `cfg.rebuildMaxUnknown`；② **每个未知格都要能用旧事实填上**
        //   （可靠基准不能含 '?' —— 带 '?' 的基准会把 '?' 当事实抄回去）。
        //   填不上或超限就**等下一帧**（恢复后总有几帧是干净的）。
        let rebuiltThisTick = false;
        if (driverState.needFreshBaseline) {
            const facts = driverState.knownFacts.list();
            const rebuilt = observed.chars.slice();
            let unknown = 0;
            let fillable = true;
            for (let i = 0; i < 90; i++) {
                if (rebuilt[i] !== "?") {
                    continue;
                }
                unknown += 1;
                let filled = false;
                for (let k = 0; k < facts.length; k++) {
                    if (facts[k] && facts[k].index === i && facts[k].char) {
                        rebuilt[i] = facts[k].char;
                        filled = true;
                        break;
                    }
                }
                if (!filled || unknown > driverState.cfg.rebuildMaxUnknown) {
                    fillable = false;
                }
            }
            if (unknown === 0 || fillable) {
                // ★★ 2026-09-20 真机死循环的根因就在这两行之间：
                //   重建基准时用「全盘 OCR 证据」保住了基准那一格（`board[i] = prev[i]`，在
                //   `rebuildBaselineFrom` **内部**发生），但那只改了 `rebuilt` ——
                //   **同一 tick 紧接着的 `sideFor` 用的还是原始观测**，里面那一格仍是模型的
                //   误读 ⇒ 又是「没有合法着法能解释」⇒ 恢复 ⇒ 重建 ⇒ 再撞，
                //   真机连刷 22 次「轮次推不出来」（now21）。
                //   所以**必须在重建之后**把改正写回观测（写回顺序反了就成了空操作 —— 自检抓过）。
                rebuiltThisTick = driverActions.rebuildBaselineFrom(rebuilt);
                for (let i = 0; i < 90; i++) {
                    observed.chars[i] = rebuilt[i];
                }
                driverState.lastBoard = observed.chars.slice();
            }
        }
        /**
         * ★★ 2026-09-21 真机 07:59（「又输了」那局）的根因之一：重建基准用的**只有这一帧**，
         *   而下面的 `sideFor` 紧随其后就把轮次定死了 ⇒ 0.25s 后引擎在没被第二帧佐证过的盘面上
         *   算出「一步杀 29999」（深度 1、2ms），3.5s 后点击 —— 下完这手对局就结算输了。
         *   重建基准自己的注释写着「轮次留给下一帧的变化去推」—— 这里把它落到实处：
         *   **本帧不出手**（快照按「轮次未知」喂给机器），下一帧佐证盘面（UNCHANGED / 唯一合法
         *   着法）之后再算棋。代价只有一帧（~1.2s），换掉的是「单帧当事实」的整类事故。
         */
        if (rebuiltThisTick) {
            /**
             * ★★ 杀棋全盘复核的终判（2026-09-21 下午，闸门 v2 的配套）：
             *   被盖格帧不配当佐证（两帧同错）⇒ tick 里请求了「全盘新鲜重读 + 重建基准」
             *   （跳差分缓存，不共享那个可能错的锚点）。重读结果就在这里判：
             *   盘面对得上 ⇒ 放行；对不上 ⇒ 作废重算。都不再等第二帧。
             *   注意：本分支的 observed.chars 已经是 rebuilt（含事实补齐）后的盘面。
             */
            if (driverState.pendingMate && driverState.pendingMate.freshRequested) {
                const held = driverState.pendingMate;
                driverState.pendingMate = null;
                if (driverActions.positionKeyOfBoard(observed.chars) === held.boardKey) {
                    driverState.stats.mateHoldPassed += 1;
                    driverActions.note("杀棋全盘复核通过：全盘新鲜重读与算棋时盘面一致 → 放行 " + held.iccs);
                    driverActions.recordPlanDecision(held.board, held.side, held.iccs);
                    driverActions.pump(
                        driverState.machine.dispatch(Object.assign({ type: "JOB_RESULT" }, held.outcome))
                    );
                    return {
                        ok: true,
                        state: driverState.machine.state(),
                        reason: "杀棋全盘复核通过",
                        board: observed.chars,
                        side: null,
                        unknownCells: observed.unknownCells
                    };
                }
                driverState.stats.mateHoldVetoed += 1;
                driverActions.note(
                    "杀棋全盘复核未通过：全盘新鲜重读的盘面与算棋时不一致" + "→ 作废这手重算（不再蒙着点）"
                );
                driverActions.pump(
                    driverState.machine.dispatch({
                        type: "JOB_RESULT",
                        jobId: held.jobId,
                        ok: false,
                        reason: "杀棋全盘复核未通过（重读盘面不一致）"
                    })
                );
                return {
                    ok: true,
                    state: driverState.machine.state(),
                    reason: "杀棋全盘复核未通过",
                    board: observed.chars,
                    side: null,
                    unknownCells: observed.unknownCells
                };
            }
            driverState.stats.rebuildHolds += 1;
            driverActions.note("重建基准（只这一帧）：本帧不出手，等下一帧佐证盘面与轮次再算");
            driverActions.pump(
                driverState.machine.dispatch({
                    type: "SNAPSHOT",
                    snapshot: {
                        board: observed.chars,
                        side: null,
                        fen: null,
                        unknownCells: observed.unknownCells,
                        conflicts: observed.conflicts,
                        stable: true,
                        geometryTrusted: true,
                        // 几何版本：新票据要用它，不能拿旧版本（不然点击器核对不上，见 state-machine 的同修复）
                        geometryVersion:
                            driverState.grid && driverState.grid.version != null ? driverState.grid.version : null
                    }
                })
            );
            return {
                ok: true,
                state: driverState.machine.state(),
                reason: "重建基准后等下一帧佐证",
                board: observed.layout,
                side: null,
                unknownCells: observed.unknownCells
            };
        }
        // ★ 对方应手「占用唯一解」快路（2026-09-21，用户提议「提前推理对手的棋」）：
        //   等待对方时，占用扫描就能把那一手定死 —— 不必等 OCR 读出兵种，更不必跨帧佐证/恢复。
        //   拿不出结论（没能力带 occupancy/changed、对不上、多解）一律 null，原路径照走。
        let side = null;
        const fastReply = driverActions.tryAdoptReplyByOccupancy(observed, replyFastBase);
        if (fastReply) {
            side = fastReply.side;
        } else {
            side = driverActions.sideFor(observed.chars, observed.unknownCells);
        }
        /**
         * 记「轮次被未知格挡住」的连续帧数（见 unknownSideStreak 的注释）：
         * 只有「确实有未知格、而且轮次推不出来」才算挡住 —— 推得出来就不算。
         */
        if (side == null && observed.unknownCells > 0) {
            driverState.unknownSideStreak += 1;
        } else {
            driverState.unknownSideStreak = 0;
        }
        // 轮次推断如果给出了「按着法改正过的盘面」（落点格被游戏装饰读错兵种），
        // 就以它为准重算本轮的观测与 lastBoard：这是**唯一合法解释**推出来的事实，
        // 比模型对那两个装饰格的读数可信；不改正的话算棋会拿着错盘面出手。
        if (driverState.lastSideFix && driverState.lastSideFix.board && observed.chars && observed.unknownCells === 0) {
            const fix = driverState.lastSideFix;
            driverState.lastSideFix = null;
            let fixed = 0;
            let cleared = 0;
            for (let i = 0; i < 90; i++) {
                const want = fix.board[i];
                // ⚠️ 空洞（undefined）≠ 空格（null）：空洞是「没有信息」，跳过不动。
                if (want === undefined) {
                    continue;
                }
                /**
                 * ★★ 整格替换 —— **包括「该变空的格子」**（2026-09-25 真机 16:06「黑炮 ×3」的根因）：
                 *   旧写法只写「非空」的格（`if (fix.board[i] && …)`），该变空的起点被整格跳过 ⇒
                 *   观测层刚按旧基准 F1 填回来的旧子**留在原地** ⇒ 同一个子两份
                 *   （对方炮 g8→g4 吃我方炮：起点被填回 'c'、落点改正成 'c' ⇒ 黑炮 3 枚，不可能盘面）
                 *   ⇒ 引擎拒算（"黑c 有 3 个"）⇒ RECOVERING ⇒ 基准重建（着法历史「完整」→「部分」）。
                 *   fix.board 是「唯一合法着法」推出来的盘面（进这里前已被两帧佐证闸门校验），
                 *   它说某格是空的，就该按空的采纳。
                 */
                if (want !== observed.chars[i]) {
                    if ((want === null || want === "") && observed.chars[i]) {
                        cleared += 1;
                    }
                    observed.chars[i] = want;
                    fixed += 1;
                }
            }
            if (fixed > 0) {
                observed.unknownCells = driverActions.countUnknownChars(observed.chars);
                driverState.lastBoard = observed.chars.slice();
                driverState.stats.sideFixes += 1;
                if (cleared > 0) {
                    driverState.stats.sideFixCleared = (driverState.stats.sideFixCleared || 0) + cleared;
                }
                driverActions.note(
                    "盘面已按着法改正 " +
                        fixed +
                        " 格（" +
                        fix.how +
                        "）" +
                        (cleared > 0 ? "，其中清了 " + cleared + " 个「该变空」的格" : "")
                );
                // ★ 改正后再过一次兵种上限体检（同 16:06 事故的第二道保险）：改正本身出过
                //   「该清没清」的 bug ⇒ 同一个子两份；将来再有别的路径写出不可能盘面，也在
                //   **进算棋之前**留痕/回滚，而不是让引擎算到一半炸掉（炸 = 恢复 + 历史降级）。
                const rolled = pieceCapAudit.auditPieceCaps(observed.chars, observed.rawChars, {
                    keepIndexes: driverState.evidenceIndexes
                });
                if (rolled.length > 0) {
                    observed.unknownCells = driverActions.countUnknownChars(observed.chars);
                    driverState.lastBoard = observed.chars.slice();
                    for (let r = 0; r < rolled.length; r++) {
                        const one = rolled[r];
                        driverState.stats.pieceCapRollbacks = (driverState.stats.pieceCapRollbacks || 0) + 1;
                        driverActions.note(
                            "⚠ 兵种上限体检（按着法改正后）：" +
                                one.char +
                                " 超上限 " +
                                one.cap +
                                (one.reverted > 0 ? " ⇒ 回滚 " + one.reverted + " 格到原始读数" : "") +
                                (one.stillOver ? "｜仍超限（引擎会拒算，将走恢复）" : "")
                        );
                    }
                }
            }
        }
        /**
         * ★★ 杀棋佐证闸门（2026-09-21 真机 07:59 输棋的另一半）：
         *   引擎报出「我方杀棋」时先别点 —— 假杀都是**坏盘面**算出来的
         *   （2026-09-20 漏认 (4,1) 黑士 ⇒ 红炮「隔子吃将」29999，连点 12 次全被游戏拒；
         *   2026-09-21 07:59 又同一族：刚重建的盘面上算出车一步杀，点下去反被将死）。
         *   闸门规则：算棋那一帧必须再被**独立观测**复核一次才放行（0 未知格且盘面一致）；
         *   第二帧盘面不一致 ⇒ 作废重算（多半前一帧读错，或者其实已经轮到对方）。
         */
        if (driverState.pendingMate) {
            if (driverState.machine.state() !== machineModule.STATE.THINKING) {
                driverActions.note("杀棋待佐证作废：状态已不是 THINKING（" + driverState.machine.state() + "）");
                driverState.pendingMate = null;
            } else if (
                driverState.stats.observations > driverState.pendingMate.obsAtStart &&
                observed.unknownCells === 0 &&
                driverActions.positionKeyOfBoard(observed.chars) === driverState.pendingMate.boardKey
            ) {
                const coveredNow = driverState.coveredFilledIndexes.length;
                if (coveredNow === 0) {
                    // 干净帧：盘面一致、每个格都是真读出来的 ⇒ 可信的独立复核
                    const held = driverState.pendingMate;
                    driverState.pendingMate = null;
                    driverState.stats.mateHoldPassed += 1;
                    driverActions.note("杀棋佐证通过：第二帧读到同一张盘面（干净帧）→ 放行 " + held.iccs);
                    driverActions.recordPlanDecision(held.board, held.side, held.iccs);
                    driverActions.pump(
                        driverState.machine.dispatch(Object.assign({ type: "JOB_RESULT" }, held.outcome))
                    );
                    return {
                        ok: true,
                        state: driverState.machine.state(),
                        reason: "杀棋佐证通过",
                        board: observed.layout,
                        side: side,
                        unknownCells: observed.unknownCells
                    };
                }
                if (!driverState.pendingMate.freshRequested) {
                    // ★★ 「两帧同错」防不住（真机 09:50 的 d6g6：两帧一致照样放行了假杀）：
                    //   帧一致但含「按基准填回」的格 ⇒ 这两帧其实是**同一份填出来的盘面**，
                    //   不算独立复核 ⇒ 强制一次全盘新鲜重读（跳差分缓存）再判。
                    driverState.pendingMate.freshRequested = true;
                    driverState.pendingMate.freshDeadlineObs = driverState.stats.observations + 10;
                    driverState.forceFreshReadOnce = true;
                    driverState.needFreshBaseline = true;
                    driverActions.note(
                        "杀棋佐证帧含 " +
                            coveredNow +
                            " 格「按基准填回」（可能是两帧同错）" +
                            "→ 强制全盘新鲜重读复核，盘面对上了才出手"
                    );
                } else if (
                    driverState.pendingMate.freshDeadlineObs &&
                    driverState.stats.observations > driverState.pendingMate.freshDeadlineObs
                ) {
                    // 全盘复核迟迟没落地（重建一直被拒/重读一直不干净）⇒ 宁可重算，不要蒙一把
                    const held = driverState.pendingMate;
                    driverState.pendingMate = null;
                    driverState.stats.mateHoldVetoed += 1;
                    driverActions.note("杀棋全盘复核超时（等不到干净的重读盘面）→ 作废这手，等下一轮重算");
                    driverActions.pump(
                        driverState.machine.dispatch({
                            type: "JOB_RESULT",
                            jobId: held.jobId,
                            ok: false,
                            reason: "杀棋全盘复核超时"
                        })
                    );
                    return {
                        ok: true,
                        state: driverState.machine.state(),
                        reason: "杀棋全盘复核超时",
                        board: observed.layout,
                        side: side,
                        unknownCells: observed.unknownCells
                    };
                }
                // 否则：全盘重读/重建还在路上 —— 重建帧那一拍会在 rebuiltThisTick 分支里给出终判
            } else if (
                driverState.stats.observations > driverState.pendingMate.obsAtStart &&
                observed.unknownCells === 0
            ) {
                const held = driverState.pendingMate;
                driverState.pendingMate = null;
                driverState.stats.mateHoldVetoed += 1;
                driverActions.note("杀棋佐证未通过：第二帧盘面与算棋时不一致 → 作废重算（多半前一帧读错了）");
                driverActions.pump(
                    driverState.machine.dispatch({
                        type: "JOB_RESULT",
                        jobId: held.jobId,
                        ok: false,
                        reason: "杀棋佐证未通过（第二帧盘面不一致）"
                    })
                );
                return {
                    ok: true,
                    state: driverState.machine.state(),
                    reason: "杀棋佐证未通过",
                    board: observed.layout,
                    side: side,
                    unknownCells: observed.unknownCells
                };
            }
            // 有未知格的帧佐证不了：继续等（下一帧读到干净的再说）
        }
        if (side == null && observed.unknownCells === 0) {
            // 「等第二帧佐证」不算「推不出来」：它是有意暂缓一帧，下一帧就会给出结论。
            // 算进去的话自检会红（`unknownTurn` 必须为 0），真机上还会让看门狗误触发。
            if (driverState.lastInferHold) {
                driverActions.note("轮次待佐证：同一个解释要在下一次观测里重复出现才采信（防模型重复框伪影）");
            } else {
                driverState.stats.unknownTurn += 1;
                if (!driverState.lastReliable) {
                    const noteCount = driverState.stats.unknownTurn;
                    if (noteCount <= 2 || noteCount % 10 === 0) {
                        driverActions.note(
                            "还没有轮次基准（第 " +
                                noteCount +
                                " 次）：按「现在轮到我」声明一次，" +
                                "或者让局面回到标准开局"
                        );
                    }
                }
            }
        }
        // ⚠️ 双保险：不只看计数，再看盘面（计数与盘面曾不一致，见 observeOnce 末尾的归一化）——
        //   带 '?' 的盘面交给 buildFen 必抛异常，而异常被上层当「本轮异常」吞掉就是空转。
        const fen =
            observed.unknownCells === 0 && observed.chars && observed.chars.indexOf("?") < 0
                ? fenModule.buildFen(observed.chars, side === "b" ? "b" : "w")
                : null;
        // 无攻子的自动求胜只会消耗时间；观察/建议模式与关闭求胜的保和模式继续运行。
        // 判据必须包含当前原始读数及可信前盘，单帧漏兵、未知格、旧补丁都不能触发。
        if (driverState.clickAllowed && driverState.cfg.winSeek && driverState.cfg.stopWithoutAttackers !== false &&
            side === driverState.machine.mySide() && fen && driverState.lastReliable) {
            const frame = driverState.lastResult && driverState.lastResult.frame;
            const identity = frame && (frame.window || frame);
            const endgame = endgameGuard.check({rawBoard:observed.rawChars,board:observed.chars,
                referenceBoard:driverState.lastReliable.board,side:side,
                sessionId:driverState.machine.versions().sessionId,observationId:driverState.stats.observations,
                frameId:identity && identity.frameId});
            if (endgame) {
                driverState.forceFreshReadOnce = true;
                if (endgame.confirmed) {
                    driverState.stats.endgameStops = (driverState.stats.endgameStops || 0) + 1;
                    driverActions.cancelPrethink();
                    driverActions.pump(driverState.machine.dispatch({type:"PAUSE",reason:endgame.reason}));
                    driverActions.note(endgame.reason);
                } else driverActions.note("残局复核：当前无进攻棋子，等待第二张新帧（暂不算棋、不点击）");
                return {ok:true,state:driverState.machine.state(),reason:endgame.reason,
                    stopRequired:endgame.confirmed,board:observed.chars,side:side,unknownCells:0};
            }
        } else endgameGuard.clear();
        const outcome = driverState.machine.dispatch({
            type: "SNAPSHOT",
            snapshot: {
                board: observed.chars,
                side: side,
                fen: fen,
                unknownCells: observed.unknownCells,
                conflicts: observed.conflicts,
                stable: true,
                geometryTrusted: true,
                // 几何版本：识别自对齐会让它 +1，票据必须拿到当前值（否则点击器核对不上直接拒）
                geometryVersion: driverState.grid && driverState.grid.version != null ? driverState.grid.version : null
            }
        });
        if (observed.unknownCells === 0) {
            driverState.stats.stableObservations += 1;
        } else {
            const unknownNotes = driverState.stats.stableObservations + driverState.stats.unknownTurn;
            // 未知格占了大半 ⇒ 不是「个别格子认不出」，而是整帧不对（棋盘不在画面里，
            // 或画面被我们自己的面板/画板挡住）。这种要说清楚，否则会被误当成几何问题
            // （真机曾出现过未知格 44/45：面板画板截进了帧里）。
            const polluted = observed.unknownCells >= 30;
            if (polluted || unknownNotes <= 1 || unknownNotes % 10 === 0) {
                driverActions.note(
                    "本轮有 " +
                        observed.unknownCells +
                        " 个未知格（第 " +
                        driverState.stats.observations +
                        " 次观察）：" +
                        (observed.layout || "（布局没生成）") +
                        (polluted
                            ? "　⚠ 未知格占了大半（" +
                              observed.unknownCells +
                              "/90）——多半是画面被我们自己的窗口/画板挡住，" +
                              "或棋盘不在画面里：把面板挪出棋盘、关掉「识别绘制」再试"
                            : "")
                );
            }
        }
        driverActions.pump(outcome);
        return {
            ok: true,
            state: driverState.machine.state(),
            reason: outcome.reason,
            board: observed.layout,
            side: side,
            unknownCells: observed.unknownCells
        };
    }

    function start() {
        driverState.running = true;
        driverActions.pump(driverState.machine.dispatch({ type: "START" }));
        driverActions.pump(driverState.machine.dispatch({ type: "READY", modelVersion: driverState.cfg.modelVersion }));
        return driverState.machine.state();
    }

    return { tick: tick, start: start };
}

module.exports = { create: create };
