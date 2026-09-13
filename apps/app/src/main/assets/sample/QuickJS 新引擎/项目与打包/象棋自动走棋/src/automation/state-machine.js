// @engine quickjs
/**
 * 走棋状态机（任务书 11 / 12.3）。
 *
 * 它只做决策，不做任何 I/O：喂它事件（用户操作、观测、算棋结果、点击结果、确认结果），
 * 它吐状态 + 一个**动作清单**（effects）。真正的截图/算棋/点击由 main.js 里的驱动执行。
 * 这样「什么时候允许出手」是纯逻辑，PC 上 `node tools/self-check.js` 就能验收
 * （S01 轮次未知超时不出手 / S02 思考中棋盘变了作废旧着法 / S03 暂停后结果到达不点 /
 *  S04 同一票据只执行一次）。
 *
 * 状态（11.1）：
 *   STOPPED → INITIALIZING → CALIBRATING → WAIT_STABLE → WAIT_TURN → THINKING
 *           → PRECHECK → CLICKING → VERIFYING → （WAIT_TURN / RECOVERING / PAUSED）
 *
 * 两条看门狗：
 *   - 轮次未知超时（unknownTurnWatchdogMs）→ 只恢复/等待，**超时不算「轮到我」**（S01）；
 *   - 确认超时（verifyTimeoutMs）→ 停止等待新观测，回到恢复。
 */
"use strict";

const defaults = require("../util/defaults.js");
const ticketModule = require("./move-ticket.js");
const verify = require("./move-verifier.js");

const STATE = {
    STOPPED: "STOPPED",
    INITIALIZING: "INITIALIZING",
    CALIBRATING: "CALIBRATING",
    WAIT_STABLE: "WAIT_STABLE",
    WAIT_TURN: "WAIT_TURN",
    THINKING: "THINKING",
    PRECHECK: "PRECHECK",
    CLICKING: "CLICKING",
    VERIFYING: "VERIFYING",
    RECOVERING: "RECOVERING",
    PAUSED: "PAUSED",
    GAME_OVER: "GAME_OVER",
    ERROR: "ERROR"
};

const EVENT = {
    START: "START",
    READY: "READY",
    GEOMETRY: "GEOMETRY",
    SNAPSHOT: "SNAPSHOT",
    JOB_RESULT: "JOB_RESULT",
    CLICK_RESULT: "CLICK_RESULT",
    VERIFY_RESULT: "VERIFY_RESULT",
    RECOVERED: "RECOVERED",
    TICK: "TICK",
    PAUSE: "PAUSE",
    RESUME: "RESUME",
    STOP: "STOP",
    GAME_OVER: "GAME_OVER",
    ERROR: "ERROR"
};

const DEFAULTS = {
    /** 我方颜色："w" 红 / "b" 黑。轮次明确等于它时才是「轮到我」。 */
    mySide: "w",
    /** 只识别 / 着法建议模式必须置 false：任何输入都不产生点击动作（验收 A05）。 */
    allowClick: true,
    /** 轮次未知多久就放弃等待（ms）——超时只恢复，不作为出手依据。 */
    unknownTurnWatchdogMs: 8000,
    /** 落子确认最多等多久（ms）。 */
    verifyTimeoutMs: 2500,
    /** 确认「没落子」后允许重试的次数（默认 1 次，够用且不会盲点）。 */
    retryLimit: 1,
    /** 票据有效期（ms，转发给 move-ticket）。 */
    ticketTtlMs: 15000,
    /** 算棋时间预算（ms）。 */
    movetimeMs: 1200
};

function opposite(side) {
    return side === "b" ? "w" : "b";
}

function createMachine(config, deps) {
    const cfg = defaults.mergeDefaults(DEFAULTS, config);
    const injected = deps || {};
    const nowMono = injected.nowMono || function () { return Date.now(); };
    const makeTicket = injected.createTicket || ticketModule.create;

    let state = STATE.STOPPED;
    let reason = "";
    let mySide = cfg.mySide;

    const versions = { sessionId: 1, positionVersion: 0, modelVersion: "", geometryVersion: 0 };
    const counters = {
        clicks: 0,
        ticketsCreated: 0,
        ticketsRefused: 0,
        jobsStarted: 0,
        jobsCancelled: 0,
        verifies: 0,
        confirms: 0,
        unchanged: 0,
        watchdogTrips: 0,
        retries: 0,
        effects: 0
    };
    const executedTickets = {};   // actionId -> true（S04：同一票据只执行一次）

    let geometryTrusted = false;
    let stable = null;            // { board, side, fen, unknownCells, atMono }
    let unknownSinceMono = null;
    let currentJob = null;        // { jobId, board, side, fen, positionVersion, geometryVersion, startedAtMono }
    let currentTicket = null;
    let verifyDeadlineMono = 0;
    let retriesUsed = 0;
    let history = [];

    function setState(next, why) {
        if (state !== next) {
            history.push({ atMono: nowMono(), from: state, to: next, reason: why || "" });
        }
        state = next;
        reason = why || "";
    }

    function remember(event, extra) {
        history.push(Object.assign({ atMono: nowMono(), event: event }, extra || {}));
        if (history.length > 200) {
            history = history.slice(-120);
        }
    }

    function effectsOut(effects, why) {
        counters.effects += effects.length;
        history.push({ atMono: nowMono(), to: state, reason: why || "", effects: effects.map(function (e) { return e.type; }) });
        return { state: state, reason: why || "", effects: effects };
    }

    /** 允许出手的完整核对（11.2）。驱动在执行前会再调用一次；自检也直接调它。 */
    function canClick(ticket) {
        if (!cfg.allowClick) {
            return { ok: false, reason: "当前模式禁止点击（只识别 / 着法建议）" };
        }
        if (state === STATE.PAUSED) {
            return { ok: false, reason: "已暂停" };
        }
        if (state !== STATE.PRECHECK && state !== STATE.CLICKING) {
            return { ok: false, reason: "当前状态是 " + state + "，不是出手前的核对状态" };
        }
        // 会话（换局/重启）先查：它跟观测无关，原因也最明确
        if (ticket.sessionId !== versions.sessionId) {
            return { ok: false, reason: "会话已变（换局/重启）：票据 " + ticket.sessionId +
                " / 当前 " + versions.sessionId };
        }
        if (!geometryTrusted) {
            return { ok: false, reason: "棋盘几何不可信（需要重新定位）" };
        }
        if (!stable) {
            return { ok: false, reason: "还没有稳定局面" };
        }
        if (stable.side !== mySide) {
            return { ok: false, reason: "当前轮次不是我方（" + (stable.side || "未知") + "）" };
        }
        if (stable.unknownCells > 0) {
            return { ok: false, reason: "稳定局面里还有 " + stable.unknownCells + " 个未知格" };
        }
        if (executedTickets[ticket.actionId]) {
            return { ok: false, reason: "该票据已经执行过（只执行一次）" };
        }
        return ticketModule.canExecute(ticket, {
            running: state !== STATE.STOPPED,
            paused: state === STATE.PAUSED,
            sessionId: versions.sessionId,
            positionVersion: versions.positionVersion,
            boardFen: stable.fen,
            side: stable.side,
            modelVersion: versions.modelVersion,
            geometryVersion: versions.geometryVersion
        }, nowMono());
    }

    function cancelCurrentJob(why) {
        const effects = [];
        if (currentJob) {
            counters.jobsCancelled += 1;
            effects.push({ type: "CANCEL_JOB", jobId: currentJob.jobId, reason: why });
            currentJob = null;
        }
        if (currentTicket) {
            ticketModule.cancel(currentTicket, why, nowMono());
            effects.push({ type: "CANCEL_TICKET", ticket: currentTicket, reason: why });
            currentTicket = null;
        }
        verifyDeadlineMono = 0;
        return effects;
    }

    /** 接受一次稳定观测：局面变了就 bump 局面版本（旧票据随之作废）。 */
    function acceptStable(snapshot) {
        const fen = snapshot.fen || "";
        const changed = !stable || stable.fen !== fen || stable.side !== snapshot.side;
        if (changed) {
            versions.positionVersion += 1;
        }
        stable = {
            board: snapshot.board ? snapshot.board.slice() : null,
            side: snapshot.side || null,
            fen: fen,
            unknownCells: snapshot.unknownCells == null ? 0 : Number(snapshot.unknownCells),
            atMono: nowMono()
        };
        return changed;
    }

    function ticketFor(job, jobResult) {
        return makeTicket({
            jobId: job.jobId,
            sessionId: versions.sessionId,
            basePositionVersion: job.positionVersion,
            baseFen: job.fen,
            baseSide: job.side,
            modelVersion: versions.modelVersion,
            geometryVersion: job.geometryVersion,
            moveICCS: jobResult.iccs,
            move: jobResult.move,
            expectedAfterFen: jobResult.expectedAfterFen,
            ttlMs: cfg.ticketTtlMs,
            score: jobResult.score,
            depth: jobResult.depth,
            source: "local-engine",
            nowMono: nowMono()
        });
    }

    function onSnapshot(event) {
        const snapshot = event.snapshot || event;
        if (!snapshot || snapshot.stable === false) {
            // 不稳定观测：只影响等待状态，绝不改变已发出的动作
            if (state === STATE.VERIFYING) {
                return effectsOut([], "观测不稳定，继续等待确认");
            }
            if (state === STATE.CLICKING) {
                return effectsOut([], "点击期间观测不稳定（以确认阶段的新观测为准）");
            }
            setState(state === STATE.STOPPED ? state : STATE.WAIT_STABLE, "等稳定观测");
            return effectsOut([], reason || "等稳定观测");
        }

        const changed = acceptStable(snapshot);
        const sideKnown = snapshot.side === "w" || snapshot.side === "b";

        if (!sideKnown) {
            if (unknownSinceMono == null) {
                unknownSinceMono = nowMono();
            }
            const waited = nowMono() - unknownSinceMono;
            if (waited > cfg.unknownTurnWatchdogMs) {
                counters.watchdogTrips += 1;
                const effects = cancelCurrentJob("轮次未知超过看门狗 " + cfg.unknownTurnWatchdogMs + "ms");
                setState(STATE.RECOVERING, "轮次未知超时（超时不是出手依据）：只恢复/等待");
                return effectsOut(effects, reason);
            }
            setState(STATE.WAIT_TURN, "轮次未知，继续观察（已等 " + Math.round(waited) + "ms）");
            return effectsOut([], reason);
        }
        unknownSinceMono = null;

        // 不可靠的观测只用来等（几何不可信 / 有未知格 / 有关键冲突）：
        // 拿这种局面去算棋或出手，就是任务书里说的「模糊轮次强行出手」。
        if (!geometryTrusted || snapshot.geometryTrusted === false) {
            setState(STATE.RECOVERING, "棋盘几何不可信，先重新定位");
            return effectsOut([], reason);
        }
        if (snapshot.conflicts > 0) {
            setState(STATE.WAIT_STABLE, "有 " + snapshot.conflicts + " 处关键冲突，继续观察");
            return effectsOut([], reason);
        }
        if (stable.unknownCells > 0) {
            setState(STATE.WAIT_STABLE, "稳定局面里还有 " + stable.unknownCells + " 个未知格，继续观察");
            return effectsOut([], reason);
        }

        if (state === STATE.CLICKING || state === STATE.VERIFYING) {
            // 点击/确认期间的观测交给确认流程（VERIFY_RESULT），这里不改变动作
            return effectsOut([], "点击/确认中，等待确认结果");
        }

        if (snapshot.side !== mySide) {
            if (changed) {
                const effects = cancelCurrentJob("轮次已交给对方");
                setState(STATE.WAIT_TURN, "轮到对方（" + snapshot.side + "）");
                return effectsOut(effects, reason);
            }
            setState(STATE.WAIT_TURN, "轮到对方，等待其落子");
            return effectsOut([], reason);
        }

        // 轮到我方：局面变了才重算（同局面重复观测不该反复算棋）
        if (!changed && (state === STATE.THINKING || state === STATE.PRECHECK)) {
            if (state === STATE.PRECHECK && currentTicket) {
                const gate = canClick(currentTicket);
                if (gate.ok) {
                    executedTickets[currentTicket.actionId] = true;
                    ticketModule.start(currentTicket, nowMono());
                    setState(STATE.CLICKING, "出手条件成立");
                    return effectsOut([{ type: "EXECUTE_TICKET", ticket: currentTicket }], reason);
                }
                ticketModule.cancel(currentTicket, gate.reason, nowMono());
                counters.ticketsRefused += 1;
                currentTicket = null;
                setState(STATE.RECOVERING, "出手前核对不通过：" + gate.reason);
                return effectsOut([], reason);
            }
            return effectsOut([], "同一局面，不重复计算");
        }

        const jobId = "job-" + versions.sessionId + "-" + versions.positionVersion + "-" + nowMono();
        // 重新计算前先把上一个任务/票据收掉（任务书：思考中局面变了，旧着法一律作废）
        const replaced = [];
        if (currentJob) {
            counters.jobsCancelled += 1;
            replaced.push({ type: "CANCEL_JOB", jobId: currentJob.jobId, reason: "局面已变，旧任务作废" });
        }
        if (currentTicket) {
            ticketModule.cancel(currentTicket, "局面已变，旧票据作废", nowMono());
            replaced.push({ type: "CANCEL_TICKET", ticket: currentTicket, reason: "局面已变" });
            currentTicket = null;
        }
        currentJob = {
            jobId: jobId,
            board: stable.board ? stable.board.slice() : null,
            side: stable.side,
            fen: stable.fen,
            positionVersion: versions.positionVersion,
            geometryVersion: versions.geometryVersion,
            startedAtMono: nowMono()
        };
        counters.jobsStarted += 1;
        currentTicket = null;
        setState(STATE.THINKING, "轮到我方，开始算棋");
        replaced.push({
            type: "COMPUTE",
            jobId: jobId,
            board: currentJob.board,
            side: currentJob.side,
            fen: currentJob.fen,
            movetimeMs: cfg.movetimeMs
        });
        return effectsOut(replaced, reason);
    }

    function onJobResult(event) {
        const job = currentJob;
        if (!job) {
            return effectsOut([], "没有待处理的算棋任务（结果丢弃）");
        }
        if (event.jobId != null && event.jobId !== job.jobId) {
            return effectsOut([], "结果的 jobId 不是当前任务（过期结果丢弃）");
        }
        if (state === STATE.PAUSED || state === STATE.STOPPED) {
            // S03：暂停后才返回的结果一律失效，票据也不生成
            counters.jobsCancelled += 1;
            currentJob = null;
            return effectsOut([], "已暂停/停止：结果失效，不生成票据");
        }
        if (state !== STATE.THINKING) {
            return effectsOut([], "当前状态 " + state + " 不接受算棋结果");
        }
        if (!event.ok) {
            currentJob = null;
            setState(STATE.RECOVERING, "算棋失败：" + (event.reason || "未知原因"));
            return effectsOut([], reason);
        }
        // 算完了再核对一次基准：思考期间棋盘变了就作废（S02）
        if (stable && (stable.fen !== job.fen || stable.side !== job.side)) {
            counters.jobsCancelled += 1;
            currentJob = null;
            setState(STATE.RECOVERING, "思考期间局面/轮次变了，着法作废");
            return effectsOut([], reason);
        }
        let ticket;
        try {
            ticket = ticketFor(job, event);
        } catch (error) {
            currentJob = null;
            setState(STATE.RECOVERING, "生成票据失败：" + (error && error.message ? error.message : error));
            return effectsOut([], reason);
        }
        counters.ticketsCreated += 1;
        currentJob = null;
        currentTicket = ticket;
        setState(STATE.PRECHECK, "拿到候选着法 " + ticket.moveICCS + "，等待出手前核对");
        return effectsOut([], reason);
    }

    function onTick(event) {
        const now = event && event.nowMono != null ? Number(event.nowMono) : nowMono();
        const effects = [];
        if (state === STATE.WAIT_TURN && unknownSinceMono != null &&
            now - unknownSinceMono > cfg.unknownTurnWatchdogMs) {
            counters.watchdogTrips += 1;
            effects.push.apply(effects, cancelCurrentJob("轮次未知超时"));
            setState(STATE.RECOVERING, "轮次未知超时（看门狗）：只恢复/等待，不点击");
            return effectsOut(effects, reason);
        }
        if (state === STATE.VERIFYING && verifyDeadlineMono > 0 && now > verifyDeadlineMono) {
            verifyDeadlineMono = 0;
            counters.unchanged += 1;
            const effects2 = cancelCurrentJob("落子确认超时");
            setState(STATE.RECOVERING, "落子确认超时，未确认成功（不重复盲点）");
            return effectsOut(effects2, reason);
        }
        return effectsOut([], "没有到期事项");
    }

    function onClickResult(event) {
        const ticket = event.ticket || currentTicket;
        counters.clicks += event.clickCount == null ? 0 : Number(event.clickCount);
        if (event.status === "SENT") {
            verifyDeadlineMono = nowMono() + cfg.verifyTimeoutMs;
            setState(STATE.VERIFYING, "点击已发出，等待新观测确认");
            return effectsOut([{ type: "VERIFY", ticket: ticket, deadlineMono: verifyDeadlineMono }], reason);
        }
        if (event.status === "CANCELLED") {
            if (ticket) {
                ticketModule.cancel(ticket, event.reason || "点击期间被取消", nowMono());
            }
            if (currentTicket === ticket) {
                currentTicket = null;
            }
            setState(STATE.RECOVERING, "点击被取消：" + (event.reason || ""));
            return effectsOut([], reason);
        }
        // FAILED / REJECTED
        if (ticket) {
            ticketModule.cancel(ticket, event.reason || "点击失败", nowMono());
        }
        if (currentTicket === ticket) {
            currentTicket = null;
        }
        setState(STATE.RECOVERING, "点击没发出去：" + (event.reason || ""));
        return effectsOut([], reason);
    }

    function onVerifyResult(event) {
        const ticket = event.ticket || currentTicket;
        const verdict = event.verdict;
        counters.verifies += 1;
        verifyDeadlineMono = 0;
        if (!ticket) {
            setState(STATE.RECOVERING, "确认结果没有对应票据");
            return effectsOut([], reason);
        }
        if (verdict.result === verify.RESULT.CONFIRMED || verdict.result === verify.RESULT.OPPONENT_REPLY) {
            counters.confirms += 1;
            retriesUsed = 0;
            ticketModule.confirm(ticket, { reason: verdict.reason }, nowMono());
            currentTicket = null;
            // 落子已确认 → 局面真的变了：丢掉旧基准、bump 版本（旧票据全部作废），
            // 重新采集一次稳定观测再继续。新基准只能来自新观测，不凭票据外推。
            versions.positionVersion += 1;
            stable = null;
            unknownSinceMono = null;
            setState(STATE.RECOVERING, "落子已确认（" + verdict.result + "），重新稳定观察");
            return effectsOut([{ type: "RECOVER", reason: verdict.reason }], reason);
        }
        if (verdict.result === verify.RESULT.UNCHANGED) {
            counters.unchanged += 1;
            // 重试的前提（任务书 14）：新鲜可靠局面仍等于基准、确认没落子、
            // 票据未过期、且没有新的对方变化 —— 任何一条不满足就不重试。
            const baseIntact = !!stable && stable.fen === ticket.baseFen &&
                stable.side === ticket.baseSide &&
                versions.positionVersion === ticket.basePositionVersion;
            if (retriesUsed < cfg.retryLimit && baseIntact) {
                retriesUsed += 1;
                counters.retries += 1;
                ticketModule.cancel(ticket, "确认未落子：旧票据作废，准备一次重试", nowMono());
                counters.ticketsCreated += 1;
                currentTicket = makeTicket({
                    jobId: ticket.jobId,
                    sessionId: versions.sessionId,
                    basePositionVersion: ticket.basePositionVersion,
                    baseFen: ticket.baseFen,
                    baseSide: ticket.baseSide,
                    modelVersion: ticket.modelVersion,
                    geometryVersion: ticket.geometryVersion,
                    moveICCS: ticket.moveICCS,
                    move: ticket.move,
                    expectedAfterFen: ticket.expectedAfterFen,
                    ttlMs: cfg.ticketTtlMs,
                    score: ticket.score,
                    depth: ticket.depth,
                    source: "retry",
                    nowMono: nowMono()
                });
                setState(STATE.PRECHECK, "确认未落子：生成新票据准备重试（" + retriesUsed + "/" + cfg.retryLimit + "），等新观测核对");
                return effectsOut([], reason);
            }
            ticketModule.cancel(ticket, "确认未落子且不满足重试条件", nowMono());
            currentTicket = null;
            setState(STATE.RECOVERING, "确认未落子，不重复盲点" + (baseIntact ? "" : "（局面已变，重试条件不成立）"));
            return effectsOut([{ type: "RECOVER", reason: verdict.reason }], reason);
        }
        if (verdict.result === verify.RESULT.UNRELIABLE) {
            // 看不清：继续等新观测（deadline 由 TICK 管），不动票据
            verifyDeadlineMono = nowMono() + cfg.verifyTimeoutMs;
            setState(STATE.VERIFYING, "确认观测不可靠（" + verdict.reason + "），继续等待");
            return effectsOut([], reason);
        }
        // UNEXPECTED
        ticketModule.cancel(ticket, "出现无法解释的局面", nowMono());
        currentTicket = null;
        setState(STATE.RECOVERING, "出现无法解释的局面：" + verdict.reason);
        return effectsOut([{ type: "RECOVER", reason: verdict.reason }], reason);
    }

    function dispatch(event) {
        const type = typeof event === "string" ? event : (event || {}).type;
        remember(type, { payload: type === "SNAPSHOT" || type === "TICK" ? undefined : event });
        switch (type) {
            case EVENT.START:
                setState(STATE.INITIALIZING, "开始");
                return effectsOut([{ type: "INIT" }], reason);
            case EVENT.READY:
                versions.modelVersion = event.modelVersion == null ? versions.modelVersion : String(event.modelVersion);
                setState(STATE.CALIBRATING, "基础能力就绪，开始定位棋盘");
                return effectsOut([{ type: "CALIBRATE" }], reason);
            case EVENT.GEOMETRY:
                versions.geometryVersion = event.geometryVersion == null
                    ? versions.geometryVersion : Number(event.geometryVersion);
                geometryTrusted = event.trusted !== false;
                if (!geometryTrusted) {
                    setState(STATE.RECOVERING, "几何不可信：" + (event.reason || ""));
                    return effectsOut([], reason);
                }
                unknownSinceMono = null;
                setState(STATE.WAIT_STABLE, "几何就绪（v" + versions.geometryVersion + "）");
                return effectsOut([], reason);
            case EVENT.SNAPSHOT:
                return onSnapshot(event);
            case EVENT.JOB_RESULT:
                return onJobResult(event);
            case EVENT.CLICK_RESULT:
                return onClickResult(event);
            case EVENT.VERIFY_RESULT:
                return onVerifyResult(event);
            case EVENT.RECOVERED:
                if (state === STATE.PAUSED || state === STATE.STOPPED) {
                    return effectsOut([], "仍是暂停/停止状态");
                }
                setState(STATE.WAIT_STABLE, "恢复完成，重新稳定观察");
                return effectsOut([], reason);
            case EVENT.TICK:
                return onTick(event);
            case EVENT.PAUSE: {
                const effects = cancelCurrentJob("用户暂停");
                setState(STATE.PAUSED, "已暂停（票据与任务都已作废）");
                return effectsOut(effects, reason);
            }
            case EVENT.RESUME:
                if (state !== STATE.PAUSED) {
                    return effectsOut([], "当前不是暂停状态");
                }
                retriesUsed = 0;
                stable = null;
                unknownSinceMono = null;
                setState(STATE.WAIT_STABLE, "继续：重新稳定观察");
                return effectsOut([], reason);
            case EVENT.STOP: {
                const effects = cancelCurrentJob("用户停止");
                setState(STATE.STOPPED, "已停止");
                return effectsOut(effects, reason);
            }
            case EVENT.GAME_OVER: {
                const effects = cancelCurrentJob("对局结束");
                setState(STATE.GAME_OVER, "对局结束");
                return effectsOut(effects, reason);
            }
            case EVENT.ERROR:
                setState(STATE.ERROR, event.reason || "错误");
                return effectsOut([], reason);
            default:
                return effectsOut([], "未知事件：" + type);
        }
    }

    return {
        config: cfg,
        STATE: STATE,
        EVENT: EVENT,
        state: function () { return state; },
        reason: function () { return reason; },
        versions: function () { return Object.assign({}, versions); },
        counters: function () { return Object.assign({}, counters); },
        history: function () { return history.slice(); },
        stable: function () { return stable ? Object.assign({}, stable) : null; },
        currentJob: function () { return currentJob ? Object.assign({}, currentJob) : null; },
        currentTicket: function () { return currentTicket; },
        geometryTrusted: function () { return geometryTrusted; },
        mySide: function () { return mySide; },
        setMySide: function (side) { mySide = side === "b" ? "b" : "w"; },
        setSession: function (id) {
            versions.sessionId = Number(id);
            versions.positionVersion = 0;
            stable = null;
            unknownSinceMono = null;
            return versions.sessionId;
        },
        canClick: canClick,
        dispatch: dispatch
    };
}

module.exports = {
    STATE: STATE,
    EVENT: EVENT,
    DEFAULTS: DEFAULTS,
    opposite: opposite,
    createMachine: createMachine
};
