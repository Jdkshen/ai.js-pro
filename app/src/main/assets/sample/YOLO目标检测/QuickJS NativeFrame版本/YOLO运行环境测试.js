// @engine quickjs

// 运行环境测试：探测三后端可用性 / 版本，并计时模型加载。
// 实际检测请运行 YOLO单帧直连测试.js（需要截图授权）。

const backends = ['ncnn', 'onnx', 'opencv'];
const results = {};
backends.forEach(function (name) {
    const available = yolo.isAvailable(name);
    results[name] = {
        available: available,
        version: yolo.getVersion(name),
        reason: yolo.getUnavailableReason(name)
    };
});
console.log('QUICKJS_YOLO_ENV', results);
toastLog('YOLO 环境探测完成：' + backends.filter(function (name) {
    return results[name].available;
}).join(' / '));

const backend = 'ncnn'; // 待测后端：ncnn / onnx / opencv
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

const isNcnn = backend === 'ncnn' || backend === 'cpu';
const modelRoot = isNcnn
    ? 'asset://sample/YOLO目标检测/NCNN版本/models/'
    : 'asset://sample/YOLO目标检测/ONNX Runtime版本/models/';

let detector = null;
const loadStarted = Date.now();
try {
    detector = yolo.load({
        backend: backend,
        model: isNcnn ? '' : modelRoot + 'yolo26_320.onnx',
        param: isNcnn ? modelRoot + 'yolo26_320.param' : '',
        bin: isNcnn ? modelRoot + 'yolo26_320.bin' : '',
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
