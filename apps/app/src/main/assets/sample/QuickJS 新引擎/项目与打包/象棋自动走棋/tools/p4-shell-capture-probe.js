// @engine quickjs
/**
 * 截图通道探针：能不能用 **Shizuku shell 跑 `screencap`** 拿屏，彻底绕开 MediaProjection？
 *
 * 背景：K40/MIUI 上 MediaProjection 会话被移除后就再也出不了帧
 * （`Timed out waiting for the first screen capture frame`，连续 31s 全失败），
 * 而 Shizuku 的 shell 身份本来就能跑 `screencap -p`（等同 adb shell screencap）。
 *
 * 本探针：先 sleep 9s（PC 端把游戏切前台）→ 连续 3 次 shell 截图，报：
 *   退出码 / 耗时 / 文件大小 / 读回来的尺寸 / 状态栏与棋盘上几个取样点的颜色
 */
const FILE = "/sdcard/yolo-bench/cap-shell.png";

console.log("CAPSH 等待 9s（PC 端把游戏切前台）");
sleep(9000);

for (let i = 1; i <= 3; i++) {
    const started = Date.now();
    let captured = null;
    let shellResult = null;
    try {
        shellResult = shell("screencap -p " + FILE, { shizuku: true, timeout: 8000 });
    } catch (error) {
        console.log("CAPSH " + i + " shell 异常：" + String(error && error.message ? error.message : error).slice(0, 80));
        continue;
    }
    const shellMs = Date.now() - started;
    const code = shellResult ? shellResult.code : "null";
    const errText = shellResult && shellResult.error ? String(shellResult.error).trim().slice(0, 80) : "";
    try {
        captured = images.read(FILE);
    } catch (error) {
        console.log("CAPSH " + i + " images.read 异常：" + String(error && error.message ? error.message : error).slice(0, 80));
    }
    if (!captured) {
        console.log("CAPSH " + i + " code=" + code + " 读不回来" + (errText ? (" err=" + errText) : ""));
        continue;
    }
    const w = captured.width;
    const h = captured.height;
    // 状态栏（顶部）与棋盘中部取样，证明拿到的是真实屏幕而不是黑图
    function hex(x, y) {
        const p = images.pixel(captured, x, y);
        const r = colors.red(p);
        const g = colors.green(p);
        const b = colors.blue(p);
        function pad(v) { const s = v.toString(16); return (s.length < 2 ? "0" : "") + s; }
        return "#" + pad(r) + pad(g) + pad(b);
    }
    console.log("CAPSH " + i + " code=" + code + " shell耗时=" + shellMs + "ms" +
        " 图片=" + w + "x" + h +
        " 顶部=" + hex(Math.round(w / 2), 20) +
        " 棋盘=" + hex(Math.round(w / 2), Math.round(h / 2)) +
        " 底部=" + hex(Math.round(w / 2), h - 40) +
        " 总耗时=" + (Date.now() - started) + "ms" +
        (errText ? (" err=" + errText) : ""));
    try { captured.recycle(); } catch (error) { /* 忽略 */ }
    sleep(700);
}
console.log("CAPSH done");
