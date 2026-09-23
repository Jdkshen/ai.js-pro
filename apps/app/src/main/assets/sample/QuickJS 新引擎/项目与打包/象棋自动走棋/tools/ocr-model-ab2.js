// @engine quickjs
/**
 * 高精度模型「装入 + 立刻实测」探针。
 *
 * 为什么不是用 `$ocr.create({models:'/dir'})`：QuickJS 侧的内联引导代码**没有实现 `models`**
 * （只有 Rhino 的 `__ocr__.js` 有），传了也永远用随包的 tiny —— 2026-09-19 实测 tiny/small/medium
 * 输出逐字符完全相同，原生日志却显示确实 load 了三次，就是被这一点坑的。
 *
 * 所以这里走「应用自己把模型装进私有目录」：PPOcr 用私有目录 `files/models/ppocrv6/` 下的
 * 那 5 个固定文件名，应用进程对自己的外部目录(`/sdcard/Android/data/<pkg>/files/ocr-model/`)
 * 与私有目录都有完整读写权（shell + run-as 都读不到外部目录，所以必须由应用自己来）。
 *
 * 变体名从 `<EXT>/variant.txt` 读（adb push 即可切换）。
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
const PRIV = "/data/data/" + PKG + "/files/models/ppocrv6";
const FILES = [
    "PP_OCRv6_tiny_det.ncnn.param",
    "PP_OCRv6_tiny_det.ncnn.bin",
    "PP_OCRv6_tiny_rec.ncnn.param",
    "PP_OCRv6_tiny_rec.ncnn.bin",
    "ppocr_keys_v6_tiny.txt"
];
/** 要看的格（覆盖真机难点：象 / 士 / 将 / 帅 / 马 / 炮） */
const WATCH = [[2, 0], [3, 0], [4, 0], [6, 0], [4, 9], [1, 9], [2, 3], [5, 0]];

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[ocrab2] " + text);
    }
}

/** 把 <EXT>/<variant>/ 下的 5 个文件装到私有目录；返回是否每个都成功 */
function install(variant) {
    const src = EXT + "/" + variant;
    const done = [];
    for (let i = 0; i < FILES.length; i++) {
        const name = FILES[i];
        let ok = false;
        try {
            if (typeof files.copy === "function") {
                ok = files.copy(src + "/" + name, PRIV + "/" + name) !== false;
            }
        } catch (error) {
            ok = false;
            log("copy " + name + " 失败：" + (error && error.message ? error.message : error));
        }
        done.push(name + (ok ? "✔" : "✘"));
    }
    return done.join(" ");
}

function main() {
    const startedAt = Date.now();
    let variant = "v6tiny";
    try {
        variant = String(files.read(EXT + "/variant.txt") || "v6tiny").trim() || "v6tiny";
    } catch (error) {
        log("读 variant.txt 失败：" + error);
    }
    log("目标变体：" + variant);
    log("装入结果：" + install(variant));

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
        session = $ocr.create({ backend: "ppocr", targetSize: 768 });
    } catch (error) {
        log("会话失败：" + (error && error.message ? error.message : error));
        return;
    }
    log("会话就绪（含装入/截图/定位 " + (Date.now() - startedAt) + "ms）");

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
            text = "!ERR";
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
