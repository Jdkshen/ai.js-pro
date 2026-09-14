// @engine quickjs
/**
 * 注入点击对照（设备端）：**同一条命令、不同发起者**，看游戏认哪个。
 *
 * 背景：手动用 `adb shell input swipe 191 1469 191 1469 600` 能选中棋子并落子；
 * 而驱动在应用里用 `shell("input swipe …", {shizuku:true})` 发同样的命令却毫无反应。
 * 两者身份都是 shell(uid 2000)，所以要么是「发起者不同」（进程/会话），要么是
 * 命令根本没被执行成功。这个探针把三条路各自点一次并**把前后帧落盘**，用 md5 判定。
 *
 * 输出：/sdcard/xiangqi-p4/tap-probe.json ＋ tap-*.png
 */
const adapter = require("../src/runtime/aijs-adapter.js");

const OUT = "/sdcard/xiangqi-p4/tap-probe.json";
const TAG = "TAPPROBE";
/** 待扫的组合：手动通过 adb 成功的是 (191,1469)/600ms，驱动用的是 (199,1501)/400ms */
const CASES = [
    { name: "c191-1469-600", x: 191, y: 1469, ms: 600 },
    { name: "c199-1501-400", x: 199, y: 1501, ms: 400 },
    { name: "c191-1469-400", x: 191, y: 1469, ms: 400 },
    { name: "c199-1501-600", x: 199, y: 1501, ms: 600 },
    { name: "c196-1496-300", x: 196, y: 1496, ms: 300 },
    { name: "c191-1490-600", x: 191, y: 1490, ms: 600 }
];

function say(text) {
    console.log(TAG + " " + text);
}

const report = { x: null, y: null, steps: [] };

const MD5 = (function () {
    // 用小工具算文件 md5（宿主有 files.md5 就用它）
    return function (path) {
        try {
            if (files.md5) {
                return String(files.md5(path));
            }
        } catch (e) { /* 退回用 .md5 文件 */ }
        try {
            const side = path + ".md5";
            if (files.md5sum) {
                files.md5sum(path);
            }
            if (files.exists(side)) {
                return String(files.read(side)).trim();
            }
        } catch (e2) { /* 忽略 */ }
        return null;
    };
})();

function grabTo(path) {
    try {
        const frame = adapter.capture("full", 1500);
        if (!frame) {
            return { path: path, error: "capture 返回空" };
        }
        images.save(frame, path);
        try {
            frame.recycle();
        } catch (e2) { /* 忽略 */ }
        return { path: path };
    } catch (e) {
        return { path: path, error: String(e && e.message ? e.message : e) };
    }
}

function step(name, action) {
    const before = grabTo("/sdcard/xiangqi-p4/tap-" + name + "-before.png");
    let act = null;
    try {
        act = action();
    } catch (e) {
        act = { error: String(e && e.message ? e.message : e) };
    }
    sleep(1600);
    const after = grabTo("/sdcard/xiangqi-p4/tap-" + name + "-after.png");
    const entry = {
        name: name,
        action: act,
        beforeBytes: before.bytes,
        afterBytes: after.bytes,
        beforeMd5: before.md5,
        afterMd5: after.md5,
        changed: before.md5 && after.md5 ? (before.md5 !== after.md5) : (before.bytes !== after.bytes)
    };
    report.steps.push(entry);
    say(name + "：改变=" + entry.changed + "（" + entry.beforeBytes + " → " + entry.afterBytes + "）" +
        " 动作=" + JSON.stringify(act));
}

function main() {
    report.capabilities = {
        shizuku: adapter.capabilities().shizuku,
        accessibilityReady: adapter.capabilities().accessibilityReady
    };
    say("能力：" + JSON.stringify(report.capabilities));
    // 固定走 shell 通道：不然 requestCapture 会弹投屏授权框，把脚本卡住
    adapter.setCaptureBackend("shell");
    say("截图通道=" + adapter.captureBackend());
    say("开始前 5s（PC 端把棋局切前台、确保棋盘干净）");
    sleep(5000);

    for (let i = 0; i < CASES.length; i++) {
        const c = CASES[i];
        const before = grabTo("/sdcard/xiangqi-p4/tap-" + c.name + "-before.png");
        const r = shell("input swipe " + c.x + " " + c.y + " " + c.x + " " + c.y + " " + c.ms,
            { shizuku: true, timeout: 6000 });
        sleep(1800);
        const after = grabTo("/sdcard/xiangqi-p4/tap-" + c.name + "-after.png");
        const entry = {
            name: c.name, x: c.x, y: c.y, ms: c.ms,
            code: r && r.code,
            beforePath: before.path, beforeError: before.error || null,
            afterPath: after.path, afterError: after.error || null
        };
        report.steps.push(entry);
        say("" + c.name + " code=" + entry.code + " 落盘=" + (!before.error && !after.error));
    }

    files.write(OUT, JSON.stringify(report));
    say("已写 " + OUT);
}

try {
    main();
} catch (error) {
    say("崩溃：" + error);
    try {
        files.write(OUT, JSON.stringify(report));
    } catch (e2) { /* 忽略 */ }
}
