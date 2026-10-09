// @engine quickjs
/**
 * 场景（**反例**）：宫里有红仕、帅没检出 ⇒ **不许**把红仕当帅补上。
 *
 * 抢救那条路是「猜」出来的一格，必须靠四道约束把它钉死：
 *   ① 九宫范围；② 该方恰好缺一个；③ 找色颜色与该方一致；④ 宫中唯一候选。
 * 本场景专门打 ①③④ 的组合：仕在九宫里、颜色也是红、字形还是「仕」——
 * YOLO 类别（label=A）不是 K ⇒ 候选筛掉；字形不是帅 ⇒ 也筛掉 ⇒ 抢救**不发生**。
 * 如果哪天有人为了「提高抢救率」把这些约束放松，这条场景会立刻红。
 */
"use strict";

module.exports = {
    name: "帅/将抢救（反例）：宫里的红仕不该被当成帅补上",
    tags: ["pipeline", "king", "negative"],
    run: function (t) {
        const host = t.host({
            pieces: [
                { col: 3, row: 9, label: "A", ocr: { text: "仕", score: 0.96 }, side: "red" },   // 九宫里的红仕
                { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.95 }, side: "black" }
            ],
            hiddenFromDetect: ["4,9"]        // 真帅那一格没检出（本来也没有帅）
        });
        const pipeline = t.pipeline(host);
        const result = t.recognize(pipeline, host, 3);

        t.equal(result.chars[84], "A", "(3,9) 应当还是红仕");
        t.notEqual(result.chars[84], "K", "(3,9) 绝不能被补成帅");
        t.ok(!result.stats.kingRescued, "不该发生抢救，实际 kingRescued=" + result.stats.kingRescued);
        t.ok(!result.validation || !result.validation.ok,
            "红帅数量 0 ⇒ 规则门应当**否决**这一帧（宁可退回旧路径，也不能凭空补一个帅）");
    }
};
