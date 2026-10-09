// @engine quickjs
"use strict";
const assert = require("assert");
const fen = require("../src/chess/fen.js");
const book = require("../src/engine/opening-book.js");
const parsed = fen.parseFen(fen.START_FEN);
const key = fen.layoutOf(parsed.board) + " w";
const fixture = {
    schema: 1, vet: { engine: "test" },
    positions: {
        [key]: {
            move: "h2e2", games: 100,
            candidates: [{ move: "h2e2", scoreType: "cp", score: 25, depth: 14 }]
        }
    }
};

assert.strictEqual(book.parseIndex("broken"), null);
assert.strictEqual(book.probe(fixture, parsed.board, "w", ["h2e2"]), null);
assert.strictEqual(book.probe(fixture, parsed.board, "b", []), null);
assert.strictEqual(book.probe(fixture, parsed.board, "w", []).iccs, "h2e2");
const bad = JSON.parse(JSON.stringify(fixture));
bad.positions[key].move = "e0e9";
assert.strictEqual(book.probe(bad, parsed.board, "w", []), null);

global.files = {
    exists: path => path === book.TRIAL_PATH,
    read: path => path === book.TRIAL_PATH ? JSON.stringify(fixture) : ""
};
let nativeSearches = 0;
global.$pikafish = {
    available: () => true,
    isRunning: () => true,
    start: () => ({ ok: true }),
    analyze: () => {
        nativeSearches++;
        return { ok: true, move: "b2e2", candidates: [
            { move: "b2e2", scoreCp: 10, depth: 12, pv: "b2e2", timeMs: 50 }
        ] };
    }
};
const router = require("../src/engine/engine-router.js");
const first = router.analyze({ board: parsed.board, side: "w" }, {});
assert.strictEqual(first.ok, true);
assert.strictEqual(first.iccs, "h2e2");
assert.strictEqual(first.engine, "开局库(PC-Pikafish)");
assert.strictEqual(nativeSearches, 0);
const excluded = router.analyze({ board: parsed.board, side: "w" }, { excludeIccs: ["h2e2"] });
assert.strictEqual(excluded.ok, true);
assert.strictEqual(excluded.iccs, "b2e2");
assert.strictEqual(nativeSearches, 1);
const disabled = router.analyze({ board: parsed.board, side: "w" }, { openingBook: false });
assert.strictEqual(disabled.iccs, "b2e2");
assert.strictEqual(nativeSearches, 2);
const fixedDepth = router.analyze({ board: parsed.board, side: "w" }, { depth: 18 });
assert.strictEqual(fixedDepth.iccs, "b2e2");
assert.strictEqual(nativeSearches, 3);

// 开局库步数闸门（2026-09-25 用户：「开局用开局库走到第 N 手，之后引擎接手算棋」）
assert.strictEqual(book.withinLimit(null, 9), true);          // 不设限 = 老行为
assert.strictEqual(book.withinLimit(0, 0), false);            // 0 = 不用库
assert.strictEqual(book.withinLimit(3, 2), true);             // 第 3 手仍在内
assert.strictEqual(book.withinLimit(3, 3), false);            // 第 4 手交给引擎
const inLimit = router.analyze({ board: parsed.board, side: "w" }, { bookOwnMoves: 3, ownMovesMade: 2 });
assert.strictEqual(inLimit.engine, "开局库(PC-Pikafish)");
assert.strictEqual(nativeSearches, 3);
const outLimit = router.analyze({ board: parsed.board, side: "w" }, { bookOwnMoves: 3, ownMovesMade: 3 });
assert.strictEqual(outLimit.iccs, "b2e2");                   // 超出 N 手 ⇒ 落到引擎（桩）
assert.strictEqual(nativeSearches, 4);
const bookOff = router.analyze({ board: parsed.board, side: "w" }, { bookOwnMoves: 0, ownMovesMade: 0 });
assert.strictEqual(bookOff.iccs, "b2e2");
assert.strictEqual(nativeSearches, 5);
console.log("opening book: legal hit, excluded/disabled/fixed-depth fallbacks OK; 步数闸门 OK");
