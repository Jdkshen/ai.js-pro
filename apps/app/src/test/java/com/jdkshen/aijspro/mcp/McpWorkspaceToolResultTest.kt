package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpWorkspaceToolResultTest {
    @Test
    fun workspaceSuccessKeepsLegacyContentTextAndPutsFlatFieldsUnderData() {
        val flat = ToolkitIo.json(
            "workspaceId" to "ws-1234",
            "targetPath" to "main.js",
            "state" to "OPEN",
            "pendingApproval" to false,
        )
        val result = McpToolResultShapes.success(flat)

        val contentText = result.getAsJsonArray("content")[0].asJsonObject.tString("text")
        val legacy = JsonParser().parse(contentText).asJsonObject
        assertEquals("ws-1234", legacy.tString("workspaceId"))
        assertEquals("main.js", legacy.tString("targetPath"))

        val structured = result.getAsJsonObject("structuredContent")
        assertTrue(structured.get("ok").asBoolean)
        assertEquals("ws-1234", structured.getAsJsonObject("data").tString("workspaceId"))
        assertEquals("OPEN", structured.getAsJsonObject("data").tString("state"))
    }

    @Test
    fun workspaceDiffKeepsRawLegacyTextAndAddsDataDiff() {
        val diff = "--- a/main.js\n+++ b/main.js\n@@ -1 +1 @@\n-old\n+new\n"
        val result = McpToolResultShapes.textResult("diff", diff)
        assertEquals(diff, result.getAsJsonArray("content")[0].asJsonObject.tString("text"))
        val structured = result.getAsJsonObject("structuredContent")
        assertTrue(structured.get("ok").asBoolean)
        assertEquals(diff, structured.getAsJsonObject("data").tString("diff"))
    }

    @Test
    fun readScriptLikePayloadKeepsFlatFieldsInBothLegacyTextAndData() {
        val flat = ToolkitIo.json(
            "content" to "console.log(1)",
            "offset" to 0,
            "nextOffset" to 14,
            "size" to 14,
            "truncated" to false,
        )
        val result = McpToolResultShapes.success(flat)
        val legacy = JsonParser().parse(result.getAsJsonArray("content")[0].asJsonObject.tString("text")).asJsonObject
        assertEquals("console.log(1)", legacy.tString("content"))
        assertEquals("console.log(1)", result.getAsJsonObject("structuredContent").getAsJsonObject("data").tString("content"))
    }
}
