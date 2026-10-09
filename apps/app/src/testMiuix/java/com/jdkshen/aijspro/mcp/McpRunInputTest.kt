package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class McpRunInputTest {
    private fun parse(json: String) = McpRunInput(JsonParser().parse(json).asJsonObject)

    @Test fun acceptsLegacyFilesAndProSourceWithConfig() {
        assertEquals("main.js", parse("""{"path":"main.js"}""").path)
        assertEquals("main.js", parse("""{"file":"main.js"}""").path)
        val input = parse("""{"source":"", "engine":"quickjs", "wait":true,
            "config":{"engine":"rhino","path":"nested","delay":10,"interval":20,"loopTimes":2,"arguments":{"n":1,"nil":null}}}""")
        assertEquals("", input.source)
        assertEquals("quickjs", input.engine)
        assertTrue(input.wait)
        assertEquals("nested", input.workingDirectory)
        assertEquals(10, input.delay); assertEquals(20, input.interval); assertEquals(2, input.loopTimes)
    }
    @Test fun sourceIsRequiredAndAmbiguousInputsAreRejected() {
        listOf("{}", """{"source":"1","path":"main.js"}""", """{"file":"main.js","path":"main.js"}""").forEach {
            assertTrue(runCatching { parse(it) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun rejectsWrongTypesAndFractionalOrOutOfRangeConfig() {
        listOf("""{"source":true}""", """{"source":"","wait":"true"}""",
            """{"source":"","engine":"nodejs"}""", """{"source":"","config":{"loopTimes":1.5}}""",
            """{"source":"","config":{"delay":-1}}""", """{"source":"","config":{"path":[1]}}""",
            """{"source":"","config":{"unknown":1}}""").forEach {
            assertTrue(it, runCatching { parse(it) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun preservesJsonArgumentValues() {
        val json = JsonParser().parse("""{"a":[true,null,{"text":"测试"}],"n":42}""")
        val values = McpRunInput.argumentValue(json) as Map<*, *>
        val array = values["a"] as List<*>
        assertEquals(true, array[0]); assertNull(array[1])
        assertEquals("测试", (array[2] as Map<*, *>) ["text"])
        assertEquals(42, (values["n"] as Number).toInt())
        assertTrue(values["n"] is Double)
    }
    @Test fun rejectsArgumentsOutsideFiniteJsNumberRange() {
        val json = JsonParser().parse("""{"nested":[1e500]}""")
        assertTrue(runCatching { McpRunInput.argumentValue(json) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun supportsSearchPathArrayAndRejectsOversizedSource() {
        assertEquals(listOf("lib", "nested"), parse("""{"source":"","config":{"path":["lib","nested"]}}""").searchPaths())
        val json = com.google.gson.JsonObject().apply { addProperty("source", "x".repeat(256 * 1024 + 1)) }
        assertTrue(runCatching { McpRunInput(json) }.exceptionOrNull() is IllegalArgumentException)
    }
}
