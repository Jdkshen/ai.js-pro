// @engine rhino
// $settings 行为验收（对照 335 页 PDF §2.28 第 211 页）
// 跑法见 tools/pro-api/README.md。每个开关都做一次「读→取反→读→还原→读」往返，跑完不留改动。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

check("$settings 模块存在", typeof $settings, 'object');
check("isEnabled 是函数", typeof $settings.isEnabled, 'function');
check("setEnabled 是函数", typeof $settings.setEnabled, 'function');

// 文档列出的 3 个 key + 样例脚本在用的 2 个
var KEYS = ['stop_all_on_volume_up', 'not_show_console', 'foreground_service',
    'stable_mode', 'enable_accessibility_service_by_root'];

for (var i = 0; i < KEYS.length; i++) {
    var key = KEYS[i];
    var before = $settings.isEnabled(key);
    check(key + ' 返回 boolean', typeof before, 'boolean');
    $settings.setEnabled(key, !before);
    var flipped = $settings.isEnabled(key);
    $settings.setEnabled(key, before);
    var restored = $settings.isEnabled(key);
    check(key + ' 写入后读回一致', flipped, !before);
    check(key + ' 还原后回到原值', restored, before);
    console.log('INFO | ' + key + ' 原值 | ' + before);
}

// 与 App 侧实现交叉验证（前台服务开关有真实的 Pref 落盘）
try {
    var Pref = com.jdkshen.aijspro.Pref;
    check("foreground_service 与 Pref 一致",
        $settings.isEnabled('foreground_service'), Pref.isForegroundServiceEnabled());
    check("stable_mode 与 Pref 一致",
        $settings.isEnabled('stable_mode'), Pref.isStableModeEnabled());
} catch (e) {
    console.log('INFO | 无法直接访问 App 的 Pref 类（跳过交叉验证）| ' + e);
}

// 未知 key：应当明确报错，而不是静默返回 false
var unknownError = '';
try {
    $settings.isEnabled('definitely_not_a_setting');
} catch (e) {
    unknownError = '' + e;
}
check("未知 key 会报错", unknownError.indexOf('未知的设置项') >= 0, true);

console.log('SETTINGS_SUMMARY|pass=' + pass + ' failed=' + fail);
