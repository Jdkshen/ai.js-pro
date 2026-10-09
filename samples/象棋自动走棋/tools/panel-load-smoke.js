// @engine quickjs
/**
 * 面板入口「加载冒烟」（**PC / Node 专用**）。
 *
 * 为什么单独要它：`tools/run-regress.js` 只覆盖 `src/**` 的模块与驱动逻辑，
 * **两个入口脚本（main.js / 悬浮窗运行与测试.js）不在里面** —— 而入口里有一大堆
 * 「模块加载期就要求值」的顶层 `const`（尺寸、文案、XML 字符串…）。
 * 一旦某个常量被放到使用点之后（顶层 TDZ），真机表现就是**面板直接起不来**，
 * 而 PC 回归全绿、`node --check` 也照样通过（语法没错，是求值顺序错）。
 *
 * 2026-09-25 实例：给参数行加「哈希」按钮时把 `HASH_BTN_W`/`HASH_DEFAULT` 写在 `const XML = [...]`
 * 之后 —— XML 在模块加载时就调用 `sliderWidth()` 与按钮工厂，于是 `Cannot access 'HASH_DEFAULT'
 * before initialization`。用本脚本一跑就露。
 *
 * 用法（工程目录下）：
 *   node tools/panel-load-smoke.js
 *   node tools/panel-load-smoke.js "悬浮窗运行与测试.js"
 *
 * 原理：给最小宿主桩（files / context / sleep / toast）后 `require` 入口脚本。
 *   · 通过 → 打印 `PANEL LOAD OK`（退出码 0）；
 *   · 失败 → 打印 `LOAD FAILED` + 前 5 行堆栈（退出码 1）。
 * ⚠️ 它**不跑业务**：`main()` 在 `floaty.window(...)` 处会因没有悬浮窗宿主而提前返回，
 *    那条 `[floaty] 打不开悬浮窗…` 是预期输出，不代表失败。
 */
"use strict";

const path = require("path");
const fs = require("fs");
const os = require("os");

const BASE_DIR = (function () {
    try {
        if (typeof __dirname !== "undefined" && __dirname) {
            return String(__dirname).replace(/\\/g, "/").replace(/\/tools$/, "");
        }
    } catch (ignore) { /* 忽略 */ }
    return ".";
}());

const full = process.argv.indexOf("--full") >= 0;
const target = process.argv.slice(2).find(function (value) { return value.indexOf("--") !== 0; }) || "悬浮窗运行与测试.js";
const targetPath = path.isAbsolute(target) ? target : path.join(BASE_DIR, target);
const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "panel-smoke-"));
const fileData = Object.create(null);

// 最小宿主桩：只够模块加载期用（文件目录解析、缓存目录、包名）。
globalThis.files = {
    join: function () {
        return Array.prototype.slice.call(arguments).join("/").replace(/\/+/g, "/");
    },
    ensureDir: function () { return true; },
    exists: function () { return true; },
    isDir: function () { return true; },
    read: function (p) { return fileData[p] || ""; },
    write: function (p, text) { fileData[p] = String(text); return true; },
    getSdcardPath: function () { return scratch; },
    cwd: function () { return BASE_DIR; }
};
globalThis.context = {
    getCacheDir: function () { return scratch; },
    getPackageName: function () { return "com.smoke.host"; }
};
globalThis.sleep = function () { };
globalThis.toast = function () { };

/**
 * 宿主对象桩：任意属性都可继续点（`ui.layout(...)`、`ui.startBtn.on("click", fn)`…）。
 * 目的只是让**模块加载期**的顶层语句能走完（入口脚本在加载时就会调 `ui.layout` 与一堆 `ui.xxx.on`），
 * 不模拟任何行为。
 */
function makeHostStub(name) {
    const stub = function () { return makeHostStub(name); };
    return new Proxy(stub, {
        get: function (target, prop) {
            if (prop === Symbol.toPrimitive) {
                return function () { return 0; };
            }
            if (prop === "toString") {
                return function () { return "[stub " + name + "]"; };
            }
            if (prop === "length") {
                return 0;
            }
            return makeHostStub(name + "." + String(prop));
        },
        set: function () { return true; },
        apply: function () { return makeHostStub(name + "()"); }
    });
}
// main.js（页面入口）在模块加载期就用 ui.layout / ui.xxx.on，没有真宿主；给个可链式调用的桩。
["ui", "engines", "device", "threads", "storages", "events", "plugins", "images", "colors",
    "files2", "app", "dialogs", "http", "keys", "floaty", "$pikafish"].forEach(function (name) {
    if (name === "floaty" || name === "$pikafish") {
        return;   // 这两个留空：入口里的 try/catch 与 typeof 守卫要看到「没有」
    }
    if (typeof globalThis[name] === "undefined") {
        globalThis[name] = makeHostStub(name);
    }
});
if (target.indexOf("悬浮窗运行与测试.js") >= 0) {
    globalThis.engines = { myEngine: function () { return { id: 1 }; }, all: function () { return []; } };
}

const windows = [];
const errors = [];
const stores = {};
const scheduledTimeouts = [];
const oldLog = console.log;
if (full) {
    console.log = function () {
        const line = Array.prototype.join.call(arguments, " ");
        if (/启动失败|打不开悬浮窗|ReferenceError|TypeError|SyntaxError/.test(line)) errors.push(line);
        oldLog.apply(console, arguments);
    };
    globalThis.device = { width: Number(process.env.XQ_WIDTH) || 1080, height: Number(process.env.XQ_HEIGHT) || 2400 };
    globalThis.setTimeout = function (callback, delay) { scheduledTimeouts.push({callback:callback,delay:delay}); return scheduledTimeouts.length; };
    globalThis.setInterval = function () { return 2; };
    globalThis.clearTimeout = function () {};
    globalThis.clearInterval = function () {};
    globalThis.storages = { create: function (name) {
        const values = stores[name] || (stores[name] = {});
        return { get: function (key, fallback) { return key in values ? values[key] : fallback; },
            put: function (key, value) { values[key] = value; } };
    } };
    globalThis.floaty = { window: function (xml, options) {
        if (/undefined|NaN/.test(xml)) throw new Error("Invalid panel XML values");
        const bindings = {}, texts = {}, widgets = {};
        const state = { x: options && options.x || 0, y: options && options.y || 0,
            visibility: 0, attached: !options || options.visible !== false, touchable: true, attachments: [] };
        const win = new Proxy({ xml: xml, bindings: bindings, texts: texts, state: state,
            setPosition: function (x, y) { state.x = x; state.y = y; },
            getX: function () { return state.x; }, getY: function () { return state.y; },
            getWidth: function () { return 900; }, getHeight: function () { return 400; },
            setVisibility: function (value) { state.attached = value === 0;
                if (state.attached) state.attachments.push(state.visibility); return true; },
            setContentVisibility: function (value) { state.visibility = value; },
            setTouchable: function (value) { state.touchable = value; },
            setDragHandle: function () {}, close: function () {}, setSize: function () {},
            setAdjustEnabled: function () {}, exitOnClose: function () {}
        }, { get: function (target, key) {
            if (key in target) return target[key];
            if (!widgets[key]) widgets[key] = new Proxy({
                on: function (event, callback) { bindings[key + ":" + event] = callback; },
                setText: function (text) { texts[key] = String(text); },
                getText: function () { return texts[key] || ""; }
            }, { get: function (widget, name) { return widget[name] || function () { return 0; }; } });
            return widgets[key];
        } });
        windows.push(win);
        return win;
    } };
}

let failed = null;
try {
    require(targetPath);
    if (full && target.indexOf("main.js") < 0) {
        const assert = require("assert");
        assert(windows.length >= 1, "No panel created");
        const panel = windows[0];
        assert(Object.keys(panel.bindings).length >= 20, "Incomplete button binding");
        assert.strictEqual(panel.state.visibility, 8, "Startup must default to the compact controls");
        assert.strictEqual(panel.state.attached, true, "More needs a mounted full panel");
        assert.deepStrictEqual(panel.state.attachments, [8], "Full panel must first mount hidden");
        const compact = windows[windows.length - 1];
        assert.notStrictEqual(compact, panel, "No compact controls created at startup");
        assert.strictEqual(compact.state.visibility, 0, "Compact controls must be visible by default");
        compact.bindings["compactExpand:click"]();
        assert.strictEqual(panel.state.visibility, 0, "More must open the full panel");
        panel.bindings["modeBtn:click"]();
        const previousThreads = panel.texts.threadsValue;
        panel.bindings["threadsPlus:touch"]({ getAction: function () { return 0; } });
        panel.bindings["threadsPlus:touch"]({ getAction: function () { return 1; } });
        assert.notStrictEqual(panel.texts.threadsValue, previousThreads, "Thread control did not update");
        panel.bindings["searchModeBtn:click"]();
        panel.bindings["collapseBtn:click"]();
        assert.strictEqual(panel.state.visibility, 8, "Expanded panel was not hidden");
        compact.bindings["compactExpand:click"]();
        assert.strictEqual(panel.state.visibility, 0, "Expanded panel was not restored");
        assert.strictEqual(panel.state.touchable, true, "Panel lost touch input");
        panel.bindings["sideBtn:click"]();
        assert.strictEqual(panel.texts.sideBtn, "我方黑", "Side before start was not retained");
        panel.bindings["sideBtn:click"]();
        assert.strictEqual(panel.texts.sideBtn, "我方红");
        const autorun = scheduledTimeouts.find(function (entry) { return entry.delay === 60; });
        assert(autorun, "Autorun callback was not scheduled");
        const autorunFile = files.join(scratch, ".aijspro-floaty", "floaty-autorun.json");
        storages.create("xiangqi-panel").put("running", true);
        files.write(autorunFile, JSON.stringify({locate:false,start:false,collapse:true}));
        const queued = scheduledTimeouts.length;
        autorun.callback();
        assert.strictEqual(scheduledTimeouts.length, queued, "Explicit start:false must suppress crash resume");
        assert.strictEqual(panel.texts.startBtn, "开始", "Explicit stop should remain stopped");
        assert.strictEqual(panel.state.visibility, 8, "Explicit collapse:true must use compact controls");
        files.write(autorunFile, JSON.stringify({locate:false,collapse:false}));
        autorun.callback();
        assert.strictEqual(panel.state.visibility, 0, "Explicit collapse:false must show parameters and logs");
        assert.strictEqual(panel.texts.startBtn, "取消", "Omitted start should retain crash resume");
        panel.bindings["startBtn:click"]();
        assert.strictEqual(panel.texts.startBtn, "开始", "Resumed startup should still be cancellable");
        assert.deepStrictEqual(errors, [], "Panel swallowed startup/runtime errors");
        console.log("PANEL FULL OK: buttons, threads, search mode, collapse/expand " + device.width + "x" + device.height);
    }
} catch (error) {
    failed = error;
}

if (failed) {
    const stack = failed && failed.stack
        ? String(failed.stack).split("\n").slice(0, 5).join(" | ")
        : String(failed);
    console.log("LOAD FAILED: " + stack);
    process.exit(1);
}
if (process.env.XQ_LAYOUT_EXPORT) fs.writeFileSync(process.env.XQ_LAYOUT_EXPORT, JSON.stringify(windows.map(function(w) { return { xml: w.xml, texts: w.texts, width: device.width, height: device.height }; })));
console.log("PANEL LOAD OK（" + target + "）");
if (full) require("./test-panel-start.js");
if (full) require("./test-page-settings.js");
process.exit(0);
