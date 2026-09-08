function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

if(!requestScreenCapture()){
    toast("请求截图失败");
    stop();
}
var captureStartedAt = __nowMs();
var img = captureScreen();
console.log("[Rhino耗时] 截图: " + __formatMs(__nowMs() - captureStartedAt) + " ms");
toastLog("开始找色");
//0x1d75b3为编辑器默认主题蓝色字体(if, var等关键字)的颜色
//找到颜色与0x1d75b3完全相等的颜色
var findStartedAt = __nowMs();
var point = findColorEquals(img, 0x006699);
console.log("[Rhino耗时] 精确找色: " + __formatMs(__nowMs() - findStartedAt) + " ms");
if(point){
    toastLog("x = " + point.x + ", y = " + point.y);
}else{
    toastLog("没有找到");
}
img.recycle();

