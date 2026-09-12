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
let averageMs = 0;
try {
    detector = yolo.load({
        backend: backend,
        model: MODEL.model,
        labels: MODEL.labels || undefined,
        inputSize: MODEL.inputSize,
        threads: 4
    });

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
