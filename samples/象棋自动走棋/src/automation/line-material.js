// @engine quickjs
"use strict";

// Near equal endings: compare the same 12 plies of legal engine lines before
// choosing a line that keeps pawns/attackers. These are predictions, never facts
// for recognition or the confirmed game history.
const legal = require("../chess/legal-moves.js");
const VALUES = { R: 900, C: 450, N: 400 };
const PLIES = 12;

function material(board, side) {
    const out = { pawns: 0, majors: 0, value: 0, totalMajors: 0, pieces: 0 };
    for (let i = 0; i < board.length; i++) {
        const p = board[i];
        if (!p) continue;
        out.pieces++;
        const type = p.toUpperCase();
        if (VALUES[type]) out.totalMajors++;
        if ((p === type) !== (side === "w")) continue;
        if (type === "P") out.pawns++;
        if (VALUES[type]) { out.majors++; out.value += VALUES[type]; }
    }
    return out;
}

function shouldCompare(board, side, enabled) {
    if (!enabled || !board || board.length !== 90 || (side !== "w" && side !== "b") ||
        board.indexOf("?") >= 0 || board.indexOf("K") < 0 || board.indexOf("k") < 0) return false;
    const m = material(board, side);
    return m.pawns > 0 && m.majors >= 1 && m.majors <= 4 && m.pieces <= 24;
}

function replayLine(board, side, candidate, minPlies, maxPlies) {
    const pv = candidate && typeof candidate.pv === "string" ? candidate.pv.trim().split(/\s+/) : [];
    if (pv.length < minPlies || pv[0] !== candidate.move) return null;
    let next = board, turn = side;
    const seen=Object.create(null);
    seen[next.join(".")+"|"+turn]=true;
    let repeated=false;
    for (let i = 0; i < Math.min(maxPlies,pv.length); i++) {
        const step = pv[i];
        if (!/^[a-i][0-9][a-i][0-9]$/.test(step)) return null;
        // Validate only the piece being moved, including self-check; do not
        // enumerate every piece for each of the 60 predicted half moves.
        const col = step.charCodeAt(0) - 97, row = 9 - Number(step.charAt(1));
        const move = legal.legalMoves(next, col, row, turn).filter(function (m) { return m.iccs === step; })[0];
        if (!move) return null;
        next = legal.applyMove(next, move).board;
        if (next.indexOf("K") < 0 || next.indexOf("k") < 0) return null;
        turn = turn === "w" ? "b" : "w";
        const key=next.join(".")+"|"+turn;
        if(seen[key]) repeated=true;
        seen[key]=true;
    }
    return { material: material(next, side), repeated: repeated };
}

function summarize(board, side, candidate) {
    const line=replayLine(board,side,candidate,PLIES,PLIES);
    return line ? line.material : null;
}

// Native PVs can end at a draw before 12 plies. Use a fully legal repeated
// board+side in the supplied line as an early soft avoid signal. Do not pad a
// short line with assumed moves or treat a missing PV as a nonrepeating line.
function forecastRepeat(board, side, candidates, bestMove, enabled) {
    if(!shouldCompare(board,side,enabled) || !Array.isArray(candidates)) return null;
    const best=candidates.filter(function(c){return c && c.move === bestMove;})[0];
    if(!best || best.mate != null) return null;
    const head=replayLine(board,side,best,4,16);
    if(!head || !head.repeated) return null;
    const avoid=[];
    for(let i=0;i<candidates.length;i++) {
        const c=candidates[i];
        if(!c || !c.move) continue;
        const line=c.mate == null ? replayLine(board,side,c,4,16) : null;
        if(!line || line.repeated) avoid.push(c.move);
    }
    return { avoid: avoid, plies: 16 };
}

function preferCandidate(board, side, candidates, options) {
    const opts = options || {};
    if (!shouldCompare(board, side, opts.enabled) || !Array.isArray(candidates)) return null;
    const best = candidates.filter(function (c) { return c && c.move === opts.bestMove; })[0];
    if (!best || best.mate != null || typeof best.scoreCp !== "number" || !isFinite(best.scoreCp) ||
        typeof best.depth !== "number" || !isFinite(best.depth) || best.depth < 16) return null;
    const flip = side === "b" ? -1 : 1, score = best.scoreCp * flip;
    if (score < (opts.minScore == null ? -25 : opts.minScore) ||
        score > (opts.maxScore == null ? 80 : opts.maxScore)) return null;
    const head = summarize(board, side, best);
    if (!head) return null;
    const excludes = opts.excludes || [];
    const slack = Math.min(20, opts.slack == null ? 20 : Math.max(0, Number(opts.slack) || 0));
    let choice = null;
    for (let i = 0; i < candidates.length; i++) {
        const c = candidates[i];
        if (!c || c.move === best.move || c.mate != null || excludes.indexOf(c.move) >= 0 ||
            typeof c.scoreCp !== "number" || !isFinite(c.scoreCp) ||
            typeof c.depth !== "number" || !isFinite(c.depth) || c.depth < best.depth) continue;
        const gap = score - c.scoreCp * flip;
        if (gap < 0 || gap > slack) continue;
        const m = summarize(board, side, c);
        if (!m || m.pawns < head.pawns || m.majors < head.majors || m.value < head.value ||
            m.totalMajors < head.totalMajors || (m.pawns === head.pawns && m.majors === head.majors)) continue;
        if (!choice || gap < choice.gap) choice = {
            iccs: c.move, index: i, gap: gap, material: m, headMaterial: head, plies: PLIES
        };
    }
    return choice;
}

module.exports = { shouldCompare: shouldCompare, summarize: summarize, preferCandidate: preferCandidate,
    forecastRepeat: forecastRepeat };
