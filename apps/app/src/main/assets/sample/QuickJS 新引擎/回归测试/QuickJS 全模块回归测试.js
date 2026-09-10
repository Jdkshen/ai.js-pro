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

// --- 分辨率适配 ---
assert('setScreenMetrics', typeof setScreenMetrics === 'function');
assert('SetScreenMetrics 别名', typeof SetScreenMetrics === 'function');
setScreenMetrics(1080, 1920);
assert('setScreenMetrics 调用不异常', true);
// 还原成真实分辨率，避免影响后面的坐标类测试。
setScreenMetrics(device.width, device.height);
assert('setScreenMetrics 可还原', true);

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
var anyNode = clickableNodes.size() > 0 ? clickableNodes.get(0) : uiNodes.get(0);if (anyNode === null || anyNode === undefined) {
    // 刚启动时窗口还没稳定，带超时的 findOne 会等到控件出现（这也是阻塞路径的实际验证）。
    anyNode = clickable(true).findOne(3000);
}assert('能取到控件对象', anyNode !== null && anyNode !== undefined && typeof anyNode.bounds === 'function');
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

// --- 顶层兼容别名 / random / sync / auto ---
assert('print/err', typeof print === 'function' && typeof err === 'function');
assert('alert/confirm/prompt/select', typeof alert === 'function' && typeof confirm === 'function'
    && typeof prompt === 'function' && typeof select === 'function');
var unitRandom = random();
assert('random()', typeof unitRandom === 'number' && unitRandom >= 0 && unitRandom < 1);
var dice = random(1, 6);
assert('random(min, max)', dice >= 1 && dice <= 6 && dice === Math.floor(dice));
var syncWrapper = sync(function (a, b) { return a + b; });
assert('sync 包装可调用', typeof syncWrapper === 'function' && syncWrapper(19, 23) === 42);
assert('setImmediate/clearImmediate', typeof setImmediate === 'function' && typeof clearImmediate === 'function');
var immediateId = setImmediate(function () {});
assert('setImmediate 返回 ID', typeof immediateId === 'number' && immediateId > 0);
clearImmediate(immediateId);
assert('timers 模块', typeof timers === 'object' && typeof timers.setTimeout === 'function'
    && typeof timers.setImmediate === 'function');
assert('waitForActivity/waitForPackage', typeof waitForActivity === 'function'
    && typeof waitForPackage === 'function');
assert('auto 对象', typeof auto === 'function' && typeof auto.waitFor === 'function'
    && typeof auto.setMode === 'function' && typeof auto.setFlags === 'function');
auto.setMode('fast');
auto.setFlags(['findOnUiThread']);
assert('auto.setMode/setFlags 调用不异常', true);
auto.setMode('normal');
auto.setFlags([]);
assert('auto 模式可还原', true);
assert('auto.service 判空语义', auto.service !== undefined);
var autoRoot = auto.rootInActiveWindow;
assert('auto.rootInActiveWindow 可读',
    autoRoot === null || typeof autoRoot.className === 'function');
assert('powerDialog/splitScreen', typeof powerDialog === 'function' && typeof splitScreen === 'function');
assert('app 快捷别名', typeof launch === 'function' && typeof launchApp === 'function'
    && typeof launchPackage === 'function' && typeof openAppSetting === 'function'
    && typeof getAppName === 'function' && typeof getPackageName === 'function'
    && typeof open === 'function');
assert('选择器动作全局形式', typeof scrollForward === 'function' && typeof setText === 'function'
    && typeof copy === 'function' && typeof collapse === 'function' && typeof contextClick === 'function');
assert('顶层 select（对话框语义）', typeof select === 'function');

// --- 手势 / 输入 / RootShell 按键助手 ---
assert('gesture/gestureAsync', typeof gesture === 'function' && typeof gestureAsync === 'function');
assert('gestures/gesturesAsync', typeof gestures === 'function' && typeof gesturesAsync === 'function');
assert('input', typeof input === 'function');
assert('RootShell 按键助手', typeof KeyCode === 'function' && typeof Tap === 'function'
    && typeof Swipe === 'function' && typeof Screencap === 'function' && typeof Text === 'function'
    && typeof Home === 'function' && typeof Back === 'function' && typeof Power === 'function'
    && typeof Up === 'function' && typeof Down === 'function' && typeof Left === 'function'
    && typeof Right === 'function' && typeof OK === 'function' && typeof VolumeUp === 'function'
    && typeof VolumeDown === 'function' && typeof Menu === 'function' && typeof Camera === 'function');
home();
sleep(800);
var gestureX = Math.round(device.width / 2);
var gestureFrom = Math.round(device.height * 0.7);
var gestureTo = Math.round(device.height * 0.5);
assert('gesture 真实滑动', typeof gesture(200, [gestureX, gestureFrom], [gestureX, gestureTo]) === 'boolean');
assert('gestures 真实滑动', typeof gestures([0, 200, [gestureX, gestureFrom], [gestureX, gestureTo]]) === 'boolean');

// --- 内置模块：crypto / zips / util / automator / context / rawInput ---
assert('crypto.md5', crypto.md5('abc') === '900150983cd24fb0d6963f7d28e17f72');
assert('crypto.sha256', crypto.sha256('abc')
    === 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
assert('crypto.hmacSha256', crypto.hmacSha256('data', 'key').length === 64);
assert('crypto base64 往返', crypto.base64Decode(crypto.base64Encode('AI.js Pro')) === 'AI.js Pro');
assert('files.join', files.join(files.getSdcardPath(), 'engine-matrix', 'x.txt')
    === files.getSdcardPath() + '/engine-matrix/x.txt');
assert('zips 压缩/列表/解压', (function () {
    var base = files.join(files.getSdcardPath(), 'engine-matrix');
    var src = files.join(base, 'zip-src');
    var zipPath = files.join(base, 'quickjs-test.zip');
    var out = files.join(base, 'zip-out');
    var helloFile = files.join(src, 'hello.txt');
    var nestedFile = files.join(src, 'nested', 'inner.txt');
    if (files.exists(zipPath)) files.remove(zipPath);
    if (files.exists(out)) files.remove(out);
    // files.ensureDir 确保的是「所在文件夹」，写文件用 createWithDirs 更直接。
    files.createWithDirs(helloFile);
    files.createWithDirs(nestedFile);
    files.write(helloFile, 'hello-zip');
    files.write(nestedFile, 'inner');
    var zipped = zips.zip(src, zipPath) && files.exists(zipPath);
    var entries = zips.list(zipPath);
    var listed = Array.isArray(entries) && entries.length > 0;
    var unzipped = zips.unzip(zipPath, out)
        && files.exists(files.join(out, 'hello.txt'))
        && files.read(files.join(out, 'hello.txt')) === 'hello-zip';
    return zipped && listed && unzipped;
})());
assert('util 判定/格式化', util.isFunction(function () { }) && !util.isString(1) && util.isString('a')
    && util.isEmpty([]) && !util.isEmpty({ a: 1 }) && util.size([1, 2, 3]) === 3
    && util.format('%s=%d/%j', 'x', 7, [1, 2]) === 'x=7/[1,2]'
    && util.join(['a', 'b'], '-') === 'a-b' && util.isArray(util.range(3)));
assert('automator 模块', typeof automator.click === 'function' && typeof automator.swipe === 'function'
    && typeof automator.gesture === 'function' && typeof automator.gestures === 'function'
    && typeof automator.input === 'function' && typeof automator.setMode === 'function');
assert('context 模块', typeof context.getPackageName() === 'string' && context.getPackageName().length > 0
    && typeof context.getFilesDir().getAbsolutePath() === 'string'
    && String(context.getFilesDir()) === context.getFilesDir().path);
assert('rawInput 模块', typeof rawInput.keyevent === 'function' && typeof rawInput.text === 'function'
    && typeof rawInput.tap === 'function' && typeof rawInput.swipe === 'function'
    && typeof rawInput.press === 'function');
assert('运行时状态全局', typeof isRunning === 'function' && typeof notStopped === 'function'
    && typeof isStopped === 'function' && typeof stop === 'function'
    && typeof requiresApi === 'function' && typeof requiresAutojsVersion === 'function'
    && isRunning() === true && isStopped() === false && isRunning === notStopped);
assert('loop/isShuttingDown', typeof loop === 'function' && loop() === undefined
    && typeof isShuttingDown === 'function' && isShuttingDown() === false);
assert('requiresApi/requiresAutojsVersion', (function () {
    requiresApi(24);
    requiresAutojsVersion('1.0.0');
    var threwOnApi = false;
    try { requiresApi(99); } catch (e) { threwOnApi = true; }
    var threwOnVersion = false;
    try { requiresAutojsVersion('99.0.0'); } catch (e) { threwOnVersion = true; }
    return threwOnApi && threwOnVersion;
})());
assert('require 内置模块', require('crypto') === crypto && require('zips') === zips
    && require('util') === util && require('automator') === automator
    && require('context') === context && require('rawInput') === rawInput
    && require('sqlite') === sqlite);
assert('sqlite 建表/插入/查询/更新/删除', (function () {
    var db = sqlite.open('quickjs-regression');
    try {
        db.exec('DROP TABLE IF EXISTS regression');
        db.exec('CREATE TABLE regression (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, score REAL)');
        var inserted = db.insert('regression', { name: '甲', score: 1.5 });
        var second = db.insert('regression', { name: '乙', score: 2.5 });
        var all = db.select('SELECT name, score FROM regression ORDER BY id');
        var updated = db.update('regression', { score: 9.5 }, 'name = ?', ['甲']);
        var after = db.select('SELECT score FROM regression WHERE name = ?', ['甲']);
        var deleted = db.delete('regression', 'name = ?', ['乙']);
        var rest = db.select('SELECT id FROM regression');
        return inserted.rowsAffected === 1 && second.insertId > 0
            && all.rows.length === 2 && all.rows[0].name === '甲'
            && updated.rowsAffected === 1 && after.rows[0].score === 9.5
            && deleted.rowsAffected === 1 && rest.rows.length === 1;
    } finally {
        db.close();
    }
})());
assert('sqlite 事务回滚', (function () {
    var db = sqlite.open('quickjs-regression');
    try {
        var threw = false;
        try {
            db.transaction(function (tx) {
                tx.insert('regression', { name: '丙' });
                throw new Error('rollback-probe');
            });
        } catch (e) { threw = true; }
        var rows = db.select("SELECT name FROM regression WHERE name = '丙'");
        db.transaction(function (tx) { tx.insert('regression', { name: '丁' }); });
        var committed = db.select("SELECT name FROM regression WHERE name = '丁'");
        return threw && rows.length === 0 && committed.rows.length === 1;
    } finally {
        db.close();
    }
})());

console.log('\n=== 回归测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
if (fail > 0) {
    throw new Error('QuickJS 全模块回归失败: ' + fail + ' 项未通过');
}
console.log('=== QUICKJS_REGRESSION_OK ===');
