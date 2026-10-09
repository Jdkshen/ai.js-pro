// @engine quickjs
"use strict";
// Measure trial-book coverage on distinct source games; no phone or game actions.
const fs = require("fs");
const fen = require("../src/chess/fen.js");
const legal = require("../src/chess/legal-moves.js");
const book = require("../src/engine/opening-book.js");
const input = process.argv[2], indexPath = process.argv[3];
if (!input || !indexPath) {
    console.error("Usage: node eval-opening-book.js input.pgn vetted-book.json");
    process.exit(2);
}
const index = book.parseIndex(fs.readFileSync(indexPath, "utf8"));
if (!index) throw new Error("invalid vetted book");
const chunks = fs.readFileSync(input, "utf8").split(/(?=^\[Event\s)/m);
const seen = new Set();
const slots = Array.from({ length: index.maxPlies }, () => ({ positions: 0, hits: 0, same: 0 }));
let games = 0;
for (const chunk of chunks) {
    if (!/^\[Event\s/m.test(chunk)) continue;
    const body = chunk.replace(/^\[[^\r\n]*\]\s*$/gm, "");
    const moves = body.match(/\b[a-i][0-9][a-i][0-9]\b/g) || [];
    const fingerprint = moves.join(" ");
    if (!moves.length || seen.has(fingerprint)) continue;
    seen.add(fingerprint);
    games++;
    let board = fen.parseFen(fen.START_FEN).board;
    let side = "w";
    for (let ply = 0; ply < Math.min(slots.length, moves.length); ply++) {
        const slot = slots[ply];
        slot.positions++;
        const choice = book.probe(index, board, side, []);
        if (choice) {
            slot.hits++;
            if (choice.iccs === moves[ply]) slot.same++;
        }
        const next = legal.applyMove(board, { iccs: moves[ply] }).board;
        if (!next) break;
        board = next;
        side = side === "w" ? "b" : "w";
    }
}
console.log(JSON.stringify({ games, plies: slots.map((slot, index) => ({
    ply: index + 1, positions: slot.positions, hits: slot.hits,
    hitRate: +(slot.hits / (slot.positions || 1)).toFixed(3),
    sameAsGame: slot.same
})) }, null, 2));
