// @engine quickjs
// 悬浮窗属性与事件
// 用途：控件 attr 读写、属性式赋值、窗口 getWidth/findView 与 on("click"/"touch") 监听
// 前置：悬浮窗权限
// 覆盖：floaty / ui / timers

var win = floaty.window(
    '<vertical padding="12" bg="#CC202020">' +
    '  <text id="title" text="属性与事件" textSize="16sp" textColor="#FFFFFFFF"/>' +
    '  <text id="info" text="等待交互" textSize="12sp" textColor="#FFB0BEC5"/>' +
    '  <button id="hit" text="点击我"/>' +
    '  <button id="toggle" text="开合菜单"/>' +
    '  <button id="fade" text="隐藏又显示"/>' +
    '</vertical>',
    { x: 80, y: 320, visible: false }   // 先不显示：setPosition 生效前不会在 (0,0) 闪现
);
win.exitOnClose();
win.show();

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
    // 原生动画：控件代理可以直接当 Java 参数，ObjectAnimator 一行式（内部自动走主线程）
    objectAnimator(win.info, 'alpha', 1, 0.3, 120);
    animateView(win.info, { alpha: 1, scaleX: 1.1 }, 160, 'overshoot');
});
win.hit.on('touch', function (event) {
    console.log('触摸事件: ' + JSON.stringify(event));
});

// 4) 窗口级显隐 / 透明度 / 缩放（不影响布局）
win.toggle.on('click', function () {
    var open = win.getAlpha() < 0.9;
    win.setAlpha(open ? 1 : 0.35).setScale(open ? 1 : 0.85);
    win.info.setText(open ? '菜单已展开' : '菜单已收起');
});
// 注意：控件 id 不要与窗口方法重名（show/hide/setVisibility/setSize/close/findView…），
// 否则 window.<id> 会命中的是方法而不是控件（与 ui.<id> 的规则一样）。
win.fade.on('click', function () {
    win.setContentVisible(false);           // 最快：只藏内容，~0ms
    sleep(600);
    win.setContentVisible(true);
    win.info.setText('已重新显示');
});

// 5) 窗口级能力：尺寸 / 位置 / 触摸穿透 / 拖动
//    注意：窗口宽度/高度在不写单位时按 **dp** 解析，margin 按 **px** 解析（与 Rhino 一致）——
//    尺寸建议显式写 px，例如 w="600px"，避免 3.25 倍密度下尺寸对不上。
win.setSize(600, -2);
setTimeout(function () {
    win.setDraggable(true);            // 可按住拖动
    win.setTouchable(false);           // 触摸穿透：点击落到下层（悬浮球/菜单常用）
    sleep(400);
    win.setTouchable(true);            // 恢复接收触摸
    console.log('getX/getY = ' + win.getX() + '/' + win.getY()
        + '，真实值 = ' + win.getX(true) + '/' + win.getY(true)
        + '，isShown = ' + win.isShown());
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
