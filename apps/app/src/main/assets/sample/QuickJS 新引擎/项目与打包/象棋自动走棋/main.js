// @engine quickjs
"ui";

/**
 * 象棋自动走棋 · 工程 v0.6.0
 *
 * 现状：FEN / 棋盘几何 / 格线自动定位 / 格点分配 / 时序融合 / 识别链路（模型随包）/ 棋规 /
 * 本机算棋 / **自动执行（票据 + 点击 + 落子确认）** 都是真代码。
 *
 * 三个模式（任务书 13.2）：
 *   ① 只识别  —— 只显示局面，点击接口是**显式禁用**的（调用即计数报错）
 *   ② 着法建议 —— 算棋并显示起终点，仍然不点击
 *   ③ 自动走棋 —— 轮次/局面/几何/票据/着法全部校验通过后真的点击，并验证落子
 *
 * 开发规格与分阶段要求见本工程 README.md。
 *
 * ⚠️ 工程内的**模块路径**：入口脚本自己的 cwd 由宿主保证（= 工程目录），但
 * `engines.execScriptFile()` 拉起的脚本 cwd 可能是 "/"，所以子脚本请用 `__dirname` 拼绝对路径
 * （见「悬浮窗运行与测试.js」里的 load()）。
 */

const VERSION = "0.6.0";

const STEPS = [
  "P0  运行时探针：截图 / 模型 / 线程 / 打包能力核对",
  "P0-A 平台能力补齐：模型契约、帧来源、UCI 桥接、原生打包",
  "P1  离线识别：棋盘定位 + 格点校准 + FEN（已可用，模型随包）",
  "P2  实时观察：截图时序 + 轮次推断 + 遮挡恢复（已接入自动模式）",
  "P3  本机算棋：本地引擎握手与着法建议（已可用，./src/engine）",
  "P4  自动执行：票据 + 点击 + 落子确认（已可用，./src/automation）",
  "P5  真机验收：打包独立 APK 后回归"
];

ui.layout(
  '<vertical padding="18">' +
  '  <text text="象棋自动走棋" textSize="24sp" textStyle="bold"/>' +
  '  <text id="version" textSize="13sp" textColor="#888888"/>' +
  '  <text id="status" textSize="14sp" textColor="#d32f2f"/>' +
  // 悬浮窗面板放最上面、不进滚动区：这是「在真实棋局上操作与验收」的入口，要一眼看得到
  '  <frame height="12"/>' +
  '  <text text="悬浮窗面板（浮在棋局上操作/验收）" textSize="16sp" textStyle="bold"/>' +
  '  <button id="floatyOpen" text="打开悬浮窗面板（运行与测试）"/>' +
  '  <button id="floatyClose" text="关闭悬浮窗面板"/>' +
  '  <text id="floatyStatus" textSize="12sp" textColor="#0d47a1"/>' +
  '  <frame height="6"/>' +
  '  <scroll>' +
  '    <vertical>' +
  '      <frame height="12"/>' +
  '      <text text="走棋模式（当前模式决定会不会点棋盘）" textSize="16sp" textStyle="bold"/>' +
  '      <button id="modeWatch" text="① 只识别：只看局面，不点击"/>' +
  '      <button id="modeAdvice" text="② 着法建议：算棋并显示，不点击"/>' +
  '      <button id="modeAuto" text="③ 自动走棋：校验通过后真的点击"/>' +
  '      <button id="mySide" text="我方：红方（点一下切换）"/>' +
  '      <button id="tapMethod" text="点击通道：自动（有 Shizuku 就用它）"/>' +
  '      <button id="captureChannel" text="截图通道：自动"/>' +
  '      <button id="assertTurn" text="现在轮到我（声明轮次基准）"/>' +
  '      <button id="autoToggle" text="开始观察 / 暂停"/>' +
  '      <button id="autoStop" text="停止（关画板）"/>' +
  '      <text id="autoStatus" textSize="12sp" textColor="#0d47a1"/>' +
  '      <frame height="14"/>' +
  '      <text text="开发阶段" textSize="16sp" textStyle="bold"/>' +
  '      <text id="steps" textSize="13sp" textColor="#555555"/>' +
  '      <frame height="10"/>' +
  '      <text text="单次操作" textSize="16sp" textStyle="bold"/>' +
  '      <button id="selfcheck" text="运行离线自检"/>' +
  '      <button id="probe" text="查看运行环境"/>' +
  '      <button id="locate" text="定位棋盘（真机）"/>' +
  '      <text text="棋盘截图路径（相册里的图就能用）" textSize="13sp" textColor="#555555"/>' +
  '      <input id="image" text="/sdcard/Download/chess-shot-01.png" textSize="13sp"/>' +
  '      <button id="locateImage" text="定位棋盘（图片）"/>' +
  '      <button id="recognizeImage" text="识别棋盘 → FEN（图片）"/>' +
  '      <button id="recognizeSeq" text="连拍识别 ×3 → FEN（多帧投票）"/>' +
  '      <text text="算棋局面（FEN，含轮次；识别成功会自动填进来）" textSize="13sp" textColor="#555555"/>' +
  '      <input id="fen" text="rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1" textSize="12sp"/>' +
  '      <text text="棋盘范围（比例 x,y,w,h；留空用默认。定位总失败时填，例如 0.013,0.251,0.958,0.472）" textSize="13sp" textColor="#555555"/>' +
  '      <input id="region" text="" textSize="13sp"/>' +
  '      <button id="think" text="算棋（本地引擎）→ 最佳着法"/>' +
  '      <frame height="10"/>' +
  '      <text id="output" textSize="12sp" textColor="#333333"/>' +
  '    </vertical>' +
  '  </scroll>' +
  '</vertical>'
);

ui.version.setText("工程版本 v" + VERSION);
ui.status.setText("等开始。自动走棋会真的点击棋盘，先读一遍模式说明。");
ui.steps.setText(STEPS.join("\n"));

/**
 * 识别成功后把局面填进算棋用的 FEN 框。
 * 有未知格就**不填**：带着 '?' 的局面算棋没意义（引擎也会拒绝），
 * 这里明确告知“先把未知格解决掉”，而不是默认拿一个凑合的局面去算。
 * 轮次默认给红先（截图上看不出轮到谁，后续由 transition.infer 推断）。
 */
function offerFen(chars, unknownCells, side) {
    if (!chars || chars.length !== 90) {
        return "";
    }
    if (unknownCells > 0) {
        return "\n\n（有 " + unknownCells + " 个未知格，没填进算棋框；先让多帧投票把它们定下来）";
    }
    try {
        const fenText = require("./src/chess/fen.js").buildFen(chars, side || "w");
        ui.fen.setText(fenText);
        return "\n\n（已填进算棋框：" + fenText + "）";
    } catch (error) {
        return "\n\n（识别结果没能转成 FEN：" + error + "）";
    }
}

let lastOutput = "";

function show(text) {
    lastOutput = text;
    ui.output.setText(text);
    console.log("[xiangqi] " + text);
}

/** 读输入框内容（不同引擎下 text()/getText() 的可用性不一样，都试一下）。 */
function inputText(view) {
    try {
        return String(view.text());
    } catch (error) {
        try {
            return String(view.getText());
        } catch (inner) {
            return "";
        }
    }
}

/**
 * 悬浮窗面板：独立的脚本（浮窗 + 在真实棋局上跑定位/识别/算棋/自检/契约测试）。
 * 它自己 require("./src/...")，所以**必须和 main.js 同目录启动** —— 引擎会把 cwd 设成脚本所在目录，
 * 下面这个路径就是同目录下的面板文件。
 */
const PANEL_FILE = "悬浮窗运行与测试.js";
let panelExecution = null;

function panelPath() {
    try {
        return files.join(files.cwd(), PANEL_FILE);
    } catch (error) {
        return "/sdcard/脚本/下载资源/象棋自动走棋/" + PANEL_FILE;
    }
}

/**
 * 找正在运行的面板引擎。
 * 为什么要扫 engines.all() 而不是只记一个句柄：面板是**独立引擎**，连点两次「打开」
 * 就会开出两个面板（真机实测出现过 2 个浮窗窗口，互相盖住），而「关闭」只关得住最后一次拉起的那个。
 */
function runningPanels() {
    const found = [];
    try {
        const all = engines.all() || [];
        for (let i = 0; i < all.length; i++) {
            const engine = all[i];
            let source = "";
            try {
                source = String(engine.getSource ? engine.getSource() : (engine.source || ""));
            } catch (error) {
                source = "";
            }
            if (source.indexOf(PANEL_FILE) >= 0) {
                found.push(engine);
            }
        }
    } catch (error) {
        // 拿不到引擎列表就退化成「只看自己记的那个句柄」
    }
    return found;
}

function setPanelStatus(text) {
    try {
        ui.floatyStatus.setText(text);
    } catch (error) {
        // 界面上没有这个控件也不影响功能
    }
}

ui.floatyOpen.on("click", () => {
    const path = panelPath();
    if (!files.exists(path)) {
        setPanelStatus("面板脚本不在工程里：\n" + path +
            "\n\n若是旧副本（应用升级过），去「资源页 → 象棋自动走棋 → 同步内置版本」再试。");
        return;
    }
    const alive = runningPanels();
    if (alive.length > 0) {
        setPanelStatus("面板已经在运行（" + alive.length + " 个）—— 不重复打开。\n" +
            "面板默认浮在屏幕下半部、抓帧时会自己临时隐藏；要收起来点面板里的「关闭面板」。");
        return;
    }
    try {
        // 显式给面板指定工作目录（cwd），否则新引擎的 cwd 可能是 "/"，面板里的相对 require 会失败。
        // 面板自己也用 __dirname 拼路径（双保险）。
        panelExecution = engines.execScriptFile(path, { path: files.cwd() });
        setPanelStatus("面板已启动（独立脚本引擎，浮在屏幕下半部）。\n" +
            "抓帧时面板会自己临时隐藏，不会挡住棋盘；面板里「离线自检」「契约测试」都可直接点。");
        show("已启动悬浮窗面板：" + path);
    } catch (error) {
        setPanelStatus("启动面板失败：" + error + "（面板需要悬浮窗权限）");
    }
});

ui.floatyClose.on("click", () => {
    const alive = runningPanels();
    if (alive.length === 0 && !panelExecution) {
        setPanelStatus("面板没在运行。");
        return;
    }
    let stopped = 0;
    for (let i = 0; i < alive.length; i++) {
        try {
            alive[i].forceStop();
            stopped += 1;
        } catch (error) {
            setPanelStatus("有关不掉的面板：" + error);
        }
    }
    if (stopped === 0 && panelExecution) {
        try {
            panelExecution.getEngine().forceStop();
            stopped += 1;
        } catch (error) {
            setPanelStatus("关不掉（可到最近任务里划掉本应用）：" + error);
            return;
        }
    }
    panelExecution = null;
    setPanelStatus("已关闭 " + stopped + " 个悬浮窗面板。");
});

// 离线自检：只跑纯 JS 模块（FEN / 几何 / 格点分配 / 时序融合），不截图、不点棋盘。
ui.selfcheck.on("click", () => {
    try {
        const result = require("./tools/self-check.js");
        const lines = [result.summary];
        for (let i = 0; i < result.failures.length; i++) {
            lines.push((i + 1) + ". " + result.failures[i]);
        }
        show(lines.join("\n"));
    } catch (error) {
        show("自检没能运行：" + error);
    }
});

// 运行时探针：核对平台能力（会截一张图看帧字段），不点击棋盘。
ui.probe.on("click", () => {
    try {
        const report = require("./tools/runtime-probe.js");
        const lines = [];
        lines.push("引擎：" + report.platform.engine + "  Android：" + report.platform.android);
        lines.push("机型：" + report.platform.model + "（" + report.platform.abi + "）");
        lines.push("屏幕：" + report.platform.screenWidth + " × " + report.platform.screenHeight);
        lines.push("截图授权：" + report.frames.granted);
        if (report.frames.rawFirst) {
            lines.push("首帧体检（不重试）：纯黑=" + report.frames.rawFirst.blank +
                "（true = 碰到了「刚授权第一帧是黑的」那个平台行为）");
        }
        if (report.frames.full) {
            lines.push("实时帧：" + report.frames.full.width + "×" + report.frames.full.height +
                "  像素 " + report.frames.full.pixelWidth + "×" + report.frames.full.pixelHeight +
                (report.frames.full.blank ? "  ⚠ 整幅纯黑" : "  非黑"));
        }
        if (report.frames.fullTop && report.frames.fullTop.length) {
            lines.push("实时帧顶部取样：" + report.frames.fullTop.join("  "));
        }
        if (report.frames.fastTop && report.frames.fastTop.length) {
            lines.push("快截图顶部取样：" + report.frames.fastTop.join("  "));
        }
        if (report.frames.fast) {
            lines.push("快截图像素：" + report.frames.fast.pixelWidth + "×" + report.frames.fast.pixelHeight);
        }
        lines.push("yolo.load：" + report.capabilities.yoloLoad + "  线程：" + report.threads.start);
        lines.push("模型：" + report.model.modelPath);
        lines.push("模型加载：" + report.model.loaded + "（" + (report.model.onDisk ? "磁盘" : "随包 asset") + "）");
        if (report.model.scriptDir) {
            lines.push("脚本目录：" + report.model.scriptDir);
        }
        if (report.missingRequired.length > 0) {
            lines.push("缺关键能力：" + report.missingRequired.join("、"));
        }
        if (report.errors.length > 0) {
            lines.push("探针告警：" + report.errors.join("；"));
        }
        show(lines.join("\n"));
    } catch (error) {
        show("探针没能运行：" + error);
    }
});

// 棋盘定位（真机）：截一帧，在默认范围内自动找格线。只截图，不点击棋盘。
ui.locate.on("click", () => {
    try {
        const board = require("./tools/locate-board.js");
        const keep = lastOutput;
        show("正在定位棋盘…");
        const result = board.locateBoardOnScreen();
        show(board.describeResult(result) + (keep ? "\n\n—— 上一次输出 ——\n" + keep : ""));
    } catch (error) {
        show("定位没能运行：" + error);
    }
});

// 识别（图片）：定位 → 按棋盘外框裁剪 → 160 模型检测 → 落格 → 格心判红黑 → FEN。
ui.recognizeImage.on("click", () => {
    try {
        const board = require("./tools/recognize-board.js");
        const path = inputText(ui.image).trim();
        if (!path) {
            show("请先填图片路径");
            return;
        }
        const keep = lastOutput;
        show("正在识别…（定位 + 检测 + 判色）");
        const result = board.recognizeImage(path);
        show(board.describeResult(result) + offerFen(result.chars, result.unknownCells, "w") +
            (keep ? "\n\n—— 上一次输出 ——\n" + keep : ""));
    } catch (error) {
        show("识别没能运行：" + error);
    }
});

// 连拍识别：同一局面多帧投票，洗掉单帧的错子（P2）。
// 图片路径可以逗号分隔多张；只填一张时会把这张当作 3 帧（用来验证链路接通）。
ui.recognizeSeq.on("click", () => {
    try {
        const board = require("./tools/recognize-board.js");
        const raw = inputText(ui.image).trim();
        if (!raw) {
            show("请先填图片路径");
            return;
        }
        const paths = raw.split(",").map(function (item) { return item.trim(); })
            .filter(function (item) { return item.length > 0; });
        while (paths.length > 0 && paths.length < 3) {
            paths.push(paths[0]);
        }
        const keep = lastOutput;
        show("正在连拍识别（" + paths.length + " 帧）…");
        const result = board.recognizeImageSequence(paths);
        const fusedChars = result.fusion && result.fusion.chars ? result.fusion.chars : null;
        const fusedUnknown = result.fusion && result.fusion.stats ? result.fusion.stats.unknown : 0;
        show(board.describeSequence(result) + offerFen(fusedChars, fusedUnknown, "w") +
            (keep ? "\n\n—— 上一次输出 ——\n" + keep : ""));
    } catch (error) {
        show("连拍识别没能运行：" + error);
    }
});

// 棋盘定位（图片）：在已保存的截图上定位。真机自测推荐用这个（不必把棋盘摆到屏幕上）。
ui.locateImage.on("click", () => {
    try {
        const board = require("./tools/locate-board.js");
        const path = inputText(ui.image).trim();
        if (!path) {
            show("请先填图片路径");
            return;
        }
        const keep = lastOutput;
        show("正在读图并定位…");
        const result = board.locateBoardInImage(path);
        show(board.describeResult(result) + (keep ? "\n\n—— 上一次输出 ——\n" + keep : ""));
    } catch (error) {
        show("定位没能运行：" + error);
    }
});

// 算棋（P3）：本地引擎（alpha-beta + 迭代加深 + 置换表 + 静态搜索），只算不点。
// 局面来自上面的 FEN 框：识别成功会自动填进来；也能自己粘一个进来试。
ui.think.on("click", () => {
    try {
        const engine = require("./src/engine/engine.js");
        const text = inputText(ui.fen).trim();
        if (!text) {
            show("请先填算棋用的 FEN（或先识别一次，会自动填进来）");
            return;
        }
        const keep = lastOutput;
        show("正在算棋…（本地引擎，顶多 1.5 秒）");
        const result = engine.analyze(text, { movetime: 1500 });
        show(engine.describe(result) + (keep ? "\n\n—— 上一次输出 ——\n" + keep : ""));
    } catch (error) {
        show("算棋没能运行：" + error);
    }
});

// ---------------------------------------------------------------- 自动走棋（P4）
//
// 三个模式的区别只有一件事：**允不允许点击**。
//   ① 只识别 / ② 着法建议：状态机配 allowClick=false，点击执行器换成显式禁用的桩
//      （调用会被计数并报错，界面上能看到「拦截 N 次」）——不点击是靠禁用保证的，
//      不是靠“记得别调”。
//   ③ 自动走棋：真的点。但必须同时满足轮次、局面、几何、票据、着法合法性，
//      而且确认阶段要用**新观测**看到落子才算成功（点击函数返回成功 ≠ 落子成功）。

const adapter = require("./src/runtime/aijs-adapter.js");
const geometryModule = require("./src/vision/geometry.js");
const driverModule = require("./src/automation/auto-driver.js");
const recognizeBoard = require("./tools/recognize-board.js");
const locateBoard = require("./tools/locate-board.js");
const engineModule = require("./src/engine/engine.js");

const MODES = { WATCH: "watch", ADVICE: "advice", AUTO: "auto" };
const MODE_LABEL = { watch: "① 只识别", advice: "② 着法建议", auto: "③ 自动走棋" };

let modeKey = MODES.WATCH;
let tapMethodKey = "auto";
let autoDriver = null;
let autoRunning = false;
let overlayShown = false;
let lastDriverLine = "";

function sideLabel(side) {
    return side === "b" ? "黑方" : "红方";
}

const TAP_METHODS = ["auto", "accessibility", "shizuku"];
const TAP_LABEL = {
    auto: "自动（有 Shizuku 就用它）",
    accessibility: "无障碍手势",
    shizuku: "Shizuku 注入 input tap"
};

function describeTapChannel() {
    const caps = adapter.capabilities();
    return TAP_LABEL[tapMethodKey] + "｜无障碍=" + (caps.accessibilityReady ? "就绪" : "未就绪") +
        "，Shizuku=" + (caps.shizuku ? "可用" : "不可用");
}

const CAPTURE_BACKENDS = ["auto", "screen", "shell"];
const CAPTURE_LABEL = {
    auto: "自动（系统截屏失败就改走 screencap）",
    screen: "只用系统截屏（MediaProjection）",
    shell: "只用 screencap（Shizuku/root）"
};

/** 截图通道现状：偏好 + 最近一次真正用上的通道 + 能用什么。 */
function describeCaptureChannel() {
    const caps = adapter.capabilities();
    return "偏好：" + CAPTURE_LABEL[adapter.captureBackend()] +
        "｜最近实际用的：" + (adapter.captureChannel() || "还没截图") +
        "｜shell 通道=" + (caps.captureShell ? ("可用（" + caps.captureShellBackend + "）") : "不可用");
}

/** 读棋盘范围输入框（比例 x,y,w,h）；空的就返回 null（用默认比例）。 */
function regionRatioFromUi() {
    const raw = inputText(ui.region).trim();
    if (!raw) {
        return null;
    }
    const parts = raw.split(/[,\s]+/).map(function (item) { return Number(item); });
    if (parts.length !== 4 || parts.some(function (value) { return !isFinite(value) || value < 0; })) {
        show("棋盘范围格式不对：应形如 0.013,0.251,0.958,0.472（比例）");
        return null;
    }
    return { x: parts[0], y: parts[1], width: parts[2], height: parts[3] };
}

/** 换模式（或换我方颜色后重建）会重置驱动：会话、几何与轮次基准全部清空。 */
function rebuildDriver() {
    stopAuto("重建驱动");
    const clickAllowed = modeKey === MODES.AUTO;
    let tapFunction = null;
    let tapNote = "（不点击）";
    if (clickAllowed) {
        if (!adapter.capabilities().tap && !adapter.capabilities().shizuku) {
            tapNote = "（这台设备没有 press/click，也没有 Shizuku，出手会一直被拒绕）";
        } else {
            tapFunction = adapter.createTap({ pressMs: 60, method: tapMethodKey }).tap;
            tapNote = "（会点击：“" + describeTapChannel() + "）";
        }
    }
    const keepSide = autoDriver ? autoDriver.mySide() : "w";
    autoDriver = driverModule.createDriver({
        allowClick: clickAllowed,
        mySide: keepSide,
        tap: tapFunction,
        recognize: function (options) { return recognizeBoard.recognizeScreen(options); },
        /**
         * 定位 + **会话级几何校准**。
         *
         * 为什么必须有：定位给的原点有系统性偏差时，会有几格**每一帧都认不出来**（多帧投票也洗不掉），
         * 状态机就会一直拒绝计算/落子。真机实测（K40 + 腾讯天天象棋）：
         * 原点 (81,666) → 未知格 6；挪到 (70,662)（dx=-11,dy=-4）→ 未知格 1。
         * 校准按**最终未知格数**打分（不是几何残差），因为残差最小的偏移并没能减少未知格。
         * 代价：十几次推理、约 2 秒，而定位本身一局只做一次，所以可以承受。
         */
        locate: function (options) {
            const located = locateBoard.locateBoardOnScreen(options);
            if (!located.ok) {
                return located;
            }
            try {
                // ⚠️ locateBoardOnScreen 给的是 frameObject（没有 imageObject）——
                // 只传 imageObject 会让校准静默失败（历史 bug，已修库里的兼容）。
                const avatar = located.imageObject || located.frameObject;
                const calibrated = recognizeBoard.calibrateGrid(avatar, located, {
                    orientation: "RED_BOTTOM",
                    confidence: 0.25,
                    search: { maxShift: 18, step: 7 },
                    maxCandidates: 16
                });
                if (calibrated.ok && calibrated.grid) {
                    console.log("[xiangqi][calibrate] 几何校准：" + calibrated.reason +
                        "（偏移 " + calibrated.dx + "," + calibrated.dy + "，试了 " + calibrated.tried +
                        " 个候选）" + (calibrated.layout ? (" 局面 " + calibrated.layout) : ""));
                    return Object.assign({}, located, {
                        grid: calibrated.grid,
                        calibration: {
                            dx: calibrated.dx, dy: calibrated.dy,
                            unknown: calibrated.unknown, tried: calibrated.tried
                        }
                    });
                }
            } catch (error) {
                console.log("[xiangqi][calibrate] 校准异常（沿用定位结果）：" +
                    String(error && error.message ? error.message : error));
            }
            return located;
        },
        analyze: function (position, options) { return engineModule.analyze(position, options); },
        isTrustworthy: function (grid) {
            return geometryModule.isTrustworthy(grid, { minSamples: 4, maxResidual: 0.35 });
        },
        screen: function () { return adapter.screenSize(); },
        log: function (text) { console.log(text); },
        config: {
            tickMs: 900,
            movetimeMs: 1200,
            modelVersion: "chessai_160",
            confidence: 0.25,
            orientation: "RED_BOTTOM",
            regionRatio: regionRatioFromUi()
        },
        executorConfig: { pressMs: 60, settleMs: 140 }
    });
    ui.mySide.setText("我方：" + sideLabel(keepSide) + "（点一下切换）");
    ui.autoStatus.setText("模式：" + MODE_LABEL[modeKey] + tapNote + "，我方：" + sideLabel(keepSide) + "，未开始");
    ui.status.setText(MODE_LABEL[modeKey] + tapNote);
    lastDriverLine = "";
    return autoDriver;
}

function driverTick() {
    if (!autoDriver) {
        return;
    }
    try {
        updateAutoStatus(autoDriver.tick());
    } catch (error) {
        show("自动走棋出错：" + error);
        stopAuto("出错");
    }
}

function updateAutoStatus(result) {
    const driver = autoDriver;
    if (!driver) {
        return;
    }
    const machine = driver.machine;
    const stats = driver.stats();
    const counters = machine.counters();
    const ticket = machine.currentTicket();
    const lines = [];
    lines.push("模式：" + MODE_LABEL[modeKey] + "，我方：" + sideLabel(driver.mySide()) +
        "，状态：" + machine.state() + "，几何：" + (driver.grid() ? ("v" + driver.grid().version) : "无"));
    if (result && result.ok) {
        lines.push("观察：未知格 " + result.unknownCells +
            "，轮次：" + (result.side ? sideLabel(result.side) : "未知") +
            "，本轮 " + stats.lastObserveMs + "ms");
    } else if (result) {
        lines.push("本轮没看清：" + result.reason);
    }
    lines.push(machine.reason());
    if (ticket) {
        lines.push("计划：" + ticket.moveICCS + "（深度 " + (ticket.depth == null ? "?" : ticket.depth) +
            "，评分 " + (ticket.score == null ? "?" : Math.round(ticket.score)) + "，" +
            ticket.executionState + "）");
    }
    lines.push("点击 " + stats.clicksByExecutor + " 次，确认 " + counters.confirms +
        " 次，重试 " + counters.retries + "，看门狗 " + counters.watchdogTrips +
        (driver.clickAllowed ? "" : ("，已拦截点击 " + stats.disabledTapCalls + " 次")));
    const text = lines.join("\n");
    if (text !== lastDriverLine) {
        lastDriverLine = text;
        ui.autoStatus.setText(text);
    }
    ui.status.setText(machine.state() + "：" + machine.reason());
    drawPlan(ticket);
}

/** 把「计划」画到悬浮层：没有悬浮窗权限就不画，不影响走棋。 */
function drawPlan(ticket) {
    const driver = autoDriver;
    if (!driver || !adapter.overlayAvailable()) {
        return;
    }
    if (!overlayShown) {
        overlayShown = adapter.overlayShow();
        if (!overlayShown) {
            return;
        }
    }
    const items = [];
    const grid = driver.grid();
    if (ticket && ticket.move && grid && grid.version === ticket.geometryVersion) {
        const from = geometryModule.pointAt(grid, ticket.move.fromCol, ticket.move.fromRow);
        const to = geometryModule.pointAt(grid, ticket.move.toCol, ticket.move.toRow);
        items.push({ x1: from.x - 28, y1: from.y - 28, x2: from.x + 28, y2: from.y + 28, label: "起点", score: 0 });
        items.push({ x1: to.x - 28, y1: to.y - 28, x2: to.x + 28, y2: to.y + 28, label: ticket.moveICCS, score: 0 });
    }
    adapter.overlayUpdate(items, "象棋 " + MODE_LABEL[modeKey] + "｜" + driver.machine.state() +
        (ticket ? ("｜计划 " + ticket.moveICCS) : "") +
        (driver.clickAllowed ? "" : "｜不点击"));
}

/**
 * 自动走棋主循环（阻塞在脚本主线程上）。
 *
 * ⚠️ 为什么不用 setInterval：真机实测（K40）本应用切到后台后**定时器不再被派发**，
 * 自动走棋会停在最后一帧；改用本仓库验证过的「sleep 分片循环」（YOLO 持续识别 同写法）。
 *
 * 退出条件（任意一条）：
 *   1) 驱动自己不在运行了（暂停/停止/出错）；
 *   2) 用户切走又**回到本页面**：说明要看界面了，交回控制权（否则按钮按不动）；
 *   3) 跑满 maxTicks（保险，防止没人看着时无限跑）。
 * 脚本被任务列表停掉时 sleep 会被中断，循环随脚本一起结束。
 */
function runAutoLoop() {
    if (autoRunning || !autoDriver) {
        return;
    }
    const ownPackage = String(currentPackage());
    const tickMs = autoDriver.config.tickMs ? autoDriver.config.tickMs : 900;
    const maxTicks = 600;
    let ticks = 0;
    let leftOwnApp = false;
    autoRunning = true;
    const caps = adapter.capabilities();
    show("开始前能力核对：截图=" + caps.requestScreenCapture + "，点击接口=" + (caps.tap ? "有" : "无") +
        "，无障碍=" + (caps.accessibilityReady ? "就绪" : "**未就绪：自动走棋点不下去**（去抽屉打开「无障碍服务」）") +
        "，悬浮画板=" + (caps.drawing ? "可用" : "无"));
    show("自动观测已开始" + (ownPackage ? "（前台：" + ownPackage + "）" : "") +
        "。请切到棋局画面；回到本页面会自动交回界面控制。");
    try {
        while (autoDriver && autoDriver.isRunning() && ticks < maxTicks) {
            if (stopRequested()) {
                show("检测到停止标记（" + stopFile() + "），退出自动循环。");
                break;
            }
            driverTick();
            ticks += 1;
            if (String(currentPackage()) !== ownPackage) {
                leftOwnApp = true;
            } else if (leftOwnApp) {
                show("检测到回到本应用，自动观测先停在这里（按钮重新可用）。");
                break;
            }
            sleep(tickMs);
        }
    } catch (error) {
        show("自动循环结束：" + error);
    } finally {
        autoRunning = false;
    }
}

/** 外部停止标记：真机测试 / 无人看着时用 `touch /sdcard/xiangqi-p4/STOP.flag` 就能停。 */
function stopFile() {
    try {
        return files.join(files.getSdcardPath(), "xiangqi-p4", "STOP.flag");
    } catch (error) {
        return "";
    }
}

function stopRequested() {
    try {
        const path = stopFile();
        return path !== "" && files.exists(path);
    } catch (error) {
        return false;
    }
}

function stopAuto(why) {
    if (autoDriver) {
        autoDriver.stop();
    }
    autoRunning = false;
    if (overlayShown) {
        adapter.overlayHide();
        overlayShown = false;
    }
    if (autoDriver) {
        ui.status.setText("已停止（" + why + "）");
    }
}

ui.modeWatch.on("click", function () {
    modeKey = MODES.WATCH;
    rebuildDriver();
    show("模式：① 只识别 —— 只看局面、算棋建议，点击接口是禁用桩（调用会被计数）。");
});

ui.modeAdvice.on("click", function () {
    modeKey = MODES.ADVICE;
    rebuildDriver();
    show("模式：② 着法建议 —— 会算棋并显示起点/终点，仍然不会点击棋盘。");
});

ui.modeAuto.on("click", function () {
    modeKey = MODES.AUTO;
    rebuildDriver();
    show("模式：③ 自动走棋 —— 点「开始观察 / 暂停」后，只有轮次、局面、几何、票据、着法" +
        "全部校验通过才会点击；落子要用新观测确认过才算成功。\n" +
        "第一次开始会弹截图授权；轮次推不出来时按「现在轮到我」声明一次。");
});

ui.tapMethod.on("click", function () {
    const index = TAP_METHODS.indexOf(tapMethodKey);
    tapMethodKey = TAP_METHODS[(index + 1) % TAP_METHODS.length];
    ui.tapMethod.setText("点击通道：" + TAP_LABEL[tapMethodKey]);
    show("点击通道改为：" + describeTapChannel() + "\n" +
        "两条通道都能落子（实测）：「无障碍」不需要装 Shizuku 但要开无障碍服务；" +
        "「Shizuku」用 shell 身份注入 input tap，不受游戏过滤影响。\n" +
        "越关键是：注入时**游戏必须真的在前台** —— 我们自己的窗口盖在上面时，\n" +
        "触摸会被我们自己吃掉（看起来像「返回 true 但没反应」）。");
    rebuildDriver();
});

ui.captureChannel.on("click", function () {
    const index = CAPTURE_BACKENDS.indexOf(adapter.captureBackend());
    const next = CAPTURE_BACKENDS[(index + 1) % CAPTURE_BACKENDS.length];
    adapter.setCaptureBackend(next);
    ui.captureChannel.setText("截图通道：" + (next === "auto" ? "自动" : (next === "screen" ? "系统截屏" : "screencap")));
    show("截图通道改为：" + describeCaptureChannel() + "\n" +
        "说明：系统截屏（MediaProjection）很快，但 MIUI 会在切前后台/前台服务变化时把会话移除，\n" +
        "之后每一帧都超时（实测连续 31s 全失败）。screencap 走 Shizuku/root，不需要授权、不会被作废，\n" +
        "代价是慢（K40 约 1.2s/帧）。自动模式：先用系统截屏，失败自动改走 screencap。");
});

ui.mySide.on("click", function () {
    if (!autoDriver) {
        rebuildDriver();
    }
    const side = autoDriver.mySide() === "b" ? "w" : "b";
    autoDriver.setMySide(side);
    ui.mySide.setText("我方：" + sideLabel(side) + "（点一下切换）");
    show("我方改为：" + sideLabel(side) + "。轮次基准已清空，跑一轮观察后按「现在轮到我」。");
});

ui.assertTurn.on("click", function () {
    if (!autoDriver) {
        rebuildDriver();
    }
    const result = autoDriver.assertTurn();
    show(result.ok
        ? ("已把当前局面当作轮次基准：" + sideLabel(autoDriver.mySide()) + "走棋。")
        : ("还不能声明：" + result.reason));
});

ui.autoToggle.on("click", function () {
    if (!autoDriver) {
        rebuildDriver();
    }
    if (autoDriver.isRunning()) {
        autoDriver.pause();
        show("已暂停：票据与算棋任务都作废了，不会再有点击。");
        return;
    }
    if (!adapter.requestCapture("portrait")) {
        show("没有拿到截图授权，自动模式不启动。");
        return;
    }
    autoDriver.start();
    driverTick();
    runAutoLoop();   // 阻塞到这里为止（详见 runAutoLoop 的退出条件）
});

ui.autoStop.on("click", function () {
    stopAuto("用户停止");
    show("已停止：会话与轮次基准清空，画板已关。");
    ui.autoStatus.setText("未开始（模式：" + MODE_LABEL[modeKey] + "）");
    lastDriverLine = "";
});

