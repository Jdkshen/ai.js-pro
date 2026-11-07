package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v4 §10.4：升级后要让客户端知道"工具目录变了、请重新 tools/list"。 */
class McpToolCatalogSignalTest {

    @Test
    fun firstInitializeHasNoNotice() {
        assertNull(McpToolCatalogSignal.notice(null, "218:1387:dev-1387", 218, "dev-1387"))
    }

    @Test
    fun unchangedFingerprintHasNoNotice() {
        val fp = McpToolCatalogSignal.fingerprint(218, 1387, "dev-1387")
        assertNull(McpToolCatalogSignal.notice(fp, fp, 218, "dev-1387"))
    }

    @Test
    fun upgradedBuildProducesNoticeWithAdvice() {
        val previous = McpToolCatalogSignal.fingerprint(215, 1382, "dev-1382")
        val current = McpToolCatalogSignal.fingerprint(218, 1388, "dev-1388")
        val notice = McpToolCatalogSignal.notice(previous, current, 218, "dev-1388")
        assertNotNull(notice)
        assertTrue(notice!!.get("changed").asBoolean)
        assertEquals(previous, notice.get("previous").asString)
        assertEquals(current, notice.get("current").asString)
        assertEquals(218, notice.get("toolCount").asInt)
        assertEquals("dev-1388", notice.get("build").asString)
        assertTrue(notice.get("advice").asString.contains("tools/list"))
        // instructions 首行提示：必须点明 build 与工具数，便于人/模型判断是否要刷新。
        val line = McpToolCatalogSignal.instructionLine(notice)
        assertTrue(line.contains("dev-1388"))
        assertTrue(line.contains("218"))
    }

    @Test
    fun toolCountChangeAloneIsEnough() {
        val previous = McpToolCatalogSignal.fingerprint(218, 1387, "dev-1387")
        val current = McpToolCatalogSignal.fingerprint(221, 1387, "dev-1387")
        assertNotNull(McpToolCatalogSignal.notice(previous, current, 221, "dev-1387"))
    }
}