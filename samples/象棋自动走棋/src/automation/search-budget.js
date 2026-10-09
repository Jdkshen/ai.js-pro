// @engine quickjs
/** Pikafish 每步预算。只读盘面，不改变棋规、历史或用户选择的固定层数模式。 */
"use strict";

const PIECES = "rnbakcpRNBAKCP";
const COMPETITIVE_TIME_MS = 5000;
const rules = require("../chess/rules.js");

function plan(board, requestedMs, depth, options) {
    const base = Math.max(100, Math.round(Number(requestedMs) || COMPETITIVE_TIME_MS));
    const fixedDepth = Math.round(Number(depth) || 0);
    const result = { movetimeMs: base, phase: "normal", pieces: null };
    if (fixedDepth > 0 || !board || board.length !== 90) {
        return result;
    }
    let pieces = 0;
    let redKing = false;
    let blackKing = false;
    for (let i = 0; i < board.length; i++) {
        const cell = board[i];
        if (cell === "?" || (cell && PIECES.indexOf(cell) < 0)) {
            return result; // 未知格不能拿来判断残局
        }
        if (cell) pieces++;
        if (cell === "K") redKing = true;
        if (cell === "k") blackKing = true;
    }
    result.pieces = pieces;
    if (!redKing || !blackKing) return result;
    if (options && options.adaptiveSearch === true) {
        const inCheck = (options.side === "w" || options.side === "b") && rules.isInCheck(board, options.side === "w");
        const target = inCheck ? 2800 : (pieces <= 10 ? 3000 : (pieces <= 16 ? 2400 : 1800));
        result.movetimeMs = Math.min(base, target);
        result.maxTotalMs = base;
        result.phase = inCheck ? "check" : (pieces <= 10 ? "endgame" : (pieces <= 16 ? "late" : "normal"));
        result.adaptive = true;
        return result;
    }
    // ★ 2026-10-06：残局上限 7s/12s → 8s/14s（把基础时间增量真正用进残局；对手 5s 封顶不变）。
    if (pieces <= 10) {
        result.movetimeMs = Math.max(base, Math.min(base * 3, 14000));
        if (result.movetimeMs > base) result.phase = "endgame";
    } else if (pieces <= 16) {
        result.movetimeMs = Math.max(base, Math.min(base * 2, 8000));
        if (result.movetimeMs > base) result.phase = "late";
    }
    return result;
}

/** 只迁移旧的 24 层 / 3 秒组合一次；用户之后仍能手动选回固定层数。 */
function shouldMigrateSavedSearch(mode, depth, timeoutMs, migrated) {
    return !migrated && mode === "depth" && Number(depth) === 24 && Number(timeoutMs) === 3000;
}

/** 0.6.21 迁移后可能把原本闲置的 0.5 秒滑条值带进限时模式；只修一次。 */
function shouldRepairLegacyTime(mode, thinkMs, migratedV1, migratedV2) {
    return migratedV1 && !migratedV2 && mode === "time" &&
        Number(thinkMs) > 0 && Number(thinkMs) < 3000;
}

/** 后台预思考共用 worker，未命中时仍需等待它退出；自动模式限制这段额外等待。 */
function prethinkMs(requestedMs, adaptive) {
    return Math.min(adaptive === true ? 700 : 2500, Math.max(500, Number(requestedMs) || 1200));
}

module.exports = {
    plan: plan,
    prethinkMs: prethinkMs,
    COMPETITIVE_TIME_MS: COMPETITIVE_TIME_MS,
    shouldMigrateSavedSearch: shouldMigrateSavedSearch,
    shouldRepairLegacyTime: shouldRepairLegacyTime
};
