// @engine quickjs
// 主线程调度与动画
// 用途：runOnMainThread/runOnUiThread/postToMain 与 animateView、objectAnimator、window.post
// 前置：悬浮窗权限（窗口演示部分）
// 覆盖：floaty

var answer = runOnMainThread(function () { return 6 * 7; });
console.log('runOnMainThread 返回值 = ' + answer);
try {
    runOnMainThread(function () { throw new Error('故意失败'); });
} catch (error) {
    console.log('异常已回传 = ' + error.message);
}
console.log('runOnUiThread === runOnMainThread = ' + (runOnUiThread === runOnMainThread)
    + ', postToMain === runOnMainThread = ' + (postToMain === runOnMainThread));

// 2) 窗口 + 控件：真实 View API 与系统动画
var win = floaty.window(
    '<vertical padding="8"><text id="label" text="主线程调度" textSize="16sp"/></vertical>',
    { x: 260, y: 420 });

var padding = runOnMainThread(function () {
    var view = win.label.javaView;          // 真实 android.view.View
    view.setPadding(14, 14, 14, 14);
    return { padding: view.getPaddingLeft(), width: view.getWidth() };
});
console.log('主线程内读取控件状态 = ' + JSON.stringify(padding));

// 3) 三种动画写法（都会在主线程执行）
console.log('objectAnimator 一行式 = ' + objectAnimator(win.label, 'alpha', 1, 0.3, 150));
sleep(300);
console.log('animateView 一行式 = ' + (animateView(win.label, { alpha: 1, scaleX: 1.2 }, 180, 'overshoot') !== undefined));
sleep(300);
console.log('窗口 animate = ' + (win.animate({ alpha: 0.85 }, 150, 'linear') === win));
sleep(250);
win.setAlpha(1);

// 4) win.post：窗口绑定的主线程快捷方式（带延迟与返回值）
console.log('win.post = ' + win.post(function () { return win.getWidth() + 'x' + win.getHeight(); }));
console.log('win.post(延迟) = ' + win.post(function () { return 'ok'; }, 100));

win.close();
console.log('=== MAIN_THREAD_DISPATCH_DONE ===');
