package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §2.7：root 变化要能被看见，而不是让旧脚本无声失效。 */
class McpRootStabilityTest {
    @Test
    fun detectsRootChangeWithinProcess() {
        McpRootStability.resetForTests()
        val first = McpRootStability.observe("/storage/emulated/0/脚本", now = 100L)
        assertEquals("/storage/emulated/0/脚本", first.get("path").asString)
        assertTrue(first.get("stableRoot").asBoolean)
        assertFalse(first.get("changedThisProcess").asBoolean)

        val second = McpRootStability.observe("/storage/emulated/0/新目录", now = 200L)
        assertTrue(second.get("changedThisProcess").asBoolean)
        assertFalse(second.get("stableRoot").asBoolean)
        assertEquals(200L, second.get("changedAt").asLong)
        assertEquals("/storage/emulated/0/脚本", second.get("previousPath").asString)
        assertTrue(second.get("note").asString.contains("绝对路径"))

        val third = McpRootStability.observe("/storage/emulated/0/新目录", now = 300L)
        assertEquals(200L, third.get("changedAt").asLong)
        assertEquals(3, third.get("observations").asInt)
    }
}
