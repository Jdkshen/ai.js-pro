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
    // App 冷启动后无障碍服务重连可能晚于授权框出现，给足 30s（宿主截图等待是 55s）。
    private static final long DEADLINE_MS = 30_000L;
    private static boolean sArmed;
    private static long sArmedAt;
    private static long sLastWaitLog;

    private ScreenCaptureAutoConsent() { }

    /** 在 startActivityForResult(ScreenCaptureIntents.create(...)) 之前调用。 */
    public static synchronized void arm() {
        sArmed = true;
        sArmedAt = System.currentTimeMillis();
        Log.i(TAG, "armed");
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(ScreenCaptureAutoConsent::tick, POLL_MS);
        // 读不到系统节点时的坐标兜底（仅授权窗口存在期间有效；结果返回会立刻 sArmed=false 取消）。
        // 只点「开始共享」类确认按钮；不再点应用列表（单应用模式会让切 App 后黑帧）。
        final android.util.DisplayMetrics metrics = armDisplayMetrics();
        // Android 15 确认按钮「共享屏幕」在右下方（实测 1080x2400 约 (872,1645)）。
        handler.postDelayed(() -> blindTap(metrics, 0.807f, 0.685f), 1300L);
        handler.postDelayed(() -> blindTap(metrics, 0.807f, 0.685f), 3000L);
        handler.postDelayed(() -> blindTap(metrics, 0.807f, 0.685f), 6000L);
        handler.postDelayed(() -> blindTap(metrics, 0.807f, 0.685f), 10000L);
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
        if (service == null) {
            long now = System.currentTimeMillis();
            if (now - sLastWaitLog > 2000L) {
                sLastWaitLog = now;
                Log.i(TAG, "waiting accessibility service");
            }
            new Handler(Looper.getMainLooper()).postDelayed(ScreenCaptureAutoConsent::tick, POLL_MS);
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        boolean didSomething = false;
        if (root != null) {
            String pkg = String.valueOf(root.getPackageName());
            boolean systemDialog = pkg.contains("systemui") || pkg.contains("permission")
                    || pkg.contains("launcher");
            if (systemDialog) {
                // 1) 先点确认按钮（精确匹配，避免命中说明段落）：整屏是默认范围，直接确认即可。
                if (clickExactText(root, "共享屏幕") || clickExactText(root, "开始共享")
                        || clickExactText(root, "开始录制") || clickExactText(root, "立即共享")
                        || clickExactText(root, "立即开始") || clickExactText(root, "允许")) {
                    didSomething = true;
                    Log.i(TAG, "clicked confirm in " + pkg);
                } else if (clickExactText(root, "共享整个屏幕") || clickExactText(root, "整个屏幕")) {
                    // 2) 旧版/部分 ROM 的选择页：显式选整屏，再等确认按钮。
                    didSomething = true;
                    Log.i(TAG, "selected entire screen in " + pkg);
                } else if (clickText(root, "下一步")) {
                    didSomething = true;
                    Log.i(TAG, "clicked next in " + pkg);
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

    /** 精确匹配文案再点击（findAccessibilityNodeInfosByText 是子串匹配，会命中说明段落）。 */
    private static boolean clickExactText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null) return false;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null) continue;
            CharSequence value = node.getText();
            boolean exact = value != null && text.contentEquals(value);
            if (!exact) {
                node.recycle();
                continue;
            }
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
