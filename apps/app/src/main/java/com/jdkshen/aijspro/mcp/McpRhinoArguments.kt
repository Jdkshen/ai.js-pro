package com.jdkshen.aijspro.mcp

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** Converts MCP JSON containers in the running Rhino scope before user code starts. */
internal object McpRhinoArguments {
    fun install(context: Context, target: Scriptable, arguments: Map<String, Any?>) {
        arguments.forEach { (key, value) ->
            ScriptableObject.putProperty(target, key, convert(context, target, value))
        }
    }

    private fun convert(context: Context, scope: Scriptable, value: Any?): Any? = when (value) {
        is Map<*, *> -> context.newObject(scope).apply {
            value.forEach { (key, item) -> ScriptableObject.putProperty(this, key.toString(), convert(context, scope, item)) }
        }
        is List<*> -> context.newArray(scope, value.map { convert(context, scope, it) }.toTypedArray())
        is Number -> value.toDouble()
        else -> value
    }
}
