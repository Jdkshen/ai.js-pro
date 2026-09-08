function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

requestScreenCapture();
console.show();
events.observeTouch();
events.setTouchEventTimeout(30);
events.on("touch", function(point){
    var captureStartedAt = __nowMs();
    // 实时取色需要跟上画面变化；50ms 超时后自动使用最近有效帧。
    var screen = images.captureScreen(true, 50);
    var captureElapsedMs = __formatMs(__nowMs() - captureStartedAt);
    var pixelStartedAt = __nowMs();
    var c = colors.toString(images.pixel(screen, point.x, point.y));
    var pixelElapsedMs = __formatMs(__nowMs() - pixelStartedAt);
    screen.recycle();
    log("[Rhino耗时] 触摸截图: " + captureElapsedMs + " ms");
    log("[Rhino耗时] 触摸取色: " + pixelElapsedMs + " ms");
    log("(" + point.x + ", " + point.y + "): " + c);
});
