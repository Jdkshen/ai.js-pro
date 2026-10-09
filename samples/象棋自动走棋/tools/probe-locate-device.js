// @engine quickjs
/**
 * 设备端「多分辨率棋盘定位」探针：在真机上用**真实的 images.pixel 采样链路**跑定位，
 * 结果写文件（logcat 里的中文在 PowerShell 侧会乱码，落盘再拉更可靠）。
 *
 * 用法：
 *   1) adb push 若干棋盘截图到 /sdcard/xiangqi-p4/（文件名见 IMAGES，可自己改）
 *   2) am start -n com.jdkshen.aijspro/com.jdkshen.aijspro.external.open.RunIntentActivity \
 *        --es path /sdcard/<工程目录>/tools/probe-locate-device.js
 *   3) adb shell cat /sdcard/xiangqi-p4/result.txt
 *
 * 为什么需要：PC 上用 node 跑 `tools/self-check.js` 只能验算法本身；真机的像素采样、
 * 图片读取、耗时都只有这条路能验。
 */
const locateBoard = require("../src/vision/locate-board.js");

const IMAGES = [
    "/sdcard/xiangqi-p4/eval-fast-720x1600.png",
    "/sdcard/xiangqi-p4/after-auto-1080x2248.png",
    "/sdcard/xiangqi-p4/shot-64-1080x2400.png",
    "/sdcard/xiangqi-p4/newphone-now-1280x2772.png"
];

const out = [];
for (let i = 0; i < IMAGES.length; i++) {
    const path = IMAGES[i];
    let result = null;
    let error = "";
    const started = Date.now();
    try {
        result = locateBoard.locateBoardInImage(path);
    } catch (e) {
        error = String(e && e.message ? e.message : e);
    }
    const ms = Date.now() - started;
    if (error) {
        out.push(path + "  → 抛异常：" + error);
        continue;
    }
    out.push(path + "  → " + (result.ok ? "OK" : "FAIL") + "  " + ms + "ms  尝试" + (result.attempts || 1) + "次");
    out.push("  " + locateBoard.describeResult(result).replace(/\n/g, "\n  "));
    if (result.ok) {
        out.push("  两轴格距 (" + result.grid.stepX.toFixed(2) + ", " + result.grid.stepY.toFixed(2) + ")" +
            "  图片 " + result.imageSize.width + "×" + result.imageSize.height);
    }
}
files.write("/sdcard/xiangqi-p4/result.txt", out.join("\n"));
console.log("PROBE_DONE");
