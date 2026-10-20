"nodejs";
// 浮窗真实View与事件(setView)
// createWindow + setViewFromXml 真实 View、enableDrag 拖动、binding.on('click') 点击更新文字，5 秒自动关闭。
const files = require('files');
const OUT = '/sdcard/脚本/_nodeapi_probe_/13_floating_setView.json';
function writeResult(value) {
    try { files.mkdir('/sdcard/脚本/_nodeapi_probe_'); } catch (ignored) { }
    try { files.write(OUT, JSON.stringify(value)); } catch (error) { console.error('写结果失败：', error); }
    console.log(JSON.stringify(value));
}

const floating = require('floating_window');
// 注意：定时器回调在 try 块外执行，window/clicks 必须在脚本顶层声明（var/let 的作用域坑）。
var window = null;
var clicks = 0;
try {
    window = floating.createWindow({});
    // setViewFromXml：主进程按 XML inflate 真实 View，字段 id 可通过 window.view.binding.<id> 访问。
    window.setViewFromXml([
        '<vertical padding="16">',
        '  <text id="title" text="Node24 浮窗示例" textSize="16" />',
        '  <text id="hint" text="拖动标题移动；点击按钮更新文字" textSize="12" />',
        '  <button id="btn" text="点我" />',
        '</vertical>'
    ].join('\n'));
    window.enableDrag('title');

    window.view.binding.btn.on('click', function () {
        clicks += 1;
        window.view.binding.btn.setText('已点击 ' + clicks + ' 次');
        console.log('浮窗按钮被点击：' + clicks);
    });
    window.show();
    console.log('浮窗已显示，5 秒后自动关闭');
} catch (error) {
    writeResult({ ok: false, api: 'floating_window.setView', error: String(error && error.stack || error) });
}

// 1.5 秒时用 host viewCommand 触发一次点击（无触摸事件也能验证按钮回调链路）。
setTimeout(function () {
    try {
        if (window) window.view.binding.btn.click();
    } catch (ignored) { }
}, 1500);

// 5 秒到点写结果并关闭；说明点击需要真实触摸或 binding.click() 才会触发。
setTimeout(function () {
    try {
        writeResult({
            ok: true, api: 'floating_window.setView',
            overlayId: window && window._overlayId || null,
            clicks,
            note: 'setViewFromXml 渲染真实 View；binding.on("click") 已注册，enableDrag("title") 已启用；程序化 binding.click() 用于无触摸验证'
        });
    } catch (error) {
        writeResult({ ok: false, api: 'floating_window.setView', error: String(error && error.stack || error) });
    }
    try { if (window) window.close(); } catch (ignored) { }
}, 5000);
