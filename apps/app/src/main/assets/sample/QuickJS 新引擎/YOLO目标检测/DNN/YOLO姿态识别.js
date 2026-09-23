// @engine quickjs
// YOLO 姿态（Pose）识别
// 用途：单帧截图 → 检测 + 关键点 → 打印检测框与 keypoints（验证引擎对 6+3K 行宽姿态输出的解析）
// 前置：截图授权；模型库里有姿态模型（如 YOLO26n-pose 320 导出的 end2end ONNX）
// 覆盖：yolo

var backend = 'dnn';
var CAPTURE_OPTIONS = { mode: 'fast', size: 720, fresh: false };
if (!yolo.isAvailable(backend)) {
    throw new Error(backend + ' 不可用：' + yolo.getUnavailableReason(backend));
}

// ---- 当前模型：由「模型管理.js」选择，没选过就用发布包内置模型 ----
var MODEL_STORE = 'aijspro.yolo.models';
var BUILTIN_MODEL = {
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
var MODEL = resolveModel();
console.log('[模型] 本次识别使用：' + MODEL.name + '（' + shapeText(MODEL) + '，来源：' + MODEL.source + '）');

var detector = yolo.load(loadOptionsFor(backend, MODEL));

var frame = null;
try {
    if (!requestScreenCapture('portrait')) {
        throw new Error('用户取消了屏幕捕获授权');
    }

    // 预热 1 帧（首次推理含图优化，耗时不计入）
    var warmup = captureScreen(CAPTURE_OPTIONS);
    try { detector.detect(warmup, { confidence: 0.25, nms: 0.45 }); } catch (e) { /* ignore */ }
    warmup.recycle();

    frame = captureScreen(CAPTURE_OPTIONS);
    if (!frame) throw new Error('截图失败');
    var detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });

    console.log('QUICKJS_YOLO_POSE_OK', {
        engine: __engine__.name,
        backend: backend,
        model: MODEL.name,
        inputSize: MODEL.inputSize,
        inputWidth: MODEL.inputWidth,
        inputHeight: MODEL.inputHeight,
        frame: frame.width + 'x' + frame.height,
        nativeFrame: frame.pixelWidth + 'x' + frame.pixelHeight,
        detections: detections.length,
        preprocessMs: detections.preprocessMs,
        inferenceMs: detections.inferenceMs,
        totalMs: detections.totalMs
    });

    // ---- 逐条打印：框 + 关键点（姿态模型每个检测多一个 keypoints 数组） ----
    // 姿态模型：输出行宽 = 6 + 3K（K = 关键点个数，如 COCO 17 点 → 57），
    // detections[i].keypoints = [{ x, y, score } × K]（全屏像素坐标，与 bounds 同一坐标系）
    var poseCount = 0;
    var printed = 0;
    detections.forEach(function (item) {
        var line = item.label + ' ' + (item.score * 100).toFixed(1) + '% ' +
            '(' + Math.round(item.bounds.left) + ',' + Math.round(item.bounds.top) + ')-(' +
            Math.round(item.bounds.right) + ',' + Math.round(item.bounds.bottom) + ')';
        if (item.keypoints && item.keypoints.length) {
            poseCount++;
            line += '  kpts=' + item.keypoints.length;
        }
        console.log(line);
        // 关键点较多，最多详列前 2 个目标
        if (item.keypoints && item.keypoints.length && printed < 2) {
            printed++;
            for (var k = 0; k < item.keypoints.length; k++) {
                var kp = item.keypoints[k];
                console.log('  #' + (k + 1) + '  x=' + kp.x.toFixed(1) +
                    '  y=' + kp.y.toFixed(1) + '  score=' + kp.score.toFixed(3));
            }
        }
    });

    if (poseCount === 0) {
        console.log('[YOLO] 本帧没有带 keypoints 的检测。若确认用的是姿态模型，请检查：' +
            '① 导出为 end2end（行宽 6+3K，如 [1,300,57]）；' +
            '② 输入尺寸与训练分辨率一致（YOLO26n-pose 应为 320，可在「模型管理.js」里设置）。');
    }
    toastLog(backend + ' 姿态识别完成：检出 ' + detections.length + ' 个，带关键点 ' + poseCount + ' 个');

} finally {
    if (frame) frame.recycle();
    detector.close();
}
