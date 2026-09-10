// @engine quickjs
// 对齐 Rhino 案例：悬浮窗运行脚本按钮（触摸拖动版）。
// 按住按钮可拖动窗口；按住超过 1.5 秒退出；轻点开始/停止脚本。
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

var execution = null;
// 记录按键被按下时的触摸坐标与悬浮窗位置
var x = 0, y = 0;
var windowX = 0, windowY = 0;
var downTime = 0;

window.action.setOnTouchListener(function (event) {
    switch (event.getAction()) {
        case event.ACTION_DOWN:
            x = event.getRawX();
            y = event.getRawY();
            windowX = window.getX();
            windowY = window.getY();
            downTime = new Date().getTime();
            return true;
        case event.ACTION_MOVE:
            // 移动手指时调整悬浮窗位置
            window.setPosition(windowX + (event.getRawX() - x),
                windowY + (event.getRawY() - y));
            // 按下的时间超过 1.5 秒视为长按，退出脚本
            if (new Date().getTime() - downTime > 1500) {
                exit();
            }
            return true;
        case event.ACTION_UP:
            // 手指弹起时如果偏移很小则判断为点击
            if (Math.abs(event.getRawY() - y) < 5 && Math.abs(event.getRawX() - x) < 5) {
                onClick();
            }
            return true;
    }
    return true;
});

function onClick() {
    if (window.action.getText() == '开始运行') {
        execution = engines.execScriptFile(path);
        window.action.setText('停止运行');
    } else {
        if (execution) {
            execution.getEngine().forceStop();
        }
        window.action.setText('开始运行');
    }
}

setInterval(function () {}, 1000);
