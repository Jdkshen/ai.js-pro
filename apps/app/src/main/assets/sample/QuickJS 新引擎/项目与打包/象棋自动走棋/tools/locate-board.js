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
 */
"use strict";

const adapter = require("../src/runtime/aijs-adapter.js");
const locator = require("../src/vision/board-locator.js");
const geometry = require("../src/vision/geometry.js");

/** 常见布局下的棋盘大致范围（占屏幕宽高的比例），可被调用方覆盖。 */
const DEFAULT_REGION_RATIO = { x: 0.025, y: 0.26, width: 0.95, height: 0.48 };

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
 * 截一帧并在范围内自动定位棋盘。
 * @param options.region      棋盘范围（像素坐标，相对**像素尺寸**）；不给就用默认比例
 * @param options.regionRatio 比例形式的棋盘范围
 * @param options.orientation "RED_BOTTOM" | "RED_TOP"（默认红方在下）
 * @param options.step        扫描步长（像素，默认 4）；调大更快更粗，到 5 以上开始会挑错格点阵
 * @param options.acrossStep  跨采样步长（像素，默认 10）
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
    const region = opts.region || ratioToRegion(opts.regionRatio, pixelWidth, pixelHeight);

    const step = opts.step == null ? 4 : opts.step;
    const acrossStep = opts.acrossStep == null ? 10 : opts.acrossStep;
    // 采样点数（供性能判断：当前平台只能逐点取像素）。粗扫 + 精修（精修约两成）
    const samples = Math.round((
        Math.ceil(region.width / step) * Math.ceil(region.height / acrossStep) +
        Math.ceil(region.height / step) * Math.ceil(region.width / acrossStep)) * 1.2);

    const scanStart = adapter.nowMono();
    const result = locator.locate({
        getValue: adapter.samplerFor(frame),
        region: region,
        orientation: opts.orientation || "RED_BOTTOM",
        step: step,
        acrossStep: acrossStep,
        minSupport: opts.minSupport,
        tolerance: opts.tolerance,
        margin: opts.margin
    });
    const locateMs = adapter.nowMono() - scanStart;

    const timing = {
        captureMs: captureMs,
        locateMs: locateMs,
        totalMs: adapter.nowMono() - startedAt,
        samples: samples
    };
    if (!result.ok) {
        return {
            ok: false,
            reason: result.reason,
            method: result.method,
            region: region,
            timing: timing,
            frame: info
        };
    }
    const grid = result.grid;
    return {
        ok: true,
        method: result.method,
        reason: result.reason,
        warning: result.warning || "",
        grid: grid,
        region: region,
        regionRatio: regionToRatio(region, pixelWidth, pixelHeight),
        columns: (result.vertical ? result.vertical.matched : null),
        rows: (result.horizontal ? result.horizontal.matched : null),
        reconstructed: {
            x: result.vertical ? result.vertical.quality.reconstructed : null,
            y: result.horizontal ? result.horizontal.quality.reconstructed : null
        },
        residual: grid.quality ? grid.quality.maxResidual : null,
        frame: info,
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
        lines.push("补回的线：竖 " + result.reconstructed.x + " 条 / 横 " + result.reconstructed.y + " 条" +
            "  最大残差 " + (result.residual == null ? "-" : result.residual.toFixed(3)));
        if (result.warning) {
            lines.push("⚠ " + result.warning);
        }
    }
    if (result.frame) {
        lines.push("屏幕 " + result.frame.width + "×" + result.frame.height +
            "（像素 " + result.frame.pixelWidth + "×" + result.frame.pixelHeight + "）");
    }
    if (result.region) {
        lines.push("棋盘范围 " + result.region.x + "," + result.region.y + " " +
            result.region.width + "×" + result.region.height);
    }
    if (result.timing) {
        lines.push("耗时：截图 " + result.timing.captureMs + "ms / 定位 " + result.timing.locateMs +
            "ms（取像素约 " + result.timing.samples + " 次）");
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
    locateBoardOnScreen: locateBoardOnScreen,
    describeResult: describeResult,
    pointOf: pointOf
};

module.exports = LOCATE_BOARD;
