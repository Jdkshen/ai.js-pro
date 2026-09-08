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

    console.log('QUICKJS_UI_FLOATY_OK');
} finally {
    if (win) win.close();
    if (uiOpened) ui.close();
    floaty.closeAll();
}
