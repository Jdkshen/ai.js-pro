package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolCatalogTest {
    @Test
    fun publicToolContractContainsCanonicalToolsAndAllProAliases() {
        assertEquals(59, McpToolCatalog.canonicalNames.size)
        assertEquals(68, McpToolCatalog.names.size)
        assertEquals(McpToolCatalog.names.size, McpToolCatalog.names.toSet().size)
        assertTrue(McpToolCatalog.names.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*")) })
        assertEquals("get_status", McpToolCatalog.names.first())
        assertEquals("workspace_cleanup", McpToolCatalog.canonicalNames[25])
        assertEquals("closeOverlays", McpToolCatalog.names.last())
        assertEquals(setOf("getUiLayout", "captureScreen", "checkSyntax", "runScript", "learnAutoJsSkill", "stopAllScripts", "getMcpLog", "listOverlays", "closeOverlays"), McpToolCatalog.aliases.keys)
        McpToolCatalog.aliases.forEach { (alias, canonical) ->
            assertTrue(canonical in McpToolCatalog.canonicalNames)
            assertEquals(canonical, McpToolCatalog.canonicalName(alias))
        }
    }

    @Test
    fun learnAutoJsSkillKeepsProSpellingAndAlsoAcceptsDeterministicCamelCase() {
        assertEquals("learn_autojs_skill", McpToolCatalog.canonicalName("learnAutoJsSkill"))
        assertEquals("learn_autojs_skill", McpToolCatalog.canonicalName("learnAutojsSkill"))
        assertTrue("learnAutoJsSkill" in McpToolCatalog.names)
        assertTrue("learnAutojsSkill" !in McpToolCatalog.names)
        assertEquals(68, McpToolCatalog.names.size)
        assertEquals(McpToolCatalog.names.size, McpToolCatalog.names.toSet().size)
    }

    @Test
    fun everyCanonicalToolHasADeterministicCamelCaseDispatchAliasWithoutGrowingTheList() {
        assertEquals(59, McpToolCatalog.dispatchAliases.size)
        McpToolCatalog.canonicalNames.forEach { canonical ->
            val alias = McpToolCatalog.dispatchAliases.getValue(canonical)
            assertEquals(camelCase(canonical), alias)
            assertTrue(alias.matches(Regex("[a-z][a-zA-Z0-9]*")))
            assertEquals(canonical, McpToolCatalog.canonicalName(alias))
            assertEquals(canonical, McpToolCatalog.canonicalName(canonical))
        }
        assertEquals("getStatus", McpToolCatalog.dispatchAliases.getValue("get_status"))
        assertEquals("workspaceRequestApply", McpToolCatalog.dispatchAliases.getValue("workspace_request_apply"))
        // Dispatch-only aliases must not appear a second time in tools/list.
        val extraDispatchNames = McpToolCatalog.dispatchAliases.values.toSet() - McpToolCatalog.aliases.keys
        assertTrue(extraDispatchNames.isNotEmpty())
        assertTrue(extraDispatchNames.none { it in McpToolCatalog.names })
    }

    private fun camelCase(name: String) = name.split('_').mapIndexed { index, part ->
        if (index == 0) part else part.replaceFirstChar { it.uppercaseChar() }
    }.joinToString("")
}
