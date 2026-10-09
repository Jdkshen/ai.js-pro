// @engine quickjs
/**
 * 诊断记录器（§14：被动记录，不反向改变轮次或产生点击）。
 *
 * 职责：
 *   · 面板上那一行行日志（限频、截断、保底长度）；
 *   · 结构化事件（便于回填报告与离线审）；
 *   · 审计导出（脚本版本/指纹/配置/通道/次数/结论）。
 * 明确不做：不做决策、不点击、不改状态机。
 */
"use strict";

const logRing = require("./log-ring.js");

function createRecorder(options) {
    const opts = options || {};
    const sink = typeof opts.sink === "function" ? opts.sink : function () { };
    const now = typeof opts.now === "function" ? opts.now : function () { return Date.now(); };
    const ring = logRing.createLogRing(opts.limit == null ? 400 : opts.limit,
        opts.keep == null ? 250 : opts.keep);
    const lastAtByKey = {};
    const counts = {};

    /** 面板日志：同时进环、进结构化事件、写给宿主。 */
    function log(text, data) {
        const line = String(text == null ? "" : text);
        const entry = { at: now(), kind: "log", text: line, data: data || null };
        ring.push(entry);
        try {
            sink(line);
        } catch (error) {
            // 日志本身绝不能把业务带崩（真机：sink 抛错会把整个 tick 变成异常）
        }
        return entry;
    }

    /** 事件：进环 + 计数（不打印，避免刷屏）。 */
    function event(kind, data) {
        counts[kind] = (counts[kind] || 0) + 1;
        const entry = { at: now(), kind: kind, data: data || null };
        ring.push(entry);
        return entry;
    }

    /**
     * 限频日志：同一个 key 在 windowMs 内只打一次；返回 true 表示这次真的打了。
     * 用途：诊断行（基准/观测/容差统计）在卡死时会刷屏。
     */
    function logThrottled(key, windowMs, text, data) {
        const stamp = now();
        const last = lastAtByKey[key];
        const window = windowMs == null ? 10000 : windowMs;
        if (last != null && (stamp - last) < window) {
            return false;
        }
        lastAtByKey[key] = stamp;
        log(text, data);
        return true;
    }

    return {
        log: log,
        event: event,
        logThrottled: logThrottled,
        counts: function () { return Object.assign({}, counts); },
        count: function (kind) { return counts[kind] || 0; },
        recent: function (n) { return ring.all().slice(-(n == null ? 50 : n)); },
        size: function () { return ring.size(); },
        /** 审计导出：给报告/离线审用。 */
        summary: function () {
            return { size: ring.size(), counts: counts, last: ring.last() };
        }
    };
}

module.exports = { createRecorder: createRecorder };
