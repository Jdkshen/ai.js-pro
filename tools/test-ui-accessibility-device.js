// @engine quickjs
"ui";
// Change only the engine marker to run the same checks on Rhino.
var engineName = typeof __aiRegisterCallback === 'function' ? 'quickjs' : 'rhino';
var resultFile = '/sdcard/Download/ui-accessibility-' + engineName + '.json';
var report = { engine:engineName, checks:[], ok:false };
var win = null, ended = false;
function check(name, value) {
    report.checks.push({ name:name, ok:!!value });
    if (!value) throw new Error(name);
}
function finish(error) {
    if (ended) return;
    ended = true;
    report.ok = !error;
    if (error) report.error = String(error) + '\n' + String(error.stack || '');
    files.write(resultFile, JSON.stringify(report, null, 2));
    console.log('UI_ACCESSIBILITY_RESULT ' + JSON.stringify(report));
    if (win) win.close();
    ui.finish();
    exit();
}
function later(callback) {
    setTimeout(function () { try { callback(); } catch (error) { finish(error); } }, 180);
}
var normal = 0, custom = 0, longClicks = 0, replacement = 0, floatNormal = 0, floatCustom = 0;
var callbackView = false, callbackInfo = false;
try {
    check('exports', typeof ui.replaceAccessibilityAction === 'function' && typeof ui.performClick === 'function');
    ui.layout('<vertical padding="16"><text text="无障碍点击 API 测试"/><text id="target" text="点击目标" h="80"/><text id="unused" text="无监听"/></vertical>');
    var target = ui.target;
    ui.run(function () { target.on('click', function () { normal++; }); });
    later(function () {
        check('performClick returns true', ui.performClick('target'));
        check('no listener returns false', !ui.performClick(ui.unused));
        ui.replaceAccessibilityAction(target, 'click', '选择', function (view, info) {
            custom++; callbackView = view === target;
            callbackInfo = info.action === 16 && info.label === '选择';
        });
        ui.replaceAccessibilityAction(target, 'ACTION_LONG_CLICK', '全选', function () { longClicks++; });
        ui.run(function () {
            var info = target.createAccessibilityNodeInfo(), actions = info.getActionList();
            var clickLabel = false, longLabel = false;
            for (var i = 0; i < actions.size(); i++) {
                var action = actions.get(i);
                if (action.getId() === 16 && String(action.getLabel()) === '选择') clickLabel = true;
                if (action.getId() === 32 && String(action.getLabel()) === '全选') longLabel = true;
            }
            info.recycle();
            check('native action labels', clickLabel && longLabel);
            check('accessibility click handled', target.performAccessibilityAction(16, null));
            check('accessibility long click handled', target.performAccessibilityAction(32, null));
        });
        later(function () {
            check('normal and custom routes fire once', normal === 1 && custom === 1 && longClicks === 1);
            check('callback view and info', callbackView && callbackInfo);
            ui.replaceAccessibilityAction(target, 16, '替换', function () { replacement++; });
            ui.run(function () { target.performAccessibilityAction(16, null); });
            later(function () {
                check('replacement does not call old callback', replacement === 1 && custom === 1);
                ui.replaceAccessibilityAction(target, 16, null, null);
                ui.run(function () { target.performAccessibilityAction(16, null); });
                later(function () {
                    check('removal restores native click', normal === 2 && replacement === 1 && custom === 1);
                    var rejected = 0;
                    try { ui.replaceAccessibilityAction(target, 'scroll', 'bad', function () {}); } catch (error) { rejected++; }
                    try { ui.replaceAccessibilityAction(target, 16, {}, function () {}); } catch (error) { rejected++; }
                    try { ui.replaceAccessibilityAction(target, 16, 'bad', {}); } catch (error) { rejected++; }
                    try { ui.performClick(null); } catch (error) { rejected++; }
                    check('invalid arguments rejected', rejected === 4);
                    win = floaty.rawWindow('<vertical bg="#FFFFFF"><text id="pad" text="悬浮窗目标" h="60"/></vertical>');
                    ui.run(function () { win.pad.on('click', function () { floatNormal++; }); });
                    ui.replaceAccessibilityAction(win.pad, 'click', '悬浮窗选择', function () { floatCustom++; });
                    later(function () {
                        check('floaty performClick returns true', ui.performClick(win.pad));
                        var nativePad = win.pad.__floatyView === true ? win.pad.javaView : win.pad;
                        ui.run(function () { nativePad.performAccessibilityAction(16, null); });
                        later(function () {
                            check('floaty routes fire once', floatNormal === 1 && floatCustom === 1);
                            ui.replaceAccessibilityAction(win.pad, 'click', null, null);
                            finish(null);
                        });
                    });
                });
            });
        });
    });
} catch (error) { finish(error); }
setTimeout(function () { finish(new Error('Timed out')); }, 10000);
