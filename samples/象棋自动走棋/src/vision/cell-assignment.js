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

/**
 * 两个候选是不是「同一个字」—— 只差红黑大小写（C/c 炮、K/k 将帅、P/p 兵卒…）。
 *
 * 真机实测（K40 + 天天象棋，2026-09-14）：③「等落子」被 **48 次「有 1 处关键冲突」** 卡死。
 * 根因就在下面那个 conflictGap 分支：模型把同一格的同一个字在**红黑之间抖动**
 * （日志实见 `(1,7)?→C`），两个候选分差小于阈值 → 判成「关键冲突」→ 验证器一票否决整帧，
 * 而抖动每帧都发生 → 永远等不到「无冲突」的那一帧 → 驱动只能一直重算。
 * 「同字异色」与「异字冲突」的可信度完全不同：前者連兵种都已经确定，缺的颜色恰好是
 * 下游「同字异色修正 + 棋规层」能靠基准盘面还原的信息（那条路已验证可用）；
 * 后者连兵种都是猜的，必须继续一票否决。
 */
function sameGlyph(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== 1 || b.length !== 1) {
        return false;
    }
    return a.toLowerCase() === b.toLowerCase();
}

const DEFAULTS = {
    minScore: 0.45,
    /**
     * 「弱证据」门槛：低于 minScore 但高于它的框**不当棋子**，但也**不当空格** ——
     * 那格标成未知格（“那里有东西，但我没把握是什么”）。
     *
     * 为什么必须这样（2026-09-14 真机事故）：模型把黑将检出来两次（0.42 / 0.26），
     * 而这里 minScore=0.45 把两个框都丢掉了 → 那一格变成**空格** → 整盘凭空少了黑将，
     * 而所有闸门（未知格/冲突/棋规数量）都拦不住（空格不是错）→ 驱动拿着一张
     * “没有将”的盘面去推轮次/算棋，永远卡住。人审绘制直接把两个 K 框画了出来。
     * 默认取 0.001 = 检测器那边的 confidence（2026-10-05 对齐基础示例口径「拉满召回」），两边口径必须一致。
     */
    weakScore: 0.001,
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
        cells[i] = { state: STATE_EMPTY, piece: null, score: 0, candidates: [], conflict: false, residual: 0, center: null };
    }
    const stats = {
        detected: 0, placed: 0, merged: 0,
        /** 异字冲突数（不同兵种争一格）= 一票否决，必须等新观测 */
        conflicts: 0,
        /** 同字异色冲突数（同一兵种只差红黑）= 不否决整帧，交给下游纠色/棋规还原 */
        sameGlyphConflicts: 0,
        /** 冲突格明细（最多记 8 个，供日志/人审）：{index, name, best, second, …} */
        conflictCells: [],
        unknown: 0, outOfBoard: 0, lowQuality: 0, weak: 0
    };
    const frameUsable = opts.frameQuality >= 0.5;

    for (let i = 0; i < (detections ? detections.length : 0); i++) {
        const det = detections[i];
        if (!det || !isFinite(det.x) || !isFinite(det.y)) {
            continue;
        }
        stats.detected++;
        const weak = det.score != null && det.score < opts.minScore;
        if (weak && (det.score == null || det.score < opts.weakScore)) {
            continue;   // 连弱证据都算不上 → 丢掉
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
            residual: hit.normalized,
            x: det.x,
            y: det.y,
            weak: weak
        });
        if (weak) {
            stats.weak++;
        }
    }

    cells.forEach(function (cell, cellIndex) {
        if (cell.candidates.length === 0) {
            // 没有框不等于「确定为空」：图像质量差时只能说「没看到」。
            cell.state = frameUsable ? STATE_EMPTY : STATE_UNKNOWN;
            if (cell.state === STATE_UNKNOWN) {
                stats.unknown++;
            }
            return;
        }
        // 「低分框」怎么算（真机事故 2026-09-14）：模型把黑将检出两次（0.42 / 0.26），
        // 而 minScore=0.45 把两个都丢掉 → 那格变成**空格** → 盘面凭空少一个将，
        // 而所有闸门（未知格/冲突/棋规数量）都拦不住「空格」→ 驱动拿着一盘没将的棋永远卡住。
        // 现在的规则：
        //   · 有强证据（≥ minScore）→ 照常判子；
        //   · 只有弱证据：**同标签 ≥2 个 = 一致证据**，可以当子（同一位置重复检出说明确实有东西）；
        //   · 其他弱证据 → 未知格（“有东西，但我没把握”），交给棋规/驱动去解释。
        const strong = cell.candidates.filter(function (candidate) { return !candidate.weak; });
        if (strong.length === 0) {
            const agree = {};
            let agreeLabel = null;
            cell.candidates.forEach(function (candidate) {
                agree[candidate.label] = (agree[candidate.label] || 0) + 1;
                if (agree[candidate.label] >= 2) {
                    agreeLabel = candidate.label;
                }
            });
            if (agreeLabel == null) {
                cell.state = STATE_UNKNOWN;
                cell.piece = null;
                cell.score = cell.candidates.reduce(function (best, candidate) {
                    return Math.max(best, candidate.score);
                }, 0);
                cell.conflict = false;
                stats.unknown++;
                return;
            }
        }
        // 同类合并：同一格同一类别的多个框只算一次，分数取最高，个数记在 count 上。
        const byLabel = {};
        const order = [];
        cell.candidates.forEach(function (candidate) {
            const entry = byLabel[candidate.label];
            if (!entry) {
                byLabel[candidate.label] = {
                    label: candidate.label, score: candidate.score, count: 1,
                    residual: candidate.residual, x: candidate.x, y: candidate.y
                };
                order.push(candidate.label);
                stats.merged++;
            } else {
                if (candidate.score > entry.score) {
                    entry.score = candidate.score;
                    entry.x = candidate.x;
                    entry.y = candidate.y;
                }
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
            stats.unknown++;
            const same = sameGlyph(best.label, second.label);
            if (same) {
                stats.sameGlyphConflicts++;
            } else {
                stats.conflicts++;
            }
            if (stats.conflictCells.length < 8) {
                stats.conflictCells.push({
                    index: cellIndex,
                    name: "(" + (cellIndex % 9) + "," + Math.floor(cellIndex / 9) + ")",
                    best: best.label,
                    bestScore: Number(best.score.toFixed(3)),
                    second: second.label,
                    secondScore: Number(second.score.toFixed(3)),
                    sameGlyph: same
                });
            }
            return;
        }
        cell.state = STATE_PIECE;
        cell.piece = best.label;
        cell.score = best.score;
        // 检测框中心（屏幕逻辑像素）：点击用它比用「网格交点」准 —— 定位给的原点/格距
        // 有系统性偏差（K40 + 天天象棋实测 rank0 差 10px、rank7 差 27px），
        // 识别靠 0.84 格距的采样框抗得住，游戏的命中判定却抗不住。
        cell.center = (isFinite(best.x) && isFinite(best.y)) ? { x: best.x, y: best.y } : null;
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
