// @engine quickjs
// 对齐 Rhino 案例：悬浮文字。点击文字切换"调整模式"，开启后可拖动窗口。
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
