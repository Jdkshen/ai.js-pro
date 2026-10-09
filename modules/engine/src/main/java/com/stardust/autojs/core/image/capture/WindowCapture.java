package com.stardust.autojs.core.image.capture;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.stardust.autojs.core.image.ImageWrapper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit window-only capture; never substitutes a full-display screenshot. */
public final class WindowCapture {
    private static final AtomicLong NEXT_FRAME = new AtomicLong();
    private WindowCapture() {}

    public static boolean isAvailable() {
        return Build.VERSION.SDK_INT >= 34 && AccessibilityScreenCapturer.isAvailable();
    }

    private static AccessibilityService service() {
        if (!isAvailable()) throw new IllegalStateException("窗口截图需要 Android 14+，并开启具有截图能力的无障碍服务");
        return com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
    }

    public static String list(Context context) {
        JSONArray result = new JSONArray();
        int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        List<AccessibilityWindowInfo> windows = service().getWindows();
        try {
            boolean ownedOverlayActive = ownedOverlayActive(context, windows);
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                Target target = new Target(window);
                target.foreground = target.active || (ownedOverlayActive && target.focused);
                if (!context.getPackageName().equals(target.packageName)) result.put(target.json().put("rotation", rotation));
            }
            return result.toString();
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private static Target target(Context context, int windowId, String packageName) {
        List<AccessibilityWindowInfo> windows = service().getWindows();
        Target selected = null;
        try {
            boolean ownedOverlayActive = ownedOverlayActive(context, windows);
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (windowId >= 0 && window.getId() != windowId) continue;
                Target candidate = new Target(window);
                candidate.foreground = candidate.active || (ownedOverlayActive && candidate.focused);
                if (context.getPackageName().equals(candidate.packageName)) continue;
                if (packageName != null && !packageName.isEmpty() && !packageName.equals(candidate.packageName)) continue;
                // Automatic selection must identify the foreground app. An explicit ID
                // also works when a game's accessibility root is not readable.
                if (windowId < 0 && (!candidate.foreground || candidate.packageName == null)) continue;
                if (candidate.displayId != 0 || candidate.bounds.isEmpty()) continue;
                if (selected == null || candidate.layer > selected.layer) selected = candidate;
            }
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
        if (selected == null) throw new IllegalStateException("找不到目标应用窗口；请切到游戏，或用 images.listCaptureWindows() 获取窗口 ID");
        return selected;
    }

    /** Touching our non-focusable overlay changes the accessibility active window,
     * while input focus stays with the game. Only accept that focused app when
     * the active overlay is verified to belong to this runtime's package. */
    private static boolean ownedOverlayActive(Context context, List<AccessibilityWindowInfo> windows) {
        for (AccessibilityWindowInfo window : windows) {
            if (!window.isActive() || (window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    && window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM)) continue;
            AccessibilityNodeInfo root = window.getRoot();
            try {
                if (root != null && context.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return true;
            } finally { if (root != null) root.recycle(); }
        }
        return false;
    }

    public static Frame capture(Context context, int windowId, String packageName, long timeout, boolean screenCoordinates) {
        if (timeout <= 0 || timeout > 5000) throw new IllegalArgumentException("窗口截图 timeout 必须在 1..5000 ms");
        Target before = target(context, windowId, packageName);
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point display = new Point();
        wm.getDefaultDisplay().getRealSize(display);
        int rotation = wm.getDefaultDisplay().getRotation();
        Bitmap local;
        long timestamp;
        try (AccessibilityScreenCapturer.HardwareFrame hardware =
                     new AccessibilityScreenCapturer().captureWindowHardware(before.id, timeout)) {
            local = hardware.toBitmap();
            timestamp = SystemClock.elapsedRealtime();
        }
        try {
            Target after = target(context, before.id, packageName);
            if (!before.bounds.equals(after.bounds) || !java.util.Objects.equals(before.packageName, after.packageName)
                    || rotation != wm.getDefaultDisplay().getRotation()) {
                throw new IllegalStateException("截图期间窗口位置或方向改变，本帧已丢弃；请重新抓帧");
            }
            JSONObject metadata = before.json();
            metadata.put("frameId", NEXT_FRAME.incrementAndGet());
            metadata.put("timestamp", timestamp);
            metadata.put("coordinateSpace", screenCoordinates ? "screen" : "window");
            metadata.put("displayWidth", display.x).put("displayHeight", display.y).put("rotation", rotation);
            metadata.put("isolated", true);
            metadata.put("windowImageWidth", local.getWidth()).put("windowImageHeight", local.getHeight());
            Bitmap result = local;
            if (screenCoordinates) {
                // Preserve existing chess geometry/click coordinates. Pixels outside the
                // target window are black, never from the display or another overlay.
                result = Bitmap.createBitmap(display.x, display.y, Bitmap.Config.ARGB_8888);
                try {
                    result.eraseColor(android.graphics.Color.BLACK);
                    new Canvas(result).drawBitmap(local, null, before.bounds, new Paint(Paint.FILTER_BITMAP_FLAG));
                } catch (Throwable error) {
                    result.recycle();
                    throw error;
                }
                local.recycle();
            }
            ImageWrapper image = ImageWrapper.ofBitmap(result);
            local = null; // transfer ownership to the returned frame
            return new Frame(image, metadata.toString());
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        } finally {
            if (local != null && !local.isRecycled()) local.recycle();
        }
    }

    public static final class Frame {
        public final ImageWrapper image;
        public final String metadata;
        Frame(ImageWrapper image, String metadata) { this.image = image; this.metadata = metadata; }
    }

    private static final class Target {
        final int id, layer, displayId;
        final boolean active, focused;
        boolean foreground;
        final Rect bounds = new Rect();
        final String packageName;
        Target(AccessibilityWindowInfo window) {
            id = window.getId(); layer = window.getLayer(); displayId = window.getDisplayId();
            active = window.isActive(); focused = window.isFocused(); foreground = active; window.getBoundsInScreen(bounds);
            AccessibilityNodeInfo root = window.getRoot();
            try { packageName = root == null || root.getPackageName() == null ? null : root.getPackageName().toString(); }
            finally { if (root != null) root.recycle(); }
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("windowId", id).put("packageName", packageName == null ? JSONObject.NULL : packageName)
                    .put("active", active).put("focused", focused).put("foreground", foreground).put("layer", layer).put("displayId", displayId)
                    .put("bounds", new JSONObject().put("left", bounds.left).put("top", bounds.top)
                            .put("right", bounds.right).put("bottom", bounds.bottom).put("width", bounds.width()).put("height", bounds.height()));
        }
    }
}
