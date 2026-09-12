// @engine quickjs
/**
 * 平台适配层：**只有这里**直接调用 ai.js Pro 的宿主接口。
 *
 * 业务模块（vision / chess / automation）只用本文件导出的能力，便于：
 * 1) 在平台上做离线自检时把宿主接口整体替换成桩；
 * 2) 接口缺失时给出明确的能力名，而不是在业务代码里散落 typeof 判断。
 *
 * 注意：这里不做任何点击/滑动，也不假设存在「原生帧序号」这类未实现的能力
 * （任务书第 21.3 节要求的 sourceAcquiredAtMonoMs 属于平台待补能力）。
 */
"use strict";

const globals = (function () {
    // QuickJS：直接拿全局对象；取不到就退化成空对象（离线自检时的环境）。
    try {
        return Function("return this")();
    } catch (error) {
        return {};
    }
})();

function has(name) {
    return typeof globals[name] !== "undefined" && globals[name] !== null;
}

function typeOf(path) {
    const parts = String(path).split(".");
    let current = globals;
    for (let i = 0; i < parts.length; i++) {
        if (current == null) {
            return "undefined";
        }
        current = current[parts[i]];
    }
    return typeof current;
}

/** 能力清单：缺失的接口必须显示为缺失，不能靠 try/catch 掩盖。 */
function capabilities() {
    return {
        requestScreenCapture: has("requestScreenCapture"),
        captureScreen: has("captureScreen"),
        yoloLoad: typeOf("yolo.load") === "function",
        imagesClip: typeOf("images.clip") === "function",
        imagesFromBase64: typeOf("images.fromBase64") === "function",
        filesPath: typeOf("files.path") === "function",
        filesRead: typeOf("files.read") === "function",
        threadsStart: typeOf("threads.start") === "function",
        eventsBus: typeOf("events.bus.on") === "function",
        shell: has("shell"),
        accessibility: has("auto"),
        device: has("device"),
        ui: has("ui"),
        storages: has("storages"),
        // 任务书要求但当前版本未必有的能力，单独列出来：
        nativeFrameSourceId: false, // 帧来源序号/时间：平台尚未提供
        persistentUci: false        // 持久 UCI 会话：平台尚未提供
    };
}

function missingRequired() {
    const caps = capabilities();
    const required = ["requestScreenCapture", "captureScreen", "yoloLoad"];
    return required.filter(function (name) { return !caps[name]; });
}

function log(tag, message) {
    const text = "[xiangqi][" + tag + "] " + message;
    if (typeof globals.console !== "undefined" && globals.console && globals.console.log) {
        globals.console.log(text);
    }
    return text;
}

/** 单调时间（毫秒）：业务里的「多久之前」只用这个，不混用墙上时钟。 */
function nowMono() {
    if (typeof globals.Date !== "undefined" && globals.Date.now) {
        return globals.Date.now();
    }
    return 0;
}

function projectPath(relative) {
    if (typeof globals.files !== "undefined" && globals.files && typeof globals.files.path === "function") {
        try {
            return globals.files.path(relative);
        } catch (error) {
            return relative;
        }
    }
    return relative;
}

function requestCapture(orientation) {
    if (!has("requestScreenCapture")) {
        throw new Error("缺少能力：requestScreenCapture");
    }
    // 已知语义：返回 false 表示用户拒绝授权；真正的新帧语义要等平台补齐（见任务书 21.3）。
    return globals.requestScreenCapture(orientation || "portrait");
}

function capture(mode, timeoutMs) {
    if (!has("captureScreen")) {
        throw new Error("缺少能力：captureScreen");
    }
    const options = { mode: mode || "full", fresh: true, timeout: timeoutMs == null ? 300 : timeoutMs };
    return globals.captureScreen(options);
}

/**
 * 描述一帧的字段：区分 width/height 与 pixelWidth/pixelHeight（快截图会降采样）。
 * 没有底层源序号时如实写 null，不用调用编号冒充。
 */
function describeFrame(frame) {
    if (!frame) {
        return null;
    }
    return {
        width: frame.width,
        height: frame.height,
        pixelWidth: frame.pixelWidth,
        pixelHeight: frame.pixelHeight,
        captureMode: frame.captureMode,
        recycled: frame.recycled,
        sourceFrameId: null,
        // 当前版本没有公开的底层时间戳；这里只记录调用侧时间，别当成源时间。
        acquiredAtMono: nowMono()
    };
}

function loadDetector(options) {
    if (typeOf("yolo.load") !== "function") {
        throw new Error("缺少能力：yolo.load");
    }
    const config = options || {};
    const request = {
        backend: "dnn",
        model: config.modelPath,
        labels: config.labels,
        threads: config.threads == null ? 4 : config.threads
    };
    if (config.inputWidth && config.inputHeight) {
        request.inputWidth = config.inputWidth;
        request.inputHeight = config.inputHeight;
    } else if (config.inputSize) {
        request.inputSize = config.inputSize;
    }
    return globals.yolo.load(request);
}

/** 统一的检测结果形态：只保留业务需要的字段，坐标系说明见任务书 8.2。 */
function normalizeDetections(raw) {
    const list = [];
    for (let i = 0; i < (raw ? raw.length : 0); i++) {
        const item = raw[i];
        if (!item) {
            continue;
        }
        const bounds = item.bounds || {};
        const x = bounds.centerX == null ? item.x : bounds.centerX;
        const y = bounds.centerY == null ? item.y : bounds.centerY;
        if (!isFinite(x) || !isFinite(y)) {
            continue;
        }
        list.push({
            label: item.label,
            classId: item.classId,
            score: item.score,
            x: x,
            y: y,
            width: bounds.width,
            height: bounds.height
        });
    }
    return list;
}

const AIJS_ADAPTER = {
    capabilities: capabilities,
    missingRequired: missingRequired,
    has: has,
    typeOf: typeOf,
    log: log,
    nowMono: nowMono,
    projectPath: projectPath,
    requestCapture: requestCapture,
    capture: capture,
    describeFrame: describeFrame,
    loadDetector: loadDetector,
    normalizeDetections: normalizeDetections
};

module.exports = AIJS_ADAPTER;
