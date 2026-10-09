package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolCatalogTest {
    @Test
    fun publicToolContractContainsCanonicalToolsAndAllProAliases() {
        assertEquals(32, McpToolCatalog.canonicalNames.size)
        assertEquals(39, McpToolCatalog.names.size)
        assertEquals(McpToolCatalog.names.size, McpToolCatalog.names.toSet().size)
        assertTrue(McpToolCatalog.names.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")) })
        assertEquals("get_status", McpToolCatalog.names.first())
        assertEquals("workspace_cleanup", McpToolCatalog.canonicalNames[25])
        assertEquals("getMcpLog", McpToolCatalog.names.last())
        assertEquals(setOf("getUiLayout", "captureScreen", "checkSyntax", "runScript", "learnAutoJsSkill", "stopAllScripts", "getMcpLog"), McpToolCatalog.aliases.keys)
        McpToolCatalog.aliases.forEach { (alias, canonical) ->
            assertTrue(canonical in McpToolCatalog.canonicalNames)
            assertEquals(canonical, McpToolCatalog.canonicalName(alias))
        }
    }
}
