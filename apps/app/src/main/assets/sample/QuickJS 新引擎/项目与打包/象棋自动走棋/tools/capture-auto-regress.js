// @engine quickjs
/** PC 回归：自动截图故障切 shell、冷却期间不重试、主通道恢复后切回。 */
"use strict";

const assert = require("assert");
const adapter = require("../src/runtime/aijs-adapter.js");
const root = Function("return this")();
const original = {
    captureScreen: root.captureScreen,
    shell: root.shell,
    images: root.images,
    shizuku: root.shizuku,
    dateNow: Date.now
};
let now = 1000;
let screenOk = false;
let screenCalls = 0;
let shellCalls = 0;
const screenFrame = { width: 1080, height: 2400, source: "screen" };
const shellFrame = { width: 1080, height: 2400, source: "shell" };

try {
    Date.now = function () { return now; };
    root.captureScreen = function () {
        screenCalls += 1;
        if (!screenOk) throw new Error("projection stopped");
        return screenFrame;
    };
    root.shizuku = { isAvailable: function () { return true; }, hasPermission: function () { return true; } };
    root.shell = function () { shellCalls += 1; return { code: 0 }; };
    root.images = { read: function () { return shellFrame; } };
    adapter.setCaptureBackend("auto", { persist: false });

    assert.strictEqual(adapter.capture("full", 10, { attempts: 1 }), shellFrame);
    assert.strictEqual(adapter.captureChannel().indexOf("shell"), 0);
    assert.strictEqual(screenCalls, 1);
    assert.strictEqual(shellCalls, 1);
    assert.strictEqual(adapter.capture("full", 10), shellFrame);
    assert.strictEqual(screenCalls, 1, "冷却期间不能反复试坏掉的系统截屏");

    now += 16000;
    screenOk = true;
    assert.strictEqual(adapter.capture("full", 10), screenFrame);
    assert.strictEqual(adapter.captureChannel(), "screen");
    assert.strictEqual(screenCalls, 2, "到期应探测并切回系统截屏");
    assert.strictEqual(adapter.capture("full", 10), screenFrame);
    assert.strictEqual(screenCalls, 3, "恢复后继续使用系统截屏");

    screenOk = false;
    adapter.setCaptureBackend("screen", { persist: false });
    assert.throws(function () { adapter.capture("full", 10, { attempts: 1 }); }, /只用系统截屏/);
    assert.strictEqual(shellCalls, 2, "手动 screen 不得偷偷回退 shell");

    adapter.setCaptureBackend("shell", { persist: false });
    assert.strictEqual(adapter.capture("full", 10), shellFrame);
    assert.strictEqual(screenCalls, 4, "手动 shell 不得试系统截屏");
    console.log("CAPTURE_AUTO_REGRESS OK");
} finally {
    Date.now = original.dateNow;
    for (const key of ["captureScreen", "shell", "images", "shizuku"]) {
        if (original[key] === undefined) delete root[key];
        else root[key] = original[key];
    }
    adapter.setCaptureBackend("auto", { persist: false });
}
