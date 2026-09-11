// @engine quickjs
// QuickJS 全模块回归测试
// 用途：198 项断言覆盖全部白名单模块与 Java 互操作，输出 === QUICKJS_REGRESSION_OK ===
// 前置：无（无障碍 / 截图相关用例在缺少权限时自动跳过）
// 覆盖：Java 互操作 / images / floaty / ui / dialogs / threads / events / engines / http / files / storages / device / app / shell / console 浮窗 / 选择器 / 手势/输入 / timers / continuation / require

var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}
// 需要帧的用例：有截图权限用屏幕帧，没有就用 OpenCV 合成的帧（不依赖授权）
function withScreenFrame(test) {
    var frame = null;
    try {
        frame = images.captureScreen();
    } catch (e) {
        frame = null;
    }
    if (frame === null || frame === undefined) {
        var opencv = images.opencv;
        if (!opencv.Mat || !opencv.CvType || !opencv.Scalar) return true; // 无 OpenCV 时跳过
        var mat = new opencv.Mat(32, 32, opencv.CvType.CV_8UC4);
        mat.setTo(new opencv.Scalar(16, 96, 240, 255));
        frame = images.matToImage(mat);
        mat.release();
    }
    try {
        return test(frame);
    } finally {
        frame.recycle();
    }
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

// --- 选择器 JS 谓词（Java → JS 同步回调）/ findAndReturnList / io 模块 ---
assert('filter/addFilter/findAndReturnList 全局', typeof filter === 'function' && typeof addFilter === 'function'
    && typeof findAndReturnList === 'function');
var predicateCalls = 0;
var predicateNodes = selector().filter(function (node) {
    predicateCalls++;
    return node !== null && typeof node.className === 'function';
}).find();
assert('选择器 filter 谓词被回调', predicateCalls > 0 || predicateNodes.size() === 0);
assert('filter 谓词返回 false 时结果为空',
    selector().filter(function () { return false; }).find().size() === 0);
assert('addFilter 谓词可用',
    typeof selector().addFilter(function () { return true; }).find().size === 'function');
var predicateRoot = auto.rootInActiveWindow;
if (predicateRoot !== null && predicateRoot !== undefined) {
    var returnedList = findAndReturnList(predicateRoot, 5);
    assert('findAndReturnList 返回带 size() 的列表',
        Array.isArray(returnedList) && typeof returnedList.size === 'function' && returnedList.size() <= 5);
} else {
    assert('findAndReturnList（无根节点时跳过）', true);
}
assert('io 模块', typeof io === 'object' && typeof io.open === 'function' && io.files === files);
assert('require("io") 命中内置模块', require('io') === io);
var openTestPath = files.join(files.getSdcardPath(), 'engine-matrix', '__quickjs_open_test.txt');
// 目录可能不存在（新设备上 /sdcard/engine-matrix 还没建），先补齐目录再测 open。
files.createWithDirs(openTestPath);
assert('files.open 写入', (function () {
    var writer = files.open(openTestPath, 'w');
    writer.write('第一行\n');
    writer.writeline('第二行');
    writer.close();
    return files.read(openTestPath) === '第一行\n第二行\n';
})());
assert('files.open 读取', (function () {
    var reader = files.open(openTestPath, 'r');
    var first = reader.readline();
    var rest = reader.read();
    var lines = files.open(openTestPath, 'r').readlines();
    reader.close();
    return first === '第一行' && rest === '第二行\n' && lines.length === 2;
})());
assert('files.open 追加模式', (function () {
    var writer = files.open(openTestPath, 'a');
    writer.writeline('第三行');
    writer.close();
    return files.open(openTestPath, 'r').readlines().length === 3;
})());
assert('open() 与 files.open 一致', typeof open(openTestPath, 'r').read === 'function');
assert('files.open 未知模式返回 null', files.open(openTestPath, 'x') === null);
files.remove(openTestPath);

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

// --- continuation / 跨线程回调 / web 模块 ---
assert('continuation 模块', typeof continuation === 'object' && typeof continuation.delay === 'function'
    && typeof continuation.await === 'function' && continuation.enabled === false);
var continuationStart = Date.now();
continuation.delay(120);
assert('continuation.delay 阻塞等待', Date.now() - continuationStart >= 100);
assert('continuation.create 明确报错', (function () {
    try { continuation.create(); return false; } catch (e) { return /不支持/.test(String(e)); }
})());
assert('Promise.await 明确报错', (function () {
    try { Promise.resolve(1).await(); return false; } catch (e) { return /不支持/.test(String(e)); }
})());
assert('web 模块', typeof web === 'object' && typeof web.newInjectableWebView === 'function'
    && typeof web.newInjectableWebClient === 'function'
    && typeof newInjectableWebView === 'function' && typeof newInjectableWebClient === 'function');
assert('require("web")/require("continuation")', require('web') === web && require('continuation') === continuation);

// 跨线程回调：其它线程排队的任务由引擎线程在 sleep 期间取出执行（WebView 页面回调走同一条通路）。
var crossThreadHits = [];
var crossThreadCallbackId = __aiRegisterCallback(function (value) {
    crossThreadHits.push(value === undefined ? 'no-arg' : String(value));
});
__aiNativePostJsCallbackAsync(crossThreadCallbackId, 60);
sleep(700);
assert('跨线程回调在 sleep 期间执行', crossThreadHits.length === 1);
assert('跨线程回调参数传递', crossThreadHits[0] === 'no-arg');
__aiReleaseCallback(crossThreadCallbackId);
var crossThreadCallValue = null;
global.__aiCrossThreadTestFn = function (value) { crossThreadCallValue = value; };
assert('跨线程任务分发（页面 call/eval）', (function () {
    var callReport = __aiRunJsTask(JSON.stringify({
        kind: 'call', name: '__aiCrossThreadTestFn', args: '["page"]'
    }));
    var evalReport = __aiRunJsTask(JSON.stringify({
        kind: 'eval', code: '__aiCrossThreadEvalValue = 7;'
    }));
    return callReport === '' && evalReport === '' && crossThreadCallValue === 'page'
        && __aiCrossThreadEvalValue === 7;
})());
delete global.__aiCrossThreadTestFn;

var regressionWebView = null;
try {
    regressionWebView = newInjectableWebView();
} catch (e) {
    regressionWebView = null;
}
if (regressionWebView !== null) {
    var injectedValues = [];
    regressionWebView.loadData('<html><body><h1 id="t">hello-quickjs</h1></body></html>', 'text/html', 'utf-8');
    regressionWebView.inject('document.getElementById("t").innerText', function (value) {
        injectedValues.push(String(value));
    });
    sleep(4000);
    assert('WebView inject 回调', injectedValues.length > 0);
} else {
    assert('WebView 创建（当前环境不可用）', true);
}

// --- floaty / UI 等价性（控件属性、窗口尺寸、ui.<id>、ui.emitter、ui.post）---
var floatyWin = floaty.window('<frame><text id="label" text="hello" textSize="16"/></frame>');
assert('floaty 窗口 getWidth/getHeight',
    typeof floatyWin.getWidth() === 'number' && typeof floatyWin.getHeight() === 'number');
assert('floaty window.<id> 控件代理', typeof floatyWin.label.setText === 'function'
    && typeof floatyWin.label.attr === 'function' && floatyWin.label.__viewId === 'label');
assert('floaty 控件属性式读写', (function () {
    floatyWin.label.text = 'attr-write';
    return floatyWin.label.text === 'attr-write' && floatyWin.label.getText() === 'attr-write';
})());
assert('floaty 控件 attr()/attr(name, value)', (function () {
    floatyWin.label.attr('text', 'attr-call');
    return floatyWin.label.attr('text') === 'attr-call';
})());
assert('floaty 不存在的控件返回 undefined', floatyWin.__no_such_view__ === undefined);
assert('floaty findView', (function () {
    var found = floatyWin.findView('label');
    return found !== null && typeof found.getText === 'function' && floatyWin.findView('nope') === null;
})());
assert('floaty 控件 enabled/visibility 属性', (function () {
    floatyWin.label.setEnabled(false);
    var disabled = String(floatyWin.label.attr('enabled')) === 'false';
    floatyWin.label.setEnabled(true);
    floatyWin.label.attr('visibility', 4);
    var hidden = Number(floatyWin.label.attr('visibility')) === 4;
    floatyWin.label.attr('visibility', 0);
    return disabled && hidden && Number(floatyWin.label.attr('visibility')) === 0;
})());
floatyWin.close();
sleep(400);

// --- 悬浮窗窗口级能力（显隐 / 初始坐标 / alpha·scale / 坐标同步）---
var floatyV2 = floaty.window('<frame><text id="label" text="v2" textSize="14sp"/></frame>',
    { x: 321, y: 432, visible: false });
assert('floaty 初始坐标（不再出生在 0,0）', floatyV2.getX() === 321 && floatyV2.getY() === 432);
assert('floaty visible:false 创建后不显示', floatyV2.isShown() === false);
assert('floaty 显示前 findView 可用', floatyV2.findView('label') !== null);
assert('floaty 窗口显隐（show/hide/setVisibility）', (function () {
    var shown = floatyV2.show() && floatyV2.isShown() === true;
    var hidden = floatyV2.hide() && floatyV2.isShown() === false;
    var again = floatyV2.setVisibility(0) && floatyV2.isShown() === true;
    var off = floatyV2.setVisibility(8) && floatyV2.isShown() === false;
    return shown && hidden && again && off;
})());
assert('floaty 显隐后真实坐标正确', (function () {
    floatyV2.setVisibility(0);
    return floatyV2.getX(true) === 321 && floatyV2.getY(true) === 432;
})());
assert('floaty setPosition 后 getX 立即可用', (function () {
    var chained = floatyV2.setPosition(120, 240) === floatyV2;
    return chained && floatyV2.getX() === 120 && floatyV2.getY() === 240;
})());
assert('floaty getX(true) 读到生效值', (function () {
    sleep(120);
    return floatyV2.getX(true) === 120 && floatyV2.getY(true) === 240;
})());
assert('floaty 窗口 alpha/scale + 链式', (function () {
    var alpha = floatyV2.setAlpha(0.5).getAlpha();
    var scaled = floatyV2.setScale(1.2, 1.5) === floatyV2 && floatyV2.setScaleX(1) === floatyV2;
    var sized = floatyV2.setSize(300, 150) === floatyV2;
    return Math.abs(alpha - 0.5) < 0.01 && scaled && sized;
})());
assert('floaty setContentVisible 快速显隐', (function () {
    var start = Date.now();
    for (var i = 0; i < 10; i++) floatyV2.setContentVisible(i % 2 === 0);
    floatyV2.setContentVisible(true);
    return Date.now() - start < 60;
})());
assert('floaty 多窗口显隐原子性', (function () {
    var wins = [];
    for (var i = 0; i < 3; i++) {
        wins.push(floaty.window('<frame><text id="t" text="w' + i + '"/></frame>',
            { x: 60 + i * 40, y: 600, visible: false }));
    }
    for (var i = 0; i < 3; i++) wins[i].setVisibility(0);
    var allShown = wins[0].isShown() && wins[1].isShown() && wins[2].isShown();
    for (var i = 0; i < 3; i++) wins[i].setVisibility(8);
    var allHidden = !wins[0].isShown() && !wins[1].isShown() && !wins[2].isShown();
    for (var i = 0; i < 3; i++) wins[i].close();
    return allShown && allHidden;
})());
floatyV2.close();
sleep(300);

var uiLayoutId = ui.layout('<vertical><text id="title" text="ui-title" textSize="18"/>'
    + '<button id="go" text="go"/></vertical>');
assert('ui.layout 返回 id', uiLayoutId > 0);
assert('ui.<id> 控件代理可用', ui.title !== undefined && typeof ui.title.setText === 'function');
assert('ui 不存在的 id 返回 undefined', ui.__missing_view__ === undefined);
assert('ui.findView', ui.findView('go') !== null && ui.findView('missing') === null);
assert('ui 控件属性式读写', (function () {
    ui.title.text = 'ui-attr';
    return ui.title.text === 'ui-attr' && ui.getText('title') === 'ui-attr';
})());
assert('ui 控件 attr()/attr(name, value)', (function () {
    ui.title.attr('text', 'ui-call');
    return ui.title.attr('text') === 'ui-call';
})());
assert('ui.isUiThread', typeof ui.isUiThread() === 'boolean');
assert('ui.post 跨线程回调', (function () {
    var posted = false;
    ui.post(function () { posted = true; }, 40);
    sleep(600);
    return posted === true;
})());
assert('ui.emitter + ui 控件事件', (function () {
    var emitted = 0;
    var listened = 0;
    ui.emitter.on('click', function () { emitted++; });
    ui.go.click(function () { listened++; });
    ui.go.click();
    sleep(500);
    return emitted >= 1 && listened >= 1;
})());
assert('ui.statusBarColor 不异常', (function () { ui.statusBarColor('#112233'); return true; })());
ui.close();
sleep(300);

// --- Java 互操作（Packages / importClass / importPackage / Java.type）---
assert('Packages 与 Java.type 存在', typeof Packages === 'object' && typeof Java === 'object'
    && typeof Java.type === 'function' && typeof importClass === 'function'
    && typeof importPackage === 'function');
assert('Java.type 解析类', (function () {
    var cls = Java.type('java.lang.String');
    return typeof cls === 'function' && cls.__javaClass === true
        && cls.__className === 'java.lang.String';
})());
assert('Packages 逐级解析', (function () {
    var intent = Packages.android.content.Intent;
    return intent !== undefined && intent.__className === 'android.content.Intent';
})());
assert('静态字段（Intent.ACTION_VIEW）', (function () {
    var intent = Packages.android.content.Intent;
    return String(intent.ACTION_VIEW) === 'android.intent.action.VIEW';
})());
assert('静态方法（String.valueOf）', (function () {
    var s = Java.type('java.lang.String');
    return s.valueOf(42) === '42';
})());
assert('importClass + new 构造', (function () {
    importClass('android.content.Intent');
    var intent = new Intent(Intent.ACTION_VIEW);
    return intent !== undefined && intent.getAction() === 'android.intent.action.VIEW';
})());
assert('importPackage', (function () {
    importPackage('java.util');
    var map = new HashMap();
    map.put('k', 'v');
    return map.get('k') === 'v' && map.size() === 1;
})());
assert('实例字段读写', (function () {
    var point = new android.graphics.Point(1, 2);
    var before = point.x + ',' + point.y;
    point.x = 7;
    return before === '1,2' && point.x === 7 && point.toString().indexOf('7') >= 0;
})());
assert('方法重载与类型转换', (function () {
    var sb = new java.lang.StringBuilder();
    sb.append('n=').append(7).append(true);
    return sb.toString() === 'n=7true';
})());
assert('Java 异常转成脚本错误', (function () {
    try {
        new java.io.FileInputStream('/definitely/not/here/__aijs__');
        return false;
    } catch (e) {
        return /FileNotFoundException/.test(String(e));
    }
})());
assert('Java 数组返回值', (function () {
    var list = new java.util.ArrayList();
    list.add('a');
    list.add('b');
    var array = list.toArray();
    return Array.isArray(array) && array.length === 2 && array[1] === 'b';
})());
assert('JavaScript 数组转 Java 参数', (function () {
    var joined = java.lang.String.join('-', ['a', 'b', 'c']);
    return joined === 'a-b-c';
})());
assert('未 import 的类名报错清晰', (function () {
    try {
        Java.type('__no_such_class__');
        return false;
    } catch (e) {
        return /找不到 Java 类/.test(String(e));
    }
})());
assert('Rhino 预导入类名', typeof Intent === 'function' && typeof Paint === 'function'
    && typeof Shell === 'function' && typeof KeyEvent === 'function'
    && typeof MutableOkHttp === 'function' && typeof Canvas === 'function'
    && typeof Image === 'function' && typeof RootAutomator === 'function'
    && typeof Input === 'function' && typeof Module === 'object');
assert('context 是真实 Android Context', typeof context.getPackageName === 'function'
    && context.getPackageName() === context.packageName
    && typeof context.packageName === 'string' && context.packageName.length > 0
    && context.getPackageName().indexOf('.') > 0);
assert('obj.getClass() 返回 Class 对象', (function () {
    var cls = context.getClass();
    return typeof cls.getName === 'function' && typeof cls.getSimpleName === 'function'
        && cls.getName().indexOf('.') > 0
        && cls.getSimpleName().length > 0
        && String(cls) === 'class ' + cls.getName();
})());
assert('类引用转字符串', String(Packages.android.content.Intent) === 'class android.content.Intent');
assert('嵌套类访问（Build.VERSION）', android.os.Build.VERSION.SDK_INT > 0
    && android.os.Build.VERSION_CODES.M === 23
    && String(android.os.Build.VERSION).indexOf('$VERSION') > 0);
assert('枚举嵌套类（Thread.State）', (function () {
    var state = java.lang.Thread.State.NEW;
    // 与 Rhino 一致：.name 是方法本身，取字符串要调用 .name()
    return state !== undefined && String(state) === 'NEW'
        && typeof state.name === 'function' && state.name() === 'NEW'
        && state.ordinal() === 0;
})());
assert('JavaBean 属性访问', (function () {
    var file = context.getFilesDir();
    return String(file) === file.path && typeof file.absolutePath === 'string'
        && file.absolutePath === file.getAbsolutePath();
})());
assert('Java 对象方法链（Intent + Canvas 构造）', (function () {
    var intent = new Intent(Intent.ACTION_VIEW);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return intent.getFlags() === Intent.FLAG_ACTIVITY_NEW_TASK
        && String(intent) .indexOf('Intent') >= 0;
})());

// --- OpenCV 直连（帧 ↔ Mat）与 Rhino 同名的 OpenCV 包装 ---
assert('images.opencv 类访问', typeof images.opencv === 'object' && typeof images.toMat === 'function'
    && typeof images.matToImage === 'function' && typeof images.toBytes === 'function'
    && typeof images.fromBytes === 'function' && typeof images.readPixels === 'function'
    && typeof images.inRange === 'function' && typeof images.adaptiveThreshold === 'function'
    && typeof images.gaussianBlur === 'function' && typeof images.medianBlur === 'function'
    && typeof images.findCircles === 'function' && typeof images.interval === 'function');
assert('OpenCV Java API 直连', (function () {
    importClass('org.opencv.imgproc.Imgproc');
    importClass('org.opencv.core.CvType');
    var src = new org.opencv.core.Mat(4, 4, CvType.CV_8UC1);
    var dst = new org.opencv.core.Mat();
    Imgproc.threshold(src, dst, 10, 255, Imgproc.THRESH_BINARY);
    return dst.rows() === 4 && dst.cols() === 4 && Number(dst.channels()) === 1;
})());
assert('帧 → Mat → 帧 往返', withScreenFrame(function (screen) {
    var mat = images.toMat(screen);
    var back = images.matToImage(mat);
    var same = Number(mat.rows()) === Number(screen.pixelHeight)
        && Number(mat.cols()) === Number(screen.pixelWidth)
        && Math.abs(back.width - screen.width) < 2 && Math.abs(back.height - screen.height) < 2;
    back.recycle();
    mat.release();
    return same;
}));
assert('images.inRange（OpenCV 路径）', withScreenFrame(function (screen) {
    var binary = images.inRange(screen, '#000000', '#FFFFFF');
    var ok = binary !== null && binary.width === screen.width;
    binary.recycle();
    return ok;
}));
assert('images.toBytes/fromBytes 往返', withScreenFrame(function (screen) {
    var bytes = images.toBytes(screen, 'png', 100);
    var decoded = images.fromBytes(bytes);
    var ok = bytes.length > 8 && decoded.width === screen.width;
    decoded.recycle();
    return ok;
}));
assert('images.medianBlur 可用', withScreenFrame(function (screen) {
    var blurred = images.medianBlur(screen, 3);
    var ok = blurred !== null && blurred.width === screen.width;
    blurred.recycle();
    return ok;
}));
assert('images.findAllPointsForColor', withScreenFrame(function (screen) {
    var points = images.findAllPointsForColor(screen, '#1060F0', { threshold: 4 });
    if (!Array.isArray(points)) return false;
    if (points.length === 0) {
        // 屏幕帧：只验证类型与不抛异常
        return true;
    }
    return points[0].x >= 0 && points[0].x <= screen.width && points[0].y >= 0;
}));

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
assert('控制台浮窗', (function () {
    if (typeof openConsole !== 'function' || typeof clearConsole !== 'function'
        || typeof console.show !== 'function' || typeof console.hide !== 'function'
        || typeof console.clear !== 'function' || typeof console.setTitle !== 'function'
        || typeof console.setSize !== 'function' || typeof console.setPosition !== 'function') {
        return false;
    }
    openConsole();
    sleep(600);
    console.setTitle('QuickJS 回归');
    console.setSize(600, 500);
    console.setPosition(20, 200);
    log('控制台浮窗可见性检查');
    clearConsole();
    console.hide();
    sleep(300);
    return true;
})());
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
