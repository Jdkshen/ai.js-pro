// @engine quickjs
/**
 * 只在已有占位但字形未知的 ? 格内约束将帅候选；绝不向空格补帅。
 * 自动走棋入口还会先检查原始将帅读数，缺失时禁止行动。
 */
"use strict";

const rules = require("./rules.js");

function kingCharOf(isRed) {
    return isRed ? "K" : "k";
}

function findIndex(board, char) {
    if (!board) {
        return -1;
    }
    for (let i = 0; i < board.length; i++) {
        if (board[i] === char) {
            return i;
        }
    }
    return -1;
}

/** 九宫 9 格的下标（红 row 7..9 / 黑 row 0..2；列 3..5）。 */
function palaceIndexes(isRed) {
    const rows = isRed ? [7, 8, 9] : [0, 1, 2];
    const list = [];
    for (let r = 0; r < rows.length; r++) {
        for (let c = 3; c <= 5; c++) {
            list.push(rules.idx(c, rows[r]));
        }
    }
    return list;
}

/**
 * @param board     当前盘面（90 格；候选格必须是已有占位的 "?"）
 * @param prevBoard 上一张可靠盘面（可为 null ⇒ 只按 ①② 两条筛）
 * @returns [{ index, char, slots }]（可能两侧都补；没有可补的返回空数组）
 */
function inferKingsByRules(board, prevBoard) {
    const out = [];
    if (!board || board.length !== 90) {
        return out;
    }
    const sides = [true, false];
    for (let s = 0; s < sides.length; s++) {
        const isRed = sides[s];
        const char = kingCharOf(isRed);
        if (findIndex(board, char) >= 0) {
            continue;   // 帅/将还在盘上：不碰
        }
        const prevIndex = prevBoard ? findIndex(prevBoard, char) : -1;
        const prevRow = prevIndex >= 0 ? Math.floor(prevIndex / 9) : -1;
        const prevCol = prevIndex >= 0 ? prevIndex % 9 : -1;
        const slots = palaceIndexes(isRed);
        const hits = [];
        for (let i = 0; i < slots.length; i++) {
            const idx = slots[i];
            if (board[idx] !== "?") {
                continue;   // 那格上是认出来的别的子（"?"= 没认出来，可以放）
            }
            if (prevIndex >= 0) {
                const row = Math.floor(idx / 9), col = idx % 9;
                if (Math.abs(prevRow - row) > 1 || Math.abs(prevCol - col) > 1) {
                    continue;   // ③ 帅/将每步最多走一格
                }
            }
            const test = board.slice();
            test[idx] = char;
            let inCheck = true;
            try {
                inCheck = rules.isInCheck(test, isRed);
            } catch (error) {
                inCheck = true;   // 判不了就当作不合适（保守）
            }
            if (!inCheck) {
                hits.push(idx);
            }
        }
        if (hits.length === 1) {
            out.push({ index: hits[0], char: char, slots: slots.length });
        }
    }
    return out;
}

module.exports = {
    inferKingsByRules: inferKingsByRules,
    palaceIndexes: palaceIndexes
};
