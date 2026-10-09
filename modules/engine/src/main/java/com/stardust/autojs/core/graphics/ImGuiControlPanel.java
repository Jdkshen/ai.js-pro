package com.stardust.autojs.core.graphics;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** A bounded touch window. GL produces button IDs; the script polls them on its own thread. */
public final class ImGuiControlPanel implements AutoCloseable {
    static { System.loadLibrary("quickjs_jni"); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    private volatile Surface view;
    private WindowManager manager;
    private WindowManager.LayoutParams params;
    private volatile boolean closed, visible = true, touchable = true;
    private volatile int x, y, width, height;
    private int screenWidth, screenHeight, safeTop, safeBottom, columns;
    private float density;

    public ImGuiControlPanel(Context context) { this.context = context; }

    private boolean onMain(Runnable action) {
        if (closed) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) { action.run(); return !closed; }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        main.post(() -> { try { if (!closed) action.run(); done.complete(!closed); }
            catch (Throwable error) { done.completeExceptionally(error); } });
        try { return done.get(2000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); close(); return false; }
        catch (Exception error) { close(); return false; }
    }

    public boolean show(JSONObject options) throws JSONException {
        Packet initial = new Packet(options);
        boolean ok = onMain(() -> {
            Context owner = com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
            boolean trusted = owner != null;
            if (!trusted) {
                if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context))
                    throw new IllegalStateException("Overlay permission required");
                owner = context;
            }
            density = owner.getResources().getDisplayMetrics().density;
            manager = (WindowManager) owner.getSystemService(Context.WINDOW_SERVICE);
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            manager.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels;
            int statusId = owner.getResources().getIdentifier("status_bar_height", "dimen", "android");
            safeTop = statusId == 0 ? 0 : owner.getResources().getDimensionPixelSize(statusId);
            int navId = owner.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            safeBottom = navId == 0 ? 0 : owner.getResources().getDimensionPixelSize(navId);
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets insets = manager.getCurrentWindowMetrics().getWindowInsets().getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                safeTop = Math.max(safeTop, insets.top); safeBottom = Math.max(safeBottom, insets.bottom);
            }
            width = Math.min(screenWidth, Math.max(Math.round(160 * density), options.optInt("width", screenWidth - 24)));
            columns = Math.max(1, Math.min(options.optInt("columns", 6), (int) ((width / density - 12) / 48)));
            int rows = (initial.ids.length + columns - 1) / columns;
            height = Math.min(screenHeight - safeTop - safeBottom, Math.round((44 + rows * 52) * density));
            int type = trusted ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
            params = new WindowManager.LayoutParams(width, height, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            if (Build.VERSION.SDK_INT >= 28) params.layoutInDisplayCutoutMode = 1;
            params.x = clampX(options.optInt("x", 12)); params.y = clampY(options.optInt("y", safeTop));
            x = params.x; y = params.y;
            Surface next = new Surface(owner); next.packet = initial;
            view = next;
            manager.addView(next, params); next.onResume();
        });
        if (!ok) close();
        return ok;
    }

    public void update(JSONObject options) throws JSONException {
        Surface current = view;
        if (closed || current == null) return;
        if (options.has("buttons")) {
            Packet next = new Packet(options);
            if (next.ids.length != current.packet.ids.length) throw new JSONException("Panel button count is fixed until close");
            current.packet = next;
        }
        if (options.has("x") || options.has("y") || options.has("visible") || options.has("touchable")) onMain(() -> {
            if (view != current) return;
            params.x = clampX(options.optInt("x", x)); params.y = clampY(options.optInt("y", y));
            x = params.x; y = params.y;
            visible = options.optBoolean("visible", visible); touchable = options.optBoolean("touchable", touchable);
            if (!visible || !touchable) { current.cancelInput(); synchronized (events) { events.clear(); } }
            if (touchable && visible) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            current.setVisibility(visible ? View.VISIBLE : View.GONE);
            manager.updateViewLayout(current, params);
        });
        current.requestRender();
    }

    public String poll() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("ok", !closed && view != null); result.put("x", x); result.put("y", y);
        result.put("width", width); result.put("height", height); result.put("visible", visible);
        JSONArray pending = new JSONArray();
        synchronized (events) { while (!events.isEmpty()) pending.put(events.removeFirst()); }
        result.put("events", pending);
        return result.toString();
    }

    private int clampX(int value) { return Math.max(0, Math.min(screenWidth - width, value)); }
    private int clampY(int value) { return Math.max(safeTop, Math.min(screenHeight - safeBottom - height, value)); }
    private void emit(String id) {
        if (closed || !visible || !touchable) return;
        try {
            JSONObject event = new JSONObject().put("id", id).put("at", System.currentTimeMillis());
            synchronized (events) { if (events.size() >= 32) events.removeFirst(); events.addLast(event); }
        } catch (JSONException ignored) { }
    }

    @Override public void close() {
        closed = true;
        synchronized (events) { events.clear(); }
        main.post(() -> {
            Surface closing = view; view = null;
            if (closing == null) return;
            closing.cancelInput(); closing.queueEvent(closing::dispose); closing.onPause();
            try { manager.removeViewImmediate(closing); } catch (IllegalArgumentException ignored) { }
        });
    }

    private static final class Packet {
        final String title, binding; final String[] ids, labels; final boolean[] enabled;
        Packet(JSONObject options) throws JSONException {
            title = options.optString("title", "ImGui");
            JSONArray buttons = options.getJSONArray("buttons");
            if (buttons.length() < 1 || buttons.length() > 12) throw new JSONException("Panel needs 1..12 buttons");
            ids = new String[buttons.length()]; labels = new String[ids.length]; enabled = new boolean[ids.length];
            for (int i = 0; i < ids.length; i++) {
                JSONObject button = buttons.getJSONObject(i);
                ids[i] = button.getString("id"); labels[i] = button.optString("label", ids[i]);
                if (!ids[i].matches("[A-Za-z0-9_-]{1,64}")) throw new JSONException("Invalid panel button ID");
                for (int j = 0; j < i; j++) if (ids[i].equals(ids[j])) throw new JSONException("Duplicate panel button ID");
                enabled[i] = button.optBoolean("enabled", true);
            }
            binding = Arrays.toString(ids) + Arrays.toString(enabled);
        }
    }

    private static final class Input {
        final int action; final float x, y; final String binding;
        Input(int action, float x, float y, String binding) { this.action = action; this.x = x; this.y = y; this.binding = binding; }
    }

    private final class Surface extends GLSurfaceView implements GLSurfaceView.Renderer {
        private final ArrayDeque<Input> input = new ArrayDeque<>();
        private long handle;
        private int surfaceWidth, surfaceHeight;
        private boolean dragging, moved;
        private float downRawX, downRawY;
        private int downX, downY;
        volatile Packet packet;
        Surface(Context owner) {
            super(owner); setEGLContextClientVersion(3); setEGLConfigChooser(8, 8, 8, 8, 0, 0);
            getHolder().setFormat(PixelFormat.TRANSLUCENT); setZOrderOnTop(true);
            setRenderer(this); setRenderMode(RENDERMODE_WHEN_DIRTY);
        }
        void cancelInput() { synchronized (input) { input.clear(); input.add(new Input(-1, -1, -1, "")); } }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (closed || !visible || !touchable) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                dragging = event.getY() < 26 * density; moved = false;
                downRawX = event.getRawX(); downRawY = event.getRawY(); downX = params.x; downY = params.y;
            }
            if (dragging) {
                float dx = event.getRawX() - downRawX, dy = event.getRawY() - downRawY;
                if (action == MotionEvent.ACTION_MOVE && (moved || Math.hypot(dx, dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop())) {
                    moved = true; params.x = clampX(downX + Math.round(dx)); params.y = clampY(downY + Math.round(dy));
                    x = params.x; y = params.y; manager.updateViewLayout(this, params);
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (moved) emit("drag"); dragging = false;
                }
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) { cancelInput(); }
            else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                synchronized (input) {
                    if (input.size() >= 64) { input.clear(); input.add(new Input(-1, -1, -1, "")); }
                    input.add(new Input(action, event.getX(), event.getY(), packet.binding));
                }
            }
            requestRender(); return true;
        }
        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            dispose();
            if (closed) return;
            String font = ImGuiFont.resolve(getContext());
            if (closed) return;
            handle = nativeInit(font, 14 * density); cancelInput();
            if (closed) dispose();
        }
        @Override public void onSurfaceChanged(GL10 gl, int w, int h) { surfaceWidth = w; surfaceHeight = h; }
        @Override public void onDrawFrame(GL10 gl) {
            if (handle == 0 || closed) return;
            Packet snapshot = packet; Input next;
            synchronized (input) { next = input.pollFirst(); }
            int action = next == null ? -2 : next.binding.equals(snapshot.binding) ? next.action : -1;
            int clicked = nativeRender(handle, surfaceWidth, surfaceHeight, density, columns, snapshot.title,
                    snapshot.binding, snapshot.ids, snapshot.labels, snapshot.enabled, action, next == null ? 0 : next.x, next == null ? 0 : next.y);
            if (clicked >= 0 && clicked < snapshot.ids.length) emit(snapshot.ids[clicked]);
            // One input per frame preserves DOWN/UP even when Android delivers them together.
            if (next != null) requestRender();
        }
        void dispose() { if (handle != 0) { nativeShutdown(handle); handle = 0; } }
    }

    private static native long nativeInit(String fontPath, float fontSize);
    private static native int nativeRender(long handle, int width, int height, float density, int columns,
            String title, String binding, String[] ids, String[] labels, boolean[] enabled, int action, float x, float y);
    private static native void nativeShutdown(long handle);
}
