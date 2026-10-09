// @engine quickjs
/**
 * 定长日志环（诊断用）。
 *
 * 为什么单独成模块：真机上 `auto-driver.js` 曾经这样写
 *
 *     const history = [];
 *     ...
 *     history = history.slice(-200);     // ← 常量重新赋值
 *
 * 一旦日志超过上限（一轮真机跑 200~320 条，正好越线）就会抛
 * "Assignment to constant variable"，崩在「记录日志」这一步 —— 长跑测试必须能覆盖到它。
 * 这里改成**原地裁剪**（splice），并且把上限策略集中到一处，避免以后再写出同类代码。
 */
"use strict";

/**
 * @param limit 超过多少条开始裁剪（默认 300）
 * @param keep  裁剪后保留末尾多少条（默认 200）
 */
function createLogRing(limit, keep) {
    const max = limit == null ? 300 : Number(limit);
    const kept = keep == null ? 200 : Number(keep);
    const items = [];

    function push(entry) {
        items.push(entry);
        if (items.length > max) {
            // ⚠️ 必须原地裁剪：`items = items.slice(...)` 会破坏外部持有的引用
            items.splice(0, items.length - kept);
        }
        return items.length;
    }

    return {
        push: push,
        all: function () { return items.slice(); },
        size: function () { return items.length; },
        last: function () { return items.length > 0 ? items[items.length - 1] : null; },
        limit: max,
        keep: kept
    };
}

module.exports = { createLogRing: createLogRing };
