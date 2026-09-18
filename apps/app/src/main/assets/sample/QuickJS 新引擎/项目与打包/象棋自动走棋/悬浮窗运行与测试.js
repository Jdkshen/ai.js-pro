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
 * 探针版本号：**改脚本时手动 +1**，所有实验记录里都带上它。
 * 真机坑：手机上的工程副本、内置示例、资源导入副本、重新装 APK 都可能各不相同，
 * 不打印版本与指纹就无法确认「跑的是哪一份」（曾经把代码改了、手机却没生效，白排查一整轮）。
 */
const PROBE_BUILD_ID = "2026-09-14.3";
/** 版本核对用的关键文件（相对工程根）。第一个用**入口脚本自身文件名**，
 *  因为探针/实验可能把面板另存为别的名字运行（panel-probe.js），写死名字会读到旧副本。 */
const PROBE_SELF_NAME = (typeof __filename === "string" && __filename)
    ? String(__filename).split("/").pop()
    : "悬浮窗运行与测试.js";
const PROBE_FILES = [
    PROBE_SELF_NAME,
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

const adapter = load("src/runtime/aijs-adapter.js");
const geometryModule = load("src/vision/geometry.js");
const recognizeBoard = load("tools/recognize-board.js");
const locateBoard = load("tools/locate-board.js");
const driverModule = load("src/automation/auto-driver.js");
const engineModule = load("src/engine/engine.js");
const cellsContract = load("src/vision/cells.js");
// 探针的开局校验要用：FEN 解析 + 盘面比对（只读，不参与循环）
const fenModule = load("src/chess/fen.js");
const legalModule = load("src/chess/legal-moves.js");
// §14 框架层：文案/令牌只定义一次（ui），配置只解析一处（app/config），日志走 diagnostics
const labelsModule = load("src/ui/labels.js");
const themeTokens = load("src/ui/theme-tokens.js");
const configModule = load("src/app/config.js");
const recorderModule = load("src/diagnostics/recorder.js");
const overlayDraw = load("src/vision/overlay-draw.js");
// 跨引擎互锁：主界面（main.js）里还有另一套驱动循环，两边一起跑会双份截图/算棋，
// 模式③还会双份点击棋盘 —— 用同一个锁文件互斥（见 src/automation/loop-lock.js）。
const loopLock = load("src/automation/loop-lock.js");
// 跨引擎互锁总线（与主界面 main.js 共用）：状态互锁 + 双向停止 + 配置双向同步。
// 锁只能「拒绝同时跑」，看不到对面在不在跑、也没法从这边停那边 —— 所以另开一条文件总线。
const busModule = load("src/app/panel-bus.js");

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
let lastThinkingLogAt = 0;    // 算棋中按秒报一次状态，不刷屏
/** 「把游戏留在前台」上次尝试的时间（限频用，见 keepGameInFront） */
let lastKeepFrontAt = 0;
/** 上一次打印的前台读数（只在变化时打，避免刷屏） */
let lastFrontText = "";
let hideOnCapture = "auto";  // "auto" 只在面板真的压住棋盘时才藏（默认）；true/false 手动开/关
let autoAlignOn = false;      // 几何按检测框自动微调（真机实测会把子认重，默认关）
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
const bus = busModule.createPanelBus({
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
        drawMode: drawMode
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
    return "页面：" + (other.state.running ? "跑中" : "未开始") +
        "｜" + String(MODE_LABEL[other.state.mode] || "?").replace(/\s/g, "");
}

/** 状态栏文字（心跳里每秒刷一次，写成一个函数免得两处不同步）。 */
function statusLine() {
    // 面板宽度有限：太长会在窄行里换行 → 第二行被裁。9sp 下约 24.8px/字，1008px 只放得下 ~40 字。
    const note = calibrationNote.length > 20 ? (calibrationNote.slice(0, 19) + "…") : calibrationNote;
    const fg = lastForeground == null
        ? "?"
        : (lastForeground === gamePackage ? "游戏✓" : (lastForeground === OWN_PACKAGE ? "自身✗" : lastForeground));
    return note + "｜" + (running ? "运行中" : "已停止") + "｜" + aliveSeconds + "s｜前台 " + fg +
        "｜" + otherLineShort();
}
let collapsed = false;
let grid = null;          // 已校准几何（会话内复用）
let calibrationNote = "未校准";
const logLines = [];
/** 屏上只显示最后几行（面板得矮到能塞进棋盘上方的空木纹带）；完整日志走 logcat（前缀 [floaty]） */
const LOG_LINES_COMPACT = themeTokens.LOG_LINES_COMPACT;    // 实测行高 ≈ 1.35×字号 → 2 行 = 60px，日志区 76px

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
function stepColor() {
    const signed = function (hex) { return hex >= 0x80000000 ? hex - 0x100000000 : hex; };
    let state = "";
    try {
        state = driver && driver.machine ? driver.machine.state() : "";
    } catch (error) {
        state = "";
    }
    if (state === "RECOVERING") {
        return signed(0xFFFF8A80);   // 红：在恢复，得管
    }
    if (state === "WAIT_TURN" || state === "WAIT_STABLE") {
        return signed(0xFFF5C56B);   // 黄：在等（正常但停滞）
    }
    if (running && (state === "THINKING" || state === "CLICKING" || state === "VERIFYING" ||
        state === "SUGGESTED" || state === "PRECHECK")) {
        return signed(0xFF7FE39A);   // 绿：在干活 / 已有结论
    }
    return signed(0xFF8F9AA8);       // 灰：没跑
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
    if (progress.point) {
        line2.push("落点 " + progress.point);
    }
    if (progress.confirm) {
        line2.push("确认 " + progress.confirm);
    }
    // 两行各自限长：9sp 下 ~37 字就顶到右边缘，再多会换行（换行 = 吃掉一行显示位）
    const clip = function (text) {
        return text.length > 38 ? (text.slice(0, 37) + "…") : text;
    };
    return clip(line1.join(" │ ")) + "\n" +
        clip(line2.length ? line2.join(" │ ") : (running ? "…" : "等待开始（点「开始」）"));
}
const LOG_LINES_FULL = 12;   // 缓冲区保留行数（方便 console 侧对账）
/** 窗口高度只由 XML 的 px 决定（见 applyWindowHeight 的注释：setSize 会把窗口框体弄坏）——
 *  这个常量必须与 XML 根节点的 h 一致（改 XML 记得一起改，panelRect 的重叠判定与自动落位要用它）。 */
const WIN_H_COMPACT = 372;

function visibleLog() {
    // 心跳类日志每秒一条，会把有用的事件挤走 → 不上屏（console 里依旧有）；
    // 过长的行（局面串/路径）也截断：面板里一换行就等于吃掉两行显示位。
    const keep = [];
    for (let i = 0; i < logLines.length; i++) {
        const line = logLines[i];
        if (/心跳间隔|保活中|心跳延迟/.test(line)) {
            continue;
        }
        keep.push(line.length > 42 ? (line.slice(0, 41) + "…") : line);
    }
    return keep.slice(Math.max(0, keep.length - LOG_LINES_COMPACT));
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
    panel: "#F0151A22",
    title: "#FF7FD1FF",
    btnNeutral: "#FF2A313C",
    btnNeutralFg: "#FFE6EAF2",
    btnPrimary: "#FF1F8B4C",
    btnDanger: "#FFA93B3B",
    btnAccent: "#FF2F6FB5",
    btnDim: "#FF232A33",
    btnDimFg: "#FFAEB7C4",
    card: "#FF1B222C",
    step: "#FFF5C56B",
    logBg: "#FF141920",
    logFg: "#FFB8C0CC",
    status: "#FF8F9AA8"
};

/** 按钮：统一用带底色的 <text> 冒充（<button> 在这个引擎上收不到 click，见下方长注释）。 */
function button(id, label, width, bg, fg) {
    return '    <text id="' + id + '" text="' + label + '" w="' + width + 'px" h="42px" bg="' +
        (bg || C.btnNeutral) + '" textColor="' + (fg || C.btnNeutralFg) +
        '" textSize="10" gravity="center"/>';
}

const XML = [
    '<vertical id="root" w="1020px" h="372px" bg="' + C.panel + '" padding="6px">',
    // 第 1 行：标题 + 模式 + 跑/停 + 收起/关闭
    '  <horizontal h="42px">',
    '    <text id="title" text="象棋面板" w="150px" h="42px" textColor="' + C.title +
    '" textSize="12" gravity="center"/>',
    button("modeBtn", "①只识别", 170, C.btnAccent),
    button("startBtn", "开始", 120, C.btnPrimary),
    button("stopBtn", "停止", 115, C.btnDanger),
    button("collapseBtn", "收起", 95, C.btnDim, C.btnDimFg),
    button("closeBtn", "关闭", 105, C.btnDanger),
    '  </horizontal>',
    // 第 2 行：定位/识别 + 阵营 + 两个开关
    '  <horizontal h="42px">',
    button("locateBtn", "定位+校准", 170, C.btnAccent),
    button("recognizeBtn", "识别一帧", 150, C.btnAccent),
    button("turnBtn", "轮到我", 110),
    button("sideBtn", "换我方", 110),
    button("hideBtn", "抓帧：自动", 140, C.btnDim, C.btnDimFg),
    button("alignBtn", "微调：关", 115, C.btnDim, C.btnDimFg),
    '  </horizontal>',
    // 第 3 行：诊断类 + 识别绘制
    '  <horizontal h="42px">',
    button("sessionBtn", "新会话", 110, C.btnDim, C.btnDimFg),
    button("selfcheckBtn", "离线自检", 130, C.btnDim, C.btnDimFg),
    button("contractBtn", "契约测试", 130, C.btnDim, C.btnDimFg),
    button("clearBtn", "清空日志", 130, C.btnDim, C.btnDimFg),
    button("drawBtn", "绘制：关", 120, C.btnDim, C.btnDimFg),
    '  </horizontal>',
    '  <text id="status" text="准备中…" w="1008px" h="34px" textColor="' + C.status +
    '" textSize="9" gravity="center_vertical" padding="2px"/>',
    '  <text id="step" text="进度：等待开始" w="1008px" h="80px" bg="' + C.card +
    '" textColor="' + C.step + '" textSize="9" padding="6px"/>',
    '  <text id="logArea" text="" w="1008px" h="76px" bg="' + C.logBg +
    '" textColor="' + C.logFg + '" textSize="8" padding="6px"/>',
    '</vertical>'
].join("\n");

let win = null;

function render() {
    if (!win) {
        return;
    }
    try {
        win.status.setText(statusLine());
        win.step.setText(stepLine());
        win.logArea.setText(visibleLog().join("\n"));
        // 运行期换色（增强：状态绿/黄/红）。不同引擎版本暴露的方法不一样，失败就算了。
        try {
            if (win.step && typeof win.step.setTextColor === "function") {
                win.step.setTextColor(stepColor());
            }
        } catch (error) {
            // 不支持就维持 XML 里的静态色
        }
        win.modeBtn.setText(MODE_LABEL[modeKey]);
        win.startBtn.setText(running ? "暂停" : "开始");
        win.collapseBtn.setText(collapsed ? "展开" : "收起");
        win.hideBtn.setText("抓帧：" + (hideOnCapture === "auto" ? "自动" : (hideOnCapture ? "开" : "关")));
        win.alignBtn.setText("微调：" + (autoAlignOn ? "开" : "关"));
        win.drawBtn.setText("绘制：" + DRAW_LABEL[drawMode]);
    } catch (error) {
        console.log("[floaty] 渲染失败：" + error);
    }
}

/**
 * 面板当前占的屏幕矩形（XML 尺寸是固定的 1020×470；位置可能被用户拖过）。
 * 拿不到就返回 null（调用方按「可能挡着」处理）。
 */
const PANEL_W = 1020;
const PANEL_H = 372;
/** 我们自己最后一次设的 y（用于区分「用户拖过」与「我们摆的」）。 */
let panelY = null;

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

/**
 * 把面板摆到**棋盘上沿之上那条空带**里 —— 真机（天天象棋 1080×2400）实测：
 *   状态栏 0..80｜对手头像+名字 137..269｜**空木纹带 280..645**｜棋盘（含子上沿）647..1620｜
 *   空档 1620..1890｜「6步」+我方头像 1900..2010｜菜单/显示分析/悔棋/提示 2060..2280
 * 所以面板 372px 高时正好塞进 280..645：**顶部不挡头像、不挡棋盘、底部不挡游戏按钮**。
 * 用户自己拖过（当前 y 与我们上次设的差得远）就不再抢；定位到棋盘之前先用屏幕高比例兜底。
 */
function snapPanelAboveBoard() {
    try {
        const size = adapter.screenSize();
        // 兜底位置 = 屏幕高 11%（K40: 2400×0.11 = 264）——真机实测正好落在棋盘上方那条空带里。
        let y = size && size.height > 0 ? Math.round(size.height * 0.11) : 264;
        const box = grid ? geometryModule.boundsOf(grid, 0) : null;
        if (box && isFinite(box.y)) {
            // 棋子圆盘比「第一条线」高出约 55~60px（半径 + 边距）→ 面板底边要留出这段。
            // ⚠️ **只允许往上挪**（Math.min）：校准偶发几十像素偏差（真机出现过偏移 (-83,36)）时，
            // 若直接按 box.y 摆就会把面板推进棋盘、盖住底线那排子（真机踩过）。
            y = Math.min(y, Math.round(box.y - 62 - PANEL_H - 10));
        }
        const minY = statusBarHeight() + 4;
        if (y < minY) {
            y = minY;
        }
        let cur = null;
        try {
            cur = win.getY(true);
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
            const curX = win.getX(true);
            if (isFinite(curX)) {
                x = curX;
            }
        } catch (error) {
            // 用默认 x
        }
        win.setPosition(x, y);
        panelY = y;
        logLine("面板已贴到棋盘上沿之上（y=" + y + "，不挡两侧按钮）");
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
    // ⚠️ 判不出来时**不要**藏（原来是 true，「安全」但真机上是灾难）：
    // 启动/校准阶段棋盘范围还不知道 → 每帧都隐藏面板，而 `win.setVisibility(true)` 恢复
    // 在真机上不生效（窗口直接从 WindowManager 里消失、再也回不来，dumpsys 里连窗口都没有）
    // —— 用户看到的现象就是「跑起来之后面板没了，看不到日志/进度」。
    // 面板默认停在屏幕顶端、棋盘上方；真压住了（重叠判定得到）再藏。
    return overlap === null ? false : overlap;
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
        // ⚠️ 点击租约期间不许把面板恢复出来：那样等于在两次点击之间又动了一次窗口，
        // 游戏会丢掉"已选中"（真机定位到根因的一条）。
        if (hidden && !clickLeaseActive) {
            try {
                win.setVisibility(true);
            } catch (error) {
                logLine("恢复面板失败：" + error);
            }
        }
    }
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
        hidden = win.setVisibility(false) !== false;
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
                win.setVisibility(true);
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
        snapPanelAboveBoard();   // 知道棋盘在哪了 → 把自己摆到棋盘上沿之上那条空带里
        return calibrated;
    }
    if (located.grid) {
        grid = located.grid;
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
                if (win.setVisibility(false) !== false) {
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
                win.setVisibility(true);
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
    driver = driverModule.createDriver({
        allowClick: clickAllowed,
        tap: tapFunction,
        recognize: function (options) {
            // ⚠️ 每帧都要走 grabFrame()（会按需先把面板藏起来再抓帧）：驱动默认走
            // recognizeScreen → adapter.capture，那是**不藏面板**的路径，面板一旦压住棋盘，
            // 抓到的帧里就带着面板的按钮/文字 → 一批格子被判成未知 → 驱动卡在 RECOVERING。
            // 真机踩过：同一帧「校准（会藏）未知 0」而「驱动观察（不藏）未知 23」。
            const grabbed = grabFrame();
            // autoAlign（按检测框微调）默认关：真机实测它会把同一子检测两次合并成一格，
            // 认出来变成「红R 3 个」这种非法局面；所有采集/评估工具也都是 autoAlign:false。
            const recognized = recognizeBoard.recognizeScreen(Object.assign({}, options, {
                autoAlign: autoAlignOn,
                frame: grabbed ? grabbed.image : undefined
            }));
            refreshOverlay(recognized && recognized.ok ? recognized : null, null);
            return recognized;
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
        foregroundGuard: foregroundGuard,
        log: function (text) { logLine(text); },
        config: {
            tickMs: 2400,          // screencap 通道 0.6~1.4s/帧；而且**游戏落子有动画** ——
            // K40 实测 tick=1400 时，点击后第一次确认会看到「还没动」的旧帧 → 误判 UNCHANGED
            // → 状态机走恢复、把轮次基准弄丢 → 非开局局面就再也推不出轮次。所以放大到 2400。
            movetimeMs: 1200,
            modelVersion: "chessai_160x320_v5r",
            confidence: 0.25,
            orientation: "RED_BOTTOM",
            autoAlign: autoAlignOn,
            // 算棋默认放独立引擎线程（worker）：不然一步 ~1.2s 的搜索会把面板触摸一起挡掉。
            // autorun 里写 "computeMode":"inline" 可以对照老路径。
            computeMode: computeModeKey
        },
        // 两击之间的间隔：太短会「只选中、没落子」（选中动画还没画完）。
        // ⚠️ 这**不是**之前「一手棋全 UNCHANGED」的原因 —— 真因是**前台是本应用**（点击被脚本宿主吃掉），
        // 已由 foregroundGuard 在看。这里保持适度间隔即可（350ms 按压 + 600ms 间隔 = 950ms）。
        executorConfig: { pressMs: clickPressMs, settleMs: Math.max(200, clickPressMs + 600) }
    });
    return driver;
}

function tick() {
    if (!driver) {
        return;
    }
    try {
        // ★ 先把游戏留在前台再观察：透明宿主的窗口一拿到焦点，游戏就 pause → 对方永不应手
        //   （真机证据见 keepGameInFront 的注释）。点击租约期间不动，免得抦断正在发的手势。
        if (!clickLeaseActive && (modeKey === "auto" || modeKey === "advice")) {
            keepGameInFront();
        }
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
            const unknown = result.unknownCells == null ? "?" : result.unknownCells;
            logLine("本轮观察：未知格 " + unknown + "，耗时 " + stats.lastObserveMs + "ms" +
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
    } catch (error) {
        // 切不动就算了：下一次 tick 还会再试，不抦断循环
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
        return;
    }
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
                    adapter.overlayHide();   // 画板是独立悬浮窗，不收掉也会变僵尸窗口
                } catch (error) {
                    // 收不掉就算了
                }
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
    // §14：解析与校验只有一处（src/app/config.js）；面板只负责把结果应用到自己的变量上。
    // 读文件这一步从外面注进去，所以 config 不依赖宿主，随时能单测。
    const loaded = configModule.loadAutorun(function (path) {
        if (typeof files === "undefined" || !files || typeof files.read !== "function") {
            return null;
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

function main() {
    // 默认停在**棋盘上沿之上那条空木纹带**（见 snapPanelAboveBoard 的量测）：
    //   · 底端会挡住游戏自己的按钮排（菜单/显示分析/悔棋/提示）—— 用户投诉过；
    //   · 顶端（状态栏下方）会挡住对手头像/名字 —— 用户也投诉过；
    //   · 只有棋盘上方那条空带是真正不挡东西的（K40 ≈ 280..645）。
    // 定位到棋盘后会自动贴到棋盘上沿之上；用户拖过就不再抢。
    let startX = 30;
    let startY = 264;
    try {
        const size = adapter.screenSize();
        if (size && size.height > 0) {
            startY = Math.round(size.height * 0.11);
        }
        startY = Math.max(startY, statusBarHeight() + 4);
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
    // ⚠️ 不要在这里 setSize(…)：真机上会把窗口框体弄坏（零高 / 熔成一条窄条），尺寸全部由 XML 的 px 指定。
    logLine("面板已启动（" + describeCapabilities() + "）");
    logLine("临时目录：" + FRAME_DIR + "｜autorun：" + AUTORUN_FILE + "（shell 可写，便于 adb 驱动）");
    // 脚本指纹：确认手机上跑的到底是哪一份（工程副本/示例副本/导入副本很容易分家）
    logLine("脚本版本 PROBE_BUILD_ID=" + PROBE_BUILD_ID + " 指纹 " + probeFingerprint());
    logLine("提示：面板默认停在棋盘上方的空档（顶部不挡头像、底部不挡菜单/悔棋/提示），可拖标题栏改位置");
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
        bus.syncConfig("mode", modeKey);   // 配置双向同步：页面跟着改
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
        // 双向停止：页面（主界面）在跑也一起停掉（否则面板停了、页面还在抓屏/算棋）
        const other = bus.readOther();
        if (other && other.fresh && other.state && other.state.running) {
            bus.stopOther("面板点了停止");
            logLine("已请页面（主界面）也停止");
        }
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
        bus.syncConfig("side", next);   // 配置双向同步：页面跟着改
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
        if (auto.computeMode) {
            // "worker"（默认，独立引擎线程）/ "inline"（本线程同步，对照老路径）
            computeModeKey = String(auto.computeMode) === "inline" ? "inline" : "worker";
            logLine("算棋模式：" + computeModeKey);
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
