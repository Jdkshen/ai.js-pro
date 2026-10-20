// SPDX-License-Identifier: MIT
package com.stardust.autojs.core.image.capture;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 自动点掉系统「共享屏幕」授权流程（Android 14/15：下一步 → 选择应用 → 开始共享）。
 * 只在能读到系统对话框根节点、且命中已知按钮文案时才点击，普通场景不会误点。
 */
public final class ScreenCaptureAutoConsent {

    private static final String TAG = "MediaProjectionAutoConsent";
    private static final long POLL_MS = 350L;
    private static final long DEADLINE_MS = 8_000L;
    private static boolean sArmed;
    private static long sArmedAt;

    private ScreenCaptureAutoConsent() { }

    /** 在 startActivityForResult(createScreenCaptureIntent()) 之前调用。 */
    public static synchronized void arm() {
        sArmed = true;
        sArmedAt = System.currentTimeMillis();
        Log.i(TAG, "armed");
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(ScreenCaptureAutoConsent::tick, POLL_MS);
        // 读不到系统节点时的坐标兜底（仅授权窗口存在期间有效；结果返回会立刻 sArmed=false 取消）。
        final android.util.DisplayMetrics metrics = armDisplayMetrics();
        handler.postDelayed(() -> blindTap(metrics, 0.80f, 0.708f), 1300L);
        handler.postDelayed(() -> blindTap(metrics, 0.12f, 0.589f), 2600L);
    }

    private static android.util.DisplayMetrics armDisplayMetrics() {
        AccessibilityService service = com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
        if (service != null) return service.getResources().getDisplayMetrics();
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        metrics.widthPixels = 1080;
        metrics.heightPixels = 2400;
        return metrics;
    }

    private static synchronized boolean consumeArmed() {
        if (!sArmed) return false;
        if (System.currentTimeMillis() - sArmedAt > DEADLINE_MS) {
            sArmed = false;
            return false;
        }
        return true;
    }

    private static void blindTap(android.util.DisplayMetrics metrics, float relX, float relY) {
        if (!consumeArmed()) return;
        AccessibilityService service = com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
        if (service == null) return;
        float x = Math.round(metrics.widthPixels * relX);
        float y = Math.round(metrics.heightPixels * relY);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        android.accessibilityservice.GestureDescription gesture =
                new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 40L))
                        .build();
        boolean ok = service.dispatchGesture(gesture, null, null);
        Log.i(TAG, "blind tap (" + x + "," + y + ") ok=" + ok);
    }

    private static void tick() {
        if (!sArmed) return;
        if (System.currentTimeMillis() - sArmedAt > DEADLINE_MS) {
            sArmed = false;
            Log.i(TAG, "timeout, stop");
            return;
        }
        AccessibilityService service = com.stardust.view.accessibility.AccessibilityService.Companion.getInstance();
        AccessibilityNodeInfo root = service == null ? null : service.getRootInActiveWindow();
        boolean didSomething = false;
        if (root != null) {
            String pkg = String.valueOf(root.getPackageName());
            if (pkg.contains("systemui") || pkg.contains("permission")) {
                if (clickText(root, "下一步") || clickText(root, "开始共享") || clickText(root, "立即开始")) {
                    didSomething = true;
                    Log.i(TAG, "clicked next/share in " + pkg);
                } else if (clickText(root, "天天象棋")) {
                    didSomething = true;
                    Log.i(TAG, "selected app in " + pkg);
                }
            }
            if (!didSomething && (pkg.contains("systemui") || pkg.contains("launcher"))) {
                if (clickText(root, "共享整个屏幕")) {
                    didSomething = true;
                    Log.i(TAG, "switched to entire screen");
                }
            }
            root.recycle();
        }
        new Handler(Looper.getMainLooper()).postDelayed(ScreenCaptureAutoConsent::tick, didSomething ? 700L : POLL_MS);
    }

    public static synchronized void disarm() {
        if (sArmed) Log.i(TAG, "disarmed");
        sArmed = false;
    }

    private static boolean clickText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null) return false;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            if (node.isClickable()) {
                boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                node.recycle();
                if (ok) return true;
                continue;
            }
            AccessibilityNodeInfo parent = node.getParent();
            node.recycle();
            while (parent != null) {
                if (parent.isClickable()) {
                    boolean ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    parent.recycle();
                    return ok;
                }
                AccessibilityNodeInfo next = parent.getParent();
                parent.recycle();
                parent = next;
            }
        }
        return false;
    }
}
