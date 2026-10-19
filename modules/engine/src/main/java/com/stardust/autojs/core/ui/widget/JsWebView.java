package com.stardust.autojs.core.ui.widget;

import android.content.Context;
import android.net.http.SslError;
import android.os.Build;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.RequiresApi;

import com.stardust.autojs.runtime.ScriptRuntime;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 脚本里的 {@code <webview>} 控件（Auto.js 经典 {@code ui.web} / Pro {@code $ui.web}）。
 *
 * <p>在原有「只是个支持宽视口/缩放/JS 的 WebView」基础上补齐 Pro 的 {@code $ui.web} 兼容层：
 * <ul>
 *   <li>{@link #getEvents()}：{@code $ui.web.events}，EventEmitter 语义（on/off/once/emit），
 *       原生事件目前有 {@code console_message}（回调 {@code (event, msg)}，msg 是
 *       {@link ConsoleMessage}，可调 {@code sourceId()/lineNumber()/message()}）；</li>
 *   <li>{@link #getJsBridge()}：{@code $ui.web.jsBridge}（{@link WebJsBridge}），
 *       页面 {@code $autojs.invoke/send} 与脚本 {@code jsBridge.handle/on/send} 互通的请求-事件桥；</li>
 *   <li>拦截 {@code autojs://sdk/v1.js} 注入页面 SDK（{@code window.$autojs}），
 *       页面可直接 {@code <script src="autojs://sdk/v1.js">}；</li>
 *   <li>保留 {@link #setIgnoreSslErrors(boolean)} 等既有行为。</li>
 * </ul>
 *
 * <p>{@link ScriptRuntime} 由 {@code UI} 注册的 {@code JsWebViewInflater} 注入（普通构造器创建的
 * 实例没有运行时，只退化为基础 WebView，不会崩）。
 */
public class JsWebView extends WebView {

    private static final String TAG = "JsWebView";
    private static final String SDK_URL_PREFIX = "autojs://sdk/";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * 页面 SDK：{@code window.$autojs} + 与 {@link WebJsBridge} 的请求/事件协议。
     * 页面最简用法见内置示例 {@code web/index.html}。
     */
    private static final String AUTOJS_SDK_JS = "(function () {\n"
            + "    if (window.$autojs) { return; }\n"
            + "    var RESP = '$autojs:internal:response:';\n"
            + "    var nextId = 1;\n"
            + "    var pending = {};\n"
            + "    var listeners = {};\n"
            + "    function post(obj) {\n"
            + "        try {\n"
            + "            if (window.__autojsBridge) { window.__autojsBridge.postMessage(JSON.stringify(obj)); }\n"
            + "        } catch (e) { console.error('$autojs bridge error: ' + e); }\n"
            + "    }\n"
            + "    function emitLocal(event, args) {\n"
            + "        var arr = listeners[event];\n"
            + "        if (!arr) { return; }\n"
            + "        for (var i = 0; i < arr.length; i++) {\n"
            + "            try { arr[i].apply(null, args); } catch (e) { console.error(e); }\n"
            + "        }\n"
            + "    }\n"
            + "    var api = {\n"
            + "        invoke: function (channel) {\n"
            + "            var args = Array.prototype.slice.call(arguments, 1);\n"
            + "            return new Promise(function (resolve, reject) {\n"
            + "                var id = nextId++;\n"
            + "                pending[id] = { resolve: resolve, reject: reject };\n"
            + "                post({ type: 'request', id: id, channel: channel, args: args });\n"
            + "            });\n"
            + "        },\n"
            + "        send: function (event) {\n"
            + "            var args = Array.prototype.slice.call(arguments, 1);\n"
            + "            post({ type: 'event', event: event, args: args });\n"
            + "        },\n"
            + "        on: function (event, fn) {\n"
            + "            if (!listeners[event]) { listeners[event] = []; }\n"
            + "            listeners[event].push(fn);\n"
            + "        },\n"
            + "        off: function (event, fn) {\n"
            + "            var arr = listeners[event];\n"
            + "            if (!arr) { return; }\n"
            + "            var index = arr.indexOf(fn);\n"
            + "            if (index >= 0) { arr.splice(index, 1); }\n"
            + "        },\n"
            + "        __dispatch: function (event, argsJson) {\n"
            + "            var args = [];\n"
            + "            try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }\n"
            + "            if (event.indexOf(RESP) === 0) {\n"
            + "                var id = parseInt(event.substring(RESP.length), 10);\n"
            + "                var handler = pending[id];\n"
            + "                if (!handler) { return; }\n"
            + "                delete pending[id];\n"
            + "                var payload = args && args.length > 0 ? args[0] : {};\n"
            + "                if (payload && payload.error) {\n"
            + "                    handler.reject(new Error(payload.error));\n"
            + "                } else {\n"
            + "                    handler.resolve(payload ? payload.result : undefined);\n"
            + "                }\n"
            + "                return;\n"
            + "            }\n"
            + "            emitLocal(event, args);\n"
            + "        }\n"
            + "    };\n"
            + "    window.$autojs = api;\n"
            + "    window.__autojsDispatch = function (event, argsJson) { api.__dispatch(event, argsJson); };\n"
            + "})();";

    /**
     * 是否忽略 SSL 证书错误（自签证书等）。默认关闭；由脚本在 loadUrl 之前通过
     * {@link #setIgnoreSslErrors(boolean)} 显式打开，只影响这一个 WebView。
     */
    private boolean mIgnoreSslErrors = false;

    private final ScriptRuntime mRuntime;
    private JsWebViewEvents mEvents;
    private WebJsBridge mJsBridge;

    public JsWebView(Context context) {
        this(context, (ScriptRuntime) null);
    }

    public JsWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mRuntime = null;
        init();
    }

    public JsWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mRuntime = null;
        init();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public JsWebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mRuntime = null;
        init();
    }

    /** 由 {@code JsWebViewInflater} 使用：带上脚本运行时，启用 {@code $ui.web} 兼容层。 */
    public JsWebView(Context context, ScriptRuntime runtime) {
        super(context);
        mRuntime = runtime;
        init();
    }

    private void init() {
        WebSettings settings = getSettings();
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setLoadWithOverviewMode(true);
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        settings.setDisplayZoomControls(false);
        // file:// 页面通过桥读取本地文件（示例用 $autojs.invoke('fetch')，不依赖此开关，
        // 但保留 Pro 的行为，方便页面内直接 fetch 本地资源）
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // 本地 file:// 页面（示例的 web/index.html）读取同目录资源/走 fetch 需要这两个开关；
        // 页面里真正读文件的请求走 jsBridge 的 fetch handler，这里只是兜底。
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // 兜底注入页面 SDK：个别 WebView 版本不会为自定义 scheme 的 <script src> 走
                // shouldInterceptRequest。SDK 自带 window.$autojs 判重，重复注入无害。
                evaluatePageScript(AUTOJS_SDK_JS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载后再补一次：工程示例的 inline script 在 loadModule 异步链里才用 $autojs，
                // 这里能覆盖「SDK <script> 被 WebView 拒绝加载」的极端情况。
                evaluatePageScript(AUTOJS_SDK_JS);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (mIgnoreSslErrors) {
                    handler.proceed();
                } else {
                    super.onReceivedSslError(view, handler, error);
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return interceptSdkRequest(url);
            }

            @Override
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return interceptSdkRequest(request == null || request.getUrl() == null
                        ? null : request.getUrl().toString());
            }
        });
        setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                if (consoleMessage != null) {
                    // 转发到脚本：$ui.web.events.on('console_message', (event, msg) => ...)
                    getEvents().emit("console_message", consoleMessage);
                }
                return super.onConsoleMessage(consoleMessage);
            }
        });
        addJavascriptInterface(new PageJavascriptInterface(), "__autojsBridge");
    }

    /** 是否启用了 Pro 的 {@code $ui.web} 兼容层（有脚本运行时才有 events/jsBridge）。 */
    public boolean isScriptRuntimeAttached() {
        return mRuntime != null;
    }

    /** {@code $ui.web.events}：EventEmitter 语义，当前原生事件：console_message。 */
    public JsWebViewEvents getEvents() {
        if (mEvents == null) {
            synchronized (this) {
                if (mEvents == null) {
                    mEvents = new JsWebViewEvents(mRuntime);
                }
            }
        }
        return mEvents;
    }

    /** {@code $ui.web.jsBridge}：页面 $autojs 与脚本 handle/on/send 的桥。 */
    public WebJsBridge getJsBridge() {
        if (mJsBridge == null) {
            synchronized (this) {
                if (mJsBridge == null) {
                    mJsBridge = new WebJsBridge(this, mRuntime);
                }
            }
        }
        return mJsBridge;
    }

    @Override
    public void loadUrl(String url) {
        if (loadLocalHtmlWithSdk(url)) {
            return;
        }
        super.loadUrl(url);
    }

    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        if (loadLocalHtmlWithSdk(url)) {
            return;
        }
        super.loadUrl(url, additionalHttpHeaders);
    }

    /**
     * 本地 {@code file://.../index.html} 页面：把页面 SDK 内联进 HTML 再以原 URL 为 base 加载。
     *
     * <p>为什么不能只靠 {@code <script src="autojs://sdk/v1.js">}：部分 WebView 版本不会给
     * 自定义 scheme 的脚本请求走 {@code shouldInterceptRequest}，而页面 inline script 在解析期
     * 就会用 {@code $autojs}（示例的 vue2-sfc-loader 在 loadModule 里同步调用 getFile）。
     * 内联后能保证 SDK 一定先于页面脚本执行；页面自身的 autojs:// 引用失败也无害。
     */
    private boolean loadLocalHtmlWithSdk(String url) {
        if (url == null || !url.startsWith("file://") || mRuntime == null) {
            return false;
        }
        String lower = url.toLowerCase();
        if (!lower.endsWith(".html") && !lower.endsWith(".htm")) {
            return false;
        }
        try {
            String path = url.substring("file://".length());
            int query = path.indexOf('?');
            if (query >= 0) {
                path = path.substring(0, query);
            }
            path = android.net.Uri.decode(path);
            File file = new File(path);
            if (!file.isFile()) {
                return false;
            }
            byte[] bytes = new byte[(int) file.length()];
            try (InputStream input = new FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read <= 0) {
                        break;
                    }
                    offset += read;
                }
            }
            String html = new String(bytes, UTF_8);
            // 只处理「确实要用 $autojs 桥」的页面，避免影响普通本地 HTML 的加载语义。
            if (html.indexOf("autojs://sdk") < 0 && html.indexOf("$autojs") < 0) {
                return false;
            }
            String injected = injectSdk(html);
            if (injected == null) {
                return false;
            }
            Log.d(TAG, "inject autojs sdk into local page: " + url);
            loadDataWithBaseURL(url, injected, "text/html", "UTF-8", null);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "loadLocalHtmlWithSdk failed: " + url, error);
            return false;
        }
    }

    private static String injectSdk(String html) {
        String script = "<script>" + AUTOJS_SDK_JS + "</script>";
        int index = html.indexOf("<script");
        if (index < 0) {
            index = html.indexOf("<body");
            if (index >= 0) {
                int end = html.indexOf('>', index);
                index = end < 0 ? -1 : end + 1;
            }
        }
        if (index < 0) {
            return null;
        }
        return html.substring(0, index) + script + html.substring(index);
    }

    /** 在页面执行 JS（内部会切到 main 线程）；给 {@link WebJsBridge#eval(String)} 用。 */
    public void evaluatePageScript(final String script) {
        if (script == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluateJavascript(script, null);
        } else {
            post(() -> evaluateJavascript(script, null));
        }
    }

    /** 派发一个页面事件/响应：页面 SDK 的 {@code __autojsDispatch(event, argsJson)}。 */
    public void dispatchPageEvent(String event, String argsJson) {
        if (event == null) {
            return;
        }
        String script = "window.__autojsDispatch && window.__autojsDispatch("
                + JSONObject.quote(event) + "," + JSONObject.quote(argsJson == null ? "[]" : argsJson)
                + ");";
        evaluatePageScript(script);
    }

    private WebResourceResponse interceptSdkRequest(String url) {
        if (url == null || !url.startsWith(SDK_URL_PREFIX)) {
            return null;
        }
        Log.d(TAG, "inject autojs sdk: " + url);
        return new WebResourceResponse("application/javascript", "UTF-8",
                new ByteArrayInputStream(AUTOJS_SDK_JS.getBytes(UTF_8)));
    }

    /**
     * 忽略本 WebView 的 SSL 证书错误（自签证书、内网 HTTPS 等场景）。
     *
     * <p>默认关闭，行为与标准 WebView 一致；脚本显式打开后，页面及其子资源的证书
     * 错误都会走 {@code handler.proceed()}。建议在 {@code loadUrl} 之前调用。
     *
     * <pre>{@code
     * ui.web.setIgnoreSslErrors(true);
     * ui.web.loadUrl("https://self-signed.example");
     * }</pre>
     */
    public void setIgnoreSslErrors(boolean ignoreSslErrors) {
        mIgnoreSslErrors = ignoreSslErrors;
    }

    /** 见 {@link #setIgnoreSslErrors(boolean)}。 */
    public boolean isIgnoreSslErrors() {
        return mIgnoreSslErrors;
    }

    /** 页面 {@code __autojsBridge.postMessage(json)} 的 Java 实现。 */
    private final class PageJavascriptInterface {

        @JavascriptInterface
        public void postMessage(String json) {
            try {
                getJsBridge().onPageMessage(json);
            } catch (Throwable error) {
                Log.w(TAG, "page bridge postMessage failed", error);
            }
        }
    }
}
