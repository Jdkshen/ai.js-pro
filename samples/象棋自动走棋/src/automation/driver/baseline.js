// @engine quickjs
"use strict";

/** 基准重建与对方应手：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const transition = require("../../chess/transition.js");
const fenModule = require("../../chess/fen.js");
const legal = require("../../chess/legal-moves.js");
const rulesModule = require("../../chess/rules.js");

function create(driverState, driverActions) {
    /** 标准开局（红先）：第一次观察到的局面就是它时，可以确定轮到红方。 */
    function isStartPosition(board) {
        const start = fenModule.parseFen(fenModule.START_FEN);
        return !!start.ok && legal.boardMatches(board, start.board);
    }

    /**
     * 游戏重开后，上一局的「棋子只会减少」约束不再成立。只用未经旧事实修正的原始观测判断：
     * 旧盘已明显进入中残局，新盘有近乎完整的双方开局阵型，才可能是换局。
     * 这里仅产生候选；tick 还要连续两次独立观察才真正清会话。
     */
    function looksLikeNewOpening(raw, oldBoard) {
        if (!raw || raw.length !== 90 || !oldBoard || oldBoard.length !== 90) {
            return false;
        }
        const start = fenModule.parseFen(fenModule.START_FEN);
        if (!start.ok) {
            return false;
        }
        let oldPieces = 0;
        let pieces = 0;
        let red = 0;
        let black = 0;
        let startMatches = 0;
        let unknown = 0;
        for (let i = 0; i < 90; i++) {
            const was = oldBoard[i];
            const got = raw[i];
            if (was && was !== "?") {
                oldPieces += 1;
            }
            if (got === "?") {
                unknown += 1;
                continue;
            }
            if (!got) {
                continue;
            }
            pieces += 1;
            if (got === got.toUpperCase()) {
                red += 1;
            } else {
                black += 1;
            }
            if (got === start.board[i]) {
                startMatches += 1;
            }
        }
        return (
            oldPieces <= 24 &&
            pieces >= 28 &&
            pieces - oldPieces >= 6 &&
            red >= 13 &&
            black >= 13 &&
            unknown <= 1 &&
            startMatches >= 25 &&
            raw[4] === "k" &&
            raw[85] === "K"
        );
    }

    /**
     * 登记一个「已知事实」格子：本手内那一格一律按这里给的字补齐。
     * 依据都是棋规唯一推出来的事实（票据 / 唯一合法着法），不是猜；那格能自己认出来就自动失效。
     */
    function addPatch(entry, label) {
        if (!entry || entry.index == null || !entry.char) {
            return;
        }
        registerPatch(entry, label);
    }

    /**
     * 登记一个「棋规上必为空」的格子（直线行进的途经格）。
     * char 为空也要能登记：空网格同样会被游戏装饰盖住、被模型读成 '?'，
     * 没人补就永远差一个未知格 → `sideFor` 放弃 → 「轮次未知」空转（K40 实测卡 60 秒）。
     */
    function addEmptyPatch(index, label) {
        registerPatch({ index: index, char: null }, label);
    }

    /** 真正的登记（char 允许为 null = 空网格）。 */
    function registerPatch(entry, label) {
        if (!entry || entry.index == null) {
            return;
        }
        const record = {
            index: entry.index,
            char: entry.char,
            cycle: driverState.verifyCycle,
            ply: driverState.moveEpoch
        };
        // 新记录 = 新开始：连着读空的计数与催过的次数都清零（旧记录可能已经催过好几回了）
        delete driverState.stuckNudgeAt[entry.index];
        if (driverState.knownFacts.upsert(record)) {
            return;
        }
        driverActions.note(
            label +
                " " +
                (entry.char == null ? "空" : entry.char) +
                "（下标 " +
                entry.index +
                "）按已知事实补齐（那格能自己认出来、或有子从那里离开时自动作废）"
        );
    }

    /**
     * 把「已知事实」作废：那些格子里有子**离开**了（新着法的起点），记录不再成立。
     *
     * 为什么必须有它（2026-09-14 真机，auto35.log）：
     * 补齐表以前靠 verifyCycle 整表作废 —— 我方一确认，周期 +1，连「对方刚落子」的记录一起丢。
     * 结果那个子（带高亮环、被模型读成空）没人补 → 观测比期望只差一格 → 装饰容差枚举出 8 种
     * 结构等价的解释（“那个炮从这一格走到别处、新位置没看见”）→ 不唯一 → 连刷 54 次 → 死等。
     * 反过来，如果永远不作废，一个真的掉了的子会被“补”回棋盘上 —— 所以作废依据必须是
     * 「那个格子上的子确实走了」，而不是“过了几手”。
     */
    function dropPatchesAt(indexes, why) {
        const dropped = driverState.knownFacts.drop(indexes, function (patch) {
            // 那格的子已经走了 ⇒ 补正的「连续帧共识」也一并作废，
            // 否则上一段共识会在下一枚子到达时被直接拿来用
            driverState.frameConsensus.drop(patch.index);
        });
        if (dropped > 0) {
            driverActions.note("已知事实作废 " + dropped + " 格（" + why + "）");
        }
    }

    /**
     * 把「刚落的子」所在的格子登记进已知事实表。
     *
     * 游戏会给刚落的子画高亮环，**下一帧起那一格就认不出来**（读成空或别的兵种）。
     * 我方那一格在确认阶段就登记了，但**对方那一格以前没人登记** —— 真机实测的后果（2026-09-14，K40）：
     * 对方 h7e7 之后 (4,2) 一直读成空 → 每帧都「轮次推不出来（UNKNOWN）」→ 看门狗 → RECOVERING，
     * 而我方那手也白走（整轮停在那儿）。这里按刚推出来的着法登记，等那格自己能认出来就自动失效。
     *
     * @param moves   刚推出来的着法（MOVE 一步 / MOVE_PAIR 两步，落点都算「刚落的子」）
     * @param settled 推完这些着法后的可靠盘面（补的字取自它）
     */
    function registerMoveDestinations(moves, settled) {
        const list = moves || [];
        for (let i = 0; i < list.length; i++) {
            const move = list[i];
            if (!move || !move.iccs || !settled) {
                continue;
            }
            const dest = transition.squareIndex(move.iccs, 1);
            if (dest < 0 || !settled[dest]) {
                continue;
            }
            addPatch({ index: dest, char: settled[dest] }, "刚落子的格子（会被画环）");
        }
    }

    /**
     * 按观测重建基准：丢掉已知事实表，基准 = 本帧盘面。
     * 只该在「恢复之后 + 这一帧够干净」时调（未知格 ≤ cfg.rebuildMaxUnknown）。
     */
    function rebuildBaselineFrom(board) {
        const dropped = driverState.knownFacts.count();
        driverState.knownFacts.clear();
        driverState.frameConsensus.clear();
        // ★ 重建基准用的是**这一帧的读数**，可高亮环/装饰会让模型把子读错（真机 2026-09-20：
        //   我方刚落下的炮被读成黑卒）⇒ 基准被写坏 ⇒ 下一帧起「既有合法单步也解释不了 4 格差异」
        //   ⇒ 又恢复、又重建 —— 循环。判据要的是**直接证据**：
        //   「全盘 OCR 说这格的子与上一张基准**完全一致**（字形兵种 + 像素颜色都一样）」
        //   ⇒ 那格根本没动过，是这一帧读错了 ⇒ 保住基准那一格。
        //   OCR 说不一样（真的换子/被吃）、或拿不到 OCR ⇒ 照旧采纳本帧（默认行为不变）。
        let kept = 0;
        const prev = driverState.lastReliable && driverState.lastReliable.board ? driverState.lastReliable.board : null;
        if (prev && typeof driverState.opts.ocrBoard === "function") {
            let look = null;
            try {
                look = driverState.opts.ocrBoard();
            } catch (error) {
                look = null;
            }
            if (look && look.ok && look.chars) {
                for (let i = 0; i < 90; i++) {
                    if (prev[i] === board[i]) {
                        continue;
                    }
                    const seen = look.chars[i];
                    if (typeof seen === "string" && seen && seen === prev[i]) {
                        board[i] = prev[i];
                        kept += 1;
                    }
                }
            }
        }
        if (kept > 0) {
            driverActions.note(
                "按观测量建基准：全盘 OCR 证据保住 " +
                    kept +
                    " 格" +
                    "（与上一张基准完全一致 ⇒ 是这一帧读错，不是局面变了）"
            );
        }
        // ★ 轮次不能沿用旧值（2026-09-20 真机踩到：整整一局卡在「轮到对方」直到超时）：
        //   重建基准用的那一帧，板面可能**已经包含对方刚应的那一手**，而旧 side 还停在
        //   「我方刚走完 ⇒ 轮到对方」→ 之后一直等一个永不再来的对方走子（差分每帧「变化 0 格」）。
        //   正确做法（与 recover 的注释同一个道理：轮次是**从上一步延续**出来的）：
        //   拿旧基准 + 这一帧推一次着法 —— 推得出（一步 / 一步+应手）就用推出来的下一手，
        //   推不出才沿用旧 side。
        let nextSide = driverState.lastReliable ? driverState.lastReliable.side : "w";
        const previousSide = driverState.lastReliable ? driverState.lastReliable.side : null;
        let recoveryMoves = [];
        // 轮次是「按着法推出来的」还是「沿用旧值猜的」——硬不变量的处置要靠它区分（见下）。
        let sideFromInference = false;
        if (driverState.lastReliable && driverState.lastReliable.board && typeof transition.infer === "function") {
            try {
                const inferred = driverActions.inferMove(
                    driverState.lastReliable.board,
                    driverState.lastReliable.side,
                    board,
                    {
                        allowPair: true,
                        allowSourceGhost: true,
                        // ★ 多步预判线：多解时用引擎预判的对面着法点名（见 predictionIccsFor）
                        preferIccs: driverActions.predictionIccsFor(
                            driverState.lastReliable.board,
                            driverState.lastReliable.side
                        )
                    }
                );
                if ((inferred.status === "MOVE" || inferred.status === "MOVE_PAIR") && inferred.nextSide) {
                    // 推断也会纠正落点字形和起点残影。轮次、可靠基准、账本必须用同一张盘面，
                    // 包括应当变空的格，否则合法两步无法与恢复目标闭合，完整棋谱被降为 partial。
                    if (inferred.board && inferred.board.length === 90) {
                        let corrected = 0;
                        for (let i = 0; i < 90; i++) {
                            if (board[i] !== inferred.board[i]) corrected += 1;
                            board[i] = inferred.board[i];
                        }
                        if (corrected) driverActions.note("恢复盘面按合法着法改正 " + corrected + " 格（含应当清空的起点）");
                    }
                    nextSide = inferred.nextSide;
                    recoveryMoves = inferred.moves || [];
                    sideFromInference = true;
                    if (inferred.viaPrediction) {
                        driverState.stats.predictionTieBreaks += 1;
                    }
                    driverActions.note(
                        "按观测量建基准：轮次按着法推出（" +
                            inferred.reason +
                            "）→ 现在轮到" +
                            (nextSide === "w" ? "红方" : "黑方")
                    );
                }
            } catch (error) {
                nextSide = driverState.lastReliable ? driverState.lastReliable.side : "w";
                sideFromInference = false;
            }
        }
        /**
         * ★★ 硬不变量（2026-09-20 真机 now37，用户报「炮为什么去吃对面的将」）：
         *   重建基准是**直接照抄这一帧的读数**，而象棋规则不许「走完把自己留在被将状态」
         *   ⇒ **刚走完的那一方（= nextSide 的对面）不可能被将军**。
         *   若这张盘显示它正被攻击 ⇒ 这一帧一定读错了（现场：漏认 (4,1) 黑士 ⇒
         *   中路只剩一个炮架 ⇒ 引擎算出「红炮隔一子吃将」合法 ⇒ 连试 12 次全被游戏拒）。
         *   处置：**不采纳这张盘**（保持恢复态、下一帧再读），绝不拿它去算棋。
         */
        if (typeof rulesModule.isInCheck === "function") {
            let moverChecked = false;
            let otherChecked = false;
            try {
                moverChecked = !!rulesModule.isInCheck(board, nextSide !== "w");
                otherChecked = !!rulesModule.isInCheck(board, nextSide === "w");
            } catch (error) {
                moverChecked = false;
            }
            if (moverChecked) {
                driverState.stats.impossibleInCheck = (driverState.stats.impossibleInCheck || 0) + 1;
                /**
                 * ★★ 2026-09-20 真机 now38（这条不变量上线后第一次真机跑，白否 6 帧、多恢复 ~60s）：
                 *   轮次若是**兜底沿用旧值**（不是这一帧按着法推出来的），那这个矛盾恰好能**定轮次** ——
                 *   「被将的一方必须应着」⇒ 它就是该走棋的一方。
                 *   现场：兜底还说「黑方刚走完」，可盘面是红炮 (4,4) 隔着黑炮 (4,2) 在将黑将 (4,0)；
                 *   真正的情况是**红方刚走完、轮到黑方**，兜底只是过时了 ⇒ 白否了 6 帧。
                 *   处置（分两种，见注释上方）：
                 *     · 兜底轮次 + 只有一方被将 ⇒ **改轮次继续用这张盘**（别白烧恢复时间）；
                 *     · 按着法推出来的轮次 + 刚走完的一方被将 ⇒ 仍否掉（那时矛盾指向读数错了）。
                 *   ⚠️ 前提：两边的将/帅都在盘上（`isInCheck` 找不到将也会返回 true）。
                 */
                let bothKingsOnBoard = true;
                try {
                    if (typeof rulesModule.findKing === "function") {
                        bothKingsOnBoard = !!rulesModule.findKing(board, true) && !!rulesModule.findKing(board, false);
                    }
                } catch (error) {
                    bothKingsOnBoard = true;
                }
                if (!sideFromInference && bothKingsOnBoard && !otherChecked) {
                    const flipped = nextSide === "w" ? "b" : "w";
                    driverActions.note(
                        "⚠ 重建基准：兜底轮次（猜" +
                            (nextSide === "w" ? "黑" : "红") +
                            "方刚走完）与盘面冲突" +
                            "（那一方正被将，规则上不可能）⇒ 按「被将方必须应着」改成轮到" +
                            (flipped === "w" ? "红" : "黑") +
                            "方，这张盘继续用"
                    );
                    nextSide = flipped;
                    driverState.stats.sideFromCheck = (driverState.stats.sideFromCheck || 0) + 1;
                } else {
                    driverActions.note(
                        "⚠ 重建基准被否：刚走完的" +
                            (nextSide === "w" ? "黑" : "红") +
                            "方还处在被将状态（规则上不可能）⇒ 这一帧读错了，不采纳（继续恢复，下一帧重读）"
                    );
                    return false;
                }
            }
        }
        driverActions.clearBaselineBorrow(); // 基准重建：旧基准（含借出的那份）已不存在
        driverState.lastReliable = { board: board.slice(), side: nextSide };
        driverActions.trackRebuild(board, nextSide, prev, previousSide, recoveryMoves);
        driverState.baselineCorrectedAt = 0; // 旧票据不会再重建，避免拿旧事实去凑
        // 换了新基准 ⇒ 之前「按旧基准兜住」的粘性一格一律放下（盘面已经不是那张了）
        for (const key in driverState.stallFilledUntil) {
            delete driverState.stallFilledUntil[key];
        }
        // 新基准不能沿用旧基准积攒的“连续两帧”证据。
        driverState.crossFrameGate.reset("rebuild");
        driverState.needFreshBaseline = false;
        driverState.stats.baselineRebuilds += 1;
        driverActions.note(
            "按观测量建基准：丢掉 " + dropped + " 条已知事实，基准 = 本帧盘面" + "（轮次留给下一帧的变化去推）"
        );
        // 返回值 = 这张盘有没有被采纳（tick 靠它决定「本帧不出手」；被否的帧不算基准）
        return true;
    }

    /**
     * 推轮次：从「上一可靠局面 + 新观测」推出现在轮到谁；推不出就返回 null。
     *
     * 副作用：推断成功时会把 lastReliable 刷成**按着法改正过的盘面**；
     * 若这次真的改动了观测（落点格被游戏装饰读错），额外留下 lastSideFix，
     * 交给本轮观察把 lastBoard / 快照一起改正 —— 否则算棋会拿着
     * 「我方炮在某格被读成兵、炮又不在原位」的错盘面出手。
     */
    /**
     * 对方应手「占用唯一解」快路（见 DEFAULTS.replyFastPath 的说明）。
     *
     * ⚠️ `base` 必须是**本帧 observeOnce 之前的基准快照**（由 tick 抓取）：observeOnce 里的补齐/纠错
     * 会就地改 lastReliable（真机 2026-09-21 21:39：对方卒 (3,6)→(3,7)，fills 先撤「起点有卒」的已知事实、
     * 把基准置空，又把落点当幻影连清 3 帧）——用被改过的基准枚举，那一手就不存在了，应手第二次被放跑。
     * 返回 { side } 表示已采信（基准已按「快照 + 这一手」重建、本帧观测已归一）；null = 本帧不适用。
     */
    function tryAdoptReplyByOccupancy(observed, base) {
        if (driverState.cfg.replyFastPath === false) {
            return null;
        }
        if (!observed || !observed.occupancy) {
            return null; // 宿主/链路没带占用图（老宿主）⇒ 不适用
        }
        const occ = observed.occupancy;
        // ── 「棋子动没动」：驱动自己拿相邻帧占用做差（不看面板的变化格）──
        //   为什么自己算（2026-09-21 真机）：面板的 changed 只有「恰好只有 2 格变」才干净，
        //   而对方落子那一帧常夹着装饰闪烁（真机实测 变化 4 格）—— 旧版 `>2 就跳过` 把应手直接放跑。
        //   差出来的格子**整轮累积**（flipEpoch）：只留最近两帧的话，等读数干净时新鲜度已过期。
        let churn = null;
        if (driverState.prevOccupancy) {
            churn = [];
            for (let i = 0; i < 90; i++) {
                const a = driverState.prevOccupancy[i];
                const b = occ[i];
                if (a == null || b == null) {
                    continue; // 看不清的格不参与
                }
                // ★ 2026-09-24：`? → 真子` 也算「翻面」——飞行中的子被读成 '?'（看不清）、
                //   落位后再读到真子，旧的「有 '?' 就跳过」会让落点格**永远进不了 flipEpoch**
                //   （真机：马 (6,4)→(5,2) 的 (5,2) 正是走 '?'→n 那条路）⇒ 快路的硬证据③
                //   “落点必须是本回合翻过面的格” 直接把它挡掉，应手放跑。
                if (a === "?" && b !== "?") {
                    churn.push(i);
                    continue;
                }
                if (a === "?" || b === "?") {
                    continue; // 其余涉及 '?' 的过渡不参与（快路只关心「落点*出现*了走棋方的子」）
                }
                if (a !== b) {
                    churn.push(i);
                }
            }
        } else if (observed.changed && observed.changed.length) {
            churn = observed.changed.slice(); // 开机后第一帧：用面板的变化格兜底
        }
        driverState.prevOccupancy = occ.slice();
        if (churn && churn.length) {
            if (churn.length > 12) {
                driverState.flipEpoch = {}; // 大范围翻面（多半是重锚/几何抖动）⇒ 重开账本，只认这一批
            }
            for (let k = 0; k < churn.length; k++) {
                driverState.flipEpoch[churn[k]] = 1;
            }
            let size = 0;
            for (const key in driverState.flipEpoch) {
                size += 1;
            }
            if (size > 24) {
                driverState.flipEpoch = {}; // 账本太长：旧的装饰翻面清掉，只留这一批
                for (let k = 0; k < churn.length; k++) {
                    driverState.flipEpoch[churn[k]] = 1;
                }
            }
        }
        if (!base || !base.board) {
            driverState.replyFastPending = null;
            return null;
        }
        // 只在「等对方应手」时用：基准轮次必须不是我方；另外我方有活票据（点击/确认在飞）时一律不动
        if (base.side === driverState.machine.mySide()) {
            driverState.replyFastPending = null;
            return null;
        }
        if (typeof driverState.machine.currentTicket === "function" && driverState.machine.currentTicket()) {
            driverState.replyFastPending = null;
            return null;
        }
        const mover = base.side === "w" ? "R" : "B";
        // ① 先看上一帧的候选还立不立得住（佐证 = 「同一手在下一帧仍然成立」）——
        //    用**创建候选时冻结的基准快照**判断：fills 会跨帧把基准改掉（真机 21:48 的 车 f0f1：
        //    「撤事实 + 清幻影 + 收编」跑完，基准里那一手已经不存在了，重新枚举/对比都会丢候选）——
        //    所以佐证不再重新枚举，只验那两格：起点仍空 + 落点仍是走棋方的颜色。
        if (driverState.replyFastPending) {
            const pend = driverState.replyFastPending;
            const pMover = pend.base.side === "w" ? "R" : "B";
            if (occ[pend.from] === "." && occ[pend.to] === pMover) {
                return adoptReply(observed, pend.base, pend.move, pend.iccs, pend.from);
            }
            // 立不住：先别急着丢（读数可能闪一帧）——给它两帧机会；连着几帧都不行才丢
            pend.age = (pend.age || 0) + 1;
            if (pend.age <= 2) {
                return null;
            }
            driverState.replyFastPending = null;
        }
        const list = legal.allLegalMoves(base.board, base.side);
        const hits = [];
        for (let i = 0; i < list.length; i++) {
            const move = list[i];
            const iccs = move && move.iccs;
            if (!iccs) {
                continue;
            }
            const fi = transition.squareIndex(iccs, 0);
            const ti = transition.squareIndex(iccs, 1);
            if (fi < 0 || ti < 0) {
                continue;
            }
            // 硬证据 ①：起点现在读「空」 —— 起点格只住一枚子，它在对方回合变空只可能是刚走这手；
            // 硬证据 ②：落点现在读「走棋方的颜色」；
            // 两条合并后结构上已经唯一（旧版还要 changed⊆{起点,落点}，已被真机证明太严）。
            if (occ[fi] !== "." || occ[ti] !== mover) {
                continue;
            }
            // 硬证据 ③：落点得是**本个对方回合里翻过面的子**（flipEpoch）——
            //   防「陈旧落点」（基准去同步时某格本来就空/本来就有子）造成的假唯一解。
            if (!driverState.flipEpoch[ti]) {
                continue;
            }
            hits.push({ move: move, iccs: iccs, from: fi, to: ti });
        }
        let hit = null;
        if (hits.length === 1) {
            hit = hits[0];
        } else if (hits.length > 1) {
            // ★ 预判裁决（2026-09-25）：多解时用引擎预判的对面着法点名（唯一命中才认）；
            //   点不出来才照旧让位给原路径（宁可慢，不采信猜的）。
            const prefered = transition.pickByPreference(
                hits,
                function (candidate) {
                    return candidate.iccs;
                },
                driverActions.predictionIccsFor(base.board, base.side)
            );
            if (prefered) {
                hit = prefered;
                driverState.stats.predictionTieBreaks += 1;
                driverActions.note(
                    "预判裁决（占用快路）：" +
                        hits.length +
                        " 个候选（" +
                        hits
                            .map(function (candidate) {
                                return candidate.iccs;
                            })
                            .join("/") +
                        "）里按预判认定 " +
                        prefered.iccs
                );
            } else {
                driverState.stats.replyFastSkipped = (driverState.stats.replyFastSkipped || 0) + 1;
                driverState.replyFastPending = null;
                return null;
            }
        }
        if (!hit) {
            driverState.replyFastPending = null;
            return null;
        }
        // 第一次看见：把「基准快照 + 这一手」**一起冻住**当候选（基准后续被 fills 改也不怕），
        // 下一帧佐证（两格仍然成立）后采纳。
        driverState.replyFastPending = { iccs: hit.iccs, from: hit.from, to: hit.to, move: hit.move, base: base };
        driverState.stats.replyFastWaits = (driverState.stats.replyFastWaits || 0) + 1;
        driverActions.note("快路：候选 " + hit.iccs + " 待佐证（下一帧再看一眼就采纳）");
        return null;
    }

    /**
     * 快路采纳收尾：按「冻结快照 + 这一手」重建基准、归一观测（待佐证路与直采路共用）。
     * 返回 { side } 或 null（硬不变量不过时交给原流程）。
     */
    function adoptReply(observed, base, move, iccs, fromIndex) {
        driverState.replyFastPending = null;
        driverState.flipEpoch = {}; // 这一手已定案：账本清空，等对面的下一手重新累积
        const applied = legal.applyMove(base.board, move).board;
        // 硬不变量：刚走完的那一方不可能被将（同 sideFor 的 MOVE 分支）——对不上说明基准有问题，交给原流程去恢复
        if (typeof rulesModule.isInCheck === "function") {
            let suspect = false;
            try {
                suspect = rulesModule.isInCheck(applied, base.side === "w");
            } catch (error) {
                suspect = false;
            }
            if (suspect) {
                driverState.stats.replyFastSkipped = (driverState.stats.replyFastSkipped || 0) + 1;
                return null;
            }
        }
        const nextSide = base.side === "w" ? "b" : "w";
        // 用**快照 + 这一手**重建基准（顺带把 fills 对基准的临时改动纠正回来）
        driverActions.clearBaselineBorrow(); // 快路认出来了：对账结案（若这手是借旧基准对出来的）
        driverState.lastReliable = { board: applied, side: nextSide };
        driverActions.trackConfirm({
            iccs: iccs,
            fromBoard: base.board,
            side: base.side,
            afterBoard: applied,
            source: "opponent-observed"
        });
        driverState.lastSideFix = null; // 观测马上被归一成这份盘面，不需要再“修正”
        dropPatchesAt([fromIndex], "占用唯一解 " + iccs + " 的起点已空");
        registerMoveDestinations([{ iccs: iccs }], applied);
        // 把本帧观测直接归一到「基准 + 这一手」：这正是快路的意义 —— 不等 OCR、不留未知格
        for (let i = 0; i < 90; i++) {
            observed.chars[i] = applied[i];
        }
        observed.unknownCells = 0;
        driverState.lastBoard = observed.chars.slice();
        driverState.stats.replyFastAdopts = (driverState.stats.replyFastAdopts || 0) + 1;
        driverActions.noteOpponentMove(iccs, "占用快路", fenModule.buildFen(base.board, base.side));
        driverState.lastMoves.opponent = { iccs: iccs, at: driverState.nowMono() };
        driverActions.note("对手应手=占用唯一解 " + iccs + "（起点已空 + 落点有子，免 OCR 直接采信）");
        return { side: nextSide };
    }

    return {
        isStartPosition: isStartPosition,
        looksLikeNewOpening: looksLikeNewOpening,
        addPatch: addPatch,
        addEmptyPatch: addEmptyPatch,
        registerPatch: registerPatch,
        dropPatchesAt: dropPatchesAt,
        registerMoveDestinations: registerMoveDestinations,
        rebuildBaselineFrom: rebuildBaselineFrom,
        tryAdoptReplyByOccupancy: tryAdoptReplyByOccupancy,
        adoptReply: adoptReply
    };
}

module.exports = { create: create };
