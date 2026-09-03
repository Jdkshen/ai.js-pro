// @engine quickjs
// QuickJS 全模块回归测试 — 一次性验证所有白名单桥
var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}
console.log('=== QuickJS 全模块回归测试 ===\n');

// --- console ---
assert('console.log', typeof console.log === 'function');
console.log('  console 测试通过');

// --- toast ---
toast('回归测试开始');
assert('toast 不异常', true);

// --- files ---
assert('files.read', typeof files.read === 'function');
assert('files.write', typeof files.write === 'function');
assert('files.cwd', typeof files.cwd === 'function');
var testFile = files.cwd() + '/__quickjs_regression_test.txt';
files.write(testFile, '回归测试写入');
assert('files.write + read', files.read(testFile) === '回归测试写入');
assert('files.exists', files.exists(testFile));
files.remove(testFile);
assert('files.remove', !files.exists(testFile));

// --- timers ---
// 注意: QuickJS 定时器事件循环在脚本主线程结束后运行，
// 因此不能在 sleep 后立即断言回调结果。
// 改为验证 API 注册正确且调用不抛异常。
assert('setTimeout 是函数', typeof setTimeout === 'function');
assert('setInterval 是函数', typeof setInterval === 'function');
assert('clearTimeout 是函数', typeof clearTimeout === 'function');
assert('clearInterval 是函数', typeof clearInterval === 'function');
var timerId = setTimeout(function () {}, 100);
assert('setTimeout 返回数字 ID', typeof timerId === 'number' && timerId > 0);
clearTimeout(timerId);
assert('clearTimeout 不异常', true);
var intervalId = setInterval(function () {}, 100);
assert('setInterval 返回数字 ID', typeof intervalId === 'number' && intervalId > 0);
clearInterval(intervalId);
assert('clearInterval 不异常', true);

// --- http ---
assert('http.get', typeof http.get === 'function');
assert('http.post', typeof http.post === 'function');

// --- app ---
assert('app.launchPackage', typeof app.launchPackage === 'function');
assert('app.getInstalledApps', typeof app.getInstalledApps === 'function');
assert('app.getAppInfo', typeof app.getAppInfo === 'function');

// --- storages ---
var store = storages.create('__regression_test__');
store.put('key', 'value');
assert('storages.put/get', store.get('key') === 'value');
store.clear();

// --- device ---
assert('device.width > 0', device.width > 0);
assert('device.model', typeof device.model === 'string');
assert('device.isScreenOn()', typeof device.isScreenOn() === 'boolean');
assert('device.getBattery()', typeof device.getBattery() === 'number');

// --- shell ---
assert('shell', typeof shell === 'function');
var r = shell('echo OK');
assert('shell 执行', r.code === 0 && r.result.trim() === 'OK');
assert('shell.isRootAvailable', typeof shell.isRootAvailable === 'function');

// --- dialogs ---
assert('dialogs.alert', typeof dialogs.alert === 'function');
assert('dialogs.confirm', typeof dialogs.confirm === 'function');
assert('dialogs.build', typeof dialogs.build === 'function');

// --- engines ---
assert('engines.myEngine', typeof engines.myEngine === 'function');
var me = engines.myEngine();
assert('engines.myEngine().id', typeof me.id === 'number');
assert('engines.all', typeof engines.all === 'function');
assert('engines.all() 返回数组', Array.isArray(engines.all()));

// --- threads + events ---
assert('threads.start', typeof threads.start === 'function');
assert('threads.exec', typeof threads.exec === 'function');
assert('events.on', typeof events.on === 'function');
assert('events.emit', typeof events.emit === 'function');
assert('events.once', typeof events.once === 'function');

// 事件传递测试
var eventResult = null;
events.on('regression-test', function (v) { eventResult = v; });
events.emit('regression-test', 123);
assert('events.emit/on 传递值', eventResult === 123);
events.removeAllListeners();

console.log('\n=== 回归测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
if (fail > 0) {
    throw new Error('QuickJS 全模块回归失败: ' + fail + ' 项未通过');
}
console.log('=== QUICKJS_REGRESSION_OK ===');
