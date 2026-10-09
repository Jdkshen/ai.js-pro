// @engine quickjs
/**
 * `images.clip` 坐标系最小诊断（2026-09-20）。
 *
 * 起因：`tools/ocr-prep-probe.js` 在真机上刚开跑就 `RangeError: Clip rectangle is outside the source frame`，
 * 而同一帧用 `session.detect(image, {region})` 一直是好的 ⇒ 说明 **帧的逻辑尺寸/像素尺寸** 与
 * `cropper` 给出的格子坐标不在同一套尺度上，必须先量清楚再改预处理代码。
 *
 * 打印：帧的 width/height（逻辑）与 pixelWidth/pixelHeight（像素）、captureMode、若干 clip 尝试的结果。
 * 日志前缀 `[clipdbg]`。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            // ⚠️ PC（Node）上是反斜杠、真机（QuickJS）是正斜杠 ⇒ 先统一再剥掉 /tools
            return String(__dirname).replace(/\\/g, "/").replace(/\/tools$/, "");
        }
    } catch (ignore) {
        // 忽略
    }
    return ".";
}());

function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

const adapter = load("src/runtime/aijs-adapter.js");

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[clipdbg] " + text);
    }
}

function tryClip(image, x, y, w, h, tag) {
    try {
        const frame = images.clip(image, x, y, w, h);
        log("clip " + tag + " (" + x + "," + y + "," + w + "," + h + ") ok 逻辑 " + frame.width + "x" + frame.height +
            " 像素 " + frame.pixelWidth + "x" + frame.pixelHeight);
        frame.recycle();
        return true;
    } catch (error) {
        log("clip " + tag + " (" + x + "," + y + "," + w + "," + h + ") 失败：" + error);
        return false;
    }
}

function main() {
    log("开始｜有 images.clip=" + (typeof images !== "undefined" && typeof images.clip === "function"));
    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    const image = adapter.capture("full", 1500);
    if (!image) {
        log("截图失败");
        return;
    }
    log("帧：逻辑 " + image.width + "x" + image.height +
        "｜像素 " + image.pixelWidth + "x" + image.pixelHeight +
        "｜mode=" + image.captureMode +
        "｜通道=" + (adapter.describeFrame ? adapter.describeFrame(image) : "-"));

    // 1) 原点小方块（任何尺度下都该合法）
    tryClip(image, 0, 0, 100, 100, "origin");
    // 2) 典型格子（K40 棋盘在第 4~5 列、第 6~7 行附近，直接用逻辑坐标试）
    tryClip(image, 400, 600, 80, 80, "cell-logical");
    // 3) 按像素坐标试同一处（若逻辑=像素，两者结果应一致）
    tryClip(image, 400, 600, 80, 80, "cell-same");
    // 4) 右下角（越界检测用）
    tryClip(image, image.width - 100, image.height - 100, 80, 80, "bottom-right-logical");
    tryClip(image, image.pixelWidth - 100, image.pixelHeight - 100, 80, 80, "bottom-right-pixel");
    // 5) 右边界外一点点（应失败，用来确认报错长什么样）
    tryClip(image, image.width - 10, image.height - 10, 80, 80, "out-of-range");
    log("结束");
}

main();
