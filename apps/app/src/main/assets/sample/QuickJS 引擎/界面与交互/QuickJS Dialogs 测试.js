"quickjs";
// Dialogs 模块测试
// 用途：dialogs.build() 的真实 builder 契约 + 事件回调 + 输入读取
// 前置：运行后按屏幕提示手动点按两次对话框
// 覆盖：dialogs
//
// 注意：QuickJS 的 dialogs.build() 返回的是**对话框句柄**（Builder），
// 不是同步 action 结果。必须 .show() 后通过 on('positive'/'dismiss'/...) 回调
// 和 dialog.getInput() 取值；把句柄当 result.action 读会永远为 undefined。

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

function finish() {
    console.log('\n=== DIALOGS 测试完成: ' + pass + ' 通过, ' + fail + ' 失败 ===');
}

// ------------------------------------------------------------------
// 1) builder 契约 + positive/any/dismiss 真实回调
// ------------------------------------------------------------------
console.log('即将弹出 build() 对话框，请点「确定」...');

var firstAction = null;
var firstAnyAction = null;
var firstBuildReturnedHandle = false;

var first = dialogs.build({
    title: 'Build 测试 1/2',
    content: '请点击「确定」，用于验证 builder.show() 的事件回调。',
    positiveText: '确定',
    negativeText: '取消',
    neutralText: '稍后'
});

// 真实契约：build() 返回句柄，不是 {action, inputText} 同步结果。
firstBuildReturnedHandle = !!first &&
    typeof first.show === 'function' &&
    typeof first.on === 'function' &&
    first.action === undefined;
assert('build 返回对话框句柄（不是同步 result.action）', firstBuildReturnedHandle);

first.on('positive', function (dialog) {
    firstAction = 'positive';
    assert('positive 回调收到 dialog', !!dialog && dialog === first);
});
first.on('any', function (action, dialog) {
    firstAnyAction = action;
});
first.on('dismiss', function (dialog) {
    // 真实 MaterialDialog 的顺序是：autoDismiss 先触发 dismiss，再由 Builder.onAny
    // 补发 positive/any。因此不能在 dismiss 回调里立即断言 action；等本轮 poll
    // 把后面的 positive/any 也派发完（setTimeout 0）再检查。
    setTimeout(function () {
        assert('build positive 事件真实触发', firstAction === 'positive');
        assert('build any 事件为 positive', firstAnyAction === 'positive');
        assert('dismiss 后 isShowing() === false', dialog.isShowing() === false);
        startInputTest();
    }, 0);
});

var firstShown = first.show();
assert('show() 返回对话框自身', firstShown === first);
assert('show() 后 isShowing() === true', first.isShowing() === true);

// ------------------------------------------------------------------
// 2) 输入框：真实 input 事件 + positive 后 getInput()
// ------------------------------------------------------------------
function startInputTest() {
    console.log('即将弹出带输入的 build() 对话框，请直接点「确认」（不要改预填文本）...');

    var inputEventText = null;
    var positiveInputText = null;

    var inputDialog = dialogs.build({
        title: '输入测试 2/2',
        content: '请直接点「确认」，验证预填文本能通过事件/句柄读到。',
        positiveText: '确认',
        negativeText: '取消',
        inputPrefill: '默认文本'
    });

    inputDialog.on('input', function (value) {
        inputEventText = value;
    });
    inputDialog.on('positive', function (dialog) {
        positiveInputText = dialog.getInput();
    });
    inputDialog.on('dismiss', function (dialog) {
        // 同上：dismiss 先到，input/positive 在同一轮 poll 的后面才派发。
        setTimeout(function () {
            assert('input 事件回调值与预填一致', inputEventText === '默认文本');
            assert('positive 后 getInput() 与预填一致', positiveInputText === '默认文本');
            assert('输入框 dismiss 后 isShowing() === false', dialog.isShowing() === false);
            finish();
        }, 0);
    });

    var inputShown = inputDialog.show();
    assert('输入框 show() 返回对话框自身', inputShown === inputDialog);
    assert('输入框 show() 后 isShowing() === true', inputDialog.isShowing() === true);
}
