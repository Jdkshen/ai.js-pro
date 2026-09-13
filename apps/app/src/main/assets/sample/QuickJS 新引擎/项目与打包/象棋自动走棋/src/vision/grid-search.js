// @engine quickjs
/**
 * 几何搜索（纯函数）：用**缓存的检测框**给候选网格打分，挑出棋盘所在的那组几何。
 *
 * 为什么不让每个候选都跑一次模型（原 `pickGrid` 的做法）：
 *   候选 171 个 → 171 次完整识别（含模型加载/关闭、预处理、推理、像素判色）≈ 19.8 秒/帧，
 *   而且「19.8 秒」里到底多少是推理、多少是其它，根本分不清（执行单 §4.1）。
 *   对齐本身是**纯坐标运算**：框心 → 最近交点 → 残差/冲突/未知格。所以：
 *     一次检测（在覆盖所有候选棋盘的固定区域）→ 缓存框 → 之后每个候选用坐标运算评分。
 *
 * 设计的硬要求（都来自真机事故）：
 *   · 计数走 `cells.js`（空格是 null，不是「非 . 非 ?」）；解析失败**不能**算 0 违规；
 *   · 代价里必须有「子力数先验」，否则「全空/挂在棋盘外」的网格代价为 0 会被选中；
 *   · **不设「至少 24 枚」这种通用门槛**（合法残局可能只有 2~8 枚）；
 *   · 颜色在这里未知（检测器只给兵种），所以校验用**颜色无关**口径：
 *     兵种总数不得超过两侧上限之和、将帅必须正好 2 个；
 *   · 已有可信局面时可传 `expectPieces` 做**软约束**（允许吃子），会话重置后不得继承；
 *   · 失败必须给出可读原因，且失败候选不能进入成功率分母。
 */
"use strict";

const geometryModule = require("./geometry.js");
const assignment = require("./cell-assignment.js");
const cellsContract = require("./cells.js");

/** 单侧上限（与 fen.js/consistency.js 一致）；颜色未知时用两倍。 */
const MAX_PER_TYPE = { R: 2, N: 2, B: 2, A: 2, K: 1, C: 2, P: 5 };
const TYPES = ["R", "N", "B", "A", "K", "C", "P"];

const DEFAULTS = {
    radius: 30,
    step: 6,
    fineRadius: 5,
    fineStep: 1,
    /** 至少要有几枚子才算「看到了棋盘」（合法残局可以很少，但不能是空盘） */
    minPieces: 2,
    /** 未知格上限 */
    maxUnknown: 4,
    /** 必须看到双方将帅（残局也一样，将帅永远在盘上） */
    requireKings: true,
    /** 检测框落在棋盘内的比例下限（棋盘证据） */
    minCoverage: 0.6,
    /** 软先验权重：|pieces - expectPieces| * weight */
    expectWeight: 20,
    /** 每次搜索最多评多少候选（防御性上限） */
    maxTried: 4000
};

/** 颜色无关的合法性检查（检测器只给兵种，颜色由像素另判）。 */
function typeAudit(chars) {
    const counts = cellsContract.count(chars);
    const perType = {};
    for (let i = 0; i < TYPES.length; i++) {
        perType[TYPES[i]] = 0;
    }
    const list = chars || [];
    for (let i = 0; i < list.length; i++) {
        const ch = list[i];
        if (cellsContract.isPieceChar(ch)) {
            const key = String(ch).toUpperCase();
            perType[key] = (perType[key] || 0) + 1;
        }
    }
    const overLimit = [];
    for (let i = 0; i < TYPES.length; i++) {
        const type = TYPES[i];
        const limit = MAX_PER_TYPE[type] * 2;
        if (perType[type] > limit) {
            overLimit.push(type + "×" + perType[type] + ">" + limit);
        }
    }
    return { counts: counts, perType: perType, kings: perType.K, overLimit: overLimit };
}

/** 单个候选的评分（纯坐标运算）。 */
function scoreCandidate(detections, grid, options) {
    const opts = options || {};
    const assigned = assignment.assign(detections, grid, opts.assign);
    const chars = assignment.cellsToChars(assigned.cells);
    const audit = typeAudit(chars);
    const unknown = assigned.stats.unknown;
    const pieces = audit.counts.pieces;
    const empty = audit.counts.empty;
    const total = detections ? detections.length : 0;
    const settled = assigned.stats.placed;
    // 「棋盘证据」= 有多少检测框真的被定成了子（参与冲突/残差过大的不算）。
    // ⚠️ 必须单调地参与代价：否则「偏 40px、7 个框全冲突」的退化网格只花 70 分，
    //    反而低于真值网格的「兵种超限 300」，退化候选会胜出（实测踩过）。
    const coverage = total > 0 ? settled / total : 0;
    const residual = 0;
    void residual;
    let residualSum = 0;
    if (assigned.cells) {
        for (let i = 0; i < assigned.cells.length; i++) {
            const cell = assigned.cells[i];
            if (cell && cell.state === assignment.STATE_PIECE) {
                residualSum += cell.residual || 0;
            }
        }
    }

    // 代价权重（实测调过）：
    //   「没有棋盘证据」必须压倒一切 —— 否则「框全落在棋盘外」的退化候选（出界 7×50=350）
    //   会打败「有子但兵种超限」的真实候选（1000），把瞎网格选成最优（实测踩过一次）。
    let cost = unknown * 10 + assigned.stats.outOfBoard * 200 + residualSum * 50;
    // 证据不足的比例（1 - coverage）直接乘大权重：它同时压住
    // 「框全在棋盘外」与「框都在盘内但全成了冲突/未知」两种退化情况。
    cost += (1 - coverage) * 3000;
    if (pieces === 0) {
        cost += 5000;
    }
    cost += audit.overLimit.length * 300;
    cost += audit.counts.invalid.length * 1000;
    if (audit.counts.total !== cellsContract.CELLS) {
        cost += 1000;
    }
    if (typeof opts.expectPieces === "number" && isFinite(opts.expectPieces)) {
        cost += Math.abs(pieces - opts.expectPieces) * (opts.expectWeight == null ? DEFAULTS.expectWeight : opts.expectWeight);
    }

    // 接受规则（硬）
    const reasons = [];
    if (audit.counts.total !== cellsContract.CELLS) {
        reasons.push("格数不是 90（" + audit.counts.total + "）");
    }
    if (audit.counts.invalid.length) {
        reasons.push("非法字符 " + audit.counts.invalid.length + " 个");
    }
    if (pieces < (opts.minPieces == null ? DEFAULTS.minPieces : opts.minPieces)) {
        reasons.push("子力过少（" + pieces + "）");
    }
    if (unknown > (opts.maxUnknown == null ? DEFAULTS.maxUnknown : opts.maxUnknown)) {
        reasons.push("未知格过多（" + unknown + "）");
    }
    // ⚠️ 兵种超限默认只当**软惩罚**（已计入 cost）：模型把士/炮多检成 5 个是**识别**问题，
    //    不是几何问题 —— 硬拒会连带把好网格一起废掉（实测 7/9 帧因此回退）。
    //    需要严格时传 requireTypeLimits: true（例如伪标签订阅/验收）。
    if (opts.requireTypeLimits === true && audit.overLimit.length) {
        reasons.push("兵种超限：" + audit.overLimit.join(" "));
    }
    if ((opts.requireKings === false ? false : true) && audit.kings !== 2) {
        reasons.push("将帅数量异常（" + audit.kings + "）");
    }
    if (coverage < (opts.minCoverage == null ? DEFAULTS.minCoverage : opts.minCoverage)) {
        reasons.push("检测框落在棋盘内的比例过低（" + coverage.toFixed(2) + "）");
    }
    return {
        grid: grid,
        cost: cost,
        cells: assigned.cells,
        chars: chars,
        stats: assigned.stats,
        counts: audit.counts,
        perType: audit.perType,
        kings: audit.kings,
        overLimit: audit.overLimit,
        unknown: unknown,
        pieces: pieces,
        empty: empty,
        residual: residualSum,
        coverage: coverage,
        detectionCount: total,
        ok: reasons.length === 0,
        reasons: reasons
    };
}

function candidateGrids(seeds, opts) {
    const list = [];
    const radius = opts.radius == null ? DEFAULTS.radius : opts.radius;
    const step = opts.step == null ? DEFAULTS.step : opts.step;
    for (let s = 0; s < seeds.length; s++) {
        const seed = seeds[s];
        for (let dx = -radius; dx <= radius; dx += step) {
            for (let dy = -radius; dy <= radius; dy += step) {
                list.push({ dx: dx, dy: dy, seed: seed });
            }
        }
    }
    // 偏移小的先试（真机偏差本来就在十几像素），保证早停时留下的是最可信的那组
    list.sort(function (a, b) {
        return (Math.abs(a.dx) + Math.abs(a.dy)) - (Math.abs(b.dx) + Math.abs(b.dy));
    });
    return list;
}

/**
 * 用缓存检测框搜索几何。
 * @param detections  [{label, score, x, y}]，中心点为**全图**逻辑像素
 * @param options.seeds       必填，候选种子网格 [{originX, originY, stepX, stepY}]
 * @param options.expectPieces 可选软先验（上一可信局面的子力数）
 * @returns { ok, grid, cost, tried, rejected:[{dx,dy,reasons}], best:{...}, rejectionSummary }
 */
function searchByDetections(detections, options) {
    const opts = options || {};
    if (!detections || !detections.length) {
        return { ok: false, reason: "没有检测框（先检测一次再搜几何）", tried: 0, rejected: [] };
    }
    const seeds = opts.seeds || [];
    if (!seeds.length) {
        return { ok: false, reason: "缺少候选种子（seeds）", tried: 0, rejected: [] };
    }
    const maxTried = opts.maxTried == null ? DEFAULTS.maxTried : opts.maxTried;
    const orientation = opts.orientation || "RED_BOTTOM";
    let best = null;
    let tried = 0;
    const rejected = [];
    const candidates = candidateGrids(seeds, opts);
    for (let i = 0; i < candidates.length && tried < maxTried; i++) {
        const item = candidates[i];
        const grid = geometryModule.createGrid({
            originX: item.seed.originX + item.dx,
            originY: item.seed.originY + item.dy,
            stepX: item.seed.stepX,
            stepY: item.seed.stepY,
            orientation: item.seed.orientation || orientation,
            version: item.seed.version
        });
        const scored = scoreCandidate(detections, grid, opts);
        tried += 1;
        scored.dx = item.dx;
        scored.dy = item.dy;
        if (best == null || scored.cost < best.cost) {
            best = scored;
        }
        if (scored.ok && scored.cost === 0) {
            best = scored;
            break;
        }
    }
    if (best == null) {
        return { ok: false, reason: "所有候选都评不出结果", tried: tried, rejected: rejected };
    }
    // 细搜
    if (opts.fineRadius !== 0) {
        const fineRadius = opts.fineRadius == null ? DEFAULTS.fineRadius : opts.fineRadius;
        const fineStep = opts.fineStep == null ? DEFAULTS.fineStep : opts.fineStep;
        const base = best.grid;
        for (let dx = -fineRadius; dx <= fineRadius; dx += fineStep) {
            for (let dy = -fineRadius; dy <= fineRadius; dy += fineStep) {
                const grid = geometryModule.createGrid({
                    originX: base.originX + dx, originY: base.originY + dy,
                    stepX: base.stepX, stepY: base.stepY,
                    orientation: base.orientation, version: base.version
                });
                const scored = scoreCandidate(detections, grid, opts);
                tried += 1;
                if (scored.cost < best.cost) {
                    scored.dx = dx;
                    scored.dy = dy;
                    best = scored;
                }
            }
        }
    }
    const rejectionSummary = {};
    if (!best.ok) {
        for (let i = 0; i < best.reasons.length; i++) {
            const reason = best.reasons[i];
            rejectionSummary[reason] = (rejectionSummary[reason] || 0) + 1;
        }
    }
    return {
        ok: best.ok,
        reason: best.ok ? "" : ("最优候选不合用：" + best.reasons.join("；")),
        grid: best.grid,
        cost: best.cost,
        tried: tried,
        best: best,
        rejected: rejected,
        rejectionSummary: rejectionSummary,
        candidates: candidates.length
    };
}

module.exports = {
    DEFAULTS: DEFAULTS,
    MAX_PER_TYPE: MAX_PER_TYPE,
    TYPES: TYPES,
    typeAudit: typeAudit,
    scoreCandidate: scoreCandidate,
    searchByDetections: searchByDetections
};
