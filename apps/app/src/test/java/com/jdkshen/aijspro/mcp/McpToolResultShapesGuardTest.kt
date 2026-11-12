package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 32 KiB 护栏（P1-A/P0-2/task-27）的**确定性**回归：用小 limitBytes 走同一条判定路径，
 * 不必构造 32 KiB 真数据。
 *
 * 判据（对齐 McpToolResultShapes.mirrorFlatResult 的实现与注释 :132-171）：
 *  · 未超限 ⇒ 补 data（全量镜像或摘要），不带 dataOmitted；
 *  · 超限   ⇒ 写 dataOmitted{reason,bytes,limitBytes,kind}，**不补 data**（让调用方知道「本该有 data」而不是静默缺失）；
 *  · 量测口径 = **镜像后 structuredContent + content[0].text**（既有注释口径，本测试钉住它）；
 *  · 跳过条件（isError=true / 无 structuredContent / 已有 ok 或 data）⇒ 原样返回，不得二次包装。
 */
class McpToolResultShapesGuardTest {

    private fun flat(structured: JsonObject, contentText: String? = null): JsonObject {
        val result = JsonObject()
        result.add("structuredContent", structured)
        if (contentText != null) {
            result.add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", contentText)
                })
            })
        }
        return result
    }

    private fun structuredWith(vararg pairs: Pair<String, String>): JsonObject = JsonObject().apply {
        pairs.forEach { (k, v) -> addProperty(k, v) }
    }

    @Test fun underLimitMirrorsDataAndDoesNotOmit() {
        val structured = structuredWith("workspaceId" to "w1", "state" to "open")
        val result = McpToolResultShapes.mirrorFlatResult(flat(structured), null, 1_000_000)
        assertTrue("未超限必须补 data", structured.has("data"))
        assertFalse("未超限不得写 dataOmitted", structured.has("dataOmitted"))
        assertFalse("非摘要工具不得打 dataSummary", structured.has("dataSummary"))
    }

    @Test fun overLimitWritesDataOmittedInsteadOfData() {
        val structured = structuredWith("workspaceId" to "w1", "state" to "open")
        val result = McpToolResultShapes.mirrorFlatResult(flat(structured), null, limitBytes = 8)
        assertTrue("超限必须写 dataOmitted（不静默缺失）", structured.has("dataOmitted"))
        assertFalse("超限不得补 data", structured.has("data"))
        val omitted = structured.getAsJsonObject("dataOmitted")
        assertEquals("量测口径必须是镜像后 structuredContent 加 content 文本",
            "mirroredStructuredContent+contentText", omitted.get("measured").asString)
        assertEquals("全量镜像路径的 kind", "全量镜像", omitted.get("kind").asString)
        assertEquals(8, omitted.get("limitBytes").asInt)
    }

    @Test fun summaryToolsGetSummaryOrSummaryOmission() {
        val under = structuredWith("nodes" to "many")
        McpToolResultShapes.mirrorFlatResult(flat(under), "get_ui_layout", 1_000_000)
        assertTrue("摘要工具未超限 ⇒ 补摘要 data", under.has("data"))
        assertTrue("摘要必须可识别", under.get("dataSummary").asBoolean)
        assertTrue("摘要必须带说明", under.has("dataSummaryNote"))

        val over = structuredWith("nodes" to "many")
        McpToolResultShapes.mirrorFlatResult(flat(over), "get_ui_layout", limitBytes = 8)
        assertTrue(over.has("dataOmitted"))
        assertEquals("摘要路径的 kind", "摘要", over.getAsJsonObject("dataOmitted").get("kind").asString)
    }

    @Test fun contentTextCountsTowardTheLimit() {
        // 同一份 structuredContent，只有 content[0].text 不同 ⇒ 长文本那份应当被判超限（钉住量测口径）。
        val shortText = structuredWith("a" to "1")
        McpToolResultShapes.mirrorFlatResult(flat(shortText, "short"), null, limitBytes = 200)
        assertTrue("短 content 未超限 ⇒ 补 data", shortText.has("data"))

        val longText = structuredWith("a" to "1")
        McpToolResultShapes.mirrorFlatResult(flat(longText, "x".repeat(500)), null, limitBytes = 200)
        assertTrue("content 文本计入体积 ⇒ 长文本那份必须写 dataOmitted", longText.has("dataOmitted"))
    }

    @Test fun skipConditionsDoNotWrapTwice() {
        val error = flat(structuredWith("a" to "1"))
        error.addProperty("isError", true)
        McpToolResultShapes.mirrorFlatResult(error, null, 1_000_000)
        assertFalse("失败结果不得被补 data", error.getAsJsonObject("structuredContent").has("data"))

        val noStructured = JsonObject().apply { addProperty("text", "plain") }
        McpToolResultShapes.mirrorFlatResult(noStructured, null, 1_000_000)
        assertFalse("无 structuredContent ⇒ 原样返回", noStructured.has("data"))

        val already = flat(JsonObject().apply { addProperty("ok", true) })
        McpToolResultShapes.mirrorFlatResult(already, null, 1_000_000)
        assertFalse("已是信封 ⇒ 不得二次包装", already.getAsJsonObject("structuredContent").has("data"))

        val hasData = flat(JsonObject().apply { addProperty("data", "x") })
        McpToolResultShapes.mirrorFlatResult(hasData, null, 1_000_000)
        assertEquals("已有 data ⇒ 不得覆盖", "x", hasData.getAsJsonObject("structuredContent").get("data").asString)
    }
}
