package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class McpRhinoArgumentsTest {
    @Test fun nestedJsonUsesNativeObjectsArraysAndPrimitives() {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()
            val target = context.newObject(scope)
            val json = JsonParser().parse("""{"nested":{"items":[1,null,"🙂"],"flag":true},"nil":null,"n":2.5}""").asJsonObject
            McpRhinoArguments.install(context, target, json.entrySet().associate { it.key to McpRunInput.argumentValue(it.value) })
            ScriptableObject.putProperty(scope, "argv", target)
            val result = context.evaluateString(scope,
                "[Array.isArray(argv.nested.items),argv.nested.items[1]===null,argv.nested.items[2]==='🙂',argv.nested.flag===true,argv.nil===null,typeof argv.n,argv.nested.items.join('|')].join(';')",
                "arguments-test", 1, null)
            assertEquals("true;true;true;true;true;number;1||🙂", Context.toString(result))
        } finally { Context.exit() }
    }

    @Test fun changingJsArgumentsDoesNotMutateExecutionConfigContainers() {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            val scope = context.initStandardObjects()
            val target = context.newObject(scope)
            val original = linkedMapOf<String, Any?>("nested" to linkedMapOf("items" to listOf(1, 2)))
            McpRhinoArguments.install(context, target, original)
            ScriptableObject.putProperty(scope, "argv", target)
            val result = context.evaluateString(scope,
                "argv.nested.items.push(3); argv.nested.message='kept'; argv.nested.items.length+':'+argv.nested.message",
                "arguments-test", 1, null)
            assertEquals("3:kept", Context.toString(result))
            assertEquals(listOf(1, 2), (original["nested"] as Map<*, *>)["items"])
        } finally { Context.exit() }
    }
}
