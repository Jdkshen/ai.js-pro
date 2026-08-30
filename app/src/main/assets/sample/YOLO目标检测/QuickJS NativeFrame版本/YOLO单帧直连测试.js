// @engine quickjs

const backend = 'ncnn'; // 可选：'ncnn' | 'onnx' | 'opencv'（别名 cpu/ort/onnxruntime/dnn/opencv5）
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

// ncnn 使用 param/bin；onnx 与 opencv 使用 model（.onnx）
const isNcnn = backend === 'ncnn' || backend === 'cpu';
const modelRoot = isNcnn
    ? 'asset://sample/YOLO目标检测/NCNN版本/models/'
    : 'asset://sample/YOLO目标检测/ONNX Runtime版本/models/';
const detector = yolo.load({
    backend: backend,
    model: isNcnn ? '' : modelRoot + 'yolo26_320.onnx',
    param: isNcnn ? modelRoot + 'yolo26_320.param' : '',
    bin: isNcnn ? modelRoot + 'yolo26_320.bin' : '',
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
    toastLog(backend + ' YOLO 完成：' + detections.length + ' 个目标');
} finally {
    if (frame) frame.recycle();
    detector.close();
}
