// @engine quickjs

const backend = 'ncnn';
if (!yolo.isAvailable(backend)) {
    throw new Error('NCNN 不可用：' + yolo.getUnavailableReason(backend));
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

if (!requestScreenCapture('portrait')) {
    detector.close();
    throw new Error('用户取消了屏幕捕获授权');
}

const loopStarted = Date.now();
let totalInferenceMs = 0;
let lastDetections = [];
try {
    for (let index = 1; index <= 60; index++) {
        const frame = captureScreen();
        try {
            lastDetections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            totalInferenceMs += lastDetections.totalMs;
        } finally {
            frame.recycle();
        }

        if (index === 1 || index % 10 === 0) {
            console.log('NATIVE_YOLO_FRAME', {
                frame: index,
                detections: lastDetections.length,
                preprocessMs: lastDetections.preprocessMs,
                inferenceMs: lastDetections.inferenceMs,
                averageModelMs: totalInferenceMs / index
            });
        }
    }
} finally {
    detector.close();
}

const elapsedMs = Date.now() - loopStarted;
console.log('QUICKJS_NATIVE_YOLO_LOOP_OK', {
    frames: 60,
    elapsedMs: elapsedMs,
    fps: 60000 / elapsedMs,
    averageModelMs: totalInferenceMs / 60,
    lastDetections: lastDetections.length
});
toastLog('Native YOLO 60 帧测试完成');
