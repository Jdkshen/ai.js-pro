package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolCatalogTest {
    @Test
    fun publicToolContractContainsExactlyTwentyTwoUniqueNames() {
        assertEquals(23, McpToolCatalog.names.size)
        assertEquals(McpToolCatalog.names.size, McpToolCatalog.names.toSet().size)
        assertTrue(McpToolCatalog.names.all { it.matches(Regex("[a-z][a-z0-9_]*")) })
        assertEquals("get_status", McpToolCatalog.names.first())
        assertEquals("workspace_request_apply", McpToolCatalog.names.last())
    }
}
