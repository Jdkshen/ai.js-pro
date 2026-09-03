function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

if(!requestScreenCapture()){
    toast("请求截图失败");
    exit();
}
var captureStartedAt = __nowMs();
var img = captureScreen();
console.log("[Rhino耗时] 截图: " + __formatMs(__nowMs() - captureStartedAt) + " ms");
toastLog("开始找色");
//指定在位置(100, 220)宽高为400*400的区域找色。
//#75438a是编辑器默认主题的棕红色字体(数字)颜色，位置大约在第5行的"2000"，坐标大约为(283, 465)
var findStartedAt = __nowMs();
var point = findColorInRegion(img, "#75438a", 90, 220, 900, 1000);
console.log("[Rhino耗时] 区域找色: " + __formatMs(__nowMs() - findStartedAt) + " ms");
if(point){
    toastLog("x = " + point.x + ", y = " + point.y);
}else{
    toastLog("没有找到");
}
img.recycle();

