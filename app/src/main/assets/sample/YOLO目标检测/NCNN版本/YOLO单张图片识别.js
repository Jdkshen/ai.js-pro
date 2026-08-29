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

if (!requestScreenCapture()) {
    detector.close();
    throw new Error("用户取消了截图权限");
}

var image = null;
try {
    image = captureScreen();
    var detections = detector.detect(image, {confidence: 0.25, nms: 0.45});
    console.show();
    console.log("NCNN " + yolo.getVersion(backend));
    console.log("预处理：" + detections.preprocessMs.toFixed(1) + " ms");
    console.log("推理：" + detections.inferenceMs.toFixed(1) + " ms");
    console.log("检测到 " + detections.length + " 个目标");
    detections.forEach(function (item) {
        console.log(item.label + "  " + (item.score * 100).toFixed(1) + "%  "
            + JSON.stringify(item.bounds));
    });
} finally {
    if (image) image.recycle();
    detector.close();
}
