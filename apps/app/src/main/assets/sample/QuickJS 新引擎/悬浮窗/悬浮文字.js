// @engine quickjs
// 悬浮文字
// 用途：对齐 Rhino 案例：点击文字切换调整模式后可拖动
// 前置：悬浮窗权限
// 覆盖：floaty / timers

var window = floaty.window(
    '<frame gravity="center">' +
    '  <text id="text" text="点击可调整位置" textSize="16sp"/>' +
    '</frame>'
);

window.exitOnClose();

window.text.click(function () {
    window.setAdjustEnabled(!window.isAdjustEnabled());
});

setInterval(function () {}, 1000);
