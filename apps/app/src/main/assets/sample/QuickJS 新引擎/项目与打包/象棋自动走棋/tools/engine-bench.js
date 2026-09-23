// @engine quickjs
/**
 * 引擎基准（离线）：同一局面跑不同时间预算，看能到几层、多少结点。
 * 用途：估算「K40 上 1200ms 只到深度 2」该把预算提到多少、以及哪些热点值得优化。
 * 用法：node tools/engine-bench.js
 */
"use strict";

const engine = require("../src/engine/engine.js");

const CASES = [
    {
        name: "开局（满盘 32 子，分枝最大）",
        fen: "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"
    },
    {
        name: "中炮三四手（实战典型）",
        fen: "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C2C4/9/RNBAKABNR w - - 0 1"
    },
    {
        name: "残局（子少，深度应更高）",
        fen: "3k5/9/9/9/9/r8/9/4R4/9/4K4 w - - 0 1"
    }
];

const MOVETIMES = [1200, 3000, 6000, 12000];

for (let i = 0; i < CASES.length; i++) {
    const item = CASES[i];
    console.log("=== " + item.name);
    for (let m = 0; m < MOVETIMES.length; m++) {
        const result = engine.analyze(item.fen, { movetime: MOVETIMES[m] });
        if (!result.ok) {
            console.log("  movetime=" + MOVETIMES[m] + " → 失败：" + result.reason);
            continue;
        }
        const nps = result.ms > 0 ? Math.round(result.nodes / (result.ms / 1000)) : 0;
        console.log("  movetime=" + MOVETIMES[m] +
            " → 深度 " + result.depth +
            " 结点 " + result.nodes + "（" + nps + " nps）" +
            " 实际 " + result.ms + "ms" +
            " 着法 " + result.iccs +
            " 评分 " + result.score);
    }
}
