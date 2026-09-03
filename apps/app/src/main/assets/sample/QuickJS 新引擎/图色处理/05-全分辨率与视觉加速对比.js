// @engine quickjs

if (!requestScreenCapture('portrait')) {
    throw new Error('需要先授权屏幕捕获');
}

// ---- 统计工具 ----
function percentile(sorted, ratio) {
    var index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
    return sorted[Math.max(0, index)];
}

function summarizeBenchmark(name, captureValues, findValues, dimensions) {
    console.log('[' + name + '] 尺寸: ' + dimensions);
    if (captureValues.length > 0) {
        var cSorted = captureValues.slice().sort(function (a, b) { return a - b; });
        var cTotal = 0;
        for (var i = 0; i < captureValues.length; i++) cTotal += captureValues[i];
        console.log('[BENCH] name=' + name + '_capture rounds=' + captureValues.length);
        console.log('[BENCH] p50=' + percentile(cSorted, 0.50).toFixed(3) +
            'ms p95=' + percentile(cSorted, 0.95).toFixed(3) +
            'ms max=' + cSorted[cSorted.length - 1].toFixed(3) +
            'ms avg=' + (cTotal / captureValues.length).toFixed(3) + 'ms');
    }
    if (findValues.length > 0) {
        var fSorted = findValues.slice().sort(function (a, b) { return a - b; });
        var fTotal = 0;
        for (var j = 0; j < findValues.length; j++) fTotal += findValues[j];
        console.log('[BENCH] name=' + name + '_findColor rounds=' + findValues.length);
        console.log('[BENCH] p50=' + percentile(fSorted, 0.50).toFixed(3) +
            'ms p95=' + percentile(fSorted, 0.95).toFixed(3) +
            'ms max=' + fSorted[fSorted.length - 1].toFixed(3) +
            'ms avg=' + (fTotal / findValues.length).toFixed(3) + 'ms');
    }
}

function benchmark(name, captureOptions, rounds) {
    var captureValues = [];
    var findValues = [];
    var dimensions = '';

    // 先预热一次
    var warmup = captureScreen(captureOptions);
    warmup.recycle();

    for (var i = 0; i < rounds; i++) {
        var captureStartedAt = performance.now();
        var frame = captureScreen(captureOptions);
        captureValues.push(performance.now() - captureStartedAt);
        try {
            if (!dimensions) {
                dimensions = frame.width + 'x' + frame.height +
                    '（Native ' + frame.pixelWidth + 'x' + frame.pixelHeight + '）';
            }
            var findStartedAt = performance.now();
            images.findColor(frame, '#01FE02', { threshold: 0 });
            findValues.push(performance.now() - findStartedAt);
        } finally {
            frame.recycle();
        }
    }

    summarizeBenchmark(name, captureValues, findValues, dimensions);
}

var rounds = 20;
// 预热 3 次让缓存和复用池稳定
console.log('[BENCH] 预热 3 次...');
for (var w = 0; w < 3; w++) {
    var wf = captureScreen({ mode: 'full', fresh: false });
    wf.recycle();
}

console.log('========== 全分辨率与视觉加速对比 ==========');
benchmark('全分辨率', { mode: 'full', fresh: false }, rounds);
benchmark('视觉加速 720p', { mode: 'fast', size: 720, fresh: false }, rounds);
benchmark('视觉加速 640p', { mode: 'fast', size: 640, fresh: false }, rounds);

// 快速帧坐标映射验证
console.log('');
console.log('========== 坐标映射验证 ==========');
var fastFrame = captureScreen({ mode: 'fast', size: 720, fresh: false });
try {
    var centerX = Math.floor(fastFrame.width / 2);
    var centerY = Math.floor(fastFrame.height / 2);
    var centerColor = images.pixel(fastFrame, centerX, centerY);
    var mappedPoint = images.findColor(fastFrame, centerColor, {
        region: [centerX, centerY, 1, 1],
        threshold: 0
    });
    console.log('[BENCH] 原屏中心: ' + centerX + ',' + centerY +
        '，找色返回: ' + JSON.stringify(mappedPoint));
    console.log('[BENCH] logical=' + fastFrame.width + 'x' + fastFrame.height +
        ' native=' + fastFrame.pixelWidth + 'x' + fastFrame.pixelHeight);
} finally {
    fastFrame.recycle();
}

console.log('模式对比完成');
