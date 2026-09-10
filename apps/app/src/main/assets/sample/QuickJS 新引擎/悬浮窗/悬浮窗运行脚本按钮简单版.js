// @engine quickjs
// 对齐 Rhino 案例：悬浮窗运行脚本按钮（简单版）。
// 点击按钮开始/停止运行指定脚本，长按进入调整模式。
var path = '/sdcard/脚本/test.js';
if (!files.exists(path)) {
    toast('脚本文件不存在: ' + path);
    exit();
}
var window = floaty.window(
    '<frame>' +
    '  <button id="action" text="开始运行" w="90" h="40" bg="#77ffffff"/>' +
    '</frame>'
);

window.exitOnClose();

var execution = null;

window.action.click(function () {
    if (window.action.getText() == '开始运行') {
        execution = engines.execScriptFile(path);
        window.action.setText('停止运行');
    } else {
        if (execution) {
            execution.getEngine().forceStop();
        }
        window.action.setText('开始运行');
    }
});

window.action.longClick(function () {
    window.setAdjustEnabled(!window.isAdjustEnabled());
});

setInterval(function () {}, 1000);
