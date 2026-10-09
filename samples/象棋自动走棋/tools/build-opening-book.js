// @engine quickjs
"use strict";

// Build a compact, position-keyed Xiangqi opening index from ICCS PGN games.
// Input is a *plain* PGN file (extract ZIP first); raw games stay outside git.
const fs = require("fs");
const path = require("path");
const fen = require("../src/chess/fen.js");
const rules = require("../src/chess/rules.js");
const legal = require("../src/chess/legal-moves.js");

const input = process.argv[2];
const output = process.argv[3];
const maxPlies = Number(process.argv[4] || 12);
const minGames = Number(process.argv[5] || 8);
if (!input || !output || !Number.isInteger(maxPlies) || maxPlies < 1 || maxPlies > 30 ||
    !Number.isInteger(minGames) || minGames < 1) {
    console.error("Usage: node build-opening-book.js input.pgn output.json [maxPlies=12] [minGames=8]");
    process.exit(2);
}

const start = fen.parseFen(fen.START_FEN).board;
const positions = Object.create(null);
const seenGames = new Set();
const stats = { sourceGames: 0, uniqueGames: 0, acceptedGames: 0, invalidGames: 0, positions: 0 };
const pgn = fs.readFileSync(input, "utf8");
const chunks = pgn.split(/(?=^\[Event\s)/m);

for (const chunk of chunks) {
    if (!/^\[Event\s/m.test(chunk)) continue;
    stats.sourceGames++;
    const body = chunk.replace(/^\[[^\r\n]*\]\s*$/gm, "");
    const allMoves = body.match(/\b[a-i][0-9][a-i][0-9]\b/g) || [];
    if (!allMoves.length) {
        stats.invalidGames++;
        continue;
    }
    const fingerprint = allMoves.join(" ");
    if (seenGames.has(fingerprint)) continue;
    seenGames.add(fingerprint);
    stats.uniqueGames++;

    let board = start.slice();
    let side = "w";
    const visits = [];
    let valid = true;
    for (let ply = 0; ply < Math.min(maxPlies, allMoves.length); ply++) {
        const move = allMoves[ply];
        const from = rules.iccsSquare(move, 0);
        const to = rules.iccsSquare(move, 1);
        const fromCol = from % 9, fromRow = Math.floor(from / 9);
        const toCol = to % 9, toRow = Math.floor(to / 9);
        if (from == null || to == null ||
            !legal.isLegalMove(board, fromCol, fromRow, toCol, toRow, side).legal) {
            valid = false;
            break;
        }
        const key = fen.layoutOf(board) + " " + side;
        visits.push({ key: key, move: move });
        board = legal.applyMove(board, { iccs: move }).board;
        side = side === "w" ? "b" : "w";
    }
    if (!valid) {
        stats.invalidGames++;
        continue;
    }
    stats.acceptedGames++;
    for (const visit of visits) {
        const row = positions[visit.key] || (positions[visit.key] = Object.create(null));
        row[visit.move] = (row[visit.move] || 0) + 1;
    }
}

const compact = Object.create(null);
for (const key of Object.keys(positions)) {
    const moves = Object.entries(positions[key]).sort((a, b) => b[1] - a[1]);
    const total = moves.reduce((sum, item) => sum + item[1], 0);
    if (total < minGames || moves[0][1] < minGames) continue;
    compact[key] = moves.slice(0, 5);
    stats.positions++;
}

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify({
    schema: 1,
    source: "Wukong xqdb masters ICCS PGN; counts only; no result metadata",
    maxPlies: maxPlies,
    minGames: minGames,
    stats: stats,
    positions: compact
}));
console.log(JSON.stringify(stats));
console.log("wrote " + output + " (" + fs.statSync(output).size + " bytes)");
