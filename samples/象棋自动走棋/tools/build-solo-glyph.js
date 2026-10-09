// @engine quickjs
/**
 * 生成「测-字形单文件.js」—— 把字形链所需的工程模块**逐字内联**进一个文件。
 *
 * 用途：单文件分发/テスト（走到哪跑到哪，模板优先取脚本所在工程的 assets/glyph-tpl）。
 * 什么时候要重跑：改了下面 MODULES 里任一模块、或改了本文件里的 SOLO_MAIN 之后。
 * 跑法（工程根）：node tools/build-solo-glyph.js
 *
 * 实现：SOLO_PRELUDE / SOLO_MAIN 两个函数**只作源码容器**（String(fn) 取原文），
 * 在本脚本里永远不会被调用（它们内部引用的 images/drawing 等宿主全局只存在于设备端）。
 */
"use strict";

const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const OUT_NAME = "测-字形单文件.js";

/** 内联的模块清单（顺序 = 生成文件里的排列顺序；被依赖的排前面，便于人工翻看）。 */
const MODULES = [
    "src/util/defaults.js",
    "src/chess/fen.js",
    "src/chess/consistency.js",
    "src/vision/glyph-recognizer.js",
    "src/vision/glyph-resolve.js",
    "src/vision/overlay-draw.js"
];

/** 取函数体原文（去掉最外层 function 包裹；保留内部注释与缩进）。 */
function bodyOf(fn) {
    const src = String(fn);
    return src.slice(src.indexOf("{") + 1, src.lastIndexOf("}")).replace(/^\r?\n/, "");
}

// ═══════════════════════════════════════════════════════════════════════════
// 生成物开头（mini 模块系统）—— 只作源码容器
// ═══════════════════════════════════════════════════════════════════════════

function SOLO_PRELUDE() {
    "use strict";

    // ═══════════════════════════ 迷你模块系统 ═══════════════════════════
    // 内联的工程模块用 __define 登记、用 __require 取用（每个工厂函数隔离作用域）。
    var __factories = {};
    var __moduleCache = {};
    function __define(id, factory) {
        __factories[id] = factory;
    }
    function __resolveId(fromId, request) {
        if (request.charAt(0) !== ".") {
            return request;
        }
        var base = fromId.split("/");
        base.pop();
        var parts = request.split("/");
        for (var i = 0; i < parts.length; i++) {
            var part = parts[i];
            if (part === "" || part === ".") {
                continue;
            }
            if (part === "..") {
                base.pop();
            } else {
                base.push(part);
            }
        }
        return base.join("/");
    }
    function __require(id) {
        if (Object.prototype.hasOwnProperty.call(__moduleCache, id)) {
            return __moduleCache[id].exports;
        }
        var factory = __factories[id];
        if (!factory) {
            throw new Error("内联模块缺失：" + id);
        }
        var module = { exports: {} };
        __moduleCache[id] = module;
        factory(module, module.exports, function (request) {
            return __require(__resolveId(id, request));
        });
        return module.exports;
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 生成物主流程 —— 只作源码容器（设备端才会被调用）
// ═══════════════════════════════════════════════════════════════════════════

function SOLO_MAIN() {
    "use strict";

    // ═════════════ 参数（要改就改这里；重新生成会重置本段） ═════════════
    var KEEP_MS = 20000;      // 画板保留多少毫秒后自动收起；0 = 不收
    var DELAY_MS = 3000;      // 抓屏前等待（毫秒）：从文件页点「运行」后用这几秒切到棋局；0 = 立即抓
    var DRAW_MODE = "cells";  // "grid" 只画网格线｜"cells" 网格 + 每格的字（默认）｜"full" 再多画原始检测框
    var IMAGE_PATH = "";      // 留空 = 抓当前屏幕；填路径（如 "/sdcard/Pictures/棋局.png"）= 用已保存的截图
    var CAPTURE_TIMEOUT_MS = 1500;
    var OUT_TXT = "/sdcard/xiangqi-p4/ce-glyph-solo.txt";
    // 模板目录候选（按序尝试）：脚本所在工程的 assets（工程随插件分发，装机后用
    // installProject() 还原到脚本目录）→ 设备上遗留的旧内置副本。
    // ⚠️ 不再有 APK 内置的 asset://sample/…/象棋自动走棋：工程已从主 APK 移出。
    var SOLO_SCRIPT_DIR = (typeof __dirname === "string" && __dirname) ? __dirname.replace(/[\\/]+$/, "") : "";
    var TPL_CANDIDATES = [
        "./assets/glyph-tpl",
        SOLO_SCRIPT_DIR ? SOLO_SCRIPT_DIR + "/assets/glyph-tpl" : null,
        "/data/data/com.jdkshen.aijspro/files/sample/QuickJS 新引擎/项目与打包/象棋自动走棋/assets/glyph-tpl"
    ].filter(function (item) { return !!item; });

    var report = [];
    function log(text) {
        console.log("[字形单] " + text);
        report.push(String(text));
    }

    var glyph = __require("src/vision/glyph-recognizer.js");
    var glyphResolve = __require("src/vision/glyph-resolve.js");
    var overlayDraw = __require("src/vision/overlay-draw.js");

    // 宿主接口（识别链只认这些函数；直接指向原生 API）
    var host = {
        sizeOf: function (image) { return { width: Number(image.width) || 0, height: Number(image.height) || 0 }; },
        grayscale: function (image) { return images.grayscale(image); },
        clip: function (image, x, y, w, h) { return images.clip(image, x, y, w, h); },
        threshold: function (image, t, max) { return images.threshold(image, t, max); },
        countColor: function (image, color, options) {
            // ⚠️ 原生 images.countColor 的 region 只认 [x,y,w,h] 数组，而识别链传的是
            // {x,y,width,height} 对象（工程里这层归一化在 aijs-adapter.countColor 里做）。
            // 异常时回退 findAllPointsForColor / null（识别链对 null 有回落通道）。
            var opts = options || {};
            var region = opts.region || null;
            var request = {
                region: region
                    ? [region.x != null ? region.x : region[0], region.y != null ? region.y : region[1],
                        region.width != null ? region.width : region[2],
                        region.height != null ? region.height : region[3]]
                    : null,
                threshold: opts.threshold == null ? 24 : opts.threshold
            };
            try {
                var count = images.countColor(image, color, request);
                return count == null ? null : count;
            } catch (error) {
                try {
                    var points = images.findAllPointsForColor(image, color, request);
                    return points ? points.length : 0;
                } catch (ignored) {
                    return null;
                }
            }
        },
        matchTemplate: function (image, template, options) { return images.matchTemplate(image, template, options); },
        pixel: function (image, x, y) { return images.pixel(image, x, y); },
        red: function (pixel) { return colors.red(pixel); },
        green: function (pixel) { return colors.green(pixel); },
        blue: function (pixel) { return colors.blue(pixel); },
        resize: function (image, size) { return images.resize(image, size); },
        read: function (path) { return images.read(path); },
        nowMono: function () { return Date.now(); }
    };

    // ⓪ 模板目录：脚本所在工程的 assets 优先，失败回退设备上遗留的旧内置副本
    function pickTplDir() {
        for (var i = 0; i < TPL_CANDIDATES.length; i++) {
            try {
                var probe = images.read(TPL_CANDIDATES[i] + "/tpl_兵.png");
                if (probe) {
                    if (typeof probe.recycle === "function") {
                        try { probe.recycle(); } catch (ignored) { /* 已回收 */ }
                    }
                    return TPL_CANDIDATES[i];
                }
            } catch (ignored) {
                // 试下一个候选
            }
        }
        return null;
    }

    // ═════════════ 1. 抓帧 ═════════════

    /** 抽样十几个点全黑 → 当作「刚授权后的黑帧」（真机踩过：第一帧整幅 0,0,0）。 */
    function frameIsBlack(frame) {
        var w = frame.width;
        var h = frame.height;
        var sum = 0;
        for (var i = 1; i <= 4; i++) {
            for (var j = 1; j <= 3; j++) {
                var p = images.pixel(frame, Math.round(w * i / 5), Math.round(h * j / 4));
                sum += colors.red(p) + colors.green(p) + colors.blue(p);
            }
        }
        return sum === 0;
    }

    function grabFrame() {
        if (!requestScreenCapture("portrait")) {
            throw new Error("截图授权被拒绝");
        }
        var frame = null;
        for (var i = 0; i < 3; i++) {
            try {
                frame = captureScreen({ mode: "full", fresh: true, timeout: CAPTURE_TIMEOUT_MS });
            } catch (error) {
                frame = null;
            }
            if (frame && !frameIsBlack(frame)) {
                return frame;
            }
            if (frame) {
                try { frame.recycle(); } catch (ignored) { /* 已回收 */ }
            }
            sleep(250);
        }
        throw new Error("连续 3 次截图失败/全黑（屏幕录制权限？）");
    }

    // ═════════════ 4. 绘制（宿主画板） ═════════════

    function showOverlay(grid, payload, extraText) {
        try { drawing.hide(); } catch (ignored) { /* 上一轮没画过 */ }
        var items = [];
        var text = String(extraText || "");
        if (grid && payload) {
            var rec = payload.rec;
            var resolved = payload.resolved;
            // 字形链的逐格分数 → 画板认的 assigned（标签配色/排序用）
            var scoreMap = {};
            (rec.cells || []).forEach(function (item) {
                if (item && item.idx != null && item.sim != null) {
                    scoreMap[item.idx] = item.sim;
                }
            });
            var assigned = [];
            for (var i = 0; i < 90; i++) {
                assigned.push(scoreMap[i] != null ? { score: scoreMap[i] } : null);
            }
            var result = {
                chars: resolved.chars,
                assigned: assigned,
                detectionCount: rec.inked,
                detections: [],
                unknownCells: resolved.stats.unknownAfter,
                timing: { totalMs: payload.ms },
                repaired: { demoted: resolved.demoted }
            };
            var built = overlayDraw.build({ grid: grid, result: result, mode: DRAW_MODE, extraText: extraText });
            items = built.items;
            text = built.text;
        }
        var shown = false;
        try { shown = drawing.show() === true; } catch (ignored) { shown = false; }
        if (!shown) {
            log("画板打不开（没有「显示在其他应用上层」权限？）——结果看日志");
            return false;
        }
        drawing.update(items, text);
        log("画板已显示：" + items.length + " 个框｜" + text);
        return true;
    }

    // ═════════════ 主流程 ═════════════

    var frame = null;
    try {
        var tplDir = pickTplDir();
        if (!tplDir) {
            throw new Error("字形模板读不到（脚本所在工程与设备上的旧副本都不行）");
        }
        log("模板目录：" + tplDir);

        // ① 抓帧：先收掉可能残留的旧画板；DELAY_MS 给「点运行后切棋局」留时间
        if (IMAGE_PATH) {
            frame = images.read(IMAGE_PATH);
            if (!frame) {
                throw new Error("读不到图片：" + IMAGE_PATH);
            }
        } else {
            try { drawing.hide(); } catch (ignored) { /* 没画过 */ }
            if (DELAY_MS > 0) {
                log(DELAY_MS + "ms 后抓屏 —— 请把棋局画面切到前台（脚本在后台继续跑）");
                sleep(DELAY_MS);
            }
            frame = grabFrame();
        }
        log("帧 " + frame.width + "x" + frame.height + (IMAGE_PATH ? "（文件 " + IMAGE_PATH + "）" : "（实时截屏）"));

        // ② 定位（原生 Canny/Hough）
        var loc = images.analyzeXiangqiBoard(frame);
        if (!loc || loc.ok !== true || !loc.grid) {
            throw new Error("原生定位失败：" + ((loc && loc.reason) || "没有返回几何"));
        }
        var grid = {
            originX: loc.grid.originX, originY: loc.grid.originY,
            stepX: loc.grid.stepX, stepY: loc.grid.stepY,
            orientation: loc.grid.orientation || "RED_BOTTOM"
        };
        log("定位（原生 canny-hough）：原点 (" + grid.originX.toFixed(1) + ", " + grid.originY.toFixed(1) +
            ")，格距 (" + grid.stepX.toFixed(1) + ", " + grid.stepY.toFixed(1) + ")，交点圆 " +
            (loc.circles ? loc.circles.length : "?") + " 枚");

        // ③ 识别（字形模板链）
        var templates = glyph.loadTemplates(host, tplDir);
        var scale = glyph.scaleFor(grid);
        var prepared = glyph.prepareFor(host, templates, scale);
        var started = Date.now();
        var rec = glyph.recognize(host, frame, grid, {
            templates: prepared.templates,
            TW: prepared.TW,
            ANCHOR_DY: prepared.ANCHOR_DY,
            coarseTemplates: prepared.coarse,
            collect: true
        });
        log("识别：触子 " + rec.inked + "｜未定 " + rec.unknown + "｜复核 " + rec.retries +
            "｜抢救 " + (rec.rescued || 0) + "/" + (rec.rescues || 0) + " 次（" + Math.round(rec.rescueMs || 0) + "ms）" +
            "｜耗时 " + rec.ms + "ms" + (Math.abs(scale - 1) >= 0.02 ? "｜模板缩放 " + scale.toFixed(3) : ""));

        // 盘面级判决：守恒补全 + 违规降级/改阵营（把「只差红黑/违棋规」的格救回来）
        var scores = {};
        (rec.cells || []).forEach(function (item) {
            if (item && item.idx != null && item.sim != null) {
                scores[item.idx] = item.sim;
            }
        });
        var resolved = glyphResolve.resolve(glyph.normalizeBoard(rec.chars), {
            scores: scores, notes: rec.notes || []
        });
        var chars = resolved.chars.map(function (ch) {
            return (ch == null || ch === ".") ? null : ch;
        });
        log("判决：补齐 " + resolved.filled.length + "｜降级 " + resolved.demoted.length +
            "｜改阵营 " + resolved.flipped.length + "｜未知 " + resolved.stats.unknownBefore +
            " → " + resolved.stats.unknownAfter);
        log("FEN: " + glyph.charsToFen(chars));
        log("识别总耗时 " + (Date.now() - started) + "ms");

        // ④ 绘制
        showOverlay(grid, { rec: rec, resolved: resolved, ms: rec.ms },
            "字形单文件" + (resolved.stats.unknownAfter > 0 ? "（未知 " + resolved.stats.unknownAfter + "）" : ""));

        console.log("GLYPH_SOLO_OK " + JSON.stringify({
            ok: true, method: "canny-hough",
            pieces: rec.inked, unknown: resolved.stats.unknownAfter,
            fen: glyph.charsToFen(chars)
        }));
    } catch (error) {
        log("出错：" + (error && error.message ? error.message : error));
        console.log("GLYPH_SOLO_ERR " + (error && error.message ? error.message : error));
    } finally {
        if (frame && typeof frame.recycle === "function") {
            try { frame.recycle(); } catch (ignored) { /* 已回收 */ }
        }
    }

    // 收尾：保留一会儿给肉眼审，然后收起画板；结果文本落盘（方便 adb pull）
    if (KEEP_MS > 0) {
        sleep(KEEP_MS);
        try { drawing.hide(); } catch (ignored) { /* 收不掉也不影响 */ }
        log("画板已收起（KEEP_MS=" + KEEP_MS + "）");
    }
    try {
        files.write(OUT_TXT, report.join("\n"));
        log("文本已写到 " + OUT_TXT);
    } catch (error) {
        try {
            files.write("/sdcard/ce-glyph-solo.txt", report.join("\n"));
            log("文本已写到 /sdcard/ce-glyph-solo.txt");
        } catch (ignored) { /* 写不了就算了，日志为准 */ }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 组装 & 写盘
// ═══════════════════════════════════════════════════════════════════════════

const header = [
    "/**",
    " * 识别 · 定位 · 绘制 —— 字形链「真·单文件版」（**生成物，别手改**）",
    " * ════════════════════════════════════════════════════════════════════════",
    " * 由 tools/build-solo-glyph.js 生成：内联以下工程模块（逐字拷贝，见文件内标注）——",
    " *   src/util/defaults.js",
    " *   src/chess/fen.js",
    " *   src/chess/consistency.js",
    " *   src/vision/glyph-recognizer.js",
    " *   src/vision/glyph-resolve.js",
    " *   src/vision/overlay-draw.js",
    " * 改了上面任一模块（或生成器里的主流程）后，在工程根跑：node tools/build-solo-glyph.js",
    " *",
    " * 与「测-工程版.js」的差别：不 require 任何工程文件 —— 一个文件走天下",
    " *   （字形模板取脚本所在工程的 assets/glyph-tpl，失败才回退设备上遗留的 files/sample 工程副本）。",
    " * 与「测.js」的差别：走**字形模板链**（≈99.6% 准，带多尺度抢救 + 盘面级判决），",
    " *   不是随包 YOLO（≈九成，会误检漏检）；定位用 images.analyzeXiangqiBoard（原生 Canny/Hough），",
    " *   本版不带「图色格线」回退（那是工程版 locate-board 里的那条）。",
    " *",
    " * 跑法：放到任何位置直接运行；参数都在主流程开头（KEEP_MS / DELAY_MS / DRAW_MODE / IMAGE_PATH）。",
    " * 日志前缀 [字形单]；结果文本写 /sdcard/xiangqi-p4/ce-glyph-solo.txt（失败退 /sdcard）。",
    " * 结束打点：GLYPH_SOLO_OK {json} / GLYPH_SOLO_ERR <原因>。",
    " */"
].join("\n");

const parts = [];
parts.push("// @engine quickjs");
parts.push(header);
parts.push(bodyOf(SOLO_PRELUDE));

let moduleBytes = 0;
for (const id of MODULES) {
    const src = fs.readFileSync(path.join(ROOT, id), "utf8").replace(/^\uFEFF/, "").replace(/\s+$/, "");
    moduleBytes += Buffer.byteLength(src, "utf8");
    parts.push("");
    parts.push("// ═══════════ 内联模块：" + id + "（逐字拷贝自工程） ═══════════");
    parts.push("__define(" + JSON.stringify(id) + ", function (module, exports, require) {");
    parts.push(src);
    parts.push("});");
}

parts.push("");
parts.push("// ═══════════ 主流程（生成自 tools/build-solo-glyph.js 的 SOLO_MAIN） ═══════════");
parts.push("(" + SOLO_MAIN.toString() + ")();");

const output = parts.join("\n") + "\n";
const outPath = path.join(ROOT, OUT_NAME);
fs.writeFileSync(outPath, output, "utf8");

console.log("written: " + OUT_NAME);
console.log("  total: " + Buffer.byteLength(output, "utf8") + " bytes, " + output.split("\n").length + " lines");
console.log("  inlined modules: " + MODULES.length + " 个，共 " + moduleBytes + " bytes");
