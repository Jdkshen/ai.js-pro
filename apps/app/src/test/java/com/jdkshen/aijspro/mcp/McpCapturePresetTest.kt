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

    @Test
    fun fastestPresetIsTheSmallest() {
        val resolved = McpCapturePreset.resolve(McpCapturePreset.MODE_FASTEST)
        assertEquals("jpg", resolved.format)
        assertEquals(40, resolved.quality)
        assertEquals(360, resolved.maxWidth)
        assertEquals(1500, resolved.timeoutMillis)
    }

    @Test
    fun regionAcceptsArrayStringAndObjectForms() {
        val array = McpCapturePreset.parseRegion(com.google.gson.JsonParser().parse("[10,20,110,220]"))!!
        assertEquals(10, array.left); assertEquals(20, array.top)
        assertEquals(110, array.right); assertEquals(220, array.bottom)
        assertEquals(100, array.width); assertEquals(200, array.height)

        val text = McpCapturePreset.parseRegion(com.google.gson.JsonParser().parse("\"[10,20][110,220]\""))!!
        assertEquals(array, text)

        val rect = McpCapturePreset.parseRegion(com.google.gson.JsonParser().parse("{\"left\":10,\"top\":20,\"right\":110,\"bottom\":220}"))!!
        assertEquals(array, rect)

        val xyw = McpCapturePreset.parseRegion(com.google.gson.JsonParser().parse("{\"x\":10,\"y\":20,\"width\":100,\"height\":200}"))!!
        assertEquals(array, xyw)

        assertEquals(null, McpCapturePreset.parseRegion(null))
        assertEquals(null, McpCapturePreset.parseRegion(com.google.gson.JsonNull.INSTANCE))
    }

    @Test
    fun regionRejectsBadInput() {
        val cases = listOf("[1,2,3]", "\"10,20,30,40\"", "{\"x\":10,\"y\":20}", "[300,20,110,220]")
        for (text in cases) {
            var failed = false
            try { McpCapturePreset.parseRegion(com.google.gson.JsonParser().parse(text)) } catch (error: IllegalArgumentException) { failed = true }
            assertTrue("应当拒绝：$text", failed)
        }
    }
}
