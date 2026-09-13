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
 * 自动运行（便于无人值守验证）：往**临时目录**（工程目录下的 .floaty-panel/，见 pickScratchDir）
 * 里的 floaty-autorun.json 写：
 *   { "captureProbe": true, "selftest": true, "contract": true, "locate": true, "mode": "watch", "start": false }
 * 启动后会按顺序执行并把结果写进日志（tag：GlobalConsole，前缀 [floaty]）。启动日志里会打印临时目录的绝对路径。
 */
"use strict";

/**
 * 模块加载：**必须用 __dirname 拼绝对路径**，不能用相对 require。
 * 真机踩过（从工程主界面的「打开悬浮窗面板」按铃拉起）：`engines.execScriptFile()` 起的引擎里
 * cwd 可能是 "/"，于是 `require("./src/...")` 被解析成 `/src/...` → 「Cannot find module」、
 * 脚本 0.005 秒就退（注意 engine 把它记在 `resolved: /src/...` 里）。
 * `__dirname` 是**入口脚本自己的目录**（bootstrap 里由 scriptDirectory(filename) 算出，与 cwd 无关）。
 */
const BASE_DIR = (typeof __dirname === "string" && __dirname && __dirname !== ".") ? __dirname : ".";

function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

const adapter = load("src/runtime/aijs-adapter.js");
const geometryModule = load("src/vision/geometry.js");
const recognizeBoard = load("tools/recognize-board.js");
const locateBoard = load("tools/locate-board.js");
const driverModule = load("src/automation/auto-driver.js");
const engineModule = load("src/engine/engine.js");
const cellsContract = load("src/vision/cells.js");
// 跨引擎互锁：主界面（main.js）里还有另一套驱动循环，两边一起跑会双份截图/算棋，
// 模式③还会双份点击棋盘 —— 用同一个锁文件互斥（见 src/automation/loop-lock.js）。
const loopLock = load("src/automation/loop-lock.js");

/**
 * 临时文件目录。**不要再写死 /sdcard/xiangqi-p4**，但也不能全放工程目录里：
 *
 *  · autorun 配置只有脚本自己读写 → 跟着工程走（工程目录下的 .floaty-panel/），
 *    应用内示例副本在 cacheDir/resource-run、独立 APK 在 filesDir/project、用户导入在脚本目录，
 *    放外部写死路径就会「面板和工程分家」；
 *  · 截图文件是**用 shell（uid 2000 / Shizuku）写的** → shell 写不进应用私有目录
 *    （/data/user/0/...）：写失败就静默退化成应用截屏通道（还要授权）。
 *    所以它必须落在 /sdcard 下的可写目录。
 */
function ensureDir(dir) {
    try {
        if (typeof files.ensureDir === "function") {
            files.ensureDir(dir + "/");
        }
        return files.exists(dir) || files.isDir(dir);
    } catch (error) {
        return false;
    }
}

function pickScratchDir() {
    // 1) 应用缓存目录优先：一定可写，而且**不会被「打包（项目）」拷进产物** ——
    //    放工程目录里会在产物 assets 里多出 .floaty-panel/（一堆截图 + 空目录条目）。
    try {
        if (typeof context !== "undefined" && context && typeof context.getCacheDir === "function") {
            const cached = files.join(String(context.getCacheDir()), "floaty-panel");
            if (ensureDir(cached)) {
                return cached;
            }
        }
    } catch (error) { /* 试下一个 */ }
    // 2) 退而求其次：工程目录（脚本自己那层），至少「跟着工程走」
    const projectLocal = files.join(BASE_DIR, ".floaty-panel");
    if (ensureDir(projectLocal)) {
        return projectLocal;
    }
    // 3) 最后兜底：老路径（PC 侧脚本也认它）
    ensureDir("/sdcard/xiangqi-p4");
    return "/sdcard/xiangqi-p4";
}

function pickFrameDir() {
    let sdcard = "/sdcard";
    try {
        if (typeof files.getSdcardPath === "function") {
            sdcard = String(files.getSdcardPath());
        }
    } catch (error) { /* 用默认值 */ }
    const dir = files.join(sdcard, ".aijspro-floaty");
    if (ensureDir(dir)) {
        return dir;
    }
    ensureDir("/sdcard/xiangqi-p4");
    return "/sdcard/xiangqi-p4";
}

const SCRATCH_DIR = pickScratchDir();
const FRAME_DIR = pickFrameDir();
const TMP_FRAME = FRAME_DIR + "/floaty-frame.png";
// ⚠️ autorun 是**给 adb / PC 用的验证钩子**，所以它要放 shell 可写的地方
//  （应用缓存目录 adb 写不进去，我们自己的无人值守流程就断了）：跟截图目录同一个。
const AUTORUN_FILE = FRAME_DIR + "/floaty-autorun.json";
const MODES = ["watch", "advice", "auto"];
const MODE_LABEL = { watch: "① 只识别", advice: "② 着法建议", auto: "③ 自动走棋" };

let modeKey = "watch";
let driver = null;
let running = false;
let timer = null;
let pendingDeclare = false;   // autorun：等首个观察成功后再声明「现在轮到我」
// 算棋放哪里跑：worker（独立引擎线程，默认）/ inline（本线程同步，对照用）
let computeModeKey = "worker";
let lastThinkingLogAt = 0;    // 算棋中按秒报一次状态，不刷屏
let hideOnCapture = "auto";  // "auto" 只在面板真的压住棋盘时才藏（默认）；true/false 手动开/关
let autoAlignOn = false;      // 几何按检测框自动微调（真机实测会把子认重，默认关）
let lastLoggedLayout = null;  // 驱动视角去重打印用
let aliveTimer = null;        // 保活心跳（见 main 里的说明）
let aliveSeconds = 0;
let lastBeatAt = 0;           // 心跳间隔：衡量「本站线程被占多久」（空载 ≈1000ms）
let lagMax = 0;
let lagCount = 0;

/** 状态栏文字（心跳里每秒刷一次，写成一个函数免得两处不同步）。 */
function statusLine() {
    return calibrationNote + "｜" + (running ? "运行中" : "已停止") +
        "｜隐藏抓帧：" + (hideOnCapture === "auto" ? "自动" : (hideOnCapture ? "开" : "关")) +
        "｜存活 " + aliveSeconds + "s" + (lagMax > 1200 ? "｜最长心跳间隔 " + lagMax + "ms" : "");
}
let collapsed = false;
let grid = null;          // 已校准几何（会话内复用）
let calibrationNote = "未校准";
const logLines = [];
/** 屏上只显示最后几行（面板固定 470px 高，不遮棋盘）；完整日志走 logcat（前缀 [floaty]） */
const LOG_LINES_COMPACT = 3;
const LOG_LINES_FULL = 12;   // 缓冲区保留行数（方便 console 侧对账）
/** 窗口高度只由 XML 的 px 决定（见 applyWindowHeight 的注释：setSize 会把窗口框体弄坏） */
const WIN_H_COMPACT = 470;

function visibleLog() {
    return logLines.slice(Math.max(0, logLines.length - LOG_LINES_COMPACT));
}

/**
 * ⚠️ **绝对不要用 `win.setSize()`**（2026-09-13 真机事故）：
 *   · `setSize(w, -2)` → 窗口变成 `(900x0)` **零高**：画得出来、点不动；
 *   · `setSize(1020, 470)` → 窗口框体直接坏掉：整个面板塔成屏幕左上角一条**撕裂窄条**，
 *     看起来就像「点一下悬浮窗就闪退了」（用户报的就是这个）。
 * 所以窗口尺寸**只由 XML 的显式 px 决定**，运行期不再改尺寸（需要另一个尺寸就重建窗口）。
 */
function applyWindowHeight() {
    // 保留函数名（调用点不动），但**不再调 setSize** —— 尺寸全交给 XML。
}

function logLine(text) {
    const line = String(text);
    logLines.push(line);
    while (logLines.length > LOG_LINES_FULL) {
        logLines.shift();
    }
    console.log("[floaty] " + line);
    scheduleRender();   // 日志驱动的重绘做 250ms 合并，别让每个 logLine 都过一次 JS→Java 桥
}

let renderScheduled = false;

/** 日志驱动的重绘合并（250ms）；用户操作直接调 render() 保持即时反馈。 */
function scheduleRender() {
    if (renderScheduled) {
        return;
    }
    renderScheduled = true;
    setTimeout(function () {
        renderScheduled = false;
        render();
    }, 250);
}

/**
 * 面板布局。两个真机踩过的坑（都是「看不到就点不动」）：
 *   1. **尺寸必须写 px**：`h="auto"` 或 `win.setSize(w, -2)` 在这个引擎上会得到
 *      `mAttrs=(900x0)` 的**零高窗口** —— 照样画得出来，但没有任何可触摸区域，
 *      点哪儿都落到下层 App（点不动）。所以这里全部显式 px。
 *   2. **`<button>` 收不到点击**：真机对照（`.artifacts/floaty-touch-probe.js`）证明
 *      `<button>` 只有 `on("touch")` 能到，`on("click")` / `.click()` / `.longClick()` 三种绑定
 *      全都不触发；而 `<text>` 的 `on("click")` 正常。所以按钮一律用带 bg 的 `<text>` 冒充。
 */
const BTN_BG = "#FFDDDDDD";
const BTN_FG = "#FF101010";

function button(id, label, width) {
    return '    <text id="' + id + '" text="' + label + '" w="' + width + 'px" h="64px" bg="' + BTN_BG +
        '" textColor="' + BTN_FG + '" textSize="12" gravity="center"/>';
}

const XML = [
    '<vertical id="root" w="1020px" h="470px" bg="#FF101014" padding="8px">',
    '  <horizontal h="70px">',
    '    <text id="title" text="象棋面板" w="200px" h="70px" textColor="#FFFFFF" textSize="16" gravity="center"/>',
    button("modeBtn", "①只识别", 210),
    button("collapseBtn", "收起", 150),
    button("hideBtn", "隐藏抓帧：开", 220),
    button("alignBtn", "微调：关", 180),
    '  </horizontal>',
    '  <horizontal h="70px">',
    button("startBtn", "开始", 190),
    button("stopBtn", "停止", 190),
    button("locateBtn", "定位+校准", 230),
    button("recognizeBtn", "识别一帧", 230),
    '  </horizontal>',
    '  <horizontal h="70px">',
    button("turnBtn", "轮到我", 260),
    button("sideBtn", "换我方", 260),
    button("sessionBtn", "新会话", 260),
    '  </horizontal>',
    '  <horizontal h="70px">',
    button("selfcheckBtn", "离线自检", 240),
    button("contractBtn", "契约测试", 240),
    button("clearBtn", "清空日志", 240),
    button("closeBtn", "关闭面板", 230),
    '  </horizontal>',
    '  <text id="status" text="准备中…" w="1000px" h="34px" textColor="#B0FFFFFF" textSize="12"/>',
    '  <text id="logArea" text="" w="1000px" h="120px" textColor="#90FFFFFF" textSize="11"/>',
    '</vertical>'
].join("\n");

let win = null;

function render() {
    if (!win) {
        return;
    }
    try {
        win.status.setText(statusLine());
        win.logArea.setText(visibleLog().join("\n"));
        win.modeBtn.setText(MODE_LABEL[modeKey]);
        win.startBtn.setText(running ? "暂停" : "开始");
        win.collapseBtn.setText(collapsed ? "展开" : "收起");
        win.hideBtn.setText("隐藏抓帧：" + (hideOnCapture === "auto" ? "自动" : (hideOnCapture ? "开" : "关")));
        win.alignBtn.setText("微调：" + (autoAlignOn ? "开" : "关"));
    } catch (error) {
        console.log("[floaty] 渲染失败：" + error);
    }
}

/**
 * 面板当前占的屏幕矩形（XML 尺寸是固定的 1020×470；位置可能被用户拖过）。
 * 拿不到就返回 null（调用方按「可能挡着」处理）。
 */
const PANEL_W = 1020;
const PANEL_H = 470;

function panelRect() {
    try {
        const x = win.getX(true);
        const y = win.getY(true);
        if (!isFinite(x) || !isFinite(y)) {
            return null;
        }
        return { x: x, y: y, width: PANEL_W, height: PANEL_H };
    } catch (error) {
        return null;
    }
}

/** 棋盘在屏幕上的矩形（用已校准几何算外框）。 */
function boardRect() {
    if (!grid) {
        return null;
    }
    try {
        const box = geometryModule.boundsOf(grid, 0);
        return { x: box.x, y: box.y, width: box.width, height: box.height };
    } catch (error) {
        return null;
    }
}

function rectsOverlap(a, b) {
    if (!a || !b) {
        return null;   // 不知道
    }
    return !(a.x + a.width <= b.x || b.x + b.width <= a.x ||
        a.y + a.height <= b.y || b.y + b.height <= a.y);
}

/** show=true 开／false 关／"auto" 只在面板真的压住棋盘时才藏（默认）。 */
function needHideForCapture() {
    if (hideOnCapture === true) {
        return true;
    }
    if (hideOnCapture === false) {
        return false;
    }
    const overlap = rectsOverlap(panelRect(), boardRect());
    return overlap === null ? true : overlap;   // 判断不了就按「会挡」处理（安全）
}

/**
 * 抓一帧：优先 Shizuku/root 的 screencap（不受投屏会话失效影响），再回退应用截屏。
 *
 * 抓帧期间可能要先把面板藏起来（面板浮在棋盘上会把上几行子盖掉，识别就废了）——
 * 但**藏+等 90ms+恢复** 每帧都要花掉约 100ms，所以默认是 "auto"：
 * 面板在棋盘外（现在的默认位置就在棋盘下方）时**不藏**，省掉这一百毫秒和一次闪动。
 */
function grabFrame() {
    if (!needHideForCapture()) {
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
            modelVersion: "chessai_160x320_v5r",
            confidence: 0.25,
            orientation: "RED_BOTTOM",
            autoAlign: autoAlignOn,
            // 算棋默认放独立引擎线程（worker）：不然一步 ~1.2s 的搜索会把面板触摸一起挡掉。
            // autorun 里写 "computeMode":"inline" 可以对照老路径。
            computeMode: computeModeKey
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
        // 互锁续期：锁被别人（主界面）抢走就果断停，绝不让两个引擎同时跑
        if (!loopLock.touch(loopLock.OWNER_FLOATY)) {
            const now = loopLock.status();
            logLine("循环锁被「" + (now.owner || "?") + "」拿走，面板循环退出。");
            stopLoop();
            return;
        }
        // 算棋中：本轮没截图、只 poll 了一次 worker 结果 → 只按秒报一次，不刷屏
        if (result && result.thinking) {
            const now = Date.now();
            if (now - lastThinkingLogAt >= 1000) {
                lastThinkingLogAt = now;
                logLine("状态 " + machine.state() + "｜" + result.reason);
            }
            return;
        }
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
    // 互锁：主界面那边正在跑就先拒绝（两个引擎同时跑等于双份截图/算棋）
    const gate = loopLock.acquire(loopLock.OWNER_FLOATY);
    if (!gate.ok) {
        logLine("拒绝开始：" + gate.reason + "。先到主界面点「开始观察 / 暂停」把它停掉。");
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
        loopLock.release(loopLock.OWNER_FLOATY);
        logLine("驱动启动失败：" + error);
        return;
    }
    running = true;
    scheduleNextTick();
    logLine("已开始：" + MODE_LABEL[modeKey] + "（已持有棋盘循环锁：" + loopLock.lockPath() + "，算棋 " +
        (driver && driver.computeMode ? driver.computeMode() : "?") + "）");
}

/**
 * 下一轮什么时候跑：
 *   · 算棋中（worker 在自己的线程里转）→ 只轮询结果，120ms 一次；本轮不截图、不观察，
 *     所以密轮询很便宜，而且能在算完的第一时间出手（不用等到下一个 1400ms）。
 *   · 其他 → 正常间隔（screencap 通道 0.6~1.4s/帧，密了只是白烧 CPU）。
 */
function scheduleNextTick() {
    const thinking = driver && typeof driver.isThinking === "function" && driver.isThinking();
    const delay = thinking ? 120 : 1400;
    timer = setTimeout(function () {
        timer = null;
        if (!running) {
            return;
        }
        tick();
        if (running) {
            scheduleNextTick();
        }
    }, delay);
}

function stopLoop() {
    if (timer) {
        clearTimeout(timer);
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
    loopLock.release(loopLock.OWNER_FLOATY);   // 互锁：停了就把锁交出去
    logLine("已停止：驱动已停、定时器已清、循环锁已释放（几何与校准结果保留）");
}

function runSelfCheck() {
    try {
        const result = load("tools/self-check.js");
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
        const result = load("tools/test-cells-contract.js");
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

/**
 * 保活（真机踩过，必读）：
 *   悬浮窗的存活**完全靠脚本里挂着的定时器**。面板一开始没有定时器（要等你点「开始」才有），
 *   所以脚本会在启动后 ~19 秒被引擎回收：
 *     `QuickJsException: Script execution interrupted` → `Engine destroy`，
 *   而**窗口像素还留在屏幕上** —— 看着是活的、点任何按钮都没反应。
 *   修法：常驻一个 1 秒心跳，绝不 clearInterval；同时把「存活 Ns」写进状态栏，
 *   这样一眼能看出面板到底还活着没有。退出时把窗口关掉，不留「僵尸面板」。
 */
function startKeepAlive() {
    if (aliveTimer) {
        return;
    }
    aliveTimer = setInterval(function () {
        const now = Date.now();
        // 心跳每 1s 一次 → **最长心跳间隔**就是「脚本线程被占多久」的滞后指标：
        // 空载 ≈1000ms；本线程里跑采集/识别（或退回同步算棋）时会被拉长。
        // ⚠️ 口径修正（小米8 探针实测）：不能再减 1000ms —— 定时器是「上一次触发 + 1000ms」重排的，
        //    减 1000 会把一次长阻塞少算一个时钟周期（实测 1279ms 的同步算棋只报出 290ms）。
        const gap = lastBeatAt ? (now - lastBeatAt) : 0;
        lastBeatAt = now;
        if (gap > 1200) {
            lagCount += 1;
            lagMax = Math.max(lagMax, gap);
            if (lagCount <= 3) {
                console.log("[floaty] 心跳间隔 " + gap + "ms（本站线程被采集/同步算棋占着，点按钮要排队）");
            }
        }
        aliveSeconds += 1;
        try {
            win.status.setText(statusLine());
        } catch (error) {
            // 渲染失败不影响保活
        }
        if (aliveSeconds % 30 === 0) {
            logLine("面板保活中（存活 " + aliveSeconds + "s，最长心跳间隔 " + lagMax +
                "ms，超 1.2s 的 " + lagCount + " 次）");
        }
    }, 1000);
    logLine("保活心跳已启动（1s 一次；顺带度量脚本线程被占多久 —— 这个数就是「卡」的来源）");
    registerExitHook();
}

/** 退出时关掉窗口：否则脚本一结束就留下一个「点不动的僵尸面板」。 */
function registerExitHook() {
    try {
        if (typeof events !== "undefined" && events && typeof events.on === "function") {
            events.on("exit", function () {
                try {
                    if (win) {
                        win.close();
                    }
                } catch (error) {
                    // 关不掉也没关系
                }
            });
            logLine("退出钩子已注册（脚本结束时会关掉面板，不留僵尸窗口）");
        } else {
            logLine("运行时没有 events.on，退出时不会自动关窗（面板停住请手动 force-stop 应用）");
        }
    } catch (error) {
        logLine("注册退出钩子失败：" + error);
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
    // 默认落在**棋盘下方的空档**：棋盘在屏幕中部（K40 实测 ~y 690..1700），面板紧凑态只有 470px 高，
    // 放下面就不会遮住棋子（用户反馈「日志挡住象棋盘了」）。屏幕高度从 device 度量取。
    let startX = 30;
    let startY = 700;
    try {
        const size = adapter.screenSize();
        if (size && size.height > 0) {
            startY = Math.max(120, size.height - WIN_H_COMPACT - 120);
        }
    } catch (error) {
        // 量不到就用默认值
    }
    try {
        win = floaty.window(XML, { x: startX, y: startY, visible: true });
    } catch (error) {
        // 从工程主界面的按钮拉起时最容易踩到：没有悬浮窗权限，窗口建不出来
        console.log("[floaty] 打不开悬浮窗（多半是没有悬浮窗权限）：" + error);
        try {
            toast("需要悬浮窗权限才能显示面板（设置 → 权限 → 悬浮窗）");
        } catch (inner) {
            // 没有 toast 就算了
        }
        return;
    }
    win.setTouchable(true);
    // ⚠️ 不要在这里 setSize(…)：真机上会把窗口框体弄坏（零高 / 熔成一条窄条），尺寸全部由 XML 的 px 指定。
    logLine("面板已启动（" + describeCapabilities() + "）");
    logLine("临时目录：" + FRAME_DIR + "｜autorun：" + AUTORUN_FILE + "（shell 可写，便于 adb 驱动）");
    logLine("提示：面板会浮在棋盘上，抓帧时已默认自动隐藏；嫌碍事可点「收起」或拖标题栏");
    startKeepAlive();

    // ---- 拖动 ----
    // 之前是自己在 JS 里拖：**每个 ACTION_MOVE 都调一次 win.setPosition()**，
    // 每次都要过 JS→Java 桥 + WindowManager.updateViewLayout，而且触摸事件排在脚本线程上
    // （自动循环/算棋一跑就更卡）——真机上手感就是「拖着走一顿一顿」。
    // 正确做法：用平台原生拖动，脚本线程完全不参与。
    let nativeDrag = false;
    try {
        win.setDraggable(true);
        nativeDrag = !!win.isAdjustEnabled();
    } catch (error) {
        nativeDrag = false;
    }
    if (nativeDrag) {
        logLine("拖动：按住窗口直接拖（平台原生拖动，不过脚本线程）");
    } else {
        // 兜底：平台没有原生拖动时才自己拖，而且**必须限频**（否则就是上面那个卡的原因）
        logLine("拖动：平台没给原生拖动，退回脚本拖动（已限频 40ms）");
        let downX = 0;
        let downY = 0;
        let winX = startX;
        let winY = startY;
        let lastMoveAt = 0;
        win.title.on("touch", function (event) {
            const action = event.getAction();
            if (action === 0) {
                downX = event.getRawX();
                downY = event.getRawY();
            } else if (action === 2) {
                const now = Date.now();
                if (now - lastMoveAt < 40) {
                    return true;   // 限频：拖动手感不受影响，但桥调用次数掉到 1/4 以下
                }
                lastMoveAt = now;
                win.setPosition(winX + (event.getRawX() - downX), winY + (event.getRawY() - downY));
            } else if (action === 1) {
                winX = winX + (event.getRawX() - downX);
                winY = winY + (event.getRawY() - downY);
                win.setPosition(winX, winY);
                logLine("拖动到 (" + Math.round(winX) + "," + Math.round(winY) + ")（实测 " +
                    win.getX(true) + "," + win.getY(true) + "）");
            }
            return true;
        });
    }

    // 触摸诊断：悬浮窗控件不在 uiautomator dump 里，只能靠日志判断「事件到底到没到」
    let touchLogs = 0;
    try {
        win.root.on("touch", function (event) {
            const action = event && typeof event.getAction === "function" ? event.getAction() : "?";
            if (action === 0 && touchLogs < 5) {
                touchLogs += 1;
                logLine("触摸到达：down@(" + event.getRawX() + "," + event.getRawY() + ")");
            }
            return false;
        });
    } catch (error) {
        logLine("根视图触摸监听没绑上：" + error);
    }

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
    // 面板自己关自己：它默认浮在主界面下半部，主界面上那一个「关闭」按钮可能被盖住
    win.closeBtn.on("click", function () {
        console.log("[floaty] 关闭面板（用户点按钮）");
        stopLoop();   // 内部会释放循环锁
        try {
            if (aliveTimer) {
                clearInterval(aliveTimer);
                aliveTimer = null;
            }
        } catch (error) {
            // 清不掉也不影响关窗
        }
        try {
            win.close();
        } catch (error) {
            console.log("[floaty] 关窗失败：" + error);
        }
    });
    logLine("按钮绑定完成：模式/收起/隐藏抓帧/微调/开始/停止/定位/识别/轮到我/换我方/新会话/自检/契约/清空/关闭");
    // 抓帧时隐藏面板：开着时面板不会盖住棋盘（实测面板会挡住上面几行棋子）
    // 隐藏抓帧：自动 → 开 → 关 循环（默认「自动」= 只在面板真的压住棋盘时才藏，省每帧 ~100ms）
    win.hideBtn.on("click", function () {
        hideOnCapture = hideOnCapture === "auto" ? true : (hideOnCapture === true ? false : "auto");
        logLine("隐藏抓帧：" + (hideOnCapture === "auto" ? "自动（只在压住棋盘时藏）" : (hideOnCapture ? "开" : "关")));
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
        if (auto.computeMode) {
            // "worker"（默认，独立引擎线程）/ "inline"（本线程同步，对照老路径）
            computeModeKey = String(auto.computeMode) === "inline" ? "inline" : "worker";
            logLine("算棋模式：" + computeModeKey);
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
