"nodejs";
/*
 * 独立 Web UI 契约探针（不依赖 project-res.apk / 不伪造 isRelease）。
 *
 * 覆盖：
 *  1. Activity.layoutXml = "<webview id=web>" 在未编译资源工程里走真实 Node Activity；
 *  2. contentView.findView('web') / contentView.binding.web 返回同一真实 WebView 门面；
 *  3. webview.loadUrl(file://...) 真正加载页面；
 *  4. webview.on('console_message') 拿到 sourceId()/lineNumber()/message()；
 *  5. 页面 $autojs.invoke('listFiles') -> Node async 文件列表 -> 页面 Promise 返回；
 *  6. webview.jsBridge.eval 同步取回页面表达式；
 *  7. webview.jsBridge.invoke -> 页面 $autojs.handle 的真实返回值；
 *  8. ui.startActivity(SecondActivity,{flags}) 启动第二个真实 Node Activity；
 *  9. ui.activityLifecycle.on('all_activities_destroyed') 在最后关闭后触发。
 *
 * 运行方式（真机）：把本文件放到一个脚本工程目录，添加 project.json（默认 Node 引擎）后运行。
 * 结果行：NODE24_WEBUI_RESULT {...}
 */
const ui = require('ui');
const fs = require('fs');
const path = require('path');

const state = {
    consoleMessages: [],
    href: null,
    listResult: null,
    goBack: null,
    reentry: false, pageError: false, evalError: false, hostError: false, event: false,
    firstAttached: false,
    contentViewHandle: null,
    windowHandle: null,
    startActivity: false,
    prematureDestroyed: false,
    allDestroyed: false,
    failure: null
};

const htmlPath = path.join(__dirname || '.', 'webui-contract.html');
const html = [
    '<!doctype html>',
    '<html><head><meta charset="utf-8"><title>webui-contract</title></head>',
    '<body><h1 id="out">loading</h1>',
    '<script src="autojs://sdk/v1.js"></script>',
    '<script>',
    '$autojs.handle("go-back", function () { return $autojs.invoke("echo", "nested").then(function(v){return v === "nested" ? "go-back-ok" : "bad";}); });',
    '$autojs.handle("page-fail", function(){ throw new Error("page failure"); });',
    '$autojs.send("bridge-event", "event-payload");',
    '$autojs.invoke("host-fail").catch(function(e){ $autojs.send("host-error", String(e)); });',
    'console.log("webui-console-marker");',
    '(function () {',
    '  var dir = decodeURIComponent(window.location.pathname.replace(/\\/[^\\/]*$/, ""));',
    '  $autojs.invoke("listFiles", { path: dir, marker: "m1" }).then(function (files) {',
    '    document.getElementById("out").textContent = "loaded " + files.count;',
    '    return $autojs.invoke("report", {',
    '      ok: files.marker === "m1", path: files.path, count: files.count,',
    '      marker: files.marker, eventName: files.eventName, href: window.location.href',
    '    });',
    '  }).catch(function (error) {',
    '    return $autojs.invoke("report", { ok: false, error: String(error) });',
    '  });',
    '})();',
    '</script></body></html>'
].join('\n');
try {
    fs.writeFileSync(htmlPath, html, 'utf8');
} catch (error) {
    console.error('写入 Web UI fixture 失败：' + error);
}

function waitUntil(predicate, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    return new Promise(function (resolve) {
        (function check() {
            let ready = false;
            try { ready = !!predicate(); } catch (ignored) { ready = false; }
            if (ready || Date.now() >= deadline) {
                resolve(ready);
                return;
            }
            setTimeout(check, 30);
        })();
    });
}

function finish(ok, detail) {
    if (state.finished) return;
    state.finished = true;
    const checks = {
        inlineRealActivity: state.firstAttached,
        realContentViewAndWindow: !!state.contentViewHandle && !!state.windowHandle,
        consoleMessage: state.consoleMessages.some(function (line) {
            return line.indexOf('webui-console-marker') >= 0;
        }),
        fileListRoundTrip: !!(state.listResult && state.listResult.ok),
        jsBridgeEval: state.href === 'webui-contract',
        bridgeReentry: state.reentry, pageError: state.pageError, evalError: state.evalError, hostError: state.hostError, event: state.event,
        jsBridgeInvoke: state.goBack === 'go-back-ok',
        startActivity: state.startActivity,
        allActivitiesDestroyed: state.allDestroyed,
        noPrematureAllDestroyed: !state.prematureDestroyed
    };
    const allChecks = Object.keys(checks).every(function (key) { return checks[key] === true; });
    const result = {
        ok: !!ok && allChecks && !state.failure,
        checks: checks,
        detail: detail || state.listResult || state.failure || null,
        console: state.consoleMessages.slice(-8)
    };
    console.log('NODE24_WEBUI_RESULT ' + JSON.stringify(result));
    process.exitCode = result.ok ? 0 : 1;
}

class SecondActivity extends ui.Activity {
    get layoutXml() {
        return '<vertical><text id="second_text" text="second"/></vertical>';
    }
    onContentViewSet(contentView) {
        try {
            state.startActivity = true;
            const text = contentView.findView('second_text');
            if (!text) throw new Error('second_text not found');
            console.log('NODE24_WEBUI_STARTACTIVITY ok');
            ui.close();
        } catch (error) {
            state.failure = 'startActivity: ' + (error && error.stack ? error.stack : error);
            ui.close();
        }
    }
}

class WebActivity extends ui.Activity {
    get layoutXml() {
        return '<vertical><webview id="web" w="*" h="*"/></vertical>';
    }

    onContentViewSet(contentView) {
        const self = this;
        Promise.resolve().then(async function () {
            try {
                state.firstAttached = true;
                state.contentViewHandle = String(contentView.__handle || '');
                const activity = ui.currentActivity();
                state.windowHandle = String(ui.getWindow().__handle || '');
                if (!activity || !state.contentViewHandle || !state.windowHandle) {
                    throw new Error('真实 Activity/Window/View 身份缺失');
                }
                const webview = contentView.findView('web');
                if (!webview || typeof webview.loadUrl !== 'function') {
                    throw new Error('contentView.findView("web") 没有返回 WebView 门面');
                }
                if (contentView.binding.web !== webview) {
                    throw new Error('contentView.binding.web 与 findView("web") 不是同一门面');
                }
                webview.on('console_message', function (event, msg) {
                    state.consoleMessages.push(String(msg.sourceId()) + ':' + msg.lineNumber() + ':'
                        + String(msg.message()));
                });
                webview.jsBridge.handle('echo', async (event, value) => {state.reentry = true; return value;});
                webview.jsBridge.handle('host-fail', () => {throw new Error('host failure');});
                webview.jsBridge.on('host-error', (event, error) => {state.hostError = /host failure/.test(error);});
                webview.jsBridge.on('bridge-event', (event, value) => {state.event = event.name === 'bridge-event' && value === 'event-payload';});
                webview.jsBridge.handle('listFiles', async function (event, args) {
                    const files = await fs.promises.readdir(args.path);
                    return {
                        path: args.path,
                        count: files.length,
                        marker: args.marker,
                        eventName: event.name
                    };
                });
                webview.jsBridge.handle('report', function (event, args) {
                    state.listResult = args;
                    return Promise.resolve().then(async function () {
                        await waitUntil(function () {
                            return state.consoleMessages.some(function (line) {
                                return line.indexOf('webui-console-marker') >= 0;
                            });
                        }, 2000);
                        const title = await webview.jsBridge.eval('document.title');
                        state.href = title;
                        state.goBack = await webview.jsBridge.invoke('go-back');
                        try {await webview.jsBridge.invoke('page-fail');} catch(e) {state.pageError = /page failure/.test(e.message);}
                        try {await webview.jsBridge.eval('throw new Error("eval failure")');} catch(e) {state.evalError = /eval failure/.test(e.message);}
                        await waitUntil(() => state.hostError && state.event, 2000);
                        if (!args.ok || args.marker !== 'm1' || args.eventName !== 'listFiles'
                            || title !== 'webui-contract' || state.goBack !== 'go-back-ok') {
                            throw new Error('Web UI 契约断言失败：' + JSON.stringify({
                                args: args, title: title, goBack: state.goBack
                            }));
                        }
                        await ui.startActivity(SecondActivity, { flags: 0x08000000 });
                        return {ok: true};
                    });
                });
                webview.loadUrl('file://' + htmlPath);
            } catch (error) {
                state.failure = error && error.stack ? error.stack : String(error);
                console.error('NODE24_WEBUI_FAIL ' + state.failure);
                ui.close();
            }
        });
    }
}

ui.activityLifecycle.on('all_activities_destroyed', function () {
    if (!state.startActivity) {
        state.prematureDestroyed = true;
    }
    state.allDestroyed = true;
    finish(!state.failure && state.startActivity, state.failure);
});

const watchdog = setTimeout(function () {
    state.failure = state.failure || 'probe timeout';
    finish(false, state.failure);
    try { ui.close(); } catch (ignored) { /* noop */ }
}, 30000);
if (watchdog && typeof watchdog.unref === 'function') watchdog.unref();

try {
    ui.setMainActivity(WebActivity);
} catch (error) {
    state.failure = error && error.stack ? error.stack : String(error);
    console.error('NODE24_WEBUI_FAIL ' + state.failure);
    finish(false, state.failure);
}
