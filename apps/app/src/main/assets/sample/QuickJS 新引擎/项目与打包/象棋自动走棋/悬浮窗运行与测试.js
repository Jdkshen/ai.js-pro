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

/**
 * 工程版本从 src/app/version.js 读取，主界面与悬浮窗共用；实验记录还带关键文件指纹。
 * 真机坑：手机上的工程副本、内置示例、资源导入副本、重新装 APK 都可能各不相同，
 * 不打印版本与指纹就无法确认「跑的是哪一份」（曾经把代码改了、手机却没生效，白排查一整轮）。
 */
// 版本由两个入口共用的 src/app/version.js 提供。
/** 版本核对用的关键文件（相对工程根）。第一个用**入口脚本自身文件名**，
 *  因为探针/实验可能把面板另存为别的名字运行（panel-probe.js），写死名字会读到旧副本。 */
const PROBE_SELF_NAME = (typeof __filename === "string" && __filename)
    ? String(__filename).split("/").pop()
    : "悬浮窗运行与测试.js";
const PROBE_FILES = [
    PROBE_SELF_NAME,
    "src/app/version.js",
    "src/runtime/aijs-adapter.js",
    "src/automation/click-executor.js",
    "src/automation/auto-driver.js",
    "src/automation/state-machine.js",
    // 下面这四个是「推一半」最容易漏的（真机事故：没推 rules.js，驱动每轮 EXECUTE_TICKET 报 not a function）
    "src/chess/rules.js",
    "src/chess/transition.js",
    "src/automation/move-verifier.js",
    "src/diagnostics/log-ring.js"
];

/** 32 位 FNV-1a，避免依赖 crypto（QuickJS 里不一定有）。 */
function cheapHash(text) {
    let hash = 0x811c9dc5;
    for (let i = 0; i < text.length; i++) {
        hash ^= text.charCodeAt(i) & 0xff;
        hash = (hash * 0x01000193) >>> 0;
    }
    return ("00000000" + hash.toString(16)).slice(-8);
}

/** 关键文件的「名字:指纹」，后面接在启动日志里（adb 那边比对本地副本即可）。 */
function probeFingerprint() {
    const parts = [];
    for (let i = 0; i < PROBE_FILES.length; i++) {
        const name = PROBE_FILES[i];
        try {
            const text = String(files.read(BASE_DIR === "." ? name : (BASE_DIR + "/" + name)) || "");
            parts.push(name.split("/").pop() + ":" + (text ? cheapHash(text) : "缺失"));
        } catch (error) {
            parts.push(name.split("/").pop() + ":读取失败");
        }
    }
    return parts.join(" ");
}

function load(relative) {
    return require(BASE_DIR === "." ? relative : (BASE_DIR + "/" + relative));
}

const PROBE_BUILD_ID = load("src/app/version.js").VERSION;

const adapter = load("src/runtime/aijs-adapter.js");
const geometryModule = load("src/vision/geometry.js");
const recognizeBoard = load("tools/recognize-board.js");
const visionPipelineModule = load("src/vision/vision-pipeline.js");
const frameGateModule = load("src/vision/frame-gate.js");
// 「那一格是哪一边」的像素判据（给单格 OCR 证据用；见 ocrCell）
const pieceSideModule = load("src/vision/piece-side.js");
// 「那一格有没有墨迹」的找色计数（见 ocrCell 的 ink 字段）
const inkColorModule = load("src/vision/ink-color.js");
const bootstrapModule = load("src/app/bootstrap.js");
const pipelineFactory = load("src/vision/pipeline-factory.js");
const locateBoard = load("tools/locate-board.js");
const driverModule = load("src/automation/auto-driver.js");
const engineModule = load("src/engine/engine-router.js");   // 引擎路由：内置 JS 引擎 ⇄ Pikafish（原生 NNUE）
const cellsContract = load("src/vision/cells.js");
// 探针的开局校验要用：FEN 解析 + 盘面比对（只读，不参与循环）
const fenModule = load("src/chess/fen.js");
const legalModule = load("src/chess/legal-moves.js");
// §14 框架层：文案/令牌只定义一次（ui），配置只解析一处（app/config），日志走 diagnostics
const labelsModule = load("src/ui/labels.js");
const themeTokens = load("src/ui/theme-tokens.js");
const panelLayout = themeTokens.layoutFor((function () {
    try { return adapter.screenSize(); } catch (error) { return { width: 1080, height: 2400 }; }
}()));
const PANEL = panelLayout.PANEL;
function panelPx(base) { return Math.max(1, Math.round(base * panelLayout.scale)); }
const configModule = load("src/app/config.js");
const recorderModule = load("src/diagnostics/recorder.js");
const overlayDraw = load("src/vision/overlay-draw.js");
// 跨引擎互锁：主界面（main.js）里还有另一套驱动循环，两边一起跑会双份截图/算棋，
// 模式③还会双份点击棋盘 —— 用同一个锁文件互斥（见 src/automation/loop-lock.js）。
const loopLock = load("src/automation/loop-lock.js");
// 跨引擎互锁总线（与主界面 main.js 共用）：状态互锁 + 双向停止 + 配置双向同步。
// 锁只能「拒绝同时跑」，看不到对面在不在跑、也没法从这边停那边 —— 所以另开一条文件总线。
// A1 批次3 归一：创建口收进 bootstrap（createBus → panel-bus 单实现），装配时同一实例注入 app。
// 单格 OCR（PP-OCRv6，模型随 APK 一起打包）：直接读那一格的汉字，给「到底有没有子」一个证据
const pieceOcr = load("src/vision/piece-ocr.js");
const boardScan = load("src/vision/board-scan.js");
const pieceDetector = load("src/vision/piece-detector.js");
// 几何体检：识破「线定位成功但整行偏」的假几何（2026-09-20 真机）
const gridSanity = load("src/vision/grid-sanity.js");

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
// 模式/档位文案：**共享部分只在 src/ui/labels.js 定义**（面板自己更细的状态文案在下面追加）
const MODES = labelsModule.MODES;
const MODE_LABEL = labelsModule.MODE_LABEL;
let autorunBlocking = false;   // autorun 有致命配置错误（如拼错的通道名）→ 不自动启动

let modeKey = "watch";
let driver = null;
let running = false;
let timer = null;
let pendingDeclare = false;   // autorun：等首个观察成功后再声明「现在轮到我」
// 算棋放哪里跑：worker（独立引擎线程，默认）/ inline（本线程同步，对照用）
let computeModeKey = "worker";
// 算棋用哪个引擎："auto"（默认：有 Pikafish 就用）/ "pikafish"（强制原生）/ "js"（只内置 JS 引擎）。
// autorun 里写 "engine":"pikafish" 可切换（见 src/engine/engine-router.js）。
let engineBackendKey = "auto";
// Pikafish 参数：{evalFile, threads, hashMb}；null = 适配器默认（权重自动找 /sdcard/xiangqi-p4/pikafish.nnue）。
let pikafishOptions = null;
/** 一次观察的帧数/帧间隔（驱动默认 2 帧 + 260ms；A/B 用，见 autorun 的 observeFrames） */
let observeFramesKey = 2;
let observeGapMsKey = 260;
/** 算棋时间预算（ms）：autorun `movetimeMs` 可调（2026-09-21 提速方案：3500 → 1000~1200） */
let movetimeMsKey = 3500;
/** 两击间隔（起点点完→点终点的等待，ms）：autorun `clickSettleMs` 可调；null = 老公式 max(200, press+600) */
let clickSettleMsKey = null;
let lastThinkingLogAt = 0;    // 算棋中按秒报一次状态，不刷屏
let lastTimingLogAt = 0;      // 「耗时分期」限频（方案 §7：一轮观察拆开看，每 2s 最多一行）
let lastAccountLogAt = 0;     // 「本轮账」限频
// 一次 tick 内的账本（方案 §7）：识别被调几次/多久、重定位几次、守前台、全盘 OCR 对照
let tickObsCalls = 0;
let tickObsMs = 0;
let tickLocateCalls = 0;
let tickLocateMs = 0;
let tickGuardMs = 0;
let tickOcrBoardMs = 0;
let tickDriverMs = 0;
/** 「把游戏留在前台」上次尝试的时间（限频用，见 keepGameInFront） */
let lastKeepFrontAt = 0;
/** 上一次打印的前台读数（只在变化时打，避免刷屏） */
let lastFrontText = "";
let hideOnCapture = "auto";  // "auto" 只在面板真的压住棋盘时才藏（默认）；true/false 手动开/关
let autoAlignOn = false;      // 几何按检测框自动微调（真机实测会把子认重，默认关）
/**
 * 新视觉流水线（方案 §2 的整条链）：YOLO 框 → 交点 → 裁剪 → OCR 字形 → 颜色 → 融合 → 规则门。
 * `autorun.visionPipeline = true` 打开；不可信时自动退回旧路径（不会把循环卡死）。
 */

/** 视觉链路模式："legacy" 旧路径 / "auto" 全盘流水线 / "diff" 差分（定位一次 + 找色扫描 + 只 OCR 变化格） */
// 识别链只有字形识别（2026-09-24「其他的都不要了」）；`visionPipeline` 实例保留给旧探针/审核工具懒建，
// 日常识别路径不再碰它（YOLO/OCR 会话也不再预热）。
let visionPipeline = null;
/** 逐格 OCR 的灰度路（先灰度、读不出再彩色兜底）：autorun `"ocrGray": false` 可关（默认开） */
let ocrGrayEnabled = true;
/** OCR 主读通道："gray"（默认，先灰度）/ "color"（老顺序，彩色优先）；autorun `"ocrPrimary": "color"` 可切 */
let ocrPrimaryMode = "gray";
/** 新流水线独占的常驻 detect-only 会话；只跑 YOLO，不进入旧识别器。 */
let visionDetectorSession = null;
let lastPipelineNoteAt = 0;
/** 帧生命周期探针（autorun `"debugFrameProbe": true`）：观察循环里打「活跃原生帧」检查点，定位泄漏段。 */
let frameProbeOn = false;
/** 对方应手「占用唯一解」快路（autorun `"replyFastPath": false` 可关，做 A/B）：diff 链路占用图直接定手，免 OCR。 */
let replyFastPathKey = true;
/**
 * 识别入口（服务层）：稳定帧 → 流水线 → 规则门 → 不可信就回退旧路径。
 * 面板只把「拓帧 / 像素 / 日志 / 几何」注入进去；这条链的编排（包括回退策略）在 `services/board-service.js`
 * —— 以前面板、驱动、探针各写一遍，稳定帧与未知格统计经常漏做一步（2026-09-20 入口收口）。
 */
let boardService = null;
let app = null;   // A1：bootstrap 装配的应用（与 boardService 同源创建，会话/板服务都从这里取）
/** 稳定帧闸门（方案 §3.1）：自己保留实例，判定与限频日志交给服务层 */
let boardFrameGate = null;
// 点击通道：auto（有 Shizuku 就走 shizuku 的 `input swipe` 原地按住）/ shizuku / accessibility。
// K40 + 天天象棋实测：超短按（`input tap`）会被游戏直接丢弃，按住 ≥300ms 才选中棋子。
let clickMethodKey = "auto";
/** 藏窗后到出手的等待（ms）。autorun `clickHideSettleMs` 可调；真机 A/B 用它区分
 *  「游戏还没接纳窗口变化」与「坐标/通道问题」。 */
let clickHideSettleMs = 120;
/** 点击租约的恢复函数（buildDriver 里赋值；停止/退出时兜底调用，别把面板留在隐藏状态） */
let releaseTapLease = null;
/** 点击租约是否正持有（抓帧时不能把面板"恢复"出来） */
let clickLeaseActive = false;
// 点击按压时长（ms）。真机实测（K40 + 天天象棋）：**60ms 的注入点击会被游戏完全忽略**，
// 300ms / 400ms / 600ms 都能落子 → 默认值取 350ms（手动点「开始」跑 ③ 也必须这个量级，
// 否则每轮都是 UNCHANGED 白跑）。小米8 上短按也够用，但长按没副作用，所以统一 350。
let clickPressMs = 350;
let lastLoggedLayout = null;  // 驱动视角去重打印用
let aliveTimer = null;        // 保活心跳（见 main 里的说明）
let aliveSeconds = 0;
let lastBeatAt = 0;           // 心跳间隔：衡量「本站线程被占多久」（空载 ≈1000ms）
let lagMax = 0;
let lagCount = 0;

// ==================== 跨引擎互锁总线（面板侧） ====================
// 两个入口跑在不同 JS 引擎里，只能用文件：各自的 state 快照 + 单槽命令文件（见 panel-bus.js）。
const bus = bootstrapModule.createBus({
    owner: "floaty",
    files: files,
    log: function (text) { console.log("[bus] " + text); }
});

/** 本面板的状态快照（页面/主界面读它来显示「面板在跑什么」）。 */
function busSnapshot() {
    return {
        version: PROBE_BUILD_ID,
        running: running,
        mode: modeKey,
        side: (driver ? driver.mySide() : "w"),
        tap: clickMethodKey,
        capture: adapter.captureBackend(),
        drawMode: drawMode,
        // A1 批次2：跨引擎对齐口径（几何版本/状态机/实际引擎）——两边用同一渲染函数显示
        gridVersion: (grid ? grid.version : null),
        machineState: (driver && driver.machine) ? driver.machine.state() : "STOPPED",
        backend: engineModule.lastEngine()
    };
}

/** 总线泵：发布自己 → 收页面命令。（面板心跳 1s 调一次，见 startKeepAlive） */
function pumpBus() {
    bus.publish(busSnapshot());
    let message;
    while ((message = bus.poll())) {
        applyBusCommand(message);
    }
}

/** 收到页面的命令：stop（双向停止）与 setConfig（配置同步）。 */
function applyBusCommand(message) {
    if (message.name === "stop") {
        const why = (message.arg && message.arg.why) ? message.arg.why : "页面请求停止";
        if (running) {
            stopLoop();
            logLine("页面请求停止：" + why);
            render();
        } else {
            logLine("页面请求停止（面板本来就没跑）");
        }
        return;
    }
    if (message.name === "setConfig" && message.arg) {
        applyConfigFromPage(message.arg.key, message.arg.value);
    }
}

/**
 * 应用页面同步过来的配置。
 * ⚠️ 只应用、不回发 —— 回发会变成两边互相回弹的死循环。
 */
function applyConfigFromPage(key, value) {
    if (key === "mode" && MODES.indexOf(value) >= 0 && value !== modeKey) {
        modeKey = value;
        if (running) {
            stopLoop();
            startLoop();
        }
        logLine("页面把模式改成：" + MODE_LABEL[modeKey]);
        render();
    } else if (key === "side" && (value === "w" || value === "b") && driver && driver.mySide() !== value) {
        driver.setMySide(value);
        logLine("页面把「我方」改成：" + (value === "w" ? "红方" : "黑方") + "（旧基准已作废）");
        render();
    } else if (key === "drawEnabled") {
        // 页面那个「绘制」是它自己的计划画板；面板这边的绘制是「识别绘制」档位（人工审核用），
        // 语义不同，所以只记一行、不跟着改（避免把审核档位莫名改掉）。
        logLine("页面把「绘制」改成：" + (value ? "开" : "关") + "（面板的「识别绘制」是另一档，未跟随）");
    }
}

/** 状态栏里「页面」那一段（面板窄，只能给最少的字）。 */
function otherLineShort() {
    const other = bus.readOther();
    if (!other || !other.state) {
        return "页面：未启动";
    }
    if (!other.fresh) {
        return "页面：离线";
    }
    // A1 批次2：和页面侧同一口径 —— 运行态｜几何｜引擎｜模式（面板窄，先放最重要的，
    // 超宽由 statusLine 截断；实测模式名放前面会把几何/引擎直接截掉）
    return "页面：" + (other.state.running ? "跑中" : "未开始") +
        "｜" + (other.state.gridVersion == null ? "—" : ("v" + other.state.gridVersion)) +
        "｜" + (other.state.backend === "pikafish" ? "Pikafish"
            : (other.state.backend === "js" ? "JS" : "?")) +
        "｜" + String(MODE_LABEL[other.state.mode] || "?").replace(/\s/g, "");
}

/** 状态栏文字（心跳里每秒刷一次，写成一个函数免得两处不同步）。 */
function statusLine() {
    // 面板宽度有限：太长会在窄行里换行 → 第二行被裁。10sp 下约 27.5px/字，1004px 只放得下 ~36 字。
    const clipText = function (text, max) {
        const s = String(text == null ? "" : text);
        return s.length > max ? (s.slice(0, max - 1) + "…") : s;
    };
    const note = clipText(calibrationNote, 14);
    const fg = lastForeground == null
        ? "?"
        : (lastForeground === gamePackage ? "游戏✓" : (lastForeground === OWN_PACKAGE ? "自身✗" : lastForeground));
    return note + "｜" + (running ? "运行中" : "已停止") + "｜" + aliveSeconds + "s｜前台 " + fg +
        "｜" + clipText(otherLineShort(), 12);
}
// 默认展开状态由 main() 启动后立即切换为收起，避免遮住棋盘顶行。
let collapsed = false;
let grid = null;          // 已校准几何（会话内复用）
let calibrationNote = "未校准";
const logLines = [];
/** 屏上只显示最后几行（面板得矮到能塞进棋盘上方的空木纹带）；完整日志走 logcat（前缀 [floaty]） */
const LOG_LINES_COMPACT = themeTokens.LOG_LINES_COMPACT;    // 默认 4 行（行高 ≈ 1.35×字号 ≈ 29.7px → 日志区 128px）

/**
 * 进度摘要：「现在跑到哪一步」——从日志里抽关键节点，每项只留**最近一次**。
 * 为什么要它：完整日志刷得快、又夹着心跳噪声，盯面板看不出「卡在哪」。
 */
/** 状态机状态 → 中文。共享部分来自 src/ui/labels；面板另外补几条更长的口语化文案。 */
const STATE_LABEL = Object.assign({}, labelsModule.STATE_LABEL, {
    IDLE: "空闲",
    READY: "已就绪",
    WAIT_STABLE: "等待局面稳定",
    THINKING: "算棋中",
    PRECHECK: "出手前核对",
    SUGGESTED: "已给出建议（本模式不点击）",
    CLICKING: "点击中",
    VERIFYING: "确认落子",
    WAIT_TURN: "等轮次",
    WAIT_OPPONENT: "等对方",
    WAIT_OPPONENT_REPLY: "等对方应手",
    RECOVERING: "恢复中（重新定位）",
    PAUSED: "已暂停",
    STOPPED: "已停止"
});
const progress = {
    turn: "",        // 轮次推断结果（标准开局 / 己方 x + 对方 y）
    think: "",       // 最近一次算棋结论（着法 + 评分）
    point: "",       // 最近一次落点（含是否用了实测框中心）
    confirm: "",     // 最近一次确认结论
    fill: "",        // 最近一次未知格补齐的说明
    unknown: null,    // 最近一次观察的未知格数
    recover: 0        // 恢复（重新定位）次数
};

/** 从一行日志里更新进度摘要（识别不了就忽略）。 */
function noteProgress(line) {
    let m = null;
    if (line.indexOf("恢复：重新定位棋盘") >= 0) {
        progress.recover += 1;
        return;
    }
    m = line.match(/本轮观察：未知格 ([\d?]+)/);
    if (m) {
        progress.unknown = m[1];
        return;
    }
    if (line.indexOf("局面是标准开局") >= 0) {
        progress.turn = "标准开局（红先）";
        return;
    }
    m = line.match(/轮次推断：(.+)$/);
    if (m) {
        progress.turn = m[1];
        return;
    }
    m = line.match(/算棋：(.+)$/);
    if (m) {
        progress.think = m[1].replace(/（深度/, " d").replace(/，评分/, " ").replace(/）/g, "");
        return;
    }
    m = line.match(/建议着法 (.+?)（.*?）：评分 (.+?)，深度 (.+)$/);
    if (m) {
        progress.think = "建议 " + m[1] + " " + m[2] + " d" + m[3];
        return;
    }
    m = line.match(/落点（([\d,]+)）→（([\d,]+)）(.*)$/);
    if (m) {
        progress.point = m[1] + "→" + m[2] + (m[3] ? "［中心］" : "［交点］");
        const pair = function (text) {
            const parts = String(text).split(",");
            return { x: Number(parts[0]), y: Number(parts[1]) };
        };
        lastClickPlan = { from: pair(m[1]), to: pair(m[2]), move: "" };
        for (let i = logLines.length - 1; i >= 0 && !lastClickPlan.move; i--) {
            const hit = String(logLines[i]).match(/点击：([a-i][0-9][a-i][0-9])/);
            if (hit) {
                lastClickPlan.move = hit[1];
            }
        }
        return;
    }
    m = line.match(/确认：(\w+)：(.+)$/);
    if (m) {
        progress.confirm = m[1] + "（" + String(m[2]).slice(0, 20) + "）";
        return;
    }
    m = line.match(/按已知事实补齐：(.+)$/);
    if (m) {
        progress.fill = m[1];
    }
}

/** 状态 → 进度行颜色（用有符号 32 位 int，Java 那边要的不是 JS 的大整数）。 */
/**
 * 状态行颜色。**必须返回字符串颜色**：
 * 引擎的 `setTextColor` 走 `writeViewAttribute`，把值当字符串交给 `Color.parseColor`；
 * 传颜色整数会被转成 "-670357"，直接报 `Cannot set view attribute textColor=-670357 Unknown color`
 *（真机日志就这么来的）。字符串 `#RRGGBB` 在所有引擎版本上都安全。
 */
function stepColor() {
    let state = "";
    try {
        state = driver && driver.machine ? driver.machine.state() : "";
    } catch (error) {
        state = "";
    }
    if (state === "RECOVERING") {
        return "#FF8A80";   // 红：在恢复，得管
    }
    if (state === "WAIT_TURN" || state === "WAIT_STABLE") {
        return "#F5C56B";   // 黄：在等（正常但停滞）
    }
    if (running && (state === "THINKING" || state === "CLICKING" || state === "VERIFYING" ||
        state === "SUGGESTED" || state === "PRECHECK")) {
        return "#7FE39A";   // 绿：在干活 / 已有结论
    }
    return "#8F9AA8";       // 灰：没跑
}

/** 状态对应的图标（纯文本，不依赖字体库；颜色靠运行期换色，失败也不影响使用）。 */
function stateIcon(state) {
    if (state === "RECOVERING") {
        return "⚠";
    }
    if (state === "WAIT_TURN" || state === "WAIT_STABLE") {
        return "⋯";
    }
    if (state === "SUGGESTED") {
        return "★";
    }
    if (state === "THINKING" || state === "CLICKING" || state === "VERIFYING") {
        return "⚙";
    }
    if (state === "PAUSED" || state === "STOPPED" || state === "IDLE") {
        return "■";
    }
    return "▶";
}

/** 进度行文字（悬浮窗第二块）：**两行**，状态名中文化，一眼看出卡在哪一步。 */
function stepLine() {
    const line1 = [];
    const line2 = [];
    try {
        const state = driver && driver.machine ? driver.machine.state() : "-";
        line1.push(stateIcon(state) + " " + (STATE_LABEL[state] || state));
    } catch (error) {
        line1.push("▶ -");
    }
    if (driver && typeof driver.stats === "function") {
        const stats = driver.stats();
        line1.push("观察 " + stats.observations + " 次｜未知格 " + (progress.unknown == null ? "-" : progress.unknown) +
            "｜本帧 " + (stats.lastObserveMs || 0) + "ms");
        const fixes = [];
        if (stats.ownMovePatched) {
            fixes.push("落点补齐 " + stats.ownMovePatched);
        }
        if (stats.knownFactFills) {
            fixes.push("事实补齐 " + stats.knownFactFills);
        }
        if (stats.colorFlips) {
            fixes.push("纠色 " + stats.colorFlips);
        }
        if (progress.recover) {
            fixes.push("恢复 " + progress.recover + " 次");
        }
        if (fixes.length) {
            line1.push(fixes.join("、"));
        }
    }
    if (progress.turn) {
        line2.push("轮次 " + progress.turn);
    }
    if (progress.think) {
        line2.push("算棋 " + progress.think);
    }
    const line3 = [];
    if (progress.point) {
        line3.push("落点 " + progress.point);
    }
    if (progress.confirm) {
        line3.push("确认 " + progress.confirm);
    }
    if (progress.fill) {
        line3.push("补齐 " + progress.fill);
    }
    /**
     * 每行各自限长（2026-09-20 用户报「运行的轮次没显示完整」）。
     * ⚠️ 11sp 下约 **30 字**就顶到右边缘（原来 9sp 的 38 字是按旧字号算的）；
     *    而且**轮次必须单独占一行开头**：以前它和「算棋/落点/确认」拼在同一行、被 38 字截断掉，
     *    用户就看不到轮到谁了 —— 那是面板上最该一眼看到的信息。
     */
    const clip = function (text) {
        return text.length > 30 ? (text.slice(0, 29) + "…") : text;
    };
    const head = clip(line1.join(" │ "));
    const mid = clip(line2.length ? line2.join(" │ ") : (running ? "…" : "等待开始（点「开始」）"));
    return head + "\n" + mid + (line3.length ? ("\n" + clip(line3.join(" │ "))) : "");
}
const LOG_LINES_FULL = 12;   // 缓冲区保留行数（方便 console 侧对账）
/** 窗口高度只由 XML 的 px 决定（见 applyWindowHeight 的注释：setSize 会把窗口框体弄坏）——
 *  这个常量必须与 XML 根节点的 h 一致（改 XML 记得一起改，panelRect 的重叠判定与自动落位要用它）。 */
const WIN_H_COMPACT = PANEL.height;

function visibleLog() {
    // 心跳类日志每秒一条，会把有用的事件挤走 → 不上屏（console 里依旧有）；
    // 过长的行**折行**而不是截断（用户两次强调「显示完整」）：一条最多占 LOG_WRAP_LINES 行。
    const wrap = panelLayout.logWrap;
    const maxLines = themeTokens.LOG_WRAP_LINES;
    const rendered = [];
    for (let i = 0; i < logLines.length; i++) {
        const raw = logLines[i];
        if (/心跳间隔|保活中|心跳延迟/.test(raw)) {
            continue;
        }
        const text = String(raw).replace(/\s+/g, " ").trim();
        if (!text) {
            continue;
        }
        rendered.push(text.slice(0, wrap));
        let rest = text.slice(wrap);
        let used = 1;
        while (rest && used < maxLines) {
            const size = wrap - 2;                 // 折行缩进 2 格
            rendered.push("  " + rest.slice(0, size));
            rest = rest.slice(size);
            used += 1;
        }
        if (rest) {                                // 超过上限才收编（罕见：超长局面串/路径）
            const last = rendered.length - 1;
            rendered[last] = rendered[last].slice(0, wrap - 3) + "…";
        }
    }
    return rendered.slice(Math.max(0, rendered.length - LOG_LINES_COMPACT));
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
    recorder.log(text);
}

/**
 * 日志的实际落地：进面板环形缓冲 + 进度摘要 + logcat。
 * §14：唯一入口是 diagnostics/recorder（限频、环长度、审计导出都在那儿），
 * 这里保留原来的入参形态，调用点一个都不用改。
 */
function sinkLog(text) {
    const line = String(text);
    noteProgress(line);
    logLines.push(line);
    while (logLines.length > LOG_LINES_FULL) {
        logLines.shift();
    }
    console.log("[floaty] " + line);
    scheduleRender();   // 日志驱动的重绘做 250ms 合并，别让每个 logLine 都过一次 JS→Java 桥
}

/** 诊断记录器：限频日志（logThrottled）与审计摘要都走它。 */
const recorder = recorderModule.createRecorder({ sink: sinkLog, limit: 400, keep: 250 });

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
/**
 * 配色：面板整体是深蓝灰底，按钮按功能分组上色（绿=开始、红=停/关、蓝=定位识别），
 * 进度行与日志区做成两张「卡片」（深色底 + 亮字）。
 * ⚠️ 静态写在 XML 里 —— 运行期改色要先确定引擎暴露了 setTextColor（见 render 里那层 try）。
 */
const C = {
    panel: "#F7F8FC", title: "#20252E",
    btnNeutral: "#E8EDF5", btnNeutralFg: "#303744",
    btnPrimary: "#3486FF", btnDanger: "#C24B52", btnAccent: "#4B83DC",
    btnDim: "#E2E6ED", btnDimFg: "#586575",
    card: "#FFFFFF", step: "#2F547D",
    logBg: "#F7F8FC", logFg: "#303744", status: "#667080"
};

const UI_MODE_LABEL = { watch: "只识别", suggest: "着法建议", auto: "自动走棋" };

/**
 * 面板标题：**只写状态**（模式已经在旁边那个蓝色模式按钮上了）。
 * ⚠️ 2026-09-20：原来写「自动走棋 · 未运行」，字号提到 12sp 后就顶到模式按钮下面（用户截图里就是被压住）。
 */
function panelTitleText() {
    let state = running ? "运行中" : "未运行";
    try {
        const key = driver && driver.machine ? driver.machine.state() : null;
        if (key) {
            state = STATE_LABEL[key] || key;
        }
    } catch (error) {
        // 状态机还没建立时用兜底文案
    }
    return "象棋 · " + state;
}

/** 顶栏只放用户此刻需要判断的信息；详细原因仍留在状态行和日志。 */
function compactStateText() {
    let state = running ? "运行中" : "未运行";
    try {
        const key = driver && driver.machine ? driver.machine.state() : null;
        if (key) {
            state = STATE_LABEL[key] || key;
        }
    } catch (error) {
        // 状态机还没建立时使用 running 的兜底文案
    }
    return (UI_MODE_LABEL[modeKey] || "象棋") + " · " + state;
}

/** 按钮：统一用带底色的 <text> 冒充（<button> 在这个引擎上收不到 click，见下方长注释）。 */
function button(id, label, width, bg, fg) {
    const row = PANEL.rowHeight;
    return '    <text id="' + id + '" text="' + label + '" w="' + panelPx(width) + 'px" h="' + row + 'px" bg="' +
        (bg || C.btnNeutral) + '" radius="' + PANEL.radiusButton + '" textColor="' +
        (fg || ((bg === C.btnPrimary || bg === C.btnDanger || bg === C.btnAccent) ? "#FFFFFF" : C.btnNeutralFg)) + '" textSize="' + themeTokens.FONT.button + '" gravity="center"/>';
}

/** 行首的分组标签（对局 / 诊断） */
function rowLabel(text) {
    return '    <text text="' + text + '" w="' + panelPx(74) + 'px" h="' + PANEL.rowHeight +
        'px" textColor="' + C.status + '" textSize="' + themeTokens.FONT.button + '" gravity="center"/>';
}

/** 参数滑条：用带底色的 <text> 冒充（拖动改值；这个引擎里 text 的 touch 能到，见 button 的说明）。
 *  ⚠1 2026-09-24 真机：11sp 下 6 格会被截断（“算棋 3.5s ■□□□□” 后面就没了）——
 *     减到 4 格 + 10sp，并且留出 padding，保证「值 + 格子」完整显示。 */
function slider(id, width) {
    return '    <text id="' + id + '" text="" w="' + width + 'px" h="' + PANEL.rowHeight + 'px" bg="' +
        C.card + '" radius="' + PANEL.radiusCard + '" textColor="' + C.step + '" textSize="' +
        themeTokens.FONT.status + '" gravity="center" padding="4px"/>';
}
/** 滑条几何：条宽与第 n 条在窗口内的左偏移（要和 XML 里 paramRow 的排布保持一致）。 */
function sliderWidth() {
    return Math.round((PANEL.width - panelPx(8) * 3) / 2);
}
function sliderLeft(index) {
    return panelPx(8) + index * (sliderWidth() + panelPx(8));
}

const ROW_H = PANEL.rowHeight;
const XML = [
    '<vertical id="root" w="' + PANEL.width + 'px" h="' + PANEL.height +
    'px" bg="' + C.panel + '" radius="' + PANEL.radiusPanel + '" padding="' + panelPx(8) + 'px">',
    // 第 1 行：当前状态 + 模式 + 主操作；停止始终固定在这一行
    '  <horizontal id="primaryRow" h="' + ROW_H + 'px">',
    '    <text id="title" text="只识别 · 未运行" w="' + panelPx(320) + 'px" h="' + ROW_H + 'px" textColor="' + C.title +
    '" textSize="' + themeTokens.FONT.title + '" gravity="center_vertical" padding="10px"/>',
    button("modeBtn", "只识别", 180, C.btnAccent),
    button("startBtn", "开始", 140, C.btnPrimary),
    button("stopBtn", "停止", 130, C.btnDanger),
    button("collapseBtn", "收起", 120, C.btnDim, C.btnDimFg),
    '  </horizontal>',
    // 第 2 行：对局操作
    '  <horizontal id="gameRow" h="' + ROW_H + 'px">',
    rowLabel("对局"),
    button("locateBtn", "定位校准", 150, C.btnAccent),
    button("recognizeBtn", "识别一帧", 140, C.btnAccent),
    button("turnBtn", "轮到我", 110),
    button("sideBtn", "换我方", 110),
    button("hideBtn", "抓帧：自动", 160, C.btnDim, C.btnDimFg),
    button("alignBtn", "微调：关", 130, C.btnDim, C.btnDimFg),
    '  </horizontal>',
    // 第 3 行：诊断工具（与对局操作分层）
    '  <horizontal id="diagnosticRow" h="' + ROW_H + 'px">',
    rowLabel("诊断"),
    button("sessionBtn", "新会话", 110, C.btnDim, C.btnDimFg),
    button("selfcheckBtn", "离线自检", 140, C.btnDim, C.btnDimFg),
    button("contractBtn", "契约测试", 140, C.btnDim, C.btnDimFg),
    button("drawBtn", "绘制：关", 130, C.btnDim, C.btnDimFg),
    button("clearBtn", "清空日志", 135, C.btnDim, C.btnDimFg),
    button("closeBtn", "关闭", 105, C.btnDanger),
    '  </horizontal>',
    // 第 4 行：参数滑条（算棋时间 / 线程）—— 拖动改值，松手生效并写盘
    '  <horizontal id="paramRow" h="' + ROW_H + 'px">',
    slider("thinkSlider", sliderWidth()),
    slider("threadsSlider", sliderWidth()),
    '  </horizontal>',
    '  <text id="status" text="准备中…" w="' + (PANEL.width - panelPx(16)) +
    'px" h="' + PANEL.statusHeight + 'px" textColor="' + C.status +
    '" textSize="' + themeTokens.FONT.status + '" gravity="center_vertical" padding="2px"/>',
    '  <text id="step" text="进度：等待开始" w="' + (PANEL.width - panelPx(16)) +
    'px" h="' + PANEL.stepHeight + 'px" bg="' + C.card + '" radius="' + PANEL.radiusCard +
    '" textColor="' + C.step + '" textSize="' + themeTokens.FONT.step + '" padding="8px"/>',
    '</vertical>'
].join("\n");

/**
 * 日志窗：**单独一个窗口**放在屏幕**下方**（用户口径：「悬浮窗面板在上面，日志在下面」）。
 *
 * 为什么要拆：日志原来挤在上方面板的末尾，高度受限于「面板不能挡棋盘」
 * （4 行、8sp，还因为行高算错把第 4 行切一半）；而屏幕**下方**棋盘与游戏底部菜单之间
 * 本来就有条空带（K40: 棋盘底 ~1716 → 菜单上沿 ~2060）⇒ 放 8 行 9sp 完全够，且不挡菜单。
 * ⚠️ 这窗在屏幕下方，藏它的方向与上方面板**相反**（要往下挪出屏幕，见 hidePanel）。
 */
const LOG_XML = '<text id="logArea" text="" w="' + PANEL.width + 'px" h="' +
    PANEL.logHeight + 'px" bg="' + C.logBg + '" radius="' + PANEL.radiusPanel +
    '" textColor="' + C.logFg + '" textSize="' + themeTokens.FONT.log + '" padding="10px"/>';

let win = null;
let compactWin = null;
/** 日志窗（单独一个窗口，在屏幕下方；见 LOG_XML 的注释） */
let logWin = null;
let logPanelY = null;
let logPanelUserMoved = false;
/** 日志窗总开关（autorun 里 `"logWindow": false` 可关 —— 旧行为：日志挤在上方面板里） */
let logWindowEnabled = true;
const COMPACT_W = panelLayout.compactWidth;
const COMPACT_H = panelLayout.compactHeight;
const COMPACT_XML = [
    '<horizontal id="compactRoot" w="' + COMPACT_W + 'px" h="' + COMPACT_H + 'px" bg="' + C.panel + '" padding="6px">',
    '  <text id="compactStatus" text="只识别 · 未运行" w="' + panelPx(280) + 'px" h="' + panelPx(42) + 'px" textColor="' + C.title +
    '" textSize="11" gravity="center_vertical" padding="8px"/>',
    button("compactMode", "只识别", 155, C.btnAccent),
    button("compactStart", "开始", 120, C.btnPrimary),
    button("compactStop", "停止", 115, C.btnDanger),
    button("compactExpand", "展开", 115, C.btnDim, C.btnDimFg),
    '</horizontal>'
].join("\n");

function activePanelWindow() {
    return collapsed && compactWin ? compactWin : win;
}

/**
 * 面板「藏 / 显」。
 *
 * ⚠️ 2026-09-20 真机事故（用户报「悬浮窗看不到了」）：原来是 `win.setVisibility(false/true)`。
 *    `setVisibility(false)` 是把视图从 WindowManager 里**移除**；而这台 ROM 上再
 *    `setVisibility(true)` **加不回来** —— 窗口直接从 `dumpsys window` 里消失，
 *    脚本还在跑、日志还在刷，但用户永远看不到面板了（抓帧藏窗每帧都会走一次这条路）。
 *
 * 现在：藏 = 用 `setPosition` 把窗口**挪到屏幕上方之外**；显 = 挪回原位。
 *    `setPosition` 在这台机器上一直可靠（拖动、自动贴板都在用它）。
 *    万一某台设备把负坐标夹回屏幕内（读回来的 y ≥ 0），才退回旧的 setVisibility 方案。
 *
 * 用**计数**表示藏了几层：嵌套抓帧（比如全盘 OCR 对照里再抓一帧）不会提前把面板显示回来。
 */
let panelPosBeforeHide = null;
let hideDepth = 0;
let hideViaVisibility = false;
/** 日志窗自己的「藏前位置」（它在屏幕下方，藏的方向与上方面板相反） */
let logPosBeforeHide = null;
let logHideViaVisibility = false;
/**
 * 面板「自己动」的计数（用户报「悬浮窗在自动动」，2026-09-20）：
 *   snap = 自动贴板（每次定位/校准/几何沿用成功都会重算 y）、hide/show = 抓帧/点击时「挪出屏幕再挪回」。
 * 毎 30s 跟保活日志一起打，一眼看出到底是哪一种在动。
 */
const panelMoveCounts = { snap: 0, hide: 0, show: 0 };
/** 用户拖过面板之后**永久**不再自动贴板（以前只看「当前 y 与上次摆放差 >24」这个启发式） */
let panelUserMoved = false;
/** 自动贴板总开关（autorun 里 `"snapPanel": false` 可关） */
let snapPanelEnabled = true;

function hidePanel(reason) {
    hideDepth += 1;
    hideLogWindow(reason);          // 日志窗在下方，与面板分开藏（方向相反）
    if (panelPosBeforeHide) {
        return true;   // 已经藏着了（嵌套调用）
    }
    const panel = activePanelWindow();
    if (!panel) {
        return false;
    }
    let x = 30;
    let y = 0;
    try {
        const cx = panel.getX(true);
        const cy = panel.getY(true);
        if (isFinite(cx)) { x = cx; }
        if (isFinite(cy)) { y = cy; }
    } catch (error) {
        // 读不到就用默认位置，反正恢复时还会再读一次
    }
    const offY = -(PANEL_H + 120);   // 完全在屏幕上方之外
    try {
        if (typeof panel.setPosition === "function") {
            panel.setPosition(x, offY);
            let landed = null;
            try { landed = panel.getY(true); } catch (error) { landed = null; }
            if (!isFinite(landed) || landed < 0) {
                panelPosBeforeHide = { x: x, y: y };
                panelMoveCounts.hide += 1;
                logHideOnce(reason, x, y);
                return true;
            }
            panel.setPosition(x, y);   // 挪不动（被夹住）→ 复位后走兜底
        }
    } catch (error) {
        // 落到 setVisibility 兜底
    }
    try {
        hideViaVisibility = panel.setVisibility(false) !== false;
    } catch (error) {
        hideViaVisibility = false;
    }
    if (hideViaVisibility) {
        panelPosBeforeHide = { x: x, y: y };
        panelMoveCounts.hide += 1;
    }
    return hideViaVisibility;
}

function showPanel() {
    hideDepth = Math.max(0, hideDepth - 1);
    showLogWindow();                // 与 hidePanel 对称：日志窗单独恢复
    if (hideDepth > 0 || !panelPosBeforeHide) {
        return true;   // 还有一层没放完
    }
    const home = panelPosBeforeHide;
    panelPosBeforeHide = null;
    const panel = activePanelWindow();
    if (!panel) {
        return false;
    }
    if (hideViaVisibility) {
        hideViaVisibility = false;
        try {
            return panel.setVisibility(true) !== false;
        } catch (error) {
            logLine("恢复面板失败（这个 ROM 的 setVisibility 恢复不可靠）：" + error);
            return false;
        }
    }
    try {
        panel.setPosition(home.x, home.y);
        panelY = home.y;   // 是我们摆的位置，不算「用户拖过」
        panelMoveCounts.show += 1;
        return true;
    } catch (error) {
        logLine("恢复面板位置失败：" + error);
        return false;
    }
}

function setActivePanelVisibility(visible, reason) {
    return visible ? showPanel() : hidePanel(reason);
}

/**
 * 日志窗的藏/显：**往上挪**会正好落在棋盘上，所以它要**往下挪出屏幕**。
 * 拿不到位置或挪不动（被 ROM 夹回屏幕内）时退回 setVisibility（与面板同一套兜底）。
 */
function hideLogWindow(reason) {
    if (!logWin || logPosBeforeHide) {
        return true;   // 没建/已经藏了
    }
    let x = PANEL.marginX;
    let y = 0;
    try {
        const cx = logWin.getX(true);
        const cy = logWin.getY(true);
        if (isFinite(cx)) { x = cx; }
        if (isFinite(cy)) { y = cy; }
    } catch (error) {
        // 读不到就用默认
    }
    let screenH = 2400;
    try {
        const size = adapter.screenSize();
        if (size && size.height > 0) { screenH = size.height; }
    } catch (error) {
        // 用兜底值
    }
    try {
        if (typeof logWin.setPosition === "function") {
            logWin.setPosition(x, screenH + 120);
            let landed = null;
            try { landed = logWin.getY(true); } catch (error) { landed = null; }
            if (isFinite(landed) && landed > screenH) {
                logPosBeforeHide = { x: x, y: y };
                logHideViaVisibility = false;
                return true;
            }
            logWin.setPosition(x, y);   // 挪不动 → 复位后走兜底
        }
    } catch (error) {
        // 落到 setVisibility 兜底
    }
    try {
        logHideViaVisibility = logWin.setVisibility(false) !== false;
    } catch (error) {
        logHideViaVisibility = false;
    }
    if (logHideViaVisibility) {
        logPosBeforeHide = { x: x, y: y };
    }
    return logHideViaVisibility;
}

function showLogWindow() {
    if (!logWin || !logPosBeforeHide || hideDepth > 0) {
        return true;
    }
    const home = logPosBeforeHide;
    logPosBeforeHide = null;
    if (logHideViaVisibility) {
        logHideViaVisibility = false;
        try {
            logWin.setVisibility(true);
        } catch (error) {
            logLine("恢复日志窗失败（这个 ROM 的 setVisibility 恢复不可靠）：" + error);
        }
        return true;
    }
    try {
        logWin.setPosition(home.x, home.y);
        logPanelY = home.y;
    } catch (error) {
        logLine("恢复日志窗位置失败：" + error);
    }
    return true;
}

/**
 * 藏面板原因的记录（限频：第 1 次 + 之后每 10s 一次）。
 * 为什么要记（2026-09-20 用户报「悬浮窗在自动动」）：实测 `藏显 33 次/30s` —— 面板每帧都被
 * 「挪出屏幕再挪回」，看起来就是在自己动。只看计数分不出是「重叠判定」还是「点击租约」，
 * 所以把面板矩形、棋盘矩形、原因一起打出来。
 */
let lastHideLogAt = 0;
function logHideOnce(reason, x, y) {
    const now = Date.now();
    if (panelMoveCounts.hide > 1 && now - lastHideLogAt < 10000) {
        return;
    }
    lastHideLogAt = now;
    let pr = null;
    let br = null;
    try { pr = panelRect(); } catch (error) { pr = null; }
    try { br = boardRect(); } catch (error) { br = null; }
    logLine("藏面板（第 " + panelMoveCounts.hide + " 次，原因 " + (reason || "未注明") + "）：面板(" +
        Math.round(x) + "," + Math.round(y) + " " + (pr ? pr.width + "×" + pr.height : "?") + ")" +
        "｜棋盘" + (br ? ("(" + Math.round(br.x) + "," + Math.round(br.y) + " " + br.width + "×" + br.height + ")") : "未知"));
}

function render() {
    if (!win) {
        return;
    }
    try {
        win.title.setText(panelTitleText());
        win.status.setText(statusLine());
        win.step.setText(stepLine());
        if (logWin && logWin.logArea) {
            logWin.logArea.setText(visibleLog().join("\n"));
        }
        // 运行期换色（增强：状态绿/黄/红）。不同引擎版本暴露的方法不一样，失败就算了。
        try {
            if (win.step && typeof win.step.setTextColor === "function") {
                win.step.setTextColor(stepColor());
            }
        } catch (error) {
            // 不支持就维持 XML 里的静态色
        }
        win.modeBtn.setText(UI_MODE_LABEL[modeKey] || MODE_LABEL[modeKey]);
        win.startBtn.setText(running ? "暂停" : "开始");
        win.collapseBtn.setText(collapsed ? "展开" : "收起");
        win.hideBtn.setText("抓帧：" + (hideOnCapture === "auto" ? "自动" : (hideOnCapture ? "开" : "关")));
        win.alignBtn.setText("微调：" + (autoAlignOn ? "开" : "关"));
        win.drawBtn.setText("绘制：" + DRAW_LABEL[drawMode]);
        renderParamSliders();
        if (compactWin) {
            compactWin.compactStatus.setText(compactStateText());
            compactWin.compactMode.setText(UI_MODE_LABEL[modeKey] || MODE_LABEL[modeKey]);
            compactWin.compactStart.setText(running ? "暂停" : "开始");
        }
    } catch (error) {
        console.log("[floaty] 渲染失败：" + error);
    }
}

// ---- 参数滑条：算棋时间 / Pikafish 线程（2026-09-24 用户：在面板上滑动选择）----
const THINK_MIN_MS = 500;
const THINK_MAX_MS = 10000;
const THINK_STEP_MS = 250;
const THREAD_MIN = 1;
const THREAD_MAX = 8;
let thinkSliderEl = null;
let threadsSliderEl = null;

/** 滑条选择的持久化（storages；取不到就退化成「本次会话生效」）。 */
function paramStore() {
    try {
        if (typeof storages === "undefined" || typeof storages.create !== "function") {
            return null;
        }
        return storages.create("xiangqi-panel");
    } catch (error) {
        return null;
    }
}

/** 当前 Pikafish 线程数（面板没设过就是引擎默认 2）。 */
function currentThreads() {
    const raw = pikafishOptions && pikafishOptions.threads != null
        ? Math.round(Number(pikafishOptions.threads)) : 2;
    return isFinite(raw) && raw >= THREAD_MIN && raw <= THREAD_MAX ? raw : 2;
}

/** 滑条文案：`前缀 值 ■■□□`（4 格示意，拖到哪儿一眼看出；格数多了会被截断，见 slider 注释）。 */
function sliderBarText(prefix, valueText, fraction) {
    const cells = 4;
    const filled = Math.max(0, Math.min(cells, Math.round(fraction * cells)));
    let bar = "";
    for (let i = 0; i < cells; i++) {
        bar += i < filled ? "■" : "□";
    }
    return prefix + " " + valueText + " " + bar;
}

function renderParamSliders() {
    if (thinkSliderEl) {
        thinkSliderEl.setText(sliderBarText("算棋", (movetimeMsKey / 1000).toFixed(1) + "s",
            (movetimeMsKey - THINK_MIN_MS) / (THINK_MAX_MS - THINK_MIN_MS)));
    }
    if (threadsSliderEl) {
        const threads = currentThreads();
        threadsSliderEl.setText(sliderBarText("线程", String(threads),
            (threads - THREAD_MIN) / (THREAD_MAX - THREAD_MIN)));
    }
}

/**
 * 拖动滑条：按手指 x 落在条内的比例取值；按下/拖动即时刷新，松手 `commit` 落定。
 * 手指 x 优先用 `event.getX()`（相对该控件）；拿不到就用屏幕坐标 − 窗口 x − 条左偏移。
 */
function bindParamSlider(view, opts) {
    if (!view) {
        return;
    }
    function valueAt(event) {
        let x = null;
        try {
            if (typeof event.getX === "function") {
                const local = Number(event.getX());
                if (isFinite(local)) {
                    x = local;
                }
            }
        } catch (error) {
            x = null;
        }
        if (x == null) {
            let winX = NaN;
            try {
                winX = Number(activePanelWindow().getX(true));
            } catch (error) {
                winX = NaN;
            }
            if (!isFinite(winX)) {
                return null;
            }
            x = Number(event.getRawX()) - winX - opts.left;
        }
        const fraction = Math.max(0, Math.min(1, x / opts.width));
        const steps = Math.round(fraction * (opts.max - opts.min) / opts.step);
        return Math.max(opts.min, Math.min(opts.max, opts.min + steps * opts.step));
    }
    view.on("touch", function (event) {
        const action = event.getAction();
        if (action === 0 || action === 2) {
            const value = valueAt(event);
            if (value != null && value !== opts.get()) {
                opts.set(value);
                renderParamSliders();
            }
        } else if (action === 1) {
            const value = valueAt(event);
            if (value != null) {
                opts.set(value);
                renderParamSliders();
                opts.commit(value);
            }
        }
        return true;
    });
    renderParamSliders();
}

/**
 * 面板当前占的屏幕矩形（XML 尺寸是固定的 1020×470；位置可能被用户拖过）。
 * 拿不到就返回 null（调用方按「可能挡着」处理）。
 */
const PANEL_W = PANEL.width;
const PANEL_H = PANEL.height;   // 必须与 XML 根节点的 h 一致（重叠判定/自动落位用）
/** 日志窗高度（与 LOG_XML 的 h 一致） */
const LOG_H = PANEL.logHeight;
/** 我们自己最后一次设的 y（用于区分「用户拖过」与「我们摆的」）。 */
let panelY = null;

function panelRect() {
    try {
        const panel = activePanelWindow();
        const x = panel.getX(true);
        const y = panel.getY(true);
        if (!isFinite(x) || !isFinite(y)) {
            return null;
        }
        return { x: x, y: y, width: collapsed ? COMPACT_W : PANEL_W, height: collapsed ? COMPACT_H : PANEL_H };
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

/**
 * 面板的「安全带」（用户口径：**不许挡棋盘、不许挡底部菜单**）。
 *
 * 两条边界都按屏幕高分比例算（换机型不用改代码）：
 *   · 上界 `safeTopRatio`：避开状态栏 + 那条系统「指针位置」调试浮层（K40 实测 y≈120–160）。
 *     不避的话，面板顶栏那排按钮就被浮层盖住（用户截图就是这个问题）。
 *   · 下界 `menuTopRatio`：游戏底部「菜单/显示分析/悔棋/提示」带的上沿（K40 实测 ~2235）。
 *     ⚠️ 这是**硬约束**：挡住了那排就点不到（用户特别强调）。
 *
 * 控制面板装不下整条安全带时以**上界优先**（宁可压住棋盘顶行，也要看得见、点得到）；
 * 日志窗的两条边都尽量满足（见 `snapLogWindowBelowBoard`）。
 */
function panelSafeBand(size) {
    const h = (size && size.height > 0) ? size.height : 2400;
    const top = Math.max(statusBarHeight() + 4, Math.round(h * PANEL.safeTopRatio));
    const bottom = Math.round(h * PANEL.menuTopRatio) - PANEL.menuGap;
    return { top: top, bottom: bottom };
}

/** 把 y 夹进安全带（见 panelSafeBand）：先夹下界（不许挡底部菜单），再抬到上界。 */
function clampPanelY(y, size) {
    const band = panelSafeBand(size);
    let out = Math.round(y);
    out = Math.min(out, band.bottom - PANEL_H);
    if (out < band.top) {
        out = band.top;
    }
    return out;
}

/**
 * 日志窗落位：塞进「棋盘含子底线」与「底部菜单带上沿」之间那条空带。
 * K40 实测（2026-09-20 截图）：棋盘含子底线 ~1777、菜单带顶 ~2235 ⇒ 空带 ~458px，装得下 420 高的日志窗。
 *
 * 取位口径（用户要求「不要挡住棋盘」）：
 *   · 默认尽量**往下**贴菜单上沿（y = band.bottom - LOG_H）—— 顶边离棋盘越远越好；
 *   · 棋盘已知时**绝不压棋盘**：顶边 ≥ 棋盘矩形底 + 62（棋子圆盘/木框）+ 14 呼吸；
 *   · 真放不下（空带比窗口还窄）时也让棋盘优先（那次退让只吃菜单余量，不碰棋盘）。
 * K40 结果：y=1802 —— 上留 ~25px、下离菜单带 ~13px。
 */
function snapLogWindowBelowBoard(size) {
    if (!logWin || !snapPanelEnabled || logPanelUserMoved) {
        return;
    }
    try {
        const band = panelSafeBand(size);
        let y = band.bottom - LOG_H;
        const box = grid ? geometryModule.boundsOf(grid, 0) : null;
        if (box && isFinite(box.y) && isFinite(box.height)) {
            const boardClear = Math.round(box.y + box.height + 62) + 14;   // 棋盘底线 + 棋子圆盘 + 14px 呼吸
            if (y < boardClear) {
                y = boardClear;                                           // 空带太窄：宁可贴近菜单，也不压棋盘
            }
        }
        if (y < band.top) {
            y = band.top;
        }
        let cur = null;
        try {
            cur = logWin.getY(true);
        } catch (error) {
            cur = null;
        }
        if (logPanelY !== null && isFinite(cur) && Math.abs(cur - logPanelY) > 24) {
            logPanelUserMoved = true;    // 用户拖过日志窗 → 不再抢
            return;
        }
        if (logPanelY !== null && Math.abs(y - logPanelY) < 6) {
            logPanelY = y;
            return;
        }
        let x = PANEL.marginX;
        try {
            const curX = logWin.getX(true);
            if (isFinite(curX)) {
                x = curX;
            }
        } catch (error) {
            // 用默认 x
        }
        logWin.setPosition(x, y);
        logPanelY = y;
        if (lastLogSnapNote !== y) {
            lastLogSnapNote = y;
            logLine("日志窗已落在棋盘与底部菜单之间（y=" + y + "，上不挡棋盘、下不挡菜单）");
        }
    } catch (error) {
        // 拿不到就保持原样
    }
}
/** 日志窗落位日志的限频（同一个 y 只打一次） */
let lastLogSnapNote = null;

/**
 * 把面板摆到**棋盘上沿之上那条空带**里 —— 真机（天天象棋 1080×2400）实测：
 *   状态栏 0..80｜对手头像+名字 137..269｜**空木纹带 280..645**｜棋盘（含子上沿）647..1620｜
 *   空档 1620..1890｜「6步」+我方头像 1900..2010｜菜单/显示分析/悔棋/提示 2235..2400
 * 所以面板（PANEL.height）会自动往上贴：把整个面板塞在盘面（647）之上，既**不挡棋子**也不挡底部按钮排。
 * 用户自己拖过（当前 y 与我们上次设的差得远）就不再抢；定位到棋盘之前先用屏幕高比例兜底。
 */
function snapPanelAboveBoard() {
    if (!snapPanelEnabled) {
        return;            // autorun 里关了自动贴板：面板就停在你放的位置
    }
    if (panelUserMoved) {
        return;            // 用户拖过：永久不抢（以前只看 24px 启发式，抖一下就会被抢回去）
    }
    try {
        const size = adapter.screenSize();
        // 兜底位置 = 屏幕高 11%（K40: 2400×0.11 = 264）——真机实测正好落在棋盘上方那条空带里。
        // ⚠️ 这个比例是按 **372 高**调的；面板变高后要等量上移，否则起始几帧会压住棋盘顶行，
        //    而「压住 ⇒ 抓帧时藏面板」在部分 ROM 上恢复不可靠（真机踩过：面板再也回不来）。
        const baseY = size && size.height > 0 ? Math.round(size.height * 0.11) : 264;
        let y = baseY - Math.max(0, PANEL_H - 372);
        const box = grid ? geometryModule.boundsOf(grid, 0) : null;
        if (box && isFinite(box.y)) {
            // 棋子圆盘比「第一条线」高出约 55~60px（半径 + 边距）→ 面板底边要留出这段。
            // ⚠️ 这里只把面板**往上抬**（Math.min）——优先不挡棋盘；抬不上去（会被状态栏/浮层盖住）时
            //    由下面的 clampPanelY 接管：允许压棋盘，但绝不允许挡底部菜单。
            y = Math.min(y, Math.round(box.y - 62 - PANEL_H - 10));
        }
        y = clampPanelY(y, size);
        let cur = null;
        try {
            cur = activePanelWindow().getY(true);
        } catch (error) {
            cur = null;
        }
        if (panelY !== null && isFinite(cur) && Math.abs(cur - panelY) > 24) {
            return;   // 用户拖过面板（原生拖动也会改变 y）→ 尊重用户的选择
        }
        if (panelY !== null && Math.abs(y - panelY) < 6) {
            panelY = y;
            return;   // 没实质变化就不动窗口（少一次 updateViewLayout）
        }
        let x = 30;
        try {
            const curX = activePanelWindow().getX(true);
            if (isFinite(curX)) {
                x = curX;
            }
        } catch (error) {
            // 用默认 x
        }
        activePanelWindow().setPosition(x, y);
        panelY = y;
        panelMoveCounts.snap += 1;
        logLine("面板已贴到安全带内（y=" + y + "，不挡底部菜单；允许压棋盘");
        snapLogWindowBelowBoard(size);
    } catch (error) {
        // 拿不到就保持原样
    }
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
    // ⚠️ 2026-09-20 改：判不出来时**要藏**（旧注释担心的是 `setVisibility(true)` 恢复不了 —— 那个坑已经
    //    由「藏 = 挪到屏幕上方之外」绕过了，且 hidePanel 会读回坐标确认没被夹住）。
    //    为什么必须藏：**定位/校准帧里带上面板**是最阴的一个坑 —— 实测那一局校准报 `偏移(1,-80)`，
    //    把棋盘上沿算高 80px ⇒ 面板被自动贴板挤到最顶上还是压着棋盘 ⇒ 每帧抓帧都要藏一次
    //    ⇒ 用户看到的就是「悬浮窗一直在自己动」（真机计数：藏显 33 次/30s）。
    //    启动阶段 `grid` 还是 null（boardRect 判不出来） именно 这种情况。
    return overlap === null ? true : overlap;
}

// ---- 识别绘制（给人审的）----
// 画板是系统悬浮窗，**`screencap` 会把它一起截进去** → 抓帧前必须收掉，
// 否则绿框/文字会被当成棋盘上的东西、直接把识别搞坏（面板的 grabFrame 已处理）。
let drawMode = "off";      // off | grid | cells | full
let overlayOn = false;     // 画板窗口是否已创建
let lastOverlay = null;    // { items, text }：抓帧后立刻恢复用，避免屏幕闪空白
/** 最近一次实际点击的起终点（从日志里抽出来，画成两个小框 → 人审"点得准不准"）。 */
let lastClickPlan = null;

const DRAW_LABEL = labelsModule.DRAW_LABEL;
const DRAW_ORDER = labelsModule.DRAW_ORDER;

function overlayTextNow() {
    const parts = [];
    try {
        const state = driver && driver.machine ? driver.machine.state() : null;
        if (state) {
            parts.push("状态 " + (STATE_LABEL[state] || state));
        }
    } catch (error) {
        // 没驱动就算了
    }
    if (progress.turn) {
        parts.push("轮次 " + progress.turn);
    }
    if (progress.think) {
        parts.push("算棋 " + progress.think);
    }
    try {
        const latest = driver && typeof driver.lastResult === "function" ? driver.lastResult() : null;
        if (latest && latest.ocr) {
            const o = latest.ocr;
            parts.push("OCR " + (o.accepted || 0) + "确认/" + (o.conflicts || 0) + "冲突");
        }
    } catch (error) {
        // OCR 状态仅用于绘制，不能影响识别与走棋。
    }
    return parts.join("｜");
}

/**
 * 用「当前几何 + 这一帧的识别结果」刷新画板。
 *
 * ⚠️ **自动循环跑着的时候不画**：驱动每 2.4 秒就要抓一帧，而画板是系统悬浮窗、
 * `screencap` 会把它一起截进去（要么污染识别，要么每帧收放一次导致画板一闪一闪）。
 * 所以要审就**停一下循环、点「识别绘制」**：抓一帧 → 认一帧 → 把结果画在**冻结的**画面上，
 * 一直留着给人看（也可以截图发给我）。
 *
 * @param result  recognizer 的结果（可为 null —— 那就只画网格）
 * @param expectChars 期望盘面（验证阶段用票据的预期后局面，能把读错的格标成 X!）
 * @param options.force  true = 循环在跑也画（审核动作专用）
 */
function refreshOverlay(result, expectChars, options) {
    const force = !!(options && options.force);
    if (drawMode === "off") {
        if (overlayOn) {
            try {
                adapter.overlayHide();
            } catch (error) {
                // 收不掉也不影响业务
            }
            overlayOn = false;
            lastOverlay = null;
        }
        return;
    }
    if (!grid) {
        return;   // 还不知道棋盘在哪，画了也没意义
    }
    if (running && !force) {
        if (!refreshOverlay.warned) {
            refreshOverlay.warned = true;
            logLine("识别绘制已开启，但循环在跑：驱动每帧都要抓屏，画板会被截进图里 → 先「暂停」，再点「识别绘制」看冻结帧");
        }
        return;
    }
    if (!overlayOn) {
        try {
            overlayOn = adapter.overlayShow() === true;
        } catch (error) {
            overlayOn = false;
        }
        if (!overlayOn) {
            logLine("画板打不开（多半是没有悬浮窗权限）");
            return;
        }
    }
    const points = [];
    if (lastClickPlan && lastClickPlan.from) {
        points.push({ x: lastClickPlan.from.x, y: lastClickPlan.from.y, label: "▶" + lastClickPlan.move });
        points.push({ x: lastClickPlan.to.x, y: lastClickPlan.to.y, label: "◀" + lastClickPlan.move });
    }
    let built = { items: [], text: "" };
    try {
        built = overlayDraw.build({
            grid: grid,
            result: result,
            expectChars: expectChars || null,
            mode: drawMode,
            points: drawMode === "grid" ? null : points,
            extraText: overlayTextNow()
        });
    } catch (error) {
        logLine("绘制失败：" + error);
        return;
    }
    lastOverlay = built;
    try {
        adapter.overlayUpdate(built.items, built.text);
    } catch (error) {
        // 画不上去不影响业务
    }
}

/**
 * 抓一帧（优先 Shizuku 的 screencap，回退应用截屏）。
 *
 * 抓帧期间可能要先把面板藏起来（面板浮在棋盘上会把上几行子盖掉）——
 * 但**藏+等 90ms+恢复** 每帧要花约 100ms，所以默认 "auto"：面板不在棋盘上就不藏。
 *
 * ⚠️ 识别画板**不在这里收放**：收放（remove/add 窗口）会让画板一闪一闪、而且一半时间看不见。
 * 改成「审核时画一次冻结帧」：循环跑着的时候不画（见 refreshOverlay 的说明）。
 */
function grabFrame() {
    if (!needHideForCapture()) {
        return rememberRecentFrame(grabFrameRaw());
    }
    let hidden = false;
    try {
        hidden = setActivePanelVisibility(false, "抓帧") !== false;
        if (hidden) {
            sleep(90);   // 给 WindowManager 一点时间真的移除窗口，否则截屏里还会有面板
        }
    } catch (error) {
        hidden = false;
    }
    try {
        return rememberRecentFrame(grabFrameRaw());
    } finally {
        // ⚠️ 点击租约期间不许把面板恢复出来：那样等于在两次点击之间又动了一次窗口，
        // 游戏会丢掉"已选中"（真机定位到根因的一条）。
        if (hidden && !clickLeaseActive) {
            try {
                setActivePanelVisibility(true);
            } catch (error) {
                logLine("恢复面板失败：" + error);
            }
        }
    }
}

/**
 * 「最近一帧」短缓存：驱动的证据调用（`ocrCell` / `ocrBoard`）**紧跟在一轮观察之后**，
 * 那一帧还是热的 —— 再抓一次是白花（抓帧 200~300ms，要藏窗的话还要再加 ~90ms）。
 *
 * 真机实测（2026-09-20，180s）：`ocrCell` 平均 **~1s/次 × 26 次 = 27s**、`ocrBoard` **~1.1s × 25 = 29s**，
 * 两项合起来占了三分之一的时间 —— 而它们要的证据就在刚刚那一帧里。
 *
 * ⚠️ TTL 取 800ms：两次点击手势之间约 1s，跨点击复用旧帧会把「还没落的子」当证据。
 * ⚠️ 几何版本变了（重新定位过）也不复用 —— 旧帧按新几何读会全错格。
 */
const FRAME_REUSE_MS = 800;
let recentFrame = null;      // { image, at, geometry }；这里拥有 image，替换/过期/停止时负责释放

/**
 * 清掉最近帧并释放原生句柄。
 * AI.js Pro 的 NativeFrame 存在 C++ FrameStore；丢掉 JS 引用不会自动 recycle，长循环必须显式释放。
 */
function clearRecentFrame() {
    const cached = recentFrame;
    recentFrame = null;      // 先断开引用，避免 recycle 报错时留下半失效缓存
    if (cached && cached.image) {
        try {
            adapter.releaseFrame(cached.image);
        } catch (error) {
            // 缓存释放失败不能中断识别循环；原生帧统计会暴露持续增长。
        }
    }
}

function rememberRecentFrame(frame) {
    if (frame && frame.image) {
        // grabFrame() 的调用方只借用 image；recentFrame 是唯一所有者。
        // 下一帧接管缓存前先释放旧帧，避免 NativeFrameStore 随观察次数线性增长。
        if (!recentFrame || recentFrame.image !== frame.image) {
            clearRecentFrame();
        }
        recentFrame = { image: frame.image, at: Date.now(), geometry: diffGeometryKey() };
    }
    return frame;
}
function recentFrameImage() {
    if (!recentFrame) {
        return null;
    }
    if (Date.now() - recentFrame.at > FRAME_REUSE_MS) {
        clearRecentFrame();
        return null;
    }
    if (recentFrame.geometry !== diffGeometryKey()) {
        clearRecentFrame();
        return null;
    }
    return recentFrame.image;
}

function nativeFrameStatsText() {
    try {
        if (typeof __aiNativeFrameStats === "function") {
            const stats = __aiNativeFrameStats();
            return "｜原生帧 活跃 " + stats.activeFrames + "，池 " + stats.poolCount +
                "（" + Math.round(stats.poolBytes / 1048576) + "MB）";
        }
    } catch (error) {
        // 统计能力不可用不影响业务。
    }
    return "";
}

/** 帧生命周期探针：读一眼当前活跃原生帧数（能力不可用返回 -1）。 */
function frameProbeActive() {
    try {
        if (typeof __aiNativeFrameStats === "function") {
            return __aiNativeFrameStats().activeFrames;
        }
    } catch (error) {
        // 探针不可用不影响业务。
    }
    return -1;
}

/** 审核绘制的落盘路径（帧 + 画板内容）—— 方便离线审，也方便把人审结果给出去。 */
const AUDIT_FRAME = FRAME_DIR + "/audit-frame.png";
const AUDIT_JSON = FRAME_DIR + "/audit-overlay.json";

// ---------------------------------------------------------------- 单手探针（通道对照实验用）

/**
 * 单手探针的落盘路径：原始帧 + 结论 JSON。
 *
 * 为什么要它（见 docs/reports/2026-09-14_…§10）：真机上「普通走子能成、吃子不成」，
 * 但在区分「通道差异 / 坐标偏差 / 选中未成立 / 游戏真的过滤自动化事件」之前不能下结论。
 * 探针做的事：**只发一手的两次手势**，每一步都留原始帧与像素证据，并把实际通道、
 * 真实手势名、坐标来源（实测圆心 / 网格交点）、起止时刻全部落盘。
 */
const PROBE_JSON = FRAME_DIR + "/tap-probe.json";
function probePng(name) {
    return FRAME_DIR + "/tap-probe-" + name + ".png";
}

/** 采样方框里「与参考帧不同」的像素数（稀疏采样，够用且快）。 */
function diffBox(refFrame, frame, box, step) {
    const s = step == null ? 4 : step;
    let changed = 0;
    let total = 0;
    for (let y = box.y1; y <= box.y2; y += s) {
        for (let x = box.x1; x <= box.x2; x += s) {
            const a = adapter.rgbAt(refFrame, x, y);
            const b = adapter.rgbAt(frame, x, y);
            total += 1;
            if (!a || !b) {
                continue;
            }
            if (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) > 60) {
                changed += 1;
            }
        }
    }
    return { changed: changed, total: total };
}

/** 以某个格点为中心、边长 2*margin 的方框（点的位置 = 实际发送的坐标）。 */
function boxAround(x, y, margin) {
    const m = margin == null ? 46 : margin;
    return { x1: Math.round(x - m), y1: Math.round(y - m), x2: Math.round(x + m), y2: Math.round(y + m) };
}

/** 计步器区域（K40：≈660~900 × 2050~2160）——「这手到底落没落子」的真值判据之一。 */
function stepBoxNow() {
    const screen = adapter.screenSize ? adapter.screenSize() : null;
    if (screen && screen.height > 0) {
        const y2 = Math.round(screen.height * 0.90);
        const y1 = Math.round(screen.height * 0.854);
        return { x1: Math.round(screen.width * 0.61), y1: y1, x2: Math.round(screen.width * 0.83), y2: y2 };
    }
    return { x1: 660, y1: 2050, x2: 900, y2: 2160 };
}

/**
 * 单手探针：只发**一手**（起点一格 + 落点一格）的两次手势，不做自动搜索、不做后续重试。
 *
 * autorun 用法：
 *   "tapProbe": { "fromCol": 1, "fromRow": 7, "toCol": 1, "toRow": 0,
 *                 "channel": "auto", "pressMs": 350, "gapMs": 600, "postMs": 1500, "label": "capture-b2x" }
 *
 * · 起点 tap 之后先看「棋面有没有反应」（选中高亮/提示点），**没反应就终止**，不发落点手势；
 * · 全程把面板窗口藏起来（保留宿主与服务：Shizuku / 无障碍都还要用），finally 恢复；
 * · 每个环节都落盘 PNG + 结论 JSON，坐标可离线叠十字核对（见 report §10.2）。
 */
function runTapProbe(config) {
    const cfg = config || {};
    const label = cfg.label ? String(cfg.label) : "probe";
    const channel = cfg.channel ? String(cfg.channel) : clickMethodKey;
    const pressMs = cfg.pressMs != null ? Math.round(Number(cfg.pressMs)) : clickPressMs;
    const gapMs = cfg.gapMs != null ? Math.round(Number(cfg.gapMs)) : Math.max(300, pressMs + 250);
    const postMs = cfg.postMs != null ? Math.round(Number(cfg.postMs)) : 1500;
    const report = {
        label: label, buildId: PROBE_BUILD_ID, at: new Date().toISOString(),
        requestedChannel: channel, pressMs: pressMs, gapMs: gapMs, postMs: postMs,
        gamePackage: gamePackage, steps: [], verdict: "未完成"
    };

    // 前台守卫：注入的点击必须落在游戏上（自家透明宿主在前台时点击会被自己吃掉）
    const guard = foregroundGuard();
    report.foregroundAfterGuard = guard ? (guard.package || "") : "";
    report.foregroundGuardOk = !!(guard && guard.ok);
    if (!report.foregroundGuardOk) {
        report.verdict = "前台不是游戏，未发手势";
        logLine("探针 " + label + "：" + report.verdict + "（当前 " + (report.foregroundAfterGuard || "未知") + "）");
        saveProbeReport(report);
        return report;
    }

    let hidden = false;
    try {
        // 整轮藏面板（不是"抓帧才藏"）：两次手势期间棋盘上不能有我们自己的窗口
        hidden = setActivePanelVisibility(false, "手势探针") !== false;
        if (hidden) {
            sleep(140);
        }
        const grid = ensureProbeGrid();
        if (!grid) {
            report.verdict = "定位失败，未发手势";
            logLine("探针 " + label + "：" + report.verdict);
            saveProbeReport(report);
            return report;
        }
        // 坐标来源可选："lattice"（网格交点 = 棋子圆心）/ "center"（检测框实测圆心）。
        // 实验目的：区分「游戏命中判定偏严（必须打实测圆心）」与「坐标无关」。
        const needCenters = cfg.fromSource === "center" || cfg.toSource === "center";
        const recognized = needCenters ? probeRecognize(grid) : null;
        const centers = recognized && recognized.centers ? recognized.centers : null;
        report.centersAvailable = !!centers;
        const from = probePoint(grid, cfg.fromCol, cfg.fromRow, cfg.fromSource, centers);
        const to = probePoint(grid, cfg.toCol, cfg.toRow, cfg.toSource, centers);
        report.geometryVersion = grid.version;
        report.geometry = { originX: grid.originX, originY: grid.originY, stepX: grid.stepX, stepY: grid.stepY };
        report.from = Object.assign({ col: cfg.fromCol, row: cfg.fromRow }, from);
        report.to = Object.assign({ col: cfg.toCol, row: cfg.toRow }, to);

        let tapper;
        try {
            tapper = adapter.createTap({ pressMs: pressMs, method: channel });
        } catch (error) {
            report.verdict = "通道不可用：" + error;
            logLine("探针 " + label + "：" + report.verdict);
            saveProbeReport(report);
            return report;
        }
        report.actualChannel = tapper.method;

        const before = grabFrameRaw();
        if (before) {
            images.save(before.image, probePng(label + "-before"));
            // 帧尺寸 / 屏幕尺寸 / 换算比例（§10.2）：识别坐标是帧上的，点击坐标是屏幕上的，
            // 两者不等比时（缩放/旋转）必须能从日志里看出来。
            try {
                const info = adapter.sizeOf(before.image);
                const screen = adapter.screenSize ? adapter.screenSize() : null;
                report.frame = {
                    width: info ? info.width : null,
                    height: info ? info.height : null,
                    screenWidth: screen ? screen.width : null,
                    screenHeight: screen ? screen.height : null,
                    scaleX: (info && screen && info.width) ? (screen.width / info.width) : null,
                    scaleY: (info && screen && info.height) ? (screen.height / info.height) : null
                };
                logLine("探针帧：" + (info ? info.width + "×" + info.height : "?") +
                    "，屏幕 " + (screen ? screen.width + "×" + screen.height : "?") +
                    "，换算 " + (report.frame.scaleX ? report.frame.scaleX.toFixed(3) : "?") + "×" +
                    (report.frame.scaleY ? report.frame.scaleY.toFixed(3) : "?"));
            } catch (error) {
                logLine("探针帧尺寸读取失败：" + error);
            }
        }
        // 开局校验（§10.2）：32 子 + 与标准开局一致，否则本轮作废（除非调用方显式关掉）。
        if (cfg.expectOpening !== false) {
            const check = probeCheckOpening(grid);
            report.openingCheck = check;
            if (!check.ok) {
                report.verdict = "开局校验不通过，未发手势：" + check.reason;
                logLine("探针 " + label + "：" + report.verdict);
                saveProbeReport(report);
                return report;
            }
        }
        logLine("探针 " + label + "：请求通道=" + channel + " 实际=" + tapper.method +
            " 按压=" + pressMs + "ms｜起点格(" + cfg.fromCol + "," + cfg.fromRow + ")→(" + from.x + "," + from.y + ")｜" +
            "落点格(" + cfg.toCol + "," + cfg.toRow + ")→(" + to.x + "," + to.y + ")｜几何v" + grid.version);

        // ① 起点手势
        report.focusBeforeFrom = adapter.focusedWindowPackage ? adapter.focusedWindowPackage() : null;
        report.windowsBeforeFrom = adapter.windowsAt ? adapter.windowsAt(from.x, from.y) : [];
        logLine("探针出手前：焦点=" + (report.focusBeforeFrom || "读不到") +
            "，覆盖该点的窗口 " + (report.windowsBeforeFrom.length ? JSON.stringify(report.windowsBeforeFrom) : "（未查到）"));
        const r1 = tapper.tap(from.x, from.y, { pressMs: pressMs });
        report.steps.push({
            step: "tap-from", x: from.x, y: from.y, ok: !!r1.ok, method: r1.method,
            command: r1.command || null, error: r1.error || "",
            startedAtMono: r1.startedAtMono, finishedAtMono: r1.finishedAtMono
        });
        logLine("探针 手势1：ok=" + (r1.ok ? "是" : "否") + " 手势=" + r1.method +
            (r1.command ? " 命令「" + r1.command + "」" : "") + (r1.error ? " 错误：" + r1.error : ""));

        sleep(gapMs);
        const selected = grabFrameRaw();
        if (selected) {
            images.save(selected.image, probePng(label + "-selected"));
        }
        const selDiff = (before && selected) ? diffBox(before.image, selected.image, boxAround(from.x, from.y)) : null;
        report.selectionDiff = selDiff;
        const selectedOk = !!(selDiff && selDiff.changed >= 12);   // 选中会有高亮/提示，明显超过噪声
        report.selected = selectedOk;
        logLine("探针 选中检查：" + (selectedOk ? "有反应" : "**没反应**") +
            (selDiff ? "（该格变化 " + selDiff.changed + "/" + selDiff.total + " 采样点）" : "（没有帧）"));
        if (!selectedOk) {
            report.verdict = "起点未选中，按规则终止（不发落点手势）";
            logLine("探针 " + label + "：" + report.verdict);
            saveProbeReport(report);
            return report;
        }

        // ② 落点手势（必须等第一次手势完成后再发）
        const r2 = tapper.tap(to.x, to.y, { pressMs: pressMs });
        report.steps.push({
            step: "tap-to", x: to.x, y: to.y, ok: !!r2.ok, method: r2.method,
            command: r2.command || null, error: r2.error || "",
            startedAtMono: r2.startedAtMono, finishedAtMono: r2.finishedAtMono,
            gapAfterPreviousMs: (r2.startedAtMono != null && r1.finishedAtMono != null)
                ? (r2.startedAtMono - r1.finishedAtMono) : null
        });
        logLine("探针 手势2：ok=" + (r2.ok ? "是" : "否") + " 手势=" + r2.method +
            (r2.command ? " 命令「" + r2.command + "」" : "") + (r2.error ? " 错误：" + r2.error : ""));

        sleep(gapMs);
        const after1 = grabFrameRaw();
        if (after1) {
            images.save(after1.image, probePng(label + "-after-1"));
        }
        sleep(postMs);
        const after2 = grabFrameRaw();
        if (after2) {
            images.save(after2.image, probePng(label + "-after-2"));
        }

        if (before && after2) {
            report.sourceAfter = diffBox(before.image, after2.image, boxAround(from.x, from.y));
            report.targetAfter = diffBox(before.image, after2.image, boxAround(to.x, to.y));
            report.stepAfter = diffBox(before.image, after2.image, stepBoxNow());
            const srcMoved = report.sourceAfter.changed >= 30;      // 子离开起点 = 大面积变化
            const dstChanged = report.targetAfter.changed >= 30;
            const stepChanged = report.stepAfter.changed >= 6;      // 计步器数字变了
            report.verdict = (srcMoved && dstChanged)
                ? ("落子成立（起点清空 + 落点变化" + (stepChanged ? " + 计步器变化" : "") + "）")
                : (!srcMoved && !dstChanged ? "两格都没变（手势没被游戏采纳）"
                    : "部分变化，需要人工看图");
        } else {
            report.verdict = "缺帧，无法判定";
        }
        logLine("探针 " + label + " 结论：" + report.verdict +
            "｜起点变化 " + (report.sourceAfter ? report.sourceAfter.changed : "?") +
            "｜落点变化 " + (report.targetAfter ? report.targetAfter.changed : "?") +
            "｜计步器变化 " + (report.stepAfter ? report.stepAfter.changed : "?"));
        saveProbeReport(report);
        return report;
    } finally {
        if (hidden) {
            try {
                setActivePanelVisibility(true);
            } catch (error) {
                logLine("探针恢复面板失败：" + error);
            }
        }
    }
}

/** 探针用几何：已有就直接用，没有就现定位一次。 */
function ensureProbeGrid() {
    if (grid) {
        return grid;
    }
    try {
        locateAndCalibrate();
    } catch (error) {
        logLine("探针定位异常：" + error);
    }
    return grid || null;
}

/**
 * 探针用的识别：拿每格的**实测框圆心**（center 模式要用）。
 * ⚠️ 不画覆盖层（recognizeOnce 会画）—— 手势期间屏幕上不能有我们自己的东西。
 */
function probeRecognize(grid) {
    const grabbed = grabFrameRaw();
    if (!grabbed) {
        return null;
    }
    try {
        return recognizeBoard.run(grabbed.image, {
            ok: true, method: "floaty-probe", grid: grid,
            imageObject: grabbed.image, frameObject: grabbed.image
        }, { orientation: "RED_BOTTOM", confidence: 0.25, autoAlign: false }, null);
    } catch (error) {
        logLine("探针识别失败：" + error);
        return null;
    }
}

/**
 * 计算探针要发的坐标。
 *   · "lattice"（默认）：`geometry.pointAt` = 网格交点 = **棋子圆心**（象棋棋子就摆在交点上）；
 *   · "center"：该格最近一次识别的**检测框实测圆心**（需通过残差/归属校验，否则退回交点）。
 */
function probePoint(grid, col, row, mode, centers) {
    const lattice = geometryModule.pointAt(grid, col, row);
    const point = {
        x: Math.round(lattice.x), y: Math.round(lattice.y),
        usedCenter: false, source: "网格交点",
        latticeX: Math.round(lattice.x), latticeY: Math.round(lattice.y)
    };
    if (mode !== "center" || !centers) {
        return point;
    }
    const entry = centers[row * geometryModule.COLS + col];
    if (!entry || !isFinite(entry.x) || !isFinite(entry.y)) {
        point.source = "网格交点（该格没有实测框）";
        return point;
    }
    const snapped = geometryModule.nearest(grid, entry.x, entry.y);
    if (!snapped || !snapped.inside || snapped.col !== col || snapped.row !== row) {
        point.source = "网格交点（实测框不属于该格）";
        return point;
    }
    point.x = Math.round(entry.x);
    point.y = Math.round(entry.y);
    point.usedCenter = true;
    point.source = "实测框圆心";
    point.normalizedResidual = geometryModule.normalizedResidual(grid, entry.x, entry.y);
    return point;
}

/**
 * 探针开局校验（§10.2）：子力 32 且与标准开局完全一致。
 * 为什么要：真机上曾经“重置开局”实际没重开（选中/悔棋残留），后面所有对照都白做。
 */
function probeCheckOpening(grid) {
    const recognized = probeRecognize(grid);
    if (!recognized || !recognized.ok || !recognized.chars) {
        return { ok: false, reason: "识别失败，拿不到盘面（" + (recognized && recognized.reason ? recognized.reason : "无结果") + "）" };
    }
    const counts = cellsContract.count(recognized.chars);
    if (recognized.unknownCells > 0) {
        return { ok: false, reason: "盘面有 " + recognized.unknownCells + " 个未知格" };
    }
    if (counts && counts.pieces !== 32) {
        return { ok: false, reason: "子力不是 32（实际 " + counts.pieces + "）" };
    }
    let start = null;
    try {
        start = fenModule.parseFen(fenModule.START_FEN);
    } catch (error) {
        start = null;
    }
    if (start && start.ok && !(legalModule && legalModule.boardMatches
        ? legalModule.boardMatches(recognized.chars, start.board)
        : recognized.chars.join("") === start.board.join(""))) {
        return { ok: false, reason: "盘面不是标准开局（子力 " + (counts ? counts.pieces : "?") + "）" };
    }
    return { ok: true, reason: "标准开局，子力 " + (counts ? counts.pieces : "?") };
}

function saveProbeReport(report) {
    try {
        if (typeof files === "undefined" || !files || typeof files.write !== "function") {
            return;
        }
        files.write(PROBE_JSON, JSON.stringify(report, null, 2));
        logLine("探针结论已落盘：" + PROBE_JSON);
    } catch (error) {
        logLine("探针结论落盘失败：" + error);
    }
}

/**
 * **识别绘制（审核）**：抓一帧 → 认一帧 → 把结果画在冻结的画面上，并落盘一份。
 *
 * 这是「人审」的入口：屏幕上能直接看到
 *   · 网格线是否穿过棋子圆心（几何对齐）
 *   · 每格读成了什么子 + 该格置信度（兵种/颜色读错一眼可见）
 *   · 原始检测框（漏检、同一个子被检测两次）
 *   · 本手实际点击的起点/落点（点得准不准）
 * 跑自动循环时不给画（驱动每帧抓屏会把画板截进去），所以让用户先暂停。
 */
function auditDraw() {
    if (running) {
        logLine("循环在跑，没法画审核帧：先点「暂停」，再点「识别绘制」（驱动每帧都要抓屏，画板会被截进图里）");
        return;
    }
    if (!grid) {
        const located = locateAndCalibrate();
        if (!located) {
            logLine("识别绘制：还没有几何（定位失败）");
            return;
        }
    }
    const grabbed = grabFrame();
    if (!grabbed) {
        logLine("识别绘制：拿不到帧");
        return;
    }
    const result = recognizeBoard.run(grabbed.image, {
        ok: true, method: "floaty-audit", grid: grid,
        imageObject: grabbed.image, frameObject: grabbed.image
    }, {
        orientation: "RED_BOTTOM",
        confidence: 0.25,
        autoAlign: false
    }, null);
    if (!result || !result.ok) {
        logLine("识别绘制：识别失败（" + ((result && result.reason) || "无结果") + "）");
        return;
    }
    if (drawMode === "off") {
        drawMode = "cells";   // 点这个按钮就是要看图：先给最干净的一档（每格的字 + 置信度）
    }
    refreshOverlay(result, null, { force: true });
    const counts = result.chars ? cellsContract.count(result.chars) : null;
    let saved = "";
    try {
        if (typeof images !== "undefined" && images && typeof images.save === "function") {
            images.save(grabbed.image, AUDIT_FRAME);
            saved = AUDIT_FRAME;
        }
        if (typeof files !== "undefined" && files && typeof files.write === "function" && lastOverlay) {
            files.write(AUDIT_JSON, JSON.stringify({
                at: new Date().toString(),
                text: lastOverlay.text,
                items: lastOverlay.items,
                layout: result.layout,
                unknownCells: result.unknownCells,
                detectionCount: result.detectionCount
            }));
            saved += (saved ? " / " : "") + AUDIT_JSON;
        }
    } catch (error) {
        saved += "（落盘失败：" + error + "）";
    }
    logLine("识别绘制：子 " + (counts ? counts.pieces : "?") + " 空 " + (counts ? counts.empty : "?") +
        " 未知 " + result.unknownCells + "｜检测框 " + result.detectionCount +
        "｜已画在屏幕上（截图/录屏就能发给我）" + (saved ? "｜落盘 " + saved : ""));
}


function grabFrameRaw() {
    const caps = adapter.capabilities();
    if (caps.captureShell) {
        // ★ 收口到适配器（2026-09-20）：shell 抓帧的 **raw 快通道**（`screencap` 不加 -p +
        //   `images.readRaw`，实测 202ms vs PNG 958ms）就实现在 `aijs-adapter.captureViaShell`。
        //   面板原来自己写了一遍 `screencap -p` ⇒ 引擎刚加的快通道在这条路上根本用不上
        //   （真机：适配器 202ms vs 面板 1011ms，就是这个重复实现造成的）。
        try {
            const frame = adapter.capture("full", 2000, { backend: "shell" });
            if (frame) {
                return { image: frame, path: null, channel: adapter.captureChannel() || ("shell(" + caps.captureShellBackend + ")") };
            }
        } catch (error) {
            logLine("shell 截图失败：" + error);
        }
    }
    try {
        if (!adapter.requestCapture("portrait")) {
            return null;
        }
        const frame = adapter.capture("full", 2000, { backend: "screen" });
        if (frame) {
            return { image: frame, path: null, channel: "screen" };
        }
    } catch (error) {
        logLine("应用截屏失败：" + error);
    }
    return null;
}

/**
 * 几何体检：拿检测框落在第几行来验一下「定位给出来的网格到底对不对」。
 * ⚠️ 2026-09-20 真机：线定位**成功**也可能是整行偏（origin.y=590 vs 真实 ~690），
 * 而且精校准照样报「未知格 0」—— 偏移接近一整格时 90 个交点全落在格子上，只是全错一行。
 * 那种几何会让「底线跑到第 1 行、真底线掉到网格外」⇒ 基准/票据/轮次全对不上。
 *
 * @return { ok, reason, rows } —— 检测不可用时一律放行（不能因为体检本身把链路卡死）
 */
function checkGridRows(image, candidate) {
    if (!image || !candidate) {
        return { ok: true, reason: "没有帧或候选几何，跳过体检" };
    }
    if (!visionDetectorSession) {
        try {
            visionDetectorSession = recognizeBoard.createDetectionSession({ autoAlign: false });
        } catch (error) {
            return { ok: true, reason: "检测会话不可用，跳过体检" };
        }
    }
    let boxes = [];
    try {
        const detected = visionDetectorSession.detect(image, candidate, {});
        boxes = pieceDetector.normalizeBoxes((detected && detected.raw) || []);
    } catch (error) {
        return { ok: true, reason: "检测异常，跳过体检" };
    }
    if (!boxes.length) {
        return { ok: true, reason: "没检出任何框，跳过体检" };
    }
    const mapped = pieceDetector.mapToCells(boxes, candidate, { autoAlign: false });
    const verdict = gridSanity.suspect(gridSanity.rowsOf((mapped && mapped.cells) || []));
    // ★ 把「落在网格内的框数」也带出去：平移救回要靠它比好坏（框数变多才说明平移救对了方向）
    verdict.pieces = (mapped && mapped.cells) ? mapped.cells.length : 0;
    return verdict;
}

/**
 * 给几何补上 `quality`：状态机的「几何可信」判定要它（`geometry.isTrustworthy`），
 * 而 `pickGrid` / `calibrateGrid` 出来的 grid 不带这个字段。
 * ⚠️ 真机 2026-09-20：校准明明报「未知格 0」，状态机却是 `RECOVERING｜几何不可信：`（连原因都没有）
 * —— 因为 `quality` 缺字段 ⇒ `isTrustworthy` 直接 false。
 * 残差用**检测框到交点**的归一化距离（与 piece-detector.mapToCells 同一把尺）。
 */
function attachGridQuality(image, candidate) {
    if (!candidate || (candidate.quality && candidate.quality.inlierCount)) {
        return candidate;
    }
    if (!image) {
        return candidate;
    }
    try {
        if (!visionDetectorSession) {
            visionDetectorSession = recognizeBoard.createDetectionSession({ autoAlign: false });
        }
        const detected = visionDetectorSession.detect(image, candidate, {});
        const boxes = pieceDetector.normalizeBoxes((detected && detected.raw) || []);
        const mapped = pieceDetector.mapToCells(boxes, candidate, { autoAlign: false });
        const cells = (mapped && mapped.cells) || [];
        /**
         * 几何质量：残差取**鲁棒最大值**（丢最差的 10%），单个跑偏的检测框不该把整块几何否掉。
         * ⚠️ 真机 2026-09-20：pickGrid+校准落下来的网格与交点是固定偏 ~⅓ 格的
         *    （实测样本 24、mean≈0.335、max≈0.369），而几何可信阀值是 0.35 ⇒ 永远「几何不可信」。
         *    而颜色/点击都是锚在**检测框**上的（不是交点）—— 偏零点几格不影响判色与落点；
         *    真正致命的「整行/整列偏」已经由 `grid-sanity` 的极端行体检拦住了。
         */
        const residuals = [];
        for (let i = 0; i < cells.length; i++) {
            residuals.push(Number(cells[i].residual) || 0);
        }
        residuals.sort(function (a, b) { return a - b; });
        const keep = Math.max(1, Math.ceil(residuals.length * 0.9));
        const inliers = residuals.slice(0, keep);
        let sum = 0;
        for (let i = 0; i < inliers.length; i++) {
            sum += inliers[i];
        }
        candidate.quality = {
            sampleCount: cells.length,
            inlierCount: inliers.length,
            maxResidual: inliers.length ? inliers[inliers.length - 1] : 0,
            meanResidual: inliers.length ? sum / inliers.length : 0
        };
        logLine("几何质量：样本 " + cells.length + "｜鲁棒最大残差 " + candidate.quality.maxResidual.toFixed(3) +
            "（丢最差 10%；状态机的「几何可信」看它）");
    } catch (error) {
        logLine("几何质量计算失败（照旧放行）：" + error);
    }
    return candidate;
}

/** 「几何沿用」允许的最大未定格数（超过就认为几何真的不对，走全量校准） */
const GEOMETRY_REUSE_MAX_UNKNOWN = 4;
/**
 * 最近一帧识别读出来的「未定格」数（每次 recognize 成功后更新）。
 * 用途：卡「几何沿用」的假阳性 —— 真机 2026-09-21 11:26：在**结算/复盘页**上调的几何
 * （复盘页棋盘下移 ~160px）被带进新局；找色占用扫描在错网格上也几乎全格「有子」（采样框
 * 跨在两枚棋子中间）⇒ 体检假阳性 ⇒ 每帧 17 个未定格死循环。未定格成堆时一律不许沿用。
 */
let lastPipelineUnknown = 0;

/**
 * 把几何的「交点」钉到**棋子视觉中心**上。
 *
 * 为什么需要（2026-09-21 深夜真机 now55，差分流一整晚的错格根因）：
 *   · `calibrateGrid` 是**拿 OCR 结果当代价**（未知格/丢子/修复）扫偏移选优的，可能停在
 *     一个「读着差不多、其实压着棋子下缘」的偏移上：真机实测起点 (88,730)、同帧检测框质心
 *     在 (95,705) —— 交点在棋子中心下方 ~25px；
 *   · 而逐格裁框（`cellRegionFor`：0.82×格再取中心 0.70，±32px）是**以交点为心**的 ⇒
 *     只切到字的下半截：真机稳定重现 車→卒、馬→兵/炮、象→炮、士→空，差分只读变化格 ⇒
 *     错值永不重读；驱动拿证据改正、下一帧又被喂回来，确认永远 UNRELIABLE；
 *   · 判色/落点/证据读数都是锚在**检测框**上的（所以证据一直读得对）——把网格钉到框质心，
 *     「面板读的」和「驱动读的」就同基准了（裁框几何与 board-scan 的 cropBoxFor+centreRegion 重合）。
 * 幂等：钉过（`pieceOffset` 已记）不再量；位移 < 6px 视为已钉牢只记标记。
 * 安全性：样本 ≥ 8 框（残局棋子少，2026-09-21 从 12 放宽；但 < 12 个框时散布门槛收紧到 ≤ 12px）、
 * 偏移散布残差 ≤ 18px、总位移 ≤ 90px 才动手；任何一步不满足都原样返回。
 *
 * @return 新网格（没量到 / 不用动 / 不敢动时原样返回）
 */
function snapGridToPieces(candidate, image, why) {
    if (!candidate || !image) {
        return candidate;
    }
    if (candidate.pieceOffset) {
        return candidate;   // 同一个几何只钉一次
    }
    if (!visionDetectorSession) {
        try {
            visionDetectorSession = recognizeBoard.createDetectionSession({ autoAlign: false });
        } catch (error) {
            return candidate;
        }
    }
    let cells = [];
    try {
        const detected = visionDetectorSession.detect(image, candidate, {});
        const boxes = pieceDetector.normalizeBoxes((detected && detected.raw) || []);
        const mapped = pieceDetector.mapToCells(boxes, candidate, { autoAlign: false });
        cells = (mapped && mapped.cells) || [];
    } catch (error) {
        logLine("几何钉棋子（" + why + "）：测量失败（" + error + "），保持原几何");
        return candidate;
    }
    const flip = candidate.orientation === "RED_TOP";
    const dxs = [];
    const dys = [];
    for (let i = 0; i < cells.length; i++) {
        const one = cells[i];
        const box = one && one.box;
        if (!box || one.residual == null || one.residual > 0.34) {
            continue;   // 离交点太远的框不参与（多半是误检）
        }
        if (one.score != null && one.score < 0.40) {
            continue;
        }
        const col = flip ? 8 - one.col : one.col;
        const row = flip ? 9 - one.row : one.row;
        const bx = box.centerX == null ? (box.x + box.width / 2) : box.centerX;
        const by = box.centerY == null ? (box.y + box.height / 2) : box.centerY;
        dxs.push(bx - (candidate.originX + col * candidate.stepX));
        dys.push(by - (candidate.originY + row * candidate.stepY));
    }
    if (dxs.length < 8) {
        logLine("几何钉棋子（" + why + "）：只量到 " + dxs.length + " 个可用框，不钉");
        return candidate;
    }
    function midOf(list) {
        const sorted = list.slice().sort(function (a, b) { return a - b; });
        return sorted[Math.floor(sorted.length / 2)];
    }
    const dx = midOf(dxs);
    const dy = midOf(dys);
    const devX = midOf(dxs.map(function (v) { return Math.abs(v - dx); }));
    const devY = midOf(dys.map(function (v) { return Math.abs(v - dy); }));
    // 残局（可用框 < 12）时收紧散布门槛：小样本对异常值更敏感
    const maxDev = dxs.length >= 12 ? 18 : 12;
    if (devX > maxDev || devY > maxDev) {
        logLine("几何钉棋子（" + why + "）：框偏移太散（±" + Math.round(devX) + "/" +
            Math.round(devY) + "px），疑似一堆误检，不钉");
        return candidate;
    }
    if (Math.abs(dx) < 6 && Math.abs(dy) < 6) {
        candidate.pieceOffset = { dx: Math.round(dx), dy: Math.round(dy), kept: true };
        return candidate;   // 已经钉牢
    }
    if (Math.abs(dx) + Math.abs(dy) > 90) {
        logLine("几何钉棋子（" + why + "）：偏移 (" + Math.round(dx) + "," + Math.round(dy) +
            ") 合计超 90px，太大不敢动网格（交给整行体检/重定位处理）");
        return candidate;
    }
    const shifted = geometryModule.createGrid({
        originX: candidate.originX + Math.round(dx),
        originY: candidate.originY + Math.round(dy),
        stepX: candidate.stepX,
        stepY: candidate.stepY,
        orientation: candidate.orientation,
        version: (candidate.version == null ? 1 : candidate.version) + 1
    });
    shifted.pieceOffset = { dx: Math.round(dx), dy: Math.round(dy) };
    shifted.snappedFrom = candidate.version;
    logLine("几何钉棋子（" + why + "）：按 " + dxs.length + " 个检测框质心平移 (" +
        Math.round(dx) + "," + Math.round(dy) + ")px（原交点压着棋子下缘 ⇒ 逐格 OCR 只切半截字）｜几何 v" +
        shifted.version + "｜起点 (" + shifted.originX + "," + shifted.originY + ")");
    return shifted;
}

// ── 对局结束（结算页）检测 ────────────────────────────────────────────────
/**
 * 真机 2026-09-21 凌晨 now56：连走三手（a0b0/b0b6/c2e1）全部 CONFIRMED 之后，第四手 b6f6
 * 时**这局其实已经终了** —— 游戏弹出结算页（「VS 特级大师 的成绩」+「分享 / 复盘分析 / 再来一局」），
 * **棋盘整体下移 ~160px** ⇒ 差分流对着结算页读出一盘垃圾（顽固未知 9、确认 UNRELIABLE 空转、
 * 看门狗连炸）。这里每 N 次观察探一次底部按钮带，命中「再来一局 / 复盘分析」就**自动停**
 * （不自动点再来一局：那会无尽连下、也不是用户时要的行为）。
 * 探针区间用屏高比例（机型无关）：结算页两排按钮在 92.5% 与 98% 高度，而活棋局同带有图标菜单无文字。
 */
const GAME_OVER_PROBE_EVERY = 5;
const GAME_OVER_WORDS = ["再来一局", "复盘分析"];
let gameOverProbeTick = 0;
let gameOverSeen = false;
function probeGameOver(image) {
    if (!image || gameOverSeen) {
        return false;
    }
    gameOverProbeTick += 1;
    if (gameOverProbeTick % GAME_OVER_PROBE_EVERY !== 0) {
        return false;
    }
    const size = adapter.screenSize();
    if (!size || !(size.height > 0)) {
        return false;
    }
    const session = ocrRuntime();
    if (!session) {
        return false;
    }
    let blocks = [];
    try {
        blocks = session.detect(image, {
            max: 8,
            region: [0, Math.round(size.height * 0.90), size.width, Math.round(size.height * 0.095)]
        }) || [];
    } catch (error) {
        return false;
    }
    let hit = null;
    for (let i = 0; i < blocks.length && !hit; i++) {
        const text = String((blocks[i] && blocks[i].text) || "");
        for (let k = 0; k < GAME_OVER_WORDS.length; k++) {
            if (text.indexOf(GAME_OVER_WORDS[k]) >= 0) {
                hit = text;
                break;
            }
        }
    }
    if (!hit) {
        return false;
    }
    gameOverSeen = true;
    logLine("检测到对局结算页（读到「" + hit + "」）→ 本轮 tick 结束后自动停：结算页棋盘被下移，继续追踪只会对着垃圾空转");
    return true;
}

/**
 * 「几何沿用」快路径：**已经有几何**、体检也认同、且这一帧真能读出盘面 ⇒ 不再做全量校准。
 *
 * 为什么（2026-09-20 性能账）：`calibrateGrid` 会把 ~40 个候选**逐个整帧识别**一遍
 * —— 真机日志 `校准完成：几何 v1 偏移(0,0) 未知格 1 用时 7975ms`，而那局的棋盘压根没动
 * （几何 v1 一直没变），这 8s 纯属白等，恢复/重定位路径上一触发就是一次。
 *
 * 接受条件比校准更严（两道路）：① `grid-sanity` 的行体检过（抓「整行偏」）；
 * ② 用现几何真读一帧，未定格 ≤ `GEOMETRY_REUSE_MAX_UNKNOWN`。任一不过就老实走全量（线定位+校准）。
 *
 * @param grabbed `{ image }`（调用方已经抓好的帧，不再多抓一次）
 * @return { ok, method, grid, unknown } 或 null（不能用 ⇒ 走全量定位+校准）
 */
function tryReuseGrid(grabbed) {
    if (!grid || !grabbed || !grabbed.image) {
        return null;
    }
    const sanity = checkGridRows(grabbed.image, grid);
    if (!sanity.ok) {
        logLine("几何沿用检查不过（" + sanity.reason + "），走全量校准");
        return null;
    }
    const started = Date.now();
    // 先用**上一帧真读出来的未定格数**挡一刀：成堆未定格 = 几何多半不对，别沿用。
    if (lastPipelineUnknown >= 8) {
        logLine("几何沿用检查：上一帧读出 " + lastPipelineUnknown +
            " 个未定格（几何多半不对）⇒ 走全量校准");
        return null;
    }
    // 识别链只有字形（2026-09-24「其他的都不要了」）：用现几何真读一帧，未定超限就不沿用。
    let result = null;
    try {
        result = glyphSource.recognize(grabbed.image, grid, {});
    } catch (error) {
        result = null;
        logLine("几何沿用检查：字形识别异常（" + error + "），走全量校准");
        return null;
    }
    const unknown = (result && result.ok && result.chars) ? boardScan.countUnknownCells(result.chars) : 999;
    if (!result || !result.ok || unknown > GEOMETRY_REUSE_MAX_UNKNOWN) {
        logLine("几何沿用检查：这一帧读出来 " + (result && result.ok ? ("未定 " + unknown + " 格") : "识别失败") +
            "，走全量校准");
        return null;
    }
    grid = attachGridQuality(grabbed.image, snapGridToPieces(grid, grabbed.image, "几何沿用"));
    if (boardService) {
        boardService.setGrid(grid);
    }
    logLine("几何沿用（体检过 + 未定 " + unknown + " 格，用时 " + (Date.now() - started) +
        "ms，省掉全量校准）｜几何 v" + grid.version);
    snapPanelAboveBoard();
    return { ok: true, method: "reuse-verified", grid: grid, unknown: unknown };
}

/** pickGrid 回退几何的「两帧一致性」连续不一致计数（防止确认总失败导致定位死循环） */
let pickLockStrikes = 0;

/**
 * 两帧一致性确认（多证据融合方案 §10 最小版第 3 条）：
 * 用**第二帧**围绕候选几何再搜一次（种子=候选自身、±0.15 格、步距 ±2%），
 * 漂移 ≤ 0.12 格 / 2.5% 才认为「锁得住」。拿不到第二帧、或第二帧搜不出结果时**放行**
 * （打印注记）—— 确认是用来抓「单帧假几何」的，不是把能用的几何挡在门外的。
 * @param grid 候选几何（pickGrid 的结果，含 originX/originY/stepX/stepY）
 * @return { ok, confirmed, note }
 */
function confirmPickedGrid(grid) {
    if (!grid) {
        return { ok: true, confirmed: false, note: "没有候选几何，跳过确认" };
    }
    let second = null;
    try {
        const grabbed2 = grabFrame();
        if (grabbed2 && grabbed2.image) {
            second = recognizeBoard.pickGrid(grabbed2.image, {
                orientation: "RED_BOTTOM", confidence: 0.25,
                seeds: [grid], radius: 6, step: 3, radiusRatio: 0.15,
                stepScales: [1, 0.98, 1.02], probeCropMargin: 0.65
            });
        }
    } catch (error) {
        second = null;
    }
    if (!second) {
        return { ok: true, confirmed: false, note: "第二帧不可用，未做确认" };
    }
    if (!second.ok) {
        return { ok: true, confirmed: false, note: "第二帧搜索未成功（" + second.reason + "）" };
    }
    const cell = Math.max(1, (Math.abs(grid.stepX) + Math.abs(grid.stepY)) / 2);
    const dx = Math.abs(second.grid.originX - grid.originX);
    const dy = Math.abs(second.grid.originY - grid.originY);
    const dsx = Math.abs(second.grid.stepX - grid.stepX) / Math.max(1e-6, Math.abs(grid.stepX));
    const dsy = Math.abs(second.grid.stepY - grid.stepY) / Math.max(1e-6, Math.abs(grid.stepY));
    const note = "Δ(" + Math.round(dx) + "," + Math.round(dy) + ")px，格距漂移 " +
        (dsx * 100).toFixed(1) + "% / " + (dsy * 100).toFixed(1) + "%";
    if (dx <= cell * 0.12 && dy <= cell * 0.12 && dsx <= 0.025 && dsy <= 0.025) {
        return { ok: true, confirmed: true, note: note };
    }
    return { ok: false, confirmed: false, note: note };
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
    // ★ 快路径：几何还在就直接用（见 tryReuseGrid；真机 8.4s 的全量校准就省在这里）
    const reused = tryReuseGrid(grabbed);
    if (reused) {
        return reused;
    }
    const options = {
        orientation: "RED_BOTTOM",
        confidence: 0.25,
        autoAlign: false,
        search: { maxShift: 18, step: 3 },
        maxCandidates: 25
    };
    let located = null;
    // ★ 2026-09-21（P0，MediaProjection 也走线定位）：条件从「shell 通道」放宽成「拿到可读帧」——
    //   手机没有 Shizuku 时截图走 MediaProjection（app 通道），旧条件会把 app 帧直接跳过线定位、
    //   送进 pickGrid → 在 1280×2772 上连续拒绝（未知格 6、检测框棋盘覆盖率 0.36~0.50）。
    //   同一帧离线线定位是好的（竖 9/9、横 8/10）⇒ app 帧与 shell 帧共用线定位，失败再回退 pickGrid。
    if (grabbed && grabbed.image) {
        // 直接传**帧对象**：raw 快通道没有磁盘路径（`readImage` 已支持帧直通，省一次解码）
        located = locateBoard.locateBoardInImage(grabbed.image, {});
        // 候选择优可见性（2026-09-22 多证据融合方案 P0 雏形）：试了多个候选时把结果写进日志，
        // 便于下次冷启动时直接核对「选了哪个/为什么继续看后面的范围」。
        if (located.ok && located.attempts > 1) {
            logLine("线定位候选择优：共试 " + located.attempts + " 个候选，选中分数 " +
                (located.chosenScore == null ? "-" : located.chosenScore.toFixed(2)) +
                (located.earlyAccept ? "（足够好，提前收工）" : "（比完全部范围）"));
        }
        // ★ 定位「成功」也要体检：整行偏是最常见的假成功（见 checkGridRows 的说明）
        if (located.ok && located.grid) {
            let sanity = checkGridRows(grabbed.image, located.grid);
            if (!sanity.ok) {
                // ★ 2026-09-20 晚（now47/now49 真机，中残局）：线定位找对了线、只是 origin 锚错一行时，
                //   体检会拒；旧行为直接回退 pickGrid 缓存框 —— 实测那路给偏 ~17px 的网格，读数全盘变差。
                //   先在线定位结果**本身上**依次试平移候选（±整行/±半行），选「体检过 + **落在网格内的
                //   检测框数变多**」的最优者。
                //   ⚠️ 「框数必须变多」是防误判的硬保护：合法的「顶行真空了」局面里，平移不会让框数变多
                //   （本来就没有子掉在网格外）⇒ 不会把一个正确的网格换成错的。
                const tries = gridSanity.shiftCandidates(located.grid);
                let rescued = null;
                for (let s = 0; s < tries.length; s++) {
                    const shifted = Object.assign({}, located.grid, {
                        originX: located.grid.originX + tries[s][0],
                        originY: located.grid.originY + tries[s][1]
                    });
                    const alt = checkGridRows(grabbed.image, shifted);
                    if (!alt.ok) {
                        continue;
                    }
                    if (sanity.pieces != null && alt.pieces != null && alt.pieces <= sanity.pieces) {
                        continue;   // 平移没把子救回网格里 ⇒ 多半不是偏行，别换
                    }
                    if (!rescued || alt.pieces > rescued.alt.pieces) {
                        rescued = { shifted: shifted, alt: alt, dx: tries[s][0], dy: tries[s][1] };
                    }
                }
                if (rescued) {
                    logLine("线定位几何体检平移救回：原点 (" + rescued.dx + "," + rescued.dy +
                        ") 后通过（" + rescued.alt.reason + "；框数 " + sanity.pieces + " → " +
                        rescued.alt.pieces + "）");
                    sanity = rescued.alt;
                    located = {
                        ok: true, method: "line-shift", grid: rescued.shifted,
                        imageObject: grabbed.image, frameObject: grabbed.image
                    };
                } else {
                    logLine("线定位几何体检不过（" + sanity.reason + "），改用 pickGrid 缓存框搜索");
                    located = { ok: false, reason: "几何体检不过：" + sanity.reason };
                }
            } else {
                logLine("线定位几何体检通过（" + sanity.reason + "）");
            }
        }
        if (!located.ok) {
            logLine("线定位失败（" + located.reason + "），改用 pickGrid 缓存框搜索");
            const picked = recognizeBoard.pickGrid(grabbed.image, {
                orientation: "RED_BOTTOM", confidence: 0.25, radius: 18, step: 4,
                // 2026-09-22（多证据融合方案 §10）：平移 ±0.5 格 + 步距 ±2%/±4% 五档；
                // 旧参数（±18px、固定格距）够不到「线定位第一条线锚错半格」（真机偏差 49px）。
                // 裁剪外扩 0.9：要盖住「搜索半径 0.5 格 + 棋子半径 ~0.45 格」的范围。
                radiusRatio: 0.5, probeCropMargin: 0.9
            });
            if (!picked.ok) {
                logLine("pickGrid 也失败：" + picked.reason);
                return null;
            }
            // 两帧一致性确认（方案 §10 最小版第 3 条）：第二帧再搜一次，漂移在容差内才锁定。
            const lock = confirmPickedGrid(picked.grid);
            if (!lock.ok) {
                pickLockStrikes += 1;
                logLine("回退几何两帧不一致（" + lock.note + "），连续 " + pickLockStrikes + " 次");
                if (pickLockStrikes < 2) {
                    return null;   // 本轮放弃，下一轮重新定位（避免拿单帧假几何去点）
                }
                logLine("两帧不一致已连续 " + pickLockStrikes + " 次，放行本轮几何（避免定位死循环）");
            } else {
                pickLockStrikes = 0;
                if (lock.note) {
                    logLine("回退几何两帧确认：" + lock.note);
                }
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
        grid = attachGridQuality(grabbed.image, snapGridToPieces(calibrated.grid, grabbed.image, "校准后"));
        if (boardService) { boardService.setGrid(grid); }   // 几何只存服务层一份（2026-09-20 入口收口）
        calibrationNote = "几何 v" + grid.version + " 偏移(" + calibrated.dx + "," + calibrated.dy +
            ") 未知格 " + calibrated.unknown + " 用时 " + (Date.now() - started) + "ms";
        logLine("校准完成：" + calibrationNote + "｜定位方式 " + located.method);
        if (calibrated.layout) {
            logLine("局面 " + calibrated.layout);
        }
        snapPanelAboveBoard();   // 知道棋盘在哪了 → 把自己摆到棋盘上沿之上那条空带里
        return calibrated;
    }
    if (located.grid) {
        grid = attachGridQuality(grabbed.image, located.grid);
        if (boardService) { boardService.setGrid(grid); }
        calibrationNote = "几何 v" + grid.version + "（未精校准）";
        logLine("精校准没成功，沿用定位几何：" + calibrationNote);
        snapPanelAboveBoard();
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
    refreshOverlay(result, null);   // 画到屏幕上 → 人可以肉眼审「每格读成了什么」
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

/** 本应用包名（判断「前台是不是我们自己」用）。 */
const OWN_PACKAGE = (function () {
    try {
        return context.getPackageName();
    } catch (error) {
        return "com.jdkshen.aijspro";
    }
})();
/** 最近一次查到的「触摸焦点」包名（面板状态行显示用）。 */
let lastForeground = null;
/** 要自动走棋的目标游戏包名（autorun 里可改；默认腾讯天天象棋）。 */
const GAME_PACKAGE_DEFAULT = "com.tencent.qqgame.xq";
let gamePackage = GAME_PACKAGE_DEFAULT;

/**
 * 点击前的**前台守卫**（真机血泪，见 auto-driver 里的注释）：
 * 脚本宿主 RunIntentActivity 是透明窗口，`am start` 起脚本后它常常仍是「有窗口焦点的前台」，
 * 游戏只是画在它下面 —— 这时注入的点击会被我们自己吃掉，截图上却一切正常。
 *
 * 判据用 `dumpsys input` 的 FocusedWindows（**决定注入触摸给谁的就是它**）：
 * 真机踩过：按 topResumedActivity 判会得到 `com.android.launcher3` 这种误导值，
 * 守卫以为切成功、实际点击全落到别处（一手棋连试 4 次全 UNCHANGED）。
 *
 * 处理：焦点不是游戏 → ① 先按一次「最近任务」；② 还不行就用 `am start` 把游戏任务带到前台
 * （不会重启游戏，实测已重置的开局不会被恢复）；③ 仍不行就告诉驱动「别点」。
 *
 * @returns {ok, package, ownPackage, packageChanged}
 */
function foregroundGuard() {
    const readFocus = function () {
        try {
            return adapter.focusedWindowPackage();
        } catch (error) {
            return null;
        }
    };
    let pkg = readFocus();
    lastForeground = pkg;
    if (pkg === null) {
        // 查不到（没有 Shizuku/root）就不拦：交给点击通道自己报错
        return { ok: true, package: null, ownPackage: false, packageChanged: false };
    }
    if (pkg === gamePackage) {
        return { ok: true, package: pkg, ownPackage: false, packageChanged: false };
    }
    const own = pkg === OWN_PACKAGE;
    let switched = false;
    // ① 直接把游戏的任务带到前台（`am start -n 包的已有 Activity`，不重启进程）。
    //    ⚠️ **不要用 `input keyevent 187`（最近任务）**：这台 ROM 上它会**打开「概览」界面**，
    //    下一次按键切到哪张任务卡不确定 —— 真机踩过：守卫「切成功了」但焦点落在桌面/概览，
    //    而驱动截到的画面是游戏的那张**缩略卡**（棋盘看得见、识别正常、点击全落空）。
    try {
        if (adapter.bringPackageToFront(gamePackage)) {
            sleep(1200);
            switched = true;
        }
    } catch (error) {
        switched = false;
    }
    let again = readFocus();
    // ② 还不行 → 按一次「最近任务」（个别 ROM 上这条才有效；只在①失败时才用）
    if (again !== gamePackage) {
        try {
            if (adapter.pressAppSwitch()) {
                sleep(900);
                switched = true;
            }
        } catch (error) {
            // 统一在下面判定
        }
        again = readFocus();
    }
    lastForeground = again;
    const ok = again === gamePackage;
    if (!ok) {
        logLine("前台守卫：切不到游戏（目标 " + gamePackage + "，当前焦点 " + (again || "未知") +
            "）—— 不会点击；请手动把游戏点到前台");
    }
    return { ok: ok, package: again, ownPackage: own, packageChanged: switched && ok };
}

/**
 * 驱动依赖计时（「本轮账」用）：把注进驱动的每个函数包一层，累计本轮 tick 里各依赖花了多久。
 *
 * 目的（2026-09-20）：tick 与「识别」之和的差（真机 ~3.3s）到底花在哪 —— 不靠猜。
 * 只在 `depAccum` 非空（tick 进行中）时累计；不改任何行为与返回值。
 */
let depAccum = null;
function wrapDriverDeps(deps) {
    const out = {};
    Object.keys(deps).forEach(function (name) {
        const value = deps[name];
        if (typeof value !== "function") {
            out[name] = value;
            return;
        }
        out[name] = function () {
            const started = Date.now();
            try {
                return value.apply(null, arguments);
            } finally {
                if (depAccum) {
                    const rec = depAccum[name] || (depAccum[name] = { calls: 0, ms: 0 });
                    rec.calls += 1;
                    rec.ms += Date.now() - started;
                }
            }
        };
    });
    return out;
}

function buildDriver() {
    const clickAllowed = modeKey === "auto";
    let tapFunction = null;
    if (clickAllowed && (adapter.capabilities().tap || adapter.capabilities().shizuku)) {
        // 通道名非法 → createTap 直接抛错：宁可停在这里，也不要偷檟切回 auto 污染对照实验。
        let tapper;
        try {
            tapper = adapter.createTap({ pressMs: clickPressMs, method: clickMethodKey });
        } catch (error) {
            logLine("点击通道不可用：" + error + "（检查 autorun 里的 clickMethod）");
            throw error;
        }
        tapFunction = tapper.tap;
        logLine("点击请求=" + clickMethodKey + "，实际=" + tapper.method +
            "，按压=" + clickPressMs + "ms（无障碍=" +
            (adapter.capabilities().accessibilityReady ? "就绪" : "未就绪") + "，Shizuku=" +
            (adapter.capabilities().shizuku ? "可用" : "不可用") + "），脚本=" + PROBE_BUILD_ID);
        // ⚠️ **整手藏面板**（真机 A/B 结论，2026-09-14）：
        //   同一抬手手势、同一通道、同一坐标 —— 单手探针（整轮藏面板）**即时生效、吃子也成**；
        //   自动循环却"点击无效"：先 SENT，确认阶段连续 UNCHANGED，跑完再截图棋子其实到位了。
        //   进一步对照后定位到：**两次点击之间把面板显示出来**会让游戏丢掉"已选中"状态
        //   （窗口增删 → 焦点变化 → 选中被取消），于是落点那一点只是又选中了目标子。
        //   所以按执行器的 kind 做租约：kind="from" 时藏、kind="to" 之后才恢复。
        // 兜底：如果 "to" 没来（失败/取消/暂停），loop 停止和下一次 "from" 都会把面板恢复回来。
        const rawTap = tapper.tap;
        let clickLeaseHidden = false;
        function acquireClickLease() {
            if (clickLeaseHidden) {
                return;
            }
            try {
                if (setActivePanelVisibility(false, "点击租约") !== false) {
                    clickLeaseHidden = true;
                    clickLeaseActive = true;
                    // 藏窗到出手的等待：真机 A/B 显示"藏完立刻点"可能还没被游戏接纳
                    // （探针是藏窗 → 定位 → 抓帧，好几秒后才出手，一点就中）。
                    sleep(clickHideSettleMs);
                }
            } catch (error) {
                clickLeaseHidden = false;
                clickLeaseActive = false;
            }
        }
        function releaseClickLease() {
            if (!clickLeaseHidden) {
                clickLeaseActive = false;
                return;
            }
            clickLeaseHidden = false;
            clickLeaseActive = false;
            try {
                setActivePanelVisibility(true);
            } catch (error) {
                logLine("点击后恢复面板失败：" + error);
            }
        }
        tapFunction = function (x, y, options) {
            const kind = options && options.kind ? String(options.kind) : "";
            if (kind !== "to") {
                // 起点点击（或没标 kind 的调用）：先藏，整手期间保持隐藏
                acquireClickLease();
            }
            try {
                return rawTap(x, y, options);
            } finally {
                if (kind === "to") {
                    releaseClickLease();
                }
            }
        };
        // 暴露给停止/退出路径，避免异常路径下面板一直藏着
        releaseTapLease = releaseClickLease;
    }
    // A1「入口收口」批次1：驱动不再由面板直建 —— 统一走会话（bootstrap 装配）；
    // 依赖仍由面板提供（保留 wrapDriverDeps 计时），构建/停止/资源所有权收在会话一处。
    driver = ensureApp().session.build({
        allowClick: clickAllowed,
        tap: tapFunction,
        extras: wrapDriverDeps({
        recognize: function (options) {
            // ★ 入口收口（2026-09-20）：这条链的编排（抓帧藏窗 → 稳定帧 → 流水线 → 规则门 → 不可信回退旧路径）
            //   全在 `services/board-service.js`；面板只负责注入平台能力并把结果交给绘制。
            //   以前这里自己写了一遍，且回退分支会在「不藏面板」的路径上抓帧 —— 真机踩过
            //   （同一帧「校准（会藏）未知 0」而「驱动观察（不藏）未知 23」）。服务层用的是注入的抓帧，
            //   一律走 `grabFrame()`。
            const service = ensureBoardService();
            const obsStarted = Date.now();
            // 识别链只有字形识别（2026-09-24「其他的都不要了」）：差分/流水线/旧路径分支全部移除；
            // 读不过的帧服务层会返回 ok:false，驱动按「这一帧不观察」处理。
            const smart = service.recognizeSmart(options || {});
            if (frameProbeOn) {
                logLine("[帧探针] 全盘识别后 " + frameProbeActive());
            }
            // 账本：一次观察里驱动会调 2 次（两帧合并）—— 分开记，才看得出「其他时间」在哪
            tickObsCalls += 1;
            tickObsMs += Date.now() - obsStarted;
            if (smart && smart.ok) {
                lastPipelineUnknown = smart.unknownCells || 0;
                rememberPipelineEvidence(smart);
                refreshOverlay(smart, null);
                probeGameOver(smart.frame && smart.frame.image);
                logPipelineNote(smart);
            }
            // `ok:false` / `unstable` 都当作「这一帧不观察」：驱动计一次未识别、不会乱推轮次
            return smart || null;
        },
        locate: function () {
            const locateStarted = Date.now();
            const result = locateAndCalibrate();
            tickLocateCalls += 1;
            tickLocateMs += Date.now() - locateStarted;
            return result ? { ok: true, grid: grid } : { ok: false, reason: "定位/校准失败" };
        },
        analyze: function (position, options) { return engineModule.analyze(position, options); },
        isTrustworthy: function (value) {
            // 阀值 0.40：与 piece-detector 的 maxCellDistance(0.38) 对齐 —— 网格偏零点几格不影响
            // 判色/落点（两者都锚在检测框上），而「整行偏」由 grid-sanity 体检负责。
            return geometryModule.isTrustworthy(value, { minSamples: 4, maxResidual: 0.40 });
        },
        screen: function () { return adapter.screenSize(); },
        foregroundGuard: foregroundGuard,
        // 催游戏（见 nudgeGame）：驱动发现「已知事实连着好几帧读成空」时会调这里 ——
        // 那不是模型漏检，而是画面卡在走子动画中间（人机对战，动画本该 1 秒内走完）。
        nudge: nudgeGame,
        // 单格 OCR（见 ocrCell）：驱动遇到「已知事实说有子、模型读成空/认不出」时，
        // 直接 OCR 那一格的汉字当证据（读到字=有子，读不到字=空格）。
        // 全盘 OCR（见 ocrBoard）：驱动遇到「同色不同兵种」的误读时拿它当证据
        ocrBoard: ocrBoard,
        // 单格 OCR（见 ocrCell）：驱动遇到「已知事实说有子、模型读成空/认不出」时问它
        ocrCell: ocrCell,
        // ★ 单帧多格证据（ROI 快速确认用）：抓一帧、认指定的几个格（见 ocrCellsNow）
        ocrCellsNow: ocrCellsNow,
        log: function (text) { logLine(text); },
        config: {
            tickMs: 2400,          // screencap 通道 0.6~1.4s/帧；而且**游戏落子有动画** ——
            // K40 实测 tick=1400 时，点击后第一次确认会看到「还没动」的旧帧 → 误判 UNCHANGED
            // → 状态机走恢复、把轮次基准弄丢 → 非开局局面就再也推不出轮次。所以放大到 2400。
            // 算棋预算：默认 3500ms；autorun `movetimeMs` 可覆盖（2026-09-21 提速方案：1000~1200）。
            // K40 + Pikafish 实测：1200ms ≈ 深度 18~19（原生引擎），快一截换来的是整手节奏。
            movetimeMs: movetimeMsKey,
            // ★ 2026-09-21 提速（方案第 3、4 条）：
            //   · 等待对方时观察降为 1 帧（出手前/确认/恢复仍 2 帧）；
            //   · 点击后先走「起点/终点两格」ROI 快速确认，拿不出结论才全盘确认。
            observeFramesByState: true,
            fastConfirmRoI: true,
            modelVersion: "chessai_160x320_v5r",
            confidence: 0.25,
            orientation: "RED_BOTTOM",
            autoAlign: autoAlignOn,
            // 算棋默认放独立引擎线程（worker）：不然一步 ~1.2s 的搜索会把面板触摸一起挡掉。
            // autorun 里写 "computeMode":"inline" 可以对照老路径。
            computeMode: computeModeKey,
            // 引擎后端与 Pikafish 参数：worker 与 inline 两条路都会带过去（见 compute/computeInline）。
            engineKey: engineBackendKey,
            pikafish: pikafishOptions,
            // 一次观察看几帧/间隔多少（autorun 可覆盖，做 A/B 用；驱动默认 2 帧 + 260ms）
            observeFrames: observeFramesKey,
            observeGapMs: observeGapMsKey,
            // ★ 对方应手「占用唯一解」快路（2026-09-21）：diff 链路的 occupancy/changed 直接定手，省 OCR
            replyFastPath: replyFastPathKey
        },
        // 两击之间的间隔：太短会「只选中、没落子」（选中动画还没画完）。
        // ⚠️ 这**不是**之前「一手棋全 UNCHANGED」的原因 —— 真因是**前台是本应用**（点击被脚本宿主吃掉），
        // 已由 foregroundGuard 在看。这里保持适度间隔即可。
        // 2026-09-21 提速：优先用 autorun `clickSettleMs`（方案建议 240ms）；没配就沿用老公式
        //（老公式 = max(200, press+600)，350ms 按压时是 950ms —— 一手棋白等近 1 秒）。
        executorConfig: {
            pressMs: clickPressMs,
            settleMs: clickSettleMsKey != null ? clickSettleMsKey : Math.max(200, clickPressMs + 600)
        }
        })
    });
    return driver;
}

function tick() {
    if (!driver) {
        return;
    }
    const tickStarted = Date.now();
    tickObsCalls = 0;
    tickObsMs = 0;
    tickLocateCalls = 0;
    tickLocateMs = 0;
    tickGuardMs = 0;
    tickOcrBoardMs = 0;
    tickDriverMs = 0;
    try {
        // ★ 先把游戏留在前台再观察：透明宿主的窗口一拿到焦点，游戏就 pause → 对方永不应手
        //   （真机证据见 keepGameInFront 的注释）。点击租约期间不动，免得抦断正在发的手势。
        if (!clickLeaseActive && (modeKey === "auto" || modeKey === "advice")) {
            const guardStarted = Date.now();
            keepGameInFront();
            tickGuardMs += Date.now() - guardStarted;
        }
        const driverStarted = Date.now();
        depAccum = {};
        const result = driver.tick();
        tickDriverMs = Date.now() - driverStarted;
        // ★ 对局结算页（probeGameOver 在观察里命中）：等驱动这轮走完再停，不在 tick 中途掉驱动
        if (gameOverSeen) {
            gameOverSeen = false;
            stopLoop();
            logLine("对局已结束，自动走棋已停。点「再来一局」后再点「开始」即可继续。");
            return;
        }
        const depTop = Object.keys(depAccum)
            .map(function (name) { return { name: name, calls: depAccum[name].calls, ms: depAccum[name].ms }; })
            .sort(function (a, b) { return b.ms - a.ms; })
            .slice(0, 4)
            .map(function (d) { return d.name + " " + d.ms + "ms×" + d.calls; })
            .join("｜");
        depAccum = null;
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
        if (frameProbeOn) {
            logLine("[帧探针] tick 开始 活跃 " + frameProbeActive());
        }
        logLine("状态 " + machine.state() + "｜" + machine.reason());
        // ★ 全盘 OCR 对照（每 15s 一次，点击租约期间跳过）：驱动盘面 vs OCR 逐格盘面
        if (ocrBoardEnabled && !clickLeaseActive && Date.now() - lastOcrBoardAt >= OCR_BOARD_INTERVAL_MS) {
            lastOcrBoardAt = Date.now();
            const ocrStarted = Date.now();
            try {
                logOcrBoardCompare();
            } catch (error) {
                logLine("全盘 OCR 对照异常：" + error);
            }
            tickOcrBoardMs += Date.now() - ocrStarted;
        }
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
                const declared = driver.assertTurn();
                logLine("autorun 声明轮到我：" + declared.reason);
                // ⚠️ 被拒（局面还有未知格）时必须**留着**这个标记、下一轮干净帧再试：
                //    真机 2026-09-20 踩到 —— 一轮没声明成，之后就再无基准，
                //    驱动一路 RECOVERING 空转到 stopAfterMs（日志里只有「轮次未知超时」）。
                pendingDeclare = !declared.ok;
            }
            // 驱动只保证给「观察到的局面/未知格」；不同版本字段不完全一样，所以逐项判空
            const unknown = result.unknownCells == null ? "?" : result.unknownCells;
            logLine("本轮观察：未知格 " + unknown + "，耗时 " + stats.lastObserveMs + "ms" +
                (result.layout ? ("，局面 " + result.layout) : ""));
            // 耗时分期（方案 §7）：把「一轮观察 7~11s」拆开 —— 抓帧 / 稳定帧 / 流水线内（检测・逐格・抢救・规则）
            if (Date.now() - lastTimingLogAt >= 2000) {
                lastTimingLogAt = Date.now();
                try {
                    const timingModule = load("src/runtime/timing.js");
                    const detail = ensureBoardService().lastTiming();
                    if (detail && detail.smart) {
                        logLine(timingModule.format(detail.smart, "耗时分期（最近一次识别）") +
                            (detail.pipeline ? "｜流水线内 " + timingModule.format(detail.pipeline) : "") +
                            (detail.legacy ? "｜已退回旧路径" : ""));
                    }
                } catch (error) {
                    // 计时不该影响主流程
                }
            }
        } else if (result) {
            logLine("本轮没出手：" + result.reason);
        }
        const ticket = machine.currentTicket();
        if (ticket) {
            logLine("计划：" + ticket.moveICCS + "（深度 " + (ticket.depth == null ? "?" : ticket.depth) +
                "，评分 " + (ticket.score == null ? "?" : Math.round(ticket.score)) + "，" + ticket.executionState + "）");
        }
        // —— 本轮账（方案 §7：抓帧降下来之后，「其他时间」到底花在哪）——
        //   本轮池 = 整次 tick 的墙钟；识别 N 次 = 驱动两帧合并调了几次、共多少 ms；
        //   剩下的大头通常是：算棋 worker（THINKING 轮次会提前 return）、点击/确认（另开一次观察）、
        //   全盘 OCR 对照（每 15s 一次 2~3s）、心跳调度。
        if (Date.now() - lastAccountLogAt >= 2000) {
            lastAccountLogAt = Date.now();
            logLine("本轮账：tick " + (Date.now() - tickStarted) + "ms｜守前台 " + tickGuardMs +
                "ms｜驱动 " + tickDriverMs + "ms（识别 " + tickObsCalls + " 次共 " + tickObsMs +
                "ms｜重定位 " + tickLocateCalls + " 次共 " + tickLocateMs + "ms）｜全盘 OCR " + tickOcrBoardMs +
                "ms｜依赖 top " + (depTop || "无") + "｜驱动自计观察 " + (stats.lastObserveMs || 0) + "ms" +
                (result && result.thinking ? "｜（算棋中）" : ""));
        }
    } catch (error) {
        logLine("自动循环出错：" + error +
            (error && error.stack
                ? "｜堆栈：" + String(error.stack).split("\n").slice(0, 4).join(" ← ")
                : ""));
        stopLoop();
    }
}

/**
 * 单格 OCR 的会话（懒建、复用；PP-OCR 建一次会话不便宜）。
 * 为什么需要「单格 OCR」（2026-09-19，K40）：模型对某些格子读不出来（一会儿 '?'、一会儿整格空），
 * 驱动只能靠补齐表猜；而 OCR 读的是**字形**，能直接给出证据：
 *   · 读到汉字 ⇒ 那格确实有子（顺便知道兵种与颜色：炮/砲、車/车、马/馬、仕/士、相/象、帅/将、兵/卒）
 *   · 读不到汉字（或置信度不足）⇒ 那格是空的（不是模型漏检）
 */
let ocrSession = null;
let ocrSessionTried = false;
/** 全盘 OCR 对照：开关（autorun 的 `ocrBoard`，默认开）与限频时间戳 */
let ocrBoardEnabled = false;
let lastOcrBoardAt = 0;
const OCR_BOARD_INTERVAL_MS = 15000;
/** 全盘 OCR 结果的缓存（驱动会调 `ocrBoard()`，日志也用它，免得重复跑 1.2s） */
let ocrBoardCache = null;
let ocrBoardCacheAt = 0;
const OCR_BOARD_CACHE_MS = 1200;
// A2：OCR 会话的懒建/释放抽到 src/vision/pipeline-factory.js（两入口共用一份），
// 这里保留原函数名与语义（`ocrCell` 等仍按 `ocrRuntime()` 取会话）。
const ocrHolder = pipelineFactory.createOcrHolder({ log: function (text) { logLine(text); } });
function ocrRuntime() {
    return ocrHolder.get();
}

function releaseOcr() {
    ocrHolder.release();
}

/**
 * 注入给服务层的视觉能力（**活对象**：属性可以在运行期补建/替换，服务每次调用都重新读）。
 * `pipeline` 故意延迟到第一次识别时再建：`ensureVisionPipeline()` 依赖 OCR 会话，
 * 可能比服务创建晚（OCR 会话初始化失败/延后时不能让服务永远只走旧路径）。
 */
const visionInjection = {
    pipeline: null,
    gate: null,
    samples: function (image, g) { return boardRegionSamples(image, g); },
    countUnknown: function (chars) { return boardScan.countUnknownCells(chars); },
    log: function (text) { logLine(text); },
    ready: function () {
        if (!visionInjection.pipeline) {
            visionInjection.pipeline = ensureVisionPipeline();
        }
        return visionInjection.pipeline;
    }
};

/**
 * 建（或取）识别服务实例：把平台能力注入服务层，**编排逻辑全在服务层**。
 * 定位/校准仍由面板自己管（它有 pickGrid 兜底与校准流程），只需把 grid 同步进服务。
 */
/**
 * A1「入口收口」批次1：应用装配改由 bootstrap 统一（棋盘服务 + 会话）。
 * 面板只提供平台特异项：抓帧端口 / 定位桩 / 旧路径包装 / 视觉流水线注入 —— 行为参数原样。
 * 可回退：还原本函数体为旧的 createBoardService 直建即可。
 */
function ensureApp() {
    if (!app) {
        ensureBoardService();   // app 与 boardService 同源创建
    }
    return app;
}

function ensureBoardService() {
    if (boardService) {
        return boardService;
    }
    if (!visionInjection.gate) {
        visionInjection.gate = boardFrameGate || (boardFrameGate = frameGateModule.create({ log: function () { } }));
    }
    // （2026-09-24「其他的都不要了」）不再预热视觉流水线：识别链只有字形识别，YOLO/OCR 会话不再预建。
    app = bootstrapModule.createApp({
        owner: "floaty",
        bus: bus,                            // A1 批次3：装配与入口共用同一总线实例
        lockOwner: loopLock.OWNER_FLOATY,    // 退出还锁的令牌（与面板 acquire 口径一致）
        adapter: adapter,
        host: {
            files: (typeof files !== "undefined" ? files : null),
            context: (typeof context !== "undefined" ? context : null),
            images: (typeof images !== "undefined" ? images : null),
            win: null,                       // 面板窗口的显隐仍由面板自己管（点击租约/抓帧）
            sleep: (typeof sleep === "function" ? sleep : null)
        },
        modules: {
            driverModule: driverModule,
            locateBoard: locateBoard,
            recognizeBoard: recognizeBoard,
            geometry: geometryModule,
            loopLock: loopLock
        },
        paths: {},                           // autorun 由面板自己读（不用装配器的配置加载）
        sink: function (text) { logLine(text); },
        // A1 批次2：状态性命令统一走控制器（参数/运行态校验 → handler → 记录事件）
        handlers: {
            START: function () { return startLoop(); },
            STOP: function () { stopLoop(); return { ok: true }; },
            RELOCATE: function () { locateAndCalibrate(); return { ok: true }; },
            RECOGNIZE: function () { recognizeOnce(); return { ok: true }; },
            SELFTEST: function () { runSelfCheck(); return { ok: true }; },
            CONTRACT: function () { runContractTests(); return { ok: true }; },
            NEW_SESSION: function () { newSession(); return { ok: true }; },
            DECLARE_TURN: function (arg) {
                if (!driver) { return { ok: false, reason: "先开始（或先识别一帧）再声明轮次" }; }
                const result = driver.assertTurn();
                logLine("声明轮到我：" + result.reason);
                return result && result.ok === false ? { ok: false, reason: result.reason } : { ok: true };
            }
        },
        boardOverrides: {
            ports: {
                now: function () { return Date.now(); },
                capture: function () { return grabFrame(); }   // 一律走「会先藏面板」的抓帧
            },
            locateBoard: {
                locateBoardInImage: function () {
                    // 定位由面板负责（locateAndCalibrate 含 pickGrid 兜底与校准），服务层不自己定位
                    return { ok: false, reason: "定位由面板负责，请先把 grid 同步进服务" };
                }
            },
            recognizeBoard: {
                // 旧路径：沿用面板的 recognizeScreen（含 autoAlign 与藏窗抓帧）
                run: function (image, located, options) {
                    return recognizeBoard.recognizeScreen(Object.assign({}, options || {}, {
                        autoAlign: autoAlignOn,
                        frame: image
                    }));
                }
            },
            vision: visionInjection,
            glyph: glyphSource,
            note: function (text) { logLine(text); }
        }
    });
    boardService = app.boards;
    if (grid) {
        boardService.setGrid(grid);
    }
    return boardService;
}

/** 跟服务要一行限频诊断：到底走的哪条路、字形/流水线自己的账怎么样 */
function logPipelineNote(smart) {
    if (!smart || !smart.ok) {
        return;
    }
    if (smart.method === "glyph") {
        if (Date.now() - lastPipelineNoteAt <= 2000) {
            return;
        }
        lastPipelineNoteAt = Date.now();
        const g = smart.glyphStats || {};
        logLine("字形识别：触子 " + (g.inked || 0) + "｜未知 " + (smart.unknownCells || 0) +
            "｜用时 " + (g.ms || 0) + "ms" + (g.retries ? ("（复核 " + g.retries + "）") : "") +
            "｜验证 " + ((smart.validation && smart.validation.ok) ? "过" : "不过"));
        return;
    }
    if (smart.method !== "vision-pipeline") {
        return;
    }
    if (Date.now() - lastPipelineNoteAt <= 2000) {
        return;
    }
    lastPipelineNoteAt = Date.now();
    const st = smart.pipelineStats || {};
    const fusion = smart.fusion || {};
    const v = smart.validation || {};
    logLine("视觉流水线：规则门 " + (v.ok ? "通过" : ("否决（" + (v.reason || "未说明") + "）")) +
        "｜确认 " + (fusion.confirmed || 0) + "／未定 " + (fusion.unknown || 0) +
        "｜OCR 成功 " + (st.ocrOk || 0) + "／缓存 " + (st.cellsCached || 0) +
        "／框字形打架 " + (st.labelMismatch || 0) + "／补全 " + (st.inferred || 0));
}

/**
 * 新视觉流水线（方案 §2）——面板侧的组装。
 *
 * 为什么要接进来：之前它只有模块 + 探针，自动走棋依旧走旧路径（模型直接吐 90 格字），
 * 于是「漏检/读成空格/红黑不分」这些老问题在实战里一个没少。
 *
 * 三条真机约束（都是踩过的）：
 *   · 检测必须复用 `recognize-board` 的 detect-only 会话（它做了宽高比处理；直接 adapter.detect 会撞 reshape 断言）；
 *   · 原生框是 `{bounds:{centerX,...}}` 形态，要过 `piece-detector.normalizeBoxes`；
 *   · OCR 会话**共用**面板那个（引擎是进程内单例，多建只是浪费）。
 */
// A2：流水线装配收进 src/vision/pipeline-factory.js（两入口共用一份）。
// 检测会话仍复用面板自己的 `visionDetectorSession`（audit/probe 等处也用它）；
// 灰度的「关掉就连能力都不给」「内部日志默认不打」等语义都在工厂里保留。
const visionCtl = pipelineFactory.create({
    adapter: adapter,
    recognizeBoard: recognizeBoard,
    getGrid: function () { return grid; },
    getOcrSession: function () { return ocrRuntime(); },
    isGrayEnabled: function () { return ocrGrayEnabled; },
    getOcrPrimary: function () { return ocrPrimaryMode; },
    getDetector: function () { return visionDetectorSession; },
    setDetector: function (session) { visionDetectorSession = session; },
    log: function (text) { logLine(text); },
    pipelineLog: function (text) {
        // 流水线内部日志默认不打（免得每帧刷屏），但**帅/将抢救**这类关键动作要看得见：
        // 它直接决定「整帧能不能用」，排查「红帅数量 0 退回旧路径」时全靠这一行。
        // ★ 灰度重试也要看得见（2026-09-20）：它是「落子高亮环格」唯一能读出字形的路。
        if (/帅\/将抢救|规则补全|灰度重试|彩色兜底/.test(String(text))) {
            logLine(text);
        }
    }
});

// 字形识别源（「新的为主」2026-09-24）：模板在工程 assets/glyph-tpl（懒加载）；
// 服务层 mode="auto" 会先跑它，规则门/未知格不过时自动回退流水线→旧路径。
const glyphSource = pipelineFactory.createGlyphSource({
    adapter: adapter,
    baseDir: BASE_DIR,
    log: function (text) { logLine(text); }
});

/**
 * 建（或取）视觉流水线实例（A2：装配逻辑在工厂里，两入口共用）。
 * 面板/服务层仍按原语义调用：失败返回 null（服务层会退回旧路径）。
 */
function ensureVisionPipeline() {
    if (visionPipeline) {
        return visionPipeline;
    }
    visionPipeline = visionCtl.ensure();
    return visionPipeline;
}

/**
 * 棋盘区域的像素采样（方案 §3.1 的「稳定帧」输入）。
 *
 * ⚠️ 必须返回**数字数组**（每个点取 R/G/B 均值当灰度）：`frame-gate.meanAbsDiff` 做的是
 *    `Number(a[i]) - Number(b[i])`，传 `[r,g,b]` 三元组会变成 `Number([1,2,3])` = NaN
 *    ⇒ 判定永远报“平均差 NaN”（2026-09-20 两个探针都踩了，日志里很好认）。
 */
// A2：采样实现收进工厂（两入口共用）；面板这里保留同名包装（含面板自己的 grid 回退）。
const boardRegionSamplesCore = pipelineFactory.createSamples(adapter);
function boardRegionSamples(image, g) {
    return boardRegionSamplesCore(image, g || grid);
}

// `recognizeViaPipeline` 已并入 `services/board-service.js`（2026-09-20 入口收口）：
// 稳定帧判定、流水线调用、规则门、不可信回退、未知格阈值都只在服务层维护一份，
// 面板不再自己组装（曾经三处各写一遍，改一处漏两处）。

// `recognizeViaPipeline`（面板旧实现）已于 2026-09-20 删除：
// 「稳定帧判定 → 流水线 → 规则门 → 不可信回退旧路径 → 未知格阈值 → 字段归一」全部收进
// `services/board-service.js` 的 `recognizeSmart()`，面板只注入能力、画结果。

/**
 * 「这一格到底有没有字符」—— 用 OCR 直接看。
 *
 * 格子中心与裁剪尺寸的算法跟 `recognize-board.js` 里的 ocrConfirm 保持一致
 * （方向 RED_TOP 时要镜像列/行）。阈值用 piece-ocr 默认的 0.78。
 *
 * @param info { index, char }  char = 已知事实里的兵种（可空，仅用于 matches 标记）
 * @return { ok, char, glyph, score, reason }  ok=false 表示「没读到可靠汉字」
 */
/**
 * 某格在屏幕上的裁剪区域（列/行 → 像素框）。方向 RED_TOP 时要镜像列/行，
 * 算法与 `recognize-board.js` 的 ocrConfirm 保持一致。
 */
function cellRegionFor(index) {
    if (!grid || index == null) {
        return null;
    }
    const col = index % 9;
    const row = Math.floor(index / 9);
    const flip = grid.orientation === "RED_TOP";
    const cx = grid.originX + (flip ? 8 - col : col) * grid.stepX;
    const cy = grid.originY + (flip ? 9 - row : row) * grid.stepY;
    /**
     * ★ 2026-09-20 深夜（now49/now51 真机）：裁框从「0.9×格」收敛到「0.82×格 再取中心 0.70」——
     *   与驱动证据那条路（board-scan 的 cropBoxFor(0.82) + centreRegion(0.70)）**完全一致**。
     *   为什么要收：游戏给「上一步」的子画的光圈/高亮环半径 ≈48~60px，0.9×格（±51px）正好把
     *   环圈进裁框 ⇒ 逐格 OCR 把带环的子读错/读不出（真机 now51：(5,3) 的車读成「馬」、
     *   (4,8)/(0,3) 直接 '?'、连带整个局面 6~7 个未知格推不动棋），而 board-scan 的窄裁框
     *   （≈±32px，只留文字区）读得干干净净 —— 「驱动读得到、面板读不到」的最后一处差异就在这里。
     *   灰度重试（同款）也在**同一个窄框**上跑。
     */
    const size = Math.max(8, Math.round(Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY)) * 0.82 * 0.70));
    return [
        Math.round(cx - size / 2), Math.round(cy - size / 2), size, size
    ];
}

/**
 * 占用扫描/找色判色的取样框：**要比 `cellRegionFor` 更小**，小到把游戏画的
 * 「选中/刚落子的红环」排除在外。
 *
 * 真机实测（K40，2026-09-20，`.artifacts/tools/ring-probe.py` / `ring-impact.py`）：
 *   · 那个环是**细红圆环**，半径 ≈48px（棋子外沿）——它**不在**判色框（ 0.72×棋子）里，
 *     所以对「红/黑墨迹判色」没有影响（带环红炮 红999/黑0 vs 不带环 红1028/黑0）；
 *   · 但旧的扫描框是 0.9×格 ≈ ±51px ⇒ **环被扫进去了** ⇒ 「只有环、子还在飞」的那一帧
 *     会被扫成「有子」，随后 OCR 认不出 ⇒ 一个多余的 `?`；
 *   · 0.6×格 = ±34px（< 环半径 48）刚好把环排除，而棋子自己的墨迹还剩 600~1300 点
 *     （密度 ≈0.13，远高于门槛 0.02）⇒ 判定依旧稳。
 * 可以用 autorun 的 `scanInner: false` 回到旧行为做 A/B。
 */
function scanRegionFor(index) {
    if (!grid || index == null) {
        return null;
    }
    const col = index % 9;
    const row = Math.floor(index / 9);
    const flip = grid.orientation === "RED_TOP";
    const cx = grid.originX + (flip ? 8 - col : col) * grid.stepX;
    const cy = grid.originY + (flip ? 9 - row : row) * grid.stepY;
    const size = Math.max(8, Math.round(Math.min(Math.abs(grid.stepX), Math.abs(grid.stepY)) * scanRatio));
    return [
        Math.round(cx - size / 2), Math.round(cy - size / 2), size, size
    ];
}

// ── 差分观察（visionPipeline: "diff"）─────────────────────────────────────────
// 为什么要它（用户提议 2026-09-20）：全盘流水线每帧都要跑检测框 + 90 格融合，而「棋子怎么动」
// 其实只需要知道**哪两格变了**。所以：模型只负责定位一次（几何复用），每帧只做
// 「逐格找色扫描」→ 与上一张盘面比变化 → 只对变化的格子 OCR 认兵种。
// 拿不准（变化太多 / 变化格认不出 / 扫不动）就返回 null，调用方退回全盘流水线。
let diffCache = null;
// ★ 2026-09-20 晚（真机 now42 定位）：**落点的第一次读数可能是「走子动画帧」**——
//   子还在飞/刚落下那一帧，逐格 OCR 会把兵种读错（实测：红炮→“兵”、黑炮→“卒”、带光环的黑馬→“炮”），
//   而一旦写进 diffCache，那格**不再变化就永远不再重读**（差分只读变化格）⇒
//   驱动每 ~14s 拿全盘 OCR 改正一次、下一帧又被差分喂回错值 —— 「改正→复发」死循环
//   （now42：恢复 25 次、同一格改正重复 ≥3 次、`(8,2)` 幻影清理空转 8 次；而且幻影清理
//   把「刚落下的那枚子」当成多余的清掉 ⇒ 差分追踪连对子都凑不齐 ⇒ 轮次推不出来）。
//   修法：**变化格写入缓存后进「复读名单」，下一帧起在格子稳定时（动画结束）再读一次**；
//   读值不同就采信新值（更新缓存 + 打一行日志）。最多试 3 帧，认不出就放弃、绝不阻塞。
let diffPending = [];             // [{ i: 格下标, tries: 已复读次数 }]
const DIFF_RECHECK_TRIES = 3;
// ★ 2026-09-20 晚（真机 now43 定位）：「全盘流水线」自己的整盘读值也可能**整格带错**（静帧实测：
//   (8,2) 带光环的黑馬→“炮”、(6,3) 红炮→“兵”），错值一旦进锚点，那格不变化就永不重读 ⇒
//   驱动每轮拿证据改正、下一帧又被喂回来（now43：同一格改正 3 次 + 恢复 30 次）。
//   两道防线：① 锚点落地前用**逐格 OCR 复核有子的格**（空格跳过，一局约 30 格 ≈1.2s，每 24 帧才一次）；
//   ② 平时每帧巡检 2 格，不符不立即采信，排进复读名单——**两帧读数一致才纠正**（防走子动画帧）。
let diffAuditCursor = 0;          // 巡检游标（0..89）
const DIFF_AUDIT_PER_FRAME = 2;   // 每帧最多巡检 2 格（只查“有子”的格）
const DIFF_MAX_CHANGES = 8;       // 变化格超过这个数就退回全盘（多半刚换局/画面大幅动过）
const DIFF_REANCHOR_FRAMES = 24;  // 每 N 帧回全盘重认一次（防增量误差累积）。
// ⚠️ 2026-09-20：12 → **24**。全盘那一次是逐格 OCR+找色（真机 ~1.2s），占一整 tick 的大头；
//   而漂移真正兜底的是驱动自己的「恢复 → 按观测量重建基准」那条路（每 1~2 手就会跑一次）。
const DIFF_INK_DENSITY = 0.02;    // 「有墨迹」的密度门槛（实测：有子 0.08~0.16、空格 ≈0）
/**
 * 「这点墨迹够不够一枚棋子」——按**密度**判，不是绝对像素数。
 * 真机实测（K40）：棋子墨迹密度 ≈8~16%、空格 ≈0；而游戏「上一步」的**星光标记**（白星+光环，
 * 像素发粉 240,200,200）会被找色计成红墨迹 ≈4.2% —— 旧的绝对阈值（≤5 像素）根本拦不住它
 * （now60：起点格 (7,2) ≈170 像素 ⇒ 判不了空 ⇒ '?' 背死 20s+ ⇒ 驱动填旧值造出「黑炮 3 枚」）。
 * 取 6% = 棋子下限 8% 与骗色 4.2% 的中点：真子（≥8%）不会被清，标记/抗锯齿（<6%）判空。
 */
const DIFF_INK_MIN_DENSITY = 0.06;
function inkLooksEmpty(ink) {
    // ★ 2026-09-21（d7 黑士漏读案）：交给 ink-color.looksEmpty 统一判 —— 「红/黑墨迹 **或** 暗墨迹」
    //   双证都低于门槛才算空。细笔画黑子（士/炮/象）的找色密度只有 5.4~8%（压 6% 线），
    //   裁剪中心偏 ~28px 就掉到 5.8% 被误判为空；暗墨迹口径同格 8~13%、空格 0% —— 风险清零。
    return inkColorModule.looksEmpty(ink);
}
// ⚠️ 2026-09-20 试过「画面静止 + 还有未知格 ⇒ 连续 3 帧就回全盘重锚」：**实测更差**，已回退。
//   真机 now31：那格未知**重锚也读不出来**（子还在飞/被画环），于是变成「重锚 38 次」的连续空转
//   （点击 2 次、未知格 1 出现 50 次），比原来的「等 24 帧」差得多。
//   要治的是「未知格本身」：面板侧也该像驱动那样有「顽固未知 → 按上一张可靠盘面填」的策略，
//   而不是靠重锚硬碰。
// 取样框比例（见 scanRegionFor 的实测说明）。
// ⚠️ 2026-09-20 A/B 实测：把框收到 0.6（排除游戏画的环）反而**更差** ——
//   环不参与「变化」判定后，走子动画那一两帧里落点看不出变化 ⇒ 观测留着旧值 ⇒
//   「推不出来」0 → 11、恢复 3 → 7（now27 → now28，同一开局各 180s）。默认仍是 0.9；
//   autorun `"scanInner": true` 才切到 0.6（留给将来配合「动画帧判定」一起用）。
let scanRatio = 0.9;

function diffGeometryKey() {
    return grid ? [grid.originX, grid.originY, grid.stepX, grid.stepY].join(",") : "";
}

/** 逐格「有子/空格 + 哪一边」扫描（纯找色：每格两次原生 findAllPointsForColor，不跑模型、不 OCR）。 */
function occupancyScan(image) {
    const map = new Array(90);
    let scanned = 0;
    const started = Date.now();
    for (let i = 0; i < 90; i++) {
        const region = scanRegionFor(i);
        if (!region) {
            map[i] = "?";
            continue;
        }
        const counts = inkColorModule.countsFor({ countColor: adapter.countColor }, image,
            { x: region[0], y: region[1], width: region[2], height: region[3] }, {});
        if (!counts || counts.source !== "findColor" || !(counts.area > 0)) {
            map[i] = "?";
            continue;
        }
        const red = counts.red / counts.area;
        const black = counts.black / counts.area;
        scanned += 1;
        if (red >= DIFF_INK_DENSITY || black >= DIFF_INK_DENSITY) {
            map[i] = red >= black ? "R" : "B";
        } else {
            map[i] = ".";
        }
    }
    return { map: map, ms: Date.now() - started, scanned: scanned };
}

/** 把一次**全盘**识别的结果变成差分锚点（用它那一帧扫一份占用图）。 */
function seedDiffCache(smart) {
    if (!grid || !smart || !smart.ok || !smart.chars || !smart.frame || !smart.frame.image) {
        return;
    }
    try {
        const scan = occupancyScan(smart.frame.image);
        if (scan.scanned < 40) {
            diffCache = null;
            diffPending = [];
            return;
        }
        const board = smart.chars.slice();
        /**
         * ★ 2026-09-20 深夜（now52 真机）：**重锚不许把「占用没变」的格子重读**——
         *   全盘那一份是**两帧合并**的读（不一致的格子会被降级成 '?'），而带「上一步光圈」的格子
         *   在两帧间本来就会轻微变化 ⇒ 每次重锚都打出 6~7 个 '?'，把刚养好的缓存打回未知，
         *   驱动 4s 看门狗连炸、永远走不出恢复（真机：每次重锚后 6~7 未知 → 慢慢收敛到 1 → 又重锚…）。
         *   差分的老规矩本来就是「只读变化格」——重锚（防漂移）只管**占用图**，
         *   占用没变的格子沿用上一版读数（那是单帧逐格读的，比合并帧可靠）。
         *   错值的兜底另有两条路：巡检（每帧 2 格轮着复核）+ 驱动证据改正。
         */
        const keptFlags = new Array(90);
        let kept = 0;
        if (diffCache && diffCache.geometry === diffGeometryKey() &&
            diffCache.occupancy && diffCache.board) {
            for (let i = 0; i < 90; i++) {
                const oldOcc = diffCache.occupancy[i];
                const newOcc = scan.map[i];
                if (oldOcc !== "?" && newOcc !== "?" && oldOcc === newOcc && diffCache.board[i] !== "?") {
                    board[i] = diffCache.board[i];
                    keptFlags[i] = true;
                    kept += 1;
                }
            }
            if (kept > 0) {
                logLine("差分锚点：沿用上一版读数 " + kept + " 格（占用没变的格子不重读，防合并帧噪音）");
            }
        }
        // ★ 锚点盘面里的**未知格先用「OCR+找色」证据补掉**（2026-09-20 真机踩到）：
        //   不然这些 '?' 会被差分**一直背着**（只有那一格再变化才会被重读），
        //   而驱动拿到带 '?' 的盘面就只能猜 —— 现场就是「对方应手那两格被猜成两个位置都有馬」
        //   → 轮次推不出来 → 卡在等对方。
        let filled = 0;
        for (let i = 0; i < 90; i++) {
            if (board[i] !== "?") {
                continue;
            }
            const one = ocrCellOnImage(smart.frame.image, i);
            if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
                continue;
            }
            const upper = String(one.cls).toUpperCase();
            board[i] = one.side === "RED" ? upper : upper.toLowerCase();
            filled += 1;
        }
        if (filled > 0) {
            logLine("差分锚点：未知格用 OCR+找色补齐 " + filled + " 格");
        }
        // ★ 锚点落地前**逐格复核有子的格**（见 diffAuditCursor 注释的 now43 现场）：
        //   全盘流水线读错的格子在这里被逐格 OCR 换掉，错值就进不了缓存。
        const healed = [];
        for (let i = 0; i < 90; i++) {
            if (keptFlags[i]) {
                continue;   // 沿用上一版读数的格子不复核（复核用合并帧，越复核越糟）
            }
            if (scan.map[i] === ".") {
                // 找色说没墨迹、流水线却有子 ⇒ 幻影脏值，直接清掉（下一帧真变了会被差分重新读）
                if (board[i] != null && board[i] !== "?") {
                    if (healed.length < 4) {
                        healed.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + board[i] + "→空");
                    }
                    board[i] = null;
                }
                continue;
            }
            if (scan.map[i] === "?" || board[i] === "?") {
                continue;              // 扫不清/'?' 刚在上一支补过
            }
            const one = ocrCellOnImage(smart.frame.image, i);
            if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
                // ★ 判空按**墨迹密度**（见 inkLooksEmpty）：旧的像素级阈值会把星光标记辉光当墨迹
                if (inkLooksEmpty(one && one.ink)) {
                    if (healed.length < 4) {
                        healed.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + board[i] + "→空");
                    }
                    board[i] = null;
                }
                continue;
            }
            const upper = String(one.cls).toUpperCase();
            const value = one.side === "RED" ? upper : upper.toLowerCase();
            if (value !== board[i]) {
                if (healed.length < 4) {
                    healed.push("(" + (i % 9) + "," + Math.floor(i / 9) + ")" + board[i] + "→" + value);
                }
                board[i] = value;
            }
        }
        if (healed.length > 0) {
            logLine("差分锚点：全盘读值被逐格 OCR 纠正（" + healed.join("；") + "）");
        }
        diffCache = {
            geometry: diffGeometryKey(),
            board: board,
            occupancy: scan.map,
            at: Date.now(),
            frames: 0
        };
        diffPending = [];   // 新锚点 = 全盘重读，旧的「复读名单」作废
        diffAuditCursor = 0;   // 巡检从头开始
    } catch (error) {
        diffCache = null;
        diffPending = [];
    }
}

/** 差分一帧；不放心返回 null（调用方退回全盘流水线）。 */
function recognizeDiffFrame(grabbed) {
    if (!diffCache || diffCache.geometry !== diffGeometryKey()) {
        return null;
    }
    if (diffCache.frames >= DIFF_REANCHOR_FRAMES) {
        return null;   // 定期回全盘对账
    }
    const scan = occupancyScan(grabbed.image);
    if (scan.scanned < 40) {
        return null;   // 找色大面积不可用：退回全盘
    }
    const changed = [];
    for (let i = 0; i < 90; i++) {
        if (scan.map[i] === "?") {
            continue;
        }
        if (scan.map[i] !== diffCache.occupancy[i]) {
            changed.push(i);
        }
    }
    if (changed.length > DIFF_MAX_CHANGES) {
        return null;
    }
    const board = diffCache.board.slice();
    // ── 落点复读（见 diffPending 注释）：把前几帧「刚变化」的格子，在**动画结束后**再读一次 ──
    for (let p = diffPending.length - 1; p >= 0; p--) {
        const item = diffPending[p];
        if (changed.indexOf(item.i) >= 0) {
            continue;                       // 这一帧又在变：交给下面变化格处理
        }
        if (scan.map[item.i] === "?") {
            item.tries += 1;
            if (item.tries >= DIFF_RECHECK_TRIES) {
                diffPending.splice(p, 1);
            }
            continue;                       // 扫不清：下一帧再看
        }
        if (scan.map[item.i] === ".") {
            board[item.i] = null;           // 确实空了（复读只做确认）
            diffPending.splice(p, 1);
            continue;
        }
        const again = ocrCellOnImage(grabbed.image, item.i);
        if (!again || !again.ok || !again.cls || (again.side !== "RED" && again.side !== "BLACK")) {
            // ★ 读不出「字」但**也没有棋子级墨迹** ⇒ 这格是空的（缓存里那个子是幻影/脏值）——
            //   真机 now46 的 `(1,4)n→空` 幻影循环死在这条上（旧逻辑把「读不出」一律当跳过）；
            //   真机 now60：起点格 (7,2) 的**白色星光标记辉光**被找色计成红墨迹（≈4.2% 密度、
            //   ~170 像素 > 旧的绝对阈值 5）⇒ 判不了空 ⇒ '?' 背死 ⇒ 驱动填旧值造出「黑炮 3 枚」。
            //   改成按密度判（见 inkLooksEmpty）。
            if (inkLooksEmpty(again && again.ink)) {
                if (board[item.i] != null && board[item.i] !== "?") {
                    logLine("差分复读：(" + (item.i % 9) + "," + Math.floor(item.i / 9) + ") " +
                        board[item.i] + " → 空（那格没有墨迹）");
                }
                board[item.i] = null;
                diffPending.splice(p, 1);
                continue;
            }
            item.tries += 1;
            if (item.tries >= DIFF_RECHECK_TRIES) {
                diffPending.splice(p, 1);   // 认不出就放弃（不阻塞这一帧）
            }
            continue;
        }
        const againUpper = String(again.cls).toUpperCase();
        const againValue = again.side === "RED" ? againUpper : againUpper.toLowerCase();
        if (board[item.i] !== againValue) {
            logLine("差分复读：(" + (item.i % 9) + "," + Math.floor(item.i / 9) + ") " +
                (board[item.i] == null ? "空" : board[item.i]) + " → " + againValue +
                "（缓存与稳定帧读数不符，已纠正）");
            board[item.i] = againValue;
        }
        diffPending.splice(p, 1);           // 复读完成（确认一致或已纠正），出名单
    }
    // ★ 锚点里的**未知格每帧顺手重认一次**（2026-09-20 真机踩到）：
    //   差分只重读「变化格」，未知格自己不动就永远读不出来 → 驱动只能猜。
    //   重认失败**不**当错误（那格本来就没动，维持 '?' 交给驱动自己的证据链）。
    let recheck = 0;
    for (let i = 0; i < 90; i++) {
        if (board[i] !== "?" || changed.indexOf(i) >= 0) {
            continue;
        }
        recheck += 1;
        if (recheck > 4) {
            break;   // 一帧最多补 4 格，别把单帧时间拖长
        }
        const one = ocrCellOnImage(grabbed.image, i);
        if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
            continue;
        }
        const upper = String(one.cls).toUpperCase();
        board[i] = one.side === "RED" ? upper : upper.toLowerCase();
    }
    for (let k = 0; k < changed.length; k++) {
        const i = changed[k];
        // ★ 变化格当场读完先别完全信：排进「复读名单」，下一帧稳定后再确认（见 diffPending 注释）
        let queued = false;
        for (let q = 0; q < diffPending.length; q++) {
            if (diffPending[q].i === i) {
                diffPending[q].tries = 0;
                queued = true;
                break;
            }
        }
        if (!queued) {
            diffPending.push({ i: i, tries: 0 });
        }
        if (scan.map[i] === ".") {
            board[i] = null;    // 这格空了
            continue;
        }
        const one = ocrCellOnImage(grabbed.image, i);
        if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
            return null;        // 变化格认不出兵种/阵营：这一帧不冒险
        }
        const upper = String(one.cls).toUpperCase();
        board[i] = one.side === "RED" ? upper : upper.toLowerCase();
    }
    // ── 缓存巡检（低强度）：每帧挑 2 格（只查“有子”的）与缓存对一遍 ──
    //   不符**不立即采信**（可能又撞上走子动画帧）：排进复读名单，下一帧再读一次，
    //   两帧读数一致才纠正（走稳定的 pending 路）；一致不了就自动丢弃。
    for (let a = 0, checked = 0; a < 90 && checked < DIFF_AUDIT_PER_FRAME; a++) {
        const i = diffAuditCursor;
        diffAuditCursor = (diffAuditCursor + 1) % 90;
        if (changed.indexOf(i) >= 0 || scan.map[i] === "?" || board[i] === "?") {
            continue;
        }
        let inPending = false;
        for (let q = 0; q < diffPending.length; q++) {
            if (diffPending[q].i === i) {
                inPending = true;
                break;
            }
        }
        if (inPending) {
            continue;
        }
        if (scan.map[i] === ".") {
            // ★ 扫出来没墨迹、缓存里却有子 ⇒ 幻影（真机 now46 的 (1,4)n 就是这么清了 66 次）：
            //   推给复读名单，下一帧复读确认后清掉（复读里已有「没墨迹 ⇒ 置空」那条）。
            if (board[i] != null) {
                diffPending.push({ i: i, tries: 0 });
            }
            continue;
        }
        checked += 1;
        const one = ocrCellOnImage(grabbed.image, i);
        if (!one || !one.ok || !one.cls || (one.side !== "RED" && one.side !== "BLACK")) {
            continue;
        }
        const upper = String(one.cls).toUpperCase();
        const value = one.side === "RED" ? upper : upper.toLowerCase();
        if (value !== board[i]) {
            diffPending.push({ i: i, tries: 0 });
        }
    }
    return { board: board, occupancy: scan.map, changed: changed, scan: scan };
}

function ocrCell(info) {
    // ★ 复用最近一帧（驱动问证据总在一轮观察之后，那一帧是热的；见 FRAME_REUSE_MS）
    const hot = recentFrameImage();
    if (hot) {
        frameReuseHits += 1;
        return ocrCellOnImage(hot, info ? info.index : null);
    }
    const grabbed = grabFrame();
    if (!grabbed || !grabbed.image) {
        return { ok: false, unavailable: true, reason: "取帧失败" };
    }
    return ocrCellOnImage(grabbed.image, info ? info.index : null);
}

/**
 * 单帧多格证据（ROI 快速确认用，2026-09-21 提速·方案第 3 条）：抓**一帧**、只认指定格，
 * 返回与 ocrCell 同构的结果数组。
 * 与 ocrCell 的差别：不复用「最近一帧」（确认要的是**点击之后**的新画面），多格共用同一次抓帧。
 */
function ocrCellsNow(indexes) {
    if (!indexes || indexes.length === 0) {
        return [];
    }
    const grabbed = grabFrame();
    if (!grabbed || !grabbed.image) {
        return null;
    }
    const out = [];
    for (let i = 0; i < indexes.length; i++) {
        out.push(ocrCellOnImage(grabbed.image, indexes[i]));
    }
    return out;
}
/** 复用命中计数（体检用：面板日志「本轮账」里会看到 `复用帧 N 次`） */
let frameReuseHits = 0;

/**
 * 单格证据（**复用已经拿到的那一帧**）：OCR 认兵种 + 像素判阵营 + 找色墨迹计数。
 * 差分模式逐格认兵种走这里 —— 不能再抓一帧（一次抓帧 ~200ms，一帧要认好几格）。
 */
function ocrCellOnImage(image, index) {
    const session = ocrRuntime();
    if (!session || index == null || !grid) {
        return { ok: false, unavailable: true, reason: "OCR 或几何不可用" };
    }
    const region = cellRegionFor(index);
    if (!region) {
        return { ok: false, unavailable: true, reason: "几何不可用" };
    }
    try {
        // ⚠️ 用**真实契约**：`$ocr` 会话给的是 `detect(image, {max, region})`，
        //    返回 [{ text, score, bounds }]，text 就是棋子上的**汉字**（炮/车/卒…）。
        //    不能走 pieceOcr.confirmCell —— 它要的是 `api.recognizeCell`，$ocr 会话没有，
        //    于是永远返回「OCR 未提供」（2026-09-19 踩过：误打误撞得出「读不到字」的结论）。
        // ★ 2026-09-21（用户口径「OCR 先灰度」）：**先灰度直读**（把格子裁出来转灰度再认）；
        //   灰度过关率整体更好（帅 彩色「印市」0.49 → 灰度「師」0.70），而每格彩色那遍常读不出
        //   （落子高亮环/动画）⇒ 先灰度省掉一次白读。
        //   灰度读不出字形时再用**彩色（整帧 + region）**兜底（灰度也有读错的：馬→志、兵→民）。
        let raw = "";
        let score = 0;
        let hit = null;
        const grayCapable = ocrGrayEnabled && typeof adapter.prepareRegion === "function";
        if (grayCapable) {
            const gray = adapter.prepareRegion(image, region, "gray");
            if (gray) {
                try {
                    const grayBlocks = session.detect(gray, { max: 1 }) || [];
                    const first = grayBlocks[0];
                    if (first) {
                        raw = String(first.text || first.label || "");
                        score = Number(first.score == null ? first.confidence : first.score) || 0;
                        hit = pieceOcr.classOf(raw);
                    }
                } catch (error) {
                    // 灰度读失败就当没读到，交给下面的彩色兜底
                }
                if (typeof adapter.releaseFrame === "function") {
                    adapter.releaseFrame(gray);
                } else if (typeof gray.recycle === "function") {
                    try {
                        gray.recycle();
                    } catch (ignore) {
                        // 忽略
                    }
                }
            }
        }
        // ★ 不管有没有字，都把这格的**墨迹计数**带回去（用户提议：棋子定位用找色扫描）：
        //   调用方靠它区分「有子 / 空格 / 分不开」，完全不看模型的检测框。
        let ink = null;
        try {
            const counts = inkColorModule.countsFor({ countColor: adapter.countColor },
                image,
                { x: region[0], y: region[1], width: region[2], height: region[3] },
                {});
            if (counts && counts.source === "findColor" && counts.area > 0) {
                ink = { red: counts.red, black: counts.black, area: counts.area };
            }
        } catch (error) {
            ink = null;
        }
        // ★ 2026-09-20 晚（真机 now45 定位，本轮卡死 62 轮的真正原因），2026-09-21 方向反转：
        //   **灰度没读出可用字形时，用彩色（整帧 + region）兜底** —— 与 `board-scan` 的同款兜底
        //   （驱动证据那条路一直有它，所以出现「驱动读得到、面板读不到」的怪象）。
        //   真机实测例：(2,7) 的红馬彩色读不出 ⇒ 面板一直 '?'、驱动的证据却能读到 N ⇒「1 个未知格」卡死。
        //   只在这格**确实有墨迹**时才兜底（空格/假框直接跳过，每格省 ~25ms）；
        //   灰度路不可用（关了/宿主没有）时，彩色就是主读 —— 老路径照旧无条件读一次。
        if (!hit || score < 0.6) {
            const inkSum = ink ? ((Number(ink.red) || 0) + (Number(ink.black) || 0)) : 0;
            if (!grayCapable || inkSum > 0) {
                let blocks = null;
                try {
                    blocks = session.detect(image, { max: 1, region: region }) || [];
                } catch (error) {
                    blocks = null;
                }
                const one = blocks ? blocks[0] : null;
                if (one) {
                    const raw2 = String(one.text || one.label || "");
                    const score2 = Number(one.score == null ? one.confidence : one.score) || 0;
                    const hit2 = pieceOcr.classOf(raw2);
                    if (hit2 && score2 >= 0.6) {
                        raw = raw2;
                        score = score2;
                        hit = hit2;
                    }
                }
            }
        }
        if (!hit || score < 0.6) {
            // 没读到可用字形 ⇒ **证据**：那格读不出来（带上墨迹计数，防止「有子没字」被当成空）
            return {
                ok: false, noText: true, score: score, reason: "置信度不足",
                text: raw, region: region, ink: ink
            };
        }
        // ★ 2026-09-20（用户提议「定位一次 + 找色判走动 + OCR 认子」）：
        //   顺带按**像素颜色**判阵营，让这一路自己给出「什么子 + 哪一边」——
        //   不吃模型的红黑读反，也不怕高亮环把格子盖住（找色只看墨迹的颜色）。
        //   判不出来就返回 null（不猜），调用方自己决定要不要用。
        let side = null;
        let sideScore = 0;
        try {
            const verdict = pieceSideModule.classifyAt(function (x, y) {
                return adapter.rgbAt(image, x, y);
            }, grid, index % 9, Math.floor(index / 9), {});
            if (verdict && verdict.side) {
                side = verdict.side;
                sideScore = verdict.score;
            }
        } catch (error) {
            side = null;
        }
        // ★ 2026-09-20 晚（真机 now44 定位）：**带「上一步光环」的棋子**会压住 classifyAt 的采样点 ⇒
        //   判不出（side=null）甚至判反，而面板侧各条路都要求 side 明确，不然整格被丢弃 ⇒
        //   缓存里留 '?' / 留错值 ⇒ 驱动每 13s 改正一次、下一帧又被打回（now44：同一格改正 3 次 + 卡死 62 轮）。
        //   字形**墨色计数**是硬证据（实测：红子 红~1000+/黑0，黑子相反，光环是白的、不参与计数）⇒
        //   「一边有、另一边≈0」时直接采信，正好盖过光环的干扰。
        if (ink) {
            const redInk = Number(ink.red) || 0;
            const blackInk = Number(ink.black) || 0;
            if (redInk >= 40 && blackInk <= 3) {
                side = "RED";
                sideScore = 0.6;
            } else if (blackInk >= 40 && redInk <= 3) {
                side = "BLACK";
                sideScore = 0.6;
            }
        }
        return {
            ok: true, cls: hit.cls, glyph: hit.glyph, score: score, text: raw, region: region,
            side: side, sideScore: sideScore, ink: ink
        };
    } catch (error) {
        return { ok: false, unavailable: true, reason: "OCR 异常：" + error };
    }
}

/**
 * 全盘 OCR 对照（每 `OCR_BOARD_INTERVAL_MS` 一次）："驱动认为的盘面" vs "OCR 逐格读出来的盘面"。
 *
 * 为什么要它（2026-09-19）：黑方那枚炮「凭空消失」时，光看驱动日志只能猜 ——
 * 驱动说 30 子、OCR 说 32 子，到底是哪一格被丢掉，一列就出来了。
 * ⚠️ 字形分不出红黑（黑炮也可能写成「炮」）⇒ 比对只比**兵种**（忽略大小写），打印时带字形。
 */
function compactBoard(chars) {
    const rows = [];
    for (let r = 0; r < 10; r++) {
        let line = "";
        let empty = 0;
        for (let c = 0; c < 9; c++) {
            const ch = chars[r * 9 + c];
            if (ch) {
                if (empty > 0) {
                    line += empty;
                    empty = 0;
                }
                line += ch;
            } else {
                empty += 1;
            }
        }
        if (empty > 0) {
            line += empty;
        }
        rows.push(line);
    }
    return rows.join("/");
}

/**
 * 记住本次识别里流水线**逐格读过什么**（`evidence`），给「全盘 OCR 询问」复用。
 *
 * 为什么要它（2026-09-20 性能账）：驱动每 ≥6s 问一次全盘 OCR，而面板**刚**为同一帧读过那些格子
 * （OCR 字形 + 找色颜色）—— 实测一次询问 1155ms 里 ~950ms 是把那 28 格重读一遍。
 * 只复用新鲜（≤ `OCR_REUSE_MS`）且几何未变的证据；不新鲜就照旧全扫。
 */
let lastEvidence = null;
const OCR_REUSE_MS = 3000;
function rememberPipelineEvidence(smart) {
    if (!smart || !smart.ok || !smart.evidence || !smart.evidence.length || !grid) {
        return;
    }
    lastEvidence = {
        cells: smart.evidence,
        geometry: [grid.originX, grid.originY, grid.stepX, grid.stepY].join(","),
        at: Date.now()
    };
}

/** 把上一条逐格证据摊成 board-scan 认的 `reuse`；不新鲜/几何变了就返回 null（宁可重读） */
function reuseFromLastEvidence() {
    if (!lastEvidence || !grid) {
        return null;
    }
    if (Date.now() - lastEvidence.at > OCR_REUSE_MS) {
        return null;
    }
    if (lastEvidence.geometry !== [grid.originX, grid.originY, grid.stepX, grid.stepY].join(",")) {
        return null;
    }
    const reuse = boardScan.reuseFromEvidence(lastEvidence.cells);
    return reuse.reused > 0 ? reuse : null;
}

/**
 * 对照用（**纯白拿**）：上次识别的逐格证据还在就直接拼一份「流水线读到的盘面」——
 * 不抓帧、不跑检测、不扫格子（0ms）。
 *
 * ⚠️ 两条边界（都必须守住）：
 *   · 它**只给对照日志**（`logOcrBoardCompare`）用。驱动要的「全盘 OCR 证据」不能走这条：
 *     流水线只看**有框的格子**，而驱动那条路的价值正在于「没框但有墨迹」的漏检候补格（要真扫才知道）。
 *   · 覆盖面只有 `evidence` 里出现过的格子 ⇒ 返回 `covered`，对比时**只比被覆盖的格**，
 *     否则「没看过」会被印成「OCR 没看到」（真机差异行就是这样误导人的）。
 */
function ocrBoardFromLastEvidence() {
    if (!lastEvidence || !grid || Date.now() - lastEvidence.at > OCR_REUSE_MS) {
        return null;
    }
    if (lastEvidence.geometry !== [grid.originX, grid.originY, grid.stepX, grid.stepY].join(",")) {
        return null;
    }
    const board = {
        ok: true, fromEvidence: true, ms: 0, at: lastEvidence.at,
        chars: new Array(90), glyphs: new Array(90), sides: new Array(90), scores: new Array(90),
        hits: 0, colourUnknown: 0, reused: 0, inkSkipped: 0, covered: []
    };
    const list = lastEvidence.cells || [];
    for (let k = 0; k < list.length; k++) {
        const one = list[k];
        if (!one || one.index == null || one.index < 0 || one.index > 89) {
            continue;
        }
        const i = one.index;
        board.covered.push(i);
        board.reused += 1;
        const cls = one.cls ? String(one.cls) : null;
        const side = (one.side === "RED" || one.side === "BLACK") ? one.side : null;
        board.scores[i] = one.ocrScore == null ? null : (Number(one.ocrScore) || 0);
        board.sides[i] = side || "UNKNOWN";
        let glyph = null;
        if (one.text) {
            const hit = pieceOcr.classOf(one.text);
            glyph = hit ? hit.glyph : null;
        }
        board.glyphs[i] = glyph;
        if (cls && side) {
            board.chars[i] = pieceOcr.applySide(cls, side);
            board.hits += 1;
        } else {
            board.chars[i] = null;
            if (glyph) {
                board.colourUnknown += 1;
            }
        }
    }
    return board.covered.length ? board : null;
}

function ocrBoard() {
    const session = ocrRuntime();
    if (!session || !grid) {
        return { ok: false, unavailable: true, reason: "OCR 或几何不可用" };
    }
    if (ocrBoardCache && Date.now() - ocrBoardCacheAt < OCR_BOARD_CACHE_MS) {
        return ocrBoardCache;
    }
    // ★ 抓帧也优先用「最近一帧」（同一轮观察里的证据复用；见 FRAME_REUSE_MS）
    let grabbed = null;
    const hot = recentFrameImage();
    if (hot) {
        frameReuseHits += 1;
        grabbed = { image: hot, path: null, channel: "reuse" };
    } else {
        grabbed = grabFrame();
    }
    if (!grabbed || !grabbed.image) {
        return { ok: false, unavailable: true, reason: "取帧失败" };
    }
    // ★ 复用刚为同一帧读过的格子（见 `rememberPipelineEvidence`）：那些格子不再重读
    const reuse = reuseFromLastEvidence();
    // ⚠️ 必须走 board-scan：它给的是**带红黑**的字符（字形只给兵种、颜色从像素来，方案 §3.6）。
    //    以前这里直接返回 `hit.cls`（全大写）⇒ 驱动把「模型漏检、OCR 看到字」的格子全补成红方，
    //    还把错颜色登记成已知事实 + 改可靠基准（真机日志：`(0,0)空→R (1,0)空→N …` 26 格）。
    //
    // ★ 颜色按 **YOLO 定位框**采（用户口径 2026-09-20：YOLO 只管定位，颜色判断就按它定的位置来）：
    //    框心比网格交点更贴棋子真位置 —— 交界格、以及被高亮环/装饰推偏的格，按格心采样容易把
    //    木纹采进去（真机 (8,9) 黑炮被采成红就是这一类）。
    let boxes = null;
    try {
        if (!visionDetectorSession) {
            visionDetectorSession = recognizeBoard.createDetectionSession({ autoAlign: false });
        }
        const detected = pieceDetector.detect({
            detect: function (img, detectOptions) {
                const out = visionDetectorSession.detect(img, grid, detectOptions || {});
                return (out && out.ok) ? (out.raw || []) : [];
            }
        }, grabbed.image, { autoAlign: false });
        if (detected && detected.ok && detected.boxes && detected.boxes.length) {
            const mapped = pieceDetector.mapToCells(detected.boxes, grid, { autoAlign: false });
            boxes = {};
            for (let i = 0; i < mapped.cells.length; i++) {
                const one = mapped.cells[i];
                if (one && one.index != null) {
                    boxes[one.index] = { box: one.box, residual: one.residual };
                }
            }
        }
    } catch (error) {
        boxes = null;   // 拿不到框就退空格心采样（board-scan 自己会兜底）
    }
    const scanned = boardScan.scan({
        session: session,
        rgbAt: function (image, x, y) { return adapter.rgbAt(image, x, y); },
        countColor: function (image, color, options) { return adapter.countColor(image, color, options); },
        prepareRegion: function (image, region, mode) { return adapter.prepareRegion(image, region, mode); },
        releaseFrame: function (frame) { return adapter.releaseFrame(frame); }
    }, grabbed.image, grid, { boxes: boxes, reuse: reuse, ocrPrimary: ocrPrimaryMode });
    if (!scanned.ok) {
        return { ok: false, unavailable: true, reason: scanned.reason || "扫描失败" };
    }
    if (scanned.grayHits > 0 || scanned.colorHits > 0) {
        // 主读=灰度时，`colorHits` 是「灰度读不出 → 彩色兜底救回」的账；老顺序则相反
        logLine("全盘 OCR 兜底救回 " + (scanned.grayHits + scanned.colorHits) + " 格（灰度 " +
            scanned.grayHits + " / 彩色 " + scanned.colorHits + "，共试 " +
            ((scanned.grayRetries || 0) + (scanned.colorRetries || 0)) + " 次）");
    }
    ocrBoardCache = {
        ok: true,
        chars: scanned.chars,
        glyphs: scanned.glyphs,
        sides: scanned.sides,
        scores: scanned.scores,
        hits: scanned.hits,
        colourUnknown: scanned.colourUnknown,
        grayHits: scanned.grayHits,
        grayRetries: scanned.grayRetries,
        colorHits: scanned.colorHits,
        colorRetries: scanned.colorRetries,
        /** 被「空框 + 无墨迹」闸门跳过的格子数（性能账本，2026-09-20） */
        inkSkipped: scanned.inkSkipped,
        /** 直接复用「刚为同一帧读过的逐格证据」的格子数（性能账本，2026-09-20） */
        reused: scanned.reused,
        ms: scanned.ms,
        // ★ 帧标识：驱动的「同一格连续 N 帧一致」闸门靠它区分新帧（命中缓存时它不变，
        //   所以重复问同一帧不会把计数刷上去）
        at: Date.now()
    };
    ocrBoardCacheAt = Date.now();
    return ocrBoardCache;
}

function logOcrBoardCompare() {
    // ★ 能白拿就白拿：上次识别的逐格证据还在就直接用它拼盘面（0 抓帧、0 扫描）。
    //   证据不新鲜就**跳过**（不再自己抓帧扫一遍）—— 这是调试日志，不该为它在主循环里花 ~900ms；
    //   驱动要的那次「全盘 OCR 询问」走的是 `ocrBoard()`（那条路才需要真扫没框但有墨迹的格）。
    const board = ocrBoardFromLastEvidence();
    if (!board || !board.ok) {
        logLine("全盘 OCR 对照：跳过（没有新鲜的逐格证据，不另花一次抓帧+扫描）");
        return;
    }
    const view = driver && driver.lastResult ? driver.lastResult() : null;
    const driverChars = view && view.chars ? view.chars : null;
    /** 被覆盖的格子；只比这些，否则「没看过」会被印成「OCR 没看到」 */
    const covered = board.covered || [];
    const coveredMap = {};
    for (let k = 0; k < covered.length; k++) {
        coveredMap[covered[k]] = true;
    }
    logLine("全盘 OCR 对照（读到 " + board.hits + " 格有字" +
        (board.colourUnknown ? ("，其中 " + board.colourUnknown + " 格颜色未定") : "") +
        "；复用上次识别的 " + covered.length + " 格逐格证据，未覆盖的格不参与比对，用时 " + board.ms + "ms）");
    logLine("  驱动：" + (view && view.layout ? view.layout : (driverChars ? compactBoard(driverChars) : "-")));
    logLine("  OCR ：" + compactBoard(board.glyphs));
    if (driverChars) {
        const diffs = [];
        const typeOnly = [];
        for (let i = 0; i < 90; i++) {
            if (covered && !coveredMap[i]) {
                continue;
            }
            const mine = driverChars[i] && driverChars[i] !== "?" ? String(driverChars[i]) : null;
            const theirs = board.chars[i] ? String(board.chars[i]) : null;
            if (String(mine || "").toLowerCase() === String(theirs || "").toLowerCase()) {
                continue;
            }
            const at = "(" + (i % 9) + "," + Math.floor(i / 9) + ")";
            if (!mine && theirs) {
                diffs.push(at + "OCR看到" + board.glyphs[i] + "｜驱动空");
            } else if (mine && !theirs) {
                diffs.push(at + "驱动" + driverChars[i] + "｜OCR没看到");
            } else {
                diffs.push(at + "驱动" + driverChars[i] + "｜OCR" + board.glyphs[i]);
                const sameColor = (mine === mine.toUpperCase()) === (theirs === theirs.toUpperCase());
                if (sameColor) {
                    typeOnly.push(at);
                }
            }
        }
        logLine(diffs.length === 0
            ? "  差异：无（两边一致）"
            : "  差异 " + diffs.length + " 处：" + diffs.slice(0, 8).join("；") + (diffs.length > 8 ? " …" : ""));
        if (typeOnly.length > 0) {
            logLine("  其中同色不同兵种（模型把炮认成兵/卒那类）：" + typeOnly.join(" "));
        }
    }
}

/**
 * 催游戏一下（画面卡在**走子动画中间**时用）。
 *
 * 现场（2026-09-19，K40 实拍放大）：黑方那手「炮 8 平 6（1,2 → 4,2）」卡住没落位 ——
 * 棋子是**隐形的**（在途），起点 (1,2) 已空，终点 (4,2) 只剩一个**闪烁的落点光环**。
 * 于是那两格永远读不出子（一会儿 `?`、一会儿空）→ 驱动每帧差一格未知 → 轮次未知空转。
 * 天天象棋是**人机对战**（电脑 AI 是瞬间应手），动画本该 1 秒内走完 —— 一连 3 帧读不出来
 * 就已经不正常了，所以驱动会调这里催一下。
 *
 * ⚠️ 只做**安全动作**，**绝不点棋盘**：万一那一格真的只是模型漏检，点下去就是走错棋。
 *   第 1 次：重新把游戏带回前台（`am start`，不重启进程）。
 *   第 2 次：用「最近任务」切出再切回（连按两次 APP_SWITCH），强制 pause→resume，
 *            让游戏的渲染/动画重新跑起来。
 */
function nudgeGame(info) {
    const text = info
        ? ("落点 (" + (info.index % 9) + "," + Math.floor(info.index / 9) + ") 连续 " + info.streak +
            " 帧读不出（已知事实 " + info.char + "）")
        : "";
    if (!info || info.attempt <= 1) {
        logLine("催游戏（第 1 次：重新带前台）：" + text);
        try {
            adapter.bringPackageToFront(gamePackage);
        } catch (error) {
            logLine("催游戏异常：" + error);
        }
        return;
    }
    logLine("催游戏（第 " + info.attempt + " 次：最近任务切出再切回，强制 pause→resume）：" + text);
    try {
        if (!adapter.bringToFrontByRecents()) {
            logLine("催游戏：最近任务切换不可用，退回重新带前台");
            adapter.bringPackageToFront(gamePackage);
        }
    } catch (error) {
        logLine("催游戏异常：" + error);
    }
}

/**
 * 把游戏留在前台（限频）：③ 的脚本宿主是一个透明窗口，它一拿到焦点游戏就 pause 了。
 *
 * 真机实测（K40，2026-09-14，r2.log）：面板跑着的时候对方（联网 AI「特级大师」）**一直不应手**
 * —— 日志里连续 25 次「轮到对方，等待其落子」、棋盘一直没变；面板一停、游戏拿回前台，
 * 棋盘几秒内就变成 4 步。没有这一步，③ 就是在「等一个永远不会来的应手」。
 *
 * 与出招前的前台守卫（foregroundGuard）的区别：那个是「出手前必须验一次」，
 * 这个是「等待期间持续把游戏留在前台」；两者共用适配层的 bringPackageToFront（不重启游戏进程）。
 */
/**
 * 面板启动时的一次性「把游戏带回前台」（见 main 里的注释）：
 * 直接把限频时间戳清 0 再走同一条路 —— 启动那一下必须真生效，不能因为上一次刚切过而被限频吞掉。
 */
function keepGameInFrontOnce() {
    lastKeepFrontAt = 0;
    keepGameInFront();
}

function keepGameInFront() {
    const now = Date.now();
    if (now - lastKeepFrontAt < 4000) {
        return;          // 限频：切前台本身有开销，而且频繁 am start 会把面板自己的输入也搅乱
    }
    lastKeepFrontAt = now;
    let focus = null;
    let top = null;
    try {
        focus = adapter.focusedWindowPackage();
    } catch (error) {
        focus = null;
    }
    try {
        top = adapter.foregroundPackage ? adapter.foregroundPackage() : null;
    } catch (error) {
        top = null;
    }
    if (focus === null && top === null) {
        return;          // 两个都读不到（没有 Shizuku/root）就不折腾
    }
    // 读数变化时记一行（限频 4s，最多 15 行/分钟）：真机上「此刻到底谁在前台」必须能查到 ——
    // 只按 focusedWindowPackage 判断时它一直报游戏（真机踩到：这一步一次都没触发，白等一整轮）。
    const text = "触摸焦点 " + (focus || "未知") + "／栈顶 " + (top || "未知");
    if (text !== lastFrontText) {
        lastFrontText = text;
        logLine("等对方应手期间的前台：" + text);
    }
    if (focus === gamePackage && top === gamePackage) {
        return;
    }
    try {
        const ok = adapter.bringPackageToFront(gamePackage);
        logLine("等对方应手：把游戏带回前台（之前 " + text + "，切换" + (ok ? "成功" : "失败") + "）");
        if (ok) {
            // 切完前台，画面会有一段时间在重建（恰好是动画/半帧的窗口）⇒ 让稳定帧闸门先预热一帧
            if (boardFrameGate) {
                boardFrameGate.onFocusChange();
            }
            // 基线本身在服务层（切窗前的采样不能拿来配新画面，否则会被判「不稳定」白丢一帧）
            if (boardService) {
                boardService.resetFrameBaseline();
            }
        }
    } catch (error) {
        // 切不动就算了：下一次 tick 还会再试，不抦断循环
    }
}

function startLoop() {
    if (running) {
        return { ok: false, reason: "已经在跑" };
    }
    // 互锁：主界面那边正在跑就先拒绝（两个引擎同时跑等于双份截图/算棋）
    const gate = loopLock.acquire(loopLock.OWNER_FLOATY);
    if (!gate.ok) {
        logLine("拒绝开始：" + gate.reason + "。先到主界面点「开始观察 / 暂停」把它停掉。");
        return { ok: false, reason: gate.reason };
    }
    if (modeKey === "auto") {
        logLine("③ 自动走棋会真的点击棋盘，请确认游戏在前台且轮到我方");
        // 开始前先查一次前台：脚本宿主（透明窗口）在前台时，注入的点击会被自己吃掉。
        // 是就自动用最近任务切一次 —— 免得用户以为「脚本坏了」（真机踩过一整轮全 UNCHANGED）。
        const pre = foregroundGuard();
        if (pre && pre.packageChanged) {
            logLine("开始前前台守卫：已把上一步应用切回前台" +
                (pre.ok ? "（当前 " + (pre.package || "?") + "）" : "（切不动，出招时会再试）"));
        } else if (pre && pre.package) {
            logLine("开始前前台：" + pre.package);
        }
    }
    try {
        buildDriver();
    } catch (error) {
        // 通道名非法 / 建驱动失败：**显式失败**，不要带着半个驱动继续跑（点击通道创建时就定了）
        loopLock.release(loopLock.OWNER_FLOATY);
        logLine("建驱动失败，已终止本次启动：" + error);
        return { ok: false, reason: "建驱动失败：" + error };
    }
    // 引擎预热：Pikafish 首次启动要加载 ~48MB NNUE 权重（K40 实测 ~0.7s）。
    // 放在这里做掉，别让第一步棋多等这一下；失败也只是日志（router 会自动回退 JS）。
    if (engineBackendKey !== "js") {
        const warm = engineModule.warmUpPikafish();
        if (warm && warm.ok) {
            logLine("引擎预热完成：" + engineModule.engineDescription());
        } else {
            logLine("引擎预热跳过：" + (warm && warm.reason ? warm.reason : "Pikafish 不可用") +
                "（本局算棋走 JS 引擎）");
        }
    }
    // ⚠️ 真机踩过：只建驱动 + 起定时器是不够的，状态机还在 STOPPED，
    //    每轮 tick() 都返回「没有在运行」。必须先 driver.start()（START → READY）。
    let started = null;
    try {
        // A1：启动也走会话（与停止同一处；驱动已在 buildDriver 里由会话构建）
        started = app ? app.session.start() : { ok: false, reason: "应用未装配" };
        if (started && started.ok === false) {
            throw new Error(started.reason || "会话启动失败");
        }
        logLine("驱动已启动，状态机：" + (started && started.state));
    } catch (error) {
        loopLock.release(loopLock.OWNER_FLOATY);
        logLine("驱动启动失败：" + error);
        return { ok: false, reason: "驱动启动失败：" + error };
    }
    running = true;
    scheduleNextTick();
    logLine("已开始：" + MODE_LABEL[modeKey] + "（已持有棋盘循环锁：" + loopLock.lockPath() + "，算棋 " +
        (driver && driver.computeMode ? driver.computeMode() : "?") + "｜" +
        engineModule.engineDescription() + "）");
    return { ok: true, state: started && started.state };
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
    if (app) {
        // A1：停止统一走会话（幂等；驱动与窗口兜底在会话里）
        try {
            app.session.stop("面板停止");
        } catch (error) {
            logLine("停止驱动出错：" + error);
        }
    }
    clearRecentFrame();                       // 停止后不保留整屏原生帧
    loopLock.release(loopLock.OWNER_FLOATY);   // 互锁：停了就把锁交出去
    // 点击租约兜底：异常路径（起点点了、落点没点）下面板可能还藏着，这里恢复回来
    if (typeof releaseTapLease === "function") {
        try {
            releaseTapLease();
        } catch (error) {
            logLine("释放点击租约失败：" + error);
        }
    }
    logLine("已停止：驱动已停、定时器已清、循环锁已释放（几何与校准结果保留）");
}

/**
 * 新会话（§14 / A1 批次2：从 sessionBtn 按钮体抽成命令实现，控制器与按钮共用）。
 * 停循环 → 驱动 newSession → 清服务层几何/缓存 → 面板备注。
 */
function newSession() {
    stopLoop();
    if (driver && typeof driver.newSession === "function") {
        driver.newSession();
    }
    grid = null;
    if (boardService) { boardService.clear(); }   // 几何/缓存放服务层一份，新会话要一起清
    calibrationNote = "已换会话（几何清空）";
    logLine("新会话：会话号 +1，票据与基准全部作废，几何已清空");
    render();
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
        // 互锁总线：发布面板状态 + 收页面命令（双向停止/配置同步）
        pumpBus();
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
                // 多出来的那部分就是「本站线程一次被占多久」：空载 1s 心跳的间隔本该 ~1000ms，
                // 观察一帧 ~500ms 时就是 ~1500ms（真机实测值）。
                console.log("[floaty] 心跳间隔 " + gap + "ms（≈本站线程被占 " + (gap - 1000) +
                    "ms：采集/识别，或退回的同步算棋）");
            }
        }
        aliveSeconds += 1;
        // 每 5 秒刷一次「触摸焦点在谁身上」：状态行要如实反映，不能让用户看着 `前台 游戏✓` 干等
        // （守卫只在出招前才查，中间被切走的话显示就是旧的）
        if (aliveSeconds % 5 === 0) {
            try {
                lastForeground = adapter.focusedWindowPackage();
            } catch (error) {
                // 查不到就保持上次的值
            }
        }
        try {
            if (collapsed && compactWin) {
                compactWin.compactStatus.setText(compactStateText());
            } else {
                win.status.setText(statusLine());
            }
        } catch (error) {
            // 渲染失败不影响保活
        }
        if (aliveSeconds % 30 === 0) {
            logLine("面板保活中（存活 " + aliveSeconds + "s，最长心跳间隔 " + lagMax +
                "ms，超 1.2s 的 " + lagCount + " 次）｜面板移动：自动贴板 " + panelMoveCounts.snap +
                "／藏显 " + panelMoveCounts.hide + " 次" + (snapPanelEnabled ? "" : "（贴板已关）") +
                nativeFrameStatsText());
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
                    clearRecentFrame();
                    releaseOcr();
                    if (visionDetectorSession) {
                        visionDetectorSession.close();
                        visionDetectorSession = null;
                    }
                    visionPipeline = null;
                } catch (error) {
                    // 模型资源释放失败也要继续关窗口
                }
                try {
                    adapter.overlayHide();   // 画板是独立悬浮窗，不收掉也会变僵尸窗口
                } catch (error) {
                    // 收不掉就算了
                }
                try {
                    if (win) {
                        win.close();
                    }
                    if (compactWin) {
                        compactWin.close();
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
    // §14：解析与校验只有一处（src/app/config.js）；面板只负责把结果应用到自己的变量上。
    // 读文件这一步从外面注进去，所以 config 不依赖宿主，随时能单测。
    const loaded = configModule.loadAutorun(function (path) {
        if (typeof files === "undefined" || !files || typeof files.read !== "function") {
            return null;
        }
        // ⚠️ 文件不存在是**正常情况**（多数人不用 autorun）：先 exists 再读。
        //    否则 files.read 抛 FileNotFoundException，会被 config 当成"致命配置错误"，
        //    日志里每次启动都堆两行红字、还写着"不自动启动"（2026-09-20 用户看到这个发懵）。
        try {
            if (typeof files.exists === "function" && !files.exists(path)) {
                return "";
            }
        } catch (error) {
            // 判不了就照旧读（真读不到会走下面的错误分支，那才是需要看见的）
        }
        return files.read(path);
    }, AUTORUN_FILE);

    const issues = loaded.issues || [];
    for (let i = 0; i < issues.length; i++) {
        logLine("配置" + (issues[i].level === "error" ? "错误" : "提示") + "：" +
            issues[i].key + " — " + issues[i].message);
    }
    autorunBlocking = configModule.hasBlockingIssue(issues);
    if (autorunBlocking) {
        logLine("autorun 有致命配置错误 → 按约定**不自动启动**（拼错的通道名会把对照实验弄脏）");
    }
    if (loaded.empty || !loaded.raw) {
        logLine("autorun：文件为空或不存在（路径 " + AUTORUN_FILE + "）");
        return null;
    }
    // 把「实际读的路径 + 原文」记下来：ADB 写的是哪个文件、脚本读的是哪个文件，
    // 不一致时（工程副本/示例副本/导入副本）会直接暴露出来，别再静默无效。
    logLine("autorun 文件 " + AUTORUN_FILE + " 原文 " + String(loaded.text || "").replace(/\s+/g, " "));
    // 归一化后的值盖在原始对象上（校验/夹取已经做过，下面的块只负责应用）
    return Object.assign({}, loaded.raw, loaded.config);
}

/**
 * 状态栏高度（面板默认停在屏幕顶端，不能压住状态栏）。
 * 量不到给 88px 兜底（K40 = 80px）。
 */
function statusBarHeight() {
    try {
        const res = context.getResources();
        const id = res.getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            const h = res.getDimensionPixelSize(id);
            if (h > 0) {
                return h;
            }
        }
    } catch (error) {
        // 拿不到就用兜底值
    }
    return 88;
}

/** 创建真正只有一行高的收起条。避免在整块面板里把控件 GONE 后仍留下遮挡触摸的大空白。 */
function ensureCompactWindow(x, y) {
    if (compactWin) {
        return compactWin;
    }
    compactWin = floaty.window(COMPACT_XML, { x: x, y: y, visible: true });
    compactWin.setTouchable(true);
    // 标题区在原生 UI 线程拖动；旧宿主保留 JS 兜底。
    let compactNativeDrag = false;
    try {
        if (typeof compactWin.setDragHandle === "function") {
            compactWin.setDragHandle("compactStatus", function () { panelUserMoved = true; });
            compactNativeDrag = true;
        }
    } catch (error) { compactNativeDrag = false; }
    if (!compactNativeDrag) {
        // 旧平台没有原生拖动时，给状态区一个限频拖动兜底
        let downX = 0;
        let downY = 0;
        let winX = x;
        let winY = y;
        let lastMoveAt = 0;
        compactWin.compactStatus.on("touch", function (event) {
            const action = event.getAction();
            if (action === 0) {
                downX = event.getRawX();
                downY = event.getRawY();
                winX = compactWin.getX(true);
                winY = compactWin.getY(true);
            } else if (action === 2 && Date.now() - lastMoveAt >= 40) {
                lastMoveAt = Date.now();
                compactWin.setPosition(winX + event.getRawX() - downX, winY + event.getRawY() - downY);
            }
            return true;
        });
    }
    compactWin.compactMode.on("click", function () {
        const index = MODES.indexOf(modeKey);
        modeKey = MODES[(index + 1) % MODES.length];
        if (running) {
            stopLoop();
            startLoop();
        }
        bus.syncConfig("mode", modeKey);
        logLine("模式切换为 " + MODE_LABEL[modeKey]);
        render();
    });
    compactWin.compactStart.on("click", function () {
        if (running) {
            stopLoop();
        } else {
            startLoop();
        }
        render();
    });
    compactWin.compactStop.on("click", function () {
        stopLoop();
        const other = bus.readOther();
        if (other && other.fresh && other.state && other.state.running) {
            bus.stopOther("面板点了停止");
            logLine("已请页面（主界面）也停止");
        }
        render();
    });
    compactWin.compactExpand.on("click", function () {
        setPanelCollapsed(false);
    });
    return compactWin;
}

/** 单窗口收起：隐藏次要内容并修改根布局高度，规避部分 ROM 的双窗口显隐异常。 */
function setPanelCollapsed(next) {
    next = !!next;
    if (next === collapsed) {
        return;
    }
    collapsed = next;
    try {
        panelY = win.getY(true);
    } catch (error) {
        // 位置读取失败不影响收起状态切换
    }
    try {
        const detailVisibility = next ? 8 : 0;
        win.gameRow.setVisibility(detailVisibility);
        win.diagnosticRow.setVisibility(detailVisibility);
        win.status.setVisibility(detailVisibility);
        win.step.setVisibility(detailVisibility);
        // 日志已经是**单独的窗口**：收起时不要 setVisibility（这台 ROM 上 re-visible 不可靠），
        // 改用「挪出屏幕再挪回」那条已验证的路（hideLogWindow / showLogWindow）。
        if (next) {
            hideLogWindow("收起面板");
        } else {
            logPosBeforeHide = null;        // 先清标记，让 showLogWindow 真的执行
            showLogWindow();
            if (logWin && typeof logWin.setPosition === "function") {
                try {
                    const size = adapter.screenSize();
                    snapLogWindowBelowBoard(size);
                } catch (error) {
                    // 摆不了就保留原位
                }
            }
        }
    } catch (error) {
        logLine("收起/展开失败：" + error);
    } finally {
        render();
    }
}

function main() {
    // 默认停在**棋盘上沿之上那条空木纹带**（见 snapPanelAboveBoard 的量测）：
    //   · 底端会挡住游戏自己的按钮排（菜单/显示分析/悔棋/提示）—— 用户投诉过；
    //   · 顶端（状态栏下方）会挡住对手头像/名字 —— 用户也投诉过；
    //   · 只有棋盘上方那条空带是真正不挡东西的（K40 ≈ 280..645）。
    // 定位到棋盘后会自动贴到棋盘上沿之上；用户拖过就不再抢。
    let startX = PANEL.marginX;
    let startY = 264;
    try {
        const size = adapter.screenSize();
        if (size && size.height > 0) {
            // 屏幕高 11% 是按 372 高调的基准位置；面板变高后等量上移（否则起始画面会压住棋盘）
            startY = Math.round(size.height * 0.11) - Math.max(0, PANEL_H - 372);
        }
        startY = clampPanelY(startY, size);
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
    panelY = startY;   // 记住我们自己摆的 y（snapPanelAboveBoard 靠它区分「用户拖过」）
    // ---- 日志窗（单独一个窗口，在屏幕下方；用户口径「悬浮窗面板在上面，日志在下面」）----
    // ⚠️ 它挡不到棋盘以外的东西：底边被 menuTop（85% 屏高）硬夹住，永不压底部菜单/提示。
    if (logWindowEnabled) {
        try {
            let logY = 0;
            try {
                const band = panelSafeBand(adapter.screenSize());
                logY = band.bottom - LOG_H;
            } catch (error) {
                logY = Math.round(2400 * 0.85) - 10 - LOG_H;
            }
            logWin = floaty.window(LOG_XML, { x: startX, y: logY, visible: true });
            logWin.setTouchable(false);       // 只展示，不接触摸（免得挡住棋盘上的点击）
            logPanelY = logY;
            logLine("日志窗已启动（屏幕下方，y=" + logY + "，底边不压底部菜单）");
        } catch (error) {
            logWin = null;
            console.log("[floaty] 日志窗建不出来（不影响面板）：" + error);
        }
    }
    // ⚠️ 不要在这里 setSize(…)：真机上会把窗口框体弄坏（零高 / 熔成一条窄条），尺寸全部由 XML 的 px 指定。
    logLine("面板已启动（" + describeCapabilities() + "）");
    logLine("临时目录：" + FRAME_DIR + "｜autorun：" + AUTORUN_FILE + "（shell 可写，便于 adb 驱动）");
    // 脚本指纹：确认手机上跑的到底是哪一份（工程副本/示例副本/导入副本很容易分家）
    logLine("脚本版本 PROBE_BUILD_ID=" + PROBE_BUILD_ID + " 指纹 " + probeFingerprint());
    logLine("提示：面板默认停在棋盘上方的空档（顶部不挡头像、底部不挡菜单/悔棋/提示），可拖标题栏改位置");
    /**
     * ★ 2026-09-20 用户报「为什么点击不了」：面板是用 `am start` 拉起来的，脚本宿主
     *   RunIntentActivity 是个 **fillxfill 的透明窗口** ⇒ 它一旦在前台，用户对棋盘的每一次触摸
     *   都被它吃掉（游戏根本收不到）。“你以为悬浮窗在上面、其实游戏点不动”就是这么来的。
     *   循环跑起来后每 tick 的 keepGameInFront() 会自己纠回来，可**没开跑之前**没人管 ——
     *   所以面板一启动就延迟一秒把游戏带回前台一次（只做一次，不周期抢；用户自己切 App 不拦）。
     */
    setTimeout(function () {
        try {
            keepGameInFrontOnce();
        } catch (error) {
            // 没有 Shizuku/root 就什么都不做
        }
    }, 1200);
    startKeepAlive();

    // ---- 拖动 ----
    // 之前是自己在 JS 里拖：**每个 ACTION_MOVE 都调一次 win.setPosition()**，
    // 每次都要过 JS→Java 桥 + WindowManager.updateViewLayout，而且触摸事件排在脚本线程上
    // （自动循环/算棋一跑就更卡）——真机上手感就是「拖着走一顿一顿」。
    // K40/LineageOS 上整窗原生拖动会吞掉按钮事件，因此只绑定标题区并限频移动。
    // 只拖标题控件，按钮仍可点击；旧宿主保留限频 JS 兜底。
    let nativeTitleDrag = false;
    try {
        if (typeof win.setDragHandle === "function") {
            win.setDragHandle("title", function () { panelUserMoved = true; });
            nativeTitleDrag = true;
        }
    } catch (error) { nativeTitleDrag = false; }
    logLine(nativeTitleDrag ? "拖动：标题栏原生" : "拖动：标题栏兼容模式");
    if (!nativeTitleDrag) {
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
            if (now - lastMoveAt < 40) return true;
            lastMoveAt = now;
            win.setPosition(winX + (event.getRawX() - downX), winY + (event.getRawY() - downY));
        } else if (action === 1) {
            winX += event.getRawX() - downX;
            winY += event.getRawY() - downY;
            win.setPosition(winX, winY);
            panelUserMoved = true;
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
        bus.syncConfig("mode", modeKey);   // 配置双向同步：页面跟着改
        logLine("模式切换为 " + MODE_LABEL[modeKey]);
        render();
    });
    win.startBtn.on("click", function () {
        // A1 批次2：启动/停止统一走控制器命令（实现见 ensureBoardService 里的 handlers）
        app.controller.run(running ? "STOP" : "START");
        render();
    });
    win.stopBtn.on("click", function () {
        app.controller.run("STOP");
        // 双向停止：页面（主界面）在跑也一起停掉（否则面板停了、页面还在抓屏/算棋）
        const other = bus.readOther();
        if (other && other.fresh && other.state && other.state.running) {
            bus.stopOther("面板点了停止");
            logLine("已请页面（主界面）也停止");
        }
        render();
    });
    win.locateBtn.on("click", function () {
        app.controller.run("RELOCATE");
        render();
    });
    win.recognizeBtn.on("click", function () {
        app.controller.run("RECOGNIZE");
        render();
    });
    win.selfcheckBtn.on("click", function () {
        app.controller.run("SELFTEST");
        render();
    });
    win.contractBtn.on("click", function () {
        app.controller.run("CONTRACT");
        render();
    });
    // 识别绘制：点一下 = 抓一帧 → 认一帧 → 把结果画在冻结的画面上（人审用）。
    // 再点 = 换成更详细的一档；从「全量」再点就收掉。循环在跑时会拒绝（驱动每帧抓屏会把画板截进图）。
    win.drawBtn.on("click", function () {
        if (!overlayOn) {
            drawMode = "cells";          // 先给「格子」档：最干净，一眼看每格读成了什么
            auditDraw();
        } else if (drawMode !== "full") {
            drawMode = "full";           // 再点：加上原始检测框和空格标记
            auditDraw();
        } else {
            drawMode = "off";
            refreshOverlay(null, null, { force: true });
            logLine("识别绘制：已收掉画板");
        }
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
            if (compactWin) {
                compactWin.close();
            }
        } catch (error) {
            console.log("[floaty] 关窗失败：" + error);
        }
    });
    logLine("按钮绑定完成：模式/收起/隐藏抓帧/微调/开始/停止/定位/识别/轮到我/换我方/新会话/自检/契约/清空/关闭");

    // ---- 参数滑条（2026-09-24 用户：在面板滑动选「算棋时间 / 线程」）----
    thinkSliderEl = win.thinkSlider;
    threadsSliderEl = win.threadsSlider;
    bindParamSlider(thinkSliderEl, {
        left: sliderLeft(0), width: sliderWidth(),
        min: THINK_MIN_MS, max: THINK_MAX_MS, step: THINK_STEP_MS,
        get: function () { return movetimeMsKey; },
        set: function (value) { movetimeMsKey = value; },
        commit: function (value) {
            if (driver && typeof driver.setMovetime === "function") {
                driver.setMovetime(value);
            }
            const store = paramStore();
            if (store) {
                try {
                    store.put("thinkMs", value);
                } catch (error) {
                    // 写不进就只生效本次
                }
            }
            logLine("算棋时间：" + (value / 1000).toFixed(1) + "s（下次算棋生效）");
        }
    });
    bindParamSlider(threadsSliderEl, {
        left: sliderLeft(1), width: sliderWidth(),
        min: THREAD_MIN, max: THREAD_MAX, step: 1,
        get: currentThreads,
        set: function (value) {
            pikafishOptions = Object.assign({}, pikafishOptions || {}, { threads: value });
        },
        commit: function (value) {
            engineModule.configurePikafish({ threads: value });
            if (driver && typeof driver.setPikafish === "function") {
                driver.setPikafish(pikafishOptions);
            }
            const store = paramStore();
            if (store) {
                try {
                    store.put("threads", value);
                } catch (error) {
                    // 写不进就只生效本次
                }
            }
            logLine("Pikafish 线程：" + value + "（下次算棋生效，会重启引擎）");
        }
    });
    // 上次滑过的值（storages 持久化；缺省 3500ms / 2 线程）
    (function loadParamStore() {
        const store = paramStore();
        if (!store) {
            return;
        }
        try {
            const think = Math.round(Number(store.get("thinkMs", 0)));
            if (isFinite(think) && think >= THINK_MIN_MS && think <= THINK_MAX_MS) {
                movetimeMsKey = think;
            }
        } catch (error) {
            // 读失败用默认
        }
        try {
            const th = Math.round(Number(store.get("threads", 0)));
            if (isFinite(th) && th >= THREAD_MIN && th <= THREAD_MAX) {
                pikafishOptions = Object.assign({}, pikafishOptions || {}, { threads: th });
                engineModule.configurePikafish({ threads: th });
            }
        } catch (error) {
            // 读失败用默认
        }
    }());
    renderParamSliders();
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
        // A1 批次2：命令式（带 side 参数；“没有驱动”由 handler 报错）
        app.controller.run("DECLARE_TURN", { side: driver ? driver.mySide() : "w" });
        render();
    });
    win.sideBtn.on("click", function () {
        const next = (driver ? driver.mySide() : "w") === "w" ? "b" : "w";
        if (driver) {
            driver.setMySide(next);
        }
        bus.syncConfig("side", next);   // 配置双向同步：页面跟着改
        logLine("我方改为 " + (next === "w" ? "红方" : "黑方") + "（旧基准已作废，需要重新声明轮次）");
        render();
    });
    win.sessionBtn.on("click", function () {
        // A1 批次2：按钮只发命令（实现见 newSession()）
        app.controller.run("NEW_SESSION");
    });
    win.collapseBtn.on("click", function () {
        setPanelCollapsed(!collapsed);
    });

    render();
    // ⚠️ 默认**不收起**（2026-09-20 用户要求「面板不要默认隐藏日志」）：
    //    收起会把日志区一起藏进小窗，用户看不到进程与日志。要小窗的人自己点「收起」，
    //    或无人值守时在 autorun 里写 {"collapse":true}（见下面 auto.collapse 的处理）。

    // 自动运行（无人值守验证用）
    const auto = readAutorun();
    if (auto) {
        logLine("检测到 autorun：" + JSON.stringify(auto));
        if (auto.collapse === true) {
            // 无人值守跑的**显式选择**（默认不收起：日志要看得见，见 main() 里的注释）
            setPanelCollapsed(true);
            logLine("面板已收起（autorun.collapse=true，日志只留小窗状态行）");
        }
        if (auto.captureProbe) {
            // 抓帧探测：两条截图通道到底哪条能用（最基础的可用性检查）
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
        if (auto.tapProbe) {
            // 单手探针（§10）：只发一手的两次手势 + 像素证据，通道对照实验用
            const result = runTapProbe(auto.tapProbe);
            if (result && auto.stopAfterProbe) {
                logLine("autorun：探针结束，按配置不启动自动循环");
                return;
            }
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
        if (auto.ocrBoard != null) {
            // 全盘 OCR 对照（默认开）：每 15s 把「驱动盘面」与「OCR 逐格盘面」并列打进日志。
            // 查「某一格/某一枚子凭空消失」时靠它一列就看清（见 logOcrBoardCompare 的说明）。
            ocrBoardEnabled = auto.ocrBoard === true;
            logLine("全盘 OCR 对照：" + (ocrBoardEnabled
                ? "开（每 15s 一次；能白拿就白拿 —— 复用上次识别的逐格证据，不重复抓帧/扫描）"
                : "关（autorun 里 \"ocrBoard\":true 可开）"));
        }
        if (auto.drawMode) {
            // 初始绘制档：off / grid / cells / full（必须在 auditDraw 之前处理，否则画完才改档）
            const key = String(auto.drawMode);
            if (DRAW_ORDER.indexOf(key) >= 0) {
                drawMode = key;
                logLine("识别绘制档位：" + DRAW_LABEL[drawMode]);
            }
        }
        if (auto.auditDraw) {
            // 人审用：抓一帧→认一帧→把结果画在冻结画面上（+落盘 audit-frame.png/audit-overlay.json）。
            // 无人工验证时靠它拿「带绘制的一帧」。
            auditDraw();
        }
        if (auto.mode) {
            modeKey = String(auto.mode);   // 合法性已在 config 校验，这里只应用
            logLine("模式设为 " + MODE_LABEL[modeKey]);
        }
        if (auto.autoAlign != null) {
            autoAlignOn = !!auto.autoAlign;
            logLine("几何自动微调：" + (autoAlignOn ? "开" : "关"));
        }
        // ⚠️ 必须**独立于** autoAlign 判断：autorun 里通常不写 autoAlign，
        //    塞在上面的块里会让 visionPipeline 静默不生效（2026-09-20 真机踩过：
        //    日志里没有任何「视觉流水线」字样，驱动其实还在走旧路径）。
        if (auto.visionPipeline != null) {
            logLine("autorun 的 visionPipeline 已废弃：识别链只有字形识别（无差分/流水线/旧路径）");
        }
        if (auto.glyph != null) {
            logLine("autorun 的 glyph 开关已废弃：字形识别是唯一识别链（始终启用）");
        }
        // 逐格 OCR 的灰度重试（落子高亮环格专用）：默认开；autorun 里 `"ocrGray": false` 可关掉，
        // 用于 A/B 对照 —— 或者某次真机上发现它反而把错读数写进观测层时先关。
        if (auto.ocrGray != null) {
            ocrGrayEnabled = auto.ocrGray !== false;
            logLine("逐格灰度路：" + (ocrGrayEnabled ? "开（默认）" : "关（全彩，A/B 对照）"));
        }
        // OCR 主读通道（2026-09-21 用户口径「先灰度」）：默认灰度优先；autorun `"ocrPrimary": "color"` 切回老顺序
        if (auto.ocrPrimary != null) {
            ocrPrimaryMode = auto.ocrPrimary === "color" ? "color" : "gray";
        }
        logLine("OCR 主读通道：" + (ocrPrimaryMode === "gray"
            ? "灰度优先（读不出/分过低 → 彩色兜底）"
            : "彩色优先（老顺序，灰度只补空）"));
        // 占用扫描取样框（差分模式）：默认 0.9×格；autorun `"scanInner": true` 切 0.6×格
        // （排除游戏画的选中/落子红环，真机实测环半径≈48px）—— 2026-09-20 A/B 实测 0.6 **更差**，
        // 详见 scanRegionFor / scanRatio 的注释；留着开关但别当默认。
        if (auto.scanInner != null) {
            scanRatio = auto.scanInner === true ? 0.6 : 0.9;
            logLine("占用扫描取样框：" + (scanRatio === 0.6
                ? "0.6×格（排除游戏画的环，实验档）"
                : "0.9×格（默认）"));
        }
        if (auto.gamePackage) {
            // 目标游戏包名（前台守卫要用它判断「焦点在不在游戏上」；默认腾讯天天象棋）
            gamePackage = String(auto.gamePackage).trim();
            logLine("目标游戏包名：" + gamePackage);
        }
        if (auto.clickMethod) {
            // 非法值**不静默退回 auto**：原样透传，让 createTap 在 buildDriver 里直接报错停下来
            clickMethodKey = String(auto.clickMethod);
            const legal = labelsModule.CHANNELS.indexOf(clickMethodKey) >= 0;
            logLine("点击通道偏好：" + clickMethodKey + (legal ? "" :
                "（⚠️ 非法值：只支持 " + labelsModule.CHANNELS.join(" / ") + "，启动时会直接报错）"));
        }
        if (auto.clickHideSettleMs != null) {
            clickHideSettleMs = Number(auto.clickHideSettleMs);
            logLine("点击前藏窗等待：" + clickHideSettleMs + "ms");
        }
        if (auto.clickPressMs != null) {
            clickPressMs = Number(auto.clickPressMs);
            logLine("点击按压时长：" + clickPressMs + "ms");
        }
        if (auto.movetimeMs != null) {
            // 算棋时间预算（2026-09-21 提速方案第 1 条：接入可配置的 movetimeMs）
            const mt = Math.round(Number(auto.movetimeMs));
            if (isFinite(mt) && mt >= 100 && mt <= 60000) {
                movetimeMsKey = mt;
                logLine("算棋时间预算：" + movetimeMsKey + "ms（默认 3500）");
            } else {
                logLine("算棋时间预算非法（" + auto.movetimeMs + "），沿用 " + movetimeMsKey + "ms");
            }
        }
        if (auto.clickSettleMs != null) {
            // 两击间隔（2026-09-21 提速方案第 1 条：接入可配置的 clickSettleMs）
            const cs = Math.round(Number(auto.clickSettleMs));
            if (isFinite(cs) && cs >= 0 && cs <= 3000) {
                clickSettleMsKey = cs;
                logLine("两击间隔：" + clickSettleMsKey + "ms");
            } else {
                logLine("两击间隔非法（" + auto.clickSettleMs + "），沿用默认公式");
            }
        }
        if (auto.computeMode) {
            // "worker"（默认，独立引擎线程）/ "inline"（本线程同步，对照老路径）
            computeModeKey = String(auto.computeMode) === "inline" ? "inline" : "worker";
            logLine("算棋模式：" + computeModeKey);
        }
        if (auto.pikafish) {
            // Pikafish 参数覆盖（worker 与 inline 都会带过去）：
            //   "pikafish": {"evalFile":"/sdcard/xiangqi-p4/pikafish.nnue","threads":2,"hashMb":128}
            pikafishOptions = {
                evalFile: auto.pikafish.evalFile == null ? null : String(auto.pikafish.evalFile),
                threads: auto.pikafish.threads == null ? null : Math.round(Number(auto.pikafish.threads)),
                hashMb: auto.pikafish.hashMb == null ? null : Math.round(Number(auto.pikafish.hashMb))
            };
            engineModule.configurePikafish(pikafishOptions);
            logLine("Pikafish 参数：" + JSON.stringify(pikafishOptions));
        }
        if (auto.engine) {
            // "auto"（默认，有 Pikafish 就用）/ "pikafish"（强制，失败不降级）/ "js"（老路径）
            engineBackendKey = engineModule.setBackend(String(auto.engine));
            logLine("算棋引擎：" + engineModule.engineDescription());
        }
        if (auto.observeFrames != null || auto.observeGapMs != null) {
            // 一次观察几帧 / 帧间隔（A/B 用）：1 帧省 ~0.4s/tick，但要盯「确认是不是变慢、误判是不是变多」。
            // 驱动自己会 clamp 到 1~3 帧（见 auto-driver 的 observe 循环）。
            if (auto.observeFrames != null) {
                observeFramesKey = Math.max(1, Math.min(3, Math.round(Number(auto.observeFrames) || 2)));
            }
            if (auto.observeGapMs != null) {
                const gap = Math.round(Number(auto.observeGapMs));
                observeGapMsKey = isFinite(gap) && gap >= 0 ? gap : 260;
            }
            logLine("观察帧数：" + observeFramesKey + " 帧（间隔 " + observeGapMsKey + "ms；默认 2 帧/260ms）");
        }
        if (auto.debugFrameProbe != null) {
            // 帧生命周期探针（2026-09-21 内存泄漏定位）：观察循环里打「活跃原生帧」检查点。
            frameProbeOn = !!auto.debugFrameProbe;
            logLine("帧探针：" + (frameProbeOn ? "开（每 tick 打检查点）" : "关"));
        }
        if (auto.replyFastPath != null) {
            // 对方应手「占用唯一解」快路（2026-09-21）：等待对方时用占用扫描直接定那一手，省掉 OCR/跨帧/恢复
            replyFastPathKey = auto.replyFastPath !== false;
            logLine("对方应手快路（占用唯一解）：" + (replyFastPathKey ? "开" : "关"));
        }
        if (auto.snapPanel === false) {
            // 面板「自己动」的两个来源之一（另一个是抓帧/点击时的挪出屏幕再挪回，见 hidePanel）。
            // 关掉之后面板就停在你放的位置（代价：可能压住棋盘顶行 → 抓帧会改成「藏面板」）。
            snapPanelEnabled = false;
            logLine("自动贴板：关（面板停在你放的位置；默认会贴到棋盘上沿之上那条空带）");
        }
        if (auto.captureBackend) {
            // "auto" / "app" / "shell"（Shizuku/root 跑 screencap，免投屏授权、无人值守首选）。
            // persist:false —— 这是测试钩子，别把偏好写进 storages（界面上选的才算主人选择）。
            const key = adapter.setCaptureBackend(auto.captureBackend, { persist: false });
            logLine("截图通道偏好：" + key + "（最近一次实际用过：" + (adapter.captureChannel() || "未用过") + "）");
        }
        if (auto.start && autorunBlocking) {
            logLine("autorun：配置有致命错误，未自动开始（修好 autorun 再启动）");
        } else if (auto.start) {
            // 测试钩子：autorun 里带 grid 就直接当几何（无人值守续跑；中局屏幕上线定位易失败时很有用）。
            // ⚠ 面板自己有一份 grid（驱动经 getGrid 端口读它），服务层也有一份 —— 两份都要写；
            //   且 autorun 比 ensureBoardService 早，先 ensure 再 setGrid。
            if (auto.grid && auto.grid.stepX && auto.grid.stepY) {
                grid = Object.assign({ orientation: "RED_BOTTOM", version: 1 }, auto.grid);
                ensureBoardService().setGrid(grid);
                logLine("autorun：用配置里的几何（起点 " + auto.grid.originX + "," + auto.grid.originY +
                    " 格距 " + auto.grid.stepX + "," + auto.grid.stepY + "）");
            }
            // 2026-09-24：冷启动没有几何时，驱动每 tick 都报「定位由面板负责，请先把 grid 同步进服务」——
            // autorun 先自己把定位校准做掉（auto.locate=false 可显式跳过）。
            let hasGrid = false;
            try {
                hasGrid = !!(boardService && boardService.grid && boardService.grid());
            } catch (error) {
                hasGrid = false;
            }
            if (!hasGrid && auto.locate !== false) {
                logLine("autorun：还没有几何，先自动定位校准（auto.locate=false 可关）");
                locateAndCalibrate();
            }
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
        renderParamSliders();   // 参数滑条始终反映当前值（autorun 可能改过 movetime/线程）
        render();
    }
}

try {
    main();
} catch (error) {
    console.log("[floaty] 启动失败：" + error);
}
