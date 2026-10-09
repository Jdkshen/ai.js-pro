// @engine quickjs
/**
 * 场景：帅**连检测框都没有** ⇒ 用 `board-scan`（字形 + 找色）在九宫里抢救，而不是整帧退回旧路径。
 *
 * 与「有框」那条的分工：
 *   · 有框 ⇒ YOLO 候选法（零开销，跑在前面）；
 *   · 没框 ⇒ 只能扫九宫 9 格的字形（≈0.4s，跑在后面）。
 * 两条都要求「九宫里恰好一个候选」，所以本场景同时守住「不许把别的子当帅」。
 */
"use strict";

module.exports = {
    name: "帅/将抢救（无框）：九宫里用 board-scan 字形 + 找色救回",
    tags: ["pipeline", "king"],
    run: function (t) {
        const host = t.host({
            pieces: [
                { col: 4, row: 9, label: "K", ocr: { text: "帅", score: 0.62 }, side: "red" },
                { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.95 }, side: "black" },
                { col: 0, row: 0, label: "R", ocr: { text: "车", score: 0.92 }, side: "black" }
            ],
            hiddenFromDetect: ["4,9"]        // 帅：检测框没检出（真机：模型对帅/将召回差）
        });
        const pipeline = t.pipeline(host);
        const result = t.recognize(pipeline, host, 3);

        t.equal(result.chars[85], "K", "(4,9) 应当被字形路抢救成红帅");
        t.ok(result.stats.kingRescued >= 1, "统计里应当记 kingRescued");
        t.notOk(result.stats.kingRescuedByYolo, "这一格没有框，不该记成「YOLO 路」（kingRescuedByYolo）");
        t.ok(result.validation && result.validation.ok,
            "抢救之后规则门应当通过，实际 " + (result.validation ? result.validation.reason : "没有 validation"));
        t.equal(host.stats().leaked, 0, "临时帧不许泄漏");
        t.equal(host.stats().detects, 5, "只读一轮，没有兜底重读（账：第 1 帧 2 枚有框子 + 每帧 1 格九宫抢救），实际 " + host.stats().detects);
        t.equal(host.stats().grays, host.stats().detects, "默认先灰度：每次读都走灰度帧，没有多花一次彩色，实际 " + host.stats().grays + "/" + host.stats().detects);
    }
};
