// @engine quickjs
/**
 * YOLO 检测底座：只负责“选检测区域 → 调宿主检测器 → 统一坐标”。
 *
 * 旧识别器和新视觉流水线必须共用这里，避免两套区域/宽高比逻辑逐渐分叉。
 * 特别是 160×320 矩形模型不能直接把方形棋盘送进 adapter.detect：
 * 它需要保留整屏或按目标宽高比扩展后的区域，否则可能触发 OpenCV reshape 断言。
 *
 * 本模块不做格点分配、红黑判断、OCR、FEN 或规则校验。
 */
"use strict";

const geometry = require("./geometry.js");
const defaults = require("../util/defaults.js");

const DEFAULTS = {
    cropMargin: 0.75,
    // 2026-10-05 对齐基础示例口径：0.001 拉满召回（重复框由交点归并/每格一个兜底）
    confidence: 0.001,
    nms: 0.001,
    regionAspect: null,
    regionMode: "board",
    useRegion: false
};

/** 把棋盘外框扩成目标宽高比，保持中心并夹在图像内。 */
function fitAspect(box, aspect, image) {
    if (!aspect || !box || !(aspect > 0)) {
        return box;
    }
    const maxWidth = image && typeof image.getWidth === "function" ? image.getWidth() : null;
    const maxHeight = image && typeof image.getHeight === "function" ? image.getHeight() : null;
    let width = box.width;
    let height = box.height;
    if (width / height < aspect) {
        width = height * aspect;
    } else {
        height = width / aspect;
    }
    const centreX = box.x + box.width / 2;
    const centreY = box.y + box.height / 2;
    let x = Math.round(centreX - width / 2);
    let y = Math.round(centreY - height / 2);
    width = Math.round(width);
    height = Math.round(height);
    if (maxWidth != null && maxHeight != null) {
        x = Math.max(0, Math.min(x, Math.max(0, maxWidth - width)));
        y = Math.max(0, Math.min(y, Math.max(0, maxHeight - height)));
        width = Math.min(width, maxWidth);
        height = Math.min(height, maxHeight);
    }
    return { x: x, y: y, width: width, height: height };
}

function regionBox(options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    if (opts.regionMode === "full" && opts.image && typeof opts.image.getWidth === "function") {
        const width = opts.image.getWidth();
        const height = opts.image.getHeight();
        if (width > 0 && height > 0) {
            return { x: 0, y: 0, width: width, height: height };
        }
    }
    return fitAspect(geometry.boundsOf(opts.grid, opts.cropMargin), opts.regionAspect, opts.image);
}

/**
 * 原生检测结果 → recognizer 使用的中心点结构。
 * box 为 null 表示原生坐标已经是全图坐标；非 null 时把裁剪偏移加回一次。
 */
function normalize(raw, box) {
    const list = [];
    const offsetX = box ? box.x : 0;
    const offsetY = box ? box.y : 0;
    for (let i = 0; i < (raw ? raw.length : 0); i++) {
        const item = raw[i];
        if (!item) {
            continue;
        }
        let x = item.x;
        let y = item.y;
        if (x == null && item.bounds) {
            x = item.bounds.centerX == null
                ? item.bounds.x + item.bounds.width / 2
                : item.bounds.centerX;
            y = item.bounds.centerY == null
                ? item.bounds.y + item.bounds.height / 2
                : item.bounds.centerY;
        }
        if (!isFinite(x) || !isFinite(y)) {
            continue;
        }
        const bounds = item.bounds || {};
        list.push({
            label: item.label,
            classId: item.classId,
            score: item.score == null ? 1 : item.score,
            x: x + offsetX,
            y: y + offsetY,
            width: bounds.width == null ? item.width : bounds.width,
            height: bounds.height == null ? item.height : bounds.height
        });
    }
    return list;
}

/**
 * 只执行检测，不进入格点分配、颜色、OCR 或 FEN。
 *
 * @return {
 *   ok, reason?, stage?, box,
 *   raw,          // 宿主原始框（给 piece-detector 使用）
 *   detections,   // 全图中心点坐标（给旧 recognizer 使用）
 *   timing: {cropMs, detectMs, totalMs}
 * }
 */
function detect(options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const startedAt = Date.now();
    const timing = { cropMs: 0, detectMs: 0, totalMs: 0 };
    if (!opts.image) {
        return { ok: false, reason: "没有图像", stage: "input", raw: [], detections: [], timing: timing };
    }
    if (!opts.grid) {
        return { ok: false, reason: "没有棋盘几何（先定位棋盘）", stage: "input", raw: [], detections: [], timing: timing };
    }
    if (typeof opts.detect !== "function") {
        return { ok: false, reason: "没有检测器", stage: "input", raw: [], detections: [], timing: timing };
    }

    const box = regionBox(opts);
    let raw = [];
    let detectTarget = opts.image;
    const detectStartedAt = Date.now();
    try {
        if (opts.useRegion === true) {
            // AI.js Pro 的区域检测会在 Java 层把 region 偏移加回，返回值已经是全图坐标。
            raw = opts.detect(opts.image, {
                confidence: opts.confidence,
                nms: opts.nms,
                region: [box.x, box.y, box.width, box.height]
            }) || [];
        } else {
            if (typeof opts.crop === "function") {
                const cropStartedAt = Date.now();
                detectTarget = opts.crop(opts.image, box);
                timing.cropMs = Date.now() - cropStartedAt;
                if (!detectTarget) {
                    timing.totalMs = Date.now() - startedAt;
                    return {
                        ok: false, reason: "裁剪棋盘区域失败", stage: "crop", box: box,
                        raw: [], detections: [], timing: timing
                    };
                }
            }
            raw = opts.detect(detectTarget, {
                confidence: opts.confidence,
                nms: opts.nms
            }) || [];
        }
    } catch (error) {
        timing.detectMs = Date.now() - detectStartedAt;
        timing.totalMs = Date.now() - startedAt;
        return {
            ok: false,
            stage: "detect",
            reason: "检测失败：" + (error && error.message ? error.message : error),
            box: box,
            raw: [],
            detections: [],
            timing: timing
        };
    }
    timing.detectMs = Date.now() - detectStartedAt;
    timing.totalMs = Date.now() - startedAt;
    return {
        ok: true,
        reason: "",
        box: box,
        raw: raw,
        // 区域模式已经回移；手工裁剪模式在这里加回裁剪偏移。
        detections: normalize(raw, opts.useRegion === true ? null : box),
        timing: timing
    };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    fitAspect: fitAspect,
    regionBox: regionBox,
    normalize: normalize,
    detect: detect
};
