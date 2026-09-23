// @engine quickjs
/**
 * PositionTracker（已确认局面的**单点写入者**）—— 任务书 §4/§5 的事实层。
 *
 * 语义（本批 = A3 影子接入，不改变驱动行为）：
 *   · `adopt()`    采纳一个新基准（对方没有着法语义的场合：重建、声明、开局）；
 *                  开局局面 → 账本以标准开局开「完整」段；其余 → 「中途接管（partial）」段。
 *   · `confirmMove()` 收一手**已验证**着法：先过账本的一致性检查（棋规 + 逐格 + 与当前局面连续），
 *                  通过才推进局面并把着法写进账本；被拒则**不推进**（失败不推进版本）。
 *   · `fix()`      基准被按观测原地改正（对账修正/OCR 改正）：局面换代但**不记着法**，
 *                  并把账本降级为 partial（这一段可能不完整）。
 *   · `invalidate()` 作废（对不上账/预算超限/换边/新会话）；`resetLedger=true` 时连账本一起清。
 *   · `snapshot()` 只读快照：局面、版本号、FEN、`positionCommand()`、historyStatus —— UI/引擎/画板
 *                  都只能读快照（单点写入）。
 *
 * 依赖：fen.js（FEN 构造）+ move-ledger.js（账本）。不碰识别/引擎/UI。
 */
"use strict";

const fenModule = require("../chess/fen.js");
const ledgerModule = require("../chess/move-ledger.js");

/**
 * @param options { note?, nowMono?, startFen? }
 */
function createPositionTracker(options) {
    const opts = options || {};
    const note = typeof opts.note === "function" ? opts.note : function () { };
    const nowMono = typeof opts.nowMono === "function" ? opts.nowMono : function () { return Date.now(); };
    const startFen = opts.startFen || fenModule.START_FEN;
    const ledger = ledgerModule.createMoveLedger({ note: note });

    /** { board, side, reason } —— 唯一被写入的当前局面 */
    let position = null;
    /** 单调递增版本（事实换代；跨模块消息都该带它） */
    let version = 0;
    const stats = {
        adopts: 0, confirms: 0, fixes: 0, invalidations: 0, rejects: 0, duplicates: 0,
        lastReason: ""
    };

    function current() {
        return position ? { board: position.board.slice(), side: position.side } : null;
    }

    function fenOf(board, side) {
        try {
            return fenModule.buildFen(board, side);
        } catch (error) {
            return null;
        }
    }

    function isStartBoard(board) {
        try {
            const parts = String(fenModule.buildFen(board, "w") || "").split(" ");
            return parts[0] === startFen.split(" ")[0];
        } catch (error) {
            return false;
        }
    }

    /** 采纳新基准（无着法语义）。 */
    function adopt(entry) {
        const e = entry || {};
        if (!e.board || !e.board.length || !e.side) {
            return { ok: false, reason: "adopt 需要 board 与 side" };
        }
        const board = e.board.slice();
        position = { board: board, side: e.side, reason: e.reason || "adopt" };
        version += 1;
        stats.adopts += 1;
        stats.lastReason = position.reason;
        if (isStartBoard(board)) {
            ledger.begin(startFen, ledger.HISTORY.COMPLETE);
            note("账本：以标准开局开段（历史完整）");
        } else {
            ledger.setPartial(fenOf(board, e.side) || null, position.reason);
        }
        return { ok: true, version: version, historyStatus: ledger.state().historyStatus };
    }

    /**
     * 收一手已验证着法。不合法 / 与验证盘面不符 / 与当前局面不连续 → 拒收（不推进）。
     */
    function confirmMove(entry) {
        const e = entry || {};
        if (!position) {
            stats.rejects += 1;
            return { ok: false, reason: "还没有基准位置，拒收着法：" + (e.iccs || "") };
        }
        const fromBoard = e.fromBoard ? e.fromBoard : position.board;
        // ① 先判「重复确认」：驱动对一张票据会连续两次走完确认块 —— 第二次是同一件事，
        //   不是新手。这类调用**必然**过不了连续性检查（账本已推进），必须先行拦截。
        const lastMove = ledger.state().last;
        if (lastMove && lastMove.iccs === String(e.iccs || "") &&
            lastMove.beforeKey === ledgerModule.cellsKey(fromBoard)) {
            stats.duplicates += 1;
            return { ok: true, duplicate: true, version: version, nextSide: position.side };
        }
        // ② 连续性：着法的「起点盘面」必须与账本当前局面一致。
        // ⚠️ 必须用 cellsKey（null 安全）比较：join("") 会折叠 null，
        //   `a0a1` 这类着法前后两盘拼出来是同一串（回放用例抳到的 2026-09-23 回归）。
        if (position.board.length && fromBoard.length &&
            ledgerModule.cellsKey(fromBoard) !== ledgerModule.cellsKey(position.board)) {
            stats.rejects += 1;
            stats.lastReason = "reject:discontinuous";
            return { ok: false, reason: "与账本当前局面不连续：" + (e.iccs || "") };
        }
        const result = ledger.confirm({
            iccs: e.iccs,
            fromBoard: fromBoard,
            side: e.side || position.side,
            afterBoard: e.afterBoard,
            toleranceIndexes: e.toleranceIndexes,
            source: e.source,
            atMono: e.atMono == null ? nowMono() : e.atMono,
            evidence: e.evidence
        });
        if (!result.ok) {
            stats.rejects += 1;
            stats.lastReason = "reject:" + result.reason;
            return { ok: false, reason: result.reason, diffIndexes: result.diffIndexes };
        }
        if (result.duplicate) {
            // 同一手的重复确认（驱动对一张票据会连续两次走完确认块）：不重复记账、不推进
            stats.duplicates += 1;
            return {
                ok: true, duplicate: true, version: version, nextSide: position.side,
                appliedBoard: result.appliedBoard
            };
        }
        const mover = e.side || position.side;
        const nextSide = mover === "w" ? "b" : "w";
        position = {
            board: (e.afterBoard && e.afterBoard.length ? e.afterBoard : result.appliedBoard).slice(),
            side: nextSide,
            reason: e.reason || "confirm"
        };
        version += 1;
        stats.confirms += 1;
        stats.lastReason = position.reason;
        return {
            ok: true,
            version: version,
            nextSide: nextSide,
            appliedBoard: result.appliedBoard,
            historyStatus: ledger.state().historyStatus
        };
    }

    /** 基准被原地改正（无着法语义）：换代 + 账本降级 partial。 */
    function fix(entry) {
        const e = entry || {};
        if (!position) {
            return { ok: false, reason: "还没有基准位置" };
        }
        if (!e.board || !e.board.length) {
            return { ok: false, reason: "fix 需要 board" };
        }
        position = { board: e.board.slice(), side: e.side || position.side, reason: e.reason || "fix" };
        version += 1;
        stats.fixes += 1;
        stats.lastReason = position.reason;
        ledger.downgrade(position.reason);
        return { ok: true, version: version, historyStatus: ledger.state().historyStatus };
    }

    /** 作废当前局面；resetLedger=true 时账本也清（换边/新会话）。 */
    function invalidate(reason, options2) {
        position = null;
        version += 1;
        stats.invalidations += 1;
        stats.lastReason = reason || "invalidate";
        if (options2 && options2.resetLedger === true) {
            ledger.clear(reason);
        }
        return { ok: true, version: version };
    }

    /** 只读快照（调用方拿到的都是副本）。 */
    function snapshot() {
        const state = ledger.state();
        return {
            version: version,
            position: current(),
            fen: position ? fenOf(position.board, position.side) : null,
            historyStatus: state.historyStatus,
            positionCommand: ledger.positionCommand(),
            ledger: { baseFen: state.baseFen, ply: state.ply, moves: state.moves }
        };
    }

    return {
        adopt: adopt,
        confirmMove: confirmMove,
        fix: fix,
        invalidate: invalidate,
        snapshot: snapshot,
        ledger: function () { return ledger; },
        stats: function () { return Object.assign({}, stats); }
    };
}

module.exports = { createPositionTracker: createPositionTracker, cellsKey: ledgerModule.cellsKey };
