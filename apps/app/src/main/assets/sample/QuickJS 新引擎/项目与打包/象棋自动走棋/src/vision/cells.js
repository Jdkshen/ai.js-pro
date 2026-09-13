// @engine quickjs
/**
 * 格子三态契约（EMPTY / PIECE / UNKNOWN）与计数。
 *
 * 为什么要有这个模块（真机事故）：
 *   `recognizer.js` 里空格在 **FEN 字符层用 `null`**，未知格用 `'?'`；
 *   而 `tools/recognize-board.js` 的 `scoreGrid()` 曾用「不是 '.' 且不是 '?'」来数棋子 →
 *   把 `null` 空格**全算成了棋子**（实测「子 90 / 未知 0」这种不可能的读数，还因此把瞎网格选成最优）。
 *   同类风险散布在评测、伪标签、采集器、打印工具里，所以约定：**所有计数与判断只走本模块**。
 *
 * 三层约定（不要混用）：
 *   1. 内部格子（`cells[i]`）= `{ state: "EMPTY" | "PIECE" | "UNKNOWN", piece, score, ... }`（cell-assignment.js 产出）
 *   2. **FEN 字符层**（`chars[i]`）= 棋子字母 `RNBAKCPrnbakcp` | `null`（空格）| `'?'`（未知）
 *      —— 空格用 `null` 是**既定契约**：`fen.layoutOf()` 与 `rules.pieceAt()` 都按 falsy 判空，
 *      换成 `'.'` 会被棋规层当成棋子。要写进 JSON / 真值文件时用 `toText()` 转成 `'.'`。
 *   3. 文本层（JSON、真值、打印）= `'.'` 空格 / `'?'` 未知 / 字母棋子
 *
 * 计数只认七种合法字母（大小写），其它一律进 `invalid` 列表并说明原因 —— 不用「排除法」。
 */
"use strict";

/** 合法的七类棋子字符（大写=红，小写=黑）。顺序与 assets/models/chessai_160.txt 一致。 */
const PIECE_CHARS = "RNBAKCPrnbakcp";
const EMPTY = null;
const UNKNOWN = "?";
const EMPTY_TEXT = ".";
const STATE_EMPTY = "EMPTY";
const STATE_PIECE = "PIECE";
const STATE_UNKNOWN = "UNKNOWN";
const CELLS = 90;

/** 这个字符是不是合法棋子（不接受 null/'.'/'?'/其它）。 */
function isPieceChar(ch) {
    return typeof ch === "string" && ch.length === 1 && PIECE_CHARS.indexOf(ch) >= 0;
}

/** 空格：null / undefined / 空串 / '.' 都算（兼容外部输入），如实说明。 */
function isEmptyChar(ch) {
    return ch == null || ch === "" || ch === EMPTY_TEXT;
}

function isUnknownChar(ch) {
    return ch === UNKNOWN;
}

/** 字符 → 三态名。非法字符返回 "INVALID"。 */
function stateOf(ch) {
    if (isPieceChar(ch)) {
        return STATE_PIECE;
    }
    if (isUnknownChar(ch)) {
        return STATE_UNKNOWN;
    }
    if (isEmptyChar(ch)) {
        return STATE_EMPTY;
    }
    return "INVALID";
}

/** 三态 → FEN 字符层（空格 = null）。 */
function charFromState(state, piece) {
    if (state === STATE_PIECE && isPieceChar(piece)) {
        return piece;
    }
    if (state === STATE_UNKNOWN) {
        return UNKNOWN;
    }
    return EMPTY;
}

/** FEN 字符层 → 文本层（空格 = '.'）。打印/写 JSON 用。 */
function toText(ch) {
    return isEmptyChar(ch) ? EMPTY_TEXT : ch;
}

/** 文本层 → FEN 字符层。解析外部真值/草稿时用。 */
function fromText(ch) {
    if (typeof ch === "string" && ch === EMPTY_TEXT) {
        return EMPTY;
    }
    if (typeof ch === "string" && ch.length > 1) {
        return ch; // 多字符一律原样返回，让 count() 报 INVALID
    }
    return ch == null ? EMPTY : ch;
}

/** 数组级转换（长度不校验，交给 count()）。 */
function toTextArray(chars) {
    const out = new Array(chars ? chars.length : 0);
    for (let i = 0; i < out.length; i++) {
        out[i] = toText(chars[i]);
    }
    return out;
}

function fromTextArray(chars) {
    const out = new Array(chars ? chars.length : 0);
    for (let i = 0; i < out.length; i++) {
        out[i] = fromText(chars[i]);
    }
    return out;
}

/**
 * 数格子。**这是唯一的计数入口**。
 * @returns { total, pieces, empty, unknown, invalid:[{index,value}], ok, reason }
 */
function count(chars) {
    const list = chars || [];
    let pieces = 0;
    let empty = 0;
    let unknown = 0;
    const invalid = [];
    for (let i = 0; i < list.length; i++) {
        const state = stateOf(list[i]);
        if (state === STATE_PIECE) {
            pieces += 1;
        } else if (state === STATE_EMPTY) {
            empty += 1;
        } else if (state === STATE_UNKNOWN) {
            unknown += 1;
        } else {
            invalid.push({ index: i, value: list[i] });
        }
    }
    const total = list.length;
    let ok = invalid.length === 0;
    let reason = "";
    if (total !== CELLS) {
        ok = false;
        reason = "长度不是 " + CELLS + "（实际 " + total + "）";
    } else if (invalid.length) {
        reason = "含非法字符：" + invalid.slice(0, 3).map(function (item) {
            return "[" + item.index + "]=" + String(item.value);
        }).join(" ") + (invalid.length > 3 ? " …" : "");
    }
    return {
        total: total,
        pieces: pieces,
        empty: empty,
        unknown: unknown,
        invalid: invalid,
        ok: ok,
        reason: reason
    };
}

/** 由 cells（内部三态）直接计数，避免经过字符层。 */
function countCells(cells) {
    const list = cells || [];
    let pieces = 0;
    let empty = 0;
    let unknown = 0;
    for (let i = 0; i < list.length; i++) {
        const cell = list[i];
        const state = cell && cell.state ? cell.state : STATE_EMPTY;
        if (state === STATE_PIECE) {
            pieces += 1;
        } else if (state === STATE_UNKNOWN) {
            unknown += 1;
        } else {
            empty += 1;
        }
    }
    return { total: list.length, pieces: pieces, empty: empty, unknown: unknown, invalid: [], ok: list.length === CELLS, reason: "" };
}

function piecesOf(chars) {
    return count(chars).pieces;
}

module.exports = {
    PIECE_CHARS: PIECE_CHARS,
    CELLS: CELLS,
    EMPTY: EMPTY,
    UNKNOWN: UNKNOWN,
    EMPTY_TEXT: EMPTY_TEXT,
    STATE_EMPTY: STATE_EMPTY,
    STATE_PIECE: STATE_PIECE,
    STATE_UNKNOWN: STATE_UNKNOWN,
    isPieceChar: isPieceChar,
    isEmptyChar: isEmptyChar,
    isUnknownChar: isUnknownChar,
    stateOf: stateOf,
    charFromState: charFromState,
    toText: toText,
    fromText: fromText,
    toTextArray: toTextArray,
    fromTextArray: fromTextArray,
    count: count,
    countCells: countCells,
    piecesOf: piecesOf
};
