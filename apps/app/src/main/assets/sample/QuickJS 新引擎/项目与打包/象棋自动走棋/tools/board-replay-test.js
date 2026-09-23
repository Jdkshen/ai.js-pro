// @engine quickjs
/**
 * 盘面回放测试（方案 §8 的 `tools/board-replay-test.js`）。
 *
 * 干什么：把**已经存下来的帧**逐张喂给视觉流水线，看每一张能不能稳定出盘面 ——
 * 这样改算法时不用真机摆一遍棋，也能在设备上做**回归**（方案 §9 阶段 D 的验收靠它）。
 *
 * 帧目录（默认按顺序找存在的那个）：
 *   /sdcard/xiangqi-p4/realframes2 下的 *.png      （早期采集的真实帧）
 *   /sdcard/xiangqi-p4/vision-probe/<stamp>/frame.png（探针自己存的帧；这些是本应用写的，
 *                                              shell push 进去的 /sdcard/xiangqi-p4 文件应用读不到）
 * 额外目录写在 <应用外部目录>/ocr-model/replay-dir.txt 里（一行一个）。
 *
 * 每帧跑两遍（方案 §3.8：两帧一致才确认），每帧都**新建流水线**：回放要的是「这一张图自己能不能读对」，
 * 不能让上一张的融合历史参与。
 * 产物：/sdcard/xiangqi-p4/replay/<stamp>/{report.json}
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) {
        // 忽略
    }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");
const recognizeBoard = load("tools/recognize-board.js");
const detector = load("src/vision/piece-detector.js");
const visionPipeline = load("src/vision/vision-pipeline.js");

const PKG = "com.jdkshen.aijspro";
const EXT = "/sdcard/Android/data/" + PKG + "/files/ocr-model";
const OUT_ROOT = "/sdcard/xiangqi-p4/replay";
const DEFAULT_DIRS = [
    "/sdcard/xiangqi-p4/realframes2",
    "/sdcard/xiangqi-p4/realframes",
    "/sdcard/xiangqi-p4/vision-probe"
];
/** 单次回放最多跑几张（性能兜底） */
const MAX_FRAMES = 24;

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[replay] " + text);
    }
}

function ensureDir(dir) {
    try {
        if (typeof files !== "undefined" && files.ensureDir) {
            files.ensureDir(dir + "/");
            return true;
        }
    } catch (ignore) {
        // 忽略
    }
    return false;
}

function writeText(path, text) {
    try {
        if (typeof files !== "undefined" && files.write) {
            files.write(path, String(text));
            return true;
        }
    } catch (ignore) {
        // 忽略
    }
    return false;
}

function listDir(path) {
    try {
        if (typeof files === "undefined" || typeof files.listDir !== "function") {
            return [];
        }
        const raw = files.listDir(path);
        if (Array.isArray(raw)) {
            return raw.map(function (n) { return String(n); });
        }
        const parsed = JSON.parse(String(raw || "[]"));
        return Array.isArray(parsed) ? parsed.map(function (n) { return String(n); }) : [];
    } catch (error) {
        return [];
    }
}

/** 收集待回放的帧路径（含 vision-probe 那种「子目录里放 frame.png」的结构） */
function collectFrames(dirs) {
    const out = [];
    for (let d = 0; d < dirs.length && out.length < MAX_FRAMES; d++) {
        const root = dirs[d];
        const names = listDir(root);
        for (let i = 0; i < names.length && out.length < MAX_FRAMES; i++) {
            const name = names[i];
            if (/\.png$/i.test(name)) {
                out.push({ path: root + "/" + name, label: name });
                continue;
            }
            // 可能是子目录（vision-probe/<stamp>/frame.png）
            const sub = listDir(root + "/" + name);
            if (sub.indexOf("frame.png") >= 0) {
                out.push({ path: root + "/" + name + "/frame.png", label: name + "/frame.png" });
            }
        }
    }
    return out;
}

function readExtraDirs() {
    try {
        if (typeof files !== "undefined" && files.read) {
            const text = String(files.read(EXT + "/replay-dir.txt") || "");
            return text.split("\n").map(function (s) { return s.trim(); }).filter(function (s) { return s.length > 0; });
        }
    } catch (ignore) {
        // 忽略
    }
    return [];
}

function makePipeline(grid, session) {
    return visionPipeline.create({
        detect: function (pic, detectOptions) {
            try {
                // 只需要 YOLO 原始框：走共用底座 detect-only，別再跑一遍旧识别（方案 §3.2）。
                const out = recognizeBoard.detectOnly(pic, grid, {
                    confidence: (detectOptions && detectOptions.confidence) || 0.25
                });
                return (out && out.ok) ? detector.normalizeBoxes(out.raw || []) : [];
            } catch (error) {
                return [];
            }
        },
        clip: function (pic, box) { return adapter.clip(pic, box); },
        rgbAt: function (pic, x, y) { return adapter.rgbAt(pic, x, y); },
        ocrSession: session,
        log: function () { }
    });
}

function main() {
    const startedAt = Date.now();
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const outDir = OUT_ROOT + "/" + stamp;
    ensureDir(outDir);

    const dirs = readExtraDirs().concat(DEFAULT_DIRS);
    const frames = collectFrames(dirs);
    log("帧目录：" + dirs.join(" ｜ "));
    log("共找到 " + frames.length + " 张帧（上限 " + MAX_FRAMES + "）");
    if (!frames.length) {
        log("没有可回放的帧：先用 tools/vision-pipeline-probe.js 存几张，或把目录写进 " + EXT + "/replay-dir.txt");
        return;
    }

    const session = (typeof $ocr !== "undefined" && $ocr.create)
        ? $ocr.create({ backend: "ppocr" })
        : null;
    if (!session) {
        log("没有 OCR 会话，结束");
        return;
    }

    const results = [];
    let previousBoard = null;
    for (let i = 0; i < frames.length; i++) {
        const frame = frames[i];
        let image = null;
        try {
            if (typeof images === "undefined" || typeof images.read !== "function") {
                log("images.read 不可用，无法回放");
                return;
            }
            image = images.read(frame.path);
        } catch (error) {
            image = null;
        }
        if (!image) {
            log("[" + (i + 1) + "/" + frames.length + "] 读不到：" + frame.path);
            continue;
        }
        // 几何：⚠️ 不要用 `locate-board`（line-locator）—— 这套皮肤上它要求 9 条竖线全命中，
        //    实测常只命中 7~8 条 ⇒ 16 张历史帧全军覆没（2026-09-20 先踩了一次）。
        //    走仓库里已验证的 `pickGrid` 种子搜索（代价含违规/未知/棋盘证据单调项，能挡退化网格）。
        let grid = null;
        try {
            const picked = recognizeBoard.pickGrid(image, {
                orientation: "RED_BOTTOM",
                seeds: [
                    { originX: 81, originY: 666, stepX: 115, stepY: 114, orientation: "RED_BOTTOM" },
                    { originX: 81, originY: 697, stepX: 114.7, stepY: 114.2, orientation: "RED_BOTTOM" },
                    { originX: 72, originY: 660, stepX: 114.8, stepY: 114.3, orientation: "RED_BOTTOM" }
                ]
            });
            if (picked && picked.ok) {
                const g = picked.grid || picked;
                grid = {
                    originX: g.originX, originY: g.originY, stepX: g.stepX, stepY: g.stepY,
                    orientation: g.orientation || "RED_BOTTOM", version: 1
                };
            }
        } catch (error) {
            grid = null;
        }
        if (!grid) {
            log("[" + (i + 1) + "/" + frames.length + "] 定位失败：" + frame.label);
            results.push({ path: frame.path, label: frame.label, ok: false, reason: "定位失败" });
            continue;
        }
        const pipeline = makePipeline(grid, session);
        const t0 = Date.now();
        let result = null;
        for (let k = 0; k < 2; k++) {
            result = pipeline.recognize(image, grid, { side: "w" });
        }
        const ms = Date.now() - t0;
        const board = (result && result.board) ? result.board.join("") : null;
        const chars = (result && result.chars) ? result.chars.join("") : null;
        let diff = null;
        if (board && previousBoard) {
            let n = 0;
            for (let c = 0; c < 90; c++) {
                if (board.charAt(c) !== previousBoard.charAt(c)) {
                    n += 1;
                }
            }
            diff = n;
        }
        if (board) {
            previousBoard = board;
        }
        const unknown = board ? board.split("").filter(function (ch) { return ch === "?"; }).length : 90;
        const validation = result && result.validation ? result.validation : null;
        log("[" + (i + 1) + "/" + frames.length + "] " + frame.label + "｜" + ms + "ms｜未知 " + unknown +
            "｜规则门 " + (validation && validation.ok ? "通过" : ("否决 " + ((validation && validation.reason) || "?"))) +
            (diff == null ? "" : ("｜与上一帧差 " + diff + " 格")));
        log("    盘面：" + (board || "-"));
        results.push({
            path: frame.path, label: frame.label, ok: true, ms: ms, unknown: unknown,
            validation: validation ? { ok: validation.ok, reason: validation.reason } : null,
            board: board, fused: chars, diffFromPrevious: diff,
            stats: result && result.stats ? result.stats : null
        });
    }

    const frameSaved = results.filter(function (r) { return r.ok; });
    const passed = frameSaved.filter(function (r) { return r.validation && r.validation.ok; });
    log("—— 汇总：" + frameSaved.length + "/" + frames.length + " 张出盘面，其中规则门通过 " + passed.length + " 张 ——");
    writeText(outDir + "/report.json", JSON.stringify({
        stamp: stamp, elapsedMs: Date.now() - startedAt, dirs: dirs,
        total: frames.length, decoded: frameSaved.length, validated: passed.length, results: results
    }, null, 1));
    log("报告：" + outDir + "/report.json");
    log("结束（总耗时 " + (Date.now() - startedAt) + "ms）");
}

main();
