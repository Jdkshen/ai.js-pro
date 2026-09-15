// @engine rhino
// $power_manager 行为验收（对照 335 页 PDF §2.24 第 190~191 页）
// 跑法见 tools/pro-api/README.md。最后一个用例会拉起系统"忽略电池优化"弹窗，跑完按返回键即可。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

var powerService = context.getSystemService(android.content.Context.POWER_SERVICE);

// 1) 默认包名 = 本应用；返回值与系统 API 一致
var ownPkg = context.getPackageName();
check("isIgnoringBatteryOptimizations 是 boolean",
    typeof $power_manager.isIgnoringBatteryOptimizations(), 'boolean');
check("默认包名走本应用",
    $power_manager.isIgnoringBatteryOptimizations(),
    powerService.isIgnoringBatteryOptimizations(ownPkg));
check("指定包名与系统 API 一致",
    $power_manager.isIgnoringBatteryOptimizations('com.android.settings'),
    powerService.isIgnoringBatteryOptimizations('com.android.settings'));

var alreadyIgnoring = $power_manager.isIgnoringBatteryOptimizations();
console.log('INFO | 本应用当前是否已忽略电池优化 | ' + alreadyIgnoring);

// 2) forceRequest = false 且已开启时：不请求（文档：则不执行请求）
check("forceRequest=false 不抛错", (function () {
    try {
        $power_manager.requestIgnoreBatteryOptimizations(false);
        return true;
    } catch (e) {
        return 'threw: ' + e;
    }
})(), true);

// 3) forceRequest = true：拉起系统弹窗（异步、不返回结果）
check("forceRequest=true 不抛错", (function () {
    try {
        $power_manager.requestIgnoreBatteryOptimizations(true);
        return true;
    } catch (e) {
        return 'threw: ' + e;
    }
})(), true);
console.log('POWER_REQUEST_DONE');

console.log('POWER_SUMMARY|pass=' + pass + ' failed=' + fail);
