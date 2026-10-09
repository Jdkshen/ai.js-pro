// @engine quickjs
/**
 * 「识别直通」闸门（2026-10-06 用户口径「识别省 300ms」）：
 *   连续两帧里盘面几乎不变（等对方/等稳定时占多数帧），先用**找色墨迹指纹**核对
 *   「90 格与本格上一帧是否同一画面」：全同 ⇒ **整帧直通**（连识别都不跑）；
 *   部分变 ⇒ 只把**变化的那几格**送去重认，其余格沿用上一帧结论 ——
 *   跳过三道门 + 模板匹配（字形识别每帧的绝对大头，K40 实测复核段 ~300-400ms/32 子）。
 *
 * 为什么指纹用**找色**（images.countColor，原生计数，~0.2ms/格）：
 *   · 棋子走了（起格变空/落格变有）、被吃（红黑墨换边） ⇒ 相关格的计数**成百上千**地跳；
 *     木纹/光影/压缩噪声顶多抖几个像素 ⇒ 阈值（area×0.006）能干净分开。
 *   · 逻辑上「同一格、不同子、红黑暗三种墨迹计数还全一样」在象棋里不可能：
 *     任何一手棋至少动两格（起点/落点），起点必从有到空、落点必从空到有或红黑换边。
 *   · 与本链既有口径同源：`board-scan` 的 inkGate（空格先找色跳过 OCR，实测 2.9s→1.0s）、
 *     `ink-color`（判红黑）用的就是这套参考色/容差。
 *
 * 安全阀（缺一不可）：
 *   · `fresh`（杀棋复核 request.freshRead / independentRead）⇒ 整帧重读，绝不直通；
 *   · 沿用/直通只在「同一几何 + 同一 side + 同一 referenceBoard」下成立（key 不匹配就当没缓存）；
 *   · 低分格（sim < adoptMinSim=0.85）、未知格（"?"）**不许沿用**——宁可多跑一次；
 *   · 宿主没有 countColor 能力（离线桩）⇒ 全部当「没这回事」，行为与从前一字不差。
 *
 * 装配在 `pipeline-factory.createGlyphSource`；本模块只管「判」与「记账」，不碰识别本身。
 */
"use strict";

const DEFAULTS = {
    /** 中心窗边长 = 格距 × ratio（盖住盘面墨迹主体；与 ink-color 0.72×框 ≈ 0.59×格距 同量级）。 */
    ratio: 0.62,
    minSide: 12,
    /** 找色参考色与容差（与 src/vision/ink-color.js 同一套真机标定值）。 */
    red: "#83412D",
    black: "#282830",
    dark: "#303030",
    threshold: 24,
    darkThreshold: 40,
    /** 计数相当 = 每通道差 ≤ max(tolMin, area × tolShare)（抖动只有几个像素；真变化几百上千）。 */
    tolShare: 0.006,
    tolMin: 4,
    /** 沿用一格的匹配分下限（明显高过 CONF=0.70 / SAFE_CONF=0.82 —— 只沿用「很确定」的格）。 */
    adoptMinSim: 0.85
};

/** 合法「可沿用」的棋子字符（含空 "."，不含未知 "?"）。 */
const KEEP_RE = /^[RrNnCcPpKkAaBb.]$/;

/** 某格的中心窗（格点为中心；返回整数 {x,y,width,height}，供 countColor 的 region）。 */
function regionOf(grid, col, row, options) {
    const opts = options || DEFAULTS;
    const step = Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY));
    const side = Math.max(opts.minSide || DEFAULTS.minSide, Math.round(step * (opts.ratio || DEFAULTS.ratio)));
    const cx = Math.round(grid.originX + col * grid.stepX);
    const cy = Math.round(grid.originY + row * grid.stepY);
    return { x: cx - Math.floor(side / 2), y: cy - Math.floor(side / 2), width: side, height: side };
}

/** 数一次找色：宿主异常/缺能力一律当「数不出来」（null）。 */
function countOnce(countColor, image, color, region, threshold) {
    try {
        const n = countColor(image, color, { region: region, threshold: threshold });
        return n == null ? null : (Number(n) || 0);
    } catch (ignore) {
        return null;
    }
}

/** 90 格指纹：每格 [红墨, 黑墨, 暗墨, area]；某格数不出来 → null（保守：那格永远算「变了」）。 */
function fingerprintAll(countColor, image, grid, options) {
    const opts = options || DEFAULTS;
    const out = new Array(90);
    for (let r = 0; r < 10; r++) {
        for (let c = 0; c < 9; c++) {
            const idx = r * 9 + c;
            const region = regionOf(grid, c, r, opts);
            const red = countOnce(countColor, image, opts.red, region, opts.threshold);
            const black = countOnce(countColor, image, opts.black, region, opts.threshold);
            const dark = countOnce(countColor, image, opts.dark, region, opts.darkThreshold);
            out[idx] = (red == null || black == null)
                ? null
                : [red, black, dark == null ? 0 : dark, region.width * region.height];
        }
    }
    return out;
}

/** 两帧指纹对比：容差内的抖动不算变化；null（数不出来）一律算变化。 */
function compare(prev, next, tol) {
    const changed = [];
    for (let i = 0; i < 90; i++) {
        const a = prev ? prev[i] : null;
        const b = next ? next[i] : null;
        if (!a || !b) {
            changed.push(i);
            continue;
        }
        if (Math.abs(a[0] - b[0]) > tol || Math.abs(a[1] - b[1]) > tol || Math.abs(a[2] - b[2]) > tol) {
            changed.push(i);
        }
    }
    return { changed: changed, same: changed.length === 0 };
}

/** 沿用缓存的键：几何（0.01px 粒度）+ side + referenceBoard（都影响识别结论，变一个就重读）。 */
function keyOf(grid, options) {
    const g = [grid.originX, grid.originY, grid.stepX, grid.stepY].map(function (v) {
        return Math.round(Number(v) * 100) / 100;
    }).join(",");
    const side = options && options.side != null ? String(options.side) : "-";
    const ref = options && options.referenceBoard ? options.referenceBoard.join("") : "-";
    return g + "|" + side + "|" + ref;
}

/**
 * 从一次识别结果（recognizer 层：chars/cells）建「可沿用」表（稀疏数组[90]）：
 *   · 空格（"."）⇒ 可沿用（无匹配数据）；
 *   · 认准的棋子（匹配分 ≥ adoptMinSim）⇒ 可沿用（带 name/sim/dy/cx/cy，供 rowDy/清晰度基准复用）；
 *   · 未知（"?"）/低分/没有匹配数据 ⇒ null（每帧照旧重跑）。
 */
function buildAdopt(rec, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const out = new Array(90);
    const chars = (rec && rec.chars) || [];
    const cells = (rec && rec.cells) || [];
    const byIdx = {};
    for (let i = 0; i < cells.length; i++) {
        const one = cells[i];
        if (one && one.idx != null) {
            byIdx[one.idx] = one;
        }
    }
    for (let i = 0; i < 90; i++) {
        const ch = chars[i];
        if (ch === ".") {
            out[i] = { char: "." };
            continue;
        }
        if (!ch || !KEEP_RE.test(String(ch)) || ch === ".") {
            out[i] = null;
            continue;
        }
        const one = byIdx[i];
        const sim = one && one.sim != null ? Number(one.sim) : null;
        if (!one || !(sim >= opts.adoptMinSim)) {
            out[i] = null;
            continue;
        }
        out[i] = {
            char: String(ch), name: one.name, sim: sim,
            dy: one.dy == null ? null : one.dy,
            cx: one.cx == null ? null : one.cx,
            cy: one.cy == null ? null : one.cy
        };
    }
    return out;
}

/** 直通结果的「快照」：数组全部克隆 —— 防下游就地修补把缓存弄脏（2026-09-24 盘面就地修补踩过）。 */
function snapshotResult(result) {
    if (!result) {
        return null;
    }
    return Object.assign({}, result, {
        chars: (result.chars || []).slice(),
        rawChars: (result.rawChars || result.chars || []).slice(),
        stats: Object.assign({}, result.stats || {}),
        validation: result.validation ? Object.assign({}, result.validation) : result.validation,
        resolvedFilled: (result.resolvedFilled || []).slice(),
        resolvedDemoted: (result.resolvedDemoted || []).slice(),
        resolvedFlipped: (result.resolvedFlipped || []).slice()
    });
}

/**
 * @param deps { countColor, nowMono?, log?, options? } —— countColor 缺省 = 整套直通关闭
 */
function create(deps) {
    const d = deps || {};
    const opts = Object.assign({}, DEFAULTS, d.options || {});
    const countColor = typeof d.countColor === "function" ? d.countColor : null;
    const now = typeof d.nowMono === "function" ? d.nowMono : function () { return Date.now(); };
    const log = typeof d.log === "function" ? d.log : function () { };
    let cache = null;      // { key, fp, adopt: [90], result: 快照 | null }
    let pending = null;    // { key, fp } —— decide 算了指纹、等 commit 落账
    let lastLogAt = 0;
    const counters = { frames: 0, reprises: 0, adoptRuns: 0, fulls: 0, fpMs: 0 };

    /** 直通/沿用的日志限频：默认 6s 一条，免得刷屏。 */
    function maybeLog(text) {
        const t = now();
        if (t - lastLogAt < 6000) {
            return;
        }
        lastLogAt = t;
        log(text);
    }

    /**
     * 每帧入口：算指纹 → 对比缓存 → 给结论。
     * @return null（没能力/异常：走原路）| { mode:"reprise"|"adopt"|"full", fp, ms, changed?, adopt?, result? }
     */
    function decide(image, grid, options) {
        if (!countColor || !image || !grid) {
            return null;
        }
        counters.frames += 1;
        const started = now();
        let counts = null;
        try {
            counts = fingerprintAll(countColor, image, grid, opts);
        } catch (ignore) {
            return null;
        }
        const ms = Math.max(0, Math.round(now() - started));
        counters.fpMs += ms;
        const key = keyOf(grid, options || {});
        pending = { key: key, fp: counts };
        if (cache && cache.key === key && !(options && options.fresh === true)) {
            const sample = counts[0] || counts[44] || [0, 0, 0, 1];
            const tol = Math.max(opts.tolMin, Math.round((sample[3] || 1) * opts.tolShare));
            const cmp = compare(cache.fp, counts, tol);
            if (cmp.same && cache.result) {
                counters.reprises += 1;
                maybeLog("识别直通：画面与上帧一致（指纹 " + ms + "ms；直通累计 " + counters.reprises +
                    " 次，只重认累计 " + counters.adoptRuns + " 次）");
                return { mode: "reprise", fp: counts, ms: ms, result: cache.result, changed: cmp.changed };
            }
            const changedMap = {};
            for (let k = 0; k < cmp.changed.length; k++) {
                changedMap[cmp.changed[k]] = true;
            }
            const adopt = {};
            let adoptable = 0;
            for (let i = 0; i < 90; i++) {
                if (changedMap[i]) {
                    continue;
                }
                const one = cache.adopt ? cache.adopt[i] : null;
                if (one) {
                    adopt[i] = one;
                    adoptable += 1;
                }
            }
            if (adoptable > 0) {
                counters.adoptRuns += 1;
                maybeLog("识别沿用：90 格里沿用 " + adoptable + " 格（只重认 " + (90 - adoptable) +
                    " 格；累计沿用 " + counters.adoptRuns + " 次）");
                return { mode: "adopt", fp: counts, ms: ms, adopt: adopt, changed: cmp.changed };
            }
        }
        counters.fulls += 1;
        return { mode: "full", fp: counts, ms: ms };
    }

    /** 识别跑完落账：把指纹 + 可沿用表 + （可直通的）结果快照存起来。 */
    function commit(grid, options, rec, result) {
        if (!pending || !rec) {
            return;
        }
        const reusable = !!(result && result.ok && result.unknownCells === 0 &&
            result.validation && result.validation.ok);
        cache = {
            key: pending.key,
            fp: pending.fp,
            adopt: buildAdopt(rec, opts),
            result: reusable ? snapshotResult(result) : null
        };
        pending = null;
    }

    /** 直通回包：克隆 + 标 reused（stats.ms = 指纹核对耗时；recognizeMs = 0）。 */
    function repriseResult(decision) {
        const snap = decision && decision.result;
        if (!snap) {
            return null;
        }
        const stats = Object.assign({}, snap.stats || {});
        stats.ms = decision.ms;
        stats.recognizeMs = 0;
        stats.reused = true;
        stats.adopted = 90;
        return Object.assign({}, snap, {
            chars: (snap.chars || []).slice(),
            rawChars: (snap.rawChars || snap.chars || []).slice(),
            stats: stats,
            reused: true
        });
    }

    return {
        decide: decide,
        commit: commit,
        repriseResult: repriseResult,
        buildAdopt: function (rec) { return buildAdopt(rec, opts); },
        stats: function () { return Object.assign({}, counters); },
        reset: function () { cache = null; pending = null; }
    };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    regionOf: regionOf,
    compare: compare,
    keyOf: keyOf,
    buildAdopt: buildAdopt,
    create: create
};
