// @engine quickjs
/**
 * 场景：帅的字形**读出来了但只有 0.51 分**（低于 0.6 门槛）⇒ 走**规则层补全**，不是「抢救」。
 *
 * 真机来源（2026-09-20，K40）：`(4,9)` 红帅 OCR 给 0.51 —— 融合层因为分数不够把它记成 `?`，
 * 然后由规则层（`inferKings`，只吃 `?` 格子）按「九宫里缺一个帅」补上。
 *
 * 与「抢救（无框 / 有框）」的区别：那两条处理的是**连 `?` 都没有**（框也没检出、字也没读出）的格子。
 * 三条路径的日志长得像，但触发条件完全不同 —— 这条就是用来钉住「分数不够 ⇒ 规则补全」的边界，
 * 免得哪天 0.6 门槛被调低，悄悄把「规则补全」变成「拿低分字形当真」。
 */
"use strict";

module.exports = {
    name: "规则补全：帅字形只有 0.51 分 ⇒ 记成 `?` 再由规则层补上",
    tags: ["pipeline", "king"],
    run: function (t) {
        const host = t.host({
            pieces: [
                { col: 4, row: 9, label: "K", ocr: { text: "帅", score: 0.51 }, side: "red" },
                { col: 3, row: 9, label: "A", ocr: { text: "仕", score: 0.95 }, side: "red" },
                { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.95 }, side: "black" }
            ]
        });
        const pipeline = t.pipeline(host);
        const result = t.recognize(pipeline, host, 3);

        t.equal(result.chars[85], "K", "(4,9) 红帅应当由规则层补全");
        t.ok(result.stats.inferred >= 1, "统计里应当记 inferred（规则补全），实际 " + result.stats.inferred);
        t.ok(!result.stats.kingRescued,
            "这一格字形读到了（只是分低）⇒ 属于规则补全，不该走「抢救」那条路，实际 kingRescued=" + result.stats.kingRescued);
        t.ok(result.validation && result.validation.ok,
            "规则门应当通过，实际 " + (result.validation ? result.validation.reason : "没有 validation"));
    }
};
