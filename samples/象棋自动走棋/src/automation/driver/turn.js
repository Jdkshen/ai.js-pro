// @engine quickjs
"use strict";

/** 轮次推断与会话重置：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const transition = require("../../chess/transition.js");
const fenModule = require("../../chess/fen.js");
const legal = require("../../chess/legal-moves.js");
const rulesModule = require("../../chess/rules.js");

function create(driverState, driverActions) {
    function sideFor(board, unknownCells) {
        driverState.lastSideFix = null;
        driverState.lastInferHold = false;
        if (unknownCells > 0) {
            return null;
        }
        if (!driverState.lastReliable) {
            // 没有任何历史时唯一的可靠推断：棋盘就是标准开局 → 中国象棋红先
            if (driverActions.isStartPosition(board)) {
                driverState.lastReliable = { board: board.slice(), side: "w" };
                driverActions.resetOwnMoveCount(); // 新的一局刚摆好 ⇒ 我方已走着数从 0 起算（开局库步数闸门）
                driverActions.trackAdopt(board, "w", { reason: "start-position", source: "manual-confirmed" });
                driverActions.note("局面是标准开局 → 按红先处理（轮到红方）");
                return "w";
            }
            return null;
        }
        // ★★ 2026-09-24 对账修订（见 recordBaselineRevision 的注释）：本帧的修订器动过基准，
        //   而那时正在等对方应手 —— 借回「修订前的基准」再算轮次：修订+观测的**累计差**里
        //   就带着对方那一手。认得出 ⇒ 下面的正常流程会按它翻轮次；认不出且超过
        //   cfg.baselineBorrowMs ⇒ 放弃、回到修订后的基准（即老行为）。
        //   ⚠️ 只在**证据像应手**时才借：「观测里走棋方的子少了一枚」（= 应手正在飞的最早证据）。
        //   反过来「凭空多出一枚」（幻影收编的典型形态：now48 的漏收）**维持老行为** ——
        //   那种情况老流程（收编进基准 + 后续恢复）已经能自愈，借旧基准反而会把基准劈成两半。
        if (driverState.pendingBaselineRevision) {
            const rev = driverState.pendingBaselineRevision;
            driverState.pendingBaselineRevision = null;
            const fresh =
                rev &&
                rev.beforeBoard &&
                rev.beforeBoard.length === 90 &&
                driverState.nowMono() - rev.atMono <= driverState.cfg.baselineBorrowMs;
            if (fresh && driverState.baselineBorrow) {
                // 已经在借：把它拉回「这份修订之前」的状态（内容与借出的那份一致），继续等对账
                driverState.lastReliable = { board: rev.beforeBoard.slice(), side: rev.beforeSide };
                driverState.baselineBorrow.base = driverState.lastReliable;
            } else if (
                fresh &&
                rev.beforeSide !== driverState.machine.mySide() &&
                driverActions.vanishedMoverPiece(rev.beforeBoard, board, rev.beforeSide)
            ) {
                const aside = driverState.lastReliable;
                driverState.lastReliable = { board: rev.beforeBoard.slice(), side: rev.beforeSide };
                driverState.baselineBorrow = {
                    base: driverState.lastReliable,
                    aside: aside,
                    atMono: rev.atMono,
                    why: rev.why
                };
                // 影子账本也跟着借回旧基准：不然「resync 改基准 + 借旧基准」一改一借，
                // 影子与诚实基准对不上，后面每一步推断着法都被它拒收（日志噪声 + 引擎丢历史）。
                driverActions.trackAdopt(driverState.lastReliable.board, driverState.lastReliable.side, {
                    reason: "baseline-borrow"
                });
                driverActions.relaxUnknownWatchdog(true); // 对账期间别让 4s 看门狗抢先恢复（结案/回退时恢复原值）
                driverState.stats.baselineBorrows = (driverState.stats.baselineBorrows || 0) + 1;
                driverActions.note(
                    "对账修订：本帧修订（“" +
                        rev.why +
                        "”）改过可靠基准，且观测里" +
                        (rev.beforeSide === "w" ? "红" : "黑") +
                        "方少了一枚子 —— " +
                        "借回修订前的基准重算轮次（修订+观测的累计差里应含对方那一手）"
                );
            }
        } else if (driverState.baselineBorrow) {
            // 借出的旧基准还在用：期间可能被修订器刷新过 lastReliable（那也算还在借）。
            // 超时仍未认出来 ⇒ 放弃对账，回到修订后的基准（即老行为）。
            if (driverState.nowMono() - driverState.baselineBorrow.atMono > driverState.cfg.baselineBorrowMs) {
                const borrowed = driverState.baselineBorrow;
                driverState.baselineBorrow = null;
                driverActions.relaxUnknownWatchdog(false); // 回退：看门狗恢复原值，该恢复就恢复
                if (driverState.lastReliable === borrowed.base) {
                    driverState.lastReliable = borrowed.aside || driverState.lastReliable;
                }
                if (driverState.lastReliable && driverState.lastReliable.board) {
                    driverActions.trackFix(driverState.lastReliable.board, driverState.lastReliable.side, {
                        reason: "borrow-timeout"
                    });
                }
                driverState.stats.baselineBorrowTimeouts = (driverState.stats.baselineBorrowTimeouts || 0) + 1;
                driverActions.note(
                    "对账修订超时（" +
                        Math.round(driverState.nowMono() - borrowed.atMono) +
                        "ms 还没认出" +
                        "对方那一手）→ 放弃旧基准，回到修订后的基准"
                );
            }
        }
        const inferred = driverActions.inferMove(driverState.lastReliable.board, driverState.lastReliable.side, board, {
            allowPair: true,
            // 起点残影（真机 2026-09-20）：只在驱动这边开 —— 这里还有「同一个解释连续两帧一致」
            // 的跨帧佐证（tolerantSupportedByTwoFrames）兜着，单帧分不出残影/幻觉才敢用。
            allowSourceGhost: true,
            // ★ 多步预判线（2026-09-25）：多解时用引擎预判的对面着法点名（点不出唯一就照旧未知）；
            //   结果同样要过下面那道「连续两帧同一解释」的佐证，预判错了也翻不了车。
            preferIccs: driverActions.predictionIccsFor(driverState.lastReliable.board, driverState.lastReliable.side)
        });
        if (inferred.status === "MOVE" || inferred.status === "MOVE_PAIR") {
            const settled = inferred.board ? inferred.board.slice() : board.slice();
            // ★ 跨帧佐证：真实着法在下一帧会**重新推出同一个解释**（因为 `lastReliable` 这时还没动），
            //   而模型伪影造成的假着法下一帧会变样 ⇒ 只等一帧就能把假事实拦在外面。
            if (!driverActions.tolerantSupportedByTwoFrames(driverState.lastReliable.board, inferred.reason)) {
                driverState.lastSideFix = null;
                driverState.lastInferHold = true;
                return null;
            }
            if (inferred.board && legal.diffBoards(settled, board).length > 0) {
                driverState.lastSideFix = { board: settled, how: inferred.reason };
            }
            const prevReliableBoard = driverState.lastReliable.board;
            const prevReliableSide = driverState.lastReliable.side;
            driverActions.clearBaselineBorrow(); // 认出来了：对账结案（若这手是借旧基准对出来的）
            driverState.lastReliable = { board: settled, side: inferred.nextSide };
            driverActions.trackInferredMoves(
                inferred.moves || [],
                prevReliableBoard,
                prevReliableSide,
                inferred.side === driverState.machine.mySide() ? "our-click" : "opponent-observed"
            );
            driverActions.note("轮次推断：" + inferred.reason);
            if (inferred.viaPrediction) {
                driverState.stats.predictionTieBreaks += 1; // 多解按预判点名（见 predictionIccsFor）
            }
            // ★ 轻量预判记账：这批推断里属于对方的着法（单着 MOVE 用基准 FEN 校验基点；
            //   MOVE_PAIR 的第二着就是把 key 换成对方回合，这里不传基点、直接对 ICCS）
            if (driverState.predictedReplies && inferred.moves && inferred.moves.length > 0) {
                const preFen =
                    inferred.moves.length === 1 ? fenModule.buildFen(prevReliableBoard, prevReliableSide) : null;
                for (let p = 0; p < inferred.moves.length; p++) {
                    const mover = p % 2 === 0 ? prevReliableSide : prevReliableSide === "w" ? "b" : "w";
                    if (mover !== driverState.machine.mySide() && inferred.moves[p] && inferred.moves[p].iccs) {
                        driverActions.noteOpponentMove(inferred.moves[p].iccs, "观测推断", preFen);
                        break;
                    }
                }
            }
            // 记录最近着法（画板「我起/我落/敌起/敌落」框用）：按 mover 归到 我方 / 对方
            if (inferred.moves && inferred.moves.length > 0) {
                for (let p2 = 0; p2 < inferred.moves.length; p2++) {
                    const mover2 = p2 % 2 === 0 ? prevReliableSide : prevReliableSide === "w" ? "b" : "w";
                    if (inferred.moves[p2] && inferred.moves[p2].iccs) {
                        if (mover2 === driverState.machine.mySide()) {
                            driverState.lastMoves.own = { iccs: inferred.moves[p2].iccs, at: driverState.nowMono() };
                        } else {
                            driverState.lastMoves.opponent = {
                                iccs: inferred.moves[p2].iccs,
                                at: driverState.nowMono()
                            };
                        }
                    }
                }
            }
            /**
             * ★★ 硬不变量（2026-09-20 真机 now37，用户报「炮为什么去吃对面的将」）：
             *   象棋规则不许「走完把自己留在被将状态」⇒ **刚刚走完的那一方不可能被将军**。
             *   所以若这张盘显示「刚走完的一方（= side）正被攻击」⇒ 它一定是错的
             *   （现场：漏认 (4,1) 黑士 ⇒ 引擎算出「红炮隔一子吃将」合法 ⇒ 连试 12 次全被游戏拒）。
             *   处置：不信它 —— 作废基准、走恢复流程重新全盘读，别拿着错盘去算棋。
             */
            if (typeof rulesModule.isInCheck === "function") {
                let suspect = false;
                try {
                    suspect = rulesModule.isInCheck(settled, inferred.side === "w");
                } catch (error) {
                    suspect = false;
                }
                if (suspect) {
                    driverState.stats.impossibleInCheck = (driverState.stats.impossibleInCheck || 0) + 1;
                    driverActions.note(
                        "⚠ 盘面自相矛盾：刚走完的" +
                            (inferred.side === "w" ? "红" : "黑") +
                            "方还处在被将状态（规则上不可能）→ 判这张盘不可信，重新全盘确认"
                    );
                    driverState.lastReliable = null;
                    driverActions.clearBaselineBorrow();
                    driverActions.trackClear("impossible-in-check");
                    driverState.needFreshBaseline = true;
                    driverState.lastSideFix = null;
                    return null;
                }
            }
            // 刚落的子会被画高亮环 → 把落点登记成已知事实，否则下一帧那一格变「空」→ 推不出轮次 → 恢复。
            // ⚠️ 顺序：先作废**这些着法的起点**（那里已经空了），再登记落点。
            // 反过来的话，一步「走到别处又回来」的棋会把自己的起点记录留下（真机：炮 (2,7)→(2,3)
            // 之后 (2,7) 的记录还在 → 下一帧那一格被补回旧子 → 整盘看起来没动）。
            const sources = [];
            const moves = inferred.moves || [];
            for (let i = 0; i < moves.length; i++) {
                const from = transition.squareIndex(moves[i].iccs, 0);
                if (from >= 0) {
                    sources.push(from);
                }
            }
            driverActions.dropPatchesAt(sources, "刚推出来的着法起点已空");
            driverActions.registerMoveDestinations(inferred.moves, settled);
            return inferred.nextSide;
        }
        // ★ 「被盖住的应手」：对手刚走完，但起点/落点被游戏「上一步」标记盖住，观测层又把那几格
        //   按旧基准填了回去（未知=没变）⇒ 差分看起来「没动」或「多出来一个子」，下面两条路全解释
        //   不了，而且「没动（UNCHANGED）」还是个**假象**——必须赶在 UNCHANGED 提前返回之前查。
        //   （真机 K40 2026-09-21：黑炮 b7b5 之后每帧「顽固未知 1 格→按旧基准填」，盘面永远
        //   没变化 ⇒ 轮次卡在「轮到对方」5 分钟，直到被绝杀。）
        //   思路：对方其实已经走完了一手，用**唯一合法着法**把它认出来——枚举它的全部合法着法m，
        //   apply(基准, m) 与观测的每一处差异都只落在「被盖住」的格上（或只是同字异色噪声）；
        //   有且只有一招说得通 ⇒ 就是这一手。
        if (driverState.coveredFilledIndexes.length > 0 && driverState.lastReliable && driverState.lastReliable.board) {
            const guess = transition.inferSingleThroughCovered(
                driverState.lastReliable.board,
                driverState.lastReliable.side,
                board,
                driverState.coveredFilledIndexes
            );
            if (guess) {
                if (
                    !driverActions.tolerantSupportedByTwoFrames(
                        driverState.lastReliable.board,
                        "盖住的应手 " + guess.iccs
                    )
                ) {
                    driverState.lastInferHold = true;
                    return null;
                }
                driverState.lastSideFix = { board: guess.board.slice(), how: "盖住的应手 " + guess.iccs };
                const prevCoveredBoard = driverState.lastReliable.board;
                const prevCoveredSide = driverState.lastReliable.side;
                driverActions.clearBaselineBorrow(); // 认出来了：对账结案（若这手是借旧基准对出来的）
                driverState.lastReliable = { board: guess.board, side: guess.nextSide };
                driverActions.trackConfirm({
                    iccs: guess.iccs,
                    fromBoard: prevCoveredBoard,
                    side: prevCoveredSide,
                    afterBoard: guess.board,
                    source: "opponent-observed",
                    toleranceIndexes: driverState.coveredFilledIndexes
                });
                driverActions.note(
                    "轮次推断（盖住的应手）：唯一合法着法 " +
                        guess.iccs +
                        "（起点/落点在游戏「上一步」标记下读不出来，已按该着法改正）"
                );
                driverActions.dropPatchesAt([transition.squareIndex(guess.iccs, 0)], "盖住的应手起点已空");
                driverActions.registerMoveDestinations([{ iccs: guess.iccs }], guess.board);
                return guess.nextSide;
            }
        }
        if (inferred.status === "UNCHANGED") {
            // 观测只差几个「颜色读反」的格 → 场面还是基准那张（本手没落子）→
            // 把观测/快照拉回基准，别把带反色的盘面拿去算棋。
            if (inferred.board && legal.diffBoards(inferred.board, board).length > 0) {
                driverState.lastSideFix = { board: inferred.board.slice(), how: inferred.reason };
            }
            return driverState.lastReliable.side;
        }
        // 严格推断失败 → 再试一次**带装饰容差**的推断（放宽唯一合法着法的**落点那一格**）。
        // 真机踩到（K40 + 天天象棋）：对方炮吃我方兵、落在兵位上，那一格被模型读成 'p'（炮认成卒）
        // → 严格推断「既没有合法单步也没有一步+应手能解释」→ 轮次未知 → 8s 看门狗 → 永久 RECOVERING。
        // 收紧点：只允许**一格**不同，而且那格必须正是该着法的落点；其余格子仍要完全一致。
        const tolerant = inferWithDecorationTolerance(
            driverState.lastReliable.board,
            driverState.lastReliable.side,
            board
        );
        if (tolerant) {
            if (
                !driverActions.tolerantSupportedByTwoFrames(
                    driverState.lastReliable.board,
                    "唯一合法单步 " + tolerant.iccs
                )
            ) {
                driverState.lastInferHold = true;
                return null;
            }
            driverState.lastSideFix = { board: tolerant.board, how: "唯一合法单步 " + tolerant.iccs };
            const prevTolerantBoard = driverState.lastReliable.board;
            const prevTolerantSide = driverState.lastReliable.side;
            driverActions.clearBaselineBorrow(); // 认出来了：对账结案（若这手是借旧基准对出来的）
            driverState.lastReliable = { board: tolerant.board, side: tolerant.nextSide };
            driverActions.trackConfirm({
                iccs: tolerant.iccs,
                fromBoard: prevTolerantBoard,
                side: prevTolerantSide,
                afterBoard: tolerant.board,
                source: prevTolerantSide === driverState.machine.mySide() ? "our-click" : "opponent-observed",
                toleranceIndexes: [transition.squareIndex(tolerant.iccs, 1)]
            });
            driverActions.note(
                "轮次推断（装饰容差）：唯一合法单步 " +
                    tolerant.iccs +
                    "（落点格被游戏装饰读错，已按该着法改正" +
                    (tolerant.colorNoise ? "；另有 " + tolerant.colorNoise + " 格只是红黑读反，已按基准纠正" : "") +
                    "）"
            );
            // ★ 这条路上**也要登记落点**（2026-09-20 探针发现）：刚落的子会被画高亮环，
            //   不登记的话下一帧那一格又读错（颜色判反/读成空）→ 又一次「轮次推不出来」。
            //   同一套顺序：先作废起点，再登记落点（理由见上面严格分支的注释）。
            driverActions.dropPatchesAt([transition.squareIndex(tolerant.iccs, 0)], "装饰容差推出的着法起点已空");
            driverActions.registerMoveDestinations([{ iccs: tolerant.iccs }], tolerant.board);
            return tolerant.nextSide;
        }
        driverState.stats.unknownTurn += 1;
        driverActions.note("轮次推不出来（" + inferred.status + "）：" + inferred.reason);
        // 诊断（限频）：把「基准 / 观测 / 容差两步统计」都写出来 —— 只报「推不出来」没法定位
        if (driverState.stats.unknownTurn <= 3 || driverState.stats.unknownTurn % 10 === 0) {
            const baseFen = driverState.lastReliable
                ? safeFen(driverState.lastReliable.board, driverState.lastReliable.side)
                : "（无基准）";
            driverActions.note("诊断基准 " + baseFen);
            if (driverState.lastRawChars) {
                driverActions.note(
                    "诊断原始读数 " +
                        safeFen(
                            driverState.lastRawChars,
                            driverState.lastReliable ? driverState.lastReliable.side : "w"
                        )
                );
            }
            driverActions.note(
                "诊断观测 " +
                    safeFen(board, driverState.lastReliable ? driverState.lastReliable.side : "w") +
                    (inferred.debug
                        ? "｜容差：我方着法 " +
                          inferred.debug.ownMoves +
                          "，试 " +
                          inferred.debug.tried +
                          "，最小差 " +
                          inferred.debug.minDiff +
                          "，命中 " +
                          inferred.debug.fits +
                          "，最优 " +
                          inferred.debug.best
                        : "")
            );
            // 补齐表内容（2026-09-14 加）：「推不出来」有两种完全不同的原因 ——
            // ① 表里根本没有那两格的记录；② 表里有、但这帧没补上（读成了别的字）。
            // 不把表打出来就只能猜（当天为这个来回跑了好几轮真机）。
            const table = [];
            const factsForLog = driverState.knownFacts.list();
            for (let i = 0; i < factsForLog.length; i++) {
                const patch = factsForLog[i];
                if (!patch || patch.index == null) {
                    continue;
                }
                table.push(
                    "(" +
                        (patch.index % 9) +
                        "," +
                        Math.floor(patch.index / 9) +
                        ")" +
                        patch.char +
                        "/本轮读到" +
                        (driverState.lastRawChars ? driverState.lastRawChars[patch.index] || "空" : "？")
                );
            }
            driverActions.note(
                "诊断补齐表 " + (table.length ? table.join(" ") : "（空）") + "｜周期 " + driverState.verifyCycle
            );
        }
        return null;
    }

    /** 诊断用：出 FEN，不抛错。 */
    function safeFen(board, side) {
        try {
            return fenModule.buildFen(board, side === "b" ? "b" : "w");
        } catch (error) {
            return "（FEN 失败：" + (error && error.message ? error.message : error) + "）";
        }
    }

    function resetSession() {
        driverActions.cancelPrethink();
        // 开局学习记录是**可选件**（装配里不一定有；history.js 的 trackerMirror 同样按可选处理）。
        // 2026-09-27：此前无护栏 —— newSession 一律炸「reading 'reset'」（回归/自检两案都挂在这）。
        if (driverState.openingRecord) {
            driverState.openingRecord.reset();
        }
        const id = driverState.machine.versions().sessionId + 1;
        driverState.workerSearch.cancel();
        driverState.pendingCompute = null;
        driverState.pendingPredict = null;
        driverState.machine.setSession(id);
        driverState.lastReliable = null;
        driverState.lastBoard = null;
        driverState.lastRawChars = null;
        driverState.royalDanger = null;
        driverState.lastObsSummary = null;
        driverState.lastSideFix = null;
        driverState.activeOwnMove = null;
        driverState.predictedReplies = null;
        driverActions.clearBaselineBorrow();
        driverActions.trackClear("new-session", true);
        driverState.grid = null;
        driverState.pendingMate = null;
        driverState.planHistory.clear();
        driverState.decisionEpoch = 0;
        driverActions.clearAttemptBudget("new-session");
        driverState.knownFacts.clear();
        driverState.frameConsensus.clear();
        driverState.phantomStreak = {};
        driverState.newOpeningStreak = 0;
        driverState.unknownSideStreak = 0;
        driverState.needFreshBaseline = false;
        driverState.forceFreshReadOnce = true;
        driverState.settledRaw = null;
        driverState.prevOccupancy = null;
        driverState.flipEpoch = {};
        driverState.replyFastPending = null;
        driverState.noMoveStreak = 0;
        driverActions.resetOwnMoveCount(); // 开局库步数：新会话重新数我方着数
        driverState.winSeekSeen.clear(); // 求胜模式：新局重新记局面
        driverState.lastWinSeekKey = null;
        driverState.lastThinkScore = null;
        driverActions.note("新会话 #" + id + "（旧基准、票据和几何已清空）");
        return id;
    }

    /**
     * 带装饰容差的轮次推断：在「唯一合法单步」上允许**落点那一格**与观测不同
     * （游戏给刚落的子加高亮环/击中特效，模型会把兵种读错）。
     * 返回 { board（按该着法改正后的盘面）, nextSide, iccs } 或 null。
     */
    function inferWithDecorationTolerance(base, baseSide, observed) {
        // 逻辑已下沉到 `transition.inferSingleWithDecorationTolerance`（纯函数，可离线自检）：
        // 单步 + 只允许「落点格被装饰读错」以及「没被这步碰过的格子只是红黑读反」。
        // 这里只做形状适配（驱动要用 board/nextSide/iccs，日志要用 colorNoise）。
        const tolerant = transition.inferSingleWithDecorationTolerance(base, baseSide, observed, {
            allowSourceGhost: true
        });
        if (!tolerant) {
            return null;
        }
        return {
            board: tolerant.board,
            nextSide: tolerant.nextSide,
            iccs: tolerant.iccs,
            colorNoise: tolerant.colorNoise
        };
    }

    return {
        sideFor: sideFor,
        safeFen: safeFen,
        resetSession: resetSession,
        inferWithDecorationTolerance: inferWithDecorationTolerance
    };
}

module.exports = { create: create };
