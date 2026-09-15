// @engine rhino
// automator.takeScreenshot / switchToInputMethod / headsetHook（§2.1.3.2~§2.1.3.4，第 31~32 页）
// + engines 配置的 getPath()（§2.12，第 110 页）+ 线程对象的 isAlive()（§2.29，第 215 页）
//
// 前置：无障碍服务必须已开启（takeScreenshot 走无障碍截图通道）。
//   adb shell settings put secure enabled_accessibility_services com.jdkshen.aijspro/com.stardust.autojs.core.accessibility.AccessibilityService
//   adb shell settings put secure accessibility_enabled 1
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

function info(msg) {
    console.log('INFO | ' + msg);
}

// ---------------------------------------------------------------- takeScreenshot
// force-stop 后系统重新绑定无障碍服务需要几秒，先等它就绪
for (var attempt = 0; attempt < 20 && auto.service == null; attempt++) sleep(500);
check('无障碍服务已就绪', auto.service != null, true);
check('automator.takeScreenshot 是函数', typeof automator.takeScreenshot, 'function');

var shot = null, shotError = null;
try {
    shot = automator.takeScreenshot();
} catch (e) {
    shotError = String(e);
}
if (shotError) info('截图抛出异常：' + shotError);
check('无障碍截图返回 Image', shot != null, true);

if (shot != null) {
    check('截图宽度 == device.width', shot.getWidth(), device.width);
    check('截图高度 == device.height', shot.getHeight(), device.height);
    check('截图能取像素', typeof images.pixel(shot, 10, 10), 'number');
}

// 系统限频：连续两次截图必须排队（Android 15 要求 > 333ms），不能直接抛异常
var started = Date.now();
var second = null, secondError = null;
try {
    second = automator.takeScreenshot();
} catch (e) {
    secondError = String(e);
}
if (secondError) info('第二次截图抛出异常：' + secondError);
check('连续第二次截图仍成功（按限频排队）', second != null, true);
check('两次截图耗时 >= 300ms', (Date.now() - started) >= 300, true);

// ------------------------------------------------------------- switchToInputMethod
check('automator.switchToInputMethod 是函数', typeof automator.switchToInputMethod, 'function');
check('不存在的输入法包名返回 false', automator.switchToInputMethod('com.example.not.an.ime'), false);

var imePackages = [];
try {
    var imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
    var methods = imm.getInputMethodList();
    for (var i = 0; i < methods.size(); i++) {
        var pkg = String(methods.get(i).getPackageName());
        if (imePackages.indexOf(pkg) < 0) imePackages.push(pkg);
    }
} catch (e) {
    info('枚举输入法失败：' + e);
}
info('设备上的输入法：' + imePackages.join(', '));
check('设备上至少有一个输入法', imePackages.length > 0, true);

function currentInputMethod() {
    try {
        return String(android.provider.Settings.Secure.getString(
            context.getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD));
    } catch (e) {
        return 'unknown';
    }
}

if (imePackages.length > 0) {
    var first = imePackages[0];
    var ok1 = automator.switchToInputMethod(first);
    check('切到第一个输入法返回 true', ok1, true);
    check('系统当前输入法已变成它', currentInputMethod().indexOf(first) >= 0, true);

    if (imePackages.length > 1) {
        var second_pkg = imePackages[1];
        var ok2 = automator.switchToInputMethod(second_pkg);
        check('切到第二个输入法返回 true', ok2, true);
        check('系统当前输入法跟着变更', currentInputMethod().indexOf(second_pkg) >= 0, true);
        // 收尾：切回原来的，避免影响后续用例
        automator.switchToInputMethod(first);
    }
}

// ------------------------------------------------------------------- headsetHook
check('automator.headsetHook 是函数', typeof automator.headsetHook, 'function');
var hooked = null, hookError = null;
try {
    hooked = automator.headsetHook();
} catch (e) {
    hookError = String(e);
}
if (hookError) info('headsetHook 抛出异常：' + hookError);
check('headsetHook 返回 boolean', typeof hooked, 'boolean');
info('headsetHook 返回值 ' + hooked + '（无 root/Shizuku 时走 AudioManager 媒体键降级）');

// ---------------------------------------------------------------------- getPath
var smoke = engines.execScript('cfg', 'sleep(4000)');
check('engines.execScript 返回 ScriptExecution', smoke != null, true);

var exec = engines.execScript('cfg-array', 'sleep(4000)', { path: ['/sdcard/probe/mod1', '/sdcard/probe/mod2'] });
var conf = exec.getConfig();
check('getConfig() 返回配置对象', conf != null, true);
check('getConfig().getPath 是函数', typeof conf.getPath, 'function');

var arrayPath = conf.getPath();
check('getPath() 返回数组长度 2', java.lang.reflect.Array.getLength(arrayPath), 2);
check('getPath()[0] 是传入的目录', String(arrayPath[0]), '/sdcard/probe/mod1');
check('getPath()[1] 是传入的目录', String(arrayPath[1]), '/sdcard/probe/mod2');
check('workingDirectory 取首个目录', String(conf.getWorkingDirectory()), '/sdcard/probe/mod1');

var exec2 = engines.execScript('cfg-string', 'sleep(4000)', { path: '/sdcard/probe/one' });
var conf2 = exec2.getConfig();
check('字符串形式的 path 也能读到', String(conf2.getPath()[0]), '/sdcard/probe/one');

function stopEngine(execution) {
    try {
        execution.getEngine().forceStop();
    } catch (e) {
        // 引擎还没建好时会拿到 null；它自己跑完就退出
    }
}

stopEngine(exec);
stopEngine(exec2);
stopEngine(smoke);

// ----------------------------------------------------------------------- isAlive
var thread = threads.start(function () {
    for (var i = 0; i < 40; i++) sleep(30);
});
check('threads.start 返回的对象有 isAlive', typeof thread.isAlive, 'function');
thread.waitFor();
check('运行中的线程 isAlive() 为 true', thread.isAlive(), true);
thread.join();
check('结束后的线程 isAlive() 为 false', thread.isAlive(), false);

console.log('AUTOMATOR_EXTRA_SUMMARY|pass=' + pass + ' failed=' + fail);
