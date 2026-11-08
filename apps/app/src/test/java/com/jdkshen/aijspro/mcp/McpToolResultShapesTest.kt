package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4 task-21：扁平结果的 data 镜像 + 大小护栏（纯函数，不依赖 Android）。
 *
 * 语义：保留全部扁平顶层字段，只**加**一份 `data` 副本；已是 `{ok,...}` 信封、已有 `data`、
 * 失败结果、图像类（无 structuredContent）一律不动；序列化超过上限时不补，改写成 `dataOmitted{...}`。
 */
class McpToolResultShapesTest {

    private fun toolResult(structured: JsonObject?, isError: Boolean = false): JsonObject = JsonObject().apply {
        if (isError) addProperty("isError", true)
        structured?.let { add("structuredContent", it) }
    }

    @Test
    fun smallFlatPayloadGetsDataMirrorAndKeepsFlatTopLevel() {
        val flat = ToolkitIo.json("tool" to "shell_capabilities", "items" to JsonArray().apply { add("grep") })
        val structured = McpToolResultShapes.mirrorFlatResult(toolResult(flat))
            .getAsJsonObject("structuredContent")
        assertEquals("shell_capabilities", structured.get("tool").asString)
        assertTrue("镜像不该丢 items", structured.has("items"))
        assertTrue("小载荷必须补 data", structured.has("data"))
        assertEquals("shell_capabilities", structured.getAsJsonObject("data").get("tool").asString)
        assertFalse("data 是加之前的副本，不该含 data", structured.getAsJsonObject("data").has("data"))
        assertFalse(structured.getAsJsonObject("data").has("dataOmitted"))
    }

    @Test
    fun envelopeAndExistingDataAndFailuresAreUntouched() {
        val envelope = toolResult(ToolkitIo.json("ok" to true, "data" to ToolkitIo.json("k" to "v")))
        val wrapped = McpToolResultShapes.mirrorFlatResult(envelope).getAsJsonObject("structuredContent")
        assertEquals("信封顶层字段不该被改写", 2, wrapped.entrySet().size)

        val already = ToolkitIo.json("jobId" to "term-1", "data" to ToolkitIo.json("jobId" to "term-1"))
        val kept = McpToolResultShapes.mirrorFlatResult(toolResult(already)).getAsJsonObject("structuredContent")
        assertEquals("已有 data 不该重复包", 1, kept.getAsJsonObject("data").entrySet().size)

        val failure = toolResult(ToolkitIo.json("ok" to false), isError = true)
        assertFalse(McpToolResultShapes.mirrorFlatResult(failure).getAsJsonObject("structuredContent").has("data"))

        val image = toolResult(null)
        assertFalse(McpToolResultShapes.mirrorFlatResult(image).has("structuredContent"))
    }

    @Test
    fun oversizedFlatPayloadIsNotMirroredAndSaysWhy() {
        val flat = ToolkitIo.json("xml" to "x".repeat(70 * 1024))
        val structured = McpToolResultShapes.mirrorFlatResult(toolResult(flat))
            .getAsJsonObject("structuredContent")
        assertFalse("超过 64KiB 不该 deepCopy", structured.has("data"))
        assertTrue("必须说明为什么没补", structured.has("dataOmitted"))
        val omitted = structured.getAsJsonObject("dataOmitted")
        assertTrue(omitted.get("bytes").asInt > McpToolResultShapes.DATA_MIRROR_LIMIT_BYTES)
        assertEquals(McpToolResultShapes.DATA_MIRROR_LIMIT_BYTES, omitted.get("limitBytes").asInt)
        assertTrue(omitted.get("reason").asString.contains("上限"))
        assertTrue("扁平顶层字段仍在", structured.has("xml"))
    }

    @Test
    fun mirrorLimitBoundaryIsExact() {
        val flat = ToolkitIo.json("k" to "v")
        val bytes = flat.toString().toByteArray(Charsets.UTF_8).size
        val atLimit = McpToolResultShapes.mirrorFlatResult(toolResult(flat.deepCopy()), limitBytes = bytes)
        assertTrue("恰好等于上限就该补", atLimit.getAsJsonObject("structuredContent").has("data"))
        val overLimit = McpToolResultShapes.mirrorFlatResult(toolResult(flat.deepCopy()), limitBytes = bytes - 1)
        assertFalse("超过一个字节就不补", overLimit.getAsJsonObject("structuredContent").has("data"))
        assertTrue(overLimit.getAsJsonObject("structuredContent").has("dataOmitted"))
    }

    @Test
    fun flatWithDataMirrorItselfNeverDoubles() {
        val flat = ToolkitIo.json("jobId" to "term-2")
        val once = McpToolResultShapes.flatWithDataMirror(flat)
        assertEquals("term-2", once.getAsJsonObject("data").get("jobId").asString)
        val twice = McpToolResultShapes.flatWithDataMirror(once)
        assertEquals(1, twice.getAsJsonObject("data").entrySet().size)
    }
}
