// @engine quickjs
/**
 * 尝试预算（「同着法 / 同局面」重试上限的计数与拦截）—— 从 auto-driver 抽出的纯簿记模块。
 *
 * 语义（原实现直搬，行为不变）：
 *   · 键 = `会话|基准 FEN|该走棋方`（+ 着法）；用**票据里记的基准**而不是当前观测 ——
 *     恢复重新定位后键不变，否则靠恢复就能无限重置预算；
 *   · `take(ticket)` 计数 +1 → 超过 `limitPerMove` / `limitPerPosition` 时 `over=true`
 *     并给人话原因；计数点应放在「真的把点击发出去之前」的最后一道闸（原实现放在
 *     canClick 通过之后）；
 *   · `clear(why)` **只在确认真实合法变化（落子/应手）后**调用：恢复成功/新票据/换几何
 *     都不算取得进展，故意不清零。
 */
"use strict";

/**
 * @param options { limitPerMove?, limitPerPosition?, note? }
 */
function createAttemptBudget(options) {
    const opts = options || {};
    const limitPerMove = opts.limitPerMove == null ? 3 : Number(opts.limitPerMove);
    const limitPerPosition = opts.limitPerPosition == null ? 5 : Number(opts.limitPerPosition);
    const note = typeof opts.note === "function" ? opts.note : function () { };

    const byMove = {};       // moveKey -> count
    const byPosition = {};   // positionKey -> count

    /** 「会话|基准 FEN|该走棋方」—— 位置键（用票据里记的基准做键）。 */
    function positionKeyOf(ticket) {
        const session = ticket && ticket.sessionId != null ? ticket.sessionId : "?";
        const fen = ticket && ticket.baseFen ? ticket.baseFen : "?";
        const side = ticket && ticket.baseSide ? ticket.baseSide : "?";
        return session + "|" + fen + "|" + side;
    }

    /**
     * 取本次尝试的预算（计数 +1）→ { over, reason, moveCount, positionCount }。
     */
    function take(ticket) {
        const positionKey = positionKeyOf(ticket);
        const moveKey = positionKey + "|" + (ticket && ticket.moveICCS ? ticket.moveICCS : "?");
        byMove[moveKey] = (byMove[moveKey] || 0) + 1;
        byPosition[positionKey] = (byPosition[positionKey] || 0) + 1;
        const moveCount = byMove[moveKey];
        const positionCount = byPosition[positionKey];
        if (moveCount > limitPerMove) {
            return {
                over: true, moveCount: moveCount, positionCount: positionCount,
                reason: "同一着法 " + ticket.moveICCS + " 已试 " + moveCount + " 次仍未确认落子"
            };
        }
        if (positionCount > limitPerPosition) {
            return {
                over: true, moveCount: moveCount, positionCount: positionCount,
                reason: "同一局面已出手 " + positionCount + " 次仍未确认落子"
            };
        }
        return { over: false, moveCount: moveCount, positionCount: positionCount, reason: "" };
    }

    /** 确认真实合法变化（落子/应手）后才清空预算。 */
    function clear(why) {
        const moveKeys = Object.keys(byMove);
        const positionKeys = Object.keys(byPosition);
        if (moveKeys.length === 0 && positionKeys.length === 0) {
            return;
        }
        for (let i = 0; i < moveKeys.length; i++) {
            delete byMove[moveKeys[i]];
        }
        for (let i = 0; i < positionKeys.length; i++) {
            delete byPosition[positionKeys[i]];
        }
        note("尝试预算已清零（" + why + "）");
    }

    return {
        take: take,
        clear: clear,
        positionKeyOf: positionKeyOf,
        snapshot: function () {
            return { byMove: Object.assign({}, byMove), byPosition: Object.assign({}, byPosition) };
        }
    };
}

module.exports = { createAttemptBudget: createAttemptBudget };
