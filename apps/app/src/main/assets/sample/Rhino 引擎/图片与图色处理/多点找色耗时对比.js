// @engine rhino
function __nowMs() { return Number(java.lang.System.nanoTime()) / 1000000; }
function __formatMs(value) { return Number(value).toFixed(3); }

// 默认 Rhino 引擎版本，用于和“QuickJS 新引擎/图色处理/02”对比。
if (!requestScreenCapture()) {
    throw new Error("需要先授权屏幕捕获");
}

var captureStartedAt = __nowMs();
var frame = captureScreen();
console.log("[Rhino耗时] 截图: " + __formatMs(__nowMs() - captureStartedAt) + " ms");

try {
    var findColorStartedAt = __nowMs();
    var green = images.findColor(frame, "#00C853", {
        region: [0, 0, frame.width, Math.floor(frame.height / 2)],
        threshold: 24
    });
    console.log("[Rhino耗时] 单点找色: " + __formatMs(__nowMs() - findColorStartedAt) + " ms");
    console.log("[Rhino结果] 单点找色: " + green);

    var multiStartedAt = __nowMs();
    var button = images.findMultiColors(frame, "#2196F3", [
        [20, 0, "#2196F3"],
        [0, 20, "#2196F3"],
        [20, 20, "#2196F3"]
    ], {
        region: [0, 0, frame.width, frame.height],
        threshold: 20
    });
    console.log("[Rhino耗时] 多点找色: " + __formatMs(__nowMs() - multiStartedAt) + " ms");
    console.log("[Rhino结果] 多点找色: " + button);
    console.log("[Rhino结果] 截图尺寸: " + frame.width + "x" + frame.height);
} finally {
    frame.recycle();
}
