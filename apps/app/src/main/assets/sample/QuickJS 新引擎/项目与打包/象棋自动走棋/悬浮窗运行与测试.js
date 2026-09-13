// @engine quickjs
/**
 * 悬浮窗：象棋自动走棋的**运行与测试面板**。
 *
 * 为什么需要悬浮窗（而不是像 main.js 那样用 Activity 界面）：
 *   ③ 自动走棋要求「游戏真的在前台」才能注入点击，Activity 界面一出来游戏就退到后台了；
 *   悬浮窗是覆盖层，可以**一边开着面板一边让游戏保持前台**。
 *
 * 可以做什么：
 *   · ① 只识别 / ② 着法建议 / ③ 自动走棋（点模式键循环切换；③ 会真的点击）
 *   · 开始/暂停、停止（清会话与几何）
 *   · 定位 + 校准（先线定位；失败时用 pickGrid 缓存框搜索兜底，再统一做 calibrateGrid 精校准）
 *   · 识别一帧（复用已校准几何；没有几何先定位），打印布局 / 未知格 / 用时
 *   · 运行离线自检（tools/self-check.js，105 项纯逻辑用例）
 *   · 运行格子契约测试（tools/test-cells-contract.js，15 项：空格计数/残局/退化网格拒绝）
 *
 * 自动运行（便于无人值守验证）：往 /sdcard/xiangqi-p4/floaty-autorun.json 写
 *   { "captureProbe": true, "selftest": true, "contract": true, "locate": true, "mode": "watch", "start": false }
 * 启动后会按顺序执行并把结果写进日志（tag：GlobalConsole，前缀 [floaty]）。
 */
"use strict";

const adapter = require("./src/runtime/aijs-adapter.js");
const geometryModule = require("./src/vision/geometry.js");
const recognizeBoard = require("./tools/recognize-board.js");
const locateBoard = require("./tools/locate-board.js");
const driverModule = require("./src/automation/auto-driver.js");
const engineModule = require("./src/engine/engine.js");
const cellsContract = require("./src/vision/cells.js");

const TMP_FRAME = "/sdcard/xiangqi-p4/floaty-frame.png";
const AUTORUN_FILE = "/sdcard/xiangqi-p4/floaty-autorun.json";
const MODES = ["watch", "advice", "auto"];
const MODE_LABEL = { watch: "① 只识别", advice: "② 着法建议", auto: "③ 自动走棋" };

let modeKey = "watch";
let driver = null;
let running = false;
let timer = null;
let pendingDeclare = false;   // autorun：等首个观察成功后再声明「现在轮到我」
let hideOnCapture = true;     // 抓帧时把面板藏起来（否则面板会盖住棋盘）
let autoAlignOn = false;      // 几何按检测框自动微调（真机实测会把子认重，默认关）
let lastLoggedLayout = null;  // 驱动视角去重打印用
let collapsed = false;
let grid = null;          // 已校准几何（会话内复用）
let calibrationNote = "未校准";
const logLines = [];

function logLine(text) {
    const line = String(text);
    logLines.push(line);
    while (logLines.length > 6) {
        logLines.shift();
    }
    console.log("[floaty] " + line);
    render();
}

const XML = [
    '<vertical w="330" h="auto" bg="#E6000000" padding="10" margin="0">',
    '  <horizontal>',
    '    <text id="title" text="象棋面板" w="120" h="auto" textColor="#FFFFFF" textSize="15" gravity="center_vertical"/>',
    '    <button id="modeBtn" text="①只识别" w="90" h="36" textSize="12"/>',
    '    <button id="collapseBtn" text="收起" w="60" h="36" textSize="12"/>',
    '    <button id="hideBtn" text="隐藏抓帧：开" w="96" h="36" textSize="11"/>',
    '    <button id="alignBtn" text="微调：关" w="80" h="36" textSize="11"/>',
    '  </horizontal>',
    '  <horizontal>',
    '    <button id="startBtn" text="开始" w="72" h="38" textSize="12"/>',
    '    <button id="stopBtn" text="停止" w="72" h="38" textSize="12"/>',
    '    <button id="locateBtn" text="定位+校准" w="88" h="38" textSize="12"/>',
    '    <button id="recognizeBtn" text="识别一帧" w="88" h="38" textSize="12"/>',
    '  </horizontal>',
    '  <horizontal>',
    '    <button id="turnBtn" text="轮到我" w="104" h="38" textSize="12"/>',
    '    <button id="sideBtn" text="换我方" w="104" h="38" textSize="12"/>',
    '    <button id="sessionBtn" text="新会话" w="104" h="38" textSize="12"/>',
    '  </horizontal>',
    '  <horizontal>',
    '    <button id="selfcheckBtn" text="离线自检" w="104" h="38" textSize="12"/>',
    '    <button id="contractBtn" text="契约测试" w="104" h="38" textSize="12"/>',
    '    <button id="clearBtn" text="清空日志" w="104" h="38" textSize="12"/>',
    '  </horizontal>',
    '  <text id="status" text="准备中…" w="310" h="auto" textColor="#B0FFFFFF" textSize="11"/>',
    '  <text id="logArea" text="" w="310" h="auto" textColor="#80FFFFFF" textSize="10"/>',
    '</vertical>'
].join("\n");

let win = null;

function render() {
    if (!win) {
        return;
    }
    try {
        win.status.setText(calibrationNote + "｜" + (running ? "运行中" : "已停止") +
            (hideOnCapture ? "｜抓帧时隐藏面板" : ""));
        win.logArea.setText(logLines.join("\n"));
        win.modeBtn.setText(MODE_LABEL[modeKey]);
        win.startBtn.setText(running ? "暂停" : "开始");
        win.collapseBtn.setText(collapsed ? "展开" : "收起");
        win.hideBtn.setText("隐藏抓帧：" + (hideOnCapture ? "开" : "关"));
        win.alignBtn.setText("微调：" + (autoAlignOn ? "开" : "关"));
    } catch (error) {
        console.log("[floaty] 渲染失败：" + error);
    }
}

/**
 * 抓一帧：优先 Shizuku/root 的 screencap（不受投屏会话失效影响），再回退应用截屏。
 * 抓帧期间默认把自己藏起来 —— 面板浮在棋盘上会直接把上几行子盖掉，识别就废了。
 */
function grabFrame() {
    if (!hideOnCapture) {
        return grabFrameRaw();
    }
    let hidden = false;
    try {
        hidden = win.setVisibility(false) !== false;
        if (hidden) {
            sleep(90);   // 给 WindowManager 一点时间真的移除窗口，否则截屏里还会有面板
        }
    } catch (error) {
        hidden = false;
    }
    try {
        return grabFrameRaw();
    } finally {
        if (hidden) {
            try {
                win.setVisibility(true);
            } catch (error) {
                logLine("恢复面板失败：" + error);
            }
        }
    }
}

function grabFrameRaw() {
    const caps = adapter.capabilities();
    if (caps.captureShell) {
        try {
            const result = shell("screencap -p " + TMP_FRAME, { shizuku: true, timeout: 12000 });
            if (result && result.code === 0) {
                const image = images.read(TMP_FRAME);
                if (image) {
                    return { image: image, path: TMP_FRAME, channel: "shell(" + caps.captureShellBackend + ")" };
                }
            }
        } catch (error) {
            logLine("shell 截图失败：" + error);
        }
    }
    try {
        if (!adapter.requestCapture("portrait")) {
            return null;
        }
        const frame = adapter.capture("full", 2000);
        if (frame) {
            return { image: frame, path: TMP_FRAME, channel: "screen" };
        }
    } catch (error) {
        logLine("应用截屏失败：" + error);
    }
    return null;
}

/**
 * 定位 + 校准。
 * 顺序：线定位（在验证集上质量最好）→ 失败则 pickGrid 缓存框搜索 → 再用 calibrateGrid 精校准。
 * K40 实测：跳过 calibrateGrid，纯阵营错会从 0 涨到 77 格，所以这一步不能省。
 */
function locateAndCalibrate() {
    const grabbed = grabFrame();
    if (!grabbed) {
        logLine("定位失败：两条截图通道都拿不到帧");
        return null;
    }
    const options = {
        orientation: "RED_BOTTOM",
        confidence: 0.25,
        autoAlign: false,
        search: { maxShift: 18, step: 3 },
        maxCandidates: 25
    };
    let located = null;
    if (grabbed.channel.indexOf("shell") === 0) {
        located = locateBoard.locateBoardInImage(TMP_FRAME, {});
        if (!located.ok) {
            logLine("线定位失败（" + located.reason + "），改用 pickGrid 缓存框搜索");
            const picked = recognizeBoard.pickGrid(grabbed.image, {
                orientation: "RED_BOTTOM", confidence: 0.25, radius: 18, step: 4, probeCropMargin: 0.5
            });
            if (!picked.ok) {
                logLine("pickGrid 也失败：" + picked.reason);
                return null;
            }
            located = {
                ok: true, method: "pick-grid-cached", grid: picked.grid,
                imageObject: grabbed.image, frameObject: grabbed.image
            };
            logLine("pickGrid 成功：尝试 " + picked.tried + " 个候选（检测 " +
                (picked.report.detectMs == null ? "-" : picked.report.detectMs) + "ms，搜索 " +
                picked.report.searchMs + "ms）");
        }
    } else {
        located = { ok: true, method: "captured-frame", grid: null, imageObject: grabbed.image, frameObject: grabbed.image };
        const picked = recognizeBoard.pickGrid(grabbed.image, {
            orientation: "RED_BOTTOM", confidence: 0.25, radius: 18, step: 4, probeCropMargin: 0.5
        });
        if (!picked.ok) {
            logLine("pickGrid 失败：" + picked.reason);
            return null;
        }
        located.grid = picked.grid;
    }
    if (!located.grid) {
        logLine("定位没有给出几何");
        return null;
    }
    const started = Date.now();
    const calibrated = recognizeBoard.calibrateGrid(grabbed.image, located, options);
    if (calibrated && calibrated.ok && calibrated.grid) {
        grid = calibrated.grid;
        calibrationNote = "几何 v" + grid.version + " 偏移(" + calibrated.dx + "," + calibrated.dy +
            ") 未知格 " + calibrated.unknown + " 用时 " + (Date.now() - started) + "ms";
        logLine("校准完成：" + calibrationNote + "｜定位方式 " + located.method);
        if (calibrated.layout) {
            logLine("局面 " + calibrated.layout);
        }
        return calibrated;
    }
    if (located.grid) {
        grid = located.grid;
        calibrationNote = "几何 v" + grid.version + "（未精校准）";
        logLine("精校准没成功，沿用定位几何：" + calibrationNote);
        return null;
    }
    logLine("校准失败且没有可用几何");
    return null;
}

/** 识别一帧（复用已校准几何；没有就先定位）。 */
function recognizeOnce() {
    if (!grid) {
        const located = locateAndCalibrate();
        if (!located) {
            return null;
        }
    }
    const grabbed = grabFrame();
    if (!grabbed) {
        logLine("识别失败：拿不到帧");
        return null;
    }
    const started = Date.now();
    const result = recognizeBoard.run(grabbed.image, {
        ok: true, method: "floaty-reuse", grid: grid,
        imageObject: grabbed.image, frameObject: grabbed.image
    }, {
        orientation: "RED_BOTTOM",
        confidence: 0.25,
        autoAlign: false
    }, null);
    const counts = result && result.chars ? cellsContract.count(result.chars) : null;
    if (!result || !result.ok) {
        logLine("识别失败：" + (result && result.reason ? result.reason : "无结果"));
        return null;
    }
    logLine("识别（" + grabbed.channel + "，" + (Date.now() - started) + "ms）：子 " +
        (counts ? counts.pieces : "?") + " 空 " + (counts ? counts.empty : "?") +
        " 未知 " + result.unknownCells + "（修复前 " + (result.stats ? result.stats.unknownBeforeRepair : "?") + "）");
    logLine("局面 " + result.layout);
    return result;
}

function describeCapabilities() {
    const caps = adapter.capabilities();
    const lines = [];
    lines.push("截图：" + adapter.captureBackend() + " → 最近 " + (adapter.captureChannel() || "未用过") +
        "｜shell=" + (caps.captureShell ? caps.captureShellBackend : "不可用"));
    lines.push("点击：无障碍=" + (caps.accessibilityReady ? "就绪" : "未就绪") +
        "，Shizuku=" + (caps.shizuku ? "可用" : "不可用"));
    return lines.join("｜");
}

function buildDriver() {
    const clickAllowed = modeKey === "auto";
    let tapFunction = null;
    if (clickAllowed && (adapter.capabilities().tap || adapter.capabilities().shizuku)) {
        tapFunction = adapter.createTap({ pressMs: 60, method: "auto" }).tap;
    }
    driver = driverModule.createDriver({
        allowClick: clickAllowed,
        tap: tapFunction,
        recognize: function (options) {
            // autoAlign（按检测框微调）默认关：真机实测它会把同一子检测两次合并成一格，
            // 认出来变成「红R 3 个」这种非法局面；所有采集/评估工具也都是 autoAlign:false。
            return recognizeBoard.recognizeScreen(Object.assign({}, options, { autoAlign: autoAlignOn }));
        },
        locate: function () {
            const result = locateAndCalibrate();
            return result ? { ok: true, grid: grid } : { ok: false, reason: "定位/校准失败" };
        },
        analyze: function (position, options) { return engineModule.analyze(position, options); },
        isTrustworthy: function (value) {
            return geometryModule.isTrustworthy(value, { minSamples: 4, maxResidual: 0.35 });
        },
        screen: function () { return adapter.screenSize(); },
        log: function (text) { logLine(text); },
        config: {
            tickMs: 1400,          // screencap 通道 0.6~1.4s/帧，tick 必须放大
            movetimeMs: 1200,
            modelVersion: "chessai_160",
            confidence: 0.25,
            orientation: "RED_BOTTOM",
            autoAlign: autoAlignOn
        },
        executorConfig: { pressMs: 60, settleMs: 140 }
    });
    return driver;
}

function tick() {
    if (!driver) {
        return;
    }
    try {
        const result = driver.tick();
        const machine = driver.machine;
        const stats = driver.stats();
        logLine("状态 " + machine.state() + "｜" + machine.reason());
        if (result) {
            // 驱动实际看到的局面（排障用）：只在与上一轮不同时打印，避免刷屏
            const view = driver.lastResult();
            const layout = view && view.layout ? view.layout : null;
            if (layout && layout !== lastLoggedLayout) {
                lastLoggedLayout = layout;
                const counted = view.chars ? cellsContract.count(view.chars) : null;
                logLine("驱动看到：子 " + (counted ? counted.pieces : "?") + " 空 " +
                    (counted ? counted.empty : "?") + " 未知 " + (counted && counted.unknown != null ? counted.unknown : "?") +
                    (counted && counted.ok ? "" : "（不合规：" + (counted ? counted.reason : "?") + "）"));
                logLine("  局面 " + layout);
            }
        }
        if (result && result.ok) {
            if (pendingDeclare) {
                pendingDeclare = false;
                logLine("autorun 声明轮到我：" + driver.assertTurn().reason);
            }
            // 驱动只保证给「观察到的局面/未知格」；不同版本字段不完全一样，所以逐项判空
            const unknown = result.unknownCells == null ? "?" : result.unknownCells;            logLine("本轮观察：未知格 " + unknown + "，耗时 " + stats.lastObserveMs + "ms" +
                (result.layout ? ("，局面 " + result.layout) : ""));
        } else if (result) {
            logLine("本轮没出手：" + result.reason);
        }
        const ticket = machine.currentTicket();
        if (ticket) {
            logLine("计划：" + ticket.moveICCS + "（深度 " + (ticket.depth == null ? "?" : ticket.depth) +
                "，评分 " + (ticket.score == null ? "?" : Math.round(ticket.score)) + "，" + ticket.executionState + "）");
        }
    } catch (error) {
        logLine("自动循环出错：" + error);
        stopLoop();
    }
}

function startLoop() {
    if (running) {
        return;
    }
    if (modeKey === "auto") {
        logLine("③ 自动走棋会真的点击棋盘，请确认游戏在前台且轮到我方");
    }
    buildDriver();
    // ⚠️ 真机踩过：只建驱动 + 起定时器是不够的，状态机还在 STOPPED，
    //    每轮 tick() 都返回「没有在运行」。必须先 driver.start()（START → READY）。
    try {
        const state = driver.start();
        logLine("驱动已启动，状态机：" + state);
    } catch (error) {
        logLine("驱动启动失败：" + error);
        return;
    }
    running = true;
    timer = setInterval(tick, 1400);
    logLine("已开始：" + MODE_LABEL[modeKey]);
}

function stopLoop() {
    if (timer) {
        clearInterval(timer);
        timer = null;
    }
    running = false;
    if (driver && typeof driver.stop === "function") {
        try {
            driver.stop();
        } catch (error) {
            logLine("停止驱动出错：" + error);
        }
    }
    logLine("已停止：驱动已停、定时器已清（几何与校准结果保留，点“定位+校准”可重来）");
}

function runSelfCheck() {
    try {
        const result = require("./tools/self-check.js");
        logLine("离线自检：" + result.summary);
        for (let i = 0; i < Math.min(result.failures.length, 3); i++) {
            logLine("  失败 " + (i + 1) + "：" + result.failures[i]);
        }
        return result;
    } catch (error) {
        logLine("自检没能运行：" + error);
        return null;
    }
}

function runContractTests() {
    try {
        const result = require("./tools/test-cells-contract.js");
        logLine("契约测试：" + result.summary);
        for (let i = 0; i < Math.min(result.failures.length, 3); i++) {
            logLine("  失败 " + (i + 1) + "：" + result.failures[i].name + " → " + result.failures[i].message);
        }
        return result;
    } catch (error) {
        logLine("契约测试没能运行：" + error);
        return null;
    }
}

function readAutorun() {
    try {
        if (!files || typeof files.read !== "function") {
            return null;
        }
        const text = String(files.read(AUTORUN_FILE) || "").trim();
        return text ? JSON.parse(text) : null;
    } catch (error) {
        return null;
    }
}

function main() {
    win = floaty.window(XML, { x: 40, y: 260, visible: true });
    win.setSize(340 * 3, -2);   // 单位是 px（1080 宽屏上 ≈ 340dp 的观感）
    logLine("面板已启动（" + describeCapabilities() + "）");
    logLine("提示：面板会浮在棋盘上，抓帧时已默认自动隐藏；嫌碍事可点「收起」或拖标题栏");

    // 拖动：按住标题栏移动窗口
    let downX = 0;
    let downY = 0;
    let winX = 40;
    let winY = 260;
    win.title.on("touch", function (event) {
        const action = event.getAction();
        if (action === 0) {
            downX = event.getRawX();
            downY = event.getRawY();
        } else if (action === 2) {
            win.setPosition(winX + (event.getRawX() - downX), winY + (event.getRawY() - downY));
        } else if (action === 1) {
            winX = winX + (event.getRawX() - downX);
            winY = winY + (event.getRawY() - downY);
        }
        return true;
    });

    win.modeBtn.on("click", function () {
        const index = MODES.indexOf(modeKey);
        modeKey = MODES[(index + 1) % MODES.length];
        if (running) {
            stopLoop();
            startLoop();
        }
        logLine("模式切换为 " + MODE_LABEL[modeKey]);
        render();
    });
    win.startBtn.on("click", function () {
        if (running) {
            stopLoop();
        } else {
            startLoop();
        }
        render();
    });
    win.stopBtn.on("click", function () {
        stopLoop();
        render();
    });
    win.locateBtn.on("click", function () {
        locateAndCalibrate();
        render();
    });
    win.recognizeBtn.on("click", function () {
        recognizeOnce();
        render();
    });
    win.selfcheckBtn.on("click", function () {
        runSelfCheck();
        render();
    });
    win.contractBtn.on("click", function () {
        runContractTests();
        render();
    });
    win.clearBtn.on("click", function () {
        logLines.length = 0;
        render();
    });
    // 抓帧时隐藏面板：开着时面板不会盖住棋盘（实测面板会挡住上面几行棋子）
    win.hideBtn.on("click", function () {
        hideOnCapture = !hideOnCapture;
        logLine("抓帧时隐藏面板：" + (hideOnCapture ? "开（推荐，面板不挡棋盘）" : "关（面板挡住的地方会识别不到）"));
        render();
    });
    // 几何自动微调：真机实测开着时会把同一子认成两个（红R 3 个），所以默认关
    win.alignBtn.on("click", function () {
        autoAlignOn = !autoAlignOn;
        logLine("几何自动微调：" + (autoAlignOn ?
            "开（按检测框微调，实测会认重子，建议只在定位明显偏时试）" :
            "关（推荐：直接用校准后的几何）"));
        render();
    });
    // 「现在轮到我」：把当前观察到的局面当可靠基准（轮次未知时状态机会一直等）
    win.turnBtn.on("click", function () {
        if (!driver) {
            logLine("先开始（或先识别一帧）再声明轮次");
        } else {
            const result = driver.assertTurn();
            logLine("声明轮到我：" + result.reason);
        }
        render();
    });
    win.sideBtn.on("click", function () {
        const next = (driver ? driver.mySide() : "w") === "w" ? "b" : "w";
        if (driver) {
            driver.setMySide(next);
        }
        logLine("我方改为 " + (next === "w" ? "红方" : "黑方") + "（旧基准已作废，需要重新声明轮次）");
        render();
    });
    win.sessionBtn.on("click", function () {
        stopLoop();
        if (driver && typeof driver.newSession === "function") {
            driver.newSession();
        }
        grid = null;
        calibrationNote = "已换会话（几何清空）";
        logLine("新会话：会话号 +1，票据与基准全部作废，几何已清空");
        render();
    });
    win.collapseBtn.on("click", function () {
        collapsed = !collapsed;
        try {
            win.status.setVisibility(collapsed ? 8 : 0);
            win.logArea.setVisibility(collapsed ? 8 : 0);
            win.setSize(340 * 3, -2);
        } catch (error) {
            logLine("收起/展开失败：" + error);
        }
        render();
    });

    render();

    // 自动运行（无人值守验证用）
    const auto = readAutorun();
    if (auto) {
        logLine("检测到 autorun：" + JSON.stringify(auto));
        if (auto.captureProbe) {
            const grabbed = grabFrame();
            let size = "?";
            if (grabbed) {
                try {
                    const info = adapter.sizeOf(grabbed.image);
                    size = info ? (info.width + "×" + info.height) : "尺寸未知";
                } catch (error) {
                    size = "尺寸读取失败";
                }
            }
            logLine("抓帧探测：" + (grabbed ? ("成功，" + grabbed.channel + " " + size) : "失败（两条通道都没有帧）"));
        }
        if (auto.selftest) {
            runSelfCheck();
        }
        if (auto.contract) {
            runContractTests();
        }
        if (auto.locate) {
            locateAndCalibrate();
        }
        if (auto.recognize) {
            recognizeOnce();
        }
        if (auto.mode && MODES.indexOf(auto.mode) >= 0) {
            modeKey = auto.mode;
            logLine("模式设为 " + MODE_LABEL[modeKey]);
        }
        if (auto.autoAlign != null) {
            autoAlignOn = !!auto.autoAlign;
            logLine("几何自动微调：" + (autoAlignOn ? "开" : "关"));
        }
        if (auto.start) {
            startLoop();
            if (auto.declareTurn) {
                // 刚启动时状态机还没观察过任何局面，assertTurn 会被拒；等第一个观察成功的 tick 再声明
                pendingDeclare = true;
                logLine("autorun：首个观察成功后会自动声明「现在轮到我」");
            }
            const stopAfterMs = auto.stopAfterMs == null ? 0 : Number(auto.stopAfterMs);
            if (stopAfterMs > 0) {
                // 无人值守验证「开始 → 停止」两条路径（watch 模式下不点击棋盘，安全）
                setTimeout(function () {
                    logLine("autorun：到时自动停止（" + stopAfterMs + "ms）");
                    stopLoop();
                    render();
                }, stopAfterMs);
            }
        }
        render();
    }
}

try {
    main();
} catch (error) {
    console.log("[floaty] 启动失败：" + error);
}
