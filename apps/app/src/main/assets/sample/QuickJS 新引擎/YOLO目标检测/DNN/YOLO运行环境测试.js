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
    name: '内置 yolo26_160',
    model: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/yolo26_160.onnx',
    labels: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/labels.txt',
    inputSize: 160,
    inputWidth: 0,
    inputHeight: 0,
    source: '内置资源'
};
// 输入尺寸：方形用 inputSize；竖屏矩形（如 160x320）用 inputWidth/inputHeight，由「模型管理.js」设置
function rectShapeOf(id) {
    const store = storages.create(MODEL_STORE);
    const width = Number(store.get('inputWidth.' + id, 0));
    const height = Number(store.get('inputHeight.' + id, 0));
    return width >= 32 && width <= 2048 && height >= 32 && height <= 2048
        ? [Math.round(width), Math.round(height)] : [0, 0];
}
function shapeText(model) {
    return model.inputWidth > 0 && model.inputHeight > 0
        ? (model.inputWidth + 'x' + model.inputHeight + '（竖屏）')
        : ('inputSize=' + model.inputSize);
}
function loadOptionsFor(backend, model) {
    const options = {
        backend: backend,
        model: model.model,
        labels: model.labels || undefined,
        threads: 4
    };
    if (model.inputWidth > 0 && model.inputHeight > 0) {
        options.inputWidth = model.inputWidth;
        options.inputHeight = model.inputHeight;
    } else {
        options.inputSize = model.inputSize;
    }
    return options;
}

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
    const shape = rectShapeOf(id);
    // 没有同名 .txt / labels.txt 时用发布包内置的默认标签（COCO 80），免得识别结果只剩 classId 数字
    const useBuiltinLabels = !files.isFile(labels);
    if (useBuiltinLabels) {
        console.log('[模型] 没找到标签文件（' + labels + '），改用内置默认标签（COCO 80）');
    }
    const modelLabels = useBuiltinLabels ? BUILTIN_MODEL.labels : labels;
    return {
        name: id.replace(/\.onnx$/i, ''),
        model: path,
        labels: modelLabels,
        inputSize: Number(store.get('inputSize.' + id, 640)),
        inputWidth: shape[0],
        inputHeight: shape[1],
        source: dir
    };
}
const MODEL = resolveModel();
console.log('[模型] 本次识别使用：' + MODEL.name + '（' + shapeText(MODEL) + '，来源：' + MODEL.source + '）');

let detector = null;
const loadStarted = performance.now();
try {
    detector = yolo.load(loadOptionsFor(backend, MODEL));
    const loadMs = performance.now() - loadStarted;
    console.log('QUICKJS_YOLO_LOAD_OK', {
        backend: backend,
        model: MODEL.name,
        inputSize: MODEL.inputSize,
        inputWidth: MODEL.inputWidth,
        inputHeight: MODEL.inputHeight,
        source: MODEL.source,
        loadMs: Number(loadMs.toFixed(3)),
        version: yolo.getVersion(backend)
    });
    toastLog(backend + ' YOLO 环境测试通过，模型加载 ' + loadMs.toFixed(3) + ' ms');
} finally {
    if (detector) detector.close();
}
