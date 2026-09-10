// @engine quickjs
// 对齐 Rhino 案例：动态悬浮文字。每秒刷新时间、当前 Activity 与包名。
var window = floaty.window(
    '<frame gravity="center">' +
    '  <text id="text" textSize="16sp" textColor="#f44336"/>' +
    '</frame>'
);

window.exitOnClose();

window.text.click(function () {
    window.setAdjustEnabled(!window.isAdjustEnabled());
});

setInterval(function () {
    // QuickJS 下控件操作本身已线程安全，ui.run 仅用于保持 Rhino 写法兼容。
    ui.run(function () {
        window.text.setText(dynamicText());
    });
}, 1000);

function pad(number) {
    return (number < 10 ? '0' : '') + number;
}

function dynamicText() {
    var date = new Date();
    var str = '时间: ' + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds()) + '\n';
    str += '当前活动: ' + currentActivity() + '\n';
    str += '当前包名: ' + currentPackage();
    return str;
}
