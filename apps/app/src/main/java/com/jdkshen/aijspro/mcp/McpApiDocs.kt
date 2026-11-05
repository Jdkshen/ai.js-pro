package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.Locale

/** Read-only documentation, loaded lazily from the exact asset used by the App WebView. */
internal class McpApiDocs(private val load: () -> String) {
    /** 文档索引里出现的一个名字（标题/指南标题或 category 的 module），用于 documented 对账。 */
    private data class DocName(
        val name: String,
        val module: String,
        val engines: Set<String>,
        val title: String,
    )

    /** list_engine_api 单个名字的文档匹配结果；规则写进返回顶层 note。 */
    internal data class DocumentedMatch(
        val documented: Boolean,
        val sameModule: Boolean,
        val matchedTitle: String?,
        val matchedModule: String?,
        val rule: String,
    )

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
    private data class Index(
        val hash: String,
        val scope: String,
        val entries: List<Entry>,
        val names: List<DocName>,
        val documentedAt: Long?,
        val documentedAtSource: String,
        val schemaVersion: Int,
    )

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
        val timestamp = indexTimestamp(data)
        Index(
            "sha256:" + hash,
            data.get("scope").asString,
            entries,
            collectDocumentedNames(entries),
            timestamp.first,
            timestamp.second,
            data.get("schemaVersion").asInt,
        )
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
        // 向后兼容：只新增字段，不改变 indexSha256/scope/indexedRecords 语义。
        addProperty("build", com.jdkshen.aijspro.BuildConfig.VERSION_NAME)
        data.documentedAt?.let { addProperty("documentedAt", it) } ?: add("documentedAt", JsonNull.INSTANCE)
        addProperty("documentedAtSource", data.documentedAtSource)
        addProperty(
            "documentedAtNote",
            if (data.documentedAt != null && data.documentedAtSource == "apk.lastUpdateTime") {
                "documentedAt 来源：apk.lastUpdateTime（这个 build 的安装时间，epoch millis）—— 索引里没有文档生成时间，" +
                    "所以这里给的是「你手上这个 build 有多新」，用来和 build 字段一起对账；它不是文档生成时间，也不是运行验证。"
            } else if (data.documentedAt != null) {
                "documentedAt 来源：" + data.documentedAtSource + "（epoch millis）；用于文档与构建对账，不是运行验证。"
            } else {
                "索引 schema v" + data.schemaVersion +
                    " 未提供生成时间，且读不到 APK 内 assets/docs/api-reference-data.js 的 ZIP 条目时间" +
                    "（或该时间早于 2000 年，视为不可信）；因此 documentedAt=null 而不是猜测。" +
                    "生成器写入 generatedAt（本实现优先读取）后即可显示。"
            },
        )
        addProperty("hint", "文档标注不等于运行验证；传统参考仅标注 Rhino。真实 API 形状用 probe_engine_api，行为需在目标引擎验证。ID 随索引更新可变化，读取时建议携带 indexSha256。")
    }

    /**
     * list_engine_api 对账入口：索引里是否存在该名字。
     *
     * 匹配规则：
     * 1) 名字来自索引标题/指南标题中的标识符（去掉参数列表、大小写不敏感），以及 category 里的 module；
     * 2) 该条记录 engines 必须包含当前 engine（未标 engines 的记录视为通用；Rhino-only 记录不会把 QuickJS/Node 标成已文档化）；
     * 3) 先同 module 匹配（如 module=device + name=width -> device.width），找不到再看任意 module；
     * 4) 仍找不到返回 documented=false，不猜。
     *
     * 注意：指南标题里出现的名字算「有文档提及」，不等于逐成员文档；返回顶层 note 会说明。
     */
    internal fun matchDocumented(engine: String, module: String, name: String): DocumentedMatch {
        val data = index
        val engineKey = engine.trim().lowercase(Locale.ROOT)
        val moduleKey = module.trim().lowercase(Locale.ROOT)
        val nameKey = name.trim().lowercase(Locale.ROOT)
        if (nameKey.isEmpty()) return DocumentedMatch(false, false, null, null, "empty_name")
        val full = if (moduleKey.isEmpty()) nameKey else moduleKey + "." + nameKey
        val relevant = data.names.filter { engineKey.isEmpty() || engineKey in it.engines }

        fun matches(candidate: DocName): Boolean =
            candidate.name == full || candidate.name == nameKey || (moduleKey.isEmpty() && candidate.module == nameKey)

        val sameModule = relevant.firstOrNull { candidate ->
            (moduleKey.isNotEmpty() && candidate.module == moduleKey &&
                (candidate.name == full || candidate.name == nameKey)) ||
                (moduleKey.isEmpty() && (candidate.name == nameKey || candidate.module == nameKey))
        }
        if (sameModule != null) {
            return DocumentedMatch(true, true, sameModule.title, sameModule.module, "same_module")
        }
        val any = relevant.firstOrNull { matches(it) }
        if (any != null) {
            return DocumentedMatch(true, false, any.title, any.module, "any_module")
        }
        return DocumentedMatch(false, false, null, null, if (relevant.isEmpty()) "no_records_for_engine" else "not_found")
    }

    /** 供诊断/测试查看索引可匹配名字数量（不暴露全文）。 */
    internal fun documentedNameCount(): Int = index.names.size

    private fun collectDocumentedNames(entries: List<Entry>): List<DocName> {
        val result = ArrayList<DocName>()
        entries.forEach { entry ->
            val categoryModule = moduleFromCategory(entry.category)
            val engines = entry.engines.map { it.lowercase(Locale.ROOT) }.toSet()
            val normalizedTitle = entry.title
                .replace('（', '(').replace('）', ')')
                .replace('，', ',').replace('、', ',')
            val head = normalizedTitle.substringBefore('(').trim()
            IDENTIFIER_TOKEN.findAll(head).forEach { match ->
                val name = match.value.lowercase(Locale.ROOT)
                if (!name.matches(IDENTIFIER_PATTERN) || name in STOPWORDS) return@forEach
                val module = when {
                    name.contains('.') -> name.substringBefore('.')
                    categoryModule.isNotEmpty() -> categoryModule
                    else -> ""
                }
                result.add(DocName(name, module, engines, entry.title))
            }
            if (categoryModule.isNotEmpty()) {
                result.add(DocName(categoryModule, categoryModule, engines, entry.title))
            }
        }
        return result
    }

    private fun moduleFromCategory(category: String): String {
        val raw = category.substringAfter("· ", "").trim().lowercase(Locale.ROOT)
        return if (raw.matches(IDENTIFIER_PATTERN)) raw else ""
    }

    /** 索引若带生成时间则直接使用；否则 documentedAt=null 并记录来源，不用构建日期冒充。 */
    private fun indexTimestamp(data: JsonObject): Pair<Long?, String> {
        val keys = listOf(
            "generatedAt", "generated_at", "builtAt", "built_at",
            "indexedAt", "indexed_at", "documentedAt", "documented_at",
        )
        for (key in keys) {
            val value = data.get(key) ?: continue
            if (!value.isJsonPrimitive) return null to "unavailable:" + key + "-not-primitive"
            val primitive = value.asJsonPrimitive
            if (primitive.isNumber) return primitive.asLong to "index." + key
            if (primitive.isString) {
                val parsed = primitive.asString.trim().toLongOrNull()
                return if (parsed != null) parsed to "index." + key else null to "unavailable:unsupported-" + key
            }
            return null to "unavailable:unsupported-" + key
        }
        // 索引没有生成时间时的兜底：APK 内该 asset 的 ZIP 条目时间（构建/打包时间，可作文件修改时间近似）。
        // 用 runCatching 包住：纯 JVM 单测里 GlobalAppContext 不可用属于预期，返回 null+来源说明即可。
        val assetTime = runCatching {
            val context = com.stardust.app.GlobalAppContext.get()
            val applicationInfo = context.javaClass.getMethod("getApplicationInfo").invoke(context)
            val sourceDir = applicationInfo.javaClass.getField("sourceDir").get(applicationInfo) as? String
            if (sourceDir.isNullOrEmpty()) return@runCatching null
            val apk = java.io.File(sourceDir)
            if (!apk.isFile) return@runCatching null
            java.util.zip.ZipFile(apk).use { zip ->
                val entry = zip.getEntry("assets/docs/api-reference-data.js") ?: return@use null
                entry.time.takeIf { it > 946684800000L } // 2000-01-01；更早的 1980 时间不可信
            }
        }.getOrNull()
        if (assetTime != null) return assetTime to "apk.assets-entry-time"
        // 最后兜底：该 build 的安装时间。APK 内 ZIP 条目时间常常是 1980/0（打包工具不写），
        // 与其报 unavailable，不如给一个真实、可解释、且明确标注来源的时间戳。
        val updatedAt = runCatching {
            val context = com.stardust.app.GlobalAppContext.get()
            val flags = 0
            val info = context.packageManager.getPackageInfo(context.packageName, flags)
            info.lastUpdateTime.takeIf { it > 946684800000L }
        }.getOrNull()
        return if (updatedAt != null) updatedAt to "apk.lastUpdateTime" else null to "unavailable"
    }

    private companion object {
        private val IDENTIFIER_PATTERN = Regex("[a-z_$][a-z0-9_$]*(?:\\.[a-z_$][a-z0-9_$]*)*")
        private val IDENTIFIER_TOKEN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
        private val STOPWORDS = setOf(
            "rhino", "quickjs", "nodejs", "node24", "node", "api", "mcp", "ocr", "yolo", "js", "dev", "pro",
        )
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
