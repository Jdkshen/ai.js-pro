// @engine quickjs
// YOLO 姿态实时绘制
// 用途：连续截图识别姿态模型，实时在屏幕上画检测框 + 关键点 + 骨架连线
// 前置：截图授权 + 悬浮窗权限（drawing 覆盖层）；姿态模型（end2end 导出，如官方 YOLO26n-pose）
// 覆盖：yolo

var backend = 'dnn';
// 实时循环：TOTAL_FRAMES=0 表示一直跑（点脚本「停止」结束）；MAX_FPS 是含推理在内的整体节奏上限
var TOTAL_FRAMES = 300;
var MAX_FPS = 12;
// 骨架连线：true = 按关键点数量选骨架连（4 点棋盘四角闭合 / 17 点 COCO 人体骨架 / 其它按顺序相邻）
var CONNECT_KEYPOINTS = true;
// 骨架边表（关键点索引对；引用的点分数低于 KPT_MIN_SCORE 时该边不画）
var SKELETONS = {
    4: [[0, 1], [1, 2], [2, 3], [3, 0]],
    17: [[0, 1], [0, 2], [1, 3], [2, 4], [5, 6], [5, 7], [7, 9], [6, 8], [8, 10],
         [5, 11], [6, 12], [11, 12], [11, 13], [13, 15], [12, 14], [14, 16]]
};
var LINE_COLOR = '#CC00E5FF';
var LINE_WIDTH = 3;
var KPT_MIN_SCORE = 0.3;
// 可选：不经过模型库直接指定模型（还没导入时先试）：
//   var MODEL_OVERRIDE = { model: '/sdcard/脚本/模型库/pose-synth-4kpt.onnx', inputSize: 320 };
var MODEL_OVERRIDE = null;

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
var MODEL = MODEL_OVERRIDE
    ? {
        name: '自定义（MODEL_OVERRIDE）',
        model: MODEL_OVERRIDE.model,
        labels: MODEL_OVERRIDE.labels || BUILTIN_MODEL.labels,
        inputSize: Number(MODEL_OVERRIDE.inputSize || 320),
        inputWidth: Number(MODEL_OVERRIDE.inputWidth || 0),
        inputHeight: Number(MODEL_OVERRIDE.inputHeight || 0),
        source: '脚本指定'
    }
    : resolveModel();
console.log('[模型] 本次识别使用：' + MODEL.name + '（' + shapeText(MODEL) + '，来源：' + MODEL.source + '）');

var detector = yolo.load(loadOptionsFor(backend, MODEL));

var drawingShown = drawing.show();
console.log('DRAWING_SHOW', drawingShown);
if (!drawingShown) {
    toast('未获得悬浮窗权限，无法绘制，请允许「显示在其他应用上层」');
}

if (!requestScreenCapture('portrait')) {
    if (drawingShown) drawing.hide();
    detector.close();
    throw new Error('用户取消了屏幕捕获授权');
}

// ---- 统计 ----
var frames = 0;
var hitsTotal = 0;
var kptsTotal = 0;
var poseFrames = 0;
var errors = 0;
var startedAt = performance.now();
function formatMs(value) {
    var n = Number(value) || 0;
    return n.toFixed(1);
}
// 按关键点数量取骨架边表；未知数量退化成“顺序相邻相连”
function edgesFor(count) {
    if (SKELETONS[count]) return SKELETONS[count];
    var seq = [];
    for (var i = 0; i + 1 < count; i++) seq.push([i, i + 1]);
    return count >= 2 ? seq : null;
}
// 用画线图元把关键点连成骨架（engine 的 drawing 升级后 drawLine 是真正的直线）
function drawSkeletons(detections) {
    detections.forEach(function (item) {
        var kps = item.keypoints;
        if (!kps || kps.length < 2) return;
        var edges = edgesFor(kps.length);
        if (!edges) return;
        for (var e = 0; e < edges.length; e++) {
            var a = kps[edges[e][0]];
            var b = kps[edges[e][1]];
            if (!a || !b) continue;
            if (a.score < KPT_MIN_SCORE || b.score < KPT_MIN_SCORE) continue;
            drawing.drawLine(a.x, a.y, b.x, b.y, LINE_COLOR, LINE_WIDTH);
        }
    });
}

try {
    // 预热 1 帧（首次推理含图优化，不计入统计）
    var warm = captureScreen(CAPTURE_OPTIONS);
    try { detector.detect(warm, { confidence: 0.25, nms: 0.45 }); } catch (e) { /* ignore */ }
    warm.recycle();

    while (TOTAL_FRAMES === 0 || frames < TOTAL_FRAMES) {
        var frameStart = performance.now();
        var frame = captureScreen(CAPTURE_OPTIONS);
        var detections = [];
        try {
            detections = detector.detect(frame, { confidence: 0.25, nms: 0.45 });
        } catch (e) {
            errors++;
            if (errors <= 5) console.log('[ERROR] frame=' + frames + ' ' + String(e.message || e));
        } finally {
            frame.recycle();
        }
        frames++;

        // 统计关键点（连线在绘制段用 drawLine 画骨架，engine 负责画点）
        var kptCount = 0;
        var withKpts = 0;
        detections.forEach(function (item) {
            if (item.keypoints && item.keypoints.length) {
                withKpts++;
                kptCount += item.keypoints.length;
            }
        });
        hitsTotal += detections.length;
        kptsTotal += kptCount;
        if (withKpts > 0) poseFrames++;

        // ---- 绘制：先 clear 上一帧图元，再提交「骨架线 + 说明文字 + 检测框（含关键点） ----
        if (drawingShown) {
            drawing.clear();
            if (CONNECT_KEYPOINTS) drawSkeletons(detections);
            drawing.drawText(30, device.height - 40,
                'YOLO Pose live  ' + MODEL.name + '  ' + (CONNECT_KEYPOINTS ? '骨架已连' : '仅画点'),
                '#B3FFFFFF', 30);
            var elapsed = performance.now() - frameStart;
            // 显示口径与 MAX_FPS 一致（含等待时间，不准显示比上限还高的瞬时值）
            var instantFps = elapsed > 0 ? Math.min(1000 / elapsed, MAX_FPS) : 0;
            drawing.update(detections,
                backend + '  ' + MODEL.name +
                '  帧 ' + frames + (TOTAL_FRAMES > 0 ? ('/' + TOTAL_FRAMES) : '') +
                '  ' + instantFps.toFixed(1) + ' FPS  inf ' + formatMs(detections.inferenceMs) +
                'ms  命中 ' + detections.length + '  关键点 ' + kptCount);
        }

        if (frames <= 3 || frames % 30 === 0) {
            console.log('YOLO_POSE_LIVE', {
                frame: frames,
                detections: detections.length,
                keypointCount: kptCount,
                preprocessMs: detections.preprocessMs,
                inferenceMs: detections.inferenceMs
            });
        }

        // 节奏控制：整体不超过 MAX_FPS（推理快的机型自动让出 CPU）
        var idle = 1000 / MAX_FPS - (performance.now() - frameStart);
        if (idle > 0) sleep(idle);
    }
} finally {
    if (drawingShown) drawing.hide();
    detector.close();
}

var totalElapsed = performance.now() - startedAt;
console.log('========================================');
console.log('QUICKJS_YOLO_POSE_LIVE_OK', {
    backend: backend,
    model: MODEL.name,
    frames: frames,
    elapsedMs: totalElapsed,
    fps: (frames * 1000 / totalElapsed).toFixed(1),
    poseFrames: poseFrames,
    hits: hitsTotal,
    keypoints: kptsTotal,
    errors: errors
});
console.log('========================================');
if (poseFrames === 0) {
    console.log('[YOLO] 全程没有关键点输出。若确认用的是姿态模型，请检查：' +
        '① 导出为 end2end（行宽 6+3K，如四角模型 [1,300,18]）；' +
        '② 输入尺寸与训练分辨率一致（可在「模型管理.js」里设置）。');
}
toastLog(backend + ' 姿态实时绘制结束：' + frames + ' 帧，' +
    ((frames * 1000 / totalElapsed).toFixed(1)) + ' FPS，带关键点帧 ' + poseFrames + '/' + frames);
