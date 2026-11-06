package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Validates the shared file/source contract before anything is written or executed. */
internal class McpRunInput(args: JsonObject) {
    val source: String?
    val path: String?
    val config: JsonObject
    val engine: String
    val name: String
    val workingDirectory: String?
    val wait: Boolean
    val timeoutSeconds: Int
    val delay: Int
    val interval: Int
    val loopTimes: Int

    init {
        // 兼容别名：check_syntax 用 code、run_script 用 source，同一份文档里两套名字必然被混用；
        // code 直接当 source 的别名，报错也点名说清楚（P0-4 反馈）。
        val hasSource = args.has("source")
        val hasCodeAlias = args.has("code")
        require(!(hasSource && hasCodeAlias)) { "source 与 code 是同义参数（code 是 source 的别名），只能给一个" }
        val inputs = listOf("path", "file").filter { args.has(it) } +
            if (hasSource || hasCodeAlias) listOf("source") else emptyList()
        require(inputs.size == 1) {
            when {
                inputs.isEmpty() && args.has("engine") && !args.has("path") ->
                    "必须且只能提供 path、file、source（或别名 code）中的一个；只给了 engine：内存源码请放 source/code，磁盘脚本请放 path/file"
                inputs.isEmpty() -> "必须且只能提供 path、file、source（或别名 code）中的一个：你这次一个都没传"
                else -> "必须且只能提供 path、file、source（或别名 code）中的一个，收到：${inputs.joinToString()}（path/file 是磁盘脚本，source/code 是内存源码）"
            }
        }
        val input = inputs.single()
        val value = if (input == "source" && !hasSource) string(args, "code") else string(args, input)!!
        source = value.takeIf { input == "source" }
        path = value.takeIf { input != "source" }
        require(source == null || source.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "源码不得超过 256 KiB" }
        config = args.get("config")?.let {
            require(it.isJsonObject) { "config 必须是 object" }; it.asJsonObject
        } ?: JsonObject()
        val allowed = setOf("path", "workingDirectory", "engine", "delay", "interval", "loopTimes", "arguments")
        require(config.keySet().all { it in allowed }) { "config 包含不支持的字段" }
        engine = McpEngines.normalize((string(args, "engine") ?: string(config, "engine")).orEmpty())
        name = string(args, "name") ?: "mcp-source"
        require(name.matches(Regex("[\\p{L}\\p{N}_.-]{1,80}")) && name != "." && name != "..") { "name 必须是 1～80 个字母、数字、下划线、点或横线" }
        val configPath = config.get("path")
        require(configPath == null || configPath.isJsonArray || configPath.isJsonPrimitive && configPath.asJsonPrimitive.isString) { "config.path 必须是目录字符串或目录数组" }
        if (configPath?.isJsonArray == true) require(configPath.asJsonArray.all {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }) { "config.path 数组只能包含目录字符串" }
        workingDirectory = string(args, "workingDirectory") ?: string(config, "workingDirectory")
            ?: configPath?.takeIf { it.isJsonPrimitive }?.asString
        wait = args.get("wait")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "wait 必须是 boolean" }; it.asBoolean
        } ?: false
        timeoutSeconds = parseWaitTimeout(args)
        delay = integer(config, "delay", 0, 0..600000)
        interval = integer(config, "interval", 0, 0..600000)
        loopTimes = integer(config, "loopTimes", 1, 0..100000)
        config.get("arguments")?.let { require(it.isJsonObject) { "config.arguments 必须是 object" } }
    }

    fun searchPaths(): List<String> = config.get("path")?.takeIf { it.isJsonArray }?.asJsonArray
        ?.map { it.asString }.orEmpty()

    companion object {
        /** MCP 的一次等待上限，不是脚本生命周期；与 waitFor() 的 coerceIn 保持一致。 */
        const val WAIT_TIMEOUT_MIN_SECONDS = 1
        const val WAIT_TIMEOUT_MAX_SECONDS = 600
        /** Node 宿主 path 脚本自身的执行上限（Node24JavaScriptEngine.DEFAULT_TIMEOUT_MILLIS）。 */
        const val NODE_PATH_SCRIPT_TIMEOUT_SECONDS = 3_600
        const val WAIT_TIMEOUT_DESCRIPTION =
            "MCP 等待上限（秒），不是脚本生命周期：1..600，默认 60。path 脚本在 Node 宿主可运行最长 1 小时；" +
                "要等更久请 wait=false 后轮询 wait_execution/get_execution。"

        private fun parseWaitTimeout(args: JsonObject): Int = try {
            integer(args, "timeoutSeconds", 60, WAIT_TIMEOUT_MIN_SECONDS..WAIT_TIMEOUT_MAX_SECONDS)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "timeoutSeconds 是 MCP 等待上限，范围 1..$WAIT_TIMEOUT_MAX_SECONDS 秒；不是脚本运行上限。" +
                    "path 脚本在 Node 宿主可运行最长 1 小时（$NODE_PATH_SCRIPT_TIMEOUT_SECONDS 秒）；" +
                    "等待更久请 wait=false 后用 wait_execution/get_execution 继续。收到值：" +
                    args.get("timeoutSeconds"),
                error
            )
        }

        private fun string(obj: JsonObject, key: String): String? = obj.get(key)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$key 必须是 string" }; it.asString
        }
        private fun integer(obj: JsonObject, key: String, fallback: Int, range: IntRange): Int = obj.get(key)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "$key 必须是 integer" }
            val decimal = it.asBigDecimal
            val number = try { decimal.intValueExact() } catch (_: ArithmeticException) {
                throw IllegalArgumentException("$key 必须是 integer")
            }
            require(number in range) { "$key 超出允许范围 $range" }; number
        } ?: fallback

        fun argumentValue(value: JsonElement): Any? = when {
            value.isJsonNull -> null
            value.isJsonArray -> value.asJsonArray.map { argumentValue(it) }
            value.isJsonObject -> value.asJsonObject.entrySet().associate { it.key to argumentValue(it.value) }
            value.asJsonPrimitive.isBoolean -> value.asBoolean
            // Gson's LazilyParsedNumber is wrapped as a string by Android JSONObject.
            // JS numbers are doubles; use that native type for both engine bridges.
            value.asJsonPrimitive.isNumber -> value.asDouble.also {
                require(it.isFinite()) { "config.arguments 的数字超出 JavaScript 有限数值范围" }
            }
            else -> value.asString
        }
    }
}
