package com.jdkshen.aijspro.autojs.api;

import com.jdkshen.aijspro.Pref;

import java.util.Locale;

/**
 * PDF §2.28 `$settings`：Auto.js 内部设置开关。
 *
 * <p>由 App 侧通过 {@code runtime.putProperty("settings", ...)} 注册，引擎的 `init.js`
 * 再把它暴露成全局 `$settings`（本模块不依赖引擎，所以两边各自实现）。
 *
 * <p>文档列出的 key：`stop_all_on_volume_up`、`not_show_console`、`foreground_service`；
 * 仓库现有设置项里还能对应上 `stable_mode`、`enable_accessibility_service_by_root`（样例脚本在用）。
 */
public class SettingsModule {

    public static final String STOP_ALL_ON_VOLUME_UP = "stop_all_on_volume_up";
    public static final String NOT_SHOW_CONSOLE = "not_show_console";
    public static final String FOREGROUND_SERVICE = "foreground_service";
    public static final String STABLE_MODE = "stable_mode";
    public static final String ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT = "enable_accessibility_service_by_root";

    /** `$settings.isEnabled(key)`。 */
    public boolean isEnabled(String key) {
        switch (normalize(key)) {
            case STOP_ALL_ON_VOLUME_UP:
                return Pref.isRunningVolumeControlEnabled();
            case NOT_SHOW_CONSOLE:
                return Pref.isConsoleHiddenOnStart();
            case FOREGROUND_SERVICE:
                return Pref.isForegroundServiceEnabled();
            case STABLE_MODE:
                return Pref.isStableModeEnabled();
            case ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT:
                return Pref.shouldEnableAccessibilityServiceByRoot();
            default:
                throw new IllegalArgumentException("未知的设置项: " + key);
        }
    }

    /** `$settings.setEnabled(key, value)`。 */
    public void setEnabled(String key, boolean value) {
        switch (normalize(key)) {
            case STOP_ALL_ON_VOLUME_UP:
                Pref.setRunningVolumeControlEnabled(value);
                return;
            case NOT_SHOW_CONSOLE:
                Pref.setConsoleHiddenOnStart(value);
                return;
            case FOREGROUND_SERVICE:
                Pref.setForegroundServiceEnabled(value);
                return;
            case STABLE_MODE:
                Pref.setStableModeEnabled(value);
                return;
            case ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT:
                Pref.setEnableAccessibilityServiceByRoot(value);
                return;
            default:
                throw new IllegalArgumentException("未知的设置项: " + key);
        }
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
