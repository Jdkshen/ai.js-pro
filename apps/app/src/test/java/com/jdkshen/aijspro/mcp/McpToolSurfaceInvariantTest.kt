package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * W4 task-20：**全量**工具面不变量（不止 file/zip）。
 *
 * 扫描来源（与 tools/list 同源）：
 *  1) `McpToolkit(root, store, toolkit-apk-schemas.json).definitions()` —— 与 `McpToolkitBridge.definitions(context, root)`
 *     是同一份实现（后者只是用 Context 取 asset + 拼 store 路径），所以 toolkit 家族（93 个：toolkit_status、project_folder、
 *     apk_*、file_*、zip_*、binary_transform、evidence_export、逆向族）的描述与 schema 都是**真对象**，不是正则猜的；
 *  2) `McpTools.kt` 源文本里的 `tool("name", "描述", schema)` 字面量 —— 主进程家族（app_*、script_*、terminal_*、ui_*
 *     等）。schema 用 `McpToolSchema.xxxSchema()` 时**反射调用真函数**拿 JsonObject，用 `objSchema(...)` 时按参数解析；
 *  3) `McpToolCatalog` / `McpToolkitCatalog` —— 目录层（名字/别名/派发名）。
 *
 * 覆盖面边界（如实）：主进程家族的**描述**来自源码字面量解析（不是运行时对象）；如果将来有人在 `tool(...)` 里
 * 用变量拼描述或把定义挪出 `McpTools.kt`，本测试会先以「schema 表达式无法静态解析」或覆盖断言失败报出来，而不是静默漏扫。
 */
class McpToolSurfaceInvariantTest {

    // ------------------------------------------------------------------ 目录层

    @Test
    fun catalogNamesAreUniqueAndEveryAliasPointsToAnExistingCanonicalTool() {
        val canonical = McpToolCatalog.canonicalNames
        val advertised = McpToolCatalog.names
        assertTrue("canonical 数量异常：${canonical.size}", canonical.size >= 200)
        assertEquals("canonical 名字有重复", canonical.size, canonical.toSet().size)
        assertEquals("advertised 名字有重复", advertised.size, advertised.toSet().size)
        assertEquals("advertised != canonical + 别名", canonical.size + McpToolCatalog.aliases.size, advertised.size)
        McpToolCatalog.aliases.forEach { (alias, target) ->
            assertTrue("别名 $alias 指向不存在的工具 $target", target in canonical)
            assertTrue("别名 $alias 没有被 advertise", alias in advertised)
        }
        assertEquals("dispatchAliases 数量", canonical.size, McpToolCatalog.dispatchAliases.size)
        assertEquals("dispatchAliases 有冲突", canonical.size, McpToolCatalog.dispatchAliases.values.toSet().size)
        canonical.forEach { name ->
            assertEquals("派发名没指回自己：$name", name, McpToolCatalog.canonicalName(McpToolCatalog.dispatchAliases.getValue(name)))
        }
    }

    @Test
    fun toolkitCatalogNamesAndAliasesAreConsistent() {
        val names = McpToolkitCatalog.names
        assertEquals("toolkit 名字有重复", names.size, names.toSet().size)
        McpToolkitCatalog.aliases.forEach { (alias, target) ->
            assertTrue("mt_ 别名 $alias 指向不存在的工具 $target", target in names)
        }
        // mt_* 别名是按 names.take(58) 的**下标窗口**生成的（McpToolkitCatalog.kt:107）：
        // 任何人在第 58 项之前插入名字都会静默改变别名集合，这里把窗口边界钉住。
        assertEquals("mt_ 别名数量变了（下标窗口 58 被改动？）", 57, McpToolkitCatalog.aliases.size)
        assertTrue("mt_project_folder 应在窗口内", "mt_project_folder" in McpToolkitCatalog.aliases)
        assertTrue("窗口外的工具不该有 mt_ 别名", "mt_evidence_export" !in McpToolkitCatalog.aliases)
    }

    // ------------------------------------------------------------------ 定义层

    @Test
    fun everyAdvertisedToolKeepsTheSurfaceInvariants() {
        val problems = ArrayList<String>()
        val temp = Files.createTempDirectory("mcp-surface").toFile()
        val scanned = LinkedHashMap<String, Int>()
        try {
            val toolkit = toolkitDefinitions(temp, problems)
            scanned["McpToolkit.definitions()（toolkit 家族，与 McpToolkitBridge 同源）"] = toolkit.size
            checkDefinitions("McpToolkit", toolkit, problems)
            val main = mainToolDefinitions(problems)
            scanned["McpTools.kt 源文本 tool(...)（主进程家族）"] = main.size
            checkDefinitions("McpTools.kt", main, problems)
            println("[surface] 扫描数量：" + scanned.entries.joinToString("；") { it.key + "=" + it.value })
        } finally {
            temp.deleteRecursively()
        }
        assertTrue("工具面不变量失败（" + problems.distinct().size + " 条）：\n" + problems.distinct().joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun scanCoversEveryAdvertisedName() {
        val problems = ArrayList<String>()
        val temp = Files.createTempDirectory("mcp-surface-coverage").toFile()
        val toolkit: List<JsonObject>
        try {
            toolkit = toolkitDefinitions(temp, problems)
        } finally {
            temp.deleteRecursively()
        }
        val main = mainToolDefinitions(problems)
        val scanned = (toolkit + main).mapNotNull { it.tStringOrNull("name") }.toSet()
        val advertised = McpToolCatalog.names
        val aliasCopies = McpToolCatalog.aliases.keys.toSet()
        val missing = advertised.filterNot { it in scanned || it in aliasCopies }
        println(
            "[surface] advertised=" + advertised.size + "（canonical=" + McpToolCatalog.canonicalNames.size +
                " + Pro 别名=" + McpToolCatalog.aliases.size + "）；扫到独立定义=" + scanned.size +
                "（toolkit=" + toolkit.size + " + 主进程=" + main.size + "）；别名副本=" + aliasCopies.size +
                "；mt_ 派发别名（不 advertise）=" + McpToolkitCatalog.aliases.size
        )
        assertTrue("扫描自身有问题：$problems", problems.isEmpty())
        assertTrue("这些 advertised 工具没有定义来源：$missing", missing.isEmpty())
    }

    // ------------------------------------------------------------------ 助手

    private fun toolkitDefinitions(temp: File, problems: MutableList<String>): List<JsonObject> {
        val schemaFile = File("src/main/assets/mcp/toolkit-apk-schemas.json")
        if (!schemaFile.isFile) {
            problems += "找不到 $schemaFile（相对路径按模块根解析；请在 :app 目录下运行测试）"
            return emptyList()
        }
        val schemas = JsonParser().parse(schemaFile.readText()).asJsonObject
        val root = File(temp, "root").apply { mkdirs() }
        val store = File(temp, "store").apply { mkdirs() }
        return McpToolkit(root, store, schemas).definitions().map { it.asJsonObject }
    }

    private fun mainToolDefinitions(problems: MutableList<String>): List<JsonObject> {
        val source = File("src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt")
        if (!source.isFile) {
            problems += "找不到 $source（相对路径按模块根解析；请在 :app 目录下运行测试）"
            return emptyList()
        }
        val text = source.readText()
        val tools = ArrayList<JsonObject>()
        var index = text.indexOf("tool(\"")
        while (index >= 0) {
            val open = index + 4
            var depth = 0
            var end = open
            while (end < text.length) {
                val ch = text[end]
                if (ch == '(') depth++ else if (ch == ')') { depth--; if (depth == 0) break }
                end++
            }
            val arguments = splitTopLevel(text.substring(open + 1, end))
            val name = arguments.getOrNull(0)?.let { literals(it).joinToString("") }.orEmpty()
            val description = arguments.getOrNull(1)?.let { literals(it).joinToString("") }.orEmpty()
            tools += JsonObject().apply {
                addProperty("name", name)
                addProperty("description", description)
                add("inputSchema", schemaFromExpression(arguments.getOrNull(2), name, problems))
            }
            index = text.indexOf("tool(\"", end)
        }
        return tools
    }

    private fun schemaFromExpression(expression: String?, tool: String, problems: MutableList<String>): JsonObject {
        val text = expression?.trim().orEmpty()
        if (text.isEmpty()) return emptySchema()
        if (text.startsWith("objSchema")) {
            val body = callBody(text, "objSchema")
            val arguments = splitTopLevel(body)
            val names = arguments.filterNot { it.contains('=') }.flatMap { literals(it) }
            val required = Regex("required\\s*=\\s*arrayOf\\(([^)]*)\\)").find(text)?.let { literals(it.groupValues[1]) } ?: emptyList()
            val properties = JsonObject().apply {
                names.forEach { add(it, JsonObject().apply { addProperty("type", "string") }) }
            }
            applyInlinePropertyTweaks(text, properties)
            return JsonObject().apply {
                addProperty("type", "object")
                add("properties", properties)
                add("required", JsonArray().apply { required.forEach { add(it) } })
            }
        }
        val reflected = Regex("^McpToolSchema\\.(\\w+)\\(\\)").find(text)
        if (reflected != null) {
            val methodName = reflected.groupValues[1]
            val method = McpToolSchema::class.java.declaredMethods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            if (method == null) {
                problems += "McpTools.kt.$tool: 找不到 McpToolSchema.$methodName()"
                return emptySchema()
            }
            method.isAccessible = true
            return method.invoke(McpToolSchema) as JsonObject
        }
        problems += "McpTools.kt.$tool: schema 表达式无法静态解析：${text.take(60)}"
        return emptySchema()
    }

    private fun callBody(text: String, callee: String): String {
        val open = text.indexOf(callee + "(") + callee.length
        var depth = 0
        var end = open
        while (end < text.length) {
            val ch = text[end]
            if (ch == '(') depth++ else if (ch == ')') { depth--; if (depth == 0) break }
            end++
        }
        return text.substring(open + 1, end)
    }

    /** 源文本里 `.getAsJsonObject("properties").getAsJsonObject("x").addProperty("...", ...)` 的轻量解析（不用正则，免得转义出错）。 */
    private fun applyInlinePropertyTweaks(text: String, properties: JsonObject) {
        val marker = "getAsJsonObject(\"properties\").getAsJsonObject(\""
        var index = text.indexOf(marker)
        while (index >= 0) {
            val nameStart = index + marker.length
            val nameEnd = text.indexOf('"', nameStart)
            val propertyName = if (nameEnd > nameStart) text.substring(nameStart, nameEnd) else ""
            val addMarker = ".addProperty(\""
            val addIndex = text.indexOf(addMarker, nameEnd)
            if (propertyName.isNotEmpty() && addIndex in (nameEnd + 1)..(nameEnd + 200)) {
                val keyStart = addIndex + addMarker.length
                val keyEnd = text.indexOf('"', keyStart)
                val key = if (keyEnd > keyStart) text.substring(keyStart, keyEnd) else ""
                val target = properties.getAsJsonObject(propertyName)
                if (target != null) {
                    val valueStart = keyEnd + 2
                    val value = literals(text.substring(valueStart, minOf(text.length, valueStart + 400))).firstOrNull()
                    if (key == "description" && value != null) target.addProperty("description", value)
                    if (key == "default" && value != null) target.addProperty("default", value)
                }
            }
            index = text.indexOf(marker, index + marker.length)
        }
    }

    private fun checkDefinitions(source: String, definitions: List<JsonObject>, problems: MutableList<String>) {
        val names = definitions.mapNotNull { it.tStringOrNull("name") }
        assertEquals("$source 内工具名有重复", names.size, names.toSet().size)
        definitions.forEach { checkTool(source, it, problems) }
    }

    private fun checkTool(source: String, tool: JsonObject, problems: MutableList<String>) {
        val name = tool.tStringOrNull("name").orEmpty()
        if (name.isEmpty()) { problems += "$source: 有工具缺 name"; return }
        val description = tool.tStringOrNull("description").orEmpty().trim()
        if (description.length < 4) problems += "$source.$name: description 为空或过短（'${description.take(40)}'）"
        // 只有「整段就是占位符」才算：正常描述里出现「占位符必须替换」这类词不算。
        if (PLACEHOLDER_PREFIX.containsMatchIn(description) || (description.length <= 24 && PLACEHOLDER_WORD.containsMatchIn(description))) {
            problems += "$source.$name: description 疑似占位符（'${description.take(60)}'）"
        }
        val schema = tool.getAsJsonObject("inputSchema")
        if (schema == null) { problems += "$source.$name: 缺 inputSchema"; return }
        if (schema.tStringOrNull("type") != "object") problems += "$source.$name: inputSchema.type 不是 object"
        val properties = schema.getAsJsonObject("properties") ?: JsonObject()
        val required = schema.getAsJsonArray("required")?.map { it.asString } ?: emptyList()
        required.forEach { key ->
            val property = properties.getAsJsonObject(key)
            if (property == null) {
                problems += "$source.$name: required '$key' 不在 properties 里"
            } else {
                if (property.has("default")) problems += "$source.$name.$key: required 且带 default=${property.get("default")}"
                val propertyText = property.tStringOrNull("description").orEmpty()
                if (DEFAULT_WORDING.containsMatchIn(propertyText)) {
                    problems += "$source.$name.$key: required 但描述写了默认语义（'${propertyText.take(80)}'）"
                }
            }
        }
    }

    private fun emptySchema(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
        add("required", JsonArray())
    }

    private fun JsonObject.tStringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun literals(text: String): List<String> {
        val result = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '"') {
                val builder = StringBuilder()
                index++
                while (index < text.length && text[index] != '"') {
                    if (text[index] == '\\' && index + 1 < text.length) { builder.append(text[index + 1]); index += 2 }
                    else { builder.append(text[index]); index++ }
                }
                index++
                result += builder.toString()
            } else index++
        }
        return result
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                inString -> {
                    current.append(ch)
                    if (ch == '\\' && index + 1 < text.length) { current.append(text[index + 1]); index++ }
                    else if (ch == '"') inString = false
                }
                ch == '"' -> { inString = true; current.append(ch) }
                ch == '(' || ch == '[' || ch == '{' -> { depth++; current.append(ch) }
                ch == ')' || ch == ']' || ch == '}' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> { parts += current.toString(); current.setLength(0) }
                else -> current.append(ch)
            }
            index++
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts
    }

    private companion object {
        /** 与既有不变量测试（McpToolSchemaParamsTest）同一套启发式，避免各写一套导致误报。 */
        val DEFAULT_WORDING = Regex("省略时\\s*=|省略时\\s*为|默认值|默认\\s*[=:：]|省略.*默认|默认.*省略")
        val PLACEHOLDER_WORD = Regex("(?i)\\b(todo|tbd|fixme|placeholder)\\b|待补|待定|占位")
        val PLACEHOLDER_PREFIX = Regex("^\\s*((?i)(todo|tbd|fixme)\\b|待补|待定|占位)")
    }
}
