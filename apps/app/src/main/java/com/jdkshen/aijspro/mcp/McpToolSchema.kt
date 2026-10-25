package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Parameter types shared by the public MCP tool definitions. */
internal object McpToolSchema {
    private val integerParams = setOf(
        "offset", "limit", "maxBytes", "afterId", "timeoutSeconds",
        "olderThanHours", "keepAppliedHours", "maxNodes", "maxDepth", "windowId", "timeoutMillis",
        "quality", "maxWidth", "maxChars", "waitMillis", "x", "y", "x1", "y1", "x2", "y2", "durationMillis", "index", "intervalMillis", "maxResults"
    )
    private val booleanParams = setOf("wait", "create", "recursive", "applied", "dataUri", "includeInherited", "eof", "includeSystem", "exact")

    fun terminalSchema(): JsonObject = objectSchema("command", "workingDirectory", "timeoutSeconds",
        required = arrayOf("command")).apply {
        getAsJsonObject("properties").add("env", JsonObject().apply {
            addProperty("type", "object")
            add("additionalProperties", JsonObject().apply { addProperty("type", "string") })
        })
    }
    fun callApiSchema(): JsonObject = objectSchema("engine", "module", "name", "timeoutSeconds",
        required = arrayOf("engine", "name")).apply {
        getAsJsonObject("properties").add("args", JsonObject().apply {
            addProperty("type", "array")
        })
    }

    fun runSchema(): JsonObject = objectSchema(
        "path", "file", "source", "engine", "wait", "timeoutSeconds", "name", "workingDirectory"
    ).apply {
        add("oneOf", JsonArray().apply {
            listOf("path", "file", "source").forEach { key ->
                add(JsonObject().apply { add("required", JsonArray().apply { add(key) }) })
            }
        })
        getAsJsonObject("properties").add("config", JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("path", JsonObject().apply {
                    add("oneOf", JsonArray().apply {
                        add(JsonObject().apply { addProperty("type", "string") })
                        add(JsonObject().apply {
                            addProperty("type", "array")
                            add("items", JsonObject().apply { addProperty("type", "string") })
                        })
                    })
                })
                listOf("workingDirectory", "engine").forEach {
                    add(it, JsonObject().apply { addProperty("type", "string") })
                }
                listOf("delay", "interval", "loopTimes").forEach {
                    add(it, JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
                }
                add("arguments", JsonObject().apply { addProperty("type", "object") })
            })
            addProperty("additionalProperties", false)
        })
    }

    fun uiFindSchema(): JsonObject = objectSchema("text", "resourceId", "contentDesc", "className", "bounds",
        "limit", "timeoutMillis", "exact")
    fun uiClickSchema(): JsonObject = objectSchema("text", "resourceId", "contentDesc", "className", "bounds",
        "x", "y", "index", "timeoutMillis", "exact")
    fun uiLongClickSchema(): JsonObject = objectSchema("text", "resourceId", "contentDesc", "className", "bounds",
        "x", "y", "index", "timeoutMillis", "exact")
    fun uiInputSchema(): JsonObject = objectSchema("text", "resourceId", "contentDesc", "className", "bounds",
        "index", "timeoutMillis", "exact", required = arrayOf("text"))
    fun uiSwipeSchema(): JsonObject = objectSchema("x1", "y1", "x2", "y2", "durationMillis",
        required = arrayOf("x1", "y1", "x2", "y2"))
    fun uiGlobalSchema(): JsonObject = objectSchema("action", required = arrayOf("action"))
    fun uiWaitSchema(): JsonObject = objectSchema("text", "resourceId", "contentDesc", "className", "bounds",
        "timeoutMillis", "intervalMillis", "exact")
    fun uiScrollSchema(): JsonObject = objectSchema("direction", "text", "resourceId", "contentDesc",
        "className", "timeoutMillis", "exact")
    fun appListSchema(): JsonObject = objectSchema("query", "includeSystem", "limit")
    fun appInfoSchema(): JsonObject = objectSchema("packageName", required = arrayOf("packageName"))
    fun appLaunchSchema(): JsonObject = objectSchema("packageName", "activity",
        required = arrayOf("packageName"))
    fun appSettingsSchema(): JsonObject = objectSchema("packageName")
    fun appUrlSchema(): JsonObject = objectSchema("url", required = arrayOf("url"))
    fun apkInstallSchema(): JsonObject = objectSchema("path", required = arrayOf("path"))
    fun apkUninstallSchema(): JsonObject = objectSchema("packageName", required = arrayOf("packageName"))
    fun objectSchema(vararg names: String, required: Array<String> = emptyArray()) = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            names.forEach { name ->
                add(name, JsonObject().apply {
                    addProperty("type", when (name) {
                        in integerParams -> "integer"
                        in booleanParams -> "boolean"
                        else -> "string"
                    })
                })
            }
        })
        if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it) } })
        addProperty("additionalProperties", false)
    }
}
