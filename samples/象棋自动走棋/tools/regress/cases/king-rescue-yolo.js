// @engine quickjs
/**
 * 场景：帅/将**有检测框、但字形读不出** ⇒ 靠 YOLO 框类别当候选在九宫里抢救。
 *
 * 真机来源（2026-09-20，K40）：红帅那一格 PP-OCR 44ms 也读不出（`印市` 0.49 这种垃圾），
 * 但**检测框是在的**。修法：本帧已经算好的框 + 类别标签当候选筛选（零额外开销），
 * 红黑仍由找色像素定，再叠「九宫 + 该方恰好缺一个 + 宫中唯一候选」。
 */
"use strict";

module.exports = {
    name: "帅/将抢救（有框）：YOLO 框类别当候选 + 九宫 + 找色颜色",
    tags: ["pipeline", "king"],
    run: function (t) {
        const host = t.host({
            pieces: [
                // 红帅：字形**两遍都读不出**（彩色 + 灰度都是 null）⇒ 只有 YOLO 候选法能救
                { col: 4, row: 9, label: "K", ocr: null, side: "red" },
                { col: 3, row: 9, label: "A", ocr: { text: "仕", score: 0.95 }, side: "red" },
                { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.95 }, side: "black" }
            ]
        });
        const pipeline = t.pipeline(host);
        const result = t.recognize(pipeline, host, 3);

        t.equal(result.chars[85], "K", "(4,9) 红帅应当被 YOLO 候选法抢救回来（85 = row9*9+col4）");
        t.equal(result.chars[84], "A", "(3,9) 红仕应当由字形路正常读出，不该被抢成帅");
        t.ok(result.stats.kingRescued >= 1, "统计里应当记 kingRescued");
        t.ok(result.stats.kingRescuedByYolo >= 1, "应当记 kingRescuedByYolo（YOLO 定位优先）");
        t.ok(result.validation && result.validation.ok,
            "抢救之后规则门应当通过，实际 " + (result.validation ? result.validation.reason : "没有 validation"));
        t.equal(host.stats().leaked, 0, "临时帧不许泄漏（真机上会堆在原生帧表里）");
    }
};
