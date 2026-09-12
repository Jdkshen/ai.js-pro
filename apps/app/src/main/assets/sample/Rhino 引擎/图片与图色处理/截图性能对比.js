// @engine rhino
// AI.js Pro（Rhino）与 Auto.js Pro 通用截图基准。
// 两边必须在同一设备、相同屏幕分辨率和刷新率下分别运行。

if (!requestScreenCapture()) {
    throw new Error("需要先授权屏幕捕获");
}

var ROUNDS = 15;
var OUTPUT_PATH = "/sdcard/脚本/aijspro_capture_compare_result.txt";
var outputLines = [];

function emit(message) {
    outputLines.push(String(message));
    console.log(message);
}

function nowMs() {
    return Number(java.lang.System.nanoTime()) / 1000000;
}

function captureOnce() {
    var startedAt = nowMs();
    var image = captureScreen();
    var elapsedMs = nowMs() - startedAt;
    var dimensions = image.getWidth() + "x" + image.getHeight();
    image.recycle();
    return { elapsedMs: elapsedMs, dimensions: dimensions };
}

function percentile(sorted, ratio) {
    var index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
    return sorted[Math.max(0, index)];
}

function summarize(name, values, dimensions) {
    var sorted = values.slice().sort(function (left, right) { return left - right; });
    var total = 0;
    for (var i = 0; i < values.length; i++) total += values[i];
    emit("[AIJSPRO_COMPARE][" + name + "] 尺寸: " + dimensions);
    emit("[AIJSPRO_COMPARE][" + name + "] 每帧: " + values.map(function (value) {
        return value.toFixed(3);
    }).join(", ") + " ms");
    emit("[AIJSPRO_COMPARE][" + name + "] 平均: " + (total / values.length).toFixed(3) +
        " ms，最小: " + sorted[0].toFixed(3) +
        " ms，P95: " + percentile(sorted, 0.95).toFixed(3) +
        " ms，最大: " + sorted[sorted.length - 1].toFixed(3) + " ms");
}

function benchmark(name, paced) {
    sleep(30);
    captureOnce();
    var values = [];
    var dimensions = "";
    for (var i = 0; i < ROUNDS; i++) {
        if (paced) sleep(20);
        var result = captureOnce();
        dimensions = result.dimensions;
        values.push(result.elapsedMs);
    }
    summarize(name, values, dimensions);
}

emit("========== AIJSPRO_COMPARE START ==========");
var cold = captureOnce();
emit("[AIJSPRO_COMPARE][首次截图] " + cold.dimensions + "，耗时: " + cold.elapsedMs.toFixed(3) + " ms");
benchmark("缓存帧", true);
benchmark("连续截图", false);
emit("========== AIJSPRO_COMPARE END ==========");
files.write(OUTPUT_PATH, outputLines.join("\n") + "\n");
toast("截图对比完成：" + OUTPUT_PATH);
