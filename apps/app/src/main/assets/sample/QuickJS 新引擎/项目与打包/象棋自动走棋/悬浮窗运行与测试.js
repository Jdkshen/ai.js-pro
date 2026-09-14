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
const overlayDraw = load("src/vision/overlay-draw.js");
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
// 点击通道：auto（有 Shizuku 就走 shizuku 的 `input swipe` 原地按住）/ shizuku / accessibility。
// K40 + 天天象棋实测：超短按（`input tap`）会被游戏直接丢弃，按住 ≥300ms 才选中棋子。
let clickMethodKey = "auto";
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

/** 状态栏文字（心跳里每秒刷一次，写成一个函数免得两处不同步）。 */
function statusLine() {
    // 面板宽度有限：太长会在窄行里换行 → 第二行被裁。9sp 下约 24.8px/字，1008px 只放得下 ~40 字。
    const note = calibrationNote.length > 20 ? (calibrationNote.slice(0, 19) + "…") : calibrationNote;
    const fg = lastForeground == null
        ? "?"
        : (lastForeground === gamePackage ? "游戏✓" : (lastForeground === OWN_PACKAGE ? "自身✗" : lastForeground));
    return note + "｜" + (running ? "运行中" : "已停止") + "｜" + aliveSeconds + "s｜前台 " + fg;
}
let collapsed = false;
let grid = null;          // 已校准几何（会话内复用）
let calibrationNote = "未校准";
const logLines = [];
/** 屏上只显示最后几行（面板得矮到能塞进棋盘上方的空木纹带）；完整日志走 logcat（前缀 [floaty]） */
const LOG_LINES_COMPACT = 2;    // 实测行高 ≈ 1.35×字号：8sp ≈ 30px/行 → 2 行 60px，日志区 76px

/**
 * 进度摘要：「现在跑到哪一步」——从日志里抽关键节点，每项只留**最近一次**。
 * 为什么要它：完整日志刷得快、又夹着心跳噪声，盯面板看不出「卡在哪」。
 */
/** 状态机状态 → 中文（给人看的面板上别丢英文枚举）。 */
const STATE_LABEL = {
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
};
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
    const line = String(text);
    noteProgress(line);
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

const DRAW_LABEL = { off: "关", grid: "网格", cells: "格子", full: "全量" };
const DRAW_ORDER = ["off", "grid", "cells", "full"];

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
        if (hidden) {
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
        tapFunction = adapter.createTap({ pressMs: clickPressMs, method: clickMethodKey }).tap;
        logLine("点击通道：" + clickMethodKey + "，按压 " + clickPressMs + "ms（无障碍=" +
            (adapter.capabilities().accessibilityReady ? "就绪" : "未就绪") + "，Shizuku=" +
            (adapter.capabilities().shizuku ? "可用" : "不可用") + "）");
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
        if (auto.mode && MODES.indexOf(auto.mode) >= 0) {
            modeKey = auto.mode;
            logLine("模式设为 " + MODE_LABEL[modeKey]);
        }
        if (auto.autoAlign != null) {
            autoAlignOn = !!auto.autoAlign;
            logLine("几何自动微调：" + (autoAlignOn ? "开" : "关"));
        }
        if (auto.gamePackage) {
            // 目标游戏包名（前台守卫要用它判断「焦点在不在游戏上」；默认腾讯天天象棋）
            const pkg = String(auto.gamePackage).trim();
            if (pkg) {
                gamePackage = pkg;
                logLine("目标游戏包名：" + gamePackage);
            }
        }
        if (auto.clickMethod) {
            // "auto"（默认）/ "shizuku"（`input tap`）/ "accessibility"（无障碍 press）。
            // 游戏会无视 shell 身份注入的触摸时，这里填 accessibility。
            const key = String(auto.clickMethod);
            clickMethodKey = (key === "shizuku" || key === "accessibility") ? key : "auto";
            logLine("点击通道偏好：" + clickMethodKey);
        }
        if (auto.clickPressMs != null) {
            // 按压时长（ms）：有些游戏会忽略过短的注入点击（K40 + 天天象棋需 ≥300ms）
            const ms = Math.round(Number(auto.clickPressMs));
            if (isFinite(ms) && ms > 0) {
                clickPressMs = Math.min(2000, Math.max(1, ms));
                logLine("点击按压时长：" + clickPressMs + "ms");
            }
        }
        if (auto.computeMode) {
            // "worker"（默认，独立引擎线程）/ "inline"（本线程同步，对照老路径）
            computeModeKey = String(auto.computeMode) === "inline" ? "inline" : "worker";
            logLine("算棋模式：" + computeModeKey);
        }
        if (auto.captureBackend) {
            // "auto"（默认，先系统截屏）/ "screen" / "shell"（Shizuku/root 跑 screencap）：
            // shell 通道不需要投屏授权（小米8 上实测又快一倍且颜色正确），适合无人值守跑。
            // persist:false —— 这是测试钩子，别把偏好写进 storages（界面上选"的才算主人选择）。
            const key = adapter.setCaptureBackend(auto.captureBackend, { persist: false });
            logLine("截图通道偏好：" + key + "（最近一次实际用过：" + (adapter.captureChannel() || "未用过") + "）");
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
