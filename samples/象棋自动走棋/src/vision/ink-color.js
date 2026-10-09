// @engine quickjs
/**
 * 红黑阵营（**找色版**）：用引擎自带的**找色**在 **YOLO 定位框**里数红/黑墨迹，数不出来就留 UNKNOWN。
 *
 * ## 为什么用找色（用户口径 2026-09-20）
 *   · 新引擎本来就带找色：`images.findColor / findColorInRegion / findColorEquals / findMultiColors /
 *     findAllPointsForColor`（示例见 `sample/图色处理/02-截图找色与多点找色.js`、`10-图色查找补充接口.js`），
 *     并且**原生实现**（`native_frame_store.cpp` 的 `findColor/findAllPointsForColor`）—— 扫描在 C++ 里做，
 *     JS 只收结果，比逐点 `images.pixel` 快得多、也不占 JS 循环。
 *   · 红黑本来就是「墨迹颜色」问题：红方棋子红字、黑方棋子黑字（天天象棋两套皮肤都这样）。
 *     在框内数「像红字的像素 / 像黑字的像素」比平均饱和度那类统计量直观得多，也好标定。
 *
 * ## 分工（不可越界）
 *   · **YOLO**：只回答「哪一格有子」→ 框决定找色/采样的**位置**（框缺失才退格心）。
 *   · **OCR**：只回答「兵种」（车马相仕帅炮兵）。
 *   · **本模块**：只回答「红还是黑」。
 *   拿不准一律 `UNKNOWN`，让上层保留上一帧结论或让那格当空位，**不许猜颜色**。
 *
 * ## 两条证据通道
 *   ① **找色**（宿主给了 `countColor` 时）：在中心区里数与参考色相近的像素个数（逐通道容差，语义同
 *      `images.findColor` 的 threshold），按**密度**门槛判（`judgeCounts`）；
 *   ② **兜底**（没有找色的宿主：Rhino / 离线夹具）：按同一比例区采样 RGB，交给 `piece-color` 的
 *      老分类器（它已经在真机上调过、也有自检守着）。两边的 `side/confidence` 字段同形，
 *      上层（board-scan / 流水线 / 投票器）不需要分支。
 *   两条通道都写 `source`，真机日志能一眼看出这帧是谁判的。
 *
 * 纯逻辑部分（`judgeCounts`）离线可测；取像素/找色由宿主注入。
 */
"use strict";

const cropper = require("./piece-cropper.js");
const pieceColor = require("./piece-color.js");

const SIDE_RED = "RED";
const SIDE_BLACK = "BLACK";
const SIDE_UNKNOWN = "UNKNOWN";

const DEFAULTS = {
    /**
     * 红方墨迹参考色：K40 真机帧（天天象棋）红字核心 ≈ rgb(131,65,45)；
     * 木纹/圆盘边是 (208,160,112) 一类的暖棕，离这个参考色 ≥ 60，容差 24 时不会被数进来。
     */
    red: "#83412D",
    /** 黑方墨迹参考色：真机黑字 ≈ rgb(40,40,48)（近中性黑，带一点蓝）。 */
    black: "#282830",
    /** 找色容差（每通道，语义同 `images.findColor` 的 threshold）。 */
    threshold: 24,
    /**
     * 「暗墨迹」第三路（2026-09-21 真机 d7 黑士漏读案）：细笔画黑子（士/炮/象）在找色口径下
     * 密度只有 5.4~8%（压在 6% 判空线上，裁剪中心再偏 ~28px 就掉到 5.8%），而按**暗像素**
     * 数它们有 8.3~13.2%（空格 0%、红子 0%、星光标记 0%）—— 判「有没有子」时作为红/黑墨迹的
     * **或**证。参数是真机帧标定的（ref #303030、容差 40）。
     */
    dark: "#303030",
    darkThreshold: 40,
    /** 找色/采样用的中心区比例（与老路径一致：框内 0.72，避开圆盘边与高亮环）。 */
    colourRatio: 0.72,
    /** 判色门槛：主证墨迹**密度**（命中数 / 扫描面积）下限。真机红字 ≈0.08~0.16、黑字 ≈0.13。 */
    minDensity: 0.02,
    /** 判 A 方要求 A 的密度至少是 B 的这么多倍（互相压不倒 ⇒ UNKNOWN）。 */
    dominance: 1.5,
    /** 采样步长（像素）——只有**兜底通道**（没有找色的宿主）才用到 */
    fallbackStep: 3,    /** 「判空」口径（`looksEmpty`）：红/黑墨迹与暗墨迹**都**低于门槛才算空 */
    emptyColorMinDensity: 0.06,
    emptyDarkMinDensity: 0.04,    /** 传给 `piece-color` 老分类器的参数（仅兜底通道用）。 */
    colorOptions: null
};

/** "#RRGGBB" / [r,g,b] / 0xRRGGBB → [r,g,b]；认不出返回 null。 */
function parseRgb(color) {
    if (color == null) {
        return null;
    }
    if (Array.isArray(color)) {
        const r = Number(color[0]), g = Number(color[1]), b = Number(color[2]);
        return [r, g, b].every(isFinite) ? [r, g, b] : null;
    }
    if (typeof color === "number") {
        return [(color >> 16) & 255, (color >> 8) & 255, color & 255];
    }
    const text = String(color).trim().replace(/^#/, "").replace(/^0x/i, "");
    if (text.length !== 6 || /[^0-9a-fA-F]/.test(text)) {
        return null;
    }
    return [
        parseInt(text.substring(0, 2), 16),
        parseInt(text.substring(2, 4), 16),
        parseInt(text.substring(4, 6), 16)
    ];
}

function nearChannel(rgb, ref, threshold) {
    return Math.abs(rgb[0] - ref[0]) <= threshold &&
        Math.abs(rgb[1] - ref[1]) <= threshold &&
        Math.abs(rgb[2] - ref[2]) <= threshold;
}

/**
 * 找色数墨迹（**只用找色通道**）。
 *
 * @return { red, black, area, source, region, ms }
 *   `source`："findColor" = 宿主支持找色且拿到了数；其它 = 没这条通道（调用方走兜底）。
 *   `area` = 区域像素数，`red/black` = 命中像素数，两者相除就是密度。
 */
function countsFor(host, image, box, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const out = { red: 0, black: 0, dark: 0, area: 0, source: "none", region: null, ms: 0 };
    if (!box) {
        return out;
    }
    const region = cropper.centreRegion(box, opts.colourRatio);
    if (!region) {
        return out;
    }
    out.region = region;
    if (!host || typeof host.countColor !== "function") {
        return out;
    }
    const started = Date.now();
    const red = host.countColor(image, opts.red, { region: region, threshold: opts.threshold });
    const black = host.countColor(image, opts.black, { region: region, threshold: opts.threshold });
    if (red == null || black == null) {
        return out;
    }
    // 暗墨迹（第三路）：宿主不认这个参考色/拿不到计数就记 0 —— 判空自动退化成旧口径，不会更差。
    const dark = host.countColor(image, opts.dark, { region: region, threshold: opts.darkThreshold });
    out.red = Number(red) || 0;
    out.black = Number(black) || 0;
    out.dark = dark == null ? 0 : (Number(dark) || 0);
    out.area = Math.max(1, region.width * region.height);
    out.source = "findColor";
    out.ms = Date.now() - started;
    return out;
}

/**
 * 计数 → 阵营（纯函数）。
 *
 * 规则（真机实测的分离度很大，阈值留了余量）：
 *   红字格：红密度 0.08~0.16、黑密度 ≈0；黑字格：黑密度 ≈0.13、红密度 ≤0.01；空格：两边都 ≈0。
 *   ⇒ 主证密度 ≥ `minDensity` 且压过对方 `dominance` 倍才算，否则 UNKNOWN。
 */
function judgeCounts(counts, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const area = counts && counts.area ? Number(counts.area) : 0;
    const red = counts ? Number(counts.red) || 0 : 0;
    const black = counts ? Number(counts.black) || 0 : 0;
    const redDensity = area > 0 ? red / area : 0;
    const blackDensity = area > 0 ? black / area : 0;
    const redScore = Math.min(1, redDensity / Math.max(1e-6, opts.minDensity));
    const blackScore = Math.min(1, blackDensity / Math.max(1e-6, opts.minDensity));
    const features = {
        red: red, black: black, area: area,
        redDensity: redDensity, blackDensity: blackDensity,
        source: counts ? counts.source : "none"
    };
    const gap = Math.abs(redScore - blackScore);
    if (area === 0) {
        return {
            side: SIDE_UNKNOWN, confidence: 0, redScore: 0, blackScore: 0,
            reason: "没有可扫描的中心区（找色与采样都拿不到）", features: features,
            counts: counts || null
        };
    }
    const redOk = redDensity >= opts.minDensity && redDensity >= blackDensity * opts.dominance;
    const blackOk = blackDensity >= opts.minDensity && blackDensity >= redDensity * opts.dominance;
    if (redOk === blackOk) {   // 两边都不够 / 两边都够（互相压不倒）⇒ 不猜
        return {
            side: SIDE_UNKNOWN, confidence: gap, redScore: redScore, blackScore: blackScore,
            reason: "墨迹分不开（红 " + redDensity.toFixed(3) + " / 黑 " + blackDensity.toFixed(3) +
                "，门槛 " + opts.minDensity + "×" + opts.dominance + "）",
            features: features, counts: counts || null
        };
    }
    return {
        side: redOk ? SIDE_RED : SIDE_BLACK,
        confidence: gap,
        redScore: redScore,
        blackScore: blackScore,
        reason: "",
        features: features,
        counts: counts || null
    };
}

/**
 * 证据载荷 → 阵营判定。
 * 载荷是**计数对象**（找色/采样计数）就按计数判；是**采样点数组**就交给 `piece-color` 老分类器。
 * 这样多帧投票器（`piece-color.createVoter`）两条通道都能复用同一套回滚策略。
 */
function judgePayload(payload, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    if (Array.isArray(payload)) {
        const verdict = pieceColor.classifySamples(payload, opts.colorOptions);
        return Object.assign({}, verdict, { source: "classifier" });
    }
    return judgeCounts(payload, opts);
}

/** 取「这一格的证据载荷」：有找色就是计数对象，没有就是采样数组（交给老分类器）。 */
function evidenceFor(host, image, box, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const counts = countsFor(host, image, box, opts);
    if (counts.source === "findColor") {
        return counts;
    }
    if (!host || typeof host.rgbAt !== "function") {
        return [];   // 什么都没有：老分类器会给出「没有采到像素」
    }
    return cropper.sampleColors(host, image, box, {
        colorRatio: opts.colourRatio, colorStep: opts.fallbackStep
    });
}

/** 一步到位：取证据 + 判定（面板/board-scan 用）。 */
function judgeFor(host, image, box, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const payload = evidenceFor(host, image, box, opts);
    return judgePayload(payload, opts);
}

/** 与 `piece-color.createVoter` 同一套多帧策略（单帧翻面保留上一帧），只是换了判据。 */
function createVoter(options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    return pieceColor.createVoter(Object.assign({}, opts.colorOptions || {}, {
        classify: function (payload) {
            return judgePayload(payload, opts);
        }
    }));
}

/**
 * 「这格该判空吗」的统一口径（2026-09-21，d7 黑士漏读案落地）。
 *
 * 判据 =「红/黑墨迹密度低于门槛」**且**「暗墨迹密度也低于门槛」才判空 —— 两路证据是**或**的关系。
 * 为什么不能只看红/黑：细笔画黑子（士/炮/象）的找色密度 5.4~8%，本就贴着 6% 线，
 * 裁剪中心再偏 ~28px（网格偏、空框格只能按交点裁）就掉到 5.8% ⇒ 被误判为空。
 * 而暗像素口径同格是 8.3~13.2%（空格 0%、星光标记 0%）—— 双证后这类「擦线误判」消失。
 */
function looksEmpty(ink, options) {
    if (!ink) {
        return false;
    }
    const opts = Object.assign({}, DEFAULTS, options || {});
    const area = Math.max(1, Number(ink.area) || 0);
    const colour = (Number(ink.red) || 0) + (Number(ink.black) || 0);
    if (colour > Math.max(5, Math.round(area * opts.emptyColorMinDensity))) {
        return false;
    }
    const dark = Number(ink.dark) || 0;
    return dark <= Math.max(5, Math.round(area * opts.emptyDarkMinDensity));
}

module.exports = {
    countsFor: countsFor,
    looksEmpty: looksEmpty,
    judgeCounts: judgeCounts,
    judgePayload: judgePayload,
    evidenceFor: evidenceFor,
    judgeFor: judgeFor,
    createVoter: createVoter,
    parseRgb: parseRgb,
    SIDE_RED: SIDE_RED,
    SIDE_BLACK: SIDE_BLACK,
    SIDE_UNKNOWN: SIDE_UNKNOWN,
    DEFAULTS: DEFAULTS
};
