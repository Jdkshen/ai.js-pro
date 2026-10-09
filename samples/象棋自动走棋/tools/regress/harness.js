// @engine quickjs
/**
 * 场景回归框架（**同一份代码在 PC 与真机上跑**）。
 *
 * 为什么要有它（2026-09-20）：
 *   · 真机上反复踩的坑（帅/将没检出、OCR 读不出、几何整行偏、灰度重试）**大都不是模块级逻辑错**，
 *     而是**组装错 / 端到端行为错** —— self-check 那种「一个模块一个断言」的写法兜不住；
 *   · 真机复现成本高（截图 → 定位 → 跑一局 ~10s），而且很多故障要碰运气才出现。
 *   ⇒ 用 `tools/fake-vision-host.js` 把**一次真实故障**固化成一条场景：造宿主 → 跑流水线 → 断言，
 *     在 PC 上用一条命令跑完全部场景（`node tools/run-regress.js`），真机上跑同一份场景。
 *
 * 场景模块的写法：
 * ```js
 * module.exports = {
 *     name: "帅/将抢救（有框）：YOLO 框类别当候选",
 *     tags: ["pipeline", "king"],
 *     run: function (t) {                       // t = 断言上下文（见下）
 *         const host = t.host({ pieces: [...] });          // 假宿主
 *         const pipeline = t.pipeline(host);               // 接上视觉流水线
 *         const result = t.recognize(pipeline, host, 3);   // 连跑 3 帧（多帧融合/投票需要）
 *         t.equal(result.chars[85], "K", "(4,9) 应当被抢救成红帅");
 *         t.ok(result.validation.ok, "规则门应当通过");
 *     }
 * };
 * ```
 * `t` 提供：`ok/notOk/equal/notEqual/deepEqual/fail/skip/log`、`host()`、`pipeline(host, extra)`、
 * `recognize(pipeline, host, frames)`、`load(rel)`、`env`（"node"/"quickjs"）、`device`。
 * 断言失败不会中断场景（会把全部失败列出来）；`t.skip("原因")` 用来跳过设备/PC 专属场景。
 */
"use strict";

/** 断言信息里的值：JSON 化，太长的截断（日志里要能读） */
function show(value) {
    if (value === undefined) {
        return "undefined";
    }
    let text;
    try {
        text = JSON.stringify(value);
    } catch (error) {
        text = String(value);
    }
    if (text === undefined) {
        text = String(value);
    }
    return text && text.length > 200 ? text.slice(0, 200) + "…" : text;
}

/** 造一个场景模块的断言上下文 */
function makeContext(env, load) {
    const failures = [];
    const logs = [];
    let assertions = 0;
    function fail(message) {
        assertions += 1;
        failures.push(message == null ? "断言失败" : String(message));
    }
    const t = {
        env: env,
        device: env !== "node",
        failures: failures,
        logs: logs,
        count: function () { return assertions; },
        log: function (message) { logs.push(String(message)); },
        load: function (rel) { return load(rel); },
        ok: function (condition, message) {
            assertions += 1;
            if (!condition) {
                failures.push(message == null ? "应当为真" : String(message));
            }
            return !!condition;
        },
        notOk: function (condition, message) {
            assertions += 1;
            if (condition) {
                failures.push((message == null ? "应当为假" : String(message)) + "｜实际 " + show(condition));
            }
            return !condition;
        },
        equal: function (actual, expected, message) {
            assertions += 1;
            if (actual !== expected) {
                failures.push((message == null ? "值不相等" : String(message)) +
                    "｜期望 " + show(expected) + "，实际 " + show(actual));
                return false;
            }
            return true;
        },
        notEqual: function (actual, expected, message) {
            assertions += 1;
            if (actual === expected) {
                failures.push((message == null ? "不该相等" : String(message)) + "｜都是 " + show(actual));
                return false;
            }
            return true;
        },
        deepEqual: function (actual, expected, message) {
            assertions += 1;
            const a = show(actual);
            const b = show(expected);
            if (a !== b) {
                failures.push((message == null ? "结构不相等" : String(message)) + "｜期望 " + b + "，实际 " + a);
                return false;
            }
            return true;
        },
        fail: fail,
        skip: function (reason) {
            const error = new Error("scenario skipped");
            error.__skip = true;
            error.reason = reason == null ? "跳过" : String(reason);
            throw error;
        }
    };
    // —— 场景常用装配（省得每个场景重抄一遍宿主→流水线的接线）——
    t.host = function (options) {
        return t.load("tools/fake-vision-host.js").createFakeHost(options);
    };
    t.pipeline = function (host, extra) {
        const module = t.load("experiments/vision/vision-pipeline.js");
        const options = {
            detect: host.detect,
            clip: host.clip,
            rgbAt: host.rgbAt,
            countColor: host.countColor,
            prepareRegion: host.prepareRegion,
            releaseFrame: host.releaseFrame,
            ocrSession: host.ocrSession,
            log: function (message) { logs.push("[pipeline] " + message); }
        };
        if (extra) {
            for (const key in extra) {
                options[key] = extra[key];
            }
        }
        return module.create(options);
    };
    /** 连跑 n 帧（多帧融合 / 颜色投票 / 抢救都要跑几帧才稳定），返回最后一帧结果 */
    t.recognize = function (pipeline, host, frames, context) {
        const count = frames == null ? 3 : frames;
        let result = null;
        for (let i = 0; i < count; i++) {
            result = pipeline.recognize({}, host.grid, context || { side: "w" });
        }
        return result;
    };
    return t;
}

function createHarness() {
    const cases = [];

    /** 注册一个场景模块（`{ name, tags?, run(t) }`） */
    function register(module, source) {
        if (!module || typeof module.run !== "function" || !module.name) {
            throw new Error("场景模块必须导出 { name, run(t) }：" + (source || "?"));
        }
        cases.push({
            name: String(module.name),
            tags: module.tags || [],
            run: module.run,
            source: source || ""
        });
    }

    /**
     * 跑全部（或 `only` 过滤）场景。
     * @param options { env, load, only, onCase }
     * @return { env, passed, failed, skipped, ms, cases: [{ name, ok, assertions, failures, skipped, ms, logs }] }
     */
    function runAll(options) {
        const opts = options || {};
        const env = opts.env || "node";
        const load = opts.load;
        const only = opts.only ? String(opts.only) : null;
        const onCase = opts.onCase || function () { };
        const report = { env: env, passed: 0, failed: 0, skipped: 0, ms: 0, cases: [] };
        const startedAt = Date.now();
        for (let i = 0; i < cases.length; i++) {
            const one = cases[i];
            const haystack = one.name + " " + one.tags.join(" ");
            if (only && haystack.indexOf(only) < 0) {
                continue;
            }
            const t = makeContext(env, load);
            const started = Date.now();
            let skipped = null;
            let error = null;
            try {
                one.run(t);
            } catch (caught) {
                if (caught && caught.__skip) {
                    skipped = caught.reason;
                } else {
                    error = caught;
                }
            }
            const record = {
                name: one.name,
                tags: one.tags,
                source: one.source,
                ok: !error && !skipped && t.failures.length === 0,
                assertions: t.count(),
                failures: t.failures.slice(),
                error: error ? String((error && error.message) || error) : null,
                skipped: skipped,
                ms: Date.now() - started,
                logs: t.logs.slice()
            };
            if (record.ok) {
                report.passed += 1;
            } else if (skipped) {
                report.skipped += 1;
            } else {
                report.failed += 1;
            }
            report.cases.push(record);
            onCase(record);
        }
        report.ms = Date.now() - startedAt;
        return report;
    }

    return { register: register, runAll: runAll, count: function () { return cases.length; } };
}

module.exports = { createHarness: createHarness, show: show };
