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
 *   ④ **「空中子」模糊门（2026-10-04）**：认不准（'?'）的格，算窗内离散拉普拉斯方差；
 *      若 < 本帧「认准的格」中位 × MOVING_RATIO（0.5）→ 判「子在空中/没落稳」按空处理（chars='.'）。
 *      落子动画中间帧的子是运动模糊的——不该对它做字符判断；静止子余量 1.35× 以上、零误伤。
 *
 * 实测成绩（K40 + 浅木皮肤 9 帧盘面级评测集）：
 *   · 「多尺度抢救」之前：763/784 = 97.3%（保留作历史对照；那时的分数还被真值错误拖了 31 格）；
 *   · 2026-09-25 真值修正（31 处）+ 抢救后：806/809 = 99.6%，比电脑 py（805/809）多救回 skin-c 那格；
 *   · 2026-10-04 加「空中子模糊门」（MOVING_*）后：**809/809 = 100%** —— skin2-04 落子动画帧的 3 格
 *     判「子在空中」归空，284 个静止格零误伤（电脑上复现：`node tools/glyph-pc-eval.js`）。
 *   电脑上复现：`node tools/glyph-pc-eval.js`（不用刷机）。
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
    SIDE_MIN_DARK: 30,
    /** ★ 「空中子」模糊门（2026-10-04）：认不准的格若整窗清晰度 <「认准的格」中位×MOVING_RATIO，
     *  判「子在空中/没落稳」（落子动画中间帧）按空处理（不再记未知、不再数它）。
     *  实测：284 个静止子离散拉普拉斯方差 ≥1289，空中子只有 248~395（差 4~5 倍）。 */
    MOVING_RATIO: 0.5,
    /** 清晰度采样步长（像素）与参考格上限（取前 N 个认准的格求中位基准）。 */
    MOVING_STEP: 3,
    MOVING_REF_CELLS: 5,
    /** ★ 多尺度抢救（2026-09-25）：读数「不放心」时用一组缩放模板再找一遍（见 rescueMatch）。
     *  1.10 = 棋子被点起/落子动画时游戏会把棋子放大（实测 1.10 档从 0.54 → 0.88）；
     *  其余档是「轻微缩放」的兜底，按「最可能先试」排序（命中就 break，省下后面的档）。 */
    RESCUE_SCALES: [1.10, 1.06, 1.18, 0.94],
    /** 抢救要拿到的分（比常规 SAFE_CONF 略松：抢救本来就是给「常规读不出」用的）。 */
    RESCUE_CONF: 0.80,
    /** 抢救结果还要求「与次名字形的分差」≥ 这个值（防「两个字形咬得很近时硬选一个」）。 */
    RESCUE_MARGIN: 0.12,
    /** 抢救搜索窗（与旧「选中放大」复核同口径：被点起/动画中的字形会平移，实测纵向漂 12px）。 */
    RESCUE_SEARCH_X: 8,
    RESCUE_SEARCH_Y: 22,
    /** 一帧最多抢救几格 + 抢救总耗时上限（抢救是「多尺度 × 11 模板」的全窗匹配，
     *  只给「读不出」的少数格用；超时就不再抢救剩下的格子，宁可先出别的结论）。 */
    RESCUE_MAX_CELLS: 3,
    RESCUE_BUDGET_MS: 2500
};

const TPL_NAMES = ["車", "馬", "象", "士", "將", "炮", "卒", "兵", "相", "仕", "帥"];
/** 红黑同形、需颜色判阵营的三字 → [红, 黑] 输出字符。 */
const SIDED = { "車": ["R", "r"], "馬": ["N", "n"], "炮": ["C", "c"] };
/** 其余八字按字形直接定阵营。 */
const FIXED = { "帥": "K", "仕": "A", "相": "B", "兵": "P", "將": "k", "士": "a", "象": "b", "卒": "p" };
const GLYPH_FOR_CHAR = { R: "車", r: "車", N: "馬", n: "馬", C: "炮", c: "炮",
    K: "帥", A: "仕", B: "相", P: "兵", k: "將", a: "士", b: "象", p: "卒" };

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
    try {
        for (let i = 0; i < templates.length; i++) {
            out.push({
                name: templates[i].name,
                image: host.resize(templates[i].image, [TW * 2, TW * 2])
            });
        }
    } catch (error) {
        releaseTemplateImages(out);
        throw error;
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
    try {
        for (let i = 0; i < prepared.templates.length; i++) {
            coarse.push({
                name: prepared.templates[i].name,
                side: coarseSide,
                image: host.resize(prepared.templates[i].image, [coarseSide, coarseSide])
            });
        }
    } catch (error) {
        releaseTemplateImages(coarse);
        if (prepared.templates !== templates) releaseTemplateImages(prepared.templates);
        throw error;
    }
    releasePrepared(templates);
    templates.__glyphPrepared = {
        scale: bucket, templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
        coarse: coarse
    };
    return {
        templates: prepared.templates, TW: prepared.TW, ANCHOR_DY: prepared.ANCHOR_DY,
        scale: scale, cached: false, coarse: coarse
    };
}

/** Release a template and all rescue-scale copies owned by it. */
function releaseTemplateImages(templates) {
    (templates || []).forEach(function (tpl) {
        const scaled = tpl.__scaled;
        delete tpl.__scaled;
        if (scaled) Object.keys(scaled).forEach(function (key) { recycle(scaled[key]); });
        recycle(tpl.image);
    });
}

function releasePrepared(templates) {
    const memo = templates && templates.__glyphPrepared;
    if (!memo) return;
    delete templates.__glyphPrepared;
    releaseTemplateImages(memo.coarse);
    // scale≈1 borrows the originals; only the derived templates are owned by the cache.
    if (memo.templates !== templates) releaseTemplateImages(memo.templates);
}

function releaseTemplates(templates) {
    releasePrepared(templates);
    releaseTemplateImages(templates);
}

/** 从 assets 目录加载 11 个模板（灰度；跨分辨率由 prepareFor 缩放）。 */
function loadTemplates(host, dir) {
    const out = [];
    try {
        for (let i = 0; i < TPL_NAMES.length; i++) {
            const name = TPL_NAMES[i];
            const path = dir + "/tpl_" + name + ".png";
            const color = host.read(path);
            if (!color) throw new Error("模板读不到：" + path);
            try {
                const gray = host.grayscale(color);
                if (!gray) throw new Error("模板灰度转换失败：" + path);
                out.push({ name: name, image: gray });
            } finally {
                recycle(color);
            }
        }
        return out;
    } catch (error) {
        releaseTemplateImages(out);
        throw error;
    }
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

/**
 * 棋子被点起/高亮时，字形会放大，但棋子不会在同一个交点变成另一兵种。
 * 仅当全盘占位完全没变且阵营相同时，保留上一盘兵种。异色不可覆盖：
 * 同兵种吃子在后续静态帧也会占位不变；当前红黑读数必须保留。真正移动/吃子至少会
 * 改变起点占位，仍交给走法与多帧校验。原始读数须另存，供诊断使用。
 */
function preserveIdentityOnSameOccupancy(board, referenceBoard) {
    const out = board.slice();
    if (!referenceBoard || referenceBoard.length !== out.length) {
        return { board: out, held: 0 };
    }
    for (let i = 0; i < out.length; i++) {
        if (!!out[i] !== !!referenceBoard[i]) {
            return { board: out, held: 0 };
        }
    }
    let held = 0;
    for (let i = 0; i < out.length; i++) {
        if (out[i] && out[i] !== "?" && referenceBoard[i] &&
            referenceBoard[i] !== "?" && out[i] !== referenceBoard[i] &&
            (out[i] === out[i].toUpperCase()) ===
                (referenceBoard[i] === referenceBoard[i].toUpperCase())) {
            out[i] = referenceBoard[i];
            held += 1;
        }
    }
    return { board: out, held: held };
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
 * 与 `bestMatch` 同窗口语义，但**不设门限**：每个字形都拿到自己在这个窗口里的最高分，
 * 于是能算出「头名 vs 次名」的分差 —— 认字最大的风险不是没分，而是**两个字形咬得很近**
 * （真机踩过：被点起的棋子让頭名与次名只差 0.02，直接取頭名就认错子，拿去走棋就是走错）。
 * @return { name, sim, cx, cy, secondName, secondSim, gap } | null
 */
function bestMatchRanked(host, grayFrame, x, cy, templates, options, size) {
    const opts = options || {};
    const TW = pick(opts, "TW");
    const sx = pick(opts, "SEARCH_X");
    const sy = pick(opts, "SEARCH_Y");
    const rx = x - TW - sx;
    const ry = cy - TW - sy;
    const rw = (TW + sx) * 2;
    const rh = (TW + sy) * 2;
    const canClip = !!(size && rx >= 0 && ry >= 0 && rx + rw <= size.width && ry + rh <= size.height);
    const clipped = canClip ? host.clip(grayFrame, rx, ry, rw, rh) : null;
    const scored = [];
    try {
        for (let i = 0; i < templates.length; i++) {
            const res = clipped
                ? host.matchTemplate(clipped, templates[i].image, { threshold: 0, max: 1 })
                : host.matchTemplate(grayFrame, templates[i].image, {
                    region: [rx, ry, rw, rh], threshold: 0, max: 1
                });
            const hit = (res && res.matches && res.matches.length) ? res.matches[0] : null;
            if (!hit) {
                continue;
            }
            scored.push({
                name: templates[i].name, sim: hit.similarity,
                cx: hit.point.x + TW + (clipped ? rx : 0),
                cy: hit.point.y + TW + (clipped ? ry : 0)
            });
        }
    } finally {
        recycle(clipped);
    }
    if (!scored.length) {
        return null;
    }
    scored.sort(function (a, b) { return b.sim - a.sim; });
    const top = scored[0];
    return {
        name: top.name, sim: top.sim, cx: top.cx, cy: top.cy,
        secondName: scored.length > 1 ? scored[1].name : "",
        secondSim: scored.length > 1 ? scored[1].sim : -1,
        gap: scored.length > 1 ? top.sim - scored[1].sim : 1
    };
}

/** 模板按「边长 side」缩放的副本（缓存在模板对象上；同一帧里多个格子共用同一档缩放）。 */
function scaledTemplate(host, tpl, side) {
    if (tpl.image && tpl.image.width === side && tpl.image.height === side) {
        return tpl.image;   // 已经是这个尺寸（prepareTemplates 可能已经缩过）
    }
    const cache = tpl.__scaled || (tpl.__scaled = {});
    if (!cache[side]) {
        cache[side] = host.resize(tpl.image, [side, side]);
    }
    return cache[side];
}

/**
 * ★ 多尺度抢救（2026-09-25，K40 真机「某几格一直读不出」）：
 *
 * 触发面 = 读数「不放心」的格子：没匹配上（`!m`）、分不高（< SAFE_CONF）、
 * 或者读成了**不可能的字形**（士/仕 跑到九宫外）。这些格子旧行为只有一条兜底路 ——
 * 「选中放大」档（1.10/1.18），而且只在 `!m` 或士/仕 非法时才走。
 *
 * 本例（评测集 skin-c r9c7，馬）：动画中的棋子**放大 10% 且上移 12px** ——
 * 原尺寸模板最好只有 0.54（低于门限 → 未知）；同一格换成 1.10 倍模板立刻 0.88。
 * 所以这里把「缩放档」做成**一组**，并逐档看「头名−次名」的分差，只收「分够 + 拉得开」的结果：
 * 宁可留一个未知格（状态机不出手，看得见），也不要认错子（走错棋，看不出来）。
 *
 * @return { accepted, best: {name,sim,gap,scale,secondName,secondSim,cx,cy} | null }
 */
function rescueMatch(host, grayFrame, x, cy, col, row, templates, opts, size) {
    const scales = pick(opts, "RESCUE_SCALES");
    const baseTW = pick(opts, "TW");
    const conf = pick(opts, "RESCUE_CONF");
    const minGap = pick(opts, "RESCUE_MARGIN");
    const sx = pick(opts, "RESCUE_SEARCH_X");
    const sy = pick(opts, "RESCUE_SEARCH_Y");
    let best = null;
    for (let s = 0; s < scales.length; s++) {
        const half = Math.max(16, Math.round(baseTW * scales[s]));
        const list = [];
        for (let i = 0; i < templates.length; i++) {
            if (!advisorAllowed(templates[i].name, col, row)) {
                continue;   // 士/仕 只能站九宫：盘边误读不算第三枚仕
            }
            list.push({ name: templates[i].name, image: scaledTemplate(host, templates[i], half * 2) });
        }
        if (!list.length) {
            continue;
        }
        const ranked = bestMatchRanked(host, grayFrame, x, cy, list, { TW: half, SEARCH_X: sx, SEARCH_Y: sy }, size);
        if (!ranked) {
            continue;
        }
        ranked.scale = scales[s];
        if (!best || ranked.sim > best.sim) {
            best = ranked;
        }
        if (best.sim >= conf && best.gap >= minGap) {
            break;   // 这一档已经够确定：不再往下试（一档约省 3/4 的抢救开销）
        }
    }
    return { accepted: !!(best && best.sim >= conf && best.gap >= minGap), best: best };
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
 * 窗内清晰度（采样网格上的离散拉普拉斯方差）：值越小越糊。
 * py 参考用逐像素 cv2.Laplacian；这里按 MOVING_STEP 采样（帧内基准比较时口径自洽）。
 * @return number | null（越界/取不到像素）
 */
function windowSharpness(host, grayFrame, cx, cy, radius, step) {
    const n = Math.floor((radius * 2) / step) + 1;
    if (n < 3) {
        return null;
    }
    const vals = [];
    for (let j = 0; j < n; j++) {
        for (let i = 0; i < n; i++) {
            let pixel = null;
            try {
                pixel = host.pixel(grayFrame, cx - radius + i * step, cy - radius + j * step);
            } catch (ignore) {
                return null;
            }
            vals.push(host.red(pixel));
        }
    }
    let sum = 0;
    let sumSq = 0;
    let count = 0;
    for (let j = 1; j < n - 1; j++) {
        for (let i = 1; i < n - 1; i++) {
            const l = 4 * vals[j * n + i] - vals[j * n + i - 1] - vals[j * n + i + 1] -
                vals[(j - 1) * n + i] - vals[(j + 1) * n + i];
            sum += l;
            sumSq += l * l;
            count += 1;
        }
    }
    if (!count) {
        return null;
    }
    const mean = sum / count;
    return sumSq / count - mean * mean;
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
    let halfFrame = null;
    try {
        halfFrame = coarseTemplates
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
        /** ★ 2026-10-06「同帧沿用」：指纹没变的格直接采用上一帧结论的格数（见 opts.adopt） */
        let adoptedCount = 0;
        let error = "";
        /** 复核/抢救计数（宽窗重试 + 金字塔回退 + 多尺度抢救；一个对象为了能传进 matchNarrowWide） */
        const matchStats = { retries: 0, rescues: 0, rescued: 0, rescueMs: 0, notes: [] };
        /** opts.collect = true 时逐格收集「认到的字形 + 匹配分」（盘面级判决挑最弱格降级用） */
        const collect = opts.collect === true;
        const collected = collect ? [] : null;
        /** 空中子模糊门：认不准的格（候选）与认准的格（清晰度基准）——收尾统一判定 */
        const blurCandidates = [];
        const sharpRefs = [];
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
                // ★ 「本格沿用」（2026-10-06，装配见 pipeline-factory + src/vision/reuse-gate.js）：
                //   找色指纹证明这格与上一帧是同一画面时，直接用上一帧结论，跳过三道门 + 模板匹配
                //   （匹配是逐格的大头，K40 实测复核段 ~300-400ms/32 子）。载荷只含「空格 或 sim≥0.85
                //   的认准格」——低分/未知格绝不会出现在这里（reuse-gate.buildAdopt 卡的口子）。
                const kept = opts.adopt ? opts.adopt[idx] : null;
                if (kept && kept.char && kept.char !== "?") {
                    chars[idx] = kept.char;
                    adoptedCount += 1;
                    if (kept.char !== ".") {
                        inked += 1;
                        if (kept.dy != null) {
                            dySamples.push(kept.dy);
                            dySamples.sort(function (a, b) { return a - b; });
                            rowDy = dySamples[Math.floor(dySamples.length / 2)];
                        }
                        if (kept.sim != null && kept.sim >= pick(opts, "CONF") && kept.cx != null &&
                            sharpRefs.length < pick(opts, "MOVING_REF_CELLS")) {
                            sharpRefs.push([kept.cx, kept.cy]);
                        }
                        if (collected && kept.name) {
                            collected.push({ idx: idx, name: kept.name, sim: kept.sim, char: kept.char,
                                dy: kept.dy, cx: kept.cx, cy: kept.cy, adopted: true });
                        }
                    }
                    continue;
                }
                const gateStarted = host.nowMono();
                // ① 暗占比门（有子？）
                let win = null, bin = null, faceBin = null;
                let dark, inner, bright;
                try {
                    win = host.clip(grayFrame, x - TW, y - TW, winW, winW);
                    bin = host.threshold(win, 140, 255);
                    dark = countOf(host, bin, "#000000", 0, 0, winW, winW);
                    if (dark / (winW * winW) <= DARK_THR) {
                        gateMs += host.nowMono() - gateStarted;
                        continue;
                    }
                    inner = countOf(host, bin, "#000000", innerX, innerY, 40, 40);
                    faceBin = host.threshold(win, 200, 255);
                    bright = winW * winW - countOf(host, faceBin, "#000000", 0, 0, winW, winW);
                } finally {
                    recycle(faceBin);
                    recycle(bin);
                    recycle(win);
                }
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
                // ★ 2026-09-25：复核从「只在没匹配上时试 1.10/1.18 两档」升级成 rescueMatch（多档缩放 +
                //   头名/次名分差门），触发面也扩到「分不高 / 读成非法字形」—— 真机「某几格一直读不出」
                //   就是动画里放大的字形被原尺寸模板挡在门限下（分 0.54），而旧复核的条件刚好漏掉它。
                const knownChar = opts.referenceBoard && opts.referenceBoard[idx];
                const knownGlyph = GLYPH_FOR_CHAR[knownChar];
                const before = m ? { name: m.name, sim: m.sim } : null;
                const needRescue = !m || m.sim < SAFE_CONF || !advisorAllowed(m.name, c, r);
                const rescueBudgetOk = matchStats.rescues < pick(opts, "RESCUE_MAX_CELLS") &&
                    matchStats.rescueMs < pick(opts, "RESCUE_BUDGET_MS");
                if (needRescue && rescueBudgetOk) {
                    matchStats.rescues += 1;
                    const rescueStarted = host.nowMono();
                    let fixed = null;
                    try {
                        fixed = rescueMatch(host, grayFrame, x, cy, c, r, templates, opts, size);
                    } catch (rescueError) {
                        error = "多尺度抢救异常：" + rescueError;
                        break;
                    }
                    matchStats.rescueMs += host.nowMono() - rescueStarted;
                    if (fixed && fixed.accepted && fixed.best) {
                        // 旧逻辑的保护（吃子落点可能原本是另一种棋）：这个交点的可信旧字形已知时，
                        // 抢救结果必须明显胜过当前读数才允许覆盖
                        const beatsOld = !before || !knownGlyph || fixed.best.name === knownGlyph ||
                            fixed.best.sim >= before.sim + 0.05;
                        if (beatsOld) {
                            m = { name: fixed.best.name, sim: fixed.best.sim, cx: fixed.best.cx, cy: fixed.best.cy };
                            matchStats.rescued += 1;
                            matchStats.notes.push({
                                kind: "rescued", idx: idx, col: c, row: r, name: fixed.best.name,
                                sim: fixed.best.sim, gap: fixed.best.gap, scale: fixed.best.scale,
                                was: before ? before.name : "", wasSim: before ? before.sim : null
                            });
                        }
                    } else if (!m) {
                        // 抢救也没把握 ⇒ 留未知；把「读到了什么、差多少」记下来（面板/日志打得出来）
                        matchStats.notes.push({
                            kind: "unknown", gate: "match", idx: idx, col: c, row: r,
                            name: fixed && fixed.best ? fixed.best.name : "",
                            sim: fixed && fixed.best ? fixed.best.sim : null,
                            second: fixed && fixed.best ? fixed.best.secondName : "",
                            secondSim: fixed && fixed.best ? fixed.best.secondSim : null
                        });
                    }
                } else if (m && knownGlyph && m.name !== knownGlyph && rescueBudgetOk) {
                    // 同一交点的可信旧棋子被点起后可能放大。只对这一格、这一种旧字形
                    // 增加尺寸候选（不把 90 格全部扩大扫描）。
                    const expected = templates.filter(function (tpl) { return tpl.name === knownGlyph; });
                    if (expected.length) {
                        matchStats.rescues += 1;
                        try {
                            const selected = rescueMatch(host, grayFrame, x, cy, c, r, expected, opts, size);
                            // 吃子落点可能原本是另一种棋：旧字形必须明显胜过当前读数才可覆盖。
                            if (selected && selected.accepted && selected.best &&
                                selected.best.sim >= m.sim + 0.03) {
                                m = {
                                    name: selected.best.name, sim: selected.best.sim,
                                    cx: selected.best.cx, cy: selected.best.cy
                                };
                                matchStats.notes.push({
                                    kind: "held", idx: idx, col: c, row: r, name: selected.best.name,
                                    sim: selected.best.sim, gap: selected.best.gap, scale: selected.best.scale,
                                    was: before ? before.name : "", wasSim: before ? before.sim : null
                                });
                            }
                        } catch (selectedError) {
                            error = "旧字形定向复核异常：" + selectedError;
                            break;
                        }
                    }
                }
                matchMs += host.nowMono() - matchStarted;
                if (!m) {
                    chars[idx] = "?";
                    unknown += 1;
                    blurCandidates.push({ idx: idx, col: c, row: r, cx: x, cy: cy, unknown: true });
                    continue;
                }
                if (m.sim >= pick(opts, "CONF") && sharpRefs.length < pick(opts, "MOVING_REF_CELLS")) {
                    sharpRefs.push([m.cx, m.cy]);
                }
                // 行自适应：实测 dy 并入本行样本（实时中位数，抗单个错配）
                dySamples.push(m.cy - cy);
                dySamples.sort(function (a, b) { return a - b; });
                rowDy = dySamples[Math.floor(dySamples.length / 2)];
                // ⑤ 阵营
                const sideStarted = host.nowMono();
                if (SIDED[m.name]) {
                    let side = sideOf(host, image, m.cx, m.cy, opts);
                    if (side === "?") {
                        // 阵营抢救：笔画颜色样本太少（半透明遮罩/动画）时，把采样步调密、下限降下来重判；
                        // R-B 门限不变（红墨的 R-B 很硬），所以不会把黑子判成红。
                        side = sideOf(host, image, m.cx, m.cy, Object.assign({}, opts, {
                            SIDE_STEP: 2, SIDE_MIN_DARK: 12
                        }));
                    }
                    if (side === "?") {
                        chars[idx] = "?";
                        unknown += 1;
                        matchStats.notes.push({
                            kind: "unknown", gate: "side", idx: idx, col: c, row: r,
                            name: m.name, sim: m.sim
                        });
                        blurCandidates.push({ idx: idx, col: c, row: r, cx: m.cx, cy: m.cy, unknown: true });
                        sideMs += host.nowMono() - sideStarted;
                        continue;
                    }
                    chars[idx] = SIDED[m.name][side === "r" ? 0 : 1];
                } else {
                    chars[idx] = FIXED[m.name];
                }
                if (m.sim < SAFE_CONF) {
                    // 勉强对上（0.70~SAFE_CONF）也可能是「空中子」：JS 的匹配比 py 多几种窗，
                    // 偶尔能给模糊字形凑出一个过门限的分 —— 收尾统一做清晰度判定。
                    blurCandidates.push({ idx: idx, col: c, row: r, cx: m.cx, cy: m.cy, unknown: false });
                }
                if (collected) {
                    // char/dy/cx/cy 是给「同帧沿用」（reuse-gate.buildAdopt）留的：下一帧指纹没变时
                    // 这格直接照抄（含着法行自适应要用的 dy 与清晰度基准要用的中心）。
                    collected.push({ idx: idx, name: m.name, sim: m.sim, char: chars[idx],
                        dy: m.cy - cy, cx: m.cx, cy: m.cy });
                }
                sideMs += host.nowMono() - sideStarted;
            }
        }
        // ★ 「空中子」模糊门（2026-10-04）：认不准的格若整窗明显比「认准的格」糊，
        //   判「子在空中/没落稳」（落子动画中间帧）按空处理：不记未知、不数它。
        let movingDropped = 0;
        if (blurCandidates.length && sharpRefs.length) {
            const movingStep = pick(opts, "MOVING_STEP");
            const refVals = [];
            for (let i = 0; i < sharpRefs.length; i++) {
                const value = windowSharpness(host, grayFrame, sharpRefs[i][0], sharpRefs[i][1], TW, movingStep);
                if (value != null) {
                    refVals.push(value);
                }
            }
            if (refVals.length) {
                refVals.sort(function (a, b) { return a - b; });
                const base = refVals[Math.floor(refVals.length / 2)];
                const gate = base * pick(opts, "MOVING_RATIO");
                for (let i = 0; i < blurCandidates.length; i++) {
                    const cand = blurCandidates[i];
                    const value = windowSharpness(host, grayFrame, cand.cx, cand.cy, TW, movingStep);
                    if (value != null && value < gate) {
                        chars[cand.idx] = ".";
                        if (cand.unknown) {
                            unknown -= 1;
                        }
                        movingDropped += 1;
                        matchStats.notes.push({
                            kind: "moving", idx: cand.idx, col: cand.col, row: cand.row,
                            sharp: Math.round(value), base: Math.round(base)
                        });
                    }
                }
            }
        }
        return {
            chars: chars, inked: inked, adopted: adoptedCount, unknown: unknown, retries: matchStats.retries,
            rescued: matchStats.rescued, rescues: matchStats.rescues, moving: movingDropped,
            notes: matchStats.notes,
            rescueMs: matchStats.rescueMs, cells: collected,
            ms: host.nowMono() - started,
            profile: { gates: gateMs, match: matchMs, side: sideMs },
            error: error
        };
    } finally {
        recycle(halfFrame);
        recycle(grayFrame);
    }
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
    releaseTemplates: releaseTemplates,
    normalizeBoard: normalizeBoard,
    countUnknown: countUnknown,
    preserveIdentityOnSameOccupancy: preserveIdentityOnSameOccupancy,
    bestMatch: bestMatch,
    bestMatchRanked: bestMatchRanked,
    rescueMatch: rescueMatch,
    sideOf: sideOf,
    windowSharpness: windowSharpness,
    recognize: recognize,
    charsToFen: charsToFen
};
