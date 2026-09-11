// @engine quickjs
// Dialogs 模块测试
// 用途：alert / confirm / prompt / select / 单选 / 多选对话框
// 前置：需要手动点按对话框
// 覆盖：dialogs

var pass = 0, fail = 0;
function assert(name, cond) {
    if (cond) { pass++; console.log('✅ ' + name); }
    else { fail++; console.error('❌ ' + name); }
}

assert('dialogs.alert 函数存在', typeof dialogs.alert === 'function');
assert('dialogs.confirm 函数存在', typeof dialogs.confirm === 'function');
assert('dialogs.prompt 函数存在', typeof dialogs.prompt === 'function');
assert('dialogs.select 函数存在', typeof dialogs.select === 'function');
assert('dialogs.singleChoice 函数存在', typeof dialogs.singleChoice === 'function');
assert('dialogs.multiChoice 函数存在', typeof dialogs.multiChoice === 'function');
assert('dialogs.build 函数存在', typeof dialogs.build === 'function');

// 测试 dialogs.build()
console.log('即将弹出 build() 对话框...');
var result = dialogs.build({
    title: 'Build 测试',
    content: '这是一个 dialogs.build() 测试对话框。\n请选择一个操作：',
    positiveText: '确定',
    negativeText: '取消',
    neutralText: '稍后'
});
console.log('  build 结果: ' + JSON.stringify(result));
assert('build 返回对象', typeof result === 'object');
assert('build 有 action', typeof result.action === 'string');
assert('build action 是有效值', ['positive', 'negative', 'neutral', 'cancel'].indexOf(result.action) >= 0);

// 测试 dialogs.build() 带输入框
console.log('即将弹出带输入的 build() 对话框...');
var result2 = dialogs.build({
    title: '输入测试',
    content: '请输入内容：',
    positiveText: '确认',
    negativeText: '取消',
    inputPrefill: '默认文本'
});
console.log('  build 输入结果: ' + JSON.stringify(result2));
assert('build 输入有 action', typeof result2.action === 'string');
assert('build 输入有 inputText', typeof result2.inputText === 'string');
if (result2.action === 'input') {
    assert('输入值与预填一致', result2.inputText === '默认文本');
}

console.log('\n=== DIALOGS 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
