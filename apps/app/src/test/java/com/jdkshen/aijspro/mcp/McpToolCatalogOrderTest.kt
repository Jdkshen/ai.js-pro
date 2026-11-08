package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `listTools()` 里有一条运行时自检：工具定义顺序必须**逐字**等于 [McpToolCatalog.names]，
 * 不一致时 MCP 会直接停止监听（dev-1402 就这么踩过一次：`tool_schema` 插到了 toolkit 名字之前）。
 * 这里把目录的"布局契约"钉住，避免下次又在中间插名字。
 */
class McpToolCatalogOrderTest {

    @Test
    fun engineToolsComeFirstThenToolkitNamesThenMetaTools() {
        val names = McpToolCatalog.canonicalNames
        val toolkit = McpToolkitCatalog.names
        val toolkitStart = names.size - toolkit.size - 1
        assertTrue("toolkit 名字必须连续排在尾部之前：$toolkitStart", toolkitStart >= 0)
        assertEquals(toolkit, names.subList(toolkitStart, toolkitStart + toolkit.size))
        // 末位是元工具（schema 自描述）；若以后再加元工具，请同步改 listTools 的添加顺序。
        assertEquals("tool_schema", names.last())
    }
}
