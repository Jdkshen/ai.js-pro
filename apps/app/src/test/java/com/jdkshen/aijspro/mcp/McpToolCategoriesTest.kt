package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolCategoriesTest {
    @Test fun everyCanonicalToolHasExactlyOneCategory() {
        val categorized = McpToolCategories.all.flatMap { category ->
            McpToolCategories.toolNamesIn(category.id).map { it to category.id }
        }
        assertEquals(McpToolCatalog.canonicalNames.size, categorized.size)
        assertEquals(McpToolCatalog.canonicalNames.toSet(), categorized.map { it.first }.toSet())
        assertEquals(McpToolCategories.all.size, categorized.map { it.second }.toSet().size)
        assertEquals(McpToolCatalog.canonicalNames.size, McpToolCategories.counts().values.sum())
        McpToolCatalog.canonicalNames.forEach { name ->
            assertTrue("未分类工具: " + name, McpToolCategories.categoryFor(name).id != "other")
        }
    }

    @Test fun categoriesAreOrderedAndTitlesAreUserFacing() {
        assertEquals(McpToolCategories.all.sortedBy { it.order }, McpToolCategories.all)
        McpToolCategories.all.forEach { category ->
            assertTrue(category.title.isNotBlank())
            assertTrue(category.summary.isNotBlank())
        }
        assertEquals("状态与诊断", McpToolCategories.categoryFor("get_status").title)
        assertEquals("终端与构建", McpToolCategories.categoryFor("terminal_exec").title)
        assertEquals("设备与无障碍", McpToolCategories.categoryFor("ui_click").title)
    }
}
