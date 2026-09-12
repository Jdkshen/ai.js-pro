// @engine quickjs
/**
 * 完整合法着法校验：在 rules.js 的伪合法着法之上过滤「走完自将 / 将帅照面」。
 *
 * 与任务书 R01–R04 对应：马腿/象眼、炮架、九宫与过河、走完不能自将或照面。
 */
"use strict";

const rules = require("./rules.js");

const COLS = rules.COLS;
const ROWS = rules.ROWS;

function cloneBoard(board) {
    return board.slice();
}

/** 走一步；返回新局面与被吃子，不改动原数组。 */
function applyMove(board, move) {
    const next = cloneBoard(board);
    const captured = next[rules.idx(move.toCol, move.toRow)] || null;
    next[rules.idx(move.toCol, move.toRow)] = next[rules.idx(move.fromCol, move.fromRow)];
    next[rules.idx(move.fromCol, move.fromRow)] = null;
    return { board: next, captured: captured };
}

/** 走完之后己方是否安全（不被将、不照面）。 */
function wouldBeSafe(board, move, red) {
    const applied = applyMove(board, move);
    return !rules.isInCheck(applied.board, red);
}

function sideToRed(side) {
    return side === "w" || side === "W" || side === true;
}

/**
 * 某个棋子的全部合法着法。
 * @param side 可选；给了就校验「这个子确实属于该走棋方」
 */
function legalMoves(board, col, row, side) {
    const piece = rules.pieceAt(board, col, row);
    if (!piece) {
        return [];
    }
    if (side != null && rules.isRedPiece(piece) !== sideToRed(side)) {
        return [];
    }
    const red = rules.isRedPiece(piece);
    return rules.pseudoMoves(board, col, row).filter(function (move) {
        return wouldBeSafe(board, move, red);
    }).map(function (move) {
        move.iccs = rules.moveToIccs(move);
        return move;
    });
}

/** 某一方全部合法着法（按 ICCS 排序，便于比对与调试）。 */
function allLegalMoves(board, side) {
    const red = sideToRed(side);
    const moves = [];
    for (let col = 0; col < COLS; col++) {
        for (let row = 0; row < ROWS; row++) {
            const piece = board[rules.idx(col, row)];
            if (!piece || rules.isRedPiece(piece) !== red) {
                continue;
            }
            const list = legalMoves(board, col, row);
            for (let i = 0; i < list.length; i++) {
                moves.push(list[i]);
            }
        }
    }
    return moves.sort(function (a, b) { return a.iccs < b.iccs ? -1 : (a.iccs > b.iccs ? 1 : 0); });
}

/**
 * 校验一步棋是否合法；不合法时给出原因（界面/日志要用具体原因，不要统一说"非法"）。
 * @returns { legal, reason }
 */
function isLegalMove(board, fromCol, fromRow, toCol, toRow, side) {
    if (!rules.inside(fromCol, fromRow) || !rules.inside(toCol, toRow)) {
        return { legal: false, reason: "坐标越界" };
    }
    const piece = rules.pieceAt(board, fromCol, fromRow);
    if (!piece) {
        return { legal: false, reason: "起点没有棋子" };
    }
    if (side != null && rules.isRedPiece(piece) !== sideToRed(side)) {
        return { legal: false, reason: "不是该走棋方的棋子" };
    }
    if (fromCol === toCol && fromRow === toRow) {
        return { legal: false, reason: "原地不动不是着法" };
    }
    const target = rules.pieceAt(board, toCol, toRow);
    if (target && rules.isRedPiece(target) === rules.isRedPiece(piece)) {
        return { legal: false, reason: "目标格是己方棋子" };
    }
    const candidate = rules.pseudoMoves(board, fromCol, fromRow).filter(function (move) {
        return move.toCol === toCol && move.toRow === toRow;
    });
    if (candidate.length === 0) {
        return { legal: false, reason: "不符合该兵种的走法（含蹩马腿/塞象眼/炮架/九宫/过河限制）" };
    }
    if (!wouldBeSafe(board, candidate[0], rules.isRedPiece(piece))) {
        return { legal: false, reason: "走完己方被将或将帅照面" };
    }
    return { legal: true, reason: "合法" };
}

/**
 * 把观测局面与枚举出的候选着法比对。
 * observed[i] 允许为 '?'（未知）——未知格不参与比较。
 * @returns { status: "UNIQUE" | "AMBIGUOUS" | "NONE", move, candidates }
 */
function matchObserved(board, side, observed) {
    const candidates = [];
    const moves = allLegalMoves(board, side);
    for (let i = 0; i < moves.length; i++) {
        const after = applyMove(board, moves[i]).board;
        if (boardMatches(after, observed)) {
            candidates.push(moves[i]);
        }
    }
    if (candidates.length === 1) {
        return { status: "UNIQUE", move: candidates[0], candidates: candidates };
    }
    if (candidates.length > 1) {
        return { status: "AMBIGUOUS", move: null, candidates: candidates };
    }
    // 「局面没变」也要能被识别：不是合法单步，但可能只是还没落子
    if (boardMatches(board, observed)) {
        return { status: "NONE", move: null, candidates: [], unchanged: true };
    }
    return { status: "NONE", move: null, candidates: [], unchanged: false };
}

/** 两个局面是否一致；observed 里的 '?' 视为未知、跳过。 */
function boardMatches(board, observed) {
    if (!board || !observed || board.length !== observed.length) {
        return false;
    }
    for (let i = 0; i < board.length; i++) {
        const expected = observed[i];
        if (expected === "?" || expected === undefined) {
            continue;
        }
        const actual = board[i] || null;
        const wanted = expected || null;
        if (actual !== wanted) {
            return false;
        }
    }
    return true;
}

/** 局面差异摘要（诊断用）：[{index, col, row, before, after}] */
function diffBoards(before, after) {
    const diff = [];
    for (let i = 0; i < before.length; i++) {
        const a = before[i] || null;
        const b = after[i] || null;
        if (a !== b) {
            diff.push({ index: i, col: i % COLS, row: Math.floor(i / COLS), before: a, after: b });
        }
    }
    return diff;
}

const XQ_LEGAL = {
    cloneBoard: cloneBoard,
    applyMove: applyMove,
    wouldBeSafe: wouldBeSafe,
    sideToRed: sideToRed,
    legalMoves: legalMoves,
    allLegalMoves: allLegalMoves,
    isLegalMove: isLegalMove,
    matchObserved: matchObserved,
    boardMatches: boardMatches,
    diffBoards: diffBoards
};

module.exports = XQ_LEGAL;
