// @engine quickjs
// 对齐 Rhino 案例：悬浮窗输入框（QuickJS 完整版）。
// 返回键关闭焦点、触摸输入框重新聚焦、长按确定切换调整模式。
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
