// @engine quickjs
/**
 * 棋子裁剪与预处理（重构方案 §3.4）。
 *
 * 两条硬约束（方案原文）：
 *   · **只裁 YOLO 确认有棋子的交点**（通常 ≤32 个），不要对 90 格全跑 OCR；
 *   · 裁块要包含完整棋子圆盘并留一点边缘：`cropSize = min(|stepX|,|stepY|) × 0.82`。
 *
 * 输出两份：
 *   · OCR 图：灰度 + 轻量对比度拉伸、去掉圆盘外圈、留中心约 70% 的文字区；
 *   · 颜色图：保留 RGB 的采样点（交给 piece-color.js 判红黑）。
 *
 * 纯几何部分（`cropBoxFor`/`centreRegion`）与平台无关，离线可测；
 * 只有 `cropAll` 需要宿主提供 clip/rgbAt。
 */
"use strict";

const DEFAULTS = {
    /** 裁块边长 = 格距 × 该比例（方案 §3.4） */
    cropRatio: 0.82,
    /** 文字只占圆盘中心这一比例（方案：中心约 70%） */
    centreRatio: 0.70,
    /** 颜色采样框比例（略小于裁块，避开边缘阴影） */
    colorRatio: 0.72,
    /** 颜色采样步长（像素） */
    colorStep: 3,
    /**
     * 裁剪是否以**检测框**为准（方案 §3.2：OCR 该认的就是 YOLO 框出来的那枚棋子）。
     * 2026-09-19 真机教训：只拿框决定「哪一格」、采样区却按格中心算，
     * 帅/将这种被九宫斜线穿过的格就会偏，OCR 只能读到 0.26~0.34 分的垃圾。
     */
    useDetectorBox: true,
    /** 检测框离交点超过这么多格距就不信它的中心（回到格中心） */
    maxBoxShift: 0.35,
    /** 检测框边长占格距的比例上下限（防框太大吞邻格、太小切掉笔画） */
    minDetectorSize: 0.5,
    maxDetectorSize: 0.95
};

/** 某一格的裁块（全图像素坐标） */
function cropBoxFor(grid, col, row, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    if (!grid) {
        return null;
    }
    const stepX = Math.abs(grid.stepX), stepY = Math.abs(grid.stepY);
    const size = Math.max(8, Math.round(Math.min(stepX, stepY) * (opts.cropRatio == null ? DEFAULTS.cropRatio : opts.cropRatio)));
    const flip = grid.orientation === "RED_TOP";
    const cx = grid.originX + (flip ? 8 - col : col) * grid.stepX;
    const cy = grid.originY + (flip ? 9 - row : row) * grid.stepY;
    return {
        x: Math.round(cx - size / 2),
        y: Math.round(cy - size / 2),
        width: size,
        height: size,
        centerX: Math.round(cx),
        centerY: Math.round(cy)
    };
}

/**
 * 某一格的裁块 —— **以检测框为准**（方案 §3.2：OCR 要认的就是 YOLO 框出来的那枚棋子）。
 * 检测框离交点太远（residual > maxBoxShift）时退回格中心：宁可用几何，也不信一个明显跑掉的框。
 */
function boxForItem(grid, item, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const base = cropBoxFor(grid, item.col, item.row, opts);
    const det = item && item.box;
    if (!base || !det || opts.useDetectorBox === false) {
        return base;
    }
    const step = Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY));
    const residual = item.residual == null ? 0 : Number(item.residual);
    if (!(residual <= opts.maxBoxShift)) {
        return base;
    }
    const cx = det.centerX != null ? Number(det.centerX)
        : (isFinite(det.x) && isFinite(det.width) ? Number(det.x) + Number(det.width) / 2 : NaN);
    const cy = det.centerY != null ? Number(det.centerY)
        : (isFinite(det.y) && isFinite(det.height) ? Number(det.y) + Number(det.height) / 2 : NaN);
    if (!isFinite(cx) || !isFinite(cy)) {
        return base;
    }
    const detSize = Math.min(Math.abs(Number(det.width) || 0), Math.abs(Number(det.height) || 0));
    let size = detSize > 0 ? detSize : step;
    size = Math.max(step * opts.minDetectorSize, Math.min(step * opts.maxDetectorSize, size)) * opts.cropRatio;
    size = Math.max(8, Math.round(size));
    return {
        x: Math.round(cx - size / 2),
        y: Math.round(cy - size / 2),
        width: size,
        height: size,
        centerX: Math.round(cx),
        centerY: Math.round(cy),
        fromDetector: true,
        residual: residual
    };
}

/** 把裁块收窄到中心比例（去圆盘外圈，只留文字区） */
function centreRegion(box, ratio) {
    if (!box) {
        return null;
    }
    const r = ratio == null ? DEFAULTS.centreRatio : ratio;
    const w = Math.max(4, Math.round(box.width * r));
    const h = Math.max(4, Math.round(box.height * r));
    return {
        x: Math.round(box.x + (box.width - w) / 2),
        y: Math.round(box.y + (box.height - h) / 2),
        width: w,
        height: h
    };
}

/**
 * 取一枚棋子的颜色采样点（RGB）。
 * @param host { rgbAt(image, x, y) → [r,g,b] }
 */
function sampleColors(host, image, box, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    if (!host || typeof host.rgbAt !== "function" || !image || !box) {
        return [];
    }
    const region = centreRegion(box, opts.colorRatio);
    const out = [];
    for (let y = region.y; y < region.y + region.height; y += opts.colorStep) {
        for (let x = region.x; x < region.x + region.width; x += opts.colorStep) {
            let px = null;
            try {
                px = host.rgbAt(image, x, y);
            } catch (error) {
                px = null;
            }
            if (px) {
                out.push(px);
            }
        }
    }
    return out;
}

/**
 * 只给「有棋子」的交点裁图（方案 §3.4：别对 90 格全跑）。
 *
 * @param host { clip(image, box), rgbAt(image, x, y) }
 * @param items [{ index, col, row }]  —— 由 detector/grid 映射给出
 * @return [{ index, box, ocrBox, image, colors }]（host 缺失能力时返回空数组）
 */
function cropAll(host, image, grid, items, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const out = [];
    if (!host || !image || !grid || !items || !items.length) {
        return out;
    }
    for (let i = 0; i < items.length; i++) {
        const item = items[i];
        if (!item || item.index == null) {
            continue;
        }
        const col = item.col == null ? (item.index % 9) : item.col;
        const row = item.row == null ? Math.floor(item.index / 9) : item.row;
        // 采样/裁剪一律以检测框为准（见 boxForItem）
        const box = boxForItem(grid, { index: item.index, col: col, row: row, box: item.box, residual: item.residual }, opts);
        if (!box) {
            continue;
        }
        let crop = null;
        if (typeof host.clip === "function") {
            try {
                crop = host.clip(image, box);
            } catch (error) {
                crop = null;
            }
        }
        out.push({
            index: item.index,
            col: col,
            row: row,
            box: box,
            ocrRegion: centreRegion(box, opts.centreRatio),
            image: crop,
            colors: sampleColors(host, image, box, opts)
        });
    }
    return out;
}

module.exports = {
    cropBoxFor: cropBoxFor,
    boxForItem: boxForItem,
    centreRegion: centreRegion,
    sampleColors: sampleColors,
    cropAll: cropAll,
    DEFAULTS: DEFAULTS
};
