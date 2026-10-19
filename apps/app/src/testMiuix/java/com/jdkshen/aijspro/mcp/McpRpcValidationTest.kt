package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRpcValidationTest {
    @Test
    fun everyCamelCaseAliasResolvesAndKeepsItsArgumentsUnchanged() {
        McpToolCatalog.canonicalNames.forEach { canonical ->
            val alias = McpToolCatalog.dispatchAliases.getValue(canonical)
            val call = McpRpcValidation.parseToolCall(params(alias, JsonObject().apply { addProperty("value", canonical) }))
            assertEquals(alias, call.requestedName)
            assertEquals(canonical, call.canonicalName)
            assertEquals(canonical, call.arguments.get("value").asString)
        }
        McpToolCatalog.canonicalNames.forEach { canonical ->
            assertEquals(canonical, McpRpcValidation.parseToolCall(params(canonical)).canonicalName)
        }
    }

    @Test
    fun argumentsMayBeOmittedButMustOtherwiseBeAnObject() {
        assertEquals(0, McpRpcValidation.parseToolCall(params("get_status")).arguments.size())
        assertEquals(0, McpRpcValidation.parseToolCall(params("get_status", JsonObject())).arguments.size())
        listOf(JsonNull.INSTANCE, JsonArray(), JsonParser().parse("\"x\""), JsonParser().parse("1"))
            .forEach { invalid ->
                assertInvalid { McpRpcValidation.parseToolCall(params("get_status", invalid)) }
            }
    }

    @Test
    fun missingOrMalformedNameAndParamsAreInvalidParamsNotToolErrors() {
        assertInvalid { McpRpcValidation.parseToolCall(null) }
        assertInvalid { McpRpcValidation.parseToolCall(JsonArray()) }
        assertInvalid { McpRpcValidation.parseToolCall(JsonNull.INSTANCE) }
        assertInvalid { McpRpcValidation.parseToolCall(JsonObject()) }
        assertInvalid { McpRpcValidation.parseToolCall(JsonObject().apply { add("name", JsonNull.INSTANCE) }) }
        assertInvalid { McpRpcValidation.parseToolCall(JsonObject().apply { addProperty("name", 1) }) }
        assertInvalid { McpRpcValidation.parseToolCall(JsonObject().apply { addProperty("name", "  ") }) }
        assertInvalid { McpRpcValidation.parseToolCall(JsonObject().apply { addProperty("name", "not_a_tool") }) }
        assertInvalid { McpRpcValidation.parseToolCall(JsonObject().apply { addProperty("name", "getStatusExtra") }) }
    }

    @Test
    fun optionalParamsAndMethodClassificationStayCompatible() {
        assertNull(McpRpcValidation.optionalParams(null))
        assertEquals(0, McpRpcValidation.optionalParams(JsonObject())!!.size())
        assertInvalid { McpRpcValidation.optionalParams(JsonArray()) }
        listOf("initialize", "ping", "tools/list", "tools/call").forEach {
            assertTrue(it, McpRpcValidation.isSupportedMethod(it))
        }
        listOf("", "tools", "notifications/initialized", "GET").forEach {
            assertFalse(it, McpRpcValidation.isSupportedMethod(it))
        }
    }

    private fun params(name: String, arguments: com.google.gson.JsonElement? = null): JsonObject =
        JsonObject().apply {
            addProperty("name", name)
            if (arguments != null) add("arguments", arguments)
        }

    private fun assertInvalid(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected invalid params, got $error", error is McpInvalidParamsException)
    }
}
