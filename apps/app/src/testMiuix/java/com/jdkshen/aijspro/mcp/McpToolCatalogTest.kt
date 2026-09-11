package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolCatalogTest {
    @Test
    fun publicToolContractContainsExactlyTwentySixUniqueNames() {
        assertEquals(26, McpToolCatalog.names.size)
        assertEquals(McpToolCatalog.names.size, McpToolCatalog.names.toSet().size)
        assertTrue(McpToolCatalog.names.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
        assertEquals("get_status", McpToolCatalog.names.first())
        assertEquals("workspace_cleanup", McpToolCatalog.names.last())
    }
}
