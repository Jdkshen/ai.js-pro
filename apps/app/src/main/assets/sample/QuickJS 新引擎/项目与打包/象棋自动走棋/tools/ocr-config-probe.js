// @engine quickjs
/**
 * `$ocr.create({models})` 生效性探针（QuickJS 侧）。
 *
 * 背景：QuickJS 的 `$ocr` 以前**没有** `models` 参数（只有 Rhino 的 `__ocr__.js` 有），
 * 传了会被静默忽略、永远用随包那份 —— 2026-09-20 已补上（原生引导 + JNI + Java 一起改）。
 * 这个探针用来**证明它真的生效**，判据是「同一批格子的识别输出换模型后随之改变」：
 *   · 随包 v6 small：象 0.9x｜士 1.00｜将 0.7x｜帅 0.5x
 *   · v6 tiny：象 0.41｜「H」(实为士)｜得 0.34｜航 0.26
 * 两者差别极大 ⇒ 换了就是换了，不会看错。
 *
 * 变体名从**应用自己的外部目录**读（`/sdcard/xiangqi-p4/**` 这种 shell push 的路径应用读不到）：
 *   /sdcard/Android/data/<pkg>/files/ocr-model/variant.txt   （内容 = 子目录名，如 v6tiny / v6medium）
 * 该子目录里必须具名那 5 个文件（字典要改成 `ppocr_keys_v6_tiny.txt`）。
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

const PKG = "com.jdkshen.aijspro";
const EXT = "/sdcard/Android/data/" + PKG + "/files/ocr-model";
const VARIANT_FILE = EXT + "/variant.txt";
/** 要看的格（覆盖真机难点：象 / 士 / 将 / 帅 / 马 / 炮） */
const WATCH = [[2, 0], [3, 0], [4, 0], [6, 0], [4, 9], [1, 9], [2, 3], [5, 0]];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[ocrcfg] " + text);
    }
}

function readVariant() {
    try {
        if (typeof files !== "undefined" && files.read) {
            return String(files.read(VARIANT_FILE) || "").trim();
        }
    } catch (error) {
        log("读 variant.txt 失败：" + error);
    }
    return "";
}

function main() {
    const startedAt = Date.now();
    const variant = readVariant();
    const modelsDir = variant ? (EXT + "/" + variant) : "";
    log("变体：" + (variant || "(空)") + "｜models 参数：" + (modelsDir || "(空 ⇒ 用随包模型)"));

    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    const image = adapter.capture("full", 1500);
    const located = locateBoard.locateBoardOnScreen({ orientation: "RED_BOTTOM" });
    if (!located || !located.ok) {
        log("定位失败：" + ((located && located.reason) || "无结果"));
        return;
    }
    const grid = Object.assign({}, located.grid, { version: 1, orientation: "RED_BOTTOM" });

    let session = null;
    try {
        session = $ocr.create({
            backend: "ppocr",
            models: modelsDir,
            targetSize: 768
        });
    } catch (error) {
        log("会话失败：" + (error && error.message ? error.message : error));
        return;
    }
    log("会话就绪（含截图/定位 " + (Date.now() - startedAt) + "ms）");

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

    let totalMs = 0;
    let line = "";
    for (let i = 0; i < WATCH.length; i++) {
        const col = WATCH[i][0];
        const row = WATCH[i][1];
        const box = cropper.cropBoxFor(grid, col, row, {});
        const region = cropper.centreRegion(box, 0.70);
        const t0 = Date.now();
        let text = "";
        let score = 0;
        try {
            const blocks = session.detect(image, { max: 1, region: [region.x, region.y, region.width, region.height] }) || [];
            if (blocks.length) {
                text = String(blocks[0].text || blocks[0].label || "");
                score = Number(blocks[0].score == null ? blocks[0].confidence : blocks[0].score) || 0;
            }
        } catch (error) {
            text = "!ERR(" + (error && error.message ? error.message : error) + ")";
        }
        const ms = Date.now() - t0;
        totalMs += ms;
        const hit = pieceOcr.classOf(text);
        line += "(" + col + "," + row + ")" + text + "/" + score.toFixed(2) + (hit ? ("→" + hit.cls) : "→?") + " ";
    }
    log("检测框 " + boxes.length + " 个");
    log("逐格：" + line);
    log("耗时：" + totalMs + "ms / " + WATCH.length + " 格（" + (totalMs / WATCH.length).toFixed(1) + "ms 每格）");
    log("结束（总 " + (Date.now() - startedAt) + "ms）");
}

main();
