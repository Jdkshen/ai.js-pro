// @engine quickjs
// YOLO 基准测试
// 用途：测量预处理 / 推理平均耗时与端到端 FPS
// 前置：截图授权
// 覆盖：yolo

const frameCount = 5;
// 基准直接使用最新缓存帧，避免把等待屏幕刷新算入模型性能。
// 改成 mode: 'full' 可测原尺寸输入；720p 通常是实时视觉的平衡档。
const CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };
const backend = 'dnn';

if (!requestScreenCapture('portrait')) {
    throw new Error('用户取消了屏幕捕获授权');
}

if (!yolo.isAvailable(backend)) {
    throw new Error('DNN 后端不可用：' + yolo.getUnavailableReason(backend));
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
let averageMs = 0;
try {
    detector = yolo.load(loadOptionsFor(backend, MODEL));

    let totalMs = 0;
    for (let i = 0; i < 1 + frameCount; i++) {
        const frame = captureScreen(CAPTURE_OPTIONS);
        try {
            const detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            if (i >= 1) totalMs += detections.totalMs; // 跳过首帧冷启动
        } finally {
            frame.recycle();
        }
    }
    averageMs = totalMs / frameCount;
    console.log('YOLO_BENCH', {
        backend: backend,
        model: MODEL.name,
        version: yolo.getVersion(backend),
        captureMode: CAPTURE_OPTIONS,
        averageMs: averageMs
    });
} finally {
    if (detector) detector.close();
}

toastLog('DNN 平均耗时 ' + averageMs.toFixed(0) + ' ms');
