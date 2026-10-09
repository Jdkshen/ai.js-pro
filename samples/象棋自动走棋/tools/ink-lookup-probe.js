// @engine quickjs
/**
 * 红黑判色（找色版）真机探针。
 *
 * 目的：把 `src/vision/ink-color.js` 的参考色/容差/密度门槛**建立在真机数据上**，同时量清成本：
 *   · 逐格跑「找色数红/黑墨迹」→ 打印 red/black/area/密度/判定 + 每格耗时；
 *   · 同一批格子再跑一遍**逐点采样 + 老分类器**（现在的兜底通道）做对照；
 *   · 报告 JSON + 原始帧落盘，便于离线复算。
 *
 * 用法：
 *   adb push 到手机 → am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path <本文件>
 * 日志前缀 `[inklook]`。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) {
        // 没有 __dirname 就按当前目录算
    }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");
const locateBoard = load("src/vision/locate-board.js");
const recognizeBoard = load("experiments/vision/recognize-board.js");
const detector = load("src/vision/piece-detector.js");
const cropper = load("src/vision/piece-cropper.js");
const pieceColor = load("src/vision/piece-color.js");
const inkColor = load("src/vision/ink-color.js");

const OUT_ROOT = "/sdcard/xiangqi-p4/ink-lookup";
/** 容差候选：24 是按真机墨色定的默认值，多试两档看分离度 */
const TOLERANCES = [16, 24, 32, 40];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[inklook] " + text);
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

/** 宿主：找色（countColor）+ 采样（rgbAt）+ 检测（复用 recognize-board 的 detect-only 会话） */
function hostFor(grid) {
    return {
        countColor: function (image, color, options) {
            return adapter.countColor(image, color, options);
        },
        rgbAt: function (image, x, y) {
            return adapter.rgbAt(image, x, y);
        },
        detect: function (image, options) {
            const recognized = recognizeBoard.recognizeScreen({
                grid: grid ? Object.assign({}, grid, { cols: 9, rows: 10 }) : null,
                orientation: "RED_BOTTOM",
                confidence: (options && options.confidence) || 0.22,
                autoAlign: false,
                frame: image
            });
            return detector.normalizeBoxes((recognized && recognized.native && recognized.native.list) || []);
        }
    };
}

function main() {
    const startedAt = Date.now();
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const outDir = OUT_ROOT + "/" + stamp;
    ensureDir(outDir);
    log("开始 " + stamp + "｜找色能力=" + (adapter.capabilities().countColor ? "有" : "无"));

    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    const image = adapter.capture("full", 1500);
    if (!image) {
        log("截图失败");
        return;
    }
    const located = locateBoard.locateBoardOnScreen({ orientation: "RED_BOTTOM" });
    if (!located || !located.ok) {
        log("定位失败：" + ((located && located.reason) || "无结果"));
        return;
    }
    const grid = Object.assign({}, located.grid, { version: 1, orientation: "RED_BOTTOM" });
    log("几何：原点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) + ") 格距 (" +
        grid.stepX.toFixed(1) + "," + grid.stepY.toFixed(1) + ")");

    const host = hostFor(grid);
    const boxes = host.detect(image, { confidence: 0.22 });
    const mapped = detector.mapToCells(boxes, grid);
    const cells = (mapped && mapped.cells) || [];
    const byIndex = {};
    for (let i = 0; i < cells.length; i++) {
        const one = cells[i];
        const index = one.index != null ? one.index : (one.row * 9 + one.col);
        if (index == null || byIndex[index]) {
            continue;
        }
        byIndex[index] = { index: index, col: index % 9, row: Math.floor(index / 9), box: one.box, residual: one.residual };
    }
    log("检测框 " + boxes.length + " ⇒ 映射 " + Object.keys(byIndex).length + " 格");

    let findColorMs = 0;
    let sampleMs = 0;
    const rows = [];
    for (let index = 0; index < 90; index++) {
        const cell = byIndex[index];
        if (!cell) {
            continue;
        }
        const box = cropper.boxForItem(grid, cell, {});
        const started = Date.now();
        const counts = inkColor.countsFor(host, image, box, { threshold: TOLERANCES[1] });
        findColorMs += Date.now() - started;
        const sampleStarted = Date.now();
        const samples = cropper.sampleColors(host, image, box, {});
        const oldVerdict = pieceColor.classifySamples(samples);
        sampleMs += Date.now() - sampleStarted;
        const verdict = inkColor.judgeCounts(counts, { threshold: TOLERANCES[1] });
        const row = {
            index: index,
            col: cell.col,
            row: cell.row,
            region: counts.region,
            area: counts.area,
            source: counts.source,
            red: counts.red,
            black: counts.black,
            redDensity: verdict.features.redDensity,
            blackDensity: verdict.features.blackDensity,
            side: verdict.side,
            confidence: verdict.confidence,
            reason: verdict.reason,
            ms: Date.now() - started,
            oldSide: oldVerdict.side,
            oldReason: oldVerdict.reason,
            samples: samples.length
        };
        rows.push(row);
        log("(" + cell.col + "," + cell.row + ") 找色 " + verdict.side +
            " 红" + row.red + "/" + row.redDensity.toFixed(3) +
            " 黑" + row.black + "/" + row.blackDensity.toFixed(3) +
            " 面积" + row.area + " 源" + row.source + " 分" + verdict.confidence.toFixed(2) +
            " ｜老分类器 " + row.oldSide + " 采样" + row.samples +
            " ｜" + row.ms + "ms" + (verdict.reason ? " ｜" + verdict.reason : ""));
    }

    // 空格对照：没被检测到的格子
    let emptyChecked = 0;
    for (let index = 0; index < 90 && emptyChecked < 8; index += 7) {
        if (byIndex[index]) {
            continue;
        }
        emptyChecked += 1;
        const col = index % 9, row = Math.floor(index / 9);
        const box = cropper.cropBoxFor(grid, col, row, {});
        const counts = inkColor.countsFor(host, image, box, { threshold: TOLERANCES[1] });
        const verdict = inkColor.judgeCounts(counts, { threshold: TOLERANCES[1] });
        log("空(" + col + "," + row + ") " + verdict.side + " 红" + counts.red + " 黑" + counts.black +
            " 面积" + counts.area);
    }

    log("耗时：找色判色合计 " + findColorMs + "ms（" + rows.length + " 格）｜老采样+分类器合计 " + sampleMs + "ms");
    writeText(outDir + "/report.json", JSON.stringify({
        stamp: stamp, elapsedMs: Date.now() - startedAt,
        grid: { originX: grid.originX, originY: grid.originY, stepX: grid.stepX, stepY: grid.stepY },
        countColorCapable: !!adapter.capabilities().countColor,
        findColorMs: findColorMs, sampleMs: sampleMs,
        cells: rows
    }, null, 1));
    try {
        if (typeof images !== "undefined" && images.save) {
            images.save(image, outDir + "/frame.png");
        }
    } catch (ignore) {
        // 存不下来就算了
    }
    log("报告：" + outDir + "/report.json");
    log("结束（总耗时 " + (Date.now() - startedAt) + "ms）");
}

main();
