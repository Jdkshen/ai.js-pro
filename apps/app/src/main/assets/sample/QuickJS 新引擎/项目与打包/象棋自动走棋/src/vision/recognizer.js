// @engine quickjs
/**
 * 识别链路：把「一帧画面 + 棋盘几何」变成「FEN 布局」。
 *
 * 链路（每一步的失败都如实报告，不硬猜）：
 *   1. 按**定位出来的棋盘外框**裁剪（不是写死屏幕比例 —— 写死会切掉边缘棋子）
 *   2. 模型在裁剪图上检测棋子（模型类别 = 7 个兵种字母 R N B A K C P，不含颜色）
 *   3. 检测框 + 棋盘几何 → 90 个交点（cell-assignment：保留候选/冲突/未知）
 *   4. 每个有子的格子采样 RGB → 判红黑（这个模型没有阵营，必须补）
 *   5. 兵种字母 + 阵营 → FEN 字符 → 布局串
 *
 * 本模块**不直接调用宿主接口**：裁剪 / 检测 / 取像素都由调用方注入，
 * 这样离线自检里可以用桩跑完整条链路（含错误分支）。
 */
"use strict";

const geometry = require("./geometry.js");
const assignment = require("./cell-assignment.js");
const pieceSide = require("./piece-side.js");
const fen = require("../chess/fen.js");
const defaults = require("../util/defaults.js");

const DEFAULTS = {
    /** 棋盘外框外扩（格距倍数） */
    cropMargin: 0.75,
    /** 检测置信度门槛 */
    confidence: 0.25,
    nms: 0.45,
    /** 格点分配：低分框直接丢掉（实测 0.2~0.35 的框基本都是假的） */
    minScore: 0.45,
    maxResidual: 0.4,
    conflictGap: 0.15,
    /** 整帧质量（0..1）：定位差就当「没看到」而不是「没有子」 */
    frameQuality: 1,
    /** 阵营采样步长（像素） */
    sideSampleStep: 3
};

/** 认字：模型类别字母 → 中文，仅用于给人看。 */
const PIECE_NAMES = {
    R: "车", N: "马", B: "相/象", A: "仕/士", K: "帅/将", C: "炮", P: "兵/卒"
};

/**
 * @param options.image       帧或图片（交给 crop/detect 用）
 * @param options.grid        棋盘几何（location 的产物）
 * @param options.detect      (image, {confidence, nms, region?}) → 检测列表
 * @param options.useRegion   true = 用宿主自带的区域检测（官方实现会把坐标**回移**到全图，本模块不再加偏移）；
 *                            false/缺省 = 先 crop 再检测，检测结果按 crop 偏移加回去
 * @param options.crop        (image, box) → 裁剪后的图（useRegion=false 时用）
 * @param options.rgbAt       (image, x, y) → [r,g,b]（原图坐标）
 * @param options.sideToMove  "w"/"b"/null（识别本身给不出轮次，默认 null）
 */
function recognize(options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const startedAt = Date.now();
    const timing = { cropMs: 0, detectMs: 0, sideMs: 0, totalMs: 0 };
    if (!opts.image) {
        return { ok: false, reason: "没有图像", stage: "input" };
    }
    if (!opts.grid) {
        return { ok: false, reason: "没有棋盘几何（先定位棋盘）", stage: "input" };
    }
    if (typeof opts.detect !== "function") {
        return { ok: false, reason: "没有检测器", stage: "input" };
    }

    const box = geometry.boundsOf(opts.grid, opts.cropMargin);
    const detectStart = Date.now();
    let raw = [];
    let detectTarget = opts.image;
    try {
        if (opts.useRegion === true) {
            // 官方区域检测：Java 侧会把 region 加回结果（OpenCvYoloDetector.detectRgba），
            // 所以拿到的坐标已经是**全图**坐标，这里不能再加偏移。
            raw = opts.detect(opts.image, {
                confidence: opts.confidence,
                nms: opts.nms,
                region: [box.x, box.y, box.width, box.height]
            }) || [];
        } else {
            if (typeof opts.crop === "function") {
                const t0 = Date.now();
                detectTarget = opts.crop(opts.image, box);
                timing.cropMs = Date.now() - t0;
                if (!detectTarget) {
                    return { ok: false, reason: "裁剪棋盘区域失败", stage: "crop", box: box };
                }
            }
            raw = opts.detect(detectTarget, { confidence: opts.confidence, nms: opts.nms }) || [];
        }
    } catch (error) {
        return {
            ok: false,
            stage: "detect",
            reason: "检测失败：" + (error && error.message ? error.message : error),
            box: box
        };
    }
    timing.detectMs = Date.now() - detectStart;
    // 两种模式下归一化的偏移量不同：区域模式坐标已回移，裁剪模式要自己加回去。
    const detections = normalize(raw, opts.useRegion === true ? null : box);

    const assigned = assignment.assign(detections, opts.grid, {
        minScore: opts.minScore,
        maxResidual: opts.maxResidual,
        conflictGap: opts.conflictGap,
        frameQuality: opts.frameQuality
    });

    // 逐格判阵营（只对「有子」的格子采样，空格不花这个钱）
    const sideStart = Date.now();
    const sideOf = new Array(90);
    const sides = [];
    for (let index = 0; index < 90; index++) {
        const cell = assigned.cells[index];
        if (cell.state !== "PIECE") {
            continue;
        }
        const col = index % geometry.COLS;
        const row = Math.floor(index / geometry.COLS);
        if (typeof opts.rgbAt !== "function") {
            sideOf[index] = { side: pieceSide.SIDE_UNKNOWN, reason: "没有取像素的能力" };
            continue;
        }
        const result = pieceSide.classifyAt(function (x, y) {
            return opts.rgbAt(opts.image, x, y);
        }, opts.grid, col, row, { sampleStep: opts.sideSampleStep });
        sideOf[index] = result;
        sides.push({
            col: col, row: row, piece: cell.piece, side: result.side,
            redShare: result.redShare, darkShare: result.darkShare,
            counted: result.counted, reason: result.reason
        });
    }
    timing.sideMs = Date.now() - sideStart;

    // 组装 FEN 字符
    const chars = new Array(90);
    const placements = [];
    for (let index = 0; index < 90; index++) {
        const cell = assigned.cells[index];
        if (cell.state === "EMPTY") {
            chars[index] = null;
            continue;
        }
        if (cell.state === "UNKNOWN") {
            chars[index] = "?";
            continue;
        }
        const side = sideOf[index] ? sideOf[index].side : pieceSide.SIDE_UNKNOWN;
        const label = cell.piece;
        const letter = pieceSide.applySide(label, side);
        chars[index] = letter;
        // 把「带阵营的字符」写回格子：下游（时序融合、棋规层）都用 FEN 字符说话，
        // 只留模型原始字母的话阵营信息会在融合时丢掉。原始字母仍留在 candidates[0].label。
        cell.piece = letter;
        placements.push({
            index: index,
            col: index % geometry.COLS,
            row: Math.floor(index / geometry.COLS),
            label: label,
            name: PIECE_NAMES[String(label).toUpperCase()] || String(label),
            side: pieceSide.describeSide(side),
            fen: letter,
            score: cell.score
        });
    }

    const unknownCount = chars.filter(function (ch) { return ch === "?"; }).length;
    const sideReasons = {};
    sides.forEach(function (entry) {
        if (entry.side === pieceSide.SIDE_UNKNOWN) {
            const key = entry.reason || "未说明";
            sideReasons[key] = (sideReasons[key] || 0) + 1;
        }
    });
    const stats = Object.assign({}, assigned.stats, {
        sideUnknown: sides.filter(function (s) { return s.side === pieceSide.SIDE_UNKNOWN; }).length,
        sideRed: sides.filter(function (s) { return s.side === pieceSide.SIDE_RED; }).length,
        sideBlack: sides.filter(function (s) { return s.side === pieceSide.SIDE_BLACK; }).length,
        sideReasons: sideReasons,
        unknownCells: unknownCount
    });

    let layout = null;
    let layoutError = "";
    try {
        layout = fen.layoutOf(chars, { allowUnknown: true });
    } catch (error) {
        layoutError = String(error && error.message ? error.message : error);
    }

    timing.totalMs = Date.now() - startedAt;
    // 有未知格时只给布局串（带 ?），不给「完整 FEN」：轮次也不知道，不装。
    return {
        ok: layout != null,
        reason: layout == null ? ("生成布局失败：" + layoutError) : "",
        box: box,
        detections: detections,
        detectionCount: detections.length,
        assigned: assigned.cells,
        stats: stats,
        placements: placements,
        sides: sides,
        chars: chars,
        layout: layout,
        unknownCells: unknownCount,
        sideToMove: opts.sideToMove || null,
        timing: timing
    };
}

/** 检测结果 → 统一形态（中心点坐标 + 可选裁剪偏移）。box 为 null 表示坐标已经是全图坐标。 */
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
            width: bounds.width,
            height: bounds.height
        });
    }
    return list;
}

/** 给人看的棋盘图（row 0 在上，字母后跟 r/b/?）。 */
function boardText(result) {
    if (!result || !result.chars) {
        return "";
    }
    const lines = [];
    for (let row = 0; row < 10; row++) {
        const cells = [];
        for (let col = 0; col < 9; col++) {
            const ch = result.chars[row * 9 + col];
            cells.push(ch == null ? " . " : " " + ch + " ");
        }
        lines.push(String(row).padStart(2, " ") + " " + cells.join(""));
    }
    return lines.join("\n");
}

module.exports = {
    DEFAULTS: DEFAULTS,
    PIECE_NAMES: PIECE_NAMES,
    recognize: recognize,
    normalize: normalize,
    boardText: boardText
};
