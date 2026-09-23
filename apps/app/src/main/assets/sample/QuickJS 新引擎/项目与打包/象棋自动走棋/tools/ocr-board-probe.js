// @engine quickjs
/**
 * 全盘 OCR 探针：把 90 格逐格裁出来 OCR 一遍，看 PP-OCR 到底能读出多少字。
 *
 * 为什么必须验它（2026-09-19）：驱动刚接上「单格 OCR 检查」——「读到字 = 那格有子」、
 * 「读不到字 = 那格是空的」。如果 OCR 对**确实有子**的格子也读不出字，那这个判据就是
 * 反的（会把真实存在的子当成空丢掉），比不加还糟。
 *
 * 用法：adb push 到手机，然后
 *   am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity --es path <路径>
 * 结果看 logcat（前缀 [ocrboard]）。
 *
 * 可选参数（autorun 那套不适用，这里用文件里改常量）：
 *   ORIGIN_X/ORIGIN_Y/STEP_X/STEP_Y —— 直接用当前这一局的实测几何（K40 实测值）。
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
const pieceOcr = load("src/vision/piece-ocr.js");
const recognizeBoard = load("tools/recognize-board.js");

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[ocrboard] " + text);
    }
}

// 当前这一局的实测几何（K40；改这里就能测别的分辨率/局面）
const GEOM = { originX: 81, originY: 666, stepX: 114.7, stepY: 118.0, orientation: "RED_BOTTOM" };

function main() {
    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    const frame = adapter.capture("full", 1500);
    if (!frame) {
        log("截图失败");
        return;
    }
    log("截图成功 " + (adapter.describeFrame ? adapter.describeFrame(frame) : ""));

    if (typeof $ocr === "undefined" || typeof $ocr.create !== "function") {
        log("没有 $ocr，结束");
        return;
    }
    const session = $ocr.create({ backend: "ppocr" });
    log("PP-OCR 会话已建立");

    // ① 先看 YOLO 那边认出来的盘面（作为参照）
    try {
        const recognized = recognizeBoard.recognizeScreen({
            grid: {
                originX: GEOM.originX, originY: GEOM.originY,
                stepX: GEOM.stepX, stepY: GEOM.stepY,
                orientation: GEOM.orientation, version: 1, cols: 9, rows: 10
            },
            orientation: GEOM.orientation,
            confidence: 0.25,
            autoAlign: false,
            frame: frame
        });
        log("YOLO 盘面：" + (recognized && recognized.ok
            ? recognized.chars.map(function (c) { return c || "."; }).join("")
            : ("失败 " + (recognized && recognized.reason))));
    } catch (error) {
        log("YOLO 识别异常：" + error);
    }

    // ① 先看整屏 detect 能不能读到字（这是最可信的能力验证）
    try {
        const t0 = Date.now();
        const blocks = session.detect(frame, { max: 12 }) || [];
        log("整屏 detect 用时 " + (Date.now() - t0) + "ms，" + blocks.length + " 块");
        for (let i = 0; i < blocks.length && i < 12; i++) {
            const b = blocks[i] || {};
            log("  块 " + i + "：text=" + (b.text || b.label) + " score=" +
                (b.score == null ? b.confidence : b.score) + " bounds=" +
                JSON.stringify(b.bounds || b.box || b.frame || null));
        }
    } catch (error) {
        log("整屏 detect 异常：" + (error && error.message ? error.message : error));
    }

    // ② 用 region 让引擎自己裁（不自己 clip）：先试 (7,2) 那格已知有黑炮
    const probeBox = {
        x: Math.round(GEOM.originX + 7 * GEOM.stepX - GEOM.stepX * 0.45),
        y: Math.round(GEOM.originY + 2 * GEOM.stepY - GEOM.stepY * 0.45),
        width: Math.round(GEOM.stepX * 0.9),
        height: Math.round(GEOM.stepY * 0.9)
    };
    try {
        const t0 = Date.now();
        const one = session.detect(frame, {
            max: 3,
            region: [probeBox.x, probeBox.y, probeBox.width, probeBox.height]
        }) || [];
        log("region detect（(7,2) 已知有黑炮）用时 " + (Date.now() - t0) + "ms，" + one.length + " 块 ⇒ " +
            JSON.stringify(one.map(function (b) {
                return { text: b && (b.text || b.label), score: b && (b.score == null ? b.confidence : b.score) };
            })));
    } catch (error) {
        log("region detect 异常：" + (error && error.message ? error.message : error));
    }

    // ③ 自己 clip 再 detect（驱动现在走的就是这条路）—— 必须把失败原因打出来
    for (let k = 0; k < 3; k++) {
        const col = [7, 0, 4][k];
        const row = [2, 0, 2][k];
        const cx = GEOM.originX + col * GEOM.stepX;
        const cy = GEOM.originY + row * GEOM.stepY;
        const size = Math.max(8, Math.round(Math.min(GEOM.stepX, GEOM.stepY) * 0.9));
        const box = {
            x: Math.round(cx - size / 2), y: Math.round(cy - size / 2),
            width: size, height: size
        };
        let crop = null;
        try {
            crop = adapter.clip(frame, box);
            const size0 = crop && adapter.sizeOf ? adapter.sizeOf(crop) : null;
            const t0 = Date.now();
            const raw = session.detect(crop, { max: 1 });
            log("clip+detect (" + col + "," + row + ") 裁图尺寸=" + JSON.stringify(size0) +
                " 用时 " + (Date.now() - t0) + "ms ⇒ " + JSON.stringify(raw));
            const verdict = pieceOcr.confirmCell(session, crop,
                { x: 0, y: 0, width: size, height: size }, null, 0.6);
            log("  confirmCell 结论：" + JSON.stringify(verdict));
        } catch (error) {
            log("clip+detect (" + col + "," + row + ") 异常：" + (error && error.message ? error.message : error));
        } finally {
            try {
                if (crop && crop.recycle) {
                    crop.recycle();
                }
            } catch (ignore) {
                // 忽略
            }
        }
    }

    // ④ 逐格 OCR（用 region 那条路）
    const size = Math.max(8, Math.round(Math.min(GEOM.stepX, GEOM.stepY) * 0.9));
    const rows = [];
    let hit = 0;
    let miss = 0;
    let lastNote = "";
    const started = Date.now();
    for (let row = 0; row < 10; row++) {
        let line = "";
        for (let col = 0; col < 9; col++) {
            const cx = GEOM.originX + col * GEOM.stepX;
            const cy = GEOM.originY + row * GEOM.stepY;
            const box = {
                x: Math.round(cx - size / 2), y: Math.round(cy - size / 2),
                width: size, height: size
            };
            let crop = null;
            let text = "·";
            let note = "";
            try {
                const t0 = Date.now();
                const blocks = session.detect(frame, {
                    max: 1,
                    region: [box.x, box.y, box.width, box.height]
                }) || [];
                const one = blocks[0];
                const verdict = one
                    ? pieceOcr.normalize(one.text || one.label)
                    : null;
                if (verdict) {
                    text = verdict.glyph;
                    hit += 1;
                } else {
                    text = "·";
                    miss += 1;
                    note = (Date.now() - t0) + "ms";
                }
            } catch (error) {
                text = "!";
                note = String(error && error.message ? error.message : error).slice(0, 40);
            }
            if (note) {
                lastNote = note;
            }
            line += text;
        }
        rows.push(line);
        log("第 " + row + " 行：" + line);
    }
    log("OCR 逐格结果（每题过读到字 " + hit + " 格 / 没读到 " + miss + " 格，用时 " +
        (Date.now() - started) + "ms）");
    log("（第一行是最上面那行；红方在下、黑方在上）");

    try {
        session.release();
    } catch (ignore) {
        // 忽略
    }
    log("结束");
}

try {
    main();
} catch (error) {
    log("异常：" + (error && error.stack ? error.stack : error));
}
