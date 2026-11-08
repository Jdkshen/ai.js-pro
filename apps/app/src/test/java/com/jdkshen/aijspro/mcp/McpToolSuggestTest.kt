package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `.4` P0-1：未知工具要给近邻名，而不是只报「未知工具」。 */
class McpToolSuggestTest {

    private val names = listOf("file_edit_text", "file_write_bytes", "file_append_text", "file_read_text", "zip_list")

    @Test
    fun suggestsRealNeighboursForInventedWriteTool() {
        // 他们真的调过 file_write_text（不存在）
        val near = McpToolSuggest.suggest("file_write_text", names)
        assertTrue("应该给出近邻：$near", near.isNotEmpty())
        assertTrue("应命中原有写工具：$near", near.contains("file_edit_text") || near.contains("file_write_bytes"))
        assertTrue(near.size <= 5)
    }

    @Test
    fun exactNameScoresFirst() {
        val near = McpToolSuggest.suggest("zip_list", names)
        assertEquals("zip_list", near.first())
    }

    @Test
    fun blankOrUnrelatedInputReturnsNothingOrFew() {
        assertTrue(McpToolSuggest.suggest("", names).isEmpty())
        val unrelated = McpToolSuggest.suggest("zzzz", names)
        assertTrue("无关输入不应硬凑：$unrelated", unrelated.size <= 1)
    }
}
