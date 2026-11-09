package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W4 task-33：族前缀别名（`dex_*` / `so_*`）—— **只派发、不广播**。
 *
 * 契约：别名能进 tools/call（canonicalName 解析），但不进 names/tools/list（advertised 不变）；
 * 别名与 canonical 走同一分支（派发只按 canonical 名），所以返回结构必然一致；未知族名走未知工具路径。
 */
class McpFamilyAliasTest {

    /** 冻结的 16 条映射（与 McpToolCatalog.familyAliases 逐字一致）。 */
    private val expected = linkedMapOf(
        "dex_read_class_outline" to "apk_dex_read_class_outline",
        "dex_xref" to "apk_dex_xref",
        "dex_search_members" to "apk_dex_search_members",
        "dex_call_graph" to "apk_dex_call_graph",
        "dex_hierarchy" to "apk_dex_hierarchy",
        "dex_interface_table" to "apk_dex_interface_table",
        "dex_list_classes" to "apk_dex_list_classes",
        "dex_list_methods" to "apk_dex_list_methods",
        "so_inspect" to "apk_native_inspect",
        "so_list_items" to "apk_native_list_items",
        "so_map_address" to "apk_native_map_address",
        "so_xref" to "apk_native_xref",
        "so_disassemble" to "apk_native_disassemble",
        "so_analyze_control_flow" to "apk_native_analyze_control_flow",
        "so_patch_instructions" to "apk_native_patch_instructions",
        "so_patch_string" to "apk_native_patch_string",
    )

    private val mcpToolsSource: String by lazy {
        File("src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt").readText()
    }

    private fun toolCallParams(name: String, arguments: JsonObject = JsonObject()): JsonObject =
        JsonObject().apply {
            addProperty("name", name)
            add("arguments", arguments)
        }

    @Test
    fun everyFamilyAliasResolvesToItsCanonicalTarget() {
        assertEquals("族前缀别名集合与冻结名单不一致", expected.keys.sorted(), McpToolCatalog.familyAliases.keys.sorted())
        expected.forEach { (alias, canonical) ->
            assertEquals("解析不一致：" + alias, canonical, McpToolCatalog.canonicalName(alias))
            assertTrue(canonical + " 必须是 canonical", canonical in McpToolCatalog.canonicalNames)
            assertFalse(alias + " 不能与 canonical 同名", alias in McpToolCatalog.canonicalNames)
        }
        assertEquals(8, expected.keys.count { it.startsWith("dex_") })
        assertEquals(8, expected.keys.count { it.startsWith("so_") })
    }

    @Test
    fun familyAliasesAreDispatchOnlyAndDoNotChangeTheAdvertisedList() {
        McpToolCatalog.familyAliases.keys.forEach { alias ->
            assertFalse(alias + " 不能进 tools/list（advertised 必须仍是 224）", alias in McpToolCatalog.names)
        }
        assertEquals("advertised 数量不能被族前缀别名改变", 224, McpToolCatalog.names.size)
        assertEquals("canonical 数量不变", 215, McpToolCatalog.canonicalNames.size)
        assertEquals(McpToolCatalog.canonicalNames.size + McpToolCatalog.aliases.size, McpToolCatalog.names.size)
        // 每个别名都能走真实的 tools/call 解析（McpRpcValidation → canonicalName）
        McpToolCatalog.familyAliases.forEach { (alias, canonical) ->
            val call = McpRpcValidation.parseToolCall(toolCallParams(alias))
            assertEquals(canonical, call.canonicalName)
            assertEquals(alias, call.requestedName)
        }
    }

    @Test
    fun aliasAndCanonicalProduceTheSameDispatch() {
        val arguments = JsonObject().apply { addProperty("path", "x.apk") }
        val byAlias = McpRpcValidation.parseToolCall(toolCallParams("dex_list_classes", arguments))
        val byCanonical = McpRpcValidation.parseToolCall(toolCallParams("apk_dex_list_classes", arguments))
        assertEquals("别名与 canonical 必须解析到同一个 canonical 名", byCanonical.canonicalName, byAlias.canonicalName)
        assertEquals("参数原样透传", byCanonical.arguments.toString(), byAlias.arguments.toString())
        assertNotEquals(byCanonical.requestedName, byAlias.requestedName)
        // 派发只按 canonical 名（单点）→ 两条路径进同一分支，返回结构必然一致
        assertTrue(
            "McpTools 的派发必须只认 invocation.canonicalName",
            mcpToolsSource.contains("val name = invocation.canonicalName"),
        )
    }

    @Test
    fun unknownFamilyAliasGoesThroughTheUnknownToolPathWithFamilyHints() {
        val error = runCatching { McpRpcValidation.parseToolCall(toolCallParams("dex_bogus")) }.exceptionOrNull()
        assertTrue("未知工具必须是 McpInvalidParamsException，实际 " + error, error is McpInvalidParamsException)
        val message = error!!.message.orEmpty()
        assertTrue(message.contains("未知工具：dex_bogus"))
        assertTrue("近邻提示里应出现族前缀名，实际：" + message, message.contains("dex_"))
        val hints = McpToolSuggest.suggest("dex_bogus", McpToolCatalog.names + McpToolCatalog.familyAliases.keys)
        assertTrue("suggest 应给出 dex_* 近邻，实际 " + hints, hints.any { it.startsWith("dex_") })
        // 未知名不能被 canonicalName 悄悄放行成自身（否则会绕过 canonical 校验）
        assertEquals("dex_bogus", McpToolCatalog.canonicalName("dex_bogus"))
    }

    @Test
    fun toolSchemaResolvesAliasesThroughTheSameCanonicalLookup() {
        assertTrue(
            "tool_schema 必须按 canonical 解析别名",
            mcpToolsSource.contains("toolDefinitionsByName[requested] ?: toolDefinitionsByName[canonical]"),
        )
        assertTrue(
            "tool_schema 回显族前缀别名",
            mcpToolsSource.contains("McpToolCatalog.familyAliasesOf(McpToolCatalog.canonicalName(name))"),
        )
        assertEquals(listOf("dex_xref"), McpToolCatalog.familyAliasesOf("apk_dex_xref"))
        assertEquals(listOf("so_list_items"), McpToolCatalog.familyAliasesOf("apk_native_list_items"))
        assertEquals(emptyList<String>(), McpToolCatalog.familyAliasesOf("shell_capabilities"))
    }
}
