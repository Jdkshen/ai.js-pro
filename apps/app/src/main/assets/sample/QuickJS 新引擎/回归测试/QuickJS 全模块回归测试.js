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
var worker = threads.start(function () {
    return __args.left + __args.right;
}, {left: 19, right: 23});
assert('worker.getResult', typeof worker.getResult === 'function');
assert('worker.waitForResult', typeof worker.waitForResult === 'function');
assert('worker 参数与返回值', worker.waitForResult(5000) === 42);
assert('worker 正常结束', worker.join(1000));
assert('events.on', typeof events.on === 'function');
assert('events.emit', typeof events.emit === 'function');
assert('events.once', typeof events.once === 'function');

// 事件传递测试
var eventResult = null;
events.on('regression-test', function (v) { eventResult = v; });
events.emit('regression-test', 123);
assert('events.emit/on 传递值', eventResult === 123);
events.removeAllListeners();

// --- 选择器 / UiObject（无障碍控件查找）---
assert('selector', typeof selector === 'function');
assert('text', typeof text === 'function');
assert('id', typeof id === 'function');
assert('desc', typeof desc === 'function');
assert('className', typeof className === 'function');
assert('packageName', typeof packageName === 'function');
// 当前前台就是运行器自己的界面，无障碍服务一定能看到这些窗口。
var uiNodes = packageName(String(currentPackage())).find();
assert('packageName().find() 返回集合', uiNodes !== null && typeof uiNodes.size === 'function');
assert('集合 size() 是数字', typeof uiNodes.size() === 'number');
assert('集合 get() 与 empty()', typeof uiNodes.get(0) === 'object' || uiNodes.size() === 0);
var clickableNodes = clickable(true).find();
assert('clickable(true).find() 可用', typeof clickableNodes.size() === 'number');
var anyNode = clickableNodes.size() > 0 ? clickableNodes.get(0) : uiNodes.get(0);
assert('能取到控件对象', anyNode !== null && anyNode !== undefined && typeof anyNode.bounds === 'function');
if (anyNode !== null && anyNode !== undefined) {
    var nodeRect = anyNode.bounds();
    assert('控件 bounds() 返回矩形', nodeRect !== null && !isNaN(nodeRect.width())
        && !isNaN(nodeRect.height()) && nodeRect.width() === nodeRect.right - nodeRect.left);
    assert('控件 className() 可读', typeof anyNode.className() === 'string');
    assert('控件 clickable() 可读', typeof anyNode.clickable() === 'boolean');
    assert('控件 childCount() 可读', typeof anyNode.childCount() === 'number');
    assert('控件 toString() 可读', typeof anyNode.toString() === 'string');
    assert('控件 find(selector) 可用', typeof anyNode.find(text('__none__')).size === 'function');
    assert('控件 depth()/indexInParent() 可读',
        typeof anyNode.depth() === 'number' && typeof anyNode.indexInParent() === 'number');
    assert('控件 boundsInParent() 可读',
        typeof anyNode.boundsInParent().width() === 'number' && !isNaN(anyNode.boundsInParent().width()));
    assert('控件 children() 返回集合',
        typeof anyNode.children().size === 'function' && anyNode.children().size() >= 0);
    assert('控件 parent() 可读',
        anyNode.parent() === null || typeof anyNode.parent().className === 'function');
    if (anyNode.childCount() > 0) {
        var firstChild = anyNode.child(0);
        assert('控件 child(0) 可读', firstChild !== null && firstChild !== undefined
            && typeof firstChild.className === 'function');
    }
}
var missingText = '__aijs_no_such_text_' + Date.now();
assert('findOnce 对不存在的文本返回 null', text(missingText).findOnce() === null);
assert('exists 对不存在的文本返回 false', text(missingText).exists() === false);
assert('链式 text().className().find() 返回集合',
    typeof selector().text(missingText).className('android.widget.TextView').find().size === 'function');
assert('不存在的文本 find() 为空集合', text(missingText).find().size() === 0);
assert('className 过滤器可用', typeof className('android.widget.TextView').find().size() === 'number');
assert('全局 find/findOne/exists/waitFor', typeof find === 'function' && typeof findOne === 'function'
    && typeof exists === 'function' && typeof waitFor === 'function');
assert('选择器可直接执行动作', typeof text('__none__').click === 'function'
    && typeof text('__none__').setText === 'function' && typeof text('__none__').scrollForward === 'function');
assert('bounds 过滤器可链式', typeof bounds(0, 0, 10000, 10000).find().size() === 'number');
if (clickableNodes.size() > 0) {
    // 有匹配时 untilFind/waitFor 必须立刻返回，不能把脚本挂住。
    var untilFound = clickable(true).untilFind();
    assert('untilFind 有匹配时立即返回', typeof untilFound.size === 'function' && untilFound.size() > 0);
    clickable(true).waitFor();
    assert('waitFor 有匹配时立即返回', true);
    var blocking = clickable(true).findOne(2000);
    assert('findOne(timeout) 能拿到控件', blocking !== null && blocking !== undefined);
}

console.log('\n=== 回归测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
if (fail > 0) {
    throw new Error('QuickJS 全模块回归失败: ' + fail + ' 项未通过');
}
console.log('=== QUICKJS_REGRESSION_OK ===');
