package com.stardust.autojs.engine.node24;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Real Views and Java callbacks share an existing Node pool process. */
public final class NodeUiLocalSession {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object STATE = new Object();
    private static volatile Pending pending;
    private static volatile Activity activity;
    private static volatile View content;
    private static volatile JSONObject views;
    private static volatile com.stardust.autojs.core.console.ConsoleImpl console;
    private static volatile ActivityCallbacks callbacks;
    private static final java.util.ArrayDeque<JSONObject> EVENTS = new java.util.ArrayDeque<>();
    private static final ImageLoader IMAGE_LOADER = new ImageLoader();
    private NodeUiLocalSession() {}
    private static final class Pending {
        final CountDownLatch ready = new CountDownLatch(1);
        final String token = java.util.UUID.randomUUID().toString();
        volatile String error;
    }

    public static JSONObject show(Context context, String projectDir, JSONObject request) throws Exception {
        Pending opening = new Pending();
        synchronized (STATE) {
            if (pending != null || activity != null) throw new IllegalStateException("请先关闭当前 Node UI");
            pending = opening;
            console = null;
            callbacks = null;
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
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            intent.putExtra("projectDir", projectDir);
            intent.putExtra("layoutId", request.optInt("layoutId"));
            intent.putExtra("layoutXml", request.optString("layoutXml"));
            intent.putExtra("token", opening.token);
            intent.putExtra("javaClassOwner", request.optString("javaClassOwner"));
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
            activity = source;
            content = root;
            views = ids;
            event("create"); event("start"); event("resume"); event("content_view_set");
            opening.ready.countDown();
            return true;
        }
    }
    public static void failed(String token, Throwable failure) {
        synchronized (STATE) {
            Pending opening = pending;
            if (opening != null && opening.token.equals(token)) {
                opening.error = String.valueOf(failure);
                opening.ready.countDown();
            }
        }
    }
    public static void destroyed(Activity source) {
        synchronized (STATE) {
            if (activity != source) return;
            if (pending != null) {
                pending.error = "Node UI 已关闭";
                pending.ready.countDown();
            }
            activity = null; content = null; pending = null; callbacks = null;
            event("pause"); event("stop"); event("destroy");
            views = null;
            console = null;
        }
    }
    public static void event(String type) {
        try {
            JSONObject event = new JSONObject().put("type", type);
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
    public static void setContentView(View root) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("setContentView 需要主线程");
        Activity owner = activity;
        if (owner == null || owner.isFinishing()) throw new IllegalStateException("Node UI 已关闭");
        owner.setContentView(root);
        synchronized (STATE) {
            if (activity != owner) return;
            content = root;
            event("content_view_set");
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
