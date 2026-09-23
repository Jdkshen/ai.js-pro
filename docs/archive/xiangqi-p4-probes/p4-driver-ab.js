// @engine quickjs
/**
 * 驱动「每帧观察」那条路的 A/B 取证（设备端）。
 *
 * 背景：面板按钮的识别（shell 截图 → 文件识别）给出合法开局，
 * 而状态机驱动的每轮观察（`recognizeScreen({grid, timeoutMs: 400})`，走 grid 复用分支
 * → `adapter.capture("full", 400)`）读到 `RnbakabnR/9/1c5C1/P1P1P1P1P/…`（非法）。
 * 两者用的是**不同**的取帧路径，且驱动的帧从未落盘 → 离线复现不出来。
 *
 * 这个探针把三条路的局面与通道都落盘对比：
 *   A 驱动同款：grid 复用 + timeoutMs 400
 *   B 放宽超时：grid 复用 + timeoutMs 1200
 *   C 注入已抓好的 shell 帧（frame 参数）
 * 输出：/sdcard/xiangqi-p4/driver-ab.json
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const locateBoard = require("./locate-board.js");
const board = require("./recognize-board.js");

const OUT = "/sdcard/xiangqi-p4/driver-ab.json";
const SHELL_PNG = "/sdcard/xiangqi-p4/ab-shell.png";
const TAG = "DRVAB";

function say(text) {
    console.log(TAG + " " + text);
}

const report = { steps: [] };

function record(name, extra) {
    report.steps.push(Object.assign({ name: name, at: Date.now() }, extra));
}

function grabShell() {
    try {
        const caps = adapter.capabilities();
        if (caps.captureShell) {
            const r = shell("screencap -p " + SHELL_PNG, { shizuku: true, timeout: 12000 });
            if (r && r.code === 0 && files.exists(SHELL_PNG)) {
                const image = images.read(SHELL_PNG);
                if (image) {
                    return { image: image, channel: "shell(" + caps.captureShellBackend + ")" };
                }
            }
        }
    } catch (error) {
        say("shell 截图失败：" + error);
    }
    return null;
}

function tryRecognize(name, options) {
    const t0 = Date.now();
    let result = null;
    let error = null;
    try {
        result = board.recognizeScreen(options);
    } catch (e) {
        error = String(e && e.message ? e.message : e);
    }
    const ms = Date.now() - t0;
    const entry = {
        name: name,
        ms: ms,
        channel: String(adapter.captureChannel()),
        ok: !!(result && result.ok),
        layout: result && result.ok ? result.layout : null,
        unknownCells: result && result.ok ? result.unknownCells : null,
        conflict: result && result.ok && result.stats ? result.stats.conflicts : null,
        reason: error || (result && !result.ok ? result.reason : null),
        sideUnknown: result && result.ok && result.stats ? result.stats.sideUnknown : null,
        sideRed: result && result.ok && result.stats ? result.stats.sideRed : null,
        sideBlack: result && result.ok && result.stats ? result.stats.sideBlack : null,
        gridOrigin: options.grid ? [Math.round(options.grid.originX), Math.round(options.grid.originY)] : null,
        resultGrid: result && result.grid ? [Math.round(result.grid.originX), Math.round(result.grid.originY)] : null
    };
    say(name + " ok=" + entry.ok + " " + ms + "ms 通道=" + entry.channel + " 局面=" + entry.layout +
        (entry.reason ? (" 原因=" + entry.reason) : ""));
    record(name, entry);
    // 阵营采样明细（红/黑占比），用于确认是「判色错」还是「格子归属错」
    if (result && result.ok && result.sides) {
        const detail = result.sides.map(function (item) {
            return item.col + "," + item.row + "=" + item.piece + ":" +
                Number(item.redShare).toFixed(3) + "/" + Number(item.darkShare).toFixed(3);
        });
        report[name + "_sides"] = detail.join(" ");
        say(name + " 采样明细：" + detail.join(" "));
    }
}

function main() {
    say("capabilities: shell=" + adapter.capabilities().captureShell +
        " shellBackend=" + adapter.shellCaptureBackend() +
        " 偏好=" + adapter.captureBackend());

    const grabbed = grabShell();
    if (!grabbed) {
        say("拿不到 shell 帧，退出");
        files.write(OUT, JSON.stringify(report));
        return;
    }
    say("shell 帧已落盘 " + SHELL_PNG);

    const options = {
        orientation: "RED_BOTTOM",
        confidence: 0.25,
        autoAlign: false,
        search: { maxShift: 18, step: 3 },
        maxCandidates: 25
    };
    const located = locateBoard.locateBoardInImage(SHELL_PNG, {});
    if (!located.ok) {
        say("定位失败：" + located.reason);
        files.write(OUT, JSON.stringify(report));
        return;
    }
    const calibrated = board.calibrateGrid(located.imageObject, located, options);
    const grid = calibrated && calibrated.grid ? calibrated.grid : located.grid;
    say("几何 v" + grid.version + " 起点 (" + Math.round(grid.originX) + "," + Math.round(grid.originY) +
        ") 格距 (" + grid.stepX.toFixed(2) + "," + grid.stepY.toFixed(2) + ")｜校准：" +
        (calibrated && calibrated.reason ? calibrated.reason : "无"));
    report.grid = {
        originX: grid.originX, originY: grid.originY,
        stepX: grid.stepX, stepY: grid.stepY, version: grid.version
    };
    report.calibrate = calibrated && calibrated.reason ? calibrated.reason : null;

    const base = { grid: grid, orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false };

    // A：驱动同款（400ms 超时，走 adapter.capture）
    tryRecognize("A_driver400", Object.assign({}, base, { timeoutMs: 400 }));

    // B：放宽超时
    tryRecognize("B_driver1200", Object.assign({}, base, { timeoutMs: 1200 }));

    // C：注入刚抓好的 shell 帧
    tryRecognize("C_injectedFrame", Object.assign({}, base, { timeoutMs: 400, frame: grabbed.image }));

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
