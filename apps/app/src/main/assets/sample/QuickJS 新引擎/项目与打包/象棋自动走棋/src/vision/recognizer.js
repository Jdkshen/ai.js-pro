// @engine quickjs
/**
 * 识别链路：把「一帧画面 + 棋盘几何」变成「FEN 布局」。
 *
 * 链路（每一步的失败都如实报告，不硬猜）：
 *   1. 按**定位出来的棋盘外框**裁剪（不是写死屏幕比例 —— 写死会切掉边缘棋子）
 *   2. 模型在裁剪图上检测棋子（模型类别 = 7 个兵种字母 R N B A K C P，不含颜色）
 *   3. 检测框 + 棋盘几何 → 90 个交点（cell-assignment：保留候选/冲突/未知）
 *   4. 每个有子的格子采样 RGB → 判红黑（这个模型没有阵营，必须补）
 *   5. 兵种字母 + 阵营 → FEN 字符 → 布局串
 *
 * 本模块**不直接调用宿主接口**：裁剪 / 检测 / 取像素都由调用方注入，
 * 这样离线自检里可以用桩跑完整条链路（含错误分支）。
 */
"use strict";

const geometry = require("./geometry.js");
const assignment = require("./cell-assignment.js");
const cellsContract = require("./cells.js");
const pieceSide = require("./piece-side.js");
const fen = require("../chess/fen.js");
const consistency = require("../chess/consistency.js");
const defaults = require("../util/defaults.js");

const DEFAULTS = {
    /** 棋盘外框外扩（格距倍数） */
    cropMargin: 0.75,
    /** 检测置信度门槛 */
    confidence: 0.25,
    nms: 0.45,
    /** 格点分配：低分框直接丢掉（实测 0.2~0.35 的框基本都是假的） */
    minScore: 0.45,
    maxResidual: 0.4,
    conflictGap: 0.15,
    /** 整帧质量（0..1）：定位差就当「没看到」而不是「没有子」 */
    frameQuality: 1,
    /** 阵营采样步长（像素） */
    sideSampleStep: 3,
    /** 识别后是否用棋规反查可疑格（违规格降级为未知，而不是当事实） */
    repairConsistency: true,
    /**
     * 检测框自动对齐几何：真机实测 board-locator 给出的原点有**系统性偏差**
     * （K40 + 腾讯天天象棋：origin (81,666)，把原点挪 (-7,-14) 后未知格 6→1）。
     * 开着时先只用一次检测结果试若干偏移（很便宜：只是把框归格），挑最优几何再分类阵营。
     */
    autoAlign: false,
    /**
     * 裁剪/区域的目标宽高比（宽/高）。null = 用棋盘外框原比例。
     * 为什么要它：矩形输入模型（如 160×320）是在**竖屏整屏**上训的（实测全屏→0.969，
     * 裁成方形棋盘→0.832），所以喂它时必须给一个竖屏比例的窗口，
     * 而不是把方形棋盘裁剪 letterbox 进去（那等于人为造域差）。
     */
    regionAspect: null,
    /**
     * 检测区域怎么取："board" = 棋盘外框（可再按 regionAspect 扩成竖屏）；
     * "full" = 整张图（与「全屏→矩形」评测口径一致，矩形档的实测最佳配对）。
     */
    regionMode: "board",
    /** 对齐搜索范围：±maxShift 像素，步长 step 像素 */
    alignSearch: { maxShift: 18, step: 3 }
};

/** 认字：模型类别字母 → 中文，仅用于给人看。 */
const PIECE_NAMES = {
    R: "车", N: "马", B: "相/象", A: "仕/士", K: "帅/将", C: "炮", P: "兵/卒"
};

/**
 * @param options.image       帧或图片（交给 crop/detect 用）
 * @param options.grid        棋盘几何（location 的产物）
 * @param options.detect      (image, {confidence, nms, region?}) → 检测列表
 * @param options.useRegion   true = 用宿主自带的区域检测（官方实现会把坐标**回移**到全图，本模块不再加偏移）；
 *                            false/缺省 = 先 crop 再检测，检测结果按 crop 偏移加回去
 * @param options.crop        (image, box) → 裁剪后的图（useRegion=false 时用）
 * @param options.rgbAt       (image, x, y) → [r,g,b]（原图坐标）
 * @param options.sideToMove  "w"/"b"/null（识别本身给不出轮次，默认 null）
 */
/**
 * 认出「说不准」的格子有多少个。
 */
function countUnknown(cells) {
    let n = 0;
    for (let i = 0; i < cells.length; i++) {
        if (cells[i].state === "UNKNOWN") {
            n++;
        }
    }
    return n;
}

/** 全部格子的残差之和：越小说明检测框越贴近格心。 */
function sumResidual(cells) {
    let sum = 0;
    for (let i = 0; i < cells.length; i++) {
        sum += cells[i].residual || 0;
    }
    return sum;
}

/**
 * 单个框到最近交点的「贴合度代价」：归一化距离（1 = 半格距），封顶 1，
 * 免得远处的假框把搜索带偏；分数太低（默认 <0.2）的框完全不参与拟合。
 */
function boxFit(det, grid, floorScore) {
    if (!det || !isFinite(det.x) || !isFinite(det.y)) {
        return null;
    }
    const score = det.score == null ? 1 : det.score;
    if (score < floorScore) {
        return null;
    }
    const hit = geometry.nearest(grid, det.x, det.y);
    return {
        score: score,
        /** 框心到最近交点的距离（格距为单位，不含出界信息） */
        distance: Math.min(1, hit.inside ? hit.normalized : 1),
        inside: hit.inside
    };
}

/**
 * 几何代价：**只看框与点阵的贴合度**，故意不看「认出了几个子 / 几个未知格 / 几个冲突」。
 *
 * 为什么不能把未知格/冲突数放进挑选目标（2026-09-13 真机取证后改）：
 *   这两项是**分类阈值的产物**（`maxResidual` / `conflictGap`），不是几何质量本身。
 *   把它们当目标，等于奖励「把边缘框刚好推过阈值」——结果是从「未知格更少」的偏移里
 *   挑出一个把噪声框也认成棋子的几何。K40 + 天天象棋实测：同一开局局面，
 *   autoAlign 开着时驱动看到 `红R 3 个 / 红N 3 个 / 红C 4 个 / 红P 9 个`（非法，状态机拒绝出手），
 *   关掉就是合法开局；位移只有 3px，物理上不可能改变「框→哪一格」的归属。
 *   更糟的是这个目标**不幂等**：挪完之后的「未知格/冲突」又变了，于是每帧在 (0,3)/(0,-3) 之间来回，
 *   几何版本被反复 +1。纯几何代价是 (框, 偏移) 的纯函数 → 幂等，挪到位之后下一帧增益为 0、不再动。
 */
function latticeCost(detections, grid, floorScore) {
    let weighted = 0;
    let weightSum = 0;
    let count = 0;
    let out = 0;
    for (let i = 0; i < (detections ? detections.length : 0); i++) {
        const fit = boxFit(detections[i], grid, floorScore);
        if (!fit) {
            continue;
        }
        count += 1;
        if (!fit.inside) {
            out += 1;
        }
        weighted += fit.score * fit.distance;
        weightSum += fit.score;
    }
    return {
        /** 总代价（分数加权）：越小 = 框越贴合交点 */
        cost: weighted,
        /** 加权平均距离：给 suspect 判定用（与框数无关） */
        mean: weightSum > 0 ? weighted / weightSum : 0,
        boxes: count,
        outOfBoard: out
    };
}

/**
 * 用检测框给几何做微调：在 ±maxShift 像素内试若干原点偏移，**按纯几何代价**挑最优
 * （分数加权的「框心到最近交点距离」之和，见 latticeCost 的说明）。
 *
 * 只挪原点，不改格距。执行几何的冻结/更新策略（执行单 §5）：
 *   · 相对基础几何的改善不足 minGain（默认 15%）→ 视为抖动，**不挪**（±3px 级的小赢不算赢）；
 *   · 位移低于 minApply → 只当诊断，不动执行几何；
 *   · 位移超过 maxApply → **拒绝**：几何不只给「框归哪一格」用，还给**判阵营的像素采样**用。
 *     采样框是「格距的 84%」且以交点为心，所以几何一旦离开颜色校准的位置，木纹就会跑进采样框。
 *     K40 + 天天象棋同一帧实测（`tools/p4-align-debug.js` 取证）：
 *       · 校准后的几何：`rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR`（合法开局）
 *       · 按框拟合挑出的 (12,6)：`rNbakabnR/9/1C5C1/P1P1p1P1P/…`（红 28 黑 2，非法）
 *     即「-7,-14 系统性偏差」是**检测框自身的偏置**，不该靠挪几何去凑：框归格用「最近交点」本来就抗得了
 *     0.4 格以内的偏置，而颜色采样扛不住。真定位错（几十像素）会表现为框大量出界 → 交给 suspect。
 *   · 真的挪了（且位移在 [minApply, maxApply] 内）→ `version + 1`（否则用旧几何算出来的票据会被拿去点新几何的格子）。
 * 代价：只有一次模型推理，对齐本身是纯坐标运算（几十个框 × 几十个候选）。
 */
function alignGrid(detections, grid, assignOptions, search) {
    const cfg = search || {};
    const maxShift = cfg.maxShift == null ? 18 : cfg.maxShift;
    const step = cfg.step == null ? 3 : cfg.step;
    /** 小于这个位移（|dx|+|dy|，像素）只当诊断，不改执行几何（同一格内不会变，免得票据天天失效） */
    const minApply = cfg.minApply == null ? 3 : cfg.minApply;
    /** 低于这个分数的框不参与几何拟合（实测 0.2 以下的框基本都是假的） */
    const floorScore = cfg.floorScore == null ? 0.2 : cfg.floorScore;
    /** 至少要改善这么多（相对）才真的挪：抑制「同一格内来回微调」 */
    const minGain = cfg.minGain == null ? 0.15 : cfg.minGain;
    /**
     * 允许真的挪的最大位移（|dx|+|dy|，像素）。超过就拒绝 —— 理由见上方注释：
     * 几何同时是颜色采样的基准，大位移会把木纹采进采样框，把黑子判成红子。
     * 真的整体错位（几十像素）不是靠微调救的，那是 suspect → 重新定位的活。
     */
    const maxApply = cfg.maxApply == null ? 6 : cfg.maxApply;
    const baseVersion = grid.version == null ? 1 : grid.version;

    const baseCost = latticeCost(detections, grid, floorScore);
    let best = { dx: 0, dy: 0, cost: baseCost };
    let tried = 1;
    for (let dx = -maxShift; dx <= maxShift; dx += step) {
        for (let dy = -maxShift; dy <= maxShift; dy += step) {
            if (dx === 0 && dy === 0) {
                continue;
            }
            const candidate = Object.assign({}, grid, {
                originX: grid.originX + dx,
                originY: grid.originY + dy
            });
            const cost = latticeCost(detections, candidate, floorScore);
            tried += 1;
            // 代价更小 → 更贴合；代价相同则取位移更小的（同样的贴合度，别多挪）
            const closer = cost.cost < best.cost.cost - 1e-9;
            const tieBreak = Math.abs(cost.cost - best.cost.cost) <= 1e-9 &&
                (Math.abs(dx) + Math.abs(dy)) < (Math.abs(best.dx) + Math.abs(best.dy));
            if (closer || tieBreak) {
                best = { dx: dx, dy: dy, cost: cost };
            }
        }
    }
    const shift = Math.abs(best.dx) + Math.abs(best.dy);
    const gain = baseCost.cost > 0 ? (baseCost.cost - best.cost.cost) / baseCost.cost : 0;
    const worthwhile = baseCost.cost > 0 && gain >= minGain;
    /** 位移太大 → 拒绝（见 maxApply 的说明）；仍把最好的偏移与增益报出来，便于诊断 */
    const tooLarge = worthwhile && shift > maxApply;
    const moved = worthwhile && !tooLarge && (best.dx !== 0 || best.dy !== 0);
    const applied = moved && shift >= minApply;

    const bestGrid = moved ? Object.assign({}, grid, {
        originX: grid.originX + best.dx,
        originY: grid.originY + best.dy
    }) : grid;
    const assigned = assignment.assign(detections, bestGrid, assignOptions);
    // 基础几何的分配只为报「这次对齐认多了几个子」；没挪就不用再算一遍
    const baseAssigned = moved ? assignment.assign(detections, grid, assignOptions) : assigned;
    let appliedGrid = grid;
    if (applied) {
        appliedGrid = geometry.createGrid({
            originX: bestGrid.originX,
            originY: bestGrid.originY,
            stepX: bestGrid.stepX,
            stepY: bestGrid.stepY,
            orientation: bestGrid.orientation,
            version: baseVersion + 1,
            quality: bestGrid.quality
        });
        appliedGrid.alignedFrom = baseVersion;
    }
    const assignedAtApplied = assigned;
    return {
        grid: appliedGrid,
        assigned: assigned,
        report: {
            dx: best.dx,
            dy: best.dy,
            tried: tried,
            // 以下都是**信息性**指标（挑选目标里不含它们）：给日志与 suspect 用
            unknown: countUnknown(assigned.cells),
            outOfBoard: assigned.stats.outOfBoard,
            conflicts: assigned.stats.conflicts,
            residual: sumResidual(assigned.cells),
            /** 纯几何代价（挑选依据）：基础 / 最优 / 相对增益 */
            costBefore: Number(baseCost.cost.toFixed(4)),
            costAfter: Number(best.cost.cost.toFixed(4)),
            gain: Number(gain.toFixed(4)),
            gainAccepted: worthwhile,
            meanDistance: Number(best.cost.mean.toFixed(4)),
            boxes: best.cost.boxes,
            /** 对齐让「认出的子」多了几个：>0 说明这次对齐在把边缘框收进来（只做诊断） */
            pieceDelta: assigned.stats.placed - baseAssigned.stats.placed,
            moved: moved,
            /** 框拟合想挪但位移超上限 → 拒绝（用校准几何；颜色采样依赖它） */
            refused: tooLarge ? ("位移 " + shift + "px 超过 maxApply " + maxApply + "，保持校准几何") : null,
            // 量化后是否真的改了「执行几何」；改了就必须换版本，否则旧票据会拿旧几何去点
            applied: applied,
            minApply: minApply,
            maxApply: maxApply,
            minGain: minGain,
            versionBefore: baseVersion,
            versionAfter: appliedGrid.version,
            /** 几何可疑（框整体离交点远 / 有框出界）：交给状态机去 RECOVER 重新定位 */
            suspect: best.cost.outOfBoard > 0 ||
                best.cost.mean > (cfg.suspectResidual == null ? 0.25 : cfg.suspectResidual)
        }
    };
}

/**
 * 把棋盘外框按目标宽高比扩成「竖屏窗口」（保持中心不变，不超出图像）。
 * 用于矩形输入模型：让它看到的是整屏那样的一竖条，而不是方形棋盘裁剪。
 */
function fitAspect(box, aspect, image) {
    if (!aspect || !box || !(aspect > 0)) {
        return box;
    }
    const maxWidth = image && typeof image.getWidth === "function" ? image.getWidth() : null;
    const maxHeight = image && typeof image.getHeight === "function" ? image.getHeight() : null;
    let width = box.width;
    let height = box.height;
    if (width / height < aspect) {
        width = height * aspect;          // 太窄 → 左右扩
    } else {
        height = width / aspect;          // 太宽 → 上下扩
    }
    const centreX = box.x + box.width / 2;
    const centreY = box.y + box.height / 2;
    let x = Math.round(centreX - width / 2);
    let y = Math.round(centreY - height / 2);
    width = Math.round(width);
    height = Math.round(height);
    if (maxWidth != null && maxHeight != null) {
        // 夹回图像内（尺寸不够时不再拉比例，宁可略偏比例也别越界）
        x = Math.max(0, Math.min(x, Math.max(0, maxWidth - width)));
        y = Math.max(0, Math.min(y, Math.max(0, maxHeight - height)));
        width = Math.min(width, maxWidth);
        height = Math.min(height, maxHeight);
    }
    return { x: x, y: y, width: width, height: height };
}

function regionBox(opts) {
    if (opts.regionMode === "full" && opts.image && typeof opts.image.getWidth === "function") {
        const width = opts.image.getWidth();
        const height = opts.image.getHeight();
        if (width > 0 && height > 0) {
            return { x: 0, y: 0, width: width, height: height };
        }
    }
    return fitAspect(geometry.boundsOf(opts.grid, opts.cropMargin), opts.regionAspect, opts.image);
}

function recognize(options) {
    const opts = defaults.mergeDefaults(DEFAULTS, options);
    const startedAt = Date.now();
    const timing = { cropMs: 0, detectMs: 0, sideMs: 0, totalMs: 0 };
    if (!opts.image) {
        return { ok: false, reason: "没有图像", stage: "input" };
    }
    if (!opts.grid) {
        return { ok: false, reason: "没有棋盘几何（先定位棋盘）", stage: "input" };
    }
    if (typeof opts.detect !== "function") {
        return { ok: false, reason: "没有检测器", stage: "input" };
    }

    const box = regionBox(opts);
    const detectStart = Date.now();
    let raw = [];
    let detectTarget = opts.image;
    try {
        if (opts.useRegion === true) {
            // 官方区域检测：Java 侧会把 region 加回结果（OpenCvYoloDetector.detectRgba），
            // 所以拿到的坐标已经是**全图**坐标，这里不能再加偏移。
            raw = opts.detect(opts.image, {
                confidence: opts.confidence,
                nms: opts.nms,
                region: [box.x, box.y, box.width, box.height]
            }) || [];
        } else {
            if (typeof opts.crop === "function") {
                const t0 = Date.now();
                detectTarget = opts.crop(opts.image, box);
                timing.cropMs = Date.now() - t0;
                if (!detectTarget) {
                    return { ok: false, reason: "裁剪棋盘区域失败", stage: "crop", box: box };
                }
            }
            raw = opts.detect(detectTarget, { confidence: opts.confidence, nms: opts.nms }) || [];
        }
    } catch (error) {
        return {
            ok: false,
            stage: "detect",
            reason: "检测失败：" + (error && error.message ? error.message : error),
            box: box
        };
    }
    timing.detectMs = Date.now() - detectStart;
    // 两种模式下归一化的偏移量不同：区域模式坐标已回移，裁剪模式要自己加回去。
    const detections = normalize(raw, opts.useRegion === true ? null : box);

    const assignOptions = {
        minScore: opts.minScore,
        maxResidual: opts.maxResidual,
        conflictGap: opts.conflictGap,
        frameQuality: opts.frameQuality
    };
    // 几何微调（定位系统性偏差的兜底）：只花一次检测的钱，所以可以逐帧跑。
    const aligned = opts.autoAlign === true
        ? alignGrid(detections, opts.grid, assignOptions, opts.alignSearch)
        : null;
    const grid = aligned ? aligned.grid : opts.grid;
    const assigned = aligned ? aligned.assigned : assignment.assign(detections, grid, assignOptions);
    // 逐格判阵营（只对「有子」的格子采样，空格不花这个钱）
    const sideStart = Date.now();
    const sideOf = new Array(90);
    const sides = [];
    for (let index = 0; index < 90; index++) {
        const cell = assigned.cells[index];
        if (cell.state !== "PIECE") {
            continue;
        }
        const col = index % geometry.COLS;
        const row = Math.floor(index / geometry.COLS);
        if (typeof opts.rgbAt !== "function") {
            sideOf[index] = { side: pieceSide.SIDE_UNKNOWN, reason: "没有取像素的能力" };
            continue;
        }
        const result = pieceSide.classifyAt(function (x, y) {
            return opts.rgbAt(opts.image, x, y);
        }, grid, col, row, { sampleStep: opts.sideSampleStep });
        sideOf[index] = result;
        sides.push({
            col: col, row: row, piece: cell.piece, side: result.side,
            redShare: result.redShare, darkShare: result.darkShare,
            counted: result.counted, reason: result.reason
        });
    }
    timing.sideMs = Date.now() - sideStart;

    // 组装 FEN 字符
    const chars = new Array(90);
    const placements = [];
    for (let index = 0; index < 90; index++) {
        const cell = assigned.cells[index];
        if (cell.state === cellsContract.STATE_EMPTY) {
            // FEN 字符层的空格用 null（既定契约：fen.layoutOf / rules.pieceAt 都按 falsy 判空）
            chars[index] = cellsContract.EMPTY;
            continue;
        }
        if (cell.state === cellsContract.STATE_UNKNOWN) {
            chars[index] = cellsContract.UNKNOWN;
            continue;
        }
        const side = sideOf[index] ? sideOf[index].side : pieceSide.SIDE_UNKNOWN;
        const label = cell.piece;
        const letter = pieceSide.applySide(label, side);
        chars[index] = letter;
        // 把「带阵营的字符」写回格子：下游（时序融合、棋规层）都用 FEN 字符说话，
        // 只留模型原始字母的话阵营信息会在融合时丢掉。原始字母仍留在 candidates[0].label。
        cell.piece = letter;
        placements.push({
            index: index,
            col: index % geometry.COLS,
            row: Math.floor(index / geometry.COLS),
            label: label,
            name: PIECE_NAMES[String(label).toUpperCase()] || String(label),
            side: pieceSide.describeSide(side),
            fen: letter,
            score: cell.score
        });
    }

    const unknownCount0 = chars.filter(function (ch) { return ch === "?"; }).length;
    // 用棋规反查：违规的格子降级为未知（详见 src/chess/consistency.js）
    let repaired = null;
    if (opts.repairConsistency) {
        repaired = consistency.repair(assigned.cells);
        if (repaired.changed) {
            // 降级后必须从 cells 重新生成 chars：被降级的格子要变成 '?'
            const repairedChars = consistency.charsOf(assigned.cells);
            for (let i = 0; i < 90; i++) {
                chars[i] = repairedChars[i];
            }
        }
    }
    const unknownCount = chars.filter(function (ch) { return ch === "?"; }).length;
    /** 三态计数走契约模块（不要再写「不是 . 不是 ?」这类排除法） */
    const stateCounts = cellsContract.count(chars);
    const sideReasons = {};
    sides.forEach(function (entry) {
        if (entry.side === pieceSide.SIDE_UNKNOWN) {
            const key = entry.reason || "未说明";
            sideReasons[key] = (sideReasons[key] || 0) + 1;
        }
    });
    const stats = Object.assign({}, assigned.stats, {
        sideUnknown: sides.filter(function (s) { return s.side === pieceSide.SIDE_UNKNOWN; }).length,
        sideRed: sides.filter(function (s) { return s.side === pieceSide.SIDE_RED; }).length,
        sideBlack: sides.filter(function (s) { return s.side === pieceSide.SIDE_BLACK; }).length,
        sideReasons: sideReasons,
        unknownCells: unknownCount,
        unknownBeforeRepair: unknownCount0,
        pieceCells: stateCounts.pieces,
        emptyCells: stateCounts.empty,
        invalidCells: stateCounts.invalid.length,
        stateCounts: stateCounts,
        autoAlign: aligned ? aligned.report : null
    });

    let layout = null;
    let layoutError = "";
    try {
        layout = fen.layoutOf(chars, { allowUnknown: true });
    } catch (error) {
        layoutError = String(error && error.message ? error.message : error);
    }

    timing.totalMs = Date.now() - startedAt;
    // 有未知格时只给布局串（带 ?），不给「完整 FEN」：轮次也不知道，不装。
    return {
        ok: layout != null,
        reason: layout == null ? ("生成布局失败：" + layoutError) : "",
        box: box,
        grid: grid,
        detections: detections,
        detectionCount: detections.length,
        assigned: assigned.cells,
        stats: stats,
        placements: placements,
        sides: sides,
        repaired: repaired,
        chars: chars,
        layout: layout,
        unknownCells: unknownCount,
        sideToMove: opts.sideToMove || null,
        timing: timing
    };
}

/** 检测结果 → 统一形态（中心点坐标 + 可选裁剪偏移）。box 为 null 表示坐标已经是全图坐标。 */
function normalize(raw, box) {
    const list = [];
    const offsetX = box ? box.x : 0;
    const offsetY = box ? box.y : 0;
    for (let i = 0; i < (raw ? raw.length : 0); i++) {
        const item = raw[i];
        if (!item) {
            continue;
        }
        let x = item.x;
        let y = item.y;
        if (x == null && item.bounds) {
            x = item.bounds.centerX == null
                ? item.bounds.x + item.bounds.width / 2
                : item.bounds.centerX;
            y = item.bounds.centerY == null
                ? item.bounds.y + item.bounds.height / 2
                : item.bounds.centerY;
        }
        if (!isFinite(x) || !isFinite(y)) {
            continue;
        }
        const bounds = item.bounds || {};
        list.push({
            label: item.label,
            classId: item.classId,
            score: item.score == null ? 1 : item.score,
            x: x + offsetX,
            y: y + offsetY,
            width: bounds.width,
            height: bounds.height
        });
    }
    return list;
}

/** 给人看的棋盘图（row 0 在上，字母后跟 r/b/?）。 */
function boardText(result) {
    if (!result || !result.chars) {
        return "";
    }
    const lines = [];
    for (let row = 0; row < 10; row++) {
        const cells = [];
        for (let col = 0; col < 9; col++) {
            const ch = result.chars[row * 9 + col];
            cells.push(ch == null ? " . " : " " + ch + " ");
        }
        lines.push(String(row).padStart(2, " ") + " " + cells.join(""));
    }
    return lines.join("\n");
}

module.exports = {
    DEFAULTS: DEFAULTS,
    PIECE_NAMES: PIECE_NAMES,
    recognize: recognize,
    alignGrid: alignGrid,
    normalize: normalize,
    boardText: boardText
};
