function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

if(!requestScreenCapture()){
    toast("请求截图失败");
    exit();
}
var captureStartedAt = __nowMs();
var img = captureScreen();
console.log("[Rhino耗时] 截图: " + __formatMs(__nowMs() - captureStartedAt) + " ms");
//0xffffff为白色
toastLog("开始找色");
//指定在位置(90, 220)宽高为900*1000的区域找色。
//0xff00cc是编辑器的深粉红色字体(字符串)颜色
var findStartedAt = __nowMs();
var point = findColor(img, "#ff00cc", {
    region: [90, 220, 900, 1000],
    threads: 8
});
console.log("[Rhino耗时] 多线程区域找色: " + __formatMs(__nowMs() - findStartedAt) + " ms");
if(point){
    toastLog("x = " + point.x + ", y = " + point.y);
}else{
    toastLog("没有找到");
}
img.recycle();
