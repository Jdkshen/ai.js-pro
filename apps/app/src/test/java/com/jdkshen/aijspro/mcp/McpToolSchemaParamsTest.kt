package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
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
