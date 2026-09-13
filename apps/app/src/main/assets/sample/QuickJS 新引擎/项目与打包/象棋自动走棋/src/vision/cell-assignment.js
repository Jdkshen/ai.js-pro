// @engine quickjs
/**
 * 格点分配：把检测框归到 90 个交点上，并保留候选与冲突信息。
 *
 * 为什么要保留候选而不是「一格留最高分」：类别接近、框跨格、中心不可靠时，
 * 直接取最高分就会把错误当成事实传给棋规层。这里只负责如实描述不确定性，
 * 最终「这一格是什么」由时序融合 + 棋规层决定。
 */
"use strict";

const geometryModule = require("./geometry.js");
const defaults = require("../util/defaults.js");

const STATE_EMPTY = "EMPTY";
const STATE_PIECE = "PIECE";
const STATE_UNKNOWN = "UNKNOWN";

const DEFAULTS = {
    minScore: 0.45,
    conflictGap: 0.15,
    maxResidual: 0.4,
    /** 整帧图像质量（0..1）：定位/清晰度越差，空格与低分框越不可信 */
    frameQuality: 1
};

/**
 * @param detections [{label, score, x, y, width?, height?}] 中心点为屏幕逻辑像素
 * @param grid       createGrid 的产物
 * @returns { cells, stats }
 */
function assign(detections, grid, options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const cells = new Array(90);
    for (let i = 0; i < 90; i++) {
        cells[i] = { state: STATE_EMPTY, piece: null, score: 0, candidates: [], conflict: false, residual: 0 };
    }
    const stats = { detected: 0, placed: 0, merged: 0, conflicts: 0, unknown: 0, outOfBoard: 0, lowQuality: 0 };
    const frameUsable = opts.frameQuality >= 0.5;

    for (let i = 0; i < (detections ? detections.length : 0); i++) {
        const det = detections[i];
        if (!det || !isFinite(det.x) || !isFinite(det.y)) {
            continue;
        }
        stats.detected++;
        if (det.score != null && det.score < opts.minScore) {
            continue;
        }
        const hit = geometryModule.nearest(grid, det.x, det.y);
        if (!hit.inside) {
            stats.outOfBoard++;
            continue;
        }
        const index = hit.row * geometryModule.COLS + hit.col;
        const cell = cells[index];
        cell.residual = Math.max(cell.residual, hit.normalized);
        cell.candidates.push({
            label: det.label,
            score: det.score == null ? 1 : det.score,
            residual: hit.normalized
        });
    }

    cells.forEach(function (cell) {
        if (cell.candidates.length === 0) {
            // 没有框不等于「确定为空」：图像质量差时只能说「没看到」。
            cell.state = frameUsable ? STATE_EMPTY : STATE_UNKNOWN;
            if (cell.state === STATE_UNKNOWN) {
                stats.unknown++;
            }
            return;
        }
        // 同类合并：同一格同一类别的多个框只算一次，分数取最高，个数记在 count 上。
        const byLabel = {};
        const order = [];
        cell.candidates.forEach(function (candidate) {
            const entry = byLabel[candidate.label];
            if (!entry) {
                byLabel[candidate.label] = { label: candidate.label, score: candidate.score, count: 1, residual: candidate.residual };
                order.push(candidate.label);
                stats.merged++;
            } else {
                entry.score = Math.max(entry.score, candidate.score);
                entry.residual = Math.min(entry.residual, candidate.residual);
                entry.count++;
            }
        });
        const merged = order.map(function (label) { return byLabel[label]; })
            .sort(function (a, b) { return b.score - a.score; });
        cell.candidates = merged;
        const best = merged[0];
        const second = merged.length > 1 ? merged[1] : null;
        if (best.residual > opts.maxResidual) {
            // 框中心离交点太远（跨格 / 动画中途 / 遮挡），不猜测。
            cell.state = STATE_UNKNOWN;
            cell.piece = null;
            cell.score = best.score;
            cell.conflict = true;
            stats.unknown++;
            stats.lowQuality++;
            return;
        }
        if (second && best.score - second.score < opts.conflictGap) {
            cell.state = STATE_UNKNOWN;
            cell.piece = null;
            cell.score = best.score;
            cell.conflict = true;
            stats.conflicts++;
            stats.unknown++;
            return;
        }
        cell.state = STATE_PIECE;
        cell.piece = best.label;
        cell.score = best.score;
        stats.placed++;
    });

    return { cells: cells, stats: stats };
}

/** 把 cells 转成「红方在下」的 FEN 行数组（未知格用 '?'）。 */
function cellsToChars(cells) {
    const chars = new Array(90);
    for (let i = 0; i < 90; i++) {
        const cell = cells[i];
        if (cell.state === STATE_PIECE) {
            chars[i] = cell.piece;
        } else if (cell.state === STATE_UNKNOWN) {
            chars[i] = "?";
        } else {
            chars[i] = null;
        }
    }
    return chars;
}

const XQ_ASSIGNMENT = {
    STATE_EMPTY: STATE_EMPTY,
    STATE_PIECE: STATE_PIECE,
    STATE_UNKNOWN: STATE_UNKNOWN,
    DEFAULTS: DEFAULTS,
    assign: assign,
    cellsToChars: cellsToChars
};

module.exports = XQ_ASSIGNMENT;
