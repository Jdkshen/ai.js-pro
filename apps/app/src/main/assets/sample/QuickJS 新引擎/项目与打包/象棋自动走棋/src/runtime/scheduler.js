// @engine quickjs
/**
 * 串行调度器（§14：主线程渲染、串行业务调度、取消）。
 *
 * 由来：面板里用裸 `setTimeout` 串循环，问题有三：
 *   · 停止时只能靠一个 `running` 标志位，已经在跑的 tick 收不住；
 *   · 算棋/抓帧都是重活，两个重活重叠会互相踩（心跳一度飙到 8s）；
 *   · "谁在跑、什么时候能再跑"只能靠人肉看日志。
 * 这里给一个最小可用的串行队列 + 取消令牌。
 */
"use strict";

function createScheduler(options) {
    const opts = options || {};
    const setTimer = opts.setTimeout || (typeof setTimeout === "function" ? setTimeout : null);
    const clearTimer = opts.clearTimeout || (typeof clearTimeout === "function" ? clearTimeout : null);
    const now = typeof opts.now === "function" ? opts.now : function () { return Date.now(); };

    let queue = [];
    let running = false;
    let cancelled = false;
    let timer = null;
    let generation = 0;
    const stats = { ran: 0, skipped: 0, cancelled: 0, lastMs: 0, maxMs: 0 };

    function token() {
        const mine = generation;
        return {
            get cancelled() { return cancelled || mine !== generation; },
            /** 任务里检查一下，能及时退出长循环。 */
            check: function () {
                if (cancelled || mine !== generation) {
                    throw new Error("任务已取消");
                }
            }
        };
    }

    function pump() {
        if (running || cancelled || queue.length === 0) {
            return;
        }
        const job = queue.shift();
        const active = token();
        running = true;
        const started = now();
        try {
            job.run(active);
            stats.ran += 1;
        } catch (error) {
            stats.skipped += 1;
            if (job.onError) {
                try {
                    job.onError(error);
                } catch (inner) {
                    // onError 自己出错就不再往下传，避免把调度器带崩
                }
            }
        } finally {
            const cost = now() - started;
            stats.lastMs = cost;
            stats.maxMs = Math.max(stats.maxMs, cost);
            running = false;
            if (!cancelled && queue.length > 0) {
                schedule(job.intervalMs || 0);
            }
        }
    }

    function schedule(delayMs) {
        if (cancelled || !setTimer) {
            return;
        }
        if (timer != null && clearTimer) {
            clearTimer(timer);
        }
        timer = setTimer(function () {
            timer = null;
            pump();
        }, Math.max(0, delayMs == null ? 0 : delayMs));
    }

    /**
     * 追加一个任务。
     * @param run(active) 任务体
     * @param options { intervalMs 完成后多久再跑下一轮, onError }
     */
    function push(run, options) {
        const opt = options || {};
        queue.push({ run: run, intervalMs: opt.intervalMs || 0, onError: opt.onError || null });
        pump();
    }

    /** 取消：清队列、加代际（正在跑的任务靠 active.check() 退出）。 */
    function cancel(reason) {
        cancelled = true;
        generation += 1;
        stats.cancelled += 1;
        queue = [];
        if (timer != null && clearTimer) {
            clearTimer(timer);
            timer = null;
        }
        return { reason: reason || "取消", stats: stats };
    }

    function reset() {
        cancelled = false;
        queue = [];
        stats.ran = 0;
        stats.skipped = 0;
        stats.lastMs = 0;
        stats.maxMs = 0;
    }

    return {
        push: push,
        schedule: schedule,
        cancel: cancel,
        reset: reset,
        token: token,
        isRunning: function () { return running; },
        pending: function () { return queue.length; },
        stats: function () {
            return {
                ran: stats.ran, skipped: stats.skipped, cancelled: stats.cancelled,
                lastMs: stats.lastMs, maxMs: stats.maxMs, pending: queue.length
            };
        }
    };
}

module.exports = { createScheduler: createScheduler };
