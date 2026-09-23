// @engine quickjs
/**
 * 棋子样本采集（重构方案 §12 交付物 10：为将来 192×192 七类专用分类模型做数据集）。
 *
 * 做法（完全不联网、不需要人工先标）：
 *   一帧 → 定位棋盘 → YOLO 候选框 → 映射交点 → 逐枚裁剪 → PP-OCR 认字形
 *   ⇒ 把「棋子小图」按**类别**存进文件夹，并把 OCR 文本/分数写进 manifest.jsonl。
 *   OCR 认同的样本直接可用；OCR 没认同的存进 `_unknown/` 供人工复核（方案 §10 要求人工复核标签）。
 *
 * 输出（手机上）：
 *   /sdcard/xiangqi-p4/piece-samples/<类别>/*.png     例：R/ 车 / N/ 马 / C/ 炮 …
 *   /sdcard/xiangqi-p4/piece-samples/manifest.jsonl   每行：{file, cls, glyph, score, box, grid, at}
 *
 * 参数（改常量即可）：
 *   ROUNDS  —— 采几轮（每轮重新截图；建议自然对局中定时跑）
 *   GAP_MS  —— 两轮间隔
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
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

const adapter = load("src/runtime/aijs-adapter.js");
const locateBoard = load("tools/locate-board.js");
const recognizeBoard = load("tools/recognize-board.js");
const pieceDetector = load("src/vision/piece-detector.js");
const pieceCropper = load("src/vision/piece-cropper.js");
const pieceOcr = load("src/vision/piece-ocr.js");

const OUT_ROOT = "/sdcard/xiangqi-p4/piece-samples";
const ROUNDS = 3;
const GAP_MS = 1500;
/** OCR 认同的门槛（方案 §3.5：≥0.70 采纳） */
const OCR_ACCEPT = 0.70;

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[samples] " + text);
    }
}

function ensureDir(dir) {
    try {
        if (typeof files !== "undefined" && files.ensureDir) {
            files.ensureDir(dir + "/");
        }
    } catch (ignore) {
        // 忽略
    }
}

function appendLine(path, line) {
    try {
        if (typeof files !== "undefined" && files.append) {
            files.append(path, line + "\n");
            return true;
        }
    } catch (ignore) {
        // 忽略
    }
    return false;
}

function main() {
    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    // ⚠️ `piece-ocr.js` 在方案 §3.5 重写后没有 `glyphs()` 了（它变成常量表）：
    //    这里从 `CLASS_OF_GLYPH` 的**值**里取七类（2026-09-20 真机踩到旧接口，脚本直接抛）。
    const classes = Object.keys(pieceOcr.CLASS_OF_GLYPH).map(function (glyph) {
        return pieceOcr.CLASS_OF_GLYPH[glyph];
    });
    const uniq = {};
    for (let i = 0; i < classes.length; i++) {
        uniq[classes[i]] = true;
    }
    for (const cls in uniq) {
        ensureDir(OUT_ROOT + "/" + cls);
    }
    ensureDir(OUT_ROOT + "/_unknown");

    let session = null;
    try {
        session = (typeof $ocr !== "undefined" && $ocr.create) ? $ocr.create({ backend: "ppocr" }) : null;
    } catch (error) {
        session = null;
    }
    if (!session) {
        log("没有 OCR 会话，无法自动打标（可以只存 _unknown 供人工标）");
    }

    let saved = 0;
    let lowScore = 0;
    for (let round = 0; round < ROUNDS; round++) {
        const frame = adapter.capture("full", 1500);
        if (!frame) {
            log("第 " + (round + 1) + " 轮截图失败");
            break;
        }
        let grid = null;
        try {
            const located = locateBoard.locateBoardOnScreen({ orientation: "RED_BOTTOM" });
            grid = located && located.ok ? Object.assign({}, located.grid, { orientation: "RED_BOTTOM" }) : null;
        } catch (error) {
            grid = null;
        }
        if (!grid) {
            log("第 " + (round + 1) + " 轮定位失败");
            adapter.waitMs(GAP_MS);
            continue;
        }
        const host = {
            clip: function (image, box) { return adapter.clip(image, box); },
            rgbAt: function (image, x, y) { return adapter.rgbAt(image, x, y); }
        };
        const detected = detectIn(frame, grid);
        const mapped = pieceDetector.mapToCells(detected.boxes || [], grid, {});
        const crops = pieceCropper.cropAll(host, frame, grid, mapped.cells, {});
        log("第 " + (round + 1) + " 轮：框 " + (detected.boxes ? detected.boxes.length : 0) +
            " ⇒ 映射 " + mapped.cells.length + " 格 ⇒ 裁剪 " + crops.length + " 枚");
        for (let i = 0; i < crops.length; i++) {
            const crop = crops[i];
            let text = "";
            let score = 0;
            let cls = null;
            if (session && crop.image) {
                let blocks = null;
                try {
                    blocks = session.detect(crop.image, { max: 1 }) || [];
                } catch (error) {
                    blocks = null;
                }
                const one = blocks && blocks[0];
                if (one) {
                    text = String(one.text || one.label || "");
                    score = Number(one.score == null ? one.confidence : one.score) || 0;
                    const hit = pieceOcr.classOf(text);
                    cls = hit ? hit.cls : null;
                }
            }
            const dir = (cls && score >= OCR_ACCEPT) ? cls : "_unknown";
            const name = Date.now() + "-" + crop.index + "-" + (cls || "x") + ".png";
            const path = OUT_ROOT + "/" + dir + "/" + name;
            let wrote = false;
            try {
                if (typeof images !== "undefined" && images.save && crop.image) {
                    images.save(crop.image, path);
                    wrote = true;
                }
            } catch (error) {
                wrote = false;
            }
            if (!wrote) {
                releaseCropImage(crop);
                continue;
            }
            saved += 1;
            if (dir === "_unknown") {
                lowScore += 1;
            }
            appendLine(OUT_ROOT + "/manifest.jsonl", JSON.stringify({
                file: dir + "/" + name,
                cls: cls, glyph: text, score: Number(score.toFixed(3)),
                index: crop.index, col: crop.col, row: crop.row,
                box: crop.box,
                grid: { originX: grid.originX, originY: grid.originY, stepX: grid.stepX, stepY: grid.stepY },
                at: new Date().toISOString()
            }));
            releaseCropImage(crop);
        }
        adapter.waitMs(GAP_MS);
    }
    log("完成：存了 " + saved + " 张（其中待人工复核 " + lowScore + " 张）→ " + OUT_ROOT);
    log("下一步：把 piece-samples 拉回电脑，人工复核 _unknown/，即可用于训练 192×192 七类字形模型");
    try {
        if (session && session.release) {
            session.release();
        }
    } catch (ignore) {
        // 忽略
    }
}

/**
 * 还掉 cropAll 裁出来的原生帧（本工具是唯一读 crop.image 的地方；原生帧不会 GC，用完要还）。
 * ⚠️ 2026-09-21：这条工具以前从来不还 —— 每轮按“有子格数”堆帧（与 vision-pipeline 同一类
 *    事故，见那里 ③ 的注释）。
 */
function releaseCropImage(crop) {
    const frame = crop && crop.image;
    if (!frame) {
        return;
    }
    try {
        if (typeof adapter.releaseFrame === "function") {
            adapter.releaseFrame(frame);
        } else if (typeof frame.recycle === "function") {
            frame.recycle();
        }
    } catch (ignore) {
        // 还不了就算了
    }
}

/**
 * 检测：复用 recognize-board（矩形模型要按宽高比送区域，自己送整屏会撞原生断言）。
 * 采集只要「有没有子」，所以用较低的置信度多收样本（方案 §3.2 建议 0.22）。
 */
function detectIn(image, grid) {
    const host = {
        detect: function (img) {
            try {
                const recognized = recognizeBoard.recognizeScreen({
                    grid: grid ? Object.assign({}, grid, { cols: 9, rows: 10 }) : null,
                    orientation: "RED_BOTTOM",
                    confidence: 0.22,
                    autoAlign: false,
                    frame: img
                });
                return (recognized && recognized.native && recognized.native.list) || [];
            } catch (error) {
                log("检测异常：" + (error && error.message ? error.message : error));
                return [];
            }
        }
    };
    return pieceDetector.detect(host, image, { confidence: 0.22 });
}

try {
    main();
} catch (error) {
    log("异常：" + (error && error.stack ? error.stack : error));
}
