package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitFridaEvalTest {
    @Test fun failedButWithMessagesIsPreservedAndTopLevel() {
        val response=JsonParser().parse("""
            {"state":"FAILED","error":{"message":{"messages":[
              {"type":"send","payload":"{\"hook\":\"installed\"}"},
              {"type":"error","message":"session detached: CONNECTION_TERMINATED"}
            ]}}}
        """.trimIndent()).asJsonObject
        val normalized=ToolkitFridaEval.normalize(response)
        assertEquals("FAILED_BUT_PARTIAL",normalized.get("state").asString)
        assertTrue(normalized.get("partial").asBoolean)
        assertEquals(2,normalized.getAsJsonArray("messages").size())
        assertEquals(2,normalized.getAsJsonArray("messageLines").size())
        assertTrue(normalized.get("messagesPreserved").asBoolean)
        assertTrue(normalized.get("statusSemantics").asJsonObject.has("decompileIncomplete"))
    }

    @Test fun failedWithoutMessagesStillFails() {
        val response=JsonParser().parse("""{"state":"FAILED"}""").asJsonObject
        try { ToolkitFridaEval.normalize(response); org.junit.Assert.fail("expected failure") }
        catch (e: ToolkitException) { assertEquals("FRIDA_SCRIPT_ERROR",e.code) }
    }
}
