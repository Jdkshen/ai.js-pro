// @engine quickjs
/**
 * 局面评估：返回**红方视角**的分数（正 = 红优）。
 *
 * 只做「子力 + 位置」两件事，位置用公式算而不是写 90 长的表 —— 可读、可改、文件小。
 * 黑方一律映射到红方坐标（row → 9 - row）再套同一套公式，保证红黑对称。
 *
 * 分值单位：兵 = 100，将/帅不估值（吃掉将帅在走法生成阶段就结束了）。
 */
"use strict";

const COLS = 9;
const ROWS = 10;

/** 子力价值（不含将/帅：将帅被吃不会出现在搜索里，将死由「无合法着法」判定）。 */
const VALUE = {
    R: 900,
    N: 400,
    C: 450,
    A: 200,
    B: 200,
    P: 100
};

const MATE = 30000;

function isRedPiece(ch) {
    return !!ch && ch >= "A" && ch <= "Z";
}

/** 中心度：越靠中间越高（0..4）。 */
function centerBonus(col) {
    const distance = Math.abs(col - 4);
    return 4 - distance;
}

/**
 * 位置分（红方视角；传进来的 row/col 已按红方坐标）。
 * 分值都不大，主要是让引擎在等价着法里挑顺眼的，别指望它体现棋理。
 */
function positionBonus(type, col, row) {
    const bonus = centerBonus(col);
    if (type === "P") {
        // 兵：往前走就加分，过河再加，逼近底线再加
        let score = (6 - row) * 4;
        if (row <= 4) {
            score += 30;
        }
        if (row <= 2) {
            score += 20;
        }
        return score + bonus;
    }
    if (type === "N") {
        // 马：在中间跳得开
        const rowCenter = 4.5 - Math.abs(row - 4.5);
        return bonus * 5 + Math.round(rowCenter) * 3;
    }
    if (type === "C") {
        // 炮：中路与河沿附近价值略高
        let score = bonus * 4;
        if (col === 4) {
            score += 8;
        }
        return score;
    }
    if (type === "R") {
        // 车：过河与中路略好（真正的价值靠搜索体现）
        let score = bonus * 2;
        if (row <= 4) {
            score += 10;
        }
        return score;
    }
    // 士/象：不鼓励乱动
    return 0;
}

/**
 * 评估一个局面。
 * @param board 90 长数组（'R'..'P' 红、'r'..'p' 黑、null 空）
 * @returns 红方视角分数
 */
function evaluate(board) {
    let score = 0;
    for (let i = 0; i < board.length; i++) {
        const piece = board[i];
        if (!piece) {
            continue;
        }
        const type = piece.toUpperCase();
        if (type === "K") {
            continue;
        }
        const red = isRedPiece(piece);
        const col = i % COLS;
        const row = (i - col) / COLS;
        const material = VALUE[type] || 0;
        const position = positionBonus(type, col, red ? row : ROWS - 1 - row);
        score += red ? (material + position) : -(material + position);
    }
    return score;
}

/** 单格上的子值（吃子排序用；将/帅给一个大值，吃到将帅就是结束）。 */
function valueOf(piece) {
    if (!piece) {
        return 0;
    }
    const type = piece.toUpperCase();
    return type === "K" ? 100000 : (VALUE[type] || 0);
}

const XQ_EVAL = {
    VALUE: VALUE,
    MATE: MATE,
    evaluate: evaluate,
    valueOf: valueOf,
    positionBonus: positionBonus
};

module.exports = XQ_EVAL;
