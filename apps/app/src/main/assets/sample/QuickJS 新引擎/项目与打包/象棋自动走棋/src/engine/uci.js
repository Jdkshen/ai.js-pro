// @engine quickjs
/**
 * UCI 文本层：只做「字符串 ↔ 走法/局面」的翻译，不含搜索。
 *
 * 为什么要有这一层：Pikafish（原生 UCI 引擎）与上层（automation / 日志 / 自检）靠它做
 * 「字符串 ↔ 走法/局面」的翻译；当初为「本地引擎也讲 UCI」而写，现在服务的是真正的
 * UCI 引擎（Pikafish）—— 也因此这里永远不该长出搜索逻辑。
 *
 * ⚠️ 坐标：中国象棋引擎（Pikafish 等）用的 UCI 坐标 = 文件 a..i 从红方视角左到右、
 * 行 0..9 从**红方底线**往上数，这与本工程的 ICCS 记法**完全一致**，
 * 所以 `rules.moveToIccs` 的输出可以直接当 UCI 着法用，不需要换算。
 */
"use strict";

const rules = require("../chess/rules.js");

const FILES = "abcdefghi";

function moveToUci(move) {
    return rules.moveToIccs(move);
}

/** 'b2e2' → {fromCol, fromRow, toCol, toRow}；不合法返回 null。 */
function parseSquare(text) {
    const value = String(text || "").trim().toLowerCase();
    if (value.length !== 2) {
        return null;
    }
    const col = FILES.indexOf(value.charAt(0));
    const rank = Number(value.charAt(1));
    if (col < 0 || !(rank >= 0 && rank <= 9)) {
        return null;
    }
    return { col: col, row: 9 - rank };
}

/** 把 UCI 着法文本落到具体走法上（必须在合法着法里找得到，找不到返回 null）。 */
function parseMove(text, legalMoves) {
    const value = String(text || "").trim().toLowerCase();
    if (value.length < 4) {
        return null;
    }
    const from = parseSquare(value.substring(0, 2));
    const to = parseSquare(value.substring(2, 4));
    if (!from || !to) {
        return null;
    }
    for (let i = 0; i < legalMoves.length; i++) {
        const move = legalMoves[i];
        if (move.fromCol === from.col && move.fromRow === from.row &&
                move.toCol === to.col && move.toRow === to.row) {
            return move;
        }
    }
    return null;
}

/** `position fen <fen> [moves ...]` 的文本（本地引擎解析不了也照样能发给外部引擎）。 */
function buildPosition(fen, moves) {
    let line = "position fen " + fen;
    if (moves && moves.length) {
        line += " moves " + moves.join(" ");
    }
    return line;
}

/**
 * 解析 `go` 命令。只认我们用得到的几个字段，其余原样留在 extra 里便于排查。
 */
function parseGo(text) {
    const parts = String(text || "").trim().split(/\s+/);
    const result = { movetime: null, depth: null, extra: [] };
    for (let i = 1; i < parts.length; i++) {
        const key = parts[i];
        const value = parts[i + 1];
        if (key === "movetime" || key === "depth" || key === "nodes") {
            const number = Number(value);
            if (isFinite(number)) {
                if (key === "movetime") {
                    result.movetime = number;
                } else if (key === "depth") {
                    result.depth = number;
                } else {
                    result.extra.push(key + " " + value);
                }
                i++;
                continue;
            }
        }
        result.extra.push(key + (value && !/^[a-z]+$/i.test(value) ? " " + value : ""));
    }
    return result;
}

function bestmoveLine(move, fallback) {
    if (!move) {
        return "bestmove " + (fallback || "0000");
    }
    return "bestmove " + moveToUci(move);
}

function infoLine(info) {
    const parts = ["info"];
    if (info.depth != null) {
        parts.push("depth", String(info.depth));
    }
    if (info.score != null) {
        parts.push("score", "cp", String(Math.round(info.score)));
    }
    if (info.nodes != null) {
        parts.push("nodes", String(info.nodes));
    }
    if (info.timeMs != null) {
        parts.push("time", String(info.timeMs));
    }
    if (info.pv && info.pv.length) {
        parts.push("pv", info.pv.join(" "));
    }
    return parts.join(" ");
}

/** 把一行 UCI 文本拆成 [命令, 参数...]。 */
function tokenize(line) {
    const parts = String(line == null ? "" : line).trim().split(/\s+/);
    return { command: parts.length ? parts[0] : "", args: parts.slice(1) };
}

const XQ_UCI = {
    FILES: FILES,
    moveToUci: moveToUci,
    parseSquare: parseSquare,
    parseMove: parseMove,
    buildPosition: buildPosition,
    parseGo: parseGo,
    bestmoveLine: bestmoveLine,
    infoLine: infoLine,
    tokenize: tokenize
};

module.exports = XQ_UCI;
