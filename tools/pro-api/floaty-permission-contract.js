// @engine rhino
// floaty 权限两条行为验收（对照 335 页 PDF §2.14 第 118~119 页）
// 跑法见 tools/pro-api/README.md。requestPermission() 会跳到系统悬浮窗权限页，跑完按返回键即可。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

// 1) checkPermission()：与系统 API 结果一致，且不触发请求
var systemValue = android.provider.Settings.canDrawOverlays(context);
check("checkPermission 是 boolean", typeof $floaty.checkPermission(), 'boolean');
check("checkPermission 与 Settings.canDrawOverlays 一致", $floaty.checkPermission(), systemValue);
console.log('INFO | 本应用当前是否有悬浮窗权限 | ' + systemValue);

// 2) requestPermission()：跳转系统界面，不阻塞、不等待
check("requestPermission 不抛错", (function () {
    try {
        $floaty.requestPermission();
        return true;
    } catch (e) {
        return 'threw: ' + e;
    }
})(), true);
sleep(1500);
check("requestPermission 后脚本仍在运行", true, true);
console.log('FLOATY_REQUEST_DONE');

console.log('FLOATY_SUMMARY|pass=' + pass + ' failed=' + fail);
