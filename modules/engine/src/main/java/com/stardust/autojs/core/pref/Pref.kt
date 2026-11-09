package com.stardust.autojs.core.pref

import android.preference.PreferenceManager
import com.stardust.app.GlobalAppContext

/**
 * 引擎侧设置读取。
 *
 * task-66（P0-4 第二批）：这两个 key 是「主进程写、:script 读」，属**多进程需要一致**的白名单，
 * 走 [MultiProcessPrefs]（store 优先 + XML 播种/回退 + 双写）；开关关掉即退回原来的 SharedPreferences。
 * 脚本 storages **不在此列**（保持写者唯一，与 Pro 一致）。
 */
object Pref {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(GlobalAppContext.get())

    val isStableModeEnabled: Boolean
        get() = MultiProcessPrefs.getBoolean(GlobalAppContext.get(), "key_stable_mode", false)

    val isGestureObservingEnabled: Boolean
        get() = MultiProcessPrefs.getBoolean(GlobalAppContext.get(), "key_gesture_observing", false)

    /** 旧路径（回滚开关关闭时 [MultiProcessPrefs] 自己会走它；保留给诊断/对照）。 */
    internal fun legacyBoolean(key: String, fallback: Boolean): Boolean =
        preferences.getBoolean(key, fallback)
}