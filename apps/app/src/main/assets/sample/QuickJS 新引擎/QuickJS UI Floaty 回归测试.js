// @engine quickjs
// 非交互回归：验证 QuickJS UI 布局、控件更新与悬浮窗生命周期。

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
    if (win.isAdjustEnabled()) throw new Error('adjust should default to false');
    win.setAdjustEnabled(true);
    if (!win.isAdjustEnabled()) throw new Error('setAdjustEnabled(true) failed');
    win.setAdjustEnabled(false);
    win.setPosition(30, 60);
    sleep(200);
    if (win.getX() !== 30 || win.getY() !== 60) {
        throw new Error('floaty getX/getY failed: ' + win.getX() + ',' + win.getY());
    }
    win.exitOnClose();
    win.close();
    win = null;

    console.log('QUICKJS_UI_FLOATY_OK');
} finally {
    if (win) win.close();
    if (uiOpened) ui.close();
    floaty.closeAll();
}
