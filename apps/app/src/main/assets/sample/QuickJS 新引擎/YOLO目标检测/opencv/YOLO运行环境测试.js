// @engine quickjs

// 运行环境测试：opencv 后端可用性 / 版本 / 模型加载耗时。
// 实际检测请运行 YOLO单帧直连测试.js（需要截图授权）。

const backend = 'opencv';
const available = yolo.isAvailable(backend);
console.log('QUICKJS_YOLO_ENV', {
    backend: backend,
    available: available,
    version: yolo.getVersion(backend),
    reason: yolo.getUnavailableReason(backend)
});
if (!available) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

const modelRoot = 'asset://sample/QuickJS 新引擎/YOLO目标检测/YOLO目标检测/OpenCV 5.0 DNN版本/models/';
let detector = null;
const loadStarted = performance.now();
try {
    detector = yolo.load({
        backend: backend,
        model: modelRoot + 'yolo26_320.onnx',
        labels: modelRoot + 'labels.txt',
        inputSize: 320,
        threads: 4
    });
    const loadMs = performance.now() - loadStarted;
    console.log('QUICKJS_YOLO_LOAD_OK', {
        backend: backend,
        loadMs: Number(loadMs.toFixed(3)),
        version: yolo.getVersion(backend)
    });
    toastLog(backend + ' YOLO 环境测试通过，模型加载 ' + loadMs.toFixed(3) + ' ms');
} finally {
    if (detector) detector.close();
}
