// @engine quickjs
/**
 * 全屏周期格线探测器（多证据融合方案 §4.2 / §4.3 的最小落地，2026-09-22）。
 *
 * 与旧两条路（`board-locator.js` 的逐点支撑度 / 暗线投影）的区别：
 *   · 响应函数用**引擎原生找色**（`countColor` → `images.findAllPointsForColor`，C++ 实现）
 *     逐条带数「棋盘线色」像素 —— 旧路是 JS 逐点 `images.pixel`，真机一次尝试 ~0.5s；
 *     找色单次 ~0.2ms（board-scan 实测「90 格共 19ms」），全屏扫描才有可行性。
 *   · **梳状（comb）评分**：一次同时评 9 条竖线（10 条横线）的位置，origin/step 全搜，
 *     用 trimmed 求和丢掉最弱的几条线 —— 棋子盖掉 2 条线也照样打分。
 *     旧路的「线条数必须 9/10」会因为遮挡直接判失败（720p 上偏 4% 就报「竖线只匹配 6/9」）。
 *   · **「存在的线」加成 + 遮挡感知门槛（2026-09-24 真机回放修复）**：trimmed 求和会把
 *     「一条线空掉（0.00）」和「一条线被棋子压住（0.5+）」同分 → 平移一格的梳子与真梳
 *     三连平局（靠扫描顺序瞎选，实测四张真实截图里两张因此偏了一整格）。现用「存在的线」
 *     条数加成（presenceCount）让真梳胜出；命中门槛用 0.2×前 3 强均值而不是 0.35 ——
 *     起始局面外侧两行整行摆满棋子，那两条线只剩 ~22% 支撑度，0.35 会把它们全判缺失。
 *
 * 几何先验（实测，不是猜的）：
 *   · 棋盘**水平居中**（K40 1080：竖线起点 82；格距 115.9 ≈ 1080×0.1073）；
 *   · 格距 ≈ 屏幕短边 × 0.107（720p ≈ 77）；
 *   · 线色是暖棕：两套皮肤实测 (187,158,127) / (182,149,114) / (165,135,105)，
 *     木纹背景 (235,212,174) 与它们各通道差 ≥ 45 ⇒ **多参考色 cast** + 每通道容差可分辨。
 *
 * 本模块**只做几何**：不认棋子、不判颜色、不 OCR（方案 §3 核心原则）。
 * 找色能力与图像尺寸由宿主注入（见 `aijs-adapter.js` 的 countColor / sizeOf）。
 *
 * 离线可测：`combFit` 是纯函数（合成响应即可断言）；`detect` 可用假宿主整体测。
 */
"use strict";

const geometry = require("./geometry.js");

const COLS = 9;    // 竖线数量（9 列交点 → 9 条竖线）
const ROWS = 10;   // 横线数量（10 行交点 → 10 条横线）

const DEFAULTS = {
    /** 棋盘线参考色 cast（threshold = 每通道容差，语义同 `images.findColor`）。 */
    refs: [
        { color: "#A58769", threshold: 50 },
        { color: "#BB9E7F", threshold: 35 },
        { color: "#AF8C69", threshold: 40 }
    ],
    /** 条带扫描步长（像素）：决定响应数组的分辨率（越细越准、越慢）。 */
    scanStep: 4,
    /** 缩窄屏幕时按宽度缩小找色采样间距，避免 720p 画面整行错位。 */
    minVerticalMatched: 8,
    minHorizontalMatched: 9,
    /**
     * matched 门槛（相对前 3 强线均值）：支持度低于它的线不算「命中的线」。
     * ⚠️ 0.35 → 0.2（2026-09-24 真机回放）：起始局面首末两行整行摆满棋子，
     * 最外两条横线只剩 ~22% 支撑度（0.5~0.9 vs 内部 2~2.8），0.35 会把棋盘线全在的
     * 四张真实截图误判成 8/10 直接拒绝；而「空槽」（梳子滑出棋盘）只有 0~0.04，
     * 0.2 落在两个量级中间。
     */
    matchedRatio: 0.2,
    /**
     * 单条线的「绝对最小支持度」：低于它的位置一律不算命中/存在。
     * ⚠️ 纯相对口径在全噪声图上会翻车（2026-09-24 实测「回到应用」文件列表截图：
     * 全线 0.01~0.02 → 前 3 强均值 0.02 → 相对门槛 0.004 → 10/10「全命中」→ 误判为棋盘）。
     * 真机实测：空槽 ≤0.04、被棋子压住的线 0.52+ —— 0.15 落在两个量级中间。
     */
    minLineSupport: 0.15,
    /**
     * 「存在的线」门槛（相对最强线）与加成（每条）—— 破解「±1 格距平移梳」平局：
     * trimmed 求和把「一条线空掉（0.00）」与「一条线被棋子压住（0.5+）」同分，
     * 平移到棋盘外的梳子与真梳完全打平（实测锚点 -1/0/+1 三连平局）。
     * 真梳比平移梳多 1~2 条「存在的线」（空槽 0~0.04 永远不达标），靠这个加成胜出。
     */
    presenceRatio: 0.15,
    presenceBonus: 0.05,
    /** 条带半宽：2*half+1 列（行）合起来数色，抗 1px 抖动/抗锯齿。 */
    stripHalf: 1,
    /** 扫描带（占屏幕比例）：横 0~100%，纵 12%~92%（排除状态栏/底部手势带）。 */
    xBand: [0, 1],
    yBand: [0.12, 0.92],
    /** 格距先验：屏幕短边 × [min, max]（K40 115.9=0.1073×1080；720p 77=0.107×720）。 */
    stepRatioMin: 0.07,
    stepRatioMax: 0.14,
    /** 梳状评分：取样窗口（±像素）与丢弃条数（trimmed）。 */
    sampleWindow: 4,
    dropCount: 2,
    /**
     * 强线加成（打破「±1 条平移梳」的精确平局，2026-09-22 调试发现）：
     * 平移一条线的梳子会把一条线「空」出来，而 trimmed 求和与「被棋子盖住」完全同分
     * ——调试实测：(82,115) 与 (-33,115) 得分一模一样，谁先被扫到谁是「最优」。
     * 加上「强线（≥ 最强线 × strongRatio）每条 +strongBonus」后，真梳（强线多一条）胜出。
     */
    strongRatio: 0.3,
    strongBonus: 0.05,
    /** 粗扫 origin 步长（像素）与细修。
     *  ⚠️ 真机 2026-09-22：originStep=2 + stepInc=0.5 时拟合要 1.5~1.7s（16k 次候选），
     *  而粗扫只负责「找到大致位置」——精度由细修（±originStep 的插值细扫 + 平顶中心化）
     *  拉回亚像素。放到 6px / 1.0px 后评估数降一个量级。 */
    originStep: 6,
    coarseStepInc: 1.0,
    refineInc: 0.5,
    /** 方形性：两轴格距差超它就不接受（棋盘格子是方的）。 */
    maxSquareness: 0.08,
    /** 证据里保留的候选数。 */
    topK: 5
};

/** 位置 → 响应值（取最近样本；用于梳状评分的窗口取样）。 */
function responseAt(resp, position) {
    const idx = Math.round((position - resp.from) / resp.step);
    if (idx < 0 || idx >= resp.values.length) {
        return 0;
    }
    return resp.values[idx];
}

/** 位置 → 响应值（线性插值；用于线中心质心估计——粗分辨率下也能回到亚采样精度）。 */
function lerpAt(resp, position) {
    const t = (position - resp.from) / resp.step;
    const last = resp.values.length - 1;
    if (t <= 0) {
        return resp.values[0] || 0;
    }
    if (t >= last) {
        return resp.values[last] || 0;
    }
    const i = Math.floor(t);
    const f = t - i;
    return resp.values[i] * (1 - f) + resp.values[i + 1] * f;
}

/** 取样窗口内的最大值（把「预测线位置」与「响应分辨率」解耦）。 */
function windowMax(resp, position, winPx) {
    let best = 0;
    for (let d = -winPx; d <= winPx; d += resp.step) {
        const value = responseAt(resp, position + d);
        if (value > best) {
            best = value;
        }
    }
    return best;
}

/** trimmed 累加器：sum 里滚动丢掉 drop 个最小值（被棋子盖住/装饰压线的线）。 */
function accumulator(drop) {
    let sum = 0;
    const lows = [];
    return {
        push: function (value) {
            sum += value;
            if (drop > 0) {
                let at = lows.length;
                while (at > 0 && lows[at - 1] > value) {
                    at--;
                }
                if (at < drop) {
                    lows.splice(at, 0, value);
                    if (lows.length > drop) {
                        lows.pop();
                    }
                }
            }
        },
        score: function () {
            let trimmed = 0;
            for (let i = 0; i < lows.length; i++) {
                trimmed += lows[i];
            }
            return sum - trimmed;
        },
        total: function () {
            return sum;
        }
    };
}

/** 强线计数（≥ 最强线 × strongRatio 的线）——用于打破平移梳的同分平局。 */
function strongCount(values, options) {
    let maxValue = 0;
    for (let i = 0; i < values.length; i++) {
        if (values[i] > maxValue) {
            maxValue = values[i];
        }
    }
    const floor = maxValue * (options.strongRatio == null ? DEFAULTS.strongRatio : options.strongRatio);
    let strong = 0;
    for (let i = 0; i < values.length; i++) {
        if (values[i] >= floor) {
            strong += 1;
        }
    }
    return strong;
}

/**
 * 「存在的线」计数：值 ≥ max(最强线 × presenceRatio, 绝对下限)。
 * 区分「空槽」（梳子滑到棋盘外，只有 0~0.04 的背景噪声）与「被棋子压住的线」（0.5+）。
 */
function presenceCount(values, options) {
    const opts = options || {};
    let maxValue = 0;
    for (let i = 0; i < values.length; i++) {
        if (values[i] > maxValue) {
            maxValue = values[i];
        }
    }
    const ratio = opts.presenceRatio == null ? DEFAULTS.presenceRatio : opts.presenceRatio;
    const absMin = opts.minLineSupport == null ? DEFAULTS.minLineSupport : opts.minLineSupport;
    const floor = Math.max(maxValue * ratio, absMin);
    let present = 0;
    for (let i = 0; i < values.length; i++) {
        if (values[i] >= floor) {
            present += 1;
        }
    }
    return present;
}

/**
 * matched 口径：支持度 ≥ max(前 3 强均值 × matchedRatio, 绝对下限) 的线数。
 * ⚠️ 不能拿「最强那条线」当基准：采样相位不同，同一把梳子里真线的值会 "1/2/3" 交替，
 *    用最强线做分母会把正常的线误判成缺失（调试实测：真线全在，matched 只有 6）。
 * ⚠️ 也不能纯相对：全噪声图（所有线 0.01~0.02）相对门槛会降到 0.004，全部「命中」。
 */
function matchedCount(perLine, options) {
    const opts = options || {};
    const ratio = opts.matchedRatio == null ? DEFAULTS.matchedRatio : opts.matchedRatio;
    const absMin = opts.minLineSupport == null ? DEFAULTS.minLineSupport : opts.minLineSupport;
    const sorted = perLine.slice().sort(function (a, b) { return b - a; });
    const topN = Math.max(1, Math.min(3, sorted.length));
    let topSum = 0;
    for (let i = 0; i < topN; i++) {
        topSum += sorted[i];
    }
    const floor = Math.max((topSum / topN) * ratio, absMin);
    let matched = 0;
    for (let i = 0; i < perLine.length; i++) {
        if (perLine[i] >= floor) {
            matched += 1;
        }
    }
    return matched;
}

/**
 * 一次梳状评分：在 origin 起点放 count 条间距 step 的线，**窗口取样**后 trimmed 求和
 * （再加强线加成，见 DEFAULTS 说明）。
 * @return { score, sum, perLine, strong }（perLine = 每条线位置的响应值，供证据/日志）
 */
function combScore(resp, count, origin, step, options) {
    const win = options.sampleWindow;
    const drop = Math.max(0, Math.min(options.dropCount, count - 3));
    const acc = accumulator(drop);
    const perLine = new Array(count);
    for (let k = 0; k < count; k++) {
        const value = windowMax(resp, origin + k * step, win);
        perLine[k] = value;
        acc.push(value);
    }
    const strong = strongCount(perLine, options);
    const present = presenceCount(perLine, options);
    const bonus = strong * (options.strongBonus == null ? DEFAULTS.strongBonus : options.strongBonus) +
        present * (options.presenceBonus == null ? DEFAULTS.presenceBonus : options.presenceBonus);
    return { score: acc.score() + bonus, sum: acc.total(), perLine: perLine, strong: strong, present: present };
}

/**
 * 插值评分（**无窗口**）：逐条线取 `lerpAt` 求和 + 强线加成——细修阶段用。
 * 窗口评分在 origin 上是**平顶**的（预测线在真线 ±win 内怎么移分数都一样），
 * 直接取极值会把原点钉在平顶边缘；插值评分的峰是唯一的、贴近真值。
 */
function scoreInterp(resp, count, origin, step, options) {
    const drop = Math.max(0, Math.min(options.dropCount, count - 3));
    const acc = accumulator(drop);
    const values = new Array(count);
    for (let k = 0; k < count; k++) {
        const value = lerpAt(resp, origin + k * step);
        values[k] = value;
        acc.push(value);
    }
    const strong = strongCount(values, options);
    const present = presenceCount(values, options);
    const bonus = strong * (options.strongBonus == null ? DEFAULTS.strongBonus : options.strongBonus) +
        present * (options.presenceBonus == null ? DEFAULTS.presenceBonus : options.presenceBonus);
    return acc.score() + bonus;
}

/**
 * 平顶中心化：把「插值评分 ≥ 极值 99%（且不低于 1e-4）」的连续区间取**中点**。
 * 平顶峰（抗锯齿/采样四舍五入产生）与方波状响应都会被放到正中。
 */
function centerOnPlateau(resp, count, origin, step, options) {
    const base = scoreInterp(resp, count, origin, step, options);
    const eps = Math.max(1e-4, Math.abs(base) * 0.01);
    let lo = origin;
    let hi = origin;
    for (let i = 0; i < 48 && scoreInterp(resp, count, lo - 0.25, step, options) >= base - eps; i++) {
        lo -= 0.25;
    }
    for (let i = 0; i < 48 && scoreInterp(resp, count, hi + 0.25, step, options) >= base - eps; i++) {
        hi += 0.25;
    }
    const centeredOrigin = (lo + hi) / 2;
    lo = step;
    hi = step;
    for (let i = 0; i < 32 && scoreInterp(resp, count, centeredOrigin, lo - 0.25, options) >= base - eps; i++) {
        lo -= 0.25;
    }
    for (let i = 0; i < 32 && scoreInterp(resp, count, centeredOrigin, hi + 0.25, options) >= base - eps; i++) {
        hi += 0.25;
    }
    return { origin: centeredOrigin, step: (lo + hi) / 2 };
}



/**
 * 在响应数组上搜「count 条等距线」的最优 (origin, step)。
 *
 * 搜索空间：step ∈ [stepMin, stepMax]（0.5px 级）；origin 扫全带（originStep 级），
 * 约束是梳子整体落在 `boundsMin..boundsMax`（画面）内 —— 越界的平移梳直接杀掉
 * （横向 ±1 条平移会把一条线顶出画面，这条硬约束比打分更早生效）。
 * 之后两级细修：① 插值评分局部细扫；② 平顶中心化。
 *
 * @param resp  buildResponse 的产物 { from, to, step, values }
 * @param count 线数（竖 9 / 横 10）
 * @param options { stepMin, stepMax, originStep, sampleWindow, dropCount, refineInc, topK }
 * @return { best: {origin, step, score, sum, perLine}, candidates: Top-K, evaluations }
 */
function combFit(resp, count, options) {
    const opts = options || {};
    const stepMin = opts.stepMin == null ? 40 : opts.stepMin;
    const stepMax = opts.stepMax == null ? 200 : opts.stepMax;
    const originStep = opts.originStep == null ? DEFAULTS.originStep : opts.originStep;
    const stepInc = opts.coarseStepInc == null ? DEFAULTS.coarseStepInc : opts.coarseStepInc;
    const settings = {
        sampleWindow: opts.sampleWindow == null ? DEFAULTS.sampleWindow : opts.sampleWindow,
        dropCount: opts.dropCount == null ? DEFAULTS.dropCount : opts.dropCount,
        strongRatio: opts.strongRatio == null ? DEFAULTS.strongRatio : opts.strongRatio,
        strongBonus: opts.strongBonus == null ? DEFAULTS.strongBonus : opts.strongBonus,
        presenceRatio: opts.presenceRatio == null ? DEFAULTS.presenceRatio : opts.presenceRatio,
        presenceBonus: opts.presenceBonus == null ? DEFAULTS.presenceBonus : opts.presenceBonus,
        minLineSupport: opts.minLineSupport == null ? DEFAULTS.minLineSupport : opts.minLineSupport
    };
    // 画面边界：不传就用响应带外扩一个采样步（调用方传真实画面尺寸更严谨）
    const boundsMin = opts.boundsMin == null ? resp.from - resp.step : opts.boundsMin;
    const boundsMax = opts.boundsMax == null ? resp.to + resp.step : opts.boundsMax;
    // 「强区」：响应里明显有内容的区间（≥ 峰值 15%）。用它把 origin 搜索夹到「紧邻强区 ±2.5 格」
    // ——棋盘线只会出现在强区附近；y 轴搜索带很高（12%~92% 屏高）时这一刀能砍掉一半以上候选。
    let firstStrong = -1;
    let lastStrong = -1;
    let peakValue = 0;
    for (let i = 0; i < resp.values.length; i++) {
        if (resp.values[i] > peakValue) {
            peakValue = resp.values[i];
        }
    }
    const strongFloor = peakValue * 0.15;
    for (let i = 0; i < resp.values.length; i++) {
        if (resp.values[i] >= strongFloor) {
            if (firstStrong < 0) {
                firstStrong = i;
            }
            lastStrong = i;
        }
    }
    const topK = opts.topK == null ? DEFAULTS.topK : opts.topK;
    let evaluations = 0;
    let best = null;
    const candidates = [];

    function consider(origin, step) {
        const scored = combScore(resp, count, origin, step, settings);
        evaluations += 1;
        const item = { origin: origin, step: step, score: scored.score, sum: scored.sum };
        if (best === null || item.score > best.score) {
            best = {
                origin: origin, step: step, score: scored.score, sum: scored.sum,
                perLine: scored.perLine
            };
        }
        // 维护 Top-K（只用于证据/日志，不参与决策）
        if (candidates.length < topK) {
            candidates.push(item);
            candidates.sort(function (a, b) { return b.score - a.score; });
        } else if (item.score > candidates[candidates.length - 1].score) {
            candidates[candidates.length - 1] = item;
            candidates.sort(function (a, b) { return b.score - a.score; });
        }
    }

    for (let step = stepMin; step <= stepMax + 1e-9; step += stepInc) {
        const span = (count - 1) * step;
        let oMin = Math.max(resp.from - step * 0.6, boundsMin - 2);
        let oMax = Math.min(resp.to + step * 0.6 - span, boundsMax + 2 - span);
        if (firstStrong >= 0) {
            // 强区夹逼：笵子（第 0 条线）最多比第一条强线早 2.5 格，最后一条线同理
            const firstPos = resp.from + firstStrong * resp.step;
            const lastPos = resp.from + lastStrong * resp.step;
            oMin = Math.max(oMin, firstPos - step * 2.5);
            oMax = Math.min(oMax, lastPos + step * 2.5 - span);
        }
        if (oMax < oMin) {
            continue;   // 这个格距放不下整把梳子（或在画面内放不下）
        }
        for (let origin = oMin; origin <= oMax + 1e-9; origin += originStep) {
            consider(origin, step);
        }
    }
    if (best === null) {
        return { best: null, candidates: candidates, evaluations: evaluations };
    }

    // 细修（两级）：
    //   ① 粗扫用窗口评分（容遮挡），但它在 origin 上是**平顶**的——在粗扫解附近用
    //      **插值评分**做局部细扫，找回唯一的峰；
    //   ② 平顶中心化：把插值评分「不低于极值 99%」的连续区间取中点（否则会停在平台边缘）。
    const inc = opts.refineInc == null ? DEFAULTS.refineInc : opts.refineInc;
    // 细扫范围要把「粗扫的两个粒度」都盖住：origin ±originStep、step ±stepInc
    const span = settings.sampleWindow + resp.step + originStep + 2;
    const stepSpan = stepInc + 2;
    let fine = null;
    for (let step = best.step - stepSpan; step <= best.step + stepSpan + 1e-9; step += inc / 2) {
        for (let origin = best.origin - span; origin <= best.origin + span + 1e-9; origin += inc) {
            const value = scoreInterp(resp, count, origin, step, settings);
            evaluations += 1;
            if (fine === null || value > fine.value) {
                fine = { origin: origin, step: step, value: value };
            }
        }
    }
    if (fine) {
        const centered = centerOnPlateau(resp, count, fine.origin, fine.step, settings);
        const scored = combScore(resp, count, centered.origin, centered.step, settings);
        // 细修只在「不劣化窗口评分」时替换（理论上总成立；防极端响应下的回归）
        if (scored.score >= best.score - 1e-6) {
            best = {
                origin: centered.origin, step: centered.step,
                score: scored.score, sum: scored.sum, perLine: scored.perLine,
                strong: scored.strong, refined: true
            };
        }
    }
    return { best: best, candidates: candidates, evaluations: evaluations };
}

/**
 * 建一条轴的响应数组：沿 `axis` 方向逐条带数「线色」像素，归一化成 0..1。
 *
 * @param host  { countColor(image, color, {region, threshold}) → count|null }
 * @param image 帧
 * @param axis  "x" 竖线（沿 x 扫、条带沿 y 数色）；"y" 横线
 * @param band  { x0, y0, x1, y1 } 扫描带（像素）
 * @param options.imageSize { width, height }：给了就把条带区域夹进画面 ——
 *        ⚠️ 引擎的 findAllPointsForColor 对**越界区域会抛异常**（adapter 统一吞成 null），
 *        而第一条竖条带在 pos=0 时 x=-1 就踩到（真机 2026-09-22：整轮探测 calls=0 直接失败）。
 * @return { axis, from, to, step, values, calls, ms }；宿主没有找色能力返回 null
 */
function buildResponse(host, image, axis, band, options) {
    const opts = options || {};
    const scanStep = opts.scanStep == null ? DEFAULTS.scanStep : opts.scanStep;
    const half = opts.stripHalf == null ? DEFAULTS.stripHalf : opts.stripHalf;
    const refs = opts.refs || DEFAULTS.refs;
    const imageSize = opts.imageSize || null;
    const vertical = axis === "x";
    const from = vertical ? band.x0 : band.y0;
    const to = vertical ? band.x1 : band.y1;
    const acrossFrom = vertical ? band.y0 : band.x0;
    const acrossTo = vertical ? band.y1 : band.x1;
    const acrossSpan = Math.max(1, acrossTo - acrossFrom);
    const stripWidth = half * 2 + 1;
    const values = [];
    let calls = 0;
    const started = Date.now();
    for (let pos = from; pos <= to; pos += scanStep) {
        let rx = vertical ? pos - half : acrossFrom;
        let ry = vertical ? acrossFrom : pos - half;
        let rw = vertical ? stripWidth : acrossSpan;
        let rh = vertical ? acrossSpan : stripWidth;
        if (imageSize) {
            // 夹进画面：贴边的条带向内收（越界区域会让引擎抛异常）
            if (rx < 0) { rw += rx; rx = 0; }
            if (ry < 0) { rh += ry; ry = 0; }
            if (rx + rw > imageSize.width) { rw = imageSize.width - rx; }
            if (ry + rh > imageSize.height) { rh = imageSize.height - ry; }
            if (rw <= 0 || rh <= 0) {
                values.push(0);
                continue;
            }
        }
        const region = { x: rx, y: ry, width: rw, height: rh };
        let count = 0;
        for (let r = 0; r < refs.length; r++) {
            const got = host.countColor(image, refs[r].color, { region: region, threshold: refs[r].threshold });
            calls += 1;
            if (got == null) {
                return null;   // 宿主没有找色能力（旧引擎）⇒ 调用方回退旧路
            }
            count += got;
        }
        // 归一化：条带内「每行（列）平均有多少个线色像素」（0..约 1.5，多参考色会叠加）
        values.push(count / (acrossSpan * stripWidth));
    }
    return {
        axis: axis, from: from, to: to, step: scanStep, values: values,
        calls: calls, ms: Date.now() - started
    };
}

/**
 * 全屏探测：竖线 + 横线两条响应 → 各自梳状拟合 → 组装几何。
 *
 * @param host  { countColor, sizeOf }
 * @param image 帧
 * @param options 见 DEFAULTS；orientation 透传给 createGrid
 * @return {
 *   ok, reason?, grid?, score?, squareness?, boundsOk?,
 *   evidence: { vertical: {origin,step,matched,perLine,score,calls,ms,topK}, horizontal: {...} },
 *   timing: { responseMs, fitMs, totalMs }, calls
 * }
 */
function detect(host, image, options) {
    const opts = options || {};
    const merged = {};
    for (const key in DEFAULTS) {
        merged[key] = (opts[key] === undefined) ? DEFAULTS[key] : opts[key];
    }
    merged.orientation = opts.orientation || "RED_BOTTOM";
    const started = Date.now();
    if (!host || typeof host.countColor !== "function") {
        return { ok: false, reason: "宿主没有找色能力（countColor）", calls: 0 };
    }
    const size = host.sizeOf ? host.sizeOf(image) : null;
    if (!size || !(size.width > 0) || !(size.height > 0)) {
        return { ok: false, reason: "拿不到图像尺寸（sizeOf）", calls: 0 };
    }
    if (opts.scanStep == null && size.width <= 800) {
        merged.scanStep = 3;
    }
    const band = {
        x0: Math.round(merged.xBand[0] * size.width),
        y0: Math.round(merged.yBand[0] * size.height),
        x1: Math.round(merged.xBand[1] * size.width),
        y1: Math.round(merged.yBand[1] * size.height)
    };
    const minSide = Math.min(size.width, size.height);
    const stepMin = opts.stepMin == null ? merged.stepRatioMin * minSide : opts.stepMin;
    const stepMax = opts.stepMax == null ? merged.stepRatioMax * minSide : opts.stepMax;
    merged.imageSize = size;   // buildResponse 用它把条带夹进画面（越界会抛异常）

    const respX = buildResponse(host, image, "x", band, merged);
    const respY = buildResponse(host, image, "y", band, merged);
    if (!respX || !respY) {
        return { ok: false, reason: "找色调用失败（countColor 返回 null）", calls: respX ? respX.calls : 0 };
    }
    const responseMs = (respX.ms || 0) + (respY.ms || 0);

    const fitStart = Date.now();
    const fitOptions = {
        stepMin: stepMin, stepMax: stepMax,
        originStep: merged.originStep, sampleWindow: merged.sampleWindow,
        dropCount: merged.dropCount, strongRatio: merged.strongRatio,
        strongBonus: merged.strongBonus, refineInc: merged.refineInc, topK: merged.topK
    };
    const fitX = combFit(respX, COLS, Object.assign({ boundsMin: 0, boundsMax: size.width }, fitOptions));
    const fitY = combFit(respY, ROWS, Object.assign({ boundsMin: 0, boundsMax: size.height }, fitOptions));
    const fitMs = Date.now() - fitStart;
    if (!fitX.best || !fitY.best) {
        return {
            ok: false, reason: "梳状拟合没出候选（扫描带里没有棋盘线色的周期性证据）",
            calls: respX.calls + respY.calls,
            evidence: { vertical: { topK: fitX.candidates }, horizontal: { topK: fitY.candidates } }
        };
    }
    const squareness = Math.abs(fitX.best.step - fitY.best.step) /
        ((fitX.best.step + fitY.best.step) / 2);
    const grid = geometry.createGrid({
        originX: fitX.best.origin,
        originY: fitY.best.origin,
        stepX: fitX.best.step,
        stepY: fitY.best.step,
        orientation: merged.orientation
    });
    // 边界体检：整块棋盘要落在图像内（允许半条线的松弛）
    const lastX = grid.originX + 8 * grid.stepX;
    const lastY = grid.originY + 9 * grid.stepY;
    const slack = Math.max(grid.stepX, grid.stepY) * 0.5;
    const boundsOk = grid.originX >= -slack && grid.originY >= -slack &&
        lastX <= size.width + slack && lastY <= size.height + slack;
    const ok = squareness <= merged.maxSquareness && boundsOk;

    function summarize(fit) {
        const perLine = fit.best.perLine;
        return {
            origin: fit.best.origin, step: fit.best.step,
            score: fit.best.score, matched: matchedCount(perLine, merged), strong: fit.best.strong,
            perLine: perLine, topK: fit.candidates
        };
    }

    const vertical = summarize(fitX);
    const horizontal = summarize(fitY);
    const linesOk = vertical.matched >= merged.minVerticalMatched &&
        horizontal.matched >= merged.minHorizontalMatched;
    return {
        ok: ok && linesOk,
        reason: !linesOk ? "格线证据不足（竖 " + vertical.matched + "/9，横 " +
            horizontal.matched + "/10）" : (ok ? "" : (squareness > merged.maxSquareness
            ? "两轴格距差 " + (squareness * 100).toFixed(1) + "%（超过 " + (merged.maxSquareness * 100) + "%）"
            : "棋盘越界（origin " + Math.round(grid.originX) + "," + Math.round(grid.originY) + "）")),
        grid: grid,
        score: fitX.best.score + fitY.best.score,
        squareness: squareness,
        boundsOk: boundsOk,
        evidence: { vertical: vertical, horizontal: horizontal },
        timing: {
            responseMs: responseMs, fitMs: fitMs,
            respXMs: respX.ms, respYMs: respY.ms,
            totalMs: Date.now() - started
        },
        calls: respX.calls + respY.calls
    };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    COLS: COLS,
    ROWS: ROWS,
    responseAt: responseAt,
    lerpAt: lerpAt,
    windowMax: windowMax,
    strongCount: strongCount,
    presenceCount: presenceCount,
    matchedCount: matchedCount,
    combScore: combScore,
    scoreInterp: scoreInterp,
    centerOnPlateau: centerOnPlateau,
    combFit: combFit,
    buildResponse: buildResponse,
    detect: detect
};
