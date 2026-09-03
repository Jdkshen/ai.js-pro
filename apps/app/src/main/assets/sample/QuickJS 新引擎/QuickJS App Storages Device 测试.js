// @engine quickjs
// QuickJS app / storages / device 模块测试

console.log('=== QuickJS 新模块测试 ===');

// ---- device 模块 ----
console.log('【device 模块】');
console.log('品牌:', device.brand);
console.log('型号:', device.model);
console.log('制造商:', device.manufacturer);
console.log('Android:', device.release, '(SDK', device.sdkInt + ')');
console.log('主板:', device.board);
console.log('硬件:', device.hardware);
console.log('屏幕:', device.width, 'x', device.height);
console.log('显示:', device.display);
console.log('产品:', device.product);
console.log('Build ID:', device.buildId);
console.log('屏幕亮:', device.isScreenOn());
console.log('电量:', device.getBattery().toFixed(1) + '%');
console.log('充电中:', device.isCharging());
console.log('亮度:', device.getBrightness());
console.log('亮度模式:', device.getBrightnessMode());
console.log('');

// 回归：device 扩展不能污染所有普通对象的原型。
console.log('对象原型未污染:', ({ }).isCharging === undefined);

// ---- storages 模块 ----
console.log('【storages 模块】');
var store = storages.create('quickjs_test');
console.log('创建存储: quickjs_test');

// 写入
store.put('name', 'QuickJS 测试');
store.put('counter', 42);
store.put('pi', 3.14159);
store.put('nested', { a: 1, b: [2, 3] });
console.log('写入 4 个键值对');

// 读取
console.log('name:', store.get('name'));
console.log('counter:', store.get('counter'));
console.log('pi:', store.get('pi'));
console.log('nested:', JSON.stringify(store.get('nested')));

// 默认值
console.log('不存在的键 (默认值):', store.get('notExist', '默认值'));

// contains
console.log('contains name:', store.contains('name'));
console.log('contains notExist:', store.contains('notExist'));

// remove
store.remove('counter');
console.log('删除 counter 后:', store.contains('counter'));

// 跨存储隔离
var store2 = storages.create('quickjs_test_other');
store2.put('key', 'other');
console.log('存储隔离: store get key =', store.get('key', '(无)'), ', store2 get key =', store2.get('key'));

// clear
store.clear();
store2.clear();
store.put('afterClear', true);
console.log('clear 后仍可继续使用:', store.get('afterClear') === true);
store.clear();
console.log('已清空两个存储');
console.log('');

// ---- app 模块 ----
console.log('【app 模块】');

// 获取当前应用信息
try {
    var selfInfo = app.getAppInfo('org.autojs.autojs');
    console.log('当前应用:', selfInfo.label, 'v' + selfInfo.versionName);
} catch (e) {
    console.log('getAppInfo 失败:', e.message || e);
}

// 获取已安装应用数量
try {
    var apps = app.getInstalledApps();
    console.log('已安装应用数:', apps.length);
    // 显示前 5 个
    for (var i = 0; i < Math.min(5, apps.length); i++) {
        console.log('  ' + apps[i].packageName + ' → ' + apps[i].label);
    }
} catch (e) {
    console.log('getInstalledApps 失败:', e.message || e);
}

console.log('名称转包名:', app.getPackageName('AI.js Pro'));
console.log('包名转名称:', app.getAppName('org.autojs.autojs'));

console.log('\n=== 测试完成 ===');
