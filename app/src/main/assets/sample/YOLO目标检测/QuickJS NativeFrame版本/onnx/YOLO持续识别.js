// @engine quickjs

// 持续识别：无限循环截图 + 检测，直到在任务列表停止脚本。
// 停止方式：应用任务页点“停止全部”或单任务停止（QuickJS interrupt 会在
// sleep 分片内及时响应，最迟 ~100ms 中止）。

const backend = 'onnx';
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

const modelRoot = 'asset://sample/YOLO目标检测/ONNX Runtime版本/models/';
const detector = yolo.load({
    backend: backend,
    model: modelRoot + 'yolo26_320.onnx',
    labels: modelRoot + 'labels.txt',
    inputSize: 320,
    threads: 4
});

const drawingShown = drawing.show();
if (!drawingShown) {
    toast('未获得悬浮窗权限，无法绘制检测框，请在系统设置里允许悬浮窗');
}

if (!requestScreenCapture('portrait')) {
    detector.close();
    throw new Error('用户取消了屏幕捕获授权');
}

let frameIndex = 0;
let hitTotal = 0;
let totalModelMs = 0;
let summaryStarted = Date.now();
let summaryFrames = 0;
let summaryHits = 0;
let summaryModelMs = 0;
try {
    while (true) {
        const frame = captureScreen();
        let detections = [];
        try {
            detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
        } finally {
            frame.recycle();
        }

        frameIndex++;
        totalModelMs += detections.totalMs;
        summaryFrames++;
        summaryModelMs += detections.totalMs;

        if (detections.length > 0) {
            hitTotal += detections.length;
            summaryHits += detections.length;
            detections.forEach(function (item) {
                console.log('YOLO_LIVE_HIT', backend,
                    item.label, (item.score * 100).toFixed(1) + '%', item.bounds);
            });
        }

        if (drawingShown) {
            drawing.update(detections, backend + '  ' + frameIndex + ' 帧  '
                + (totalModelMs / frameIndex).toFixed(0) + ' ms  命中 ' + hitTotal);
        }

        const now = Date.now();
        const summaryElapsed = now - summaryStarted;
        if (summaryElapsed >= 5000) {
            console.log('YOLO_LIVE_SUMMARY', {
                backend: backend,
                seconds: (summaryElapsed / 1000).toFixed(1),
                frames: summaryFrames,
                fps: Math.round(summaryFrames * 1000 / summaryElapsed),
                averageModelMs: summaryModelMs / summaryFrames,
                hits: summaryHits,
                totalHits: hitTotal
            });
            summaryStarted = now;
            summaryFrames = 0;
            summaryHits = 0;
            summaryModelMs = 0;
    if (drawingShown) drawing.hide();
        }

        sleep(100); // 可中断，控制帧率并让停止信号快速生效
    }
} finally {
    detector.close();
}
