package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `capture_screen` 三种预设：默认参数、显式覆盖、未知模式兜底。 */
class McpCapturePresetTest {
    @Test
    fun fastPresetIsSmallJpegWithShortTimeout() {
        val resolved = McpCapturePreset.resolve(McpCapturePreset.MODE_FAST)
        assertEquals("fast", resolved.mode)
        assertEquals("jpg", resolved.format)
        assertEquals(60, resolved.quality)
        assertEquals(540, resolved.maxWidth)
        assertEquals(2000, resolved.timeoutMillis)
        assertTrue(resolved.note.contains("fast"))
    }

    @Test
    fun qualityPresetIsOriginalPngWithLongerTimeout() {
        val resolved = McpCapturePreset.resolve(McpCapturePreset.MODE_QUALITY)
        assertEquals("quality", resolved.mode)
        assertEquals("png", resolved.format)
        assertEquals(100, resolved.quality)
        assertEquals(0, resolved.maxWidth)
        assertEquals(6000, resolved.timeoutMillis)
    }

    @Test
    fun balancedPresetKeepsLegacyDefaults() {
        val resolved = McpCapturePreset.resolve(McpCapturePreset.MODE_BALANCED)
        assertEquals("png", resolved.format)
        assertEquals(100, resolved.quality)
        assertEquals(0, resolved.maxWidth)
        assertEquals(3000, resolved.timeoutMillis)
    }

    @Test
    fun explicitArgumentsOverrideThePreset() {
        val resolved = McpCapturePreset.resolve(McpCapturePreset.MODE_FAST, format = "PNG", quality = 95, maxWidth = 1080, timeoutMillis = 5000)
        assertEquals("png", resolved.format)
        assertEquals(95, resolved.quality)
        assertEquals(1080, resolved.maxWidth)
        assertEquals(5000, resolved.timeoutMillis)
    }

    @Test
    fun unknownModeFallsBackToBalancedAndIsRejectedByTool() {
        assertFalse(McpCapturePreset.isKnownMode("turbo"))
        assertTrue(McpCapturePreset.isKnownMode("fast"))
        assertTrue(McpCapturePreset.isKnownMode("balanced"))
        assertTrue(McpCapturePreset.isKnownMode("quality"))
        val resolved = McpCapturePreset.resolve("turbo")
        assertEquals("balanced", resolved.mode)
    }
}
