// Rhino 回归测试 — 验证 QuickJS 改动没有破坏 Rhino 引擎功能
// 注意：此脚本使用 Rhino 引擎（默认），不需要 // @engine quickjs

var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('  PASS: ' + name); }
    else { fail++; console.error('  FAIL: ' + name); }
}

console.log('=== Rhino Regression Test ===');

// Basic APIs
assert('toast function exists', typeof toast === 'function');
assert('sleep function exists', typeof sleep === 'function');
assert('log function exists', typeof log === 'function');
assert('console.log exists', typeof console.log === 'function');
assert('click function exists', typeof click === 'function');
assert('setClip function exists', typeof setClip === 'function');
assert('getClip function exists', typeof getClip === 'function');
assert('currentPackage function exists', typeof currentPackage === 'function');
assert('currentActivity function exists', typeof currentActivity === 'function');
assert('shell function exists', typeof shell === 'function');
assert('events object exists', typeof events === 'object' || typeof events === 'function');

// files module
assert('files.read exists', typeof files.read === 'function');
assert('files.write exists', typeof files.write === 'function');
assert('files.exists exists', typeof files.exists === 'function');
assert('files.cwd exists', typeof files.cwd === 'function');

var testFile = files.cwd() + '/rhino_regression_test.txt';
files.write(testFile, 'Rhino test content');
assert('files.write + read', files.read(testFile) === 'Rhino test content');
assert('files.exists', files.exists(testFile));
files.remove(testFile);
assert('files.remove', !files.exists(testFile));

// storages
assert('storages.create exists', typeof storages.create === 'function');
var store = storages.create('rhino_test_' + Date.now());
store.put('key', 'value');
assert('storages.put/get', store.get('key') === 'value');
store.clear();

// device
assert('device.width exists', typeof device.width === 'number');
assert('device.model exists', typeof device.model === 'string');

// images
assert('images.captureScreen exists', typeof images.captureScreen === 'function');
assert('images.read exists', typeof images.read === 'function');
assert('images.findColor exists', typeof images.findColor === 'function');

// timers
assert('setTimeout exists', typeof setTimeout === 'function');
assert('setInterval exists', typeof setInterval === 'function');

// YOLO
assert('yolo.isAvailable exists', typeof yolo.isAvailable === 'function');

// dialogs
assert('dialogs.alert exists', typeof dialogs.alert === 'function');
assert('dialogs.confirm exists', typeof dialogs.confirm === 'function');

// engines
assert('engines.execScript exists', typeof engines.execScript === 'function');

// Java interop (Rhino-specific)
assert('java.lang.String exists', typeof java.lang.String === 'function');

console.log('\n=== Rhino Regression Result: ' + pass + ' passed, ' + fail + ' failed ===');
if (fail > 0) {
    throw new Error('Rhino 回归失败: ' + fail + ' 项未通过');
}
console.log('RHINO_REGRESSION_OK');
