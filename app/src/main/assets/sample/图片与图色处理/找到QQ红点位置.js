function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

if(!requestScreenCapture()){
    toast("请求截图失败");
    exit();
}
launchApp("QQ");
sleep(2000);
var captureStartedAt = __nowMs();
// 打开 QQ 后等待新画面，避免识别到启动前的缓存截图。
var img = images.captureScreen(true, 100);
console.log("[Rhino耗时] 截图: " + __formatMs(__nowMs() - captureStartedAt) + " ms");
toastLog("开始找色");
var findStartedAt = __nowMs();
var point = findColor(img, "#f64d30");
console.log("[Rhino耗时] QQ红点找色: " + __formatMs(__nowMs() - findStartedAt) + " ms");
if(point){
    toastLog("x = " + point.x + ", y = " + point.y);
}else{
    toastLog("没有找到");
}
img.recycle();
