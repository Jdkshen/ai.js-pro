// @engine quickjs

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

let frame = null;
try {
    if (!requestScreenCapture('portrait')) {
        throw new Error('用户取消了屏幕捕获授权');
    }
    frame = captureScreen();
    const detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });

    console.log('QUICKJS_NATIVE_YOLO_OK', {
        engine: __engine__.name,
        backend: backend,
        version: yolo.getVersion(backend),
        frame: frame.width + 'x' + frame.height,
        detections: detections.length,
        preprocessMs: detections.preprocessMs,
        inferenceMs: detections.inferenceMs,
        totalMs: detections.totalMs
    });
    detections.forEach(function (item) {
        console.log(item.label, (item.score * 100).toFixed(1) + '%', item.bounds);
    });
    toastLog(backend + ' YOLO 单帧完成：' + detections.length + ' 个目标，'
        + detections.totalMs.toFixed(1) + ' ms');
} finally {
    if (frame) frame.recycle();
    detector.close();
}
