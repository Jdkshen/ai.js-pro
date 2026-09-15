package com.jdkshen.aijspro.inrt

import android.content.SharedPreferences
import android.preference.PreferenceManager

import com.stardust.app.GlobalAppContext

/**
 * Created by Stardust on 2017/12/8.
 */

object Pref {

    private const val KEY_FIRST_USING = "key_first_using"
    private var sPreferences: SharedPreferences? = null

    val preferences: SharedPreferences
        get() {
            return sPreferences ?: {
                val pref = PreferenceManager.getDefaultSharedPreferences(GlobalAppContext.get())
                sPreferences = pref
                pref
            }()
        }

    val isFirstUsing: Boolean
        get() {
            val firstUsing = preferences.getBoolean(KEY_FIRST_USING, true)
            if (firstUsing) {
                preferences.edit().putBoolean(KEY_FIRST_USING, false).apply()
            }
            return firstUsing
        }

    private fun getString(res: Int): String {
        return GlobalAppContext.getString(res)
    }

    fun shouldEnableAccessibilityServiceByRoot(): Boolean {
        return preferences.getBoolean(getString(R.string.key_enable_accessibility_service_by_root), false)
    }

    fun shouldHideLogs(): Boolean {
        return preferences.getBoolean(getString(R.string.key_dont_show_main_activity), false)
    }

    fun shouldStopAllScriptsWhenVolumeUp(): Boolean {
        return preferences.getBoolean(getString(R.string.key_use_volume_control_running), true)
    }

    // `$settings`（PDF §2.28）需要的写入接口

    fun setStopAllScriptsWhenVolumeUp(enabled: Boolean) {
        preferences.edit().putBoolean(getString(R.string.key_use_volume_control_running), enabled).apply()
    }

    fun setEnableAccessibilityServiceByRoot(enabled: Boolean) {
        preferences.edit().putBoolean(getString(R.string.key_enable_accessibility_service_by_root), enabled).apply()
    }

    fun setHideLogs(hidden: Boolean) {
        preferences.edit().putBoolean(getString(R.string.key_dont_show_main_activity), hidden).apply()
    }
}
