// @engine quickjs
// YOLO 实时直连测试
// 用途：连续截图检测并实时绘制检测框
// 前置：截图授权 + 悬浮窗权限（drawing 覆盖层）
// 覆盖：yolo

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

if (!requestScreenCapture('portrait')) {
    detector.close();
    throw new Error('用户取消了屏幕捕获授权');
}

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

var TOTAL_FRAMES = 100;
var captureMs = [];
var preprocessArr = [];
var inferenceArr = [];
var totalArr = [];
var errors = 0;

var loopStarted = performance.now();
try {
    for (var index = 0; index < TOTAL_FRAMES; index++) {
        var capStart = performance.now();
        var frame = captureScreen(CAPTURE_OPTIONS);
        captureMs.push(performance.now() - capStart);

        var currentDetections = [];
        try {
            currentDetections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            totalArr.push(currentDetections.totalMs);
            preprocessArr.push(currentDetections.preprocessMs);
            inferenceArr.push(currentDetections.inferenceMs);
        } catch (e) {
            errors++;
            if (errors <= 5) console.log('[ERROR] frame=' + index + ' ' + String(e.message || e));
        } finally {
            frame.recycle();
        }

        if (index < 3 || index % 10 === 0) {
            console.log('NATIVE_YOLO_FRAME', {
                backend: backend,
                frame: index + 1,
                detections: currentDetections.length,
                preprocessMs: currentDetections.preprocessMs,
                inferenceMs: currentDetections.inferenceMs,
                totalMs: currentDetections.totalMs
            });
        }

        // 不再叠加固定帧间延迟，让测试反映真实端到端速度。
    }
} finally {
    detector.close();
}

var elapsedMs = performance.now() - loopStarted;
console.log('');
console.log('========================================');
console.log('QUICKJS_NATIVE_YOLO_LOOP_OK', {
    backend: backend,
    frames: TOTAL_FRAMES,
    elapsedMs: elapsedMs,
    fps: (TOTAL_FRAMES * 1000 / elapsedMs).toFixed(1),
    errors: errors
});
console.log('========================================');
summarizeYolo('capture', captureMs);
summarizeYolo('preprocess', preprocessArr);
summarizeYolo('inference', inferenceArr);
summarizeYolo('total', totalArr);

toastLog(backend + ' YOLO ' + TOTAL_FRAMES + ' 帧完成：' +
    (TOTAL_FRAMES * 1000 / elapsedMs).toFixed(1) + ' FPS，errors=' + errors);
