// @engine quickjs
"use strict";
const fen = require("../chess/fen.js");
const legal = require("../chess/legal-moves.js");

function identity(board, side, command, session, config) {
    return JSON.stringify([fen.buildFen(board, side), command, session, config || null]);
}
function plan(snapshot, reply, mySide, session, config, movetime) {
    if (!snapshot || !snapshot.position || !snapshot.positionCommand || !reply) return null;
    const pos = snapshot.position;
    if (pos.side === mySide) return null;
    const move = legal.allLegalMoves(pos.board, pos.side).filter(function (m) {return m.iccs === reply;})[0];
    if (!move) return null;
    const board = legal.applyMove(pos.board, move).board;
    const command = snapshot.positionCommand + (/\bmoves\b/.test(snapshot.positionCommand) ? " " : " moves ") + reply;
    return {position:fen.buildFen(board, mySide), command:command,
        key:identity(board, mySide, command, session, config),
        budget:Math.min(2500, Math.max(500, Number(movetime) || 1200)), reply:reply};
}

// Deliberately never emits a playable move. The matching search warms native Hash;
// the foreground always searches again using the actual verified move history.
// Cancellation drains the bounded worker; it never destroys a context inside JNI.
function create(worker) {
    let task = null, completed = null;
    function poll() {
        if (!worker.isRunning()) return;
        const state = worker.poll();
        if (state.status === "running") return;
        if (task && !task.invalid && state.status === "done" && state.result && state.result.ok) completed = task;
        task = null;
    }
    function cancel() {
        completed = null;
        if (task) task.invalid = true;
        if (worker.isRunning()) worker.cancel();
    }
    return {
        poll:poll, cancel:cancel, busy:function () {poll(); return worker.isRunning();},
        start:function (p, config, engine, historyStatus) {
            poll(); if (!p || worker.isRunning()) return false;
            completed = null;
            if (!worker.start(p.position, {movetime:p.budget, timeoutMs:p.budget + 1000,
                positionCommand:p.command, historyStatus:historyStatus, pikafish:config, engine:engine,
                multiPv:1, openingBook:false, bookOwnMoves:0, criticalReview:false})) return false;
            task = Object.assign({}, p, {invalid:false}); return true;
        },
        take:function (key) {
            poll(); const hit = !!(completed && completed.key === key);
            completed = null; return hit;
        }
    };
}
module.exports = {identity:identity, plan:plan, create:create};
