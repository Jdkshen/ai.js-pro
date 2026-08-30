// @engine quickjs

// YOLO 三后端基准对比：每个后端预热 1 帧 + 测 5 帧，输出平均耗时并推荐最快后端。
// 需要截图授权。想跑更准可以加大 frameCount。

const frameCount = 5;
const backends = [
    {
        name: 'ncnn',
        modelRoot: 'asset://sample/YOLO目标检测/NCNN版本/models/',
        options: function (root) {
            return {
                param: root + 'yolo26_320.param',
                bin: root + 'yolo26_320.bin',
                labels: root + 'labels.txt'
            };
        }
    },
    {
        name: 'onnx',
        modelRoot: 'asset://sample/YOLO目标检测/ONNX Runtime版本/models/',
        options: function (root) {
            return { model: root + 'yolo26_320.onnx', labels: root + 'labels.txt' };
        }
    },
    {
        name: 'opencv',
        modelRoot: 'asset://sample/YOLO目标检测/ONNX Runtime版本/models/',
        options: function (root) {
            return { model: root + 'yolo26_320.onnx', labels: root + 'labels.txt' };
        }
    }
];

if (!requestScreenCapture('portrait')) {
    throw new Error('用户取消了屏幕捕获授权');
}

const results = [];
backends.forEach(function (entry) {
    if (!yolo.isAvailable(entry.name)) {
        results.push({ backend: entry.name, available: false,
            reason: yolo.getUnavailableReason(entry.name) });
        return;
    }
    let detector = null;
    try {
        detector = yolo.load(Object.assign({
            backend: entry.name,
            inputSize: 320,
            threads: 4
        }, entry.options(entry.modelRoot)));

        let totalMs = 0;
        for (let i = 0; i < 1 + frameCount; i++) {
            const frame = captureScreen();
            try {
                const detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
                if (i >= 1) totalMs += detections.totalMs; // 跳过首帧冷启动
            } finally {
                frame.recycle();
            }
        }
        results.push({
            backend: entry.name,
            available: true,
            version: yolo.getVersion(entry.name),
            averageMs: totalMs / frameCount
        });
    } catch (error) {
        results.push({ backend: entry.name, available: false, reason: error.message });
    } finally {
        if (detector) detector.close();
    }
});

let fastest = null;
results.forEach(function (result) {
    console.log('YOLO_BENCH', result);
    if (result.available && (fastest === null || result.averageMs < fastest.averageMs)) {
        fastest = result;
    }
});
console.log('YOLO_BENCH_FASTEST', fastest);
toastLog(fastest ? '最快后端：' + fastest.backend + ' ' + fastest.averageMs.toFixed(0) + ' ms'
    : '无可用后端');
