package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context

class McpEngineApiTest {
    @Test fun rhinoJavaObjectsExposeMembersWithoutEcmaDescriptorSupport() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            scope.put("fixture", scope, Context.javaToJS(java.util.ArrayList<String>(), scope))
            cx.evaluateString(scope, "var output='';var console={log:function(s){output=s;}};", "fixture", 1, null)
            cx.evaluateString(scope, McpEngineApi.probe("", "fixture", true), "probe", 1, null)
            val data = JsonParser().parse(scope.get("output", scope).toString().substringAfter("MCP_API_PROBE_RESULT=")).asJsonObject
            assertTrue(data.getAsJsonArray("members").any { it.asJsonObject.get("name").asString == "add" })
            assertTrue(data.getAsJsonArray("keys").any { it.asString == "size" })
        } finally { Context.exit() }
    }
    @Test fun callPreservesReceiverAndNestedJsonArguments() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, "var output='';var console={log:function(s){output=s;}};var fixture={n:4,add:function(x){return this.n+x.nested[0];}};", "fixture", 1, null)
            cx.evaluateString(scope, McpEngineApi.call("", "fixture.add", JsonParser().parse("[{\"nested\":[7]}]").asJsonArray), "call", 1, null)
            val text = scope.get("output", scope).toString().substringAfter("MCP_API_PROBE_RESULT=")
            assertEquals(11, JsonParser().parse(text).asJsonObject.get("value").asInt)
        } finally { Context.exit() }
    }
    @Test fun memberQueryDoesNotExecuteGetters() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, "var console={log:function(s){}};var calls=0;var fixture={get value(){calls++;return 3;}};", "fixture", 1, null)
            cx.evaluateString(scope, McpEngineApi.probe("", "fixture", false), "probe", 1, null)
            assertEquals(0.0, Context.toNumber(scope.get("calls", scope)), 0.0)
        } finally { Context.exit() }
    }
    @Test fun probeMarksMissingAndAccessorExplicitlyWithoutReadingGetter() {
        val cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, "var output='';var console={log:function(s){output=s;}};var calls=0;" +
                "var fixture={nested:{ok:true},get value(){calls++;return 3;}};", "fixture", 1, null)
            cx.evaluateString(scope, McpEngineApi.probe("", "fixture.value", false), "probe", 1, null)
            val accessor = result(scope)
            assertEquals("accessor", accessor.get("presence").asString)
            assertTrue(accessor.get("present").asBoolean)
            assertEquals(0.0, Context.toNumber(scope.get("calls", scope)), 0.0)

            cx.evaluateString(scope, McpEngineApi.probe("", "fixture.missing", false), "probe", 1, null)
            val missing = result(scope)
            assertEquals("missing", missing.get("presence").asString)
            assertFalse(missing.get("present").asBoolean)
            assertEquals("undefined", missing.get("type").asString)
        } finally { Context.exit() }
    }
    private fun result(scope: org.mozilla.javascript.Scriptable) =
        JsonParser().parse(scope.get("output", scope).toString().substringAfter("MCP_API_PROBE_RESULT=")).asJsonObject
    @Test fun rejectsMemberExpressionInjection() {
        assertTrue(runCatching { McpEngineApi.probe("", "x);exit();//", true) }.isFailure)
    }
    @Test fun nodeSyntaxDiagnosticsKeepSourceLocation() {
        val result = McpNodeSyntax.result(1, "[stdin]:2\nconst x = ;\n          ^\nSyntaxError: Unexpected token ';'", "bad.node.js", 1)
        assertFalse(result.get("valid").asBoolean)
        assertFalse(result.get("executed").asBoolean)
        assertEquals(2, result.getAsJsonArray("errors")[0].asJsonObject.get("line").asInt)
        assertEquals(11, result.getAsJsonArray("errors")[0].asJsonObject.get("column").asInt)
    }
}
