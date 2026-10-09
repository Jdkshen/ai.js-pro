package com.jdkshen.aijspro.pluginclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * 「Pro 扩展调试服务器」（VS Code 的 Auto.js Pro 扩展连的 9317 端口）开关的持久化。
 *
 * <p>以前这个开关只有内存状态：应用一重启（或进程被系统回收后再打开），服务就没了，
 * 用户必须重新进抽屉把开关再点一次。这里把用户的选择落到默认 SharedPreferences，
 * 由 {@link DevPluginAutoStart} 在应用回到前台时自动恢复。
 */
public final class DevPluginPrefs {

    private static final String KEY_SERVER_ENABLED = "key_dev_plugin_server_enabled";

    private DevPluginPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /** 用户上次是否把调试服务器开着（默认关）。 */
    public static boolean isServerEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SERVER_ENABLED, false);
    }

    /** 记录开关选择：开 → 之后每次回到前台都会自动拉起；关 → 不再自动启动。 */
    public static void setServerEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SERVER_ENABLED, enabled).apply();
    }
}
