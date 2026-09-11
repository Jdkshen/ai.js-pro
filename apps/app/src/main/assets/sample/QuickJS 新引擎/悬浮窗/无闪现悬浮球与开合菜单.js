// @engine quickjs
// 无闪现悬浮球与开合菜单
// 用途：悬浮球 + 环形菜单的骨架：创建即定位（不再左上角闪现）、窗口级显隐、无重排的展开/收起
// 前置：悬浮窗权限
// 覆盖：floaty / timers / 手势/输入

var BALL = 96;          // 悬浮球尺寸
var ITEM = 120;         // 菜单项尺寸
var RADIUS = 260;       // 环形菜单半径
var ITEMS = ['图色', '点击', '滑动', '脚本', '设置'];

// 1) 创建即定位：visible:false + {x, y}，show() 之前不会出现在 (0,0)
//    尺寸单位：w/h 不写单位时按 dp，margin 用 px（与 Rhino 一致），所以这里显式写 px。
var ball = floaty.window(
    '<frame w="' + BALL + 'px" h="' + BALL + 'px" bg="#CC1E88E5" gravity="center">' +
    '  <text id="label" text="球" textSize="14sp" textColor="#FFFFFFFF"/>' +
    '</frame>',
    { x: 900, y: 1800, visible: false, touchable: true });
ball.show();
console.log('球位置 = ' + ball.getX() + '/' + ball.getY()
    + '，真实值 = ' + ball.getX(true) + '/' + ball.getY(true));

// 2) 菜单容器：一个窗口装全部菜单项，靠内容显隐整体开合（~0ms，不会部分隐藏）
var xml = ['<frame w="' + (RADIUS * 2 + ITEM) + 'px" h="' + (RADIUS * 2 + ITEM) + 'px">'];
for (var i = 0; i < ITEMS.length; i++) {
    var angle = Math.PI * 2 * i / ITEMS.length - Math.PI / 2;   // 从正上方开始
    var x = Math.round(RADIUS + RADIUS * Math.cos(angle));
    var y = Math.round(RADIUS + RADIUS * Math.sin(angle));
    xml.push('<button id="item' + i + '" w="' + ITEM + 'px" h="' + ITEM + 'px"'
        + ' margin="' + (x - ITEM / 2) + 'px ' + (y - ITEM / 2) + 'px 0px 0px"'
        + ' text="' + ITEMS[i] + '" textSize="12sp"/>');
}
xml.push('</frame>');
var menu = floaty.window(xml.join(''), {
    x: Math.round(ball.getX() - RADIUS - ITEM / 2 + BALL / 2),
    y: Math.round(ball.getY() - RADIUS - ITEM / 2 + BALL / 2),
    visible: false   // 先建不显示，展开时再开
});
menu.setAlpha(1);
console.log('菜单已预建，未显示 = ' + (menu.isShown() === false));

// 3) 开合：整体容器显隐 + 外层窗口 attach/detach
var open = false;
function setOpen(next) {
    if (next === open) return;
    open = next;
    if (open) {
        // 先小再弹出：系统动画（ViewPropertyAnimator）在渲染线程跑，脚本不逐帧 sleep
        menu.setAlpha(0.2).setScale(0.85);
        menu.show();
        menu.animate({ alpha: 1, scaleX: 1, scaleY: 1 }, 220, 'overshoot');
    } else {
        menu.animate({ alpha: 0.2, scaleX: 0.85, scaleY: 0.85 }, 160, 'accelerate');
        sleep(180);
        menu.hide();                            // 原子：返回时窗口已不可见
        menu.setAlpha(1).setScale(1);
    }
    ball.label.setText(open ? '收' : '球');
}

ball.label.on('click', function () {
    setOpen(!open);
    console.log('菜单 ' + (open ? '展开' : '收起') + '，isShown = ' + menu.isShown());
});
// 需要拖动悬浮球时打开调整模式（窗口级 on('touch') 属于后续批次）
ball.setAdjustEnabled(true);

for (var i = 0; i < ITEMS.length; i++) {
    (function (index) {
        menu['item' + index].on('click', function () {
            console.log('点击菜单项：' + ITEMS[index]);
            toast('选择了 ' + ITEMS[index]);
            setOpen(false);
        });
    })(i);
}

// 4) 演示：自动开合两次后关闭（真实脚本里由点击驱动）
sleep(1200);
setOpen(true);
sleep(1500);
setOpen(false);
sleep(800);
console.log('=== FLOATY_MENU_DEMO_DONE ===');
menu.close();
ball.close();
