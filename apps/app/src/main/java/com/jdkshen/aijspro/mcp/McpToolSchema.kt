package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Parameter types shared by the public MCP tool definitions. */
internal object McpToolSchema {
    /** 数组型参数（元素为数字）：坐标点/矩形。 */
    private val numberArrayParams = setOf("point", "rect", "windowPoint1", "screenshotPoint1", "windowPoint2", "screenshotPoint2")

    private val integerParams = setOf(
        "offset", "limit", "maxBytes", "afterId", "timeoutSeconds",
        "olderThanHours", "keepAppliedHours", "maxNodes", "maxDepth", "windowId", "timeoutMillis",
        "quality", "maxWidth", "maxChars", "waitMillis", "x", "y", "x1", "y1", "x2", "y2", "durationMillis", "index", "intervalMillis", "maxResults",
        // 同类漏网（本次审计发现）：pid 是整数、keepLatest 是整数，之前都落进 string。
        "pid", "keepLatest"
    )
    private val booleanParams = setOf("wait", "create", "recursive", "applied", "dataUri", "includeInherited", "eof", "includeSystem", "exact",
        "measureScreenshot",
        // 反馈 bug#1：withLogs 之前不在本表里 → 被默认声明成 string，客户端传布尔直接被拒，整个功能不可用。
        "withLogs",
        // 同类漏网（本次审计发现）：workspace_cleanup 的两个开关。
        "dryRun", "protectApplied")

    fun terminalSchema(): JsonObject = terminalCommandSchema(includeBackend = true)
    fun shellRishSchema(): JsonObject = terminalCommandSchema(includeBackend = false)

    private fun terminalCommandSchema(includeBackend: Boolean): JsonObject {
        val names = if (includeBackend) {
            arrayOf("command", "workingDirectory", "timeoutSeconds", "backend", "autoRoute")
        } else {
            arrayOf("command", "workingDirectory", "timeoutSeconds")
        }
        return objectSchema(*names, required = arrayOf("command")).apply {
            val fields = getAsJsonObject("properties")
            fields.add("env", JsonObject().apply {
                addProperty("type", "object")
                add("additionalProperties", JsonObject().apply { addProperty("type", "string") })
            })
            fields.getAsJsonObject("timeoutSeconds").apply {
                addProperty("minimum", 1)
                addProperty("maximum", 3600)
                addProperty("default", 300)
            }
            if (includeBackend) fields.getAsJsonObject("backend").apply {
                addProperty("description", "app = 应用 UID（默认）；shizuku = Shizuku 授权身份，不回退 app")
                add("enum", JsonArray().apply { add("app"); add("shizuku") })
                addProperty("default", "app")
            }
            if (includeBackend) fields.getAsJsonObject("autoRoute").apply {
                addProperty("description", "默认 false：用错后端时在输出首行给出「该命令需要 X 后端」提示并保留原输出；" +
                    "true 时仅对只读白名单（logcat -d / pm list / dumpsys 等）自动切换后端，并在首行标注 routedFrom/routedTo")
                addProperty("default", false)
            }
        }
    }
    fun callApiSchema(): JsonObject = objectSchema("engine", "module", "name", "timeoutSeconds",
        required = arrayOf("engine", "name")).apply {
        getAsJsonObject("properties").add("args", JsonObject().apply {
            addProperty("type", "array")
        })
    }

    fun searchApiDocsSchema(): JsonObject = objectSchema("query", "engine", "category", "kind", "offset", "limit").apply {
        val fields = getAsJsonObject("properties")
        fields.getAsJsonObject("query").addProperty("maxLength", 256)
        fields.getAsJsonObject("category").addProperty("maxLength", 128)
        fields.getAsJsonObject("engine").add("enum", JsonArray().apply {
            listOf("all", "rhino", "quickjs", "nodejs", "node", "node24", "rhinojs").forEach { add(it) }
        })
        fields.getAsJsonObject("kind").add("enum", JsonArray().apply { listOf("all", "guide", "legacy").forEach { add(it) } })
        fields.getAsJsonObject("offset").apply { addProperty("minimum", 0); addProperty("maximum", Int.MAX_VALUE); addProperty("default", 0) }
        fields.getAsJsonObject("limit").apply { addProperty("minimum", 1); addProperty("maximum", 20); addProperty("default", 10) }
    }

    fun readApiDocSchema(): JsonObject = objectSchema("id", "indexSha256", "section", "offset", "maxChars", required = arrayOf("id")).apply {
        val fields = getAsJsonObject("properties")
        fields.add("includeExamples", JsonObject().apply { addProperty("type", "boolean"); addProperty("default", true) })
        fields.getAsJsonObject("section").addProperty("maxLength", 80)
        fields.getAsJsonObject("id").addProperty("pattern", "^api-[1-9][0-9]{0,8}$")
        fields.getAsJsonObject("indexSha256").apply { addProperty("maxLength", 71); addProperty("pattern", "^(|sha256:[0-9a-f]{64})$") }
        fields.getAsJsonObject("offset").apply { addProperty("minimum", 0); addProperty("maximum", Int.MAX_VALUE); addProperty("default", 0) }
        fields.getAsJsonObject("maxChars").apply { addProperty("minimum", 128); addProperty("maximum", 32000); addProperty("default", 16000) }
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
    fun uiClickSchema(): JsonObject = uiSelectorSchema("text", "resourceId", "contentDesc", "className", "bounds",
        "x", "y", "index", "timeoutMillis", "exact")
    fun uiLongClickSchema(): JsonObject = uiSelectorSchema("text", "resourceId", "contentDesc", "className", "bounds",
        "x", "y", "index", "timeoutMillis", "exact")
    fun uiInputSchema(): JsonObject = uiSelectorSchema("text", "resourceId", "contentDesc", "className", "bounds",
        "index", "timeoutMillis", "exact", required = arrayOf("text"))
    fun uiNodeActionSchema(): JsonObject = uiSelectorSchema("text", "resourceId", "contentDesc", "className",
        "bounds", "index", "timeoutMillis", "exact", "action", "args", required = arrayOf("action")).apply {
        getAsJsonObject("properties").add("args", JsonObject().apply { addProperty("type", "object") })
    }
    fun uiSwipeSchema(): JsonObject = objectSchema("x1", "y1", "x2", "y2", "durationMillis",
        required = arrayOf("x1", "y1", "x2", "y2"))
    fun uiGlobalSchema(): JsonObject = objectSchema("action", required = arrayOf("action"))
    fun uiWaitSchema(): JsonObject = uiSelectorSchema("index", "text", "resourceId", "contentDesc", "className", "bounds",
        "timeoutMillis", "intervalMillis", "exact")
    fun uiScrollSchema(): JsonObject = uiSelectorSchema("direction", "text", "resourceId", "contentDesc",
        "className", "timeoutMillis", "exact")
    fun appListSchema(): JsonObject = objectSchema("query", "includeSystem", "limit")
    fun appInfoSchema(): JsonObject = objectSchema("packageName", required = arrayOf("packageName"))
    fun appLaunchSchema(): JsonObject = objectSchema("packageName", "activity",
        required = arrayOf("packageName"))
    fun appSettingsSchema(): JsonObject = objectSchema("packageName")
    fun appUrlSchema(): JsonObject = objectSchema("url", required = arrayOf("url"))
    fun apkInstallSchema(): JsonObject = objectSchema("path", required = arrayOf("path"))
    fun apkUninstallSchema(): JsonObject = objectSchema("packageName", required = arrayOf("packageName"))
    private fun uiSelectorSchema(vararg names: String, required: Array<String> = emptyArray()): JsonObject =
        objectSchema(*names, required = required).apply {
            val properties = getAsJsonObject("properties")
            val nested = objectSchema("text", "resourceId", "contentDesc", "contentDescription", "className", "bounds", "x", "y", "index", "exact")
            val bounds = JsonObject().apply {
                add("oneOf", JsonArray().apply {
                    add(JsonObject().apply { addProperty("type", "string") })
                    add(JsonObject().apply {
                        addProperty("type", "array"); addProperty("minItems", 4); addProperty("maxItems", 4)
                        add("items", JsonObject().apply { addProperty("type", "integer") })
                    })
                    add(JsonObject().apply { addProperty("type", "object") })
                })
            }
            nested.getAsJsonObject("properties").add("bounds", bounds.deepCopy())
            properties.add("selector", nested)
            if (properties.has("bounds")) properties.add("bounds", bounds)
        }

    fun objectSchema(vararg names: String, required: Array<String> = emptyArray()) = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            names.forEach { name ->
                add(name, JsonObject().apply {
                    if (name in numberArrayParams) {
                        // 坐标点/矩形：[x,y] 或 [l,t,r,b]
                        addProperty("type", "array")
                        add("items", JsonObject().apply { addProperty("type", "number") })
                        addProperty("minItems", 2); addProperty("maxItems", 4)
                    } else {
                        addProperty("type", when (name) {
                            in integerParams -> "integer"
                            in booleanParams -> "boolean"
                            else -> "string"
                        })
                    }
                })
            }
        })
        if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it) } })
        addProperty("additionalProperties", false)
    }
}
