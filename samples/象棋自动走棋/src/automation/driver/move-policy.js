// @engine quickjs
"use strict";

/** 拒收着法、循环与求胜策略：每个实例独立持有状态；跨模块动作由装配入口注入。
 * 状态通过参数显式共享；跨模块调用通过动作表，模块间不循环 require。
 */
const fenModule = require("../../chess/fen.js");
const legal = require("../../chess/legal-moves.js");
const rulesModule = require("../../chess/rules.js");
const winSeekModule = require("../win-seek.js");

function create(driverState, driverActions) {
    /**
     * 局面指纹：「90 格逐格序列 | 该走棋方」。
     * ⚠️ **必须逐格拼（null → "."），不能用 `board.join("")`**（2026-09-25 修）：
     *   join 会把空格折叠掉 —— 残局里稀疏盘面的「子的先后顺序」常常不变
     *   （帅/车/将各挪一格，顺序还是 帅-车-将）⇒ 两个**不同**局面算出同一个键。
     *   真机用例实锤：车 e0↔d0 的两个局面被算成一键 ⇒ 循环历史把两边的着法混在一起，
     *   禁手表里出现「e0d0 与 d0e0 互串」。同一教训在 move-ledger 的 cellsKey 里记过（a0a1 用例）。
     */
    function positionKeyOfBoard(board, side) {
        if (!board) {
            return "|" + (side || "");
        }
        let out = "";
        for (let i = 0; i < board.length; i++) {
            out += board[i] == null ? "." : String(board[i]);
        }
        return out + "|" + (side || "");
    }

    function positionKeyOfTicket(ticket) {
        if (!ticket || !ticket.baseFen) {
            return "";
        }
        let board = null;
        try {
            const parsed = fenModule.parseFen(ticket.baseFen);
            board = parsed && parsed.ok ? parsed.board : null;
        } catch (error) {
            board = null;
        }
        return board ? positionKeyOfBoard(board, ticket.baseSide) : "";
    }

    /** 记录「这手游戏没接受」，本局面内不再选（最多留 8 个局面，防止无限增长）。 */
    function markMoveRejected(ticket, why) {
        const key = positionKeyOfTicket(ticket);
        if (!key || !ticket || !ticket.moveICCS) {
            return;
        }
        let entry = driverState.rejectedMoves.get(key);
        if (!entry) {
            entry = { iccs: {}, at: driverState.nowMono() };
            driverState.rejectedMoves.set(key, entry);
        }
        if (!entry.iccs[ticket.moveICCS]) {
            entry.iccs[ticket.moveICCS] = true;
            driverActions.note(
                "这手游戏没接受（" +
                    why +
                    "），本局面内不再选：" +
                    ticket.moveICCS +
                    "（已记 " +
                    Object.keys(entry.iccs).length +
                    " 手）"
            );
        }
        while (driverState.rejectedMoves.size > 8) {
            const oldest = driverState.rejectedMoves.keys().next();
            if (oldest.done) {
                break;
            }
            driverState.rejectedMoves.delete(oldest.value);
        }
    }

    /** 当前局面要排除的着法（没有就返回 null）。 */
    function rejectedIccsFor(board, side) {
        const entry = driverState.rejectedMoves.get(positionKeyOfBoard(board, side));
        if (!entry) {
            return null;
        }
        const list = Object.keys(entry.iccs);
        return list.length ? list : null;
    }

    /** 引擎评分是不是「我方杀棋」（只看正杀分；被将死的负分不影响点击，不用管）。 */
    function isMateClaim(score) {
        return typeof score === "number" && score >= driverState.MATE_CLAIM_MIN_SCORE;
    }

    /** 把「一手之后的局面」记进求胜账本（带连续去重，见 lastWinSeekKey）。 */
    function noteWinSeekPosition(board, side) {
        if (!board) {
            return;
        }
        const key = winSeekModule.keyOf(board, side);
        if (key === driverState.lastWinSeekKey) {
            return; // 同一局面连续记两次（确认 + 推断）只算一次
        }
        driverState.lastWinSeekKey = key;
        driverState.winSeekSeen.note(board, side);
    }

    function recordPlanDecision(board, side, iccs) {
        if (!board || !iccs) {
            return;
        }
        driverState.planHistory.record(positionKeyOfBoard(board, side), iccs, driverState.decisionEpoch);
    }

    /** 这个着法走完之后是不是**将军对方**（长将检测；判不出来一律按 false）。 */
    function isCheckingIccs(board, side, iccs) {
        let applied = null;
        try {
            applied = legal.applyMove(board, { iccs: iccs });
        } catch (error) {
            applied = null;
        }
        if (!applied || !applied.board) {
            return false; // 走不了（那格已经空了/不合法）：不当作进攻
        }
        try {
            return typeof rulesModule.isInCheck === "function" && rulesModule.isInCheck(applied.board, side !== "w");
        } catch (error) {
            return false;
        }
    }

    /**
     * 计划重复不等于规则违规：一次将军夹着退车也会重复，不能据此认定长将。
     * 已确认的棋谱由 verifiedHistoryFor 传给 Pikafish 判定重复责任。
     * 保留此接口供诊断调用；历史不完整时也不根据计划记录臆造禁手。
     */
    function loopBansFor(board, side) {
        return null;
    }

    /** 只有游戏实际拒绝过的着法进入硬排除表，求胜偏好另行评分比较。 */
    function excludedIccsFor(board, side) {
        return rejectedIccsFor(board, side);
    }

    /** 重复着法只作为软偏好；是否换招由本次候选评分决定，不能用上一手评分担保。 */
    function winSeekRepeatListFor(board, side) {
        if (!driverState.cfg.winSeek || !board) {
            return null;
        }
        let repeats = [];
        let shuttles = [];
        try {
            const moves = legal.allLegalMoves(board, side);
            repeats = winSeekModule.repeatMoves(
                moves,
                board,
                side,
                driverState.winSeekSeen,
                {
                    minSeen: driverState.cfg.winSeekMinSeen
                }
            );
            if (driverState.winSeekShuttles && side === driverState.machine.mySide()) {
                shuttles = driverState.winSeekShuttles.moves(moves, board);
                shuttles.forEach(function (item) {
                    if (!repeats.some(function (repeat) { return repeat.iccs === item.iccs; })) repeats.push(item);
                });
            }
        } catch (error) {
            repeats = [];
        }
        if (!repeats.length) {
            return null;
        }
        const list = [];
        for (let i = 0; i < repeats.length; i++) {
            list.push(repeats[i].iccs);
        }
        driverState.stats.winSeekHits += 1;
        const at = driverState.nowMono();
        if (at - driverState.lastWinSeekNoteAt >= 15000) {
            driverState.lastWinSeekNoteAt = at;
            driverActions.note("求胜模式：重复候选 " + list.join("/") +
                (shuttles.length ? "（已确认同子连续往返）" : "") +
                "，只在本次评分接近时换招，不硬排除");
        }
        return list;
    }

    /**
     * ★ 求胜模式——「避开兑子」那一半（**不硬排除**，只当偏好）：
     *   不落后且有等价兑子候选时，返回这些着法的 iccs 列表 —— 算棋时开多路（multiPv），
     *   若引擎**首选**是兑子就在候选里挑非兑子替代（见 win-seek.preferNonSwap），
     *   差太多/没有替代就照走兑子。**绝不强制亏棋**（真机教训见 cfg.winSeekSwapSlack 注释）。
     */
    function winSeekSwapListFor(board, side) {
        if (!driverState.cfg.winSeek || !board) {
            return null;
        }
        let swaps = [];
        try {
            const moves = legal.allLegalMoves(board, side);
            swaps = winSeekModule.swapMoves(moves, board, side, {}).concat(
                winSeekModule.quietTradeMoves(moves, board, side, {}));
        } catch (error) {
            swaps = [];
        }
        if (!swaps.length) {
            return null;
        }
        const list = [];
        for (let i = 0; i < swaps.length; i++) {
            list.push(swaps[i].iccs);
        }
        driverState.stats.winSeekSwapBans += list.length;
        const at = driverState.nowMono();
        if (at - driverState.lastWinSeekNoteAt >= 15000) {
            driverState.lastWinSeekNoteAt = at;
            driverActions.note(
                "求胜模式：这个局面评分 " +
                    "待本次搜索核验" +
                    "，有 " +
                    list.length +
                    " 手等价兑子候选（" +
                    list.join("/") +
                    "）—— 开多路，优先挑非兑子（差 ≤ " +
                    driverState.cfg.winSeekSwapSlack +
                    "cp 才换，否则照走）"
            );
        }
        return list;
    }

    return {
        positionKeyOfBoard: positionKeyOfBoard,
        positionKeyOfTicket: positionKeyOfTicket,
        markMoveRejected: markMoveRejected,
        rejectedIccsFor: rejectedIccsFor,
        isMateClaim: isMateClaim,
        noteWinSeekPosition: noteWinSeekPosition,
        recordPlanDecision: recordPlanDecision,
        isCheckingIccs: isCheckingIccs,
        loopBansFor: loopBansFor,
        excludedIccsFor: excludedIccsFor,
        winSeekRepeatListFor: winSeekRepeatListFor,
        winSeekSwapListFor: winSeekSwapListFor
    };
}

module.exports = { create: create };
