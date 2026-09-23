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

// ---------------------------------------------------------------- 支撑度路径（真实照片用）

/**
 * 「整条线支撑度」：格线在**整段长度**上始终比左右（上下）邻居暗，棋子边缘/文字做不到。
 * 这是本文件的主路径 —— 真实照片上比"绝对暗线阈值"稳得多（后者会把木纹、阴影、UI 文字都算成线）。
 *
 * @param getValue (x, y) → 亮度 0..1
 * @param options.axis         "x" 找竖线（沿 y 采样），"y" 找横线
 * @param options.from/to      候选位置的区间（要找线的那一轴）
 * @param options.step         候选位置的步长
 * @param options.acrossFrom/acrossTo/acrossStep 沿另一轴的采样范围与步长
 * @param options.gap          与邻居比较的间距（像素）
 * @param options.margin       要暗多少才算「比邻居暗」
 * @returns { positions, support }
 */
/**
 * 把「按参考格距算出来的像素量」夹到整数且不越界。
 * 扫描参数不能写死像素：同一条参数在 720×1600（格距 ≈77px）和 1280×2772（格距 ≈137px）
 * 上必须自动缩放，否则小屏会挑错格点阵（真机踩过：720p 直接定位失败）。
 */
function clampInt(value, min, max) {
    const rounded = Math.round(value);
    if (!isFinite(rounded)) return min;
    return Math.max(min, Math.min(max, rounded));
}

function scanSupport(getValue, options) {
    const opts = options || {};
    const axis = opts.axis === "y" ? "y" : "x";
    const step = opts.step == null ? 2 : opts.step;
    const gap = opts.gap == null ? 6 : opts.gap;
    const margin = opts.margin == null ? 0.03 : opts.margin;
    const acrossStep = opts.acrossStep == null ? 8 : opts.acrossStep;
    const positions = [];
    const support = [];
    for (let pos = opts.from; pos <= opts.to; pos += step) {
        let hits = 0;
        let total = 0;
        for (let across = opts.acrossFrom; across <= opts.acrossTo; across += acrossStep) {
            const x = axis === "x" ? pos : across;
            const y = axis === "x" ? across : pos;
            const value = getValue(x, y);
            if (!isFinite(value)) {
                continue;
            }
            const left = getValue(axis === "x" ? pos - gap : across - gap, axis === "x" ? across : pos - gap);
            const right = getValue(axis === "x" ? pos + gap : across + gap, axis === "x" ? across : pos + gap);
            total++;
            if (isFinite(left) && isFinite(right) && value + margin < left && value + margin < right) {
                hits++;
            }
        }
        positions.push(pos);
        support.push(total > 0 ? hits / total : 0);
    }
    return { positions: positions, support: support };
}

/**
 * 由支撑度曲线拟合「等距格点阵」：先估步长，再从最强的一条线向两侧延伸，
 * 允许 1～2 条线被棋子挡住（真实棋盘上很常见）。
 *
 * @param scan scanSupport 的返回值
 * @param options.expected    这一轴应有的线条数（竖 9 / 横 10）
 * @param options.minSupport  单条线的支撑度门槛（默认 0.45）
 * @param options.tolerance   归入格点的位置容差（步长的比例，默认 0.35）
 * @param options.minMatched  至少命中几条才算数（默认 expected-2）
 * @returns { ok, start, step, matched, expected, quality, reason, centers }
 */
function latticeFromSupport(scan, options) {
    const opts = options || {};
    const expected = opts.expected == null ? 9 : opts.expected;
    const minSupport = opts.minSupport == null ? 0.45 : opts.minSupport;
    const tolerance = opts.tolerance == null ? 0.35 : opts.tolerance;
    const minMatched = opts.minMatched == null ? Math.max(2, expected - 2) : opts.minMatched;
    // —— 步长先验 ——
    // rangeFrom/rangeTo 是「棋盘大致范围」，expected 条线在其间近似均匀分布 → 先验步长。
    // 小屏（如 720×1600，格距 ≈77px）上格线更细、支撑度更贴近阈值，支撑度曲线里会冒出
    // 一批「半格」位置的伪线，把步长估成真值的一半（真机表现：命中 12/10 条 → 序号对不上 → 定位失败）。
    // 这里只用先验处理「整数倍歧义」（估成 1/2、1/3 或 2 倍），其余情况一律不动。
    const rangeFrom = opts.rangeFrom;
    const rangeTo = opts.rangeTo;
    const hasRange = isFinite(rangeFrom) && isFinite(rangeTo) && rangeTo > rangeFrom;
    const priorStep = hasRange && expected > 1 ? (rangeTo - rangeFrom) / (expected - 1) : null;
    if (!scan || scan.positions.length < 3) {
        return { ok: false, reason: "支撑度采样点太少", centers: [] };
    }
    const strong = [];
    for (let i = 0; i < scan.support.length; i++) {
        if (scan.support[i] >= minSupport) {
            strong.push(scan.positions[i]);
        }
    }
    if (strong.length < 2) {
        return { ok: false, reason: "没有明显格线（支撑度 ≥ " + minSupport + " 的点不足 2 个）", centers: [] };
    }
    // 相邻合并成线中心（合并阈值同样跟格距走：小屏要收紧，但不超过参考值 6）
    const clusterGap = options && options.clusterGap != null
        ? options.clusterGap
        : (priorStep && priorStep > 8 ? clampInt(priorStep * 0.05, 3, 6) : 6);
    const centers = [];
    let run = [strong[0]];
    for (let i = 1; i < strong.length; i++) {
        if (strong[i] - run[run.length - 1] <= clusterGap) {
            run.push(strong[i]);
        } else {
            centers.push(run.reduce(function (a, b) { return a + b; }, 0) / run.length);
            run = [strong[i]];
        }
    }
    centers.push(run.reduce(function (a, b) { return a + b; }, 0) / run.length);
    if (centers.length < 2) {
        return { ok: false, reason: "只找到 1 条线", centers: centers };
    }
    const gaps = [];
    for (let i = 1; i < centers.length; i++) {
        gaps.push(centers[i] - centers[i - 1]);
    }
    // 步长估计：先用「间距中位数」当粗略步长（大多数相邻线就是单步），
    // 再对每个间距按倍数精修 —— 这样能容忍 ①被棋子挡住的 2 倍间距
    // ②棋盘外框那种「双线」造成的 10~20px 小间距。
    const roughStep = medianOf(gaps);
    if (!(roughStep > 4)) {
        return { ok: false, reason: "步长估计失败（间距中位数 " + roughStep + "）", centers: centers };
    }
    const refined = [];
    for (let i = 0; i < gaps.length; i++) {
        const multiple = Math.round(gaps[i] / roughStep);
        if (multiple >= 1) {
            refined.push(gaps[i] / multiple);
        }
    }
    let step = refined.length > 0 ? medianOf(refined) : roughStep;
    if (!(step > 4)) {
        return { ok: false, reason: "步长估计失败", centers: centers };
    }
    // 用「棋盘范围先验」纠正整数倍歧义（小屏最常见的错法是估成真值的一半）。
    // 先验本身允许偏大 5~15%（范围通常略大于棋盘），所以只在接近 2/3/4 倍时动手。
    let stepNote = "";
    if (priorStep && priorStep > 8) {
        const tooSmall = priorStep / step; // >1 表示估小了
        const tooLarge = step / priorStep; // >1 表示估大了
        const down = Math.round(tooSmall);
        const up = Math.round(tooLarge);
        if (down >= 2 && down <= 4 && Math.abs(tooSmall - down) <= 0.35) {
            stepNote = "步长按棋盘范围先验 ×" + down + " 修正（" + step.toFixed(1) + " → " + (step * down).toFixed(1) + "）";
            step *= down;
        } else if (up >= 2 && up <= 4 && Math.abs(tooLarge - up) <= 0.35) {
            stepNote = "步长按棋盘范围先验 ÷" + up + " 修正（" + step.toFixed(1) + " → " + (step / up).toFixed(1) + "）";
            step /= up;
        }
    }
    // 找支撑度最强的那条线当锚点
    let anchor = centers[0];
    let anchorScore = -1;
    for (let i = 0; i < centers.length; i++) {
        const value = supportAt(scan, centers[i]);
        if (value > anchorScore) {
            anchorScore = value;
            anchor = centers[i];
        }
    }
    // 沿格点阵延伸，统计命中
    const matched = [];
    const used = {};
    for (let k = -expected; k <= expected; k++) {
        const target = anchor + k * step;
        if (target < scan.positions[0] - step || target > scan.positions[scan.positions.length - 1] + step) {
            continue;
        }
        const hit = nearestCenter(centers, target, step * tolerance, used);
        if (hit !== null) {
            matched.push({ index: k, position: hit });
            used[hit] = true;
        }
    }
    if (matched.length < minMatched) {
        return {
            ok: false,
            reason: "只匹配到 " + matched.length + " 条线（需要 " + minMatched + " 条），可能是棋盘没找全或步长估错",
            centers: centers,
            step: step
        };
    }
    // 最小二乘修正起始位置与步长
    const ks = matched.map(function (item) { return item.index; });
    const ps = matched.map(function (item) { return item.position; });
    const kMean = ks.reduce(function (a, b) { return a + b; }, 0) / ks.length;
    const pMean = ps.reduce(function (a, b) { return a + b; }, 0) / ps.length;
    let num = 0;
    let den = 0;
    for (let i = 0; i < ks.length; i++) {
        num += (ks[i] - kMean) * (ps[i] - pMean);
        den += (ks[i] - kMean) * (ks[i] - kMean);
    }
    const fittedStep = den > 0 ? num / den : step;
    const startAtZero = pMean - fittedStep * kMean; // 锚点那条线的位置（k=0）
    const kMin = Math.min.apply(null, ks);
    const firstMatched = startAtZero + fittedStep * kMin;

    // —— 序号锚定（关键）——
    // 棋盘最外圈那条线经常被裁掉或被棋子挡住。若直接把「第一条命中的线」当成第 0 条，
    // 整盘就会错一整行/一整列（残局判断全错）。
    // 旧做法只用「棋盘范围」推序号：范围不准就报失败，或干脆跳过推理 —— 真机上都踩过：
    // ① 9/9 命中、步长也对，却报「对不齐」；② 序号交给 kMin，起点整体偏 3 格（1080×2248 上
    // 起点从 80.9 变成 425.4，棋盘直接跑到屏幕外）。
    // 现在改成**用扫描曲线核对**：把候选序号逐一试过，看哪一套格点阵真的落在格线上
    // （落在范围外/非格线位置的预测拿不到支撑度），取分数最高的；分数太低则不接受。
    const minLineScore = opts.minLineScore == null ? 0.2 : opts.minLineScore;
    const slack = fittedStep * 0.6;
    const allMatched = matched.length === expected;
    // 候选窗口要够宽：真正确定序号的信息是「第 0 条线靠近范围起点」与「格点落在格线上」，
    // 不是 firstMatched 那一条线是第几条 —— 窗口太窄会让正确答案根本不进候选。
    const slots = [];
    for (let k = kMin - expected; k <= kMin + expected; k++) {
        slots.push(k);
    }
    if (isFinite(opts.originHint)) {
        const hinted = Math.round((firstMatched - opts.originHint) / fittedStep);
        if (slots.indexOf(hinted) < 0) {
            slots.push(hinted);
        }
    }
    let best = null;
    for (let s = 0; s < slots.length; s++) {
        const slot = slots[s];
        if (slot + matched.length > expected) continue;
        const origin = firstMatched - fittedStep * slot;
        // 硬约束：region 的契约是「紧贴棋盘外沿，误差 ≤ 半个格距」，所以第 0 条线必须
        // 落在范围起点附近（允许 1.2 个格距的松弛）。少了这条，序号选错时整盘会落到
        // 屏幕外（真机上出现过起点 1189、367.9，而棋盘只有 1280 宽）。
        // 例外：**全中**（9/9、10/10）时序号本来就是确定的（第一条命中就是第 0 条），
        // 此时范围装不下整块棋盘也不该拒（真机踩过：全中、步长也对，却报"对不齐"）——
        // 但仍要过下面的分数校验，免得把假几何放进去。
        if (hasRange && !allMatched &&
            (origin < rangeFrom - slack || origin > rangeFrom + fittedStep * 1.2)) continue;
        const score = latticeScore(scan, origin, fittedStep, expected);
        const distance = hasRange ? Math.abs(origin - rangeFrom) : 0;
        if (best === null || score > best.score + 1e-6 ||
            (Math.abs(score - best.score) <= 1e-6 && distance < best.distance)) {
            best = { slot: slot, score: score, distance: distance, origin: origin };
        }
    }
    if (best === null) {
        return {
            ok: false,
            reason: "找不到与棋盘范围相容的序号（步长 " + fittedStep.toFixed(1) +
                "，命中 " + matched.length + "/" + expected + " 条）；请把范围调到紧贴棋盘，或改人工校准",
            centers: centers,
            step: fittedStep
        };
    }
    if (best.score < minLineScore) {
        return {
            ok: false,
            reason: "格点阵与图像对不上（最佳序号的平均支撑度 " + best.score.toFixed(2) +
                "，命中 " + matched.length + "/" + expected + " 条）——通常是把半格伪线当成了格线，" +
                "请把范围调到紧贴棋盘，或改人工校准",
            centers: centers,
            step: fittedStep,
            lineScore: best.score
        };
    }
    let startIndex = best.slot;
    if (startIndex + matched.length > expected) {
        return {
            ok: false,
            reason: "线条数与序号对不上（起点序号 " + startIndex + " + 命中 " + matched.length + " > " + expected + "）",
            centers: centers,
            step: fittedStep
        };
    }
    let maxResidual = 0;
    for (let i = 0; i < ks.length; i++) {
        const predicted = startAtZero + fittedStep * ks[i];
        maxResidual = Math.max(maxResidual, Math.abs(ps[i] - predicted) / fittedStep);
    }
    const origin = firstMatched - fittedStep * startIndex;
    // 范围紧贴棋盘时，第 0 条线应该离范围起点不到一个格距；差太多说明传进来的
    // 棋盘范围不准（或棋盘被裁掉一截），此时只能靠人工校准，必须让调用方知道。
    let inset = null;
    if (isFinite(rangeFrom)) inset = (origin - rangeFrom) / fittedStep;
    const notes = [];
    if (inset !== null && (inset > 0.6 || inset < -0.6)) {
        notes.push("棋盘范围与格点阵对不齐（第 0 条线偏离范围起点 " + inset.toFixed(2) + " 格），建议人工校准");
    }
    if (stepNote) notes.push(stepNote);
    const warning = notes.join("；");
    return {
        ok: true,
        // 网格第 0 条线的位置 = 第一条命中线回退 startIndex 步
        start: origin,
        startIndex: startIndex,
        firstMatchedIndex: kMin,
        step: fittedStep,
        matched: matched.length,
        expected: expected,
        quality: {
            matched: matched.length,
            expected: expected,
            maxResidual: maxResidual,
            coverage: matched.length / expected,
            // 被棋子/边框挡住、靠格点阵补回来的线条数（真实照片里通常是 1~3 条）
            reconstructed: expected - matched.length,
            originInset: inset
        },
        centers: centers,
        reason: "",
        warning: warning,
        lineScore: best.score
    };
}

/**
 * 精修格点：粗扫（step 4~6）只能保证「找对了哪几条线」，线的**位置**还带着 ±step/2 的
 * 采样相位误差（实测竖线起点会偏 6px）。这里在每条线附近做一次 1px 细扫，用支撑度加权质心
 * 定出线中心，再对「确实看到线」的那些位置重新最小二乘拟合起点与步长。
 *
 * 代价：约 (2*halfWidth+1) × 每条线的跨采样点数 × 线条数，只占粗扫的百分之几。
 *
 * @param getValue     (x, y) → 亮度
 * @param scanOptions  与粗扫同一套参数（axis/from/to/acrossFrom/acrossTo/acrossStep/gap/margin）
 * @param lattice      latticeFromSupport 的结果
 */
function refineLattice(getValue, scanOptions, lattice) {
    if (!lattice || lattice.ok !== true || typeof getValue !== "function") {
        return lattice;
    }
    const half = scanOptions.halfWidth == null ? 3 : scanOptions.halfWidth;
    const minPeak = (scanOptions.minSupport == null ? 0.45 : scanOptions.minSupport) / 2;
    const positions = [];
    const seen = [];
    for (let k = 0; k < lattice.expected; k++) {
        const predicted = lattice.start + lattice.step * k;
        if (predicted < scanOptions.from - half || predicted > scanOptions.to + half) {
            positions.push(predicted);
            seen.push(false);
            continue;
        }
        const fine = scanSupport(getValue, {
            axis: scanOptions.axis,
            from: Math.round(predicted - half),
            to: Math.round(predicted + half),
            step: 1,
            acrossFrom: scanOptions.acrossFrom,
            acrossTo: scanOptions.acrossTo,
            acrossStep: scanOptions.acrossStep,
            gap: scanOptions.gap,
            margin: scanOptions.margin
        });
        let num = 0;
        let den = 0;
        let peak = 0;
        for (let i = 0; i < fine.positions.length; i++) {
            const s = fine.support[i];
            if (s > peak) {
                peak = s;
            }
            num += fine.positions[i] * s;
            den += s;
        }
        if (den > 0 && peak >= minPeak) {
            positions.push(num / den);
            seen.push(true);
        } else {
            positions.push(predicted); // 被棋子挡住：用格点阵推出来的位置，但不参与拟合
            seen.push(false);
        }
    }
    let count = 0;
    let sumK = 0;
    let sumP = 0;
    for (let k = 0; k < positions.length; k++) {
        if (!seen[k]) {
            continue;
        }
        count++;
        sumK += k;
        sumP += positions[k];
    }
    if (count < 3) {
        return lattice;
    }
    const kMean = sumK / count;
    const pMean = sumP / count;
    let num = 0;
    let den = 0;
    for (let k = 0; k < positions.length; k++) {
        if (!seen[k]) {
            continue;
        }
        num += (k - kMean) * (positions[k] - pMean);
        den += (k - kMean) * (k - kMean);
    }
    if (!(den > 0)) {
        return lattice;
    }
    const step = num / den;
    const start = pMean - step * kMean;
    // —— 精修只能「微调」，不能改变尺度 ——
    // 细扫用的是粗扫推出来的格点阵，一旦粗扫的序号/起点偏了，细扫就会落到「半格」伪线上，
    // 重新拟合出来的步长会明显偏离粗扫值（真机 720×1600 上出现过 79.1 → 60.26，
    // 两轴格距差 21% 会直接让整盘行列错位）。这里发现偏差过大就保留粗扫结果。
    const coarseStep = lattice.step;
    const coarseStart = lattice.start;
    if (!(coarseStep > 0) ||
        Math.abs(step - coarseStep) > coarseStep * 0.12 ||
        Math.abs(start - coarseStart) > coarseStep * 0.5) {
        return lattice;
    }
    let maxResidual = lattice.quality ? lattice.quality.maxResidual : 0;
    for (let k = 0; k < positions.length; k++) {
        if (!seen[k]) {
            continue;
        }
        maxResidual = Math.max(maxResidual, Math.abs(positions[k] - (start + step * k)) / step);
    }
    const inset = isFinite(scanOptions.rangeFrom) ? (start - scanOptions.rangeFrom) / step : null;
    return {
        ok: true,
        start: start,
        startIndex: lattice.startIndex,
        firstMatchedIndex: lattice.firstMatchedIndex,
        step: step,
        matched: lattice.matched,
        expected: lattice.expected,
        quality: {
            matched: lattice.quality.matched,
            expected: lattice.quality.expected,
            maxResidual: maxResidual,
            coverage: lattice.quality.coverage,
            reconstructed: lattice.quality.reconstructed,
            originInset: inset
        },
        centers: lattice.centers,
        refined: true,
        seenCount: count,
        shift: Math.abs(lattice.start - start),
        reason: "",
        warning: inset !== null && (inset > 0.6 || inset < -0.6)
            ? "棋盘范围与格点阵对不齐（第 0 条线偏离范围起点 " + inset.toFixed(2) + " 格），建议人工校准"
            : ""
    };
}

function supportAt(scan, position) {
    let best = 0;
    for (let i = 0; i < scan.positions.length; i++) {
        if (Math.abs(scan.positions[i] - position) <= 1.5 && scan.support[i] > best) {
            best = scan.support[i];
        }
    }
    return best;
}

/**
 * 给一套「格点阵假设」打分：预计出来的每条线，在粗扫曲线上有没有支撑度。
 * 落在扫描范围之外（棋盘外）或非格线位置的预测拿不到分，于是「序号偏几格」
 * 这类错误会被打低分 —— 判定序号最可靠的证据是图本身，而不是近似范围。
 */
function latticeScore(scan, origin, step, expected) {
    if (!scan || !(step > 0) || !(expected > 0)) {
        return 0;
    }
    let sum = 0;
    for (let k = 0; k < expected; k++) {
        sum += supportAt(scan, origin + step * k);
    }
    return sum / expected;
}

function nearestCenter(centers, target, tolerance, used) {
    let bestIndex = -1;
    let bestDistance = Infinity;
    for (let i = 0; i < centers.length; i++) {
        if (used[centers[i]]) {
            continue;
        }
        const distance = Math.abs(centers[i] - target);
        if (distance <= tolerance && distance < bestDistance) {
            bestDistance = distance;
            bestIndex = i;
        }
    }
    return bestIndex >= 0 ? centers[bestIndex] : null;
}

/**
 * 支撑度路径的完整定位：两轴各扫一遍 → 拟合格点阵 → 建立几何。
 * @param getValue (x, y) → 亮度
 * @param options.region {x, y, width, height}：**棋盘大致范围**（可略大于棋盘）。
 */
function locateBySupport(getValue, options) {
    const opts = options || {};
    const region = opts.region;
    if (!region) {
        return { ok: false, grid: null, method: "support", reason: "需要先给出棋盘大致范围（region）" };
    }
    const margin = opts.margin == null ? 10 : opts.margin;
    // 扫描步长/跨采样步长/邻域 gap 全部按**格距**推导，不写死像素。
    // 参考格距 125px 对应调好的 step 4 / acrossStep 10 / gap 6（比例 0.032 / 0.08 / 0.05），
    // 于是在 1080~1280 档与原来完全一致，而在 720p 档自动缩小（格距 ≈77 → 3 / 7 / 4）。
    // ⚠️ 上限就是参考值：**只允许比参考值更细，不允许更粗**。实测把 step 放大到 5、gap 放大到 7
    // （大屏档位）会因粗扫跨过细线而丢掉 2~3 条线（横线只匹配 5~7/10），反而比不缩放更差。
    const pitchX = (region.width - margin * 2) / Math.max(1, COLS - 1);
    const pitchY = (region.height - margin * 2) / Math.max(1, ROWS - 1);
    const unitPitch = Math.max(16, Math.min(pitchX, pitchY));
    const scanStep = opts.step == null ? clampInt(unitPitch * 0.032, 1, 4) : opts.step;
    const acrossStep = opts.acrossStep == null ? clampInt(unitPitch * 0.08, 4, 10) : opts.acrossStep;
    const gap = opts.gap == null ? clampInt(unitPitch * 0.05, 2, 6) : opts.gap;
    const optionsX = {
        axis: "x",
        from: Math.round(region.x + margin),
        to: Math.round(region.x + region.width - margin),
        step: scanStep,
        acrossFrom: Math.round(region.y + margin),
        acrossTo: Math.round(region.y + region.height - margin),
        acrossStep: acrossStep,
        gap: gap,
        margin: opts.darkMargin,
        minSupport: opts.minSupport,
        halfWidth: opts.halfWidth
    };
    const optionsY = {
        axis: "y",
        from: Math.round(region.y + margin),
        to: Math.round(region.y + region.height - margin),
        step: scanStep,
        acrossFrom: Math.round(region.x + margin),
        acrossTo: Math.round(region.x + region.width - margin),
        acrossStep: acrossStep,
        gap: gap,
        margin: opts.darkMargin,
        minSupport: opts.minSupport,
        halfWidth: opts.halfWidth
    };
    const vertical = scanSupport(getValue, optionsX);
    const horizontal = scanSupport(getValue, optionsY);
    // 粗扫定「有几条线、步长多少、序号从哪开始」，再各轴精修一次线位置
    let verticalLattice = latticeFromSupport(vertical, {
        expected: COLS,
        minSupport: opts.minSupport,
        tolerance: opts.tolerance,
        // 棋盘范围就是「第 0 条线到第 8 条线」的近似区间，用它给格点定序号
        rangeFrom: optionsX.from,
        rangeTo: optionsX.to
    });
    if (!verticalLattice.ok) {
        return { ok: false, grid: null, method: "support", reason: "竖线：" + verticalLattice.reason };
    }
    verticalLattice = refineLattice(getValue, optionsX, verticalLattice);
    optionsY.rangeFrom = optionsY.from;
    optionsY.rangeTo = optionsY.to;
    let horizontalLattice = latticeFromSupport(horizontal, {
        expected: ROWS,
        minSupport: opts.minSupport,
        tolerance: opts.tolerance,
        rangeFrom: optionsY.from,
        rangeTo: optionsY.to
    });
    if (!horizontalLattice.ok) {
        return { ok: false, grid: null, method: "support", reason: "横线：" + horizontalLattice.reason };
    }
    horizontalLattice = refineLattice(getValue, optionsY, horizontalLattice);
    if (Math.abs(verticalLattice.step - horizontalLattice.step) >
        Math.max(verticalLattice.step, horizontalLattice.step) * 0.15) {
        return {
            ok: false,
            grid: null,
            method: "support",
            reason: "两轴格距不一致（竖 " + verticalLattice.step.toFixed(1) + " / 横 " + horizontalLattice.step.toFixed(1) +
                "，相差 " + (Math.abs(verticalLattice.step - horizontalLattice.step) /
                    Math.max(verticalLattice.step, horizontalLattice.step) * 100).toFixed(0) +
                "%）——象棋棋盘格子是正方形，两轴必须基本一致；偏差这么大通常是把半格伪线当成了格线，" +
                "请把范围调到紧贴棋盘或改人工校准"
        };
    }
    const grid = geometry.createGrid({
        originX: verticalLattice.start,
        originY: horizontalLattice.start,
        stepX: verticalLattice.step,
        stepY: horizontalLattice.step,
        orientation: opts.orientation,
        version: opts.version == null ? 1 : opts.version
    });
    grid.quality = {
        sampleCount: verticalLattice.matched + horizontalLattice.matched,
        inlierCount: verticalLattice.matched + horizontalLattice.matched,
        maxResidual: Math.max(verticalLattice.quality.maxResidual, horizontalLattice.quality.maxResidual),
        meanResidual: Math.max(verticalLattice.quality.maxResidual, horizontalLattice.quality.maxResidual) / 2
    };
    return {
        ok: true,
        grid: grid,
        method: "support",
        reason: "支撑度定位：竖线 " + verticalLattice.matched + "/" + COLS +
            "、横线 " + horizontalLattice.matched + "/" + ROWS +
            "（精修 竖 " + (verticalLattice.refined ? "✓" : "-") + " / 横 " + (horizontalLattice.refined ? "✓" : "-") + "）",
        warning: [verticalLattice.warning, horizontalLattice.warning].filter(Boolean).join("；"),
        vertical: verticalLattice,
        horizontal: horizontalLattice
    };
}

/**
 * 组合入口：手动校准点最可靠 → 支撑度定位（真实照片）→ 暗线投影（合成/简单画面）。
 * @returns { ok, grid, method, reason }
 */
function locate(options) {
    const opts = options || {};
    if (opts.corners && opts.corners.length >= 2) {
        return locateByCorners(opts.corners, opts);
    }
    if (typeof opts.getValue === "function" && opts.region) {
        return locateBySupport(opts.getValue, opts);
    }
    if (opts.region && typeof opts.sampler === "function") {
        return locateBySupport(opts.sampler, opts);
    }
    if (typeof opts.sampler === "function") {
        return locateByLines(opts.sampler, opts);
    }
    return { ok: false, grid: null, method: "none", reason: "既没有校准点也没有图像采样接口" };
}

/**
 * ======================= 定位候选评分（供「收集后选优」用） =======================
 * 2026-09-22 多证据融合方案 §2.2/§10：「第一个 ok 即返回」会让一个低质量但勉强过线的
 * 候选挡住后面的正确候选。分数只看**线证据**（不掺棋子识别）——
 * 棋子中心只用于精修，不参与定位验收。
 */
function latticeStatsOf(lattice) {
    const q = (lattice && lattice.quality) || {};
    return {
        matched: q.matched == null ? ((lattice && lattice.matched != null) ? lattice.matched : 0) : q.matched,
        maxResidual: q.maxResidual == null ? 1 : q.maxResidual,
        reconstructed: q.reconstructed == null ? 0 : q.reconstructed,
        lineScore: (lattice && lattice.lineScore != null) ? lattice.lineScore : 0
    };
}

/**
 * 一次定位结果的质量分（越高越好；只有 ok 的结果参与比较）。
 * 组成：命中条数（19 满）+ 支撑度加成 − 补线惩罚 − 残差惩罚 − 警告惩罚。
 * 依据：真成功 lineScore 高、残差小、几乎不用补线；「格距全对、锚偏一行」的假成功
 * 往往带范围警告/补线/低支撑度——先按这个分排，再靠面板的整行体检兜底。
 */
function scoreLocateResult(result) {
    if (!result || !result.ok) {
        return -Infinity;
    }
    const v = latticeStatsOf(result.vertical);
    const h = latticeStatsOf(result.horizontal);
    let score = v.matched + h.matched;                            // 0..19
    score += (v.lineScore + h.lineScore) * 0.5;                   // 支撑度（0..~1）
    score -= (v.reconstructed + h.reconstructed) * 0.35;          // 靠格点阵补回来的线
    score -= Math.max(v.maxResidual, h.maxResidual) * 2;          // 最大残差（格距倍数）
    if (result.warning) {
        score -= 0.75;                                            // 范围对不齐等警告
    }
    return score;
}

/**
 * 「足够好，不必再看后面的候选」：
 * 无警告、补线 ≤1、竖 ≥8/9、横 ≥9/10、最大残差 ≤0.25 格。
 * 达到它 attemptLocate 立即收工（默认范围就命中时与旧的「首个即胜」等速）；
 * 达不到才继续收集后面的范围（换手机/换皮肤时的兜底）。
 */
function isAcceptableLocate(result) {
    if (!result || !result.ok || result.warning) {
        return false;
    }
    const v = latticeStatsOf(result.vertical);
    const h = latticeStatsOf(result.horizontal);
    if (v.reconstructed + h.reconstructed > 1) {
        return false;
    }
    if (v.matched < 8 || h.matched < 9) {
        return false;
    }
    if (Math.max(v.maxResidual, h.maxResidual) > 0.25) {
        return false;
    }
    return true;
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
    scanSupport: scanSupport,
    latticeFromSupport: latticeFromSupport,
    refineLattice: refineLattice,
    locateBySupport: locateBySupport,
    scoreLocateResult: scoreLocateResult,
    isAcceptableLocate: isAcceptableLocate,
    locate: locate
};

module.exports = XQ_BOARD_LOCATOR;
