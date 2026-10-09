// @engine rhino
// Android 8+；手机必须支持在指纹传感器上滑动。指纹解锁不代表支持手势。
// 运行后监听 30 秒；停止脚本也会自动释放监听。
auto.waitFor();
events.on("fingerprint_gesture", function (direction) {
    log("指纹滑动：" + direction); // up / down / left / right
    // 可按需调用 back()、home()、scrollDown() 等操作。
});
events.on("fingerprint_gesture_available", function (available) {
    log("指纹手势当前可用：" + available);
});
if (!events.observeFingerprintGesture()) {
    log("需要 Android 8 或以上系统");
} else {
    setTimeout(function () {
        log("检测结果：" + events.isFingerprintGestureAvailable());
        log("若为 false，请确认硬件/ROM 支持；指纹认证期间也会暂时不可用。");
    }, 500);
    setTimeout(function () {
        events.stopObservingFingerprintGesture();
        log("已停止指纹手势监听");
    }, 30000);
}
