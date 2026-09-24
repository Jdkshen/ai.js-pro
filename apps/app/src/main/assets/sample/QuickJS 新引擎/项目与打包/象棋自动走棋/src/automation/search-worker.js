// @engine quickjs
"use strict";
/**
 * 把「算棋」搬到**独立 QuickJS 引擎（自己的线程）**里跑，主脚本线程不再被 ~1.2s 的搜索占住。
 *
 * 为什么要有它（K40 真机实测）：采集/识别/算棋原本都排在脚本线程上，
 * 算棋一步约 1.2~1.4s，期间悬浮窗的触摸事件、心跳全被挡住 —— 观感就是「点了没反应」。
 * 实测（小米8 / K40）：worker 算棋时主线程最长心跳间隔 ≈ 1009ms（= 空载），
 * 两个 worker 并发 1368ms（串行应 ~2400ms）→ 真并行。
 *
 * 三条硬约束（都用真机验证过，别踩）：
 *   1) worker 的源码是**内存串**，没有 __dirname（`scriptDirectory("QuickJS-Thread")` 得到 "."），
 *      所以 worker 里 require 必须用**绝对路径** —— 由 __args.dir 传进来。
 *   2) 任务函数会被序列化成 `(fn)();` 执行，**不能捕获外层闭包**，数据只能走 __args（JSON）。
 *   3) 脚本体形式（threads.start("...")）里写顶层 `return` 会报
 *      `SyntaxError: return not in a function` —— 要用**函数形式**。
 *
 * 句柄的 `getResult()` 是**非阻塞**的：{status:'running'} / {status:'success', value} /
 * {status:'error', error}，所以主线程「起任务 + 每轮 poll 一次」即可。
 */

/**
 * worker 侧任务（会被序列化后在独立引擎里执行，只能用 __args 与引擎全局）。
 * 返回 JSON 文本（跨引擎回传；主线程侧再还原成对象）。
 */
function workerTask() {
    var dir = __args.dir;
    // 走 engine-router：worker 里必须跟主线程同一个后端（否则面板选 Pikafish、实际还是 JS 在算）。
    // worker 是独立引擎实例，模块状态不共享 —— 后端与配置只能从 __args 带进来。
    var engine = require(dir + "/src/engine/engine-router.js");
    if (__args.engine != null) {
        engine.setBackend(String(__args.engine));
    }
    if (__args.pikafish != null) {
        engine.configurePikafish(__args.pikafish);
    }
    var options = {};
    if (__args.movetime != null) {
        options.movetime = __args.movetime;
    }
    if (__args.depth != null) {
        options.depth = __args.depth;
    }
    // 传进来的是 90 格数组时才有用（FEN 里已经带了走棋方）；不传就让引擎自己判
    if (__args.side != null) {
        options.side = __args.side;
    }
    // 规划期的排除表（游戏拒绝过的着法 + 疑似循环/长捉的着法）：以前这条路上漏传了，
    // 结果 worker 模式下黑名单完全不生效（自检 2026-09-21 补）。
    if (__args.excludeIccs != null) {
        options.excludeIccs = __args.excludeIccs;
    }
    if (__args.positionCommand != null) {
        options.positionCommand = __args.positionCommand;
        options.historyStatus = __args.historyStatus;
    }
    var result = engine.analyze(__args.position, options);
    return JSON.stringify({
        ok: !!result.ok,
        engine: result.engine || engine.lastEngine() || null,
        historyUsed: result.historyUsed === true,
        historyStatus: result.historyStatus || "fen",
        iccs: result.iccs || null,
        score: result.score == null ? null : result.score,
        depth: result.depth == null ? null : result.depth,
        nodes: result.nodes == null ? null : result.nodes,
        ms: result.ms == null ? null : result.ms,
        mateIn: result.mateIn == null ? null : result.mateIn,
        reason: result.reason || ""
    });
}

/** 宿主有没有 threads（Rhino 或裁剪过的引擎可能没有）→ 没有就退回同步算棋。 */
function available() {
    return typeof threads !== "undefined" && threads != null &&
        typeof threads.start === "function" && typeof threads.start === "function";
}

/**
 * 还原 worker 回传的载荷。
 * ⚠️ 真机实测（小米8）：引擎在 makeEngineHandle.getResult() 里已经会把「以 { 开头的字符串」
 * 自动 JSON.parse 成对象（restoreResultValue），所以这里**对象和字符串都要认** ——
 * 只认字符串会得到 `worker 回传的内容解析不了：[object Object]`。
 */
function decode(value) {
    if (value == null) {
        return null;
    }
    if (typeof value === "object") {
        return value;
    }
    if (typeof value !== "string") {
        return null;
    }
    const text = value.trim();
    if (text.length === 0 || text.charAt(0) !== "{") {
        return null;
    }
    try {
        return JSON.parse(text);
    } catch (error) {
        return null;
    }
}

/**
 * 管理「一个时刻最多一次」的算棋任务。
 * @param options.dir        项目目录（worker 里 require 的绝对路径前缀）
 * @param options.graceMs    结果超时下限（ms）：worker 卡死时也必须有结论，默认 4000；
 *                           单次任务实际用 max(graceMs, movetime + 2000)（Pikafish 会用满 movetime，见 start）
 * @param options.nowMono    单调时钟
 * @param options.log        日志函数
 */
function create(options) {
    const opts = options || {};
    const dir = String(opts.dir || ".");
    const graceMs = opts.graceMs == null ? 4000 : Math.max(1000, Number(opts.graceMs));
    const nowMono = opts.nowMono || function () { return Date.now(); };
    const log = opts.log || function () { };

    let thread = null;
    let pending = null;

    function cancel() {
        if (thread) {
            try {
                thread.interrupt();
            } catch (error) {
                // 已经在退出/已结束：无所谓
            }
        }
        thread = null;
        pending = null;
    }

    /**
     * 起一次算棋（非阻塞）。
     * @returns true = 已交给 worker；false = 起不来（调用方退回同步算棋）
     */
    function start(position, searchOptions) {
        const search = searchOptions || {};
        cancel();
        if (!available()) {
            return false;
        }
        try {
            thread = threads.start(workerTask, {
                dir: dir,
                position: position,
                side: search.side == null ? null : search.side,
                movetime: search.movetime == null ? null : search.movetime,
                depth: search.depth == null ? null : search.depth,
                excludeIccs: (search.excludeIccs && search.excludeIccs.length) ? search.excludeIccs : null,
                positionCommand: search.positionCommand || null,
                historyStatus: search.historyStatus || "unknown",
                engine: search.engine == null ? null : String(search.engine),
                pikafish: search.pikafish || null
            });
        } catch (error) {
            thread = null;
            log("worker 起不来，回退同步算棋：" + String(error && error.message ? error.message : error));
            return false;
        }
        pending = {
            startedAtMono: nowMono(),
            movetimeMs: search.movetime || 0,
            // 本次任务的超时：Pikafish（原生引擎）会把 movetime **用满**才返回 bestmove，
            // 固定的 graceMs(4000) 在 movetime 调到 4000 以上时会把「正常算棋」误判成 worker 超时
            // （真机踩点：movetime 3500 + grace 4000 只富余 500ms）→ 用 movetime+2000 保底。
            deadlineMs: Math.max(graceMs, (search.movetime || 0) + 2000)
        };
        return true;
    }

    /**
     * 非阻塞收结果。
     * @returns { status: "idle" }                     没有任务
     *          { status: "running", elapsedMs }       还在算
     *          { status: "done", result, elapsedMs }  算完（result 为 worker 回传对象）
     *          { status: "error", error, elapsedMs }  失败了
     *          { status: "timeout", elapsedMs }       超过本次任务超时（见 start 的 deadlineMs）还没结论
     */
    function poll() {
        if (!thread || !pending) {
            return { status: "idle" };
        }
        const elapsed = nowMono() - pending.startedAtMono;
        let state;
        try {
            state = thread.getResult();
        } catch (error) {
            state = { status: "error", error: String(error && error.message ? error.message : error) };
        }
        if (state && state.status === "success") {
            const result = decode(state.value);
            cancel();
            if (!result) {
                return {
                    status: "error",
                    error: "worker 回传的内容解析不了：" + String(state.value).slice(0, 80),
                    elapsedMs: elapsed
                };
            }
            return { status: "done", result: result, elapsedMs: elapsed };
        }
        if (state && state.status === "error") {
            cancel();
            return { status: "error", error: state.error || "worker 失败", elapsedMs: elapsed };
        }
        if (elapsed > pending.deadlineMs) {
            cancel();
            return { status: "timeout", elapsedMs: elapsed };
        }
        return { status: "running", elapsedMs: elapsed };
    }

    function isRunning() {
        return !!thread;
    }

    return {
        dir: dir,
        graceMs: graceMs,
        start: start,
        poll: poll,
        cancel: cancel,
        isRunning: isRunning
    };
}

module.exports = {
    create: create,
    available: available,
    workerTask: workerTask,
    /** 导出给离线自检用（引擎会把 JSON 串提前反序列化成对象，两种形态都得认）。 */
    decode: decode
};
