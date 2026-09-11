// @engine quickjs
// 悬浮窗属性与事件
// 用途：控件 attr 读写、属性式赋值、窗口 getWidth/findView 与 on("click"/"touch") 监听
// 前置：悬浮窗权限
// 覆盖：floaty / timers

var win = floaty.window(
    '<vertical padding="12" bg="#CC202020">' +
    '  <text id="title" text="属性与事件" textSize="16sp" textColor="#FFFFFFFF"/>' +
    '  <text id="info" text="等待交互" textSize="12sp" textColor="#FFB0BEC5"/>' +
    '  <button id="hit" text="点击我"/>' +
    '</vertical>'
);
win.exitOnClose();

// 1) attr(name, value) 写、attr(name) 读；属性式赋值等价
win.title.attr('textSize', '18sp');
win.info.text = '窗口宽度 ' + win.getWidth();      // 属性式写入
win.info.attr('textColor', '#FF80CBC4');
console.log('title.attr("text") = ' + win.title.attr('text'));
console.log('info.text = ' + win.info.text);

// 2) findView 与不存在的控件
console.log('findView("hit") = ' + (win.findView('hit') !== null));
console.log('win.__missing__ = ' + win.__missing__);

// 3) on("click") / on("touch")：事件回到脚本引擎线程
var clicks = 0;
win.hit.on('click', function () {
    clicks++;
    win.info.setText('点击 ' + clicks + ' 次');
    console.log('按钮点击 ' + clicks);
});
win.hit.on('touch', function (event) {
    console.log('触摸事件: ' + JSON.stringify(event));
});

// 4) 窗口级能力：尺寸 / 位置 / 可触摸 / 调整模式
win.setSize(600, -2);
win.setPosition(80, 320);
setTimeout(function () {
    win.setTouchable(true);
    console.log('getX/getY = ' + win.getX() + '/' + win.getY() + ', 可触摸 = true');
    win.info.setText('3 秒后自动关闭');
}, 3000);

// 5) 3 秒后收尾（onClose 回调在窗口关闭时触发）
win.onClose(function () {
    console.log('窗口已关闭，总点击次数 ' + clicks);
});
setTimeout(function () {
    win.close();
}, 6000);
sleep(7000);
console.log('示例结束');
