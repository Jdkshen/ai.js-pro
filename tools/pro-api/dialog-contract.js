// @engine rhino
// dialog 三条行为验收（对照 335 页 PDF §2.11 第 94~95、104 页）
// 跑法见 tools/pro-api/README.md。
// 注意：setDefaultDialogType 只改类型，不弹窗；getProgress/getMaxProgress 走 dialogs.build 出来的对象。
var pass = 0, fail = 0;

function check(name, actual, expected) {
    var ok = (actual === expected);
    if (ok) { pass++; } else { fail++; }
    console.log((ok ? 'OK   ' : 'FAIL ') + '| ' + name + ' | ' + actual + (ok ? '' : '  (期望 ' + expected + ')'));
}

// 1) setDefaultDialogType：4 个合法值 + 未知值报错
check("默认类型是 app-or-overlay", $dialogs.getDefaultDialogType(), 'app-or-overlay');
var types = ['overlay', 'app', 'app-or-overlay', 'foreground-or-overlay'];
for (var i = 0; i < types.length; i++) {
    $dialogs.setDefaultDialogType(types[i]);
    check("setDefaultDialogType('" + types[i] + "') 生效", $dialogs.getDefaultDialogType(), types[i]);
}
var unknownError = '';
try {
    $dialogs.setDefaultDialogType('not-a-type');
} catch (e) {
    unknownError = '' + e;
}
check("未知类型报错", unknownError.indexOf('未知的对话框类型') >= 0, true);
$dialogs.setDefaultDialogType('app-or-overlay');

// 2) dialogs.build 出来的对话框对象：getProgress / getMaxProgress
//    （进度条用 build 的 progress 属性设置，不 show，避免弹窗打断自动化）
var dialog = $dialogs.build({
    title: '进度条契约测试',
    progress: { max: 100, horizontal: true, showMinMax: true }
});
check("dialog 有 getProgress", typeof dialog.getProgress, 'function');
check("dialog 有 getMaxProgress", typeof dialog.getMaxProgress, 'function');
check("getMaxProgress 是 100", dialog.getMaxProgress(), 100);
check("getProgress 是整数", dialog.getProgress() === parseInt(dialog.getProgress(), 10), true);
console.log('INFO | 初始 progress | ' + dialog.getProgress() + ' / ' + dialog.getMaxProgress());

// 3) 覆盖 type（不 show，只验证类型能被接受、不抛错）
try {
    var overlayDialog = $dialogs.build({ title: 'x', type: 'overlay', progress: { max: 10 } });
    check("build({type:'overlay'}) 可用", overlayDialog.getMaxProgress(), 10);
} catch (e) {
    check("build({type:'overlay'}) 可用", 'threw: ' + e, 'ok');
}
var badTypeError = '';
try {
    $dialogs.build({ title: 'x', type: 'nope' });
} catch (e) {
    badTypeError = '' + e;
}
check("build 传非法 type 会报错", badTypeError.indexOf('未知的对话框类型') >= 0, true);

console.log('DIALOG_SUMMARY|pass=' + pass + ' failed=' + fail);
