package com.stardust.autojs.core.pref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * task-66：真存储（FileCrossProcessPrefsStore）的一致性与降级单测——**用真实文件**，纯 JVM。
 *
 * 「两个进程」= 两个 CrossProcessPrefs 实例 + 同一个 FileCrossProcessPrefsStore 根目录。
 */
class FileCrossProcessPrefsStoreTest {

    private fun newRoot(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "w4-t66-" + System.nanoTime())
        dir.mkdirs()
        return dir
    }

    @Test
    fun crossInstanceReadWriteOverRealFiles() {
        val root = newRoot()
        val main = CrossProcessPrefs(FileCrossProcessPrefsStore(root), "settings.pref")
        val script = CrossProcessPrefs(FileCrossProcessPrefsStore(root), "settings.pref")

        main.put("key_stable_mode", true)
        assertTrue("另一个实例（=另一个进程）必须看到", script.boolean("key_stable_mode"))

        script.put("key_gesture_observing", true)
        assertTrue("反向也要看到", main.boolean("key_gesture_observing"))
        // 不要留下临时文件
        assertEquals(0, root.listFiles()!!.count { it.name.contains(".tmp-") })
    }

    @Test
    fun atomicWriteLeavesNoPartialFile() {
        val root = newRoot()
        val store = FileCrossProcessPrefsStore(root)
        val prefs = CrossProcessPrefs(store, "settings.pref")
        prefs.put("key_a", "1")
        val bytes = store.read("settings.pref")
        assertNotNull(bytes)
        assertEquals("1", CrossProcessPrefs.decode(bytes)["key_a"])
        // 正式文件里绝不能出现半份（解析出乱键）
        assertEquals(1, CrossProcessPrefs.decode(bytes).size)
    }

    /** 锁被别的线程占着且超时为 0：必须**降级继续**并记一次 lockTimeouts（不抛、不丢键）。 */
    @Test
    fun lockTimeoutDegradesWithoutLosingKeys() {
        val root = newRoot()
        val store = FileCrossProcessPrefsStore(root, lockTimeoutMillis = 0L)
        val prefs = CrossProcessPrefs(store, "settings.pref")
        prefs.put("key_before", "1")

        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = FileCrossProcessPrefsStore(root)
        val thread = Thread {
            holder.withFileLock("settings.pref") {
                held.countDown()
                release.await()
            }
        }
        thread.start()
        held.await()
        try {
            prefs.put("key_during_lock", "2")
        } finally {
            release.countDown()
            thread.join(3000)
        }
        assertEquals("超时要被记录", 1L, store.lockTimeouts)
        val after = CrossProcessPrefs(FileCrossProcessPrefsStore(root), "settings.pref").snapshot()
        assertEquals("1", after["key_before"])
        assertEquals("2", after["key_during_lock"])
    }

    /** 并发写不同 key（8 线程 × 25 key）：一个都不能丢。 */
    @Test
    fun concurrentWritersDoNotLoseKeys() {
        val root = newRoot()
        val total = AtomicInteger()
        val threads = ArrayList<Thread>()
        for (worker in 0 until 8) {
            val thread = Thread {
                val prefs = CrossProcessPrefs(FileCrossProcessPrefsStore(root), "settings.pref")
                for (index in 0 until 25) {
                    prefs.put("key_" + worker + "_" + index, "v")
                    total.incrementAndGet()
                }
            }
            threads.add(thread)
            thread.start()
        }
        threads.forEach { it.join(15000) }
        val snapshot = CrossProcessPrefs(FileCrossProcessPrefsStore(root), "settings.pref").snapshot()
        assertEquals(200, total.get())
        assertEquals("200 个 key 一个都不能丢", 200, snapshot.size)
    }

    @Test
    fun fingerprintChangesOnlyWhenFileChanges() {
        val root = newRoot()
        val store = FileCrossProcessPrefsStore(root)
        val prefs = CrossProcessPrefs(store, "settings.pref")
        assertNull(store.fingerprint("settings.pref"))
        prefs.put("key_x", "1")
        val first = store.fingerprint("settings.pref")
        assertNotNull(first)
        assertEquals(first, store.fingerprint("settings.pref"))
        prefs.put("key_x", "2")
        assertTrue("内容变了指纹必须变", first != store.fingerprint("settings.pref"))
    }
}
