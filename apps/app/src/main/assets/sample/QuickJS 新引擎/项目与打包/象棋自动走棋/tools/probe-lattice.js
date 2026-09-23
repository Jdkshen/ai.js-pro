// @engine quickjs
/**
 * 全屏格线探测器（lattice-detector.js）真机探针（2026-09-22，多证据融合 P1）。
 *
 * 素材：scr-now.png（1080×2400，真值：竖 82+115.9k / 横 695+114k）、scr-live.png（同一局的活帧）。
 * 目标（一次问清四件事）：
 *   ① 召回——能否恢复 origin/step，各线误差多少；
 *   ② 成本——找色调用数与各阶段耗时（响应/拟合/合计）；
 *   ③ 参考色 cast 的必要性（单参考 vs 三参考，实测 scr-live 上单参考会散）；
 *   ④ 扫描步长的成本档位（step 4 vs 6）。
 * 用法：adb push 本文件 + 两张图 → 再
 *   am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path /sdcard/xiangqi-p4/proj/tools/probe-lattice.js
 * 日志前缀 [latprobe]。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) { /* 默认当前目录 */ }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");
const lattice = load("src/vision/lattice-detector.js");

const V_LINES = [82, 197, 312, 427, 542, 657, 772, 887, 1002];
const H_LINES = [695, 809, 923, 1037, 1151, 1265, 1379, 1493, 1607, 1721];
const IMAGES = [
    "/sdcard/xiangqi-p4/probe/scr-now.png",
    "/sdcard/xiangqi-p4/probe/scr-live.png"
];

const VARIANTS = [
    { key: "默认(3参考/step4)", options: {} },
    { key: "单参考/step4     ", options: { refs: [{ color: "#A58769", threshold: 50 }] } },
    { key: "3参考/step6      ", options: { scanStep: 6 } }
];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[latprobe] " + text);
    }
}

function hostFromAdapter() {
    return {
        sizeOf: function (image) { return adapter.sizeOf(image); },
        countColor: function (image, color, options) { return adapter.countColor(image, color, options); }
    };
}

function lineError(fit, truth) {
    let sum = 0;
    let worst = 0;
    const list = [];
    for (let k = 0; k < truth.length; k++) {
        const predicted = fit.origin + k * fit.step;
        const error = Math.abs(predicted - truth[k]);
        list.push(Math.round(predicted));
        sum += error;
        if (error > worst) {
            worst = error;
        }
    }
    return { mean: sum / truth.length, worst: worst, list: list };
}

for (let i = 0; i < IMAGES.length; i++) {
    const path = IMAGES[i];
    let image = null;
    try {
        image = images.read(path);
    } catch (error) {
        log("读图异常 " + path + "：" + error);
        continue;
    }
    if (!image) {
        log("读图失败（空）" + path);
        continue;
    }
    const size = adapter.sizeOf(image);
    log("== " + path.replace(/^.*\//, "") + "（" + (size ? size.width + "x" + size.height : "?") + "）==");
    try {
        const probe = images.findAllPointsForColor(image, "#A58769", { region: [10, 10, 8, 8], threshold: 50 });
        log("  找色能力试探：ok len=" + (probe ? probe.length : probe) +
            "；adapter.countColor=" + adapter.countColor(image, "#A58769", { region: { x: 10, y: 10, width: 8, height: 8 }, threshold: 50 }));
    } catch (error) {
        log("  找色能力试探异常：" + error);
    }
    for (let v = 0; v < VARIANTS.length; v++) {
        const variant = VARIANTS[v];
        let out = null;
        try {
            out = lattice.detect(hostFromAdapter(), image, variant.options);
        } catch (error) {
            log("  [" + variant.key + "] 异常：" + error);
            continue;
        }
        if (!out || !out.ok) {
            log("  [" + variant.key + "] 失败：" + (out ? out.reason : "无结果") +
                "（calls=" + (out ? out.calls : "-") + "）");
            continue;
        }
        const ev = out.evidence;
        const vErr = lineError(ev.vertical, V_LINES);
        const hErr = lineError(ev.horizontal, H_LINES);
        log("  [" + variant.key + "] ✓ 竖 origin=" + ev.vertical.origin.toFixed(1) + " step=" +
            ev.vertical.step.toFixed(1) + "（真 82 / 115.9；平均误差 " + vErr.mean.toFixed(1) +
            "px，最差 " + vErr.worst.toFixed(1) + "，matched " + ev.vertical.matched + "/9）");
        log("     横 origin=" + ev.horizontal.origin.toFixed(1) + " step=" +
            ev.horizontal.step.toFixed(1) + "（真 695 / 114；平均误差 " + hErr.mean.toFixed(1) +
            "px，最差 " + hErr.worst.toFixed(1) + "，matched " + ev.horizontal.matched + "/10）");
        log("     calls=" + out.calls + "｜响应 " + out.timing.responseMs + "ms（竖 " +
            out.timing.respXMs + " / 横 " + out.timing.respYMs + "）｜拟合 " + out.timing.fitMs +
            "ms｜合计 " + out.timing.totalMs + "ms｜score=" + out.score.toFixed(2) +
            "｜方形差 " + (out.squareness * 100).toFixed(1) + "%");
        log("     竖线预测 " + vErr.list.join(","));
        log("     横线预测 " + hErr.list.join(","));
    }
    try { image.recycle(); } catch (ignore) { /* 忽略 */ }
}
log("结束");
