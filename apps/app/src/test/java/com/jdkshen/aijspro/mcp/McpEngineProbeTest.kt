package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.stardust.autojs.script.JavaScriptSource
import com.stardust.autojs.script.StringScriptSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpEngineProbeTest {
    private val body = "console.log('MCP_API_PROBE_RESULT=' + JSON.stringify(['ui','floaty']))"

    @Test
    fun selectsQuickJsAndRhinoExplicitlyInTheRealScriptSource() {
        mapOf("quickjs" to JavaScriptSource.ENGINE_QUICKJS, "rhino" to JavaScriptSource.ENGINE_RHINO,
            "nodejs" to JavaScriptSource.ENGINE_NODE24)
            .forEach { (engine, expected) ->
                val probe = McpEngineProbe(engine, body)
                val source = StringScriptSource(probe.name, probe.source).apply {
                    setPreferredEngine(if (engine == "quickjs") "rhino" else "quickjs")
                }
                assertEquals(expected, source.engineName)
            }
    }

    @Test
    fun interleavedLogsOnlyReturnTheCallingProbesResult() {
        val quickjs = McpEngineProbe("quickjs", body)
        val rhino = McpEngineProbe("rhino", body)
        val logs = listOf(log("MCP_API_PROBE_RESULT=[\"stale\"]"),
            log(rhino.marker + "[\"rhino\"]"), log(quickjs.marker + "[\"quickjs\"]"))

        assertNotEquals(quickjs.name, rhino.name)
        assertEquals("[\"quickjs\"]", quickjs.result(logs))
        assertEquals("[\"rhino\"]", rhino.result(logs))
        assertNull(McpEngineProbe("quickjs", body).result(logs))
    }

    @Test
    fun concurrentProbesHaveDistinctSourcesAndMarkers() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val probes = (1..100).map {
                pool.submit<McpEngineProbe> { McpEngineProbe("quickjs", body) }
            }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(100, probes.map { it.name }.toSet().size)
            assertEquals(100, probes.map { it.marker }.toSet().size)
            probes.forEach { assertTrue(it.source.contains(it.marker)) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownEngines() {
        McpEngineProbe("unknown", body)
    }

    private fun log(content: String) = JsonObject().apply { addProperty("content", content) }
}
