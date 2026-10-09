// @engine quickjs
/**
 * 场景：彩色读「印市」0.49 ⇒ **灰度重试**读「師」0.70 ⇒ 抢救成功。
 *
 * 真机来源（2026-09-20，K40，(4,7) 红帅）：游戏画的是**繁体「帥」**，
 * 彩色原图 OCR 给出垃圾「印市」0.49；灰度图给出「師」0.70 —— 两者只差一撇。
 * 「師」不在棋盘字形集合里（棋子只有 帥/將/車/馬/炮/相/仕/兵），已在 `piece-ocr` 的字形表里收成 K。
 *
 * 这条场景同时守住三件事：灰度路真的会跑、救回的字真的被采信、临时帧都要还回去。
 *（2026-09-21 起主读就是灰度 ⇒ 「師」一次直读出来；彩色那遍「印市」根本不跑。）
 */
"use strict";

module.exports = {
    name: "灰度直读救回红帅：「師」0.70（2026-09-20 老版：彩色「印市」0.49 ⇒ 灰度重试 0.70）",
    tags: ["pipeline", "king", "ocr"],
    run: function (t) {
        const host = t.host({
            pieces: [
                { col: 4, row: 9, label: "K", side: "red",
                    ocr: { text: "\u5370\u5E02", score: 0.49 },            // 彩色：印市（真机原样）
                    ocrGray: { text: "\u5E2B", score: 0.70 } },            // 灰度：師（U+5E2B）
                { col: 4, row: 0, label: "K", ocr: { text: "将", score: 0.95 }, side: "black" }
            ],
            hiddenFromDetect: ["4,9"]        // 没有框 ⇒ 只能走字形（+ 灰度重试）那条路
        });
        const pipeline = t.pipeline(host);
        const result = t.recognize(pipeline, host, 3);

        t.equal(result.chars[85], "K", "(4,9) 应当靠「灰度重试 + 師→帅」救回来");
        t.ok(host.stats().grays >= 1, "应当真的申请过灰度帧，实际 " + host.stats().grays + " 次");
        t.ok(result.stats.kingRescued >= 1, "统计里应当记 kingRescued");
        t.ok(result.validation && result.validation.ok,
            "抢救之后规则门应当通过，实际 " + (result.validation ? result.validation.reason : "没有 validation"));
        t.equal(host.stats().leaked, 0, "临时帧不许泄漏（灰度帧也要还）");
    }
};
