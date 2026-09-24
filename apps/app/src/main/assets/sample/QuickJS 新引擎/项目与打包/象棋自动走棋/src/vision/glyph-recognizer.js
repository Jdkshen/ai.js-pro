// @engine quickjs
/**
 * 「字形模板」识别（电脑 py 移植，2026-09-24）—— 源自 `.artifacts/k40-now/cv-chess-recognize-2.py`：
 *   逐格三道门（暗占比 → 内窗 → 亮面）判「有子」→ 11 个字形模板做 TM_CCOEFF_NORMED 匹配（阈值 0.70）
 *   → 車/馬/炮 用笔画颜色（暗像素平均 R-B）判阵营 → 90 格字符。
 *
 * 与 py 的差异（都有实测依据）：
 *   ① **行自适应窄窗 + 宽窗复核**（v2，2026-09-24）：py 用 ±12 对称窗是因为吃它自带的「山谷网格」；
 *      本模块吃工程 lattice 网格，9 帧 284 格实测字形**纵向按行累积漂移**（|dy|max=16、行9；
 *      行间 median 最大跳 6px、行内离散 ≤6px）—— 于是按行预测：用「上一个匹配到的格子的实测 dy
 *      中位数」当窗心修正，窄窗（横 ±SEARCH_X / 纵 ±SEARCH_Y_TIGHT）先匹配；没命中或得分
 *      < SAFE_CONF 才用宽窗（纵 ±SEARCH_Y，与 v1 等价）复核。窄窗把每模板的匹配点从 533 → 169。
 *   ①b **金字塔粗搜**（v3）：整帧缩一半 + 模板缩一半，先提名 top-K（每调用约 0.22× 成本），
 *      再在全尺寸上小窗精修；精修分 < SAFE_CONF 就回退「窄+宽」全搜。匹配再省 ~3-4×。
 *      另：匹配窗能落在一帧内时改走「clip 后匹配」（原生连续内存，实测 ~0.61×）。
 *      亮面门改成数「少数类」再把亮面算回来（同一个值，少一半找色点对象分配）。
 *   ② 支持跨分辨率：模板按「板距 / REF_STEP」缩放（`scaleFor` + `prepareTemplates`）；
 *      `prepareFor` 带缓存（同一模板数组 + 同一缩放比不重复缩放）。
 *   ③ 模板由 `loadTemplates` 从 `assets/glyph-tpl/` 加载；结果带 `profile` 分段计时（gates/match/side）。
 *
 * 实测成绩（K40 + 浅木皮肤 9 帧盘面级评测集）：763/784 = 97.3%，与电脑 py 逐帧一致。
 * 边界：模板按「浅木皮肤」采集，换皮肤需重采；跨分辨率靠 `prepareTemplates` 缩放。
 *
 * 宿主注入（tools/probe-glyph.js 有现成映射；离线自检用假宿主，见 self-check.js）：
 *   sizeOf / grayscale / clip / threshold / countColor / matchTemplate / pixel / red / green / blue /
 *   resize / read / nowMono
 */
"use strict";

const DEFAULTS = {
    /** 基准板距（像素）：模板采集尺度（K40 浅木皮肤 1080 宽 ≈114.85）。 */
    REF_STEP: 114.85,
    /** 字窗半宽（68×68）与字形中心相对格点偏移（与 py 一致）。 */
    TW: 34,
    ANCHOR_DY: -5,
    /** 搜索窗半宽（像素，不对称；见文件头 ①）。宽窗 = 复核窗（面积与 py 原 92×92 持平）。 */
    SEARCH_X: 6,
    SEARCH_Y: 20,
    /** 窄窗纵向半宽（行自适应；实测行间 median 跳 ≤6px、行内离散 ≤6px）。 */
    SEARCH_Y_TIGHT: 6,
    /** 窄窗得分低于它就再用宽窗复核（正常匹配 ≥0.9，0.70~0.82 属于「能对上但要复核」）。 */
    SAFE_CONF: 0.82,
    /** 金字塔粗搜（半尺寸）：粗层阈值 / 提名数（精修候选）/ 精修半径（全尺寸像素）。 */
    COARSE_CONF: 0.30,
    PYRAMID_TOP: 3,
    PYRAMID_R: 5,
    /** 模板相似度门限（TM_CCOEFF_NORMED）。 */
    CONF: 0.70,
    /** ① 有子：窗内暗像素（gray<140）占比 > 0.18。 */
    DARK_THR: 0.18,
    /** ② 内窗：字形必在格心附近（40×40 内窗暗占比 > 0.12）。 */
    INNER_THR: 0.12,
    /** ③ 亮面：真棋子窗内必有大片亮盘面（gray≥200 占比 ≥10%，≈ py 的 p90≥200）。 */
    BRIGHT_RATIO: 0.10,
    /** 阵营：匹配窗内暗像素（gray<150）平均 R-B > 45 判红。 */
    SIDE_RB: 45,
    SIDE_STEP: 4,
    SIDE_MIN_DARK: 30
};

const TPL_NAMES = ["車", "馬", "象", "士", "將", "炮", "卒", "兵", "相", "仕", "帥"];
/** 红黑同形、需颜色判阵营的三字 → [红, 黑] 输出字符。 */
const SIDED = { "車": ["R", "r"], "馬": ["N", "n"], "炮": ["C", "c"] };
/** 其余八字按字形直接定阵营。 */
const FIXED = { "帥": "K", "仕": "A", "相": "B", "兵": "P", "將": "k", "士": "a", "象": "b", "卒": "p" };

function pick(options, key) {
    return options && options[key] != null ? options[key] : DEFAULTS[key];
}

/** 当前帧的模板缩放比：板距均值 / 基准板距。 */
function scaleFor(grid, refStep) {
    const ref = refStep == null ? DEFAULTS.REF_STEP : refStep;
    return ((grid.stepX + grid.stepY) / 2) / ref;
}

/**
 * 按缩放比准备模板与窗口参数。scale≈1 原样返回（不无谓 resize）；
 * 否则把每个模板缩放到 2*TW 见方，并把 TW / ANCHOR_DY 一起缩放。
 * SEARCH_X/Y 不缩放——它们是「网格漂移容差」的像素值，与字形尺寸无关。
 * @return { templates: [{name, image}], TW, ANCHOR_DY }
 */
function prepareTemplates(host, templates, scale, options) {
    const opts = options || {};
    const baseTW = pick(opts, "TW");
    const baseAnchor = pick(opts, "ANCHOR_DY");
    if (!(scale > 0) || Math.abs(scale - 1) < 0.02) {
        return { templates: templates, TW: baseTW, ANCHOR_DY: baseAnchor };
    }
    const TW = Math.round(baseTW * scale);
    const out = [];
    for (let i = 0; i < templates.length; i++) {
        out.push({
            name: templates[i].name,
            image: host.resize(templates[i].image, [TW * 2, TW * 2])
        });
    }
    return { templates: out, TW: TW, ANCHOR_DY: -Math.round(Math.abs(baseAnchor) * scale) };
}

/**
 * 带缓存的模板准备：同一模板数组 + 同一缩放比只缩放一次（帧间复用）。
 * 缩放比按 1/1000 归并（板距在会话内基本不变）。缓存挂在 templates 数组上（`__glyphPrepared`）。
 * @return { templates, TW, ANCHOR_DY, scale, cached }
 */
function prepareFor(host, templates, scale, options) {
    const bucket = Math.round((scale || 1) * 1000) / 1000;
    const memo = templates.__glyphPrepared;
    if (memo && memo.scale === bucket) {
        return {
            templates: memo.templates, TW: memo.TW, ANCHOR_DY: memo.ANCHOR_DY,
            scale: scale, cached: true, coarse: memo.coarse
        };
    }
    const prepared = prepareTemplates(host, templates, scale, options);
    // 金字塔粗搜模板（半尺寸）：全尺寸模板 2*TW → 半尺寸 TW；side 用于回算全尺寸中心。
    const coarseSide = Math.max(8, Math.round(prepared.TW));
    const coarse = [];
    for (let i = 0; i < prepared.templates.length; i++) {
        coarse.push({
            name: prepared.templates[i].name,
            side: coarseSide,
            image: host.resize(prepared.templates[i].image, [coarseSide, coarseSide])
        });
    }
    templates.__glyphPrepared = {
        scale: bucket, templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
        coarse: coarse
    };
    return {
        templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
        scale: scale, cached: false, coarse: coarse
    };
}

/** 从 assets 目录加载 11 个模板（灰度；跨分辨率由 prepareFor 缩放）。 */
function loadTemplates(host, dir) {
    const out = [];
    for (let i = 0; i < TPL_NAMES.length; i++) {
        const name = TPL_NAMES[i];
        const path = dir + "/tpl_" + name + ".png";
        const color = host.read(path);
        if (!color) {
            throw new Error("模板读不到：" + path);
        }
        const gray = host.grayscale(color);
        recycle(color);   // grayscale 产的是新帧；彩色原图不回收会在 GC 时报 MemoryLeak（真机实测 11 条）
        out.push({ name: name, image: gray });
    }
    return out;
}

/** 字形层字符（'.' 空格 / '?' 未知）→ 工程盘面格式（null 空格 / '?' 未知）。 */
function normalizeBoard(chars) {
    const out = new Array(chars.length);
    for (let i = 0; i < chars.length; i++) {
        out[i] = chars[i] === "." ? null : chars[i];
    }
    return out;
}

/** 数未知格（'?' 个数）。 */
function countUnknown(chars) {
    let n = 0;
    for (let i = 0; i < chars.length; i++) {
        if (chars[i] === "?") {
            n += 1;
        }
    }
    return n;
}

function countOf(host, image, color, x, y, width, height) {
    const got = host.countColor(image, color, {
        region: { x: x, y: y, width: width, height: height }, threshold: 1
    });
    return got == null ? 0 : got;
}

function recycle(image) {
    try {
        if (image && typeof image.recycle === "function") {
            image.recycle();
        }
    } catch (ignore) { /* 忽略释放异常 */ }
}

/**
 * py `match_at` 的等价物：若窗口能完整落在帧内，先 clip 再匹配（同数学；原生走连续内存，实测快 ~1.6×）。
 * ⚠️ 引擎 `images.matchTemplate` 的 `point` 是**窗口内坐标的模板左上角**（有 region 时原生会加上窗口原点）
 *    → 中心 = point + TW（+ clip 窗原点）。
 * @param size 帧尺寸（{width,height}，可选；不传则一律走 region 模式）
 * @return { name, sim, cx, cy } | null（全部低于 CONF）
 */
function bestMatch(host, grayFrame, x, cy, templates, options, size) {
    const opts = options || {};
    const TW = pick(opts, "TW");
    const sx = pick(opts, "SEARCH_X");
    const sy = pick(opts, "SEARCH_Y");
    const conf = pick(opts, "CONF");
    const rx = x - TW - sx;
    const ry = cy - TW - sy;
    const rw = (TW + sx) * 2;
    const rh = (TW + sy) * 2;
    const canClip = !!(size && rx >= 0 && ry >= 0 &&
        rx + rw <= size.width && ry + rh <= size.height);
    const clipped = canClip ? host.clip(grayFrame, rx, ry, rw, rh) : null;
    let best = null;
    try {
        for (let i = 0; i < templates.length; i++) {
            const res = clipped
                ? host.matchTemplate(clipped, templates[i].image, { threshold: conf, max: 1 })
                : host.matchTemplate(grayFrame, templates[i].image, {
                    region: [rx, ry, rw, rh], threshold: conf, max: 1
                });
            const m = (res && res.matches && res.matches.length) ? res.matches[0] : null;
            if (m && (!best || m.similarity > best.sim)) {
                best = {
                    name: templates[i].name, sim: m.similarity,
                    cx: m.point.x + TW + (clipped ? rx : 0),
                    cy: m.point.y + TW + (clipped ? ry : 0)
                };
            }
        }
    } finally {
        recycle(clipped);
    }
    return best;
}

/** v2 匹配：窄窗（窗心 = 字形中心 + 行自适应修正）+ 不放心时宽窗复核（复核计入 stats.retries）。 */
function matchNarrowWide(host, grayFrame, x, cy, rowDy, templates, tightOpts, opts, size, stats) {
    let m = bestMatch(host, grayFrame, x, cy + rowDy, templates, tightOpts, size);
    if (!m || m.sim < pick(opts, "SAFE_CONF")) {
        if (stats) {
            stats.retries += 1;
        }
        const wide = bestMatch(host, grayFrame, x, cy, templates, opts, size);
        if (wide && (!m || wide.sim > m.sim)) {
            m = wide;
        }
    }
    return m;
}

/**
 * v3 金字塔：半尺寸粗搜（11 模板各一次）→ 提名 top-K 在**全尺寸**上小窗精修。
 * 粗搜窗口位置 = 预测中心的一半；候选中心回算：全尺寸中心 = 粗点×2 + 粗模板边长。
 * @return 最佳精修结果 {name,sim,cx,cy} | null（粗层无提名）
 */
function matchPyramid(host, grayFrame, halfFrame, x, cy, templates, coarse, opts, size) {
    const TW = pick(opts, "TW");
    const ew = Math.max(2, Math.round((TW + pick(opts, "SEARCH_X")) / 2));
    const eh = Math.max(2, Math.round((TW + pick(opts, "SEARCH_Y_TIGHT")) / 2));
    const cx2 = Math.round(x / 2);
    const cy2 = Math.round(cy / 2);
    const region = [cx2 - ew, cy2 - eh, ew * 2, eh * 2];
    const coarseConf = pick(opts, "COARSE_CONF");
    const cands = [];
    for (let i = 0; i < coarse.length; i++) {
        const res = host.matchTemplate(halfFrame, coarse[i].image, {
            region: region, threshold: coarseConf, max: 1
        });
        const hit = (res && res.matches && res.matches.length) ? res.matches[0] : null;
        if (hit) {
            cands.push({
                index: i, sim: hit.similarity,
                cx: hit.point.x * 2 + coarse[i].side,
                cy: hit.point.y * 2 + coarse[i].side
            });
        }
    }
    if (!cands.length) {
        return null;
    }
    cands.sort(function (a, b) { return b.sim - a.sim; });
    const top = Math.min(cands.length, pick(opts, "PYRAMID_TOP"));
    const r = pick(opts, "PYRAMID_R");
    const safe = pick(opts, "SAFE_CONF");
    let best = null;
    for (let k = 0; k < top; k++) {
        const ref = bestMatch(host, grayFrame, cands[k].cx, cands[k].cy, [templates[cands[k].index]], {
            TW: TW, SEARCH_X: r, SEARCH_Y: r, CONF: 0
        }, size);
        if (ref && (!best || ref.sim > best.sim)) {
            best = ref;
        }
        // 提前收敛：精修分已经「可信」就不再精修剩下的提名（正常格只精修 1 个）
        if (best && best.sim >= safe) {
            break;
        }
    }
    return best;
}

/** 士/仕只能站在本方九宫的五个斜线点，盘边误读不能算成第三枚仕。 */
function advisorAllowed(name, col, row) {
    if (name !== "仕" && name !== "士") {
        return true;
    }
    const homeRow = name === "仕" ? 9 : 0;
    return (row === homeRow && (col === 3 || col === 5)) ||
        (row === homeRow + (name === "仕" ? -1 : 1) && col === 4) ||
        (row === homeRow + (name === "仕" ? -2 : 2) && (col === 3 || col === 5));
}

/**
 * 游戏选中棋子后会把棋子和字一起放大，普通尺寸模板可能读错。
 * 只在常规匹配没有结果或给出非法位置的士/仕时复核，避免每帧给 90 格增加开销。
 */
function matchSelectedScale(host, grayFrame, x, cy, col, row, templates, opts, size) {
    const baseTW = pick(opts, "TW");
    let best = null;
    const scales = [1.10, 1.18];
    for (let s = 0; s < scales.length; s++) {
        const half = Math.round(baseTW * scales[s]);
        const side = half * 2;
        for (let i = 0; i < templates.length; i++) {
            const tpl = templates[i];
            if (!advisorAllowed(tpl.name, col, row)) {
                continue;
            }
            let enlarged = null;
            try {
                enlarged = host.resize(tpl.image, [side, side]);
                const hit = bestMatch(host, grayFrame, x, cy,
                    [{ name: tpl.name, image: enlarged }],
                    { TW: half, SEARCH_X: 8, SEARCH_Y: 22, CONF: pick(opts, "SAFE_CONF") }, size);
                if (hit && (!best || hit.sim > best.sim)) {
                    best = hit;
                }
            } finally {
                recycle(enlarged);
            }
        }
        if (best && best.sim >= 0.90) {
            break;
        }
    }
    return best;
}

/** py `side_of_center`：匹配窗内暗像素（gray<150）平均 R-B > 45 判红（步进采样）。 */
function sideOf(host, colorFrame, cx, cy, options) {
    const opts = options || {};
    const TW = pick(opts, "TW");
    const step = pick(opts, "SIDE_STEP");
    let n = 0;
    let sumR = 0;
    let sumB = 0;
    for (let yy = cy - TW + 2; yy <= cy + TW - 2; yy += step) {
        for (let xx = cx - TW + 2; xx <= cx + TW - 2; xx += step) {
            let pixel = null;
            try {
                pixel = host.pixel(colorFrame, xx, yy);
            } catch (ignore) {
                continue;
            }
            const r = host.red(pixel);
            const g = host.green(pixel);
            const b = host.blue(pixel);
            const gray = (r * 299 + g * 587 + b * 114) / 1000;
            if (gray < 150) {
                n += 1;
                sumR += r;
                sumB += b;
            }
        }
    }
    if (n < pick(opts, "SIDE_MIN_DARK")) {
        return "?";
    }
    return (sumR / n - sumB / n) > pick(opts, "SIDE_RB") ? "r" : "b";
}

/**
 * 一帧识别：90 格逐格跑三道门 + 模板匹配 + 阵营。
 *
 * v2 匹配（见文件头 ①）：先「行自适应窄窗」——行内每匹配到一格，就把实测 dy 并入**实时中位数**
 * 当窗心修正（漂移是「按行」的量，实测行间跳 ≤6px）；窄窗没命中或得分 < SAFE_CONF 时用宽窗
 * 复核（宽窗窗心 = 未修正的字形中心，语义与 v1 相同；复核分更高就采用）。
 *
 * @param host { sizeOf, grayscale, clip, threshold, countColor, matchTemplate, pixel, red, green, blue, nowMono }
 * @param image 彩色帧
 * @param grid  lattice 网格 { originX, originY, stepX, stepY }
 * @param options { templates 必填; TW/ANCHOR_DY/SEARCH_X/SEARCH_Y/SEARCH_Y_TIGHT/SAFE_CONF/CONF/... 可覆盖 }
 * @return { chars: 90, inked, unknown, ms, retries, profile{gates,match,side}, error? }
 */
function recognize(host, image, grid, options) {
    const opts = options || {};
    const templates = opts.templates;
    if (!templates || !templates.length) {
        return { chars: null, inked: 0, unknown: 0, ms: 0, retries: 0, profile: null, error: "没有模板（templates 必填）" };
    }
    const TW = pick(opts, "TW");
    const ANCHOR_DY = pick(opts, "ANCHOR_DY");
    const SY_TIGHT = pick(opts, "SEARCH_Y_TIGHT");
    const SAFE_CONF = pick(opts, "SAFE_CONF");
    const DARK_THR = pick(opts, "DARK_THR");
    const INNER_THR = pick(opts, "INNER_THR");
    const BRIGHT_RATIO = pick(opts, "BRIGHT_RATIO");
    const started = host.nowMono();
    const size = host.sizeOf(image);
    const grayFrame = host.grayscale(image);
    // 金字塔（v3）：粗搜用半尺寸帧（整帧只缩一次；粗模板在 prepareFor 里备好）
    const coarseTemplates = opts.pyramid === false ? null : (opts.coarseTemplates || null);
    const halfFrame = coarseTemplates
        ? host.resize(grayFrame, [Math.round(size.width / 2), Math.round(size.height / 2)])
        : null;
    const chars = new Array(90);
    const winW = TW * 2;
    // 内窗：以 (x, cy) 为心的 40×40；在字窗内坐标 = [TW-20, TW+ANCHOR_DY-20]（K40 时为 [14,9]）
    const innerX = TW - 20;
    const innerY = TW + ANCHOR_DY - 20;
    /** 窄窗参数（只改纵向半宽，其余与原语义一致） */
    const tightOpts = Object.assign({}, opts, { SEARCH_Y: SY_TIGHT });
    let inked = 0;
    let unknown = 0;
    let error = "";
    /** 复核/回退计数（宽窗重试 + 金字塔回退；一个对象为了能传进 matchNarrowWide） */
    const matchStats = { retries: 0 };
    let gateMs = 0;
    let matchMs = 0;
    let sideMs = 0;
    /** 行自适应窗心修正（上一行/本行实测 dy 的中位数；初值 0 = 直接用网格） */
    let rowDy = 0;
    for (let r = 0; r < 10 && !error; r++) {
        const dySamples = [];
        for (let c = 0; c < 9 && !error; c++) {
            const idx = r * 9 + c;
            chars[idx] = ".";
            const x = Math.round(grid.originX + c * grid.stepX);
            const y = Math.round(grid.originY + r * grid.stepY);
            const cy = y + ANCHOR_DY;
            if (x - TW < 0 || y - TW < 0 || x + TW >= size.width || y + TW >= size.height) {
                continue;
            }
            const gateStarted = host.nowMono();
            // ① 暗占比门（有子？）
            const win = host.clip(grayFrame, x - TW, y - TW, winW, winW);
            const bin = host.threshold(win, 140, 255);
            const dark = countOf(host, bin, "#000000", 0, 0, winW, winW);
            if (dark / (winW * winW) <= DARK_THR) {
                recycle(win);
                recycle(bin);
                gateMs += host.nowMono() - gateStarted;
                continue;
            }
            // ② 内窗门（字形必在格心附近）
            const inner = countOf(host, bin, "#000000", innerX, innerY, 40, 40);
            // ③ 亮面门（真棋子窗内必有大片亮盘面）。
            // ⚠ 数「少数类」（≤200 的暗像素）再把亮面算回来：findAllPointsForColor 会为每个命中点
            //   建 JS 对象（亮像素是多数，数它每次要建几千个）—— 同一个值，少一半分配。
            const faceBin = host.threshold(win, 200, 255);
            const bright = winW * winW - countOf(host, faceBin, "#000000", 0, 0, winW, winW);
            recycle(win);
            recycle(bin);
            recycle(faceBin);
            gateMs += host.nowMono() - gateStarted;
            if (inner / 1600 <= INNER_THR) {
                continue;
            }
            if (bright / (winW * winW) < BRIGHT_RATIO) {
                continue;
            }
            inked += 1;
            // ④ 模板匹配：金字塔（若给了粗模板）→ 不放心再「窄窗+宽窗」全搜（行自适应窗心）
            const matchStarted = host.nowMono();
            let m = null;
            try {
                if (halfFrame) {
                    m = matchPyramid(host, grayFrame, halfFrame, x, cy + rowDy, templates, coarseTemplates, opts, size);
                }
                if (!m || m.sim < SAFE_CONF) {
                    if (halfFrame) {
                        matchStats.retries += 1;   // 金字塔不放心 ⇒ 回退全搜
                    }
                    const fine = matchNarrowWide(host, grayFrame, x, cy, rowDy, templates, tightOpts, opts, size, matchStats);
                    if (fine && (!m || fine.sim > m.sim)) {
                        m = fine;
                    }
                }
            } catch (matchError) {
                error = "matchTemplate 异常：" + matchError;
                break;
            }
            // (2,9) 被选中的红相放大约 10% 后，普通模板曾把它误判成九宫外的第三枚红仕。
            // 此处仍只使用同一套字形模板；放大复核不足 SAFE_CONF 就留作未知，绝不硬填。
            if (!m || !advisorAllowed(m.name, c, r)) {
                try {
                    m = matchSelectedScale(host, grayFrame, x, cy, c, r, templates, opts, size);
                } catch (selectedError) {
                    error = "选中棋子字形复核异常：" + selectedError;
                    break;
                }
            }
            matchMs += host.nowMono() - matchStarted;
            if (!m) {
                chars[idx] = "?";
                unknown += 1;
                continue;
            }
            // 行自适应：实测 dy 并入本行样本（实时中位数，抗单个错配）
            dySamples.push(m.cy - cy);
            dySamples.sort(function (a, b) { return a - b; });
            rowDy = dySamples[Math.floor(dySamples.length / 2)];
            // ⑤ 阵营
            const sideStarted = host.nowMono();
            if (SIDED[m.name]) {
                const side = sideOf(host, image, m.cx, m.cy, opts);
                if (side === "?") {
                    chars[idx] = "?";
                    unknown += 1;
                    sideMs += host.nowMono() - sideStarted;
                    continue;
                }
                chars[idx] = SIDED[m.name][side === "r" ? 0 : 1];
            } else {
                chars[idx] = FIXED[m.name];
            }
            sideMs += host.nowMono() - sideStarted;
        }
    }
    recycle(halfFrame);
    recycle(grayFrame);
    return {
        chars: chars, inked: inked, unknown: unknown, retries: matchStats.retries,
        ms: host.nowMono() - started,
        profile: { gates: gateMs, match: matchMs, side: sideMs },
        error: error
    };
}

/** 90 格字符 → FEN 盘面段（与 probes 一致）。 */
function charsToFen(chars) {
    const rows = [];
    for (let r = 0; r < 10; r++) {
        let text = "";
        let empty = 0;
        for (let c = 0; c < 9; c++) {
            const ch = chars[r * 9 + c];
            if (ch && ch !== ".") {
                if (empty > 0) {
                    text += empty;
                    empty = 0;
                }
                text += ch;
            } else {
                empty += 1;
            }
        }
        if (empty > 0) {
            text += empty;
        }
        rows.push(text || "9");
    }
    return rows.join("/");
}

module.exports = {
    DEFAULTS: DEFAULTS,
    TPL_NAMES: TPL_NAMES,
    SIDED: SIDED,
    FIXED: FIXED,
    scaleFor: scaleFor,
    prepareTemplates: prepareTemplates,
    prepareFor: prepareFor,
    loadTemplates: loadTemplates,
    normalizeBoard: normalizeBoard,
    countUnknown: countUnknown,
    bestMatch: bestMatch,
    sideOf: sideOf,
    recognize: recognize,
    charsToFen: charsToFen
};
