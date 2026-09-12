// @engine quickjs
// YOLO 运行环境测试
// 用途：检查 DNN 后端、当前模型（模型库选择或内置）与截图权限是否可用
// 前置：截图授权（模型默认随 APK 内置，可先用「模型管理.js」换库内模型）
// 覆盖：yolo / files / storages

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

// ---- 当前模型：由「模型管理.js」选择，没选过就用发布包内置模型 ----
const MODEL_STORE = 'aijspro.yolo.models';
const BUILTIN_MODEL = {
    name: '内置 yolo26_640',
    model: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/yolo26_640.onnx',
    labels: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/labels.txt',
    inputSize: 640,
    source: '内置资源'
};
function resolveModel() {
    const store = storages.create(MODEL_STORE);
    const id = String(store.get('current', '@builtin'));
    if (id === '@builtin') return BUILTIN_MODEL;
    const dir = String(store.get('dir', files.join(files.getSdcardPath(), '脚本', '模型库')));
    const path = files.join(dir, id);
    if (!files.isFile(path)) {
        console.log('[模型] 模型库里的 ' + id + ' 已不存在，回退内置模型');
        return BUILTIN_MODEL;
    }
    const labels = String(store.get('labels.' + id, files.join(dir, 'labels.txt')));
    return {
        name: id.replace(/\.onnx$/i, ''),
        model: path,
        labels: files.isFile(labels) ? labels : '',
        inputSize: Number(store.get('inputSize.' + id, 640)),
        source: dir
    };
}
const MODEL = resolveModel();
console.log('[模型] 本次识别使用：' + MODEL.name + '（inputSize=' + MODEL.inputSize + '，来源：' + MODEL.source + '）');

let detector = null;
const loadStarted = performance.now();
try {
    detector = yolo.load({
        backend: backend,
        model: MODEL.model,
        labels: MODEL.labels || undefined,
        inputSize: MODEL.inputSize,
        threads: 4
    });
    const loadMs = performance.now() - loadStarted;
    console.log('QUICKJS_YOLO_LOAD_OK', {
        backend: backend,
        model: MODEL.name,
        inputSize: MODEL.inputSize,
        source: MODEL.source,
        loadMs: Number(loadMs.toFixed(3)),
        version: yolo.getVersion(backend)
    });
    toastLog(backend + ' YOLO 环境测试通过，模型加载 ' + loadMs.toFixed(3) + ' ms');
} finally {
    if (detector) detector.close();
}
