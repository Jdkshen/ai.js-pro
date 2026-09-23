// @engine quickjs
/**
 * 宿主接口层（§14：宿主差异只在 adapter 适配，业务层依赖接口而不是全局对象）。
 *
 * 现状：页面/悬浮窗/驱动/探针都直接拿 `adapter` 到处用 —— 于是
 *   · 想换截图实现、想在测试里换成假实现，都没有插口；
 *   · 能力缺失（没有 Shizuku、没有无障碍）只能各自写 if；
 *   · "业务层 vs 宿主层"的边界靠人记。
 *
 * 这一步只做**声明 + 组装 + 校验**，不重写 adapter：
 *   createPorts(adapter, extra) → { capture, tap, foreground, windows, shell, files, device, now, sleep }
 * 缺关键能力时 `missing()` 会列出来，调用方据此明确失败（而不是跑到一半才崩）。
 */
"use strict";

/** 每项必需的宿主能力：名字 → 判断"有没有"的方法。 */
const REQUIRED = {
    capture: function (adapter) {
        return !!(adapter && (typeof adapter.capture === "function" || typeof adapter.grab === "function"));
    },
    deviceInfo: function (adapter) {
        return !!(adapter && typeof adapter.screenSize === "function");
    },
    rgbAt: function (adapter) {
        return !!(adapter && typeof adapter.rgbAt === "function");
    }
};

/** 可选能力：缺了不影响核心流程，但要在日志里说清楚。 */
const OPTIONAL = {
    tap: function (adapter) { return !!(adapter && typeof adapter.createTap === "function"); },
    foreground: function (adapter) { return !!(adapter && typeof adapter.focusedWindowPackage === "function"); },
    windows: function (adapter) { return !!(adapter && typeof adapter.windowsAt === "function"); },
    shell: function (adapter) { return !!(adapter && typeof adapter.runShellCapture === "function"); },
    sizeOf: function (adapter) { return !!(adapter && typeof adapter.sizeOf === "function"); },
    /** 引擎找色（红黑判色用它；缺了会退回逐点采样，只是慢） */
    countColor: function (adapter) { return !!(adapter && typeof adapter.countColor === "function"); }
};

/**
 * @param adapter aijs-adapter（唯一的宿主实现）
 * @param extra   { now, sleep, readFile, writeFile, files, context, images, win }
 */
function createPorts(adapter, extra) {
    const injected = extra || {};

    function has(name) {
        const probe = REQUIRED[name] || OPTIONAL[name];
        return probe ? probe(adapter) : false;
    }

    function missing() {
        const out = [];
        const names = Object.keys(REQUIRED);
        for (let i = 0; i < names.length; i++) {
            if (!has(names[i])) {
                out.push(names[i]);
            }
        }
        return out;
    }

    /** 缺了会影响功能（但不致命）的能力，启动时打印。 */
    function degraded() {
        const out = [];
        const names = Object.keys(OPTIONAL);
        for (let i = 0; i < names.length; i++) {
            if (!has(names[i])) {
                out.push(names[i]);
            }
        }
        return out;
    }

    return {
        // ---- 宿主能力（薄转发：业务层只认这些名字，不认 adapter 的方法名） ----
        capture: function (options) {
            return typeof adapter.capture === "function" ? adapter.capture(options) : adapter.grab(options);
        },
        sizeOf: function (image) { return typeof adapter.sizeOf === "function" ? adapter.sizeOf(image) : null; },
        screenSize: function () { return adapter.screenSize(); },
        rgbAt: function (image, x, y) { return adapter.rgbAt(image, x, y); },
        countColor: function (image, color, options) {
            return typeof adapter.countColor === "function" ? adapter.countColor(image, color, options) : null;
        },
        createTap: function (options) { return adapter.createTap(options); },
        setCaptureBackend: function (backend, options) { return adapter.setCaptureBackend(backend, options); },
        captureBackend: function () { return adapter.captureBackend(); },
        captureChannel: function () { return adapter.captureChannel(); },
        capabilities: function () { return adapter.capabilities(); },
        focusedWindowPackage: function () {
            return typeof adapter.focusedWindowPackage === "function" ? adapter.focusedWindowPackage() : null;
        },
        foregroundPackage: function () {
            return typeof adapter.foregroundPackage === "function" ? adapter.foregroundPackage() : null;
        },
        bringPackageToFront: function (pkg) {
            return typeof adapter.bringPackageToFront === "function" ? adapter.bringPackageToFront(pkg) : false;
        },
        /**
         * 用「最近任务」切出再切回（连按两次 APP_SWITCH）：强制游戏 pause→resume。
         * 用于「走子动画卡在中间」时催一下（见 auto-driver 的 opts.nudge）。
         */
        bringToFrontByRecents: function () {
            return typeof adapter.bringToFrontByRecents === "function" ? adapter.bringToFrontByRecents() : false;
        },
        windowsAt: function (x, y) {
            return typeof adapter.windowsAt === "function" ? adapter.windowsAt(x, y) : [];
        },
        runShellCapture: function (command, timeoutMs) {
            return typeof adapter.runShellCapture === "function" ? adapter.runShellCapture(command, timeoutMs) : null;
        },

        // ---- 宿主基础件（由宿主注入：Node/QuickJS 里各不相同） ----
        now: typeof injected.now === "function" ? injected.now : function () { return Date.now(); },
        sleep: typeof injected.sleep === "function" ? injected.sleep : function () { },
        readFile: typeof injected.readFile === "function" ? injected.readFile : null,
        writeFile: typeof injected.writeFile === "function" ? injected.writeFile : null,
        files: injected.files || null,
        context: injected.context || null,
        images: injected.images || null,
        /** 面板窗口句柄（布局由 ui 层建，生命周期由 window-manager 管）。 */
        window: injected.win || null,

        has: has,
        missing: missing,
        degraded: degraded,
        /** 一行摘要，启动日志用。 */
        describe: function () {
            const miss = missing();
            const deg = degraded();
            return "端口：" + (miss.length === 0 ? "必需能力齐备" : ("**缺 " + miss.join(",") + "**")) +
                (deg.length > 0 ? ("｜降级：" + deg.join(",")) : "");
        }
    };
}

module.exports = {
    REQUIRED: REQUIRED,
    OPTIONAL: OPTIONAL,
    createPorts: createPorts
};
