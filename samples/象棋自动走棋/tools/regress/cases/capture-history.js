// @engine quickjs
"use strict";

/** K40 03:21 h0h9 吃黑车后断谱：原始观测、实际驱动、账本恢复和重复局面检测。 */
module.exports = {
    name: "K40 吃车确认保留前盘面和棋谱，断谱后恢复重复检测",
    tags: ["capture-history", "driver", "replay", "win-seek"],
    run: function (t) {
        const fen = t.load("src/chess/fen.js");
        const legal = t.load("src/chess/legal-moves.js");
        const trackerModule = t.load("src/services/position-tracker.js");
        const historyModule = t.load("src/automation/driver/history.js");
        const factsModule = t.load("src/automation/known-facts.js");
        const winSeek = t.load("src/automation/win-seek.js");
        const before = fen.parseFen("2bak2r1/4a4/1cn1b1n1c/p3p1C1p/2p2r3/P2R2P2/2P1P3P/N3C1N2/4A4/2B1KABR1 w - - 0 1").board;
        const after = legal.applyMove(before, { iccs: "h0h9" }).board;
        const reply = legal.applyMove(after, { iccs: "g7h9" }).board;
        const key = trackerModule.cellsKey;
        const notes = [];
        const noop = function () {};
        const state = {
            cfg: {}, opts: {}, stats: { trackerErrors: 0, trackerRejects: 0, trackerMismatch: 0 },
            lastReliable: { board: before.slice(), side: "w" }, ownMoveCount: 0,
            nowMono: function () { return 10000; },
            machine: { mySide: function () { return "w"; }, state: function () { return "VERIFYING"; },
                versions: function () { return { sessionId: 1 }; } },
            knownFacts: factsModule.createKnownFacts(),
            positionTracker: trackerModule.createPositionTracker()
        };
        state.positionTracker.adopt(state.lastReliable);
        const actions = {
            note: function (text) { notes.push(text); },
            noteWinSeekPosition: noop,
            countUnknownChars: function (b) { return b.filter(function (ch) { return ch === "?"; }).length; },
            resetStuckStreak: noop, clearPhantoms: noop, applyOcrBoardFixes: noop
        };
        Object.assign(actions, historyModule.create(state, actions));
        Object.assign(actions, t.load("src/automation/driver/evidence.js").create(state, actions));
        Object.assign(actions, t.load("src/automation/driver/recognition.js").create(state, actions));
        const observe = t.load("src/automation/driver/observation.js").create(state, actions);
        // 即使执行层漏掉了清理，观察层也不能把被吃的旧黑车补回或删掉前盘面的黑车。
        ["R", "?"].forEach(function (dest) {
            state.knownFacts.replace([{ index: 7, char: "r" }, { index: 88, char: "R" }]);
            const image = after.slice();
            image[7] = dest;
            actions.recognizeMerged = function () {
                return { ok: true, chars: image.slice(), rawChars: image.slice(),
                    unknownCells: dest === "?" ? 1 : 0,
                    glyphStats: { circleEvidence: { active: true, sides: { 7: "RED" } } } };
            };
            const result = observe.observeOnce({ forVerify: true, expect: after,
                ticket: { moveICCS: "h0h9" } });
            t.equal(key(state.lastReliable.board), key(before), "吃子确认不能改动落子前基准");
            t.equal(state.knownFacts.count(), 0, "起点和被吃落点旧记录都应删除");
            t.notEqual(result.chars[7], "r", "未知落点也不能补回被吃的黑车");
            t.notOk(state.baselineCorrectedAt, "正常吃子不是基准修订");
        });
        // 实际驱动：上一手黑车 h8h9 建立旧落点记录，下一手红车 h0h9 吃掉它。
        const initial = before.slice();
        initial[16] = initial[7]; initial[7] = null;
        initial[79] = initial[88]; initial[88] = null;
        const first = legal.applyMove(initial, { iccs: "h1h0" }).board;
        const restored = legal.applyMove(first, { iccs: "h8h9" }).board;
        t.equal(key(restored), key(before), "驱动夹具到达 K40 吃子前盘面");
        let live = initial, searches = 0, taps = 0, now = 10000;
        const grid = { originX: 50, originY: 200, stepX: 100, stepY: 90, version: 1, orientation: "RED_BOTTOM" };
        const driver = t.load("src/automation/auto-driver.js").createDriver({
            allowClick: true, nowMono: function () { return now; }, sleep: noop,
            log: function (text) { notes.push(text); }, locate: function () { return { ok: true, grid: grid }; },
            recognize: function () { return { ok: true, chars: live.slice(), rawChars: live.slice(),
                unknownCells: 0, grid: grid,
                glyphStats: { circleEvidence: { active: true, sides: { 7: live[7] === "R" ? "RED" : "BLACK" } } } }; },
            analyze: function () { return { ok: true, iccs: ++searches === 1 ? "h1h0" : "h0h9",
                score: 0, depth: 20, ms: 10 }; },
            tap: function (x, y, opt) {
                taps++;
                if (opt.kind === "to") live = searches === 1 ? first : after;
                return { ok: true };
            },
            screen: function () { return { width: 1080, height: 2400 }; },
            config: { computeMode: "inline", prethink: false, winSeek: false, bookOwnMoves: 0,
                observeFrames: 1, movetimeMs: 20 }
        });
        const until = function (condition) {
            for (let i = 0; i < 30 && !condition(); i++) { now += 100; driver.tick(); }
            return condition();
        };
        driver.start(); driver.tick(); driver.assertTurn();
        t.ok(until(function () { return driver.ownMoveCount() === 1; }), "第一手红车到 h0 应确认");
        live = before;
        t.ok(until(function () { return driver.lastReliable() && key(driver.lastReliable().board) === key(before); }),
            "黑车 h8h9 应确认");
        t.ok(until(function () { return driver.ownMoveCount() === 2; }), "K40 红车吃黑车应确认");
        t.equal(driver.tracker().ledger.moves.join(" "), "h1h0 h8h9 h0h9", "吃子不能断开已有棋谱");
        live = reply;
        t.ok(until(function () { return driver.lastReliable() && key(driver.lastReliable().board) === key(reply); }),
            "黑马反吃 g7h9 应确认");
        t.equal(driver.tracker().ledger.moves.join(" "), "h1h0 h8h9 h0h9 g7h9", "反吃继续连续入账");
        t.equal(driver.stats().trackerRejects, 0, "正常吃子无需断谱恢复");
        t.equal(taps, 4, "两手只发四次点击");
        driver.stop();

        // An unrelated OCR repair rebuilt the ticket: bookkeeping must use that same base.
        const correctedBase = before.slice(); correctedBase[27] = null;
        const correctedAfter = legal.applyMove(correctedBase, { iccs: "h0h9" }).board;
        state.positionTracker.adopt({ board: correctedBase, side: "w" });
        state.lastReliable = { board: correctedBase, side: "w" };
        state.baselineCorrectedAt = 9999;
        state.verifyCycle = 0; state.decisionEpoch = 0; state.lastMoves = {};
        let event = null;
        state.machine.dispatch = function (value) { event = value; return value; };
        const verifyActions = Object.assign({}, actions, {
            tryFastConfirmRoI: function () { return null; },
            observeOnce: function () { return { ok: true, chars: correctedAfter.slice(), unknownCells: 0 }; },
            applyKnownPatchesTo: function (board) { return board; },
            rebuildTicketBoards: function () { return { baseFen: fen.buildFen(correctedBase, "w"),
                expectedAfterFen: fen.buildFen(correctedAfter, "b") }; },
            dropPatchesAt: noop, addPatch: noop, addEmptyPatch: noop, clearAttemptBudget: noop,
            pump: function (value) { return value; }
        });
        t.load("src/automation/driver/verification.js").create(state, verifyActions).doVerify({ ticket: {
            actionId: "repaired-ticket", moveICCS: "h0h9", baseSide: "w",
            baseFen: fen.buildFen(before, "w"), expectedAfterFen: fen.buildFen(after, "b")
        } });
        t.equal(event.verdict.result, "CONFIRMED", "修订票据后仍按真实观测确认");
        t.equal(key(state.positionTracker.snapshot().position.board), key(correctedAfter), "入账采用修订后的期望盘面");
        t.equal(state.positionTracker.snapshot().ledger.moves.join(" "), "h0h9", "修订后不因原票据而拒收入账");
        t.notOk(state.stats.historyResegments, "一致的修订票据无需额外断谱恢复");

        // 故意模拟旧版本已删掉被吃黑车的坏账本；恢复必须严格重放，标记部分历史。
        const broken = before.slice(); broken[7] = null;
        state.positionTracker.adopt({ board: broken, side: "w" });
        state.lastReliable = { board: after, side: "b" };
        const seen = winSeek.createSeenPositions();
        actions.noteWinSeekPosition = function (board, side) { seen.note(board, side); };
        actions.trackConfirm({ iccs: "h0h9", fromBoard: before, side: "w", afterBoard: after });
        t.equal(state.positionTracker.snapshot().historyStatus, "partial", "不伪造旧棋谱");
        t.equal(state.positionTracker.snapshot().ledger.moves.join(" "), "h0h9", "已验证吃子重开可用历史段");
        state.lastReliable = { board: reply, side: "w" };
        actions.trackInferredMoves([{ iccs: "g7h9" }], after, "b", "opponent");
        t.equal(state.positionTracker.snapshot().ledger.moves.join(" "), "h0h9 g7h9", "恢复后应手继续入账");
        // 重放日志里的双方走马循环，重复检测必须能得到走回旧局面的候选。
        let board = reply, side = "w";
        ["a2b4", "c7b5", "b4a2", "b5c7", "a2b4", "c7b5", "b4a2", "b5c7"].forEach(function (iccs) {
            const next = legal.applyMove(board, { iccs: iccs }).board;
            state.lastReliable = { board: next, side: side === "w" ? "b" : "w" };
            actions.trackConfirm({ iccs: iccs, fromBoard: board, side: side, afterBoard: next });
            board = next; side = state.lastReliable.side;
        });
        t.ok(winSeek.repeatMoves(legal.allLegalMoves(board, side), board, side, seen, { minSeen: 2 })
            .some(function (move) { return move.iccs === "a2b4"; }), "断谱恢复后识别 K40 走马重复候选");
        t.equal(state.stats.historyResegments, 1, "只恢复一次，后续历史连续");
        t.equal(state.stats.trackerErrors, 0, "无账本异常");

        // 非法着法和不匹配后盘面不能触发恢复，更不能推进账本。
        ["h0a9", "h0h9"].forEach(function (iccs) {
            state.positionTracker.adopt({ board: broken, side: "w" });
            const old = JSON.stringify(state.positionTracker.snapshot());
            actions.trackConfirm({ iccs: iccs, fromBoard: before, side: "w", afterBoard: before });
            t.equal(JSON.stringify(state.positionTracker.snapshot()), old, "错误着法或后盘面不改账本");
            t.equal(state.stats.historyResegments, 1, "无效确认不重开历史段");
        });
        t.log("K40 h0h9→g7h9 吃子链和 a2b4↔b4a2 双方循环均已回放");
    }
};
