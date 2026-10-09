// @engine quickjs
/**
 * 局面工具（原「引擎门面」的**非搜索**部分）：上层（engine-router / pikafish 适配器 /
 * automation）只跟这里打交道。
 *
 * 输入可以给三种形态：
 *   1) FEN 字符串
 *   2) { board: 90 长数组, side: 'w'|'b' }   ← 识别链路直接给这个
 *   3) 90 长数组 + options.side
 * 有未知格（'?'）一律明确报错 —— 宁可不算棋，也不要在残局上瞎算。
 *
 * ⚠️ 2026-09-24（用户「把 js 引擎算法删除了 不要了」）：alpha-beta 搜索（search.js）、
 *    评估（eval.js）、本地 UCI 引擎（local-engine.js）与这里的 analyze() **已整体删除**，
 *    算棋只有 Pikafish 一条路（见 engine-router.js / pikafish-engine.js）。
 *    本文件只留：resolvePosition / inspect / applyBest / describe / describeMove。
 */
"use strict";

const fenModule = require("../chess/fen.js");
const legal = require("../chess/legal-moves.js");
const uci = require("./uci.js");

function normalizeSide(side) {
    return side === "b" || side === "B" || side === "black" || side === false ? "b" : "w";
}

/**
 * 把各种输入形态统一成 { board, side }；不合法就抛错（信息写给用户看）。
 */
function resolvePosition(position, options) {
    const opts = options || {};
    if (typeof position === "string") {
        const parsed = fenModule.parseFen(position);
        if (!parsed.ok) {
            throw new Error("FEN 解析失败：" + parsed.errors.join("；"));
        }
        return { board: parsed.board, side: parsed.sideToMove === "b" ? "b" : "w", fen: position };
    }
    if (position && typeof position === "object" && position.board) {
        return {
            board: position.board.slice(),
            side: normalizeSide(position.side == null ? opts.side : position.side),
            fen: null
        };
    }
    if (position && typeof position.length === "number" && position.length === 90) {
        return { board: position.slice(), side: normalizeSide(opts.side), fen: null };
    }
    throw new Error("算棋的输入既不是 FEN，也不是 90 格的局面");
}

/** 局面能不能算：格子完整、有双方将帅、轮到的一方有子可动。检查结果写明原因。 */
function inspect(board) {
    const problems = [];
    for (let i = 0; i < board.length; i++) {
        if (board[i] === "?" || board[i] === "unknown") {
            problems.push("第 " + (Math.floor(i / 9) + 1) + " 行第 " + (i % 9 + 1) + " 列还是未知格");
        }
    }
    let redKing = 0;
    let blackKing = 0;
    for (let i = 0; i < board.length; i++) {
        if (board[i] === "K") {
            redKing++;
        } else if (board[i] === "k") {
            blackKing++;
        }
    }
    if (redKing !== 1) {
        problems.push("红方帅的数量是 " + redKing + "（应为 1）");
    }
    if (blackKing !== 1) {
        problems.push("黑方将的数量是 " + blackKing + "（应为 1）");
    }
    // 用棋规再兜一层：士象位置、过河等硬约束
    if (problems.length === 0) {
        const check = fenModule.validateBoard(board);
        if (check.errors && check.errors.length) {
            problems.push("棋规校验不过：" + check.errors.join("；"));
        }
    }
    return problems;
}

/** 算完之后走到那一步，得到新局面（给 automation 用）。 */
function applyBest(board, result) {
    if (!result || !result.ok) {
        throw new Error("没有可用的着法");
    }
    return legal.applyMove(board, result.move);
}

/** 给人看的多行文本。 */
function describe(result) {
    const lines = [];
    if (!result) {
        return "没有结果";
    }
    if (!result.ok) {
        lines.push("算棋失败（阶段：" + (result.stage || "?") + "）：" + result.reason);
        return lines.join("\n");
    }
    lines.push("轮到：" + (result.side === "w" ? "红方" : "黑方"));
    lines.push("最佳着法：" + result.iccs +
        (result.captured ? "（吃 " + result.captured + "）" : ""));
    if (result.mateIn != null) {
        lines.push(result.mateIn > 0
            ? ("看到杀棋：约 " + result.mateIn + " 步杀")
            : ("局面危险：约 " + Math.abs(result.mateIn) + " 步内被将死"));
    }
    lines.push("评分：" + Math.round(result.score) + "（正=红优；兵=100、车=900）");
    lines.push("搜索：深度 " + result.depth + "、" + result.nodes + " 个结点、" + result.ms + "ms");
    if (result.pvIccs && result.pvIccs.length > 1) {
        lines.push("主变：" + result.pvIccs[0] + " " + result.pvIccs[1] +
            (result.pvIccs.length > 2 ? " …" : ""));
    }
    return lines.join("\n");
}

/** 一步棋的起点终点文本（给日志/界面用）。 */
function describeMove(move) {
    if (!move) {
        return "(无)";
    }
    return uci.moveToUci(move) + "  从 (col " + move.fromCol + ", row " + move.fromRow +
        ") 到 (col " + move.toCol + ", row " + move.toRow + ")";
}

const XQ_ENGINE = {
    applyBest: applyBest,
    describe: describe,
    describeMove: describeMove,
    inspect: inspect,
    resolvePosition: resolvePosition
};

module.exports = XQ_ENGINE;
