package com.stardust.autojs.engine.node24;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.stardust.autojs.core.ui.widget.JsWebView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Node UI 的真实 WebView（{@code <webview>} 的 Node24 实现）。
 *
 * <p>不是 marker/no-op：页面里的 {@code $autojs.invoke()} 通过
 * {@code addJavascriptInterface} 进入 Java，Java 把请求投递到
 * {@link NodeUiLocalSession} 的 Node 事件队列；Node 侧处理器完成后，
 * 再回到 WebView 主线程执行页面里的 resolve。反向 {@code jsBridge.invoke()}
 * 同样等待页面 handler 的真实返回值。控制台消息走 {@link WebChromeClient}
 * 进入同一个队列，由 ui.js 派发成 {@code webview.on('console_message')}。
 */
public class NodeWebView extends WebView {

    private static final String TAG = "NodeWebView";
    static final String SDK_URL_PREFIX = "autojs://sdk/";
    private static final String BRIDGE_NAME = "__ajsNodeBridge";
    private static final long DEFAULT_TIMEOUT_MS = 10_000L;
    private static final Object PREPARE_LOCK = new Object();
    private static boolean sDataDirectorySuffixApplied;

    /**
     * 页面 SDK。Promise 只做页面侧等待；真正跨进程/线程的等待由页面请求 id 与
     * Java 的 evaluateJavascript 回调完成，没有同步阻塞 WebView JS 线程。
     */
    static final String SDK_JS = "(function () {\n"
            + "  if (window.$autojs && window.$autojs.__nodeUiSdk) { return; }\n"
            + "  var pending = Object.create(null);\n"
            + "  var nextId = 1;\n"
            + "  var handlers = Object.create(null);\n"
            + "  var listeners = Object.create(null);\n"
            + "  function bridge() { return window.__ajsNodeBridge; }\n"
            + "  function post(event, args) {\n"
            + "    var b = bridge();\n"
            + "    if (!b) { return false; }\n"
            + "    try { b.event(String(event), JSON.stringify(args || [])); return true; }\n"
            + "    catch (e) { console.error('[autojs] event bridge failed: ' + e); return false; }\n"
            + "  }\n"
            + "  var api = {\n"
            + "    __nodeUiSdk: true,\n"
            + "    invoke: function (channel) {\n"
            + "      var args = Array.prototype.slice.call(arguments, 1);\n"
            + "      return new Promise(function (resolve, reject) {\n"
            + "        var b = bridge();\n"
            + "        if (!b) { reject(new Error('$autojs bridge unavailable')); return; }\n"
            + "        var id = 'p' + (nextId++);\n"
            + "        pending[id] = { resolve: resolve, reject: reject };\n"
            + "        try { b.request(id, String(channel), JSON.stringify(args)); }\n"
            + "        catch (e) { delete pending[id]; reject(new Error(String(e))); }\n"
            + "      });\n"
            + "    },\n"
            + "    handle: function (channel, fn) {\n"
            + "      if (typeof fn === 'function') { handlers[String(channel)] = fn; }\n"
            + "    },\n"
            + "    send: function (event) {\n"
            + "      post(event, Array.prototype.slice.call(arguments, 1));\n"
            + "    },\n"
            + "    on: function (event, fn) {\n"
            + "      var key = String(event);\n"
            + "      (listeners[key] || (listeners[key] = [])).push(fn);\n"
            + "    },\n"
            + "    off: function (event, fn) {\n"
            + "      var arr = listeners[String(event)];\n"
            + "      if (!arr) { return; }\n"
            + "      var i = arr.indexOf(fn);\n"
            + "      if (i >= 0) { arr.splice(i, 1); }\n"
            + "    },\n"
            + "    __resolve: function (id, ok, payloadJson) {\n"
            + "      var waiter = pending[id];\n"
            + "      if (!waiter) { return; }\n"
            + "      delete pending[id];\n"
            + "      var payload = {};\n"
            + "      try { payload = JSON.parse(payloadJson || '{}'); } catch (e) { payload = {}; }\n"
            + "      if (ok === true) { waiter.resolve(payload); }\n"
            + "      else { waiter.reject(new Error(payload && payload.error ? payload.error : 'host request failed')); }\n"
            + "    },\n"
            + "    __emit: function (event, argsJson) {\n"
            + "      var args = [];\n"
            + "      try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }\n"
            + "      var arr = listeners[String(event)] || [];\n"
            + "      for (var i = 0; i < arr.length; i++) {\n"
            + "        try { arr[i].apply(null, args); } catch (e) { console.error(e); }\n"
            + "      }\n"
            + "    },\n"
            + "    __invoke: function (id, channel, argsJson) {\n"
            + "      var args = [];\n"
            + "      try { args = JSON.parse(argsJson || '[]'); } catch (e) { args = []; }\n"
            + "      var fn = handlers[String(channel)] || handlers[''];\n"
            + "      if (typeof fn !== 'function') {\n"
            + "        try { bridge().invokeResult(id, JSON.stringify({ok:false,error:'no handler for action: ' + channel})); } catch (e) {}\n"
            + "        return;\n"
            + "      }\n"
            + "      Promise.resolve().then(function () { return fn.apply(null, args); })\n"
            + "        .then(function (value) {\n"
            + "          try { bridge().invokeResult(id, JSON.stringify({ok:true,value:value === undefined ? null : value})); } catch (e) {}\n"
            + "        }, function (error) {\n"
            + "          try { bridge().invokeResult(id, JSON.stringify({ok:false,error:String(error && error.message ? error.message : error)})); } catch (e) {}\n"
            + "        });\n"
            + "    }\n"
            + "  };\n"
            + "  window.$autojs = api;\n"
            + "  window.__ajsNodeSdk = api;\n"
            + "})();";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, InvokeWaiter> pendingInvokes = new ConcurrentHashMap<>();
    private volatile int webId;
    private volatile boolean released;
    private volatile String projectDir = "";

    public NodeWebView(Context context) {
        super(context);
        init();
    }

    public NodeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NodeWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * 必须在进程内第一个 WebView 创建前调用（Activity inflate 之前）。
     * 每个 :node24_1..3 进程使用自己的 WebView 数据目录后缀，避免 Android
     * 多进程共用默认目录导致的 data directory already in use 崩溃。
     */
    public static void prepareProcess(Context context) {
        synchronized (PREPARE_LOCK) {
            if (sDataDirectorySuffixApplied) {
                return;
            }
            sDataDirectorySuffixApplied = true;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || context == null) {
                return;
            }
            String suffix = processDataDirectorySuffix(context);
            try {
                WebView.setDataDirectorySuffix(suffix);
                Log.i(TAG, "WebView dataDirectorySuffix=" + suffix);
            } catch (Throwable error) {
                // 进程里已经有 WebView 时 API 会抛 IllegalStateException；不 crash，只记录。
                Log.w(TAG, "WebView dataDirectorySuffix 设置失败（继续用进程默认目录）：" + suffix, error);
            }
        }
    }

    private static String processDataDirectorySuffix(Context context) {
        String process = null;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = manager == null
                    ? null : manager.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo info : processes) {
                    if (info.pid == android.os.Process.myPid()) {
                        process = info.processName;
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取进程名失败，WebView 数据目录使用 pid", error);
        }
        String part = process == null ? ("pid" + android.os.Process.myPid())
                : process.substring(process.lastIndexOf(':') + 1);
        if (part.isEmpty()) {
            part = "main";
        }
        return "nodeui_" + part.replaceAll("[^A-Za-z0-9_]", "_");
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void init() {
        prepareProcess(getContext() == null ? null : getContext().getApplicationContext());
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // 生产构建通常是 file:///sdcard/.../web/index.html；页面需要读取同目录资源。
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        // 开发模式常用 http://localhost:port（同进程 Node http server），不能因为混合内容被拦。
        try {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Throwable ignored) {
            // 老 WebView 没有该 API 时保持系统默认。
        }
        setWebViewClient(new NodeWebViewClient());
        setWebChromeClient(new NodeWebChromeClient());
        addJavascriptInterface(new NodePageBridge(), BRIDGE_NAME);
        Log.i(TAG, "NodeWebView created pid=" + android.os.Process.myPid());
    }

    /** 由 NodeUiLocalSession 在 onContentViewSet 里绑定；页面事件用该 id 回到 ui.js。 */
    void bindSession(int id, String directory) {
        webId = id;
        projectDir = directory == null ? "" : directory;
        Log.i(TAG, "NodeWebView bind webId=" + id + " project=" + projectDir);
    }

    int getWebId() {
        return webId;
    }

    boolean isReleased() {
        return released;
    }

    void loadUrlFromNode(String rawUrl) {
        if (released) {
            throw new IllegalStateException("WebView 已释放");
        }
        String url = resolveUrl(rawUrl);
        Log.i(TAG, "loadUrl webId=" + webId + " " + url);
        if (loadLocalHtmlWithSdk(url)) {
            return;
        }
        loadUrl(url);
    }

    /**
     * 生产构建的本地 HTML：把页面 SDK 内联进 HTML 再用原 URL 作为 base 加载。
     * 这样不依赖 WebView 是否给自定义 scheme 的 &lt;script src&gt; 走拦截，页面解析期
     * 就能同步拿到 $autojs（React/Vue 构建页的 inline bootstrap 会立即 invoke）。
     */
    private boolean loadLocalHtmlWithSdk(String url) {
        if (url == null || !url.startsWith("file://")) {
            return false;
        }
        String lower = url.toLowerCase();
        if (!lower.endsWith(".html") && !lower.endsWith(".htm")
                && lower.indexOf(".html?") < 0 && lower.indexOf(".htm?") < 0) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if (path == null) {
                return false;
            }
            File file = new File(path);
            if (!file.isFile()) {
                return false;
            }
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.InputStream input = new java.io.FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read <= 0) {
                        break;
                    }
                    offset += read;
                }
            }
            String html = new String(bytes, StandardCharsets.UTF_8);
            String injected = injectSdk(html);
            if (injected == null) {
                return false;
            }
            Log.d(TAG, "inject Node UI sdk into local page: " + url);
            loadDataWithBaseURL(url, injected, "text/html", "UTF-8", url);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "本地 HTML 注入 SDK 失败，回退普通 loadUrl: " + url, error);
            return false;
        }
    }

    private static String injectSdk(String html) {
        String script = "<script>" + SDK_JS + "</script>";
        String lower = html.toLowerCase();
        int index = lower.indexOf("<script");
        if (index < 0) {
            index = lower.indexOf("<body");
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

    String resolveUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("loadUrl 需要非空 URL");
        }
        String url = rawUrl.trim().replace('\\', '/');
        if (url.startsWith("file://")) {
            String path = url.substring("file://".length());
            // file:///sdcard/x -> rest 以 / 开头；file://host/path 不是本地文件，保持原样。
            if (path.startsWith("/")) {
                String decoded = Uri.decode(path);
                return Uri.fromFile(new File(decoded)).toString();
            }
            return url;
        }
        if (url.startsWith("/")) {
            return Uri.fromFile(new File(url)).toString();
        }
        if (url.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            return url;
        }
        String base = projectDir;
        if (base == null || base.isEmpty()) {
            return url;
        }
        return Uri.fromFile(new File(base, url)).toString();
    }

    /**
     * Node -> 页面请求；在 Node 线程调用，内部投递到主线程并等待页面 handler
     * 通过 {@link NodePageBridge#invokeResult} 回传。超时/释放都返回明确错误 JSON。
     */
    String invokePage(String channel, String argsJson, long timeoutMs) {
        if (released) {
            return errorJson("WebView 已释放");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return errorJson("jsBridge.invoke 不能在 WebView 主线程等待");
        }
        final String id = "h" + System.nanoTime();
        InvokeWaiter waiter = new InvokeWaiter();
        synchronized (pendingInvokes) {
            if (released) {
                return errorJson("WebView 已释放");
            }
            pendingInvokes.put(id, waiter);
        }
        final String script = "(function(){var s=window.__ajsNodeSdk;if(!s||typeof s.__invoke!=='function'){"
                + "try{window.__ajsNodeBridge.invokeResult(" + JSONObject.quote(id)
                + ",JSON.stringify({ok:false,error:'page SDK missing'}));}catch(e){}return;}"
                + "try{s.__invoke(" + JSONObject.quote(id) + "," + JSONObject.quote(channel == null ? "" : channel)
                + "," + JSONObject.quote(argsJson == null ? "[]" : argsJson) + ");}"
                + "catch(e){try{window.__ajsNodeBridge.invokeResult(" + JSONObject.quote(id)
                + ",JSON.stringify({ok:false,error:String(e)}));}catch(ignored){}}})();";
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!released) {
                    evaluateJavascript(script, null);
                }
            }
        });
        try {
            String result = waiter.await(timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs);
            return result == null ? errorJson("页面 handler 没有返回") : result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return errorJson("等待页面 handler 被中断");
        } finally {
            pendingInvokes.remove(id);
        }
    }

    private void completeInvoke(String id, String resultJson) {
        InvokeWaiter waiter = pendingInvokes.remove(id);
        if (waiter != null) {
            waiter.complete(resultJson == null ? errorJson("页面返回为空") : resultJson);
        }
    }

    /**
     * 主线程执行 evaluateJavascript，回调在主线程；调用方（Node 线程）通过 latch 等结果。
     * 本方法本身必须从主线程调用；异步 callback 完成 latch。
     */
    void evaluateWithResult(String script, ValueCallback<String> callback) {
        if (released) {
            callback.onReceiveValue("null");
            return;
        }
        evaluateJavascript(script == null ? "null" : script, callback);
    }

    /** 页面请求的响应：回到主线程 resolve 页面 Promise。 */
    void resolvePageRequest(String requestId, boolean ok, String payloadJson) {
        if (released) {
            return;
        }
        String script = "window.__ajsNodeSdk&&window.__ajsNodeSdk.__resolve("
                + JSONObject.quote(requestId == null ? "" : requestId) + ","
                + (ok ? "true" : "false") + ","
                + JSONObject.quote(payloadJson == null ? "{}" : payloadJson) + ");";
        evaluateJavascript(script, null);
    }

    /** Node -> 页面单向事件（jsBridge.send）。 */
    void emitPageEvent(String event, String argsJson) {
        if (released) {
            return;
        }
        String script = "window.__ajsNodeSdk&&window.__ajsNodeSdk.__emit("
                + JSONObject.quote(event == null ? "" : event) + ","
                + JSONObject.quote(argsJson == null ? "[]" : argsJson) + ");";
        evaluateJavascript(script, null);
    }

    /**
     * 释放：取消所有等待中的页面 handler，移除 JS 接口并销毁 WebView。
     * 必须在主线程调用（Activity.onDestroy / session close 均保证）。
     */
    void release() {
        if (released) {
            return;
        }
        java.util.List<String> ids;
        synchronized (pendingInvokes) {
            released = true;
            ids = new ArrayList<>(pendingInvokes.keySet());
        }
        for (String id : ids) {
            InvokeWaiter waiter = pendingInvokes.remove(id);
            if (waiter != null) {
                waiter.complete(errorJson("WebView 已释放"));
            }
        }
        try {
            stopLoading();
            loadUrl("about:blank");
            clearHistory();
            removeJavascriptInterface(BRIDGE_NAME);
            setWebChromeClient(null);
            setWebViewClient(null);
            ViewGroup parent = getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null;
            if (parent != null) {
                parent.removeView(this);
            }
            destroy();
            Log.i(TAG, "NodeWebView released webId=" + webId);
        } catch (Throwable error) {
            Log.w(TAG, "释放 WebView 失败 webId=" + webId, error);
        }
    }

    /** 编译布局里的 &lt;webview&gt; 仍是 JsWebView；在 Node UI Activity 内替换成真实 NodeWebView。 */
    public static View replaceJsWebViews(View root) {
        if (root == null) {
            return null;
        }
        if (root instanceof JsWebView && !(root instanceof NodeWebView)) {
            NodeWebView replacement = new NodeWebView(root.getContext());
            copyViewState(root, replacement);
            try {
                ((JsWebView) root).destroy();
            } catch (Throwable ignored) {
                // 旧根销毁失败不影响新实例。
            }
            return replacement;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof JsWebView && !(child instanceof NodeWebView)) {
                    NodeWebView replacement = new NodeWebView(child.getContext());
                    copyViewState(child, replacement);
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    group.removeViewAt(i);
                    if (params != null) {
                        group.addView(replacement, i, params);
                    } else {
                        group.addView(replacement, i);
                    }
                    try {
                        ((JsWebView) child).destroy();
                    } catch (Throwable ignored) {
                        // 旧占位 WebView 的销毁失败不影响新实例。
                    }
                    Log.i(TAG, "替换 JsWebView -> NodeWebView id=" + replacement.getId());
                } else {
                    replaceJsWebViews(child);
                }
            }
        }
        return root;
    }

    private static void copyViewState(View from, View to) {
        to.setId(from.getId());
        to.setVisibility(from.getVisibility());
        to.setAlpha(from.getAlpha());
        to.setContentDescription(from.getContentDescription());
        try {
            to.setTag(from.getTag());
        } catch (Throwable ignored) {
            // 某些工程 tag 不是普通对象，复制失败可忽略。
        }
        ViewGroup.LayoutParams params = from.getLayoutParams();
        if (params != null) {
            to.setLayoutParams(params);
        }
    }

    /** overlay / Activity 关闭时销毁树内所有 NodeWebView（收集后释放，避免遍历时改树）。 */
    public static void destroyInTree(View root) {
        List<NodeWebView> views = new ArrayList<>();
        collectNodeWebViews(root, views);
        for (final NodeWebView view : views) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                view.release();
            } else {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        view.release();
                    }
                });
            }
        }
    }

    private static void collectNodeWebViews(View root, List<NodeWebView> out) {
        if (root instanceof NodeWebView) {
            out.add((NodeWebView) root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectNodeWebViews(group.getChildAt(i), out);
            }
        }
    }

    private static String errorJson(String message) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", message == null ? "WebView 操作失败" : message).toString();
        } catch (Throwable ignored) {
            return "{\"ok\":false,\"error\":\"WebView 操作失败\"}";
        }
    }

    private void enqueue(String type, JSONObject payload) {
        if (released || webId <= 0) {
            return;
        }
        NodeUiLocalSession.enqueueWebEvent(webId, type, payload);
    }

    private final class NodeWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (released) {
                return;
            }
            // 自定义 scheme 的 <script src> 在部分 WebView 版本不会走 shouldInterceptRequest；
            // onPageStarted 兜底注入（SDK 自带判重），保证页面 inline script 能拿到 $autojs。
            evaluateJavascript(SDK_JS, null);
            try {
                enqueue("webview_page_started", new JSONObject().put("url", url == null ? "" : url));
            } catch (Throwable ignored) {
                // JSON 构造不会失败；保持 WebView 回调不 crash。
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (released) {
                return;
            }
            evaluateJavascript(SDK_JS, null);
            try {
                enqueue("webview_page_finished", new JSONObject().put("url", url == null ? "" : url));
            } catch (Throwable ignored) {
                // 同上。
            }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return interceptSdk(url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return interceptSdk(request == null || request.getUrl() == null ? null : request.getUrl().toString());
        }

        private WebResourceResponse interceptSdk(String url) {
            if (url == null || !url.startsWith(SDK_URL_PREFIX)) {
                return null;
            }
            return new WebResourceResponse("application/javascript", "UTF-8",
                    new ByteArrayInputStream(SDK_JS.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private final class NodeWebChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            if (consoleMessage != null) {
                try {
                    JSONObject payload = new JSONObject()
                            .put("sourceId", consoleMessage.sourceId() == null ? "" : consoleMessage.sourceId())
                            .put("lineNumber", consoleMessage.lineNumber())
                            .put("message", consoleMessage.message() == null ? "" : consoleMessage.message())
                            .put("level", consoleMessage.messageLevel() == null
                                    ? "" : consoleMessage.messageLevel().name());
                    enqueue("webview_console", payload);
                } catch (Throwable error) {
                    Log.w(TAG, "console_message 入队失败", error);
                }
            }
            return super.onConsoleMessage(consoleMessage);
        }
    }

    /** 页面 -> Java。运行在 WebView 的 JavaBridge 线程，绝不直接碰 View。 */
    private final class NodePageBridge {
        @JavascriptInterface
        public void request(String requestId, String channel, String argsJson) {
            if (released) {
                return;
            }
            if (webId <= 0) {
                // overlay 等没有 Node 会话的路径：明确拒绝，而不是让页面 Promise 永久挂着。
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!released) {
                            resolvePageRequest(requestId, false,
                                    "{\"error\":\"WebView 未绑定 Node UI 会话（请使用 Activity.layoutXml）\"}");
                        }
                    }
                });
                return;
            }
            try {
                JSONObject payload = new JSONObject()
                        .put("requestId", requestId == null ? "" : requestId)
                        .put("channel", channel == null ? "" : channel)
                        .put("argsJson", argsJson == null ? "[]" : argsJson);
                enqueue("webview_request", payload);
            } catch (Throwable error) {
                Log.w(TAG, "页面 request 入队失败", error);
            }
        }

        @JavascriptInterface
        public void event(String name, String argsJson) {
            if (released || webId <= 0) {
                return;
            }
            try {
                JSONObject payload = new JSONObject()
                        .put("name", name == null ? "" : name)
                        .put("argsJson", argsJson == null ? "[]" : argsJson);
                enqueue("webview_event", payload);
            } catch (Throwable error) {
                Log.w(TAG, "页面 event 入队失败", error);
            }
        }

        @JavascriptInterface
        public void invokeResult(String id, String resultJson) {
            completeInvoke(id, resultJson);
        }
    }

    private static final class InvokeWaiter {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile String value;

        void complete(String result) {
            value = result;
            done.countDown();
        }

        String await(long timeoutMs) throws InterruptedException {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return null;
            }
            return value;
        }
    }
}
