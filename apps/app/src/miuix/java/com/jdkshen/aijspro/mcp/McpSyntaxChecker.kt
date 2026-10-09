package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.autojs.engine.QuickJsBytecodeCompiler
import com.stardust.autojs.engine.QuickJsException
import com.stardust.autojs.engine.RhinoSyntaxPreprocessor
import com.stardust.autojs.engine.XmlLiteralPreprocessor
import org.mozilla.javascript.Context
import org.mozilla.javascript.RhinoException

internal object McpSyntaxChecker {
    fun check(code: String, engine: String, name: String = "mcp-syntax.js"): JsonObject {
        require(engine == "quickjs" || engine == "rhino") { "engine 必须是 quickjs 或 rhino" }
        require(code.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "源码不得超过 256 KiB" }
        val started = System.nanoTime()
        var prepared = code.removePrefix("\uFEFF")
        var failure: Throwable? = null
        try {
            if (engine == "quickjs") {
                prepared = XmlLiteralPreprocessor.apply(prepared)
                QuickJsBytecodeCompiler.compile(prepared, name)
            } else {
                prepared = RhinoSyntaxPreprocessor.apply(prepared)
                val compiler = Context.enter()
                try {
                    compiler.languageVersion = Context.VERSION_ES6
                    compiler.optimizationLevel = -1
                    compiler.compileString(prepared, name, 1, null)
                } finally { Context.exit() }
            }
        } catch (error: RhinoException) { failure = error }
        catch (error: QuickJsException) { failure = error }
        // Native-library or compiler infrastructure errors are not syntax errors.
        return JsonObject().apply {
            addProperty("success", failure == null); addProperty("valid", failure == null)
            addProperty("engine", engine); addProperty("name", name)
            addProperty("preprocessed", prepared != code.removePrefix("\uFEFF"))
            addProperty("executed", false)
            addProperty("elapsedMs", (System.nanoTime() - started) / 1_000_000)
            add("errors", JsonArray().apply {
                failure?.let { error -> add(JsonObject().apply {
                    addProperty("type", error.javaClass.simpleName)
                    addProperty("message", error.message.orEmpty())
                    if (error is RhinoException) {
                        if (error.lineNumber() > 0) addProperty("line", error.lineNumber())
                        if (error.columnNumber() > 0) addProperty("column", error.columnNumber())
                    } else {
                        Regex("(?m)\\bat .*?:(\\d+)(?::(\\d+))?\\)?(?:\\s|$)")
                            .find(error.message.orEmpty())?.let { position ->
                                addProperty("line", position.groupValues[1].toInt())
                                position.groupValues[2].toIntOrNull()?.let { addProperty("column", it) }
                            }
                    }
                    addProperty("coordinates", if (prepared != code) "preprocessed" else "source")
                }) }
            })
        }
    }
}
