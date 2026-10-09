// @engine quickjs
/**
 * 棋子候选框检测（重构方案 §3.2）：YOLO **只回答「哪里有棋子」**。
 *
 * 职责边界（方案原文）：
 *   ✔ 棋盘大致在哪、哪些位置有棋子、每个棋子圆盘的检测框与置信度
 *   ✘ 红黑阵营、最终棋子名称、轮到哪一方、局面是否合法 —— 都不归它
 *
 * 检测框统一结构（方案 §3.2）：
 *   { x, y, width, height, centerX, centerY, score, source: "yolo" }
 *
 * 映射到交点（方案 §3.3）：
 *   · 一个框只能给一个交点、一个交点只收一个框；
 *   · 超过 0.38 格距的框拒绝分配；
 *   · 冲突时选「更近 + 置信度更高」的框。
 *
 * 去重（2026-10-05 对齐基础示例口径）：detect() 拿到框后先按 IoU 去重（dedupeIou=0.3，
 * 只留分高的），再去映射交点 —— 阈值拉满召回（0.001）后一个棋子常出多个叠框/低分框。
 */
"use strict";

const DEFAULTS = {
    /** 检测置信度（2026-10-05 对齐基础示例口径：0.001 = 拉满召回、宁多勿漏；方案原文建议 0.22） */
    confidence: 0.001,
    /** NMS（对齐基础示例：0.001；宿主只校验取值、不参与解码，实际不生效） */
    nms: 0.001,
    /** 检测后按 IoU 去重（对齐基础示例 DEDUPE_IOU；重叠 ≥ 此值判重复只留分高的，0 = 关） */
    dedupeIou: 0.3,
    /** 最多保留多少个框（方案建议 40） */
    maxDetections: 40,
    /** 框到交点的最大距离（格距倍数，方案 §3.3：0.38） */
    maxCellDistance: 0.38,
    /** 只保留落在棋盘范围内的框（留一点余量） */
    edgeMargin: 0.6
};

/** 把任意宿主检测结果规整成方案要求的统一结构 */
function normalizeBoxes(raw) {
    const out = [];
    const list = raw || [];
    for (let i = 0; i < list.length; i++) {
        const one = list[i];
        if (!one) {
            continue;
        }
        // 原生形态（AI.js Pro 的 yolo 检测）：{ bounds: { centerX, centerY, width, height }, label, score }
        // ⚠️ 不认这个形态的话会把 32 个框全判成「坐标非法」丢掉（2026-09-19 真机撞过：检测 32 个、流水线 0 个）
        const b = one.bounds || null;
        let centerX = null, centerY = null, width = null, height = null;
        if (b) {
            centerX = b.centerX != null ? b.centerX : null;
            centerY = b.centerY != null ? b.centerY : null;
            width = b.width != null ? b.width : null;
            height = b.height != null ? b.height : null;
        }
        if (one.centerX != null) {
            centerX = one.centerX;
        }
        if (one.centerY != null) {
            centerY = one.centerY;
        }
        if (width == null && one.width != null) {
            width = one.width;
        }
        if (height == null && one.height != null) {
            height = one.height;
        }
        if (centerX == null) {
            // 退回左上角形态（x,y 为左上角）
            const left = one.x != null ? one.x : (one.left != null ? one.left : (one.cx != null ? one.cx - (width || 0) / 2 : NaN));
            centerX = left + (Number(width) || 0) / 2;
        }
        if (centerY == null) {
            const top = one.y != null ? one.y : (one.top != null ? one.top : (one.cy != null ? one.cy - (height || 0) / 2 : NaN));
            centerY = top + (Number(height) || 0) / 2;
        }
        if (!isFinite(centerX) || !isFinite(centerY)) {
            continue;
        }
        const w = Number(width);
        const h = Number(height);
        if (!isFinite(w) || !isFinite(h) || w <= 0 || h <= 0) {
            continue;
        }
        const score = Number(one.score != null ? one.score : (one.confidence != null ? one.confidence : 0));
        out.push({
            x: centerX - w / 2, y: centerY - h / 2, width: w, height: h,
            centerX: centerX, centerY: centerY,
            score: isFinite(score) ? score : 0,
            label: one.label == null ? null : one.label,
            source: one.source || "yolo"
        });
    }
    return out;
}

/** 两框交并比（IoU）；a/b 为 {x,y,width,height} 左上角形态。 */
function overlapRatio(a, b) {
    const left = Math.max(a.x, b.x);
    const top = Math.max(a.y, b.y);
    const right = Math.min(a.x + a.width, b.x + b.width);
    const bottom = Math.min(a.y + a.height, b.y + b.height);
    if (right <= left || bottom <= top) {
        return 0;
    }
    const inter = (right - left) * (bottom - top);
    const union = a.width * a.height + b.width * b.height - inter;
    return union > 0 ? inter / union : 0;
}

/**
 * 按 IoU 去重（对齐基础示例 DEDUPE_IOU 口径）：分数从高到低保留，
 * 与已保留框重叠 ≥ threshold 的当重复丢掉。0/负值 = 不去重。
 */
function dedupeByIou(boxes, threshold) {
    if (!(threshold > 0) || !boxes || boxes.length < 2) {
        return boxes;
    }
    const sorted = boxes.slice().sort(function (a, b) { return b.score - a.score; });
    const kept = [];
    for (let i = 0; i < sorted.length; i++) {
        let duplicate = false;
        for (let j = 0; j < kept.length; j++) {
            if (overlapRatio(sorted[i], kept[j]) >= threshold) {
                duplicate = true;
                break;
            }
        }
        if (!duplicate) {
            kept.push(sorted[i]);
        }
    }
    return kept;
}

/**
 * 跑一次检测。
 * @param host { detect(image, options) → 原始框数组 }
 */
function detect(host, image, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    if (!host || typeof host.detect !== "function" || !image) {
        return { ok: false, reason: "没有检测能力或没有帧", boxes: [] };
    }
    let raw = null;
    try {
        raw = host.detect(image, {
            confidence: opts.confidence,
            nms: opts.nms,
            maxDetections: opts.maxDetections
        });
    } catch (error) {
        return { ok: false, reason: "检测异常：" + (error && error.message ? error.message : error), boxes: [] };
    }
    let boxes = normalizeBoxes(raw);
    // 先在脚本侧按 IoU 去重（对齐基础示例 DEDUPE_IOU：阈值调低后一个棋子常出多个叠框）
    boxes = dedupeByIou(boxes, opts.dedupeIou);
    // ⚠️ 先排序再截断：反过来的话，原生检测器若没按分数排序，高分框可能先被截掉
    //    （2026-09-20 评审指出的问题；maxDetections 默认 40，而一局面最多 32 子，
    //      所以现实中很少触发 —— 但顺序本来就没理由写反）。
    boxes.sort(function (a, b) { return b.score - a.score; });
    if (boxes.length > opts.maxDetections) {
        boxes = boxes.slice(0, opts.maxDetections);
    }
    return { ok: true, reason: "", boxes: boxes, raw: raw ? raw.length : 0 };
}

/** 框中心到某个交点的距离（单位：格距） */
function distanceInCells(box, grid, col, row, orientation) {
    const stepX = Math.abs(grid.stepX), stepY = Math.abs(grid.stepY);
    const flip = (orientation || grid.orientation) === "RED_TOP";
    const cx = grid.originX + (flip ? 8 - col : col) * grid.stepX;
    const cy = grid.originY + (flip ? 9 - row : row) * grid.stepY;
    const dx = (box.centerX - cx) / (stepX || 1);
    const dy = (box.centerY - cy) / (stepY || 1);
    return Math.sqrt(dx * dx + dy * dy);
}

/**
 * 检测框 → 交点映射（方案 §3.3 的三条规则）。
 * @return { cells: [{index, col, row, box, residual}], rejected: [{box, reason}] }
 */
function mapToCells(boxes, grid, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const cells = [];
    const rejected = [];
    if (!grid) {
        return { cells: cells, rejected: rejected };
    }
    const taken = {};
    // 按「距离近、置信度高」排序：距离优先（同一格有多框时取最近的那个）
    const candidates = [];
    for (let i = 0; i < (boxes || []).length; i++) {
        const box = boxes[i];
        let best = null;
        for (let row = 0; row < 10; row++) {
            for (let col = 0; col < 9; col++) {
                const d = distanceInCells(box, grid, col, row);
                if (d > opts.maxCellDistance + 0.25) {
                    continue;   // 连候选都不用进（离所有交点都太远）
                }
                if (!best || d < best.distance) {
                    best = { index: row * 9 + col, col: col, row: row, distance: d };
                }
            }
        }
        if (!best) {
            rejected.push({ box: box, reason: "离任何交点都超过 " + (opts.maxCellDistance + 0.25) + " 格距" });
            continue;
        }
        if (best.distance > opts.maxCellDistance) {
            rejected.push({ box: box, reason: "最近交点也有 " + best.distance.toFixed(2) + " 格距（> " + opts.maxCellDistance + "）" });
            continue;
        }
        candidates.push({
            index: best.index, col: best.col, row: best.row,
            distance: best.distance, score: box.score, box: box
        });
    }
    // 距离近的优先占位；同格冲突时优先「更近」，距离相同再比置信度
    candidates.sort(function (a, b) {
        if (Math.abs(a.distance - b.distance) > 0.001) {
            return a.distance - b.distance;
        }
        return b.score - a.score;
    });
    for (let i = 0; i < candidates.length; i++) {
        const one = candidates[i];
        if (taken[one.index]) {
            rejected.push({ box: one.box, reason: "交点已被更近的框占用" });
            continue;
        }
        taken[one.index] = true;
        cells.push({
            index: one.index, col: one.col, row: one.row,
            box: one.box, residual: one.distance, score: one.score
        });
    }
    return { cells: cells, rejected: rejected };
}

module.exports = {
    detect: detect,
    mapToCells: mapToCells,
    normalizeBoxes: normalizeBoxes,
    dedupeByIou: dedupeByIou,
    overlapRatio: overlapRatio,
    distanceInCells: distanceInCells,
    DEFAULTS: DEFAULTS
};
