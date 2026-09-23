// @engine quickjs
/**
 * 红黑颜色标定探针（重构方案 §8 的 `tools/color-calibration-probe.js`）。
 *
 * 目的：把「红/黑颜色分类」的阈值**建立在真实帧的数据上**，而不是拍脑袋。
 * 真机问题（2026-09-19）：黑方棋子全被判 UNKNOWN（`红黑得分差 0.03`），
 * 说明「暗像素占比」这条特征在天天象棋的实际渲染下取不到值 —— 得看数说话。
 *
 * 输出（logcat 前缀 `[colorcal]`）：
 *   · 逐格特征表：redRatio / darkRatio / meanSaturation / meanLabA + 判定与得分；
 *   · 亮度直方图：一枚**已知有子**的格 vs 一个**背景空**格，看暗像素到底在哪一档；
 *   · `/sdcard/xiangqi-p4/color-calib/<时间戳>.json` + `frame.png`（供离线复算）。
 *
 * 用法：
 *   adb push 到手机 → am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity --es path <本文件>
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
const locateBoard = load("tools/locate-board.js");
const recognizeBoard = load("tools/recognize-board.js");
const detector = load("src/vision/piece-detector.js");
const cropper = load("src/vision/piece-cropper.js");
const pieceColor = load("src/vision/piece-color.js");

const OUT_ROOT = "/sdcard/xiangqi-p4/color-calib";
/** 采样比例（方案 §3.6 的常规值 + 更贴盘面的两个候选，一次跑完对比） */
const RATIOS = [0.72, 0.86, 1.0];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[colorcal] " + text);
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

function hostFor(grid) {
    let cachedFrame = null;
    let cachedBoxes = null;
    return {
        clip: function (image, box) {
            return adapter.clip(image, box);
        },
        rgbAt: function (image, x, y) {
            return adapter.rgbAt(image, x, y);
        },
        detect: function (image, options) {
            if (image === cachedFrame && cachedBoxes) {
                return cachedBoxes;
            }
            let boxes = [];
            try {
                const recognized = recognizeBoard.recognizeScreen({
                    grid: grid ? Object.assign({}, grid, { cols: 9, rows: 10 }) : null,
                    orientation: "RED_BOTTOM",
                    confidence: (options && options.confidence) || 0.22,
                    autoAlign: false,
                    frame: image
                });
                boxes = detector.normalizeBoxes((recognized && recognized.native && recognized.native.list) || []);
            } catch (error) {
                log("检测异常：" + (error && error.message ? error.message : error));
                boxes = [];
            }
            cachedFrame = image;
            cachedBoxes = boxes;
            return boxes;
        }
    };
}

/** 亮度直方图（按 max(R,G,B) 分 8 档），看「暗像素」的真实分布 */
function luminanceHistogram(host, image, box) {
    const region = cropper.centreRegion(box, 0.86);
    const buckets = [0, 0, 0, 0, 0, 0, 0, 0];
    let count = 0;
    for (let y = region.y; y < region.y + region.height; y += 2) {
        for (let x = region.x; x < region.x + region.width; x += 2) {
            let px = null;
            try {
                px = host.rgbAt(image, x, y);
            } catch (error) {
                px = null;
            }
            if (!px) {
                continue;
            }
            count += 1;
            const v = Math.max(Number(px[0]) || 0, Number(px[1]) || 0, Number(px[2]) || 0);
            buckets[Math.min(7, Math.floor(v / 32))] += 1;
        }
    }
    const out = [];
    for (let i = 0; i < buckets.length; i++) {
        out.push(Math.round(100 * buckets[i] / Math.max(1, count)) + "%");
    }
    return { count: count, buckets: buckets, percent: out };
}

function featureLine(host, image, grid, col, row, options) {
    const box = cropper.cropBoxFor(grid, col, row, options);
    if (!box) {
        return null;
    }
    const perRatio = {};
    for (let i = 0; i < RATIOS.length; i++) {
        const ratio = RATIOS[i];
        const boxLike = Object.assign({}, box, {
            width: Math.round(box.width * ratio),
            height: Math.round(box.height * ratio),
            x: Math.round(box.centerX - box.width * ratio / 2),
            y: Math.round(box.centerY - box.height * ratio / 2)
        });
        const samples = cropper.sampleColors(host, image, boxLike, { colorRatio: 1.0 });
        const verdict = pieceColor.classifySamples(samples);
        perRatio[String(ratio)] = {
            samples: samples.length,
            features: verdict.features,
            side: verdict.side,
            redScore: verdict.redScore,
            blackScore: verdict.blackScore,
            reason: verdict.reason
        };
    }
    return { index: row * 9 + col, col: col, row: row, box: box, perRatio: perRatio };
}

function main() {
    const startedAt = Date.now();
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const outDir = OUT_ROOT + "/" + stamp;
    ensureDir(outDir);
    log("开始：" + stamp);

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
    log("定位成功｜原点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
        ") 格距 (" + grid.stepX.toFixed(1) + "," + grid.stepY.toFixed(1) + ")");

    const host = hostFor(grid);
    const boxes = host.detect(image, { confidence: 0.22 });
    const mapped = detector.mapToCells(boxes, grid);
    const cells = (mapped && mapped.cells) || [];
    log("检测框 " + boxes.length + " 个 ⇒ 映射 " + cells.length + " 格（拒绝 " +
        (((mapped && mapped.rejected) || []).length) + "）");

    // 有子的格（拿 YOLO 的位置，不看 OCR）
    const pieceCells = [];
    const taken = {};
    for (let i = 0; i < cells.length; i++) {
        const one = cells[i];
        const index = one.index != null ? one.index : (one.row * 9 + one.col);
        if (index == null || taken[index]) {
            continue;
        }
        taken[index] = true;
        pieceCells.push({ index: index, col: index % 9, row: Math.floor(index / 9) });
    }
    // 背景参照：没被检测到的格（多半是空的），挑 8 个分散的
    const emptyCells = [];
    for (let index = 0; index < 90 && emptyCells.length < 8; index += 7) {
        if (!taken[index]) {
            emptyCells.push({ index: index, col: index % 9, row: Math.floor(index / 9) });
        }
    }

    const report = {
        stamp: stamp,
        elapsedMs: Date.now() - startedAt,
        grid: { originX: grid.originX, originY: grid.originY, stepX: grid.stepX, stepY: grid.stepY },
        ratios: RATIOS,
        pieceCells: [],
        emptyCells: []
    };

    log("采样比例：" + RATIOS.join(" / "));
    log("—— 有子的格（YOLO 位置）——");
    for (let i = 0; i < pieceCells.length; i++) {
        const cell = pieceCells[i];
        const row = featureLine(host, image, grid, cell.col, cell.row, {});
        if (!row) {
            continue;
        }
        report.pieceCells.push(row);
        log("(" + cell.col + "," + cell.row + ") " + describeRatios(row.perRatio));
    }
    log("—— 背景参照（未被检测到的格）——");
    for (let i = 0; i < emptyCells.length; i++) {
        const cell = emptyCells[i];
        const row = featureLine(host, image, grid, cell.col, cell.row, {});
        if (!row) {
            continue;
        }
        report.emptyCells.push(row);
        log("(" + cell.col + "," + cell.row + ") " + describeRatios(row.perRatio));
    }

    // 亮度直方图：一枚有子的格 vs 一个背景格（按 0.86 采样区）
    const pieceRef = pieceCells[0];
    const emptyRef = emptyCells[0];
    if (pieceRef) {
        const box = cropper.cropBoxFor(grid, pieceRef.col, pieceRef.row, {});
        report.histogramPiece = luminanceHistogram(host, image, box);
        log("亮度直方图（有子 (" + pieceRef.col + "," + pieceRef.row + ")）：" +
            report.histogramPiece.percent.join(" "));
    }
    if (emptyRef) {
        const box = cropper.cropBoxFor(grid, emptyRef.col, emptyRef.row, {});
        report.histogramEmpty = luminanceHistogram(host, image, box);
        log("亮度直方图（背景 (" + emptyRef.col + "," + emptyRef.row + ")）：" +
            report.histogramEmpty.percent.join(" "));
    }

    writeText(outDir + "/report.json", JSON.stringify(report, null, 1));
    let frameSaved = false;
    try {
        if (typeof images !== "undefined" && images.save) {
            images.save(image, outDir + "/frame.png");
            frameSaved = true;
        }
    } catch (error) {
        frameSaved = false;
    }
    log("报告：" + outDir + "/report.json");
    log("截图：" + (frameSaved ? outDir + "/frame.png" : "（没存下来）"));
    log("结束（总耗时 " + (Date.now() - startedAt) + "ms）");
}

function describeRatios(perRatio) {
    const parts = [];
    for (let i = 0; i < RATIOS.length; i++) {
        const one = perRatio[String(RATIOS[i])];
        if (!one) {
            continue;
        }
        const f = one.features || {};
        parts.push(
            "@" + RATIOS[i] + " " + one.side +
            " 红" + (f.redRatio == null ? "-" : f.redRatio.toFixed(3)) +
            " 暗" + (f.darkRatio == null ? "-" : f.darkRatio.toFixed(3)) +
            " 饱和" + (f.meanSaturation == null ? "-" : f.meanSaturation.toFixed(2)) +
            " a*" + (f.meanLabA == null ? "-" : f.meanLabA.toFixed(1)) +
            " 分" + one.redScore.toFixed(2) + "/" + one.blackScore.toFixed(2) +
            " n" + one.samples
        );
    }
    return parts.join("｜");
}

main();
