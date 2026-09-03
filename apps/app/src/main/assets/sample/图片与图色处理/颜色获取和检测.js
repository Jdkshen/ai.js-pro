function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

if(!requestScreenCapture()){
   toast("请求截图失败");
   exit
}
sleep(2000);
var x = 760;
var y = 180;
var captureStartedAt = __nowMs();
// 本例在等待用户准备画面后截图，因此主动请求一张新帧。
var screen = images.captureScreen(true, 100);
console.log("[Rhino耗时] 截图: " + __formatMs(__nowMs() - captureStartedAt) + " ms");
//获取在点(x, y)处的颜色
var pixelStartedAt = __nowMs();
var c = images.pixel(screen, x, y);
console.log("[Rhino耗时] 读取像素: " + __formatMs(__nowMs() - pixelStartedAt) + " ms");
//显示该颜色
var msg = "";
msg += "在位置(" + x + ", " + y + ")处的颜色为" + colors.toString(c);
msg += "\nR = " + colors.red(c) + ", G = " + colors.green(c) + ", B = " + colors.blue(c);
//检测在点(x, y)处是否有颜色#73bdb6 (模糊比较)
var detectStartedAt = __nowMs();
var isDetected = images.detectsColor(screen, "#73bdb6", x, y);
console.log("[Rhino耗时] 颜色检测: " + __formatMs(__nowMs() - detectStartedAt) + " ms");
msg += "\n该位置是否匹配到颜色#73bdb6: " + isDetected;
screen.recycle();
alert(msg);
