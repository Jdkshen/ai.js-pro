// @engine quickjs
// 系统事件监听测试
// 用途：按键 / 触摸 / 通知 / Toast 等系统事件回调
// 前置：无障碍服务（按键与触摸事件）
// 覆盖：events / 手势/输入 / timers

function assert(name, condition) {
    if (!condition) throw new Error('ASSERT_FAIL: ' + name);
    console.log('PASS:', name);
}

assert('events.observeKey', typeof events.observeKey === 'function');
assert('events.observeTouch', typeof events.observeTouch === 'function');
assert('events.observeNotification', typeof events.observeNotification === 'function');
assert('events.observeToast', typeof events.observeToast === 'function');
assert('events.observeGesture', typeof events.observeGesture === 'function');
assert('events.onKeyDown', typeof events.onKeyDown === 'function');
assert('events.onKeyUp', typeof events.onKeyUp === 'function');
assert('events.stopObserving', typeof events.stopObserving === 'function');

var timeoutId;
events.onNotification(function (event) {
    console.log('QUICKJS_SYSTEM_NOTIFICATION_OK', JSON.stringify(event));
    clearTimeout(timeoutId);
    events.stopObserving();
});

if (!events.observeNotification()) throw new Error('无法启用通知观察');
console.log('请在 10 秒内发出一条系统通知');

timeoutId = setTimeout(function () {
    events.stopObserving();
    throw new Error('等待系统通知超时');
}, 10000);
