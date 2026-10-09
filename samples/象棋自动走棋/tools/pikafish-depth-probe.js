// @engine quickjs
/**
 * Pikafish 深度探针：测「最多能算到第几步」——同一批局面在不同 movetime / 线程数下的
 * depth / seldepth / nodes / nps 实测值（K40 真机）。
 *
 * 为什么单独写一个：面板的滑条上限只有 10s，而「技术上能到多深」要看 10s~30s 的曲线；
 * 直接量出 depth 数字比猜「大概 20 多层」有说服力（用户 2026-09-24：「看看最多可以算到第几步」）。
 *
 * ⚠️ $pikafish 是**进程级单例**：跑这个探针前必须停掉面板（否则两边抢同一个引擎线程）。
 *    adb shell am force-stop com.jdkshen.aijspro
 *    adb shell am start -n com.jdkshen.aijspro/.external.open.RunIntentActivity \
 *      --es path "/sdcard/xiangqi-p4/proj/tools/pikafish-depth-probe.js"
 *    结果写 /sdcard/xiangqi-p4/pikafish-depth-probe.txt（跑完 adb pull 取）。
 */
"use strict";

const NNUE = "/sdcard/xiangqi-p4/pikafish.nnue";   // 旧位置（2026-09-24 已挪到应用私有目录）
const NNUE_PRIVATE = "/data/user/0/com.jdkshen.aijspro/files/pikafish.nnue";   // 现在的位置（宿主 filesDir）
const OUT = "/sdcard/xiangqi-p4/pikafish-depth-probe.txt";
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

/** 局面：开局 / 游戏里那种中局（之前假杀事故的现场）/ 简单残局（看深度上限）。 */
const OPENING = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
const MIDGAME = "rnbakabnr/9/1c7/p1C1p1p1p/4c4/9/P1P3P1P/2N4C1/9/R1BAKABNR w - - 0 1";
const ENDGAME = "3k5/9/9/9/9/9/9/9/9/2R1K4 w - - 0 1";

say("=== Pikafish 深度探针 " + new Date().toISOString() + " ===");
say("权重（旧位置）：" + NNUE + "（存在：" + files.exists(NNUE) + "）");
say("权重（宿主默认）：" + NNUE_PRIVATE + "（存在：" + files.exists(NNUE_PRIVATE) + "）");

if (typeof $pikafish === "undefined" || $pikafish == null || !$pikafish.available()) {
    say("$pikafish 不可用，结束");
    flush();
    exit();
}

function runCase(label, fen, movetime, threads) {
    const t0 = Date.now();
    const result = $pikafish.analyze(fen, movetime, movetime + 15000, 1);
    const wallMs = Date.now() - t0;
    if (!result || !result.ok) {
        say("✗ " + label + "：" + (result && result.error ? result.error : "无结果"));
        return;
    }
    const nodes = result.nodes == null ? null : result.nodes;
    const timeMs = result.timeMs == null ? wallMs : result.timeMs;
    const nps = nodes != null && timeMs > 0 ? Math.round(nodes / timeMs * 1000) : null;
    let score = "?";
    if (result.mate != null) {
        score = "杀 " + result.mate;
    } else if (result.scoreCp != null) {
        score = (result.scoreCp / 100).toFixed(2) + " 兵";
    }
    say("✓ " + label + "（movetime=" + movetime + "ms，线程 " + threads + "）" +
        " depth=" + result.depth + " seldepth=" + (result.seldepth == null ? "-" : result.seldepth) +
        " nodes=" + nodes + " 用时=" + timeMs + "ms nps=" + nps +
        " 分=" + score + " 着=" + result.move);
    const pvText = Array.isArray(result.pv) ? result.pv.join(" ")
        : String(result.pv == null ? "" : result.pv);
    say("   主变：" + (pvText.length > 160 ? pvText.substring(0, 160) + "…" : pvText));
}

function startEngine(threads, hashMb) {
    $pikafish.quit();   // 幂等；保证 threads 变更一定生效
    // ⚠️ 不显式传 evalFile：宿主会按默认候选找（现在是 filesDir 下的 pikafish.nnue，2026-09-24 挪的）
    const started = $pikafish.start({ threads: threads, hashMb: hashMb });
    say("start 线程 " + threads + " / hash " + hashMb + "MB：" + JSON.stringify(started));
    return !!(started && started.ok);
}

// ── 阶段 1：默认参数（面板默认线程 2 / hash 128），扫描算棋时间
if (!startEngine(2, 128)) {
    say("引擎启动失败，结束");
    flush();
    exit();
}

runCase("开局", OPENING, 1000, 2);
runCase("开局", OPENING, 10000, 2);
runCase("中局", MIDGAME, 1000, 2);
runCase("中局", MIDGAME, 3000, 2);
runCase("中局", MIDGAME, 10000, 2);
runCase("中局", MIDGAME, 30000, 2);
runCase("残局", ENDGAME, 10000, 2);

// ── 阶段 2：线程拉满（面板滑条上限 8），看同样 10s 能多算几层
if (startEngine(8, 256)) {
    runCase("中局", MIDGAME, 10000, 8);
    runCase("开局", OPENING, 10000, 8);
}

$pikafish.quit();
say("=== 探针结束 ===");
flush();
