// @engine quickjs
"ui";

/**
 * 象棋自动走棋 · 工程骨架 v0.1.0
 *
 * 现状：这里只有界面骨架和目录约定。棋盘识别（ONNX）、棋规、本机算棋（UCI）与
 * 自动落子都还没有实现，界面显示的状态就是真实状态——不做任何"看起来能跑"的假动作。
 * 开发规格与分阶段要求见本工程 README.md。
 */

const VERSION = "0.1.0";

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
  '  <frame height="14"/>' +
  '  <button id="probe" text="查看运行环境"/>' +
  '</vertical>'
);

ui.version.setText("工程版本 v" + VERSION + "（骨架）");
ui.status.setText("状态：识别 / 棋规 / 算棋 / 落子均未接入。本工程当前不会点击棋盘。");
ui.steps.setText(STEPS.join("\n"));

// 探针只读环境信息，不截图、不加载模型、不点击。真正的能力探针按任务书另建脚本。
ui.probe.on("click", () => {
    const lines = ["引擎：QuickJS"];
    try {
        lines.push("Android：" + android.os.Build.VERSION.RELEASE);
        lines.push("机型：" + android.os.Build.MODEL + "（" + android.os.Build.SUPPORTED_ABIS[0] + "）");
    } catch (error) {
        lines.push("设备信息读取失败：" + error);
    }
    try {
        lines.push("屏幕：" + device.width + " × " + device.height);
    } catch (error) {
        lines.push("屏幕信息读取失败：" + error);
    }
    try {
        lines.push("工程目录：" + files.cwd());
    } catch (error) {
        lines.push("工程目录读取失败：" + error);
    }
    ui.status.setText(lines.join("\n"));
    console.log("[xiangqi] " + lines.join(" | "));
});
