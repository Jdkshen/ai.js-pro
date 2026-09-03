// @engine quickjs
// ============================================================
// YOLO区域检测.js — 区域 vs 全屏性能对比 + p50/p95/max 统计
// 演示 region 参数 + 每 5 秒输出"全屏 vs 区域"性能/命中对比。
// 停止方式：应用任务列表停止该脚本（sleep 分片内及时响应）。
// ============================================================

var backend = 'opencv';
var CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

var modelRoot = 'asset://sample/YOLO目标检测/OpenCV 5.0 DNN版本/models/';
var detector = yolo.load({
    backend: backend,
    model: modelRoot + 'yolo26_320.onnx',
    labels: modelRoot + 'labels.txt',
    inputSize: 320,
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

// ---- 参数 ----
var REGION = [100, 100, 700, 700];
var TOTAL_FRAMES = 1000;

// ---- 结果收集 ----
var fullTotalMs = [];
var regionTotalMs = [];
var errors = 0;

var frameIndex = 0;
try {
    // 预热 3 帧
    console.log('[YOLO] 预热 3 帧...');
    for (var w = 0; w < 3; w++) {
        var wf = captureScreen(CAPTURE_OPTIONS);
        try {
            detector.detect(wf, { confidence: 0.25, nms: 0.45 });
        } catch (e) { /* ignore */ }
        wf.recycle();
    }

    console.log('[YOLO] 区域检测 x' + TOTAL_FRAMES + ' region=' + REGION.join(','));
    var loopStarted = performance.now();
    var summaryStarted = performance.now();
    var summaries = { full: { frames: 0, ms: 0, hits: 0 }, region: { frames: 0, ms: 0, hits: 0 } };

    for (var i = 0; i < TOTAL_FRAMES; i++) {
        var frame = captureScreen(CAPTURE_OPTIONS);
        var fullDetections = null;
        var regionDetections = null;
        try {
            fullDetections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            fullTotalMs.push(fullDetections.totalMs);
        } catch (e) {
            errors++;
            if (errors <= 10) console.log('[ERROR] frame=' + i + ' stage=full ' +
                String(e.message || e));
        }
        try {
            regionDetections = detector.detect(frame, {
                confidence: 0.25, nms: 0.45,
                region: REGION
            });
            regionTotalMs.push(regionDetections.totalMs);
        } catch (e) {
            errors++;
            if (errors <= 10) console.log('[ERROR] frame=' + i + ' stage=region ' +
                String(e.message || e));
        } finally {
            frame.recycle();
        }

        frameIndex++;
        if (fullDetections !== null) {
            summaries.full.frames++;
            summaries.full.ms += fullDetections.totalMs;
            summaries.full.hits += fullDetections.length;
        }
        if (regionDetections !== null) {
            summaries.region.frames++;
            summaries.region.ms += regionDetections.totalMs;
            summaries.region.hits += regionDetections.length;
        }

        if (regionDetections !== null && regionDetections.length > 0) {
            regionDetections.forEach(function (item) {
                console.log('YOLO_ROI_HIT', backend, 'region=' + REGION.join(','),
                    item.label, (item.score * 100).toFixed(1) + '%', item.bounds);
            });
        }

        if (drawingShown) {
            drawing.update(regionDetections || [], backend + ' ROI  '
                + (summaries.region.ms / summaries.region.frames).toFixed(0) + ' ms  '
                + '命中 ' + summaries.region.hits);
        }

        var now = performance.now();
        if (now - summaryStarted >= 5000 || (i + 1) === TOTAL_FRAMES) {
            console.log('YOLO_ROI_SUMMARY', {
                backend: backend,
                region: REGION,
                full: {
                    averageMs: summaries.full.ms / Math.max(1, summaries.full.frames),
                    hits: summaries.full.hits
                },
                region: {
                    averageMs: summaries.region.ms / Math.max(1, summaries.region.frames),
                    hits: summaries.region.hits
                }
            });
            summaryStarted = now;
            summaries = { full: { frames: 0, ms: 0, hits: 0 }, region: { frames: 0, ms: 0, hits: 0 } };
        }

        // 全屏和 ROI 各推理一次，不再额外 sleep 拉低吞吐。
    }

    var totalElapsed = performance.now() - loopStarted;

    // ---- 输出统计 ----
    console.log('');
    console.log('========================================');
    console.log('[YOLO] 区域检测测试完成');
    console.log('[YOLO] 总耗时: ' + (totalElapsed / 1000).toFixed(1) + 's');
    console.log('[YOLO] frames=' + TOTAL_FRAMES + ' errors=' + errors);
    console.log('========================================');

    var sFull = summarizeYolo('full_total', fullTotalMs);
    var sRegion = summarizeYolo('region_total', regionTotalMs);
    console.log('[YOLO] full    p50=' + sFull.p50.toFixed(3) +
        'ms p95=' + sFull.p95.toFixed(3) + 'ms max=' + sFull.max.toFixed(3) + 'ms');
    console.log('[YOLO] region  p50=' + sRegion.p50.toFixed(3) +
        'ms p95=' + sRegion.p95.toFixed(3) + 'ms max=' + sRegion.max.toFixed(3) + 'ms');

    toastLog(backend + ' YOLO 区域检测 ' + TOTAL_FRAMES + ' 帧完成，errors=' + errors);

} catch (error) {
    if (!String(error && error.message ? error.message : error).match(/interrupt/i)) {
        throw error;
    }
} finally {
    if (drawingShown) drawing.hide();
    detector.close();
}
