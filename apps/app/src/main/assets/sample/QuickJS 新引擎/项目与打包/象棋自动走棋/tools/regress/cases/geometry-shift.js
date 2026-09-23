// @engine quickjs
/**
 * 场景（**负例**）：检测框整体错位一格 ⇒ 框必须被拒，**不许把子写进错格子**。
 *
 * 真机来源（2026-09-13 小米8）：定位锁到「偏移 3 列的假格点」，局面全是 `?`、一路 RECOVERING。
 *
 * ⚠️ 这里只测**半格级**错位（60px 下移）。为什么不用「整格」：
 *   框心正好落在**错格的中心**时距离是 0 ⇒ 距离闸门**根本抓不住**（实测：整格下移后 2 个框
 *   “映射”到了隔壁格、杳无声息），流水线会安静地把每个子写到隔壁格。
 *   **整格/整行错位要靠几何体检**（面板的 `grid-sanity` 整行偏检测 + `quality` 残差），不是这一层的事。
 *   所以本场景守两条：① 半格错位 ⇒ 全被拒；② 被拒之后不许在非九宫格子出现任何子。
 */
"use strict";

module.exports = {
    name: "几何错位（负例）：框整体偏半格 ⇒ 全被拒、不许写进错格子",
    tags: ["pipeline", "geometry", "negative"],
    run: function (t) {
        const host = t.host({
            pieces: [
                { col: 4, row: 9, label: "K", ocr: { text: "帅", score: 0.95 }, side: "red" },
                { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.95 }, side: "black" },
                { col: 0, row: 0, label: "R", ocr: { text: "车", score: 0.92 }, side: "black" }
            ]
        });
        // 把所有检测框**整体下移半格**：模拟几何定偏（不是整格 ⇒ 框心落在两交点之间）
        const shifted = Object.assign({}, host, {
            detect: function () {
                return host.detect().map(function (one) {
                    const bounds = one.bounds;
                    return Object.assign({}, one, {
                        bounds: {
                            centerX: bounds.centerX,
                            centerY: bounds.centerY + host.grid.stepY / 2,
                            width: bounds.width,
                            height: bounds.height
                        }
                    });
                });
            }
        });
        const pipeline = t.pipeline(shifted);
        const result = t.recognize(pipeline, shifted, 3);

        t.ok(result && result.ok === true, "流水线应当正常返回（不许抛异常）");
        t.equal(result.detection.mapped, 0, "偏半格的框应当**一个都不映射**，实际映射 " + result.detection.mapped + " 个");
        t.ok(result.detection.rejected.length >= 3,
            "每个框都该带拒绝理由，实际 " + result.detection.rejected.length + " 个：" +
            JSON.stringify(result.detection.rejected.slice(0, 2)));
        // 关键：非九宫的格子必须全空（错位的框不许把子写到隔壁格）
        const palace = function (i) {
            const row = Math.floor(i / 9);
            const col = i % 9;
            return col >= 3 && col <= 5 && (row <= 2 || row >= 7);
        };
        let bogus = [];
        for (let i = 0; i < 90; i++) {
            if (!palace(i) && result.chars[i] != null) {
                bogus.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")=" + result.chars[i]);
            }
        }
        t.equal(bogus.length, 0, "非九宫格子不该出现棋子，实际 " + bogus.join(" "));
        t.equal(shifted.stats().leaked, 0, "临时帧不许泄漏");
    }
};
