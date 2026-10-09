// @engine rhino
// 前置：悬浮窗权限。适用于脚本自己的 UI / 悬浮窗控件。
// 两个新接口：ui.replaceAccessibilityAction、ui.performClick。

var win = floaty.rawWindow([
    '<vertical w="' + Math.round(device.width * 0.88) + 'px" padding="16" bg="#FFF8FAFC">',
    '<text text="自定义触摸与无障碍点击" textSize="19sp" textColor="#0F172A"/>',
    '<text text="轻触蓝色区域；拖动不会触发点击。无障碍点击动作名为“选择”。" textColor="#475569" marginTop="8"/>',
    '<text id="pad" text="轻触选择" gravity="center" h="100" bg="#DBEAFE" textColor="#1D4ED8" textSize="22sp" marginTop="12"/>',
    '<text id="result" text="已选择 0 次" textColor="#0F172A" marginTop="12"/>',
    '<button id="simulate" text="调用 performClick"/>',
    '<button id="accessible" text="测试无障碍点击"/>',
    '<button id="quit" text="关闭"/>',
    '</vertical>'
].join(''));
win.setPosition(Math.round(device.width * 0.06), Math.round(device.height * 0.2));

var count = 0;
function select() {
    count++;
    ui.run(function () { win.result.setText('已选择 ' + count + ' 次'); });
    console.log('选择次数：' + count);
}

// 同一业务函数分别接到普通点击和无障碍点击，两条路径各触发一次。
ui.run(function () { win.pad.on('click', select); });
ui.replaceAccessibilityAction(win.pad, 'click', '选择', select);
// 长按动作同样支持：ui.replaceAccessibilityAction(win.pad, 'long_click', '全选', callback);

var downX = 0, downY = 0, tapping = false;
var slop = Number(android.view.ViewConfiguration.get(context).getScaledTouchSlop());
ui.run(function () {
    win.pad.on('touch', function (event) {
        event.consumed = true;
        var action = event.getAction() & 255;
        if (action === 0) {
            downX = event.getX(); downY = event.getY(); tapping = true;
        } else if (action === 2) {
            if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) tapping = false;
        } else if (action === 1) {
            var clicked = tapping && Math.abs(event.getX() - downX) <= slop && Math.abs(event.getY() - downY) <= slop;
            tapping = false;
            // 自己接管触摸后，用原生 performClick 分发普通点击和无障碍事件。
            if (clicked) ui.performClick(win.pad);
        } else if (action === 3 || action === 5) {
            tapping = false;
        }
    });
    win.simulate.on('click', function () { ui.performClick(win.pad); });
    win.accessible.on('click', function () {
        var nativePad = win.pad.__floatyView === true ? win.pad.javaView : win.pad;
        ui.run(function () { nativePad.performAccessibilityAction(16, null); });
    });
    win.quit.on('click', function () { win.close(); exit(); });
});
// 脚本停止时自动移除登记的无障碍动作。
