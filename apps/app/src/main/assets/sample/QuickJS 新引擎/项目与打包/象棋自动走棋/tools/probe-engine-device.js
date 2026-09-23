// @engine quickjs
/**
 * 设备端引擎探针：验证「被将盲区修复」+ 新预算（3500ms）在 K40 上实际到几层。
 * 用法：push 后 am start RunIntentActivity --es path <本文件>；结果看 logcat 前缀 [engprobe]。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) { /* 忽略 */ }
    return ".";
}());

function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[engprobe] " + text);
    }
}

const engine = load("src/engine/engine.js");

// ① 被将盲区回归：一步杀 depth1（修后必须 29999）
const MATE = "4k4/R2P1P3/4P4/9/9/9/9/9/9/4K4 w - - 0 1";
const m = engine.analyze(MATE, { depth: 1 });
log("一步杀 depth1：ok=" + m.ok + " 着法=" + m.iccs + " 评分=" + m.score);

// ② 中炮三四手：3500ms 实际到几层（新预算）
const MID = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C2C4/9/RNBAKABNR w - - 0 1";
const t0 = Date.now();
const r = engine.analyze(MID, { movetime: 3500 });
log("中盘 3500ms：ok=" + r.ok + " 深度=" + r.depth + " 结点=" + r.nodes +
    " 实际=" + r.ms + "ms（外层 " + (Date.now() - t0) + "ms）着法=" + r.iccs + " 评分=" + r.score);

// ③ 开局 3500ms
const OPEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
const r2 = engine.analyze(OPEN, { movetime: 3500 });
log("开局 3500ms：深度=" + r2.depth + " 结点=" + r2.nodes + " 实际=" + r2.ms + "ms 着法=" + r2.iccs);

log("完成");
