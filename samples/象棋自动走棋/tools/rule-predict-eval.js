// @engine quickjs
// 规则版「预判对面」离线命中率对照实验（2026-10-06）
// 数据源①：设备对局记录 XML（每局 baseFen + moves，用棋规层回放生成「(局面,对面实际走法)」样本）
// 数据源②：面板日志里的引擎预判记录（「预判（对面视角…）」+「预判命中/偏差」行）= 引擎基线
// 用法：node tools/rule-predict-eval.js [results-store.xml 路径] [面板日志1 面板日志2 ...]
"use strict";
const fs = require("fs");
const path = require("path");
const fen = require("../src/chess/fen.js");
const rules = require("../src/chess/rules.js");
const legal = require("../src/chess/legal-moves.js");

const PIECE_VALUE = { R: 900, C: 450, N: 400, P: 100, A: 200, B: 200, K: 100000 };
function value(ch) { return ch ? (PIECE_VALUE[String(ch).toUpperCase()] || 0) : 0; }
function idxOf(col, row) { return rules.idx(col, row); }

/** 落点攻防表（走前盘面，一次扫描）：map[dest] = {red:{count,min}, black:{count,min}}
 *  炮只把「翻过炮架吃子」的落点算攻击（滑到空格的落点不是攻击）。 */
function buildAttackMap(board) {
    const map = new Map();
    for (let i = 0; i < 90; i++) {
        const ch = board[i];
        if (!ch) continue;
        const col = i % 9, row = (i - col) / 9;
        const isCannon = ch.toUpperCase() === "C";
        const side = rules.isRedPiece(ch) ? "red" : "black";
        const moves = rules.pseudoMoves(board, col, row);
        for (let k = 0; k < moves.length; k++) {
            const m = moves[k];
            if (isCannon && !m.captured) continue;
            const dest = idxOf(m.toCol, m.toRow);
            let cell = map.get(dest);
            if (!cell) { cell = { red: { count: 0, min: null }, black: { count: 0, min: null } }; map.set(dest, cell); }
            const v = value(ch);
            cell[side].count += 1;
            if (cell[side].min == null || v < cell[side].min) cell[side].min = v;
        }
    }
    return map;
}

/**
 * 规则版预判：对面（side）视角，按单层启发式排名。
 * ctx: { prevTo: 上一步(我方)落点格, prevCaptured: 上一步是否吃子 }
 */
function predict(board, side, ctx, limit) {
    const sides = side === "w" ? "red" : "black";
    const oppSides = side === "w" ? "black" : "red";
    const moverRed = side === "w";
    const inCheckBefore = rules.isInCheck(board, moverRed);
    const attackMap = buildAttackMap(board);
    const moves = legal.allLegalMoves(board, side);
    const scored = [];
    for (let i = 0; i < moves.length; i++) {
        const m = moves[i];
        const src = idxOf(m.fromCol, m.fromRow);
        const dest = idxOf(m.toCol, m.toRow);
        const moving = board[src];
        const captured = board[dest];
        const movingV = value(moving);
        let score = captured ? value(captured) : 0;

        const cell = attackMap.get(dest);
        const attackers = cell ? cell[oppSides] : null;
        if (attackers && attackers.count > 0) {
            const defenders = cell ? cell[sides] : null;
            const realDef = defenders ? Math.max(0, defenders.count - 1) : 0; // 减去「移动者自己」
            if (realDef === 0) {
                score -= movingV; // 没保护：按白送处理
            } else {
                score += -movingV + (attackers.min || 0); // 被最便宜攻子吃回后还能吃回它
            }
        }
        // 吃回奖励：上一步我方吃子于 dest，对面最常见应手就是吃回
        if (captured && ctx && ctx.prevTo === dest && value(captured) >= 100) {
            score += Math.round(value(captured) * 0.5) + 40;
        }
        // 逃子：本来被攻击的大子走到不再被攻击的格
        const srcCell = attackMap.get(src);
        const srcAtt = srcCell ? srcCell[oppSides] : null;
        if (!captured && srcAtt && srcAtt.count > 0 && !(attackers && attackers.count > 0) && movingV >= 400) {
            score += 25;
        }
        const applied = legal.applyMove(board, { iccs: m.iccs });
        const givesCheck = !!applied.board && rules.isInCheck(applied.board, !moverRed);
        if (givesCheck) score += 50;
        if (!inCheckBefore && (moving === "K" || moving === "k")) score -= 15;
        scored.push({ iccs: m.iccs, score: score, capture: !!captured, check: givesCheck });
    }
    scored.sort(function (a, b) {
        if (b.score !== a.score) return b.score - a.score;
        if (a.capture !== b.capture) return a.capture ? -1 : 1;
        if (a.check !== b.check) return a.check ? -1 : 1;
        return a.iccs < b.iccs ? -1 : a.iccs > b.iccs ? 1 : 0;
    });
    return scored.slice(0, limit || 8).map(function (s) { return s.iccs; });
}

// ---------- ① 对局回放 ----------
function decodeXml(s) {
    return s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");
}
function loadGames(xmlPath) {
    const raw = fs.readFileSync(xmlPath, "utf8");
    const games = [];
    const re = /<string name="game:([^"]+)">([\s\S]*?)<\/string>/g;
    let m;
    while ((m = re.exec(raw)) != null) {
        try { games.push(JSON.parse(decodeXml(m[2]))); } catch (e) { /* 跳过坏记录 */ }
    }
    return games;
}

function main() {
    const xmlPath = process.argv[2] || path.join(process.env.TEMP || "/tmp", "results-store.xml");
    const games = loadGames(xmlPath);
    console.log("[数据] 对局 " + games.length + " 局（" + path.basename(xmlPath) + "）");

    const samples = [];
    let truncated = 0, replayed = 0;
    games.forEach(function (g) {
        const parsed = fen.parseFen(g.baseFen);
        if (!parsed || !parsed.ok) { truncated++; return; }
        let board = parsed.board, side = parsed.sideToMove, i = 0;
        let prevTo = -1;
        for (; i < g.moves.length; i++) {
            const iccs = g.moves[i];
            const move = legal.allLegalMoves(board, side).filter(function (mm) { return mm.iccs === iccs; })[0];
            if (!move) break; // 记录不一致：截断此局
            const destIdx = idxOf(move.toCol, move.toRow);
            if (side !== g.side) {
                samples.push({ board: board.slice(), side: side, actual: iccs, prevTo: prevTo });
            }
            board = legal.applyMove(board, { iccs: iccs }).board;
            prevTo = destIdx;
            side = side === "w" ? "b" : "w";
            replayed++;
        }
        if (i < g.moves.length) truncated++;
    });
    console.log("[数据] 回放 " + replayed + " 手｜对面样本 " + samples.length + " 个｜截断 " + truncated + " 局");

    // ---------- 评估 ----------
    function pieceCount(board) { let n = 0; for (let i = 0; i < 90; i++) if (board[i]) n++; return n; }
    function phaseOf(board) { const n = pieceCount(board); return n >= 24 ? "开局" : n >= 12 ? "中局" : "残局"; }
    function buckets() { return { n: 0, t1: 0, t3: 0, t5: 0 }; }
    function add(b, rank) { b.n++; if (rank === 0) b.t1++; if (rank >= 0 && rank < 3) b.t3++; if (rank >= 0 && rank < 5) b.t5++; }
    const all = buckets(), stages = { "开局": buckets(), "中局": buckets(), "残局": buckets() },
        quiet = buckets(), tactical = buckets(), inCheck = buckets(), recapture = buckets();

    samples.forEach(function (s) {
        const ranked = predict(s.board, s.side, { prevTo: s.prevTo }, 8);
        const rank = ranked.indexOf(s.actual);
        add(all, rank);
        add(stages[phaseOf(s.board)], rank);
        const moves = legal.allLegalMoves(s.board, s.side);
        const captureAvail = moves.some(function (m) { return !!s.board[idxOf(m.toCol, m.toRow)]; });
        const checkNow = rules.isInCheck(s.board, s.side === "w");
        const recaptureAvail = s.prevTo >= 0 && moves.some(function (m) {
            return idxOf(m.toCol, m.toRow) === s.prevTo && !!s.board[idxOf(m.toCol, m.toRow)];
        });
        add(checkNow ? inCheck : (captureAvail ? tactical : quiet), rank);
        if (recaptureAvail) add(recapture, rank);
    });

    function pct(x) { return (x * 100).toFixed(1) + "%"; }
    function fmt(b) { return b.n ? (b.n + " 例｜top1 " + pct(b.t1 / b.n) + "｜top3 " + pct(b.t3 / b.n) + "｜top5 " + pct(b.t5 / b.n)) : "0 例"; }
    console.log("");
    console.log("═══ 规则版（纯棋规层 + 单层启发式）═══");
    console.log("  全部   : " + fmt(all));
    console.log("  开局   : " + fmt(stages["开局"]));
    console.log("  中局   : " + fmt(stages["中局"]));
    console.log("  残局   : " + fmt(stages["残局"]));
    console.log("  平静   : " + fmt(quiet));
    console.log("  有吃子 : " + fmt(tactical));
    console.log("  被将军 : " + fmt(inCheck));
    console.log("  可吃回 : " + fmt(recapture));

    // ---------- ② 引擎基线（面板日志） ----------
    const logArgs = process.argv.slice(3);
    const logs = (logArgs.length ? logArgs : [
        path.join(process.env.TEMP || "/tmp", "panel-log9.txt"),
        path.join(process.env.TEMP || "/tmp", "panel-log15.txt")
    ]).filter(function (f) { return fs.existsSync(f); });
    const eng = { n: 0, t1: 0, t5: 0 };
    logs.forEach(function (file) {
        const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
        let pending = null;
        lines.forEach(function (line) {
            const m = line.match(/预判（对面视角[^）]*）：(.+?)（头名分/);
            if (m) { pending = m[1].split("/").map(function (x) { return x.trim(); }).filter(Boolean); return; }
            if (!pending) return;
            const hit = line.match(/预判命中[^）]*）：[a-i][0-9][a-i][0-9]（第 (\d+)\/\d+ 路）/);
            if (hit) { const rank = Number(hit[1]) - 1; eng.n++; if (rank === 0) eng.t1++; if (rank < 5) eng.t5++; pending = null; return; }
            const miss = line.match(/预判偏差[^）]*）：预判 [^，]+，实际 ([a-i][0-9][a-i][0-9])/);
            if (miss) { eng.n++; if (pending.indexOf(miss[1]) >= 0) eng.t5++; pending = null; }
        });
    });
    console.log("");
    console.log("═══ 引擎版基线（面板日志：对面视角 900ms×5）═══");
    console.log(eng.n ? ("  全部   : " + eng.n + " 例｜top1 " + pct(eng.t1 / eng.n) + "｜top5 " + pct(eng.t5 / eng.n)) : "  没解析到样本");
}

main();
