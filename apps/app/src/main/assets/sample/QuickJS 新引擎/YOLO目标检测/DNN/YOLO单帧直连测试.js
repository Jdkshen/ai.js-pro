// @engine quickjs
// ============================================================
// YOLO单帧直连测试.js — 完整输出分阶段耗时 + 10 帧 p50/p95/max
// ============================================================

var backend = 'dnn';
var CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

var modelRoot = 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/';
var detector = yolo.load({
    backend: backend,
    model: modelRoot + 'yolo26_320.onnx',
    labels: modelRoot + 'labels.txt',
    inputSize: 320,
    threads: 4
});

// ---- 统计工具 ----
function percentile(sorted, ratio) {
    var index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
    return sorted[Math.max(0, index)];
}

function summarizeYolo(name, values) {
    if (values.length === 0) {
        console.log('[YOLO] ' + name + ' rounds=0（无成功样本）');
        return;
    }
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var total = 0;
    for (var i = 0; i < values.length; i++) total += values[i];
    console.log('[YOLO] ' + name + ' rounds=' + values.length);
    console.log('[YOLO] p50=' + percentile(sorted, 0.50).toFixed(3) +
        'ms p95=' + percentile(sorted, 0.95).toFixed(3) +
        'ms max=' + sorted[sorted.length - 1].toFixed(3) +
        'ms avg=' + (total / values.length).toFixed(3) + 'ms');
}

// ---- 参数 ----
var ROUNDS = 10;
var WARMUP_ROUNDS = 3;

var frame = null;
try {
    if (!requestScreenCapture('portrait')) {
        throw new Error('用户取消了屏幕捕获授权');
    }

    // 预热
    console.log('[YOLO] 预热 ' + WARMUP_ROUNDS + ' 帧...');
    for (var w = 0; w < WARMUP_ROUNDS; w++) {
        var wf = captureScreen(CAPTURE_OPTIONS);
        try { detector.detect(wf, { confidence: 0.25, nms: 0.45 }); } catch (e) { /* ignore */ }
        wf.recycle();
    }

    var captureMs = [];
    var preprocessArr = [];
    var inferenceArr = [];
    var totalArr = [];
    var errors = 0;

    console.log('[YOLO] 单帧直连测试 x' + ROUNDS + ' mode=fast size=720');
    var loopStarted = performance.now();

    for (var i = 0; i < ROUNDS; i++) {
        var capStart = performance.now();
        frame = captureScreen(CAPTURE_OPTIONS);
        captureMs.push(performance.now() - capStart);

        try {
            var detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            preprocessArr.push(detections.preprocessMs);
            inferenceArr.push(detections.inferenceMs);
            totalArr.push(detections.totalMs);

            if (i === 0) {
                console.log('QUICKJS_NATIVE_YOLO_OK', {
                    engine: __engine__.name,
                    backend: backend,
                    version: yolo.getVersion(backend),
                    frame: frame.width + 'x' + frame.height,
                    nativeFrame: frame.pixelWidth + 'x' + frame.pixelHeight,
                    captureMode: frame.captureMode,
                    detections: detections.length,
                    preprocessMs: detections.preprocessMs,
                    inferenceMs: detections.inferenceMs,
                    totalMs: detections.totalMs
                });
                detections.forEach(function (item) {
                    console.log(item.label, (item.score * 100).toFixed(1) + '%', item.bounds);
                });
            }
        } catch (e) {
            errors++;
            if (errors <= 5) console.log('[ERROR] round=' + i + ' ' + String(e.message || e));
        } finally {
            frame.recycle();
            frame = null;
        }
    }

    var totalElapsed = performance.now() - loopStarted;
    console.log('');
    console.log('[YOLO] 测试完成 总耗时=' + (totalElapsed / 1000).toFixed(1) + 's errors=' + errors);
    summarizeYolo('capture', captureMs);
    summarizeYolo('preprocess', preprocessArr);
    summarizeYolo('inference', inferenceArr);
    summarizeYolo('total', totalArr);

    toastLog(backend + ' YOLO 单帧 ' + ROUNDS + ' 次完成，errors=' + errors);

} finally {
    if (frame) frame.recycle();
    detector.close();
}
