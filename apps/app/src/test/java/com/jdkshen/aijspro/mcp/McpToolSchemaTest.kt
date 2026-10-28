package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolSchemaTest {
    @Test fun documentationSchemasPublishRequiredIdFiltersAndBoundedNativeIntegers() {
        val search = McpToolSchema.searchApiDocsSchema()
        assertFalse(search.has("required"))
        val fields = search.getAsJsonObject("properties")
        assertEquals("integer", fields.getAsJsonObject("limit").get("type").asString)
        assertEquals(20, fields.getAsJsonObject("limit").get("maximum").asInt)
        assertTrue(fields.getAsJsonObject("engine").getAsJsonArray("enum").map { it.asString }.contains("nodejs"))
        val read = McpToolSchema.readApiDocSchema()
        assertEquals(listOf("id"), read.getAsJsonArray("required").map { it.asString })
        assertEquals(32000, read.getAsJsonObject("properties").getAsJsonObject("maxChars").get("maximum").asInt)
        assertFalse(read.get("additionalProperties").asBoolean)
    }
    @Test fun terminalEnvironmentAndPollingHaveNativeJsonTypes() {
        val schema = McpToolSchema.terminalSchema()
        val env = schema.getAsJsonObject("properties").getAsJsonObject("env")
        assertEquals("object", env.get("type").asString)
        assertEquals("string", env.getAsJsonObject("additionalProperties").get("type").asString)
        val poll = McpToolSchema.objectSchema("waitMillis", "eof").getAsJsonObject("properties")
        assertEquals("integer", poll.getAsJsonObject("waitMillis").get("type").asString)
        assertEquals("boolean", poll.getAsJsonObject("eof").get("type").asString)
    }

    @Test
    fun recursiveListingAndCleanupPublishTheTypesTheirHandlersAccept() {
        val properties = McpToolSchema.objectSchema(
            "recursive", "applied", "olderThanHours", "keepAppliedHours"
        ).getAsJsonObject("properties")

        assertEquals("boolean", properties.getAsJsonObject("recursive").get("type").asString)
        assertEquals("boolean", properties.getAsJsonObject("applied").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("olderThanHours").get("type").asString)
        assertEquals("integer", properties.getAsJsonObject("keepAppliedHours").get("type").asString)
    }

    @Test
    fun keepsExecutionAndFileParametersCompatible() {
        val schema = McpToolSchema.objectSchema("path", "engine", "wait", "timeoutSeconds",
            "offset", "maxBytes", "afterId", "limit", "create", required = arrayOf("path"))
        val properties = schema.getAsJsonObject("properties")

        listOf("path", "engine").forEach {
            assertEquals("string", properties.getAsJsonObject(it).get("type").asString)
        }
        listOf("wait", "create").forEach {
            assertEquals("boolean", properties.getAsJsonObject(it).get("type").asString)
        }
        listOf("timeoutSeconds", "offset", "maxBytes", "afterId", "limit").forEach {
            assertEquals("integer", properties.getAsJsonObject(it).get("type").asString)
        }
        assertEquals(listOf("path"), schema.getAsJsonArray("required").map { it.asString })
        assertFalse(schema.get("additionalProperties").asBoolean)
    }

    @Test
    fun parameterlessToolsPublishAnObjectSchema() {
        val schema = McpToolSchema.objectSchema()
        assertEquals("object", schema.get("type").asString)
        assertEquals(0, schema.getAsJsonObject("properties").size())
        assertFalse(schema.has("required"))
        assertTrue(schema.has("additionalProperties"))
    }
    @org.junit.Test fun uiSelectorsAdvertiseNestedTargetsAndBoundsShapes() {
        val input = McpToolSchema.uiInputSchema().getAsJsonObject("properties")
        org.junit.Assert.assertEquals("object", input.getAsJsonObject("selector").get("type").asString)
        org.junit.Assert.assertTrue(input.getAsJsonObject("selector").getAsJsonObject("properties").has("text"))
        org.junit.Assert.assertEquals(3, input.getAsJsonObject("bounds").getAsJsonArray("oneOf").size())
        org.junit.Assert.assertTrue(McpToolSchema.uiWaitSchema().getAsJsonObject("properties").has("index"))
    }

}
