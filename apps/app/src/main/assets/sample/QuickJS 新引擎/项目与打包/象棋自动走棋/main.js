// @engine quickjs
"ui";

/**
 * 象棋自动走棋 · 工程骨架 v0.4.0
 *
 * 现状：FEN / 棋盘几何 / 格线自动定位 / 格点分配 / 时序融合 / 识别链路（模型随包）都是真代码，
 * 棋规与合法着法也已在；缺的是本机算棋（UCI）与自动落子 —— 界面显示的就是真实状态。
 * 开发规格与分阶段要求见本工程 README.md。
 */

const VERSION = "0.4.0";

const STEPS = [
  "P0  运行时探针：截图 / 模型 / 线程 / 打包能力核对",
  "P0-A 平台能力补齐：模型契约、帧来源、UCI 桥接、原生打包",
  "P1  离线识别：棋盘定位 + 格点校准 + FEN（已可用，模型随包）",
  "P2  实时观察：截图时序 + 轮次 + 遮挡恢复（待接）",
  "P3  本机算棋：本地引擎握手与着法建议（待接）",
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
  '  <scroll height="260dp">' +
  '    <text id="output" textSize="12sp" textColor="#333333"/>' +
  '  </scroll>' +
  '</vertical>'
);

ui.version.setText("工程版本 v" + VERSION + "（骨架）");
ui.status.setText("状态：棋盘定位 + 识别→FEN 可用（模型随包）/ 棋规 / 算棋 / 落子未接入。本工程当前不会点击棋盘。");
ui.steps.setText(STEPS.join("\n"));

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
        show(board.describeResult(result) + (keep ? "\n\n—— 上一次输出 ——\n" + keep : ""));
    } catch (error) {
        show("识别没能运行：" + error);
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
