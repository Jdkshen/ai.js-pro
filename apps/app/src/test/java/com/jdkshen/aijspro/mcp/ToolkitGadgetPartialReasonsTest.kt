package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归：partialReasons 必须按 entry 区分目标 APK 自带库与注入的 Gadget，不能把目标库归因给上游。 */
class ToolkitGadgetPartialReasonsTest {
    private fun issue(entry: String): JsonObject = ToolkitIo.json(
        "check" to "native",
        "code" to "ELF_PT_LOAD_ALIGNMENT_16K",
        "severity" to "error",
        "message" to "PT_LOAD 不满足 16 KiB 页兼容",
        "entry" to entry,
        "segmentIndex" to 2,
        "pAlign" to 0x1000,
    )

    private fun verification(vararg entries: String): JsonObject = ToolkitIo.json(
        "status" to "partial",
        "checks" to ToolkitIo.json("native" to ToolkitIo.json("status" to "partial", "issueCount" to entries.size)),
        "native" to ToolkitIo.json("issues" to entries.map { issue(it) }),
    )

    @Test
    fun distinguishesTargetLibraryFromInjectedGadget() {
        val targetEntry = "lib/arm64-v8a/libroot_automator.so"
        val injectedEntry = "lib/arm64-v8a/libaijsgadget.so"
        val reasons = gadgetPartialReasons(verification(targetEntry, injectedEntry))
        assertEquals(2, reasons.size())
        val target = reasons.map { it.asJsonObject }.single { it.tString("entry") == targetEntry }
        val injected = reasons.map { it.asJsonObject }.single { it.tString("entry") == injectedEntry }
        assertEquals("target", target.tString("scope"))
        assertEquals("target", target.tString("owner"))
        assertFalse(target.tBool("upstream"))
        assertEquals("injected", injected.tString("scope"))
        assertEquals("injected", injected.tString("owner"))
        assertTrue(injected.tBool("upstream"))
        assertEquals("static-check-only", injected.tString("impact"))
        assertEquals(2, target.get("segmentIndex").asInt)
        assertTrue(injected.has("errorCodes"))
    }

    @Test
    fun noteBlamesTargetLibrariesNotFridaGadget() {
        val reasons = gadgetPartialReasons(verification("lib/arm64-v8a/libroot_automator.so"))
        val note = gadgetPartialNote(reasons)
        assertTrue(note.contains("目标 APK 自带 native 库的 PT_LOAD 页对齐"))
        assertTrue(note.contains("本工具注入的 Gadget（64 位 ABI）本次检测通过"))
        assertTrue(note.contains("需目标提供方重编该库"))
        assertFalse(note.contains("上游 Frida Gadget"))
    }
}
