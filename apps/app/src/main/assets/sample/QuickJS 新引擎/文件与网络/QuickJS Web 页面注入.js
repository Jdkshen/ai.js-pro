// @engine quickjs
// Web 页面注入
// 用途：newInjectableWebView 加载页面后注入脚本，页面侧 rhino.call/eval 回调到脚本
// 前置：需要悬浮窗权限与网络
// 覆盖：—

var view = newInjectableWebView();
view.loadData(
    '<html><head><meta charset="utf-8"></head><body>'
    + '<h1 id="title">hello-quickjs</h1>'
    + '<button id="go" onclick="rhino.call(\'pageClicked\', '
    + 'document.getElementById(\'title\').innerText)">点我</button>'
    + '</body></html>',
    'text/html', 'utf-8');
sleep(2500);

// 1) 页面 → 脚本：页面里的 rhino.call('fnName', ...args) 会调用脚本里的同名全局函数
global.pageClicked = function (text) {
    console.log('页面回调 pageClicked = ' + text);
};

// 2) 页面 → 脚本：rhino.eval('代码') 在脚本引擎里求值（可用来触发任意脚本逻辑）
global.triggerFromPage = function (source) {
    console.log('页面通过 rhino.eval 触发的脚本执行: ' + source);
};

// 3) 脚本 → 页面：读页面内容（返回值作为回调参数传回脚本引擎线程）
view.inject('document.getElementById("title").innerText', function (value) {
    console.log('页面标题 = ' + value);
});

// 4) 脚本 → 页面：修改 DOM
view.inject('document.getElementById("title").style.color = "red"; true', function (value) {
    console.log('样式修改结果 = ' + value);
});

// 5) 模拟点击按钮，触发页面里的 rhino.call
view.inject('document.getElementById("go").click(); true');
sleep(2000);

// 6) 从页面发起 rhino.eval
view.inject('rhino.eval("triggerFromPage(\'from-page\')"); true');
sleep(2000);

console.log('当前 URL = ' + view.getUrl());
view.reload();
sleep(1500);
view.stopLoading();
console.log('web 示例结束');
