// @engine quickjs
// App 模块测试
// 用途：启动应用、打开链接、读取应用信息
// 前置：无
// 覆盖：app

var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}

// 测试 app 基础方法
assert('app.launchPackage 函数存在', typeof app.launchPackage === 'function');
assert('app.getPackageName 函数存在', typeof app.getPackageName === 'function');
assert('app.getAppName 函数存在', typeof app.getAppName === 'function');
assert('app.openAppSetting 函数存在', typeof app.openAppSetting === 'function');
assert('app.viewFile 函数存在', typeof app.viewFile === 'function');
assert('app.editFile 函数存在', typeof app.editFile === 'function');
assert('app.uninstall 函数存在', typeof app.uninstall === 'function');
assert('app.startActivity 函数存在', typeof app.startActivity === 'function');

// 获取当前应用信息
var info = app.getAppInfo('com.android.settings');
assert('getAppInfo 返回对象', typeof info === 'object' && info !== null);
assert('getAppInfo 有 label', typeof info.label === 'string');
assert('getAppInfo 有 versionName', typeof info.versionName === 'string');
console.log('  设置应用: ' + info.label + ' v' + info.versionName);

// 获取包名
var pkg = app.getPackageName('设置');
console.log('  设置包名: ' + JSON.stringify(pkg));

console.log('\n=== APP 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
