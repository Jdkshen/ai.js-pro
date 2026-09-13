// @engine quickjs
/**
 * 一致性修复：用棋规反查「识别结果里哪些格不可信」，把它们降级成未知，而不是当成事实。
 *
 * 动机（真机实测）：单帧识别出 32 个棋子、阵营 16 红 16 黑，看着挺好，但拿棋规一校验就露馅 ——
 * 「红仕有 3 个（上限 2）」「黑卒退到起始线之后（第 3 行）」。这两类错误是**可归因到具体格子**的，
 * 所以不必整盘作废：把违规的格子降级成 '?'，剩下的照用 —— 后面还有多帧融合和时序来补。
 *
 * 优先级：格子级违规（九宫/过河/兵卒后退）证据更硬，先降它们；数量超限只是数量矛盾，
 * 拿不出"哪个是假的"，所以按分数最低的先降。
 *
 * 纯 JS，不碰平台接口。
 */
"use strict";

const fen = require("./fen.js");
const defaults = require("../util/defaults.js");

const STATE_PIECE = "PIECE";
const STATE_UNKNOWN = "UNKNOWN";

const DEFAULTS = {
    /** 最多迭代几轮（每轮降一格） */
    maxRounds: 6,
    /** 低于这个分数的格子更容易被判为可疑（数量超限时优先降它） */
    preferLowScore: true
};

/** 把 cells 变成 board（只保留确定有子的格）。 */
function boardOf(cells) {
    const board = new Array(90);
    for (let i = 0; i < 90; i++) {
        const cell = cells[i];
        board[i] = cell && cell.state === STATE_PIECE && cell.piece ? cell.piece : null;
    }
    return board;
}

function demote(cells, index, reason, log) {
    const cell = cells[index];
    if (!cell) {
        return;
    }
    log.push({
        index: index,
        col: index % 9,
        row: Math.floor(index / 9),
        char: cell.piece,
        score: cell.score,
        reason: reason
    });
    cell.state = STATE_UNKNOWN;
    cell.piece = null;
    cell.conflict = true;
}

/**
 * @param cells 90 格（assign 的产物；会被就地修改）
 * @returns { cells, demoted: [...], errors, warnings, rounds, changed }
 */
function repair(cells, options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const demoted = [];
    let rounds = 0;
    let validation = null;
    for (let round = 0; round < opts.maxRounds; round++) {
        const board = boardOf(cells);
        validation = fen.validateBoard(board);
        const violations = validation.violations || [];
        if (violations.length === 0) {
            break;
        }
        // 1. 先处理格子级违规（九宫 / 过河 / 兵卒后退）—— 这类"这个格子本身就不合法"证据最硬
        const hard = violations.filter(function (item) {
            return item.kind === "palace" || item.kind === "river" || item.kind === "pawn-back";
        });
        if (hard.length > 0) {
            const target = opts.preferLowScore
                ? hard.reduce(function (worst, item) {
                    const score = cells[item.index] ? (cells[item.index].score || 0) : 0;
                    return (worst === null || score < worst.score) ? { index: item.index, score: score, message: item.message } : worst;
                }, null)
                : { index: hard[0].index, score: 0, message: hard[0].message };
            demote(cells, target.index, target.message, demoted);
            rounds++;
            continue;
        }
        // 2. 再处理数量超限：这类拿不出"哪个是假的"，按分数最低的降
        const count = violations[0];
        const sameChar = violations.filter(function (item) { return item.char === count.char; });
        const target = sameChar.reduce(function (worst, item) {
            const score = cells[item.index] ? (cells[item.index].score || 0) : 0;
            return (worst === null || score < worst.score) ? { index: item.index, score: score, message: item.message } : worst;
        }, null);
        demote(cells, target.index, target.message, demoted);
        rounds++;
    }
    const board = boardOf(cells);
    const final = fen.validateBoard(board);
    return {
        cells: cells,
        demoted: demoted,
        changed: demoted.length > 0,
        rounds: rounds,
        errors: final.errors,
        warnings: final.warnings,
        before: validation ? validation.errors : [],
        after: final.errors
    };
}

/** 把 chars（含 '?'）里的未知格对应到 cells 的 UNKNOWN（给修复后的布局用）。 */
function charsOf(cells) {
    const chars = new Array(90);
    for (let i = 0; i < 90; i++) {
        const cell = cells[i];
        if (!cell) {
            chars[i] = null;
        } else if (cell.state === STATE_PIECE) {
            chars[i] = cell.piece;
        } else if (cell.state === STATE_UNKNOWN) {
            chars[i] = "?";
        } else {
            chars[i] = null;
        }
    }
    return chars;
}

module.exports = {
    DEFAULTS: DEFAULTS,
    repair: repair,
    boardOf: boardOf,
    charsOf: charsOf
};
