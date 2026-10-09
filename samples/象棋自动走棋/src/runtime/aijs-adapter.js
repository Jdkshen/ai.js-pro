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

/**
 * 截图通道偏好：`auto`（默认：先系统截屏，失败自动退到 shell）| `screen`（只用系统截屏）| `shell`（只用 screencap）。
 * 模块级状态，因为截图是在识别链路深处调的，没法逐次传 UI 选择。
 */
var captureBackendKey = "auto";
/** 最近一次**真正用上**的通道：`screen` | `shizuku` | `root`（界面/日志要看这个，别只看偏好） */
var lastCaptureChannel = null;
var windowCapturePackage = null;
var lastWindowCapture = null;
var windowCaptureGeometryKey = null;
var windowCaptureNeedsCalibration = false;
/** auto 退到 shell 后暂时不再碰坏掉的系统截屏；到期试一帧，成功就切回。 */
var autoScreenRetryAt = 0;
var AUTO_SCREEN_RETRY_MS = 15000;

/** 截图通道偏好的持久化（关机/重启脚本后不用重选）。取不到 storages 就退化成内存。 */
const CAPTURE_STORE = "xiangqi-capture";
const CAPTURE_STORE_KEY = "backend";
function captureStore() {
    try {
        if (typeOf("storages.create") !== "function") {
            return null;
        }
        return globals.storages.create(CAPTURE_STORE);
    } catch (error) {
        return null;
    }
}

/**
 * 设截图通道偏好。
 * @param key "auto" | "screen" | "shell"
 * @param options.persist true（界面上的选择，默认）| false（autorun/测试钩子，不写盘）
 */
function setCaptureBackend(key, options) {
    const value = String(key || "auto");
    captureBackendKey = (value === "screen" || value === "shell" || value === "window") ? value : "auto";
    windowCapturePackage = null;
    lastWindowCapture = null;
    windowCaptureGeometryKey = null;
    windowCaptureNeedsCalibration = false;
    autoScreenRetryAt = 0;
    if (!options || options.persist !== false) {
        const store = captureStore();
        if (store) {
            try {
                store.put(CAPTURE_STORE_KEY, captureBackendKey);
            } catch (error) {
                // 写不进去就算了：内存里的选择仍然生效
            }
        }
    }
    return captureBackendKey;
}

/** 启动时把上次选的通道读回来（面板/主界面都靠它保持一致）。 */
(function restoreCaptureBackend() {
    const store = captureStore();
    if (!store) {
        return;
    }
    try {
        const saved = store.get(CAPTURE_STORE_KEY, null);
        if (saved === "auto" || saved === "screen" || saved === "shell" || saved === "window") {
            captureBackendKey = saved;
        }
    } catch (error) {
        // 读不到就用默认
    }
})();

function captureBackend() {
    return captureBackendKey;
}

function captureChannel() {
    return lastCaptureChannel;
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
        captureWindow: typeOf("images.captureWindow") === "function" &&
            typeOf("images.isWindowCaptureAvailable") === "function" && globals.images.isWindowCaptureAvailable() === true,
        // shell 截图（Shizuku/root 跑 screencap）：MediaProjection 会话被系统移除后的救生通道
        captureShell: shellCaptureBackend() !== null,
        captureShellBackend: shellCaptureBackend(),
        yoloLoad: typeOf("yolo.load") === "function",
        imagesRead: typeOf("images.read") === "function",
        /** raw 快通道（`screencap` 不加 -p + `images.readRaw`）：PNG 那条路 893ms、这条 194ms（K40 实测） */
        imagesReadRaw: typeOf("images.readRaw") === "function",
        imagesClip: typeOf("images.clip") === "function",
        imagesPixel: typeOf("images.pixel") === "function",
        /** 引擎找色（红黑判色走它；没有就只能退回逐点采样） */
        countColor: typeOf("images.findAllPointsForColor") === "function",
        /** 原生计数（`images.countColor`，无对象分配）：找色类判定的快通道 */
        countColorNative: typeOf("images.countColor") === "function",
        analyzeXiangqiBoard: typeOf("images.analyzeXiangqiBoard") === "function",
        detectXiangqiCircles: typeOf("images.detectXiangqiCircles") === "function",
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
        // 点击能力（P4）：press 优先（能配按压时长），click 兑底；
        // tap 为 true 只代表接口存在，**能不能真的点下去**还要看无障碍服务是否就绪
        press: has("press"),
        click: has("click"),
        tap: has("press") || has("click"),
        accessibilityReady: !!(globals.auto && globals.auto.service),
        // Shizuku 特权 shell（可跑 `input tap`）：有些游戏会**忽略无障碍注入的事件**
        // （实测腾讯天天象棋：click/press/swipe/longClick 都返回 true 但画面零变化，
        // 而 `input tap` 能正常落子）→ 这条通道是必需的备选。
        shizuku: shizukuReady(),
        drawing: typeOf("drawing.show") === "function",
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
    if (captureBackendKey === "window") {
        if (!capabilities().captureWindow) throw new Error("窗口截图需要新版宿主、Android 14+ 和无障碍服务");
        return true;
    }
    // 走 shell 截图（Shizuku/root 的 screencap）时**根本不需要**系统截屏授权：
    // 这里直接返回 true，免得白白弹出授权界面（弹了也不影响，只是多余）。
    if (captureBackendKey === "shell") {
        return true;
    }
    if (!has("requestScreenCapture")) {
        if (captureBackendKey === "auto" && shellCaptureBackend() !== null) {
            return true; // 没有系统截屏接口，但 auto 还有 shell 通道
        }
        throw new Error("缺少能力：requestScreenCapture");
    }
    // 已知语义：返回 false 表示用户拒绝授权；真正的新帧语义要等平台补齐（见任务书 21.3）。
    const granted = globals.requestScreenCapture(orientation || "portrait");
    if (granted === false && captureBackendKey === "auto" && shellCaptureBackend() !== null) {
        // 授权被拒/系统不给（MIUI 上常见）：auto 模式还能退到 shell 通道，
        // 所以不要在这里让整条链路提前失败。
        log("capture", "系统截屏授权失败，auto 模式将改用 shell 通道（screencap）");
        autoScreenRetryAt = nowMono() + AUTO_SCREEN_RETRY_MS;
        return true;
    }
    return granted;
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
            // 传进来的不是真帧（比如离线自检里的普通对象）时宿主会抛错，
            // 这时应该老实说「判不了」，而不是把异常抛给调用方。
            try {
                const pixel = globals.images.pixel(target, x, y);
                if (pixel === undefined || pixel === null) {
                    return null;
                }
                return Math.max(globals.colors.red(pixel), globals.colors.green(pixel),
                    globals.colors.blue(pixel));
            } catch (error) {
                return null;
            }
        };
    }
    const limit = opts.threshold == null ? 3 : opts.threshold;
    const xs = [Math.round(width * 0.25), Math.round(width * 0.5), Math.round(width * 0.75)];
    const ys = [Math.round(height * 0.02), Math.round(height * 0.5), Math.round(height * 0.98)];
    for (let i = 0; i < xs.length; i++) {
        for (let j = 0; j < ys.length; j++) {
            const value = read(frame, xs[i], ys[j]);
            if (value === null || value === undefined) {
                return false; // 取不到像素就没法判，不要凭空说它是黑的
            }
            if (value > limit) {
                return false;
            }
        }
    }
    return true;
}

/**
 * shell 截图的落盘路径：**必须是 shell 身份能写的地方**（应用内部目录 shell 写不了）。
 * 用两个文件轮流写（ping-pong）：避免宿主按路径缓存图片导致拿到上一帧。
 */
var SHELL_CAPTURE_FILES = ["/sdcard/aijspro-capture-0.png", "/sdcard/aijspro-capture-1.png"];
var shellCaptureIndex = 0;
/** raw（`screencap` 不加 `-p`）那条快通道的文件；也是 ping-pong */
var SHELL_RAW_FILES = ["/sdcard/aijspro-capture-0.raw", "/sdcard/aijspro-capture-1.raw"];
var shellRawIndex = 0;
/**
 * raw 快通道到底能不能用（null=还没试过）。
 * 为什么要记住：老引擎没有 `images.readRaw`、或者某台设备 raw 尺寸/头对不上 ——
 * 那种情况下**不要每帧都白试一次**（一次失败 = 多花 200ms 的 screencap）。
 */
var shellRawSupported = null;
var shellRawNote = "";
/**
 * 用 `screencap`（**不加 `-p`，原始 RGBA**）抓一帧再读回来。
 * 为什么快（真机 K40 实测 2026-09-20）：`screencap -p` 的 **893ms** 里 ~700ms 是设备端 PNG 编码，
 * raw 只要 **194ms** + 建帧 ~十几 ms —— 而 `images.readRaw` 就是为它加的。
 * ⚠️ 失败必须能干净地退回 PNG：所以这里只负责写文件+读帧，不处理回退（调用方做）。
 */
function captureViaShellRaw(backend, shellOptions) {
    if (typeOf("images.readRaw") !== "function") {
        shellRawSupported = false;
        shellRawNote = "引擎没有 images.readRaw（旧 .so）";
        return null;
    }
    const size = screenSize();
    if (!size) {
        shellRawSupported = false;
        shellRawNote = "拿不到 device.width/height（raw 必须知道屏幕像素尺寸）";
        return null;
    }
    const path = SHELL_RAW_FILES[shellRawIndex % SHELL_RAW_FILES.length];
    shellRawIndex += 1;
    const result = globals.shell("screencap " + path, shellOptions);
    if (!result || result.code !== 0) {
        shellRawSupported = false;
        shellRawNote = "screencap（raw）失败：exit " + (result ? result.code : "无结果");
        return null;
    }
    const frame = globals.images.readRaw(path, size.width, size.height);
    if (!frame) {
        shellRawSupported = false;
        shellRawNote = "images.readRaw 没读回帧";
        return null;
    }
    shellRawSupported = true;
    shellRawNote = "";
    return frame;
}

/**
 * 能用 shell 截图吗？返回 "shizuku" / "root" / null。
 * shell 身份的 `screencap` 等同 `adb shell screencap`，不需要 MediaProjection 授权、
 * 也不会被系统在前后台切换时移除 —— 这是 MediaProjection 挂掉后的救生通道。
 */
function shellCaptureBackend() {
    if (shizukuReady()) {
        return "shizuku";
    }
    if (typeOf("shell.isRootAvailable") === "function") {
        try {
            if (globals.shell.isRootAvailable() === true) {
                return "root";
            }
        } catch (error) {
            // 判不了就当没有
        }
    }
    return null;
}

/**
 * 用 shell 身份抓一帧：**先试 raw 快通道，失败退回 `screencap -p`（PNG）**。
 *
 * 两条路的实测（K40 1080×2400，各 3 次取中位数）：
 *   · `screencap -p` + `images.read`：**893ms + 56ms**（PNG 编码占 ~700ms）
 *   · `screencap`（raw）+ `images.readRaw`：**194ms + ~15ms**
 * 两条都稳定（不需要授权、不会被切前后台作废）；raw 只在「引擎没能力 / 尺寸对不上」时才退回 PNG。
 */
function captureViaShell(options) {
    const opts = options || {};
    const backend = opts.shellBackend || shellCaptureBackend();
    if (!backend) {
        throw new Error("没有 Shizuku/root，无法用 shell 截图（screencap 需要 shell 身份）");
    }
    if (!has("images") || typeOf("images.read") !== "function") {
        throw new Error("缺少能力：images.read（读不回截图文件）");
    }
    const shellOptions = { timeout: opts.shellTimeoutMs == null ? 12000 : opts.shellTimeoutMs };
    shellOptions[backend] = true;
    // ① 快通道：raw（除非已经证实这台设备上不行）
    if (opts.preferRaw === false) {
        shellRawSupported = false;
        shellRawNote = "调用方显式要求 PNG（options.preferRaw=false）";
    }
    if (shellRawSupported !== false) {
        const fast = captureViaShellRaw(backend, shellOptions);
        if (fast) {
            // ⚠️ 通道名必须**以 shell 开头**（面板靠 `channel.indexOf("shell") === 0` 分流），
            //    但又要把 raw/png 标出来（否则看不出走的哪条路）
            lastCaptureChannel = "shell(" + backend + "/raw)";
            return fast;
        }
        if (opts.preferRaw === true) {
            throw new Error("raw 抓帧失败：" + shellRawNote);
        }
    }
    // ② 回退：PNG
    const files = opts.paths || SHELL_CAPTURE_FILES;
    const path = files[shellCaptureIndex % files.length];
    shellCaptureIndex += 1;
    const result = globals.shell("screencap -p " + path, shellOptions);
    if (!result || result.code !== 0) {
        throw new Error("shell 截图失败：exit " + (result ? result.code : "无结果") + " " +
            String((result && result.error) || "").trim().slice(0, 80));
    }
    const frame = globals.images.read(path);
    if (!frame) {
        throw new Error("shell 截图读不回来：" + path);
    }
    lastCaptureChannel = "shell(" + backend + "/png)";
    return frame;
}

/**
 * 截一帧。
 *
 * **两条通道**（`options.backend` / `setCaptureBackend()`）：
 *   - `screen`：宿主的 `captureScreen()`（MediaProjection）—— 快（几毫秒），但要用户授权，
 *     而且**会话很脆**：MIUI 会在前台/前台服务变化时移除它（`removeMediaProjection`），
 *     之后每一帧都是 `Timed out waiting for the first screen capture frame`（真机实测连续 31s 全失败）；
 *   - `shell`：**用 Shizuku / root 跑 `screencap -p` 再读回图片** —— 慢（K40 实测约 1.2s/帧），
 *     但**不需要任何授权、也不会被系统悄悄作废**（2026-09-13 真机实测：连续多次都拿到真实屏）。
 *
 * 默认 `auto`：先用 `screen`（便宜），失败就改走 `shell`（有 Shizuku/root 才行）；
 * shell 使用期间每隔一段时间探一次 screen，恢复后自动切回。若系统撤销了截图授权，
 * 探测不能替用户重新授权；此时继续用 shell，直到用户重新授予权限。
 *
 * ⚠️ 还会**自动丢掉纯黑帧**并重试（原因见 looksBlank）：默认最多 3 次、每次间隔 60ms。
 * 拿到非黑帧就立即返回，所以正常情况下（第二帧起）不会多花时间。
 * 三次都还是黑的话，仍然把最后一帧交出去（并在 describeFrame 里报 blank=true），
 * 不抛错 —— 因为极暗的真实界面也可能命中，调用方需要能拿到图并自己处理。
 */
function capture(mode, timeoutMs, options) {
    const opts = options || {};
    const backend = opts.backend || captureBackendKey;
    if (backend === "window") {
        const captureStarted = Date.now();
        if (!capabilities().captureWindow) throw new Error("窗口截图不可用：需要 Android 14+ 和无障碍服务");
        const captured = globals.images.captureWindow({ packageName: opts.packageName || windowCapturePackage || "",
            windowId: opts.windowId, screenCoordinates: true, timeout: timeoutMs == null ? 2000 : timeoutMs });
        if (!captured || !captured.image || captured.isolated !== true || captured.coordinateSpace !== "screen") {
            if (captured && captured.image) releaseFrame(captured.image);
            throw new Error("窗口截图未返回隔离的屏幕坐标帧");
        }
        const b = captured.bounds;
        const geometryKey = [captured.windowId, captured.packageName, b.left, b.top, b.right, b.bottom, captured.rotation].join(",");
        if (windowCaptureNeedsCalibration || (windowCaptureGeometryKey && geometryKey !== windowCaptureGeometryKey)) {
            windowCaptureNeedsCalibration = true;
            releaseFrame(captured.image);
            throw new Error("目标窗口位置或方向已改变，请点定位校准；旧坐标已停用");
        }
        windowCaptureGeometryKey = geometryKey;
        windowCapturePackage = captured.packageName;
        lastWindowCapture = { windowId:captured.windowId, packageName:captured.packageName, bounds:captured.bounds,
            frameId:captured.frameId, timestamp:captured.timestamp, rotation:captured.rotation,
            captureMs:Date.now() - captureStarted };
        lastCaptureChannel = "window(" + captured.packageName + ")";
        return captured.image;
    }
    const wantShell = backend === "shell";
    const wantScreen = backend === "screen";
    const auto = !wantShell && !wantScreen;
    const retryMs = opts.autoRetryMs == null ? AUTO_SCREEN_RETRY_MS : Math.max(0, Number(opts.autoRetryMs) || 0);

    if (wantShell) {
        return captureViaShell(opts);
    }
    if (auto && autoScreenRetryAt > nowMono() && shellCaptureBackend() !== null) {
        try {
            return captureViaShell(opts);
        } catch (error) {
            // 备用通道也坏了，立刻再试主通道，不必空等到探测期限。
            autoScreenRetryAt = 0;
        }
    }
    if (!has("captureScreen")) {
        if (wantScreen) {
            throw new Error("缺少能力：captureScreen");
        }
        const fallback = captureViaShell(opts);
        autoScreenRetryAt = nowMono() + retryMs;
        return fallback;
    }

    // 退到 shell 后的恢复探测只试一帧，免得每 15 秒再卡住好几秒。
    const recoveryProbe = auto && autoScreenRetryAt > 0;
    const attempts = opts.attempts == null ? (recoveryProbe ? 1 : 3) : opts.attempts;
    const gapMs = opts.gapMs == null ? 60 : opts.gapMs;
    const base = timeoutMs == null ? 600 : timeoutMs;
    const ladder = opts.timeouts || [base, Math.max(base, 1500), Math.max(base, 3000)];
    let frame = null;
    let lastError = null;
    for (let i = 0; i < attempts; i++) {
        const limit = ladder[Math.min(i, ladder.length - 1)];
        try {
            frame = globals.captureScreen({
                mode: mode || "full",
                fresh: true,
                timeout: limit
            });
            lastError = null;
        } catch (error) {
            frame = null;
            lastError = error;
        }
        if (frame && !looksBlank(frame)) {
            if (recoveryProbe) {
                log("capture", "系统截屏已恢复，自动切回 screen 通道");
            }
            autoScreenRetryAt = 0;
            lastCaptureChannel = "screen";
            return frame;
        }
        if (i < attempts - 1 && gapMs > 0) {
            waitMs(gapMs);
        }
    }
    if (!frame || looksBlank(frame)) {
        const message = !frame ? ("截屏连续 " + attempts + " 次失败：" +
            (lastError && lastError.message ? lastError.message : String(lastError)))
            : ("截屏连续 " + attempts + " 次都是黑帧");
        // MediaProjection 会话被系统移除后就再也出不了帧 —— 有 Shizuku/root 就直接换通道，
        // 而不是把这个错误抛给上层（上层只会当成「这一轮没看清」，永远恢复不了）。
        if (auto && opts.allowShellFallback !== false && shellCaptureBackend() !== null) {
            try {
                const fallback = captureViaShell(opts);
                if (fallback) {
                    autoScreenRetryAt = nowMono() + retryMs;
                    if (!recoveryProbe) {
                        log("capture", "系统截屏失败，已自动改用 " + lastCaptureChannel + " 通道；稍后探测恢复");
                    }
                    return fallback;
                }
            } catch (error) {
                throw new Error(message + "；改用 shell 截图也失败：" +
                    (error && error.message ? error.message : String(error)));
            }
        }
        if (wantScreen) {
            throw new Error(message + "；当前选择了只用系统截屏，请重新授权或选自动通道");
        }
        throw new Error(message + "；这台设备没有可用的 Shizuku/root 截图通道，请重新授权系统截屏");
    }
    lastCaptureChannel = "screen";
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
/**
 * 读一张图；**也可以直接传已经拿到的帧**（原样返回）。
 * 为什么加「帧直通」（2026-09-20）：raw 快通道（`images.readRaw`）的帧没有磁盘路径，
 * 而 `locate-board.locateBoardInImage()` 原来是「拿路径再读一次」—— 传帧就不用再解码一次（省 ~56ms）。
 */
function readImage(source) {
    if (source && typeof source === "object" && typeof source.width === "number") {
        return source;      // 已经是帧
    }
    if (typeOf("images.read") !== "function") {
        throw new Error("缺少能力：images.read");
    }
    const image = globals.images.read(source);
    if (!image) {
        throw new Error("读不到图片：" + source);
    }
    return image;
}

/** 图片（或帧）的像素尺寸；取不到返回 null。 */
function sizeOf(imageOrFrame) {
    if (!imageOrFrame) {
        return null;
    }
    // NativeFrame 的 width/height 是逻辑坐标；fast 截图的 pixelWidth/Height 是缩放后的存储尺寸。
    // images.pixel/countColor/clip 都按逻辑坐标接参，棋盘几何必须使用同一坐标系。
    const width = imageOrFrame.getWidth ? imageOrFrame.getWidth()
        : (typeof imageOrFrame.width === "number" ? imageOrFrame.width : imageOrFrame.pixelWidth);
    const height = imageOrFrame.getHeight ? imageOrFrame.getHeight()
        : (typeof imageOrFrame.height === "number" ? imageOrFrame.height : imageOrFrame.pixelHeight);
    if (!isFinite(width) || !isFinite(height)) {
        return null;
    }
    return { width: width, height: height };
}

/** 本地 OpenCV 棋盘分析。旧版宿主没有该接口时返回 null，交给格线定位回退。 */
function analyzeXiangqiBoard(frame) {
    if (!frame || typeOf("images.analyzeXiangqiBoard") !== "function") return null;
    try {
        return globals.images.analyzeXiangqiBoard(frame);
    } catch (error) {
        return { ok: false, reason: String(error) };
    }
}

function detectXiangqiCircles(frame, grid) {
    if (!frame || !grid || typeOf("images.detectXiangqiCircles") !== "function") return null;
    try {
        return globals.images.detectXiangqiCircles(frame, grid);
    } catch (error) {
        return null;
    }
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

/**
 * 数「区域内与目标色相近的像素个数」——**原生计数**（`images.countColor`；旧引擎回退找色点数组）。
 *
 * 为什么不用逐点 `rgbAt`：一个棋子中心区 ≈ 68×68 ≈ 4600 个点，逐点在 JS 里问一遍要几十毫秒。
 * 为什么优先 `images.countColor`（2026-09-24 新增）：`findAllPointsForColor` 会为**每个命中点**
 * 在 JS 侧建一个对象（大面积高命中时是分配风暴；象棋字形识别的门实测被它拖到 ~60ms），
 * countColor 是纯原生计数、不建任何对象；两者都在 `modules/engine/src/main/cpp/native_frame_store.cpp`。
 *
 * @param image  帧（与 `images.pixel` 同一个对象）
 * @param color  参考色（'#RRGGBB' / 0xAARRGGBB）
 * @param options { region: {x,y,width,height} 或 [x,y,w,h], threshold: 每通道容差 }
 * @return 命中像素数；宿主没有任何计数能力时返回 **null**（调用方据此回退到采样通道）
 */
function countColor(image, color, options) {
    if (!image) {
        return null;
    }
    const opts = options || {};
    const region = opts.region || null;
    const regionArray = region
        ? [region.x != null ? region.x : region[0], region.y != null ? region.y : region[1],
            region.width != null ? region.width : region[2],
            region.height != null ? region.height : region[3]]
        : null;
    const request = {
        region: regionArray,
        threshold: opts.threshold == null ? 24 : opts.threshold
    };
    // 原生计数（`images.countColor`，无对象分配）优先：findAllPointsForColor 会为**每个命中点**
    // 建一个 JS 对象，大面积高命中数时开销远大于扫描本身（象棋字形识别的门就是被它拖到 ~60ms）。
    if (typeOf("images.countColor") === "function") {
        try {
            const count = globals.images.countColor(image, color, request);
            return count == null ? null : count;
        } catch (error) {
            // 落到下面的回退（旧引擎/异常）
        }
    }
    if (typeOf("images.findAllPointsForColor") !== "function") {
        return null;
    }
    try {
        const points = globals.images.findAllPointsForColor(image, color, request);
        return points ? points.length : 0;
    } catch (error) {
        return null;
    }
}

/**
 * 给 OCR 做预处理：把格子/整盘先裁出来，可选再转灰度。**返回的帧用完必须 releaseFrame()**。
 *
 * 为什么要有这个能力（真机实测 2026-09-20，`tools/ocr-prep-probe.js`，同一帧 20 格 × 10 种预处理）：
 *   · `clip`（只裁剪，不处理）与「整帧 + region」结果**逐格完全一致**（文字/分数 20/20 相同），
 *     但每格少拷约 10MB（1080×2400 RGBA）⇒ 31ms → 25ms；
 *   · `gray`（灰度）在**认不出的格子**上救回一批字形：红帅那格彩色读「印市」0.49、灰度读「師」0.70
 *     （「師」已在 piece-ocr 的字形表里收成 K）；士 0.57→0.82、將 0.83→0.98；
 *   · 代价是**也会让一部分原本读对的格子读错**（馬 1.00→志 0.67、兵 0.80→民 0.39）
 *     ⇒ 所以灰度**只能当「彩色读不出来时的重试」，不能当默认通道**；
 *   · 二值化（Otsu 各档）整体**更差**（识字 3~12/20 vs 17/20）、放大不加分还慢、
 *     `inRange` 在这条路上基本无效 —— 都不要用。
 *
 * @param image 整帧
 * @param region [x, y, width, height]（整帧坐标）
 * @param mode "clip" | "gray"
 * @return NativeFrame（调用方负责 releaseFrame）；能力缺失或区域越界时返回 **null**
 */
function prepareRegion(image, region, mode) {
    if (!image || !region) {
        return null;
    }
    if (typeOf("images.clip") !== "function") {
        return null;
    }
    const x = Math.round(Number(region[0]));
    const y = Math.round(Number(region[1]));
    const width = Math.round(Number(region[2]));
    const height = Math.round(Number(region[3]));
    if (!(width > 0 && height > 0)) {
        return null;
    }
    let clipped = null;
    try {
        clipped = globals.images.clip(image, x, y, width, height);
    } catch (error) {
        return null;      // 区域越界（棋盘边上的格子很常见）⇒ 让调用方走原来的「整帧 + region」
    }
    if (!clipped || mode === "clip") {
        return clipped;
    }
    if (mode !== "gray" || typeOf("images.grayscale") !== "function") {
        releaseFrame(clipped);
        return null;
    }
    let gray = null;
    try {
        gray = globals.images.grayscale(clipped);
    } catch (error) {
        gray = null;
    }
    releaseFrame(clipped);
    return gray;
}

/** 释放 `prepareRegion` 造出来的临时帧（原生帧不会 GC，不还就堆在帧表里）。 */
function releaseFrame(frame) {
    if (!frame || typeof frame.recycle !== "function") {
        return false;
    }
    try {
        return frame.recycle() !== false;
    } catch (error) {
        return false;
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
        // 2026-10-05 对齐基础示例口径：0.001/0.001（拉满召回；宿主只校验 nms 取值、不参与解码）
        confidence: opts.confidence == null ? 0.001 : opts.confidence,
        nms: opts.nms == null ? 0.001 : opts.nms
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
 * 工程有两种跑法：
 *  - 从脚本目录（或打包成独立 APK 后的运行端）跑：整棵工程在磁盘上 → 就是普通文件
 *  - 单文件/半独立脚本（__dirname 指向工程外）：回退到 asset://
 *    （⚠️ 「象棋自动走棋」已从主 APK 移出，随「象棋引擎插件」分发，所以 asset:// 兜底
 *     只对仍在 APK 里的工程有效；本工程装机后请用 xq.installProject() 还原到脚本目录。）
 * 这里优先用磁盘路径（存在就用），否则回退 asset://。
 */
function resolveProjectFile(relative) {
    const dir = (typeof globals.__dirname === "string" && globals.__dirname) ? globals.__dirname : ".";
    const clean = String(relative).replace(/^\/+/, "");
    // 从脚本目录开始**逐级向上**找：工程里的 tools/ 下的脚本（__dirname = …/tools）
    // 也要能按「工程内相对路径」找到 assets/models/…，否则会解析成 …/tools/assets/… 而失败。
    let base = String(dir).replace(/\/+$/, "");
    for (let level = 0; level < 4 && base; level++) {
        const joined = base + "/" + clean;
        if (typeOf("files.isFile") === "function") {
            try {
                if (globals.files.isFile(joined)) {
                    return joined;
                }
            } catch (error) {
                // 忽略：继续往上或落到 asset 分支
            }
        }
        const slash = base.lastIndexOf("/");
        if (slash <= 0) {
            break;
        }
        base = base.slice(0, slash);
    }
    return "asset://" + String(dir).replace(/\/+$/, "") + "/" + clean;
}

/** 当前脚本所在目录（诊断用：能看出这次是 assets 跑还是磁盘跑）。 */
function scriptDir() {
    return (typeof globals.__dirname === "string" && globals.__dirname) ? globals.__dirname : "";
}

/**
 * Shizuku 可用且已授权吗？（可用时 `shell(cmd, {shizuku:true})` 以 shell 身份执行）
 */
function shizukuReady() {
    if (typeOf("shizuku.isAvailable") !== "function" || typeOf("shizuku.hasPermission") !== "function") {
        return false;
    }
    try {
        return globals.shizuku.isAvailable() === true && globals.shizuku.hasPermission() === true;
    } catch (error) {
        return false;
    }
}

/**
 * 跑一条 shell 命令（Shizuku / root），返回 stdout 文本；都没有就返回 null。
 * 用途见 foregroundPackage / bringToFrontByRecents。
 */
function runShellCapture(command, timeoutMs) {
    const backend = shellCaptureBackend();
    if (!backend) {
        return null;
    }
    const options = { timeout: timeoutMs == null ? 4000 : timeoutMs };
    options[backend] = true;
    try {
        const result = globals.shell(command, options);
        if (!result || result.code !== 0) {
            return null;
        }
        return String(result.result || result.output || result.stdout || "");
    } catch (error) {
        return null;
    }
}

/**
 * 列出「覆盖屏幕上某点的窗口」（诊断用，§10.2）。
 *
 * 为什么要它：注入的触摸落在**最上面的那个可触摸窗口**上 —— 如果目标坐标那一带压着别人的
 * 窗口（包括我们自己的悬浮窗），游戏就收不到点击，而日志上只会看到「接口调用成功」。
 * ⚠️ 只做粗解析（`Window{…}` / `mFrame=` / `isVisible=` 三样），拿不到就返回空数组 ——
 *    空数组只能说明「没查到」，不能当作「确认没遮挡」。
 */
function windowsAt(x, y) {
    const text = runShellCapture("dumpsys window windows", 5000);
    if (!text) {
        return [];
    }
    const lines = String(text).split("\n");
    const all = [];
    let current = null;
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const head = line.match(/Window\{[0-9a-f]+ u0 ([^ }]+)\}/);
        if (head) {
            if (current) {
                all.push(current);
            }
            current = { name: head[1], frame: null, visible: null };
            continue;
        }
        if (!current) {
            continue;
        }
        if (!current.frame) {
            const frame = line.match(/mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
            if (frame) {
                current.frame = [Number(frame[1]), Number(frame[2]), Number(frame[3]), Number(frame[4])];
            }
        }
        if (current.visible == null) {
            const vis = line.match(/isVisible=(\w+)/);
            if (vis) {
                current.visible = vis[1] === "true";
            }
        }
    }
    if (current) {
        all.push(current);
    }
    const covering = [];
    for (let i = 0; i < all.length; i++) {
        const win = all[i];
        if (!win.frame) {
            continue;
        }
        const f = win.frame;
        if (x >= f[0] && y >= f[1] && x <= f[2] && y <= f[3]) {
            covering.push({ name: win.name, frame: f, visible: win.visible });
        }
        if (covering.length >= 8) {
            break;
        }
    }
    return covering;
}

/**
 * **谁真的拿到触摸焦点**（包名）；拿不到返回 null。
 *
 * 为什么不用 `dumpsys activity activities | grep topResumedActivity`：多任务下它会给误导值
 * —— 真机实测（K40/Android 16）：守卫切完读到的 topResumedActivity 是 `com.android.launcher3`，
 * 而屏幕上明明是游戏、注入的点击全落到别处（一手棋连试 4 次全 UNCHANGED）。
 * 决定「注入的触摸给谁」的是 InputDispatcher 的 FocusedWindows。
 */
function focusedWindowPackage() {
    const text = runShellCapture("dumpsys input | grep -A2 FocusedWindows", 4000);
    if (!text) {
        return null;
    }
    // 形如：displayId=0, name='e693d35 com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity'
    const matched = text.match(/name='[^ ]* ([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\//);
    return matched ? matched[1] : null;
}

/**
 * 把某个包的任务带到前台（**尽量不重启进程**）。
 *
 * ⚠️ 不能用 `am start -a MAIN -c LAUNCHER -p <包>`：腾讯天天象棋**没有 LAUNCHER 入口**
 * （真机实测 `Error: Activity not started, unable to resolve Intent`）。
 * 也不能用 `monkey -p <pkg> -c LAUNCHER`：那一路会重启/恢复对局，把刚「重来」出的干净开局弄丢。
 *
 * 做法：先从 `dumpsys activity activities` 里把这个包已有的 ActivityRecord 解析出来
 * （形如 `ActivityRecord{... com.x.y/.AppActivity t33}`），再用 `am start -n 包/类` ——
 * 目标任务已存在时这条只会把它提到前台（真机实测开局不会被恢复成旧局面）。
 * 解析不到就退回 monkey（可能重启游戏，日志里说清楚）。
 */
function bringPackageToFront(pkg) {
    if (!pkg) {
        return false;
    }
    const dump = runShellCapture("dumpsys activity activities | grep -m1 '" + pkg + "/'", 5000);
    let component = null;
    if (dump) {
        const matched = dump.match(new RegExp(pkg.replace(/\./g, "\\.") + "/([\\w.$]+)"));
        if (matched) {
            component = pkg + "/" + matched[1];
        }
    }
    if (component) {
        return runShellCapture("am start -n " + component, 6000) !== null;
    }
    // 兜底：可能重启游戏（调用方会在之后重新校准/确认局面）
    return runShellCapture("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1", 8000) !== null;
}

/**
 * 当前**真正**在前台（拿到输入焦点）的应用包名；拿不到返回 null。
 *
 * 为什么要它：脚本宿主 `RunIntentActivity` 是个**透明窗口**，用 `am start` 起脚本之后
 * 它常常仍是「有焦点的前台」——游戏只是画在它下面。这时注入的点击会被**我们自己吃掉**
 * （游戏毫无反应、确认阶段永远 UNCHANGED），而截图上一切正常，极难看出。
 * 真机证据（K40）：`dumpsys input` 的 FocusedApplications/FocusedWindows 都是本应用。
 *
 * ⚠️ 用 `topResumedActivity`：这台 Android 16 上 `mResumedActivity` / `mCurrentFocus` 取不到值。
 */
function foregroundPackage() {
    const text = runShellCapture("dumpsys activity activities | grep -m1 topResumedActivity", 4000);
    if (!text) {
        return null;
    }
    // 形如：topResumedActivity=ActivityRecord{1a2b3c u0 com.tencent.qqgame.xq/.MainActivity t88}
    const matched = text.match(/([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)\/[\w.$]+/);
    return matched ? matched[1] : null;
}

/**
 * 按一次「最近任务」键（APP_SWITCH=187）。只按一次 —— 连按两次会在最近两个任务间来回切，
 * 呼叫方应该「按一次 → 查前台 → 还不对再按」（见面板的 foregroundGuard）。
 */
function pressAppSwitch() {
    return runShellCapture("input keyevent 187", 4000) !== null;
}

/**
 * 用「最近任务快速切换」把**上一个应用**切回前台（连按两次 APP_SWITCH 键）。
 *
 * 为什么不用 `monkey -p <pkg> -c LAUNCHER`：那会**重新启动**游戏，而天天象棋启动后会
 * **恢复未完的对局** —— 刚「重来」出来的干净开局会变回旧局面（真机踩过）。
 * 最近任务切换不会重启进程，游戏保持原样。
 */
function bringToFrontByRecents() {
    if (!pressAppSwitch()) {
        return false;
    }
    if (typeOf("sleep") === "function") {
        globals.sleep(500);
    }
    pressAppSwitch();
    return true;
}

/**
 * 真机点击（P4）：只有这里碰宿主的点击接口；执行器只管「什么时候点、点哪里」。
 * 返回的 ok 只代表**接口调用有没有发出**，不代表棋子真的走了 —— 落子成败由
 * move-verifier 看新观测判断（这两件事必须分开统计）。
 *
 * ⚠️ 两个通道差别很大，默认 `auto`（有 Shizuku 就用它）：
 *   - `shizuku`：shell 身份注入 `input tap`／`input swipe`（按住时长可调），最接近真手指，
 *     实测能点动**会忽略无障碍事件的游戏**（腾讯天天象棋）；
 *     ⚠️ 部分游戏还会过滤**过短的按压**（K40 + Android 16 + 天天象棋：60ms 被丢弃、300ms 生效），
 *     这种情况把 pressMs 调到 300~400 即可（上层会自动改用 `input swipe`）。
 *   - `accessibility`：`press/click`（无障碍手势）—— 通用、无额外依赖，但事件带自动化标记，
 *     部分游戏直接忽略（**返回 true 却没反应，静默失败**）。
 * 另外：`auto.service` 在 force-stop 之后可能一直不重新绑定（MIUI），所以上层要先有界等待，
 * 不要调会无限阻塞的 `auto.waitFor()`。
 */
function createTap(options) {
    const opts = options || {};
    const requested = opts.method || "auto";
    // 通道名必须显式合法：拼错一个字母不得静默退回 auto，否则对照实验全部污染。
    if (requested !== "auto" && requested !== "shizuku" && requested !== "accessibility") {
        throw new Error("未知的点击通道：" + requested + "（只支持 auto / shizuku / accessibility）");
    }
    const method = requested === "auto" ? (shizukuReady() ? "shizuku" : "accessibility") : requested;
    const defaultDuration = opts.pressMs == null ? 60 : Number(opts.pressMs);
    /** 按压时长 ≥ 此值就走 `input swipe`（原地按住），否则用 `input tap`。见 tapViaShizuku 的说明。 */
    const shizukuMinPressMs = 120;
    const stats = { calls: 0, ok: 0, failed: 0, lastError: "", method: method };

    /**
     * Shizuku 注入。
     *
     * ⚠️ `input tap` 的按压时长≈0，实测有些游戏会**把这种「瞬按」直接丢掉**：
     *   K40（LineageOS 23.2 / Android 16）+ 天天象棋：同一坐标
     *     · `input tap x y` / `input motionevent DOWN/UP` → 毫无反应；
     *     · `input swipe x y x y 300` → 棋子被选中（300ms 就对）；600ms 同样有效。
     *   而同一台机器的**系统弹窗**用 `input tap` 是好的 → 是游戏自己按「按压时长」过滤，
     *   不是注入失效。所以时长 ≥ 阈值时改用 `input swipe`（起点==终点 = 原地按住的按压），
     *   只有极短按压才继续用 `input tap`。
     */
    function tapViaShizuku(px, py, duration) {
        const ms = Math.max(1, Math.round(duration == null ? defaultDuration : duration));
        const useSwipe = ms >= shizukuMinPressMs;
        const gestureName = useSwipe ? "shizuku-input-swipe" : "shizuku-input-tap";
        if (!shizukuReady()) {
            // 没执行也要报出「本来会用哪个手势」：否则日志里的名字会误导通道对照实验
            return { ok: false, method: gestureName, error: "Shizuku 不可用或未授权（在应用里授权后重试）" };
        }
        const command = useSwipe
            ? "input swipe " + px + " " + py + " " + px + " " + py + " " + ms
            : "input tap " + px + " " + py;
        let result;
        try {
            result = globals.shell(command, { shizuku: true, timeout: 5000 });
        } catch (exception) {
            // ⚠️ 名称必须反映**真实执行的手势**（原地 swipe ≠ tap），否则通道对照实验会被日志误导。
            return {
                ok: false,
                method: gestureName,
                command: command,
                error: String(exception && exception.message ? exception.message : exception)
            };
        }
        return {
            ok: result.code === 0,
            method: gestureName,
            command: command,
            error: result.code === 0 ? "" : String(result.error || ("exit " + result.code))
        };
    }

    function tapViaAccessibility(px, py, duration) {
        if (!has("press") && !has("click")) {
            return { ok: false, error: "缺少能力：press/click（这台设备上无法点击）" };
        }
        const usePress = has("press");
        const gestureName = usePress ? "accessibility-press" : "accessibility-click";
        try {
            const ok = usePress
                ? !!globals.press(px, py, Math.max(1, Math.round(duration)))
                : !!globals.click(px, py);
            return {
                ok: ok,
                method: gestureName,
                error: ok ? "" : ((globals.auto && globals.auto.service)
                    ? "点击接口返回失败（被系统拦下？目标窗口不可点？）"
                    : "点击接口返回失败：无障碍服务未就绪")
            };
        } catch (exception) {
            return {
                ok: false,
                method: gestureName,
                error: String(exception && exception.message ? exception.message : exception)
            };
        }
    }

    function tap(x, y, callOptions) {
        stats.calls += 1;
        // Reject a move if the captured window moved, disappeared or lost focus
        // while Pikafish was searching. Cached board coordinates are then stale.
        if (captureBackendKey === "window") {
            try {
                const recent = lastWindowCapture;
                const current = recent && globals.images.listCaptureWindows().find(function (item) {
                    return item.windowId === recent.windowId && item.packageName === recent.packageName && (item.foreground === true || item.active);
                });
                if (windowCaptureNeedsCalibration || !current || ["left","top","right","bottom"].some(function (key) {
                    return current.bounds[key] !== recent.bounds[key];
                }) || current.rotation !== recent.rotation) {
                    windowCaptureNeedsCalibration = true;
                    return { ok:false, method:"window-guard", error:"目标窗口已改变，请重新定位棋盘" };
                }
            } catch (error) { return { ok:false, method:"window-guard", error:String(error) }; }
        }
        const px = Math.round(Number(x));
        const py = Math.round(Number(y));
        if (!isFinite(px) || !isFinite(py)) {
            stats.failed += 1;
            stats.lastError = "坐标不是有效数字：" + x + "," + y;
            return { ok: false, method: null, error: stats.lastError };
        }
        const duration = callOptions && callOptions.pressMs != null
            ? Number(callOptions.pressMs) : defaultDuration;
        const startedAtMono = Date.now();
        const result = method === "shizuku"
            ? tapViaShizuku(px, py, duration)
            : tapViaAccessibility(px, py, duration);
        if (result.ok) {
            stats.ok += 1;
        } else {
            stats.failed += 1;
            stats.lastError = result.error;
        }
        return {
            ok: result.ok,
            // **真实执行的手势**（原地 swipe / tap / 无障碍 press / click），不再按请求通道猜。
            method: result.method || (method === "shizuku" ? "shizuku-input-tap" : "accessibility-unknown"),
            channel: method,
            requested: requested,
            command: result.command || null,
            x: px,
            y: py,
            durationMs: duration,
            startedAtMono: startedAtMono,
            finishedAtMono: Date.now(),
            error: result.error
        };
    }

    return {
        tap: tap,
        disabled: false,
        method: method,
        requested: requested,
        describe: function () {
            return requested + "→" + method + "：" + stats.ok + " 成功 / " + stats.failed +
                " 失败（共 " + stats.calls + " 次）";
        },
        stats: function () { return { calls: stats.calls, ok: stats.ok, failed: stats.failed }; }
    };
}

/** 屏幕尺寸（点击前做越界兜底用）；拿不到返回 null。 */
function screenSize() {
    if (globals.device && globals.device.width > 0 && globals.device.height > 0) {
        return { width: Number(globals.device.width), height: Number(globals.device.height) };
    }
    // 后台服务启动的脚本没有 Activity 初始化 ScreenMetrics，device 的尺寸可能仍为 0。
    // 用 Android 实际显示尺寸补齐，避免藏窗/结束页探针直接跳过。
    try {
        const ctx = globals.context;
        if (ctx) {
            let metrics = ctx.getResources().getDisplayMetrics();
            try {
                const real = new Packages.android.util.DisplayMetrics();
                ctx.getSystemService("window").getDefaultDisplay().getRealMetrics(real);
                metrics = real;
            } catch (error) { /* 没有 WindowManager 时用资源尺寸，不改写资源对象。 */ }
            if (metrics && metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return { width:Number(metrics.widthPixels), height:Number(metrics.heightPixels) };
            }
        }
    } catch (error) { /* 离线测试或宿主没有 Android Context。 */ }
    return null;
}

/**
 * 画出「计划/状态」（起始点标记 + 一行文字）。
 *
 * 说明：只有这里碰 `drawing`。它是可选的 —— 没有悬浮窗权限就返回 false，
 * 业务照跑（不画框不等于不能走棋）。
 */
function overlayAvailable() {
    return typeOf("drawing.show") === "function";
}

function overlayShow() {
    if (!overlayAvailable()) {
        return false;
    }
    try {
        return globals.drawing.show({ renderer:"imgui" }) === true;
    } catch (error) {
        return false;
    }
}

function overlayUpdate(items, text) {
    if (!overlayAvailable()) {
        return false;
    }
    try {
        globals.drawing.update(items || [], text == null ? "" : String(text));
        return true;
    } catch (error) {
        return false;
    }
}

function overlayHide() {
    if (!overlayAvailable()) {
        return false;
    }
    try {
        globals.drawing.hide();
        return true;
    } catch (error) {
        return false;
    }
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
    setCaptureBackend: setCaptureBackend,
    captureBackend: captureBackend,
    captureChannel: captureChannel,
    windowCaptureInfo: function () { return lastWindowCapture; },
    windowCaptureNeedsCalibration: function () { return windowCaptureNeedsCalibration; },
    invalidateWindowCaptureGeometry: function () { windowCaptureNeedsCalibration = true; },
    resetWindowCaptureGeometryForCalibration: function () {
        windowCaptureGeometryKey = null;
        lastWindowCapture = null;
        windowCaptureNeedsCalibration = false;
    },
    shellCaptureBackend: shellCaptureBackend,
    captureViaShell: captureViaShell,
    looksBlank: looksBlank,
    waitMs: waitMs,
    createTap: createTap,
    shizukuReady: shizukuReady,
    foregroundPackage: foregroundPackage,
    focusedWindowPackage: focusedWindowPackage,
    bringPackageToFront: bringPackageToFront,
    bringToFrontByRecents: bringToFrontByRecents,
    pressAppSwitch: pressAppSwitch,
    screenSize: screenSize,
    windowsAt: windowsAt,
    overlayAvailable: overlayAvailable,
    overlayShow: overlayShow,
    overlayUpdate: overlayUpdate,
    overlayHide: overlayHide,
    readImage: readImage,
    sizeOf: sizeOf,
    analyzeXiangqiBoard: analyzeXiangqiBoard,
    detectXiangqiCircles: detectXiangqiCircles,
    rgbAt: rgbAt,
    countColor: countColor,
    clip: clip,
    prepareRegion: prepareRegion,
    releaseFrame: releaseFrame,
    /** shell 抓帧走的是 raw 还是 PNG（null=还没试过；note 里有原因） */
    shellCaptureInfo: function () {
        return { supported: shellRawSupported, note: shellRawNote };
    },
    detect: detect,
    resolveProjectFile: resolveProjectFile,
    scriptDir: scriptDir,
    describeFrame: describeFrame,
    loadDetector: loadDetector,
    normalizeDetections: normalizeDetections
};

module.exports = AIJS_ADAPTER;
