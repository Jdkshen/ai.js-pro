// @engine quickjs
/** 棋盘定位：电脑端图色格线法移植到手机。证据不足就停止，不再用区域扫描猜位置。 */
"use strict";

const adapter = require("../runtime/aijs-adapter.js");
const geometry = require("./geometry.js");
const latticeDetector = require("./lattice-detector.js");

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

function mapCannyResult(analysis, ctx) {
    const size = ctx.size;
    const grid = geometry.createGrid({
        originX: analysis.grid.originX, originY: analysis.grid.originY,
        stepX: analysis.grid.stepX, stepY: analysis.grid.stepY,
        orientation: ctx.orientation || "RED_BOTTOM",
        quality: { method: "canny-hough", scoreX: analysis.lineScoreX,
            scoreY: analysis.lineScoreY, circles: analysis.circles.length }
    });
    const region = analysis.outer;
    const result = {
        ok: true, method: "canny-hough", grid: grid,
        reason: "Canny 格线 9×10；交点圆 " + analysis.circles.length + " 枚",
        warning: "", region: region,
        regionRatio: regionToRatio(region, size.width, size.height),
        regionTries: 0, attempts: 1, chosenScore: analysis.lineScoreX + analysis.lineScoreY,
        earlyAccept: true, reconstructed: { x: null, y: null }, residual: null,
        columns: 9, rows: 10, circles: analysis.circles,
        canny: { lineScoreX: analysis.lineScoreX, lineScoreY: analysis.lineScoreY,
            verticalSegments: analysis.verticalSegments,
            horizontalSegments: analysis.horizontalSegments },
        timing: { captureMs: ctx.captureMs || 0, locateMs: ctx.locateMs,
            totalMs: ctx.locateMs + (ctx.captureMs || 0), samples: 0,
            regionTries: 0, attempts: 1 }
    };
    if (ctx.source) result.source = ctx.source;
    if (ctx.info) result.frame = ctx.info;
    if (ctx.frameObject) result.frameObject = ctx.frameObject;
    if (ctx.imageObject) { result.imageObject = ctx.imageObject; result.imageSize = size; }
    return result;
}

function regionToRatio(region, pixelWidth, pixelHeight) {
    return {
        x: region.x / pixelWidth,
        y: region.y / pixelHeight,
        width: region.width / pixelWidth,
        height: region.height / pixelHeight
    };
}

let siftOptions = null, siftSession = null, siftError = "", siftTried = false, siftVersion = 0;
function releaseSift() {
    if (siftSession) siftSession.close();
    siftSession = null;
    siftTried = false;
}
function configureSift(options) {
    releaseSift();
    siftOptions = options || null;
    siftError = "";
}
function getSift() {
    if (!siftOptions || siftTried) return siftSession;
    siftTried = true;
    try {
        const api = siftOptions.images || images;
        const dir = siftOptions.baseDir + "/assets/board-sift/";
        siftSession = require("./sift-board-locator.js").create({ images: api,
            template: dir + "board.png", calibration: JSON.parse(files.read(dir + "calibration.json")),
            host: siftOptions.host || { sizeOf: adapter.sizeOf, nowMono: adapter.nowMono,
                clip: function (i, x, y, w, h) { return api.clip(i, x, y, w, h); },
                matchTemplate: function (i, t, o) { return api.matchTemplate(i, t, o); } } });
    } catch (error) { siftError = String(error); }
    return siftSession;
}
function confirmGeometry(image, grid) {
    const session = getSift();
    if (session) session.confirm(image, grid);
}
function checkGeometry(image, grid) {
    const session = getSift();
    return session ? session.check(image, grid) : { ok: true, tracked: false };
}
if (typeof events !== "undefined" && events && typeof events.on === "function") events.on("exit", releaseSift);

/** 两个入口共用 SIFT 定位；其他皮肤回退 Canny/Hough 与图色格线检测。 */
function locateFrame(image, options, context) {
    const opts = options || {};
    const size = adapter.sizeOf(image);
    if (!size) return { ok: false, method: "lattice", reason: "图像没有有效尺寸" };
    const started = adapter.nowMono();
    const sift = opts.sift === false ? null : getSift();
    let siftResult = null;
    if (sift) {
        try { siftResult = sift.locate(image, opts); }
        catch (error) { siftError = String(error); }
    }
    if (siftResult && siftResult.ok) {
        if (!siftResult.geometryCacheHit) siftResult.grid.version = ++siftVersion;
        const region = regionFromGrid(siftResult.grid, size.width, size.height);
        return { ok: true, method: "sift", grid: siftResult.grid, region: region,
            regionRatio: regionToRatio(region, size.width, size.height),
            columns: 9, rows: 10, attempts: 1, earlyAccept: true,
            geometryCacheHit: siftResult.geometryCacheHit, sift: siftResult.location,
            timing: { captureMs: context.captureMs || 0, locateMs: adapter.nowMono() - started,
                totalMs: adapter.nowMono() - started + (context.captureMs || 0),
                templatePrepareMs: siftResult.templatePrepareMs },
            frame: context.info || null, frameObject: context.frameObject || null,
            imageObject: context.imageObject || null, imageSize: size, source: context.source || null };
    }
    const native = adapter.analyzeXiangqiBoard(image);
    if (native && native.ok && native.grid && native.outer && Array.isArray(native.circles)) {
        const logicalSize = { width: Number(image.width) || size.width,
            height: Number(image.height) || size.height };
        return mapCannyResult(native, {
            size: logicalSize, orientation: opts.orientation,
            locateMs: adapter.nowMono() - started,
            captureMs: context.captureMs, info: context.info,
            frameObject: context.frameObject, imageObject: context.imageObject,
            source: context.source
        });
    }
    const result = latticeDetector.detect(latticeHost(), image, Object.assign({
        orientation: opts.orientation || "RED_BOTTOM"
    }, opts.latticeOptions || {}));
    const totalMs = adapter.nowMono() - started + (context.captureMs || 0);
    if (!result.ok) {
        return {
            ok: false, method: "lattice", reason: "Canny 定位：" +
                (native ? native.reason : "宿主接口不可用") + "；图色格线定位：" + result.reason +
                (siftResult ? "；SIFT：" + siftResult.reason : (siftError ? "；SIFT：" + siftError : "")),
            lattice: { ok: false, reason: result.reason, evidence: result.evidence || null },
            timing: { captureMs: context.captureMs || 0, locateMs: result.timing ? result.timing.totalMs : null,
                totalMs: totalMs, samples: result.calls || 0 },
            frame: context.info || null, frameObject: context.frameObject || null,
            imageObject: context.imageObject || null, imageSize: context.imageObject ? size : null
        };
    }
    return mapLatticeResult(result, {
        size: size, captureMs: context.captureMs || 0, totalMs: totalMs,
        info: context.info, frameObject: context.frameObject,
        imageObject: context.imageObject, source: context.source
    });
}

function locateBoardOnScreen(options) {
    const opts = options || {};
    const started = adapter.nowMono();
    let frame = opts.frame || null;
    if (!frame) {
        try { frame = adapter.capture("full", opts.timeoutMs == null ? 800 : opts.timeoutMs); }
        catch (error) { return { ok: false, method: "lattice", reason: "截图失败：" + error }; }
    }
    if (!frame) return { ok: false, method: "lattice", reason: "截图失败：没有拿到帧" };
    return locateFrame(frame, opts, {
        captureMs: opts.frame ? 0 : adapter.nowMono() - started,
        info: adapter.describeFrame(frame), frameObject: frame
    });
}

function locateBoardInImage(source, options) {
    const started = adapter.nowMono();
    let image;
    try { image = adapter.readImage(source); }
    catch (error) { return { ok: false, method: "lattice", reason: "读图失败：" + error }; }
    return locateFrame(image, options || {}, {
        captureMs: adapter.nowMono() - started,
        imageObject: image, source: typeof source === "string" ? source : null
    });
}

function describeResult(result) {
    if (!result || !result.ok) return "定位失败：" + (result && result.reason || "没有结果");
    const grid = result.grid;
    return (result.method === "sift" ? "SIFT" : (result.method === "canny-hough" ? "Canny/Hough" : "图色格线")) +
        "定位成功：竖 " + result.columns + "/9，横 " + result.rows + "/10" +
        "\n棋盘起点 (" + grid.originX.toFixed(1) + ", " + grid.originY.toFixed(1) + ")" +
        "  格距 (" + grid.stepX.toFixed(2) + ", " + grid.stepY.toFixed(2) + ")" +
        "\n耗时：" + (result.timing && result.timing.locateMs) + "ms";
}

function pointOf(result, col, row) {
    return result && result.grid ? geometry.pointAt(result.grid, col, row) : null;
}

module.exports = {
    regionFromGrid: regionFromGrid, regionToRatio: regionToRatio,
    mapLatticeResult: mapLatticeResult, mapCannyResult: mapCannyResult,
    configureSift: configureSift, releaseSift: releaseSift,
    confirmGeometry: confirmGeometry, checkGeometry: checkGeometry,
    locateBoardOnScreen: locateBoardOnScreen, locateBoardInImage: locateBoardInImage,
    describeResult: describeResult, pointOf: pointOf
};
