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

function toHex(value) {
    const text = Number(value).toString(16).toUpperCase();
    return text.length < 2 ? "0" + text : text;
}

/**
 * 顶部若干行的颜色（取画面水平中点）。用途：确认**截下来的图**里状态栏那一条正不正常。
 * 应用自己的状态栏是 colorPrimaryDark（亮色 #00796B / 深色 #006064），
 * 截出来的图里如果是 #000000，就说明问题在截图链路而不是界面主题。
 */
function topRowColors(frame) {
    if (!frame) {
        return null;
    }
    const width = frame.pixelWidth || frame.width;
    const height = frame.pixelHeight || frame.height;
    if (!width || !height) {
        return null;
    }
    const out = [];
    const rows = [1, 20, 60, 200];
    for (let i = 0; i < rows.length; i++) {
        if (rows[i] >= height) {
            continue;
        }
        const rgb = adapter.rgbAt(frame, Math.round(width / 2), rows[i]);
        if (!rgb) {
            continue;
        }
        out.push("y" + rows[i] + "=#" + toHex(rgb[0]) + toHex(rgb[1]) + toHex(rgb[2]));
    }
    return out;
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

// 诊断：紧接授权之后**不重试**地抓一帧 —— 用来观察「刚授权后第一帧整幅纯黑」这个平台行为。
// 日常流程不该这样抓（capture 默认会自动丢弃黑帧并重截）。
safe("首帧体检（不重试）", function () {
    const frame = adapter.capture("full", 400, { attempts: 1 });
    report.frames.rawFirst = adapter.describeFrame(frame);
    if (frame && frame.recycle) {
        frame.recycle();
    }
    return true;
});

safe("实时截图", function () {
    const frame = adapter.capture("full", 400);
    report.frames.full = adapter.describeFrame(frame);
    report.frames.fullTop = topRowColors(frame);
    if (frame && frame.recycle) {
        frame.recycle();
    }
    return true;
});

safe("快截图", function () {
    const frame = adapter.capture("fast", 400);
    report.frames.fast = adapter.describeFrame(frame);
    report.frames.fastTop = topRowColors(frame);
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
// 路径解析：脚本在 assets 里跑（应用内置示例）还是磁盘上跑（独立 APK / 已导入）会不一样，
// 这里两条都报出来，免得「明明随包了却加载不到」猜半天。
safe("模型文件", function () {
    report.model.scriptDir = adapter.scriptDir();
    report.model.modelPath = adapter.resolveProjectFile("assets/models/chessai_160.onnx");
    report.model.labelsPath = adapter.resolveProjectFile("assets/models/chessai_160.txt");
    const onnxExists = (function () {
        try {
            return globals.files && typeof globals.files.isFile === "function"
                ? globals.files.isFile(report.model.modelPath)
                : false;
        } catch (error) {
            return false;
        }
    })();
    report.model.onDisk = onnxExists;
    report.model.assetMode = !onnxExists;
    const detector = adapter.loadDetector({
        modelPath: report.model.modelPath,
        labels: report.model.labelsPath,
        inputSize: 160,
        threads: 4
    });
    report.model.loaded = !!detector;
    report.model.backend = "dnn";
    if (detector && detector.close) {
        detector.close();
    }
    report.model.note = "加载成功只说明接口可用；识别效果看「识别棋盘 → FEN」按钮";
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
