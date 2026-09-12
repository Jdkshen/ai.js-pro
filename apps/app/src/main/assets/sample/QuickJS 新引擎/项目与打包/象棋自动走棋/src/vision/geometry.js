// @engine quickjs
/**
 * 棋盘几何：规范交点 ↔ 屏幕逻辑像素。
 *
 * 中国象棋的棋子落在**交点**上，共 9 列 × 10 行 = 90 个交点（不是格子中心）。
 * 规范坐标 (col 0..8, row 0..9) 固定为「红方在下」的 FEN 图面；屏幕方向只影响映射，
 * 不影响规范坐标 —— 换方向不会让局面定义漂移。
 */
"use strict";

const ROWS = 10;
const COLS = 9;

const ORIENTATION_RED_BOTTOM = "RED_BOTTOM";
const ORIENTATION_RED_TOP = "RED_TOP";

function median(values) {
    if (values.length === 0) {
        return NaN;
    }
    const sorted = values.slice().sort(function (a, b) { return a - b; });
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

function clampOrZero(value) {
    return isFinite(value) ? value : 0;
}

/**
 * 建立几何模型。
 * @param options.originX/originY 规范 (col=0,row=0) 那个交点在屏幕逻辑像素里的位置
 * @param options.stepX/stepY     相邻交点的间距（带符号：负数表示该轴向反）
 * @param options.orientation     屏幕方向，默认红方在下
 * @param options.version         几何版本，变了就要让旧缓存/旧票据失效
 */
function createGrid(options) {
    const opts = options || {};
    const orientation = opts.orientation === ORIENTATION_RED_TOP ? ORIENTATION_RED_TOP : ORIENTATION_RED_BOTTOM;
    return {
        originX: Number(opts.originX) || 0,
        originY: Number(opts.originY) || 0,
        stepX: Number(opts.stepX) || 0,
        stepY: Number(opts.stepY) || 0,
        orientation: orientation,
        version: opts.version == null ? 1 : opts.version,
        /** 拟合质量：内点比例、最大/平均残差（格距的倍数） */
        quality: opts.quality || null
    };
}

/** 规范坐标 → 屏幕坐标（含方向映射）。 */
function pointAt(grid, col, row) {
    const screenCol = grid.orientation === ORIENTATION_RED_TOP ? COLS - 1 - col : col;
    const screenRow = grid.orientation === ORIENTATION_RED_TOP ? ROWS - 1 - row : row;
    return {
        x: grid.originX + screenCol * grid.stepX,
        y: grid.originY + screenRow * grid.stepY
    };
}

/** 屏幕坐标 → 最近的规范交点。residual 为到该交点的像素距离，normalized 为除以格距。 */
function nearest(grid, x, y) {
    const step = Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY));
    if (!(step > 0)) {
        return { col: -1, row: -1, residual: Infinity, normalized: Infinity, inside: false };
    }
    const screenCol = (x - grid.originX) / grid.stepX;
    const screenRow = (y - grid.originY) / grid.stepY;
    const snappedCol = Math.round(screenCol);
    const snappedRow = Math.round(screenRow);
    const inside = snappedCol >= 0 && snappedCol < COLS && snappedRow >= 0 && snappedRow < ROWS;
    const col = grid.orientation === ORIENTATION_RED_TOP ? COLS - 1 - snappedCol : snappedCol;
    const row = grid.orientation === ORIENTATION_RED_TOP ? ROWS - 1 - snappedRow : snappedRow;
    const point = pointAt(grid, inside ? col : 0, inside ? row : 0);
    const dx = x - point.x;
    const dy = y - point.y;
    const residual = Math.sqrt(dx * dx + dy * dy);
    return {
        col: col,
        row: row,
        residual: residual,
        normalized: residual / step,
        inside: inside
    };
}

/** 归一化残差：离最近交点超过 1 格距的一半就说明这个中心不可靠。 */
function normalizedResidual(grid, x, y) {
    return nearest(grid, x, y).normalized;
}

/**
 * 用可靠样本（已知 col/row 的屏幕坐标）稳健拟合原点与格距。
 * @param samples [{col, row, x, y}]
 * @param options.mode "canonical"（样本已是规范坐标）或 "screen"（样本是屏幕图面坐标，需按方向换算）
 * @returns grid（含 quality）或 null（样本不足/退化）
 */
function fit(samples, options) {
    const opts = options || {};
    const orientation = opts.orientation === ORIENTATION_RED_TOP ? ORIENTATION_RED_TOP : ORIENTATION_RED_BOTTOM;
    const version = opts.version == null ? 1 : opts.version;
    const usable = [];
    for (let i = 0; i < (samples ? samples.length : 0); i++) {
        const s = samples[i];
        if (!s || !isFinite(s.x) || !isFinite(s.y)) {
            continue;
        }
        const screenCol = orientation === ORIENTATION_RED_TOP ? COLS - 1 - s.col : s.col;
        const screenRow = orientation === ORIENTATION_RED_TOP ? ROWS - 1 - s.row : s.row;
        usable.push({ screenCol: screenCol, screenRow: screenRow, x: s.x, y: s.y });
    }
    if (usable.length < 2) {
        return null;
    }
    let grid = fitOnce(usable, orientation, version);
    if (!grid) {
        return null;
    }
    // 稳健化：先按残差剔一遍离群点再拟合一次（单个错误中心不该拖动整张网格）。
    const kept = usable.filter(function (s) {
        return normalizedResidual(grid, s.x, s.y) <= 0.5;
    });
    if (kept.length >= 2 && kept.length < usable.length) {
        const refit = fitOnce(kept, orientation, version);
        if (refit) {
            grid = refit;
            grid.quality.sampleCount = usable.length;
            grid.quality.inlierCount = kept.length;
        }
    }
    return grid;
}

function fitOnce(usable, orientation, version) {
    const stepXValues = [];
    const stepYValues = [];
    for (let i = 0; i < usable.length; i++) {
        for (let j = i + 1; j < usable.length; j++) {
            const a = usable[i];
            const b = usable[j];
            if (a.screenRow === b.screenRow && a.screenCol !== b.screenCol) {
                stepXValues.push((b.x - a.x) / (b.screenCol - a.screenCol));
            }
            if (a.screenCol === b.screenCol && a.screenRow !== b.screenRow) {
                stepYValues.push((b.y - a.y) / (b.screenRow - a.screenRow));
            }
        }
    }
    const stepX = median(stepXValues);
    const stepY = median(stepYValues);
    if (!isFinite(stepX) || !isFinite(stepY) || Math.abs(stepX) < 1 || Math.abs(stepY) < 1) {
        return null;
    }
    const originXValues = usable.map(function (s) { return s.x - s.screenCol * stepX; });
    const originYValues = usable.map(function (s) { return s.y - s.screenRow * stepY; });
    const grid = createGrid({
        originX: clampOrZero(median(originXValues)),
        originY: clampOrZero(median(originYValues)),
        stepX: stepX,
        stepY: stepY,
        orientation: orientation,
        version: version
    });
    let maxResidual = 0;
    let sumResidual = 0;
    for (let i = 0; i < usable.length; i++) {
        const r = normalizedResidual(grid, usable[i].x, usable[i].y);
        maxResidual = Math.max(maxResidual, r);
        sumResidual += r;
    }
    grid.quality = {
        sampleCount: usable.length,
        inlierCount: usable.length,
        maxResidual: maxResidual,
        meanResidual: sumResidual / usable.length
    };
    return grid;
}

/** 几何是否可用于出手判定：样本够、残差小。 */
function isTrustworthy(grid, options) {
    const opts = options || {};
    const maxResidual = opts.maxResidual == null ? 0.35 : opts.maxResidual;
    const minSamples = opts.minSamples == null ? 4 : opts.minSamples;
    if (!grid || !grid.quality) {
        return false;
    }
    return grid.quality.inlierCount >= minSamples && grid.quality.maxResidual <= maxResidual;
}

const XQ_GEOMETRY = {
    ROWS: ROWS,
    COLS: COLS,
    ORIENTATION_RED_BOTTOM: ORIENTATION_RED_BOTTOM,
    ORIENTATION_RED_TOP: ORIENTATION_RED_TOP,
    median: median,
    createGrid: createGrid,
    pointAt: pointAt,
    nearest: nearest,
    normalizedResidual: normalizedResidual,
    fit: fit,
    isTrustworthy: isTrustworthy
};

module.exports = XQ_GEOMETRY;
