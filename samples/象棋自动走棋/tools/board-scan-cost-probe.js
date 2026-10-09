// @engine quickjs
/**
 * 全盘扫描成本探针：把「全盘 OCR 一次 ≈2.8s」拆开，并验证「**空框格先找色、无墨迹就跳过 OCR**」可行。
 *
 * 为什么（2026-09-20 真机账）：驱动每 ≥6s 问一次全盘 OCR（`applyOcrBoardFixes`），
 * 实测一次 2.8~3.0s —— 而它其实只要「模型漏检/误读」那几格的证据。
 * `board-scan.scan` 现在对**90 格**每格都跑一次 `session.detect`（≈30ms）⇒ 2.7s 全花在这上面。
 * 但天天象棋的空格子里**没有墨迹**：如果先用引擎找色（native，快）确认「这格没墨迹」，
 * 就可以整格跳过 OCR —— 而有子却被漏检的格子一定有墨迹，所以不会漏掉真正的漏检。
 *
 * 本探针量化三件事（都在同一帧、同一几何上跑，可直接对比）：
 *   A. 现状：`scan` 全盘（90 格都 OCR）
 *   B. 找色预筛：90 格逐格 `countsFor` 的耗时与结论（有墨迹 / 无墨迹）
 *      · 假阴性（有 YOLO 框 ⇒ 肯定有子，却报「无墨迹」）——这是**不能接受**的错误
 *      · 假阳性（没框却报有墨迹）——只是多花一次 OCR，无害
 *   C. 收窄后：`scan` 只扫「有框 ∪ 有墨迹」的格子，与 A 的逐格结论对比（应完全一致）
 *
 * 用法：push 到手机的工程目录里再跑（脚本靠 `__dirname` 找模块）：
 *   adb push 工程 /sdcard/xiangqi-p4/proj
 *   am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity \
 *       --es path /sdcard/xiangqi-p4/proj/tools/board-scan-cost-probe.js
 * 结果看 logcat（前缀 [scancost]）。
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
const boardScan = load("src/vision/board-scan.js");
const pieceDetector = load("src/vision/piece-detector.js");
const cropper = load("src/vision/piece-cropper.js");
const inkColor = load("src/vision/ink-color.js");
const locateBoardModule = load("src/vision/locate-board.js");
const recognizeBoard = load("experiments/vision/recognize-board.js");

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[scancost] " + text);
    }
}

/** 「有墨迹」的门槛：真空格两边都 ≈0，真棋子红/黑密度 0.08~0.16 ⇒ 0.01 留了大余量 */
const INK_DENSITY = 0.01;

function main() {
    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    const frame = adapter.capture("full", 2000);
    if (!frame) {
        log("截图失败");
        return;
    }
    log("截图成功 " + (adapter.describeFrame ? adapter.describeFrame(frame) : ""));

    // 几何：与面板同一条路（先定位再校准）
    const located = locateBoardModule.locateBoardOnScreen({ frame: frame, timeoutMs: 2000 });
    if (!located || !located.ok || !located.grid) {
        log("定位失败：" + ((located && located.reason) || "?"));
        return;
    }
    const grid = located.grid;
    log("几何 origin=(" + grid.originX + "," + grid.originY + ") step=(" + grid.stepX + "," + grid.stepY +
        ") orientation=" + grid.orientation);

    if (typeof $ocr === "undefined" || typeof $ocr.create !== "function") {
        log("没有 $ocr，结束");
        return;
    }
    const session = $ocr.create({ backend: "ppocr" });
    if (!session) {
        log("OCR 会话建立失败");
        return;
    }
    const host = {
        session: session,
        rgbAt: function (image, x, y) { return adapter.rgbAt(image, x, y); },
        countColor: function (image, color, options) { return adapter.countColor(image, color, options); },
        prepareRegion: function (image, region, mode) { return adapter.prepareRegion(image, region, mode); },
        releaseFrame: function (frame2) { return adapter.releaseFrame(frame2); }
    };

    // YOLO 定位框（与面板 ocrBoard() 同一套）
    let boxes = null;
    const boxesByIndex = {};
    try {
        const detSession = recognizeBoard.createDetectionSession({ autoAlign: false });
        const detected = pieceDetector.detect({
            detect: function (img, detectOptions) {
                const out = detSession.detect(img, grid, detectOptions || {});
                return (out && out.ok) ? (out.raw || []) : [];
            }
        }, frame, { autoAlign: false });
        if (detected && detected.ok && detected.boxes && detected.boxes.length) {
            const mapped = pieceDetector.mapToCells(detected.boxes, grid, { autoAlign: false });
            boxes = {};
            for (let i = 0; i < mapped.cells.length; i++) {
                const one = mapped.cells[i];
                if (one && one.index != null) {
                    boxes[one.index] = { box: one.box, residual: one.residual };
                    boxesByIndex[one.index] = one.box;
                }
            }
            log("YOLO 框 " + detected.boxes.length + " 个，映射到格 " + Object.keys(boxes).length + " 个");
        }
    } catch (error) {
        log("检测异常：" + error);
        boxes = null;
    }

    // ── A. 现状：全盘 scan（90 格都 OCR）
    const aStart = Date.now();
    const full = boardScan.scan(host, frame, grid, { boxes: boxes });
    const fullMs = Date.now() - aStart;
    log("A 全盘 scan：" + fullMs + "ms｜读到 " + full.hits + " 格有字｜灰度重试 " + full.grayRetries +
        " 次救回 " + full.grayHits + " 格｜颜色未定 " + full.colourUnknown);
    if (!full.ok) {
        log("A 失败：" + full.reason);
        return;
    }

    // ── B. 找色预筛：90 格逐格数墨迹（native 找色），统计耗时与真假阳性/阴性
    const inkStart = Date.now();
    const inkList = [];
    const noInkList = [];
    let perCellMax = 0;
    let perCellMaxAt = -1;
    let falseNegative = 0;    // 有框（肯定有子）却报「无墨迹」
    let falsePositive = 0;    // 没框却报「有墨迹」
    const boxedCount = boxes ? Object.keys(boxes).length : 0;
    for (let i = 0; i < 90; i++) {
        const col = i % 9;
        const row = Math.floor(i / 9);
        const detBox = boxes && boxes[i] ? boxes[i].box : null;
        const box = detBox
            ? cropper.boxForItem(grid, {
                index: i, col: col, row: row, box: detBox,
                residual: (boxes[i].residual == null ? 0 : boxes[i].residual)
            }, {})
            : cropper.cropBoxFor(grid, col, row, {});
        if (!box) {
            continue;
        }
        const counts = inkColor.countsFor(host, frame, box, {});
        if (counts.ms > perCellMax) {
            perCellMax = counts.ms;
            perCellMaxAt = i;
        }
        const density = counts.area > 0 ? (counts.red + counts.black) / counts.area : 0;
        const hasInk = counts.source === "findColor" && density >= INK_DENSITY;
        if (hasInk) {
            inkList.push({ index: i, density: density, boxed: !!detBox });
            if (!detBox) {
                falsePositive += 1;
            }
        } else {
            noInkList.push(i);
            if (detBox) {
                falseNegative += 1;
                log("  ⚠️ 假阴性：格 " + i + " 有 YOLO 框却说无墨迹（红 " + counts.red + " 黑 " + counts.black +
                    " 面积 " + counts.area + " 密度 " + density.toFixed(4) + "）");
            }
        }
    }
    const inkMs = Date.now() - inkStart;
    const boxedNoInk = full.hits > 0 ? null : null;
    log("B 找色预筛：" + inkMs + "ms（90 格，平均 " + Math.round(inkMs / 90) + "ms/格，最慢 " + perCellMax +
        "ms@格" + perCellMaxAt + "）｜有墨迹 " + inkList.length + " 格（其中无框 " + falsePositive +
        "）｜无墨迹 " + noInkList.length + " 格｜**假阴性 " + falseNegative + "**（有框却报无墨迹）｜" +
        "有框 " + boxedCount + " 格");

    // ── C. 收窄后：只扫「有框 ∪ 有墨迹」
    const only = [];
    for (let i = 0; i < 90; i++) {
        const boxed = !!(boxes && boxes[i]);
        const inked = inkList.some(function (one) { return one.index === i; });
        if (boxed || inked) {
            only.push(i);
        }
    }
    const cStart = Date.now();
    const narrowed = boardScan.scan(host, frame, grid, { boxes: boxes, only: only });
    const narrowedMs = Date.now() - cStart;
    let mismatch = 0;
    const mismatchNotes = [];
    for (let k = 0; k < only.length; k++) {
        const i = only[k];
        if (String(full.chars[i] || "") !== String(narrowed.chars[i] || "")) {
            mismatch += 1;
            if (mismatchNotes.length < 5) {
                mismatchNotes.push(i + "：" + (full.chars[i] || "空") + "→" + (narrowed.chars[i] || "空"));
            }
        }
    }
    log("C 收窄 scan：" + narrowedMs + "ms｜扫 " + only.length + " 格｜读到 " + narrowed.hits +
        " 格有字｜与 A 不一致 " + mismatch + (mismatchNotes.length ? "：" + mismatchNotes.join("；") : ""));
    log("D 小结：现状 " + fullMs + "ms → 收窄 " + narrowedMs + "ms（省 " +
        Math.round((1 - narrowedMs / Math.max(1, fullMs)) * 100) + "%），找色预筛另花 " + inkMs + "ms；" +
        "净变化 " + (narrowedMs + inkMs - fullMs) + "ms");

    if (session.release) {
        try {
            session.release();
        } catch (ignore) {
            // 释放失败不影响结论
        }
    }
}

main();
