// @engine quickjs
/**
 * 单模型 OCR 探针：一次只用一个模型目录（目录写在设备上的 /sdcard/xiangqi-p4/ocr/model.txt），
 * 每次跑之前 force-stop 应用，保证进程里只加载过这一个模型 —— 用来排除「同进程多模型切换」的干扰。
 *
 * 为什么需要它：2026-09-19 用「同帧三模型循环」对比时，tiny/small/medium 输出**逐字符完全相同**，
 * 而原生日志显示确实 load 了三次 ⇒ 必须用「单模型 + 重启进程」把变量隔离开再下结论。
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

const MODEL_FILE = "/sdcard/xiangqi-p4/ocr/model.txt";
/** 要看的格（覆盖真机上的难点：象 / 士 / 将 / 帅 / 马 / 炮） */
const WATCH = [[2, 0], [3, 0], [4, 0], [6, 0], [4, 9], [1, 9], [2, 3], [5, 0]];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[ocr-one] " + text);
    }
}

function readModelDir() {
    try {
        if (typeof files !== "undefined" && files.read) {
            return String(files.read(MODEL_FILE) || "").trim();
        }
    } catch (error) {
        log("读 " + MODEL_FILE + " 失败：" + error);
    }
    return "";
}

function main() {
    const startedAt = Date.now();
    const dir = readModelDir();
    log("模型目录：" + (dir || "(空 ⇒ 用随包模型)"));
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
            models: dir || undefined,
            targetSize: 768
        });
    } catch (error) {
        log("会话失败：" + (error && error.message ? error.message : error));
        return;
    }
    const loadMs = Date.now() - startedAt;
    log("会话就绪（含截图/定位 " + loadMs + "ms）");

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
    log("检测框 " + boxes.length + " 个");

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
        line += "(" + col + "," + row + ")" + text + "/" + score.toFixed(2) +
            (hit ? ("→" + hit.cls) : "→?") + "(" + ms + "ms) ";
    }
    log("逐格：" + line);
    log("耗时：" + totalMs + "ms / " + WATCH.length + " 格（" + (totalMs / WATCH.length).toFixed(1) + "ms 每格）");
    log("结束（总 " + (Date.now() - startedAt) + "ms）");
}

main();
