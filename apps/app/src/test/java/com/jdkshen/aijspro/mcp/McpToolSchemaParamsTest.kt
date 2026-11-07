package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 锁死一类真实事故：工具的 inputSchema 参数类型与运行时读取方式不一致。
 *
 * 实例（用户反馈 dev-1365 bug#1）：app_trace_start.withLogs 没被登记进 booleanParams，于是被默认声明成
 * string，客户端按 schema 传布尔值直接被拒 —— 整个功能对任何客户端都不可用。
 * 同类还有 workspace_cleanup.dryRun/protectApplied（布尔）与 pid/keepLatest（整数）。
 *
 * 参数声明的来源有三处，本测试全部纳入：
 *  1) McpToolSchema 的 booleanParams / integerParams / numberArrayParams；
 *  2) assets/mcp 下 JSON 资产里 apk_ 与逆向工具的 properties.type；
 *  3) Kotlin 里 field("x", ToolkitIo.json("type" to "boolean")) 这种内联声明。
 *
 * 检查规则：凡是运行时用 args.tBool("x") / args.tInt("x") 读的参数，都必须在上面三处被声明成
 * 对应类型；没声明（会退化成 string）或类型不符即失败。
 */
class McpToolSchemaParamsTest {
    private val mcpDir = File("src/main/java/com/jdkshen/aijspro/mcp")
    private val schemaAssets = File("src/main/assets/mcp")

    @Test
    fun knownBooleanAndIntegerParamsAreDeclaredWithTheRightType() {
        val schema = McpToolSchema.objectSchema("withLogs", "dryRun", "protectApplied", "pid", "keepLatest")
        val props = schema.getAsJsonObject("properties")
        listOf("withLogs", "dryRun", "protectApplied").forEach { name ->
            assertEquals(name + " 必须是 boolean", "boolean", props.getAsJsonObject(name).get("type").asString)
        }
        listOf("pid", "keepLatest").forEach { name ->
            assertEquals(name + " 必须是 integer", "integer", props.getAsJsonObject(name).get("type").asString)
        }
    }

    @Test
    fun everyParamReadAsBooleanOrIntegerIsDeclaredWithThatType() {
        if (!mcpDir.isDirectory) return
        val declared = declaredTypes()
        val tolerated = setOf("args", "input", "region", "bounds", "value", "options")
        val problems = mutableListOf<String>()
        val files = mcpDir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()
        for (file in files) {
            val text = file.readText()
            collect(text, "args\\.tBool\\(\"([A-Za-z0-9_]+)\"", "boolean", "args.tBool", declared, tolerated, file.name, problems)
            collect(text, "args\\.tInt\\(\"([A-Za-z0-9_]+)\"", "integer", "args.tInt", declared, tolerated, file.name, problems)
        }
        assertTrue("schema 参数类型与运行时读取不一致：\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun noRequiredPropertyCarriesDefaultValueOrDefaultDescription() {
        val problems = mutableListOf<String>()
        if (schemaAssets.isDirectory) {
            val apkSchemas = File(schemaAssets, "toolkit-apk-schemas.json")
            if (apkSchemas.isFile) {
                for ((name, value) in JsonParser().parse(apkSchemas.readText()).asJsonObject.entrySet()) {
                    if (value.isJsonObject) collectRequiredDefaultProblems(value.asJsonObject, name, problems)
                }
            }
        }
        val temp = Files.createTempDirectory("mcp-schema-required").toFile()
        try {
            for ((source, definitions) in listOf(
                "McpFileToolkit" to McpFileToolkit(temp).definitions(),
                "McpZipToolkit" to McpZipToolkit(temp).definitions(),
            )) {
                for (definition in definitions) {
                    val tool = definition.asJsonObject
                    val name = source + "." + tool.get("name")?.asString
                    tool.getAsJsonObject("inputSchema")?.let { collectRequiredDefaultProblems(it, name, problems) }
                }
            }
        } finally {
            temp.deleteRecursively()
        }
        assertTrue("required 同时带 default 或描述默认值（会把可选参数伪装成必填）：\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun writeTargetEditAcceptsOmittedMatchText() {
        val apkSchemas = JsonParser().parse(File(schemaAssets, "toolkit-apk-schemas.json").readText()).asJsonObject
        val apkItem = apkSchemas.getAsJsonObject("apk_edit_text").getAsJsonObject("properties")
            .getAsJsonObject("edits").getAsJsonObject("items")
        assertFalse(apkItem.getAsJsonArray("required").map { it.asString }.contains("matchText"))
        assertEquals("", apkItem.getAsJsonObject("properties").getAsJsonObject("matchText").get("default").asString)
        val apkEdits = JsonParser().parse("[{\"mode\":\"write_target\",\"writeText\":\"hello\"}]").asJsonArray
        assertEquals("hello", ToolkitTextEdits.apply("old", apkEdits))

        val temp = Files.createTempDirectory("mcp-write-target").toFile()
        try {
            val toolkit = McpFileToolkit(temp)
            val fileEdit = toolkit.definitions().first { it.asJsonObject.get("name").asString == "file_edit_text" }
                .asJsonObject.getAsJsonObject("inputSchema")
            val fileItem = fileEdit.getAsJsonObject("properties").getAsJsonObject("edits").getAsJsonObject("items")
            assertFalse(fileItem.getAsJsonArray("required").map { it.asString }.contains("matchText"))
            assertEquals("", fileItem.getAsJsonObject("properties").getAsJsonObject("matchText").get("default").asString)
            val result = toolkit.call("file_edit_text", ToolkitIo.json(
                "path" to "new.txt", "targetVersion" to "missing", "encoding" to "utf-8",
                "edits" to listOf(ToolkitIo.json("mode" to "write_target", "writeText" to "hello")),
                "organize" to false,
            ))
            assertEquals(true, result.get("ok").asBoolean)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun documentedExamplesMatchToolSchemas() {
        val schemas = linkedMapOf<String, JsonObject>()
        val apkSchemas = JsonParser().parse(File(schemaAssets, "toolkit-apk-schemas.json").readText()).asJsonObject
        for ((name, value) in apkSchemas.entrySet()) if (value.isJsonObject) schemas[name] = value.asJsonObject
        val temp = Files.createTempDirectory("mcp-schema-examples").toFile()
        try {
            for (definitions in listOf(McpFileToolkit(temp).definitions(), McpZipToolkit(temp).definitions(), ToolkitReverseTools.definitions())) {
                for (definition in definitions) {
                    val tool = definition.asJsonObject
                    val name = tool.get("name")?.asString ?: continue
                    tool.getAsJsonObject("inputSchema")?.let { schemas[name] = it }
                }
            }
        } finally {
            temp.deleteRecursively()
        }
        val docs = listOf("AIjs-MCP-Guide.md", "AIjs-MCP-Playbook.md", "AIjs-Reverse-Guide.md")
        val problems = mutableListOf<String>()
        var validated = 0
        for (doc in docs) {
            val file = File(schemaAssets, doc)
            if (!file.isFile) continue
            file.readLines().forEachIndexed { index, line ->
                val example = parseExample(line.trim()) ?: return@forEachIndexed
                val schema = schemas[example.first] ?: return@forEachIndexed
                validateExample(schema, example.second, doc + ":" + (index + 1) + " " + example.first, problems)
                validated++
            }
        }
        assertTrue("至少应校验到文档示例", validated > 0)
        assertTrue("文档工具示例与 inputSchema 不符（漏 required / 多未知字段）：\n" + problems.distinct().joinToString("\n"),
            problems.isEmpty())
    }

    /** 提取 {"name":...,"arguments":...} / JSON-RPC / {"tool":...,"args":...} 三类单行示例。 */
    private fun parseExample(line: String): Pair<String, JsonObject>? {
        if (!line.startsWith("{") || !line.endsWith("}")) return null
        val obj = runCatching { JsonParser().parse(line).asJsonObject }.getOrNull() ?: return null
        obj.get("name")?.takeIf { it.isJsonPrimitive }?.let { name ->
            obj.getAsJsonObject("arguments")?.let { return name.asString to it }
        }
        obj.getAsJsonObject("params")?.let { params ->
            params.get("name")?.takeIf { it.isJsonPrimitive }?.let { name ->
                params.getAsJsonObject("arguments")?.let { return name.asString to it }
            }
        }
        obj.get("tool")?.takeIf { it.isJsonPrimitive }?.let { tool ->
            obj.getAsJsonObject("args")?.let { return tool.asString to it }
        }
        return null
    }

    private fun validateExample(schema: JsonObject, value: JsonElement, path: String, problems: MutableList<String>) {
        if (value.isJsonArray) {
            val items = schema.getAsJsonObject("items")
            if (items != null) value.asJsonArray.forEachIndexed { index, item ->
                validateExample(items, item, path + "[" + index + "]", problems)
            }
            return
        }
        if (!value.isJsonObject) return
        val args = value.asJsonObject
        val objectSchema = when {
            schema.get("type")?.takeIf { it.isJsonPrimitive }?.asString == "object" -> schema
            schema.has("anyOf") -> schema.getAsJsonArray("anyOf").map { it.asJsonObject }.firstOrNull { branch ->
                val required = branch.getAsJsonArray("required")?.map { it.asString } ?: emptyList()
                required.all { it in args.keySet() } &&
                    (branch.get("additionalProperties")?.takeIf { it.isJsonPrimitive }?.asBoolean != false ||
                        args.keySet().all { it in (branch.getAsJsonObject("properties")?.keySet() ?: emptySet()) })
            }
            else -> null
        } ?: return
        val properties = objectSchema.getAsJsonObject("properties") ?: return
        for (required in objectSchema.getAsJsonArray("required") ?: return) {
            val name = required.asString
            if (!args.has(name)) problems += path + " 缺少 required 字段 " + name
        }
        if (objectSchema.get("additionalProperties")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) {
            for (name in args.keySet()) if (!properties.has(name)) problems += path + " 含未知字段 " + name
        }
        for ((name, child) in args.entrySet()) {
            properties.getAsJsonObject(name)?.let { validateExample(it, child, path + "." + name, problems) }
        }
    }

    private fun collectRequiredDefaultProblems(schema: JsonObject, path: String, problems: MutableList<String>) {
        val properties = schema.getAsJsonObject("properties")
        val required = schema.getAsJsonArray("required")
        if (properties != null && required != null) {
            for (element in required) {
                val name = element.asString
                val property = properties.getAsJsonObject(name) ?: continue
                if (property.has("default")) problems += path + "." + name + " required 但 default=" + property.get("default")
                val description = property.get("description")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                if (Regex("省略时\\s*=|默认值|默认\\s*[=:]|省略.*默认|默认.*省略").containsMatchIn(description)) {
                    problems += path + "." + name + " required 但描述写了默认值：" + description
                }
            }
        }
        for ((key, value) in schema.entrySet()) {
            if (key == "properties") continue
            if (value.isJsonObject) collectRequiredDefaultProblems(value.asJsonObject, path + "." + key, problems)
            else if (value.isJsonArray) value.asJsonArray.forEachIndexed { index, item ->
                if (item.isJsonObject) collectRequiredDefaultProblems(item.asJsonObject, path + "." + key + "[" + index + "]", problems)
            }
        }
    }

    @Test
    fun apkReadTextAndNativeListItemsAreNotOverRequired() {
        val schemas = JsonParser().parse(File(schemaAssets, "toolkit-apk-schemas.json").readText()).asJsonObject
        val read = schemas.getAsJsonObject("apk_read_text")
        assertEquals(setOf("workspaceId", "editSessionId", "locator"), read.getAsJsonArray("required").map { it.asString }.toSet())
        val readProps = read.getAsJsonObject("properties")
        assertEquals(500, readProps.getAsJsonObject("limit").get("default").asInt)
        assertEquals(49152, readProps.getAsJsonObject("maxChars").get("default").asInt)
        assertEquals(0, readProps.getAsJsonObject("startLine").get("default").asInt)
        assertEquals(0, readProps.getAsJsonObject("startColumn").get("default").asInt)

        val nativeItems = schemas.getAsJsonObject("apk_native_list_items")
        assertEquals(setOf("workspaceId", "editSessionId", "locator", "itemKind"),
            nativeItems.getAsJsonArray("required").map { it.asString }.toSet())
        val nativeProps = nativeItems.getAsJsonObject("properties")
        assertEquals("", nativeProps.getAsJsonObject("namePrefix").get("default").asString)
        assertEquals(false, nativeProps.getAsJsonObject("demangle").get("default").asBoolean)
        assertEquals(100, nativeProps.getAsJsonObject("limit").get("default").asInt)

        val window = ToolkitTextWindow.read("a\nb\n", JsonObject())
        assertEquals(500, window.get("limit").asInt)
        assertEquals(0, window.get("startLine").asInt)
        assertEquals(0, window.get("startColumn").asInt)
    }

    @Test
    fun trulyRequiredSafetyFieldsExplainWhyTheyAreRequired() {
        val schemas = JsonParser().parse(File(schemaAssets, "toolkit-apk-schemas.json").readText()).asJsonObject
        val build = schemas.getAsJsonObject("apk_build").getAsJsonObject("properties").getAsJsonObject("overwrite")
        assertTrue(build.get("description").asString.contains("必填"))
        val copy = schemas.getAsJsonObject("apk_copy_entry").getAsJsonObject("properties").getAsJsonObject("overwrite")
        assertTrue(copy.get("description").asString.contains("必填"))
        val patch = schemas.getAsJsonObject("apk_native_patch_instructions").getAsJsonObject("properties")
            .getAsJsonObject("replacementAssembly")
        assertTrue(patch.get("description").asString.contains("必填"))
    }

    private fun collect(text: String, pattern: String, expected: String, reader: String,
                        declared: Map<String, MutableSet<String>>, tolerated: Set<String>,
                        fileName: String, problems: MutableList<String>) {
        for (match in Regex(pattern).findAll(text)) {
            val name = match.groupValues[1]
            if (name in tolerated) continue
            val types = declared[name]
            if (types == null) {
                problems += fileName + ": " + reader + "(\"" + name + "\") 未在任何 schema 里声明（会退化成 string）"
            } else if (!types.contains(expected)) {
                problems += fileName + ": " + reader + "(\"" + name + "\") 声明为 " + types.joinToString("/") + "，运行时却按 " + expected + " 读"
            }
        }
    }

    /* 汇总三处声明：Kotlin 的 setOf、内联 field(...)、以及 assets 下的 JSON 资产。 */
    private fun declaredTypes(): Map<String, MutableSet<String>> {
        val out = HashMap<String, MutableSet<String>>()
        fun put(name: String, type: String) { out.getOrPut(name) { mutableSetOf() }.add(type) }
        val kotlinFiles = mcpDir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()
        kotlinFiles.firstOrNull { it.name == "McpToolSchema.kt" }?.let { schema ->
            val pairs = mapOf("booleanParams" to "boolean", "integerParams" to "integer", "numberArrayParams" to "array")
            for ((setName, type) in pairs) {
                val block = Regex("private val " + setName + "[^=]*=\\s*setOf\\(([\\s\\S]*?)\\)")
                    .find(schema.readText())?.groupValues?.get(1) ?: continue
                for (name in Regex("\"([^\"]+)\"").findAll(block)) put(name.groupValues[1], type)
            }
        }
        // 显式声明：field("x", ToolkitIo.json("type" to "integer", ...))
        val fieldPattern = Regex("field\\(\"([A-Za-z0-9_]+)\"\\s*,\\s*ToolkitIo\\.json\\(\\s*\"type\"\\s*to\\s*\"([a-z]+)\"")
        // 也有大量参数用助手声明：field("x", num(1,30,10)) / str() / bool() / path()。
        val helperTypes = mapOf("num" to "integer", "bool" to "boolean", "str" to "string", "path" to "string", "text" to "string")
        val helperPattern = Regex("field\\(\"([A-Za-z0-9_]+)\"\\s*,\\s*([A-Za-z]+)\\(")
        for (file in kotlinFiles) {
            val text = file.readText()
            for (m in fieldPattern.findAll(text)) put(m.groupValues[1], m.groupValues[2])
            for (m in helperPattern.findAll(text)) {
                val type = helperTypes[m.groupValues[2]] ?: continue
                put(m.groupValues[1], type)
            }
        }
        val assets = schemaAssets.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        for (asset in assets) walk(JsonParser().parse(asset.readText()), out)
        return out
    }

    /* 递归找出 JSON 里所有 properties 下带 type 的键名。 */
    private fun walk(node: JsonElement, out: MutableMap<String, MutableSet<String>>) {
        if (node.isJsonObject) {
            val obj: JsonObject = node.asJsonObject
            val props = obj.get("properties")
            if (props != null && props.isJsonObject) {
                for ((name, value) in props.asJsonObject.entrySet()) {
                    val type = value.takeIf { it.isJsonObject }?.asJsonObject?.get("type")
                    if (type != null && type.isJsonPrimitive && type.asJsonPrimitive.isString) {
                        out.getOrPut(name) { mutableSetOf() }.add(type.asString)
                    }
                    walk(value, out)
                }
            }
            for ((_, value) in obj.entrySet()) walk(value, out)
        } else if (node.isJsonArray) {
            for (item in node.asJsonArray) walk(item, out)
        }
    }
}
