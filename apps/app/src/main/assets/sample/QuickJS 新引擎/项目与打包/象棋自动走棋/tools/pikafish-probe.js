// @engine quickjs
/**
 * Pikafish 探针：验证 $pikafish（libpikafish.so + pikafish.nnue）在真机上的端到端链路。
 *
 * 覆盖：available / start（含权重加载耗时）/ analyze（开局 + 中局战术 FEN）/ isRunning / quit。
 * 结果同时写 /sdcard/xiangqi-p4/pikafish-probe.txt（headless 跑完用 adb 取）。
 * 运行：
 *   adb shell am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity \
 *     --es path "/sdcard/xiangqi-p4/proj/tools/pikafish-probe.js"
 */
"use strict";

const NNUE = "/sdcard/xiangqi-p4/pikafish.nnue";
const OUT = "/sdcard/xiangqi-p4/pikafish-probe.txt";
const lines = [];

function say(text) {
    lines.push(text);
    log(text);
}

function flush() {
    try {
        files.write(OUT, lines.join("\n") + "\n");
    } catch (error) {
        log("写结果文件失败：" + error);
    }
}

say("=== Pikafish 探针 " + new Date().toISOString() + " ===");
say("权重文件存在：" + files.exists(NNUE) + "（" + NNUE + "）");

const hasModule = typeof $pikafish !== "undefined" && $pikafish != null;
say("宿主有 $pikafish：" + hasModule);
if (!hasModule) {
    say("没有 $pikafish —— 宿主原生层没挂上这条 API");
    flush();
    exit();
}

say("原生库可用（.so 加载）：" + $pikafish.available());

let startedAt = Date.now();
const started = $pikafish.start({ evalFile: NNUE, threads: 2, hashMb: 128 });
say("start 用时 " + (Date.now() - startedAt) + "ms：" + JSON.stringify(started));
if (!started.ok) {
    say("启动失败，结束");
    flush();
    exit();
}

// 1) 开局，1200ms：看深度/结点/主变是否合理
let t0 = Date.now();
let open = $pikafish.analyze("rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1", 1200);
let openMs = Date.now() - t0;
if (open.ok) {
    say("① 开局 1200ms：move=" + open.move + " scoreCp=" + open.scoreCp +
        " mate=" + (open.mate === undefined ? "-" : open.mate) +
        " depth=" + open.depth + " 实际用时 " + openMs + "ms");
    say("   pv: " + open.pv);
} else {
    say("① 开局分析失败：" + JSON.stringify(open));
}

// 2) 真实中局 FEN（JS 引擎在这局面上报过假杀 c6g6 29999；Pikafish 应给出平缓分数）
t0 = Date.now();
let mid = $pikafish.analyze("rnbakabnr/9/1c7/p1C1p1p1p/4c4/9/P1P3P1P/2N4C1/9/R1BAKABNR w - - 0 1", 1500);
let midMs = Date.now() - t0;
if (mid.ok) {
    say("② 中局 1500ms：move=" + mid.move + " scoreCp=" + mid.scoreCp +
        " mate=" + (mid.mate === undefined ? "-" : mid.mate) +
        " depth=" + mid.depth + " 实际用时 " + midMs + "ms");
    say("   pv: " + mid.pv);
} else {
    say("② 中局分析失败：" + JSON.stringify(mid));
}

say("isRunning：" + $pikafish.isRunning());
$pikafish.quit();
say("quit 完成");
say("=== 探针结束 ===");
flush();
