package com.stardust.autojs.core.graphics;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Noninteractive native drawing surface. Interactive controls belong in a bounded window. */
public final class ImGuiDrawingOverlay implements AutoCloseable {
    static { System.loadLibrary("quickjs_jni"); }
    private static final Set<ImGuiDrawingOverlay> ALL = Collections.synchronizedSet(new HashSet<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager manager;
    private volatile Surface view;
    private boolean shown;
    private volatile boolean closed;
    private final Context context;

    public ImGuiDrawingOverlay(Context context) { this.context = context; ALL.add(this); }
    public static void closeAll() {
        ImGuiDrawingOverlay[] overlays;
        synchronized (ALL) { overlays = ALL.toArray(new ImGuiDrawingOverlay[0]); }
        for (ImGuiDrawingOverlay overlay : overlays) overlay.close();
    }

    public boolean show() {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Runnable action = () -> {
            if (closed) { result.complete(false); return; }
            try {
                Context owner = com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
                boolean trusted = owner != null;
                if (!trusted) {
                    if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                        result.complete(false); return;
                    }
                    owner = context;
                }
                manager = (WindowManager) owner.getSystemService(Context.WINDOW_SERVICE);
                view = new Surface(owner);
                int type = trusted ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        : Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                // Untrusted application overlays need window alpha <=0.8 for Android 12+
                // touch delivery. Framebuffer transparency alone does not satisfy that rule.
                if (!trusted) params.alpha = 0.8f;
                if (Build.VERSION.SDK_INT >= 28) params.layoutInDisplayCutoutMode = 1;
                manager.addView(view, params);
                shown = true;
                view.onResume();
                result.complete(true);
            } catch (Throwable error) {
                android.util.Log.e("ImGuiDrawingOverlay", "Cannot create overlay", error);
                result.complete(false);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
        try {
            boolean success = result.get(2000, TimeUnit.MILLISECONDS);
            if (!success) close();
            return success;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); close(); return false;
        } catch (Exception error) { close(); return false; }
    }

    public void update(String shapesJson, String stats) throws JSONException {
        Surface current = view;
        if (closed || current == null) return;
        // The script submits data only. JSON parsing, allocation and rendering must
        // not hold up its observation loop (especially during ART compaction).
        if (current.pending.offer(shapesJson, stats)) current.requestRender();
    }

    private static Packet decode(String shapesJson, String stats) throws JSONException {
        JSONArray shapes = new JSONArray(shapesJson == null ? "[]" : shapesJson);
        int count = Math.min(2048, shapes.length());
        float[] data = new float[count * 8];
        int[] colors = new int[count];
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            JSONObject shape = shapes.getJSONObject(i);
            String kind = shape.optString("kind", "rect");
            int p = i * 8;
            data[p] = "line".equals(kind) ? 1 : "circle".equals(kind) ? 2 : "text".equals(kind) ? 3
                    : "arrow".equals(kind) ? 4 : "hud".equals(kind) ? 5 : 0;
            data[p + 1] = (float) shape.optDouble("x1"); data[p + 2] = (float) shape.optDouble("y1");
            data[p + 3] = (float) shape.optDouble("x2"); data[p + 4] = (float) shape.optDouble("y2");
            data[p + 5] = (float) shape.optDouble("arrow".equals(kind) ? "headSize" : "radius", "arrow".equals(kind) ? 18 : 8);
            data[p + 6] = (float) shape.optDouble("strokeWidth", 3);
            data[p + 7] = (float) shape.optDouble("size", 24);
            for (int j = 1; j < 8; j++) if (Float.isNaN(data[p + j]) || Float.isInfinite(data[p + j]))
                throw new JSONException("Non-finite shape coordinate");
            colors[i] = Color.GREEN;
            Object color = shape.opt("color");
            if (color instanceof Number) colors[i] = ((Number) color).intValue();
            else if (color instanceof String) try { colors[i] = Color.parseColor((String) color); } catch (IllegalArgumentException ignored) {}
            labels[i] = shape.optString("label", "");
        }
        return new Packet(data, colors, labels, stats == null ? "" : stats);
    }

    @Override public void close() {
        closed = true;
        ALL.remove(this);
        main.post(() -> {
            if (view == null) return;
            Surface closing = view;
            closing.retired = true;
            closing.pending.close();
            closing.queueEvent(closing::dispose);
            closing.onPause();
            if (shown) try { manager.removeViewImmediate(closing); } catch (IllegalArgumentException ignored) {}
            shown = false;
            view = null;
        });
    }

    private static final class Packet {
        final float[] data; final int[] colors; final String[] labels; final String stats;
        Packet(float[] data, int[] colors, String[] labels, String stats) {
            this.data = data; this.colors = colors; this.labels = labels; this.stats = stats;
        }
    }

    private static final class Surface extends GLSurfaceView implements GLSurfaceView.Renderer {
        private long handle;
        volatile boolean retired;
        private int width, height;
        private final int hudTop;
        private final int[] origin = new int[2];
        final LatestOverlayFrame pending = new LatestOverlayFrame();
        private Packet packet = new Packet(new float[0], new int[0], new String[0], "");
        private Packet lastRenderedPacket;
        Surface(Context context) {
            super(context);
            int status = 0;
            int resource = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resource != 0) status = context.getResources().getDimensionPixelSize(resource);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                status = Math.max(status, wm.getCurrentWindowMetrics().getWindowInsets()
                        .getInsets(android.view.WindowInsets.Type.statusBars()).top);
            }
            hudTop = status + Math.round(8 * context.getResources().getDisplayMetrics().density);
            setEGLContextClientVersion(3);
            setEGLConfigChooser(8, 8, 8, 8, 0, 0);
            getHolder().setFormat(PixelFormat.TRANSLUCENT);
            setZOrderOnTop(true);
            setRenderer(this);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
        }
        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            dispose();
            lastRenderedPacket = null;
            if (retired) return;
            String font = ImGuiFont.resolve(getContext());
            if (retired) return;
            handle = nativeInit(font, Math.max(18f, 12f * getResources().getDisplayMetrics().density));
            if (retired) dispose();
        }
        @Override public void onSurfaceChanged(GL10 gl, int width, int height) { this.width = width; this.height = height; }
        @Override public void onDrawFrame(GL10 gl) {
            if (handle == 0 || retired) return;
            LatestOverlayFrame.Request request = pending.take();
            if (request != null) try {
                packet = decode(request.shapes, request.stats);
            } catch (JSONException error) {
                // A malformed new request leaves the last completed frame visible.
                android.util.Log.w("ImGuiDrawingOverlay", "Invalid drawing request", error);
            }
            getLocationOnScreen(origin);
            Packet snapshot = packet;
            nativeRender(handle, width, height, origin[0], origin[1], hudTop, snapshot.data, snapshot.colors, snapshot.labels, snapshot.stats);
            // Auto-sized ImGui windows measure themselves on their first frame.
            // A second dirty frame displays a static HUD without a continuous loop.
            if (lastRenderedPacket != snapshot) { lastRenderedPacket = snapshot; requestRender(); }
        }
        void dispose() { if (handle != 0) { nativeShutdown(handle); handle = 0; } }
    }

    private static native long nativeInit(String fontPath, float fontSize);
    private static native void nativeRender(long handle, int width, int height, int offsetX, int offsetY, int hudTop,
                                           float[] data, int[] colors, String[] labels, String stats);
    private static native void nativeShutdown(long handle);
}
