package com.jdkshen.aijspro.tool;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import com.stardust.app.GlobalAppContext;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;

import com.stardust.autojs.core.accessibility.AccessibilityService;
import com.google.android.accessibility.selecttospeak.SelectToSpeakService;
import com.stardust.autojs.core.util.ProcessShell;
import com.stardust.autojs.runtime.api.ShizukuShell;
import com.stardust.view.accessibility.AccessibilityServiceUtils;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by Stardust on 2017/1/26.
 */

public class AccessibilityServiceTool {

    private static final Class<? extends AccessibilityService> sAccessibilityServiceClass = SelectToSpeakService.class;
    private static final AtomicBoolean sFastEnableInProgress = new AtomicBoolean(false);

    public static void enableAccessibilityService() {
        if (hasSecureSettingsPermission()) {
            if (enableAccessibilityServiceDirectly(sAccessibilityServiceClass)) {
                GlobalAppContext.toast(R.string.text_enable_accessibility_service_fast_success);
            } else {
                goToAccessibilitySetting();
            }
            return;
        }
        if (ShizukuShell.isAvailable()) {
            enableAccessibilityServiceByShizukuInBackground();
            return;
        }
        if (Pref.shouldEnableAccessibilityServiceByRoot()) {
            if (!enableAccessibilityServiceByRoot(sAccessibilityServiceClass)) {
                goToAccessibilitySetting();
            }
        } else {
            goToAccessibilitySetting();
        }
    }

    private static void enableAccessibilityServiceByShizukuInBackground() {
        if (!sFastEnableInProgress.compareAndSet(false, true)) return;
        new Thread(() -> {
            boolean enabled;
            try {
                enabled = enableAccessibilityServiceByShizukuAndWaitFor(4_000, true);
            } catch (Throwable error) {
                enabled = false;
            } finally {
                sFastEnableInProgress.set(false);
            }
            if (enabled) {
                GlobalAppContext.toast(R.string.text_enable_accessibility_service_fast_success);
            } else {
                GlobalAppContext.post(AccessibilityServiceTool::goToAccessibilitySetting);
            }
        }, "Accessibility-fast-enable").start();
    }

    public static void goToAccessibilitySetting() {
        Context context = GlobalAppContext.get();
        if (Pref.isFirstGoToAccessibilitySetting()) {
            GlobalAppContext.toast(context.getString(R.string.text_please_choose) + context.getString(R.string.app_name));
        }
        try {
            AccessibilityServiceUtils.INSTANCE.goToAccessibilitySetting(context);
        } catch (ActivityNotFoundException e) {
            GlobalAppContext.toast(context.getString(R.string.go_to_accessibility_settings) + context.getString(R.string.app_name));
        }
    }

    private static final String ENABLE_COMMAND =
            "enabled=$(settings get secure enabled_accessibility_services); " +
            "pkg='%s'; " +
            "case \"$enabled\" in " +
            "''|null) enabled=\"$pkg\" ;; " +
            "*\"$pkg\"*) ;; " +
            "*) enabled=\"$pkg:$enabled\" ;; " +
            "esac; " +
            "settings put secure enabled_accessibility_services \"$enabled\" && " +
            "settings put secure accessibility_enabled 1";

    private static String enableCommand(
            Class<? extends android.accessibilityservice.AccessibilityService> accessibilityService) {
        String serviceName = GlobalAppContext.get().getPackageName() + "/" + accessibilityService.getName();
        return String.format(Locale.US, ENABLE_COMMAND, serviceName);
    }

    private static String grantAndEnableCommand(
            Class<? extends android.accessibilityservice.AccessibilityService> accessibilityService) {
        String packageName = GlobalAppContext.get().getPackageName();
        return "pm grant '" + packageName + "' android.permission.WRITE_SECURE_SETTINGS " +
                ">/dev/null 2>&1 || true; " + enableCommand(accessibilityService);
    }

    public static boolean hasSecureSettingsPermission() {
        return ContextCompat.checkSelfPermission(GlobalAppContext.get(),
                Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isFastEnableAvailable() {
        return hasSecureSettingsPermission() || ShizukuShell.isAvailable();
    }

    private static boolean enableAccessibilityServiceDirectly(
            Class<? extends android.accessibilityservice.AccessibilityService> accessibilityService) {
        if (!hasSecureSettingsPermission()) return false;
        Context context = GlobalAppContext.get();
        String serviceName = context.getPackageName() + "/" + accessibilityService.getName();
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean alreadyPresent = false;
            if (!TextUtils.isEmpty(enabled) && !"null".equals(enabled)) {
                for (String component : enabled.split(":")) {
                    if (serviceName.equals(component)) {
                        alreadyPresent = true;
                        break;
                    }
                }
            }
            if (!alreadyPresent) {
                enabled = TextUtils.isEmpty(enabled) || "null".equals(enabled)
                        ? serviceName : serviceName + ":" + enabled;
                if (!Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled)) return false;
            }
            return Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException error) {
            return false;
        }
    }

    public static boolean enableAccessibilityServiceDirectlyAndWaitFor(long timeOut) {
        if (!enableAccessibilityServiceDirectly(sAccessibilityServiceClass)) return false;
        return com.jdkshen.aijspro.automation.AutomationConnectionState.awaitReady(GlobalAppContext.get(),timeOut)
                || isAccessibilityServiceEnabled(GlobalAppContext.get());
    }

    public static boolean enableAccessibilityServiceByRoot(Class<? extends android.accessibilityservice.AccessibilityService> accessibilityService) {
        try {
            return TextUtils.isEmpty(ProcessShell.execCommand(
                    grantAndEnableCommand(accessibilityService), true).error);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isShizukuAvailable() {
        return ShizukuShell.isAvailable();
    }

    public static boolean hasShizukuPermission() {
        return ShizukuShell.hasPermission();
    }

    /**
     * Enables the service through Shizuku's shell identity. This avoids Xiaomi's
     * accessibility confirmation countdown after Shizuku permission is granted.
     * Must be called from a worker thread when requestPermission is true.
     */
    public static boolean enableAccessibilityServiceByShizukuAndWaitFor(
            long timeOut, boolean requestPermission) {
        if (hasSecureSettingsPermission()) {
            return enableAccessibilityServiceDirectlyAndWaitFor(timeOut);
        }
        if (!ShizukuShell.isAvailable()) return false;
        if (!ShizukuShell.hasPermission()) {
            if (!requestPermission || !ShizukuShell.requestPermission(60_000)) return false;
        }
        ShizukuShell.Result result = ShizukuShell.execute(
                grantAndEnableCommand(sAccessibilityServiceClass), 5_000, 8_192);
        if (result.code != 0 || !TextUtils.isEmpty(result.error)) return false;
        if (com.jdkshen.aijspro.automation.AutomationConnectionState.awaitReady(GlobalAppContext.get(),timeOut)) return true;
        // MIUI can publish the secure setting slightly before binding the service.
        return isAccessibilityServiceEnabled(GlobalAppContext.get());
    }

    public static boolean enableAccessibilityServiceByRootAndWaitFor(long timeOut) {
        if (enableAccessibilityServiceByRoot(sAccessibilityServiceClass)) {
            return com.jdkshen.aijspro.automation.AutomationConnectionState.awaitReady(GlobalAppContext.get(),timeOut);
        }
        return false;
    }

    public static void enableAccessibilityServiceByRootIfNeeded() {
        if (!com.jdkshen.aijspro.automation.AutomationConnectionState.isReady(GlobalAppContext.get()))
            if (Pref.shouldEnableAccessibilityServiceByRoot()) {
                AccessibilityServiceTool.enableAccessibilityServiceByRoot(sAccessibilityServiceClass);
            }
    }

    public static boolean isAccessibilityServiceEnabled(Context context) {
        return AccessibilityServiceUtils.INSTANCE.isAccessibilityServiceEnabled(context, sAccessibilityServiceClass);
    }

    /** task-54：当前是否允许悬浮窗（SYSTEM_ALERT_WINDOW / OVERLAY）。 */
    public static boolean canDrawOverlays(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * task-54：Shizuku 已授权时，用 shell 身份执行
     * {@code appops set <pkg> SYSTEM_ALERT_WINDOW allow} 恢复悬浮窗权限，并以系统真实状态复查。
     *
     * <p>只在 Shizuku 已运行且 App 已授权时执行（不触发授权对话框）；失败返回 false，调用方回退提示。
     */
    public static boolean enableOverlayByShizukuAndVerify() {
        if (!ShizukuShell.isAvailable() || !ShizukuShell.hasPermission()) {
            return false;
        }
        Context context = GlobalAppContext.get();
        if (context == null) {
            return false;
        }
        try {
            String packageName = context.getPackageName();
            ShizukuShell.Result result = ShizukuShell.execute(
                    "appops set '" + packageName + "' SYSTEM_ALERT_WINDOW allow", 5_000, 4_096);
            if (result == null || result.code != 0) {
                return false;
            }
        } catch (Throwable error) {
            return false;
        }
        // MIUI 上 appops 写入后 canDrawOverlays 可能有一小段延迟，最多重试 3 次。
        for (int attempt = 0; attempt < 3; attempt++) {
            if (canDrawOverlays(context)) {
                return true;
            }
            try {
                Thread.sleep(120L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    /**
     * task-54：后台无提示地尝试用 Shizuku 恢复无障碍（不触发授权对话框；未授权直接返回 false）。
     * 调用方负责放到工作线程。
     */
    public static boolean restoreAccessibilityByShizukuIfAuthorized(long waitTimeout) {
        if (!ShizukuShell.isAvailable() || !ShizukuShell.hasPermission()) {
            return false;
        }
        try {
            return enableAccessibilityServiceByShizukuAndWaitFor(waitTimeout, false);
        } catch (Throwable error) {
            return false;
        }
    }
}
