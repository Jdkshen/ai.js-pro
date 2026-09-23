// @engine quickjs
/**
 * 真机入口：截一帧 → 在给定范围内自动定位棋盘格线 → 返回几何。
 *
 * 这一步**只截图，不点击**（棋盘的自动走棋链路还没接入，见 README 的验收状态）。
 *
 * 用法（ScriptExecuteActivity 的控制台 / main.js 的按钮）：
 *   const out = require("./tools/locate-board.js").locateBoardOnScreen();
 *   out.ok / out.reason / out.grid / out.timing
 *
 * 棋盘范围 region 的契约：**紧贴棋盘外沿，误差 ≤ 半个格距**。
 * 给大了会选错格线序号（整盘错一行），所以宁可先用「画一张截图量一下比例」的方式校准，
 * 或者改用人工四点校准（board-locator.locateByCorners）。
 *
 * 定位有两条路（2026-09-22 多证据融合 P1 接入）：**全屏格线探测**
 * （lattice-detector，不依赖 regionRatio，默认先试）→ 失败后回落**区域定位**
 * （board-locator 逐点支撑度 + 候选按线证据选优）。opts.lattice=false 可强制旧路。
 */
"use strict";

const adapter = require("../src/runtime/aijs-adapter.js");
const locator = require("../src/vision/board-locator.js");
const geometry = require("../src/vision/geometry.js");
const latticeDetector = require("../src/vision/lattice-detector.js");

/** 常见布局下的棋盘大致范围（占屏幕宽高的比例），可被调用方覆盖。 */
const DEFAULT_REGION_RATIO = { x: 0.025, y: 0.26, width: 0.95, height: 0.48 };

/**
 * 全屏格线探测器（多证据融合 P1，`src/vision/lattice-detector.js`）的宿主桥：
 * 只暴露它需要的两个能力（找色计数 + 图像尺寸），方便离线用假宿主做单测。
 */
function latticeHost() {
    return {
        sizeOf: function (image) { return adapter.sizeOf(image); },
        countColor: function (image, color, options) { return adapter.countColor(image, color, options); }
    };
}

/** 宿主是否具备找色能力（没有就只走旧区域定位）。 */
function canUseLattice() {
    return typeof adapter.countColor === "function";
}

/**
 * 由几何反推「棋盘范围」（像素，紧贴外沿 ±0.5 格）。
 * ⚠️ lattice 路径本身**不需要** region（这正是它比旧路径强的地方），这里只是把结果
 * 映射成旧字段供面板日志/回落使用。
 */
function regionFromGrid(grid, pixelWidth, pixelHeight) {
    const padX = grid.stepX * 0.5;
    const padY = grid.stepY * 0.5;
    return {
        x: Math.round(grid.originX - padX),
        y: Math.round(grid.originY - padY),
        width: Math.round(8 * grid.stepX + 2 * padX),
        height: Math.round(9 * grid.stepY + 2 * padY)
    };
}

/** 单轴证据 → 汇总字段（供日志/测试）。 */
function axisSummary(axis) {
    if (!axis) {
        return null;
    }
    return { origin: axis.origin, step: axis.step, matched: axis.matched, score: axis.score };
}

/**
 * lattice 成功结果 → 与 board-locator 路径**同形**的结果（面板/识别只认这些字段：
 * ok/method/grid/reason/warning/region/columns/rows/timing + frame/图片对象）。
 * 纯函数（不碰 adapter），totalMs 等由 ctx 传入，方便自测。
 */
function mapLatticeResult(lattice, ctx) {
    const size = ctx.size;
    const region = regionFromGrid(lattice.grid, size.width, size.height);
    const evidence = lattice.evidence || {};
    const vMatched = evidence.vertical ? evidence.vertical.matched : null;
    const hMatched = evidence.horizontal ? evidence.horizontal.matched : null;
    const scoreText = lattice.score == null ? "-" : lattice.score.toFixed(2);
    const result = {
        ok: true,
        method: "lattice",
        reason: "全屏格线：竖 " + (vMatched == null ? "-" : vMatched) + "/9，横 " +
            (hMatched == null ? "-" : hMatched) + "/10，分数 " + scoreText,
        warning: "",
        grid: lattice.grid,
        region: region,
        regionRatio: regionToRatio(region, size.width, size.height),
        regionTries: 0,
        attempts: 1,
        chosenScore: lattice.score,
        earlyAccept: true,
        reconstructed: { x: null, y: null },
        residual: null,
        columns: vMatched,
        rows: hMatched,
        lattice: {
            score: lattice.score,
            squareness: lattice.squareness,
            calls: lattice.calls,
            vertical: axisSummary(evidence.vertical),
            horizontal: axisSummary(evidence.horizontal),
            timing: lattice.timing
        },
        timing: {
            captureMs: ctx.captureMs,
            locateMs: lattice.timing ? lattice.timing.totalMs : null,
            totalMs: ctx.totalMs,
            samples: lattice.calls || 0,
            regionTries: 0,
            attempts: 1
        }
    };
    if (ctx.source) {
        result.source = ctx.source;
    }
    if (ctx.info) {
        result.frame = ctx.info;
    }
    if (ctx.frameObject) {
        result.frameObject = ctx.frameObject;
    }
    if (ctx.imageObject) {
        result.imageObject = ctx.imageObject;
        result.imageSize = size;
    }
    return result;
}

function ratioToRegion(ratio, pixelWidth, pixelHeight) {
    const r = ratio || DEFAULT_REGION_RATIO;
    return {
        x: Math.round(r.x * pixelWidth),
        y: Math.round(r.y * pixelHeight),
        width: Math.round(r.width * pixelWidth),
        height: Math.round(r.height * pixelHeight)
    };
}

function regionToRatio(region, pixelWidth, pixelHeight) {
    return {
        x: region.x / pixelWidth,
        y: region.y / pixelHeight,
        width: region.width / pixelWidth,
        height: region.height / pixelHeight
    };
}

/**
 * 备选棋盘范围（比例制，基于默认比例做平移/放宽）。
 *
 * 为什么需要：**同一块棋盘在不同手机上位置会变** —— 状态栏、游戏顶部横幅、屏幕长宽比
 * （16:9 / 20:9 / 21:9）都会让棋盘整体上移或下移几个百分点。实测默认比例只容忍
 * 画面上下移 ±8%（1280×2772 的图），而 720×1600 的图**只容忍 ±0%**：偏 4% 就报
 * 「竖线只匹配到 6 条线（需要 7 条）」。所以默认范围失败时要换几个范围再试，
 * 否则「换个手机就识别不出来」。
 */
function regionCandidates(pixelWidth, pixelHeight, ratio) {
    const base = ratio || DEFAULT_REGION_RATIO;
    const ratios = [base];
    const shifts = [-0.04, 0.04, -0.08, 0.08, -0.12, 0.12];
    for (let i = 0; i < shifts.length; i++) {
        ratios.push({ x: base.x, y: base.y + shifts[i], width: base.width, height: base.height });
    }
    // 纵向放宽两档（范围没盖全棋盘：上下各吃到一点边）
    ratios.push({ x: base.x, y: base.y - 0.04, width: base.width, height: base.height + 0.12 });
    ratios.push({ x: base.x, y: base.y - 0.06, width: base.width, height: base.height + 0.20 });
    // 横向也放宽一档（左右被裁）
    ratios.push({ x: Math.max(0, base.x - 0.02), y: base.y - 0.04, width: base.width + 0.04, height: base.height + 0.12 });
    const list = [];
    ratios.forEach(function (r) {
        const region = ratioToRegion(r, pixelWidth, pixelHeight);
        // 去掉越界/重复的候选
        if (region.width < 8 || region.height < 8) return;
        for (let i = 0; i < list.length; i++) {
            if (list[i].x === region.x && list[i].y === region.y &&
                list[i].width === region.width && list[i].height === region.height) return;
        }
        list.push(region);
    });
    return list;
}

/**
 * 把「自动调参」这件事显式说出来（用户/日志能看到实际用了什么）。
 */
function describeAttempt(attempt) {
    if (!attempt || attempt.tries <= 1) {
        return "";
    }
    if (attempt.relaxed != null) {
        return "已自动调参：降低支撑度门槛到 " + attempt.relaxed + "（范围里混进了棋盘外的空白/状态栏），" +
            "已按方格比例与线数校验；建议人工校准核对一次";
    }
    return "已自动调参：默认棋盘范围没盖住棋盘，已换到第 " + attempt.regionTries + " 个候选范围" +
        "（不同手机/游戏顶部横幅不同，棋盘位置会平移）";
}

/**
 * 依次用「默认范围 + 备选范围」尝试定位，**收集所有候选、按线证据选优**（2026-09-22
 * 多证据融合方案 §2.2/§10：不再「第一个成功即返回」——低质量候选会挡住后面的正确候选）。
 * 默认范围给出足够好的结果时立即收工（与旧的单次定位等速，不会变慢）；
 * 结果可疑（带警告/补线/条数少）才继续看后面的范围，硬上限仍由 budgetMs 兜住。
 *
 * @returns { result, region, tries, relaxed, regionTries, budgetExceeded, tried,
 *            candidates（ok 候选评分表，供日志/测试）, score, earlyAccept }
 */
function attemptLocate(getValue, pixelWidth, pixelHeight, opts) {
    const useFallback = opts.regionFallback !== false;
    let candidates;
    if (opts.region) {
        candidates = [opts.region];   // 调用方显式给了范围：默认尊重它，不加备选
    } else if (opts.regionRatio) {
        candidates = useFallback
            ? regionCandidates(pixelWidth, pixelHeight, opts.regionRatio)
            : [ratioToRegion(opts.regionRatio, pixelWidth, pixelHeight)];
    } else {
        candidates = useFallback ? regionCandidates(pixelWidth, pixelHeight) : [ratioToRegion(null, pixelWidth, pixelHeight)];
    }
    // 门槛梯度：先用定位器默认门槛（0.45）走一遍所有范围，能解决「位置不对」；
    // 只有当「线找到了、但条数不够」时才放宽门槛 —— 棋盘range 里混进棋盘外的空白/状态栏时，
    // 每列/每行的支撑度都被稀释，真线会掉到门槛以下（真机表现：720p 上棋盘位置一偏就报
    // 「竖线：只匹配到 6 条线（需要 7 条）」）。放宽后仍要过方格校验 + 线数 + 序号锚定，
    // 且结果会带 warning 提示，不会静默给出可疑几何。
    // 技巧：真机上一次尝试约 0.5 秒（逐点取像素），所以「顺序 + 预算」都要管。
    // 计划顺序按「命中概率 / 代价」排：
    //   ① 前 3 个最可能的范围 × 默认门槛（绝大多数手机第 1 次就中，与旧行为完全一致）
    //   ② 同样 3 个范围 × 放宽门槛（小屏/线较细的情况第 2 次就中，而不是把 10 个范围都试完）
    //   ③ 其余范围 × 默认门槛（棋盘位置偏得多）
    //   ④ 其余范围 × 放宽门槛
    // 再加一个总时间预算，避免失败时卡住界面。
    const plans = useFallback && opts.minSupport == null
        ? [
            { minSupport: null, limit: 3 },
            { minSupport: 0.32, limit: 3 },
            { minSupport: null, limit: candidates.length },
            { minSupport: 0.32, limit: candidates.length }
        ]
        : [{ minSupport: null, limit: candidates.length }];
    const budgetMs = opts.budgetMs == null ? 4000 : opts.budgetMs;
    /**
     * 收集到「足够好」的候选后最多再看这么久（毫秒）。
     * 为什么不能扫完：真机一次尝试约 0.5s（逐点取像素），扫满 12 个范围要 6s。
     */
    const collectMs = opts.collectMs == null ? 1400 : opts.collectMs;
    const startedAt = Date.now();
    const tried = [];
    /** 收集到的 ok 候选（含评分，供日志/测试查看；方案 P0「先把证据画出来」的雏形） */
    const scored = [];
    let best = null;          // {score, result, region, relaxed, regionIndex}
    let firstOkAt = 0;
    let result = null;
    let budgetExceeded = false;
    const done = function (earlyAccept) {
        return {
            result: best ? best.result : result,
            region: best ? best.region : candidates[candidates.length - 1],
            tries: tried.length,
            relaxed: best ? best.relaxed : null,
            regionTries: best ? best.regionIndex + 1 : candidates.length,
            budgetExceeded: budgetExceeded,
            tried: tried,
            candidates: scored,
            score: best ? best.score : null,
            earlyAccept: !!earlyAccept
        };
    };
    for (let p = 0; p < plans.length; p++) {
        const plan = plans[p];
        const limit = Math.min(plan.limit, candidates.length);
        for (let i = 0; i < limit; i++) {
            if (Date.now() - startedAt > budgetMs) {
                budgetExceeded = true;
                return done(false);
            }
            if (best && firstOkAt && Date.now() - firstOkAt > collectMs) {
                return done(false);   // 已有可用的，再看下去不划算
            }
            result = locator.locate({
                getValue: getValue,
                region: candidates[i],
                orientation: opts.orientation || "RED_BOTTOM",
                step: opts.step,
                acrossStep: opts.acrossStep,
                minSupport: plan.minSupport == null ? opts.minSupport : plan.minSupport,
                tolerance: opts.tolerance,
                margin: opts.margin
            });
            tried.push({
                region: candidates[i],
                minSupport: plan.minSupport,
                ok: !!result.ok,
                reason: result.reason
            });
            if (result.ok) {
                const score = locator.scoreLocateResult(result);
                scored.push({
                    region: candidates[i], minSupport: plan.minSupport, score: score,
                    matched: (result.vertical && result.vertical.matched) + "/9," +
                        (result.horizontal && result.horizontal.matched) + "/10",
                    warning: result.warning || ""
                });
                if (best == null || score > best.score) {
                    best = {
                        score: score, result: result, region: candidates[i],
                        relaxed: plan.minSupport, regionIndex: i
                    };
                }
                if (locator.isAcceptableLocate(result)) {
                    return done(true);
                }
                if (!firstOkAt) {
                    firstOkAt = Date.now();
                }
            }
        }
    }
    return done(false);
}

/**
 * 截一帧并在范围内自动定位棋盘。
 * @param options.region      棋盘范围（像素坐标，相对**像素尺寸**）；不给就用默认比例
 * @param options.regionRatio 比例形式的棋盘范围
 * @param options.orientation "RED_BOTTOM" | "RED_TOP"（默认红方在下）
 * @param options.step        扫描步长（像素，**默认不传**：由定位器按格距推导）。
 *                            手填像素值只适合「只想快点扫」的粗定位，step ≥5 容易挑错格点阵。
 * @param options.acrossStep  跨采样步长（像素，默认不传：由定位器按格距推导）
 * @param options.frame       复用已有的一帧（省一次截图）
 */
function locateBoardOnScreen(options) {
    const opts = options || {};
    const caps = adapter.capabilities();
    if (!opts.frame && !caps.requestScreenCapture) {
        return { ok: false, reason: "缺少能力：requestScreenCapture（无法截图，请用人工四点校准）" };
    }
    if (!adapter.canSamplePixels()) {
        return { ok: false, reason: "缺少能力：images.pixel / colors（无法采样像素，只能人工校准）" };
    }

    const startedAt = adapter.nowMono();
    let frame = opts.frame;
    let captureMs = 0;
    if (!frame) {
        const granted = adapter.requestCapture("portrait");
        if (granted === false) {
            return { ok: false, reason: "用户拒绝了截图授权" };
        }
        const captureStart = adapter.nowMono();
        frame = adapter.capture("full", opts.timeoutMs == null ? 800 : opts.timeoutMs);
        captureMs = adapter.nowMono() - captureStart;
    }
    const info = adapter.describeFrame(frame);
    if (!info) {
        return { ok: false, reason: "截图失败：没有拿到帧" };
    }
    // 采样用的是**像素坐标**（快截图会降采样，与 width/height 不同）
    const pixelWidth = info.pixelWidth || info.width;
    const pixelHeight = info.pixelHeight || info.height;

    // ---- 路径 A：全屏格线探测（多证据融合 P1，2026-09-22 接入）----
    // 不依赖 regionRatio 与逐点采样；失败（皮肤不匹配/被遮挡/越界）时自动掉到下面的区域定位。
    let latticeOut = null;
    if (opts.lattice !== false && canUseLattice()) {
        latticeOut = latticeDetector.detect(latticeHost(), frame, Object.assign({
            orientation: opts.orientation || "RED_BOTTOM"
        }, opts.latticeOptions || {}));
        if (latticeOut.ok) {
            return mapLatticeResult(latticeOut, {
                size: { width: pixelWidth, height: pixelHeight },
                captureMs: captureMs,
                totalMs: adapter.nowMono() - startedAt,
                info: info,
                frameObject: frame
            });
        }
    }

    const region = opts.region || ratioToRegion(opts.regionRatio, pixelWidth, pixelHeight);

    const step = opts.step == null ? null : opts.step;
    const acrossStep = opts.acrossStep == null ? null : opts.acrossStep;
    // 采样点数（供性能判断：当前平台只能逐点取像素）。粗扫 + 精修（精修约两成）
    const samples = Math.round((
        Math.ceil(region.width / (step || 4)) * Math.ceil(region.height / (acrossStep || 10)) +
        Math.ceil(region.height / (step || 4)) * Math.ceil(region.width / (acrossStep || 10))) * 1.2);

    const scanStart = adapter.nowMono();
    const attempt = attemptLocate(adapter.samplerFor(frame), pixelWidth, pixelHeight, {
        region: opts.region,
        regionRatio: opts.regionRatio,
        regionFallback: opts.regionFallback,
        orientation: opts.orientation,
        step: step,
        acrossStep: acrossStep,
        minSupport: opts.minSupport,
        tolerance: opts.tolerance,
        margin: opts.margin
    });
    const result = attempt.result;
    const usedRegion = attempt.region;
    const regionNote = describeAttempt(attempt);
    const locateMs = adapter.nowMono() - scanStart;

    const timing = {
        captureMs: captureMs,
        locateMs: locateMs,
        totalMs: adapter.nowMono() - startedAt,
        samples: samples * attempt.tries,
        regionTries: attempt.regionTries,
        attempts: attempt.tries
    };
    if (!result.ok) {
        return {
            ok: false,
            reason: (latticeOut && !latticeOut.ok ? "全屏格线：" + latticeOut.reason + "；区域定位：" : "") +
                result.reason + (attempt.budgetExceeded
                ? "（已尝试 " + attempt.tries + " 次后到达时间预算，可能还有更合适的范围没试；可手动给 region）"
                : ""),
            method: result.method,
            lattice: latticeOut ? { ok: !!latticeOut.ok, reason: latticeOut.reason } : null,
            region: usedRegion,
            timing: timing,
            frame: info,
            frameObject: frame,
            regionTried: attempt.tried
        };
    }
    const grid = result.grid;
    return {
        ok: true,
        method: result.method,
        reason: result.reason,
        warning: [result.warning || "", regionNote].filter(Boolean).join("；"),
        lattice: latticeOut ? { ok: !!latticeOut.ok, reason: latticeOut.reason } : null,
        grid: grid,
        region: usedRegion,
        regionRatio: regionToRatio(usedRegion, pixelWidth, pixelHeight),
        regionTries: attempt.regionTries,
        attempts: attempt.tries,
        columns: (result.vertical ? result.vertical.matched : null),
        rows: (result.horizontal ? result.horizontal.matched : null),
        reconstructed: {
            x: result.vertical ? result.vertical.quality.reconstructed : null,
            y: result.horizontal ? result.horizontal.quality.reconstructed : null
        },
        residual: grid.quality ? grid.quality.maxResidual : null,
        frame: info,
        frameObject: frame,
        timing: timing
    };
}

/**
 * 在**已保存的图片**上定位棋盘（不用把棋盘摆到屏幕上来截）。
 * 这是真机自测最方便的路子：手机相册里有一张棋盘截图就能跑。
 * @param path 图片路径（如 /sdcard/Download/chess.png）
 */
function locateBoardInImage(path, options) {
    const opts = options || {};
    if (!adapter.capabilities().imagesRead) {
        return { ok: false, reason: "缺少能力：images.read（读不了磁盘图片）" };
    }
    if (!adapter.canSamplePixels()) {
        return { ok: false, reason: "缺少能力：images.pixel / colors（无法采样像素）" };
    }
    const startedAt = adapter.nowMono();
    let image;
    let readMs;
    try {
        const readStart = adapter.nowMono();
        image = adapter.readImage(path);
        readMs = adapter.nowMono() - readStart;
    } catch (error) {
        return { ok: false, reason: String(error && error.message ? error.message : error) };
    }
    const size = adapter.sizeOf(image);
    if (!size) {
        return { ok: false, reason: "读到的图片没有尺寸：" + path };
    }

    // ---- 路径 A：全屏格线探测（同 locateBoardOnScreen，见上）----
    let latticeOut = null;
    if (opts.lattice !== false && canUseLattice()) {
        latticeOut = latticeDetector.detect(latticeHost(), image, Object.assign({
            orientation: opts.orientation || "RED_BOTTOM"
        }, opts.latticeOptions || {}));
        if (latticeOut.ok) {
            return mapLatticeResult(latticeOut, {
                size: size,
                captureMs: readMs,
                totalMs: adapter.nowMono() - startedAt,
                imageObject: image,
                source: path
            });
        }
    }

    const region = opts.region || ratioToRegion(opts.regionRatio, size.width, size.height);
    const step = opts.step == null ? null : opts.step;
    const acrossStep = opts.acrossStep == null ? null : opts.acrossStep;
    const scanStart = adapter.nowMono();
    const attempt = attemptLocate(adapter.samplerFor(image), size.width, size.height, {
        region: opts.region,
        regionRatio: opts.regionRatio,
        regionFallback: opts.regionFallback,
        orientation: opts.orientation,
        step: step,
        acrossStep: acrossStep,
        minSupport: opts.minSupport,
        tolerance: opts.tolerance,
        margin: opts.margin
    });
    const result = attempt.result;
    const usedRegion = attempt.region;
    const regionNote = describeAttempt(attempt);
    const locateMs = adapter.nowMono() - scanStart;
    const timing = {
        captureMs: readMs,
        locateMs: locateMs,
        totalMs: adapter.nowMono() - startedAt,
        regionTries: attempt.regionTries,
        attempts: attempt.tries
    };
    if (!result.ok) {
        return {
            ok: false,
            reason: (latticeOut && !latticeOut.ok ? "全屏格线：" + latticeOut.reason + "；区域定位：" : "") +
                result.reason + (attempt.budgetExceeded
                ? "（已尝试 " + attempt.tries + " 次后到达时间预算，可能还有更合适的范围没试；可手动给 region）"
                : ""),
            method: result.method,
            lattice: latticeOut ? { ok: !!latticeOut.ok, reason: latticeOut.reason } : null,
            region: usedRegion,
            timing: timing,
            candidateScores: attempt.candidates,
            regionTried: attempt.tried
        };
    }
    return {
        ok: true,
        source: path,
        method: result.method,
        reason: result.reason,
        warning: [result.warning || "", regionNote].filter(Boolean).join("；"),
        lattice: latticeOut ? { ok: !!latticeOut.ok, reason: latticeOut.reason } : null,
        grid: result.grid,
        region: usedRegion,
        regionRatio: regionToRatio(usedRegion, size.width, size.height),
        regionTries: attempt.regionTries,
        attempts: attempt.tries,
        candidateScores: attempt.candidates,
        chosenScore: attempt.score,
        earlyAccept: attempt.earlyAccept,
        imageSize: size,
        imageObject: image,
        columns: result.vertical ? result.vertical.matched : null,
        rows: result.horizontal ? result.horizontal.matched : null,
        reconstructed: {
            x: result.vertical ? result.vertical.quality.reconstructed : null,
            y: result.horizontal ? result.horizontal.quality.reconstructed : null
        },
        residual: result.grid.quality ? result.grid.quality.maxResidual : null,
        timing: timing
    };
}

/** 把定位结果整理成给人看的多行文本（main.js 的按钮与日志共用）。 */
function describeResult(result) {
    const lines = [];
    if (!result.ok) {
        lines.push("定位失败：" + result.reason);
    } else {
        lines.push("定位成功（" + result.method + "）：" + result.reason);
        lines.push("棋盘起点 (" + result.grid.originX.toFixed(1) + ", " + result.grid.originY.toFixed(1) + ")" +
            "  格距 (" + result.grid.stepX.toFixed(2) + ", " + result.grid.stepY.toFixed(2) + ")");
        if (result.lattice) {
            lines.push("全屏格线：分数 " + (result.lattice.score == null ? "-" : result.lattice.score.toFixed(2)) +
                "  方格差 " + (result.lattice.squareness == null ? "-" : (result.lattice.squareness * 100).toFixed(2) + "%") +
                "  找色 " + (result.lattice.calls == null ? "-" : result.lattice.calls) + " 次");
        } else {
            lines.push("补回的线：竖 " + result.reconstructed.x + " 条 / 横 " + result.reconstructed.y + " 条" +
                "  最大残差 " + (result.residual == null ? "-" : result.residual.toFixed(3)));
        }
        if (result.warning) {
            lines.push("⚠ " + result.warning);
        }
        if (result.candidateScores && result.candidateScores.length > 1) {
            lines.push("候选择优：共 " + result.candidateScores.length + " 个可用候选，选中分数 " +
                (result.chosenScore == null ? "-" : result.chosenScore.toFixed(2)) +
                (result.earlyAccept ? "（提前收工）" : "（比完全部范围）"));
        }
    }
    if (result.imageSize) {
        lines.push("图片 " + result.imageSize.width + "×" + result.imageSize.height + "  " + (result.source || ""));
    } else if (result.frame) {
        lines.push("屏幕 " + result.frame.width + "×" + result.frame.height +
            "（像素 " + result.frame.pixelWidth + "×" + result.frame.pixelHeight + "）");
    }
    if (result.region) {
        lines.push("棋盘范围 " + result.region.x + "," + result.region.y + " " +
            result.region.width + "×" + result.region.height);
    }
    if (result.timing) {
        const cost = result.timing.samples == null
            ? "定位 " + result.timing.locateMs + "ms"
            : "定位 " + result.timing.locateMs + "ms（取像素约 " + result.timing.samples + " 次）";
        lines.push("耗时：读图/截图 " + result.timing.captureMs + "ms / " + cost);
    }
    return lines.join("\n");
}

/** 用规范坐标取屏幕点（落子、核对画面时都要用）。 */
function pointOf(result, col, row) {
    if (!result || !result.grid) {
        return null;
    }
    return geometry.pointAt(result.grid, col, row);
}

const LOCATE_BOARD = {
    DEFAULT_REGION_RATIO: DEFAULT_REGION_RATIO,
    ratioToRegion: ratioToRegion,
    regionToRatio: regionToRatio,
    regionCandidates: regionCandidates,
    regionFromGrid: regionFromGrid,
    mapLatticeResult: mapLatticeResult,
    attemptLocate: attemptLocate,
    locateBoardOnScreen: locateBoardOnScreen,
    locateBoardInImage: locateBoardInImage,
    describeResult: describeResult,
    pointOf: pointOf
};

module.exports = LOCATE_BOARD;
