package com.stardust.autojs.core.pref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-4（task-61）：多进程偏好一致性单测（纯 JVM，不需要 Android）。
 *
 * 「两个进程」= 两个 [CrossProcessPrefs] 实例 + **同一份** [CrossProcessPrefs.Store]（真实现里就是同一个
 * 私有目录文件；主进程与 :script 进程各自持有一个实例）。
 */
class CrossProcessPrefsTest {

    /** 单 JVM 内模拟「一份磁盘文件 + 跨进程文件锁」。 */
    private class SharedStore : CrossProcessPrefs.Store {
        val files = HashMap<String, ByteArray>()
        val lockCount = AtomicInteger()
        private val mutex = Any()
        override fun read(file: String): ByteArray? = files[file]?.copyOf()
        override fun writeAtomically(file: String, bytes: ByteArray) { files[file] = bytes.copyOf() }
        override fun <T> withFileLock(file: String, block: () -> T): T = synchronized(mutex) {
            lockCount.incrementAndGet()
            block()
        }
        override fun fingerprint(file: String): String? =
            files[file]?.let { it.size.toString() + ":" + it.contentHashCode() }
    }

    private val store = SharedStore()
    private val main = CrossProcessPrefs(store, "settings.pref")
    private val script = CrossProcessPrefs(store, "settings.pref")

    /** R1：一个进程写，另一个进程下一次读就能看到（不需要重启进程）。 */
    @Test
    fun writeInOneProcessIsVisibleInTheOther() {
        main.put("key_stable_mode", true)
        assertTrue(":script 侧应看到主进程刚写的值", script.boolean("key_stable_mode"))
        script.put("key_gesture_observing", true)
        assertTrue("主进程侧应看到 :script 刚写的值", main.boolean("key_gesture_observing"))
    }

    /** R2（核心）：两个进程各自先加载（都看到空），再各自写不同键 → 两个键都要在，不许整份覆盖。 */
    @Test
    fun twoProcessesWritingDifferentKeysDoNotLoseKeys() {
        assertEquals(0, main.snapshot().size)
        assertEquals(0, script.snapshot().size)

        main.put("key_stable_mode", true)
        script.put("key_gesture_observing", true)

        val observer = CrossProcessPrefs(store, "settings.pref").snapshot()
        assertTrue("主进程写的键不能被 :script 的写覆盖掉", observer.containsKey("key_stable_mode"))
        assertTrue(":script 写的键必须在", observer.containsKey("key_gesture_observing"))
        assertEquals(2, observer.size)
        assertTrue("每次写都要持文件锁", store.lockCount.get() >= 2)
    }

    /** R3：写是同步的（put 返回后，一个全新实例立刻能读到），没有 apply() 的延迟窗口。 */
    @Test
    fun writesAreSynchronousAndRemoveIsPersisted() {
        main.put("key_a", "1")
        assertEquals("1", CrossProcessPrefs(store, "settings.pref").string("key_a"))

        main.remove("key_a")
        val fresh = CrossProcessPrefs(store, "settings.pref")
        assertNull(fresh.string("key_a"))
        assertFalse(fresh.snapshot().containsKey("key_a"))
    }

    /** 类型与转义往返（含分隔符/换行/反斜杠），避免存储格式把值吃掉。 */
    @Test
    fun valuesRoundTripIncludingSeparators() {
        main.put("key_int", 42)
        main.put("key_long", 42L)
        main.put("key_weird", "a\u0001b\nc\\d")
        val other = CrossProcessPrefs(store, "settings.pref")
        assertEquals(42, other.int("key_int"))
        assertEquals(42L, other.long("key_long"))
        assertEquals("a\u0001b\nc\\d", other.string("key_weird"))
        assertEquals(false, other.boolean("key_missing"))
        assertEquals(7, other.int("key_missing", 7))
    }

    /**
     * R4（边界钉住）：跨进程改动**不会**被推送到另一个进程的监听器；只有本进程自己 reload 时才回调。
     * 真正的跨进程通知需要广播/Binder（第二批），本类不假装有。
     */
    @Test
    fun crossProcessChangesAreNotPushedToListeners() {
        val seen = ArrayList<String?>()
        main.addListener { key -> seen.add(key) }
        main.put("key_local", true)
        assertEquals("本进程写要回调", listOf<String?>("key_local"), seen)

        script.put("key_from_script", true)
        assertEquals("跨进程写不能凭空回调（没有推送通道）", 1, seen.size)

        assertTrue("本进程 reload 后才会看到并回调", main.reloadIfChanged())
        assertTrue(seen.contains("key_from_script"))
        assertFalse("文件没变时 reload 是 no-op", main.reloadIfChanged())
    }

    /** 指纹没变就不重载（避免每次读都解析整份文件）。 */
    @Test
    fun reloadIsSkippedWhenFileIsUnchanged() {
        main.put("key_x", true)
        assertFalse(main.reloadIfChanged())
        assertEquals(0, main.listenerCount())
        main.addListener { }
        assertEquals(1, main.listenerCount())
    }
}
