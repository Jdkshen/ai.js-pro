// @engine quickjs
// YOLO 持续识别
// 用途：后台持续识别，任务列表可停止；sleep 分片保证可中断
// 前置：截图授权 + 悬浮窗权限
// 覆盖：yolo

var backend = 'dnn';
// 直接读取 ImageReader 最新缓存帧，避免静止画面每轮阻塞 50ms。
var CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

var modelRoot = 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/';
var detector = yolo.load({
    backend: backend,
    model: modelRoot + 'yolo26_640.onnx',
    labels: modelRoot + 'labels.txt',
    inputSize: 640,
    threads: 4
});

var drawingShown = drawing.show();
console.log('DRAWING_SHOW', drawingShown);
if (!drawingShown) {
    toast('未获得悬浮窗权限，无法绘制检测框，请在系统设置里允许悬浮窗');
}

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
    var result = { p50: 0, p95: 0, max: 0, avg: 0 };
    if (values.length === 0) return result;
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var total = 0;
    for (var i = 0; i < values.length; i++) total += values[i];
    result.p50 = percentile(sorted, 0.50);
    result.p95 = percentile(sorted, 0.95);
    result.max = sorted[sorted.length - 1];
    result.avg = total / values.length;
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
var TOTAL_FRAMES = 1000;
var WARMUP_FRAMES = 5;

// ---- 结果收集 ----
var captureMs = [];
var preprocessMsArr = [];
var inferenceMsArr = [];
var totalMsArr = [];
var errors = 0;
var totalHits = 0;
var successfulFrames = 0;
var baselineStats = null;
var finalStats = null;
var peakActiveFrames = 0;
var peakPoolBytes = 0;

console.log('========================================');
console.log('[YOLO] YOLO持续识别长稳测试');
console.log('[YOLO] TOTAL_FRAMES=' + TOTAL_FRAMES + ' backend=' + backend);
console.log('========================================');

var frameIndex = 0;
var dimensions = '';
try {
    // ---- 预热 ----
    console.log('[YOLO] 预热 ' + WARMUP_FRAMES + ' 帧...');
    for (var w = 0; w < WARMUP_FRAMES; w++) {
        var wf = captureScreen(CAPTURE_OPTIONS);
        try {
            detector.detect(wf, { confidence: 0.25, nms: 0.45 });
        } catch (e) { /* ignore warmup errors */ }
        wf.recycle();
    }
    baselineStats = readFrameStats('after-warmup');
    if (baselineStats) {
        peakActiveFrames = baselineStats.activeFrames;
        peakPoolBytes = baselineStats.poolBytes;
    }

    // ---- 主循环 ----
    console.log('[YOLO] 开始 ' + TOTAL_FRAMES + ' 帧检测...');
    var loopStarted = performance.now();
    var summaryStarted = performance.now();
    var summaryFrames = 0;
    var summaryHits = 0;
    var summaryModelMs = 0;

    for (var i = 0; i < TOTAL_FRAMES; i++) {
        var capStart = performance.now();
        var frame = captureScreen(CAPTURE_OPTIONS);
        captureMs.push(performance.now() - capStart);

        if (i === 0) {
            dimensions = frame.width + 'x' + frame.height +
                ' native=' + frame.pixelWidth + 'x' + frame.pixelHeight;
        }

        var detections = null;
        try {
            detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            totalMsArr.push(detections.totalMs);
            preprocessMsArr.push(detections.preprocessMs);
            inferenceMsArr.push(detections.inferenceMs);
        } catch (e) {
            errors++;
            if (errors <= 10) {
                console.log('[ERROR] frame=' + i + ' ' + String(e.message || e));
            }
        } finally {
            frame.recycle();
        }

        frameIndex++;
        if (detections !== null) {
            successfulFrames++;
            summaryFrames++;
            summaryModelMs += detections.totalMs;
            if (detections.length > 0) {
                totalHits += detections.length;
                summaryHits += detections.length;
            }
        }

        if (drawingShown) {
            drawing.update(detections || [], backend + '  ' + frameIndex + '/' + TOTAL_FRAMES +
                '  ' + (summaryModelMs / Math.max(1, summaryFrames)).toFixed(0) + ' ms' +
                '  命中 ' + totalHits);
        }

        // 每 ~3 秒或最后一帧输出一次汇总
        var now = performance.now();
        if (now - summaryStarted >= 3000 || (i + 1) === TOTAL_FRAMES) {
            var elapsed = now - summaryStarted;
            var avgModel = summaryFrames > 0 ? summaryModelMs / summaryFrames : 0;
            console.log('[YOLO] 帧=' + (i + 1) + '/' + TOTAL_FRAMES +
                ' sec=' + (elapsed / 1000).toFixed(1) +
                ' fps=' + Math.round(summaryFrames * 1000 / Math.max(1, elapsed)) +
                ' avg=' + avgModel.toFixed(1) + 'ms' +
                ' hits=' + summaryHits + ' totalHits=' + totalHits +
                ' errors=' + errors);
            summaryStarted = now;
            summaryFrames = 0;
            summaryHits = 0;
            summaryModelMs = 0;
        }

        if ((i + 1) % 200 === 0) {
            var periodicStats = readFrameStats('frame-' + (i + 1));
            if (periodicStats) {
                peakActiveFrames = Math.max(peakActiveFrames, periodicStats.activeFrames);
                peakPoolBytes = Math.max(peakPoolBytes, periodicStats.poolBytes);
            }
        }

        // DNN 推理本身已超过一帧，不再人为增加 16ms 延迟。
    }

    var totalElapsed = performance.now() - loopStarted;
    finalStats = readFrameStats('after-release');

    // ---- 输出统计 ----
    console.log('');
    console.log('========================================');
    console.log('[YOLO] 测试完成');
    console.log('[YOLO] logical=' + dimensions);
    console.log('[YOLO] 总耗时: ' + (totalElapsed / 1000).toFixed(1) + 's');
    console.log('[YOLO] frames=' + TOTAL_FRAMES + ' successful=' + successfulFrames +
        ' errors=' + errors +
        ' totalHits=' + totalHits);
    console.log('[YOLO] fps=' + (TOTAL_FRAMES * 1000 / totalElapsed).toFixed(1));
    console.log('========================================');

    var sCapture = summarizeYolo('capture', captureMs);
    var sPreprocess = summarizeYolo('preprocess', preprocessMsArr);
    var sInference = summarizeYolo('inference', inferenceMsArr);
    var sTotal = summarizeYolo('total', totalMsArr);

    console.log('[YOLO] capture   p50=' + sCapture.p50.toFixed(3) +
        'ms p95=' + sCapture.p95.toFixed(3) + 'ms max=' + sCapture.max.toFixed(3) + 'ms');
    console.log('[YOLO] preprocess p50=' + sPreprocess.p50.toFixed(3) +
        'ms p95=' + sPreprocess.p95.toFixed(3) + 'ms max=' + sPreprocess.max.toFixed(3) + 'ms');
    console.log('[YOLO] inference  p50=' + sInference.p50.toFixed(3) +
        'ms p95=' + sInference.p95.toFixed(3) + 'ms max=' + sInference.max.toFixed(3) + 'ms');
    console.log('[YOLO] total      p50=' + sTotal.p50.toFixed(3) +
        'ms p95=' + sTotal.p95.toFixed(3) + 'ms max=' + sTotal.max.toFixed(3) + 'ms');

    console.log('');
    console.log('[STABILITY] YOLO ' + TOTAL_FRAMES + '帧完成 errors=' + errors +
        ' totalHits=' + totalHits);

    if (baselineStats && finalStats) {
        console.log('[STABILITY] activeFrames ' + baselineStats.activeFrames + ' -> ' +
            finalStats.activeFrames + ' peak=' + peakActiveFrames +
            ' poolBytes ' + baselineStats.poolBytes + ' -> ' + finalStats.poolBytes +
            ' peak=' + peakPoolBytes);
        if (finalStats.activeFrames !== 0 || finalStats.activeHandles !== 0) {
            throw new Error('Native Frame 生命周期异常：YOLO 结束后仍有活动句柄');
        }
        if (finalStats.poolCount > 4 || finalStats.poolBytes > 50 * 1024 * 1024) {
            throw new Error('Native Frame 复用池超过设计上限');
        }
    }

    toastLog(backend + ' YOLO ' + TOTAL_FRAMES + ' 帧完成：' +
        (TOTAL_FRAMES * 1000 / totalElapsed).toFixed(1) + ' FPS，errors=' + errors);

} catch (error) {
    if (!String(error && error.message ? error.message : error).match(/interrupt/i)) {
        throw error;
    }
} finally {
    if (drawingShown) drawing.hide();
    detector.close();
}
