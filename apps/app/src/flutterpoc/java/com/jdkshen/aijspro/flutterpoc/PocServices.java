package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.stardust.autojs.runtime.api.ShizukuShell;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统服务桥：Shizuku 状态/授权/用它启用无障碍、无障碍授权修复、原生悬浮菜单。
 *
 * <p>与 {@link PocBridge} 的约定一致：同步方法直接返回数据（JSON 形状由本类负责），
 * 异常交给 {@code PocBridge} 统一包成 {@code {ok:false,error}}；
 * {@code shizukuEnableAccessibility} 要等 Shizuku 授权对话框 + 无障碍服务绑定（最多数十秒），
 * 必须走后台线程 + 回调，所以单独暴露 {@link #isAsync(String)} /
 * {@link #startAsync(Activity, String, JSONObject, PocIntegration.Callback)}。
 *
 * <p>无障碍组件名取 AndroidManifest 里实际注册的 Pro 兼容类
 * {@code com.google.android.accessibility.selecttospeak.SelectToSpeakService}
 * （{@code AccessibilityServiceTool} 的 sAccessibilityServiceClass 指向它），
 * 不是包名下的 core/accessibility/Xxx。
 */
final class PocServices {

    private static final String TAG = "GlassFlutterPoc";

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String SHIZUKU_URL = "https://shizuku.rikka.app/";
    /** AndroidManifest 里真正注册的无障碍服务类（Pro 兼容组件名）。 */
    private static final String ACCESSIBILITY_SERVICE =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService";

    /** 启用无障碍（Shizuku 授权 + 绑定服务）的总超时；内部 requestPermission 自己的上限更大。 */
    private static final long ENABLE_TIMEOUT = 8_000L;
    /** 修复无障碍的总预算：三种通道共享，避免同步桥把界面卡太久。 */
    private static final long REPAIR_TIMEOUT = 4_000L;
    private static final long MIN_ATTEMPT_TIMEOUT = 250L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 后台正在通过 Shizuku 启用无障碍；界面用 busy 显示进度/禁用按钮。 */
    private static final AtomicBoolean sEnabling = new AtomicBoolean(false);
    /** 最近一次 Shizuku 启用结果：idle / success / failed（供轮询恢复界面状态）。 */
    private static volatile String sPhase = "idle";
    /** 最近一次 Shizuku 启用结果说明；空串表示还没执行过。 */
    private static volatile String sHint = "";

    private PocServices() {
    }

    // ---- 桥入口 ----------------------------------------------------------

    /** 同步桥方法；异步方法（isAsync=true）不允许走到这里。 */
    static Object execute(Activity activity, String method, JSONObject args) throws Exception {
        switch (method) {
            case "shizukuState":
                return shizukuState(activity);
            case "shizukuOpen":
                return shizukuOpen(activity);
            case "repairAccessibility":
                return repairAccessibility(activity);
            case "floatingWindow":
                return floatingWindow(activity, args);
            case "shizukuEnableAccessibility":
                throw new IllegalStateException("异步桥方法不应走到同步分支");
            default:
                throw new UnsupportedOperationException("POC 尚未实现系统服务桥方法：" + method);
        }
    }

    /** 需要等待 Shizuku 授权对话框/无障碍绑定的桥方法。 */
    static boolean isAsync(String method) {
        return "shizukuEnableAccessibility".equals(method);
    }

    static void startAsync(Activity activity, String method, JSONObject args,
                           PocIntegration.Callback callback) throws Exception {
        switch (method) {
            case "shizukuEnableAccessibility":
                enableAccessibilityByShizuku(activity, callback);
                return;
            default:
                callback.reply(null, new UnsupportedOperationException("未知系统服务异步桥方法：" + method));
        }
    }

    /** 供 deviceState 的 floatingWindowShowing 使用的真实状态。 */
    static boolean floatingMenuShowing() {
        try {
            return FloatyWindowManger.isCircularMenuShowing();
        } catch (Throwable error) {
            Log.w(TAG, "读取悬浮菜单状态失败", error);
            return false;
        }
    }

    // ---- Shizuku ---------------------------------------------------------

    /**
     * {"running","authorized","supported","installed","busy","phase","hint"}。
     * 轮询接口：不阻塞、不触发授权对话框。phase 保留最近一次启用结果，直到下次启用。
     */
    private static JSONObject shizukuState(Context context) throws JSONException {
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        boolean installed = isPackageInstalled(context, SHIZUKU_PACKAGE);
        boolean running = ShizukuShell.isAvailable();
        boolean authorized = ShizukuShell.hasPermission();
        boolean busy = sEnabling.get();
        String phase = busy ? "idle" : sPhase;
        String hint = busy ? "正在通过 Shizuku 授权并启用无障碍服务，请稍候…" : sHint;
        return new JSONObject()
                .put("running", running)
                .put("authorized", authorized)
                .put("supported", supported)
                .put("installed", installed)
                .put("busy", busy)
                .put("phase", phase == null ? "idle" : phase)
                .put("hint", hint == null ? "" : hint);
    }

    private static boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return false;
        } catch (Throwable error) {
            // 包可见性/系统限制导致的查询失败按“未安装”处理，不影响其它状态位。
            Log.w(TAG, "查询 Shizuku 安装状态失败", error);
            return false;
        }
    }

    /**
     * 后台：通过 Shizuku（首次会弹授权）给应用授予 WRITE_SECURE_SETTINGS 并启用无障碍，
     * 完成后在主线程回调与 shizukuState 同形状的结果，phase=success/failed。
     */
    private static void enableAccessibilityByShizuku(Activity activity,
                                                     PocIntegration.Callback callback) {
        if (!sEnabling.compareAndSet(false, true)) {
            // 已有一次在跑：立即回报当前状态（busy=true），界面继续轮询 phase。
            JSONObject value;
            try {
                value = shizukuState(activity)
                        .put("hint", "正在通过 Shizuku 启用无障碍服务，请稍候…");
            } catch (Throwable error) {
                Log.w(TAG, "组装 Shizuku 状态失败", error);
                value = new JSONObject();
            }
            final JSONObject reply = value;
            MAIN.post(() -> callback.reply(reply, null));
            return;
        }
        new Thread(() -> {
            boolean enabled = false;
            try {
                enabled = AccessibilityServiceTool
                        .enableAccessibilityServiceByShizukuAndWaitFor(ENABLE_TIMEOUT, true);
            } catch (Throwable error) {
                Log.w(TAG, "通过 Shizuku 启用无障碍失败", error);
            }
            if (enabled) {
                Pref.setAccessibilityUserEnabled(true);
            }
            String hint = enabled
                    ? "已通过 Shizuku 启用无障碍服务"
                    : "未能通过 Shizuku 启用无障碍服务：请确认 Shizuku 已运行并已授权";
            JSONObject value;
            try {
                value = shizukuState(activity)
                        .put("phase", enabled ? "success" : "failed")
                        .put("busy", false)
                        .put("hint", hint);
            } catch (Throwable error) {
                Log.w(TAG, "组装 Shizuku 启用结果失败", error);
                value = new JSONObject();
            }
            sPhase = enabled ? "success" : "failed";
            sHint = hint;
            sEnabling.set(false);
            final JSONObject reply = value;
            MAIN.post(() -> callback.reply(reply, null));
        }, "poc-shizuku-enable").start();
    }

    /** 打开 Shizuku：已安装用启动页，未安装回退到官网。 */
    private static Object shizukuOpen(Activity activity) {
        Intent launch = null;
        try {
            launch = activity.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        } catch (Throwable error) {
            Log.w(TAG, "解析 Shizuku 启动页失败", error);
        }
        if (launch != null) {
            activity.startActivity(launch);
        } else {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)));
        }
        return Boolean.TRUE;
    }

    // ---- 无障碍修复 ------------------------------------------------------

    /**
     * {"repaired","hint"}：同步，最长约 {@link #REPAIR_TIMEOUT} 毫秒。
     * 已启用直接返回；否则按 Shizuku → WRITE_SECURE_SETTINGS → root 的顺序尝试，
     * 最后以真实服务状态为准，失败时给出可复制的 adb 命令。
     */
    private static JSONObject repairAccessibility(Activity activity) throws JSONException {
        boolean alreadyEnabled = false;
        try {
            alreadyEnabled = AccessibilityServiceTool.isAccessibilityServiceEnabled(activity);
        } catch (Throwable error) {
            Log.w(TAG, "读取无障碍服务状态失败", error);
        }
        if (alreadyEnabled) {
            // task-54：无障碍处于开启状态即记录「用户开过」，供下次启动检测/自动恢复。
            Pref.setAccessibilityUserEnabled(true);
            return new JSONObject().put("repaired", false).put("hint", "无障碍服务已启用");
        }

        final long deadline = SystemClock.uptimeMillis() + REPAIR_TIMEOUT;
        boolean repaired = false;

        // 1) Shizuku：应用已授权时走 shell 身份写 secure settings（MIUI 上可跳过确认倒计时）。
        if (ShizukuShell.isAvailable() && ShizukuShell.hasPermission() && hasBudget(deadline)) {
            repaired = attemptRepair("Shizuku", () -> AccessibilityServiceTool
                    .enableAccessibilityServiceByShizukuAndWaitFor(budgetLeft(deadline), false));
        }

        // 2) 已授予 WRITE_SECURE_SETTINGS：直接写 Settings.Secure。
        if (!repaired && hasBudget(deadline)) {
            boolean secureSettings = false;
            try {
                secureSettings = AccessibilityServiceTool.hasSecureSettingsPermission();
            } catch (Throwable error) {
                Log.w(TAG, "读取 WRITE_SECURE_SETTINGS 状态失败", error);
            }
            if (secureSettings) {
                repaired = attemptRepair("WRITE_SECURE_SETTINGS", () -> AccessibilityServiceTool
                        .enableAccessibilityServiceDirectlyAndWaitFor(budgetLeft(deadline)));
            }
        }

        // 3) 用户显式开了「用 Root 启用无障碍」：root 的 su 可能停在授权弹窗上无限阻塞，
        //    放到后台线程限时等待，避免同步桥把主线程拖到 ANR。
        if (!repaired && hasBudget(deadline) && Pref.shouldEnableAccessibilityServiceByRoot()) {
            final long wait = budgetLeft(deadline);
            repaired = awaitBounded(() -> AccessibilityServiceTool
                    .enableAccessibilityServiceByRootAndWaitFor(wait), wait);
        }

        // 以真实服务状态为准（MIUI 上通道可能报成功但服务没绑上）。
        boolean enabledAfter = false;
        try {
            enabledAfter = AccessibilityServiceTool.isAccessibilityServiceEnabled(activity);
        } catch (Throwable error) {
            Log.w(TAG, "复查无障碍服务状态失败", error);
        }
        if (enabledAfter) {
            Pref.setAccessibilityUserEnabled(true);
            return new JSONObject().put("repaired", true).put("hint", "已恢复无障碍授权");
        }

        String component = activity.getPackageName() + "/" + ACCESSIBILITY_SERVICE;
        String hint = "未能自动恢复无障碍授权，请用 adb 执行：\n"
                + "adb shell settings put secure enabled_accessibility_services " + component + "\n"
                + "adb shell settings put secure accessibility_enabled 1";
        // 走到这里说明复查仍未启用：即使某条通道自报成功也不认账，以真实状态为准。
        return new JSONObject().put("repaired", false).put("hint", hint);
    }

    private static boolean attemptRepair(String channel, Callable<Boolean> attempt) {
        try {
            return Boolean.TRUE.equals(attempt.call());
        } catch (Throwable error) {
            Log.w(TAG, "通过 " + channel + " 修复无障碍失败", error);
            return false;
        }
    }

    /** 在后台线程执行可能无限阻塞的命令（root su），最多等待 timeoutMillis。 */
    private static boolean awaitBounded(Callable<Boolean> task, long timeoutMillis) {
        FutureTask<Boolean> future = new FutureTask<>(task);
        Thread thread = new Thread(future, "poc-root-repair");
        thread.setDaemon(true);
        thread.start();
        try {
            return Boolean.TRUE.equals(future.get(
                    Math.max(MIN_ATTEMPT_TIMEOUT, timeoutMillis), TimeUnit.MILLISECONDS));
        } catch (TimeoutException | InterruptedException | ExecutionException error) {
            future.cancel(true);
            return false;
        }
    }

    private static boolean hasBudget(long deadline) {
        return SystemClock.uptimeMillis() < deadline;
    }

    private static long budgetLeft(long deadline) {
        return Math.max(MIN_ATTEMPT_TIMEOUT, deadline - SystemClock.uptimeMillis());
    }

    // ---- 悬浮菜单 --------------------------------------------------------

    /** {"granted","showing","userEnabled"}；带 enabled 时显示/隐藏原生悬浮菜单并持久化显示意图。 */
    private static Object floatingWindow(Activity activity, JSONObject args) throws JSONException {
        boolean granted = hasOverlayPermission(activity);
        boolean showing = floatingMenuShowing();
        if (args.has("enabled")) {
            boolean enabled = args.optBoolean("enabled", false);
            // task-54：持久化「用户希望显示悬浮窗」的意图，App 启动时自动恢复（权限被撤销时提示一次）。
            Pref.setFloatingMenuShown(enabled);
            if (enabled) {
                Pref.setOverlayRevokedPrompted(false);
                // 无悬浮窗权限时 showCircularMenu 自己会弹提示并打开授权页；Shizuku 自动恢复在
                // App 启动时（StartupPermissionRestore）后台执行，这里不做同步 Shell（避免卡主线程）。
                showing = FloatyWindowManger.showCircularMenu() || floatingMenuShowing();
            } else {
                FloatyWindowManger.hideCircularMenu();
                showing = floatingMenuShowing();
            }
        }
        return new JSONObject()
                .put("granted", granted)
                .put("showing", showing)
                .put("userEnabled", Pref.isFloatingMenuShown());
    }

    private static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable error) {
            Log.w(TAG, "读取悬浮窗权限失败", error);
            return false;
        }
    }
}
