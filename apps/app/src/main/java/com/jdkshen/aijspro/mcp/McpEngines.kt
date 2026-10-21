package com.jdkshen.aijspro.mcp

import com.stardust.autojs.script.JavaScriptSource

internal object McpEngines {
    val names = listOf("rhino", "quickjs", "nodejs")
    fun normalize(value: String): String = when (value.trim().lowercase()) {
        "" -> ""
        "rhino", "rhinojs" -> "rhino"
        "quickjs" -> "quickjs"
        "node", "node24", "nodejs" -> "nodejs"
        else -> throw IllegalArgumentException("engine 必须是 rhino、quickjs 或 nodejs（兼容 node/node24）")
    }
    fun directive(engine: String): String = "// @engine " + if (engine == "nodejs") "node24" else engine
    fun actual(source: JavaScriptSource): String = when (source.engineName) {
        JavaScriptSource.ENGINE_QUICKJS -> "quickjs"
        JavaScriptSource.ENGINE_NODE24 -> "nodejs"
        else -> "rhino"
    }
}
