// @engine rhino
// RootAutomator2（PDF §2.2.3，第 40~45 页）
//
// 本机（小米 8，ce4d2bdb）情况：**没有 root（su 不存在），但 Shizuku 服务在跑**，
// 所以：
//   - root 通道：验证「不可用时明确报错」，以及错误信息是否给出替代方案
//   - adb 通道：**真实注入验证** —— 用对话框按钮 + 坐标点击，看回调有没有被触发
// 有 root 的设备上应该补一次 root 通道的真实注入验收。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

function capture(fn) {
    try {
        return { value: fn(), error: null };
    } catch (e) {
        return { value: null, error: String(e) };
    }
}

check('RootAutomator2 是全局函数', typeof RootAutomator2, 'function');

var ra2 = new RootAutomator2({ root: true });
check('new RootAutomator2({root:true}) 返回对象', ra2 != null, true);
check('isAdb() 为 false（root 通道）', ra2.isAdb(), false);

var methods = ['tap', 'longPress', 'press', 'swipe', 'touchDown', 'touchMove', 'touchUp', 'flush', 'exit'];
var missing = [];
for (var i = 0; i < methods.length; i++) {
    if (typeof ra2[methods[i]] !== 'function') missing.push(methods[i]);
}
check('文档里的方法都在', missing.join(','), '');
check('setScreenMetrics 也在', typeof ra2.setScreenMetrics, 'function');

// tickle 一下：touchDown 必须报错（没 root），而不是返回成功
var noRoot = capture(function () { ra2.touchDown(100, 100); });
check('没有 root 时 touchDown 明确报错', noRoot.error != null, true);
check('报错信息提到 root 通道', noRoot.error != null && noRoot.error.indexOf('root') >= 0, true);
check('报错信息给出 adb 通道的替代方案', noRoot.error != null && noRoot.error.indexOf('adb') >= 0, true);
console.log('INFO | 无 root 时的报错：' + noRoot.error);

var adb = new RootAutomator2({ adb: true });
check('new RootAutomator2({adb:true}) 返回对象', adb != null, true);
check('isAdb() 为 true', adb.isAdb(), true);

var shizuku = capture(function () { adb.tap(5, 5); });
check('adb 通道可用（Shizuku 在跑）', shizuku.error, null);
if (shizuku.error != null) {
    console.log('INFO | adb 通道不可用的报错：' + shizuku.error);
}

// ---- adb 通道真实注入：点对话框按钮 ----
// ⚠️ 顺序有讲究：先点中心（正向），负向对照放在**后一个**对话框上。
// 原因：点在对话框外面会把它 dismiss 掉（实测踩过），先做负向对照会让后面的正向验证扑空。
var clicked = false;
var dialog = dialogs.build({ title: 'RootAutomator2 adb 通道', positive: '确定', negative: '取消' })
    .on('positive', function () {
        clicked = true;
    });
dialog.show();
sleep(1500);

var button = text('确定').findOnce();
check('选择器能选到对话框按钮', button != null, true);
if (button != null) {
    var bounds = button.bounds();
    var cx = Math.round((bounds.left + bounds.right) / 2);
    var cy = Math.round((bounds.top + bounds.bottom) / 2);
    console.log('INFO | 按钮中心 ' + cx + ',' + cy);

    adb.tap(cx, cy);
    sleep(800);
    check('adb.tap 点中按钮后 positive 回调被触发', clicked, true);
}
dialog.dismiss();

// 负向对照：偏 500px 的位置不能触发回调（用完这个对话框就换新的，
// 因为点在对话框外面会把它 dismiss 掉）
clicked = false;
var dialog2 = dialogs.build({ title: 'RootAutomator2 对照', positive: '确定', negative: '取消' })
    .on('positive', function () {
        clicked = true;
    });
dialog2.show();
sleep(1200);
var button2 = text('确定').findOnce();
if (button2 != null) {
    var b2 = button2.bounds();
    adb.tap(Math.round((b2.left + b2.right) / 2), Math.round((b2.top + b2.bottom) / 2) + 500);
    sleep(800);
    check('点偏的位置没触发回调（对照）', clicked, false);
} else {
    check('第二个对话框按钮能选到', false, true);
}
dialog2.dismiss();

clicked = false;
var dialog3 = dialogs.build({ title: 'RootAutomator2 press', positive: '确定', negative: '取消' })
    .on('positive', function () {
        clicked = true;
    });
dialog3.show();
sleep(1200);
var button3 = text('确定').findOnce();
if (button3 != null) {
    var b3 = button3.bounds();
    adb.press(Math.round((b3.left + b3.right) / 2), Math.round((b3.top + b3.bottom) / 2), 300);
    sleep(800);
    check('adb.press 也能点中', clicked, true);
} else {
    check('第三个对话框按钮能选到', false, true);
}
dialog3.dismiss();

// 多指数组在 adb 通道上必须明确拒绝（不静默只动一根手指）
var multi = capture(function () {
    adb.touchDown([{ x: 100, y: 100, id: 0 }, { x: 200, y: 200, id: 1 }]);
});
check('adb 通道拒绝多点触控数组', multi.error != null, true);
check('拒绝信息提到多点触控', multi.error != null && multi.error.indexOf('多点触控') >= 0, true);

// 选项校验
var both = capture(function () { new RootAutomator2({ adb: true, root: true }); });
check('adb 与 root 同时为 true 报错', both.error != null, true);

var neither = capture(function () { new RootAutomator2({ adb: false, root: false }); });
check('两个通道都不给时报错', neither.error != null, true);

// 单指数组（只有一根手指）在 adb 通道上是允许的（会被展开成单指调用）
var single = capture(function () { adb.touchDown([{ x: 100, y: 100, id: 0 }]); adb.touchUp([{ x: 100, y: 100, id: 0 }]); });
check('单指数组不再被“多点触控”拦住', single.error, null);

// exit / flush 必须安全
var closed = capture(function () { ra2.flush(); ra2.exit(); ra2.exit(); adb.flush(); adb.exit(); });
check('flush/exit 不抛异常', closed.error, null);

console.log('ROOTAUTOMATOR2_SUMMARY|pass=' + pass + ' failed=' + fail);
