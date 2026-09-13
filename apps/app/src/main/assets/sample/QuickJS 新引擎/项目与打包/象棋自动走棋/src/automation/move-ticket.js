// @engine quickjs
/**
 * 动作票据（任务书 12.2 / 11.2）。
 *
 * 一张票据 = 一次「打算走这一步」的全部前提：会话、局面版本、方向、模型版本、
 * 几何版本、着法、预期后局面、有效期。**点击前必须重新核对**，任何一项变了就作废 ——
 * 这是「思考中棋盘变了就不许按旧着法出手」（S02）和「同一票据只能执行一次」（S04）
 * 落地的地方。
 *
 * 纯逻辑、不碰宿主接口：PC 上 `node tools/self-check.js` 就能覆盖。
 */
"use strict";

/** 票据生命周期：NEW → STARTED → CONFIRMED / CANCELLED，任何状态都不能回到 NEW。 */
const STATE = {
    NEW: "NEW",
    STARTED: "STARTED",
    CONFIRMED: "CONFIRMED",
    CANCELLED: "CANCELLED"
};

const DEFAULTS = {
    /** 默认有效期：从创建算起 15 秒。超时一律不点击（宁可重新算）。 */
    ttlMs: 15000
};

let sequence = 0;

/** 进程内自增编号（写进日志用；不依赖宿主时间，也不跨会话复用）。 */
function nextActionId(prefix) {
    sequence += 1;
    return (prefix || "act") + "-" + sequence;
}

function requireField(input, name) {
    const value = input[name];
    if (value === undefined || value === null || value === "") {
        throw new Error("票据缺少字段：" + name);
    }
    return value;
}

/**
 * 创建票据。
 *
 * @param input.sessionId            会话编号（换局/重启会 +1）
 * @param input.basePositionVersion  计算时所用局面的版本号
 * @param input.baseFen              计算时的局面 FEN（出手前要一模一样）
 * @param input.baseSide             计算时轮到的一方 "w"（红）/ "b"（黑）
 * @param input.modelVersion         模型版本
 * @param input.geometryVersion      几何版本（棋盘位置/缩放一变就换）
 * @param input.moveICCS             要走的着法（ICCS，如 "b2c2"）；走法对象另用 input.move
 * @param input.move                 着法对象 {fromCol, fromRow, toCol, toRow}
 * @param input.expectedAfterFen     走完之后的局面 FEN（落子确认要比这个）
 * @param input.ttlMs                有效期（默认 15 秒）
 * @param input.source / score / depth  来自哪里、评分、搜索深度（记录用）
 * @param input.nowMono              单调时间（默认用 Date.now）
 */
function create(input) {
    const src = input || {};
    const ttlMs = src.ttlMs == null ? DEFAULTS.ttlMs : Number(src.ttlMs);
    if (!(ttlMs > 0)) {
        throw new Error("票据有效期必须大于 0");
    }
    const createdAtMono = src.nowMono == null ? Date.now() : Number(src.nowMono);
    return {
        actionId: src.actionId == null ? nextActionId("act") : String(src.actionId),
        jobId: src.jobId == null ? null : String(src.jobId),
        sessionId: requireField(src, "sessionId"),
        basePositionVersion: requireField(src, "basePositionVersion"),
        baseFen: requireField(src, "baseFen"),
        baseSide: requireField(src, "baseSide"),
        modelVersion: requireField(src, "modelVersion"),
        geometryVersion: requireField(src, "geometryVersion"),
        moveICCS: requireField(src, "moveICCS"),
        move: src.move || null,
        expectedAfterFen: requireField(src, "expectedAfterFen"),
        createdAtMono: createdAtMono,
        expiresAtMono: createdAtMono + ttlMs,
        source: src.source == null ? "engine" : String(src.source),
        score: src.score == null ? null : Number(src.score),
        depth: src.depth == null ? null : Number(src.depth),
        executionState: STATE.NEW,
        history: [{ atMono: createdAtMono, state: STATE.NEW, reason: "已创建" }]
    };
}

function expired(ticket, nowMono) {
    if (!ticket) {
        return true;
    }
    const now = nowMono == null ? Date.now() : Number(nowMono);
    return now >= ticket.expiresAtMono;
}

/** 已经走过/被取消的票据不能再进点击流程（S04）。 */
function isFinished(ticket) {
    return !!ticket && (ticket.executionState === STATE.CONFIRMED ||
        ticket.executionState === STATE.CANCELLED);
}

function hasStarted(ticket) {
    return !!ticket && ticket.executionState === STATE.STARTED;
}

function record(ticket, state, reason, atMono) {
    ticket.executionState = state;
    ticket.history.push({
        atMono: atMono == null ? Date.now() : Number(atMono),
        state: state,
        reason: reason || ""
    });
}

function start(ticket, nowMono) {
    if (!hasStarted(ticket)) {
        if (ticket.executionState !== STATE.NEW) {
            return { ok: false, reason: "票据不是 NEW（当前 " + ticket.executionState + "），不能开始执行" };
        }
        record(ticket, STATE.STARTED, "开始点击", nowMono);
    }
    return { ok: true, reason: "已开始" };
}

function confirm(ticket, info, nowMono) {
    if (ticket.executionState === STATE.CANCELLED) {
        return { ok: false, reason: "票据已取消，不能再记为确认" };
    }
    record(ticket, STATE.CONFIRMED, (info && info.reason) || "落子已确认", nowMono);
    if (info && info.verifiedFen) {
        ticket.verifiedFen = info.verifiedFen;
    }
    return { ok: true, reason: "已确认" };
}

function cancel(ticket, reason, nowMono) {
    if (!ticket || ticket.executionState === STATE.CANCELLED) {
        return { ok: true, reason: "本来就是取消状态" };
    }
    record(ticket, STATE.CANCELLED, reason || "取消", nowMono);
    return { ok: true, reason: reason || "取消" };
}

/**
 * 出手前的完整核对（任务书 11.2 里属于票据的那几条）。
 *
 * @param ctx.sessionId / positionVersion / boardFen / side / modelVersion / geometryVersion
 * @param ctx.running / ctx.paused  运行与暂停状态（暂停/停止一律不点，S03）
 * @returns { ok, reason }
 */
function canExecute(ticket, ctx, nowMono) {
    const c = ctx || {};
    if (!ticket) {
        return { ok: false, reason: "没有票据" };
    }
    if (!ticket.moveICCS) {
        return { ok: false, reason: "票据没有着法" };
    }
    if (ticket.executionState !== STATE.NEW) {
        return { ok: false, reason: "票据状态是 " + ticket.executionState + "，不能再次执行（同一票据只执行一次）" };
    }
    if (expired(ticket, nowMono)) {
        return { ok: false, reason: "票据已过期（超过 " + (ticket.expiresAtMono - ticket.createdAtMono) + "ms）" };
    }
    if (c.running === false) {
        return { ok: false, reason: "自动模式没有在运行" };
    }
    if (c.paused === true) {
        return { ok: false, reason: "已暂停" };
    }
    if (c.sessionId != null && c.sessionId !== ticket.sessionId) {
        return { ok: false, reason: "会话已变（换局或重启）：票据 " + ticket.sessionId + " / 当前 " + c.sessionId };
    }
    if (c.modelVersion != null && c.modelVersion !== ticket.modelVersion) {
        return { ok: false, reason: "模型版本已变：票据 " + ticket.modelVersion + " / 当前 " + c.modelVersion };
    }
    if (c.geometryVersion != null && c.geometryVersion !== ticket.geometryVersion) {
        return { ok: false, reason: "几何版本已变（棋盘移动/缩放）：票据 " + ticket.geometryVersion +
            " / 当前 " + c.geometryVersion };
    }
    if (c.positionVersion != null && c.positionVersion !== ticket.basePositionVersion) {
        return { ok: false, reason: "局面版本已变：票据 " + ticket.basePositionVersion +
            " / 当前 " + c.positionVersion };
    }
    if (c.boardFen != null && c.boardFen !== ticket.baseFen) {
        return { ok: false, reason: "当前局面与计算基准不一致（思考期间棋盘变了）" };
    }
    if (c.side != null && c.side !== ticket.baseSide) {
        return { ok: false, reason: "轮次已变：票据要求 " + ticket.baseSide + "，当前 " + c.side };
    }
    return { ok: true, reason: "可以执行" };
}

/** 一行摘要（日志/界面）。 */
function describe(ticket) {
    if (!ticket) {
        return "(无票据)";
    }
    return ticket.actionId + " " + ticket.moveICCS +
        " base=" + shortFen(ticket.baseFen) +
        " → " + shortFen(ticket.expectedAfterFen) +
        " [" + ticket.executionState + "]" +
        (ticket.score == null ? "" : " score=" + Math.round(ticket.score));
}

function shortFen(fen) {
    const text = String(fen == null ? "" : fen);
    const first = text.split(" ")[0];
    return first.length > 18 ? first.slice(0, 18) + "…" : first;
}

module.exports = {
    STATE: STATE,
    DEFAULTS: DEFAULTS,
    create: create,
    start: start,
    confirm: confirm,
    cancel: cancel,
    canExecute: canExecute,
    isFinished: isFinished,
    hasStarted: hasStarted,
    expired: expired,
    describe: describe,
    nextActionId: nextActionId
};
