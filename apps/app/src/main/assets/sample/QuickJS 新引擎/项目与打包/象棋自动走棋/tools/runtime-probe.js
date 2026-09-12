// @engine quickjs
/**
 * P0 运行时探针：只做能力核对，**不点击棋盘、不改动任何东西**。
 *
 * 输出一行 `RUNTIME_PROBE_JSON {...}`，其余为可读日志。缺失的能力如实写成 false/null，
 * 不用 try/catch 掩盖，也不把「调用成功」当成「拿到了新帧」（当前版本没有底层帧序号）。
 */
"use strict";

const adapter = require("../src/runtime/aijs-adapter.js");

const globals = (function () {
    try {
        return Function("return this")();
    } catch (error) {
        return {};
    }
})();

const report = {
    capabilities: adapter.capabilities(),
    missingRequired: adapter.missingRequired(),
    platform: {},
    frames: {},
    model: {},
    threads: {},
    errors: []
};

function safe(label, body) {
    try {
        return body();
    } catch (error) {
        report.errors.push(label + "：" + (error && error.message ? error.message : String(error)));
        return null;
    }
}

// ---- 平台信息 ----
safe("平台信息", function () {
    report.platform.engine = "quickjs";
    try {
        report.platform.android = String(globals.android.os.Build.VERSION.RELEASE);
        report.platform.model = String(globals.android.os.Build.MODEL);
        report.platform.abi = String(globals.android.os.Build.SUPPORTED_ABIS[0]);
    } catch (error) {
        report.errors.push("android.os.Build 读取失败：" + error);
    }
    if (globals.device) {
        report.platform.screenWidth = globals.device.width;
        report.platform.screenHeight = globals.device.height;
        report.platform.density = globals.device.density;
    }
    return true;
});

// ---- 截图能力与帧字段 ----
safe("截图授权", function () {
    report.frames.granted = adapter.requestCapture("portrait");
    return true;
});

safe("实时截图", function () {
    const frame = adapter.capture("full", 400);
    report.frames.full = adapter.describeFrame(frame);
    if (frame && frame.recycle) {
        frame.recycle();
    }
    return true;
});

safe("快截图", function () {
    const frame = adapter.capture("fast", 400);
    report.frames.fast = adapter.describeFrame(frame);
    if (frame && frame.recycle) {
        frame.recycle();
    }
    return true;
});

report.frames.samePixelSize = (function () {
    const full = report.frames.full;
    const fast = report.frames.fast;
    if (!full || !fast) {
        return null;
    }
    return full.pixelWidth === fast.pixelWidth && full.pixelHeight === fast.pixelHeight;
})();

// ---- 模型 ----
safe("模型文件", function () {
    const modelsDir = adapter.projectPath("assets/models");
    report.model.directory = modelsDir;
    const names = (function () {
        try {
            if (globals.files && typeof globals.files.listDir === "function") {
                return globals.files.listDir(modelsDir);
            }
        } catch (error) {
            return null;
        }
        return null;
    })();
    report.model.files = names;
    const onnx = (names || []).filter(function (name) { return /\.onnx$/i.test(String(name)); });
    report.model.hasOnnx = onnx.length > 0;
    if (onnx.length === 0) {
        report.model.note = "工程里没有 ONNX 模型：识别链路无法实测，这不是接口缺失";
        return true;
    }
    const detector = adapter.loadDetector({
        modelPath: adapter.projectPath("assets/models/" + onnx[0]),
        labels: adapter.projectPath("assets/models/labels.txt"),
        inputSize: 160,
        threads: 4
    });
    report.model.loaded = !!detector;
    report.model.backend = "dnn";
    if (detector && detector.close) {
        detector.close();
    }
    report.model.note = "加载成功只说明接口可用，不代表模型能识别象棋棋子";
    return true;
});

// ---- 线程与消息 ----
safe("线程与消息", function () {
    report.threads.start = adapter.typeOf("threads.start") === "function";
    report.threads.exec = adapter.typeOf("threads.exec") === "function";
    report.threads.eventsBus = adapter.typeOf("events.bus.on") === "function";
    report.threads.note = "参数经 JSON 序列化；子引擎不共享闭包与 native 对象";
    return true;
});

adapter.log("probe", "capabilities=" + JSON.stringify(report.capabilities));
adapter.log("probe", "errors=" + JSON.stringify(report.errors));
console.log("RUNTIME_PROBE_JSON " + JSON.stringify(report));

module.exports = report;
