function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

if(!requestScreenCapture()){
    toast("请求截图失败");
    exit();
}
var captureStartedAt = __nowMs();
// 保存文件要尽量对应当前画面：等待新帧最多 100ms，超时回退最近有效帧。
var img = images.captureScreen(true, 100);
console.log("[Rhino耗时] 截图: " + __formatMs(__nowMs() - captureStartedAt) + " ms");

var saveStartedAt = __nowMs();
images.saveImage(img, "/sdcard/1.png");
console.log("[Rhino耗时] 保存PNG: " + __formatMs(__nowMs() - saveStartedAt) + " ms");
img.recycle();
