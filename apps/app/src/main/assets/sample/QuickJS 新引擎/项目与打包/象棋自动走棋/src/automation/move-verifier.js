// @engine quickjs
/**
 * 落子确认（任务书 14 的确认表）。
 *
 * 只用**新观测**判断棋子到底有没有走到目标格，五种结论：
 *
 * | 结论 | 含义 | 上层动作 |
 * |---|---|---|
 * | CONFIRMED | 与预期己方着法后局面一致 | 记录成功，等对方 |
 * | OPPONENT_REPLY | 连对方一个合法应手都出现了 | 连续记两步，仍是我方走 |
 * | UNCHANGED | 还是走之前那一步没动 | 不算成功；按选择状态决定等待/恢复/有限重试 |
 * | UNEXPECTED | 是别的可靠局面 | 停止旧票据，解析/恢复 |
 * | UNRELIABLE | 不完整/不稳定/几何不可信 | 等新观测，超时再恢复 |
 *
 * 注意：UNRELIABLE 与「没变化」完全不是一回事 —— 把「看不清」当成「没落子」就会重复点击。
 */
"use strict";

const fenModule = require("../chess/fen.js");
const legal = require("../chess/legal-moves.js");

const RESULT = {
    CONFIRMED: "CONFIRMED",
    OPPONENT_REPLY: "OPPONENT_REPLY",
    UNCHANGED: "UNCHANGED",
    UNEXPECTED: "UNEXPECTED",
    UNRELIABLE: "UNRELIABLE"
};

function opposite(side) {
    return side === "b" ? "w" : "b";
}

function boardOfFen(fen) {
    const parsed = fenModule.parseFen(fen);
    if (!parsed.ok) {
        return null;
    }
    return parsed.board;
}

/**
 * @param input.ticket          票据（要 baseFen 与 expectedAfterFen）
 * @param input.observed        新观测的 90 格字符（'?' = 未知，null = 空）
 * @param input.unknownCells    未知格数量（默认按 observed 数）
 * @param input.stable          观测是否已稳定（时序融合的结论）
 * @param input.geometryTrusted 当前几何是否可信
 * @param input.conflicts       关键冲突数（>0 视为不可靠）
 * @returns { result, reason, board, plies, unknownCells }
 */
function verify(input) {
    const src = input || {};
    const ticket = src.ticket;
    const observed = src.observed;
    const unknownCells = src.unknownCells == null
        ? (observed ? observed.filter(function (ch) { return ch === "?"; }).length : 90)
        : Number(src.unknownCells);
    const base = { board: null, plies: [], unknownCells: unknownCells };

    if (!ticket) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "没有票据，无法确认" });
    }
    if (src.stable === false) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "观测还不稳定（等下一次采集）" });
    }
    if (src.geometryTrusted === false) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "棋盘几何不可信（需要重新定位）" });
    }
    if (src.conflicts > 0) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "有 " + src.conflicts + " 处关键冲突" });
    }
    if (!observed || observed.length !== 90) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "观测不是完整的 90 格" });
    }
    // 有未知格就不做「成功」判定：看不清的格子可能正是那个子
    if (unknownCells > 0) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "还有 " + unknownCells + " 个未知格" });
    }

    const expected = boardOfFen(ticket.expectedAfterFen);
    if (!expected) {
        return Object.assign({}, base, { result: RESULT.UNRELIABLE, reason: "票据的预期局面 FEN 解析不了" });
    }
    if (legal.boardMatches(expected, observed)) {
        return Object.assign({}, base, {
            result: RESULT.CONFIRMED,
            reason: "与预期落子后局面一致",
            board: observed.slice(),
            plies: [ticket.moveICCS]
        });
    }

    // 对方是不是已经应了一步（点击确认期间很常见）
    const replies = legal.allLegalMoves(expected, opposite(ticket.baseSide));
    const matched = [];
    for (let i = 0; i < replies.length; i++) {
        const after = legal.applyMove(expected, replies[i]).board;
        if (legal.boardMatches(after, observed)) {
            matched.push(replies[i]);
        }
    }
    if (matched.length === 1) {
        return Object.assign({}, base, {
            result: RESULT.OPPONENT_REPLY,
            reason: "我方 " + ticket.moveICCS + " 已落子，对方应手 " + matched[0].iccs,
            board: observed.slice(),
            plies: [ticket.moveICCS, matched[0].iccs]
        });
    }
    if (matched.length > 1) {
        return Object.assign({}, base, {
            result: RESULT.UNRELIABLE,
            reason: "对方应手有 " + matched.length + " 种解释，无法唯一确定"
        });
    }

    const baseBoard = boardOfFen(ticket.baseFen);
    if (baseBoard && legal.boardMatches(baseBoard, observed)) {
        return Object.assign({}, base, {
            result: RESULT.UNCHANGED,
            reason: "局面仍是走之前的样子（可能只选中了棋子，没落子）",
            board: observed.slice()
        });
    }

    const diff = baseBoard ? legal.diffBoards(baseBoard, observed) : [];
    return Object.assign({}, base, {
        result: RESULT.UNEXPECTED,
        reason: "出现了无法解释的局面（与基准差 " + diff.length + " 格：" +
            diff.slice(0, 6).map(function (item) {
                return "(col " + item.col + ",row " + item.row + ") " +
                    (item.before || "空") + "→" + (item.after || "空");
            }).join("；") + (diff.length > 6 ? " …" : "") + "）",
        board: observed.slice()
    });
}

/** 结论是否算「我方真的走成了」（CONFIRMED 与 OPPONENT_REPLY 都算）。 */
function isSuccess(result) {
    return result === RESULT.CONFIRMED || result === RESULT.OPPONENT_REPLY;
}

function describe(verdict) {
    if (!verdict) {
        return "没有确认结果";
    }
    return verdict.result + "：" + verdict.reason;
}

module.exports = {
    RESULT: RESULT,
    verify: verify,
    isSuccess: isSuccess,
    describe: describe
};
