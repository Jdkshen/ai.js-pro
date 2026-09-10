// @engine quickjs
// 对齐 Rhino 案例：悬浮窗输入框（QuickJS 简化版）。
// 输入名字后点击"确定"弹出问候；长按"确定"切换调整模式。
// 说明：QuickJS 暂未桥接键盘事件（on("key")），这里以点击确定代替。
var window = floaty.window(
    '<vertical>' +
    '  <input id="input" text="请输入你的名字" textSize="16sp" focusable="true"/>' +
    '  <button id="ok" text="确定"/>' +
    '</vertical>'
);

window.exitOnClose();

toast('长按确定键可调整位置');

window.ok.click(function () {
    toast('你好! ' + window.input.getText());
    window.disableFocus();
});

window.ok.longClick(function () {
    window.setAdjustEnabled(!window.isAdjustEnabled());
});

setInterval(function () {}, 1000);
