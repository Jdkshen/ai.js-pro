// @engine quickjs
"use strict";

/**
 * 所有运行入口共用的棋盘读数。棋子类别只来自 glyphSource。
 * detect-only 仅给定位/几何体检提供圆盘中心；不能用模型标签写入盘面。
 * 不引用 tools 或 experiments，也不为运行失败提供另一种分类回退。
 */
const detectorSession = require("./detector-session.js");
const calibration = require("./grid-calibration.js");
const adapter = require("../runtime/aijs-adapter.js");
const locator = require("./locate-board.js");
const geometry = require("./geometry.js");
const circleCheck = require("./circle-grid-check.js");

function create(glyphSource) {
    if (!glyphSource || typeof glyphSource.recognize !== "function") {
        throw new Error("board-reader 需要字形识别源");
    }

    function run(image, located, options) {
        if (!image || !located || !located.ok || !located.grid) {
            return { ok: false, reason: "字形识别缺少图像或已定位的棋盘" };
        }
        return glyphSource.recognize(image, located.grid, options || {});
    }

    const gridCalibration = calibration.create(run);
    /** SIFT 的模板带已校准交点；完整盘面及圆心都通过时直接采用，省去重复候选识别。 */
    function confirmSiftGrid(image, located, options) {
        if (!located || !located.ok || located.method !== "sift" || !located.grid) return null;
        const started = adapter.nowMono(), grid = located.grid;
        const result = run(image, located, options || {});
        if (!result || !result.ok || result.unknownCells !== 0 || !result.validation || !result.validation.ok) return null;
        const circles = adapter.detectXiangqiCircles(image, grid);
        const verdict = circleCheck.evaluate(circles, grid);
        const pieces = result.chars.filter(function (p) { return !!p; }).length;
        if (!verdict.conclusive || !verdict.ok || verdict.matched < pieces || verdict.matched < Math.ceil(verdict.total * 0.9)) return null;
        const residuals = (circles || []).map(function (c) {
            return geometry.normalizedResidual(grid, c.x, c.y);
        }).filter(function (r) { return isFinite(r) && r <= 0.27; }).sort(function (a, b) { return a - b; });
        if (residuals.length < 4) return null;
        grid.quality = { method: "sift-circle-verified", sampleCount: circles.length,
            inlierCount: residuals.length, maxResidual: residuals[residuals.length - 1],
            meanResidual: residuals.reduce(function (a, b) { return a + b; }, 0) / residuals.length };
        return { ok: true, method: "sift-verified", grid: grid, unknown: 0, dx: 0, dy: 0,
            tried: 1, layout: result.layout, ms: adapter.nowMono() - started };
    }
    function calibrateGrid(image, located, options) {
        return confirmSiftGrid(image, located, options) || gridCalibration.calibrateGrid(image, located, options);
    }
    function recognizeImage(path, options) {
        const image = adapter.readImage(path);
        try {
            const located = locator.locateBoardInImage(image, options || {});
            const result = run(image, located, options);
            if (result && result.validation && !result.validation.ok) {
                return Object.assign({}, result, { ok: false, reason: "字形规则校验未通过" });
            }
            return result;
        } finally {
            adapter.releaseFrame(image);
        }
    }
    function describeResult(result) {
        if (!result || !result.ok) return "字形识别失败：" + ((result && result.reason) || "无结果");
        return "字形识别｜未知格 " + result.unknownCells + "\n" + (result.layout || "");
    }
    function recognizeImageSequence(paths, options) {
        const results = paths.map(function (path) {
            return recognizeImage(path, options);
        });
        const valid =
            results.length > 0 &&
            results.every(function (r) {
                return r && r.ok;
            });
        const same =
            valid &&
            results.every(function (r) {
                return r.layout === results[0].layout;
            });
        return {
            ok: same,
            results: results,
            reason: same ? "各帧字形读数一致" : "存在失败帧或盘面变化，请重拍稳定局面"
        };
    }
    return {
        run: run,
        recognizeImage: recognizeImage,
        recognizeImageSequence: recognizeImageSequence,
        describeResult: describeResult,
        describeSequence: function (value) {
            return value.reason + "\n" + value.results.map(describeResult).join("\n\n");
        },
        // 旧名称仅用于几何调用方的接口兼容；实现没有棋子分类。
        createDetectionSession: detectorSession.createDetectionSession,
        pickGrid: gridCalibration.pickGrid,
        scoreGrid: gridCalibration.scoreGrid,
        calibrateGrid: calibrateGrid,
        confirmSiftGrid: confirmSiftGrid
    };
}

module.exports = { create: create };
