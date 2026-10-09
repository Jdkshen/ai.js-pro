package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import java.util.UUID

/** Each probe has its own source name and log marker, including concurrent requests. */
internal class McpEngineProbe(engine: String, body: String) {
    private val id = UUID.randomUUID().toString()
    val name = "mcp-api-probe-$id"
    val marker = "MCP_API_PROBE_RESULT_$id="
    val source: String

    init {
        require(engine == "quickjs" || engine == "rhino") { "engine 必须是 quickjs 或 rhino" }
        require(body.contains(RESULT_MARKER)) { "探针缺少结果标记" }
        source = "// @engine $engine\n" + body.replace(RESULT_MARKER, marker)
    }

    fun result(logs: Iterable<JsonObject>): String? = logs.firstNotNullOfOrNull { log ->
        val content = log.get("content")?.asString.orEmpty()
        val offset = content.indexOf(marker)
        if (offset < 0) null else content.substring(offset + marker.length)
    }

    companion object {
        const val RESULT_MARKER = "MCP_API_PROBE_RESULT="
    }
}
