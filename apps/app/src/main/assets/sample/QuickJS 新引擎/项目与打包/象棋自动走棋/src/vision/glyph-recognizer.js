// @engine quickjs
/**
 * 「字形模板」识别（电脑 py 移植，2026-09-24）—— 源自 `.artifacts/k40-now/cv-chess-recognize-2.py`：
 *   逐格三道门（暗占比 → 内窗 → 亮面）判「有子」→ 11 个字形模板做 TM_CCOEFF_NORMED 匹配（阈值 0.70）
 *   → 車/馬/炮 用笔画颜色（暗像素平均 R-B）判阵营 → 90 格字符。
 *
 * 与 py 的差异（都有实测依据，2026-09-24 移植核查）：
 *   ① 搜索窗改成不对称「横 ±SEARCH_X / 纵 ±SEARCH_Y」：py 用 ±12 对称窗是因为吃它自带的
 *      山谷网格；本模块吃工程 lattice 网格，9 帧 284 格实测 |dx|max=2、|dy|max=16（行9 累积
 *      漂移）—— 对称 ±12 会把 skin-a 行9 整行掉出窗（5 格未知）。80×108 窗面积与原 92×92 持平。
 *   ② 支持跨分辨率：模板按「板距 / REF_STEP」缩放（`scaleFor` + `prepareTemplates`）。
 *      K40（板距≈114.9）scale≈1；新手机 1280（板距≈136）scale≈1.19，实测整盘 32 子 0 未知。
 *   ③ 模板由调用方加载（工程风格；资产在 `assets/glyph-tpl/`，加载见 tools/probe-glyph.js）。
 *
 * 实测成绩（K40 + 浅木皮肤 9 帧盘面级评测集）：763/784 = 97.3%，与电脑 py 逐帧一致；
 * 单帧识别 ~350ms（90 格 × 11 模板 matchTemplate）。边界：模板按「浅木皮肤」采集，换皮肤需重采；
 * 跨分辨率靠 `prepareTemplates` 缩放。
 *
 * 宿主注入（tools/probe-glyph.js 有现成映射；离线自检用假宿主，见 self-check.js）：
 *   sizeOf / grayscale / clip / threshold / countColor / matchTemplate / pixel / red / green / blue /
 *   resize / nowMono
 */
"use strict";

const DEFAULTS = {
    /** 基准板距（像素）：模板采集尺度（K40 浅木皮肤 1080 宽 ≈114.85）。 */
    REF_STEP: 114.85,
    /** 字窗半宽（68×68）与字形中心相对格点偏移（与 py 一致）。 */
    TW: 34,
    ANCHOR_DY: -5,
    /** 搜索窗半宽（像素，不对称；见文件头 ①。面积 = (TW+6)*2 × (TW+20)*2 ≈ 原 92×92）。 */
    SEARCH_X: 6,
    SEARCH_Y: 20,
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
 * py `match_at` 的等价物：11 个模板在 (x, cy) 的 ±SEARCH_X / ±SEARCH_Y 窗内取最佳。
 * ⚠️ 引擎 `images.matchTemplate` 的 `point` 是**绝对坐标的模板左上角**（native_frame_store.cpp
 *    里 `x + maxLocation.x`）→ 中心 = point + TW。
 * @return { name, sim, cx, cy } | null（全部低于 CONF）
 */
function bestMatch(host, grayFrame, x, cy, templates, options) {
    const opts = options || {};
    const TW = pick(opts, "TW");
    const sx = pick(opts, "SEARCH_X");
    const sy = pick(opts, "SEARCH_Y");
    const conf = pick(opts, "CONF");
    const rx = x - TW - sx;
    const ry = cy - TW - sy;
    const rw = (TW + sx) * 2;
    const rh = (TW + sy) * 2;
    let best = null;
    for (let i = 0; i < templates.length; i++) {
        const res = host.matchTemplate(grayFrame, templates[i].image, {
            region: [rx, ry, rw, rh], threshold: conf, max: 1
        });
        const m = (res && res.matches && res.matches.length) ? res.matches[0] : null;
        if (m && (!best || m.similarity > best.sim)) {
            best = { name: templates[i].name, sim: m.similarity, cx: m.point.x + TW, cy: m.point.y + TW };
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
 * @param host { sizeOf, grayscale, clip, threshold, countColor, matchTemplate, pixel, red, green, blue, nowMono }
 * @param image 彩色帧
 * @param grid  lattice 网格 { originX, originY, stepX, stepY }
 * @param options { templates 必填; TW/ANCHOR_DY/SEARCH_X/SEARCH_Y/CONF/... 可覆盖 }
 * @return { chars: 90, inked, unknown, ms, error? }
 */
function recognize(host, image, grid, options) {
    const opts = options || {};
    const templates = opts.templates;
    if (!templates || !templates.length) {
        return { chars: null, inked: 0, unknown: 0, ms: 0, error: "没有模板（templates 必填）" };
    }
    const TW = pick(opts, "TW");
    const ANCHOR_DY = pick(opts, "ANCHOR_DY");
    const DARK_THR = pick(opts, "DARK_THR");
    const INNER_THR = pick(opts, "INNER_THR");
    const BRIGHT_RATIO = pick(opts, "BRIGHT_RATIO");
    const started = host.nowMono();
    const size = host.sizeOf(image);
    const grayFrame = host.grayscale(image);
    const chars = new Array(90);
    const winW = TW * 2;
    // 内窗：以 (x, cy) 为心的 40×40；在字窗内坐标 = [TW-20, TW+ANCHOR_DY-20]（K40 时为 [14,9]）
    const innerX = TW - 20;
    const innerY = TW + ANCHOR_DY - 20;
    let inked = 0;
    let unknown = 0;
    let error = "";
    for (let r = 0; r < 10 && !error; r++) {
        for (let c = 0; c < 9 && !error; c++) {
            const idx = r * 9 + c;
            chars[idx] = ".";
            const x = Math.round(grid.originX + c * grid.stepX);
            const y = Math.round(grid.originY + r * grid.stepY);
            const cy = y + ANCHOR_DY;
            if (x - TW < 0 || y - TW < 0 || x + TW >= size.width || y + TW >= size.height) {
                continue;
            }
            // ① 暗占比门（有子？）
            const win = host.clip(grayFrame, x - TW, y - TW, winW, winW);
            const bin = host.threshold(win, 140, 255);
            const dark = countOf(host, bin, "#000000", 0, 0, winW, winW);
            if (dark / (winW * winW) <= DARK_THR) {
                recycle(win);
                recycle(bin);
                continue;
            }
            // ② 内窗门（字形必在格心附近）
            const inner = countOf(host, bin, "#000000", innerX, innerY, 40, 40);
            // ③ 亮面门（真棋子窗内必有大片亮盘面）
            const faceBin = host.threshold(win, 200, 255);
            const bright = countOf(host, faceBin, "#FFFFFF", 0, 0, winW, winW);
            recycle(win);
            recycle(bin);
            recycle(faceBin);
            if (inner / 1600 <= INNER_THR) {
                continue;
            }
            if (bright / (winW * winW) < BRIGHT_RATIO) {
                continue;
            }
            inked += 1;
            // ④ 模板匹配（全部低于 CONF = 未知格）
            let m = null;
            try {
                m = bestMatch(host, grayFrame, x, cy, templates, opts);
            } catch (matchError) {
                error = "matchTemplate 异常：" + matchError;
                break;
            }
            if (!m) {
                chars[idx] = "?";
                unknown += 1;
                continue;
            }
            // ⑤ 阵营
            if (SIDED[m.name]) {
                const side = sideOf(host, image, m.cx, m.cy, opts);
                if (side === "?") {
                    chars[idx] = "?";
                    unknown += 1;
                    continue;
                }
                chars[idx] = SIDED[m.name][side === "r" ? 0 : 1];
            } else {
                chars[idx] = FIXED[m.name];
            }
        }
    }
    recycle(grayFrame);
    return { chars: chars, inked: inked, unknown: unknown, ms: host.nowMono() - started, error: error };
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
    bestMatch: bestMatch,
    sideOf: sideOf,
    recognize: recognize,
    charsToFen: charsToFen
};
