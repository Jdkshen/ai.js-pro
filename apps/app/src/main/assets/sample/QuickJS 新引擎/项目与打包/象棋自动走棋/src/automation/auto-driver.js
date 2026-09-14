// @engine quickjs
/**
 * 自动走棋的驱动：把状态机吐出的**动作清单**变成真的截图 / 算棋 / 点击 / 确认。
 *
 * 分工：
 *   状态机（state-machine.js）  —— 什么时候允许出手（纯逻辑）
 *   本文件                      —— 把动作清单执行掉，并把结果事件喂回去
 *   适配层（aijs-adapter.js）   —— 唯一碰宿主接口的地方
 *
 * 一轮（tick）做这些事：
 *   截图 + 识别（复用已校准几何）→ 推轮次 → 喂 SNAPSHOT → 按机器给的动作干活
 *
 * 轮次怎么来的：截图里没有「轮到谁」，只有棋盘布局。所以：
 *   1) 用户按「轮到我」显式声明一次（最可靠，界面上有按钮）；
 *   2) 之后用 transition.infer 从「上一可靠局面 + 新观测」推：唯一合法单步 → 轮到下一方；
 *      解释不了 → 返回 unknown，交给状态机等待/恢复（**超时不算轮到我**）。
 */
"use strict";

const machineModule = require("./state-machine.js");
const verifier = require("./move-verifier.js");
const executorModule = require("./click-executor.js");
const searchWorkerModule = require("./search-worker.js");
const transition = require("../chess/transition.js");
const fenModule = require("../chess/fen.js");
const legal = require("../chess/legal-moves.js");
const rulesModule = require("../chess/rules.js");
const defaults = require("../util/defaults.js");
const logRing = require("../diagnostics/log-ring.js");

const DEFAULTS = {
    /** 每轮观察间隔（ms）。识别一轮约 200ms，留出余量给界面。 */
    tickMs: 900,
    /**
     * 每次观察抓几帧做合并（默认 2）。两帧一致的格子才采信，不一致的降级为未知格 ——
     * 模型单帧会有多处孤立误读（真机：一帧内 3~4 处），不合并就会把假盘面喂给轮次推断。
     */
    observeFrames: 2,
    /** 多帧之间的间隔（ms）：留一点时间让游戏动画/装饰变化，避免两帧拿到同一个坏帧。 */
    observeGapMs: 260,
    /** 模型版本号（写进票据；换模型要让旧票据失效）。 */
    modelVersion: "chessai_160x320_v5r",
    /** 算棋时间预算（ms）。 */
    movetimeMs: 1200,
    /**
     * 算棋跑在哪里："worker" = 独立引擎线程（默认，不阻塞悬浮窗）；
     * "inline" = 老路径（本线程同步算，仅用于对照/宿主没有 threads 时）。
     * "auto" = 有 threads 用 worker，否则 inline。
     */
    computeMode: "worker",
    /** worker 算棋的结果超时（ms）：算不出来也要有结论，别把状态机永远卡在 THINKING。 */
    computeGraceMs: 4000,
    /** 检测置信度。 */
    confidence: 0.25,
    /** 棋盘方向。 */
    orientation: "RED_BOTTOM",
    /** 棋盘大致范围（占屏幕比例 {x, y, width, height}）；null = 用 locate-board 的默认值。 */
    regionRatio: null,
    /** 每隔多少轮强制重新定位一次（0 = 只在恢复时定位）。 */
    relocateEveryTicks: 0,
    /**
     * 同一「可靠局面 + 同一着法」最多完整尝试几次（含第一次）。
     * 真机教训：点击明明发出、棋子却不落时，旧逻辑会一直重算同一手、一直重点
     * （换新票据/恢复成功都不算取得进展）→ 一整轮就烧在同一个局面上。
     */
    attemptLimitPerMove: 3,
    /** 同一可靠局面（不区分着法）总共最多尝试几次，防止不断换着法绕过上面的上限。 */
    attemptLimitPerPosition: 5
};

function createDriver(options) {
    const opts = options || {};
    const cfg = defaults.mergeDefaults(DEFAULTS, opts.config);
    const log = opts.log || function () { };
    const nowMono = opts.nowMono || function () { return Date.now(); };
    const clock = opts.sleep || function (ms) {
        if (typeof sleep === "function") {
            sleep(ms);
        }
    };

    if (typeof opts.recognize !== "function") {
        throw new Error("驱动需要注入 recognize(options)（真机用 recognize-board.recognizeScreen）");
    }
    if (typeof opts.locate !== "function") {
        throw new Error("驱动需要注入 locate()（真机用 locate-board.locateBoardOnScreen）");
    }
    if (typeof opts.analyze !== "function") {
        throw new Error("驱动需要注入 analyze(board, side, options)（真机用 engine.analyze）");
    }

    const clickAllowed = opts.allowClick !== false;
    const disabledTap = executorModule.createDisabledTap("只识别 / 着法建议模式：不会点击棋盘");
    const executor = opts.executor || executorModule.createExecutor({
        tap: clickAllowed ? opts.tap : disabledTap.tap,
        nowMono: nowMono,
        waitMs: function (ms) { clock(ms); },
        config: opts.executorConfig
    });
    if (!clickAllowed && !opts.executor) {
        // 只识别/建议模式一律用禁用桩：调用会被计数并报错，验收 A05 查的就是这个计数
        executor.tapIsDisabled = true;
    }
    const machine = opts.machine || machineModule.createMachine({
        mySide: opts.mySide === "b" ? "b" : "w",
        allowClick: clickAllowed,
        movetimeMs: cfg.movetimeMs,
        ticketTtlMs: opts.ticketTtlMs
    }, { nowMono: nowMono });

    const stats = {
        ticks: 0,
        observations: 0,
        unrecognized: 0,
        stableObservations: 0,
        unknownTurn: 0,
        clicks: 0,
        confirms: 0,
        errors: 0,
        lastError: "",
        lastObserveMs: 0,
        /** 算棋去重：交给 worker 线程的次数 / 退回本线程同步算的次数 */
        thinkOffThread: 0,
        thinkInline: 0,
        /** 最近一次「状态机发 COMPUTE → 拿到结果」的总耗时（ms），含排队与轮询延迟 */
        thinkMs: 0,
        /** ② 模式给出的建议着法次数（不点击） */
        suggestions: 0
    };
    /** 落点格按票据补齐的次数（游戏给刚落的子加高亮，那一格认不出来） */
    stats.ownMovePatched = 0;
    /** 其中「同字异色（红黑读反）」被按已知事实补齐的次数（见 observeOnce 第 ③ 支） */
    stats.sameGlyphPatched = 0;
    stats.knownFactFills = 0;
    /** 颜色读反被纠正的格子数（游戏装饰导致的已知模型弱点） */
    stats.colorFlips = 0;
    /** 多帧合并：做过合并的观察次数 / 因两帧不一致而降级的格子数 */
    stats.mergedFrames = 0;
    stats.frameConflicts = 0;
    stats.sideFixes = 0;          // 「盘面按着法改正」次数（落点格被装饰读错时）
    stats.budgetPauses = 0;       // 因「同局面/同手重试超限」暂停的次数
    stats.sourceGoneRefusals = 0; // 重试前发现「起点子已不在原位」而拒发的次数（§11）
    stats.rawCorrected = 0;       // 「模型原始读数和最终盘面不一致」的观测次数（§12）
    let lastRawCorrectedText = "";
    /** 冲突明细去重用的上一次文本（限频：同一局面不重复刷） */
    let lastConflictText = "";
    // 日志环：超上限**原地裁剪**（以前的 `const history` + `history = history.slice()` 会抛异常）
    const history = logRing.createLogRing(300, 200);

    let running = false;
    let grid = null;
    let lastReliable = null;      // { board, side }：上一可靠局面与「该走棋方」
    let lastResult = null;        // 最近一次识别结果（界面/诊断用）
    let lastBoard = null;
    /** 最近一次观测里**模型读到的原始盘面**（补丁/补齐之前）——诊断与审核用（§12） */
    let lastRawChars = null;
    /** 本次观测里，轮次推断给出的「按着法改正过的盘面」（仅当确实改动了观测时才存在） */
    let lastSideFix = null;
    /**
     * 尝试预算（见 DEFAULTS.attemptLimitPerMove 的说明）。
     * 键 = 会话|基准 FEN|该走棋方（+ 着法）；**故意不在恢复成功/新票据/换几何时清零** ——
     * 那些都不算取得进展，只有在确认真实合法变化（落子/应手）后才清空。
     */
    const attemptsByMove = {};
    const attemptsByPosition = {};
    /**
     * 容差解释的跨帧证据（§12）：装饰容差给的解释必须**连续两帧一致**才采信。
     * 为什么：两步解释即使唯一，也只是「在给定基准和候选集合内唯一」；模型可能持续把同一格
     * 读错，单帧就据此行动等于拿噪声当事实。冲突（换了另一种解释）则重新计数、继续等。
     */
    let toleranceEvidence = { key: "", count: 0 };
    /** 上次重新定位的单调时间 + 最小间隔（见 recover 的限频说明：定位一次 ~8s，而棋子移动不改变几何） */
    let lastRelocateMono = 0;
    const RELOCATE_MIN_INTERVAL_MS = 30000;
    /** 最近一次观测的**每格实测框中心**（点棋用；见 click-executor.pointOf 的说明） */
    let lastCenters = null;
    let lastCentersVersion = null;

    // ---- 算棋放在哪跑 ----
    // worker：独立 QuickJS 引擎（自己的线程），主线程只负责「起任务 + 每轮 poll 一次」；
    // inline：老路径（本线程同步算，会挡触控），只在宿主没有 threads 或显式要求对照时用。
    const projectDir = opts.projectDir || (typeof __dirname === "string" ? __dirname + "/../.." : ".");
    const workerSearch = searchWorkerModule.create({
        dir: projectDir,
        graceMs: cfg.computeGraceMs,
        nowMono: nowMono,
        log: function (text) { note(text); }
    });
    const computeMode = cfg.computeMode === "inline" || !searchWorkerModule.available()
        ? "inline"
        : "worker";
    let pendingCompute = null;    // { jobId, effect, startedAtMono }：正在 worker 里算的任务
    /**
     * 「游戏画花了的格子」的已知事实补齐表：[{ index, char }]。
     * 游戏会给**刚落的子**加高亮环，那一格会被模型认错（实测：带环的炮读成马），
     * 棋规层再把它降级成 '?'。我方落子与对方应手的子都会被画环，所以是张表。
     * 兵种是**已知事实**（票据 + 唯一合法应手推出来的），不是猜；格子里读到别的子/变空就自动失效。
     */
    let knownPatches = [];
    /**
     * 「手数周期」：每确认成功一次 +1。补齐表里的记录只在**同一个周期内**有效 ——
     * 游戏的高亮环只挂在「刚落的那个子」上，下一手一旦确认，上一手的装饰就不再存在。
     * 真机踩过：不加这个界限，上一手的记录会一直生效，把「那个子真的被吃了」也覆盖掉。
     */
    let verifyCycle = 0;
    /** 上一句「观察层补齐」的说明文案（只用于去重打日志，免得每秒刷屏） */
    let lastFillText = "";

    function useWorkerSearch() {
        return computeMode === "worker";
    }

    function note(text) {
        history.push({ atMono: nowMono(), text: text });
        log("[driver] " + text);
    }

    // ---- 动作清单的执行 ----

    function handleEffect(effect) {
        switch (effect.type) {
            case "CALIBRATE":
                return calibrate();
            case "COMPUTE":
                return compute(effect);
            case "SUGGEST":
                return onSuggest(effect);
            case "EXECUTE_TICKET":
                return executeTicket(effect);
            case "VERIFY":
                return doVerify(effect);
            case "RECOVER":
                return recover();
            case "INIT":
            case "CANCEL_TICKET":
                note(effect.type + "：" + (effect.reason || ""));
                return null;
            case "CANCEL_JOB":
                // 局面变了/暂停：worker 里的旧任务立刻停掉，别白烧 CPU
                if (workerSearch.isRunning()) {
                    workerSearch.cancel();
                    pendingCompute = null;
                    note("算棋任务已取消（worker 已停）：" + (effect.reason || ""));
                } else {
                    note(effect.type + "：" + (effect.reason || ""));
                }
                return null;
            default:
                return null;
        }
    }

    /** 把一段动作清单按顺序执行完（动作可能又产生新动作，用队列而不是递归）。 */
    function pump(outcome) {
        const queue = [outcome];
        let guard = 0;
        while (queue.length > 0 && guard++ < 24) {
            const current = queue.shift();
            for (let i = 0; i < current.effects.length; i++) {
                let follow = null;
                try {
                    follow = handleEffect(current.effects[i]);
                } catch (error) {
                    stats.errors += 1;
                    stats.lastError = String(error && error.message ? error.message : error);
                    note("动作 " + current.effects[i].type + " 失败：" + stats.lastError);
                    follow = machine.dispatch({ type: machineModule.EVENT.ERROR, reason: stats.lastError });
                }
                if (follow) {
                    queue.push(follow);
                }
            }
        }
        return outcome;
    }

    /**
     * ② 只识别 / 着法建议：状态机算出着法后直接把建议报出来（**不点击**）。
     * 这条日志就是面板「算棋/建议」那一格的来源；也方便 logcat 里对账。
     */
    function onSuggest(effect) {
        const ticket = effect.ticket || {};
        stats.suggestions = (stats.suggestions || 0) + 1;
        note("建议着法 " + ticket.moveICCS + "（② 只给建议，不点击）：评分 " + ticket.score +
            "，深度 " + ticket.depth);
        return null;
    }

    function calibrate() {
        try {
            const located = opts.locate(locateOptions());
            if (!located || !located.ok) {
                note("定位失败：" + (located ? located.reason : "没有结果"));
                return pump(machine.dispatch({ type: "GEOMETRY", trusted: false, reason: located ? located.reason : "" }));
            }
            grid = located.grid;
            lastReliable = null;
            lastRelocateMono = nowMono();   // 定位过就记时间（recover 的限频用它）
            note("棋盘已定位：起点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
                ") 格距 (" + Math.round(grid.stepX) + "," + Math.round(grid.stepY) +
                ") 版本 v" + grid.version);
            return pump(machine.dispatch({
                type: "GEOMETRY",
                geometryVersion: grid.version,
                trusted: opts.isTrustworthy ? opts.isTrustworthy(grid) : true
            }));
        } catch (error) {
            stats.errors += 1;
            stats.lastError = String(error && error.message ? error.message : error);
            note("定位异常：" + stats.lastError);
            return pump(machine.dispatch({ type: "GEOMETRY", trusted: false, reason: stats.lastError }));
        }
    }

    function compute(effect) {
        // 算棋放到独立引擎线程（worker）：主线程不再被 ~1.2s 的搜索占住，
        // 悬浮窗触摸/心跳照常。结果由 pollCompute() 在后续 tick 里非阻塞收回来。
        if (useWorkerSearch()) {
            const started = nowMono();
            if (workerSearch.start(effect.fen || effect.board, {
                side: effect.side,
                movetime: effect.movetimeMs
            })) {
                pendingCompute = {
                    jobId: effect.jobId,
                    effect: effect,
                    startedAtMono: started
                };
                stats.thinkOffThread += 1;
                note("算棋（worker 线程）：轮到 " + (effect.side === "w" ? "红" : "黑") +
                    "，预算 " + effect.movetimeMs + "ms");
                return null;   // 不阻塞：JOB_RESULT 稍后由 pollCompute 喂回去
            }
        }
        return computeInline(effect);
    }

    /** 兜底 / 对照路径：在本线程同步算（会挡住触控，就是被替换掉的那条老路）。 */
    function computeInline(effect) {
        stats.thinkInline += 1;
        let result;
        try {
            result = opts.analyze({ board: effect.board, side: effect.side }, { movetime: effect.movetimeMs });
        } catch (error) {
            result = { ok: false, reason: String(error && error.message ? error.message : error) };
        }
        return finishCompute(effect, {
            ok: !!result.ok,
            reason: result.reason || "",
            iccs: result.iccs,
            move: result.move,
            score: result.score,
            depth: result.depth,
            ms: result.ms
        });
    }

    /**
     * 把一个算棋结果变成 JOB_RESULT 喂回状态机（worker 与同步两条路共用）。
     * @param effect 当时那个 COMPUTE 效果（里面有 board/side，用来算预期后局面）
     * @param result { ok, reason, iccs, move, score, depth, ms }
     */
    function finishCompute(effect, result) {
        const outcome = { jobId: effect.jobId, ok: !!result.ok, reason: result.reason || "" };
        if (result.ok) {
            outcome.iccs = result.iccs;
            outcome.move = result.move;
            outcome.score = result.score;
            outcome.depth = result.depth;
            // 预期后局面：拿引擎给的着法算，不手写 FEN
            const applied = legal.applyMove(effect.board, result.move);
            outcome.expectedAfterFen = fenModule.buildFen(applied.board,
                effect.side === "b" ? "w" : "b");
            note("算棋：轮到 " + (effect.side === "w" ? "红" : "黑") + "，" + result.iccs +
                "（深度 " + result.depth + "，" + result.ms + "ms，评分 " + Math.round(result.score) + "）");
        } else {
            note("算棋失败：" + outcome.reason);
        }
        stats.thinkMs = pendingCompute ? (nowMono() - pendingCompute.startedAtMono) : 0;
        pendingCompute = null;
        return pump(machine.dispatch(Object.assign({ type: "JOB_RESULT" }, outcome)));
    }

    /** worker 里只回传 ICCS：在这里从合法着法表里把着法对象找回来（着法生成比搜索便宜得多）。 */
    function moveFromIccs(effect, iccs) {
        const list = legal.allLegalMoves(effect.board, effect.side);
        for (let i = 0; i < list.length; i++) {
            if (list[i].iccs === iccs) {
                return list[i];
            }
        }
        return null;
    }

    /**
     * 非阻塞收算棋结果（每轮 tick 开头调一次）。
     * @returns null                                  没有在算
     *          { pending: true,  elapsedMs }         还在算（本轮不用再截图/观察）
     *          { pending: false, elapsedMs, status } 已收尾（结果或失败已喂回状态机）
     */
    function pollCompute() {
        if (!pendingCompute || !workerSearch.isRunning()) {
            return null;
        }
        const state = workerSearch.poll();
        if (state.status === "running") {
            return { pending: true, elapsedMs: state.elapsedMs };
        }
        const job = pendingCompute;
        // 收尾期间状态机可能已经离开 THINKING（暂停/新会话/局面变了）：交给 finishCompute 的路由，
        // 由状态机自己判定是否接受（onJobResult 里有 S02/S03 两道门）。
        if (state.status === "done") {
            const result = state.result;
            let move = null;
            if (result.ok && result.iccs) {
                move = moveFromIccs(job.effect, result.iccs);
            }
            if (!result.ok) {
                finishCompute(job.effect, { ok: false, reason: result.reason || "worker 算棋失败" });
            } else if (!move) {
                finishCompute(job.effect, {
                    ok: false,
                    reason: "worker 给的着法 " + result.iccs + " 不在合法着法表里"
                });
            } else {
                finishCompute(job.effect, {
                    ok: true,
                    iccs: result.iccs,
                    move: move,
                    score: result.score,
                    depth: result.depth,
                    ms: result.ms
                });
            }
            return { pending: false, elapsedMs: state.elapsedMs, status: "done" };
        }
        const reason = state.status === "timeout"
            ? ("worker 算棋超时（超过 " + state.elapsedMs + "ms）")
            : ("worker 算棋异常：" + (state.error || ""));
        finishCompute(job.effect, { ok: false, reason: reason });
        return { pending: false, elapsedMs: state.elapsedMs, status: state.status };
    }

    /**
     * 「会话|基准 FEN|该走棋方」—— 尝试预算的位置键。
     * 用**票据里记的基准**（而不是当前观测）做键：恢复重新定位后键不会变，
     * 否则靠恢复就能无限重置预算。
     */
    function positionKeyOf(ticket) {
        const session = ticket && ticket.sessionId != null ? ticket.sessionId : "?";
        const fen = ticket && ticket.baseFen ? ticket.baseFen : "?";
        const side = ticket && ticket.baseSide ? ticket.baseSide : "?";
        return session + "|" + fen + "|" + side;
    }

    /**
     * 取本次尝试的预算（计数 +1）→ { over, reason, moveCount, positionCount }。
     * 计数点放在 canClick 通过之后：只有真的把两次点击发出去才算一次「完整尝试」。
     */
    function takeAttemptBudget(ticket) {
        const positionKey = positionKeyOf(ticket);
        const moveKey = positionKey + "|" + (ticket && ticket.moveICCS ? ticket.moveICCS : "?");
        attemptsByMove[moveKey] = (attemptsByMove[moveKey] || 0) + 1;
        attemptsByPosition[positionKey] = (attemptsByPosition[positionKey] || 0) + 1;
        const moveCount = attemptsByMove[moveKey];
        const positionCount = attemptsByPosition[positionKey];
        if (moveCount > cfg.attemptLimitPerMove) {
            return {
                over: true, moveCount: moveCount, positionCount: positionCount,
                reason: "同一着法 " + ticket.moveICCS + " 已试 " + moveCount + " 次仍未确认落子"
            };
        }
        if (positionCount > cfg.attemptLimitPerPosition) {
            return {
                over: true, moveCount: moveCount, positionCount: positionCount,
                reason: "同一局面已出手 " + positionCount + " 次仍未确认落子"
            };
        }
        return { over: false, moveCount: moveCount, positionCount: positionCount, reason: "" };
    }

    /** 确认真实合法变化（落子/应手）后才清空预算。 */
    function clearAttemptBudget(why) {
        const moveKeys = Object.keys(attemptsByMove);
        const positionKeys = Object.keys(attemptsByPosition);
        if (moveKeys.length === 0 && positionKeys.length === 0) {
            return;
        }
        for (let i = 0; i < moveKeys.length; i++) {
            delete attemptsByMove[moveKeys[i]];
        }
        for (let i = 0; i < positionKeys.length; i++) {
            delete attemptsByPosition[positionKeys[i]];
        }
        note("尝试预算已清零（" + why + "）");
    }

    function executeTicket(effect) {
        const ticket = effect.ticket;
        const gate = machine.canClick(ticket);
        if (!gate.ok) {
            // 到了这一步还被拦下：按「没发出去」处理，绝不点
            note("出手前核对不通过：" + gate.reason);
            return pump(machine.dispatch({
                type: "CLICK_RESULT", ticket: ticket, status: "REJECTED",
                clickCount: 0, reason: gate.reason
            }));
        }
        // ---- §11：重试前必须确认「起点子还在」----
        // 局面已经变了（子被吃/已走/记错了）时再点起点，只会点到空点或对方子，
        // 白烧一次尝试额度（预算就是被这种空转吃掉的）。
        const srcIndex = rulesModule.iccsSquare(ticket.moveICCS, 0);
        const trustedBoard = (lastReliable && lastReliable.board) ? lastReliable.board : lastBoard;
        if (srcIndex != null && trustedBoard) {
            const here = trustedBoard[srcIndex];
            const mine = (machine.mySide() === "w")
                ? rulesModule.isRedPiece(here)
                : rulesModule.isBlackPiece(here);
            if (!mine) {
                stats.sourceGoneRefusals += 1;
                note("重试前核对：起点格(" + ticket.move.fromCol + "," + ticket.move.fromRow + ")现在是 " +
                    (here && here !== "?" ? here : "空/未知") + "，不再是我方子 → **不发点击**（局面已变，重算）");
                return pump(machine.dispatch({
                    type: "CLICK_RESULT", ticket: ticket, status: "REJECTED", clickCount: 0,
                    reason: "起点子已不在原位（局面已变），未发送点击"
                }));
            }
        }
        // ---- 尝试预算（§11：同一局面/同一手不许无限重试）----
        const budget = takeAttemptBudget(ticket);
        if (budget.over) {
            note("同局面落子未确认：" + budget.reason + " → 暂停（不再盲点），请看截图与实际通道");
            note("  预算明细：本手第 " + budget.moveCount + " 次 / 本局面共 " + budget.positionCount +
                " 次（上限 " + cfg.attemptLimitPerMove + " / " + cfg.attemptLimitPerPosition + "）");
            return pump(machine.dispatch({ type: "PAUSE", reason: budget.reason }));
        }
        // ---- 点击前的前台守卫（真机血泪）----
        // 脚本宿主 RunIntentActivity 是**透明窗口**：用 am start 起脚本后它常常仍是「有焦点的前台」，
        // 游戏只是画在它下面。这时注入的点击会被我们自己吃掉 —— 截图一切正常、棋子纹丝不动、
        // 确认阶段永远 UNCHANGED（K40 实测：一手棋连试 4 次全 UNCHANGED，`dumpsys input` 的
        // FocusedApplications 就是本应用）。所以出手前必须确认前台不是自己；是就自动切一次
        // （最近任务快速切换，不重启游戏），切不回来就不点，别把票据白烧掉。
        if (typeof opts.foregroundGuard === "function") {
            let guard = null;
            try {
                guard = opts.foregroundGuard();
            } catch (error) {
                guard = null;
            }
            if (guard && guard.packageChanged) {
                note("前台守卫：点击前前台是" + (guard.ownPackage ? "本应用（脚本宿主）" : (guard.package || "其它")) +
                    " → 已用最近任务切回上一步应用" + (guard.ok ? "" : "（仍不是目标，先不点）"));
            }
            if (guard && guard.ok === false) {
                note("前台不是游戏（当前 " + (guard.package || "未知") + "）：**不发点击**，等下一轮再试");
                return pump(machine.dispatch({
                    type: "CLICK_RESULT", ticket: ticket, status: "CANCELLED",
                    clickCount: 0, reason: "前台不是游戏（" + (guard.package || "未知") + "），未发送点击"
                }));
            }
        }
        // ★ 出手前先作废「这一步起点」的已知事实：棋子从这一刻起就不在那儿了。
        // ⚠️ 必须在**点击之前**丢（而不是等确认成功再丢）：否则确认阶段那几帧里，
        // observeOnce 会把旧子补回起点 → 观测看起来「起点还占着、落点也有了」→
        // 被当成动画帧（UNRELIABLE）等下去，而这条记录永远等不到「确认成功」去作废 → 死锁。
        const fromIndex = transition.squareIndex(ticket.moveICCS, 0);
        if (fromIndex >= 0) {
            dropPatchesAt([fromIndex], "我方要走 " + ticket.moveICCS + "，起点已空");
        }
        const outcome = executor.execute(ticket, {
            grid: grid,
            // 点棋优先用最近一次观测的**实测框中心**（几何版本必须与票据一致，否则退回交点）：
            // 定位的系统性偏移会让「按交点点的击」落在棋子外沿 → 只选中、不落子。
            centers: (lastCenters && lastCentersVersion === ticket.geometryVersion) ? lastCenters : null,
            screen: opts.screen ? opts.screen() : null,
            cancel: function () { return !running || machine.state() === machineModule.STATE.PAUSED; }
        });
        // 已知事实补齐表**不在这里清空**：我方新落点的子会被再画一次环，而对方上一手的子
        // 可能还带着环；那些格子一旦能自己认出来（或变成空格/别的子），observeOnce 会自动
        // 把它从表里去掉，所以留着不会补错。
        note("点击：" + ticket.moveICCS + " → " + outcome.status + "（" + outcome.reason + "）" +
            "，点击 " + outcome.clickCount + " 次");
        if (outcome.clicks && outcome.clicks.length > 0) {
            // 每次手势单独记：真实手势名（原地 swipe≠tap、无障碍 press≠click）、格号、用的是
            // 实测框中心还是网格交点、交点坐标、接口返回、起止时刻 —— 通道对照实验就靠这几行。
            for (let i = 0; i < outcome.clicks.length; i++) {
                const click = outcome.clicks[i];
                note("  手势" + (i + 1) + " " + (click.kind || "?") + "：" + (click.method || "未知手势") +
                    " 格(" + click.col + "," + click.row + ") 屏幕(" + click.x + "," + click.y + ")" +
                    (click.usedCenter ? " 实测框中心" : " 网格交点") +
                    (click.latticeX != null ? "（交点 " + click.latticeX + "," + click.latticeY + "）" : "") +
                    " 几何v" + click.geometryVersion +
                    " ok=" + (click.ok ? "是" : "否") + " 耗时" + click.elapsedMs + "ms" +
                    (click.command ? " 命令「" + click.command + "」" : "") +
                    (click.error ? " 错误：" + click.error : ""));
                if (i > 0) {
                    const previous = outcome.clicks[i - 1];
                    const gap = click.startedAtMono - previous.finishedAtMono;
                    note("  手势间隔 " + gap + "ms" +
                        (gap < 0 ? "⚠️ 第二次手势在前一次完成前就发出了（手势可能被取消）" : ""));
                }
            }
        }
        return pump(machine.dispatch({
            type: "CLICK_RESULT", ticket: ticket, status: outcome.status,
            clickCount: outcome.clickCount, reason: outcome.reason
        }));
    }

    function doVerify(effect) {
        const ticket = effect.ticket;
        // 把票据的「预期落子后局面」当期望传给观察层：用来纠正「同一兵种颜色读反」的装饰格
        let expectedBoard = null;
        try {
            const parsed = fenModule.parseFen(ticket && ticket.expectedAfterFen);
            expectedBoard = parsed && parsed.ok ? parsed.board : null;
        } catch (error) {
            expectedBoard = null;
        }
        const observed = observeOnce({ forVerify: true, expect: expectedBoard });
        if (!observed.ok) {
            return pump(machine.dispatch({
                type: "VERIFY_RESULT", ticket: ticket,
                verdict: { result: verifier.RESULT.UNRELIABLE, reason: observed.reason }
            }));
        }
        // 冲突明细（2026-09-14 真机）：以前只报「有 N 处关键冲突」，看不见是**哪一格**，
        // 48 次卡死的真因（同字异色抖动）只能靠翻视觉层源码猜。现在把格子与两个候选都打出来，
        // 并把「同字异色」单独计数（那类冲突已不再否决整帧，只在这里留痕）。
        (function noteConflicts() {
            const stats = observed.result && observed.result.stats;
            if (!stats) {
                return;
            }
            const cells = stats.conflictCells || [];
            const text = "冲突明细：异字 " + (stats.conflicts || 0) +
                " 处、同字异色 " + (stats.sameGlyphConflicts || 0) + " 处" +
                (cells.length
                    ? "｜" + cells.map(function (item) {
                        return item.name + " " + item.best + "/" + item.second +
                            "(" + item.bestScore + "/" + item.secondScore + ")" +
                            (item.sameGlyph ? "同字" : "异字");
                    }).join(" ")
                    : "");
            if (text !== lastConflictText) {
                lastConflictText = text;
                note(text);
            }
        })();
        const verdict = verifier.verify({
            ticket: ticket,
            observed: observed.chars,
            unknownCells: observed.unknownCells,
            stable: true,
            geometryTrusted: true,
            conflicts: observed.conflicts
        });
        note("确认：" + verifier.describe(verdict));
        // 诊断（量很少，一手棋 2~3 行）：把实际喂给验证器的观测盘面与期望/基准都打出来。
        // 「确认判错」这类问题不看这三串就只能猜（真机踩过：观测其实是「我方落子+对方应手」，
        // 但合并/补齐改过的格子没记录下来，事后无法复盘）。
        if (!verifier.isSuccess(verdict.result)) {
            note("  确认观测：" + boardText(observed.chars) +
                "｜期望：" + (ticket.expectedAfterFen || "-") +
                "｜基准：" + (ticket.baseFen || "-"));
        }
        if (verifier.isSuccess(verdict.result)) {
            stats.confirms += 1;
            verifyCycle += 1;
            // ★ 我方这一步**真的走了** → 起点那一格从此是空的，它在补齐表里的记录必须作废。
            // ⚠️ 以前这里是「按周期把**整张表**作废」（`patch.cycle !== verifyCycle`）——
            // 真机事故（2026-09-14，K40，auto35.log）：上一步「对方刚落子」的格子登记在周期 N，
            // 我方这手一确认周期变 N+1 → 那条记录立刻失效 → 那个子带着高亮环被读成**空** →
            // 观测比期望只差这一格 → 装饰容差枚举出 8 种结构等价的解释（“那格子的炮走到别处、
            // 新位置没看见”）→「不唯一」→ 连刷 54 次、一手棋白等 54 秒、连第二手都走不下去。
            // 现在只丢**我方这手的起点**，其余记录留到它们真的失效（自己认出来了 / 有子从那里离开 /
            // 被换成异色子）—— 判断依据是“棋子动了”，不是“过了几手”。
            const from = transition.squareIndex(ticket.moveICCS, 0);
            if (from >= 0) {
                dropPatchesAt([from], "我方这手 " + ticket.moveICCS + " 的起点已空");
            }
        }
        // ★ 确认成功后**刷新可靠基准** —— ③ 连续走子必需的一环（K40 真机踩到）：
        // 不刷新的后果：基准还停在「我方落子之前」，而下一帧局面已经多了我方这手（以及对方应手）
        //   → 从旧基准看是「两手都变了」→ 观察层「唯一合法着法能解释」这条补不成立
        //   → 对方应手那格的高亮环补不出来（未知格≠0）→ `sideFor` 直接放弃 → 8s 看门狗
        //   → 永久 RECOVERING（真机日志：确认 CONFIRMED 之后就一直「轮次未知超时」）。
        // 刷成「确认后的局面」之后：对方应手只改 2 格，正好是 F2 能解释的情形 → 补得出来 → 轮次接得上。
        if (verifier.isSuccess(verdict.result) && verdict.board && verdict.board.indexOf("?") < 0) {
            const mySide = ticket.baseSide === "b" ? "b" : "w";
            const nextSide = (verdict.result === verifier.RESULT.OPPONENT_REPLY)
                ? mySide                                   // 对方已应手 → 又轮到我方
                : (mySide === "w" ? "b" : "w");            // 只确认了我方落子 → 轮到对方
            lastReliable = { board: verdict.board.slice(), side: nextSide };
            note("可靠基准已刷新（确认 " + verdict.result + "）：" +
                (nextSide === mySide ? "轮到我方" : "轮到对方"));
            // 确实取得了进展（棋真的变了）→ 才能清尝试预算。
            // 恢复成功、换新票据、换几何**都不算**进展，所以它们都不在这里。
            clearAttemptBudget("已确认真实变化 " + verdict.result);
        }
        // ★ 把「落点格」登记进已知事实表（不管这次确认有没有用到它）。
        // 为什么要无条件登记：游戏会给我方刚落的子画高亮环，**下一帧起**那一格就认不出来了。
        // 如果只在「确认时刚好是未知格」才登记，就会出现：确认成功 → 下一帧那格变 '?' →
        // 未知格≠0 → `sideFor` 直接放弃 → 8s 看门狗 → 永久 RECOVERING
        // （真机实测：一手棋确认成功后原地空转 90 秒，没走第二手）。
        // 落点这一格是**已知事实**（票据 + 棋规推出来的），不是猜；等那格能自己认出来就自动失效。
        if (verifier.isSuccess(verdict.result) && expectedBoard) {
            let dest = null;
            try {
                dest = verifier.destinationIndex(ticket);
            } catch (error) {
                dest = null;
            }
            if (dest != null && expectedBoard[dest]) {
                addPatch({ index: dest, char: expectedBoard[dest] }, "落点格（我方新落的子会被画环）");
            }
        }
        // 落点格被游戏画花（未知）→ 记下来，后续观察一律按已知事实补齐。
        // 我方落点与对方应手落点都是棋盘上的事实（都从票据 + 棋规唯一推出来），不是猜。
        // （addPatch 本体在工厂作用域，见 registerMoveDestinations 旁边 —— sideFor 也要用它。）
        if (verdict.patched) {
            addPatch(verdict.patched, "落点格");
        }
        if (verdict.replyPatch) {
            addPatch(verdict.replyPatch, "对方应手落点格");
        }
        return pump(machine.dispatch({ type: "VERIFY_RESULT", ticket: ticket, verdict: verdict }));
    }

    function recover() {
        // ⚠️ 重新定位一次 ~8s（K40 实测 7.6~8.2s），而**棋子移动不改变几何**。
        // 失败循环里每次都重新定位的话，一手棋要白烧十几秒 → 这里限频：
        // 距上次定位不足 RELOCATE_MIN_INTERVAL_MS 时只「重新武装」（直接 RECOVERED → 下一轮观察）。
        const now = nowMono();
        if (lastRelocateMono > 0 && now - lastRelocateMono < RELOCATE_MIN_INTERVAL_MS) {
            note("恢复：距上次定位不到 " + Math.round(RELOCATE_MIN_INTERVAL_MS / 1000) +
                "s —— 几何没变，直接重新观察（省掉 ~8s 重定位）");
            return pump(machine.dispatch({ type: "RECOVERED" }));
        }
        lastRelocateMono = now;
        note("恢复：重新定位棋盘");
        // ⚠️ **不要**在这里清 lastReliable / knownPatches（曾经清过，K40 实测直接死锁）：
        // recover 只是重新对齐几何，**轮次不是从当前帧推出来的、而是从上一步延续的**。
        // 一旦清掉，而局面已经不是标准开局（双方各走了一手），`sideFor` 就永远推不出来
        // → 永久 RECOVERING。保留基准反而更安全：`transition.infer` 会自己校验（一致=MOVE/
        // MOVE_PAIR，不一致=其它状态 → 仍然是「推不出」），不会拄着错盘面出手。
        // 补齐表里的格子是**棋盘格子下标**，与几何无关，换几何也一样有效。
        const located = opts.locate(locateOptions());
        if (!located || !located.ok) {
            stats.errors += 1;
            stats.lastError = located ? located.reason : "定位没有结果";
            note("恢复时定位失败（继续等下一轮）：" + stats.lastError);
            return null;   // 留在 RECOVERING，下一轮再试
        }
        grid = located.grid;
        note("恢复完成，几何 v" + grid.version);
        return pump(machine.dispatch({ type: "RECOVERED" }));
    }

    // ---- 观察 ----

    /** 定位参数：棋盘方向 + 可选的范围比例。 */
    function locateOptions() {
        const options = { orientation: cfg.orientation };
        if (cfg.regionRatio) {
            options.regionRatio = cfg.regionRatio;
        }
        return options;
    }

    /**
     * 观察层的「已知事实补齐」：用**唯一解释**把少数未知格补出来。
     *
     * 为什么需要：游戏给**刚落的子**画高亮环，那一格会被模型认错（实测带环的炮读成马），
     * 棋规层再降级成 '?'。而状态机的轮次推断要求 `unknownCells === 0` —— 于是对方刚应完一手，
     * 驱动就“看不见轮次” → 超时进 RECOVERING；`recover()` 又把基准清掉，而局面已经不是
     * 标准开局 → **再也推不出轮次，永久卡死**（K40 实测：走完第一手后就卡在这）。
     *
     * 两种**可验证**的解释（都不是猜，全都要精确对上其余所有已知格）：
     *   F1 与上一张可靠盘面逐格一致（对方那手的环还在）→ 直接按基准补；
     *   F2 恰好等于「上一张可靠盘面 + 某一个合法着法」→ 且只有**唯一一种**解释。
     * 解释不唯一或对不上就返回 null，保持未知（宁可等着）。
     */
    function fillKnownFacts(chars) {
        if (!lastReliable || !lastReliable.board) {
            return null;
        }
        const base = lastReliable.board;
        // ⚠️ 基准自己必须是**没有未知格**的（它的名字就是「已知事实」）。
        // 真机踩到：assertTurn 把一个仍然带 '?' 的局面当成了基准 → F1 把 '?' 原样抄进结果、
        // 还报「与上一张可靠盘面一致」并把 unknownCells 当成 0 → 下游 buildFen 抛
        // 「存在未知格，不能生成 FEN」→ 每轮都被当异常跳过，面板上只看到「轮次未知」。
        if (base.indexOf("?") >= 0) {
            return null;
        }
        const unknown = [];
        for (let i = 0; i < 90; i++) {
            if (chars[i] === "?") {
                unknown.push(i);
            }
        }
        if (unknown.length === 0) {
            return null;
        }
        const norm = function (ch) { return ch == null ? null : ch; };
        const matches = function (board) {
            for (let i = 0; i < 90; i++) {
                if (chars[i] === "?") {
                    continue;
                }
                if (norm(chars[i]) !== norm(board[i])) {
                    return false;
                }
            }
            return true;
        };
        // F1：除了未知格，其余格子与上一张可靠盘面完全一致
        if (matches(base)) {
            const board = chars.slice();
            for (let k = 0; k < unknown.length; k++) {
                board[unknown[k]] = base[unknown[k]];
            }
            if (board.indexOf("?") >= 0) {
                return null;   // 补不出确定的局面就别假装成功
            }
            return { board: board, how: "与上一张可靠盘面一致（对方那手的环还在）" };
        }
        // F2：唯一一个合法着法就能完全解释
        const fits = [];
        const sides = [lastReliable.side, lastReliable.side === "w" ? "b" : "w"];
        for (let s = 0; s < sides.length && fits.length < 2; s++) {
            const moves = legal.allLegalMoves(base, sides[s]);
            for (let m = 0; m < moves.length && fits.length < 2; m++) {
                let after = null;
                try {
                    after = legal.applyMove(base, moves[m]).board;
                } catch (error) {
                    continue;
                }
                // 未知格只允许落在「这一步改动过的格子」上
                const diff = legal.diffBoards(base, after);
                const changed = {};
                for (let d = 0; d < diff.length; d++) {
                    changed[diff[d].row * 9 + diff[d].col] = true;
                }
                let allowed = true;
                for (let k = 0; k < unknown.length; k++) {
                    if (!changed[unknown[k]]) {
                        allowed = false;
                        break;
                    }
                }
                if (!allowed || !matches(after)) {
                    continue;
                }
                fits.push({ board: after, iccs: moves[m].iccs });
            }
        }
        if (fits.length !== 1 || fits[0].board.indexOf("?") >= 0) {
            return null;
        }
        return { board: fits[0].board, how: "唯一合法着法 " + (fits[0].iccs || "?") + " 解释得通" };
    }

    /**
     * 多帧合并识别（默认 2 帧）：**两帧一致的格子才采信，不一致的降级成未知格**。
     *
     * 为什么必须这么干（K40 真机实测）：模型单帧会有**多处**孤立误读 —— 同一帧里同时出现
     * 「黑車(0,0) 读成空」「(0,1) 空位读成黑炮」「黑炮(7,2) 读成红炮」共 3~4 处。
     * 这种盘面任何推断都解释不了 → 轮次推不出来 → 8s 看门狗 → 永久 RECOVERING。
     * 而误读是**逐帧随机**的：第二帧往往是对的 → 取「两帧都同意」的格子，
     * 冲突格算未知格，交给已有的「唯一合法着法能解释就补齐」（fillKnownFacts）走 ——
     * 宁可拿一张“部分未知”的真盘面，也不要拿一张“看起来完整”的假盘面。
     *
     * 代价：每次观察多一帧（K40 shell 通道 ~1.2s/帧），tickMs 已留 2.4s 余量。
     */
    function recognizeMerged(request) {
        const times = Math.max(1, Math.min(3, cfg.observeFrames == null ? 2 : cfg.observeFrames));
        let first = null;
        let last = null;
        let merged = null;
        for (let i = 0; i < times; i++) {
            if (i > 0 && cfg.observeGapMs > 0) {
                clock(cfg.observeGapMs);
            }
            const one = opts.recognize(request);
            if (i === 0) {
                first = one;
            }
            if (!one || !one.ok || !one.chars) {
                if (i === 0) {
                    return one;            // 第一帧就失败：直接上报（下一轮再试）
                }
                break;                     // 后续帧失败就用已有结果
            }
            last = one;
            if (!merged) {
                merged = { source: one, chars: one.chars.slice() };
                continue;
            }
            let conflicts = 0;
            for (let k = 0; k < 90; k++) {
                const a = merged.chars[k];
                const b = one.chars[k];
                if (a === b) {
                    continue;
                }
                // ⚠️ **严格合并**：只要两帧不一致就降级成未知格。
                // 曾经写成「一帧未知、另一帧有值 → 采信有值的那帧」，那会**跨帧累积棋子**：
                // 真机实测（对方炮 7→4 的应手帧）每帧的棋规修复降级的是「分数最低的那个重复炮」，
                // 两帧降的不是同一个 → 合并后 (1,2)(4,2)(7,2) 全留成黑炮 = **3 个炮**的不可能局面
                // → 确认/轮次推断全都解释不了 → UNEXPECTED + 永久 RECOVERING。
                // 严格合并后：那两格变成未知，交给「唯一合法着法能解释就补齐」（fillKnownFacts F2）还原。
                merged.chars[k] = "?";
                conflicts += 1;
            }
            if (conflicts > 0) {
                stats.frameConflicts += conflicts;
            }
        }
        if (!merged) {
            return first;
        }
        stats.mergedFrames += 1;
        // 合并后的盘面挂到**最后一帧**上：框中心（点棋用）/几何 都要用最新的那一帧。
        const out = last || first || merged.source;
        if (times > 1) {
            out.chars = merged.chars;
        }
        let unknown = 0;
        for (let k = 0; k < 90; k++) {
            if (out.chars[k] === "?") {
                unknown += 1;
            }
        }
        out.unknownCells = unknown;
        return out;
    }

    /**
     * 盘面 → 紧凑文本（FEN 风格：空位用数字压缩，未知格写 '?'）。
     * ⚠️ 不能用「只拼字符」的写法：那样丢掉了空格子，**看不出列号**，诊断等于白打
     * （真机踩过：想把「我方落子+对方应手」的判错盘面读出来，结果两行都没列号，只能重跑）。
     */
    function boardText(chars) {
        if (!chars) {
            return "-";
        }
        const rows = [];
        for (let r = 0; r < 10; r++) {
            let line = "";
            let empty = 0;
            for (let c = 0; c < 9; c++) {
                const ch = chars[r * 9 + c];
                if (ch) {
                    if (empty > 0) {
                        line += empty;
                        empty = 0;
                    }
                    line += ch;
                } else {
                    empty += 1;
                }
            }
            if (empty > 0) {
                line += empty;
            }
            rows.push(line);
        }
        return rows.join("/");
    }

    /** 截一帧并识别。复用已校准几何（700ms 的定位不必每轮都做）。 */
    function observeOnce(options) {
        const started = nowMono();
        const request = {
            grid: options && options.relocate ? null : grid,
            orientation: cfg.orientation,
            confidence: cfg.confidence,
            timeoutMs: 400
        };
        if (cfg.regionRatio) {
            request.regionRatio = cfg.regionRatio;
        }
        stats.observations += 1;
        let result;
        try {
            result = recognizeMerged(request);
        } catch (error) {
            stats.errors += 1;
            stats.lastError = String(error && error.message ? error.message : error);
            return { ok: false, reason: "识别异常：" + stats.lastError };
        }
        stats.lastObserveMs = nowMono() - started;
        lastResult = result;
        if (!result || !result.ok) {
            stats.unrecognized += 1;
            return { ok: false, reason: (result && result.reason) ? result.reason : "识别失败" };
        }
        // 识别时几何被微调过（按检测框对齐）→ 记下来，让后面的点击用同一套几何，
        // 否则会出现「按 A 几何认棋盘、按 B 几何点坐标」的错位。
        if (result.grid && grid &&
            (result.grid.originX !== grid.originX || result.grid.originY !== grid.originY)) {
            const dx = Math.round(result.grid.originX - grid.originX);
            const dy = Math.round(result.grid.originY - grid.originY);
            grid = result.grid;
            note("几何按检测框微调：原点偏移 (" + dx + "," + dy + ")（未知格 " +
                (result.stats && result.stats.autoAlign ? result.stats.autoAlign.unknown : "?") + "）");
        }
        // 点棋用「实测框中心」：连它所属的几何版本一起记下（版本不符就退回交点）
        lastCenters = result.centers || null;
        lastCentersVersion = (result.grid && result.grid.version != null)
            ? result.grid.version
            : (grid && grid.version != null ? grid.version : null);
        // 「游戏画花了的格子」→ 按已知事实补齐。
        // 真机现象：游戏给刚落的子画高亮环，那一格会被模型认错（实测：带环的炮读成马），
        // 棋规层再把它降级成 '?'。**我方落子和对方应手的子都会被画环**，所以这是
        // 一张表（最多两格）。不补的话未知格≠0，轮次推断直接放弃 → 卡死在 RECOVERING。
        // 每一格一旦能自己认出来（或被吃/换子）就自动从表里去掉。
        // ⚠️ **先留一份模型读到的原始观测**（§12）：后面的补丁/补脑/纠色会就地改 `result.chars`，
        // 不留原始读数就分不清「模型看到了什么」与「我们按规则改成了什么」。
        // 诊断与审核只看 rawChars，行动仍看修正后的盘面（两者都必须能查到）。
        const rawChars = result.chars ? result.chars.slice() : null;
        let patchedNow = null;
        let unknownCells = result.unknownCells == null ? 0 : result.unknownCells;
        if (knownPatches.length > 0 && result.chars) {
            const keep = [];
            for (let i = 0; i < knownPatches.length; i++) {
                const patch = knownPatches[i];
                if (patch == null || patch.index == null) {
                    continue;
                }
                const got = result.chars[patch.index];
                // ⚠️ 这里**不看周期**（2026-09-14 真机事故，见 doVerify 里的说明）：
                // 以前是「上一手的记录一律作废」，结果是「对方刚落子的那格」在我方一确认就失效
                // → 那格读成空时没人补 → 每帧都解释不了 → 白等 54 秒。
                // 现在改成按**棋子是否离开**作废（dropPatchesAt）：能自己认出来/变成别的同色子
                // 就留着（装饰会反复发作），真被吃成异色子则丢掉（不能把吃子藏起来）。
                // ⚠️ **票据优先**：确认阶段带着「预期落子后局面」时，如果预期说这一格已经**空了**
                //    （= 我方这手正是从这一格走出去的），那这个"补齐"就是跟票据对着干。
                //    真机实测后果（2026-09-14，K40，b2c2 确认成功后走 c2c6）：(2,7) 被补回旧字 →
                //    整帧看起来跟基准一模一样 → 确认判 UNCHANGED → 重试/恢复，
                //    而**棋其实已经走成了**（事后截图：红炮确实在 (2,3)，游戏 4 步）。
                //    所以这种记录不但不能补，还要从表里丢掉（那一格的子真的走了，不会再回来）。
                if (options && options.expect) {
                    const want = options.expect[patch.index];
                    if (want === null || want === "" || want === undefined) {
                        continue;
                    }
                }
                if (got === "?" || got === "" || got === null || got === undefined) {
                    // ① 读不出来 / **整格漏检（读成空）**：高亮环把子盖住时模型常常直接看不到它
                    //    （真机：对方刚落的黑炮在 (4,2) 被读成空 → 盘面与「我方落子+对方应手」对不上）。
                    //    本手周期内的落点是**已知事实**，按已知补齐。
                    result.chars[patch.index] = patch.char;
                    if (got === "?") {
                        unknownCells = Math.max(0, unknownCells - 1);
                    }
                    patchedNow = patch;
                    stats.ownMovePatched += 1;
                    keep.push(patch);
                    continue;
                }
                if (got === patch.char) {
                    // ② 自己能认出来了 → 保留（同一手内下一帧可能又被盖住）
                    keep.push(patch);
                    continue;
                }
                // ③ 读成了**别的子**：分两种，都要按已知事实补齐，但判据不同 ——
                //    · **同字异色**（C↔c、P↔p…）= 同一个兵种被读成红黑反了。这台机器上实测会**反复抖**
                //      （2026-09-14 r1.log：`(1,7)?→C` 这类行反复出现）。⚠️ 以前这种情况走 ④ 丢掉记录，
                //      于是**一帧抖动就足够把记录丢掉**，之后同色读错的帧再没人补 →
                //      「轮次推不出来（UNKNOWN）」→ 看门狗 → 整轮停在那儿。
                //      离线回放（diag-patch-life2.js）已复现：一帧 红黑读反 就把 (2,3) 的记录丢掉。
                //    · **同色不同字**（C↔P）= 兵种被游戏装饰读错（真机实测：我方炮吃卒落在卒位被读成红兵）。
                //    异色**且**不同字（C↔n）才是「真的被吃/走开了」→ 必须让它变，
                //    否则会把吃子藏起来，后面算棋就按错盘面走。
                const sameGlyph = got && patch.char && got.length === 1 && patch.char.length === 1 &&
                    got.toLowerCase() === patch.char.toLowerCase();
                const sameColor = got && patch.char &&
                    (got === got.toUpperCase()) === (patch.char === patch.char.toUpperCase());
                if (got && got !== "" && (sameGlyph || sameColor)) {
                    result.chars[patch.index] = patch.char;
                    patchedNow = patch;
                    stats.ownMovePatched += 1;
                    if (sameGlyph && !sameColor) {
                        stats.sameGlyphPatched += 1;   // 计数：真出现「同兵种吃子被掩盖」时能在日志里看见
                    }
                    keep.push(patch);
                    continue;
                }
                // ④ 变空 / 换成异色且不同字 → 真的被吃或走开了，不再保留
            }
            knownPatches = keep;
        }
        // 还有未知格 → 试试用「已知事实」补齐（唯一解释；见 fillKnownFacts 的说明）
        if (unknownCells > 0 && result.chars) {
            const filled = fillKnownFacts(result.chars);
            // 只有「补完真的没未知格」才算成功：宁可老老实实报未知格，也不要假装补齐（见 fillKnownFacts 的注释）
            if (filled && filled.board && filled.board.indexOf("?") < 0) {
                result.chars = filled.board;
                unknownCells = 0;
                stats.knownFactFills += 1;
                if (lastFillText !== filled.how) {
                    lastFillText = filled.how;
                    note("未知格按已知事实补齐：" + filled.how);
                }
            }
        }
        // 颜色翻转纠正：游戏给刚落的子/被威胁的子加装饰（高亮环、命中标记），实测会让模型
        // 把**同一个兵种的颜色判反**（例：落点上的红炮被读成红兵/黑卒）。
        //
        // ⚠️ 只改「观测字符与期望字符是同一个字母的不同大小写」的格子，而且**只在确认阶段**
        // 用（options.expect = 票据的「预期落子后局面」）：
        //   · 为什么不看上一张可靠盘面？因为「我的红兵吃掉对方的黑卒」也会表现为同一格
        //     P↔p，拿旧盘面去纠就会**把真实的吃子改掉**；
        //   · 用票据的预期盘面就没有这个问题：纠正之后整盘必须与预期**完全一致**，
        //     否则验证器照样判 UNCHANGED/UNEXPECTED —— 风险由验证器兜住，不是无条件采信。
        if (options && options.expect && result.chars) {
            const expectBoard = options.expect;
            let flips = 0;
            for (let i = 0; i < 90; i++) {
                const got = result.chars[i];
                const want = expectBoard[i];
                if (typeof got !== "string" || typeof want !== "string") {
                    continue;
                }
                if (got !== want && got.toLowerCase() === want.toLowerCase() && /^[A-Za-z]$/.test(got)) {
                    result.chars[i] = want;
                    flips += 1;
                }
            }
            if (flips > 0) {
                stats.colorFlips += flips;
            }
        }
        // lastBoard 要在**补齐/纠色之后**记：它会被「现在轮到我」拿去当基准，
        // 存的必须是「目前最可信的那张盘」（带 '?' 的原始观测会让基准不干净）。
        lastBoard = result.chars ? result.chars.slice() : null;
        lastRawChars = rawChars;
        // 原始读数与修正后盘面不一致时记一笔（限频）：审核、复现都靠它
        if (rawChars && result.chars && rawChars.join("") !== result.chars.join("")) {
            let diffCount = 0;
            const changed = [];
            for (let i = 0; i < 90; i++) {
                if (rawChars[i] !== result.chars[i]) {
                    diffCount += 1;
                    if (changed.length < 6) {
                        changed.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" +
                            (rawChars[i] || "空") + "→" + (result.chars[i] || "空"));
                    }
                }
            }
            stats.rawCorrected += 1;
            const text = "原始读数被修正 " + diffCount + " 格：" + changed.join(" ") +
                (diffCount > changed.length ? " …" : "");
            if (lastRawCorrectedText !== text) {
                lastRawCorrectedText = text;
                note(text);
            }
        } else {
            lastRawCorrectedText = "";
        }
        return {
            ok: true,
            chars: result.chars,
            rawChars: rawChars,
            unknownCells: unknownCells,
            patched: patchedNow,
            conflicts: result.stats && result.stats.conflicts ? result.stats.conflicts : 0,
            layout: result.layout,
            result: result
        };
    }

    /** 标准开局（红先）：第一次观察到的局面就是它时，可以确定轮到红方。 */
    function isStartPosition(board) {
        const start = fenModule.parseFen(fenModule.START_FEN);
        return !!start.ok && legal.boardMatches(board, start.board);
    }

    /**
     * 登记一个「已知事实」格子：本手内那一格一律按这里给的字补齐。
     * 依据都是棋规唯一推出来的事实（票据 / 唯一合法着法），不是猜；那格能自己认出来就自动失效。
     */
    function addPatch(entry, label) {
        if (!entry || entry.index == null || !entry.char) {
            return;
        }
        for (let i = 0; i < knownPatches.length; i++) {
            if (knownPatches[i].index === entry.index) {
                knownPatches[i] = { index: entry.index, char: entry.char, cycle: verifyCycle };
                return;
            }
        }
        knownPatches.push({ index: entry.index, char: entry.char, cycle: verifyCycle });
        note(label + " " + entry.char + "（下标 " + entry.index +
            "）按已知事实补齐（那格能自己认出来、或有子从那里离开时自动作废）");
    }

    /**
     * 把「已知事实」作废：那些格子里有子**离开**了（新着法的起点），记录不再成立。
     *
     * 为什么必须有它（2026-09-14 真机，auto35.log）：
     * 补齐表以前靠 verifyCycle 整表作废 —— 我方一确认，周期 +1，连「对方刚落子」的记录一起丢。
     * 结果那个子（带高亮环、被模型读成空）没人补 → 观测比期望只差一格 → 装饰容差枚举出 8 种
     * 结构等价的解释（“那个炮从这一格走到别处、新位置没看见”）→ 不唯一 → 连刷 54 次 → 死等。
     * 反过来，如果永远不作废，一个真的掉了的子会被“补”回棋盘上 —— 所以作废依据必须是
     * 「那个格子上的子确实走了」，而不是“过了几手”。
     */
    function dropPatchesAt(indexes, why) {
        if (!indexes || indexes.length === 0 || knownPatches.length === 0) {
            return;
        }
        const keep = [];
        let dropped = 0;
        for (let i = 0; i < knownPatches.length; i++) {
            const patch = knownPatches[i];
            if (patch && indexes.indexOf(patch.index) >= 0) {
                dropped += 1;
                continue;
            }
            keep.push(patch);
        }
        if (dropped > 0) {
            knownPatches = keep;
            note("已知事实作废 " + dropped + " 格（" + why + "）");
        }
    }

    /**
     * 把「刚落的子」所在的格子登记进已知事实表。
     *
     * 游戏会给刚落的子画高亮环，**下一帧起那一格就认不出来**（读成空或别的兵种）。
     * 我方那一格在确认阶段就登记了，但**对方那一格以前没人登记** —— 真机实测的后果（2026-09-14，K40）：
     * 对方 h7e7 之后 (4,2) 一直读成空 → 每帧都「轮次推不出来（UNKNOWN）」→ 看门狗 → RECOVERING，
     * 而我方那手也白走（整轮停在那儿）。这里按刚推出来的着法登记，等那格自己能认出来就自动失效。
     *
     * @param moves   刚推出来的着法（MOVE 一步 / MOVE_PAIR 两步，落点都算「刚落的子」）
     * @param settled 推完这些着法后的可靠盘面（补的字取自它）
     */
    function registerMoveDestinations(moves, settled) {
        const list = moves || [];
        for (let i = 0; i < list.length; i++) {
            const move = list[i];
            if (!move || !move.iccs || !settled) {
                continue;
            }
            const dest = transition.squareIndex(move.iccs, 1);
            if (dest < 0 || !settled[dest]) {
                continue;
            }
            addPatch({ index: dest, char: settled[dest] }, "刚落子的格子（会被画环）");
        }
    }

    /**
     * 推轮次：从「上一可靠局面 + 新观测」推出现在轮到谁；推不出就返回 null。
     *
     * 副作用：推断成功时会把 lastReliable 刷成**按着法改正过的盘面**；
     * 若这次真的改动了观测（落点格被游戏装饰读错），额外留下 lastSideFix，
     * 交给本轮观察把 lastBoard / 快照一起改正 —— 否则算棋会拿着
     * 「我方炮在某格被读成兵、炮又不在原位」的错盘面出手。
     */
    function sideFor(board, unknownCells) {
        lastSideFix = null;
        if (unknownCells > 0) {
            return null;
        }
        if (!lastReliable) {
            // 没有任何历史时唯一的可靠推断：棋盘就是标准开局 → 中国象棋红先
            if (isStartPosition(board)) {
                lastReliable = { board: board.slice(), side: "w" };
                note("局面是标准开局 → 按红先处理（轮到红方）");
                return "w";
            }
            return null;
        }
        const inferred = transition.infer(lastReliable.board, lastReliable.side, board, { allowPair: true });
        if (inferred.status === "MOVE" || inferred.status === "MOVE_PAIR") {
            const settled = inferred.board ? inferred.board.slice() : board.slice();
            // 容差解释（§12）：必须连续两帧一致才行动，否则只等
            if (inferred.tolerant === true && !tolerantSupportedByTwoFrames(lastReliable.board, inferred.reason)) {
                lastSideFix = null;
                return null;
            }
            if (inferred.board && legal.diffBoards(settled, board).length > 0) {
                lastSideFix = { board: settled, how: inferred.reason };
            }
            lastReliable = { board: settled, side: inferred.nextSide };
            note("轮次推断：" + inferred.reason);
            // 刚落的子会被画高亮环 → 把落点登记成已知事实，否则下一帧那一格变「空」→ 推不出轮次 → 恢复。
            // ⚠️ 顺序：先作废**这些着法的起点**（那里已经空了），再登记落点。
            // 反过来的话，一步「走到别处又回来」的棋会把自己的起点记录留下（真机：炮 (2,7)→(2,3)
            // 之后 (2,7) 的记录还在 → 下一帧那一格被补回旧子 → 整盘看起来没动）。
            const sources = [];
            const moves = inferred.moves || [];
            for (let i = 0; i < moves.length; i++) {
                const from = transition.squareIndex(moves[i].iccs, 0);
                if (from >= 0) {
                    sources.push(from);
                }
            }
            dropPatchesAt(sources, "刚推出来的着法起点已空");
            registerMoveDestinations(inferred.moves, settled);
            return inferred.nextSide;
        }
        if (inferred.status === "UNCHANGED") {
            // 观测只差几个「颜色读反」的格 → 场面还是基准那张（本手没落子）→
            // 把观测/快照拉回基准，别把带反色的盘面拿去算棋。
            if (inferred.board && legal.diffBoards(inferred.board, board).length > 0) {
                lastSideFix = { board: inferred.board.slice(), how: inferred.reason };
            }
            return lastReliable.side;
        }
        // 严格推断失败 → 再试一次**带装饰容差**的推断（放宽唯一合法着法的**落点那一格**）。
        // 真机踩到（K40 + 天天象棋）：对方炮吃我方兵、落在兵位上，那一格被模型读成 'p'（炮认成卒）
        // → 严格推断「既没有合法单步也没有一步+应手能解释」→ 轮次未知 → 8s 看门狗 → 永久 RECOVERING。
        // 收紧点：只允许**一格**不同，而且那格必须正是该着法的落点；其余格子仍要完全一致。
        const tolerant = inferWithDecorationTolerance(lastReliable.board, lastReliable.side, board);
        if (tolerant) {
            if (!tolerantSupportedByTwoFrames(lastReliable.board,
                "唯一合法单步 " + tolerant.iccs)) {
                return null;
            }
            lastSideFix = { board: tolerant.board, how: "唯一合法单步 " + tolerant.iccs };
            lastReliable = { board: tolerant.board, side: tolerant.nextSide };
            note("轮次推断（装饰容差）：唯一合法单步 " + tolerant.iccs +
                "（落点格被游戏装饰读错，已按该着法改正" +
                (tolerant.colorNoise ? "；另有 " + tolerant.colorNoise + " 格只是红黑读反，已按基准纠正" : "") +
                "）");
            return tolerant.nextSide;
        }
        stats.unknownTurn += 1;
        note("轮次推不出来（" + inferred.status + "）：" + inferred.reason);
        // 诊断（限频）：把「基准 / 观测 / 容差两步统计」都写出来 —— 只报「推不出来」没法定位
        if (stats.unknownTurn <= 3 || stats.unknownTurn % 10 === 0) {
            const baseFen = lastReliable ? safeFen(lastReliable.board, lastReliable.side) : "（无基准）";
            note("诊断基准 " + baseFen);
            if (lastRawChars) {
                note("诊断原始读数 " + safeFen(lastRawChars, lastReliable ? lastReliable.side : "w"));
            }
            note("诊断观测 " + safeFen(board, lastReliable ? lastReliable.side : "w") +
                (inferred.debug ? "｜容差：我方着法 " + inferred.debug.ownMoves +
                    "，试 " + inferred.debug.tried + "，最小差 " + inferred.debug.minDiff +
                    "，命中 " + inferred.debug.fits + "，最优 " + inferred.debug.best : ""));
            // 补齐表内容（2026-09-14 加）：「推不出来」有两种完全不同的原因 ——
            // ① 表里根本没有那两格的记录；② 表里有、但这帧没补上（读成了别的字）。
            // 不把表打出来就只能猜（当天为这个来回跑了好几轮真机）。
            const table = [];
            for (let i = 0; i < knownPatches.length; i++) {
                const patch = knownPatches[i];
                if (!patch || patch.index == null) {
                    continue;
                }
                table.push("(" + (patch.index % 9) + "," + Math.floor(patch.index / 9) + ")" +
                    patch.char + "/本轮读到" + (lastRawChars ? (lastRawChars[patch.index] || "空") : "？"));
            }
            note("诊断补齐表 " + (table.length ? table.join(" ") : "（空）") +
                "｜周期 " + verifyCycle);
        }
        return null;
    }

    /** 诊断用：出 FEN，不抛错。 */
    function safeFen(board, side) {
        try {
            return fenModule.buildFen(board, side === "b" ? "b" : "w");
        } catch (error) {
            return "（FEN 失败：" + (error && error.message ? error.message : error) + "）";
        }
    }

    /**
     * 带装饰容差的轮次推断：在「唯一合法单步」上允许**落点那一格**与观测不同
     * （游戏给刚落的子加高亮环/击中特效，模型会把兵种读错）。
     * 返回 { board（按该着法改正后的盘面）, nextSide, iccs } 或 null。
     */
    function inferWithDecorationTolerance(base, baseSide, observed) {
        // 逻辑已下沉到 `transition.inferSingleWithDecorationTolerance`（纯函数，可离线自检）：
        // 单步 + 只允许「落点格被装饰读错」以及「没被这步碰过的格子只是红黑读反」。
        // 这里只做形状适配（驱动要用 board/nextSide/iccs，日志要用 colorNoise）。
        const tolerant = transition.inferSingleWithDecorationTolerance(base, baseSide, observed);
        if (!tolerant) {
            return null;
        }
        return {
            board: tolerant.board,
            nextSide: tolerant.nextSide,
            iccs: tolerant.iccs,
            colorNoise: tolerant.colorNoise
        };
    }

    /**
     * 一轮：观察 → 喂状态机 → 执行动作。
     * @returns { ok, state, reason, board, side, unknownCells }
     */
    function tick() {
        if (!running) {
            return { ok: false, reason: "没有在运行" };
        }
        stats.ticks += 1;

        // 先非阻塞地把 worker 的算棋结果收回来（不截图、不观察，每轮只花一次 getResult()）。
        // 还在算就直接返回：既不挡界面，也不会在思考期间反复去截屏干扰棋盘。
        const thinking = pollCompute();
        if (thinking && thinking.pending) {
            return {
                ok: true,
                state: machine.state(),
                reason: "算棋中（worker 线程，已 " + Math.round(thinking.elapsedMs) + "ms）",
                thinking: true
            };
        }

        pump(machine.dispatch({ type: "TICK", nowMono: nowMono() }));

        // 没有可用几何（还没定位成功 / 恢复中）就**每一轮都重试定位**：
        // RECOVERING 不是终点，只在恢复时定位一次的话，用户把棋盘切上来之后
        // 会一直停在 RECOVERING（真机踩过：脚本先在自己的界面上启动，定位失败后就再也不试了）。
        if (!grid) {
            calibrate();
        }

        const observed = observeOnce({});
        if (!observed.ok) {
            // 失败不能静默：否则界面停在 WAIT_STABLE，日志里什么都看不到（真机踩过）
            const failures = stats.unrecognized;
            if (failures <= 2 || failures % 10 === 0) {
                note("本轮没看清（第 " + failures + " 次）：" + observed.reason);
            }
            pump(machine.dispatch({
                type: "SNAPSHOT",
                snapshot: { board: lastBoard, side: null, fen: null, unknownCells: 90, stable: false }
            }));
            return { ok: false, reason: observed.reason, state: machine.state() };
        }

        const side = sideFor(observed.chars, observed.unknownCells);
        // 轮次推断如果给出了「按着法改正过的盘面」（落点格被游戏装饰读错兵种），
        // 就以它为准重算本轮的观测与 lastBoard：这是**唯一合法解释**推出来的事实，
        // 比模型对那两个装饰格的读数可信；不改正的话算棋会拿着错盘面出手。
        if (lastSideFix && lastSideFix.board && observed.chars && observed.unknownCells === 0) {
            const fix = lastSideFix;
            lastSideFix = null;
            let fixed = 0;
            for (let i = 0; i < 90; i++) {
                if (fix.board[i] && fix.board[i] !== observed.chars[i]) {
                    observed.chars[i] = fix.board[i];
                    fixed += 1;
                }
            }
            if (fixed > 0) {
                lastBoard = observed.chars.slice();
                stats.sideFixes += 1;
                note("盘面已按着法改正 " + fixed + " 格（" + fix.how + "）");
            }
        }
        if (side == null && observed.unknownCells === 0) {
            stats.unknownTurn += 1;
            if (!lastReliable) {
                const noteCount = stats.unknownTurn;
                if (noteCount <= 2 || noteCount % 10 === 0) {
                    note("还没有轮次基准（第 " + noteCount + " 次）：按「现在轮到我」声明一次，" +
                        "或者让局面回到标准开局");
                }
            }
        }
        const fen = observed.unknownCells === 0
            ? fenModule.buildFen(observed.chars, side === "b" ? "b" : "w")
            : null;
        const outcome = machine.dispatch({
            type: "SNAPSHOT",
            snapshot: {
                board: observed.chars,
                side: side,
                fen: fen,
                unknownCells: observed.unknownCells,
                conflicts: observed.conflicts,
                stable: true,
                geometryTrusted: true
            }
        });
        if (observed.unknownCells === 0) {
            stats.stableObservations += 1;
        } else {
            const unknownNotes = stats.stableObservations + stats.unknownTurn;
            if (unknownNotes <= 1 || unknownNotes % 10 === 0) {
                note("本轮有 " + observed.unknownCells + " 个未知格（第 " + (stats.observations) + " 次观察）：" +
                    (observed.layout || "（布局没生成）"));
            }
        }
        pump(outcome);
        return {
            ok: true,
            state: machine.state(),
            reason: outcome.reason,
            board: observed.layout,
            side: side,
            unknownCells: observed.unknownCells
        };
    }

    function start() {
        running = true;
        pump(machine.dispatch({ type: "START" }));
        pump(machine.dispatch({ type: "READY", modelVersion: cfg.modelVersion }));
        return machine.state();
    }

    return {
        config: cfg,
        machine: machine,
        executor: executor,
        disabledTap: disabledTap,
        clickAllowed: clickAllowed,
        start: start,
        /**
         * 安全网：一轮里的异常不能把整个自动循环带走。
         * 真机踩过：从我们自己的界面刚切到游戏时截屏超时，`locateBoard` 抛异常直接结束脚本。
         * 这里只记数 + 返回 ok:false（**不**把状态机推到 ERROR），下一轮还有机会成功。
         */
        tick: function () {
            try {
                return tick();
            } catch (error) {
                stats.errors += 1;
                stats.lastError = String(error && error.message ? error.message : error);
                note("本轮异常（已跳过，下一轮重试）：" + stats.lastError);
                return { ok: false, reason: stats.lastError, state: machine.state() };
            }
        },
        isRunning: function () { return running; },
        /** 还在 worker 里算棋（界面可用它决定要不要缩短轮询间隔）。 */
        isThinking: function () { return !!pendingCompute; },
        /** 非阻塞收算棋结果：宿主自己起定时器轮询时可以直接调它。 */
        pollCompute: pollCompute,
        computeMode: function () { return computeMode; },
        pause: function () { return pump(machine.dispatch({ type: "PAUSE" })); },
        resume: function () { return pump(machine.dispatch({ type: "RESUME" })); },
        stop: function () {
            running = false;
            workerSearch.cancel();
            pendingCompute = null;
            knownPatches = [];
            return pump(machine.dispatch({ type: "STOP" }));
        },
        setMySide: function (side) {
            machine.setMySide(side);
            lastReliable = null;
        },
        mySide: function () { return machine.mySide(); },
        /** 用户显式声明「现在轮到我」：把当前观察到的局面当作可靠基准。 */
        assertTurn: function () {
            if (!lastBoard) {
                return { ok: false, reason: "还没有识别到局面（先跑一轮观察）" };
            }
            // ⚠️ 基准里不能有未知格：它是后面「已知事实补齐」的唯一依据，
            // 带 '?' 的基准会把 '?' 当成事实抄回去（真机踩到：报告「已补齐」后 buildFen 抛异常）。
            const bad = lastBoard.indexOf("?");
            if (bad >= 0) {
                return {
                    ok: false,
                    reason: "当前局面还有未知格（第 " + (Math.floor(bad / 9) + 1) + " 行第 " + (bad % 9 + 1) +
                        " 列），不能当基准：等它认出来，或先把棋盘「菜单→重来」清成干净开局"
                };
            }
            lastReliable = { board: lastBoard.slice(), side: machine.mySide() };
            note("用户声明轮到" + (machine.mySide() === "w" ? "红方（我方）" : "黑方（我方）"));
            return { ok: true, reason: "已把当前局面当作基准" };
        },
        /** 换局/重启：会话 +1，旧票据与基准全部作废。 */
        newSession: function () {
            const id = machine.versions().sessionId + 1;
            machine.setSession(id);
            lastReliable = null;
            grid = null;
            knownPatches = [];
            note("新会话 #" + id + "（基准与几何已清空）");
            return id;
        },
        grid: function () { return grid; },
        setGrid: function (value) { grid = value; },
        lastResult: function () { return lastResult; },
        lastBoard: function () { return lastBoard ? lastBoard.slice() : null; },
        lastReliable: function () {
            return lastReliable ? { board: lastReliable.board.slice(), side: lastReliable.side } : null;
        },
        stats: function () {
            return Object.assign({}, stats, {
                clicksByExecutor: executor.stats ? executor.stats().clicks : 0,
                disabledTapCalls: disabledTap.calls(),
                machine: machine.counters()
            });
        },
        history: function () { return history.all(); },
        describe: function () {
            const lines = [];
            lines.push("状态：" + machine.state() + "（" + machine.reason() + "）");
            lines.push("我方：" + (machine.mySide() === "w" ? "红" : "黑") +
                "  轮次基准：" + (lastReliable ? (lastReliable.side === "w" ? "红" : "黑") : "未知") +
                "  几何：" + (grid ? ("v" + grid.version) : "无"));
            const counters = machine.counters();
            lines.push("观察 " + stats.observations + " 次（未识别 " + stats.unrecognized + "）" +
                "，点击 " + (executor.stats ? executor.stats().clicks : 0) + " 次" +
                "，确认 " + counters.confirms + " 次，重试 " + counters.retries + " 次");
            lines.push("算棋：" + computeMode + "（worker " + stats.thinkOffThread +
                " 次 / 本线程 " + stats.thinkInline + " 次，最近一次 " + Math.round(stats.thinkMs) + "ms）");
            lines.push("落点补齐 " + stats.ownMovePatched + " 次（其中同字异色 " + stats.sameGlyphPatched +
                " 次）、已知事实补齐 " + stats.knownFactFills +
            " 次、颜色纠正 " + stats.colorFlips + " 格（游戏给刚落的子加装饰，那几格认不准）");
            if (!clickAllowed) {
                lines.push("模式：不点击（拦截了 " + disabledTap.calls() + " 次点击调用）");
            }
            return lines.join("\n");
        }
    };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    createDriver: createDriver
};
