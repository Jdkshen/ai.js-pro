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
const defaults = require("../util/defaults.js");

const DEFAULTS = {
    /** 每轮观察间隔（ms）。识别一轮约 200ms，留出余量给界面。 */
    tickMs: 900,
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
    relocateEveryTicks: 0
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
        thinkMs: 0
    };
    /** 落点格按票据补齐的次数（游戏给刚落的子加高亮，那一格认不出来） */
    stats.ownMovePatched = 0;
    const history = [];

    let running = false;
    let grid = null;
    let lastReliable = null;      // { board, side }：上一可靠局面与「该走棋方」
    let lastResult = null;        // 最近一次识别结果（界面/诊断用）
    let lastBoard = null;

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
     * 「我方落点格」的补齐信息 { index, char }：
     * 游戏会给刚落的子加高亮/换画法，那一格就认不出来了（小米8 真机实测）。
     * 兵种是**已知事实**（就是我们票据里的那个子），所以后续观察里只要那格还是 `?`，
     * 就用它补齐 —— 这样轮次推断/状态机看到的才是完整局面。格子里读到别的子就自动失效。
     */
    let ownMovePatch = null;

    function useWorkerSearch() {
        return computeMode === "worker";
    }

    function note(text) {
        history.push({ atMono: nowMono(), text: text });
        if (history.length > 300) {
            history = history.slice(-200);
        }
        log("[driver] " + text);
    }

    // ---- 动作清单的执行 ----

    function handleEffect(effect) {
        switch (effect.type) {
            case "CALIBRATE":
                return calibrate();
            case "COMPUTE":
                return compute(effect);
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

    function calibrate() {
        try {
            const located = opts.locate(locateOptions());
            if (!located || !located.ok) {
                note("定位失败：" + (located ? located.reason : "没有结果"));
                return pump(machine.dispatch({ type: "GEOMETRY", trusted: false, reason: located ? located.reason : "" }));
            }
            grid = located.grid;
            lastReliable = null;
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
        const outcome = executor.execute(ticket, {
            grid: grid,
            screen: opts.screen ? opts.screen() : null,
            cancel: function () { return !running || machine.state() === machineModule.STATE.PAUSED; }
        });
        ownMovePatch = null;   // 要走新的子了：上一次的落点补齐作废
        note("点击：" + ticket.moveICCS + " → " + outcome.status + "（" + outcome.reason + "）" +
            "，点击 " + outcome.clickCount + " 次");
        return pump(machine.dispatch({
            type: "CLICK_RESULT", ticket: ticket, status: outcome.status,
            clickCount: outcome.clickCount, reason: outcome.reason
        }));
    }

    function doVerify(effect) {
        const ticket = effect.ticket;
        const observed = observeOnce({ forVerify: true });
        if (!observed.ok) {
            return pump(machine.dispatch({
                type: "VERIFY_RESULT", ticket: ticket,
                verdict: { result: verifier.RESULT.UNRELIABLE, reason: observed.reason }
            }));
        }
        const verdict = verifier.verify({
            ticket: ticket,
            observed: observed.chars,
            unknownCells: observed.unknownCells,
            stable: true,
            geometryTrusted: true,
            conflicts: observed.conflicts
        });
        note("确认：" + verifier.describe(verdict));
        if (verifier.isSuccess(verdict.result)) {
            stats.confirms += 1;
        }
        // 落点格被游戏画花（未知）→ 记住这一格，后续观察一律按票据已知的兵种补齐
        if (verdict.patched) {
            ownMovePatch = { index: verdict.patched.index, char: verdict.patched.char };
            note("落点格 " + verdict.patched.char + "（下标 " + verdict.patched.index +
                "）后续观察按票据补齐（直到那格能自己认出来）");
        } else if (verifier.isSuccess(verdict.result)) {
            ownMovePatch = null;   // 认得出来就不需要补
        }
        return pump(machine.dispatch({ type: "VERIFY_RESULT", ticket: ticket, verdict: verdict }));
    }

    function recover() {
        note("恢复：重新定位棋盘");
        lastReliable = null;
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
            result = opts.recognize(request);
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
        lastBoard = result.chars ? result.chars.slice() : null;
        // 我方落点格被游戏画花（那格还是 `?`）→ 按票据已知的兵种补齐。
        // 不补的话未知格≠0，轮次推断会直接放弃（真机就是这样卡在 RECOVERING 的）。
        let patchedNow = null;
        let unknownCells = result.unknownCells == null ? 0 : result.unknownCells;
        if (ownMovePatch && result.chars) {
            if (result.chars[ownMovePatch.index] === "?") {
                result.chars[ownMovePatch.index] = ownMovePatch.char;
                unknownCells = Math.max(0, unknownCells - 1);
                patchedNow = ownMovePatch;
                stats.ownMovePatched += 1;
            } else {
                // 那格已经能自己认出来（或已经被吃/换子）→ 不再需要补
                ownMovePatch = null;
            }
        }
        return {
            ok: true,
            chars: result.chars,
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

    /** 推轮次：从「上一可靠局面 + 新观测」推出现在轮到谁；推不出就返回 null。 */
    function sideFor(board, unknownCells) {
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
            lastReliable = { board: board.slice(), side: inferred.nextSide };
            note("轮次推断：" + inferred.reason);
            return inferred.nextSide;
        }
        if (inferred.status === "UNCHANGED") {
            return lastReliable.side;
        }
        stats.unknownTurn += 1;
        note("轮次推不出来（" + inferred.status + "）：" + inferred.reason);
        return null;
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
            ownMovePatch = null;
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
            ownMovePatch = null;
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
        history: function () { return history.slice(); },
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
            lines.push("落点补齐 " + stats.ownMovePatched + " 次（游戏给刚落的子加高亮，那格认不出来时按票据补）");
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
