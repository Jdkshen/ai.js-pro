// @engine quickjs
// UI 控件代理与事件
// 用途：ui.<id> 控件代理、attr 读写、ui.emitter 事件与 ui.post
// 前置：悬浮窗权限
// 覆盖：ui

ui.layout([
    '<vertical padding="16">',
    '  <text id="title" text="QuickJS UI" textSize="20sp" textColor="#FF2196F3"/>',
    '  <text id="counter" text="点击次数：0" textSize="14sp"/>',
    '  <button id="hit" text="点我 +1"/>',
    '  <button id="quit" text="关闭"/>',
    '</vertical>'
].join(''));

// 2) ui.<id> 控件代理：方法式与属性式两种写法等价
//    注意：id 不要与 ui 自己的方法重名（layout/close/findView/post/run…），否则会命中方法而不是控件。
ui.title.setText('控件代理示例');
ui.title.attr('textSize', '22sp');
console.log('title = ' + ui.title.getText() + ' / ' + ui.title.attr('text'));

// 3) 事件：控件自身 click(fn) 与 ui.emitter 转发（Rhino 里来自 UI Activity 的 EventEmitter）
var clicks = 0;
ui.emitter.on('click', function (view) {
    console.log('ui.emitter 收到 click: ' + (view && view.__viewId));
});
ui.hit.click(function () {
    clicks++;
    ui.counter.setText('点击次数：' + clicks);
});
ui.quit.click(function () {
    console.log('关闭界面');
    ui.close();
});

// 4) ui.findView 与不存在的 id
console.log('findView("hit") = ' + (ui.findView('hit') !== null));
console.log('ui.__no_such_view__ = ' + ui.__no_such_view__);

// 5) ui.post：延迟后在脚本引擎线程执行（跨线程回调通路）
ui.post(function () {
    console.log('ui.post 回调执行，isUiThread = ' + ui.isUiThread());
    ui.counter.attr('textColor', '#FFFF5722');
}, 200);

// 6) statusBarColor 与显式关闭
ui.statusBarColor('#112233');
sleep(6000);
ui.close();
console.log('界面已关闭');
