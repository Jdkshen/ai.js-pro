// @engine quickjs
/**
 * 取帧超时取证（设备端）。
 *
 * 怀疑：驱动每轮观察走 `recognizeScreen({grid, timeoutMs: 400})` 的 grid 复用分支
 *   → `adapter.capture("full", 400)`，而本机 screencap 通道 0.6~1.4s/帧 →
 *   400ms 可能拿到「半更新 / 陈旧」帧，颜色采样错位 → 黑子判红 → 非法局面。
 *
 * 这个探针把同一通道、不同超时拿到的帧**各自落盘**，PC 上离线重放判色即可定论。
 * 输出：/sdcard/xiangqi-p4/cap-<timeout>.png + /sdcard/xiangqi-p4/cap-probe.json
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const locateBoard = require("./locate-board.js");
const board = require("./recognize-board.js");

const TAG = "CAPPROBE";
const OUT = "/sdcard/xiangqi-p4/cap-probe.json";
const REF = "/sdcard/xiangqi-p4/cap-ref.png";

function say(text) {
    console.log(TAG + " " + text);
}

const report = { frames: [] };

function main() {
    report.capabilities = {
        captureShell: adapter.capabilities().captureShell,
        captureScreen: adapter.capabilities().captureScreen,
        shizuku: adapter.capabilities().shizuku,
        shellBackend: adapter.shellCaptureBackend()
    };
    say("能力：" + JSON.stringify(report.capabilities) + "｜偏好=" + adapter.captureBackend());

    // 强制走 shell（Shizuku screencap），避免投屏授权弹窗与坏掉的 MediaProjection 会话
    adapter.setCaptureBackend("shell");
    say("已把通道偏好设为：" + adapter.captureBackend());

    // 参考帧：shell 直接截图（面板按钮那条路用的就是这个）
    const r = shell("screencap -p " + REF, { shizuku: true, timeout: 12000 });
    say("参考截图 code=" + (r && r.code) + " 存在=" + files.exists(REF));

    const options = {
        orientation: "RED_BOTTOM",
        confidence: 0.25,
        autoAlign: false,
        search: { maxShift: 18, step: 3 },
        maxCandidates: 25
    };
    const located = locateBoard.locateBoardInImage(REF, {});
    if (!located.ok) {
        say("参考帧定位失败：" + located.reason);
        files.write(OUT, JSON.stringify(report));
        return;
    }
    const calibrated = board.calibrateGrid(located.imageObject, located, options);
    const grid = calibrated && calibrated.grid ? calibrated.grid : located.grid;
    report.grid = {
        originX: grid.originX, originY: grid.originY,
        stepX: grid.stepX, stepY: grid.stepY, version: grid.version
    };
    say("几何 起点(" + grid.originX.toFixed(2) + "," + grid.originY.toFixed(2) + ") 格距(" +
        grid.stepX.toFixed(2) + "," + grid.stepY.toFixed(2) + ")｜校准：" +
        (calibrated && calibrated.reason ? calibrated.reason : "无"));

    const timeouts = [400, 800, 1200, 2000, 400];
    for (let i = 0; i < timeouts.length; i++) {
        const to = timeouts[i];
        const path = "/sdcard/xiangqi-p4/cap-" + i + "-" + to + ".png";
        const t0 = Date.now();
        let frame = null;
        let error = null;
        try {
            frame = adapter.capture("full", to);
        } catch (e) {
            error = String(e && e.message ? e.message : e);
        }
        const ms = Date.now() - t0;
        const entry = {
            index: i, timeoutMs: to, ms: ms, channel: String(adapter.captureChannel()),
            got: !!frame, error: error
        };
        if (frame) {
            try {
                const info = adapter.describeFrame(frame);
                entry.width = info.pixelWidth || info.width;
                entry.height = info.pixelHeight || info.height;
                entry.blank = info.blank;
                entry.format = info.format == null ? null : String(info.format);
                images.save(frame, path);
                entry.saved = path;
                entry.bytes = files.exists(path) ? files.size(path) : null;
            } catch (e2) {
                entry.describeError = String(e2 && e2.message ? e2.message : e2);
            }
            try {
                frame.recycle();
            } catch (e3) { /* 忽略 */ }
        }
        say("第" + i + " 次 超时" + to + "ms → " + ms + "ms 通道=" + entry.channel +
            " 拿到=" + entry.got + (entry.bytes ? (" 落盘=" + entry.bytes + "B") : "") +
            (entry.error ? (" 错误=" + entry.error) : ""));
        report.frames.push(entry);
    }

    report.refBytes = files.exists(REF) ? files.size(REF) : null;
    files.write(OUT, JSON.stringify(report));
    say("已写 " + OUT);
}

try {
    main();
} catch (error) {
    say("崩溃：" + error + " @ " + (error && error.stack ? String(error.stack).slice(0, 300) : ""));
    try {
        files.write(OUT, JSON.stringify(report));
    } catch (e2) { /* 忽略 */ }
}
