// @engine quickjs
// YOLO 单帧直连测试
// 用途：单帧截图 → 检测 → 打印结果与耗时
// 前置：截图授权
// 覆盖：yolo

var backend = 'dnn';
var CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

// ---- 当前模型：由「模型管理.js」选择，没选过就用发布包内置模型 ----
var MODEL_STORE = 'aijspro.yolo.models';
var BUILTIN_MODEL = {
    name: '内置 yolo26_640',
    model: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/yolo26_640.onnx',
    labels: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/labels.txt',
    inputSize: 640,
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
    var store = storages.create(MODEL_STORE);
    var id = String(store.get('current', '@builtin'));
    if (id === '@builtin') return BUILTIN_MODEL;
    var dir = String(store.get('dir', files.join(files.getSdcardPath(), '脚本', '模型库')));
    var path = files.join(dir, id);
    if (!files.isFile(path)) {
        console.log('[模型] 模型库里的 ' + id + ' 已不存在，回退内置模型');
        return BUILTIN_MODEL;
    }
    var labels = String(store.get('labels.' + id, files.join(dir, 'labels.txt')));
    const shape = rectShapeOf(id);
    return {
        name: id.replace(/\.onnx$/i, ''),
        model: path,
        labels: files.isFile(labels) ? labels : '',
        inputSize: Number(store.get('inputSize.' + id, 640)),
        inputWidth: shape[0],
        inputHeight: shape[1],
        source: dir
    };
}
var MODEL = resolveModel();
console.log('[模型] 本次识别使用：' + MODEL.name + '（' + shapeText(MODEL) + '，来源：' + MODEL.source + '）');

var detector = yolo.load(loadOptionsFor(backend, MODEL));

// ---- 统计工具 ----
function percentile(sorted, ratio) {
    var index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
    return sorted[Math.max(0, index)];
}

function summarizeYolo(name, values) {
    if (values.length === 0) {
        console.log('[YOLO] ' + name + ' rounds=0（无成功样本）');
        return;
    }
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var total = 0;
    for (var i = 0; i < values.length; i++) total += values[i];
    console.log('[YOLO] ' + name + ' rounds=' + values.length);
    console.log('[YOLO] p50=' + percentile(sorted, 0.50).toFixed(3) +
        'ms p95=' + percentile(sorted, 0.95).toFixed(3) +
        'ms max=' + sorted[sorted.length - 1].toFixed(3) +
        'ms avg=' + (total / values.length).toFixed(3) + 'ms');
}

// ---- 参数 ----
var ROUNDS = 10;
var WARMUP_ROUNDS = 3;

var frame = null;
try {
    if (!requestScreenCapture('portrait')) {
        throw new Error('用户取消了屏幕捕获授权');
    }

    // 预热
    console.log('[YOLO] 预热 ' + WARMUP_ROUNDS + ' 帧...');
    for (var w = 0; w < WARMUP_ROUNDS; w++) {
        var wf = captureScreen(CAPTURE_OPTIONS);
        try { detector.detect(wf, { confidence: 0.25, nms: 0.45 }); } catch (e) { /* ignore */ }
        wf.recycle();
    }

    var captureMs = [];
    var preprocessArr = [];
    var inferenceArr = [];
    var totalArr = [];
    var errors = 0;

    console.log('[YOLO] 单帧直连测试 x' + ROUNDS + ' mode=fast size=720');
    var loopStarted = performance.now();

    for (var i = 0; i < ROUNDS; i++) {
        var capStart = performance.now();
        frame = captureScreen(CAPTURE_OPTIONS);
        captureMs.push(performance.now() - capStart);

        try {
            var detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
            preprocessArr.push(detections.preprocessMs);
            inferenceArr.push(detections.inferenceMs);
            totalArr.push(detections.totalMs);

            if (i === 0) {
                console.log('QUICKJS_NATIVE_YOLO_OK', {
                    engine: __engine__.name,
                    backend: backend,
                    model: MODEL.name,
                    inputSize: MODEL.inputSize,
                    inputWidth: MODEL.inputWidth,
                    inputHeight: MODEL.inputHeight,
                    version: yolo.getVersion(backend),
                    frame: frame.width + 'x' + frame.height,
                    nativeFrame: frame.pixelWidth + 'x' + frame.pixelHeight,
                    captureMode: frame.captureMode,
                    detections: detections.length,
                    preprocessMs: detections.preprocessMs,
                    inferenceMs: detections.inferenceMs,
                    totalMs: detections.totalMs
                });
                detections.forEach(function (item) {
                    console.log(item.label, (item.score * 100).toFixed(1) + '%', item.bounds);
                });
            }
        } catch (e) {
            errors++;
            if (errors <= 5) console.log('[ERROR] round=' + i + ' ' + String(e.message || e));
        } finally {
            frame.recycle();
            frame = null;
        }
    }

    var totalElapsed = performance.now() - loopStarted;
    console.log('');
    console.log('[YOLO] 测试完成 总耗时=' + (totalElapsed / 1000).toFixed(1) + 's errors=' + errors);
    summarizeYolo('capture', captureMs);
    summarizeYolo('preprocess', preprocessArr);
    summarizeYolo('inference', inferenceArr);
    summarizeYolo('total', totalArr);

    toastLog(backend + ' YOLO 单帧 ' + ROUNDS + ' 次完成，errors=' + errors);

} finally {
    if (frame) frame.recycle();
    detector.close();
}
