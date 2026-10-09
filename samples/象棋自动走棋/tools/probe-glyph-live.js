// @engine quickjs
/**
 * 字形识别链 · **对着当前屏幕**跑一遍（真机现场诊断，2026-09-25）。
 *
 * 与 `probe-glyph.js`（跑 9 帧图片集、算成绩）不同：这支抓**当前屏幕这一帧**，
 * 走完整条新链路（定位 → 字形识别 → 多尺度抢救 → 盘面级判决），然后把
 * **每个读不出的格子「为什么读不出」**逐项打出来：
 *   · 常规窗（±6/±20）的最高分与次名；
 *   · 每个抢救档（1.10/1.06/1.18/0.94）的最高分；
 *   · 窗内「亮面比」（真棋子 ≥0.4，被动画盖住的 <0.25）与最佳匹配的位移。
 *
 * 用法： adb push src assets/glyph-tpl tools/probe-glyph-live.js → 
 *        am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity -d file:///sdcard/…/tools/probe-glyph-live.js
 * 输出： logcat `[glyph-live]` + `/sdcard/xiangqi-p4/glyph-live.txt`
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
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

const adapter = load("src/runtime/aijs-adapter.js");
const locateBoard = load("src/vision/locate-board.js");
const glyph = load("src/vision/glyph-recognizer.js");
const glyphResolve = load("src/vision/glyph-resolve.js");

const TPL_DIR = BASE_DIR + "/assets/glyph-tpl";
const OUT = "/sdcard/xiangqi-p4/glyph-live.txt";

const lines = [];
function log(text) {
    console.log("[glyph-live] " + text);
    lines.push(text);
}

const host = {
    sizeOf: function (image) { return adapter.sizeOf(image); },
    grayscale: function (image) { return images.grayscale(image); },
    clip: function (image, x, y, w, h) { return images.clip(image, x, y, w, h); },
    threshold: function (image, t, max) { return images.threshold(image, t, max); },
    countColor: function (image, color, options) { return adapter.countColor(image, color, options); },
    matchTemplate: function (image, template, options) { return images.matchTemplate(image, template, options); },
    pixel: function (image, x, y) { return images.pixel(image, x, y); },
    red: function (pixel) { return colors.red(pixel); },
    green: function (pixel) { return colors.green(pixel); },
    blue: function (pixel) { return colors.blue(pixel); },
    resize: function (image, size) { return images.resize(image, size); },
    read: function (path) { return images.read(path); },
    nowMono: function () { return Date.now(); }
};

/** 每个读不出的格：逐档打分 + 亮面比 + 位移。 */
function diagnose(grayFrame, size, x, cy, col, row, templates, rec) {
    const TW = 34;
    const win = host.clip(grayFrame, x - TW, cy - TW, TW * 2, TW * 2);
    const face = host.threshold(win, 200, 255);
    const bright = (TW * TW * 4 - adapter.countColor(face, "#000000", {
        region: { x: 0, y: 0, width: TW * 2, height: TW * 2 }
    })) / (TW * TW * 4);
    const dark = host.threshold(win, 140, 255);
    const inner = host.clip(grayFrame, x - 20, cy - 20, 40, 40);
    const innerBin = host.threshold(inner, 140, 255);
    const innerRatio = adapter.countColor(innerBin, "#000000", {
        region: { x: 0, y: 0, width: 40, height: 40 }
    }) / 1600;
    const ranked = glyph.bestMatchRanked(host, grayFrame, x, cy, templates,
        { TW: TW, SEARCH_X: 6, SEARCH_Y: 20 }, size);
    let text = "  r" + row + "c" + col + " 亮面 " + bright.toFixed(3) + "｜内窗暗 " + innerRatio.toFixed(3) +
        "｜常规窗 " + (ranked ? (ranked.name + " " + ranked.sim.toFixed(3) + "（次 " +
            ranked.secondName + " " + ranked.secondSim.toFixed(3) + "，差 " + ranked.gap.toFixed(3) +
            "，位移 " + (ranked.cx - x) + "," + (ranked.cy - cy) + "）") : "无");
    const scales = glyph.DEFAULTS.RESCUE_SCALES;
    for (let s = 0; s < scales.length; s++) {
        const half = Math.max(16, Math.round(TW * scales[s]));
        const list = [];
        for (let i = 0; i < templates.length; i++) {
            list.push({
                name: templates[i].name,
                image: host.resize(templates[i].image, [half * 2, half * 2])
            });
        }
        const got = glyph.bestMatchRanked(host, grayFrame, x, cy, list,
            { TW: half, SEARCH_X: 8, SEARCH_Y: 22 }, size);
        text += "｜×" + scales[s] + " " + (got ? (got.name + " " + got.sim.toFixed(3)) : "无");
    }
    return text;
}

const templates = glyph.loadTemplates(host, TPL_DIR);
log("模板 " + templates.length + " 个（" + TPL_DIR + "）");

const granted = adapter.requestCapture("portrait");
log("截图授权：" + granted);
const frame = adapter.capture("full", 800);
if (!frame) {
    log("拿不到帧（截图通道不可用）");
} else {
    const located = locateBoard.locateBoardOnScreen({ frame: frame });
    if (!located || !located.ok) {
        log("定位失败：" + (located && located.reason));
    } else {
        const grid = located.grid;
        log("定位 " + (located.method || "?") + "：原点 (" + Math.round(grid.originX) + "," +
            Math.round(grid.originY) + ") 步长 (" + grid.stepX.toFixed(1) + "," + grid.stepY.toFixed(1) + ")" +
            " 用时 " + (located.timing ? located.timing.totalMs : "?") + "ms");
        const scale = glyph.scaleFor(grid);
        const prepared = glyph.prepareFor(host, templates, scale);
        const started = Date.now();
        const rec = glyph.recognize(host, frame, grid, {
            templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
            coarseTemplates: prepared.coarse,
            collect: true
        });
        log("识别：触子 " + rec.inked + "｜未知 " + rec.unknown + "｜复核 " + rec.retries +
            "｜抢救 " + (rec.rescued || 0) + "/" + (rec.rescues || 0) + " 次（" +
            Math.round(rec.rescueMs || 0) + "ms）｜耗时 " + rec.ms + "ms" +
            (Math.abs(scale - 1) >= 0.02 ? ("｜缩放 " + scale.toFixed(3)) : ""));
        const scores = {};
        (rec.cells || []).forEach(function (item) {
            if (item && item.idx != null && item.sim != null) {
                scores[item.idx] = item.sim;
            }
        });
        const resolved = glyphResolve.resolve(glyph.normalizeBoard(rec.chars), {
            scores: scores, notes: rec.notes || []
        });
        const chars = resolved.chars.map(function (ch) { return ch == null ? "." : ch; });
        log("判决：补 " + resolved.filled.length + "｜降 " + resolved.demoted.length +
            "｜翻 " + resolved.flipped.length + "｜未知 " + resolved.stats.unknownBefore +
            " → " + resolved.stats.unknownAfter);
        resolved.filled.forEach(function (it) {
            log("  补 r" + it.row + "c" + it.col + " →「" + it.char + "」（" + it.why + "）");
        });
        resolved.demoted.forEach(function (it) {
            log("  降 r" + it.row + "c" + it.col + "（" + (it.reason || "") + "）");
        });
        resolved.flipped.forEach(function (it) {
            log("  翻 r" + it.row + "c" + it.col + " " + it.from + "→" + it.to);
        });
        log("FEN: " + glyph.charsToFen(chars));
        // 逐格诊断：还留在 ? 的格子（含被判决降级出来的）
        const unknownIdx = [];
        for (let i = 0; i < chars.length; i++) {
            if (chars[i] === "?") {
                unknownIdx.push(i);
            }
        }
        if (!unknownIdx.length) {
            log("没有未知格");
        } else {
            log("未知格逐项诊断（" + unknownIdx.length + " 个）：");
            const grayFrame = host.grayscale(frame);
            for (let u = 0; u < unknownIdx.length; u++) {
                const idx = unknownIdx[u];
                const col = idx % 9;
                const row = Math.floor(idx / 9);
                const x = Math.round(grid.originX + col * grid.stepX);
                const cy = Math.round(grid.originY + row * grid.stepY + prepared.ANCHOR_DY);
                log(diagnose(grayFrame, adapter.sizeOf(frame), x, cy, col, row, prepared.templates, rec));
            }
        }
        log("耗时合计 " + (Date.now() - started) + "ms（识别部分）");
    }
    frame.recycle();
}

try {
    files.write(OUT, lines.join("\n"));
} catch (error) {
    console.log("[glyph-live] 写结果失败：" + error);
}
console.log("[glyph-live] DONE");
