package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.Locale

/** Read-only documentation, loaded lazily from the exact asset used by the App WebView. */
internal class McpApiDocs(private val load: () -> String) {
    private data class Entry(
        val id: String, val title: String, val category: String, val kind: String,
        val source: String, val engines: List<String>, val text: String, val content: String, val link: String?,
        val sections: JsonArray, val examples: JsonArray,
    ) {
        val search = "$title $category $text".lowercase(Locale.ROOT)
        val lowerTitle = title.lowercase(Locale.ROOT)
        fun metadata() = JsonObject().apply {
            addProperty("id", id); addProperty("title", title); addProperty("category", category)
            addProperty("kind", kind); addProperty("source", source)
            addProperty("link", link)
            add("engines", JsonArray().apply { engines.forEach { add(it) } })
            addProperty("supportEvidence", "documentation_only")
            addProperty("exampleCount", examples.size())
            add("sectionTitles", JsonArray().apply { sections.forEach { add(it.asJsonObject.get("title").asString) } })
            addProperty("engineNote", when {
                kind == "legacy" -> "传统 Rhino 参考；不是运行验证"
                source == "docs/guides/API_REFERENCE.md" -> "使用手册按章节标注范围；章节内各段可能有不同引擎限制，请阅读正文"
                source == "docs/guides/API_DETAILS.md" -> "按本条声明的引擎范围阅读；源码分析不等于目标手机已验证"
                else -> "Node 专题；不是运行验证"
            })
        }
    }
    private data class Index(val hash: String, val scope: String, val entries: List<Entry>)
    private val index by lazy {
        val asset = load()
        val prefix = "window.AIJS_API_REFERENCE="
        val encoded = asset.trimEnd()
        check(encoded.startsWith(prefix) && encoded.endsWith(';')) { "API 文档索引格式无效" }
        // Parse JSON only: never evaluate the asset as JavaScript or start an engine.
        val data = JsonParser().parse(encoded.substring(prefix.length, encoded.length - 1)).asJsonObject
        check(data.get("schemaVersion").asInt in 2..3) { "API 文档索引版本不支持" }
        val entries = data.getAsJsonArray("records").map { item ->
            val row = item.asJsonObject
            Entry(row.get("id").asString, row.get("title").asString, row.get("category").asString,
                row.get("kind").asString, row.get("source").asString,
                row.getAsJsonArray("engines").map { it.asString }, row.get("text").asString,
                row.get("content").asString, row.get("link")?.takeUnless { it.isJsonNull }?.asString,
                row.getAsJsonArray("sections") ?: JsonArray(), row.getAsJsonArray("examples") ?: JsonArray())
        }
        check(entries.map { it.id }.toSet().size == entries.size) { "API 文档 ID 重复" }
        val hash = MessageDigest.getInstance("SHA-256").digest(asset.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        Index("sha256:$hash", data.get("scope").asString, entries)
    }

    fun search(args: JsonObject): JsonObject {
        allowed(args, setOf("query", "engine", "category", "kind", "offset", "limit"))
        val query = string(args, "query").trim().also { require(it.length <= 256) { "query 最多 256 字符" } }
        val engine = when (val value = string(args, "engine").trim().lowercase(Locale.ROOT)) {
            "", "all" -> ""
            else -> McpEngines.normalize(value)
        }
        val category = string(args, "category").trim().also { require(it.length <= 128) { "category 最多 128 字符" } }
        val kind = string(args, "kind", "all").trim().also { require(it in listOf("all", "guide", "legacy")) { "kind 必须是 all、guide 或 legacy" } }
        val offset = integer(args, "offset", 0, 0, Int.MAX_VALUE)
        val limit = integer(args, "limit", 10, 1, 20)
        val data = index
        val categories = data.entries.filter { engine.isEmpty() || engine in it.engines }.map { it.category }.distinct()
        require(category.isEmpty() || category in categories) { "category 不存在；请先省略 category 获取 categories" }
        val tokens = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matches = data.entries.filter {
            (engine.isEmpty() || engine in it.engines) && (category.isEmpty() || category == it.category) &&
                (kind == "all" || kind == it.kind) && tokens.all { token -> token in it.search }
        }.sortedWith(compareByDescending<Entry> { entry ->
            tokens.map { token -> when {
                entry.lowerTitle == token -> 100
                entry.lowerTitle.startsWith(token) -> 60
                token in entry.lowerTitle -> 40
                else -> 1
            } }.sum()
        }.thenBy { if (it.source == "docs/guides/API_DETAILS.md") 0 else if (it.kind == "guide") 1 else 2 })
        require(offset <= matches.size) { "offset 超出结果范围（total=${matches.size}）" }
        val end = minOf(offset.toLong() + limit, matches.size.toLong()).toInt()
        return base(data).apply {
            addProperty("query", query); addProperty("engine", engine.ifEmpty { "all" })
            addProperty("category", category); addProperty("kind", kind)
            add("categories", JsonArray().apply { categories.forEach { add(it) } })
            addProperty("total", matches.size); addProperty("offset", offset)
            addProperty("nextOffset", end); addProperty("hasMore", end < matches.size)
            add("items", JsonArray().apply {
                matches.subList(offset, end).forEach { entry ->
                    val at = tokens.map { entry.text.lowercase(Locale.ROOT).indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
                    var start = maxOf(0, at - 60)
                    if (start > 0 && Character.isLowSurrogate(entry.text[start]) && Character.isHighSurrogate(entry.text[start - 1])) start--
                    val endExcerpt = endAtBoundary(entry.text, minOf(start + 360, entry.text.length))
                    add(entry.metadata().apply {
                        addProperty("excerpt", (if (start > 0) "…" else "") + entry.text.substring(start, endExcerpt) +
                            if (entry.text.length > endExcerpt) "…" else "")
                        addProperty("contentLength", entry.content.length)
                    })
                }
            })
        }
    }

    fun read(args: JsonObject): JsonObject {
        allowed(args, setOf("id", "indexSha256", "section", "includeExamples", "offset", "maxChars"))
        val id = string(args, "id")
        require(id.matches(Regex("api-[1-9][0-9]{0,8}"))) { "id 必须使用 search_api_docs 返回的 api-N" }
        val expectedHash = string(args, "indexSha256").also {
            require(it.isEmpty() || it.matches(Regex("sha256:[0-9a-f]{64}"))) { "indexSha256 必须使用 search_api_docs 返回的 sha256 摘要" }
        }
        val offset = integer(args, "offset", 0, 0, Int.MAX_VALUE)
        val maxChars = integer(args, "maxChars", 16000, 128, 32000)
        val section = string(args, "section").trim().also { require(it.length <= 80) { "section 最多 80 字符" } }
        val includeExamples = args.get("includeExamples")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "includeExamples 必须是 JSON 布尔值" }
            it.asBoolean
        } ?: true
        val data = index
        require(expectedHash.isEmpty() || expectedHash == data.hash) { "文档索引已变化，请重新 search_api_docs 获取 id/indexSha256" }
        val entry = data.entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("找不到文档条目：$id")
        val text = if (section.isEmpty()) entry.content else entry.sections.firstOrNull {
            it.asJsonObject.get("title").asString == section
        }?.asJsonObject?.get("content")?.asString ?: throw IllegalArgumentException("section 不存在；请使用 sectionTitles 返回的名称")
        require(offset <= text.length) { "offset 超出条目范围（contentLength=${text.length}）" }
        require(offset == 0 || offset == text.length || !Character.isLowSurrogate(text[offset]) ||
            !Character.isHighSurrogate(text[offset - 1])) { "offset 不能切开 UTF-16 字符；请使用 nextOffset" }
        val end = endAtBoundary(text, minOf(offset.toLong() + maxChars, text.length.toLong()).toInt())
        return base(data).apply {
            entry.metadata().entrySet().forEach { (key, value) -> add(key, value) }
            addProperty("contentFormat", "markdown"); addProperty("content", text.substring(offset, end))
            addProperty("contentLength", text.length); addProperty("offset", offset)
            addProperty("nextOffset", end); addProperty("hasMore", end < text.length)
            addProperty("truncated", offset > 0 || end < text.length)
            addProperty("selectedSection", section)
            add("sections", JsonArray().apply { entry.sections.forEach {
                val row = it.asJsonObject
                add(JsonObject().apply { addProperty("title", row.get("title").asString); addProperty("contentLength", row.get("content").asString.length) })
            } })
            // Partial pages never leak the unbounded full example collection outside maxChars.
            val complete = offset == 0 && end == text.length
            addProperty("examplesIncluded", includeExamples && complete)
            addProperty("examplesOmittedReason", if (!includeExamples) "not_requested" else if (!complete) "paged_content" else "")
            add("examples", JsonArray().apply {
                if (includeExamples && complete) entry.examples.filter {
                    section.isEmpty() || text.contains(it.asJsonObject.get("code").asString)
                }.forEach { add(it.deepCopy()) }
            })
        }
    }

    private fun base(data: Index) = JsonObject().apply {
        addProperty("indexSha256", data.hash); addProperty("indexedRecords", data.entries.size)
        addProperty("scope", data.scope)
        addProperty("hint", "文档标注不等于运行验证；传统参考仅标注 Rhino。真实 API 形状用 probe_engine_api，行为需在目标引擎验证。ID 随索引更新可变化，读取时建议携带 indexSha256。")
    }

    private fun allowed(args: JsonObject, names: Set<String>) {
        require(args.keySet().all { it in names }) { "未知参数：${args.keySet() - names}" }
    }
    private fun endAtBoundary(text: String, end: Int): Int =
        if (end > 0 && end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end - 1 else end
    private fun string(args: JsonObject, name: String, fallback: String = ""): String {
        val value = args.get(name) ?: return fallback
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name 必须是字符串" }
        return value.asString
    }
    private fun integer(args: JsonObject, name: String, fallback: Int, min: Int, max: Int): Int {
        val value = args.get(name) ?: return fallback
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name 必须是 JSON 整数" }
        val number = try { value.asBigDecimal.intValueExact() } catch (_: ArithmeticException) {
            throw IllegalArgumentException("$name 必须是范围内的整数")
        }
        require(number in min..max) { "$name 范围为 $min..$max" }
        return number
    }
}
