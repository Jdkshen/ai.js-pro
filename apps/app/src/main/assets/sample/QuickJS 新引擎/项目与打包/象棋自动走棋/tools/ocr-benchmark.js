// @engine quickjs
/**
 * OCR 基准（方案 §8 的 `tools/ocr-benchmark.js`，也是 §12 交付物 #9 的性能/准确率数据源）。
 *
 * 量什么（都是真机数字，不靠估）：
 *   · **耗时**：单格逐候选（中心 × 尺度）的毫秒分布、均值、P90；整盘 30 格的总时间；
 *   · **准确率代理**：以「流水线三帧融合后的盘面」为参考（它不是真值，但比单帧稳），
 *     统计这一帧 OCR 的兵种与参考盘面一致的格子数 / 有子格数；
 *   · **候选有效性**：第几个候选命中（0=格中心 0.70），命中时分数落在哪一档。
 *
 * 配置从设备文件读（应用自己的外部目录，shell push 进去的 /sdcard/xiangqi-p4 应用读不到）：
 *   /sdcard/Android/data/<pkg>/files/ocr-model/variant.txt   （模型档位，可空=随包）
 *
 * 产物：
 *   /sdcard/xiangqi-p4/ocr-bench/<stamp>/report.json + frame.png
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
const locateBoard = load("tools/locate-board.js");
const recognizeBoard = load("tools/recognize-board.js");
const detector = load("src/vision/piece-detector.js");
const cropper = load("src/vision/piece-cropper.js");
const pieceOcr = load("src/vision/piece-ocr.js");
const visionPipeline = load("src/vision/vision-pipeline.js");
const frameGate = load("src/vision/frame-gate.js");

const PKG = "com.jdkshen.aijspro";
const EXT = "/sdcard/Android/data/" + PKG + "/files/ocr-model";
const OUT_ROOT = "/sdcard/xiangqi-p4/ocr-bench";
/** 候选：与流水线一致（格中心 / 检测框中心）× 多个尺度 */
const SCALES = [0.70, 0.54];
const CENTRES = ["grid", "detector"];
/** 表格里最多打这么多行，免得刷爆日志 */
const MAX_ROWS = 34;

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[ocrbench] " + text);
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

function readVariant() {
    try {
        if (typeof files !== "undefined" && files.read) {
            return String(files.read(EXT + "/variant.txt") || "").trim();
        }
    } catch (ignore) {
        // 忽略
    }
    return "";
}

function percentile(sorted, ratio) {
    if (!sorted.length) {
        return 0;
    }
    const idx = Math.min(sorted.length - 1, Math.max(0, Math.round((sorted.length - 1) * ratio)));
    return sorted[idx];
}

function main() {
    const startedAt = Date.now();
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const outDir = OUT_ROOT + "/" + stamp;
    ensureDir(outDir);
    const variant = readVariant();
    log("开始：" + stamp + "｜模型：" + (variant || "(随包)"));

    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    // 稳定帧：动画帧上的 OCR 数字没有意义（帧门是方案 §3.1 的东西，这里先自证一下）
    const gate = frameGate.create({ log: log });
    const frames = [];
    for (let i = 0; i < 2; i++) {
        if (i > 0) {
            adapter.waitMs(140);
        }
        const one = adapter.capture("full", 1500);
        if (one) {
            frames.push(one);
        }
    }
    if (!frames.length) {
        log("截图失败");
        return;
    }
    const image = frames[frames.length - 1];
    if (frames.length >= 2) {
        const samples = [];
        for (let i = 0; i < frames.length; i++) {
            const pts = [];
            for (let y = 200; y < 1800; y += 160) {
                for (let x = 60; x < 1000; x += 160) {
                    const px = adapter.rgbAt(frames[i], x, y);
                    if (px) {
                        // ⚠️ 必须是**数字**（灰度均值）：frame-gate.meanAbsDiff 做的是 Number(a[i])-Number(b[i])，
                        //    传 [r,g,b] 会变成 NaN（2026-09-20 这里就打印过「平均差 NaN」）。
                        pts.push((Number(px[0]) + Number(px[1]) + Number(px[2])) / 3);
                    }
                }
            }
            samples.push(pts);
        }
        const stability = gate.check(samples[0], samples[1], "bench");
        log("稳定帧：" + (stability.stable ? "稳定" : "不稳定") +
            "（平均差 " + (stability.meanDiff == null ? "-" : stability.meanDiff.toFixed(2)) + "）");
    }

    const located = locateBoard.locateBoardOnScreen({ orientation: "RED_BOTTOM" });
    if (!located || !located.ok) {
        log("定位失败：" + ((located && located.reason) || "无结果"));
        return;
    }
    const grid = Object.assign({}, located.grid, { version: 1, orientation: "RED_BOTTOM" });
    log("定位成功｜原点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) + ")");

    // 检测（走共用底座 detect-only：只要原始框，不跑旧识别）
    let boxes = [];
    try {
        const out = recognizeBoard.detectOnly(image, grid, { confidence: 0.22 });
        boxes = (out && out.ok) ? detector.normalizeBoxes(out.raw || []) : [];
        if (!out || !out.ok) {
            log("检测失败：" + ((out && out.reason) || "无结果"));
        }
    } catch (error) {
        log("检测异常：" + (error && error.message ? error.message : error));
    }
    const mapped = detector.mapToCells(boxes, grid);
    const cells = (mapped && mapped.cells) || [];
    const labelOf = {};
    for (let i = 0; i < cells.length; i++) {
        labelOf[cells[i].index] = cells[i].box ? cells[i].box.label : null;
    }
    log("检测框 " + boxes.length + " 个 ⇒ 映射 " + cells.length + " 格");

    // 参考盘面：流水线连喂 3 帧（方案 §3.8 要两帧一致；单帧全是 `?` 没法当参考）
    const session = (typeof $ocr !== "undefined" && $ocr.create)
        ? $ocr.create({ backend: "ppocr", models: variant ? (EXT + "/" + variant) : "", targetSize: 768 })
        : null;
    if (!session) {
        log("没有 OCR 会话，结束");
        return;
    }
    let reference = null;
    let pipeline = null;
    try {
        pipeline = visionPipeline.create({
            detect: function (pic, detectOptions) {
                try {
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
        const t0 = Date.now();
        for (let i = 0; i < 3; i++) {
            reference = pipeline.recognize(image, grid, { side: "w" });
        }
        log("参考盘面（流水线 3 帧：" + (Date.now() - t0) + "ms）：" +
            (reference && reference.board ? reference.board.join("") : "-"));
    } catch (error) {
        log("流水线参考失败：" + (error && error.message ? error.message : error));
    }

    // 逐格跑候选
    const rows = [];
    const times = [];
    let hits = 0;
    let agree = 0;
    let notAgree = 0;
    for (let c = 0; c < cells.length; c++) {
        const cell = cells[c];
        const gridBox = cropper.cropBoxFor(grid, cell.col, cell.row, {});
        const detBox = cell.box || null;
        const candidates = [];
        for (let b = 0; b < CENTRES.length; b++) {
            for (let s = 0; s < SCALES.length; s++) {
                candidates.push({ centre: CENTRES[b], scale: SCALES[s] });
            }
        }
        const tries = [];
        let best = null;
        for (let k = 0; k < candidates.length; k++) {
            const cand = candidates[k];
            const box = cand.centre === "grid" ? gridBox : detBox;
            if (!box) {
                continue;
            }
            const region = cropper.centreRegion(box, cand.scale);
            if (!region) {
                continue;
            }
            const t0 = Date.now();
            let text = "";
            let score = 0;
            try {
                const blocks = session.detect(image, {
                    max: 1, region: [region.x, region.y, region.width, region.height]
                }) || [];
                if (blocks.length) {
                    text = String(blocks[0].text || blocks[0].label || "");
                    score = Number(blocks[0].score == null ? blocks[0].confidence : blocks[0].score) || 0;
                }
            } catch (error) {
                text = "!ERR";
            }
            const ms = Date.now() - t0;
            times.push(ms);
            const hit = pieceOcr.classOf(text);
            tries.push({ centre: cand.centre, scale: cand.scale, text: text, score: score, ms: ms, cls: hit ? hit.cls : null });
            if (hit && (!best || score > best.score)) {
                best = { cls: hit.cls, text: text, score: score, centre: cand.centre, scale: cand.scale, rank: k };
            }
            if (best && best.score >= 0.8) {
                break;   // 与流水线一致：够确定就不再试
            }
        }
        const index = cell.index;
        const refChar = reference && reference.board ? reference.board[index] : null;
        const refCls = refChar && refChar !== "?" ? String(refChar).toUpperCase() : null;
        let verdict = "跳过（参考为空）";
        if (refCls) {
            if (best && best.cls === refCls) {
                agree += 1;
                verdict = "一致";
            } else {
                notAgree += 1;
                verdict = "不一致（OCR " + (best ? best.cls : "-") + " vs 参考 " + refCls + "）";
            }
        }
        if (best) {
            hits += 1;
        }
        rows.push({
            index: index, col: cell.col, row: cell.row,
            best: best, tries: tries, reference: refChar, verdict: verdict
        });
    }

    const sorted = times.slice().sort(function (a, b) { return a - b; });
    const total = times.reduce(function (a, b) { return a + b; }, 0);
    const mean = times.length ? total / times.length : 0;
    log("候选调用 " + times.length + " 次｜总 " + total + "ms｜均值 " + mean.toFixed(1) + "ms" +
        "｜P50 " + percentile(sorted, 0.5) + "ms｜P90 " + percentile(sorted, 0.9) + "ms" +
        "｜最大 " + (sorted.length ? sorted[sorted.length - 1] : 0) + "ms");
    log("有子格 " + cells.length + "｜OCR 认出兵种 " + hits + " 格｜与参考一致 " + agree +
        "／不一致 " + notAgree);
    const table = [];
    for (let i = 0; i < rows.length && i < MAX_ROWS; i++) {
        const r = rows[i];
        table.push("(" + r.col + "," + r.row + ")" +
            (r.best ? (r.best.text + "/" + r.best.score.toFixed(2) + "→" + r.best.cls +
                "@" + r.best.centre + r.best.scale + " 第" + (r.best.rank + 1) + "候选") : "（没认出来）") +
            "｜参考 " + (r.reference || "空") + "｜" + r.verdict);
    }
    log("逐格：");
    for (let i = 0; i < table.length; i++) {
        log("  " + table[i]);
    }

    const report = {
        stamp: stamp, elapsedMs: Date.now() - startedAt, variant: variant || "(bundled)",
        grid: { originX: grid.originX, originY: grid.originY, stepX: grid.stepX, stepY: grid.stepY },
        candidates: { scales: SCALES, centres: CENTRES },
        timing: {
            calls: times.length, totalMs: total, meanMs: mean,
            p50: percentile(sorted, 0.5), p90: percentile(sorted, 0.9),
            max: sorted.length ? sorted[sorted.length - 1] : 0
        },
        accuracy: { pieceCells: cells.length, ocrHits: hits, agree: agree, notAgree: notAgree },
        referenceBoard: reference && reference.board ? reference.board.join("") : null,
        reductionStats: reference && reference.stats ? reference.stats : null,
        rows: rows
    };
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
    log("报告：" + outDir + "/report.json" + (frameSaved ? "｜截图 " + outDir + "/frame.png" : ""));
    log("结束（总耗时 " + (Date.now() - startedAt) + "ms）");
}

main();
