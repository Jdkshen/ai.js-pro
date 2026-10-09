// @engine quickjs
"use strict";
const fen = require("../chess/fen.js");
const legal = require("../chess/legal-moves.js");
const PROFILE = "ttxq-grandmaster";
const LABEL = "天天象棋·特级大师";
const OUTCOMES = ["win", "draw", "loss", "excluded"];
function storage() {
    try { return storages.create("xiangqi-opening-results-v1"); } catch (error) { return null; }
}
function ids(s) { const v = s && s.get("ids", []); return Array.isArray(v) ? v : []; }
function list(s) {
    s = s || storage();
    return ids(s).map(function (id) {
        const game = s.get("game:" + id, null);
        return game ? Object.assign({}, game, {outcome:s.get("result:" + id, null)}) : null;
    }).filter(function (g) {return g != null;});
}
function result(id, outcome, s) {
    s = s || storage();
    if (!s) return {ok:false,reason:"战绩存储不可用"};
    if (OUTCOMES.indexOf(outcome) < 0 || !s.get("game:" + id, null)) return {ok:false,reason:"对局或结果无效"};
    // Result is stored independently: a late observation cannot overwrite the user's confirmation.
    s.put("result:" + id, outcome);
    s.put("revision", Date.now() + ":" + Math.random());
    return {ok:true};
}
function createSession(options) {
    const opts = options || {}, s = opts.storage === undefined ? storage() : opts.storage;
    let game = null, last = "";
    function update(snap, side, config) {
        if (!s || !snap || !snap.position || !snap.ledger) return;
        const moves = snap.ledger.moves || [];
        const signature = JSON.stringify([snap.fen, snap.historyStatus, snap.positionCommand, side]);
        if (signature === last) return;
        last = signature;
        if (game && (game.side !== side || (snap.historyStatus === "complete" && !moves.length && game.moves.length))) game = null;
        if (!game) {
            // Restarting a driver may resume only the identical verified game, never a similar FEN.
            const active = s.get("active", null), old = active && s.get("game:" + active, null);
            if (old && !s.get("result:" + active, null) && old.side === side && old.profile === PROFILE &&
                snap.historyStatus === "complete" && old.historyStatus === "complete" &&
                old.moves.length > 0 && JSON.stringify(old.moves) === JSON.stringify(moves)) game = old;
            else {
                game = {id:Date.now() + "-" + Math.random().toString(36).slice(2,10), profile:PROFILE,
                    side:side, startedAt:Date.now(), moves:[], eligible:snap.historyStatus === "complete",
                    baseFen:snap.ledger.baseFen, config:config || null};
                s.put("ids", ids(s).concat([game.id]));
            }
            s.put("active", game.id);
        }
        // Any unverified rebuild invalidates this game's opening evidence. Retain the record for audit.
        game.eligible = game.eligible && snap.historyStatus === "complete";
        game.historyStatus = snap.historyStatus;
        game.moves = moves.slice(); game.baseFen = snap.ledger.baseFen;
        game.lastFen = snap.fen; game.updatedAt = Date.now();
        s.put("game:" + game.id, game);
        if (s.get("result:" + game.id, null)) s.put("revision", Date.now() + ":" + Math.random());
    }
    return {update:update, reset:function () {
        if (s && game && s.get("active", null) === game.id) s.put("active", null);
        game = null; last = "";
    }};
}
function aggregate(games, profile, side) {
    const out = {};
    games.forEach(function (g) {
        if (!g.eligible || g.profile !== profile || g.side !== side || ["win","draw","loss"].indexOf(g.outcome) < 0) return;
        const parsed = fen.parseFen(g.baseFen);
        if (!parsed || !parsed.ok || g.baseFen.split(" ")[0] !== fen.START_FEN.split(" ")[0]) return;
        let board = parsed.board, turn = parsed.sideToMove;
        const seen = {}, contributions = [];
        for (let i=0; i < Math.min(24, g.moves.length); i++) {
            const iccs = g.moves[i];
            if (!legal.allLegalMoves(board, turn).some(function (m) {return m.iccs === iccs;})) return;
            const key = fen.layoutOf(board) + " " + turn;
            if (turn === side && !seen[key]) {contributions.push([key,iccs]); seen[key] = true;}
            board = legal.applyMove(board, {iccs:iccs}).board; turn = turn === "w" ? "b" : "w";
        }
        contributions.forEach(function (entry) {
            const row = out[entry[0]] || (out[entry[0]] = {});
            const cell = row[entry[1]] || (row[entry[1]] = {games:0, points:0});
            cell.games++; cell.points += g.outcome === "win" ? 1 : g.outcome === "draw" ? 0.5 : 0;
        });
    });
    return out;
}
let cachedRevision = null, cached = {};
function stats(key, side) {
    const s = storage(); if (!s) return null;
    const revision = s.get("revision", "empty");
    if (revision !== cachedRevision) {cachedRevision = revision; cached = {};}
    if (!cached[side]) cached[side] = aggregate(list(s), PROFILE, side);
    return cached[side][key] || null;
}
function choose(pool, data) {
    // Require evidence for every safe candidate. Five games is an entry threshold, not proof of superiority.
    if (!data || pool.length < 2 || pool.some(function (p) {return !data[p.iccs] || data[p.iccs].games < 5;})) return null;
    const ranked = pool.map(function (p) {
        const d = data[p.iccs]; return {iccs:p.iccs, rate:(d.points+2)/(d.games+4)};
    }).sort(function (a,b) {return b.rate-a.rate;});
    return ranked[0].rate - ranked[ranked.length-1].rate >= 0.1 ? ranked[0].iccs : null;
}
module.exports = {PROFILE:PROFILE, LABEL:LABEL, storage:storage, list:list, result:result,
    createSession:createSession, aggregate:aggregate, stats:stats, choose:choose};
