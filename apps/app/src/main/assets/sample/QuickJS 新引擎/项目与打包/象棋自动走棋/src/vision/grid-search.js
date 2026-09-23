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
    /**
     * 未知格上限：**默认只作为代价罚项，不再一票否决**（2026-09-22 多证据融合方案 §10）。
     * 「未知格多」是识别噪声的证据，不是几何错的证据；硬拒会把「分类差、几何对」的帧
     * 一条道堵死（真机：新手机 unknown 7 / coverage 0.50 ⇒ pickGrid 连续拒绝、永久定不了位）。
     * 需要严格求值时显式传 `requireUnknown: true`（评估/验收）。
     */
    maxUnknown: 4,
    /**
     * 是否**硬性**要求看到双方将帅。默认 false = 只当软惩罚（cost +250）。
     * ⚠️ 2026-09-20 真机：检测模型对帅/将召回差（面板日志里反复出现「红帅数量 0」），
     *    硬拒会让**每一个候选**都被否掉 ⇒ `pickGrid 也失败：最优候选不合用：将帅数量异常（1）`
     *    ⇒ 定位彻底做不出来（比“网格选错”还严重）。
     *    与上面「兵种超限默认软惩罚」同一个道理：缺将帅是**识别**问题，不是几何问题；
     *    几何侧的证据有 coverage/unknown/残差，另加面板的 `grid-sanity` 极端行体检。
     *    需要严格求值时（评估工具/验收）显式传 `requireKings: true`。
     */
    requireKings: false,
    /**
     * 检测框落在棋盘内的比例下限：**同 maxUnknown，默认软门槛**（代价里权重 3000）。
     * 严格求值传 `requireCoverage: true`。
     */
    minCoverage: 0.6,
    /**
     * 步距（格距）缩放候选：种子格距偏 3%~5%（换分辨率/游戏缩放/皮肤）时，只搜平移
     * 永远救不回来（边缘格累计偏半格，真机偏差 49px）。±2%/±4% 五档一起搜，候选 ×5。
     */
    stepScales: [1, 0.98, 1.02, 0.96, 1.04],
    /**
     * 平移搜索半径 = 格距 × 该比例（与固定 radius 取较大的那个）；
     * 多证据融合方案要求「原点 ±0.5 格」——线定位偶尔把第一条线锚错半格，
     * 调用方习惯传的 ±18px 那段参数够不到真值。默认 null = 只用固定 radius（兼容旧调用）。
     */
    radiusRatio: null,
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

/**
 * 对一次完整识别做统一的执行前验收。detect/cached 两条定位路径都必须走这里，
 * 不能把 recognizer 的 `ok`（它只表示能生成布局串）误当成“棋盘可执行”。
 */
function auditRecognition(chars, evidence, options) {
    const opts = options || {};
    const info = evidence || {};
    const audit = typeAudit(chars || []);
    const counts = audit.counts;
    const reasons = [];
    const minPieces = opts.minPieces == null ? DEFAULTS.minPieces : opts.minPieces;
    const maxUnknown = opts.maxUnknown == null ? DEFAULTS.maxUnknown : opts.maxUnknown;
    const minCoverage = opts.minCoverage == null ? DEFAULTS.minCoverage : opts.minCoverage;
    const detectionCount = info.detectionCount == null ? 0 : info.detectionCount;
    const placed = info.placed == null ? counts.pieces : info.placed;
    const coverage = detectionCount > 0 ? placed / detectionCount : 0;

    if (counts.total !== cellsContract.CELLS) reasons.push("格数不是 90（" + counts.total + "）");
    if (counts.invalid.length) reasons.push("非法字符 " + counts.invalid.length + " 个");
    if (counts.pieces < minPieces) reasons.push("子力过少（" + counts.pieces + "）");
    if (counts.pieces > 32) reasons.push("子力超过 32（" + counts.pieces + "）");
    if (counts.unknown > maxUnknown) reasons.push("未知格过多（" + counts.unknown + "）");
    if (opts.requireKings !== false && audit.kings !== 2) reasons.push("将帅数量异常（" + audit.kings + "）");
    if (opts.requireOpposingKings !== false) {
        let redKing = 0;
        let blackKing = 0;
        for (let i = 0; i < (chars || []).length; i++) {
            if (chars[i] === "K") redKing += 1;
            if (chars[i] === "k") blackKing += 1;
        }
        if (redKing !== 1 || blackKing !== 1) reasons.push("双方将帅不完整（红 " + redKing + " / 黑 " + blackKing + "）");
    }
    if (audit.overLimit.length) reasons.push("兵种超限：" + audit.overLimit.join(" "));
    if (detectionCount <= 0) reasons.push("没有检测框");
    if (coverage < minCoverage) reasons.push("检测框落在棋盘内的比例过低（" + coverage.toFixed(2) + "）");
    if ((info.outOfBoard || 0) > 0 && opts.rejectOutOfBoard !== false) {
        reasons.push("检测框越出棋盘（" + info.outOfBoard + "）");
    }
    return {
        ok: reasons.length === 0,
        reason: reasons.join("；"),
        reasons: reasons,
        counts: counts,
        perType: audit.perType,
        kings: audit.kings,
        overLimit: audit.overLimit,
        coverage: coverage,
        detectionCount: detectionCount,
        placed: placed
    };
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
    // 将帅必须 2 枚（残局也一样）：但模型会漏检，所以这里只当**软惩罚**，
    // 让「代价最小」与「合法」同向 —— 否则代价最小的候选常常恰好缺一个将帅，
    // 再由硬规则一票否决，把整次搜索废掉（真机 1080×2400 上踩过）。
    cost += audit.kings === 2 ? 0 : 250;
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
    // ⚠️ 2026-09-22（多证据融合方案 §10）：定位阶段未知格/覆盖率**默认只罚不拒** ——
    //    它们是识别噪声的证据，不是几何错的证据。代价里仍按 unknown*10 与 (1-coverage)*3000
    //    参与排序；读局阶段要严格走 auditRecognition（它保留全部硬门槛），或显式传 requireUnknown。
    if (opts.requireUnknown === true &&
        unknown > (opts.maxUnknown == null ? DEFAULTS.maxUnknown : opts.maxUnknown)) {
        reasons.push("未知格过多（" + unknown + "）");
    }
    // ⚠️ 兵种超限默认只当**软惩罚**（已计入 cost）：模型把士/炮多检成 5 个是**识别**问题，
    //    不是几何问题 —— 硬拒会连带把好网格一起废掉（实测 7/9 帧因此回退）。
    //    需要严格时传 requireTypeLimits: true（例如伪标签订阅/验收）。
    if (opts.requireTypeLimits === true && audit.overLimit.length) {
        reasons.push("兵种超限：" + audit.overLimit.join(" "));
    }
    if (opts.requireKings === true && audit.kings !== 2) {
        reasons.push("将帅数量异常（" + audit.kings + "）");
    }
    if (opts.requireCoverage === true &&
        coverage < (opts.minCoverage == null ? DEFAULTS.minCoverage : opts.minCoverage)) {
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
    const stepScales = opts.stepScales == null ? DEFAULTS.stepScales : opts.stepScales;
    const radiusRatio = opts.radiusRatio == null ? DEFAULTS.radiusRatio : opts.radiusRatio;
    for (let s = 0; s < seeds.length; s++) {
        const seed = seeds[s];
        // 平移范围：固定 radius 与「格距 × radiusRatio」取大者（方案：原点 ±0.5 格）；
        // 横竖格距可能差 1%~2%，两个轴分别算。
        const radiusX = radiusRatio == null ? radius
            : Math.max(radius, Math.round(Math.abs(seed.stepX || 0) * radiusRatio));
        const radiusY = radiusRatio == null ? radius
            : Math.max(radius, Math.round(Math.abs(seed.stepY || 0) * radiusRatio));
        // 粗扫步长自适应：范围变宽后还按原 step 会爆候选量（±58px / 4px ≈ 900 个/轴）
        const maxRadius = Math.max(radiusX, radiusY);
        const coarseStep = Math.max(step, Math.round(maxRadius / 6) || 1);
        for (let si = 0; si < stepScales.length; si++) {
            const scale = stepScales[si];
            for (let dx = -radiusX; dx <= radiusX; dx += coarseStep) {
                for (let dy = -radiusY; dy <= radiusY; dy += coarseStep) {
                    list.push({ dx: dx, dy: dy, scale: scale, seed: seed });
                }
            }
        }
    }
    // 偏移小的先试（真机偏差本来就在十几像素），保证早停时留下的是最可信的那组；
    // 平移相同时先试原始格距（scale=1），缩放档排在后面。
    list.sort(function (a, b) {
        const da = Math.abs(a.dx) + Math.abs(a.dy);
        const db = Math.abs(b.dx) + Math.abs(b.dy);
        if (da !== db) {
            return da - db;
        }
        return Math.abs(a.scale - 1) - Math.abs(b.scale - 1);
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
    // best = 代价最低（可能不合用）；bestAccepted = 代价最低**且通过接受规则**的。
    // 接受规则是「候选资格」，所以要在候选之间挑选，而不是只拿来否决代价最小的那一个。
    let best = null;
    let bestAccepted = null;
    let tried = 0;
    const rejected = [];
    const scoredAll = [];
    const candidates = candidateGrids(seeds, opts);
    for (let i = 0; i < candidates.length && tried < maxTried; i++) {
        const item = candidates[i];
        const scale = item.scale == null ? 1 : item.scale;
        const grid = geometryModule.createGrid({
            originX: item.seed.originX + item.dx,
            originY: item.seed.originY + item.dy,
            stepX: item.seed.stepX * scale,
            stepY: item.seed.stepY * scale,
            orientation: item.seed.orientation || orientation,
            version: item.seed.version
        });
        const scored = scoreCandidate(detections, grid, opts);
        tried += 1;
        scored.dx = item.dx;
        scored.dy = item.dy;
        scored.scale = scale;
        scoredAll.push(scored);
        if (best == null || scored.cost < best.cost) {
            best = scored;
        }
        if (scored.ok && (bestAccepted == null || scored.cost < bestAccepted.cost)) {
            bestAccepted = scored;
        }
        if (bestAccepted && bestAccepted.cost === 0) {
            break;
        }
    }
    if (best == null) {
        return { ok: false, reason: "所有候选都评不出结果", tried: tried, rejected: rejected };
    }
    // 细搜：对**代价最低的 K 个候选 + 已合用的最优**各细搜一遍。
    // 只围绕一个锚点细搜（±fineRadius，默认 5px）不够：接受规则里的「将帅必须 2 枚」
    // 对 1~2px 很敏感，而粗扫是 4~6px 步长，好几何可能整个落在锚点的细搜窗口之外
    // （真机 1080×2400 实测：老种子采到 (83,670) 通过、新种子锚点差 2px 就全废）。
    const anchorCount = opts.fineAnchors == null ? 5 : opts.fineAnchors;
    const anchors = [];
    if (anchorCount > 0 && opts.fineRadius !== 0) {
        const byCost = scoredAll.slice().sort(function (a, b) { return a.cost - b.cost; });
        for (let i = 0; i < byCost.length && anchors.length < anchorCount; i++) {
            anchors.push(byCost[i]);
        }
        if (bestAccepted && anchors.indexOf(bestAccepted) < 0) {
            anchors.push(bestAccepted);
        }
    }
    if (anchors.length) {
        const fineRadius = opts.fineRadius == null ? DEFAULTS.fineRadius : opts.fineRadius;
        const fineStep = opts.fineStep == null ? DEFAULTS.fineStep : opts.fineStep;
        for (let a = 0; a < anchors.length; a++) {
            const base = anchors[a].grid;
            for (let dx = -fineRadius; dx <= fineRadius; dx += fineStep) {
                for (let dy = -fineRadius; dy <= fineRadius; dy += fineStep) {
                    if (dx === 0 && dy === 0) {
                        continue;   // 粗扫已经评过
                    }
                    const grid = geometryModule.createGrid({
                        originX: base.originX + dx, originY: base.originY + dy,
                        stepX: base.stepX, stepY: base.stepY,
                        orientation: base.orientation, version: base.version
                    });
                    const scored = scoreCandidate(detections, grid, opts);
                    tried += 1;
                    scored.dx = dx;
                    scored.dy = dy;
                    if (scored.cost < best.cost) {
                        best = scored;
                    }
                    if (scored.ok && (bestAccepted == null || scored.cost < bestAccepted.cost)) {
                        bestAccepted = scored;
                    }
                }
            }
        }
    }
    // 能合用就用合用的：合法性优先于代价
    const chosen = bestAccepted || best;
    const rejectionSummary = {};
    if (!chosen.ok) {
        for (let i = 0; i < chosen.reasons.length; i++) {
            const reason = chosen.reasons[i];
            rejectionSummary[reason] = (rejectionSummary[reason] || 0) + 1;
        }
    }
    return {
        ok: chosen.ok,
        reason: chosen.ok ? "" : ("最优候选不合用：" + chosen.reasons.join("；")),
        grid: chosen.grid,
        cost: chosen.cost,
        tried: tried,
        best: chosen,
        bestCost: best,
        accepted: !!bestAccepted,
        rejected: rejected,
        rejectionSummary: rejectionSummary,
        candidates: candidates.length
    };
}

/**
 * 按屏幕尺寸推导默认种子（不再写死某一台手机的坐标）。
 *
 * 依据（2026-09-22，多证据融合方案 §2 配套修复）：
 *   · 天天象棋类对局页里棋盘**水平居中**：originX = (宽 - 8×格距) / 2；
 *   · 格距 ≈ 屏幕短边 × 0.107（K40 1080×2400：0.107×1080 = 115.6，实测 115.9）；
 *   · 棋盘上沿 ≈ 屏高 × 0.25 / 0.2775 / 0.31（K40 实测 666/2400 ≈ 0.2775 居中档）。
 * ⚠️ 旧版写死 K40 种子（81,666,115,114）在新机上让 pickGrid 的采样框整体错位：
 *    半数检测框落在候选棋盘外 ⇒ coverage 0.36~0.50 连续拒绝（新机 1280×2772 真机证据）。
 * @param size {width, height}（getWidth/pixelWidth 取到的像素尺寸）；无效返回 null
 * @returns 种子数组（三档 y）或 null
 */
function deriveSeeds(size) {
    if (!size || !isFinite(size.width) || !isFinite(size.height) || size.width <= 0 || size.height <= 0) {
        return null;
    }
    const minSide = Math.min(size.width, size.height);
    const cell = minSide * 0.107;
    const originX = (size.width - cell * 8) / 2;
    const ratios = [0.25, 0.2775, 0.31];
    const seeds = [];
    for (let i = 0; i < ratios.length; i++) {
        seeds.push({
            originX: Math.round(originX),
            originY: Math.round(size.height * ratios[i]),
            stepX: cell,
            stepY: cell,
            orientation: "RED_BOTTOM"
        });
    }
    return seeds;
}

/**
 * 探测裁剪用的合成网格：覆盖**所有种子**的外接框（2026-09-22）。
 *
 * 为什么（新机事故链）：pickGrid 只检测一次，裁剪区域决定了哪些棋子能进入评分。
 * 旧版只按 seeds[0] 裁 —— 设备推导的种子有 3 档 y（±5% 屏高），
 * 只锚一个种子会把其它档位的棋盘底/顶行棋子裁掉 ⇒ 那些格子全成未知格/空格。
 * stepX/stepY 取外接框的 8/9 等分，调用方再交给 detection-stage 的 cropMargin 外扩。
 * @return 合成网格；种子无效时返回 null（调用方退回旧行为）
 */
function probeGridFor(seeds) {
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (let i = 0; i < (seeds ? seeds.length : 0); i++) {
        const seed = seeds[i];
        const sx = Math.abs(seed.stepX || 0);
        const sy = Math.abs(seed.stepY || 0);
        if (!(sx > 0) || !(sy > 0)) {
            continue;
        }
        minX = Math.min(minX, seed.originX);
        minY = Math.min(minY, seed.originY);
        maxX = Math.max(maxX, seed.originX + 8 * sx);
        maxY = Math.max(maxY, seed.originY + 9 * sy);
    }
    if (!isFinite(minX) || !isFinite(minY) || !isFinite(maxX) || !isFinite(maxY)) {
        return null;
    }
    return geometryModule.createGrid({
        originX: minX,
        originY: minY,
        stepX: (maxX - minX) / 8,
        stepY: (maxY - minY) / 9,
        orientation: (seeds[0] && seeds[0].orientation) || "RED_BOTTOM"
    });
}

module.exports = {
    DEFAULTS: DEFAULTS,
    MAX_PER_TYPE: MAX_PER_TYPE,
    TYPES: TYPES,
    typeAudit: typeAudit,
    auditRecognition: auditRecognition,
    scoreCandidate: scoreCandidate,
    candidateGrids: candidateGrids,
    deriveSeeds: deriveSeeds,
    probeGridFor: probeGridFor,
    searchByDetections: searchByDetections
};
