// @engine quickjs

// YOLO 基准测试（OpenCV 5.0 DNN 后端）：预热 1 帧 + 测 N 帧，输出平均耗时。
// 需要截图授权。想跑更准可以加大 frameCount。

const frameCount = 5;
// 基准直接使用最新缓存帧，避免把等待屏幕刷新算入模型性能。
// 改成 mode: 'full' 可测原尺寸输入；720p 通常是实时视觉的平衡档。
const CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };
const modelRoot = 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/';
const backend = 'dnn';

if (!requestScreenCapture('portrait')) {
    throw new Error('用户取消了屏幕捕获授权');
}

if (!yolo.isAvailable(backend)) {
    throw new Error('DNN 后端不可用：' + yolo.getUnavailableReason(backend));
}

let detector = null;
let averageMs = 0;
try {
    detector = yolo.load({
        backend: backend,
        model: modelRoot + 'yolo26_320.onnx',
        labels: modelRoot + 'labels.txt',
        inputSize: 320,
        threads: 4
    });

    let totalMs = 0;
    for (let i = 0; i < 1 + frameCount; i++) {
        const frame = captureScreen(CAPTURE_OPTIONS);
        try {
            const detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            if (i >= 1) totalMs += detections.totalMs; // 跳过首帧冷启动
        } finally {
            frame.recycle();
        }
    }
    averageMs = totalMs / frameCount;
    console.log('YOLO_BENCH', {
        backend: backend,
        version: yolo.getVersion(backend),
        captureMode: CAPTURE_OPTIONS,
        averageMs: averageMs
    });
} finally {
    if (detector) detector.close();
}

toastLog('DNN 平均耗时 ' + averageMs.toFixed(0) + ' ms');
