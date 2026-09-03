function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

if(!requestScreenCapture()){
    toast("请求截图失败");
    exit();
}
var captureStartedAt = __nowMs();
var img = captureScreen();
console.log("[Rhino耗时] 截图: " + __formatMs(__nowMs() - captureStartedAt) + " ms");
//0x9966ff为编辑器紫色字体的颜色
toastLog("开始找色");
var findStartedAt = __nowMs();
var point = findColor(img, 0x9966ff);
console.log("[Rhino耗时] 模糊找色: " + __formatMs(__nowMs() - findStartedAt) + " ms");
if(point){
    toastLog("x = " + point.x + ", y = " + point.y);
}else{
    toastLog("没有找到");
}
img.recycle();

