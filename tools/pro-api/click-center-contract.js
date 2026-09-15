// @engine rhino
// UiObject.clickCenter / auto.clearCache / Rect.intersect 行为验收
// （对照 335 页 PDF §2.1.3.1 第 18 页、§2.1.4 第 32~34 页）
//
// 前置：无障碍服务必须已开启（否则 clickCenter 没有手势通道）。本机测试前用
//   adb shell settings put secure enabled_accessibility_services com.jdkshen.aijspro/com.stardust.autojs.core.accessibility.AccessibilityService
//   adb shell settings put secure accessibility_enabled 1
// 打开过。
//
// clickCenter 的验证方式：脚本自己弹一个对话框，用选择器找到"确定"按钮，点它的中点，
// 再看 positive 回调有没有被触发 —— 这样能证明点击真的落在按钮上，而不只是"没报错"。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

// 1) Rect.intersect（UiObject.bounds() 返回的就是它）
var r1 = new android.graphics.Rect(0, 0, 10, 10);
var r2 = new android.graphics.Rect(5, 5, 20, 20);
check("Rect.intersect 相交返回 true", r1.intersect(r2), true);
check("Rect.intersect 自身被改成交集", r1.left + ',' + r1.top + ',' + r1.right + ',' + r1.bottom, '5,5,10,10');
var r3 = new android.graphics.Rect(100, 100, 110, 110);
check("Rect.intersect 不相交返回 false", r2.intersect(r3), false);

// 2) auto.clearCache()
check("auto.clearCache 是函数", typeof auto.clearCache, 'function');
check("清缓存前能取到根节点", auto.rootInActiveWindow != null, true);
auto.clearCache();
sleep(500);
check("清缓存后服务仍可用", auto.rootInActiveWindow != null, true);

// 3) UiObject.clickCenter()
var positiveClicked = false;
var dialog = dialogs.build({ title: 'clickCenter 测试', positive: '确定', negative: '取消' })
    .on('positive', function () {
        positiveClicked = true;
        console.log('INFO | positive 回调被触发');
    });
dialog.show();
sleep(2000);

var button = text('确定').findOnce();
check("选择器能选到对话框按钮", button != null, true);

if (button != null) {
    var bounds = button.bounds();
    console.log('INFO | 按钮 bounds | ' + bounds.left + ',' + bounds.top + ',' + bounds.right + ',' + bounds.bottom);
    check("bounds() 返回的是 Rect", typeof bounds.intersect, 'function');
    var result = button.clickCenter();
    check("clickCenter 返回 boolean", typeof result, 'boolean');
    console.log('INFO | clickCenter 返回 | ' + result + '（中点 ' + bounds.centerX() + ',' + bounds.centerY() + '）');
    sleep(1500);
    check("点击落在按钮上（positive 回调触发）", positiveClicked, true);
} else {
    check("点击落在按钮上（positive 回调触发）", 'no-button', true);
}

// 对照：同一套回调接线，用控件自身的 click()（无障碍 ACTION_CLICK）能不能触发
if (!positiveClicked) {
    var controlClicked = false;
    var dialog2 = dialogs.build({ title: 'click 对照', positive: '确定' })
        .on('positive', function () {
            controlClicked = true;
        });
    dialog2.show();
    sleep(1500);
    var button2 = text('确定').findOnce();
    if (button2 != null) {
        console.log('INFO | 对照 click() 返回 | ' + button2.click());
    }
    sleep(1000);
    console.log('INFO | 对照结果 | ' + (controlClicked ? 'click() 能触发回调' : 'click() 也没触发（回调接线问题）'));
    try {
        dialog2.dismiss();
    } catch (e) {
    }
}

sleep(500);
try {
    dialog.dismiss();
} catch (e) {
}

console.log('CLICKCENTER_SUMMARY|pass=' + pass + ' failed=' + fail);
