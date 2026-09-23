// @engine quickjs
/**
 * 分段计时（方案 §7「性能预算」靠它落数字）。
 *
 * 为什么要有它（2026-09-20 用户口径「先只做性能分段计时」）：
 *   真机上「一轮观察 7~11s」是个黑盒 —— 抓帧？稳定帧？YOLO？逐格 OCR？找色？规则门？还是退回旧路径？
 *   只有把它拆成分段数字，才知道该优化哪儿（方案 §7 的目标是「单次完整识别 ≤1.2s」）。
 *
 * 用法（**必须在 try/finally 里收尾**，异常路径也要留一份数字）：
 * ```js
 * const t = timing.create();
 * ...
 * t.mark("抓帧");
 * ...
 * t.mark("流水线");
 * ...
 * const summary = t.summary();      // { sections: [{name, ms}], total }
 * ```
 *
 * 设计约束：
 *   · **纯 JS、零依赖**（PC 自检与真机 QuickJS 同一份）；
 *   · `summary()` 之后不再计时（避免"边打印边涨"）；
 *   · `format()` 与 `create()` 在这里定义，调用方（面板/探针/服务层）不用各自拼字符串。
 */
"use strict";

/**
 * @param options { now? } 可注入时钟（离线自检用它做确定性断言）
 */
function create(options) {
    const opts = options || {};
    const now = typeof opts.now === "function" ? opts.now : function () { return Date.now(); };
    const sections = [];
    let lastAt = now();
    const startedAt = lastAt;
    let closed = false;

    function elapsed() {
        return now() - startedAt;
    }

    return {
        /** 打一个分段点：记下「距上一个点」的耗时 */
        mark: function (name) {
            const at = now();
            sections.push({ name: String(name), ms: at - lastAt });
            lastAt = at;
            return at - startedAt;
        },
        elapsed: elapsed,
        /**
         * 收尾并返回快照。
         * @param tail 最后一个分段的默认名字（默认「其它」）；传 null 表示不补这一段
         */
        summary: function (tail) {
            if (!closed) {
                const name = tail === undefined ? "其它" : tail;
                if (name) {
                    this.mark(name);
                }
                closed = true;
            }
            let total = 0;
            for (let i = 0; i < sections.length; i++) {
                total += sections[i].ms;
            }
            return { sections: sections.slice(), total: Math.round(total) };
        },
        sections: function () { return sections.slice(); }
    };
}

/**
 * 快照 → 一行文本：`抓帧 1240ms｜流水线 1880ms｜合计 3120ms`
 * 只保留 ≥1ms 的分段里耗时最长的前 N 段？——不：**全都要看**（漏掉的那段才是真凶）。
 */
function format(summary, prefix) {
    if (!summary || !summary.sections || !summary.sections.length) {
        return (prefix ? prefix + "：" : "") + "（没有分段数据）";
    }
    const parts = [];
    for (let i = 0; i < summary.sections.length; i++) {
        const one = summary.sections[i];
        parts.push(one.name + " " + Math.round(one.ms) + "ms");
    }
    parts.push("合计 " + Math.round(summary.total) + "ms");
    return (prefix ? prefix + "：" : "") + parts.join("｜");
}

module.exports = { create: create, format: format };
