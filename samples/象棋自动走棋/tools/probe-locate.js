// @engine quickjs
/**
 * 定位两条通道的**实机体检**（2026-09-25）：文件图 vs 当前屏幕。
 *
 * 为什么单独体检：`tools/probe-glyph.js`（跑 9 帧图片集）在真机上卡在「模板加载完成」之后，
 * 而对着当前屏幕的新链路探针 0.4s 就跑完了 —— 差别只在于**喂进去的是文件图还是屏幕帧**。
 * 主页面还有一个「定位图片」按钮也走文件图这条路，所以值得单独量一次。
 *
 * 用法： am start … -d file:///sdcard/…/tools/probe-locate.js
 * 输出： logcat `[locate-probe]` + `/sdcard/xiangqi-p4/locate-probe.txt`
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) { /* 默认当前目录 */ }
    return ".";
}());

function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

const adapter = load("src/runtime/aijs-adapter.js");
const locateBoard = load("src/vision/locate-board.js");

const FRAME = "/sdcard/xiangqi-p4/skin-eval/skin-a.png";
const OUT = "/sdcard/xiangqi-p4/locate-probe.txt";

const lines = [];
function log(text) {
    console.log("[locate-probe] " + text);
    lines.push(text);
}

log("起点目录：" + BASE_DIR);
log("探针文件：" + FRAME);

// ① 原生读图（adapter.readImage）
let image = null;
try {
    const t0 = Date.now();
    image = adapter.readImage(FRAME);
    const size = image ? adapter.sizeOf(image) : null;
    log("① adapter.readImage：" + (image ? "ok" : "null") + " 用时 " + (Date.now() - t0) + "ms" +
        (size ? (" 尺寸 " + size.width + "×" + size.height) : ""));
} catch (error) {
    log("① adapter.readImage 异常：" + error);
}

// ② 引擎 images.read（tools/probe-glyph.js 走的那条）
try {
    const t1 = Date.now();
    const img2 = images.read(FRAME);
    log("② images.read：" + (img2 ? "ok" : "null") + " 用时 " + (Date.now() - t1) + "ms");
} catch (error) {
    log("② images.read 异常：" + error);
}

// ③ 文件图定位（主页面「定位图片」按钮 / probe-glyph 走这条路）
try {
    const t2 = Date.now();
    const got = locateBoard.locateBoardInImage(FRAME);
    log("③ locateBoardInImage：" + (got && got.ok ? ("ok " + got.method) : ("失败 " + (got && got.reason))) +
        " 用时 " + (Date.now() - t2) + "ms" +
        (got && got.ok ? ("｜" + locateBoard.describeResult(got).replace(/\n/g, " / ")) : ""));
} catch (error) {
    log("③ locateBoardInImage 异常：" + error);
}

// ④ 屏幕帧定位（生产链路走这条）
try {
    const t3 = Date.now();
    const got = locateBoard.locateBoardOnScreen();
    log("④ locateBoardOnScreen：" + (got && got.ok ? ("ok " + got.method) : ("失败 " + (got && got.reason))) +
        " 用时 " + (Date.now() - t3) + "ms" +
        (got && got.ok ? ("｜" + locateBoard.describeResult(got).replace(/\n/g, " / ")) : ""));
} catch (error) {
    log("④ locateBoardOnScreen 异常：" + error);
}

if (image && typeof image.recycle === "function") {
    try { image.recycle(); } catch (ignore) { /* 忽略 */ }
}
try {
    files.write(OUT, lines.join("\n"));
} catch (error) {
    console.log("[locate-probe] 写结果失败：" + error);
}
console.log("[locate-probe] DONE");
