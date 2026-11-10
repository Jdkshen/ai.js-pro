package com.jdkshen.aijspro.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser

// task-131/132：plugins.json 的**纯逻辑**解析与校验（无 Android 依赖，可 JVM 单测）。
// 与 tools/plugins-manifest.mjs 生成侧同一份字段约定；schema 不认识就整份拒绝（不猜）。
object PluginManifest {
    const val SCHEMA = 1
    const val PENDING_SHA = "pending"

    data class Entry(
        val id: String,
        val displayName: String,
        val packageName: String,
        val version: String,
        val versionCode: Int,
        val minAppVersion: Int,
        val file: String,
        val sizeBytes: Long?,
        val sha256: String,
        val requiresRestart: Boolean,
        val abis: List<String>,
        val notes: String
    ) {
        // pending 表示该 APK 尚未构建 ⇒ 不可下载（提前拒，别下白流量）。
        fun isDownloadable(): Boolean = PluginVerifier.isHexSha256(sha256)
    }

    sealed class ParseResult {
        data class Success(val entries: List<Entry>, val generatedAt: String) : ParseResult()
        data class Failure(val reason: String, val message: String) : ParseResult()
    }

    @JvmStatic
    fun parse(json: String?): ParseResult {
        if (json.isNullOrBlank()) return ParseResult.Failure("malformed", "清单为空")
        val root = try {
            // 用 Gson 的**实例** API：`JsonParser.parseString` 需要 Gson 2.8.6+，而本模块的 Gson 更旧
            // ⇒ 会报「Unresolved reference 'parseString'」并让后面所有成员引用级联报错（Lead 修 2026-11-10）。
            JsonParser().parse(json).asJsonObject
        } catch (error: Throwable) {
            return ParseResult.Failure("malformed", "清单不是 JSON 对象：" + (error.message ?: error.javaClass.simpleName))
        }
        val schema = root.get("schema")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (schema != SCHEMA) return ParseResult.Failure("schema", "清单 schema=" + schema + " 不受支持（需要 " + SCHEMA + "）")
        val array = root.getAsJsonArray("plugins")
            ?: return ParseResult.Failure("malformed", "plugins 必须是数组")
        if (array.size() == 0) return ParseResult.Failure("malformed", "plugins 不能为空")
        val entries = ArrayList<Entry>(array.size())
        val seen = HashSet<String>()
        for (element in array) {
            if (!element.isJsonObject) return ParseResult.Failure("malformed", "plugins 里出现非对象元素")
            val o = element.asJsonObject
            val id = o.string("id") ?: return ParseResult.Failure("malformed", "插件缺 id")
            if (!Regex("^[a-z][a-z0-9-]*$").matches(id)) return ParseResult.Failure("malformed", "插件 id 不合法：" + id)
            if (!seen.add(id)) return ParseResult.Failure("malformed", "插件 id 重复：" + id)
            val displayName = o.string("displayName") ?: return ParseResult.Failure("malformed", id + " 缺 displayName")
            val pkg = o.string("package") ?: return ParseResult.Failure("malformed", id + " 缺 package")
            val version = o.string("version") ?: return ParseResult.Failure("malformed", id + " 缺 version")
            val file = o.string("file") ?: return ParseResult.Failure("malformed", id + " 缺 file")
            val sha = o.string("sha256") ?: return ParseResult.Failure("malformed", id + " 缺 sha256")
            if (sha != PENDING_SHA && !PluginVerifier.isHexSha256(sha)) {
                return ParseResult.Failure("malformed", id + " 的 sha256 既不是 64 位 hex 也不是 pending：" + sha)
            }
            val abis = ArrayList<String>()
            o.getAsJsonArray("abis")?.forEach { abis.add(it.asString) }
            entries.add(
                Entry(
                    id = id,
                    displayName = displayName,
                    packageName = pkg,
                    version = version,
                    versionCode = o.int("versionCode") ?: 0,
                    minAppVersion = o.int("minAppVersion") ?: 0,
                    file = file,
                    sizeBytes = o.long("sizeBytes"),
                    sha256 = sha,
                    requiresRestart = o.get("requiresRestart")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    abis = abis,
                    notes = o.string("notes") ?: ""
                )
            )
        }
        val generatedAt = root.string("generatedAt") ?: ""
        return ParseResult.Success(entries, generatedAt)
    }

    private fun JsonObject.string(key: String): String? {
        val value = get(key) ?: return null
        if (!value.isJsonPrimitive) return null
        val text = value.asString
        return if (text.isBlank()) null else text
    }

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isString }?.let { runCatching { it.asLong }.getOrNull() }
}
