// @engine quickjs
"use strict";

/** 局面账本与跨帧佐证：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const transition = require("../../chess/transition.js");
const fenModule = require("../../chess/fen.js");
const positionTrackerModule = require("../../services/position-tracker.js");

function create(driverState, driverActions) {
    function countOwnMove(side) {
        if (side === driverState.machine.mySide()) {
            driverState.ownMoveCount += 1;
        }
    }

    function resetOwnMoveCount() {
        driverState.ownMoveCount = 0;
    }

    /**
     * 「多步预判线」取用（识别裁决专用）：当前基准（board+side）正好是预判线的基点时，
     * 返回**用于裁决本回合分歧的预判着法表**（对方下一手：top-K 排名 + 主变第一手），按可信度排序；
     * 基点不符/没有预判 → null。
     *
     * ⚠️ 只包括「能解释这一个回合」的着法（list + enemy[0]）—— enemy[1..] 是更后面的回合，
     * 拿去裁决当前分歧会张冠李戴。
     */
    function predictionIccsFor(board, side) {
        if (!driverState.predictedReplies || !board) {
            return null;
        }
        let fen = null;
        try {
            fen = fenModule.buildFen(board, side);
        } catch (error) {
            return null;
        }
        if (fen !== driverState.predictedReplies.key) {
            return null;
        }
        const out = [];
        const push = function (value) {
            const iccs = value == null ? "" : String(value);
            if (iccs && out.indexOf(iccs) < 0) {
                out.push(iccs);
            }
        };
        const list = Array.isArray(driverState.predictedReplies.list) ? driverState.predictedReplies.list : [];
        for (let i = 0; i < list.length; i++) {
            push(list[i]);
        }
        const enemy = Array.isArray(driverState.predictedReplies.enemy) ? driverState.predictedReplies.enemy : [];
        if (enemy.length) {
            push(enemy[0]);
        }
        return out.length ? out : null;
    }

    function boardOfFen(fen) {
        try {
            const parsed = fenModule.parseFen(fen);
            return parsed && parsed.ok ? parsed.board : null;
        } catch (error) {
            return null;
        }
    }

    /** 影子守护：tracker 快照与 `lastReliable` 必须一致；不一致只计数 + 前几次打日志。 */
    function trackerMirror(detail) {
        try {
            if (driverState.openingRecord && driverState.cfg.observeOnly !== true)
                driverState.openingRecord.update(driverState.positionTracker.snapshot(), driverState.machine.mySide(),
                    {pikafish:driverState.cfg.pikafish, movetimeMs:driverState.cfg.movetimeMs});
        } catch (error) { driverActions.note("战绩快照保存失败：" + error); }
        const snap = driverState.positionTracker.snapshot();
        const cur = snap.position;
        const same =
            (!cur && !driverState.lastReliable) ||
            (cur &&
                driverState.lastReliable &&
                cur.side === driverState.lastReliable.side &&
                positionTrackerModule.cellsKey(cur.board) ===
                    positionTrackerModule.cellsKey(driverState.lastReliable.board));
        if (same) {
            return;
        }
        driverState.stats.trackerMismatch += 1;
        if (driverState.stats.trackerMismatch <= 5) {
            driverActions.note(
                "⚠ 账本影子与可靠基准不一致（" +
                    (detail || "") +
                    "）：tracker=" +
                    (cur ? cur.side + "/" + cur.board.join("") : "null") +
                    "｜基准=" +
                    (driverState.lastReliable
                        ? driverState.lastReliable.side + "/" + driverState.lastReliable.board.join("")
                        : "null")
            );
        }
    }

    /** 只有账本局面与本次待算局面逐格一致，才可把已确认着法交给引擎。 */
    function verifiedHistoryFor(effect) {
        const snap = driverState.positionTracker.snapshot();
        const current = snap.position;
        if (
            !current ||
            !snap.positionCommand ||
            !effect.board ||
            current.side !== effect.side ||
            positionTrackerModule.cellsKey(current.board) !== positionTrackerModule.cellsKey(effect.board)
        ) {
            return { positionCommand: null, historyStatus: "unknown" };
        }
        return { positionCommand: snap.positionCommand, historyStatus: snap.historyStatus };
    }

    /** 影子：采纳新基准（无着法语义的场合：重建/声明/开局）。 */
    function trackAdopt(board, side, meta) {
        if (driverState.winSeekShuttles) driverState.winSeekShuttles.clear();
        try {
            driverState.positionTracker.adopt(Object.assign({ board: board, side: side }, meta || {}));
        } catch (error) {
            driverState.stats.trackerErrors += 1;
        }
        trackerMirror((meta && meta.reason) || "adopt");
    }

    function trackRebuild(board, side, fromBoard, fromSide, moves) {
        try {
            const result = driverState.positionTracker.reconcile({ board: board, side: side,
                fromBoard: fromBoard, fromSide: fromSide, moves: moves, reason: "rebuild" });
            if (result.preserved) {
                // 恢复补入的链尚未逐手收录；保守清空往返段，完整棋谱仍保留。
                if (result.appended.length && driverState.winSeekShuttles) driverState.winSeekShuttles.clear();
                driverState.stats.historyPreservedOnRecovery = (driverState.stats.historyPreservedOnRecovery || 0) + 1;
                result.appended.forEach(function (item) {
                    countOwnMove(item.side);
                    driverActions.noteWinSeekPosition(item.board, item.nextSide);
                });
                driverActions.note("恢复对账：局面与已确认棋谱一致，保留" +
                    (result.historyStatus === "complete" ? "完整" : "已有部分") +
                    "历史" + (result.appended.length ? "（补入 " + result.appended.length + " 手合法着法）" : "（未重复记账）"));
            } else if (driverState.winSeekShuttles) driverState.winSeekShuttles.clear();
        } catch (error) {
            if (driverState.winSeekShuttles) driverState.winSeekShuttles.clear();
            driverState.stats.trackerErrors += 1;
            // A failed reconciliation must not leave a stale command eligible for search.
            driverState.positionTracker.adopt({ board: board, side: side, reason: "rebuild reconciliation failed" });
        }
        trackerMirror("rebuild");
    }

    /** 已验证着法遇到断谱时，先独立重放验证，再从其前盘面重开部分历史段。 */
    function confirmTrackedMove(entry) {
        const e = entry || {};
        const result = driverState.positionTracker.confirmMove(e);
        if (!result || result.ok || driverState.positionTracker.stats().lastReason !== "reject:discontinuous" ||
            !e.fromBoard || e.fromBoard.length !== 90 || e.fromBoard.indexOf("?") >= 0 ||
            (e.side !== "w" && e.side !== "b")) {
            return result;
        }
        // 严格预检棋规、走方和 afterBoard；不因调用方说“已确认”就把非法变化收入账本。
        const probe = positionTrackerModule.createPositionTracker();
        probe.adopt({ board: e.fromBoard, side: e.side });
        const checked = probe.confirmMove(e);
        if (!checked || !checked.ok || !checked.appliedBoard) {
            return result;
        }
        // fix 总是开启 partial 段，连回到标准初始盘面也不伪称拥有此前完整棋谱。
        driverState.positionTracker.fix({ board: e.fromBoard, side: e.side, reason: "已验证着法断谱恢复" });
        if (driverState.winSeekShuttles) driverState.winSeekShuttles.clear();
        const recovered = driverState.positionTracker.confirmMove(e);
        if (recovered && recovered.ok) {
            driverState.stats.historyResegments = (driverState.stats.historyResegments || 0) + 1;
            driverActions.note("账本恢复：从已验证的落子前盘面重开部分历史，记入 " + e.iccs +
                "；缺失的旧着法不补写，后续继续记录重复局面");
        }
        return recovered;
    }

    /** 收一手已验证着法；只有通过账本检查才更新走子数和重复局面记录。 */
    function trackConfirm(entry) {
        try {
            const result = confirmTrackedMove(entry);
            if (result && result.ok === true && result.appliedBoard) {
                if (!result.duplicate) {
                    countOwnMove(entry && entry.side);
                    if (driverState.winSeekShuttles) driverState.winSeekShuttles.note(
                        entry.fromBoard, entry.side, entry.iccs, driverState.machine.mySide());
                }
                // 求胜模式：把「这一手之后的局面」记进出现次数账本（见 winSeekSeen）
                driverActions.noteWinSeekPosition(result.appliedBoard, result.nextSide);
            }
            if (result && result.ok === false) {
                driverState.stats.trackerRejects += 1;
                if (driverState.stats.trackerRejects <= 5) {
                    driverActions.note("账本拒收着法：" + result.reason);
                }
            }
        } catch (error) {
            driverState.stats.trackerErrors += 1;
        }
        trackerMirror((entry && entry.iccs) || "confirm");
    }

    /** 影子：基准按观测原地改正（无着法）。 */
    function trackFix(board, side, meta) {
        if (driverState.winSeekShuttles) driverState.winSeekShuttles.clear();
        try {
            driverState.positionTracker.fix(Object.assign({ board: board, side: side }, meta || {}));
        } catch (error) {
            driverState.stats.trackerErrors += 1;
        }
        trackerMirror((meta && meta.reason) || "fix");
    }

    /** 影子：把一串「刚被采纳的推断着法」按顺序记账（before 一路链推）。 */
    function trackInferredMoves(moves, beforeBoard, beforeSide, source) {
        let board = beforeBoard;
        let side = beforeSide;
        for (let i = 0; i < (moves || []).length; i++) {
            const item = moves[i] || {};
            let result = null;
            try {
                result = confirmTrackedMove({
                    iccs: item.iccs,
                    fromBoard: board,
                    side: side,
                    afterBoard: null,
                    source: source
                });
            } catch (error) {
                result = null;
            }
            if (!result || result.ok !== true) {
                driverState.stats.trackerRejects += 1;
                if (driverState.stats.trackerRejects <= 5) {
                    driverActions.note(
                        "账本拒收推断着法（" + (item.iccs || "?") + "）：" + (result ? result.reason : "异常")
                    );
                }
                trackerMirror("inferred");
                return;
            }
            if (!result.duplicate) {
                countOwnMove(side);
                if (driverState.winSeekShuttles) driverState.winSeekShuttles.note(
                    board, side, item.iccs, driverState.machine.mySide());
            }
            board = result.appliedBoard;
            side = result.nextSide;
            // 求胜模式：推断链上每一手之后的局面都记一次（见 winSeekSeen）
            driverActions.noteWinSeekPosition(result.appliedBoard, result.nextSide);
        }
        trackerMirror("inferred×" + (moves || []).length);
    }

    /** 影子：作废基准（与 lastReliable = null 同步）。 */
    function trackClear(reason, resetLedger) {
        if (driverState.winSeekShuttles) driverState.winSeekShuttles.clear();
        try {
            driverState.positionTracker.invalidate(reason, { resetLedger: resetLedger === true });
        } catch (error) {
            driverState.stats.trackerErrors += 1;
        }
        trackerMirror(reason);
    }

    /** 修订器在**动基准之前**调用：登记「修订前的基准」（同一轮观测里多次修订只留最早那份）。 */
    function recordBaselineRevision(why) {
        if (!driverState.lastReliable || !driverState.lastReliable.board || !driverState.lastReliable.side) {
            return;
        }
        if (driverState.pendingBaselineRevision) {
            return; // 同一轮里的多次修订合并成「最早那份」：它才同时含起点的空与落点的子
        }
        driverState.pendingBaselineRevision = {
            beforeBoard: driverState.lastReliable.board.slice(),
            beforeSide: driverState.lastReliable.side,
            why: why || "修订",
            atMono: driverState.nowMono()
        };
    }

    /** 作废对账账本（基准重建 / 换边 / 新会话等场合：旧基准已经不存在了）。 */
    function clearBaselineBorrow() {
        driverState.pendingBaselineRevision = null;
        driverState.baselineBorrow = null;
        relaxUnknownWatchdog(false);
    }

    /**
     * 对账进行中暂时放宽「轮次未知看门狗」（4s）：在借基准对账时侧本来就是 null，
     * 而看门狗一冒头就 RECOVERING，会抢在对账（2~3 帧）前面把恢复带起（真机 03:29 实况）。
     * 放宽上限 = 借基准时限 + 3s（超过时限 borrow 会自己回退，看门狗随后照常生效）——有界的，不会掩盖真卡死。
     */
    function relaxUnknownWatchdog(on) {
        const mc = driverState.machine && driverState.machine.config ? driverState.machine.config : null;
        if (!mc) {
            return;
        }
        if (on) {
            if (driverState.borrowWatchdogSaved == null) {
                if (typeof mc.unknownTurnWatchdogMs !== "number") {
                    return; // 自定义机器没有这个配置键：别去动它（免得恢复时写出错值）
                }
                driverState.borrowWatchdogSaved = mc.unknownTurnWatchdogMs;
                mc.unknownTurnWatchdogMs = driverState.cfg.baselineBorrowMs + 3000;
            }
        } else if (driverState.borrowWatchdogSaved != null) {
            mc.unknownTurnWatchdogMs = driverState.borrowWatchdogSaved;
            driverState.borrowWatchdogSaved = null;
        }
    }

    /**
     * 「观测里走棋方的子少了一枚」（它站的格不再有那枚子）—— 等对方时应手正在发生的**最早证据**：
     * 起点先空、落点后到（飞行中的子还可能被读成 '?'/整格空）。对账修订靠它触发（见 sideFor）。
     * 反向形态（凭空多子）不算：那是幻影收编/漏收的地盘，老流程自己会自愈。
     */
    function vanishedMoverPiece(beforeBoard, nowBoard, moverSide) {
        for (let i = 0; i < 90; i++) {
            const a = beforeBoard[i];
            if (!a || a === "?") {
                continue;
            }
            const isMover = moverSide === "w" ? a === a.toUpperCase() : a === a.toLowerCase();
            if (!isMover) {
                continue;
            }
            const b = nowBoard[i];
            if (b === a) {
                continue; // 还在原位
            }
            return true; // 这枚子从原位消失了（真走了 / 装饰读错 —— 交给对账去分辨）
        }
        return false;
    }

    /**
     * 对方应手也要跨帧佐证：同一个应手要**连续两次验证**都枚举出来才采信。
     *
     * 为什么要它（2026-09-19，K40 假事实事故）：模型偶尔会把同一枚子读成**两个位置**
     * （重复检测框伪影）—— 实测黑炮在 (7,2)，某几帧却被读成 (4,2)，于是应手枚举「确认」了
     * 一个**不存在的应手 h7e7**。而验证阶段会把应手**直接写进 `lastReliable` 和补齐表** ⇒
     * 之后每帧都拿假事实去补（(4,2) 被钉成未知格）⇒ `sideFor` 永远数不齐子力 ⇒
     * 「轮次未知」空转、验证变「有多种应手解释，不唯一」，重定位也治不好（截图为证：(4,2) 是空的）。
     * 要求两次验证都枚举出同一个应手，单独一帧的伪影就过不了关。
     */
    function replyCorroborated(key) {
        const result = driverState.crossFrameGate.replyCorroborated(key);
        if (result.wait) {
            driverState.stats.replyHolds += 1;
        }
        return result.ok;
    }

    function tolerantSupportedByTwoFrames(baseBoard, reason) {
        // 采样单位是**观测次数**（见 cross-frame-gate.js：自检是瞬时循环，墙钟等不到第二帧）
        const result = driverState.crossFrameGate.supportedByTwoFrames(baseBoard, reason);
        if (result.wait) {
            driverState.stats.corroborateWaits += 1;
        }
        return result.ok;
    }

    /**
     * 轮次推断：**先「差分追踪」（用户 2026-09-20 提的算法），给不出结论再跑枚举式 infer**。
     *
     * 差分追踪 = 「起点空了 + 终点多了个东西 ⇒ 就是这一枚子走的」+ 兵种规则校验（马腿/象眼/炮架/
     * 九宫/过河/自将）⇒ O(路程) 的检查，而且**不依赖落点格读成了什么**（高亮环把落点读成敌色也认得出）。
     * 枚举式 infer 是「几十手 × 每手整盘比对」，解释力最强但落点被画花时会全军覆没。
     * 两者不冲突：追踪只在「差分恰好是两格、且兵种规则唯一合法」时才给结论，其余一律回退。
     */
    function inferMove(beforeBoard, beforeSide, board, options) {
        let tracked = null;
        if (typeof transition.inferTracked === "function") {
            try {
                tracked = transition.inferTracked(beforeBoard, beforeSide, board, options);
            } catch (error) {
                tracked = null; // 追踪层不许把整轮搞崩（它只是个快路，坏了就回退）
            }
        }
        if (tracked) {
            driverState.stats.trackedMoves = (driverState.stats.trackedMoves || 0) + 1;
            return tracked;
        }
        driverState.stats.trackedMisses = (driverState.stats.trackedMisses || 0) + 1;
        return transition.infer(beforeBoard, beforeSide, board, options);
    }

    function useWorkerSearch() {
        return driverState.computeMode === "worker";
    }

    return {
        countOwnMove: countOwnMove,
        resetOwnMoveCount: resetOwnMoveCount,
        predictionIccsFor: predictionIccsFor,
        boardOfFen: boardOfFen,
        trackerMirror: trackerMirror,
        verifiedHistoryFor: verifiedHistoryFor,
        trackAdopt: trackAdopt,
        trackRebuild: trackRebuild,
        trackConfirm: trackConfirm,
        trackFix: trackFix,
        trackInferredMoves: trackInferredMoves,
        trackClear: trackClear,
        recordBaselineRevision: recordBaselineRevision,
        clearBaselineBorrow: clearBaselineBorrow,
        relaxUnknownWatchdog: relaxUnknownWatchdog,
        vanishedMoverPiece: vanishedMoverPiece,
        replyCorroborated: replyCorroborated,
        tolerantSupportedByTwoFrames: tolerantSupportedByTwoFrames,
        inferMove: inferMove,
        useWorkerSearch: useWorkerSearch
    };
}

module.exports = { create: create };
