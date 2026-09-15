package com.jdkshen.aijspro.inrt.autojs.api

import com.jdkshen.aijspro.inrt.Pref

import java.util.Locale

/**
 * PDF §2.28 `$settings`：Auto.js 内部设置开关（打包版实现）。
 *
 * 打包版只有部分设置项（音量上键停止脚本、启动时隐藏日志、用 root 启用无障碍服务），
 * 没有的 key 统一报错，避免静默骗脚本。
 */
class SettingsModule {

    fun isEnabled(key: String?): Boolean {
        return when (normalize(key)) {
            STOP_ALL_ON_VOLUME_UP -> Pref.shouldStopAllScriptsWhenVolumeUp()
            NOT_SHOW_CONSOLE -> Pref.shouldHideLogs()
            ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT -> Pref.shouldEnableAccessibilityServiceByRoot()
            else -> throw IllegalArgumentException("未知的设置项: $key")
        }
    }

    fun setEnabled(key: String?, value: Boolean) {
        when (normalize(key)) {
            STOP_ALL_ON_VOLUME_UP -> Pref.setStopAllScriptsWhenVolumeUp(value)
            NOT_SHOW_CONSOLE -> Pref.setHideLogs(value)
            ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT -> Pref.setEnableAccessibilityServiceByRoot(value)
            else -> throw IllegalArgumentException("未知的设置项: $key")
        }
    }

    private fun normalize(key: String?): String = key?.trim()?.lowercase(Locale.ROOT) ?: ""

    companion object {
        const val STOP_ALL_ON_VOLUME_UP = "stop_all_on_volume_up"
        const val NOT_SHOW_CONSOLE = "not_show_console"
        const val ENABLE_ACCESSIBILITY_SERVICE_BY_ROOT = "enable_accessibility_service_by_root"
    }
}
