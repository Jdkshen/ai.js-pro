// @engine quickjs

const backend = 'ncnn'; // 可选：'ncnn' | 'onnx' | 'opencv'（别名 cpu/ort/onnxruntime/dnn/opencv5）
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

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

if (!requestScreenCapture('portrait')) {
    detector.close();
    throw new Error('用户取消了屏幕捕获授权');
}

const loopStarted = Date.now();
let totalModelMs = 0;
let maxModelMs = 0;
let minModelMs = Number.POSITIVE_INFINITY;
let lastDetections = [];
try {
    for (let index = 1; index <= 60; index++) {
        const frame = captureScreen();
        try {
            lastDetections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            const modelMs = lastDetections.totalMs;
            totalModelMs += modelMs;
            if (modelMs > maxModelMs) maxModelMs = modelMs;
            if (modelMs < minModelMs) minModelMs = modelMs;
        } finally {
            frame.recycle();
        }

        if (index === 1 || index % 10 === 0) {
            console.log('NATIVE_YOLO_FRAME', {
                backend: backend,
                frame: index,
                detections: lastDetections.length,
                preprocessMs: lastDetections.preprocessMs,
                inferenceMs: lastDetections.inferenceMs,
                totalMs: lastDetections.totalMs,
                averageModelMs: totalModelMs / index
            });
        }
    }
} finally {
    detector.close();
}

const elapsedMs = Date.now() - loopStarted;
console.log('QUICKJS_NATIVE_YOLO_LOOP_OK', {
    backend: backend,
    frames: 60,
    elapsedMs: elapsedMs,
    fps: 60000 / elapsedMs,
    averageModelMs: totalModelMs / 60,
    maxModelMs: maxModelMs,
    minModelMs: minModelMs,
    lastDetections: lastDetections.length
});
toastLog(backend + ' YOLO 60 帧测试完成：' + (60000 / elapsedMs).toFixed(1) + ' FPS');
