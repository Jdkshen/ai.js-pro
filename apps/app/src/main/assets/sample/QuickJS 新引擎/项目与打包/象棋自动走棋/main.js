// @engine quickjs
"ui";

/**
 * 象棋自动走棋 · 工程骨架 v0.5.0
 *
 * 现状：FEN / 棋盘几何 / 格线自动定位 / 格点分配 / 时序融合 / 识别链路（模型随包）/ 棋规与合法着法 /
 * 本机算棋（本地 UCI 语义引擎）都是真代码；缺的是自动落子 —— 界面显示的就是真实状态。
 * 开发规格与分阶段要求见本工程 README.md。
 */

const VERSION = "0.5.0";

const STEPS = [
  "P0  运行时探针：截图 / 模型 / 线程 / 打包能力核对",
  "P0-A 平台能力补齐：模型契约、帧来源、UCI 桥接、原生打包",
  "P1  离线识别：棋盘定位 + 格点校准 + FEN（已可用，模型随包）",
  "P2  实时观察：截图时序 + 轮次 + 遮挡恢复（待接）",
  "P3  本机算棋：本地引擎握手与着法建议（已可用，\./src/engine）",
  "P4  自动执行：动作票据 + 点击 + 落子确认（待接）",
  "P5  真机验收：打包独立 APK 后回归"
];

ui.layout(
  '<vertical padding="18">' +
  '  <text text="象棋自动走棋" textSize="24sp" textStyle="bold"/>' +
  '  <text id="version" textSize="13sp" textColor="#888888"/>' +
  '  <frame height="14"/>' +
  '  <text id="status" textSize="15sp" textColor="#d32f2f"/>' +
  '  <frame height="14"/>' +
  '  <text text="开发阶段" textSize="16sp" textStyle="bold"/>' +
  '  <text id="steps" textSize="13sp" textColor="#555555"/>' +
  '  <frame height="10"/>' +
  '  <button id="selfcheck" text="运行离线自检"/>' +
  '  <button id="probe" text="查看运行环境"/>' +
  '  <button id="locate" text="定位棋盘（真机）"/>' +
  '  <text text="棋盘截图路径（相册里的图就能用）" textSize="13sp" textColor="#555555"/>' +
  '  <input id="image" text="/sdcard/Download/chess-shot-01.png" textSize="13sp"/>' +
  '  <button id="locateImage" text="定位棋盘（图片）"/>' +
  '  <button id="recognizeImage" text="识别棋盘 → FEN（图片）"/>' +
  '  <button id="recognizeSeq" text="连拍识别 ×3 → FEN（多帧投票）"/>' +
  '  <text text="算棋局面（FEN，含轮次；识别成功会自动填进来）" textSize="13sp" textColor="#555555"/>' +
  '  <input id="fen" text="rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1" textSize="12sp"/>' +
  '  <button id="think" text="算棋（本地引擎）→ 最佳着法"/>' +
  '  <scroll height="260dp">' +
  '    <text id="output" textSize="12sp" textColor="#333333"/>' +
  '  </scroll>' +
  '</vertical>'
);

ui.version.setText("工程版本 v" + VERSION + "（骨架）");
ui.status.setText("状态：识别→FEN 可用（模型随包）/ 棋规可用 / 本地引擎算棋可用；自动落子未接入。本工程当前不会点击棋盘。");
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
        if (report.frames.full) {
            lines.push("实时帧：" + report.frames.full.width + "×" + report.frames.full.height +
                "  像素 " + report.frames.full.pixelWidth + "×" + report.frames.full.pixelHeight);
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
