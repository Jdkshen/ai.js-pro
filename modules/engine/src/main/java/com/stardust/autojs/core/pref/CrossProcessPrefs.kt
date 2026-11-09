package com.stardust.autojs.core.pref

/**
 * 多进程一致的偏好存取（**自研**；P0-4 TraySharedPreference 的等价物候选）。
 *
 * 背景与缺陷清单见 docs/reports/2026-10-18_多进程Pref与设置同步评估.md（R1 读旧值 / R2 并发写丢键 /
 * R3 apply() 异步 / R4 监听不跨进程）。本类只解决「同一份偏好被多个进程读写」的**存储一致性**：
 *
 * - **R1 读旧值**：每次读前比对文件指纹（长度 + 内容校验和），变了就整份重载
 *   → 别的进程写过，本进程下一次读就能看到（不用重启进程）。
 * - **R2 并发写丢键**：所有写操作都在**文件锁**内「读盘 → 只合并本次改动 → 原子替换（临时文件 + rename）」，
 *   **绝不**拿进程内的旧 map 整份覆盖 → 两个进程交替写不同键不会互相抹掉。
 * - **R3 异步丢更新**：只提供**同步提交**（put 返回时已落盘），没有 apply() 那种「排队稍后写」的语义。
 * - **R4 变更监听**：同进程内即时回调；**跨进程通知不在本类**（需要广播/Binder，属第二批），
 *   所以不能把「监听器一定收到别的进程的改动」当成本类的保证（有单测钉住这个边界）。
 *
 * 设计约束（有意）：
 * - 存储抽象成 [Store]（读 / 原子写 / 文件锁 / 指纹），因此**不依赖 Android**：JVM 单测可以用同一份 Store
 *   造两个实例，直接验「两个进程」的读写一致性；
 * - **没有抄任何反编译实现**（Pro 的 Tray 只作思路参照），存储格式自定；
 * - 尚未接线到 Pref/SettingsModule（那些调用点在 apps 侧、不在本轮写作用域）；迁移落地属第二批。
 */
class CrossProcessPrefs(
    private val store: Store,
    private val file: String
) {

    /** 存储后端：真实现是「应用私有目录文件 + FileChannel.lock + tmp/rename」；测试用内存实现。 */
    interface Store {
        /** 读整份内容；不存在返回 null。 */
        fun read(file: String): ByteArray?

        /** 原子替换（写临时文件后 rename）：实现方保证不会留下半份文件。 */
        fun writeAtomically(file: String, bytes: ByteArray)

        /** 持文件锁执行（跨进程互斥）。 */
        fun <T> withFileLock(file: String, block: () -> T): T

        /** 文件指纹；不存在返回 null。 */
        fun fingerprint(file: String): String?
    }

    /** 监听器：key 非空表示某个键变了；null 表示整份被替换过（实现保留，便于上层做全量刷新）。 */
    fun interface Listener {
        fun onPrefChanged(key: String?)
    }

    private val listeners = LinkedHashSet<Listener>()

    /** key -> 原始字符串值（不存在的键不在 map 里）。 */
    private var values: Map<String, String> = emptyMap()
    private var loadedFingerprint: String? = null
    private var everLoaded = false

    fun string(key: String): String? {
        reloadIfChanged()
        return values[key]
    }

    fun boolean(key: String, fallback: Boolean = false): Boolean =
        string(key)?.toBooleanStrictOrNull() ?: fallback

    fun int(key: String, fallback: Int = 0): Int = string(key)?.toIntOrNull() ?: fallback

    fun long(key: String, fallback: Long = 0L): Long = string(key)?.toLongOrNull() ?: fallback

    /** 写：同步提交（返回时已落盘），并在同进程内通知监听器。 */
    fun put(key: String, value: String?) {
        val changed = store.withFileLock(file) {
            val current = decode(store.read(file))
            val next = LinkedHashMap(current)
            if (value == null || value.isEmpty()) next.remove(key) else next[key] = value
            if (next == current) {
                false
            } else {
                store.writeAtomically(file, encode(next))
                true
            }
        }
        if (!changed) return
        // reloadIfChanged() 会按 diff 通知（每个有效改动**恰好一次**）；
        // 只有「盘变了但本进程快照已经是该值」这种边角才需要补一次通知。
        if (!reloadIfChanged()) notify(key)
    }

    fun put(key: String, value: Boolean) = put(key, value.toString())
    fun put(key: String, value: Int) = put(key, value.toString())
    fun put(key: String, value: Long) = put(key, value.toString())
    fun remove(key: String) = put(key, null)

    /** 只读快照（先按需重载）。 */
    fun snapshot(): Map<String, String> {
        reloadIfChanged()
        return LinkedHashMap(values)
    }

    /** 文件变了才重载；返回是否真的重载过。 */
    fun reloadIfChanged(): Boolean {
        val fingerprint = store.fingerprint(file)
        if (everLoaded && fingerprint == loadedFingerprint) return false
        val fresh = decode(store.read(file))
        val previous = values
        values = fresh
        loadedFingerprint = fingerprint
        everLoaded = true
        if (previous != fresh) {
            for (key in previous.keys + fresh.keys) {
                if (previous[key] != fresh[key]) notify(key)
            }
        }
        return true
    }

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }
    fun listenerCount(): Int = listeners.size

    private fun notify(key: String) {
        for (listener in listeners.toList()) listener.onPrefChanged(key)
    }

    companion object {
        private const val SEPARATOR = '\u0001'

        fun encode(values: Map<String, String>): ByteArray {
            val builder = StringBuilder()
            for ((key, value) in values) {
                builder.append(escape(key)).append(SEPARATOR).append(escape(value)).append('\n')
            }
            return builder.toString().toByteArray(Charsets.UTF_8)
        }

        fun decode(bytes: ByteArray?): Map<String, String> {
            if (bytes == null || bytes.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            for (line in String(bytes, Charsets.UTF_8).split('\n')) {
                if (line.isEmpty()) continue
                val index = line.indexOf(SEPARATOR)
                if (index <= 0) continue
                map[unescape(line.substring(0, index))] = unescape(line.substring(index + 1))
            }
            return map
        }

        private fun escape(text: String): String =
            text.replace("\\", "\\\\").replace("\n", "\\n").replace(SEPARATOR.toString(), "\\s")

        private fun unescape(text: String): String {
            val builder = StringBuilder()
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '\\' && index + 1 < text.length) {
                    val next = text[index + 1]
                    when (next) {
                        'n' -> builder.append('\n')
                        's' -> builder.append(SEPARATOR)
                        '\\' -> builder.append('\\')
                        else -> builder.append(next)
                    }
                    index += 2
                } else {
                    builder.append(char)
                    index += 1
                }
            }
            return builder.toString()
        }
    }
}
