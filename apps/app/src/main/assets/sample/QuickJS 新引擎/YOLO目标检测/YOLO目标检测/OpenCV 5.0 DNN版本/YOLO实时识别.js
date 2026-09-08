var backend = "opencv";
if (!yolo.isAvailable(backend)) {
    throw new Error("OpenCV DNN 不可用：" + yolo.getUnavailableReason(backend));
}

var assetRoot = "sample/QuickJS 新引擎/YOLO目标检测/YOLO目标检测/OpenCV 5.0 DNN版本";
var useLocalModel = files.exists("./models/yolo26_320.onnx");
var labels = (useLocalModel
    ? files.read("./models/labels.txt")
    : files.readAssets(assetRoot + "/models/labels.txt")).trim().split(/\r?\n/);
var detector = yolo.load({
    backend: backend,
    model: useLocalModel ? "./models/yolo26_320.onnx"
        : "asset://" + assetRoot + "/models/yolo26_320.onnx",
    inputSize: 320,
    threads: 4,
    labels: labels
});
events.on("exit", function () { detector.close(); });

if (!requestScreenCapture()) {
    detector.close();
    throw new Error("用户取消了截图权限");
}

console.show();
console.log("OpenCV DNN 实时识别已启动，停止脚本即可退出");
var frameCount = 0;
while (true) {
    var image = captureScreen();
    try {
        var detections = detector.detect(image, {confidence: 0.25, nms: 0.45});
        frameCount++;
        if (frameCount % 5 === 0) {
            console.log("#" + frameCount + "  " + detections.length + " 个目标  "
                + detections.totalMs.toFixed(1) + " ms");
        }
    } finally {
        image.recycle();
    }
    sleep(80);
}
