// @engine quickjs
/**
 * OCR 模型 A/B 探针：同一帧、同一批格子，用不同模型目录各认一遍，比准确率与耗时。
 *
 * 为什么必须是「同帧 A/B」：换模型前后如果帧不同，分不清是模型变好还是盘面变了。
 *
 * 背景（引擎契约，2026-09-19 核对）：
 *   · `$ocr.create({backend:'ppocr', models:'/绝对目录'})` 会**直接**从该目录按固定文件名加载：
 *     `PP_OCRv6_tiny_det.ncnn.param/.bin`、`PP_OCRv6_tiny_rec.ncnn.param/.bin`、`ppocr_keys_v6_tiny.txt`；
 *   · 原生侧靠路径里有没有 “OCRv6” 决定 v6 模式，v6 模式下**字典文件名是写死的** ——
 *     所以大模型要把它自己的字典改名成 `ppocr_keys_v6_tiny.txt` 放进同目录（否则索引对不上，全是乱码）。
 *
 * 用法：把本文件 push 到手机后运行；模型目录见下方 MODELS。
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
const locateBoard = load("src/vision/locate-board.js");
const recognizeBoard = load("experiments/vision/recognize-board.js");
const detector = load("src/vision/piece-detector.js");
const cropper = load("src/vision/piece-cropper.js");
const pieceOcr = load("src/vision/piece-ocr.js");

/** 待比较的模型目录（设备上；每个目录都要有那 5 个固定名文件） */
const MODELS = [
    { name: "v6tiny(随包)", dir: "/sdcard/xiangqi-p4/ocr/v6tiny" },
    { name: "v6small", dir: "/sdcard/xiangqi-p4/ocr/v6small" },
    { name: "v6medium", dir: "/sdcard/xiangqi-p4/ocr/v6medium" }
];

/** 同一帧用同一批格子：格中心 0.70 的裁剪区 */
const CELL_RATIO = 0.70;

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[ocr-ab] " + text);
    }
}

function hostFor(grid) {
    let cachedFrame = null;
    let cachedBoxes = null;
    return {
        detect: function (image) {
            if (image === cachedFrame && cachedBoxes) {
                return cachedBoxes;
            }
            let boxes = [];
            try {
                const recognized = recognizeBoard.recognizeScreen({
                    grid: Object.assign({}, grid, { cols: 9, rows: 10 }),
                    orientation: "RED_BOTTOM",
                    confidence: 0.22,
                    autoAlign: false,
                    frame: image
                });
                boxes = detector.normalizeBoxes((recognized && recognized.native && recognized.native.list) || []);
            } catch (error) {
                log("检测异常：" + (error && error.message ? error.message : error));
            }
            cachedFrame = image;
            cachedBoxes = boxes;
            return boxes;
        }
    };
}

function main() {
    const startedAt = Date.now();
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
    log("定位成功｜原点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) + ")");

    const host = hostFor(grid);
    const boxes = host.detect(image);
    const mapped = detector.mapToCells(boxes, grid);
    const cells = (mapped && mapped.cells) || [];
    log("检测框 " + boxes.length + " 个 ⇒ 映射 " + cells.length + " 格");

    // 每格的裁剪区（先算好，所有模型共用同一组区域）
    const targets = [];
    for (let i = 0; i < cells.length; i++) {
        const one = cells[i];
        const box = cropper.cropBoxFor(grid, one.col, one.row, {});
        const region = cropper.centreRegion(box, CELL_RATIO);
        if (region) {
            targets.push({ col: one.col, row: one.row, region: [region.x, region.y, region.width, region.height] });
        }
    }

    const results = [];
    for (let m = 0; m < MODELS.length; m++) {
        const model = MODELS[m];
        let session = null;
        try {
            session = $ocr.create({ backend: "ppocr", models: model.dir, targetSize: 768 });
        } catch (error) {
            log("[" + model.name + "] 会话建立失败：" + (error && error.message ? error.message : error));
            results.push({ name: model.name, ok: false, reason: String(error) });
            continue;
        }
        log("[" + model.name + "] 会话就绪，开始逐格识别（" + targets.length + " 格）");
        const rows = [];
        let glyphs = 0;
        let scoreSum = 0;
        let scoreCount = 0;
        const started = Date.now();
        for (let i = 0; i < targets.length; i++) {
            const t = targets[i];
            let text = "";
            let score = 0;
            try {
                const blocks = session.detect(image, { max: 1, region: t.region }) || [];
                if (blocks.length) {
                    text = String(blocks[0].text || blocks[0].label || "");
                    score = Number(blocks[0].score == null ? blocks[0].confidence : blocks[0].score) || 0;
                }
            } catch (error) {
                text = "!ERR";
                score = -1;
            }
            const hit = pieceOcr.classOf(text);
            if (hit) {
                glyphs += 1;
                scoreSum += score;
                scoreCount += 1;
            }
            rows.push({ col: t.col, row: t.row, text: text, score: score, cls: hit ? hit.cls : null });
        }
        const ms = Date.now() - started;
        const mean = scoreCount > 0 ? (scoreSum / scoreCount) : 0;
        log("[" + model.name + "] 认出棋种 " + glyphs + "/" + targets.length +
            "｜认出的平均分 " + mean.toFixed(3) + "｜" + ms + "ms（" + (ms / targets.length).toFixed(1) + "ms/格）");
        let line = "";
        for (let i = 0; i < rows.length; i++) {
            line += "(" + rows[i].col + "," + rows[i].row + ")" + rows[i].text + "/" + rows[i].score.toFixed(2) + " ";
        }
        log("[" + model.name + "] 逐格：" + line);
        results.push({ name: model.name, ok: true, glyphs: glyphs, total: targets.length, mean: mean, ms: ms, rows: rows });
        try {
            if (session && session.release) {
                session.release();
            }
        } catch (error) {
            // 忽略释放失败
        }
    }

    log("—— 汇总（同帧 " + targets.length + " 格）——");
    for (let i = 0; i < results.length; i++) {
        const one = results[i];
        if (!one.ok) {
            log(one.name + "：不可用（" + one.reason + "）");
        } else {
            log(one.name + "：认种 " + one.glyphs + "/" + one.total + "｜平均分 " + one.mean.toFixed(3) +
                "｜" + one.ms + "ms");
        }
    }
    log("结束（总耗时 " + (Date.now() - startedAt) + "ms）");
}

main();
