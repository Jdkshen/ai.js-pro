// @engine quickjs
// UI 与 Floaty 回归测试
// 用途：ui 覆盖层布局与 floaty 窗口的创建、更新与关闭
// 前置：悬浮窗权限
// 覆盖：floaty / ui / device / 手势/输入

var win = null;
var uiOpened = false;
try {
    var layoutId = ui.layout([
        '<vertical padding="16dp" bg="#FF202124">',
        '  <text id="title" text="QuickJS UI" textSize="18sp" textColor="#FFFFFFFF" />',
        '</vertical>'
    ].join('\n'));
    uiOpened = true;
    if (!(layoutId > 0)) throw new Error('ui.layout did not return a valid id');

    $ui.title.setText('QuickJS UI updated');
    if ($ui.title.getText() !== 'QuickJS UI updated') {
        throw new Error('UI text update/readback failed');
    }
    ui.close();
    uiOpened = false;

    win = floaty.window({
        text: 'QuickJS floaty',
        textSize: 14,
        backgroundColor: '#CC202124',
        x: 40,
        y: 160,
        width: 260,
        height: 72,
        touchable: true
    });
    if (!(win.id > 0)) throw new Error('floaty.window did not return a valid id');
    win.setText('QuickJS floaty updated');
    win.setPosition(56, 180);
    win.setSize(280, 76);
    sleep(250);
    win.close();
    win = null;

    // XML 布局 + window.<id> 控件代理(Rhino 悬浮窗案例对齐)
    win = floaty.window([
        '<vertical>',
        '  <text id="label" text="hello" textSize="16sp" textColor="#FFFFFFFF" />',
        '  <button id="action" text="开始" />',
        '</vertical>'
    ].join('\n'));
    if (!(win.id > 0)) throw new Error('floaty xml window did not return a valid id');
    if (win.label.getText() !== 'hello') throw new Error('floaty view getText failed');
    win.label.setText('updated');
    if (win.label.getText() !== 'updated') throw new Error('floaty view setText failed');
    win.action.setVisibility(8);
    win.action.click(function () { });
    win.action.longClick(function () { });
    if (typeof win.action.setOnTouchListener !== 'function') {
        throw new Error('floaty view setOnTouchListener missing');
    }
    win.action.setOnTouchListener(function (event) {
        if (!event || typeof event.getAction !== 'function') {
            throw new Error('touch event object invalid');
        }
    });
    if (typeof win.action.requestFocus !== 'function') {
        throw new Error('floaty view requestFocus missing');
    }
    if (typeof win.requestFocus !== 'function' || typeof win.disableFocus !== 'function') {
        throw new Error('floaty window focus helpers missing');
    }
    if (typeof ui.run !== 'function') throw new Error('ui.run missing');
    if (typeof exit !== 'function') throw new Error('exit missing');
    if (!(device.getAvailMem() > 0)) throw new Error('device.getAvailMem failed');
    if (!(device.getTotalMem() > 0)) throw new Error('device.getTotalMem failed');
    if (keys.back !== 4) throw new Error('keys.back missing');
    if (typeof win.action.onKey !== 'function') throw new Error('floaty view onKey missing');
    win.action.onKey(function (keyCode, event) {
        if (!event || typeof event.getAction !== 'function') {
            throw new Error('key event object invalid');
        }
    });
    if (win.isAdjustEnabled()) throw new Error('adjust should default to false');
    win.setAdjustEnabled(true);
    if (!win.isAdjustEnabled()) throw new Error('setAdjustEnabled(true) failed');
    win.setAdjustEnabled(false);
    win.setPosition(30, 60);
    sleep(200);
    if (win.getX() !== 30 || win.getY() !== 60) {
        throw new Error('floaty getX/getY failed: ' + win.getX() + ',' + win.getY());
    }
    // 注意：exitOnClose() 会让 close() 结束时退出脚本（与 Rhino 一致），
    // 因此这里不做该组合，exitOnClose 的行为由独立场景验证。
    win.close();
    win = null;

    console.log('QUICKJS_UI_FLOATY_OK');
} finally {
    if (win) win.close();
    if (uiOpened) ui.close();
    floaty.closeAll();
}
