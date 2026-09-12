// @engine quickjs
/**
 * 局面变化解析：从「上一可靠局面 + 新观测」推断发生了什么（任务书 9.3）。
 *
 * 原则：
 * - 唯一合法单步匹配 → 才能确定着法与轮次；
 * - 多个匹配 → 未知，不靠颜色差分硬猜；
 * - 点击确认期间允许「预期己方着法 + 一个合法对方应手」这种两步情形；
 * - 跨过更多着法时**不补造历史**，直接进入恢复。
 */
"use strict";

const legal = require("./legal-moves.js");
const rules = require("./rules.js");

const STATUS = {
    MOVE: "MOVE",             // 唯一合法单步
    MOVE_PAIR: "MOVE_PAIR",   // 己方着法 + 对方一个应手
    UNCHANGED: "UNCHANGED",   // 与走之前一致（可能还没落子）
    AMBIGUOUS: "AMBIGUOUS",   // 多个合法解释
    UNKNOWN: "UNKNOWN"        // 无法解释
};

function opposite(side) {
    return side === "w" || side === "W" ? "b" : "w";
}

/**
 * @param before  上一可靠局面的 90 格数组（字符/null）
 * @param side    该走棋方 "w"（红）或 "b"（黑）
 * @param observed 新观测的 90 格数组，未知格用 '?'
 * @param options.allowPair 是否允许「己方 + 对方一步」的两步解释（默认允许）
 * @returns { status, moves, side, nextSide, candidates, reason }
 */
function infer(before, side, observed, options) {
    const opts = options || {};
    const single = legal.matchObserved(before, side, observed);
    if (single.status === "UNIQUE") {
        return {
            status: STATUS.MOVE,
            moves: [single.move],
            side: side,
            nextSide: opposite(side),
            candidates: [single.move],
            reason: "唯一合法单步：" + single.move.iccs
        };
    }
    if (single.status === "NONE" && single.unchanged) {
        return {
            status: STATUS.UNCHANGED,
            moves: [],
            side: side,
            nextSide: side,
            candidates: [],
            reason: "局面没有变化"
        };
    }
    if (single.status === "AMBIGUOUS") {
        return {
            status: STATUS.AMBIGUOUS,
            moves: [],
            side: side,
            nextSide: side,
            candidates: single.candidates,
            reason: "有 " + single.candidates.length + " 个合法着法都能得到该局面"
        };
    }

    if (opts.allowPair === false) {
        return {
            status: STATUS.UNKNOWN, moves: [], side: side, nextSide: side, candidates: [],
            reason: "没有合法单步能解释这次变化"
        };
    }

    // 两步解释：己方走一步 + 对方一个合法应手（点击确认期间常见）
    const ownMoves = legal.allLegalMoves(before, side);
    const pairs = [];
    for (let i = 0; i < ownMoves.length; i++) {
        const first = ownMoves[i];
        const midBoard = legal.applyMove(before, first).board;
        const replies = legal.allLegalMoves(midBoard, opposite(side));
        for (let j = 0; j < replies.length; j++) {
            const second = replies[j];
            const endBoard = legal.applyMove(midBoard, second).board;
            if (legal.boardMatches(endBoard, observed)) {
                pairs.push([first, second]);
                if (pairs.length > 8) {
                    // 解释太多说明观测太模糊，早点停下来交给恢复流程
                    return {
                        status: STATUS.AMBIGUOUS, moves: [], side: side, nextSide: side,
                        candidates: [], reason: "两步解释过多（>" + pairs.length + "），按未知处理"
                    };
                }
            }
        }
    }
    if (pairs.length === 1) {
        return {
            status: STATUS.MOVE_PAIR,
            moves: pairs[0],
            side: side,
            nextSide: side,
            candidates: pairs[0],
            reason: "己方 " + pairs[0][0].iccs + " + 对方应手 " + pairs[0][1].iccs
        };
    }
    if (pairs.length > 1) {
        return {
            status: STATUS.AMBIGUOUS, moves: [], side: side, nextSide: side,
            candidates: pairs, reason: "有 " + pairs.length + " 种两步解释"
        };
    }
    return {
        status: STATUS.UNKNOWN, moves: [], side: side, nextSide: side, candidates: [],
        reason: "既没有合法单步也没有「一步+应手」能解释，需要恢复"
    };
}

const XQ_TRANSITION = { STATUS: STATUS, opposite: opposite, infer: infer };

module.exports = XQ_TRANSITION;
