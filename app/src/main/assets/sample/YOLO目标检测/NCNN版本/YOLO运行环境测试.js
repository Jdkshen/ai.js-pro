importClass(android.graphics.Bitmap);
importClass(android.util.Log);

var backend = "ncnn";
var tag = "AutoJsYoloNcnnSmoke";
var detector = null;
var image = null;
try {
    if (!yolo.isAvailable(backend)) {
        throw new Error(yolo.getUnavailableReason(backend));
    }
    var assetRoot = "sample/YOLO目标检测/NCNN版本/models/";
    detector = yolo.load({
        backend: backend,
        param: "asset://" + assetRoot + "yolo26_320.param",
        bin: "asset://" + assetRoot + "yolo26_320.bin",
        inputSize: 320,
        threads: 4
    });
    image = Image.ofBitmap(Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888));
    var result = detector.detect(image, {confidence: 0.25, nms: 0.45});
    var message = "OK backend=" + backend + " version=" + yolo.getVersion(backend)
        + " detections=" + result.length
        + " preprocessMs=" + result.preprocessMs.toFixed(1)
        + " inferenceMs=" + result.inferenceMs.toFixed(1);
    Log.i(tag, message);
    toastLog("NCNN 运行环境测试通过");
} catch (error) {
    Log.e(tag, "FAILED " + error);
    throw error;
} finally {
    if (image) image.recycle();
    if (detector) detector.close();
}
