// @engine quickjs
// 圆形触摸穿透
// 用途：圆形/圆角悬浮窗的正确做法：view 别名、clipToOutline 轮廓裁剪，以及实测有效的“整窗穿透 + 独立小窗口”方案
// 前置：悬浮窗权限
// 覆盖：floaty / timers

// 0) 能力探测：按区域输入（圆外精确穿透）依赖系统隐藏 API，实测多数设备不可用
var info = floaty.touchRegionInfo();
console.log('按区域输入能力 = ' + JSON.stringify(info));

// 1) 圆形视觉 + 轮廓裁剪：XML 直接写 cardCornerRadius="50%" + clipToOutline="true"
var BALL = 200;
var ball = floaty.window(
    '<card id="c" w="' + BALL + 'px" h="' + BALL + 'px"'
    + ' cardCornerRadius="50%" cardBackgroundColor="#F01E88E5" clipToOutline="true"/>',
    { x: 200, y: 1200 });
sleep(600);
console.log('球窗口 = ' + ball.getWidth() + '×' + ball.getHeight()
    + '，触摸区域 = ' + JSON.stringify(ball.getTouchRegion()));
console.log('win.view / 控件 view 都是真 View = '
    + (ball.view !== undefined && ball.c.view !== undefined));
runOnMainThread(function () { ball.view.setClipToOutline(true); });
ball.c.onTouch(function (ev) {
    if (ev.getAction() === ev.ACTION_UP) {
        console.log('球内抬起 raw=(' + ev.getRawX() + ', ' + ev.getRawY() + ')');
    }
});

// 2) 圆外“穿透”的正确做法（实测有效）：容器窗口 setTouchable(false) 整窗不吃触摸，
//    可点的菜单项各自独立小窗口 —— 容器整块矩形都不再拦截触摸。
var RING = 520;
var ITEM = 120;
var ITEMS = ['图色', '点击', '滑动', '脚本', '设置'];
var baseX = 560;
var baseY = 1200;
var container = floaty.window(
    '<frame w="' + RING + 'px" h="' + RING + 'px" bg="#00000000"/>', { x: baseX, y: baseY });
container.setTouchable(false);      // ← 关键：整窗穿透（点击落到下层 App / 下层小窗口）
container.setAlpha(0.6);            // 透明度 ≤0.8 更稳妥（Android S+ 的 SAW 遮挡规则）
console.log('菜单容器 touchable = ' + container.getTouchRegion().touchable);

var items = [];
for (var i = 0; i < ITEMS.length; i++) {
    var angle = Math.PI * 2 * i / ITEMS.length - Math.PI / 2;
    var cx = baseX + RING / 2 + Math.cos(angle) * (RING / 2 - ITEM / 2);
    var cy = baseY + RING / 2 + Math.sin(angle) * (RING / 2 - ITEM / 2);
    var item = floaty.window(
        '<card id="c" w="' + ITEM + 'px" h="' + ITEM + 'px" cardCornerRadius="50%"'
        + ' cardBackgroundColor="#F0E91E63" clipToOutline="true">'
        + '<text text="' + ITEMS[i] + '" textSize="12sp" textColor="#FFFFFFFF" gravity="center"/>'
        + '</card>',
        { x: Math.round(cx - ITEM / 2), y: Math.round(cy - ITEM / 2) });
    item.c.click((function (name) {
        return function () { console.log('点击菜单项：' + name); };
    })(ITEMS[i]));
    items.push(item);
}
console.log('菜单项窗口数 = ' + items.length + '（每项只遮挡自身小方块）');

// 3) 非 XML 场景：轮廓形状 API（只影响绘制与裁剪，不改窗口输入区域）
var panel = floaty.window('<frame w="300px" h="200px" bg="#CC111111"/>', { x: 120, y: 1900 });
panel.setOutlineShape('roundRect', 24);
console.log('面板轮廓 = ' + panel.__outlineShape + '，圆角 = ' + panel.__outlineRadius);
panel.setCornerRadius('50%');       // 50% → 圆形轮廓（正方形控件就是圆）
console.log('改成圆后轮廓 = ' + panel.__outlineShape);

// 4) 只有设备支持按区域输入时，setShape/setTouchShape/setTouchableRegion 才会真正生效
if (info.supported) {
    panel.setShape('circle');
    console.log('已按圆形收缩窗口输入区域：' + JSON.stringify(panel.getTouchRegion()));
} else {
    panel.setOutlineShape('roundRect', 24);   // 退回视觉圆角
    console.log('按区域输入不可用 → 用“容器整窗穿透 + 独立小窗口”（见上面的环形菜单）');
}

// 5) 收尾
sleep(8000);
for (var i = 0; i < items.length; i++) items[i].close();
container.close();
panel.close();
ball.close();
console.log('=== FLOATY_CIRCLE_TOUCH_DONE ===');
