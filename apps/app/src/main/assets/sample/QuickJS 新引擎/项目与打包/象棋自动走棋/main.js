// @engine quickjs
"ui";

/**
 * 象棋自动走棋 · 工程骨架 v0.3.0
 *
 * 现状：FEN / 棋盘几何 / 格点分配 / 时序融合 / 棋盘格线自动定位已是真代码
 * （离线自检 + 真实截图都已验过），但没有模型、棋规、本机算棋（UCI）与自动落子都还没接入 ——
 * 界面显示的就是真实状态。开发规格与分阶段要求见本工程 README.md。
 */

const VERSION = "0.3.0";

const STEPS = [
  "P0  运行时探针：截图 / 模型 / 线程 / 打包能力核对",
  "P0-A 平台能力补齐：模型契约、帧来源、UCI 桥接、原生打包",
  "P1  离线识别：棋盘定位 + 格点校准 + FEN",
  "P2  实时观察：截图时序 + 轮次 + 遮挡恢复",
  "P3  本机算棋：本地引擎握手与着法建议",
  "P4  自动执行：动作票据 + 点击 + 落子确认",
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
  '  <scroll height="260dp">' +
  '    <text id="output" textSize="12sp" textColor="#333333"/>' +
  '  </scroll>' +
  '</vertical>'
);

ui.version.setText("工程版本 v" + VERSION + "（骨架）");
ui.status.setText("状态：棋盘定位可用（需自备模型）/ 棋规 / 算棋 / 落子未接入。本工程当前不会点击棋盘。");
ui.steps.setText(STEPS.join("\n"));

let lastOutput = "";

function show(text) {
    lastOutput = text;
    ui.output.setText(text);
    console.log("[xiangqi] " + text);
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
        lines.push("模型：" + (report.model.hasOnnx ? "已随包" : "未随包（识别无法实测）"));
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
