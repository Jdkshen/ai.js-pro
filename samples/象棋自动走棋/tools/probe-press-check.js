// @engine quickjs
/**
 * 按压通道机制验证（小型探针）：脚本进程内 shell 按压能不能落到游戏？
 * 前置：当前游戏选中 =(4,7) 红炮（7 个绿点）。本探针按 (2,7) 红炮——
 *   落地 → 换选成功 → 点数变 4（(2,7) 的 4 步）；
 *   不落地 → 点数仍 7。
 * 然后（对照组）用「先截图→按→再截图」流程重复一次。
 * 结果看 logcat（前缀 [presschk]）。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) { }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");
const locateBoardModule = load("src/vision/locate-board.js");
const pipelineFactory = load("src/vision/pipeline-factory.js");

function log(text) {
    try { console.log("[presschk] " + text); } catch (ignore) { }
}
function pause(ms) {
    try { if (typeof sleep === "function") { sleep(ms); } } catch (ignore) { }
}
function rgbOf(pix) {
    if (pix == null) { return null; }
    const n = Number(pix);
    return [(n >>> 16) & 255, (n >>> 8) & 255, n & 255];
}
const GOFFS = [-0.12, -0.08, -0.04, 0, 0.04, 0.08, 0.12];
function isGreenDot(rgb) {
    return !!rgb && (rgb[1] - rgb[2]) >= 55 && rgb[1] >= 140 && (rgb[1] - rgb[0]) >= -10;
}
function dotCells(image, grid) {
    const out = [];
    for (let r = 0; r < 10; r++) {
        for (let c = 0; c < 9; c++) {
            const cx = grid.originX + c * grid.stepX;
            const cy = grid.originY + r * grid.stepY;
            let hits = 0;
            for (let i = 0; i < GOFFS.length; i++) {
                for (let j = 0; j < GOFFS.length; j++) {
                    const x = Math.round(cx + GOFFS[j] * grid.stepX);
                    const y = Math.round(cy + GOFFS[i] * grid.stepY);
                    let rgb = null;
                    try { rgb = rgbOf(images.pixel(image, x, y)); } catch (ignore) { rgb = null; }
                    if (isGreenDot(rgb)) { hits += 1; }
                }
            }
            if (hits >= 2) { out.push(c + "," + r); }
        }
    }
    return out;
}

function main() {
    if (!adapter.requestCapture("portrait")) { log("截图授权失败"); return; }
    try { if (locateBoardModule.configureSift) { locateBoardModule.configureSift({ baseDir: BASE_DIR }); } }
    catch (ignore) { }
    const f0 = adapter.capture("full", 2000);
    if (!f0) { log("拿不到帧"); return; }
    const located = locateBoardModule.locateBoardOnScreen({ frame: f0, timeoutMs: 2500 });
    if (!located || !located.ok || !located.grid) { log("定位失败：" + ((located && located.reason) || "?")); return; }
    const grid = located.grid;
    log("几何 origin=(" + grid.originX.toFixed(1) + "," + grid.originY.toFixed(1) + ")");

    const before = dotCells(f0, grid);
    log("按前绿点 " + before.length + " 个：" + before.join(" "));

    // 焦点假说验证：留 12s 窗口（PC 侧把游戏拉回前台），再按压
    log("准备：12s 后按压 (2,7)——期间把游戏带回前台（焦点实验）");
    pause(12000);

    // 试验 1：脚本进程内 shell(Shizuku) 按压 (2,7)=(311,1504)
    let out = null;
    try {
        out = shell("input swipe 311 1504 311 1504 350", { shizuku: true, timeout: 20000 });
    } catch (error) { log("shell 异常：" + error); }
    log("shell 返回：" + (out == null ? "(空)" : String(out).slice(0, 80)));
    pause(1300);
    const f1 = adapter.capture("full", 2000);
    if (f1) {
        const after1 = dotCells(f1, grid);
        log("试验1 按后绿点 " + after1.length + " 个：" + after1.join(" ") +
            (after1.length !== before.length ? "（变了 ✓ 换选落地）" : "（没变 ✗ 没落地）"));
    }
    log("完成");
}

main();
