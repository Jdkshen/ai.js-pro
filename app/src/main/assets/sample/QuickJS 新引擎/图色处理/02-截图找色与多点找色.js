// @engine quickjs

if (!requestScreenCapture('portrait')) {
    throw new Error('需要先授权屏幕捕获授权');
}

// ---- 统计工具 ----
function percentile(sorted, ratio) {
    var index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
    return sorted[Math.max(0, index)];
}

function summarize(name, values) {
    if (values.length === 0) {
        console.log('[BENCH] name=' + name + ' rounds=0');
        return;
    }
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var total = 0;
    for (var i = 0; i < values.length; i++) total += values[i];
    console.log('[BENCH] name=' + name + ' rounds=' + values.length);
    console.log('[BENCH] p50=' + percentile(sorted, 0.50).toFixed(3) +
        'ms p95=' + percentile(sorted, 0.95).toFixed(3) +
        'ms max=' + sorted[sorted.length - 1].toFixed(3) +
        'ms avg=' + (total / values.length).toFixed(3) + 'ms');
}

var CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };
var ROUNDS = 100;

// ---- 预热 5 次 ----
console.log('[BENCH] 预热 5 次...');
for (var w = 0; w < 5; w++) {
    var wf = captureScreen(CAPTURE_OPTIONS);
    wf.recycle();
}

var captureMs = [];
var findColorMs = [];
var findMultiColorsMs = [];
var errors = 0;

console.log('[BENCH] 截图找色与多点找色 x' + ROUNDS + ' mode=fast size=720');

for (var i = 0; i < ROUNDS; i++) {
    var capStart = performance.now();
    var frame = captureScreen(CAPTURE_OPTIONS);
    captureMs.push(performance.now() - capStart);

    try {
        var fcStart = performance.now();
        var green = images.findColor(frame, '#00C853', {
            region: [0, 0, frame.width, Math.floor(frame.height / 2)],
            threshold: 24
        });
        findColorMs.push(performance.now() - fcStart);

        var mcStart = performance.now();
        var button = images.findMultiColors(frame, '#2196F3', [
            [20, 0, '#2196F3'],
            [0, 20, '#2196F3'],
            [20, 20, '#2196F3']
        ], {
            region: [0, 0, frame.width, frame.height],
            threshold: 20
        });
        findMultiColorsMs.push(performance.now() - mcStart);

        if (i === 0) {
            console.log('[BENCH] logical=' + frame.width + 'x' + frame.height +
                ' native=' + frame.pixelWidth + 'x' + frame.pixelHeight);
            console.log('单点找色结果: ' + JSON.stringify(green));
            console.log('多点找色结果: ' + JSON.stringify(button));
        }
    } catch (e) {
        errors++;
        if (errors <= 5) console.log('[ERROR] round=' + i + ' ' + String(e.message || e));
    } finally {
        frame.recycle();
    }
}

console.log('');
console.log('[BENCH] errors=' + errors);
summarize('capture', captureMs);
summarize('findColor', findColorMs);
summarize('findMultiColors', findMultiColorsMs);
toastLog('截图找色 ' + ROUNDS + ' 轮完成，errors=' + errors);
