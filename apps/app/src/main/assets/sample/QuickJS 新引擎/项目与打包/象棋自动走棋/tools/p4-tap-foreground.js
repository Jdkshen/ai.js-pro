// @engine quickjs
/**
 * 注入点击「前台归属」对照（设备端）。
 *
 * 前面 `p4-tap-probe.js` 得到「应用内 Shizuku 注入点不动棋盘」的结论，但它有个缺陷：
 * 脚本自己是跑在应用的 RunIntentActivity 里的 —— **应用窗口在前台**，注入的点击
 * 很可能被我们自己的窗口吃掉了，压根没到游戏。
 *
 * 这个探针先把游戏切到前台（脚本自己在后台跑），再注入同一串点击，然后把帧落盘。
 * 输出：/sdcard/xiangqi-p4/tap-fg.json ＋ tap-fg-*.png
 */
const adapter = require("../src/runtime/aijs-adapter.js");

const OUT = "/sdcard/xiangqi-p4/tap-fg.json";
const TAG = "TAPFG";
const PKG = "com.tencent.qqgame.xq";
/** 红炮（FEN col1,row7）与目标格（col2,row7）：点中会选中/落子 */
const FROM = { x: 199, y: 1501 };
const TO = { x: 310, y: 1501 };

function say(text) {
    console.log(TAG + " " + text);
}

const report = { frames: [] };

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

function sh(cmd) {
    const r = shell(cmd, { shizuku: true, timeout: 10000 });
    return { code: r && r.code, out: String((r && r.result) || "").slice(0, 120) };
}

function tap(x, y, ms, label) {
    const r = sh("input swipe " + x + " " + y + " " + x + " " + y + " " + ms);
    report.frames.push({ label: label, cmd: "input swipe " + x + " " + y + " " + ms, code: r.code, out: r.out });
    say(label + " code=" + r.code);
}

function main() {
    report.capabilities = {
        shizuku: adapter.capabilities().shizuku,
        accessibilityReady: adapter.capabilities().accessibilityReady
    };
    adapter.setCaptureBackend("shell");
    say("能力：" + JSON.stringify(report.capabilities));

    grabTo("/sdcard/xiangqi-p4/tap-fg-0-before.png");
    say("基线帧已存");

    // 把自己的 Activity 切走，让游戏回到前台（否则点击落在我们自己的窗口上）
    say("把游戏切到前台…");
    sh("monkey -p " + PKG + " -c android.intent.category.LAUNCHER 1");
    sleep(6000);

    tap(FROM.x, FROM.y, 400, "1-起点");
    sleep(1500);
    grabTo("/sdcard/xiangqi-p4/tap-fg-1-afterFrom.png");

    tap(TO.x, TO.y, 400, "2-终点");
    sleep(1800);
    grabTo("/sdcard/xiangqi-p4/tap-fg-2-afterTo.png");

    tap(FROM.x, FROM.y, 400, "3-再点起点");
    sleep(1500);
    grabTo("/sdcard/xiangqi-p4/tap-fg-3-afterAgain.png");

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
