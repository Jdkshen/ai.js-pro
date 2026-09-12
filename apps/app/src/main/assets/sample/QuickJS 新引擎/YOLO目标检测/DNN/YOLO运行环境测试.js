// @engine quickjs
// YOLO 运行环境测试
// 用途：检查内置 ONNX 模型、DNN 后端与截图权限是否可用
// 前置：截图授权（模型随 APK 内置）
// 覆盖：yolo

const backend = 'dnn';
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

const modelRoot = 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/';
let detector = null;
const loadStarted = performance.now();
try {
    detector = yolo.load({
        backend: backend,
        model: modelRoot + 'yolo26_640.onnx',
        labels: modelRoot + 'labels.txt',
        inputSize: 640,
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
