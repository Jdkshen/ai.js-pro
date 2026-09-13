// @engine quickjs
/**
 * 阵营判定（红 / 黑）。
 *
 * 为什么需要它：模型只有 7 个兵种类别（车马相仕帅炮兵），**红黑是合并的** ——
 * 光靠「这一格是车」无法知道是红车还是黑车，也就出不了 FEN。
 * 但象棋盘上红方棋子是红字、黑方是黑字（实物棋盘同理：红子偏红、黑子偏黑），
 * 所以在棋子范围内数一下「红笔画像素 / 黑笔画像素」就能把阵营补回来，不必重训模型。
 *
 * 本模块是纯 JS：只接收已经采好的 RGB 采样点，不碰任何平台接口，可离线测试。
 */
"use strict";

const defaults = require("../util/defaults.js");

const SIDE_RED = "RED";
const SIDE_BLACK = "BLACK";
const SIDE_UNKNOWN = "UNKNOWN";

const DEFAULTS = {
    /** 采样框边长 = 格距 × 该比例（棋子落在交点上，84% 能框住棋子又不碰邻格） */
    sampleRatio: 0.84,
    /** 采样步长（像素）：真机上逐点取像素是要花时间的，3 足够稳定 */
    sampleStep: 3,
    /** 红笔画：红通道下限 */
    redMin: 110,
    /** 红笔画：红通道要比另外两个通道大多少倍 */
    redDominance: 1.35,
    /** 黑笔画：三通道都低于此值 */
    darkMax: 105,
    /** 红像素占比太低时不算红（木纹本身偏红，不能只看一点红） */
    minRedShare: 0.02,
    /** 红占比要压过暗占比多少倍才判红方 */
    ratio: 1.2
};

function max3(a, b, c) {
    return a > b ? (a > c ? a : c) : (b > c ? b : c);
}

function isRedPixel(r, g, b, options) {
    const o = options || DEFAULTS;
    return r > o.redMin && r > g * o.redDominance && r > b * o.redDominance;
}

function isDarkPixel(r, g, b, options) {
    const o = options || DEFAULTS;
    return max3(r, g, b) < o.darkMax;
}

/**
 * 由 RGB 采样点判阵营。
 * @param samples [[r,g,b], ...]
 * @returns { side, redShare, darkShare, counted, reason }
 */
function classify(samples, options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    let red = 0;
    let dark = 0;
    let counted = 0;
    for (let i = 0; i < (samples ? samples.length : 0); i++) {
        const s = samples[i];
        if (!s || !isFinite(s[0]) || !isFinite(s[1]) || !isFinite(s[2])) {
            continue;
        }
        counted++;
        if (isRedPixel(s[0], s[1], s[2], opts)) {
            red++;
        } else if (isDarkPixel(s[0], s[1], s[2], opts)) {
            dark++;
        }
    }
    if (counted === 0) {
        return { side: SIDE_UNKNOWN, redShare: 0, darkShare: 0, counted: 0, reason: "没有采到像素" };
    }
    const redShare = red / counted;
    const darkShare = dark / counted;
    let side = SIDE_UNKNOWN;
    let reason = "";
    if (redShare >= opts.minRedShare && redShare > darkShare * opts.ratio) {
        side = SIDE_RED;
    } else if (darkShare > 0 && redShare <= darkShare * opts.ratio) {
        side = SIDE_BLACK;
    } else {
        reason = "红/黑笔画都太少（红 " + redShare.toFixed(3) + " / 暗 " + darkShare.toFixed(3) + "）";
    }
    return { side: side, redShare: redShare, darkShare: darkShare, counted: counted, reason: reason };
}

/** 某一格（规范坐标）在屏幕上的采样框，边长按格距算。 */
function samplingBox(grid, col, row, options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const geometry = require("./geometry.js");
    const point = geometry.pointAt(grid, col, row);
    const step = Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY)) * opts.sampleRatio;
    const half = step / 2;
    return {
        x: Math.round(point.x - half),
        y: Math.round(point.y - half),
        width: Math.max(2, Math.round(step)),
        height: Math.max(2, Math.round(step))
    };
}

/** 在框内按 step 采样 RGB。sampler(x, y) 返回 [r,g,b] 或 null。 */
function sampleBox(sampler, box, options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const samples = [];
    for (let y = box.y; y < box.y + box.height; y += opts.sampleStep) {
        for (let x = box.x; x < box.x + box.width; x += opts.sampleStep) {
            const rgb = sampler(x, y);
            if (rgb) {
                samples.push(rgb);
            }
        }
    }
    return samples;
}

/** 直接对着某一格判阵营。 */
function classifyAt(sampler, grid, col, row, options) {
    const box = samplingBox(grid, col, row, options);
    const samples = sampleBox(sampler, box, options);
    const result = classify(samples, options);
    result.box = box;
    return result;
}

/**
 * 把模型给出的兵种字母套上阵营：
 * 大写 = 红方（模型类别是按红方字形命名的：车 R / 马 N / 相 B / 仕 A / 帅 K / 炮 C / 兵 P），
 * 判为黑方就转小写；阵营判不出来就返回 '?'（未知），**不猜**。
 */
function applySide(letter, side) {
    if (!letter) {
        return null;
    }
    const upper = String(letter).toUpperCase();
    if (side === SIDE_RED) {
        return upper;
    }
    if (side === SIDE_BLACK) {
        return upper.toLowerCase();
    }
    return "?";
}

function describeSide(side) {
    if (side === SIDE_RED) {
        return "红";
    }
    if (side === SIDE_BLACK) {
        return "黑";
    }
    return "未知";
}

module.exports = {
    SIDE_RED: SIDE_RED,
    SIDE_BLACK: SIDE_BLACK,
    SIDE_UNKNOWN: SIDE_UNKNOWN,
    DEFAULTS: DEFAULTS,
    isRedPixel: isRedPixel,
    isDarkPixel: isDarkPixel,
    classify: classify,
    samplingBox: samplingBox,
    sampleBox: sampleBox,
    classifyAt: classifyAt,
    applySide: applySide,
    describeSide: describeSide,
    max3: max3
};
