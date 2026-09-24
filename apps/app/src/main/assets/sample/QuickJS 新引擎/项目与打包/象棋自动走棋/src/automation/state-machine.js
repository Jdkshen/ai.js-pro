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
    /**
     * 只识别 / 着法建议模式专用：着法算出来了、已经报给用户，但**本模式不点击**。
     * 它是一个**稳态**（不是错误、不需要恢复）—— 牌面不变就一直停在这里等局面变化。
     * 为什么要单独一个状态：以前建议模式的着法停在 PRECHECK，下一次观测进入出手核对
     * → `canClick` 因「当前模式禁止点击」拒绝 → 票据作废 + RECOVERING + 重新定位，
     * 把已算好的建议和几何基准一起打散 → 真机症状：② 模式一直在「恢复中/轮次未知」空转。
     */
    SUGGESTED: "SUGGESTED",
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
    /** 用户/自动脚本显式声明「现在轮到我」（基准由驱动写好）——必须能把机器从恢复/等待里推出来 */
    TURN_DECLARED: "TURN_DECLARED",
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
    /**
     * 轮次未知看门狗（ms）：稳定局面里数不齐子力、推不出轮次时，等这么久就进恢复。
     * ⚠️ 2026-09-20：8s → **4s**。恢复本身只是「重新对齐 + 按观测重建基准」（不重定位时 <1s），
     * 而每局能碰到十几次，8s 一挡就是半分钟起步（真机实测每手 ~35s，其中 ~100s 耗在这里）。
     */
    unknownTurnWatchdogMs: 4000,
    /** 落子确认最多等多久（ms）。一次确认要先截图+识别（~1.1s），所以至少留 3 次的机会。 */
    /**
     * 确认超时（ms）：点击发出后这么久还没确认成功就判超时（进恢复，不重复盲点）。
     * ⚠️ 2026-09-20：4s → **2.5s**。确认本身 ~2s/次，4s 常常已经多跑了 2~3 次确认；
     * 2.5s 仍容得下「一两次看不清」。
     */
    verifyTimeoutMs: 2500,
    /**
     * 恢复动作的重试间隔（ms）：进入 RECOVERING 时发一次 RECOVER，之后每隔这么久再发一次。
     * 驱动那边定位失败时会返回 null（「留在 RECOVERING，下一轮再试」），**必须有人再发**，
     * 否则就是一整轮空转（真机踩到：重新定位失败 → 之后 36 次观察全是「只恢复/等待」）。
     * 取值要略大于一次定位耗时（K40 实测 ~8s）。
     */
    /** 恢复动作的重试间隔（ms）：12s → **6s**（同 unknownTurnWatchdogMs 的理由：恢复很便宜，别让它拖时间）。 */
    recoverRetryMs: 6000,
    /**
     * 「轮到对方」等多久后在状态行里点明「对方一直没动」（ms）。
     * ⚠️ 2026-09-20 真机：轮次一旦被**判反**（板面其实在等我方），差分每帧「变化 0 格」，
     * 状态行只会重复「轮到对方，等待其落子」——看日志的人会以为「对方在想棋」。
     * 这条只改文案、不出手（依据不足时绝不点），作用是把这种死等**一眼可见**。
     */
    opponentIdleWarnMs: 8000,   // 2026-09-24：20s→8s（判反要尽早提示；对面电脑应手一般 <5s）
    /**
     * 「轮到对方 + 画面一直没变」到这么久 ⇒ **按轮次判反自动处理**：发 DECLARE_TURN 动作
     * （驱动重立基准 + 翻轮次，与用户点「轮到我」走同一条路）。
     * ⚠️ 2026-09-21 凌晨真机 now62：对方那步其实已经走完（观测都看到了新落的子），但幻影逻辑
     *   把它收编成了「基准修正」⇒ 差分永远变化 0 格 ⇒ 空闲警告从 20s 刷到 163s+ 直到循环到点。
     *   只警告不动作 = 永久死等。翻错的代价有界：游戏会拒绝非我方回合的落子，确认判 UNCHANGED，
     *   「尝试预算」几次内自动暂停（可见）、且棋子不会被点飞——比无限死等强得多。
     * 阈值要明显大于「对方真在想」的合理时长（本地特级大师应手一般 <10s）。
     * ⚠️ 2026-09-24：45s→**15s**（真机事故：判反一次白等 45 秒——对方那步被幻影误判洗掉后
     *   画面静止，用户干等；对面是电脑，15s 无变几乎必是判反。误翻代价仍有界：游戏拒非我方
     *   回合的落子→UNCHANGED→尝试预算几次内自动暂停）。
     */
    opponentIdleDeclareMs: 15000,
    /** 确认「没落子」后允许重试的次数（默认 1 次，够用且不会盲点）。 */
    retryLimit: 1,
    /** 票据有效期（ms，转发给 move-ticket）。 */
    ticketTtlMs: 15000,
    /** 算棋时间预算（ms）。 */
    movetimeMs: 3500
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
    let opponentIdleSinceMono = null;   // 「轮到对方」且画面一直没变的起点（只用于诊断文案）
    let currentJob = null;        // { jobId, board, side, fen, positionVersion, geometryVersion, startedAtMono }
    let currentTicket = null;
    let verifyDeadlineMono = 0;
    let recoverRetryMono = 0;     // 上次发出 RECOVER 效果的时刻（恢复重试限频用）
    let retriesUsed = 0;
    let history = [];

    function setState(next, why) {
        if (state !== next) {
            history.push({ atMono: nowMono(), from: state, to: next, reason: why || "" });
            if (next === STATE.RECOVERING) {
                // 恢复重试从这个时刻开始计时（只在**真的进入**时重置：
                // 看门狗路径会反复调 setState(RECOVERING)，每帧重置的话重试永远不会发）
                recoverRetryMono = nowMono();
            }
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
            source: "pikafish",
            nowMono: nowMono()
        });
    }

    function onSnapshot(event) {
        const snapshot = event.snapshot || event;
        /**
         * ★★ 暂停/停止/结束是**粘性**状态（2026-09-20 真机 now37 铁证）：
         *   面板的自动循环在暂停后**仍在 tick**、观察也照常往下走，而这里原来没有 PAUSED 闸门 ——
         *   一条「轮次未知」的观测会 `setState(WAIT_TURN)` 把机器**悄悄拽出暂停**，紧接着
         *   「轮到我方」的观测又把它推进 THINKING ⇒ 同一个着法（现场是 e6e9 炮吃将）被反复
         *   重算/重试 20 次，日志里刷「已试 4 次…20 次」，暂停形同虚设、白烧两分半钟。
         *   语义回到本来的设计：暂停只能靠显式 RESUME / START 解除（见 EVENT 表）。
         *   ⚠️ RESUME（主界面「继续」）与 START（面板「开始」重开一轮）都**不要**再 gates —— 它们是
         *     用户的两条出路；这里只闸「观测/回报」这类自动输入。
         */
        if (state === STATE.PAUSED || state === STATE.STOPPED || state === STATE.GAME_OVER) {
            // 观测照旧由驱动记录（lastBoard 等），这里只是不把它当「推进动作」的依据；
            // 票据/任务在进 PAUSED / STOP / GAME_OVER 的那一刻就已经取消了，这里不必再动。
            return effectsOut([], state === STATE.PAUSED
                ? "已暂停：不接受新的观测（等 RESUME）"
                : (state === STATE.STOPPED ? "已停止：不接受新的观测（等 START）" : "对局已结束：不接受新的观测"));
        }
        // ★ 几何版本跟观察帧走（2026-09-21 真机事故）：识别自对齐/钉棋子会让几何版本 +1
        //   （驱动把 result.grid 收编进 grid），但票据的 geometryVersion 是**发票据那一刻**冻结的
        //   ⇒ 之后的新票据也拿着旧版本，点击器一核对 grid.version !== ticket.geometryVersion 就
        //   直接 REJECTED ⇒ 日志「票据几何版本 v1 / 当前 v2」、面板在点、游戏没反应，
        //   一路空转到尝试预算耗尽暂停。
        //   这里只同步**版本号**，不动状态 —— GEOMETRY 事件会把状态打回 WAIT_STABLE，
        //   确认/出手途中不能那么干（那会把在飞的票据/任务全拆掉）。
        if (snapshot.geometryVersion != null) {
            const observedGeometryVersion = Number(snapshot.geometryVersion);
            if (isFinite(observedGeometryVersion) && observedGeometryVersion > 0) {
                versions.geometryVersion = observedGeometryVersion;
            }
        }
        if (state === STATE.RECOVERING) {
            // 恢复中：进入 RECOVERING 的那一刻已经发过一次 RECOVER；但如果那次没成（驱动定位失败会
            // 返回 null「下一轮再试」，或干脆没跑完），**必须有人再发** —— 否则就是整轮空转。
            // 真机现场（2026-09-14，K40）：确认取消后重新定位失败 → 之后 36 次观察全是
            // 「RECOVERING｜只恢复/等待」，90 秒白烧。
            unknownSinceMono = null;
            if (nowMono() - recoverRetryMono >= cfg.recoverRetryMs) {
                recoverRetryMono = nowMono();
                return effectsOut([{ type: "RECOVER", reason: "恢复中重试（还没恢复完）" }],
                    "恢复没进展，重发恢复动作");
            }
            return effectsOut([], reason);
        }
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

        // ★ 点击/确认进行中：观测只喂确认流程 —— 必须在任何看门狗/几何/冲突判断**之前**返回。
        //   真机 2026-09-21 07:42（K40）铁证：点击 g0g3 后确认连刷「装饰容差下 12~13 种应手解释」，
        //   而每条观测的轮次都推不出来 ⇒ 旧顺序里「轮次未知看门狗」先动手：4s 一跳 RECOVERING →
        //   恢复 → 回来仍是同一张票据 ⇒ 2.5s 的「确认超时 → CANCEL_TICKET」永远等不到
        //   （TICK 到时状态总不是 VERIFYING）⇒ 同一手卡死两分半，最后还把落点保护（activeOwnMove）清掉。
        //   和「迟到的点击回报」「暂停粘住」是同一类洞：动作在飞的时候，观测不许改变机器状态。
        if (state === STATE.CLICKING || state === STATE.VERIFYING) {
            return effectsOut([], "点击/确认中，等待确认结果");
        }

        if (!sideKnown) {
            if (!cfg.allowClick) {
                // 只识别 / 着法建议：**不出手就不需要轮次基准** → 不走看门狗、也不进 RECOVERING，
                // 免得把算好的建议、几何基准一起打散（真机症状：② 模式长期「恢复中/轮次未知」空转）。
                setState(STATE.WAIT_TURN, "轮次未知：建议模式只等待（不恢复、不出手）");
                return effectsOut([], reason);
            }
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

        if (snapshot.side !== mySide) {
            if (changed) {
                opponentIdleSinceMono = null;
                const effects = cancelCurrentJob("轮次已交给对方");
                setState(STATE.WAIT_TURN, "轮到对方（" + snapshot.side + "）");
                return effectsOut(effects, reason);
            }
            // 等对方落子：画面**一点没变**地等太久 ⇒ 先怀疑「轮次被判反」（我方才该走）。
            // 只写进状态行（让日志自证），不改动作 —— 出手依据不足时绝不点。
            if (opponentIdleSinceMono == null) {
                opponentIdleSinceMono = nowMono();
            }
            const idle = nowMono() - opponentIdleSinceMono;
            // ★ 升级门槛（见 opponentIdleDeclareMs 的注释）：等得够久就不只警告了 ——
            //   按「轮次判反」处理，发 DECLARE_TURN（驱动重立基准 + 翻轮次，与用户点「轮到我」同路）。
            //   真机 now62：对方已走完但被幻影逻辑收编进基准 ⇒ 163s 死等直到循环到点。
            if (cfg.allowClick && idle > cfg.opponentIdleDeclareMs) {
                opponentIdleSinceMono = nowMono();   // 重新计时：下一轮再等满才再翻（防连翻）
                counters.idleTurnDeclares = (counters.idleTurnDeclares || 0) + 1;
                return effectsOut([{ type: "DECLARE_TURN", reason:
                    "对方 " + Math.round(idle / 1000) + "s 没动且画面没变（多半是轮次判反）→ 重立基准翻轮次" }],
                    reason);
            }
            setState(STATE.WAIT_TURN, idle > cfg.opponentIdleWarnMs
                ? "轮到对方，等待其落子（已 " + Math.round(idle / 1000) + "s 画面没变 —— " +
                  "多半是轮次判反，别当成对方在想棋）"
                : "轮到对方，等待其落子");
            return effectsOut([], reason);
        }
        opponentIdleSinceMono = null;

        // 轮到我方：局面变了才重算（同局面重复观测不该反复算棋）
        if (!changed && (state === STATE.THINKING || state === STATE.PRECHECK || state === STATE.SUGGESTED)) {
            if (state === STATE.SUGGESTED) {
                // 建议模式稳态：局面没变就什么都不做（票据留着，作为「当前建议」的载体）。
                return effectsOut([], "同一局面，建议已给出（" +
                    (currentTicket && currentTicket.moveICCS ? currentTicket.moveICCS : "-") + "）");
            }
            if (state === STATE.PRECHECK && currentTicket) {
                const gate = canClick(currentTicket);
                if (gate.ok) {
                    // ⚠️ 这里**不能**先记 executedTickets、也不能先 ticketModule.start()：
                    // 驱动拿到 EXECUTE_TICKET 后会再调一次 canClick()（防「排队期间局面/轮次变了」），
                    // 而 canClick → ticketModule.canExecute 要求票据还是 NEW。提前动状态就会把自己的
                    // 那一核挡掉——真机症状：卡在「该票据已经执行过 / 票据状态是 STARTED」，③ 永远点不出去。
                    // 防重执行靠的是状态（下面立刻进 CLICKING），那两件事放到点击真的发出后（onClickResult SENT）。
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
        }        if (state === STATE.PAUSED || state === STATE.STOPPED) {
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
        if (!cfg.allowClick) {
            // 只识别 / 着法建议：着法算完就交出去（SUGGEST 效果 = 驱动打一行「建议着法 …」），
            // 停在 SUGGESTED 稳态；**不进 PRECHECK**，也就不会有「出手前核对不通过 → 恢复」那条链。
            setState(STATE.SUGGESTED, "建议着法 " + ticket.moveICCS + "（本模式不点击）");
            return effectsOut([{ type: "SUGGEST", ticket: ticket }], reason);
        }
        setState(STATE.PRECHECK, "拿到候选着法 " + ticket.moveICCS + "，等待出手前核对");
        return effectsOut([], reason);
    }

    function onTick(event) {
        const now = event && event.nowMono != null ? Number(event.nowMono) : nowMono();
        const effects = [];
        if (cfg.allowClick && state === STATE.WAIT_TURN && unknownSinceMono != null &&
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
        /**
         * 迟到的点击回报（在出手中途按下暂停/停止）：PAUSE 已经把票据作废并清了动作，
         * 这里再 setState(VERIFYING/RECOVERING) 就又把状态从暂停里拽出去了 ——
         * 与 onSnapshot 那个洞是同一类，一起堵上。
         */
        if (state === STATE.PAUSED || state === STATE.STOPPED || state === STATE.GAME_OVER) {
            return effectsOut([], "已暂停/停止/结束：迟到的点击回报忽略（票据已作废）");
        }
        counters.clicks += event.clickCount == null ? 0 : Number(event.clickCount);
        if (event.status === "SENT") {
            // 点击真的发出去了，才算「这张票据执行过」（S04：只执行一次）。
            // 提前记（或提前 start()）会把驱动的出手前核对挡掉（见 PRECHECK 分支里的注释）。
            if (ticket) {
                ticketModule.start(ticket, nowMono());
                executedTickets[ticket.actionId] = true;
            }
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
        // 同 onClickResult：暂停/停止之后迟到的确认结果不许把机器推回运行态。
        if (state === STATE.PAUSED || state === STATE.STOPPED || state === STATE.GAME_OVER) {
            return effectsOut([], "已暂停/停止/结束：迟到的确认结果忽略（票据已作废）");
        }
        counters.verifies += 1;
        const previousDeadline = verifyDeadlineMono;
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
            // 落子已确认 → 局面真的变了：丢掉稳定快照、bump 版本（旧票据全部作废），等下一帧观测再继续。
            // ⚠️ **不要**在这里发 RECOVER（旧行为）：RECOVER 会让驱动**重新定位 + 校准** ——
            // 真机实测一次 ~8 秒（K40：校准完成…用时 8221ms），而且 CONFIRMED 与 OPPONENT_REPLY
            // 会各触发一次 ⇒ **一手棋白烧 ~16 秒**（面板上一手一闪「恢复：重新定位棋盘」）。
            // 棋子移动不改变棋盘几何，这里需要的只是「重新观察一次」—— 那就是下一轮 tick 的 SNAPSHOT。
            versions.positionVersion += 1;
            stable = null;
            unknownSinceMono = null;
            setState(STATE.WAIT_STABLE, "落子已确认（" + verdict.result + "），等下一帧观测");
            return effectsOut([], reason);
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
            // 看不清（点完那一帧常常正赶上落子动画/走法提示图层）：**立刻安排下一次确认**，
            // 而不是干等到 deadline 超时。每次确认前都要重新截图+识别（~1.1s），
            // 天然拉开间隔；超时上限仍由 TICK 的 deadline 管。
            const next = previousDeadline > 0 ? previousDeadline : (nowMono() + cfg.verifyTimeoutMs);
            if (nowMono() < next) {
                verifyDeadlineMono = next;
                setState(STATE.VERIFYING, "确认观测不可靠（" + verdict.reason + "），再确认一次");
                return effectsOut([{ type: "VERIFY", ticket: ticket, deadlineMono: next }], reason);
            }
            /**
             * ⚠️ 超时到了之后**必须保留原来那个（已过期的）截止时间**，不能留 0 ——
             * TICK 里那条超时判定写的是 `verifyDeadlineMono > 0 && now > verifyDeadlineMono`，
             * 清成 0 就永远不会判超时；而下一帧的 SNAPSHOT 又会重发一次 VERIFY，
             * `previousDeadline` 变成 0 ⇒ 每次都用 `nowMono() + verifyTimeoutMs` 重新开一个 4 秒
             * ⇒ 「再确认一次」死循环：真机 v4 实测连刷 **181 次**
             * `UNRELIABLE：还有 N 个未知格（且不全是落点格）`、3 分钟不动、恢复 0 次。
             */
            verifyDeadlineMono = next;
            setState(STATE.VERIFYING, "确认观测不可靠（" + verdict.reason + "），等超时判定");
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
                unknownSinceMono = null;   // 恢复完重新计时：否则立刻又被看门狗判成「轮次未知超时」
                setState(STATE.WAIT_STABLE, "恢复完成，重新稳定观察");
                return effectsOut([], reason);
            case EVENT.TURN_DECLARED:
                // 用户/自动脚本显式声明「现在轮到我」（基准已由驱动写好）：把机器放回正常观察。
                // ⚠️ 2026-09-20 真机：声明原来只在 WAIT_TURN 那条路上起作用 —— 一旦已经进了
                //    RECOVERING（轮次未知看门狗），onSnapshot 只会重发 RECOVER，声明再也推不动，
                //    整轮就空转到 stopAfterMs。
                if (state === STATE.PAUSED || state === STATE.STOPPED) {
                    return effectsOut([], "当前是暂停/停止状态：声明无效");
                }
                unknownSinceMono = null;
                setState(STATE.WAIT_STABLE, "已声明轮到我（按当前局面立基准）");
                return effectsOut([], reason);
            case EVENT.TICK:
                return onTick(event);
            case EVENT.PAUSE: {
                const effects = cancelCurrentJob("用户暂停");
                // 带上调用方给的原因（例如「同一着法重试 3 次仍未确认落子」）——
                // 否则界面/日志只能看到笼统的「已暂停」，看不出为什么停。
                setState(STATE.PAUSED, event && event.reason
                    ? "已暂停（" + event.reason + "）"
                    : "已暂停（票据与任务都已作废）");
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
