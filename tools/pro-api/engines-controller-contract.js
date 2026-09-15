// @engine rhino
// engines.startFloatingController(path[, config, options])（PDF §2.12，第 108 页，Pro 9.1.10 新增）
//
// 行为验收方式：控制器的「启动」按钮走的是和 start() 同一条路径（同一份代码），
// 所以这里直接调控制器对象的方法，并用“目标脚本有没有真的跑起来（写标记文件）”自证。
//
// 前置：悬浮窗权限（本机已授予 SYSTEM_ALERT_WINDOW）。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

function info(msg) {
    console.log('INFO | ' + msg);
}

var marker = '/sdcard/probe/ctrl-ran.txt';
var target = '/sdcard/probe/ctrl-target.js';

files.remove(marker);
files.write(target, [
    "console.log('CTRL_TARGET_START');",
    "sleep(1500);",
    "files.write('/sdcard/probe/ctrl-ran.txt', 'ran');",
    "console.log('CTRL_TARGET_DONE');"
].join('\n'));

check('engines.startFloatingController 是函数', typeof engines.startFloatingController, 'function');

var typeError = 'no-error';
try {
    engines.startFloatingController(123);
} catch (e) {
    typeError = 'error';
}
check('path 不是字符串时报错', typeError, 'error');

// ---- 默认不立即启动 ----
var controller = engines.startFloatingController(target, {}, {});
check('返回控制器对象', controller != null, true);
check('默认 runImmediately=false：未启动', controller.isRunning(), false);
check('未启动时没有标记文件', files.exists(marker), false);
check('控制器知道自己的脚本路径', String(controller.getPath()), target);

// ---- 手动启动 ----
check('start() 返回 true', controller.start(), true);
sleep(500);
check('启动中 isRunning() 为 true', controller.isRunning(), true);
check('启动中标记文件还没写', files.exists(marker), false);
sleep(2500);
check('目标脚本跑完了（标记文件存在）', files.exists(marker), true);
check('跑完后 isRunning() 回到 false', controller.isRunning(), false);

// ---- 日志开关 ----
check('toggleLog() 打开日志', controller.toggleLog(), true);
check('isLogVisible() 为 true', controller.isLogVisible(), true);
sleep(500);
check('toggleLog() 收起日志', controller.toggleLog(), false);
check('isLogVisible() 回到 false', controller.isLogVisible(), false);

// ---- runImmediately ----
files.remove(marker);
var immediate = engines.startFloatingController(target, {}, { runImmediately: true });
sleep(3000);
check('runImmediately=true 当场把脚本跑起来', files.exists(marker), true);
check('runImmediately 控制器也回到空闲', immediate.isRunning(), false);

// ---- 停止 ----
files.remove(marker);
var stopping = engines.startFloatingController(target, {}, { runImmediately: true });
sleep(500);
check('正在跑时可以停', stopping.stop(), true);
sleep(2000);
check('停掉之后标记文件不会被写出来', files.exists(marker), false);
check('重复 stop() 返回 false', stopping.stop(), false);

// ---- 关闭窗口 ----
var closed = 'ok';
try {
    controller.close();
    immediate.close();
    stopping.close();
} catch (e) {
    closed = String(e);
}
check('close() 不抛异常', closed, 'ok');

files.remove(marker);
console.log('FLOATCTRL_SUMMARY|pass=' + pass + ' failed=' + fail);
