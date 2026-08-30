// @engine quickjs

// 运行环境测试：onnx 后端可用性 / 版本 / 模型加载耗时。
// 实际检测请运行 YOLO单帧直连测试.js（需要截图授权）。

const backend = 'onnx';
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

const modelRoot = 'asset://sample/YOLO目标检测/ONNX Runtime版本/models/';
let detector = null;
const loadStarted = Date.now();
try {
    detector = yolo.load({
        backend: backend,
        model: modelRoot + 'yolo26_320.onnx',
        labels: modelRoot + 'labels.txt',
        inputSize: 320,
        threads: 4
    });
    const loadMs = Date.now() - loadStarted;
    console.log('QUICKJS_YOLO_LOAD_OK', {
        backend: backend,
        loadMs: loadMs,
        version: yolo.getVersion(backend)
    });
    toastLog(backend + ' YOLO 环境测试通过，模型加载 ' + loadMs + ' ms');
} finally {
    if (detector) detector.close();
}
