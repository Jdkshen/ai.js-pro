// @engine quickjs
// 与系统录屏并用：独立的无障碍系统截图 API，不申请 MediaProjection。
// Android 11+；请手动开启 AI.js Pro 无障碍，更新后需要关闭再开启以刷新截图能力。
// 注意：截取整个屏幕；系统限频，不能用这里的 FPS 评价 YOLO 推理速度。
// 推荐先启动系统录屏，再运行本示例；也可在识别过程中启动系统录屏。

if (!requestScreenCapture({ mode: 'accessibility' })) {
    throw new Error('无法启用录屏兼容截图');
}
const detector = yolo.load({
    backend: 'dnn',
    model: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/yolo26_160.onnx',
    labels: 'asset://sample/QuickJS 新引擎/YOLO目标检测/DNN/models/labels.txt',
    inputSize: 160,
    threads: 4
});
const started = Date.now();
let frames = 0;
let hits = 0;
try {
    while (frames < 60) {
        const frame = captureScreen({ mode: 'fast', size: 720, timeout: 2000 });
        try {
            const detections = detector.detect(frame);
            hits += detections.length;
            frames++;
            if (frames === 1 || frames % 10 === 0) {
                console.log('[录屏兼容] 帧=' + frames + ' 命中=' + hits
                    + ' 画面=' + frame.width + '×' + frame.height
                    + ' 实际取帧FPS=' + (frames * 1000 / (Date.now() - started)).toFixed(1));
            }
        } finally {
            frame.recycle();
        }
    }
    console.log('[录屏兼容] 60帧完成，请确认系统录屏仍在继续且画面正确');
} finally {
    detector.close();
}
