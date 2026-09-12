// @engine quickjs
/**
 * 棋盘定位：先自动（亮度投影找格线），失败就明确要求手动校准 —— 不猜。
 *
 * 两点如实说明：
 * 1) 自动定位目前**没有在真实棋盘上验证过**（工程里还没有模型/样本），只保证算法与接口正确；
 * 2) 手动校准（给 4 个已知交点）是可靠路径，任何时候都能覆盖自动结果。
 *
 * 坐标：规范 (col 0..8, row 0..9)，红方在下；屏幕方向由 geometry.orientation 决定。
 */
"use strict";

const geometry = require("./geometry.js");

const COLS = 9;
const ROWS = 10;

/** 由 (位置, 亮度) 采样序列构造投影；亮度取值 0（黑）..1（白）。 */
function profileFromSamples(samples) {
    const positions = [];
    const values = [];
    for (let i = 0; i < (samples ? samples.length : 0); i++) {
        const item = samples[i];
        if (!item || !isFinite(item.pos) || !isFinite(item.value)) {
            continue;
        }
        positions.push(item.pos);
        values.push(item.value);
    }
    return { positions: positions, values: values };
}

function medianOf(values) {
    if (!values || values.length === 0) {
        return NaN;
    }
    const sorted = values.slice().sort(function (a, b) { return a - b; });
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

/**
 * 从亮度投影里找「暗线」：低于中位亮度一定比例的连续段各算一条线，取其中点。
 * @param options.darkRatio 暗线阈值 = 中位亮度 × darkRatio（默认 0.72）
 * @param options.minGap    两条线至少相隔多少像素（默认 4），避免把一条粗线拆成两条
 * @returns { lines, spacing, uniformity, reason }  uniformity = 1 - 间距变异系数
 */
function detectLines(profile, options) {
    const opts = options || {};
    const darkRatio = opts.darkRatio == null ? 0.72 : opts.darkRatio;
    const minGap = opts.minGap == null ? 4 : opts.minGap;
    const positions = profile.positions;
    const values = profile.values;
    if (positions.length < 8) {
        return { lines: [], spacing: 0, uniformity: 0, reason: "采样点太少" };
    }
    const median = medianOf(values);
    if (!isFinite(median) || median <= 0) {
        return { lines: [], spacing: 0, uniformity: 0, reason: "画面亮度异常" };
    }
    const threshold = median * darkRatio;
    const lines = [];
    let runStart = -1;
    for (let i = 0; i < values.length; i++) {
        const dark = values[i] <= threshold;
        if (dark && runStart < 0) {
            runStart = i;
        }
        if ((!dark || i === values.length - 1) && runStart >= 0) {
            const runEnd = dark ? i : i - 1;
            let sum = 0;
            for (let k = runStart; k <= runEnd; k++) {
                sum += positions[k];
            }
            const center = sum / (runEnd - runStart + 1);
            if (lines.length === 0 || center - lines[lines.length - 1] >= minGap) {
                lines.push(center);
            }
            runStart = -1;
        }
    }
    if (lines.length < 2) {
        return { lines: lines, spacing: 0, uniformity: 0, reason: "只找到 " + lines.length + " 条线" };
    }
    const gaps = [];
    for (let i = 1; i < lines.length; i++) {
        gaps.push(lines[i] - lines[i - 1]);
    }
    const spacing = medianOf(gaps);
    let variance = 0;
    for (let i = 0; i < gaps.length; i++) {
        const d = gaps[i] - spacing;
        variance += d * d;
    }
    variance = Math.sqrt(variance / gaps.length);
    const uniformity = spacing > 0 ? 1 - variance / spacing : 0;
    return { lines: lines, spacing: spacing, uniformity: uniformity, reason: "" };
}

/**
 * 由 9 条竖线、10 条横线建立几何。
 * @returns { grid, reason }
 */
function gridFromLines(vertical, horizontal, options) {
    const opts = options || {};
    const tolerance = opts.tolerance == null ? 1 : opts.tolerance;
    if (!vertical || !horizontal) {
        return { grid: null, reason: "缺少投影结果" };
    }
    if (Math.abs(vertical.lines.length - COLS) > tolerance) {
        return { grid: null, reason: "竖线 " + vertical.lines.length + " 条（需要 " + COLS + " 条）" };
    }
    if (Math.abs(horizontal.lines.length - ROWS) > tolerance) {
        return { grid: null, reason: "横线 " + horizontal.lines.length + " 条（需要 " + ROWS + " 条）" };
    }
    if (vertical.uniformity < 0.9 || horizontal.uniformity < 0.9) {
        return {
            grid: null,
            reason: "线间距不均匀（竖 " + vertical.uniformity.toFixed(2) + " / 横 " + horizontal.uniformity.toFixed(2) + "）"
        };
    }
    const firstV = vertical.lines[0];
    const lastV = vertical.lines[vertical.lines.length - 1];
    const firstH = horizontal.lines[0];
    const lastH = horizontal.lines[horizontal.lines.length - 1];
    const stepX = (lastV - firstV) / (COLS - 1);
    const stepY = (lastH - firstH) / (ROWS - 1);
    if (Math.abs(stepX - stepY) > Math.max(stepX, stepY) * 0.25) {
        // 象棋格是方形的：横纵格距差太多说明找错了线
        return { grid: null, reason: "格距不是方形（" + stepX.toFixed(1) + " vs " + stepY.toFixed(1) + "）" };
    }
    const grid = geometry.createGrid({
        originX: firstV,
        originY: firstH,
        stepX: stepX,
        stepY: stepY,
        orientation: opts.orientation,
        version: opts.version == null ? 1 : opts.version
    });
    grid.quality = {
        sampleCount: vertical.lines.length + horizontal.lines.length,
        inlierCount: vertical.lines.length + horizontal.lines.length,
        maxResidual: Math.max(1 - vertical.uniformity, 1 - horizontal.uniformity) * 0.5,
        meanResidual: Math.max(1 - vertical.uniformity, 1 - horizontal.uniformity) * 0.25
    };
    return { grid: grid, reason: "" };
}

/** 沿某条横线采样亮度：同一 x 取多个 y 的均值，减少棋子/文字干扰。 */
function sampleRow(sampler, y, x0, x1, step) {
    const samples = [];
    for (let x = x0; x <= x1; x += step) {
        let sum = 0;
        let count = 0;
        const offsets = [-2, 0, 2];
        for (let i = 0; i < offsets.length; i++) {
            const value = sampler(x, y + offsets[i]);
            if (isFinite(value)) {
                sum += value;
                count++;
            }
        }
        if (count > 0) {
            samples.push({ pos: x, value: sum / count });
        }
    }
    return profileFromSamples(samples);
}

/** 沿某条竖线采样亮度（用于找横线）。 */
function sampleColumn(sampler, x, y0, y1, step) {
    const samples = [];
    for (let y = y0; y <= y1; y += step) {
        let sum = 0;
        let count = 0;
        const offsets = [-2, 0, 2];
        for (let i = 0; i < offsets.length; i++) {
            const value = sampler(x + offsets[i], y);
            if (isFinite(value)) {
                sum += value;
                count++;
            }
        }
        if (count > 0) {
            samples.push({ pos: y, value: sum / count });
        }
    }
    return profileFromSamples(samples);
}

/**
 * 自动定位：在给定区域内找格线。
 * @param sampler (x, y) → 亮度 0..1（由适配层提供，内部用 images.pixel）
 * @param options.region {x, y, width, height} 采样区域（默认整帧）
 * @returns { ok, grid, method, reason }
 */
function locateByLines(sampler, options) {
    const opts = options || {};
    const region = opts.region || { x: 0, y: 0, width: opts.width || 1080, height: opts.height || 1920 };
    const step = opts.sampleStep == null ? 2 : opts.sampleStep;
    const x0 = region.x + (opts.margin == null ? 8 : opts.margin);
    const x1 = region.x + region.width - (opts.margin == null ? 8 : opts.margin);
    const y0 = region.y + (opts.margin == null ? 8 : opts.margin);
    const y1 = region.y + region.height - (opts.margin == null ? 8 : opts.margin);

    // 多行/多列采样后取「中位数投影」：格线是稳定的暗线，棋子只影响个别行。
    const rowsToScan = opts.scanRows || [0.5, 1.5, 2.5, 3.5, 6.5, 7.5, 8.5];
    const vertical = medianProfiles(rowsToScan.map(function (ratio) {
        const y = y0 + (y1 - y0) * ratio / 9;
        return sampleRow(sampler, Math.round(y), x0, x1, step);
    }));

    const colsToScan = opts.scanCols || [0.5, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5];
    const horizontal = medianProfiles(colsToScan.map(function (ratio) {
        const x = x0 + (x1 - x0) * ratio / 8;
        return sampleColumn(sampler, Math.round(x), y0, y1, step);
    }));

    const verticalLines = detectLines(vertical, opts);
    const horizontalLines = detectLines(horizontal, opts);
    const built = gridFromLines(verticalLines, horizontalLines, opts);
    if (!built.grid) {
        return {
            ok: false,
            grid: null,
            method: "lines",
            reason: "自动定位失败：" + (built.reason || verticalLines.reason || horizontalLines.reason),
            verticalLines: verticalLines.lines.length,
            horizontalLines: horizontalLines.lines.length
        };
    }
    return { ok: true, grid: built.grid, method: "lines", reason: "投影找到格线" };
}

/** 多个投影取逐点中位数（位置对齐时用）。 */
function medianProfiles(profiles) {
    const usable = profiles.filter(function (p) { return p && p.positions.length > 0; });
    if (usable.length === 0) {
        return profileFromSamples([]);
    }
    const length = Math.min.apply(null, usable.map(function (p) { return p.values.length; }));
    const positions = [];
    const values = [];
    for (let i = 0; i < length; i++) {
        positions.push(usable[0].positions[i]);
        const column = usable.map(function (p) { return p.values[i]; });
        values.push(medianOf(column));
    }
    return profileFromSamples(positions.map(function (pos, index) {
        return { pos: pos, value: values[index] };
    }));
}

/**
 * 手动校准：给若干「已知规范坐标」的交点（4 个角或更多），稳健拟合出几何。
 * @param corners [{col, row, x, y}]
 */
function locateByCorners(corners, options) {
    const opts = options || {};
    const grid = geometry.fit(corners, { version: opts.version, orientation: opts.orientation });
    if (!grid) {
        return { ok: false, grid: null, method: "corners", reason: "校准点不足（至少两个不同列/行）" };
    }
    if (!geometry.isTrustworthy(grid, opts)) {
        return {
            ok: false,
            grid: grid,
            method: "corners",
            reason: "校准残差过大（最大 " + grid.quality.maxResidual.toFixed(2) + " 格距），请重新点选交点"
        };
    }
    return { ok: true, grid: grid, method: "corners", reason: "手动校准" };
}

/**
 * 组合入口：有手动校准点就用手动（更可靠），否则试自动。
 * @returns { ok, grid, method, reason }
 */
function locate(options) {
    const opts = options || {};
    if (opts.corners && opts.corners.length >= 2) {
        return locateByCorners(opts.corners, opts);
    }
    if (typeof opts.sampler === "function") {
        return locateByLines(opts.sampler, opts);
    }
    return { ok: false, grid: null, method: "none", reason: "既没有校准点也没有图像采样接口" };
}

const XQ_BOARD_LOCATOR = {
    COLS: COLS,
    ROWS: ROWS,
    profileFromSamples: profileFromSamples,
    medianOf: medianOf,
    detectLines: detectLines,
    gridFromLines: gridFromLines,
    sampleRow: sampleRow,
    sampleColumn: sampleColumn,
    locateByLines: locateByLines,
    locateByCorners: locateByCorners,
    locate: locate
};

module.exports = XQ_BOARD_LOCATOR;
