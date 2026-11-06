package com.jdkshen.aijspro.mcp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子进程堆必须作为 **VM 选项** 出现在 `/system/bin`（app_process 的 cmd-dir）之前——
 * 放到后面会被当成被调类的程序参数，堆不会生效。这条以前只能靠反汇编确认，现在用单测锁死。
 */
class McpChildProcessCommandTest {
    private fun temp() = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aijs-xmx-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun apktoolCommandPutsXmxBeforeExecutablePath() {
        val temp = temp()
        val command = apktoolCommand(temp, File(temp, "apktool.dex.jar"), "/data/app/base.apk", File(temp, "fw"), listOf("d", "in.apk", "-o", "out"))
        val heap = command.indexOf("-Xmx${McpLimits.CHILD_HEAP_MB}m")
        val cmdDir = command.indexOf("/system/bin")
        assertTrue("必须带 -Xmx", heap > 0)
        assertTrue("必须带 cmd-dir", cmdDir > 0)
        assertTrue("-Xmx 必须在 /system/bin 之前（否则是程序参数）", heap < cmdDir)
        assertEquals("/system/bin/app_process", command[0])
        assertTrue(command.contains("brut.apktool.Main"))
        assertTrue(command.contains("d") && command.contains("in.apk"))
    }

    @Test
    fun jadxCommandPutsXmxBeforeExecutablePath() {
        val temp = temp()
        val command = jadxCommand(temp, File(temp, "jadx.jar"), "/data/app/base.apk", File(temp, "req.json"))
        val heap = command.indexOf("-Xmx${McpLimits.CHILD_HEAP_MB}m")
        val cmdDir = command.indexOf("/system/bin")
        assertTrue(heap > 0 && cmdDir > 0)
        assertTrue("-Xmx 必须在 /system/bin 之前", heap < cmdDir)
        assertEquals("/system/bin/app_process", command[0])
        // request 是 File，命令里出现的是它的绝对路径
        assertTrue("必须把 request 文件路径传给被调类", command.any { it.endsWith("req.json") })
    }

    @Test
    fun limitsAreWidenedBeyondTheOldHardcodedValues() {
        assertTrue("工程条目上限必须大于旧的 30000", McpLimits.MAX_PROJECT_ENTRIES > 30000)
        assertTrue("工程展开上限必须大于旧的 512MiB", McpLimits.MAX_PROJECT_BYTES > 512L * 1024 * 1024)
        assertTrue("子进程堆必须大于旧的系统默认 256MB", McpLimits.CHILD_HEAP_MB >= 1024)
        assertTrue("超时上界必须大于旧的 300s", McpLimits.MAX_TIMEOUT_SECONDS > 300)
        val json = McpLimits.describe()
        assertEquals(McpLimits.MAX_PROJECT_ENTRIES, json.get("maxProjectEntries").asInt)
        assertEquals(McpLimits.CHILD_HEAP_MB, json.get("childHeapMb").asInt)
        assertEquals(McpLimits.MAX_TIMEOUT_SECONDS, json.get("maxTimeoutSeconds").asInt)
    }
}
