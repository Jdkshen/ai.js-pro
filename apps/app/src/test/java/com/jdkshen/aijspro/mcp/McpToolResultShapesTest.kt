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
        assertFalse("超过 32KiB（按镜像后总大小）不该 deepCopy", structured.has("data"))
        assertTrue("必须说明为什么没补", structured.has("dataOmitted"))
        val omitted = structured.getAsJsonObject("dataOmitted")
        assertTrue(omitted.get("bytes").asInt > McpToolResultShapes.DATA_MIRROR_LIMIT_BYTES)
        assertEquals(McpToolResultShapes.DATA_MIRROR_LIMIT_BYTES, omitted.get("limitBytes").asInt)
        assertTrue(omitted.get("reason").asString.contains("上限"))
        assertTrue("扁平顶层字段仍在", structured.has("xml"))
    }

    @Test
    fun mirrorLimitBoundaryCountsMirroredStructuredContentPlusContentText() {
        val flat = ToolkitIo.json("k" to "v")
        val withContent = toolResult(flat.deepCopy())
        withContent.add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", flat.toString()) }) })
        // 口径 = 镜像后 structuredContent + content[0].text；先算出这个总字节（探针：limit 设成整数上限永不触发）
        val probe = McpToolResultShapes.mirrorFlatResult(withContent.deepCopy(), limitBytes = Int.MAX_VALUE)
        val mirroredBytes = probe.getAsJsonObject("structuredContent").toString().toByteArray(Charsets.UTF_8).size
        val textBytes = flat.toString().toByteArray(Charsets.UTF_8).size
        val measured = mirroredBytes + textBytes
        val atLimit = McpToolResultShapes.mirrorFlatResult(withContent.deepCopy(), limitBytes = measured)
        assertTrue("恰好等于口径值就该补", atLimit.getAsJsonObject("structuredContent").has("data"))
        val overLimit = McpToolResultShapes.mirrorFlatResult(withContent.deepCopy(), limitBytes = measured - 1)
        assertFalse("超过一个字节就不补", overLimit.getAsJsonObject("structuredContent").has("data"))
        val omitted = overLimit.getAsJsonObject("structuredContent").getAsJsonObject("dataOmitted")
        assertEquals(measured, omitted.get("bytes").asInt)
        assertEquals(measured - 1, omitted.get("limitBytes").asInt)
        assertEquals(textBytes, omitted.get("contentTextBytes").asInt)
        assertEquals("mirroredStructuredContent+contentText", omitted.get("measured").asString)
        assertTrue(omitted.get("reason").asString.contains("镜像后 structuredContent + content[0].text"))
    }

    /**
     * W4 task-27（c）：名单内的大工具只补轻量摘要，不整棵 deepCopy；标量保留、容器只给计数。
     */
    @Test
    fun summaryToolsGetLightweightDataInsteadOfAFullCopy() {
        val items = JsonArray().apply { repeat(200) { add(JsonObject().apply { addProperty("name", "cap-$it"); addProperty("available", it % 2 == 0) }) } }
        val flat = ToolkitIo.json(
            "tool" to "shell_capabilities",
            "count" to 200,
            "truncated" to false,
            "longText" to "x".repeat(McpToolResultShapes.SUMMARY_STRING_CHARS + 50),
            "items" to items,
            "matrix" to ToolkitIo.json("grep" to true, "find" to false),
        )
        val result = McpToolResultShapes.mirrorFlatResult(toolResult(flat.deepCopy()), "shell_capabilities")
        val structured = result.getAsJsonObject("structuredContent")
        assertTrue("名单内工具必须补摘要 data", structured.has("data"))
        val summary = structured.getAsJsonObject("data")
        assertEquals(true, summary.get("summary").asBoolean)
        assertEquals("shell_capabilities", summary.get("tool").asString)
        assertEquals(200, summary.get("count").asInt)
        assertEquals(false, summary.get("truncated").asBoolean)
        assertFalse("摘要里不该出现整棵数组", summary.has("items"))
        assertFalse("摘要里不该出现整棵对象", summary.has("matrix"))
        val omitted = summary.getAsJsonArray("omitted")
        assertEquals(2, omitted.size())
        val omittedItems = omitted[0].asJsonObject
        assertEquals("items", omittedItems.get("key").asString)
        assertEquals("array", omittedItems.get("type").asString)
        assertEquals(200, omittedItems.get("count").asInt)
        assertTrue(omittedItems.get("bytes").asInt > 0)
        assertEquals("matrix", omitted[1].asJsonObject.get("key").asString)
        assertEquals("object", omitted[1].asJsonObject.get("type").asString)
        assertEquals(2, omitted[1].asJsonObject.get("keys").asInt)
        assertTrue("长字符串要截断", summary.get("longText").asString.length < flat.get("longText").asString.length)
        assertTrue(summary.get("longText").asString.endsWith("见 content[0].text）"))
        assertEquals(true, structured.get("dataSummary").asBoolean)
        assertEquals(McpToolResultShapes.SUMMARY_NOTE, structured.get("dataSummaryNote").asString)
        assertTrue("顶层扁平字段必须保留", structured.has("items"))
        assertTrue("摘要必须明显小于整棵镜像", summary.toString().length < structured.get("items").toString().length)
    }

    /** 名单外的工具行为不变：整棵镜像（小载荷），不出现 dataSummary。 */
    @Test
    fun toolsOutsideTheSummaryListStillGetTheFullMirror() {
        val flat = ToolkitIo.json("tool" to "list_engines", "items" to JsonArray().apply { add("rhino") })
        val result = McpToolResultShapes.mirrorFlatResult(toolResult(flat.deepCopy()), "list_engines")
        val structured = result.getAsJsonObject("structuredContent")
        val data = structured.getAsJsonObject("data")
        assertTrue("名单外工具应整棵镜像", data.has("items"))
        assertEquals("list_engines", data.get("tool").asString)
        assertFalse(structured.has("dataSummary"))
        assertFalse(structured.has("dataOmitted"))
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
