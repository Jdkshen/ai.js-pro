var backend = "ncnn";
if (!yolo.isAvailable(backend)) {
    throw new Error("NCNN 不可用：" + yolo.getUnavailableReason(backend));
}

var assetRoot = "sample/YOLO目标检测/NCNN版本";
var useLocalModel = files.exists("./models/yolo26_320.param")
    && files.exists("./models/yolo26_320.bin");
var labels = (useLocalModel
    ? files.read("./models/labels.txt")
    : files.readAssets(assetRoot + "/models/labels.txt")).trim().split(/\r?\n/);
var detector = yolo.load({
    backend: backend,
    param: useLocalModel ? "./models/yolo26_320.param"
        : "asset://" + assetRoot + "/models/yolo26_320.param",
    bin: useLocalModel ? "./models/yolo26_320.bin"
        : "asset://" + assetRoot + "/models/yolo26_320.bin",
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
console.log("NCNN 实时识别已启动，停止脚本即可退出");
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
