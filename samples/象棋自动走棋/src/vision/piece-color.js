// @engine quickjs
/**
 * 红黑颜色分类器（重构方案 §3.6）：**独立于 OCR**，只回答「这枚子是红是黑」。
 *
 * 为什么不跟字形混（方案 §3.6 原文）：字形模型同时学红黑就要 14 类数据、还容易过拟合皮肤；
 * 而且天天象棋里**黑炮也写成「炮」**（K40 实测），字形根本分不出颜色 —— 颜色只能靠**像素**。
 *
 * 特征（方案 §3.6）：
 *   { redRatio, darkRatio, meanHue, meanSaturation, meanLabA, sampleCount }
 * 判定：
 *   · 红比例高 + Lab a* 明显偏正        → RED
 *   · 低饱和、低亮度的文字像素占比高     → BLACK
 *   · 有效采样 < 40 像素                → UNKNOWN
 *   · 两种得分差 < 0.18                 → UNKNOWN
 *   · 同一个交点单帧读反：**保留上一帧结果**并记冲突，不直接换阵营（两帧投票）
 *
 * 纯 JS：只吃 RGB 采样点，离线可测（真机由 recognizer/adapter 负责取点）。
 */
"use strict";

const pieceSide = require("./piece-side.js");

const SIDE_RED = "RED";
const SIDE_BLACK = "BLACK";
const SIDE_UNKNOWN = "UNKNOWN";

const DEFAULTS = {
    /** 有效采样下限（方案 §3.6：<40 像素判 UNKNOWN） */
    minSamples: 40,
    /** 两种得分差下限（方案 §3.6：<0.18 判 UNKNOWN） */
    minScoreGap: 0.18,
    /** Lab a* 偏正的门槛（越大越红） */
    labARed: 8,
    /** 红像素占比门槛 */
    redRatioRed: 0.06,
    /** 暗像素占比门槛 */
    darkRatioBlack: 0.10,
    /**
     * 交给 piece-side 的**像素判定阈值**（redMin/redDominance/darkMax…）。
     * ⚠️ 2026-09-19 真机事故：不显式传这个，piece-side 会在自己的 opts 里读不到
     *    `redMin`/`darkMax` ⇒ `r > undefined`、`max3 < undefined` **恒为 false**，
     *    于是 redRatio/darkRatio **永远是 0**（红方靠 a* 勉强过得去，黑方直接没特征）。
     */
    sideOptions: null,
    /**
     * 「单帧翻面保留上一帧」最多容忍多少帧：超过就采纳新颜色。
     * ⚠️ 2026-09-20 真机事故：没有这个上限时，一旦某格被读反（落子动画/高亮环那一瞬），
     *    以后每一帧正确的读数都被当成「单帧翻面」而保留旧结论 ⇒ **整格颜色被永久钉死**。
     *    真机症状：驱动反复报「轮次推不出来」（原始读数里 (2,3) 红炮一直是 c、(4,6) 黑炮一直是 C），
     *    而面板的全盘 OCR（board-scan，同样用找色）每轮都在把它改回来 → 两套读数互相打架。
     */
    flipFrames: 3
};

// ---------------------------------------------------------------- 颜色空间

function rgbToHsv(r, g, b) {
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const d = max - min;
    let h = 0;
    if (d !== 0) {
        if (max === r) {
            h = 60 * (((g - b) / d) % 6);
        } else if (max === g) {
            h = 60 * ((b - r) / d + 2);
        } else {
            h = 60 * ((r - g) / d + 4);
        }
    }
    if (h < 0) {
        h += 360;
    }
    return { h: h, s: max === 0 ? 0 : d / max, v: max / 255 };
}

/** sRGB → CIE Lab（只算 a*：红-绿轴，方案要的就是它） */
function rgbToLabA(r, g, b) {
    const toLinear = function (v) {
        const c = v / 255;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    };
    const R = toLinear(r), G = toLinear(g), B = toLinear(b);
    let x = (R * 0.4124 + G * 0.3576 + B * 0.1805) / 0.95047;
    let y = (R * 0.2126 + G * 0.7152 + B * 0.0722) / 1.0;
    let z = (R * 0.0193 + G * 0.1192 + B * 0.9505) / 1.08883;
    const f = function (t) {
        return t > 0.008856 ? Math.cbrt(t) : (7.787 * t + 16 / 116);
    };
    const fx = f(x), fy = f(y), fz = f(z);
    return 500 * (fx - fy);   // a*
}

// ---------------------------------------------------------------- 特征与判定

/**
 * 由 RGB 采样点算特征（方案 §3.6 的特征表）。
 * @param samples [[r,g,b], ...]
 */
function features(samples, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    // 像素级判定必须用 piece-side 的键名（redMin/redDominance/darkMax），否则恒为 false
    const sideOpts = Object.assign({}, pieceSide.DEFAULTS, opts.sideOptions || {});
    let red = 0, dark = 0, counted = 0, hueSum = 0, satSum = 0, labSum = 0, labCounted = 0;
    for (let i = 0; i < (samples ? samples.length : 0); i++) {
        const s = samples[i];
        if (!s || !isFinite(s[0]) || !isFinite(s[1]) || !isFinite(s[2])) {
            continue;
        }
        counted += 1;
        const r = Number(s[0]), g = Number(s[1]), b = Number(s[2]);
        if (pieceSide.isRedPixel(r, g, b, sideOpts)) {
            red += 1;
        } else if (pieceSide.isDarkPixel(r, g, b, sideOpts)) {
            dark += 1;
        }
        const hsv = rgbToHsv(r, g, b);
        hueSum += hsv.h;
        satSum += hsv.s;
        // a* 只在「饱和度够」的像素上平均：木纹/高光会把 a* 拉乱
        if (hsv.s >= 0.25) {
            labSum += rgbToLabA(r, g, b);
            labCounted += 1;
        }
    }
    if (counted === 0) {
        return {
            redRatio: 0, darkRatio: 0, meanHue: 0, meanSaturation: 0, meanLabA: 0,
            sampleCount: 0
        };
    }
    return {
        redRatio: red / counted,
        darkRatio: dark / counted,
        meanHue: hueSum / counted,
        meanSaturation: satSum / counted,
        meanLabA: labCounted > 0 ? labSum / labCounted : 0,
        sampleCount: counted
    };
}

/**
 * 特征 → { side, confidence, redScore, blackScore, reason, features }
 * 纯函数：给同一组采样点总是同一个结论（两帧投票在 createVoter 里）。
 */
function classifySamples(samples, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const f = features(samples, opts);
    if (f.sampleCount < opts.minSamples) {
        return {
            side: SIDE_UNKNOWN, confidence: 0, redScore: 0, blackScore: 0,
            reason: "有效采样 " + f.sampleCount + " 个（<" + opts.minSamples + "）", features: f
        };
    }
    // 红：红像素占比 + a* 偏正（各占一半权重，都归一化到 0~1）
    const redFromRatio = Math.min(1, f.redRatio / Math.max(0.0001, opts.redRatioRed));
    const redFromLab = Math.min(1, Math.max(0, (f.meanLabA - opts.labARed * 0.5) / (opts.labARed * 1.5)));
    const redScore = redFromRatio * 0.5 + redFromLab * 0.5;
    // 黑：暗像素占比 + 低饱和
    const blackFromDark = Math.min(1, f.darkRatio / Math.max(0.0001, opts.darkRatioBlack));
    const blackFromSat = Math.min(1, Math.max(0, (0.45 - f.meanSaturation)) / 0.45);
    const blackScore = blackFromDark * 0.6 + blackFromSat * 0.4;

    if (Math.abs(redScore - blackScore) < opts.minScoreGap) {
        return {
            side: SIDE_UNKNOWN, confidence: Math.abs(redScore - blackScore),
            redScore: redScore, blackScore: blackScore,
            reason: "红黑得分差 " + Math.abs(redScore - blackScore).toFixed(2) +
                "（<" + opts.minScoreGap + "）", features: f
        };
    }
    const red = redScore > blackScore;
    return {
        side: red ? SIDE_RED : SIDE_BLACK,
        confidence: Math.abs(redScore - blackScore),
        redScore: redScore,
        blackScore: blackScore,
        reason: "",
        features: f
    };
}

/**
 * 两帧投票器（方案 §3.6）：同一个交点单帧读反时**保留上一帧**并记冲突，不直接换阵营。
 * 用法：每帧对同一交点调 `vote(index, samples)`，拿它返回的 side。
 *
 * `options.classify(payload)`：可换判据（`ink-color.js` 的找色版就用它复用这套多帧策略）。
 * 不传就是本模块的 `classifySamples` —— 采样点数组的老行为，一点没变。
 *
 * ⚠️ 防“永久钉死”：连续 `flipFrames` 帧都与上一帧相反 ⇒ 认定这格真的换了颜色，采纳新结论。
 *    没有这条时，一次误读（落子动画、高亮环）会把那格颜色钉到最后（真机 2026-09-20 踩到）。
 */
function createVoter(options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const classify = typeof opts.classify === "function"
        ? opts.classify
        : function (payload) { return classifySamples(payload, opts); };
    const limit = Math.max(1, Number(opts.flipFrames) || DEFAULTS.flipFrames);
    const last = {};
    const pending = {};
    const conflicts = [];
    const flips = [];
    let votes = 0;

    function vote(index, samples) {
        votes += 1;
        const now = classify(samples, opts);
        const prev = last[index];
        if (now.side === SIDE_UNKNOWN) {
            return now;                       // 这一帧看不清 ⇒ 不覆盖上一帧的结论
        }
        if (prev && prev.side !== SIDE_UNKNOWN && prev.side !== now.side) {
            const waiting = pending[index];
            const count = (waiting && waiting.side === now.side ? waiting.count : 0) + 1;
            pending[index] = { side: now.side, count: count };
            if (count < limit) {
                // 单帧翻面：保留上一帧，记冲突（方案要求：不直接换阵营）
                conflicts.push({ index: index, was: prev.side, now: now.side, confidence: now.confidence, count: count });
                return {
                    side: prev.side, confidence: prev.confidence, kept: true, pending: count,
                    redScore: now.redScore, blackScore: now.blackScore,
                    reason: "单帧翻面（" + prev.side + "→" + now.side + "，第 " + count + "/" + limit + " 帧），保留上一帧",
                    features: now.features
                };
            }
            // 连续 limit 帧都读反 ⇒ 不是噪声，采纳新颜色（例如格子真的换了子）
            flips.push({ index: index, was: prev.side, now: now.side, frames: count });
            delete pending[index];
            last[index] = { side: now.side, confidence: now.confidence };
            return Object.assign({}, now, {
                swapped: true,
                reason: "连续 " + count + " 帧读反 ⇒ 采纳新颜色（" + prev.side + "→" + now.side + "）"
            });
        }
        delete pending[index];
        last[index] = { side: now.side, confidence: now.confidence };
        return now;
    }

    function stats() {
        return { votes: votes, conflicts: conflicts.length, flips: flips.length, last: Object.keys(last).length };
    }

    function reset() {
        for (const key in last) {
            delete last[key];
        }
        for (const key in pending) {
            delete pending[key];
        }
        conflicts.length = 0;
        flips.length = 0;
        votes = 0;
    }

    return { vote: vote, stats: stats, reset: reset, conflicts: conflicts, flips: flips };
}

module.exports = {
    features: features,
    classifySamples: classifySamples,
    createVoter: createVoter,
    rgbToHsv: rgbToHsv,
    rgbToLabA: rgbToLabA,
    SIDE_RED: SIDE_RED,
    SIDE_BLACK: SIDE_BLACK,
    SIDE_UNKNOWN: SIDE_UNKNOWN,
    DEFAULTS: DEFAULTS
};
