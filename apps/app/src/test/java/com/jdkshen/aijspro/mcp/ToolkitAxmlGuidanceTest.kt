package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitAxmlGuidanceTest {
    @Test
    fun manifestPatchErrorNamesWorkingReadEditPath() {
        val error = assertThrows(ToolkitException::class.java) {
            ToolkitApkTargets.ordinary("AndroidManifest.xml")
        }
        assertEquals("AXML_ENTRY_RESERVED", error.code)
        val message = error.message.orEmpty()
        assertTrue(message.contains("apk_read_text"))
        assertTrue(message.contains("apk_edit_text"))
        assertTrue(message.contains("axml:"))
        assertFalse(message.contains("请使用对应的 DEX/资源/AXML 接口"))
    }

    @Test
    fun allowAxmlStillBypassesOnlyTheGenericGuard() {
        ToolkitApkTargets.ordinary("AndroidManifest.xml", allowAxml = true)
    }
}