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
            board: before.slice(),
            reason: "局面没有变化"
        };
    }

    // 「只是颜色读反」的装饰噪声（真机决定性证据 2026-09-14，K40 + 天天象棋离线局）：
    // 观测与基准只差若干**同字母、大小写不同**的格子（p→P / P→p），其余完全一致
    // → 一步都没走（真走子一定让起点变空，那是「棋子→空」的变化，不可能是同字母换大小写；
    //    吃子也一定同时改变起点）。
    // 现场：我方 炮(c2)、对方 炮(e7) 都还在原位，而卒/兵两格被读出反色
    // → 严格匹配 0 解、容差两步 0 解 → 「轮次推不出来」→ 永久 RECOVERING（一整轮白烧）。
    const flips = legal.diffBoards(before, observed);
    if (flips.length > 0 && flips.every(function (item) {
        return typeof item.before === "string" && typeof item.after === "string" &&
            item.before.length === 1 && item.after.length === 1 &&
            item.before !== item.after && item.before.toLowerCase() === item.after.toLowerCase();
    })) {
        return {
            status: STATUS.UNCHANGED,
            moves: [], side: side, nextSide: side, candidates: [],
            board: before.slice(),
            reason: "局面没有变化（" + flips.length + " 格颜色读反，已按基准纠正）"
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
            board: legal.applyMove(legal.applyMove(before, pairs[0][0]).board, pairs[0][1]).board,
            reason: "己方 " + pairs[0][0].iccs + " + 对方应手 " + pairs[0][1].iccs
        };
    }
    if (pairs.length > 1) {
        return {
            status: STATUS.AMBIGUOUS, moves: [], side: side, nextSide: side,
            candidates: pairs, reason: "有 " + pairs.length + " 种两步解释"
        };
    }

    // 严格两步也失败 → 再试一次**带装饰容差**的两步解释（见函数注释）。
    const tolerant = inferPairWithDecorationTolerance(before, side, observed);
    if (tolerant) {
        return {
            status: STATUS.MOVE_PAIR,
            moves: [tolerant.first, tolerant.second],
            side: side,
            nextSide: side,
            candidates: [tolerant.first, tolerant.second],
            board: tolerant.board,
            reason: "己方 " + tolerant.first.iccs + " + 对方应手 " + tolerant.second.iccs +
                "（落点格被游戏装饰读错，已按着法改正）"
        };
    }
    return {
        status: STATUS.UNKNOWN, moves: [], side: side, nextSide: side, candidates: [],
        debug: lastTolerantDebug,
        reason: "既没有合法单步也没有「一步+应手」能解释，需要恢复"
    };
}

/** 最近一次容差两步的解释情况（诊断用：真机上为什么没匹配上）。 */
let lastTolerantDebug = null;

/** ICCS 记法（文件字母 a..i + 行号 9..0）里第 which 个端点的 90 格下标；非法返回 -1。 */
function squareIndex(iccs, which) {
    const text = String(iccs || "");
    if (!/^[a-i][0-9][a-i][0-9]$/.test(text)) {
        return -1;
    }
    const file = "abcdefghi".indexOf(text.charAt(which === 0 ? 0 : 2));
    const rank = Number(text.charAt(which === 0 ? 1 : 3));
    if (file < 0 || !isFinite(rank)) {
        return -1;
    }
    return (9 - rank) * 9 + file;
}

/** 同色（红子的字母为大写、黑子为小写）；非棋子返回 false。 */
function sameColour(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== 1 || b.length !== 1) {
        return false;
    }
    return (a === a.toUpperCase()) === (b === b.toUpperCase());
}

/**
 * 带装饰容差的**两步**解释：模型把「刚落的那个子」读错兵种时用。
 *
 * 真机现场（2026-09-14，K40 + 天天象棋离线局）：我方 炮(c2)吃卒落在 (2,3)、
 * 对方紧接着 炮(c4)吃我兵落在 (4,6)，而这两格被游戏的高亮装饰影响，
 * 被模型读成了「兵」（**颜色对、兵种错**）→ 与「基准 + 两手」差 2 格
 * → 严格匹配 0 解 → 「轮次推不出来」→ 8s 看门狗 → 永久 RECOVERING（白等一整轮）。
 *
 * 收紧点（宁可交给恢复流程，也不乱认）：
 *  - 差异**只允许落在两手的落点**上：起点必须完全一致 —— 子确实离开了原位；
 *  - 每格必须与原子的**颜色一致**（红黑串色另有专门规则处理，这里不接）；
 *  - 必须有**唯一**解释（≥2 个候选一律判未知）。
 */
function inferPairWithDecorationTolerance(before, side, observed) {
    const ownMoves = legal.allLegalMoves(before, side);
    const debug = { ownMoves: ownMoves.length, tried: 0, minDiff: -1, best: "", fits: 0 };
    lastTolerantDebug = debug;
    const fits = [];
    for (let i = 0; i < ownMoves.length && fits.length < 2; i++) {
        const first = ownMoves[i];
        const firstDest = squareIndex(first.iccs, 1);
        if (firstDest < 0) {
            continue;
        }
        const midBoard = legal.applyMove(before, first).board;
        const replies = legal.allLegalMoves(midBoard, opposite(side));
        for (let j = 0; j < replies.length && fits.length < 2; j++) {
            const second = replies[j];
            const secondDest = squareIndex(second.iccs, 1);
            if (secondDest < 0 || secondDest === firstDest) {
                continue;
            }
            const endBoard = legal.applyMove(midBoard, second).board;
            const diff = legal.diffBoards(endBoard, observed);
            debug.tried += 1;
            if (debug.minDiff < 0 || diff.length < debug.minDiff) {
                debug.minDiff = diff.length;
                debug.best = first.iccs + "+" + second.iccs + ":" + diff.slice(0, 4).map(function (item) {
                    return "(" + item.col + "," + item.row + ")" + (item.before || "空") + ">" +
                        (item.after || "空");
                }).join(",");
            }
            if (diff.length > 2) {
                continue;
            }
            let usable = true;
            for (let k = 0; k < diff.length; k++) {
                const index = diff[k].row * 9 + diff[k].col;
                if (index !== firstDest && index !== secondDest) {
                    usable = false;
                    break;
                }
                if (!sameColour(endBoard[index], observed[index])) {
                    usable = false;
                    break;
                }
            }
            if (usable) {
                fits.push({ first: first, second: second, board: endBoard });
            }
        }
    }
    debug.fits = fits.length;
    return fits.length === 1 ? fits[0] : null;
}

const XQ_TRANSITION = { STATUS: STATUS, opposite: opposite, infer: infer };

module.exports = XQ_TRANSITION;
