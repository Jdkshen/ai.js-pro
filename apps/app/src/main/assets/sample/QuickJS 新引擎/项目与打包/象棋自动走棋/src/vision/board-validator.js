// @engine quickjs
/**
 * 局面规则校验（重构方案 §3.9「规则校验与安全门」）。
 *
 * 它是**唯一有权否决局面**的模块：识别层说「我读完了」，规则层说「这局面可信吗」。
 * 方案要求进算棋前必须全部通过：
 *   · 恰好一个红帅、一个黑将；
 *   · 每方不超过 16 子；
 *   · 总子数不超过 32；
 *   · 未知格为 0，或未知格能由合法单步补全；
 *   · 当前局面至少存在一个合法着法；
 *   · （引擎返回的着法必须在该局面合法着法表里 —— 用 `isLegalMove` 查）
 *
 * 禁止把 `a0b0` 之类当兜底着法；无合法着法时必须报错，不许硬点。
 */
"use strict";

const pieceOcr = require("./piece-ocr.js");
const fenModule = require("../chess/fen.js");
const legalModule = require("../chess/legal-moves.js");

/** 七类的 FEN 字母表（用于统计兵种数量） */
const CLASSES = ["R", "N", "B", "A", "K", "C", "P"];

function isPieceChar(ch) {
    return typeof ch === "string" && /^[A-Za-z]$/.test(ch);
}

/**
 * 数一方的兵种分布与总数。
 * @param side "w" | "b"
 */
function countSide(board, side) {
    const wantRed = (side === "w");
    const out = { total: 0, byClass: {}, unknown: 0, empty: 0 };
    for (let i = 0; i < CLASSES.length; i++) {
        out.byClass[CLASSES[i]] = 0;
    }
    for (let i = 0; i < 90; i++) {
        const ch = board[i];
        if (ch === "?") {
            out.unknown += 1;
            continue;
        }
        if (!isPieceChar(ch)) {
            out.empty += 1;
            continue;
        }
        const red = (ch === ch.toUpperCase());
        if (red !== wantRed) {
            continue;
        }
        out.total += 1;
        const cls = ch.toUpperCase();
        if (out.byClass[cls] != null) {
            out.byClass[cls] += 1;
        }
    }
    return out;
}

/**
 * 校验一个盘面（90 格数组）是否可信。
 *
 * @param board    90 格；空格用 null/""、未知格用 "?"、棋子用 FEN 字符
 * @param options  { allowUnknown, side, legalMoves }
 * @return { ok, reason, stats, problems }
 *   ok=false ⇒ **绝不许拿去算棋**，reason 说明原因（中文，直接进日志）
 */
function validate(board, options) {
    const opts = options || {};
    const problems = [];
    if (!board || board.length !== 90) {
        return {
            ok: false,
            reason: "盘面长度不是 90（" + (board ? board.length : "无") + "）",
            problems: ["板面长度非法"],
            stats: null
        };
    }
    const red = countSide(board, "w");
    const black = countSide(board, "b");
    // ⚠️ 未知格**只数一次**：`countSide` 不管查哪一方都会把整盘的 `?` 数进去，
    //    所以 `red.unknown + black.unknown` 会把同一个未知格算两遍
    //    （真机表现：30 个 `?` 打印成「未知 60」，上层拿这个数做阈值就会提前拒掉正常结果）。
    let unknown = 0;
    for (let i = 0; i < 90; i++) {
        if (board[i] === "?") {
            unknown += 1;
        }
    }
    const stats = { red: red, black: black, unknown: unknown, total: red.total + black.total };

    // ① 帅/将各一个（方案 §3.9）
    if (red.byClass.K !== 1) {
        problems.push("红帅数量 " + red.byClass.K + "（应为 1）");
    }
    if (black.byClass.K !== 1) {
        problems.push("黑将数量 " + black.byClass.K + "（应为 1）");
    }
    // ② 每方 ≤16、总数 ≤32
    if (red.total > 16) {
        problems.push("红方 " + red.total + " 子（超 16）");
    }
    if (black.total > 16) {
        problems.push("黑方 " + black.total + " 子（超 16）");
    }
    if (stats.total > 32) {
        problems.push("总子数 " + stats.total + "（超 32）");
    }
    // ③ 兵种数量不能超过上限（例：三个炮一定是读错了）
    ["w", "b"].forEach(function (side) {
        const one = side === "w" ? red : black;
        CLASSES.forEach(function (cls) {
            const limit = pieceOcr.CLASS_LIMIT[cls];
            if (limit != null && one.byClass[cls] > limit) {
                problems.push((side === "w" ? "红" : "黑") + pieceOcr.CLASS_NAME[cls] +
                    " " + one.byClass[cls] + " 个（上限 " + limit + "）");
            }
        });
    });
    // ④ 未知格
    if (unknown > 0 && opts.allowUnknown !== true) {
        problems.push("还有 " + unknown + " 个未知格");
    }
    // ⑤ 至少有一个合法着法（方案：无合法着法时必须报错，不许当兜底）
    let moves = null;
    if (opts.side && typeof legalModule.allLegalMoves === "function") {
        try {
            moves = legalModule.allLegalMoves(board, opts.side) || [];
        } catch (error) {
            problems.push("合法着法枚举异常：" + (error && error.message ? error.message : error));
            moves = null;
        }
        if (moves && moves.length === 0 && problems.length === 0) {
            problems.push("当前局面没有任何合法着法");
        }
    }
    const ok = problems.length === 0;
    return {
        ok: ok,
        reason: ok ? "" : ("局面不可信：" + problems.join("；")),
        problems: problems,
        stats: stats,
        moves: moves ? moves.length : null
    };
}

/**
 * 引擎/规划给出的着法是否真的在当前合法着法表里（方案 §3.9 最后一条 + §5 双重保护）。
 * @return { ok, reason }
 */
function isLegalMove(board, side, iccs) {
    if (!iccs || String(iccs).length < 4 || String(iccs) === "a0b0") {
        return { ok: false, reason: "着法非法/无兜底着法：" + iccs };
    }
    if (typeof legalModule.allLegalMoves !== "function") {
        return { ok: false, reason: "缺少合法着法模块" };
    }
    let moves = null;
    try {
        moves = legalModule.allLegalMoves(board, side) || [];
    } catch (error) {
        return { ok: false, reason: "合法着法枚举异常：" + (error && error.message ? error.message : error) };
    }
    for (let i = 0; i < moves.length; i++) {
        if (moves[i] && moves[i].iccs === iccs) {
            return { ok: true, reason: "" };
        }
    }
    return { ok: false, reason: "着法 " + iccs + " 不在当前局面的合法着法表里" };
}

/** 便捷：把 FEN 字符串变成校验结果（面板/日志用） */
function validateFen(fen, options) {
    let board = null;
    try {
        const parsed = fenModule.parseFen(fen);
        board = parsed && parsed.ok ? parsed.board : null;
    } catch (error) {
        board = null;
    }
    if (!board) {
        return { ok: false, reason: "FEN 解析失败：" + fen, problems: ["FEN 解析失败"], stats: null };
    }
    return validate(board, options);
}

module.exports = {
    validate: validate,
    validateFen: validateFen,
    isLegalMove: isLegalMove,
    countSide: countSide,
    CLASSES: CLASSES
};
