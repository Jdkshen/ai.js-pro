// @engine quickjs

console.log('=== QuickJS dialogs 交互示例 ===');

dialogs.alert('AI.js Pro', '这是 QuickJS 同步对话框示例。');

var confirmed = dialogs.confirm('确认', '继续测试输入框和选择框吗？');
if (!confirmed) {
    console.log('用户取消测试');
} else {
    var name = dialogs.prompt('输入名称', 'QuickJS');
    var single = dialogs.singleChoice('选择一个引擎', ['Rhino', 'QuickJS', 'V8（未接入）'], 1);
    var multiple = dialogs.multiChoice('选择模块', ['app', 'device', 'shell', 'engines'], [0, 2]);

    console.log('输入:', name);
    console.log('单选索引:', single);
    console.log('多选索引:', JSON.stringify(multiple));
    dialogs.alert('测试结果',
            '名称: ' + name + '\n单选: ' + single + '\n多选: ' + JSON.stringify(multiple));
}
