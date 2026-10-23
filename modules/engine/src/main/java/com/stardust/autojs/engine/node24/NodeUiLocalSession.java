package com.stardust.autojs.engine.node24;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Real Views and Java callbacks share an existing Node pool process. */
public final class NodeUiLocalSession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object STATE = new Object();
    private static volatile Pending pending;
    private static volatile Activity activity;
    private static volatile Context applicationContext;
    private static volatile View content;
    private static volatile JSONObject views;
    private static volatile com.stardust.autojs.core.console.ConsoleImpl console;
    private static volatile ActivityCallbacks callbacks;
    private static final java.util.ArrayDeque<JSONObject> EVENTS = new java.util.ArrayDeque<>();
    private static final ImageLoader IMAGE_LOADER = new ImageLoader();
    /** 当前 Activity 会话的真实 NodeWebView；webId 由本会话分配，销毁时统一释放。 */
    private static final Map<Integer, NodeWebView> WEB_VIEWS = new LinkedHashMap<>();
    private static int nextWebId = 1;
    private static volatile boolean backHandled;
    private NodeUiLocalSession() {}
    private static final class Pending {
        final CountDownLatch ready = new CountDownLatch(1);
        final String token = java.util.UUID.randomUUID().toString();
        volatile String error;
        volatile boolean backHandled;
        /** 首次 attach 完成后，后续 attach 都是 Activity 重建（显式 recreate 或系统 config change）。 */
        volatile boolean attached;
        /** Activity 正在被重建；destroy 时必须保留 token/callbacks/keep-alive。 */
        volatile boolean recreating;
        /** 已经历过一次重建（用于给新 Activity 的事件打 recreated=true）。 */
        volatile boolean recreated;
    }

    /** 记录 application Context，供 Activity 之外（只有 parent 甚至没有 parent）的 ui.inflate 兜底。 */
    public static void setApplicationContext(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        applicationContext = app != null ? app : context;
    }

    public static JSONObject show(Context context, String projectDir, JSONObject request) throws Exception {
        setApplicationContext(context);
        Pending opening = new Pending();
        opening.backHandled = request != null && request.optBoolean("backHandled", false);
        synchronized (STATE) {
            if (pending != null || activity != null) throw new IllegalStateException("请先关闭当前 Node UI");
            pending = opening;
            console = null;
            callbacks = null;
            backHandled = false;
            destroyWebViewsLocked();
            synchronized (EVENTS) { EVENTS.clear(); }
        }
        boolean opened = false;
        try {
            String process = null;
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes != null) for (ActivityManager.RunningAppProcessInfo info : processes) {
                if (info.pid == android.os.Process.myPid()) process = info.processName;
            }
            String suffix = process == null ? "" : process.substring(process.lastIndexOf(':') + 1);
            if (!suffix.matches("node24_[123]")) {
                throw new IllegalStateException("原生 Node UI 需要现有 Node 池进程，当前进程：" + process);
            }
            Intent intent = new Intent().setClassName(context.getPackageName(),
                    "com.jdkshen.aijspro.engine.NodeUiLocalActivity$Slot" + suffix.substring(suffix.length() - 1));
            // A service needs NEW_TASK, but MULTIPLE_TASK creates another app card on
            // every run. Reuse the host task unless the script explicitly requests one.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("projectDir", projectDir);
            intent.putExtra("layoutId", request.optInt("layoutId"));
            intent.putExtra("layoutXml", request.optString("layoutXml"));
            intent.putExtra("token", opening.token);
            intent.putExtra("javaClassOwner", request.optString("javaClassOwner"));
            int flags = request.optInt("flags", 0);
            if (flags != 0) intent.addFlags(flags);
            context.startActivity(intent);
            if (!opening.ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Node 进程内 Activity 加载超时");
            }
            synchronized (STATE) {
                if (opening.error != null) throw new IllegalStateException(opening.error);
                if (pending != opening || activity == null) throw new IllegalStateException("Node UI 启动已取消");
                opened = true;
                return new JSONObject().put("ok", true).put("mode", "local_activity")
                        .put("views", views == null ? new JSONObject() : views);
            }
        } finally {
            // Also invalidate launches whose await was interrupted or whose startActivity failed.
            if (!opened) closeOpening(opening);
        }
    }

    public static boolean attach(Activity source, String token, View root, JSONObject ids) {
        synchronized (STATE) {
            Pending opening = pending;
            if (opening == null || opening.error != null || !opening.token.equals(token)) return false;
            boolean recreated = opening.recreating || opening.recreated || opening.attached;
            activity = source;
            content = root;
            views = ids;
            backHandled = opening.backHandled;
            opening.attached = true;
            opening.recreating = false;
            opening.recreated = recreated;
            // 系统重建时旧 Activity 的 destroy 可能晚于新 Activity 的 onCreate；
            // 新根之外的旧 WebView 必须在这里释放，不能把旧页面的 webId 留给新页面。
            destroyWebViewsOutsideLocked(root);
            enqueueEvent("create", recreated);
            enqueueEvent("start", recreated);
            enqueueEvent("resume", recreated);
            enqueueEvent("content_view_set", recreated);
            opening.ready.countDown();
            return true;
        }
    }
    public static void failed(String token, Throwable failure) {
        synchronized (STATE) {
            Pending opening = pending;
            if (opening == null || !opening.token.equals(token)) return;
            opening.error = String.valueOf(failure);
            opening.ready.countDown();
            if (!opening.attached) return;
            // 已经显示过的会话在重建时失败：新 Activity 不会再来 attach，
            // 必须补终态 destroy，否则 pending/isShowing/keep-alive 会永久保留。
            pending = null;
            activity = null; content = null; views = null; callbacks = null;
            backHandled = false;
            destroyWebViewsLocked();
            enqueueEvent("pause", false); enqueueEvent("stop", false); enqueueEvent("destroy", false);
            console = null;
        }
    }
    public static void destroyed(Activity source) {
        synchronized (STATE) {
            if (activity != source) return;
            Pending opening = pending;
            boolean recreation = !source.isFinishing()
                    && (source.isChangingConfigurations() || (opening != null && opening.recreating));
            if (recreation && opening != null) {
                // Activity 重建（显式 recreate 或旋转/字体/深色模式等系统 config change）：
                // 保留 pending token、callbacks、console(isShowing/keep-alive)，不投终态 destroy。
                // 新 Activity 会用同一个 token attach，并补 create/start/resume/content_view_set。
                opening.recreating = true;
                opening.recreated = true;
                activity = null;
                content = null;
                destroyWebViewsLocked();
                enqueueEvent("pause", false);
                enqueueEvent("stop", false);
                return;
            }
            if (opening != null) {
                opening.error = "Node UI 已关闭";
                opening.ready.countDown();
            }
            activity = null; content = null; pending = null; callbacks = null;
            backHandled = false;
            destroyWebViewsLocked();
            enqueueEvent("pause", false); enqueueEvent("stop", false); enqueueEvent("destroy", false);
            views = null;
            console = null;
        }
    }
    public static void event(String type) {
        enqueueEvent(type, false);
    }
    private static void enqueueEvent(String type, boolean recreated) {
        try {
            JSONObject event = new JSONObject().put("type", type);
            if (recreated) event.put("recreated", true);
            if ("content_view_set".equals(type)) event.put("views", views);
            synchronized (EVENTS) { EVENTS.addLast(event); }
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    public static JSONArray drainEvents() {
        JSONArray result = new JSONArray();
        synchronized (EVENTS) { while (!EVENTS.isEmpty()) result.put(EVENTS.removeFirst()); }
        return result;
    }
    public static boolean isShowing() { return pending != null || activity != null; }
    public static Activity getActivity() { return activity; }
    public static View getContentView() { return content; }

    /**
     * ui.inflate(XML 字符串, parent, attachToParent) 的 Node24 入口。
     *
     * <p>Context 优先级固定为：当前 Node UI Activity → parent.getContext() → application Context。
     * 真正 inflate 始终在主线程完成（在 Node 线程调用会同步等主线程结果，但主线程调用绝不反向等待
     * Node 线程）；LayoutParams 由 DynamicLayoutInflater 按 parent 类型生成，attach 时不会丢掉
     * layout_margin/layout_gravity 等属性。id 仍由 inflater 的 IdHandler 写入，并按 XML 中的名字
     * 走 Ids.parse（与 ui.js 的 binding fallback 同一张表）。
     */
    public static View inflateXml(String xml, ViewGroup parent, boolean attachToParent) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new IllegalArgumentException("ui.inflate 需要非空 XML 字符串");
        }
        if (attachToParent && parent == null) {
            throw new IllegalArgumentException("attachToParent=true 需要非空 parent");
        }
        final View[] result = new View[1];
        runOnMainAndWait(() -> {
            Context themed = resolveInflateContext(parent);
            result[0] = NodeUiHost.inflateInlineLayout(themed, xml,
                    themed.getClassLoader(), parent, attachToParent);
        }, 10_000L, "inflate");
        return result[0];
    }

    private static Context resolveInflateContext(ViewGroup parent) {
        Activity current = activity;
        if (current != null) {
            try {
                if (!current.isFinishing() && !current.isDestroyed()) return current;
            } catch (Throwable ignored) {
                // Activity 已经 detach：继续尝试 parent / application。
            }
        }
        if (parent != null && parent.getContext() != null) {
            return parent.getContext();
        }
        Context app = applicationContext;
        if (app != null) {
            return NodeUiHost.wrapThemedContext(app, null);
        }
        throw new IllegalStateException("ui.inflate 需要 Activity、parent 或 application Context");
    }

    /**
     * 显式重建当前 Node UI Activity（Activity.recreate 的标准语义）。
     *
     * <p>调用方（ui.js）已在主线程外重读 layoutXml 并展开自定义 Widget，把最终的 expandedXml
     * 传进来。这里只做主线程的两件事：更新当前 Activity Intent 的 layoutXml，然后 Activity.recreate。
     * 不等待新 Activity attach —— 新 Activity 的 onCreate 会在主线程走 attach，等待它会造成主线程死锁。
     */
    public static void recreate(final String expandedXml) {
        if (expandedXml == null || expandedXml.trim().isEmpty()) {
            throw new IllegalArgumentException("recreate 需要非空 layoutXml");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            recreateOnMain(expandedXml);
        } else {
            runOnMainAndWait(() -> recreateOnMain(expandedXml), 5_000L, "recreate");
        }
    }

    private static void recreateOnMain(String expandedXml) {
        final Activity current;
        final Pending opening;
        synchronized (STATE) {
            opening = pending;
            current = activity;
            if (opening == null || opening.error != null) {
                throw new IllegalStateException("Node UI 未显示，无法 recreate");
            }
            if (current == null) {
                throw new IllegalStateException("Node UI Activity 正在重建或尚未 attach，无法 recreate");
            }
            if (current.isFinishing() || current.isDestroyed()) {
                throw new IllegalStateException("Node UI Activity 已关闭，无法 recreate");
            }
            opening.recreating = true;
        }
        Intent intent = current.getIntent();
        if (intent == null) {
            resetRecreating(opening);
            throw new IllegalStateException("Node UI Activity 缺少 Intent，无法更新 layoutXml");
        }
        intent.putExtra("layoutXml", expandedXml);
        try {
            current.recreate();
        } catch (Throwable error) {
            resetRecreating(opening);
            if (error instanceof RuntimeException) throw (RuntimeException) error;
            throw new IllegalStateException("Activity.recreate 失败：" + error.getMessage(), error);
        }
    }

    private static void resetRecreating(Pending opening) {
        synchronized (STATE) {
            if (pending == opening) opening.recreating = false;
        }
    }
    /** Pro View.attr uses the same real attribute handlers as XML inflation, on main. */
    public static String viewAttribute(View view, String name, String value, boolean write) {
        if (view == null) throw new IllegalArgumentException("View 不能为空");
        final String[] result = new String[1];
        runOnMainAndWait(() -> {
            com.stardust.autojs.core.ui.attribute.ViewAttributes attributes =
                    com.stardust.autojs.core.ui.ViewExtras.getViewAttributes(view,
                            new com.stardust.autojs.core.ui.inflater.ResourceParser(
                                    new com.stardust.autojs.core.ui.inflater.util.Drawables()));
            com.stardust.autojs.core.ui.attribute.ViewAttributes.Attribute attribute = attributes.get(name);
            if (attribute == null) throw new IllegalArgumentException("未知 View 属性：" + name);
            if (write) attribute.set(value);
            else result[0] = attribute.get();
        }, 5000L, "View.attr");
        return result[0];
    }
    public static void configureStatusBar(String json) throws org.json.JSONException {
        JSONObject settings = new JSONObject(json);
        Activity owner = activity;
        if (owner == null) throw new IllegalStateException("Node UI 已关闭");
        runOnMainAndWait(() -> {
            android.view.Window window = owner.getWindow();
            if (settings.has("color")) {
                Object color = settings.opt("color");
                int value = color instanceof Number ? ((Number) color).intValue()
                        : android.graphics.Color.parseColor(String.valueOf(color));
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(value);
            }
            if (settings.has("light")) {
                androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView())
                        .setAppearanceLightStatusBars(settings.optBoolean("light"));
            }
        }, 5000L, "initialStatusBar");
    }
    public static void setContentView(View root) {
        // setTimeout/Node 回调里调用时自动切主线程，避免静默不刷新（兼容旧脚本的同步语义）。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnMainAndWait(() -> setContentView(root), 5000L, "setContentView");
            return;
        }
        Activity owner = activity;
        if (owner == null || owner.isFinishing()) throw new IllegalStateException("Node UI 已关闭");
        owner.setContentView(root);
        synchronized (STATE) {
            if (activity != owner) return;
            content = root;
            // 旧根上的 WebView 不再显示；立即释放，避免替换界面后残留页面/回调。
            destroyWebViewsOutsideLocked(root);
            event("content_view_set");
        }
    }

    /**
     * 返回键：脚本覆盖了 Activity.onBackPressed 时只投递 back_pressed 事件，由脚本决定是否关闭；
     * 否则返回 true 让 Activity 直接 finish（保持既有「一次返回关闭 Node UI」）。
     */
    public static boolean onBackPressed(Activity source) {
        synchronized (STATE) {
            if (activity != source) return true;
            if (!backHandled) return true;
            event("back_pressed");
            return false;
        }
    }
    public static int viewId(String name) {
        JSONObject ids = views;
        return ids == null ? 0 : ids.optInt(name);
    }
    public static ImageLoader getImageLoader() { return IMAGE_LOADER; }
    public interface ActivityCallbacks {
        boolean onCreateOptionsMenu(android.view.Menu menu);
        boolean onOptionsItemSelected(android.view.MenuItem item);
    }
    public static void setActivityCallbacks(ActivityCallbacks listener) {
        callbacks = listener;
        Activity current = activity;
        if (current != null) MAIN.post(() -> { if (activity == current) current.invalidateOptionsMenu(); });
    }
    public static boolean createOptionsMenu(android.view.Menu menu) {
        ActivityCallbacks listener = callbacks;
        return listener != null && listener.onCreateOptionsMenu(menu);
    }
    public static boolean optionsItemSelected(android.view.MenuItem item) {
        ActivityCallbacks listener = callbacks;
        return listener != null && listener.onOptionsItemSelected(item);
    }
    public static synchronized void bindConsoleView(com.stardust.autojs.core.console.ConsoleView view) {
        if (pending == null && activity == null) return;
        if (console == null) console = new com.stardust.autojs.core.console.ConsoleImpl(
                new com.stardust.util.UiHandler(view.getContext().getApplicationContext()));
        view.setConsole(console);
    }
    public static void onConsoleLine(String text) {
        com.stardust.autojs.core.console.ConsoleImpl current = console;
        if (current == null) return;
        org.autojs.source.engine.android.Node24ConsoleLine line = org.autojs.source.engine.android.Node24ConsoleLine.decode(text);
        current.println(line.level, line.text);
    }

    // ------------------------------------------------------------------
    // WebView：真实 View + 双向 Node 桥（供 assets/node24/ui.js 调用）
    // ------------------------------------------------------------------

    /** 把 onContentViewSet 拿到的真实 WebView 绑定到当前会话，返回稳定的 webId。 */
    public static int webAttach(View view, String projectDir) {
        if (!(view instanceof NodeWebView)) {
            throw new IllegalArgumentException("webview 需要 NodeWebView（当前是 "
                    + (view == null ? "null" : view.getClass().getName())
                    + "）；布局请用 <webview> 或让 NodeUiLocalActivity 替换 JsWebView");
        }
        NodeWebView web = (NodeWebView) view;
        synchronized (STATE) {
            Activity owner = activity;
            if (owner == null || owner.isFinishing()) {
                throw new IllegalStateException("Node UI 已关闭，无法绑定 WebView");
            }
            for (Map.Entry<Integer, NodeWebView> entry : WEB_VIEWS.entrySet()) {
                if (entry.getValue() == web) {
                    return entry.getKey();
                }
            }
            int id = nextWebId++;
            WEB_VIEWS.put(id, web);
            web.bindSession(id, projectDir);
            return id;
        }
    }

    public static void webLoadUrl(int webId, String url) {
        final NodeWebView web = requireWebView(webId);
        runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                web.loadUrlFromNode(url);
            }
        }, 5000L, "loadUrl");
    }

    /** Node -> 页面 evaluate 的同步结果（JSON 文本，与 evaluateJavascript 回调一致）。 */
    public static String webEval(int webId, String script, int timeoutMs) {
        final NodeWebView web = requireWebView(webId);
        final CountDownLatch done = new CountDownLatch(1);
        final String[] value = new String[]{"null"};
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateWithResult(script, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String result) {
                        value[0] = result == null ? "null" : result;
                        done.countDown();
                    }
                });
            }
        });
        try {
            if (!done.await(timeoutMs <= 0 ? 5000L : timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("WebView evaluate 超时（" + timeoutMs + "ms）");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 WebView evaluate 被中断");
        }
        return value[0];
    }

    /** Node -> 页面 jsBridge.invoke；页面 handler 的真实返回值以 JSON 文本返回。 */
    public static String webInvokePage(int webId, String channel, String argsJson, int timeoutMs) {
        return requireWebView(webId).invokePage(channel, argsJson, timeoutMs);
    }

    /** 页面请求的异步结果：回到主线程 resolve 页面 Promise。 */
    public static void webBridgeRespond(int webId, final String requestId, final boolean ok,
                                        final String payloadJson) {
        final NodeWebView web;
        try {
            web = requireWebView(webId);
        } catch (Throwable ignored) {
            // 会话已关闭：页面也已被销毁，丢弃迟到响应，不串下一脚本。
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                web.resolvePageRequest(requestId, ok, payloadJson);
            }
        });
    }

    public static void webSend(int webId, final String event, final String argsJson) {
        final NodeWebView web = requireWebView(webId);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                web.emitPageEvent(event, argsJson);
            }
        });
    }

    /** NodeWebView 的页面事件（console/request/page_*）投递；由 ui.js pollEvents 消费。 */
    public static void enqueueWebEvent(int webId, String type, JSONObject payload) {
        try {
            JSONObject event = payload == null ? new JSONObject() : new JSONObject(payload.toString());
            event.put("type", type);
            event.put("webId", webId);
            synchronized (EVENTS) { EVENTS.addLast(event); }
        } catch (Throwable error) {
            // 事件入队失败不能影响 WebView 回调线程。
        }
    }

    private static NodeWebView requireWebView(int webId) {
        NodeWebView web;
        synchronized (STATE) {
            web = WEB_VIEWS.get(webId);
        }
        if (web == null || web.isReleased()) {
            throw new IllegalStateException("WebView 不存在或已释放（webId=" + webId + "）");
        }
        return web;
    }

    private static void destroyWebViewsLocked() {
        if (WEB_VIEWS.isEmpty()) return;
        List<NodeWebView> closing = new ArrayList<>(WEB_VIEWS.values());
        WEB_VIEWS.clear();
        releaseWebViews(closing);
    }

    private static void destroyWebViewsOutsideLocked(View keepRoot) {
        List<NodeWebView> closing = new ArrayList<>();
        for (Map.Entry<Integer, NodeWebView> entry : new ArrayList<>(WEB_VIEWS.entrySet())) {
            NodeWebView web = entry.getValue();
            if (web == null || web.isReleased()) {
                WEB_VIEWS.remove(entry.getKey());
                continue;
            }
            if (!containsView(keepRoot, web)) {
                WEB_VIEWS.remove(entry.getKey());
                closing.add(web);
            }
        }
        releaseWebViews(closing);
    }

    private static boolean containsView(View root, View target) {
        if (root == null || target == null) return false;
        if (root == target) return true;
        if (!(root instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsView(group.getChildAt(i), target)) return true;
        }
        return false;
    }

    private static void releaseWebViews(final List<NodeWebView> views) {
        if (views == null || views.isEmpty()) return;
        Runnable release = new Runnable() {
            @Override
            public void run() {
                for (NodeWebView web : views) {
                    try {
                        web.release();
                    } catch (Throwable error) {
                        android.util.Log.w("NodeUiLocalSession", "释放 WebView 失败", error);
                    }
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            release.run();
        } else {
            MAIN.post(release);
        }
    }

    private static void runOnMainAndWait(final Runnable task, long timeoutMs, String label) {
        FutureTask<Void> future = new FutureTask<>(task, null);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }
        MAIN.post(future);
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("Node UI " + label + " 超时");
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException("Node UI " + label + " 失败：" + cause.getMessage(), cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Node UI " + label + " 被中断");
        }
    }
    public static void close() {
        closeOpening(null);
    }
    private static void closeOpening(Pending expected) {
        final Activity closing;
        synchronized (STATE) {
            if (expected != null && pending != expected) return;
            Pending opening = pending;
            closing = activity;
            pending = null;
            activity = null;
            content = null;
            views = null;
            callbacks = null;
            console = null;
            backHandled = false;
            destroyWebViewsLocked();
            if (opening != null) {
                opening.error = "Node UI 启动或运行已取消";
                opening.ready.countDown();
            }
        }
        // A later attach must see the invalidated token and finish itself. An already
        // attached Activity is captured here so its destroy cannot clear a newer session.
        if (closing != null) MAIN.post(() -> { if (!closing.isFinishing()) closing.finish(); });
    }

    /** Invoked on the Java IO executor, then publishes on Android's UI thread. */
    public static final class ImageLoader {
        private final java.util.Map<ImageView, Object> requests = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        public boolean loadImageInto(ImageView view, String address) throws Exception {
            Activity owner = activity;
            if (owner == null) throw new IllegalStateException("Node UI 已关闭");
            Object token = new Object();
            requests.put(view, token);
            URL url = new URL(address);
            if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
                throw new IllegalArgumentException("图片 URL 需要 http 或 https");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            Bitmap image;
            try (java.io.InputStream stream = connection.getInputStream()) {
                image = BitmapFactory.decodeStream(stream);
                if (image == null) throw new java.io.IOException("无法解码图片：" + address);
            } finally { connection.disconnect(); }
            FutureTask<Boolean> apply = new FutureTask<>(() -> {
                if (activity != owner || owner.isFinishing() || requests.get(view) != token) { image.recycle(); return false; }
                view.setImageBitmap(image);
                return true;
            });
            MAIN.post(apply);
            return apply.get(10, TimeUnit.SECONDS);
        }
    }
}
