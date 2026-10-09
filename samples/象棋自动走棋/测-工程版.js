// @engine quickjs
/**
 * 识别 · 定位 · 绘制 —— 一页式完整案例（在「文件页」点这条脚本就能跑）
 * ══════════════════════════════════════════════════════════════════════════
 * 它做的事（每步都有日志，前缀 [测]）：
 *   ① 抓一帧当前屏幕（或读一张已保存的截图）
 *   ② 定位棋盘 —— 工具链 tools/locate-board.js（Canny/Hough 为主，失败回退图色格线）
 *   ③ 识别棋子 —— 字形模板链 src/vision/glyph-recognizer.js（含多尺度抢救）
 *                + 盘面级判决 src/vision/glyph-resolve.js（守恒补全/违规降级）
 *   ④ 绘制 —— 把几何与识别结果画成**屏幕上的悬浮画板**（网格线 + 每格的字）
 *
 * 怎么看结果：
 *   · 屏幕上：线框应穿过棋子圆心；每格左上角是读出的字（? = 读不出）；顶部一行 HUD 是数字
 *   · 日志里：定位原点/格距、触子数、未知格、FEN、耗时
 *   · 结束时还会写一份文本到 /sdcard/xiangqi-p4/ce-demo.txt（目录不存在就退到 /sdcard）
 *   · 画板里**每个框都会带一个分数标签**（网格线显示成 "100%"）——这是宿主渲染器的固定行为
 *     （caption = 标签 + 分数%，空标签也画），面板的「识别绘制」同样如此，不是识别错误
 *
 * 注意事项（都是真机踩过的坑）：
 *   · 画板是**系统悬浮窗**——任何截图（包括面板的自动识别）都会把它一起截进去。
 *     跑这个案例前最好先停掉自动走棋循环；否则这 20 秒里面板会把画板当成棋盘上的东西。
 *   · 系统刚授权后的第一帧可能是全黑 → 脚本自动重试。
 *   · 画板要有「显示在其他应用上层」权限；没权限也能跑，只是不画（日志会说明）。
 *   · 识别链当前只有字形模板（assets/glyph-tpl，11 个模板）；模板目录跟着「模块目录」（工程根）走。
 *   · 本文件需要工程模块（src/、tools/、assets/）：放在工程目录里运行最自然；
 *     拷到别处（/sdcard 等）也能跑 —— 会自动改从「设备上已知的工程副本」加载，
 *     日志首行会写清用的哪份；也可以用顶部参数 PROJECT_DIR 显式指定工程根目录。
 */
"use strict";

// ═══════════════════════════ 参数（要改就改这里） ═══════════════════════════

const PROJECT_DIR = "";         // 工程根目录（src/、tools/、assets/ 所在处）：留空 = 自动（先看脚本自己
                                // 所在目录，再看设备上已知的工程副本）；填绝对路径可强制
const IMAGE_PATH = "";          // 留空 = 抓当前屏幕；填路径（如 "/sdcard/Pictures/棋局.png"）= 用已保存的截图
const KEEP_MS = 20000;          // 画板保留多少毫秒后自动收起；0 = 不收（下次运行会先收掉旧的）
const DRAW_MODE = "cells";      // "grid" 只画网格线｜"cells" 网格 + 每格的字（默认）｜"full" 再加原始检测框
const CAPTURE_TIMEOUT_MS = 1500;

// ═══════════════════════════ 基础加载 ═══════════════════════════

// 模块必须用绝对路径 —— 从文件页运行时 cwd 可能是 "/"，相对 require 会找不到。
// 模块目录选取顺序：PROJECT_DIR（显式指定）→ 脚本自己所在目录（放在工程里跑的老路径）→
// 设备上已知的工程副本（于是本文件被拷到 /sdcard 也能单独跑，要求设备上留着工程）：
//   · 插件装出来的默认位置（$plugins.load("com.jdkshen.xiangqi.plugin").installProject()）
//   · 旧版主程序内置示例同步出来的位置（升级上来的设备可能还留着）
const PROJECT_GUESSES = [
    "/sdcard/脚本/象棋自动走棋",
    "/data/data/com.jdkshen.aijspro/files/sample/QuickJS 新引擎/项目与打包/象棋自动走棋"
];
// 一个目录要**同时**具备这五件（缺一不可）才算「可用工程」—— 只查一个文件的话，
// /sdcard 上遗留的旧快照（缺新模块）也会被误判成工程。
const PROJECT_MARKERS = [
    "src/runtime/aijs-adapter.js",
    "tools/locate-board.js",
    "src/vision/glyph-recognizer.js",
    "src/vision/glyph-resolve.js",
    "src/vision/overlay-draw.js"
];
function hasProject(dir) {
    try {
        if (typeof files === "undefined") {
            return false;
        }
        for (let i = 0; i < PROJECT_MARKERS.length; i++) {
            if (files.exists(dir + "/" + PROJECT_MARKERS[i]) !== true) {
                return false;
            }
        }
        return true;
    } catch (ignored) {
        return false;
    }
}
function pickBaseDir() {
    if (PROJECT_DIR) {
        return PROJECT_DIR;
    }
    const own = (typeof __dirname === "string" && __dirname && __dirname !== ".") ? __dirname : "";
    if (own && hasProject(own)) {
        return own;
    }
    for (let i = 0; i < PROJECT_GUESSES.length; i++) {
        if (hasProject(PROJECT_GUESSES[i])) {
            return PROJECT_GUESSES[i];
        }
    }
    return own || ".";
}
const BASE_DIR = pickBaseDir();
function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

let adapter;                                                // 宿主能力（截图/找色/尺寸……）
let locateBoard;                                            // 定位：Canny/Hough → 图色格线
let glyph;                                                  // 字形识别（模板链 + 抢救）
let glyphResolve;                                           // 盘面级判决（补全/降级/改阵营）
let overlayDraw;                                            // 结果 → 画板的框 + 标签
try {
    adapter = load("src/runtime/aijs-adapter.js");
    locateBoard = load("src/vision/locate-board.js");
    glyph = load("src/vision/glyph-recognizer.js");
    glyphResolve = load("src/vision/glyph-resolve.js");
    overlayDraw = load("src/vision/overlay-draw.js");
} catch (error) {
    console.log("[测] 模块加载失败（模块目录：" + BASE_DIR + "）：" + (error && error.message ? error.message : error));
    console.log("[测] 需要设备上有完整工程（src/、tools/、assets/）；只想随处单跑请用「测.js」（单文件版）；" +
        "也可用顶部参数 PROJECT_DIR 指定工程目录。");
    throw error;
}

const TPL_DIR = BASE_DIR + "/assets/glyph-tpl";             // 字形模板目录
const OUT_TXT = "/sdcard/xiangqi-p4/ce-demo.txt";           // 结果文本

// SIFT 定位优先（与 main.js / 面板同一接线）：配置了才会被 locate-board 使用，
// 失败自动回退 Canny/Hough 与图色格线；模板与标定放在 assets/board-sift/。
try {
    locateBoard.configureSift({ baseDir: BASE_DIR });
} catch (error) {
    console.log("[测] SIFT 配置失败（继续走 Canny/格线）：" + (error && error.message ? error.message : error));
}

const report = [];
function log(text) {
    console.log("[测] " + text);
    report.push(String(text));
}

log("模块目录：" + BASE_DIR);

/** 宿主能力打包 —— 识别链只认这些函数（与 tools/probe-glyph-live.js 完全一致）。 */
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

// ═══════════════════════════ ① 抓帧 ═══════════════════════════

/** 帧是否全黑（首帧常见）；接口不在就当作不黑。 */
function frameIsBlank(frame) {
    try {
        return typeof adapter.looksBlank === "function" && adapter.looksBlank(frame) === true;
    } catch (error) {
        return false;
    }
}

function grabFrame() {
    if (!adapter.requestCapture("portrait")) {
        throw new Error("截图授权被拒绝（屏幕录制权限没给）");
    }
    let frame = adapter.capture("full", CAPTURE_TIMEOUT_MS);
    // 系统刚授权后的第一帧可能整幅全黑 → 重试两次
    for (let i = 0; i < 2 && frame && frameIsBlank(frame); i++) {
        try { frame.recycle(); } catch (ignored) { /* 已回收 */ }
        sleep(250);
        frame = adapter.capture("full", CAPTURE_TIMEOUT_MS);
    }
    return frame;
}

// ═══════════════════════════ ④ 绘制 ═══════════════════════════

/**
 * 打开画板并刷新内容。
 * @param grid      棋盘几何（null = 只画一行说明文字）
 * @param payload   { rec, resolved, ms }（null = 没有识别结果）
 * @param extraText HUD 追加文字
 */
function showOverlay(grid, payload, extraText) {
    try { adapter.overlayHide(); } catch (ignored) { /* 上一轮的画板先收掉 */ }

    let items = [];
    let text = String(extraText || "");
    if (grid && payload) {
        const rec = payload.rec;
        const resolved = payload.resolved;
        // 把字形链的逐格分数映射成画板认的 assigned（用于标签配色/排序，可选）
        const scoreMap = {};
        (rec.cells || []).forEach(function (item) {
            if (item && item.idx != null && item.sim != null) {
                scoreMap[item.idx] = item.sim;
            }
        });
        const assigned = [];
        for (let i = 0; i < 90; i++) {
            assigned.push(scoreMap[i] != null ? { score: scoreMap[i] } : null);
        }
        // 画板只认自己那套字段：chars 里 null/""=空格，'?'=未知，字母=棋子（大写红/小写黑）
        const result = {
            chars: resolved.chars,
            assigned: assigned,
            detectionCount: rec.inked,
            detections: [],                     // 字形链没有「原始检测框」，这里留空
            unknownCells: resolved.stats.unknownAfter,
            timing: { totalMs: payload.ms },
            repaired: { demoted: resolved.demoted }   // HUD 上会多一个「降级 N」
        };
        const built = overlayDraw.build({
            grid: grid,
            result: result,
            mode: DRAW_MODE,
            extraText: extraText
        });
        items = built.items;
        text = built.text;
    }

    if (!adapter.overlayShow()) {
        log("画板打不开（宿主没给 drawing 接口，或没有「显示在其他应用上层」权限）——结果看日志/文本");
        return false;
    }
    adapter.overlayUpdate(items, text);
    log("画板已显示：" + items.length + " 个框｜" + text);
    return true;
}

// ═══════════════════════════ 主流程 ═══════════════════════════

function main() {
    let frame = null;
    let located = null;
    try {
        // ① 抓帧
        if (IMAGE_PATH) {
            frame = adapter.readImage(IMAGE_PATH);
            if (!frame) {
                throw new Error("读不到图片：" + IMAGE_PATH);
            }
        } else {
            frame = grabFrame();
        }
        if (!frame) {
            throw new Error("没有拿到帧（截图通道不可用）");
        }
        const size = adapter.sizeOf(frame) || { width: frame.width, height: frame.height };
        log("帧 " + size.width + "x" + size.height + (IMAGE_PATH ? "（文件 " + IMAGE_PATH + "）" : "（实时截屏）"));

        // ② 定位棋盘
        located = IMAGE_PATH
            ? locateBoard.locateBoardInImage(IMAGE_PATH)
            : locateBoard.locateBoardOnScreen({ frame: frame });
        if (!located.ok) {
            log("定位失败：" + located.reason);
            showOverlay(null, null, "定位失败：" + located.reason);
            return { ok: false, reason: located.reason };
        }
        const grid = located.grid;
        log("定位成功（" + located.method + "）：原点 (" + grid.originX.toFixed(1) + ", " + grid.originY.toFixed(1) + ")" +
            "，格距 (" + grid.stepX.toFixed(1) + ", " + grid.stepY.toFixed(1) + ")，用时 " +
            (located.timing ? located.timing.totalMs : "?") + "ms" +
            (located.warning ? "｜⚠ " + located.warning : ""));
        // 每格中心的屏幕坐标 = (originX + col*stepX, originY + row*stepY)，row 0 在顶部

        // ③ 识别（字形链）
        const templates = glyph.loadTemplates(host, TPL_DIR);
        const scale = glyph.scaleFor(grid);
        const prepared = glyph.prepareFor(host, templates, scale);
        const started = Date.now();
        const rec = glyph.recognize(host, frame, grid, {
            templates: prepared.templates,
            TW: prepared.TW,
            ANCHOR_DY: prepared.ANCHOR_DY,
            coarseTemplates: prepared.coarse,
            collect: true                              // 收集逐格分数（判决要用来挑最弱格）
        });
        log("识别：触子 " + rec.inked + "｜未定 " + rec.unknown + "｜复核 " + rec.retries +
            "｜抢救 " + (rec.rescued || 0) + "/" + (rec.rescues || 0) + " 次（" + Math.round(rec.rescueMs || 0) + "ms）" +
            "｜耗时 " + rec.ms + "ms" + (Math.abs(scale - 1) >= 0.02 ? "｜模板缩放 " + scale.toFixed(3) : ""));

        // 盘面级判决：守恒补全 + 违规降级/改阵营（把「只差红黑/违棋规」的格救回来）
        const scores = {};
        (rec.cells || []).forEach(function (item) {
            if (item && item.idx != null && item.sim != null) {
                scores[item.idx] = item.sim;
            }
        });
        const resolved = glyphResolve.resolve(glyph.normalizeBoard(rec.chars), {
            scores: scores, notes: rec.notes || []
        });
        // 判决后的盘面：null=空，'?'=未知，字母=棋子（大写红/小写黑）
        const chars = resolved.chars.map(function (ch) { return (ch == null || ch === ".") ? null : ch; });
        log("判决：补齐 " + resolved.filled.length + "｜降级 " + resolved.demoted.length +
            "｜改阵营 " + resolved.flipped.length + "｜未知 " + resolved.stats.unknownBefore +
            " → " + resolved.stats.unknownAfter);
        log("FEN: " + glyph.charsToFen(chars));
        log("识别总耗时 " + (Date.now() - started) + "ms");

        // ④ 绘制
        showOverlay(grid, { rec: rec, resolved: resolved, ms: rec.ms },
            "识别定位演示" + (resolved.stats.unknownAfter > 0 ? "（未知 " + resolved.stats.unknownAfter + "）" : ""));
        return {
            ok: true, method: located.method, grid: grid,
            pieces: rec.inked, unknown: resolved.stats.unknownAfter,
            fen: glyph.charsToFen(chars)
        };
    } finally {
        if (frame && typeof frame.recycle === "function") {
            try { frame.recycle(); } catch (ignored) { /* 已回收 */ }
        }
    }
}

// ═══════════════════════════ 跑 + 收尾 ═══════════════════════════

let summary = { ok: false, reason: "未执行" };
try {
    summary = main();
    console.log("CE_DEMO_OK " + JSON.stringify(summary));
} catch (error) {
    log("出错：" + (error && error.message ? error.message : error));
    console.log("CE_DEMO_ERR " + (error && error.message ? error.message : error));
}

// 保留一会儿给肉眼审，然后收起画板（KEEP_MS=0 则一直留着）
if (KEEP_MS > 0) {
    sleep(KEEP_MS);
    try { adapter.overlayHide(); } catch (ignored) { /* 收不掉也不影响 */ }
    log("画板已收起（KEEP_MS=" + KEEP_MS + "）");
}

// 落一份文本，方便 adb pull / 发给别人看
try {
    files.write(OUT_TXT, report.join("\n"));
    console.log("[测] 文本已写到 " + OUT_TXT);
} catch (error) {
    try {
        files.write("/sdcard/ce-demo.txt", report.join("\n"));
        console.log("[测] 文本已写到 /sdcard/ce-demo.txt");
    } catch (ignored) { /* 写不了就算了，日志为准 */ }
}
