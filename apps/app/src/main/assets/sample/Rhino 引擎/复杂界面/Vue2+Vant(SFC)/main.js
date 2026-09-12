// @engine rhino
// 复杂界面：Vue2 + Vant + 单文件组件（SFC）跑在 WebView 里
//
// 依赖全部随示例携带（web/vendor/ 下的 vue、vant、vue2-sfc-loader），不联网也能跑。
//
// 与原版（Auto.js Pro）的差别：
// - Pro 用 `$ui.web.jsBridge` + `autojs://sdk/v1.js` 注入页面的 $autojs SDK；
//   本引擎用 newInjectableWebClient() 提供的 `window.rhino.eval`（在脚本引擎线程同步求值）
//   实现同一套 $autojs 协议，页面侧代码（index.html / main.vue）不用改。
// - 页面里的 `rhino` 桥必须在 loadUrl / loadData 之前注册，setWebViewClient() 时引擎
//   会自动完成；若 WebView 是别的方式创建的，可手动调 webClient.attach(webView)。
// - Pro 的 `$settings`（前台服务开关）本引擎没有，示例里改成提示。
"ui";

ui.layout(
    <vertical>
        <webview id="web" w="*" h="*" />
    </vertical>
);

ui.statusBarColor('#ffffff');
const webRoot = files.join(files.cwd(), 'web');

// 把 $autojs 桥注入到页面：invoke 同步取回 JSON 结果并包成 Promise，send 是单向通知
const bridgeShim = '(function () {' +
    '  function call(fn, name, args) {' +
    '    var payload = JSON.stringify(args === undefined ? null : args);' +
    '    var code = fn + "(" + JSON.stringify(String(name)) + "," + JSON.stringify(payload) + ")";' +
    '    return window.rhino.eval(code);' +
    '  }' +
    '  window.$autojs = {' +
    '    invoke: function (name, args) {' +
    '      try {' +
    '        var raw = call("__webInvoke__", name, args);' +
    '        try { return Promise.resolve(JSON.parse(raw)); } catch (e) { return Promise.resolve(raw); }' +
    '      } catch (e) {' +
    '        return Promise.reject(e);' +
    '      }' +
    '    },' +
    '    send: function (name, args) {' +
    '      try { call("__webSend__", name, args); } catch (e) { console.error(e); }' +
    '    }' +
    '  };' +
    '})();';

const webClient = newInjectableWebClient();
ui.web.setWebViewClient(webClient);          // 引擎会在这一步就把页面桥 window.rhino 注册好
webClient.inject(bridgeShim);                 // 注入 $autojs 垫片（排队到页面加载完成后执行）
ui.web.loadUrl('file://' + webRoot + '/index.html');

// ---------------- 页面 → 脚本 的请求响应 ----------------
const handlers = {};

// 页面里 vue2-sfc-loader 的 getFile 会用它读取 web/ 目录下的 .vue 文件
handlers['fetch'] = function (args) {
    let relative = (args && typeof args === 'object') ? args.path : args;
    relative = String(relative == null ? '' : relative).replace(/^\/+/, '');
    return files.read(files.join(webRoot, relative));
};

handlers['show-log'] = function () {
    app.startActivity('console');
    return 'ok';
};

handlers['set-foreground'] = function (enabled) {
    // 本引擎没有 $settings.setEnabled('foreground_service', ...)，这里只做提示
    toastLog('页面请求设置前台服务: ' + enabled);
    return 'ok';
};

handlers['get-foreground'] = function () {
    return false;
};

global.__webInvoke__ = function (name, argsJson) {
    const handler = handlers[name];
    if (!handler) {
        console.warn('未处理的请求: ' + name);
        return JSON.stringify(null);
    }
    let args = null;
    try {
        args = JSON.parse(argsJson);
    } catch (e) {
        args = argsJson;
    }
    const result = handler(args);
    return JSON.stringify(result === undefined ? null : result);
};

// ---------------- 页面 → 脚本 的单向事件 ----------------
const listeners = {};

listeners['open-url'] = function (url) {
    app.openUrl(typeof url === 'string' ? url : (url && url.url));
};

global.__webSend__ = function (name, argsJson) {
    const listener = listeners[name];
    if (!listener) {
        console.warn('未处理的事件: ' + name);
        return;
    }
    let args = null;
    try {
        args = JSON.parse(argsJson);
    } catch (e) {
        args = argsJson;
    }
    listener(args);
};

console.warn('本方式加载的Vue效率较低，建议使用Node.js版本的cli方式');