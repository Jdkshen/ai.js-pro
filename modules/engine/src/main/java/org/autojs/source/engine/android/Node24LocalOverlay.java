package org.autojs.source.engine.android;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.stardust.autojs.engine.node24.NodeUiHost;
import com.stardust.autojs.overlay.OverlayRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * task-201：:node24 进程内 floatingWindow overlay。
 *
 * <p>大 XML 走主进程 show 时回复会被 Binder/15s 超时丢弃；这里在 Node 进程内直接
 * inflate 真实 View + WindowManager 挂载，点击/触摸/拖动全部本地处理，不再跨进程。
 * 仅接管 op=floatingWindow；任何失败返回 null，由 Node24HostBridge 回退主进程 overlay。
 */
public final class Node24LocalOverlay {
    private static final String TAG = "Node24LocalOverlay";
    private static final Map<String, Overlay> OVERLAYS = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final int TOUCH_SLOP = 8;

    private Node24LocalOverlay() {
    }

    /** 返回 null 表示未接管/失败，调用方回退主进程 NodeUiBridgeService。 */
    public static JSONObject handle(Context context, String projectDir, JSONObject request) throws Exception {
        if (request == null || !"floatingWindow".equals(request.optString("op", ""))) {
            return null;
        }
        String action = request.optString("action", "show");
        if ("show".equals(action) && request.optString("overlayId", "").isEmpty()) {
            try {
                return show(context, projectDir, request);
            } catch (Throwable error) {
                Log.w(TAG, "local overlay show failed, fallback main: " + error, error);
                return null;
            }
        }
        if ("info".equals(action)) {
            Overlay last = latest();
            if (last == null) return null;
            try { return last.info(); } catch (Throwable error) { return null; }
        }
        if ("closeAll".equals(action)) {
            closeAll();
            return ok();
        }
        String id = request.optString("overlayId", "");
        Overlay overlay = OVERLAYS.get(id);
        if (overlay == null) return null;
        try {
            return overlay.handle(action, request);
        } catch (Throwable error) {
            Log.w(TAG, "local overlay action failed: " + action, error);
            return new JSONObject().put("ok", false).put("error", String.valueOf(error.getMessage()));
        }
    }

    public static boolean hasOverlay() {
        return !OVERLAYS.isEmpty();
    }

    /** 供 Node24HostBridge 把真实 View 注册成 Java 对象句柄（binding.raw()）。 */
    public static View findRawView(String overlayId, int viewId) {
        if (overlayId == null || overlayId.isEmpty()) return null;
        Overlay overlay = OVERLAYS.get(overlayId);
        if (overlay == null) return null;
        Object result = runOnMain(() -> overlay.rawView(viewId), 2000L);
        return result instanceof View ? (View) result : null;
    }

    public static void closeAll() {
        // 快照遍历：close() 会从 OVERLAYS 移除自身。
        for (Overlay overlay : new ArrayList<>(OVERLAYS.values())) {
            overlay.close();
        }
        OVERLAYS.clear();
    }

    private static Overlay latest() {
        Overlay found = null;
        for (Overlay overlay : OVERLAYS.values()) found = overlay;
        return found;
    }

    private static JSONObject show(Context context, String projectDir, JSONObject request) throws Exception {
        if (context == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(context)) {
            return null;
        }
        final String xml = request.optString("xml", "").trim();
        final String text = request.optString("text", "Node24");
        final int x = request.optInt("x", 100);
        final int y = request.optInt("y", 200);
        final int width = request.optInt("width", 0);
        final int height = request.optInt("height", 0);
        final int flags = request.optInt("flags", 0);
        // task-218：owner 由 Node24HostBridge 注入；touchable 默认 true，绘制型显式 false。
        final int ownerEngineId = request.optInt("engineId", request.optInt("ownerEngineId", -1));
        final String ownerTag = request.optString("owner", "");
        final boolean touchable = request.optBoolean("touchable", true);
        final Object result = runOnMain(() -> {
            try {
                Context app = context.getApplicationContext();
                WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return null;
                Overlay overlay = new Overlay(app, wm, "local-" + SEQ.incrementAndGet(),
                        ownerEngineId, ownerTag, touchable);
                overlay.build(xml, text, x, y, width, height, flags, touchable);
                OVERLAYS.put(overlay.id, overlay);
                return overlay.info();
            } catch (Throwable error) {
                Log.w(TAG, "create local overlay failed", error);
                return null;
            }
        }, 5000L);
        return result instanceof JSONObject ? (JSONObject) result : null;
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    private interface MainTask {
        Object run() throws Exception;
    }

    static Object runOnMain(MainTask task, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return task.run(); } catch (Throwable error) { Log.w(TAG, "main task failed", error); return null; }
        }
        final AtomicReference<Object> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try { result.set(task.run()); } catch (Throwable error) { Log.w(TAG, "main task failed", error); }
            finally { latch.countDown(); }
        });
        try {
            latch.await(Math.max(300L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private static final class Overlay {
        final Context context;
        final WindowManager wm;
        final String id;
        final int ownerEngineId;
        final String ownerTag;
        boolean touchable;
        final ArrayDeque<JSONObject> events = new ArrayDeque<>();
        FrameLayout root;
        View content;
        WindowManager.LayoutParams params;
        JSONObject views = new JSONObject();
        int dragAnchor;
        int downX, downY, lastX, lastY;
        boolean moved;
        int downViewId;
        boolean dragCandidate;
        long lastMoveForwardAt;
        long lastDragForwardAt;

        Overlay(Context context, WindowManager wm, String id, int ownerEngineId, String ownerTag,
                boolean touchable) {
            this.context = context;
            this.wm = wm;
            this.id = id;
            this.ownerEngineId = ownerEngineId;
            this.ownerTag = ownerTag == null ? "" : ownerTag;
            this.touchable = touchable;
        }

        void build(String xml, String text, int x, int y, int width, int height, int flags,
                   boolean touchable) throws Exception {
            root = new FrameLayout(context) {
                @Override public boolean dispatchTouchEvent(MotionEvent ev) {
                    onTouch(ev);
                    return super.dispatchTouchEvent(ev);
                }
            };
            if (xml != null && !xml.isEmpty()) {
                int themeRes = context.getApplicationInfo().theme;
                if (themeRes == 0) themeRes = android.R.style.Theme_Material_Light_NoActionBar;
                Log.i(TAG, "C-LOCAL inflate theme=0x" + Integer.toHexString(themeRes) + " xmlLen=" + xml.length());
                android.view.ContextThemeWrapper themed = new android.view.ContextThemeWrapper(context, themeRes);
                content = NodeUiHost.inflateInlineLayout(themed, xml);
                if (content == null) throw new IllegalStateException("inflateInlineLayout returned null");
                Object rawViews = NodeUiHost.buildInlineViews(content, xml);
                if (rawViews instanceof JSONObject) views = (JSONObject) rawViews;
                root.addView(content, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            } else {
                TextView tv = new TextView(context);
                tv.setText(text == null ? "Node24" : text);
                tv.setTextSize(16);
                tv.setTextColor(0xFFFFFFFF);
                tv.setBackgroundColor(0xCC222222);
                int pad = Math.round(12 * context.getResources().getDisplayMetrics().density);
                tv.setPadding(pad, pad, pad, pad);
                content = tv;
                root.addView(tv, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            }
            params = new WindowManager.LayoutParams(
                    width > 0 ? width : WindowManager.LayoutParams.WRAP_CONTENT,
                    height > 0 ? height : WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            // task-218：touchable 语义显式化；绘制型 overlay 传 touchable=false 才加 NOT_TOUCHABLE，
            // 交互型默认去掉，不再无条件强制可触摸。
            params.flags |= flags;
            if (touchable) {
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            if (!touchable) {
                // 绘制型 overlay：不接收点击/焦点（规格 3.2）。
                root.setClickable(false);
                root.setFocusable(false);
                root.setFocusableInTouchMode(false);
            }
            params.x = x;
            params.y = y;
            wm.addView(root, params);
            // task-218：所有本地 overlay 统一登记，脚本结束/引擎清理时可 closeByOwner。
            OverlayRegistry.register(ownerEngineId, ownerTag, "nodejs",
                    OverlayRegistry.Kind.NODE_UI, root, wm, touchable);
            // 大 XML/weighted 布局首次测量可能偏小；在本地进程内补一次限高 remeasure。
            root.post(() -> {
                try {
                    int maxH = context.getResources().getDisplayMetrics().heightPixels - params.y - 80;
                    if (maxH < 200) maxH = 200;
                    content.measure(View.MeasureSpec.makeMeasureSpec(
                                    context.getResources().getDisplayMetrics().widthPixels,
                                    View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
                    int needed = Math.min(content.getMeasuredHeight(), maxH);
                    if (needed > 0 && needed > root.getHeight()) {
                        params.height = needed;
                        wm.updateViewLayout(root, params);
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "local remeasure failed", error);
                }
            });
        }

        JSONObject info() throws Exception {
            JSONObject response = new JSONObject().put("ok", true).put("overlayId", id);
            response.put("views", views == null ? new JSONObject() : views);
            return response;
        }

        JSONObject handle(String action, JSONObject request) throws Exception {
            switch (action) {
                case "viewCommand":
                    return viewCommand(request);
                case "enableDrag":
                    dragAnchor = request.optInt("viewId", 0);
                    Log.i(TAG, "C-LOCAL drag anchor=" + dragAnchor);
                    return ok();
                case "move": {
                    final int nx = request.optInt("x", params.x);
                    final int ny = request.optInt("y", params.y);
                    runOnMain(() -> {
                        params.x = nx; params.y = ny;
                        try { if (root != null && root.getParent() != null) wm.updateViewLayout(root, params); } catch (Throwable ignored) { }
                        return null;
                    }, 2000L);
                    return ok();
                }
                case "hide": {
                    runOnMain(() -> {
                        try { if (root != null && root.getParent() != null) wm.removeView(root); } catch (Throwable ignored) { }
                        return null;
                    }, 2000L);
                    return ok();
                }
                case "show": {
                    final int sx = request.optInt("x", params.x);
                    final int sy = request.optInt("y", params.y);
                    final int sw = request.optInt("width", params.width);
                    final int sh = request.optInt("height", params.height);
                    runOnMain(() -> {
                        params.x = sx; params.y = sy;
                        if (sw > 0) params.width = sw;
                        if (sh > 0) params.height = sh;
                        try { if (root != null && root.getParent() == null) wm.addView(root, params); } catch (Throwable error) { Log.w(TAG, "local overlay re-show failed", error); }
                        return null;
                    }, 2000L);
                    return ok();
                }
                case "resize": {
                    final int newWidth = request.optInt("width", 0);
                    final int newHeight = request.optInt("height", 0);
                    runOnMain(() -> {
                        if (newWidth > 0) params.width = newWidth;
                        if (newHeight > 0) params.height = newHeight;
                        try { if (root != null && root.getParent() != null) wm.updateViewLayout(root, params); } catch (Throwable ignored) { }
                        return null;
                    }, 2000L);
                    return ok();
                }
                case "setTouchable": {
                    final boolean nextTouchable = request.optBoolean("touchable", true);
                    runOnMain(() -> {
                        touchable = nextTouchable;
                        if (nextTouchable) {
                            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                        } else {
                            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                        }
                        if (root != null) {
                            root.setClickable(nextTouchable);
                            root.setFocusable(nextTouchable);
                            root.setFocusableInTouchMode(nextTouchable);
                        }
                        wm.updateViewLayout(root, params);
                        return null;
                    }, 2000L);
                    return ok();
                }
                case "metrics":
                    return metrics();
                case "addFlags":
                    params.flags |= request.optInt("flags", 0);
                    if (!touchable) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    break;
                case "removeFlags":
                    params.flags &= ~request.optInt("flags", 0);
                    break;
                case "setFlags":
                    params.flags = request.optInt("flags", 0)
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                    if (!touchable) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    break;
                case "pollEvents": {
                    JSONArray array = new JSONArray();
                    synchronized (events) {
                        while (!events.isEmpty()) array.put(events.removeFirst());
                    }
                    if (array.length() > 0) Log.i(TAG, "C-LOCAL poll events=" + array.length());
                    return new JSONObject().put("ok", true).put("events", array);
                }
                case "close":
                    close();
                    return ok();
                case "clear":
                    close();
                    return ok();
                default:
                    return new JSONObject().put("ok", false).put("error", "local overlay 不支持 action=" + action);
            }
            runOnMain(() -> { wm.updateViewLayout(root, params); return null; }, 2000L);
            return ok();
        }

        View rawView(int viewId) {
            if (viewId <= 0) return content != null ? content : root;
            return NodeUiHost.findInlineViewById(content, viewId);
        }

        JSONObject metrics() throws Exception {
            Object result = runOnMain(() -> {
                JSONObject response = new JSONObject().put("ok", true).put("overlayId", id);
                int[] location = new int[2];
                int width = 0;
                int height = 0;
                if (root != null) {
                    root.getLocationOnScreen(location);
                    width = root.getWidth();
                    height = root.getHeight();
                    if (width <= 0 || height <= 0) {
                        width = root.getMeasuredWidth();
                        height = root.getMeasuredHeight();
                    }
                }
                response.put("x", location[0]).put("y", location[1]);
                response.put("width", width).put("height", height);
                response.put("contentWidth", content == null ? 0 : content.getWidth());
                response.put("contentHeight", content == null ? 0 : content.getHeight());
                response.put("visible", root != null && root.isShown());
                return response;
            }, 2000L);
            return result instanceof JSONObject ? (JSONObject) result : new JSONObject().put("ok", false);
        }

        JSONObject viewCommand(JSONObject request) throws Exception {
            Object result = runOnMain(() -> viewCommandOnMain(request), 5000L);
            return result instanceof JSONObject ? (JSONObject) result
                    : new JSONObject().put("ok", false).put("error", "本地 viewCommand 超时");
        }

        private JSONObject viewCommandOnMain(JSONObject request) throws Exception {
            int viewId = request.optInt("viewId", 0);
            String command = request.optString("command", "");
            View target = NodeUiHost.findInlineViewById(content, viewId);
            if (target == null && content != null && content.getId() == viewId) target = content;
            if (target == null) return new JSONObject().put("ok", false).put("error", "找不到 viewId=" + viewId);
            if ("click".equals(command)) {
                target.performClick();
                enqueueClick(viewId, 0, 0);
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("setText".equals(command)) {
                if (target instanceof TextView) ((TextView) target).setText(request.optString("value", ""));
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("getText".equals(command)) {
                String value = target instanceof TextView ? ((TextView) target).getText().toString() : "";
                return new JSONObject().put("ok", true).put("value", value);
            }
            if ("setVisibility".equals(command)) {
                target.setVisibility(request.optInt("value", View.VISIBLE));
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("setEnabled".equals(command)) {
                target.setEnabled(request.optBoolean("value", true));
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("setClickable".equals(command)) {
                target.setClickable(request.optBoolean("value", true));
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("setChecked".equals(command)) {
                try {
                    View.class.getMethod("setChecked", boolean.class).invoke(target, request.optBoolean("value", false));
                } catch (Throwable ignored) { }
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("getChecked".equals(command)) {
                return new JSONObject().put("ok", true).put("value", false);
            }
            if ("setAlpha".equals(command)) {
                target.setAlpha((float) request.optDouble("value", 1d));
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("setBackgroundColor".equals(command)) {
                target.setBackgroundColor(request.optInt("value", 0));
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("setTextColor".equals(command)) {
                if (target instanceof TextView) ((TextView) target).setTextColor(request.optInt("value", 0));
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("setPadding".equals(command)) {
                target.setPadding(request.optInt("left", 0), request.optInt("top", 0),
                        request.optInt("right", 0), request.optInt("bottom", 0));
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("setLayoutParams".equals(command)) {
                ViewGroup.LayoutParams layoutParams = target.getLayoutParams();
                if (layoutParams != null) {
                    int width = request.optInt("width", layoutParams.width);
                    int height = request.optInt("height", layoutParams.height);
                    if (width >= 0) layoutParams.width = width;
                    if (height >= 0) layoutParams.height = height;
                    target.setLayoutParams(layoutParams);
                    target.requestLayout();
                }
                return new JSONObject().put("ok", true).put("value", true);
            }
            if ("getWidth".equals(command)) {
                return new JSONObject().put("ok", true).put("value", target.getWidth());
            }
            if ("getHeight".equals(command)) {
                return new JSONObject().put("ok", true).put("value", target.getHeight());
            }
            if ("getLocationOnScreen".equals(command)) {
                int[] location = new int[2];
                target.getLocationOnScreen(location);
                return new JSONObject().put("ok", true).put("x", location[0]).put("y", location[1]);
            }
            return new JSONObject().put("ok", false).put("error", "不支持 command=" + command);
        }

        void onTouch(MotionEvent ev) {
            int action = ev.getActionMasked();
            int x = Math.round(ev.getRawX());
            int y = Math.round(ev.getRawY());
            if (action == MotionEvent.ACTION_DOWN) {
                downX = lastX = x;
                downY = lastY = y;
                moved = false;
                downViewId = findViewIdAt(root, x, y, true);
                if (downViewId == 0) downViewId = findViewIdAt(root, x, y, false);
                dragCandidate = dragAnchor != 0 && contains(dragAnchor, x, y);
                Log.i(TAG, "C-LOCAL touch down raw=" + x + "," + y + " viewId=" + downViewId
                        + " dragCandidate=" + dragCandidate + " root=" + root.getWidth() + "x" + root.getHeight());
                enqueueTouch("down", x, y);
                return;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (dragCandidate) {
                    int dx = x - lastX, dy = y - lastY;
                    if (Math.abs(x - downX) > TOUCH_SLOP || Math.abs(y - downY) > TOUCH_SLOP) moved = true;
                    if (moved) {
                        params.x += dx;
                        params.y += dy;
                        try { wm.updateViewLayout(root, params); } catch (Throwable ignored) { }
                        long nowDrag = SystemClock.uptimeMillis();
                        if (nowDrag - lastDragForwardAt >= 16L) {
                            lastDragForwardAt = nowDrag;
                            enqueueDrag(params.x, params.y);
                        }
                        Log.i(TAG, "C-LOCAL drag move x=" + params.x + " y=" + params.y);
                    }
                    lastX = x; lastY = y;
                    return;
                }
                long nowMove = SystemClock.uptimeMillis();
                if (nowMove - lastMoveForwardAt >= 16L) {
                    lastMoveForwardAt = nowMove;
                    enqueueTouch("move", x, y);
                }
                return;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                enqueueTouch(action == MotionEvent.ACTION_UP ? "up" : "cancel", x, y);
                if (action == MotionEvent.ACTION_UP && !moved && downViewId != 0) {
                    enqueueClick(downViewId, x, y);
                    Log.i(TAG, "C-LOCAL click queued viewId=" + downViewId + " raw=" + x + "," + y);
                }
                dragCandidate = false;
            }
        }

        boolean contains(int viewId, int screenX, int screenY) {
            View target = NodeUiHost.findInlineViewById(content, viewId);
            if (target == null || !target.isShown()) return false;
            int[] loc = new int[2];
            target.getLocationOnScreen(loc);
            return screenX >= loc[0] && screenX < loc[0] + target.getWidth()
                    && screenY >= loc[1] && screenY < loc[1] + target.getHeight();
        }

        int findViewIdAt(View view, int screenX, int screenY, boolean clickable) {
            if (view == null || view.getVisibility() != View.VISIBLE) return 0;
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            if (screenX < loc[0] || screenX >= loc[0] + view.getWidth()
                    || screenY < loc[1] || screenY >= loc[1] + view.getHeight()) return 0;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    int hit = findViewIdAt(group.getChildAt(i), screenX, screenY, clickable);
                    if (hit != 0) return hit;
                }
            }
            if (!clickable || view.isClickable()) return view.getId();
            return 0;
        }

        void enqueueTouch(String action, int x, int y) {
            try {
                synchronized (events) {
                    events.addLast(new JSONObject().put("type", "touch").put("overlayId", id)
                            .put("action", action).put("x", x).put("y", y).put("time", System.currentTimeMillis()));
                }
            } catch (Throwable ignored) { }
        }

        void enqueueDrag(int x, int y) {
            try {
                synchronized (events) {
                    events.addLast(new JSONObject().put("type", "drag").put("overlayId", id)
                            .put("x", x).put("y", y));
                }
            } catch (Throwable ignored) { }
        }

        void enqueueClick(int viewId, int x, int y) {
            try {
                synchronized (events) {
                    events.addLast(new JSONObject().put("type", "click").put("overlayId", id)
                            .put("viewId", viewId).put("x", x).put("y", y));
                }
            } catch (Throwable ignored) { }
        }

        void close() {
            OVERLAYS.remove(id);
            try { OverlayRegistry.unregister(root); } catch (Throwable ignored) { }
            runOnMain(() -> {
                try { if (root != null) wm.removeView(root); } catch (Throwable ignored) { }
                return null;
            }, 2000L);
        }
    }
}
