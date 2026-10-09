// @engine quickjs
/**
 * 场景（**负例**）：OCR 全废（一个格子都读不出、框也没有）⇒ 不许乱猜、不许崩。
 *
 * 真机上这种帧很常见（抓帧糊了 / 游戏在动画 / OCR 会话刚建好没热）。
 * 期望行为：整帧产出「没有任何确认」，而不是补出一盘假局面 ——
 * 调用方看到确认数 0 会自然退回旧路径或重试下一帧。
 */
"use strict";

module.exports = {
    name: "OCR 全废：没有任何确认、不许乱补、不许崩",
    tags: ["pipeline", "negative"],
    run: function (t) {
        const host = t.host({
            pieces: [
                { col: 4, row: 9, label: "K", ocr: null, side: "red" },
                { col: 3, row: 9, label: "A", ocr: null, side: "red" },
                { col: 4, row: 0, label: "K", ocr: null, side: "black" },
                { col: 0, row: 0, label: "R", ocr: null, side: "black" }
            ],
            hiddenFromDetect: ["4,9", "3,9", "4,0", "0,0"]
        });
        const pipeline = t.pipeline(host);
        const result = t.recognize(pipeline, host, 3);

        t.ok(result && result.ok === true, "流水线应当正常返回（不许抛异常）");
        t.equal(result.fusion.confirmed, 0, "没有任何证据时确认数必须是 0");
        t.ok(result.chars[85] == null, "(4,9) 不许凭空补一个帅，实际 " + JSON.stringify(result.chars[85]));
        t.ok(!result.stats.kingRescued, "抢救也不该发生（没有字形、没有框）");
        t.ok(host.stats().leaked === 0, "临时帧不许泄漏");
    }
};
