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

/** 各兵种单侧上限（与 fen.js 的 MAX_PER_TYPE 一致）。 */
const MAX_PER_TYPE = { R: 2, N: 2, B: 2, A: 2, K: 1, C: 2, P: 5 };

const DEFAULTS = {
    /** 最多迭代几轮（每轮降一格 / 翻一格） */
    maxRounds: 6,
    /** 低于这个分数的格子更容易被判为可疑（数量超限时优先降它） */
    preferLowScore: true,
    /**
     * 数量超限时先试「改阵营」而不是直接丢掉：
     * 真机实测（天天象棋皮肤）单帧的红黑判错会让「红仕 3 个 / 红兵 8 个」，
     * 这时丢掉棋子会**真的少一个子**，而翻一色去正好对得上 —— 证据上前者更可信。
     */
    allowColourFlip: true
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

function flipChar(ch) {
    const text = String(ch == null ? "" : ch);
    return text === text.toLowerCase() ? text.toUpperCase() : text.toLowerCase();
}

function countOf(board, ch) {
    let n = 0;
    for (let i = 0; i < board.length; i++) {
        if (board[i] === ch) {
            n += 1;
        }
    }
    return n;
}

/** 硬违规 = 「这个格子本身就不合法」（九宫 / 过河 / 兵卒后退）。 */
function hardViolationsOf(validation) {
    const list = (validation && validation.violations) || [];
    return list.filter(function (item) {
        return item.kind === "palace" || item.kind === "river" || item.kind === "pawn-back";
    });
}

/**
 * 试翻一格的颜色：翻完「至少不引入新硬违规、且硬违规总数没变化」就采纳。
 *
 * 为什么值得优先试：真机实测（天天象棋皮肤）里，黑「士」被判成红「仕」时，
 * 它在**黑方九宫**里就同时触发「仕士不在九宫」和「红仕 3 个」两条违规 ——
 * 真正的错因是颜色，直接翻回去两违规一起消失；而丢掉这枚子会让棋盘**真的少一个子**。
 *
 * @returns {{index,from,to,reason}|null}
 */
function tryFlipCell(cells, index, validation, opts, reason) {
    const cell = cells[index];
    if (!cell || cell.state !== STATE_PIECE || !cell.piece) {
        return null;
    }
    const limit = MAX_PER_TYPE[String(cell.piece).toUpperCase()];
    if (limit == null) {
        return null;
    }
    const from = cell.piece;
    const to = flipChar(from);
    const board = boardOf(cells);
    if (countOf(board, to) + 1 > limit) {
        return null; // 另一色已满 → 翻过去会超限，只能降级
    }
    const hardBefore = hardViolationsOf(validation).length;
    cell.piece = to;
    const after = fen.validateBoard(boardOf(cells));
    const hardAfter = hardViolationsOf(after).length;
    // 翻完必须真的变好（硬违规减少）或不引入新的；否则回退
    if (hardAfter > hardBefore || (hardAfter === hardBefore && hardBefore > 0)) {
        cell.piece = from;
        return null;
    }
    return {
        index: index,
        col: index % 9,
        row: Math.floor(index / 9),
        from: from,
        to: to,
        score: cell.score,
        reason: reason || ("改阵营 " + from + "→" + to)
    };
}

/**
 * 试着把「数量超限的那一色」里分数最低的一枚翻成另一色。
 * 只有当翻完两色都不超限、且不会引入新的硬违规时才采纳（否则返回 null，交给降级处理）。
 */
function tryFlipColour(cells, ch, validation, opts) {
    if (opts.allowColourFlip === false) {
        return null;
    }
    const other = flipChar(ch);
    const max = MAX_PER_TYPE[String(ch).toUpperCase()];
    if (max == null) {
        return null;
    }
    const board = boardOf(cells);
    if (countOf(board, ch) <= max) {
        return null;
    }
    if (countOf(board, other) + 1 > max) {
        return null; // 另一色也满了 → 真的多出一个子，只能降级
    }
    let target = null;
    for (let i = 0; i < 90; i++) {
        const cell = cells[i];
        if (!cell || cell.state !== STATE_PIECE || cell.piece !== ch) {
            continue;
        }
        const score = cell.score == null ? 0 : cell.score;
        if (target === null || score < target.score) {
            target = { index: i, score: score };
        }
    }
    if (target === null) {
        return null;
    }
    return tryFlipCell(cells, target.index, validation, opts,
        "数量超限但另一色未满 → 改阵营（" + ch + "→" + other + "）");
}

/**
 * @param cells 90 格（assign 的产物；会被就地修改）
 * @returns { cells, demoted: [...], flipped: [...], errors, warnings, rounds, changed }
 */
function repair(cells, options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const demoted = [];
    const flipped = [];
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
            // 1a. 先想「是不是红黑判错了」：翻一色就能合法的话不要丢子
            if (opts.allowColourFlip !== false) {
                const flipResult = tryFlipCell(cells, target.index, validation, opts,
                    "硬违规优先改阵营（" + target.message + "）");
                if (flipResult) {
                    flipped.push(flipResult);
                    rounds++;
                    continue;
                }
            }
            demote(cells, target.index, target.message, demoted);
            rounds++;
            continue;
        }
        // 2. 再处理数量超限：这类拿不出"哪个是假的"。
        //    2a) 先试「改阵营」：红黑分错时这样能**保住棋子**（而且数量正好对得上）；
        //    2b) 改不了（另一色也满）才按分数最低的降级。
        const count = violations[0];
        const flipResult = tryFlipColour(cells, count.char, validation, opts);
        if (flipResult) {
            flipped.push(flipResult);
            rounds++;
            continue;
        }
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
        flipped: flipped,
        changed: demoted.length > 0 || flipped.length > 0,
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
