// @engine quickjs
/**
 * 棋盘服务（§14：把 locate / recognize / audit 这些"用一次就散"的工具调用收成业务入口）。
 *
 * 现在面板、驱动、探针、离线工具各写一遍 `locateAndCalibrate()` / `recognizeScreen()` 调用序列，
 * 抓帧藏窗、几何版本校验、未知格统计这些"每次都该做"的事经常漏做一步。
 */
"use strict";

function createBoardService(options) {
    const opts = options || {};
    const ports = opts.ports;
    const locateBoard = opts.locateBoard;
    const recognizeBoard = opts.recognizeBoard;
    const geometry = opts.geometry || null;
    const recorder = opts.recorder || null;

    let grid = null;
    let lastResult = null;
    let lastFrame = null;
    /** 抓帧前后的窗口处理由调用方注入（面板=窗口管理器；离线工具=空实现）。 */
    const hideWindow = typeof opts.hideWindow === "function" ? opts.hideWindow : function (fn) { return fn(); };
    const note = typeof opts.note === "function" ? opts.note : function () { };

    function setGrid(next) {
        grid = next || null;
        return grid;
    }

    /** 抓一帧（自动处理藏窗）。 */
    function grab() {
        return hideWindow(function () {
            const frame = ports.capture({ backend: opts.captureBackend });
            if (frame) {
                lastFrame = frame;
            }
            return frame;
        });
    }

    /** 定位 + 校准；成功返回 grid。 */
    function locate(options) {
        const started = ports.now();
        const grabbed = grab();
        if (!grabbed) {
            return { ok: false, reason: "拿不到帧", ms: ports.now() - started };
        }
        let located = null;
        try {
            located = locateBoard.locateBoardInImage
                ? locateBoard.locateBoardInImage(grabbed.image, options || {})
                : locateBoard.locate(grabbed.image, options || {});
        } catch (error) {
            return { ok: false, reason: "定位异常：" + error, ms: ports.now() - started };
        }
        if (!located || !located.ok) {
            return {
                ok: false, reason: located && located.reason ? located.reason : "定位没有结果",
                ms: ports.now() - started
            };
        }
        grid = located.grid;
        const result = { ok: true, grid: grid, ms: ports.now() - started, located: located };
        note("棋盘已定位：起点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
            ") 格距 (" + Math.round(grid.stepX) + "," + Math.round(grid.stepY) + ") 版本 v" + grid.version);
        return result;
    }

    /** 识别一帧（必要时先定位）。 */
    function recognize(options) {
        const callbacks = options || {};
        if (!grid) {
            const located = locate({});
            if (!located.ok) {
                return { ok: false, reason: "定位失败：" + located.reason };
            }
        }
        const grabbed = grab();
        if (!grabbed) {
            return { ok: false, reason: "识别失败：拿不到帧" };
        }
        let result = null;
        try {
            result = recognizeBoard.run(grabbed.image, {
                ok: true, method: "board-service", grid: grid,
                imageObject: grabbed.image, frameObject: grabbed.image
            }, {
                orientation: callbacks.orientation || opts.orientation || "RED_BOTTOM",
                confidence: callbacks.confidence == null ? (opts.confidence == null ? 0.25 : opts.confidence) : callbacks.confidence,
                autoAlign: !!callbacks.autoAlign
            }, null);
        } catch (error) {
            return { ok: false, reason: "识别异常：" + error };
        }
        if (!result || !result.ok) {
            return { ok: false, reason: result && result.reason ? result.reason : "识别没有结果" };
        }
        lastResult = result;
        // 几何跟着检测框微调时，务必同步 grid（否则"按 A 几何认棋盘、按 B 几何点坐标"）
        if (result.grid && grid && (result.grid.originX !== grid.originX || result.grid.originY !== grid.originY)) {
            const dx = Math.round(result.grid.originX - grid.originX);
            const dy = Math.round(result.grid.originY - grid.originY);
            grid = result.grid;
            note("几何按检测框微调：原点偏移 (" + dx + "," + dy + ")");
        }
        if (recorder) {
            recorder.event("recognize", {
                unknownCells: result.unknownCells,
                layout: result.layout || null,
                gridVersion: grid ? grid.version : null
            });
        }
        return { ok: true, result: result, grid: grid, frame: grabbed };
    }

    return {
        locate: locate,
        recognize: recognize,
        grab: grab,
        grid: function () { return grid; },
        setGrid: setGrid,
        lastResult: function () { return lastResult; },
        lastFrame: function () { return lastFrame; },
        clear: function () {
            grid = null;
            lastResult = null;
            lastFrame = null;
        },
        describe: function () {
            if (!grid) {
                return "棋盘：未定位";
            }
            return "棋盘：v" + grid.version + " 起点 (" + Math.round(grid.originX) + "," +
                Math.round(grid.originY) + ") 格距 (" + Math.round(grid.stepX) + "," +
                Math.round(grid.stepY) + ")";
        }
    };
}

module.exports = { createBoardService: createBoardService };
