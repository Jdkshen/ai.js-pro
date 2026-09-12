// @engine quickjs
/**
 * 中国象棋走法规则（纯 JS，可离线测试）。
 *
 * 分工：本文件只回答「这个棋子按兵种规则能走到哪」——即**伪合法着法**；
 * 「走完之后自己会不会被将 / 将帅照面」由 legal-moves.js 过滤。
 *
 * 坐标：col 0..8 左→右，row 0..9 上→下；红方在下（rows 7..9）。
 */
"use strict";

const ROWS = 10;
const COLS = 9;

function idx(col, row) {
    return row * COLS + col;
}

function inside(col, row) {
    return col >= 0 && col < COLS && row >= 0 && row < ROWS;
}

function pieceAt(board, col, row) {
    if (!inside(col, row)) {
        return null;
    }
    return board[idx(col, row)] || null;
}

function isRedPiece(ch) {
    return !!ch && ch >= "A" && ch <= "Z";
}

function isBlackPiece(ch) {
    return !!ch && ch >= "a" && ch <= "z";
}

function sameSide(a, b) {
    if (!a || !b) {
        return false;
    }
    return isRedPiece(a) === isRedPiece(b);
}

function inPalace(col, row, red) {
    if (col < 3 || col > 5) {
        return false;
    }
    return red ? (row >= 7 && row <= 9) : (row >= 0 && row <= 2);
}

/** 过河线：红方 row<=4 即过河；黑方 row>=5 即过河。 */
function hasCrossedRiver(row, red) {
    return red ? row <= 4 : row >= 5;
}

const ORTHOGONAL = [[0, -1], [0, 1], [-1, 0], [1, 0]];
const HORSE = [[1, 2], [2, 1], [-1, 2], [-2, 1], [1, -2], [2, -1], [-1, -2], [-2, -1]];
const DIAGONAL = [[1, 1], [1, -1], [-1, 1], [-1, -1]];

function push(moves, fromCol, fromRow, toCol, toRow, board) {
    if (!inside(toCol, toRow)) {
        return;
    }
    const target = pieceAt(board, toCol, toRow);
    const mover = pieceAt(board, fromCol, fromRow);
    if (target && sameSide(mover, target)) {
        return;
    }
    moves.push({ fromCol: fromCol, fromRow: fromRow, toCol: toCol, toRow: toRow, captured: target || null });
}

/** 伪合法着法：只按兵种走法生成，不检查走完后是否自将。 */
function pseudoMoves(board, fromCol, fromRow) {
    const moves = [];
    const piece = pieceAt(board, fromCol, fromRow);
    if (!piece) {
        return moves;
    }
    const red = isRedPiece(piece);
    const type = piece.toUpperCase();

    if (type === "R" || type === "C") {
        for (let d = 0; d < ORTHOGONAL.length; d++) {
            const dc = ORTHOGONAL[d][0];
            const dr = ORTHOGONAL[d][1];
            let col = fromCol + dc;
            let row = fromRow + dr;
            let jumped = false;
            while (inside(col, row)) {
                const target = pieceAt(board, col, row);
                if (type === "R") {
                    if (!target) {
                        push(moves, fromCol, fromRow, col, row, board);
                    } else {
                        push(moves, fromCol, fromRow, col, row, board);
                        break;
                    }
                } else if (!jumped) {
                    if (!target) {
                        push(moves, fromCol, fromRow, col, row, board);
                    } else {
                        jumped = true; // 这个子是炮架，不能吃
                    }
                } else if (target) {
                    push(moves, fromCol, fromRow, col, row, board); // 翻过炮架后的第一个子可吃
                    break;
                }
                col += dc;
                row += dr;
            }
        }
        return moves;
    }

    if (type === "N") {
        for (let i = 0; i < HORSE.length; i++) {
            const dc = HORSE[i][0];
            const dr = HORSE[i][1];
            const legCol = fromCol + (Math.abs(dc) === 2 ? (dc > 0 ? 1 : -1) : 0);
            const legRow = fromRow + (Math.abs(dr) === 2 ? (dr > 0 ? 1 : -1) : 0);
            if (pieceAt(board, legCol, legRow)) {
                continue; // 蹩马腿
            }
            push(moves, fromCol, fromRow, fromCol + dc, fromRow + dr, board);
        }
        return moves;
    }

    if (type === "B") {
        for (let i = 0; i < DIAGONAL.length; i++) {
            const dc = DIAGONAL[i][0];
            const dr = DIAGONAL[i][1];
            const toCol = fromCol + dc * 2;
            const toRow = fromRow + dr * 2;
            if (!inside(toCol, toRow)) {
                continue;
            }
            if (pieceAt(board, fromCol + dc, fromRow + dr)) {
                continue; // 塞象眼
            }
            if (red ? toRow < 5 : toRow > 4) {
                continue; // 象不过河
            }
            push(moves, fromCol, fromRow, toCol, toRow, board);
        }
        return moves;
    }

    if (type === "A") {
        for (let i = 0; i < DIAGONAL.length; i++) {
            const toCol = fromCol + DIAGONAL[i][0];
            const toRow = fromRow + DIAGONAL[i][1];
            if (!inPalace(toCol, toRow, red)) {
                continue;
            }
            push(moves, fromCol, fromRow, toCol, toRow, board);
        }
        return moves;
    }

    if (type === "K") {
        for (let i = 0; i < ORTHOGONAL.length; i++) {
            const toCol = fromCol + ORTHOGONAL[i][0];
            const toRow = fromRow + ORTHOGONAL[i][1];
            if (!inPalace(toCol, toRow, red)) {
                continue;
            }
            push(moves, fromCol, fromRow, toCol, toRow, board);
        }
        return moves;
    }

    if (type === "P") {
        const forward = red ? -1 : 1;
        push(moves, fromCol, fromRow, fromCol, fromRow + forward, board);
        if (hasCrossedRiver(fromRow, red)) {
            push(moves, fromCol, fromRow, fromCol - 1, fromRow, board);
            push(moves, fromCol, fromRow, fromCol + 1, fromRow, board);
        }
        return moves;
    }

    return moves;
}

/** 找某方的将/帅位置；找不到返回 null。 */
function findKing(board, red) {
    const target = red ? "K" : "k";
    for (let i = 0; i < board.length; i++) {
        if (board[i] === target) {
            return { col: i % COLS, row: Math.floor(i / COLS) };
        }
    }
    return null;
}

/** 将帅照面：同一列、中间无子。 */
function kingsFacing(board) {
    const redKing = findKing(board, true);
    const blackKing = findKing(board, false);
    if (!redKing || !blackKing || redKing.col !== blackKing.col) {
        return false;
    }
    const from = Math.min(redKing.row, blackKing.row) + 1;
    const to = Math.max(redKing.row, blackKing.row);
    for (let row = from; row < to; row++) {
        if (board[idx(redKing.col, row)]) {
            return false;
        }
    }
    return true;
}

/** 某一格是否被 byRed 方攻击（只按兵种走法，不考虑对方是否自将）。 */
function isAttacked(board, col, row, byRed) {
    for (let c = 0; c < COLS; c++) {
        for (let r = 0; r < ROWS; r++) {
            const piece = board[idx(c, r)];
            if (!piece || isRedPiece(piece) !== byRed) {
                continue;
            }
            const moves = pseudoMoves(board, c, r);
            for (let i = 0; i < moves.length; i++) {
                if (moves[i].toCol === col && moves[i].toRow === row) {
                    return true;
                }
            }
        }
    }
    return false;
}

/** 某方是否被将（含将帅照面）。 */
function isInCheck(board, red) {
    const king = findKing(board, red);
    if (!king) {
        return true; // 找不到将帅本身就是异常状态，按「不能走」处理
    }
    if (kingsFacing(board)) {
        return true;
    }
    return isAttacked(board, king.col, king.row, !red);
}

function moveToIccs(move) {
    const files = "abcdefghi";
    return files.charAt(move.fromCol) + (9 - move.fromRow) + files.charAt(move.toCol) + (9 - move.toRow);
}

const XQ_RULES = {
    ROWS: ROWS,
    COLS: COLS,
    idx: idx,
    inside: inside,
    pieceAt: pieceAt,
    isRedPiece: isRedPiece,
    isBlackPiece: isBlackPiece,
    inPalace: inPalace,
    hasCrossedRiver: hasCrossedRiver,
    pseudoMoves: pseudoMoves,
    findKing: findKing,
    kingsFacing: kingsFacing,
    isAttacked: isAttacked,
    isInCheck: isInCheck,
    moveToIccs: moveToIccs
};

module.exports = XQ_RULES;
