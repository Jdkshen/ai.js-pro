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
    fun nodeRuntimeFormSeparatesEmbeddedRuntimeFromStandaloneCli() {
        // 默认形态：嵌入式共享库；CLI 尚未提取 → 不能声称可以启动独立 node。
        val embedded = McpEngineDiagnostics.nodeRuntimeForm(
            McpEngineDiagnostics.NodeRuntimeFormInput(
                processName = "com.jdkshen.aijspro:node24_1",
                hostExecutablePath = "/system/bin/app_process64",
                hostExecutablePresent = true,
                runtimeDirectoryPath = "/data/user/0/com.jdkshen.aijspro/app_node24-runtime/d2165091a8360066",
                cliLauncherPath = "/data/user/0/com.jdkshen.aijspro/app_node24-runtime/d2165091a8360066/node",
                cliLauncherPresent = false,
                cliLauncherExecutable = false,
                runtimeLibraryPath = "/data/user/0/com.jdkshen.aijspro/app_node24-runtime/d2165091a8360066/libnode.so",
                runtimeLibraryPresent = true,
                toolsDirectoryPath = "/data/user/0/com.jdkshen.aijspro/app_node24-tools/8619e4cdfc02e6b2",
                toolsDirectoryPresent = false
            )
        )
        assertEquals("embedded-shared-library", embedded.get("runtimeMode").asString)
        assertFalse(embedded.get("canSpawnStandaloneNode").asBoolean)
        assertTrue(embedded.get("hostExecutableNote").asString.contains("process.execPath"))
        assertEquals(1, embedded.getAsJsonArray("supportedChildScriptRunner").size())
        assertFalse(embedded.getAsJsonObject("standaloneCli").get("runnable").asBoolean)

        // CLI 已提取且同目录有 libnode.so：才能说可以启动独立 CLI，并给出命令形态。
        val extracted = McpEngineDiagnostics.nodeRuntimeForm(
            McpEngineDiagnostics.NodeRuntimeFormInput(
                processName = "com.jdkshen.aijspro",
                hostExecutablePath = "/system/bin/app_process64",
                hostExecutablePresent = true,
                runtimeDirectoryPath = "/data/user/0/com.jdkshen.aijspro/app_node24-runtime/d2165091a8360066",
                cliLauncherPath = "/data/user/0/com.jdkshen.aijspro/app_node24-runtime/d2165091a8360066/node",
                cliLauncherPresent = true,
                cliLauncherExecutable = true,
                runtimeLibraryPath = "/data/user/0/com.jdkshen.aijspro/app_node24-runtime/d2165091a8360066/libnode.so",
                runtimeLibraryPresent = true,
                toolsDirectoryPath = "/data/user/0/com.jdkshen.aijspro/app_node24-tools/8619e4cdfc02e6b2",
                toolsDirectoryPresent = true
            )
        )
        assertTrue(extracted.get("canSpawnStandaloneNode").asBoolean)
        assertEquals(2, extracted.getAsJsonArray("supportedChildScriptRunner").size())
        assertTrue(extracted.getAsJsonArray("supportedChildScriptRunner")[1].asString.startsWith("cli:"))
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
