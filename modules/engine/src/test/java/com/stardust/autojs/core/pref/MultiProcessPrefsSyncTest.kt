package com.stardust.autojs.core.pref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * task-66：重播种判定的单测（纯 JVM）。
 *
 * 钉住的行为：**旧 XML 指纹变了 → 必须重播种**（否则只写 XML 的写者——DrawerFragment 的稳定模式开关、
 * $settings 等——的新值永远进不了 store，引擎在 :script 里会读到旧值）。
 */
class MultiProcessPrefsSyncTest {

    @Test
    fun reseedOnlyWhenLegacyStampChanged() {
        assertFalse("没见过 XML 指纹时无需重播种", MultiProcessPrefsSync.shouldReseed(null, null))
        assertTrue("首次发现 XML 指纹也要播种", MultiProcessPrefsSync.shouldReseed("10:100", null))
        assertFalse("指纹没变就不播种（避免每次读都全量复制）", MultiProcessPrefsSync.shouldReseed("10:100", "10:100"))
        assertTrue("指纹变了必须播种", MultiProcessPrefsSync.shouldReseed("10:101", "10:100"))
        assertTrue("文件被截断/重写也要播种", MultiProcessPrefsSync.shouldReseed("8:101", "10:100"))
    }

    @Test
    fun stampFollowsTheRealFile() {
        assertNull("不存在的文件没有指纹", MultiProcessPrefsSync.stampOf(null))
        val file = File(System.getProperty("java.io.tmpdir"), "w4-t66-xml-" + System.nanoTime() + ".xml")
        assertNull(MultiProcessPrefsSync.stampOf(file))
        file.writeText("<map/>")
        val first = MultiProcessPrefsSync.stampOf(file)
        assertTrue(first != null && first.startsWith(file.length().toString() + ":"))
        Thread.sleep(1100) // 文件系统 mtime 粒度（1s）——只在这里等，不用于生产路径
        file.writeText("<map><boolean name=\"key_stable_mode\" value=\"true\"/></map>")
        assertTrue("内容变了指纹必须变", first != MultiProcessPrefsSync.stampOf(file))
        file.delete()
    }
}
