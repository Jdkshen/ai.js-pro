"quickjs";
// 只弹出一次截图授权；两个子引擎各自截图、回收，不传递整个 images 对象。
// 普通同步截图和 mode: "accessibility" 支持共享；async / auto 暂不支持。
// 共享凭据仅在当前应用进程、仍有参与引擎持有会话时有效。
function captureWorker() {
    images.useSharedScreenCapture(captureToken);
    try {
        for (var i = 0; i < 4; i++) {
            var img = images.captureScreen();
            try {
                console.log(workerName + " 第 " + (i + 1) + " 帧: " + img.width + "x" + img.height);
            } finally { img.recycle(); }
            sleep(600);
        }
    } finally { images.stopScreenCapture(); }
}
function childEngine(execution) {
    return typeof execution.isDestroyed === "function" ? execution : execution.getEngine();
}
function childFinished(execution) {
    var engine = childEngine(execution);
    return engine != null && engine.isDestroyed();
}
if (!images.requestScreenCapture()) throw new Error("未授予截图权限");
var workers = [];
try {
    var token = images.shareScreenCapture();
    for (var i = 1; i <= 2; i++) {
        var name = "共享截图子引擎" + i;
        // 把凭据写入子脚本，子脚本第一步就能连接，避免 engine-ready 监听注册时序问题。
        var source = "// @engine quickjs\nvar captureToken = " + JSON.stringify(token) +
            "; var workerName = " + JSON.stringify(name) + ";\n(" + captureWorker.toString() + ")();";
        workers.push(engines.execScript(name, source));
    }
    var deadline = Date.now() + 15000;
    while (workers.some(function(w) { return !childFinished(w); })) {
        if (Date.now() > deadline) throw new Error("子引擎未按时结束，请查看日志");
        sleep(100);
    }
    toastLog("两个共享截图子引擎已结束，请查看各引擎日志");
} finally {
    workers.forEach(function(w) {
        var engine = childEngine(w);
        if (engine != null && !engine.isDestroyed()) engine.forceStop();
    });
    // 只释放本引擎的引用；最后一个参与引擎停止时才释放系统截图授权。
    images.stopScreenCapture();
}
