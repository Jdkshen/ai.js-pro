// @engine quickjs
// 图色模板长时间稳定性测试
// 用途：长时间循环截图 + 找色 + 模板匹配 + 回收，观察内存与耗时是否稳定
// 前置：截图授权
// 覆盖：images / colors

if (!requestScreenCapture('portrait')) {
    throw new Error('用户取消了屏幕捕获授权');
}

// ---- 统计工具 ----
function percentile(sorted, ratio) {
    var index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
    return sorted[Math.max(0, index)];
}

function summarize(name, values) {
    var result = { name: name, count: values.length, p50: 0, p95: 0, max: 0, avg: 0 };
    if (values.length === 0) {
        console.log('[BENCH] name=' + name + ' rounds=0');
        return result;
    }
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var total = 0;
    for (var i = 0; i < values.length; i++) total += values[i];
    result.p50 = percentile(sorted, 0.50);
    result.p95 = percentile(sorted, 0.95);
    result.max = sorted[sorted.length - 1];
    result.avg = total / values.length;
    console.log('[BENCH] name=' + name + ' rounds=' + values.length);
    console.log('[BENCH] p50=' + result.p50.toFixed(3) + 'ms' +
        ' p95=' + result.p95.toFixed(3) + 'ms' +
        ' max=' + result.max.toFixed(3) + 'ms' +
        ' avg=' + result.avg.toFixed(3) + 'ms');
    return result;
}

function readFrameStats(stage) {
    if (typeof __aiNativeFrameStats !== 'function') return null;
    var stats = __aiNativeFrameStats();
    console.log('[POOL] stage=' + stage +
        ' activeHandles=' + stats.activeHandles +
        ' activeFrames=' + stats.activeFrames +
        ' poolCount=' + stats.poolCount +
        ' poolBytes=' + stats.poolBytes +
        ' createCount=' + stats.createCount +
        ' reuseCount=' + stats.reuseCount +
        ' rejectCount=' + stats.rejectCount);
    return stats;
}

// ---- 参数 ----
var ROUNDS = 1000;
var WARMUP_ROUNDS = 10;
var CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };

// ---- 结果收集 ----
var captureMs = [];
var findColorMs = [];
var findMultiColorsMs = [];
var findImageMs = [];
var matchTemplateMs = [];
var errors = 0;
var baselineStats = null;
var finalStats = null;
var peakActiveFrames = 0;
var peakPoolBytes = 0;

console.log('========================================');
console.log('[BENCH] 图色模板长时间稳定性测试');
console.log('[BENCH] ROUNDS=' + ROUNDS + ' mode=fast size=720');
console.log('========================================');

var template = null;
var screen = null;

try {
    // ---- 预热 ----
    console.log('[BENCH] 预热 ' + WARMUP_ROUNDS + ' 轮...');
    for (var w = 0; w < WARMUP_ROUNDS; w++) {
        var wf = captureScreen(CAPTURE_OPTIONS);
        try {
            images.findColor(wf, '#01FE02', { threshold: 0 });
        } catch (e) { /* ignore warmup errors */ }
        wf.recycle();
    }
    baselineStats = readFrameStats('after-warmup');
    if (baselineStats) {
        peakActiveFrames = baselineStats.activeFrames;
        peakPoolBytes = baselineStats.poolBytes;
    }

    // ---- 采集一张模板用于 findImage / matchTemplate ----
    screen = captureScreen(CAPTURE_OPTIONS);
    var tw = Math.min(64, screen.width);
    var th = Math.min(64, screen.height);
    var tx = Math.floor((screen.width - tw) / 2);
    var ty = Math.floor((screen.height - th) / 2);
    template = images.clip(screen, tx, ty, tw, th);
    screen.recycle();
    screen = null;

    var TEMPLATE_REGION = [tx, ty, tw + 40, th + 40];
    var dimensions = '';

    // ---- 主循环 ----
    console.log('[BENCH] 开始 ' + ROUNDS + ' 轮测试...');
    var loopStarted = performance.now();

    for (var i = 0; i < ROUNDS; i++) {
        // 1) 截图
        var capStart = performance.now();
        var frame = captureScreen(CAPTURE_OPTIONS);
        captureMs.push(performance.now() - capStart);

        if (i === 0) {
            dimensions = frame.width + 'x' + frame.height +
                ' native=' + frame.pixelWidth + 'x' + frame.pixelHeight;
        }

        try {
            // 2) 单点找色（全屏扫描最坏情况）
            var fcStart = performance.now();
            images.findColor(frame, '#01FE02', {
                region: [0, 0, frame.width, frame.height],
                threshold: 0
            });
            findColorMs.push(performance.now() - fcStart);

            // 3) 多点找色
            var mcStart = performance.now();
            images.findMultiColors(frame, '#01FE02', [
                [10, 0, '#01FE02'],
                [0, 10, '#01FE02']
            ], {
                region: [0, 0, frame.width, frame.height],
                threshold: 0
            });
            findMultiColorsMs.push(performance.now() - mcStart);

            // 4) 找图
            var fiStart = performance.now();
            images.findImage(frame, template, {
                region: TEMPLATE_REGION,
                threshold: 0.8
            });
            findImageMs.push(performance.now() - fiStart);

            // 5) 模板匹配
            var mtStart = performance.now();
            images.matchTemplate(frame, template, {
                region: TEMPLATE_REGION,
                threshold: 0.8,
                max: 5
            });
            matchTemplateMs.push(performance.now() - mtStart);

        } catch (e) {
            errors++;
            if (errors <= 10) {
                console.log('[ERROR] round=' + i + ' ' + String(e.message || e));
            }
        } finally {
            frame.recycle();
        }

        // 每 200 轮输出一次进度
        if ((i + 1) % 200 === 0) {
            var elapsed = performance.now() - loopStarted;
            console.log('[BENCH] 进度: ' + (i + 1) + '/' + ROUNDS +
                ' elapsed=' + (elapsed / 1000).toFixed(1) + 's' +
                ' errors=' + errors);
            var periodicStats = readFrameStats('round-' + (i + 1));
            if (periodicStats) {
                peakActiveFrames = Math.max(peakActiveFrames, periodicStats.activeFrames);
                peakPoolBytes = Math.max(peakPoolBytes, periodicStats.poolBytes);
            }
        }
    }

    var totalElapsed = performance.now() - loopStarted;

    // The final lifecycle sample must be taken after every frame owned by this test is released.
    template.recycle();
    template = null;
    finalStats = readFrameStats('after-release');

    // ---- 输出统计 ----
    console.log('');
    console.log('========================================');
    console.log('[BENCH] 测试完成');
    console.log('[BENCH] logical=' + dimensions);
    console.log('[BENCH] 总耗时: ' + (totalElapsed / 1000).toFixed(1) + 's');
    console.log('[BENCH] errors=' + errors + ' rounds=' + ROUNDS);
    console.log('========================================');

    var sCapture = summarize('capture', captureMs);
    var sFindColor = summarize('findColor', findColorMs);
    var sFindMultiColors = summarize('findMultiColors', findMultiColorsMs);
    var sFindImage = summarize('findImage', findImageMs);
    var sMatchTemplate = summarize('matchTemplate', matchTemplateMs);

    // ---- 稳定性摘要 ----
    console.log('');
    console.log('[STABILITY] 图色模板1000轮完成 errors=' + errors);
    console.log('[STABILITY] capture p50=' + sCapture.p50.toFixed(3) +
        'ms p95=' + sCapture.p95.toFixed(3) + 'ms max=' + sCapture.max.toFixed(3) + 'ms');
    console.log('[STABILITY] findColor p50=' + sFindColor.p50.toFixed(3) +
        'ms p95=' + sFindColor.p95.toFixed(3) + 'ms max=' + sFindColor.max.toFixed(3) + 'ms');
    console.log('[STABILITY] findMultiColors p50=' + sFindMultiColors.p50.toFixed(3) +
        'ms p95=' + sFindMultiColors.p95.toFixed(3) + 'ms max=' + sFindMultiColors.max.toFixed(3) + 'ms');
    console.log('[STABILITY] findImage p50=' + sFindImage.p50.toFixed(3) +
        'ms p95=' + sFindImage.p95.toFixed(3) + 'ms max=' + sFindImage.max.toFixed(3) + 'ms');
    console.log('[STABILITY] matchTemplate p50=' + sMatchTemplate.p50.toFixed(3) +
        'ms p95=' + sMatchTemplate.p95.toFixed(3) + 'ms max=' + sMatchTemplate.max.toFixed(3) + 'ms');

    if (baselineStats && finalStats) {
        console.log('[STABILITY] activeFrames ' + baselineStats.activeFrames + ' -> ' +
            finalStats.activeFrames + ' peak=' + peakActiveFrames +
            ' poolBytes ' + baselineStats.poolBytes + ' -> ' + finalStats.poolBytes +
            ' peak=' + peakPoolBytes);
        if (finalStats.activeFrames !== 0 || finalStats.activeHandles !== 0) {
            throw new Error('Native Frame 生命周期异常：测试结束后仍有活动句柄');
        }
        if (finalStats.poolCount > 4 || finalStats.poolBytes > 50 * 1024 * 1024) {
            throw new Error('Native Frame 复用池超过设计上限');
        }
    }

    toastLog('图色模板 1000 轮完成: errors=' + errors);

} catch (e) {
    console.log('[FATAL] ' + String(e.message || e));
    throw e;
} finally {
    if (template) template.recycle();
    if (screen) screen.recycle();
}
