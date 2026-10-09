// @engine quickjs
/**
 * OCR 批量探针：**整盘一次 detect** 能不能一次吐回所有字形（对比「逐格一次 detect」）。
 *
 * 为什么（2026-09-20）：全盘 OCR 已经用「空框格先找色」闸门从 2895ms 降到 1015ms，
 * 但剩下的 1s 全是「每格一次 `session.detect`」（≈34ms/格 × 30 格）。
 * PP-OCR 的检测器内部会把图缩到固定边长再跑 —— 如果**整盘一块图**跑一次就能拿回所有字形，
 * 那 30 次调用可以变成 1 次（探针要回答：耗时、召回、以及 bounds 是相对裁剪图还是整帧）。
 *
 * 顺带对比三条路（同一帧、同一几何）：
 *   A. 逐格 detect（现状，`board-scan.scan`）
 *   B. 整盘裁剪图一次 detect（`prepareRegion(clip)` → `detect(clip, {max:N})`）
 *   C. 整帧 + region 一次 detect（`detect(frame, {max:N, region:[x,y,w,h]})`）
 * 每条都打：耗时、返回块数、**能否映射到格子**（块心距格心 ≤ 半格）、与 A 的逐格结论对比。
 *
 * 用法：push 到工程目录后跑（脚本靠 `__dirname` 找模块）：
 *   am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity \
 *       --es path /sdcard/xiangqi-p4/proj/tools/ocr-batch-probe.js
 * 结果看 logcat（前缀 [ocrbatch]）。
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
const pieceOcr = load("src/vision/piece-ocr.js");
const inkColor = load("src/vision/ink-color.js");
const cropper = load("src/vision/piece-cropper.js");
const locateBoardModule = load("src/vision/locate-board.js");
const recognizeBoard = load("experiments/vision/recognize-board.js");

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[ocrbatch] " + text);
    }
}

/** 块心 → 格子下标（半格内才算命中，返回 null 表示落不到格上） */
function cellOfPoint(grid, x, y) {
    const col = Math.round((x - grid.originX) / grid.stepX);
    const row = Math.round((y - grid.originY) / grid.stepY);
    if (col < 0 || col > 8 || row < 0 || row > 9) {
        return null;
    }
    const dx = Math.abs(grid.originX + col * grid.stepX - x);
    const dy = Math.abs(grid.originY + row * grid.stepY - y);
    if (dx > Math.abs(grid.stepX) / 2 || dy > Math.abs(grid.stepY) / 2) {
        return null;
    }
    return { index: row * 9 + col, col: col, row: row, dx: dx, dy: dy };
}

/** 棋盘在屏幕上的包围盒（含外扩半格，保证边上的子不被切掉） */
function boardRegionOf(grid) {
    const margin = 0.6;
    const x0 = grid.originX - Math.abs(grid.stepX) * margin;
    const y0 = grid.originY - Math.abs(grid.stepY) * margin;
    const w = Math.abs(grid.stepX) * (8 + margin * 2);
    const h = Math.abs(grid.stepY) * (9 + margin * 2);
    return [Math.round(x0), Math.round(y0), Math.round(w), Math.round(h)];
}

/** 把一次 detect 的返回块映射成「格子 → 字形」表，并统计落空/撞格 */
function mapBlocks(grid, blocks, offsetX, offsetY, what) {
    const byCell = {};
    let unmapped = 0;
    let collisions = 0;
    for (let i = 0; i < blocks.length; i++) {
        const one = blocks[i];
        const b = one.bounds || one.box || null;
        const cx = b ? (Number(b.x == null ? b[0] : b.x) + Number(b.width == null ? b[2] : b.width) / 2) : null;
        const cy = b ? (Number(b.y == null ? b[1] : b.y) + Number(b.height == null ? b[3] : b.height) / 2) : null;
        if (cx == null || !isFinite(cx) || !isFinite(cy)) {
            unmapped += 1;
            continue;
        }
        const cell = cellOfPoint(grid, cx + offsetX, cy + offsetY);
        if (!cell) {
            unmapped += 1;
            if (blocks.length <= 8) {
                log("  " + what + " 落空：text=" + (one.text || one.label) + " 分数=" + one.score +
                    " 块心=(" + Math.round(cx + offsetX) + "," + Math.round(cy + offsetY) + ")");
            }
            continue;
        }
        if (byCell[cell.index]) {
            collisions += 1;
        }
        byCell[cell.index] = {
            text: String(one.text || one.label || ""),
            score: Number(one.score == null ? one.confidence : one.score) || 0,
            cls: pieceOcr.classOf(one.text || one.label),
            dx: cell.dx, dy: cell.dy
        };
    }
    return { byCell: byCell, unmapped: unmapped, collisions: collisions };
}

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
    const info = adapter.describeFrame(frame);
    log("截图成功 " + JSON.stringify(info));

    const located = locateBoardModule.locateBoardOnScreen({ frame: frame, timeoutMs: 2000 });
    if (!located || !located.ok || !located.grid) {
        log("定位失败：" + ((located && located.reason) || "?"));
        return;
    }
    const grid = located.grid;
    log("几何 origin=(" + grid.originX.toFixed(1) + "," + grid.originY.toFixed(1) + ") step=(" +
        grid.stepX.toFixed(1) + "," + grid.stepY.toFixed(1) + ") orientation=" + grid.orientation);

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
        releaseFrame: function (f) { return adapter.releaseFrame(f); }
    };

    // YOLO 框（给 board-scan 用，与面板 ocrBoard() 一致）
    let boxes = null;
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
                }
            }
        }
    } catch (error) {
        log("检测异常：" + error);
    }

    // ── A. 现状：逐格 detect（只扫有框 ∪ 有墨迹，与线上同一条路）
    const only = [];
    for (let i = 0; i < 90; i++) {
        if (boxes && boxes[i]) {
            only.push(i);
            continue;
        }
        const col = i % 9;
        const row = Math.floor(i / 9);
        const box = cropper.cropBoxFor(grid, col, row, {});
        const counts = box ? inkColor.countsFor(host, frame, box, {}) : null;
        const area = counts && counts.area ? counts.area : 0;
        if (counts && counts.source === "findColor" && area > 0 &&
            (counts.red + counts.black) / area >= 0.01) {
            only.push(i);
        }
    }
    const aStart = Date.now();
    const perCell = boardScan.scan(host, frame, grid, { boxes: boxes, only: only });
    const aMs = Date.now() - aStart;
    log("A 逐格 detect：" + aMs + "ms｜扫 " + only.length + " 格｜读到 " + perCell.hits +
        " 格有字｜灰度重试 " + perCell.grayRetries + " 次救回 " + perCell.grayHits + " 格");

    const region = boardRegionOf(grid);
    log("棋盘包围盒（外扩 0.6 格）：[" + region.join(",") + "]");

    // ── B. 整盘裁剪图一次 detect
    let bMs = -1;
    let bBlocks = [];
    let bMap = null;
    try {
        const clip = host.prepareRegion(frame, region, "clip");
        if (!clip) {
            log("B 跳过：prepareRegion 返回 null（越界？）");
        } else {
            const bStart = Date.now();
            bBlocks = session.detect(clip, { max: 60 }) || [];
            bMs = Date.now() - bStart;
            host.releaseFrame(clip);
            bMap = mapBlocks(grid, bBlocks, region[0], region[1], "B");
            log("B 整盘裁剪一次 detect：" + bMs + "ms｜返回 " + bBlocks.length + " 块｜映射到格 " +
                Object.keys(bMap.byCell).length + "｜落空 " + bMap.unmapped + "｜同格多块 " + bMap.collisions);
            const show = bBlocks.slice(0, 6).map(function (one) {
                const b = one.bounds || one.box || {};
                return (one.text || one.label) + "@" + Math.round(Number(b.x == null ? b[0] : b.x)) + "," +
                    Math.round(Number(b.y == null ? b[1] : b.y)) + "(" + (Number(one.score) || 0).toFixed(2) + ")";
            });
            log("  B 前几块：" + show.join("｜"));
        }
    } catch (error) {
        log("B 异常：" + error);
    }

    // ── C. 整帧 + region 一次 detect
    let cMs = -1;
    let cMap = null;
    try {
        const cStart = Date.now();
        const cBlocks = session.detect(frame, { max: 60, region: region }) || [];
        cMs = Date.now() - cStart;
        cMap = mapBlocks(grid, cBlocks, 0, 0, "C");
        log("C 整帧 + region 一次 detect：" + cMs + "ms｜返回 " + cBlocks.length + " 块｜映射到格 " +
            Object.keys(cMap.byCell).length + "｜落空 " + cMap.unmapped + "｜同格多块 " + cMap.collisions);
    } catch (error) {
        log("C 异常：" + error);
    }

    // ── 汇总：与 A 的逐格结论对比（只比兵种，忽略红黑：这里都没判色）
    function compare(name, map) {
        if (!map) {
            log(name + " 对比：跳过");
            return;
        }
        let agree = 0;
        let onlyBatch = 0;
        let onlyPerCell = 0;
        const notes = [];
        for (let i = 0; i < 90; i++) {
            const a = perCell.glyphs[i] ? pieceOcr.classOf(perCell.glyphs[i]) : null;
            const b = map.byCell[i] ? map.byCell[i].cls : null;
            const aCls = a ? a.cls : null;
            const bCls = b ? b.cls : null;
            if (!aCls && !bCls) {
                continue;
            }
            if (aCls && bCls) {
                if (aCls === bCls) {
                    agree += 1;
                } else if (notes.length < 6) {
                    notes.push(i + "(" + aCls + "≠" + bCls + ")");
                }
                continue;
            }
            if (bCls && !aCls) {
                onlyBatch += 1;
                if (notes.length < 6) {
                    notes.push(i + "(只有批量:" + bCls + ")");
                }
            } else {
                onlyPerCell += 1;
                if (notes.length < 6) {
                    notes.push(i + "(只有逐格:" + aCls + ")");
                }
            }
        }
        log(name + " 对比逐格：一致 " + agree + "｜只有批量 " + onlyBatch + "｜只有逐格 " + onlyPerCell +
            (notes.length ? "｜样本 " + notes.join("；") : ""));
    }
    compare("B", bMap);
    compare("C", cMap);

    log("小结：A 逐格 " + aMs + "ms（" + only.length + " 次 detect）｜B 整盘 " + bMs + "ms（1 次）｜C 整帧+region " +
        cMs + "ms（1 次）");

    if (session.release) {
        try {
            session.release();
        } catch (ignore) {
            // 释放失败不影响结论
        }
    }
}

main();
