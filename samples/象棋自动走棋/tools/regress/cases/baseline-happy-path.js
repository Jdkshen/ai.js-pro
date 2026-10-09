// @engine quickjs
/**
 * 场景（**基线/对照**）：一切正常的一帧 —— 每枚子都读得出、框都对得上。
 *
 * 这条是「别为了救一个帅把整盘搞坏」的对照：
 *   · 字形直接读出，**不该发生任何抢救**（`kingRescued` 为空）；
 *   · 一轮读完（没有兜底重读：`detects === 4`，且全部走灰度帧）；
 *   · 规则门通过、四个格子落在正确位置。
 * 前面几条「抢救类」场景如果在别的帧上变得过于激进，这条会第一个红。
 */
"use strict";

module.exports = {
    name: "基线（对照）：正常帧先灰度一轮直接读出，无抢救、无兜底重读、规则门通过",
    tags: ["pipeline", "baseline"],
    run: function (t) {
        const host = t.host({
            pieces: [
                { col: 4, row: 9, label: "K", ocr: { text: "帅", score: 0.95 }, side: "red" },
                { col: 3, row: 9, label: "A", ocr: { text: "仕", score: 0.96 }, side: "red" },
                { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.95 }, side: "black" },
                { col: 3, row: 0, label: "A", ocr: { text: "士", score: 0.94 }, side: "black" }
            ]
        });
        const pipeline = t.pipeline(host);
        const result = t.recognize(pipeline, host, 3);

        t.equal(result.chars[85], "K", "(4,9) 红帅");
        t.equal(result.chars[84], "A", "(3,9) 红仕");
        t.equal(result.chars[4], "k", "(4,0) 黑将");
        t.equal(result.chars[3], "a", "(3,0) 黑士");
        t.ok(!result.stats.kingRescued, "正常帧不该发生抢救，实际 kingRescued=" + result.stats.kingRescued);
        t.equal(host.stats().detects, 4, "正常帧应当一轮读完（4 枚子 × 1 次 OCR），实际 " + host.stats().detects);
        t.equal(host.stats().grays, 4, "默认「先灰度」：4 枚子都该走灰度帧（没有多花一次彩色），实际 " + host.stats().grays);
        t.ok((result.fusion.unknown || 0) <= 2, "未知格应当很少，实际 " + result.fusion.unknown);
        t.ok(result.validation && result.validation.ok,
            "规则门应当通过，实际 " + (result.validation ? result.validation.reason : "没有 validation"));
        t.equal(host.stats().leaked, 0, "临时帧不许泄漏");
    }
};
