// @engine quickjs
// 悬浮窗输入框
// 用途：悬浮窗里的输入框取值与回填
// 前置：悬浮窗权限
// 覆盖：floaty / 手势/输入 / timers

var window = floaty.window(
    '<vertical>' +
    '  <input id="input" text="请输入你的名字" textSize="16sp" focusable="true"/>' +
    '  <button id="ok" text="确定"/>' +
    '</vertical>'
);

window.exitOnClose();

toast('长按确定键可调整位置');

// 返回键关闭焦点（对齐 Rhino 案例的 key 监听写法）
window.input.on('key', function (keyCode, event) {
    if (event.getAction() === event.ACTION_DOWN && keyCode === keys.back) {
        window.disableFocus();
        event.consumed = true;
    }
});

// 触摸输入框时重新聚焦
window.input.setOnTouchListener(function (event) {
    if (event.getAction() === event.ACTION_DOWN) {
        window.requestFocus();
        window.input.requestFocus();
    }
    return false;
});

window.ok.click(function () {
    toast('你好! ' + window.input.getText());
    window.disableFocus();
});

window.ok.longClick(function () {
    window.setAdjustEnabled(!window.isAdjustEnabled());
});

setInterval(function () {}, 1000);
