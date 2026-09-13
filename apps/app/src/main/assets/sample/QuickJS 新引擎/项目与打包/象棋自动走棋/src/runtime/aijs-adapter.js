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
        imagesRead: typeOf("images.read") === "function",
        imagesClip: typeOf("images.clip") === "function",
        imagesPixel: typeOf("images.pixel") === "function",
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

/** 小睡：优先用平台的 sleep，没有就忙等。只在「丢了黑帧要重截」这条小概率路径上用。 */
function waitMs(ms) {
    if (typeof globals.sleep === "function") {
        globals.sleep(ms);
        return;
    }
    const until = nowMono() + ms;
    while (nowMono() < until) {
        // 忙等
    }
}

/**
 * 一帧是不是「整幅纯黑」——这不是屏幕内容，而是截图管线还没出图。
 *
 * 实测（K40）：`requestScreenCapture()` 之后的**第一帧 `full` 截图整幅 0,0,0**
 * （1080×2400，上/中/下取样全是纯黑），而同一时刻的 `fast` 截图是正常的。
 * 不看这一层，定位/识别就会拿到一张全黑图然后报一堆看不懂的失败。
 *
 * 判法：取 9 个点（上/中/下 × 左/中/右）看最大亮度，**全都** ≤ threshold 才算黑。
 * 真·深色界面很少整屏 0,0,0（我们自己的深色主题也还留着 #0A0E0F、#006064 这类值），
 * 所以阈值取 3 足够保守；只要有一个点不是黑的就不当作黑帧。
 *
 * @param options.read 自定义取样函数 (frame, x, y) -> 0..255 亮度；不给就用宿主像素接口。
 *                     给这个参数是为了能离线自检（宿主接口在 PC 上不存在）。
 */
function looksBlank(frame, options) {
    const opts = options || {};
    if (!frame) {
        return false;
    }
    const width = frame.pixelWidth || frame.width;
    const height = frame.pixelHeight || frame.height;
    if (!width || !height) {
        return false;
    }
    let read = opts.read;
    if (!read) {
        if (!canSamplePixels()) {
            return false; // 取不到像素就没法判，不要凭空说它是黑的
        }
        read = function (target, x, y) {
            const pixel = globals.images.pixel(target, x, y);
            return Math.max(globals.colors.red(pixel), globals.colors.green(pixel),
                globals.colors.blue(pixel));
        };
    }
    const limit = opts.threshold == null ? 3 : opts.threshold;
    const xs = [Math.round(width * 0.25), Math.round(width * 0.5), Math.round(width * 0.75)];
    const ys = [Math.round(height * 0.02), Math.round(height * 0.5), Math.round(height * 0.98)];
    for (let i = 0; i < xs.length; i++) {
        for (let j = 0; j < ys.length; j++) {
            if (read(frame, xs[i], ys[j]) > limit) {
                return false;
            }
        }
    }
    return true;
}

/**
 * 截一帧。
 *
 * ⚠️ 会**自动丢掉纯黑帧**并重试（原因见 looksBlank）：默认最多 3 次、每次间隔 60ms。
 * 拿到非黑帧就立即返回，所以正常情况下（第二帧起）不会多花时间。
 * 三次都还是黑的话，仍然把最后一帧交出去（并在 describeFrame 里报 blank=true），
 * 不抛错 —— 因为极暗的真实界面也可能命中，调用方需要能拿到图并自己处理。
 */
function capture(mode, timeoutMs, options) {
    if (!has("captureScreen")) {
        throw new Error("缺少能力：captureScreen");
    }
    const opts = options || {};
    const attempts = opts.attempts == null ? 3 : opts.attempts;
    const gapMs = opts.gapMs == null ? 60 : opts.gapMs;
    let frame = null;
    for (let i = 0; i < attempts; i++) {
        frame = globals.captureScreen({
            mode: mode || "full",
            fresh: true,
            timeout: timeoutMs == null ? 300 : timeoutMs
        });
        if (!looksBlank(frame)) {
            return frame;
        }
        if (i < attempts - 1 && gapMs > 0) {
            waitMs(gapMs);
        }
    }
    return frame;
}

/**
 * 描述一帧的字段：区分 width/height 与 pixelWidth/pixelHeight（快截图会降采样）。
 * 没有底层源序号时如实写 null，不用调用编号充数。
 * blank 是「整幅纯黑」的体检结果 —— 自动重截之后还报 true，就说明真该看设备的截图权限/foreground 了。
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
        blank: looksBlank(frame),
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

/** 统一的检测结果形态：只保留业务需要的字段，坐标系说明见任务书 8.2。 */function normalizeDetections(raw) {
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

/** 图像采样是否可用：棋盘定位的自动路径依赖它（缺了就只能手动校准）。 */
function canSamplePixels() {
    return typeOf("images.pixel") === "function" && has("colors");
}

/**
 * 采样某点亮度 0（黑）..1（白）；取不到返回 NaN。
 * 这里只读一个像素，采样密度由调用方控制（棋盘定位会扫很多点）。
 */
function brightnessAt(frame, x, y) {
    if (!frame || !canSamplePixels()) {
        return NaN;
    }
    try {
        const pixel = globals.images.pixel(frame, Math.round(x), Math.round(y));
        return (0.299 * globals.colors.red(pixel) +
            0.587 * globals.colors.green(pixel) +
            0.114 * globals.colors.blue(pixel)) / 255;
    } catch (error) {
        return NaN;
    }
}

/**
 * 读一张磁盘图片（截图/相册导出）。
 * 用途：棋盘定位可以在**已保存的截图**上先标定，不必把棋盘摆到屏幕上来截。
 * images.pixel 对 Image 与 ScreenCapture 是同一套接口，所以 samplerFor 两者通用。
 */
function readImage(path) {
    if (typeOf("images.read") !== "function") {
        throw new Error("缺少能力：images.read");
    }
    const image = globals.images.read(path);
    if (!image) {
        throw new Error("读不到图片：" + path);
    }
    return image;
}

/** 图片（或帧）的像素尺寸；取不到返回 null。 */
function sizeOf(imageOrFrame) {
    if (!imageOrFrame) {
        return null;
    }
    const width = imageOrFrame.getWidth ? imageOrFrame.getWidth() : imageOrFrame.pixelWidth;
    const height = imageOrFrame.getHeight ? imageOrFrame.getHeight() : imageOrFrame.pixelHeight;
    if (!isFinite(width) || !isFinite(height)) {
        return null;
    }
    return { width: width, height: height };
}

/** 给 board-locator 用的采样器：闭包持有帧，采样期间帧必须有效。 */
function samplerFor(frame) {
    return function (x, y) {
        return brightnessAt(frame, x, y);
    };
}

/** 采样某点的 RGB；取不到返回 null。 */
function rgbAt(image, x, y) {
    if (!image || typeOf("images.pixel") !== "function" || !has("colors")) {
        return null;
    }
    try {
        const pixel = globals.images.pixel(image, Math.round(x), Math.round(y));
        return [globals.colors.red(pixel), globals.colors.green(pixel), globals.colors.blue(pixel)];
    } catch (error) {
        return null;
    }
}

/** 裁剪一块区域（识别时按定位出来的棋盘外框裁）。 */
function clip(image, box) {
    if (typeOf("images.clip") !== "function") {
        throw new Error("缺少能力：images.clip");
    }
    return globals.images.clip(image, box.x, box.y, box.width, box.height);
}

/**
 * 检测器封装：detector.detect 的返回还带 preprocessMs/inferenceMs/totalMs，
 * 这里统一成 { list, preprocessMs, inferenceMs, totalMs }，并把坐标留在裁剪图坐标系。
 */
function detect(detector, image, options) {
    if (!detector || typeof detector.detect !== "function") {
        throw new Error("检测器不可用（先 yolo.load）");
    }
    const opts = options || {};
    const request = {
        confidence: opts.confidence == null ? 0.25 : opts.confidence,
        nms: opts.nms == null ? 0.45 : opts.nms
    };
    if (opts.region) {
        // 只算这块区域；宿主会把检测框坐标回移到整帧坐标系（不必自己再加偏移）
        request.region = opts.region;
    }
    const raw = detector.detect(image, request);
    const items = [];
    for (let i = 0; i < (raw ? raw.length : 0); i++) {
        items.push(raw[i]);
    }
    return {
        list: items,
        length: items.length,
        preprocessMs: raw ? raw.preprocessMs : null,
        inferenceMs: raw ? raw.inferenceMs : null,
        totalMs: raw ? raw.totalMs : null
    };
}

/**
 * 把「工程内的相对路径」解析成能直接用的路径。
 * 因为工程有两种跑法：
 *  - 应用内置示例直接运行：脚本在 assets 里，模型也随包 → 要用 asset://
 *  - 打包成独立 APK（或用户导入到脚本目录）：运行端会把整棵工程拷到磁盘 → 就是普通文件
 * 这里优先用磁盘路径（存在就用），否则回退 asset://。
 */
function resolveProjectFile(relative) {
    const dir = (typeof globals.__dirname === "string" && globals.__dirname) ? globals.__dirname : ".";
    const joined = String(dir).replace(/\/+$/, "") + "/" + String(relative).replace(/^\/+/, "");
    if (typeOf("files.isFile") === "function") {
        try {
            if (globals.files.isFile(joined)) {
                return joined;
            }
        } catch (error) {
            // 忽略：落到 asset 分支
        }
    }
    return "asset://" + joined;
}

/** 当前脚本所在目录（诊断用：能看出这次是 assets 跑还是磁盘跑）。 */
function scriptDir() {
    return (typeof globals.__dirname === "string" && globals.__dirname) ? globals.__dirname : "";
}

const AIJS_ADAPTER = {
    capabilities: capabilities,
    canSamplePixels: canSamplePixels,
    brightnessAt: brightnessAt,
    samplerFor: samplerFor,
    missingRequired: missingRequired,
    has: has,
    typeOf: typeOf,
    log: log,
    nowMono: nowMono,
    projectPath: projectPath,
    requestCapture: requestCapture,
    capture: capture,
    looksBlank: looksBlank,
    waitMs: waitMs,
    readImage: readImage,
    sizeOf: sizeOf,
    rgbAt: rgbAt,
    clip: clip,
    detect: detect,
    resolveProjectFile: resolveProjectFile,
    scriptDir: scriptDir,
    describeFrame: describeFrame,
    loadDetector: loadDetector,
    normalizeDetections: normalizeDetections
};

module.exports = AIJS_ADAPTER;
