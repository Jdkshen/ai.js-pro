// @engine quickjs
// 悬浮窗运行脚本按钮（简单版）
// 用途：最小可用的「按钮 → 执行脚本」悬浮窗
// 前置：悬浮窗权限
// 覆盖：floaty / engines / files / 手势/输入 / timers

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
