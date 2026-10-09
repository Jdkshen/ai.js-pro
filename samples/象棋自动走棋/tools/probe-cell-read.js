// @engine quickjs
/**
 * 探针：对比「整帧+region」「裁剪后 detect」「裁剪+灰度」三条逐格读法。
 *
 * 背景（2026-09-21 00:07 now53 真机）：同样窄裁框下，驱动证据（board-scan：裁剪+detect）把
 * (5,3) 读成 r、而面板的逐格路（整帧+region）读成 n，两路长期打架 ⇒ 确认每次 UNRELIABLE。
 * 这个探针在同一帧、同一 region 上把三条读法都跑一遍，直接看差在哪。
 *
 * 用法：push 到工程目录后
 *   am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity --es path <路径>
 * 结果看 logcat（前缀 [cellread]）。几何用实测值（K40），换机改 ORIGIN 常量。
 */
"use strict";

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return __dirname.replace(/\/tools$/, "");
        }
    } catch (ignore) {
        // 没有 __dirname 就按当前目录算
    }
    return ".";
}());

function load(relative) {
    const path = BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative);
    return require(path);
}

const adapter = load("src/runtime/aijs-adapter.js");

function log(text) {
    if (typeof console !== "undefined" && console.log) {
        console.log("[cellread] " + text);
    }
}

// K40 实测几何（棋子中心 ≈ origin + col*stepX / row*stepY）
const ORIGIN = { x: 95, y: 705, sx: 115, sy: 114 };
// 争议三格 + 对照组（(8,2) 黑馬 n、(4,2) 黑炮 c、(4,8) 红炮 C、(0,6) 红兵 P）
const CELLS = [[5, 3], [1, 4], [4, 6], [8, 2], [4, 2], [4, 8], [0, 6], [2, 7], [3, 7]];

function readOnce(session, what, image, region) {
    try {
        const blocks = session.detect(image, what === "clip" || what === "gray"
            ? { max: 1 }
            : { max: 1, region: region }) || [];
        const one = blocks[0];
        if (!one) {
            return "空";
        }
        const score = one.score == null ? one.confidence : one.score;
        return String(one.text || one.label || "?") + "/" + score;
    } catch (error) {
        return "异常:" + error;
    }
}

function main() {
    if (!adapter.requestCapture("portrait")) {
        log("截图授权失败");
        return;
    }
    const frame = adapter.capture("full", 1500);
    if (!frame) {
        log("截图失败");
        return;
    }
    log("帧 OK " + (adapter.describeFrame ? adapter.describeFrame(frame) : ""));
    if (typeof $ocr === "undefined" || typeof $ocr.create !== "function") {
        log("没有 $ocr，结束");
        return;
    }
    const session = $ocr.create({ backend: "ppocr" });
    const size = Math.round(Math.min(ORIGIN.sx, ORIGIN.sy) * 0.82 * 0.70);
    for (let k = 0; k < CELLS.length; k++) {
        const c = CELLS[k][0];
        const r = CELLS[k][1];
        const cx = ORIGIN.x + c * ORIGIN.sx;
        const cy = ORIGIN.y + r * ORIGIN.sy;
        const region = [Math.round(cx - size / 2), Math.round(cy - size / 2), size, size];
        const a = readOnce(session, "full", frame, region);
        let clip = null;
        let b = "跳过";
        try {
            clip = adapter.prepareRegion(frame, region, "clip");
            b = clip ? readOnce(session, "clip", clip, null) : "越界";
        } catch (error) {
            b = "异常:" + error;
        }
        if (clip && typeof clip.recycle === "function") {
            try { clip.recycle(); } catch (ignore) { /* 忽略 */ }
        }
        let gray = null;
        let c2 = "跳过";
        try {
            gray = adapter.prepareRegion(frame, region, "gray");
            c2 = gray ? readOnce(session, "gray", gray, null) : "越界";
        } catch (error) {
            c2 = "异常:" + error;
        }
        if (gray && typeof gray.recycle === "function") {
            try { gray.recycle(); } catch (ignore) { /* 忽略 */ }
        }
        log("(" + c + "," + r + ") region[" + region.join(",") + "] A整帧=" + a +
            "｜B裁剪=" + b + "｜C裁剪灰度=" + c2);
    }
    log("完成");
}

main();
