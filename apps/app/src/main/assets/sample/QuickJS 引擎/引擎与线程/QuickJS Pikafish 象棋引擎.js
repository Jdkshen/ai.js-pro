"quickjs";
// Pikafish 象棋引擎
// 用途：$pikafish 原生 NNUE 引擎：start（权重自动查找）/ analyze（按 FEN）/ analyzePosition（按着法历史，带 multiPv 多路候选）/ stop
// 前置：arm64 设备（原生库随包）；权重 pikafish.nnue 自动查找；analyze 的 FEN 必须完整（带「 w - - 0 1」，裸 FEN 会静默打死进程）
// 覆盖：pikafish / files

var START_FEN = 'rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1';
var HISTORY_COMMAND = 'position fen ' + START_FEN + ' moves h2e2 h9g7';
// ⚠️ FEN 必须完整（含走棋方/回合字段，即“ w - - 0 1”）；analyze 是阻塞调用（时长 ≈ movetime）：
//    只给棋盘段（"rnbakabnr/…/RNBAKABNR"）会让原生引擎直接带走整个进程，
//    而且无墓碑、无 ANR、无 FATAL（2026-09-25 三次实测）。别把后缀删掉！

console.log('=== Pikafish 象棋引擎 ===');

if (typeof $pikafish === 'undefined' || $pikafish === null || typeof $pikafish.analyze !== 'function') {
    throw new Error('宿主没有 $pikafish（原生库目前只随包 arm64-v8a）');
}
console.log('available = ' + $pikafish.available() + '，isRunning = ' + $pikafish.isRunning());

/** 权重按候选表找；找不到就传 null，交给宿主默认目录（filesDir / externalFilesDir）。 */
var EVAL_CANDIDATES = ['/sdcard/xiangqi-p4/pikafish.nnue'];
var evalFile = null;
for (var i = 0; i < EVAL_CANDIDATES.length; i++) {
    try {
        if (files.exists(EVAL_CANDIDATES[i])) {
            evalFile = EVAL_CANDIDATES[i];
            break;
        }
    } catch (error) { /* 换下一个 */ }
}
console.log('权重：' + (evalFile || '（候选位置没有，交给宿主默认目录）'));

var started = $pikafish.start({ evalFile: evalFile, threads: 2, hashMb: 128 });
console.log('start = ' + JSON.stringify(started));
if (!started || started.ok !== true) {
    throw new Error('引擎启动失败：' + ((started && started.error) || '未知'));
}

/** 结果里 raw 是引擎输出原文（很长），这里只打关键字段。 */
function summarize(result) {
    if (!result || result.ok !== true) {
        return JSON.stringify(result || { ok: false });
    }
    var parts = ['move ' + result.move];
    if (result.scoreCp != null) parts.push('scoreCp ' + result.scoreCp);
    if (result.mate != null) parts.push('mate ' + result.mate);
    if (result.depth != null) parts.push('depth ' + result.depth);
    if (result.nodes != null) parts.push('nodes ' + result.nodes);
    if (result.timeMs != null) parts.push('timeMs ' + result.timeMs);
    if (result.pv) parts.push('pv ' + result.pv);
    if (result.candidates) parts.push('candidates ' + result.candidates.length);
    return parts.join('｜');
}

// 1) 按 FEN 分析（1 秒预算）：标准开局
var t0 = performance.now();
var r1 = $pikafish.analyze(START_FEN, 1000);
console.log('analyze(FEN, 1000ms，实测 ' + (performance.now() - t0).toFixed(0) + 'ms)：' + summarize(r1));

// 2) 多路候选（multiPv = 3）：前 3 路着法 + 分数；脚本侧可以避开禁手/重复着再挑
var r2 = $pikafish.analyze(START_FEN, 800, 0, 3);
console.log('analyze(multiPv=3)：' + summarize(r2));
if (r2 && r2.candidates) {
    for (var c = 0; c < r2.candidates.length; c++) {
        var cand = r2.candidates[c];
        console.log('  候选 ' + cand.multipv + '：' + cand.move +
            '（scoreCp ' + cand.scoreCp + '，depth ' + cand.depth + '）');
    }
}

// 3) 按「着法历史」分析（analyzePosition）：引擎能看到完整重复局面，长将/长捉判罚才算得出来
//    入参只认这种格式：position fen <FEN> w - - 0 1 [moves a0a1 ...]（1~512 步）
var r3 = $pikafish.analyzePosition(HISTORY_COMMAND, 1000);
console.log('analyzePosition(带 2 步历史)：' + summarize(r3));

$pikafish.stop();
console.log('stop 后 isRunning = ' + $pikafish.isRunning());
$pikafish.quit();
console.log('=== PIKAFISH_DONE ===');
