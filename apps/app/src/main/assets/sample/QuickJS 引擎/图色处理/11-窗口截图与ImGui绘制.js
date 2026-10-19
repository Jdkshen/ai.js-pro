"quickjs";
// Android 14+：请开启无障碍服务，运行后切到目标应用；最多等待 10 秒。
if (!images.isWindowCaptureAvailable()) throw new Error("窗口截图需要 Android 14+ 和无障碍服务");
toast("请切到要截图的应用（最多等待 10 秒）");
let target = null;
const deadline = Date.now() + 10000;
let signature = "", stableSince = 0;
while (!target && Date.now() < deadline) {
    const candidate = images.listCaptureWindows().filter(function (w) { return (w.foreground === true || w.active) && w.packageName; })[0] || null;
    const next = candidate ? JSON.stringify([candidate.windowId,candidate.packageName,candidate.bounds,candidate.rotation]) : "";
    if (next && next === signature && Date.now() - stableSince >= 600) target = candidate;
    else if (next !== signature) { signature = next; stableSince = Date.now(); }
    if (!target) sleep(150);
}
if (!target) throw new Error("未找到活动应用窗口，请切到目标应用后重试");
let frame = null;
try {
    if (!drawing.show({renderer:"imgui"})) throw new Error("无法创建 ImGui 绘制层");
    const start = performance.now();
    frame = images.captureWindow({windowId:target.windowId, packageName:target.packageName, timeout:2000, screenCoordinates:true});
    const b = frame.bounds;
    // 绘制坐标与 frame.image 的屏幕坐标一致；透明绘制层不会进入窗口截图。
    const x1 = b.left + b.width * 0.25, y1 = b.top + b.height * 0.4;
    const x2 = b.left + b.width * 0.75, y2 = b.top + b.height * 0.6;
    drawing.drawArrow(x1, y1, x2, y2, "#FFD54F", 5, 24);
    drawing.drawCircle(x1, y1, 16, "#40C4FF", 3);
    drawing.drawCircle(x2, y2, 22, "#FFD54F", 3);
    drawing.update([{x1:b.left, y1:b.top, x2:b.right, y2:b.bottom, label:frame.packageName}],
        "窗口截图 " + Math.round(performance.now() - start) + "ms · frame " + frame.frameId);
    log("窗口信息：", {windowId:frame.windowId, packageName:frame.packageName, bounds:b, frameId:frame.frameId, isolated:frame.isolated});
    log("截图已取得；可将 frame.image 直接传给 YOLO / SIFT。");
    sleep(5000);
} finally {
    if (frame) frame.image.recycle();
    drawing.hide();
}
