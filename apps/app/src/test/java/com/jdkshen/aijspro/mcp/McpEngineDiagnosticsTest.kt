package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpEngineDiagnosticsTest {
    private fun snapshot(lastFailure: JsonObject? = null) = McpEngineDiagnostics.buildSnapshot(
        capturedAt = 123456789L,
        processName = "com.jdkshen.aijspro",
        pid = 321,
        rhinoAvailable = false,
        poolCapacity = 3,
        nodeProcesses = listOf(
            McpEngineDiagnostics.NodeProcessObservation(
                "com.jdkshen.aijspro:node24", "legacy", null, "not_visible"),
            McpEngineDiagnostics.NodeProcessObservation(
                "com.jdkshen.aijspro:node24_1", "slot1", 777, "alive"),
            McpEngineDiagnostics.NodeProcessObservation(
                "com.jdkshen.aijspro:node24_2", "slot2", null, "not_visible"),
            McpEngineDiagnostics.NodeProcessObservation(
                "com.jdkshen.aijspro:node24_3", "slot3", null, "not_visible")
        ),
        runtimePinCount = 3,
        lastFailure = lastFailure
    )

    @Test
    fun schemaIsStableAndUnknownsAreNotClaimedAsIdle() {
        val root = snapshot()
        val encoded = com.google.gson.JsonParser().parse(root.toString()).asJsonObject
        assertTrue(encoded.getAsJsonObject("nodePool").has("busy"))
        assertTrue(encoded.getAsJsonObject("nodePool").get("busy").isJsonNull)
        assertTrue(encoded.has("lastFailure"))
        assertEquals(1, root.get("schemaVersion").asInt)
        assertEquals(123456789L, root.get("capturedAt").asLong)
        assertEquals("com.jdkshen.aijspro", root.getAsJsonObject("process").get("name").asString)
        assertEquals(321, root.getAsJsonObject("process").get("pid").asInt)

        val engines = root.getAsJsonArray("engines")
        assertEquals(3, engines.size())
        assertEquals("rhino", engines[0].asJsonObject.get("engine").asString)
        assertFalse(engines[0].asJsonObject.get("available").asBoolean)
        engines.forEach { element ->
            assertEquals("unverified", element.asJsonObject.get("behaviorStatus").asString)
            assertEquals("shape", element.asJsonObject.get("probeEvidence").asString)
        }

        val pool = root.getAsJsonObject("nodePool")
        assertEquals(3, pool.get("capacity").asInt)
        assertTrue(pool.get("busy").isJsonNull)
        assertFalse(pool.get("busyKnown").asBoolean)
        assertEquals("process-local", pool.get("scope").asString)
        assertTrue(pool.get("note").asString.contains("不能当成 0"))

        val processes = pool.getAsJsonArray("knownProcesses")
        assertEquals(4, processes.size())
        assertTrue(processes[0].asJsonObject.get("pid").isJsonNull)
        assertEquals(777, processes[1].asJsonObject.get("pid").asInt)
        assertEquals("not_visible", processes[2].asJsonObject.get("observed").asString)

        val runtime = root.getAsJsonObject("runtime")
        assertFalse(runtime.get("hashRead").asBoolean)
        assertFalse(runtime.get("scriptsStarted").asBoolean)
        assertFalse(runtime.getAsJsonObject("cache").get("speedMeasured").asBoolean)
        assertEquals("C-NODE24", runtime.getAsJsonObject("cache").get("statsLogTag").asString)
        assertEquals(3, runtime.get("runtimePins").asInt)

        assertTrue(root.get("lastFailure").isJsonNull)
    }

    @Test
    fun lastFailureIsOnlySerializedWhenEvidenceExists() {
        val failure = JsonObject().apply {
            addProperty("engine", "nodejs")
            addProperty("type", "PoolBusyException")
            addProperty("message", "busy")
        }
        val root = snapshot(failure)
        assertNotNull(root.getAsJsonObject("lastFailure"))
        assertEquals("PoolBusyException", root.getAsJsonObject("lastFailure").get("type").asString)
    }

    @Test
    fun failureRecordingKeepsNewestEvidenceWithoutCopyingUserData() {
        val now = System.currentTimeMillis()
        McpEngineDiagnostics.recordFailure("nodejs", "latest", now, "PoolBusyException")
        McpEngineDiagnostics.recordFailure("rhino", "older", now - 1, "OlderFailure")
        val failure = McpEngineDiagnostics.failureSnapshot()!!
        assertEquals("latest", failure.get("executionId").asString)
        assertEquals("PoolBusyException", failure.get("type").asString)
        assertFalse(failure.has("message"))
        assertFalse(failure.has("source"))
        assertFalse(failure.has("path"))
        failure.addProperty("type", "mutated")
        assertEquals("PoolBusyException", McpEngineDiagnostics.failureSnapshot()!!.get("type").asString)
    }
}
