// @engine quickjs
"ui";

/**
 * 象棋自动走棋 · 工程 v0.6.3
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

const VERSION = require("./src/app/version.js").VERSION;

const STEPS = [
  "P0  运行时探针：截图 / 模型 / 线程 / 打包能力核对",
  "P0-A 平台能力补齐：模型契约、帧来源、UCI 桥接、原生打包",
  "P1  离线识别：棋盘定位 + 格点校准 + FEN（已可用，模型随包）",
  "P2  实时观察：截图时序 + 轮次推断 + 遮挡恢复（已接入自动模式）",
  "P3  本机算棋：本地引擎握手与着法建议（已可用，./src/engine）",
  "P4  自动执行：票据 + 点击 + 落子确认（已可用，./src/automation）",
  "P5  真机验收：打包独立 APK 后回归"
];

const UI = {
    page: "#F5F7FA", panel: "#FFFFFF", panelAlt: "#EAF0F8",
    text: "#20252E", muted: "#667080", blue: "#3486FF",
    green: "#24865C", red: "#C24B52", amber: "#B97819"
};

ui.layout(
  '<vertical bg="' + UI.page + '" padding="16">' +
  '  <horizontal gravity="center_vertical">' +
  '    <vertical layout_weight="1">' +
  '      <text text="象棋助手" textSize="24sp" textStyle="bold" textColor="' + UI.text + '"/>' +
  '      <text id="version" textSize="12sp" textColor="' + UI.muted + '"/>' +
  '    </vertical>' +
  '    <button id="floatyOpen" text="悬浮面板" bg="' + UI.blue + '" textColor="#FFFFFF"/>' +
  '  </horizontal>' +
  '  <frame height="10"/>' +
  '  <card cardCornerRadius="20dp" cardElevation="0dp" cardBackgroundColor="' + UI.panel + '">' +
  '    <vertical padding="14">' +
  '      <text id="statusIdle" text="●  未运行" textSize="18sp" textStyle="bold" textColor="' + UI.muted + '"/>' +
  '      <text id="statusGood" text="●  观察中" textSize="18sp" textStyle="bold" textColor="#24865C"/>' +
  '      <text id="statusWarn" text="●  等待处理" textSize="18sp" textStyle="bold" textColor="#B97819"/>' +
  '      <text id="statusError" text="●  需要处理" textSize="18sp" textStyle="bold" textColor="#C24B52"/>' +
  '      <text id="status" textSize="13sp" textColor="' + UI.muted + '"/>' +
  '    </vertical>' +
  '  </card>' +
  '  <frame height="10"/>' +
  '  <horizontal>' +
  '    <button id="autoToggle" text="开始" layout_weight="1" bg="' + UI.green + '" textColor="#FFFFFF"/>' +
  '    <button id="autoStop" text="停止" layout_weight="1" bg="' + UI.red + '" textColor="#FFFFFF"/>' +
  '  </horizontal>' +
  // 状态与停止固定在滚动区外，长日志或小屏幕上也始终能看见、能操作。
  '  <scroll layout_weight="1">' +
  '    <vertical>' +
  '      <frame height="12"/>' +
  '      <text text="运行模式" textSize="14sp" textStyle="bold" textColor="' + UI.text + '"/>' +
  '      <horizontal>' +
  '        <button id="modeWatch" text="只识别" layout_weight="1" bg="' + UI.panelAlt + '" textColor="' + UI.text + '"/>' +
  '        <button id="modeAdvice" text="着法建议" layout_weight="1" bg="' + UI.blue + '" textColor="#FFFFFF"/>' +
  '        <button id="modeAuto" text="自动走棋" layout_weight="1" bg="' + UI.amber + '" textColor="#FFFFFF"/>' +
  '      </horizontal>' +
  '      <text text="自动走棋会在安全检查全部通过后操作棋盘" textSize="12sp" textColor="#B97819"/>' +
  '      <frame height="10"/>' +
  '      <text text="对局设置" textSize="14sp" textStyle="bold" textColor="' + UI.text + '"/>' +
  '      <horizontal>' +
  '        <button id="mySide" text="我方：红方" layout_weight="1"/>' +
  '        <button id="assertTurn" text="现在轮到我" layout_weight="1"/>' +
  '      </horizontal>' +
  '      <button id="captureChannel" text="截图来源：自动"/>' +
  '      <button id="tapMethod" text="点击通道：自动"/>' +
  '      <text id="autoStatus" textSize="12sp" textColor="#4B83DC"/>' +
  '      <frame height="10"/>' +
  '      <text text="快捷工具" textSize="14sp" textStyle="bold" textColor="' + UI.text + '"/>' +
  '      <horizontal>' +
  '        <button id="locate" text="定位棋盘" layout_weight="1"/>' +
  '        <button id="recognizeSeq" text="识别局面" layout_weight="1"/>' +
  '      </horizontal>' +
  '      <horizontal>' +
  '        <button id="think" text="查看建议" layout_weight="1"/>' +
  '        <button id="pageDraw" text="识别绘制：开" layout_weight="1"/>' +
  '      </horizontal>' +
  '      <button id="advancedToggle" text="高级设置  ▾" bg="' + UI.panelAlt + '" textColor="' + UI.text + '"/>' +
  '      <vertical id="advancedPanel" bg="' + UI.panel + '" padding="10">' +
  '        <text text="悬浮窗" textSize="12sp" textColor="' + UI.muted + '"/>' +
  '        <button id="floatyClose" text="关闭悬浮面板"/>' +
  '        <text id="floatyStatus" textSize="12sp" textColor="#4B83DC"/>' +
  '        <text text="棋盘截图路径" textSize="12sp" textColor="' + UI.muted + '"/>' +
  '        <input id="image" text="/sdcard/Download/chess-shot-01.png" textSize="12sp"/>' +
  '        <horizontal>' +
  '          <button id="locateImage" text="定位图片" layout_weight="1"/>' +
  '          <button id="recognizeImage" text="识别图片" layout_weight="1"/>' +
  '        </horizontal>' +
  '        <text text="当前局面 FEN" textSize="12sp" textColor="' + UI.muted + '"/>' +
  '        <input id="fen" text="rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1" textSize="12sp"/>' +
  '      </vertical>' +
  '      <button id="diagnosticToggle" text="诊断工具  ▾" bg="' + UI.panelAlt + '" textColor="' + UI.text + '"/>' +
  '      <vertical id="diagnosticPanel" bg="' + UI.panel + '" padding="10">' +
  '        <horizontal>' +
  '          <button id="selfcheck" text="离线自检" layout_weight="1"/>' +
  '          <button id="contract" text="契约测试" layout_weight="1"/>' +
  '        </horizontal>' +
  '        <horizontal>' +
  '          <button id="probe" text="运行环境" layout_weight="1"/>' +
  '          <button id="newSession" text="新会话" layout_weight="1"/>' +
  '        </horizontal>' +
  '        <button id="clearLog" text="清空日志"/>' +
  '        <text text="开发阶段" textSize="12sp" textColor="' + UI.muted + '"/>' +
  '        <text id="steps" textSize="12sp" textColor="' + UI.muted + '"/>' +
  '      </vertical>' +
  '      <frame height="10"/>' +
  '      <text text="运行记录" textSize="14sp" textStyle="bold" textColor="' + UI.text + '"/>' +
  '      <scroll height="180dp" bg="#FFFFFF" padding="10">' +
  '        <text id="output" textSize="12sp" textColor="#303744"/>' +
  '      </scroll>' +
  '      <frame height="16"/>' +
  '    </vertical>' +
  '  </scroll>' +
  '</vertical>'
);

ui.version.setText("本地模型 160×320 · v" + VERSION);
ui.statusGood.setVisibility(8);
ui.statusWarn.setVisibility(8);
ui.statusError.setVisibility(8);
ui.status.setText("选择模式后点击开始；停止按钮始终可用。");
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

/** 主状态卡只呈现用户需要采取的动作；内部状态名仍保留在运行记录里。 */
function setMainStatus(state, reason) {
    const key = String(state || "STOPPED");
    let kind = "idle";
    let label = "未运行";
    if (key === "THINKING") {
        kind = "good";
        label = "思考中";
    } else if (key === "WAIT_TURN") {
        kind = "warn";
        label = "等待对方";
    } else if (key === "PAUSED") {
        kind = "warn";
        label = "已暂停";
    } else if (key === "ERROR" || key === "RECOVERING") {
        kind = "error";
        label = "需要处理";
    } else if (key === "INITIALIZING" || key === "CALIBRATING" || key === "WAIT_STABLE" ||
               key === "PRECHECK" || key === "CLICKING" || key === "VERIFYING") {
        kind = "good";
        label = key === "CLICKING" || key === "VERIFYING" ? "执行中" : "观察中";
    } else if (key === "GAME_OVER") {
        kind = "idle";
        label = "对局结束";
    }
    ui.statusIdle.setVisibility(kind === "idle" ? 0 : 8);
    ui.statusGood.setVisibility(kind === "good" ? 0 : 8);
    ui.statusWarn.setVisibility(kind === "warn" ? 0 : 8);
    ui.statusError.setVisibility(kind === "error" ? 0 : 8);
    const view = kind === "good" ? ui.statusGood : (kind === "warn" ? ui.statusWarn :
        (kind === "error" ? ui.statusError : ui.statusIdle));
    view.setText("●  " + label);
    ui.status.setText(reason || "等待操作");
}

function bindSection(toggle, panel, label) {
    let expanded = false;
    panel.setVisibility(8);
    toggle.setText(label + "  ▾");
    toggle.on("click", function () {
        expanded = !expanded;
        panel.setVisibility(expanded ? 0 : 8);
        toggle.setText(label + (expanded ? "  ▴" : "  ▾"));
    });
}

bindSection(ui.advancedToggle, ui.advancedPanel, "高级设置");
bindSection(ui.diagnosticToggle, ui.diagnosticPanel, "诊断工具");
setMainStatus("STOPPED", "选择模式后点击开始；停止按钮始终可用。");

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
        const lock = loopLock.status();
        setPanelStatus("面板已启动（独立脚本引擎，浮在屏幕下半部）。\n" +
            "抓帧时面板会自己临时隐藏，不会挡住棋盘；面板里「离线自检」「契约测试」都可直接点。\n" +
            (lock.held
                ? "注意：棋盘循环已在「" + lock.owner + "」里跑着（两边互锁，别重复开始）。"
                : "当前没有循环在跑（两边互锁：谁先开始谁持有，另一方会被拒绝）。"));
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
// A1 批次2：按钮只发命令。
ui.selfcheck.on("click", () => {
    app.controller.run("SELFTEST");
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
// A1 批次2：按钮只发命令，实现在 relocateCommand（参数/运行态由控制器校验）。
ui.locate.on("click", () => {
    app.controller.run("RELOCATE");
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
const loopLock = require("./src/automation/loop-lock.js");
const recognizeBoard = require("./tools/recognize-board.js");
const locateBoard = require("./tools/locate-board.js");
const bootstrapModule = require("./src/app/bootstrap.js");
const pipelineFactory = require("./src/vision/pipeline-factory.js");
const frameGateModule = require("./src/vision/frame-gate.js");
const boardScan = require("./src/vision/board-scan.js");
const engineModule = require("./src/engine/engine-router.js");   // 内置 JS 引擎 ⇄ Pikafish（原生 NNUE）

// 跨引擎互锁（与悬浮窗面板共用一个文件总线）：状态互锁 + 双向停止 + 配置双向同步。
// A1 批次3 归一：创建口收进 bootstrap（createBus → panel-bus 单实现），
// 装配时把**同一个实例**注入 createApp（app.bus），不再各处自己 new 一套协议对象。
const bus = bootstrapModule.createBus({
    owner: "main",
    files: files,
    log: function (text) { console.log("[bus] " + text); }
});

const MODES = { WATCH: "watch", ADVICE: "advice", AUTO: "auto" };
const MODE_LABEL = { watch: "① 只识别", advice: "② 着法建议", auto: "③ 自动走棋" };

/**
 * A2 视觉收口：主界面也接上视觉流水线（装配器在 src/vision/pipeline-factory.js，两入口共用）。
 * 懒加载：第一次识别时才建（OCR 会话 / 检测会话都不便宜）；失败自动退回旧路径（服务层有回退链）。
 */
const mainOcrHolder = pipelineFactory.createOcrHolder({
    log: function (text) { console.log("[xiangqi][ocr] " + text); }
});
const mainVisionSamples = pipelineFactory.createSamples(adapter);
const mainVisionCtl = pipelineFactory.create({
    adapter: adapter,
    recognizeBoard: recognizeBoard,
    getGrid: function () {
        return (boardService && typeof boardService.grid === "function") ? boardService.grid() : null;
    },
    getOcrSession: function () { return mainOcrHolder.get(); },
    isGrayEnabled: function () { return true; },          // 灰度优先（与面板默认一致）
    getOcrPrimary: function () { return "gray"; },
    log: function (text) { console.log("[xiangqi][vision] " + text); },
    pipelineLog: function (text) {
        // 默认不打（免得每帧刷屏）；帅/将抢救、规则补全、灰度重试这类关键动作要看得见
        if (/帅\/将抢救|规则补全|灰度重试|彩色兜底/.test(String(text))) {
            console.log("[xiangqi][vision] " + text);
        }
    }
});
// 字形识别源（「新的为主」2026-09-24）：模板在工程 assets/glyph-tpl（懒加载）；
// 服务层 mode="auto" 会先跑它，规则门/未知格不过时自动回退流水线→旧路径。
const mainGlyphSource = pipelineFactory.createGlyphSource({
    adapter: adapter,
    baseDir: (typeof files !== "undefined" && files && typeof files.cwd === "function") ? files.cwd() : ".",
    log: function (text) { console.log("[xiangqi][glyph] " + text); }
});
const visionInjection = {
    pipeline: null,
    gate: null,
    samples: function (image, g) { return mainVisionSamples(image, g); },
    countUnknown: function (chars) { return boardScan.countUnknownCells(chars); },
    log: function (text) { console.log("[xiangqi][vision] " + text); },
    ready: function () {
        if (!visionInjection.pipeline) {
            visionInjection.pipeline = mainVisionCtl.ensure();
        }
        return visionInjection.pipeline;
    }
};
visionInjection.gate = frameGateModule.create({ log: function () { } });

/**
 * 棋盘服务与会话（A1「入口收口」批次1）：装配改由 bootstrap 统一，
 * 主界面只提供平台特异项（抓帧端口 / 定位 / 校准 / 驱动附加依赖）。
 * 行为参数原样注入，旧直建路径不再使用（可回退：还原本块即可）。
 */
const app = bootstrapModule.createApp({
    owner: "main",
    bus: bus,                            // A1 批次3：装配与入口共用同一总线实例
    lockOwner: loopLock.OWNER_MAIN,      // 退出还锁的令牌（与入口 acquire 口径一致）
    adapter: adapter,
    host: {
        files: (typeof files !== "undefined" ? files : null),
        context: (typeof context !== "undefined" ? context : null),
        images: (typeof images !== "undefined" ? images : null),
        win: null,                       // 页面没有悬浮窗句柄：窗口管理退化为直通
        sleep: (typeof sleep === "function" ? sleep : null)
    },
    modules: {
        driverModule: driverModule,
        locateBoard: locateBoard,
        recognizeBoard: recognizeBoard,
        geometry: geometryModule,
        loopLock: loopLock
    },
    paths: {},                           // 页面不读 autorun 文件（配置走页面的通道）
    sink: function (text) { console.log("[xiangqi][app] " + text); },
    // 会话静态注入（识别/定位都指向服务层；驱动不再自己拼链路）
    recognize: function (options) { return app.boards.recognizeSmart(options || {}); },
    locate: function (options) { return app.boards.locate(options || {}); },
    analyze: function (position, options) { return engineModule.analyze(position, options); },
    // A1 批次2：状态性命令统一走控制器（参数/运行态校验 → handler → 记录事件）
    handlers: {
        START: function () { return startAutoCommand(); },
        PAUSE: function () { return pauseAutoCommand(); },
        STOP: function () { return stopAutoCommand(); },
        RELOCATE: function () { return relocateCommand(); },
        SELFTEST: function () { return selfcheckCommand(); },
        CONTRACT: function () { return contractCommand(); },
        NEW_SESSION: function () { return newSessionCommand(); }
    },
    driverExtras: {
        isTrustworthy: function (grid) {
            return geometryModule.isTrustworthy(grid, { minSamples: 4, maxResidual: 0.35 });
        },
        screen: function () { return adapter.screenSize(); }
    },
    boardOverrides: {
        ports: {
            now: function () { return Date.now(); },
            capture: function () {
                const granted = adapter.requestCapture("portrait");
                if (granted === false) {
                    return null;   // 用户拒了授权：服务会报「拿不到帧」
                }
                const frame = adapter.capture("full", 800);
                return frame ? { image: frame, imageObject: frame, frameObject: frame } : null;
            }
        },
        locateBoard: {
            // 服务已经拓好一帧，给 locate-board 的屏幕版复用（frame 选项），不重复截图
            locateBoardInImage: function (image, options) {
                return locateBoard.locateBoardOnScreen(Object.assign({}, options || {}, { frame: image }));
            }
        },
        /**
         * 几何精校准：按**最终未知格数**打分挑偏移（不是几何残差 —— 残差最小的并没能减少未知格）。
         * 为什么必须有：定位原点有系统偏差时会有几格**每一帧都认不出来**（多帧投票也洗不掉），
         * 状态机就一直拒绝计算/落子。真机实测（K40 + 天天象棋）：原点 (81,666) → 未知格 6；
         * 挪到 (70,662)（dx=-11,dy=-4）→ 未知格 1。定位一局只做一次，几十次推理约 2 秒可以承受。
         */
        calibrate: function (image, located) {
            return recognizeBoard.calibrateGrid(image, located, {
                orientation: "RED_BOTTOM",
                confidence: 0.25,
                search: { maxShift: 18, step: 7 },
                maxCandidates: 16
            });
        },
        note: function (text) { console.log("[xiangqi][board] " + text); },
        // A2：主界面的视觉流水线注入（懒加载；没有就退回 legacy，见 board-service.recognizeSmart）
        vision: visionInjection,
        // 「新的为主」：字形识别注入（auto 模式先跑它，见 board-service.recognizeSmart）
        glyph: mainGlyphSource
    }
});
const boardService = app.boards;
visionInjection.ready();   // 提前把 OCR 会话/检测会话拉起来（失败只会记日志，服务层仍可跑）

let modeKey = MODES.WATCH;
let tapMethodKey = "auto";
let autoDriver = null;
let autoRunning = false;
let overlayShown = false;
// 「绘制（画板）」开关：关掉就不画起点/落点、也不弹悬浮层。
// （面板上那个「绘制」是**识别绘制**档位，给人审冻结帧用的，是另一件事）
let pageDrawEnabled = true;
// 面板通过总线发来的停止请求（循环每 tick 查一次，见 stopRequested）
let stopByCommand = false;
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

/** 按钮上直写「偏好 + 最近实际用过」——只看偏好会骗人（auto 下实际可能走的是 screencap）。 */
function captureButtonText() {
    const used = adapter.captureChannel();
    return "截图通道：" + CAPTURE_LABEL[adapter.captureBackend()] +
        (used ? ("（最近：" + used + "）") : "");
}

// 启动时把「上次选的通道」写回按钮上。
// ⚠️ 这一行必须在 adapter / CAPTURE_LABEL / captureButtonText 都定义之后 ——
// 它们都是 const，提前用会报 "... is not initialized"（真机上就是 main.js 启动即退）。
ui.captureChannel.setText(captureButtonText());

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
    // A1「入口收口」批次1：驱动不再由入口直建 —— 统一走会话（装配、停止幂等、资源清理收在一处）。
    app.session.discard();
    autoDriver = app.session.build({
        allowClick: clickAllowed,
        tap: tapFunction,
        extras: {
            mySide: keepSide,
            executorConfig: { pressMs: 60, settleMs: 140 }
        },
        // 算棋放独立引擎线程：不然一步 ~1.2s 的搜索会把本页按钮/日志一起挡住
        driverConfig: {
            tickMs: 900,
            movetimeMs: 1200,
            modelVersion: "chessai_160x320_v5r",
            confidence: 0.25,
            orientation: "RED_BOTTOM",
            computeMode: "worker"
        }
    });
    ui.mySide.setText("我方：" + sideLabel(keepSide) + "（点一下切换）");
    ui.autoStatus.setText("模式：" + MODE_LABEL[modeKey] + tapNote + "，我方：" + sideLabel(keepSide) + "，未开始");
    setMainStatus("STOPPED", "模式：" + MODE_LABEL[modeKey] + tapNote + "；我方：" + sideLabel(keepSide));
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
        if (result.thinking) {
            lines.push("算棋中（" + driver.computeMode() + " 线程）：" + result.reason);
        } else {
            lines.push("观察：未知格 " + result.unknownCells +
                "，轮次：" + (result.side ? sideLabel(result.side) : "未知") +
                "，本轮 " + stats.lastObserveMs + "ms");
        }
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
    lines.push("算棋：" + driver.computeMode() + "（独立线程 " + stats.thinkOffThread +
        " 次 / 本线程 " + stats.thinkInline + " 次，最近一次 " + Math.round(stats.thinkMs) + "ms）");
    lines.push("截图：" + describeCaptureChannel());
    ui.captureChannel.setText(captureButtonText());
    const text = lines.join("\n");
    if (text !== lastDriverLine) {
        lastDriverLine = text;
        ui.autoStatus.setText(text);
    }
    setMainStatus(machine.state(), machine.reason());
    drawPlan(ticket);
}

/** 把「计划」画到悬浮层：没有悬浮窗权限就不画，不影响走棋。 */
function drawPlan(ticket) {
    const driver = autoDriver;
    if (!pageDrawEnabled) {
        // 绘制开关关掉：既不再画，也把已经画上去的收掉（不再依赖「停止」来关画板）
        if (overlayShown) {
            adapter.overlayHide();
            overlayShown = false;
        }
        return;
    }
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
            // 循环期间也要走总线：发布「页面正在跑」（面板靠它显示互锁状态），
            // 顺便收面板发来的停止/配置命令（stopRequested 会看到 stopByCommand）。
            pumpBus();
            if (stopRequested()) {
                show("检测到停止标记（" + stopFile() + "），退出自动循环。");
                break;
            }
            // 互锁续期：被别人抢走（例如面板里又点了开始）就果断停，不要两个引擎同时跑
            if (!loopLock.touch(loopLock.OWNER_MAIN)) {
                const now = loopLock.status();
                show("互锁被「" + (now.owner || "?") + "」拿走，本页自动循环退出。");
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
            // 算棋中（worker 在自己线程里转）→ 只密轮询结果（不截图）：算完第一时间出手；
            // 其他情况按 tickMs 走（screencap 通道 0.6~1.4s/帧，密了只是白烧 CPU）。
            const thinking = typeof autoDriver.isThinking === "function" && autoDriver.isThinking();
            sleep(thinking ? 120 : tickMs);
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
    if (stopByCommand) {
        return true;   // 面板通过总线发来的停止（详见 src/app/panel-bus.js）
    }
    try {
        const path = stopFile();
        return path !== "" && files.exists(path);
    } catch (error) {
        return false;
    }
}

function stopAuto(why) {
    app.session.stop(why || "停止");   // A1：停止路径统一走会话（幂等；驱动/窗口兜底在会话里）
    autoRunning = false;
    loopLock.release(loopLock.OWNER_MAIN);   // 互锁：本页停了就把锁交出去
    if (overlayShown) {
        adapter.overlayHide();
        overlayShown = false;
    }
    if (autoDriver) {
        setMainStatus("STOPPED", "已停止（" + why + "）");
    }
}

// ==================== A1 批次2：控制器命令实现（按钮 → 命令 → 业务） ====================

/** START：抢互锁 → 要截图授权 → 会话启动 → 先跑一拍再进主循环（原 ui.autoToggle 的启动段）。 */
function startAutoCommand() {
    // 互锁：悬浮窗面板里有另一套驱动循环，两个引擎一起跑会双份截图/算棋（模式③还会双份点击）
    const gate = loopLock.acquire(loopLock.OWNER_MAIN);
    if (!gate.ok) {
        show(gate.reason + "。先把悬浮窗面板里的循环停掉（面板上的「停止」或「关闭面板」）。");
        ui.autoStatus.setText("未开始：" + gate.reason);
        return { ok: false, reason: gate.reason };
    }
    if (!adapter.requestCapture("portrait")) {
        loopLock.release(loopLock.OWNER_MAIN);
        show("没有拿到截图授权，自动模式不启动。");
        return { ok: false, reason: "没有拿到截图授权" };
    }
    const started = app.session.start();
    if (started && started.ok === false) {
        loopLock.release(loopLock.OWNER_MAIN);
        show("自动模式启动失败：" + (started.reason || "未说明"));
        return { ok: false, reason: started.reason || "启动失败" };
    }
    ui.autoToggle.setText("暂停");
    setMainStatus("INITIALIZING", "正在取得新画面并校准棋盘。");
    driverTick();
    runAutoLoop();   // 阻塞到这里为止（详见 runAutoLoop 的退出条件）
    return { ok: true };
}

/** PAUSE：作废票据与算棋任务，但保留会话/几何（原 ui.autoToggle 的暂停段）。 */
function pauseAutoCommand() {
    autoDriver.pause();
    loopLock.release(loopLock.OWNER_MAIN);
    ui.autoToggle.setText("继续");
    setMainStatus("PAUSED", "票据与算棋任务已作废，不会继续点击。");
    show("已暂停：票据与算棋任务都作废了，不会再有点击。");
    return { ok: true };
}

/** STOP：停本页；「双向停止」仍留在按钮层做（总线语义，不在业务命令里）。 */
function stopAutoCommand() {
    stopAuto("用户停止");
    ui.autoStatus.setText("未开始（模式：" + MODE_LABEL[modeKey] + "）");
    ui.autoToggle.setText("开始");
    lastDriverLine = "";
    return { ok: true };
}

/** RELOCATE：截一帧自动找格线（原「定位棋盘」按钮的体）。 */
function relocateCommand() {
    try {
        const keep = lastOutput;
        show("正在定位棋盘…");
        const result = locateBoard.locateBoardOnScreen();
        show(locateBoard.describeResult(result) + (keep ? "\n\n—— 上一次输出 ——\n" + keep : ""));
        return { ok: !!(result && result.ok) };
    } catch (error) {
        show("定位没能运行：" + error);
        return { ok: false, reason: String(error) };
    }
}

/** SELFTEST：离线自检（原「离线自检」按钮的体）。 */
function selfcheckCommand() {
    try {
        const result = require("./tools/self-check.js");
        const lines = [result.summary];
        for (let i = 0; i < result.failures.length; i++) {
            lines.push((i + 1) + ". " + result.failures[i]);
        }
        show(lines.join("\n"));
        return { ok: result.failures.length === 0 };
    } catch (error) {
        show("自检没能运行：" + error);
        return { ok: false, reason: String(error) };
    }
}

/** CONTRACT：格子契约测试（原「契约测试」按钮的体）。 */
function contractCommand() {
    try {
        const result = require("./tools/test-cells-contract.js");
        const lines = ["契约测试：" + result.summary];
        for (let i = 0; i < Math.min(result.failures.length, 5); i++) {
            lines.push("  失败 " + (i + 1) + "：" + result.failures[i].name + " → " + result.failures[i].message);
        }
        show(lines.join("\n"));
        return { ok: result.failures.length === 0 };
    } catch (error) {
        show("契约测试没能运行：" + error);
        return { ok: false, reason: String(error) };
    }
}

/** NEW_SESSION：停 → 重建驱动（票据/几何/轮次基准全部作废）。 */
function newSessionCommand() {
    stopAuto("新会话");
    rebuildDriver();      // 重建驱动 = 会话/票据/几何/轮次基准全部作废
    ui.autoStatus.setText("新会话：票据与基准已作废（模式：" + MODE_LABEL[modeKey] + "）");
    show("新会话：驱动已重建，既有票据、几何与轮次基准全部作废。");
    return { ok: true };
}

// ======================= 跨引擎互锁总线（页面侧） =======================
// 面板与页面跑在不同 JS 引擎里：状态靠各自发布的快照、命令靠单槽文件（见 panel-bus.js）。
// 三条语义：状态互锁（谁在跑对方看得见）、双向停止、配置双向同步。

/** 本页状态快照：面板据此显示「页面在跑什么」，也用于判互锁。 */
function busSnapshot() {
    return {
        version: VERSION,
        running: autoRunning,
        mode: modeKey,
        side: (autoDriver ? autoDriver.mySide() : "w"),
        tap: tapMethodKey,
        capture: adapter.captureBackend(),
        drawEnabled: pageDrawEnabled,
        // A1 批次2：跨引擎对齐口径（几何版本/状态机/实际引擎）——两边用同一渲染函数显示
        gridVersion: (autoDriver && typeof autoDriver.grid === "function" && autoDriver.grid())
            ? autoDriver.grid().version : null,
        machineState: (autoDriver && autoDriver.machine) ? autoDriver.machine.state() : "STOPPED",
        backend: engineModule.lastEngine()
    };
}

/**
 * 总线泵：发布自己 → 收命令 → 刷新「面板」那一行。
 * 循环里每 tick 调一次；空闲时由文件末尾的 1s 定时器调（页面切后台没定时器，真机实测）。
 */
function pumpBus() {
    bus.publish(busSnapshot());
    let message;
    while ((message = bus.poll())) {
        applyBusCommand(message);
    }
    refreshPanelLine();
}

/** 「悬浮窗面板」那一行：显示面板状态，并在它跑着时把「开始」写成提醒。 */
function refreshPanelLine() {
    const other = bus.readOther();
    const otherRunning = !!(other && other.fresh && other.state && other.state.running);
    ui.floatyStatus.setText(bus.describeOther() +
        (otherRunning ? "｜两边互锁：要本页跑，先在面板点「停止」" : ""));
    if (!autoRunning) {
        ui.autoToggle.setText(otherRunning ? "面板运行中" : "开始");
    }
}

/** 收到面板的命令。目前只有 stop（双向停止）与 setConfig（配置同步）两种。 */
function applyBusCommand(message) {
    if (message.name === "stop") {
        const why = (message.arg && message.arg.why) ? message.arg.why : "面板请求停止";
        if (autoRunning || (autoDriver && autoDriver.isRunning())) {
            stopByCommand = true;         // 循环每 tick 看这个标记
            show("面板请求停止：" + why);
        } else {
            show("面板请求停止（本页本来没在跑）");
        }
        return;
    }
    if (message.name === "setConfig" && message.arg) {
        applyConfigFromPanel(message.arg.key, message.arg.value);
    }
}

/**
 * 应用面板同步过来的配置。
 * ⚠️ 只应用、不回发 —— 回发会变成两边互相回弹的死循环。
 */
function applyConfigFromPanel(key, value) {
    if (key === "mode" && MODES[value] && value !== modeKey) {
        modeKey = value;
        rebuildDriver();
        show("面板把模式改成：" + MODE_LABEL[modeKey]);
    } else if (key === "side" && (value === "w" || value === "b")) {
        if (!autoDriver) {
            rebuildDriver();
        }
        if (autoDriver.mySide() !== value) {
            autoDriver.setMySide(value);
            ui.mySide.setText("我方：" + sideLabel(value) + "（点一下切换）");
            show("面板把「我方」改成：" + sideLabel(value) + "（轮次基准已清空）");
        }
    } else if (key === "tap" && TAP_METHODS.indexOf(value) >= 0 && value !== tapMethodKey) {
        tapMethodKey = value;
        ui.tapMethod.setText("点击通道：" + TAP_LABEL[tapMethodKey]);
        rebuildDriver();
        show("面板把点击通道改成：" + describeTapChannel());
    } else if (key === "capture" && CAPTURE_BACKENDS.indexOf(value) >= 0 &&
               adapter.captureBackend() !== value) {
        adapter.setCaptureBackend(value);
        ui.captureChannel.setText(captureButtonText());
        rebuildDriver();
        show("面板把截图通道改成：" + describeCaptureChannel());
    } else if (key === "drawEnabled") {
        setPageDraw(!!value, false);
        show("面板把「绘制」改成：" + (pageDrawEnabled ? "开" : "关"));
    }
}

/** 绘制开关（本页的画板）：broadcast=true 时把状态同步给面板。 */
function setPageDraw(on, broadcast) {
    pageDrawEnabled = !!on;
    ui.pageDraw.setText(pageDrawEnabled ? "绘制：开（点一下关）" : "绘制：关（点一下开）");
    if (!pageDrawEnabled) {
        if (overlayShown) {
            adapter.overlayHide();
            overlayShown = false;
        }
    } else if (autoDriver && autoDriver.machine) {
        drawPlan(autoDriver.machine.currentTicket());   // 开的时候立刻把当前计划画出来
    }
    if (broadcast) {
        bus.syncConfig("drawEnabled", pageDrawEnabled);
    }
    return pageDrawEnabled;
}

ui.modeWatch.on("click", function () {
    modeKey = MODES.WATCH;
    rebuildDriver();
    bus.syncConfig("mode", modeKey);
    show("模式：① 只识别 —— 只看局面、算棋建议，点击接口是禁用桩（调用会被计数）。");
});

ui.modeAdvice.on("click", function () {
    modeKey = MODES.ADVICE;
    rebuildDriver();
    bus.syncConfig("mode", modeKey);
    show("模式：② 着法建议 —— 会算棋并显示起点/终点，仍然不会点击棋盘。");
});

ui.modeAuto.on("click", function () {
    modeKey = MODES.AUTO;
    rebuildDriver();
    bus.syncConfig("mode", modeKey);
    show("模式：③ 自动走棋 —— 点「开始观察 / 暂停」后，只有轮次、局面、几何、票据、着法" +
        "全部校验通过才会点击；落子要用新观测确认过才算成功。\n" +
        "第一次开始会弹截图授权；轮次推不出来时按「现在轮到我」声明一次。");
});

ui.tapMethod.on("click", function () {
    const index = TAP_METHODS.indexOf(tapMethodKey);
    tapMethodKey = TAP_METHODS[(index + 1) % TAP_METHODS.length];
    ui.tapMethod.setText("点击通道：" + TAP_LABEL[tapMethodKey]);
    bus.syncConfig("tap", tapMethodKey);
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
    adapter.setCaptureBackend(next);   // 界面上的选择会持久化（storages），重启脚本也记得
    ui.captureChannel.setText(captureButtonText());
    bus.syncConfig("capture", next);
    rebuildDriver();
    show("截图通道改为：" + describeCaptureChannel() + "\n" +
        "说明：系统截屏（MediaProjection）很快，但两条真实问题都踩到过：\n" +
        "  · MIUI 切前后台/前台服务变化会把会话移除，之后每帧都超时（实测连续 31s 全失败）；\n" +
        "  · 小米8 + Android 15 上还会给「半更新帧」：左列丢子、颜色判反（同一帧用文件识别是全对的）。\n" +
        "screencap 走 Shizuku/root：不需要授权、不会被作废、帧是完整的（小米8 实测 469~510ms/帧，\n" +
        "比投屏的 490~550ms 还略快）；没有 Shizuku/root 的设备只能用系统截屏。自动：先系统截屏，失败改走 screencap。");
});

ui.mySide.on("click", function () {
    if (!autoDriver) {
        rebuildDriver();
    }
    const side = autoDriver.mySide() === "b" ? "w" : "b";
    autoDriver.setMySide(side);
    ui.mySide.setText("我方：" + sideLabel(side) + "（点一下切换）");
    bus.syncConfig("side", side);
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
    // A1 批次2：启动/暂停统一走控制器命令（实现见下方的 xxxCommand）
    app.controller.run(autoDriver.isRunning() ? "PAUSE" : "START");
});

ui.autoStop.on("click", function () {
    app.controller.run("STOP");
    // 双向停止：面板在跑也一起停掉（否则本页没跑，用户点「停止」看着像没反应）
    const other = bus.readOther();
    if (other && other.fresh && other.state && other.state.running) {
        bus.stopOther("页面点了停止");
        show("已停止本页，并已请悬浮窗面板也停止。");
    } else {
        show("已停止：循环已停、画板已关。");
    }
});

// ==================== 与面板对齐的三项 + 绘制开关 ====================

ui.pageDraw.on("click", function () {
    const on = setPageDraw(!pageDrawEnabled, true);
    show("绘制（画板）：" + (on
        ? "已开 —— 有可靠计划时把起点/落点画在屏幕悬浮层上"
        : "已关 —— 不再画、也不再弹悬浮层（面板那边的「识别绘制」是另一档，不受影响）"));
});

ui.newSession.on("click", function () {
    // A1 批次2：按钮只发命令（实现同 newSessionCommand）
    app.controller.run("NEW_SESSION");
});

ui.contract.on("click", function () {
    app.controller.run("CONTRACT");
});

ui.clearLog.on("click", function () {
    lastOutput = "";
    ui.output.setText("");
});

// 空闲时的总线心跳（1s）：页面在前台才有定时器（切到后台定时器不再被派发，真机实测），
// 所以「面板正在跑」这类互锁显示只在页面可见时刷新 —— 那正是用户看得见的时刻。
setInterval(function () {
    if (!autoRunning) {
        pumpBus();
    }
}, 1000);
pumpBus();
setPageDraw(pageDrawEnabled, false);   // 按钮文案与状态对齐一次

