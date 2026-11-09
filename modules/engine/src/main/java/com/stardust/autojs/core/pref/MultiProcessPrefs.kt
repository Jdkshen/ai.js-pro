package com.stardust.autojs.core.pref

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log

/**
 * 多进程偏好的**接线层**（P0-4 第二批 / task-66）：把「多进程需要一致」的 key 迁到
 * [CrossProcessPrefs]（真存储 [FileCrossProcessPrefsStore]），其余（含脚本 storages）保持原样。
 *
 * 设计（灰度 + 可回滚）：
 * - **只迁白名单 key**（由调用方逐 key 显式传入；脚本 storages 不在此列，保持写者唯一）；
 * - **读**：store 优先；store 里没有就**从 XML 播种**（老用户设置不丢）后再读；
 * - **写**：**双写**（store + XML）——store 给跨进程读者，XML 保证设置页/$settings 等既有路径不变；
 * - **回滚开关**：默认开启，读**旧 XML** 里的 [SWITCH_KEY]（默认 true）+ 系统属性 aijspro.crossProcessPrefs
 *   可覆盖（单测用）。关掉即整条路径退回原来的 SharedPreferences（开关本身只读 XML，不会递归）；
 * - **失败降级**：store 抛任何异常都吞掉并回退 XML（[failures] 计数 + logcat），绝不因为新机制让设置读写失败。
 */
object MultiProcessPrefs {

    /** 回滚开关的 key（存在旧 XML 里）。 */
    const val SWITCH_KEY = "key_cross_process_prefs_enabled"

    /** 落盘文件名（应用私有目录下 pref-sync/）。 */
    const val STORE_FILE = "aijspro-settings.pref"

    private const val TAG = "MultiProcessPrefs"

    /** 新机制的失败次数（诊断：大于 0 说明发生过降级）。 */
    @Volatile
    var failures: Long = 0L
        private set

    @Volatile
    private var cached: CrossProcessPrefs? = null

    @Volatile
    private var cachedRoot: String? = null

    /** 开关：系统属性优先（单测），否则读旧 XML（默认开）。 */
    @JvmStatic
    fun enabled(context: Context): Boolean {
        val override = System.getProperty("aijspro.crossProcessPrefs")
        if (override != null) return override.equals("on", ignoreCase = true) || override == "true"
        return runCatching { legacy(context).getBoolean(SWITCH_KEY, true) }.getOrDefault(true)
    }

    @Volatile
    private var lastLegacyStamp: String? = null

    /**
     * task-66：旧 XML 变了就把白名单 key 重播种进 store。
     * 理由：仍存在**只写 XML 的写者**（DrawerFragment 的稳定模式开关、$settings 等，均不在本任务写作用域），
     * 若只在「store 里没有」时播种，它们的新值永远进不了 store → 引擎（:script）会读到旧值。
     */
    private fun reseedIfLegacyChanged(context: Context): String? {
        val legacyFile = runCatching { legacyFileOf(context) }.getOrNull()
        val stamp = MultiProcessPrefsSync.stampOf(legacyFile)
        if (!MultiProcessPrefsSync.shouldReseed(stamp, lastLegacyStamp)) return stamp
        val previous = lastLegacyStamp
        lastLegacyStamp = stamp
        if (previous != null) {
            // 只有「见过一次且这次变了」才重播种；首次交给各 get 的按需播种。
            runCatching { seedFromLegacy(context) }
        }
        return stamp
    }

    /** 把当前 XML 里存在的白名单 key 复制进 store（不做删除：XML 没有的 key 保持 store 现值）。 */
    private fun seedFromLegacy(context: Context) {
        val legacy = legacy(context)
        val store = prefs(context)
        for (key in legacy.all.keys) {
            val value = legacy.all[key] ?: continue
            val text = value.toString()
            if (store.string(key) != text) {
                store.put(key, text)
                android.util.Log.i(TAG, "reseed key=" + key + " value=" + text)
            }
        }
    }

    private fun legacyFileOf(context: Context): java.io.File = java.io.File(
        context.applicationInfo.dataDir + "/shared_prefs/" + context.packageName + "_preferences.xml")

    /** 造/取 store（同一 filesDir 只造一次）。 */
    @JvmStatic
    fun prefs(context: Context): CrossProcessPrefs {
        val root = java.io.File(context.filesDir, "pref-sync")
        val path = root.absolutePath
        val current = cached
        if (current != null && path == cachedRoot) return current
        val created = CrossProcessPrefs(FileCrossProcessPrefsStore(root), STORE_FILE)
        cached = created
        cachedRoot = path
        return created
    }

    /** Java 侧可调；**不能**叫 `string`/`boolean`（Java 里是关键字/易混）→ 与 SharedPreferences 同名。 */
    @JvmStatic
    fun getString(context: Context, key: String, fallback: String? = null): String? {
        if (!enabled(context)) return legacy(context).getString(key, fallback)
        return runCatching {
            reseedIfLegacyChanged(context)
            val store = prefs(context)
            val stored = store.string(key)
            if (stored != null) return@runCatching stored
            val seeded = legacy(context).getString(key, null)
            if (seeded != null) store.put(key, seeded)
            seeded ?: fallback
        }.getOrElse { degrade(key, it); legacy(context).getString(key, fallback) }
    }

    @JvmStatic
    fun getBoolean(context: Context, key: String, fallback: Boolean = false): Boolean {
        if (!enabled(context)) return legacy(context).getBoolean(key, fallback)
        return runCatching {
            reseedIfLegacyChanged(context)
            val store = prefs(context)
            val stored = store.string(key)
            if (stored != null) return@runCatching stored.toBoolean()
            val seeded = legacy(context).getBoolean(key, fallback)
            store.put(key, seeded.toString())
            seeded
        }.getOrElse { degrade(key, it); legacy(context).getBoolean(key, fallback) }
    }

    /** 双写：store（跨进程可见）+ XML（既有路径不变）。 */
    @JvmStatic
    fun putBoolean(context: Context, key: String, value: Boolean) {
        runCatching { legacy(context).edit().putBoolean(key, value).apply() }
        if (!enabled(context)) return
        runCatching { prefs(context).put(key, value) }.onFailure { degrade(key, it) }
    }

    @JvmStatic
    fun putString(context: Context, key: String, value: String?) {
        runCatching {
            val editor = legacy(context).edit()
            if (value == null) editor.remove(key) else editor.putString(key, value)
            editor.apply()
        }
        if (!enabled(context)) return
        runCatching { prefs(context).put(key, value) }.onFailure { degrade(key, it) }
    }

    private fun degrade(key: String, error: Throwable) {
        failures += 1
        Log.w(TAG, "跨进程偏好降级到 SharedPreferences：key=" + key, error)
    }

    /** 诊断用：开关 + 降级计数 + 文件名（logcat/工具可查）。 */
    @JvmStatic
    fun describe(context: Context): String =
        "enabled=" + enabled(context) + " failures=" + failures + " file=" + STORE_FILE

    private fun legacy(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)
}
