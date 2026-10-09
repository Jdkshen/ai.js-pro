// @engine rhino
// Android 14+：开启无障碍，运行后在 3 秒内切到目标应用。
if (!images.isWindowCaptureAvailable()) throw new Error("窗口截图需要 Android 14+ 和无障碍服务");
toast("请在 3 秒内切到要截图的应用");
sleep(3000);
var started = Date.now();
var frame = images.captureWindow({timeout:2000});
try {
    log("窗口：" + frame.packageName + "，耗时：" + (Date.now() - started) + "ms");
    log(JSON.stringify(frame.bounds));
    var output = files.getSdcardPath() + "/AIjs-window.png";
    images.save(frame.image, output);
    toastLog("已保存：" + output);
} finally {
    frame.image.recycle();
}
