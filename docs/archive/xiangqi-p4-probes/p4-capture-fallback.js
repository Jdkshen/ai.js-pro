// @engine quickjs
/**
 * 截图通道「自动回退」集成验收：
 * 现在 K40 上的 MediaProjection 会话是坏的（每帧都超时），所以 auto 模式必须
 * **自动改走 shell（Shizuku screencap）**，并且整条识别链路要能照常跑完。
 *
 * 判定：capture 成功 + 通道变成 shizuku + recognizeScreen 出局面。
 */
const adapter = require("../src/runtime/aijs-adapter.js");
const board = require("./recognize-board.js");

console.log("CAPF 等待 9s（PC 端把游戏切前台）｜偏好=" + adapter.captureBackend() +
    "｜shell 通道=" + adapter.shellCaptureBackend() +
    "｜系统截屏接口=" + (adapter.capabilities().captureScreen ? "有" : "无"));
sleep(9000);

// 1) 单独试一次 capture：系统截屏坏掉时应自动退到 shell
let frame = null;
let captureError = "";
const started = Date.now();
try {
    frame = adapter.capture("full", 600);
} catch (error) {
    captureError = String(error && error.message ? error.message : error);
}
console.log("CAPF capture ok=" + (!!frame) + " 用时=" + (Date.now() - started) + "ms" +
    " 实际通道=" + adapter.captureChannel() + (captureError ? (" 错误=" + captureError.slice(0, 110)) : ""));
if (frame) {
    const info = adapter.describeFrame(frame);
    console.log("CAPF 帧尺寸=" + (info.pixelWidth || info.width) + "x" + (info.pixelHeight || info.height) +
        " blank=" + info.blank);
    try { frame.recycle(); } catch (error) { /* 忽略 */ }
}

// 2) 整条识别链路（定位 + 识别），走的就是自动回退后的通道
const t2 = Date.now();
let recognized = null;
try {
    recognized = board.recognizeScreen({ orientation: "RED_BOTTOM", confidence: 0.25 });
} catch (error) {
    console.log("CAPF recognize 异常：" + String(error && error.message ? error.message : error).slice(0, 120));
}
if (recognized) {
    console.log("CAPF recognize ok=" + recognized.ok +
        " stage=" + (recognized.stage || "-") +
        " 未知格=" + (recognized.ok ? recognized.unknownCells : "-") +
        " 用时=" + (Date.now() - t2) + "ms" +
        (recognized.ok ? (" 局面=" + recognized.layout) : (" 原因=" + recognized.reason)));
}
console.log("CAPF 最终通道=" + adapter.captureChannel());
console.log("CAPF done");
