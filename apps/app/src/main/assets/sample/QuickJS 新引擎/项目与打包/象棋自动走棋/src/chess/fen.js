// @engine quickjs
/**
 * 中国象棋 FEN：生成、解析、静态校验。（纯 JS，不依赖任何平台接口，可离线测试）
 *
 * 规范坐标（FEN 图面）：col 0..8 左→右，row 0..9 上→下，红方在下。
 * 大写 = 红方 R N B A K C P；小写 = 黑方。
 */
"use strict";

const ROWS = 10;
const COLS = 9;
const CELLS = ROWS * COLS;
const RED_CHARS = "RNBAKCP";
const BLACK_CHARS = "rnbakcp";
const ALL_CHARS = RED_CHARS + BLACK_CHARS;

const START_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

/** 每类棋子上限（标准象棋）。 */
const MAX_PER_TYPE = { R: 2, N: 2, B: 2, A: 2, K: 1, C: 2, P: 5 };

function index(col, row) {
    return row * COLS + col;
}

function colOf(idx) {
    return idx % COLS;
}

function rowOf(idx) {
    return Math.floor(idx / COLS);
}

function isRed(ch) {
    return ch >= "A" && ch <= "Z";
}

/** 由 90 格生成 FEN。cells[i] = 棋子字符、null（空格）或 ""（未知，用 '?' 表示）。 */
function buildFen(cells, sideToMove, options) {
    const opts = options || {};
    if (!cells || cells.length !== CELLS) {
        throw new Error("cells 必须是长度 90 的数组");
    }
    const rows = [];
    for (let row = 0; row < ROWS; row++) {
        let line = "";
        let empty = 0;
        for (let col = 0; col < COLS; col++) {
            const ch = cells[index(col, row)];
            if (!ch) {
                empty++;
                continue;
            }
            if (ch === "?") {
                if (!opts.allowUnknown) {
                    throw new Error("存在未知格，不能生成 FEN（第 " + (row + 1) + " 行第 " + (col + 1) + " 列）");
                }
            } else if (ALL_CHARS.indexOf(ch) < 0) {
                throw new Error("非法棋子字符：" + ch);
            }
            if (empty > 0) {
                line += String(empty);
                empty = 0;
            }
            line += ch;
        }
        if (empty > 0) {
            line += String(empty);
        }
        rows.push(line);
    }
    const side = sideToMove === "b" || sideToMove === "B" ? "b" : (sideToMove === "w" || sideToMove === "W" ? "w" : "-");
    if (side === "-") {
        throw new Error("轮次未知时不能生成带 side 字段的 FEN");
    }
    return rows.join("/") + " " + side + " - - 0 1";
}

/** 解析 FEN；坏 FEN 一律报错，不做「补空格静默修复」。 */
function parseFen(fen) {
    const errors = [];
    const text = String(fen == null ? "" : fen).trim();
    if (text.length === 0) {
        return { ok: false, errors: ["FEN 为空"], board: null, sideToMove: null };
    }
    const parts = text.split(/\s+/);
    if (parts.length < 2) {
        errors.push("FEN 至少需要「布局 + 轮次」两段");
    }
    const rows = String(parts[0]).split("/");
    const board = new Array(CELLS);
    for (let i = 0; i < CELLS; i++) {
        board[i] = null;
    }
    if (rows.length !== ROWS) {
        errors.push("FEN 必须有 " + ROWS + " 行，实际 " + rows.length + " 行");
    }
    for (let row = 0; row < rows.length && row < ROWS; row++) {
        const line = rows[row];
        let col = 0;
        for (let i = 0; i < line.length; i++) {
            const ch = line.charAt(i);
            if (ch >= "0" && ch <= "9") {
                if (ch === "0") {
                    errors.push("第 " + (row + 1) + " 行出现非法数字 0");
                }
                col += Number(ch);
                continue;
            }
            if (ALL_CHARS.indexOf(ch) < 0) {
                errors.push("第 " + (row + 1) + " 行出现非法字符 " + ch);
                continue;
            }
            if (col >= COLS) {
                errors.push("第 " + (row + 1) + " 行超过 " + COLS + " 列");
                break;
            }
            board[index(col, row)] = ch;
            col++;
        }
        if (col !== COLS) {
            errors.push("第 " + (row + 1) + " 行展开为 " + col + " 格（应为 " + COLS + "）");
        }
    }
    const side = parts.length >= 2 ? parts[1].toLowerCase() : "";
    if (side !== "w" && side !== "b") {
        errors.push("轮次字段必须是 w 或 b，实际「" + (parts.length >= 2 ? parts[1] : "") + "」");
    }
    return {
        ok: errors.length === 0,
        errors: errors,
        board: board,
        sideToMove: side === "w" || side === "b" ? side : null
    };
}

function inPalace(col, row, red) {
    if (col < 3 || col > 5) {
        return false;
    }
    return red ? (row >= 7 && row <= 9) : (row >= 0 && row <= 2);
}

/**
 * 静态局面校验：只报告「与标准象棋规则不符」的可疑点，不替用户改棋盘。
 * 返回 { errors: [...], warnings: [...] }。
 */
function validateBoard(board) {
    const errors = [];
    const warnings = [];
    const counts = {};
    let total = 0;
    for (let i = 0; i < CELLS; i++) {
        const ch = board[i];
        if (!ch) {
            continue;
        }
        total++;
        counts[ch] = (counts[ch] || 0) + 1;
        const red = isRed(ch);
        const col = colOf(i);
        const row = rowOf(i);
        const upper = ch.toUpperCase();
        if (upper === "K" && !inPalace(col, row, red)) {
            errors.push((red ? "红帅" : "黑将") + "不在九宫内（第 " + (row + 1) + " 行第 " + (col + 1) + " 列）");
        }
        if (upper === "A" && !inPalace(col, row, red)) {
            errors.push((red ? "红仕" : "黑士") + "不在九宫内（第 " + (row + 1) + " 行第 " + (col + 1) + " 列）");
        }
        if (upper === "B") {
            const ownSideRows = red ? [7, 8, 9] : [0, 1, 2];
            if (ownSideRows.indexOf(row) < 0) {
                errors.push((red ? "红相" : "黑象") + "过河（第 " + (row + 1) + " 行第 " + (col + 1) + " 列）");
            }
            if (row % 2 !== 0) {
                warnings.push((red ? "红相" : "黑象") + "落在非象位行（第 " + (row + 1) + " 行）");
            }
        }
        if (upper === "P") {
            const crossed = red ? row <= 4 : row >= 5;
            const startRow = red ? 6 : 3;
            const behind = red ? row > startRow : row < startRow;
            if (behind) {
                errors.push((red ? "红兵" : "黑卒") + "退到起始线之后（第 " + (row + 1) + " 行）");
            }
            if (!crossed && col % 2 === 0) {
                warnings.push((red ? "红兵" : "黑卒") + "未过河却不在兵位列（第 " + (col + 1) + " 列）");
            }
        }
    }
    if (total > 32) {
        errors.push("棋子总数 " + total + " 超过标准上限 32");
    }
    for (let t = 0; t < RED_CHARS.length; t++) {
        const red = RED_CHARS.charAt(t);
        const black = BLACK_CHARS.charAt(t);
        const max = MAX_PER_TYPE[red];
        if ((counts[red] || 0) > max) {
            errors.push("红" + red + " 有 " + counts[red] + " 个，超过上限 " + max);
        }
        if ((counts[black] || 0) > max) {
            errors.push("黑" + black + " 有 " + counts[black] + " 个，超过上限 " + max);
        }
    }
    if ((counts.K || 0) !== 1 || (counts.k || 0) !== 1) {
        // 少子残局也可能合法，但「双方各一枚将帅」是标准在局状态；缺了要显式报告。
        errors.push("将帅数量异常：红帅 " + (counts.K || 0) + " 枚、黑将 " + (counts.k || 0) + " 枚");
    }
    return { errors: errors, warnings: warnings };
}

function pieceCount(board) {
    let n = 0;
    for (let i = 0; i < CELLS; i++) {
        if (board[i]) {
            n++;
        }
    }
    return n;
}

const XQ_FEN = {
    ROWS: ROWS,
    COLS: COLS,
    CELLS: CELLS,
    START_FEN: START_FEN,
    index: index,
    colOf: colOf,
    rowOf: rowOf,
    isRed: isRed,
    inPalace: inPalace,
    buildFen: buildFen,
    parseFen: parseFen,
    validateBoard: validateBoard,
    pieceCount: pieceCount
};

module.exports = XQ_FEN;
