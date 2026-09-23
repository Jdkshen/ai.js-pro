// @engine quickjs
/**
 * 字形模板识别 · 9 帧皮肤评测 + 跨分辨率抽查（2026-09-24，电脑 py 移植版）。
 *
 * 素材：`/sdcard/xiangqi-p4/skin-eval/{skin-a,b,c,skin2-01..06}.png`（K40 浅木皮肤，含真值）
 *       `/sdcard/xiangqi-p4/newphone-now-1280x2772.png`（新手机 1280 宽，无真值，只做缩放抽查）
 * 模板：工程 `assets/glyph-tpl/tpl_<字>.png`（11 个，68×68，2026-09-22 采集）
 * 期望：9 帧合计 763/784 = 97.3%（与电脑 py 逐帧一致）；新手机帧缩放后 32 子、0 未知。
 * 结果：logcat `[glyph]` + `/sdcard/xiangqi-p4/glyph-probe.txt`
 * 跑法（先 force-stop，否则不重跑）：
 *   am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity \
 *     --es path "/sdcard/脚本/<工程名>/tools/probe-glyph.js"
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
const locateBoard = load("tools/locate-board.js");
const glyph = load("src/vision/glyph-recognizer.js");

const TPL_DIR = BASE_DIR + "/assets/glyph-tpl";
const FRAME_DIR = "/sdcard/xiangqi-p4/skin-eval";
const EXTRA_FRAME = "/sdcard/xiangqi-p4/newphone-now-1280x2772.png";
const OUT = "/sdcard/xiangqi-p4/glyph-probe.txt";

const FRAMES = ["skin-a.png", "skin-b.png", "skin-c.png",
    "skin2-01.png", "skin2-02.png", "skin2-03.png",
    "skin2-04.png", "skin2-05.png", "skin2-06.png"];

const TRUTH = {
    "skin-a.png": "rnbakab1r/9/3c2nc1/p1p1p1p1p/9/9/P1P1P1P1P/2N1C2C1/9/R1BAKABNR",
    "skin-b.png": "rnbakab1r/9/1c4nc1/p1p1p1p1p/9/9/P1P1P1P1P/4C2C1/9/RNBAKABNR",
    "skin-c.png": "rnbakab1r/9/3c2nc1/p1p1p3p/6p2/7C1/P1P1P1P1P/2N1C4/9/R1BAKABNR",
    "skin2-01.png": "rnbakab1r/9/3c2nc1/p1p1p3p/6p2/7C1/P1P1P1P1P/C1N1?4/9/?1BAKA?NR",
    "skin2-02.png": "r1bakab1r/9/2nc2nc1/p1p1p3p/6p2/7?1/P1P1P1P1P/C1N1?4/9/2BAKA?NR",
    "skin2-03.png": "r1bakab1r/9/2nc2nc1/p1p1p3p/6p2/7?1/P1P1P1P1P/C1N1?3C/9/2BAKA?N?",
    "skin2-04.png": "r1bakab1r/9/2c2?n2/p1p5p/4?1p2/7?1/P1P1P1P1P/C1N1?3C/9/2BAKA??1",
    "skin2-05.png": "r1bakab1r/9/2c2?n2/p1p1p3p/6p2/7?1/P1P1P1P1P/C1N1?3C/9/2BAKA??1",
    "skin2-06.png": "r1bakab1r/9/2c2?n2/p1p1p3p/6p2/7?1/P1P1P1P1P/C1N1?3C/9/2BAKA??1"
};

const lines = [];
function log(text) {
    console.log("[glyph] " + text);
    lines.push(text);
}

/** 引擎宿主映射（字形识别走 images.* / colors.*，尺寸与计时走 adapter）。 */
function makeHost() {
    return {
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
        nowMono: function () { return adapter.nowMono(); }
    };
}

function truthChars(fen) {
    const out = [];
    const rows = fen.split(" ")[0].split("/");
    for (let i = 0; i < rows.length; i++) {
        for (let j = 0; j < rows[i].length; j++) {
            const ch = rows[i][j];
            if (ch >= "1" && ch <= "9") {
                const n = Number(ch);
                for (let k = 0; k < n; k++) {
                    out.push(".");
                }
            } else {
                out.push(ch);
            }
        }
    }
    return out;
}

function runFrame(host, templates, path) {
    const image = images.read(path);
    if (!image) {
        return { error: "读图失败" };
    }
    const located = locateBoard.locateBoardInImage(image);
    if (!located.ok) {
        image.recycle();
        return { error: "定位失败：" + located.reason };
    }
    const scale = glyph.scaleFor(located.grid);
    const prepared = glyph.prepareFor(host, templates, scale);
    const rec = glyph.recognize(host, image, located.grid, {
        templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
        coarseTemplates: prepared.coarse
    });
    image.recycle();
    rec.scale = scale;
    rec.TW = prepared.TW;
    rec.coarse = !!prepared.coarse;
    rec.locateMs = located.timing.locateMs;
    return rec;
}

const host = makeHost();
const templates = glyph.loadTemplates(host, TPL_DIR);
log("模板加载完成：" + templates.length + " 个（" + TPL_DIR + "）");

let totalOk = 0;
let totalBad = 0;
for (let i = 0; i < FRAMES.length; i++) {
    const name = FRAMES[i];
    const rec = runFrame(host, templates, FRAME_DIR + "/" + name);
    if (rec.error) {
        log(name + " " + rec.error);
        continue;
    }
    const truth = truthChars(TRUTH[name]);
    let ok = 0;
    let bad = 0;
    for (let k = 0; k < 90; k++) {
        if (truth[k] === "?") {
            continue;
        }
        if (rec.chars[k] === truth[k]) {
            ok += 1;
        } else {
            bad += 1;
        }
    }
    totalOk += ok;
    totalBad += bad;
    log(name + " 准确 " + ok + "/" + (ok + bad) + " = " + ((ok / Math.max(1, ok + bad)) * 100).toFixed(1) + "%" +
        "  触子 " + rec.inked + " 未知 " + rec.unknown +
        "  定位 " + rec.locateMs + "ms 识别 " + rec.ms + "ms" +
        (Math.abs(rec.scale - 1) >= 0.02 ? ("  缩放 " + rec.scale.toFixed(3)) : ""));
    log("   FEN: " + glyph.charsToFen(rec.chars));
    if (rec.profile) {
        log("   分段：门 " + rec.profile.gates + "ms／匹配 " + rec.profile.match +
            "ms（复核 " + rec.retries + " 次）／阵营 " + rec.profile.side + "ms");
    }
}
log("合计 " + totalOk + "/" + (totalOk + totalBad) + " = " +
    ((totalOk / Math.max(1, totalOk + totalBad)) * 100).toFixed(1) + "%");

// —— 跨分辨率抽查（新手机 1280 宽；模板需按板距缩放） ——
log("跨分辨率抽查：" + EXTRA_FRAME);
const extra = runFrame(host, templates, EXTRA_FRAME);
if (extra.error) {
    log("  " + extra.error);
} else {
    log("  scale=" + extra.scale.toFixed(3) + " TW=" + extra.TW +
        " 触子 " + extra.inked + " 未知 " + extra.unknown +
        "  定位 " + extra.locateMs + "ms 识别 " + extra.ms + "ms");
    if (extra.profile) {
        log("  分段：门 " + extra.profile.gates + "ms／匹配 " + extra.profile.match +
            "ms（复核 " + extra.retries + " 次）／阵营 " + extra.profile.side + "ms");
    }
    log("  FEN: " + glyph.charsToFen(extra.chars));
}

try {
    files.write(OUT, lines.join("\n"));
} catch (error) {
    console.log("[glyph] 写结果失败：" + error);
}
console.log("[glyph] DONE");
