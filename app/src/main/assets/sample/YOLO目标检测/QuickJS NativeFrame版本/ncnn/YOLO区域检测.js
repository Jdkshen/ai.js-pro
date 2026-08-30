// @engine quickjs

// 区域检测（ROI）：只对画面中指定区域进行识别，检测框坐标已回移到全屏坐标系。
// 演示 region 参数 + 每 5 秒输出“全屏 vs 区域”性能/命中对比。
// 停止方式：应用任务列表停止该脚本（sleep 分片内及时响应）。

const backend = 'ncnn';
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

const modelRoot = 'asset://sample/YOLO目标检测/NCNN版本/models/';
const detector = yolo.load({
    backend: backend,
    param: modelRoot + 'yolo26_320.param',
    bin: modelRoot + 'yolo26_320.bin',
    labels: modelRoot + 'labels.txt',
    inputSize: 320,
    threads: 4
});

const drawingShown = drawing.show();
console.log('DRAWING_SHOW', drawingShown);
if (!drawingShown) {
    toast('未获得悬浮窗权限，无法绘制检测框，请在系统设置里允许悬浮窗');
}

if (!requestScreenCapture('portrait')) {
    detector.close();
    throw new Error('用户取消了屏幕捕获授权');
}

// 待检测区域 [x, y, width, height]（全屏像素坐标，需在画面范围内，否则报错）
const REGION = [100, 100, 700, 700];

let frameIndex = 0;
let summaryStarted = Date.now();
let summaries = { full: { frames: 0, ms: 0, hits: 0 }, region: { frames: 0, ms: 0, hits: 0 } };
try {
    while (true) {
        const frame = captureScreen();
        let fullDetections, regionDetections;
        try {
            // 全屏检测（对照）
            fullDetections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            // 区域检测：只取 REGION 内的目标，坐标自动回移到全屏
            regionDetections = detector.detect(frame, {
                confidence: 0.25, nms: 0.45,
                region: REGION
            });
        } finally {
            frame.recycle();
        }

        frameIndex++;
        summaries.full.frames++;
        summaries.full.ms += fullDetections.totalMs;
        summaries.full.hits += fullDetections.length;
        summaries.region.frames++;
        summaries.region.ms += regionDetections.totalMs;
        summaries.region.hits += regionDetections.length;

        if (regionDetections.length > 0) {
            regionDetections.forEach(function (item) {
                console.log('YOLO_ROI_HIT', backend, 'region=' + REGION.join(','),
                    item.label, (item.score * 100).toFixed(1) + '%', item.bounds);
            });
        }

        if (drawingShown) {
            drawing.update(regionDetections, backend + ' ROI  '
                + (summaries.region.ms / summaries.region.frames).toFixed(0) + ' ms  '
                + '命中 ' + summaries.region.hits);
        }

        const now = Date.now();
        if (now - summaryStarted >= 5000) {
            console.log('YOLO_ROI_SUMMARY', {
                backend: backend,
                region: REGION,
                full: {
                    averageMs: summaries.full.ms / summaries.full.frames,
                    hits: summaries.full.hits
                },
                region: {
                    averageMs: summaries.region.ms / summaries.region.frames,
                    hits: summaries.region.hits
                }
            });
            summaryStarted = now;
            summaries = { full: { frames: 0, ms: 0, hits: 0 }, region: { frames: 0, ms: 0, hits: 0 } };
        }

        sleep(16);
    }
} catch (error) {
    // 主动停止（任务列表停止）会抛 “Script execution interrupted”，属正常中断，静默结束；其它错误照常上报。
    if (!String(error && error.message ? error.message : error).match(/interrupt/i)) {
        throw error;
    }
} finally {
    if (drawingShown) drawing.hide();
    detector.close();
}
