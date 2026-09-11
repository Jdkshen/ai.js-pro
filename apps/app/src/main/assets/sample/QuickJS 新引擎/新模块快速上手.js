// @engine quickjs
// QuickJS 新模块快速上手
// 用途：一页跑通 require / runtime / ui / floaty / images 的常见写法
// 前置：无（ui 与 floaty 需要悬浮窗权限）
// 覆盖：images / floaty / ui / require

var mod = require('./QuickJS 模块示例.js');          // 同目录模块（缓存）
log('require.mod = ' + mod.add(2, 3));               // 5
log('runtime.engine = ' + runtime.engine.name);      // QuickJS
log('global is object = ' + (typeof global === 'object'));

// 2) UI 界面（DynamicLayoutInflater 全控件）
ui.layout([
    '<vertical>',
    '  <text id="title" text="你好 QuickJS" textSize="24sp" textColor="#FFFFFFFF" />',
    '  <text id="sub" text="loading..." textSize="14sp" textColor="#CCFFFFFF" />',
    '  <button id="btn" text="点击我" />',
    '</vertical>'
].join('\n'));
$ui.btn.click(function () {
    $ui.sub.setText('按钮被点击了！');
});
$ui.sub.setText('UI 就绪');

// 3) 悬浮窗
var win = floaty.window({
    text: '悬浮信息',
    textSize: 16,
    backgroundColor: '#AA000000',
    x: 40, y: 600, width: 240, height: 60,
    touchable: true
});
win.setText('可拖动悬浮窗');

// 4) 图片 base64
var tiny = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
var img = images.fromBase64(tiny);
log('images.fromBase64 = ' + img.width + 'x' + img.height);

// 5) 收尾
sleep(1500);
win.close();
ui.close();
log('QuickJS 新模块演示完成');
