package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import java.util.regex.Pattern

/** Bounded search over the actual semantic targets, not just ZIP/class names. */
internal class ToolkitApkSearch(private val entries: List<String>, private val read: (String) -> ByteArray,
    private val resourceTable: () -> ToolkitResources?, private val dexes: () -> Sequence<Pair<String, ToolkitDex>>) {
    data class Result(val hits: List<JsonObject>, val partial: Boolean, val metadata: JsonObject)
    fun search(args: JsonObject): Result {
        val target = args.tString("target", "overview")
        val targets = listOf("overview", "files", "resource_table_names", "resource_table_values", "resource_table_file_paths",
            "dex_names", "dex_strings", "dex_string_members", "smali", "native_symbols", "native_strings")
        require(target in targets) { "搜索范围无效" }
        val query = args.tString("query"); require(query.isNotEmpty() && query.length <= 16384) { "搜索词长度必须为1..16384" }
        val queryType = args.tString("queryType", "literal"); require(queryType in listOf("literal", "regex"))
        val matchMode = args.tString("matchMode", "contains"); require(matchMode in listOf("contains", "exact"))
        require(target != "smali" || matchMode != "exact") { "smali 不支持 exact" }
        val prefix = args.tString("prefix", ""); require(target != "overview" || prefix.isEmpty()) { "overview 的 prefix 必须为空" }
        val snippet = args.tInt("snippetMaxChars", 240, 0, 1000)
        val offsets = args.tBool("includeMatchOffsets")
        val pattern = ToolkitRegex.compile(if(queryType == "literal") Pattern.quote(query) else query,
            if(args.tBool("caseSensitive")) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val hits = arrayListOf<JsonObject>(); val skipped = arrayListOf<JsonObject>()
        val delivered = linkedMapOf<String, JsonObject>()
        val notSearched = linkedSetOf<String>()
        var scanned = 0; var partial = false
        val deadline = System.nanoTime() + 8_000_000_000L
        fun available(): Boolean {
            if(hits.size >= 10000 || scanned >= 300000 || System.nanoTime() > deadline || Thread.currentThread().isInterrupted) {
                partial = true; return false
            }
            return true
        }
        fun add(item: JsonObject, text: String, scope: String, matchKind: String) {
            if(!available()) return
            scanned++
            val matcher = ToolkitRegex.matcher(pattern, text)
            val matched = if(matchMode == "exact") ToolkitRegex.matches(matcher) else ToolkitRegex.find(matcher)
            if(!matched) return
            val identity = item.tString("locator") + "|" + scope + "|" + matchKind + "|" +
                (item.get("variant")?.asString ?: "") + "|" + (item.get("nativeLocation")?.toString() ?: "")
            val at = matcher.start(); val end = matcher.end()
            val copy = item.deepCopy()
            if(copy.get("value")?.isJsonPrimitive == true && copy.getAsJsonPrimitive("value").isString && copy.get("value").asString.length > 1000) {
                val full = copy.get("value").asString
                copy.addProperty("value", full.substring(0, full.offsetByCodePoints(0, minOf(1000, full.codePointCount(0, full.length)))))
                copy.addProperty("valueTruncated", true)
            }
            copy.addProperty("searchScope", scope)
            val hit = copy.deepCopy().apply { remove("locator"); addProperty("kind", matchKind); addProperty("matchKind", matchKind) }
            val windowStart = if(snippet == 0) 0 else (at - snippet / 2).coerceAtLeast(0).let { if(it > 0 && Character.isLowSurrogate(text[it])) it - 1 else it }
            val windowEnd = if(snippet == 0) windowStart else text.offsetByCodePoints(windowStart,
                minOf(snippet, text.codePointCount(windowStart, text.length)))
            if(snippet > 0) {
                val preview = text.substring(windowStart, windowEnd)
                copy.addProperty("snippet", preview); hit.addProperty("snippet", preview)
            }
            val span = if(offsets) {
                ToolkitIo.json("text" to if(snippet == 0) text.substring(0, text.offsetByCodePoints(0, minOf(1000, text.codePointCount(0, text.length)))) else text.substring(windowStart, windowEnd),
                    "textStartOffset" to windowStart, "textTruncated" to (windowStart > 0 || windowEnd < text.length), "start" to at, "end" to end)
            } else null
            if(span != null) { hit.add("matchOffsets", span); copy.add("matchOffsets", span); copy.addProperty("matchOffset", at) }
            val existing = delivered[identity]
            if(matchKind == "string_literal") {
                val value = ToolkitIo.json("locator" to item.get("memberLocator"), "text" to copy.get("value"), "matchOffsets" to span)
                if(existing != null) {
                    val strings = existing.getAsJsonObject("hit").getAsJsonArray("matchedStrings")
                    if(strings.size() < 200 && strings.none { it == value }) strings.add(value)
                    return
                }
                hit.add("matchedStrings", com.google.gson.JsonArray().apply { add(value) })
            } else if(existing != null) return
            copy.add("hit", hit); hits.add(copy); delivered[identity] = copy
        }
        fun scope(name: String, block: () -> Unit) {
            if(!available()) { notSearched.add(name); return }
            try { block() } catch(failure: Exception) {
                partial = true; notSearched.add(name)
                skipped.add(ToolkitIo.json("scope" to name, "message" to (failure.message ?: failure.javaClass.simpleName)))
            }
        }
        if(target in listOf("overview", "files")) {
            scope("zip_entries") { entries.filter { it.startsWith(prefix) }.forEach { path ->
                add(ToolkitIo.json("locator" to "zip_entry:$path", "path" to path), path, "zip_entries", "path")
            } }
            scope("axml") {
                val candidates = entries.filter { (it.endsWith(".xml", true) || it == "AndroidManifest.xml") && it.startsWith(prefix) }
                if(candidates.size > 2000) { partial = true; notSearched.add("axml") }
                val resources by lazy { resourceTable() }
                candidates.take(2000).forEach { path ->
                    if(available()) try {
                        val bytes = read(path)
                        if(ToolkitResources.isBinaryXml(bytes)) {
                            val xml = ToolkitResources.decodeXml(bytes, resources)
                            require(xml.length <= ToolkitIo.MAX_TEXT) { "AXML 文本超过10MiB" }
                            add(ToolkitIo.json("locator" to "axml:$path", "path" to path), xml, "axml", "axml_text")
                        }
                    } catch(failure: Exception) {
                        partial = true; skipped.add(ToolkitIo.json("scope" to "axml", "locator" to "axml:$path", "message" to failure.message))
                    }
                }
            }
        }
        if(target in listOf("overview", "resource_table_names", "resource_table_values", "resource_table_file_paths")) scope("resource_table") {
            resourceTable()?.let { table -> table.entries().forEach { entry ->
                if(available()) {
                    val locator = table.locator(entry); val fullName = "${entry.typeName}/${entry.name}"
                    val resourcePrefix = prefix.removePrefix("resource:")
                    val accepts = resourcePrefix.isEmpty() || if(resourcePrefix.startsWith("0x", true)) locator.substringAfter(':').startsWith(resourcePrefix, true)
                        else fullName.startsWith(resourcePrefix) || entry.name.startsWith(resourcePrefix)
                    if(accepts) {
                        val item = table.item(entry)
                        if(target in listOf("overview", "resource_table_names")) {
                            add(item, fullName, "resource_table_names", "resource_name")
                            add(item, locator.substringAfter(':'), "resource_table_names", "resource_id")
                        }
                        if(target in listOf("overview", "resource_table_values")) add(item, table.xml(entry), "resource_table_values", "resource_value")
                        if(target == "resource_table_file_paths") entry.valueAsString?.takeIf { it.startsWith("res/") }?.let {
                            add(item, it, "resource_table_file_paths", "resource_file_path")
                        }
                    }
                }
            } }
        }
        if(target in listOf("overview", "dex_names", "dex_strings", "dex_string_members", "smali")) scope("dex") {
            val ownerPrefix = ToolkitDex.ownerPrefix(prefix)
            for((dexPath, dex) in dexes()) {
                if(!available()) break
                if(target in listOf("overview", "dex_names", "smali")) for(type in dex.classes) {
                    if(!available()) break
                    if(!type.type.startsWith(ownerPrefix)) continue
                    val base = ToolkitIo.json("locator" to "dex_class:${type.type}", "dexEntry" to dexPath)
                    if(target == "smali") add(base, dex.renderClass(type), "smali", "smali_text") else {
                        add(base, type.type, "dex_names", "class_name")
                        add(base, type.type.removePrefix("L").removeSuffix(";").replace('/', '.'), "dex_names", "class_name")
                        add(base, type.type.substringAfterLast('/').removeSuffix(";"), "dex_names", "class_name")
                        type.fields.forEach { field ->
                            val item = ToolkitIo.json("locator" to "dex_field:${dex.fieldId(field)}", "dexEntry" to dexPath)
                            add(item, dex.fieldId(field), "dex_names", "field_name"); add(item, field.name, "dex_names", "field_name")
                        }
                        type.methods.forEach { method ->
                            val item = ToolkitIo.json("locator" to "dex_method:${dex.methodId(method)}", "dexEntry" to dexPath)
                            add(item, dex.methodId(method), "dex_names", "method_name"); add(item, method.name, "dex_names", "method_name")
                        }
                    }
                }
                if(target in listOf("overview", "dex_strings", "dex_string_members")) {
                    val values = dex.strings(100001)
                    if(values.size > 100000) { partial = true; notSearched.add("dex_strings") }
                    values.take(100000).forEach { value ->
                        val original = value.tString("locator"); val owner = original.substringAfter(':').substringBefore("->")
                        if(owner.startsWith(ownerPrefix) && available()) {
                            val item = value.deepCopy(); item.addProperty("dexEntry", dexPath)
                            item.addProperty("memberLocator", original)
                            if(target != "dex_string_members") item.addProperty("locator", "dex_class:$owner")
                            add(item, value.tString("value"), "dex_strings", "string_literal")
                        }
                    }
                }
            }
        }
        if(target in listOf("native_symbols", "native_strings")) scope(target) {
            entries.filter { it.endsWith(".so") && it.startsWith(prefix) }.forEach { path ->
                if(available()) try {
                    if(target == "native_symbols") add(ToolkitIo.json("locator" to "zip_entry:$path", "path" to path), path, target, "native_entry_path")
                    val elf = ToolkitElf(read(path))
                    val items = if(target == "native_symbols") elf.items("symbols", "") + elf.items("imports", "") + elf.items("relocations", "") else elf.strings(100001)
                    if(items.size > 100000) { partial = true; notSearched.add(target) }
                    items.take(100000).forEach { source ->
                        val item = source.deepCopy(); item.addProperty("locator", "zip_entry:$path")
                        val symbolAddress = source.get("address")?.takeUnless { it.isJsonNull || source.get("sectionIndex")?.asInt == 0 }?.asString?.let {
                            runCatching { if(it.startsWith("0x", true)) it.substring(2).toLong(16) else it.toLong() }.getOrNull()
                        }
                        val fileOffset = source.get("fileOffset")?.takeUnless { it.isJsonNull }?.asLong ?: symbolAddress?.let { elf.fileOffset(it) }
                        val address = fileOffset?.let { elf.virtual(it) }
                        val location = ToolkitIo.json("name" to source.get("name"), "fileOffset" to fileOffset?.let { ToolkitElf.hex(it) },
                            "virtualAddress" to address?.let { ToolkitElf.hex(it) }, "xrefAddressKind" to if(fileOffset != null) "file_offset" else null,
                            "xrefAddress" to fileOffset?.let { ToolkitElf.hex(it) })
                        item.add("nativeLocation", location)
                        val kind = if(target == "native_symbols") "native_symbol" else "native_string"
                        val decoded = if(target == "native_symbols" && source.has("name")) ToolkitNative.demangle(source.tString("name")) else null
                        if(decoded != null) { item.addProperty("demangledName", decoded); location.addProperty("demangledName", decoded) }
                        add(item, source.get("value")?.asString ?: source.tString("name", ""), target, kind)
                        if(decoded != null) add(item, decoded, target, kind)
                    }
                } catch(failure: Exception) { partial = true; skipped.add(ToolkitIo.json("scope" to target, "locator" to "zip_entry:$path", "message" to failure.message)) }
            }
        }
        if(partial && notSearched.isEmpty()) notSearched.add(target)
        return Result(hits, partial, ToolkitIo.json("notSearchedTargets" to notSearched, "skippedScopes" to skipped,
            "filterHint" to ToolkitIo.json("code" to if(hits.isEmpty() && prefix.isNotEmpty()) "PREFIX_FILTER" else "", "message" to if(prefix.isEmpty()) "" else "prefix 按目标类型过滤候选")))
    }
}
