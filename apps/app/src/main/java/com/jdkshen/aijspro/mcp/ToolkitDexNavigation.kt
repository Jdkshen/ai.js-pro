package com.jdkshen.aijspro.mcp

import com.google.gson.*
import org.jf.baksmali.Adaptors.ClassDefinition
import org.jf.baksmali.BaksmaliOptions
import org.jf.baksmali.formatter.BaksmaliWriter
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.DualReferenceInstruction
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.*
import org.jf.dexlib2.iface.value.*
import java.io.StringWriter
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * Shared DEX parsing/rendering helper. [ToolkitDex] itself is intentionally left untouched, so this
 * helper reparses the already-buffered DEX at a modern API level. The legacy reader uses
 * Opcodes.getDefault() (API 20), which decodes invoke-custom / const-method-handle as NOP on some
 * dexlib2 versions. Reparsing the same bytes keeps the existing ToolkitDex ownership and gives this
 * navigation layer access to CallSiteReference / MethodHandleReference / DualReferenceInstruction.
 */
internal object ToolkitDexSupport {
    const val MODERN_API = 28
    private val cache = WeakHashMap<ToolkitDex, DexBackedDexFile>()

    @Synchronized
    fun dexFile(toolkit: ToolkitDex): DexBackedDexFile {
        cache[toolkit]?.let { return it }
        val decoded = try {
            DexBackedDexFile(Opcodes.forApi(maxOf(MODERN_API, toolkit.dex.opcodes.api)), toolkit.dex.getBuffer().getBuf())
        } catch (_: Exception) {
            toolkit.dex
        }
        cache[toolkit] = decoded
        return decoded
    }

    fun classes(toolkit: ToolkitDex): List<ClassDef> = dexFile(toolkit).classes.sortedBy { it.type }

    fun definition(toolkit: ToolkitDex, type: String): ClassDef? = dexFile(toolkit).classes.firstOrNull { it.type == type }

    fun isModern(toolkit: ToolkitDex): Boolean = dexFile(toolkit) !== toolkit.dex

    fun renderClass(toolkit: ToolkitDex, definition: ClassDef): String {
        val output = StringWriter()
        val writer = BaksmaliWriter(output)
        ClassDefinition(BaksmaliOptions().apply { apiLevel = maxOf(MODERN_API, toolkit.dex.opcodes.api) }, definition).writeTo(writer)
        writer.flush()
        require(output.buffer.length <= ToolkitIo.MAX_TEXT) { "Smali 类超过 10 MiB" }
        return output.toString()
    }
}

/** Line/method anchors for one rendered class. Lines are zero based internally. */
internal object ToolkitDexAnchors {
    data class MethodAnchor(
        val locator: String,
        val methodId: String,
        val startOffset: Int,
        val endOffset: Int,
        val lineStart: Int,
        val lineEnd: Int,
        val codeUnitByLine: Map<Int, Int>,
    )

    data class Index(val anchors: List<MethodAnchor>, val lineStarts: IntArray) {
        fun at(charOffset: Int): MethodAnchor? = anchors.firstOrNull { charOffset >= it.startOffset && charOffset < it.endOffset }

        fun lineOf(charOffset: Int): Int {
            if (lineStarts.isEmpty()) return 0
            var low = 0
            var high = lineStarts.size - 1
            var found = 0
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (lineStarts[middle] <= charOffset) { found = middle; low = middle + 1 } else high = middle - 1
            }
            return found
        }

        fun lineRange(start: Int, end: Int): Pair<Int, Int> {
            val last = if (end > start) end - 1 else start
            return lineOf(start) + 1 to lineOf(last) + 1
        }

        fun instructionLine(anchor: MethodAnchor, codeUnitOffset: Int): Int? =
            anchor.codeUnitByLine.entries.firstOrNull { it.value == codeUnitOffset }?.key?.plus(1)
    }

    fun index(source: String, definition: ClassDef, methodId: (Method) -> String): Index {
        val starts = ArrayList<Int>(); starts.add(0)
        source.forEachIndexed { index, character -> if (character == '\n') starts.add(index + 1) }
        val lineStarts = starts.toIntArray()
        val lines = source.split('\n')
        val anchors = ArrayList<MethodAnchor>()
        var line = 0
        while (line < lines.size) {
            val trimmed = lines[line].trim()
            if (trimmed.startsWith(".method ")) {
                val descriptor = trimmed.substringAfterLast(' ')
                val fullId = definition.type + "->" + descriptor
                var end = line + 1
                while (end < lines.size && lines[end].trim() != ".end method") end++
                if (end >= lines.size) break
                val method = definition.methods.firstOrNull { methodId(it) == fullId }
                val startOffset = lineStarts[line]
                val endOffset = if (end + 1 < lineStarts.size) lineStarts[end + 1] else source.length
                val codeUnitByLine = method?.let { instructionOffsets(lines, line, end, it.implementation?.instructions?.toList().orEmpty()) }.orEmpty()
                anchors.add(MethodAnchor("dex_method:$fullId", fullId, startOffset, endOffset, line, end, codeUnitByLine))
                line = end + 1
            } else line++
        }
        return Index(anchors, lineStarts)
    }

    private fun instructionOffsets(lines: List<String>, methodStart: Int, methodEnd: Int, instructions: List<Instruction>): Map<Int, Int> {
        if (instructions.isEmpty()) return emptyMap()
        val offsets = LinkedHashMap<Int, Int>()
        var instructionIndex = 0
        var codeUnit = 0
        var line = methodStart + 1
        while (line < methodEnd) {
            val trimmed = lines[line].trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(':') -> { line++; continue }
                trimmed.startsWith(".array-data") || trimmed.startsWith(".packed-switch") || trimmed.startsWith(".sparse-switch") -> {
                    val payload = instructions.getOrNull(instructionIndex) ?: break
                    if (!payload.opcode.name.contains("payload")) break
                    val endDirective = when {
                        trimmed.startsWith(".array-data") -> ".end array-data"
                        trimmed.startsWith(".packed-switch") -> ".end packed-switch"
                        else -> ".end sparse-switch"
                    }
                    offsets[line] = codeUnit
                    instructionIndex++
                    codeUnit += payload.codeUnits
                    line++
                    while (line < methodEnd && lines[line].trim() != endDirective) line++
                    line++
                    continue
                }
                trimmed.startsWith('.') -> { line++; continue }
                else -> {
                    val token = trimmed.substringBefore(' ').substringBefore('\t')
                    val instruction = instructions.getOrNull(instructionIndex) ?: break
                    if (!token.equals(instruction.opcode.name, ignoreCase = true)) break
                    offsets[line] = codeUnit
                    instructionIndex++
                    codeUnit += instruction.codeUnits
                    line++
                }
            }
        }
        return offsets
    }
}

/**
 * Cross-DEX navigation over the existing ToolkitDex readers. It never mutates DEX bytes and every
 * identity returned here carries its dexEntry, so duplicate class definitions in classes2/3/... are
 * not silently merged.
 *
 * Public integration surface (root owns dispatch/schema):
 *   searchMembers(args)
 *   hierarchy(args)
 *   callGraph(args)
 *   call(name, args)
 *
 * All methods return the *data* JsonObject, not a ToolkitIo.ok envelope. Root can wrap/paginate.
 */
internal class ToolkitDexNavigation(dexes: List<Pair<String, ToolkitDex>>) {
    companion object {
        private const val ACC_PRIVATE = 0x2
        private const val ACC_STATIC = 0x8
        private const val ACC_NATIVE = 0x100
        private const val ACC_INTERFACE = 0x200
        private const val ACC_ABSTRACT = 0x400

        private const val SEARCH_LIMIT_MAX = 10_000
        private const val SEARCH_SCANNED_MAX = 300_000
        private const val GRAPH_INDEX_EDGE_MAX = 200_000
        private const val GRAPH_INDEX_METHOD_MAX = 250_000
        private const val GRAPH_SCAN_INSTRUCTION_MAX = 500_000
        private const val GRAPH_EDGES_PER_METHOD_MAX = 5_000
        private const val FACTS_MAX = 600

        private val HANDLE_TYPES = listOf("static_put", "static_get", "instance_put", "instance_get",
            "invoke_static", "invoke_instance", "invoke_constructor", "invoke_direct", "invoke_interface")

        private val CLASS_LOOKUPS = setOf("forName", "getMethod", "getDeclaredMethod", "getMethods", "getDeclaredMethods",
            "getConstructor", "getDeclaredConstructor", "getConstructors", "getDeclaredConstructors",
            "getField", "getDeclaredField", "getFields", "getDeclaredFields")
        private val CLASS_LOADER_METHODS = setOf("loadClass", "findClass", "findLoadedClass")
    }

    private data class Entry(val name: String, val toolkit: ToolkitDex, val file: DexBackedDexFile, val classes: List<ClassDef>, val modern: Boolean)

    private data class ClassRef(val entry: Entry, val definition: ClassDef) {
        val type: String get() = definition.type
        val locator: String get() = "dex_class:$type"
        val uniqueLocator: String get() = "dex_entry:${entry.name}!$locator"
    }

    private data class MethodRef(val owner: ClassRef, val method: Method) {
        val methodId: String get() = owner.entry.toolkit.methodId(method)
        val locator: String get() = "dex_method:$methodId"
        val uniqueLocator: String get() = "dex_entry:${owner.entry.name}!$locator"
        val slot: String get() = methodId.substringAfter("->")
    }

    private sealed class NodeSpec {
        data class Local(val ref: MethodRef) : NodeSpec()
        data class External(val reference: MethodReference) : NodeSpec()
    }

    private data class Edge(
        val from: MethodRef,
        val to: NodeSpec?,
        val kind: String,
        val opcode: String,
        val codeUnitOffset: Int,
        val resolution: String,
        val potential: Boolean,
        val dynamic: Boolean,
        val reference: String,
        val referenceClass: String,
        val referenceSlot: String,
        val virtual: Boolean,
        val methodHandleType: Int? = null,
        val callsiteName: String? = null,
        val secondary: String? = null,
    ) {
        private fun identity(spec: NodeSpec): String = when (spec) {
            is NodeSpec.Local -> spec.ref.uniqueLocator
            is NodeSpec.External -> "external:" + spec.reference.definingClass + "->" + spec.reference.name +
                "(" + spec.reference.parameterTypes.joinToString("") + ")" + spec.reference.returnType
        }

        val key: String
            get() = listOf(from.uniqueLocator, to?.let { identity(it) } ?: "unresolved:$reference", kind, opcode,
                codeUnitOffset.toString(), resolution, potential.toString(), dynamic.toString(),
                methodHandleType?.toString() ?: "").joinToString("|")
    }

    private data class ScanResult(
        val edges: List<Edge> = emptyList(),
        val unresolved: List<JsonObject> = emptyList(),
        val dynamic: List<JsonObject> = emptyList(),
        val methodHandles: List<JsonObject> = emptyList(),
        val methodTypes: List<JsonObject> = emptyList(),
        val invokeCustom: List<JsonObject> = emptyList(),
        val invokePolymorphic: List<JsonObject> = emptyList(),
        val reflectionCount: Int = 0,
        val dynamicUnknown: Boolean = false,
        val ambiguous: Boolean = false,
        val truncated: Boolean = false,
        val cancelled: Boolean = false,
    )

    private data class ReverseIndex(
        val byTarget: Map<String, List<Edge>>,
        val virtualBySlot: Map<String, List<Edge>>,
        val reflectionCount: Int,
        val dynamicUnknown: Boolean,
        val ambiguous: Boolean,
        val scannedMethods: Int,
        val scannedEdges: Int,
        val truncated: Boolean,
        val cancelled: Boolean,
    )

    private sealed class MethodResolution {
        data class Exact(val ref: MethodRef) : MethodResolution()
        data class Inherited(val ref: MethodRef) : MethodResolution()
        data class External(val reference: MethodReference, val reason: String) : MethodResolution()
        data class Ambiguous(val candidates: List<ClassRef>) : MethodResolution()
    }

    private data class ParsedLocator(val raw: String, val dexEntry: String?)

    private val entries: List<Entry>
    private val entriesByName: Map<String, Entry>
    private val classIndex: Map<String, List<ClassRef>>
    private val parentsByType: Map<String, Set<String>>
    private val childrenByType: Map<String, Set<String>>
    private val subtypeCache = HashMap<String, Boolean>()
    private val overrideCache = HashMap<String, List<MethodRef>>()

    init {
        require(dexes.isNotEmpty()) { "DEX 导航至少需要一个 DEX" }
        require(dexes.map { it.first }.toSet().size == dexes.size) { "DEX 条目名不能重复" }
        entries = dexes.map { (name, toolkit) ->
            require(name.isNotEmpty()) { "DEX 条目名不能为空" }
            val file = ToolkitDexSupport.dexFile(toolkit)
            Entry(name, toolkit, file, file.classes.sortedBy { it.type }, file !== toolkit.dex)
        }
        entriesByName = entries.associateBy { it.name }
        val classes = LinkedHashMap<String, MutableList<ClassRef>>()
        entries.forEach { entry -> entry.classes.forEach { definition ->
            classes.getOrPut(definition.type) { ArrayList() }.add(ClassRef(entry, definition))
        } }
        classIndex = classes.mapValues { it.value.toList() }
        val parents = HashMap<String, LinkedHashSet<String>>()
        classIndex.forEach { (type, refs) ->
            val all = linkedSetOf<String>()
            refs.forEach { ref ->
                ref.definition.superclass?.let { all.add(it) }
                all.addAll(ref.definition.interfaces)
            }
            if (all.isNotEmpty()) parents[type] = all
        }
        parentsByType = parents.mapValues { it.value.toSet() }
        val children = HashMap<String, LinkedHashSet<String>>()
        parents.forEach { (child, parentTypes) -> parentTypes.forEach { parent ->
            children.getOrPut(parent) { LinkedHashSet() }.add(child)
        } }
        childrenByType = children.mapValues { it.value.toSet() }
    }

    fun call(name: String, args: JsonObject): JsonObject = when (name) {
        "apk_dex_search_members", "search_members" -> searchMembers(args)
        "apk_dex_call_graph", "call_graph" -> callGraph(args)
        "apk_dex_hierarchy", "hierarchy" -> hierarchy(args)
        else -> throw IllegalArgumentException("未知 DEX 导航工具：$name")
    }

    // ------------------------------------------------------------------ search

    fun searchMembers(args: JsonObject): JsonObject {
        val query = args.tString("query")
        require(query.isNotEmpty() && query.length <= 16384) { "搜索词长度必须为1..16384" }
        val queryType = args.tString("queryType", "literal")
        require(queryType in listOf("literal", "regex")) { "queryType 必须为 literal 或 regex" }
        val matchMode = args.tString("matchMode", "contains")
        require(matchMode in listOf("contains", "exact")) { "matchMode 必须为 contains 或 exact" }
        val caseSensitive = args.tBool("caseSensitive")
        val scope = args.tString("scope", "method")
        require(scope in listOf("method", "name", "descriptor", "string", "reference", "all")) { "scope 无效" }
        val prefix = args.tString("prefix", "")
        val dexEntry = optionalString(args, "dexEntry")
        dexEntry?.let { require(entriesByName.containsKey(it)) { "DEX 条目不存在：$it" } }
        val limit = args.tInt("limit", 200, 1, SEARCH_LIMIT_MAX)
        val snippetMax = args.tInt("snippetMaxChars", 240, 0, 1000)
        val includeOffsets = args.tBool("includeMatchOffsets")
        val pattern = ToolkitRegex.compile(if (queryType == "literal") java.util.regex.Pattern.quote(query) else query,
            if (caseSensitive) 0 else java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE)
        val ownerPrefix = ToolkitDex.ownerPrefix(prefix)
        val startedAt = System.nanoTime()
        val deadline = startedAt + 8_000_000_000L
        var scanned = 0
        var scannedMembers = 0
        var scannedInstructions = 0
        var truncated = false
        var cancelled = false
        var stopReason: String? = null
        val warnings = LinkedHashSet<String>()
        val hits = ArrayList<JsonObject>()
        val seen = HashSet<String>()
        val anchorCache = HashMap<String, ToolkitDexAnchors.Index>()
        val anchorFailures = HashSet<String>()

        fun check(): Boolean {
            if (Thread.currentThread().isInterrupted) { cancelled = true; truncated = true; stopReason = "cancelled"; return false }
            if (System.nanoTime() > deadline) { truncated = true; stopReason = "time_budget"; return false }
            if (scanned >= SEARCH_SCANNED_MAX) { truncated = true; stopReason = "scan_budget"; return false }
            if (stopReason != null) return false
            return true
        }

        fun anchors(ref: ClassRef): ToolkitDexAnchors.Index? {
            val key = ref.uniqueLocator
            anchorCache[key]?.let { return it }
            if (key in anchorFailures) return null
            return try {
                val source = ToolkitDexSupport.renderClass(ref.entry.toolkit, ref.definition)
                val index = ToolkitDexAnchors.index(source, ref.definition) { ref.entry.toolkit.methodId(it) }
                anchorCache[key] = index
                index
            } catch (failure: Exception) {
                anchorFailures.add(key)
                warnings.add("无法为 ${ref.type} 计算 Smali 行号：${failure.message ?: failure.javaClass.simpleName}")
                null
            }
        }

        fun snippet(text: String, start: Int, end: Int): String {
            if (snippetMax <= 0) return ""
            val requested = (start - snippetMax / 2).coerceAtLeast(0)
            val from = if (requested in 1 until text.length && Character.isLowSurrogate(text[requested])) requested - 1 else requested
            val to = text.offsetByCodePoints(from, minOf(snippetMax, text.codePointCount(from, text.length)))
            return text.substring(from, to)
        }

        fun bounded(value: String): String {
            if (value.length <= 1000) return value
            return value.substring(0, value.offsetByCodePoints(0, minOf(1000, value.codePointCount(0, value.length))))
        }

        fun match(text: String): Pair<Int, Int>? {
            val matcher = ToolkitRegex.matcher(pattern, text)
            val found = if (matchMode == "exact") ToolkitRegex.matches(matcher) else ToolkitRegex.find(matcher)
            return if (found) matcher.start() to matcher.end() else null
        }

        fun addHit(method: MethodRef, field: String, matched: String, span: Pair<Int, Int>, codeUnitOffset: Int?,
                   opcode: String?, value: String?, resolution: String?, resolvedLocator: String?) {
            if (hits.size >= limit) { truncated = true; stopReason = "limit"; return }
            val identity = method.uniqueLocator + "|" + field + "|" + (codeUnitOffset ?: -1) + "|" + matched
            if (!seen.add(identity)) return
            scanned++
            val item = methodJson(method)
            item.addProperty("kind", when (field) { "name", "descriptor" -> "method"; "string" -> "string_literal"; else -> "reference" })
            item.addProperty("matchField", field)
            item.addProperty("searchScope", "dex_$field")
            if (snippetMax > 0) item.addProperty("snippet", snippet(matched, span.first, span.second))
            val anchorIndex = anchors(method.owner)
            val anchor = anchorIndex?.anchors?.firstOrNull { it.methodId == method.methodId }
            if (anchor != null) {
                val methodRange = ToolkitIo.json("start" to anchor.lineStart + 1, "end" to anchor.lineEnd + 1)
                item.add("lineRange", methodRange)
                item.add("memberLineRange", methodRange)
                item.addProperty("lineStart", anchor.lineStart + 1)
                item.addProperty("lineEnd", anchor.lineEnd + 1)
                item.addProperty("anchorKind", if (codeUnitOffset != null) "instruction" else "method")
            }
            codeUnitOffset?.let { item.addProperty("codeUnitOffset", it) }
            opcode?.let { item.addProperty("opcode", it) }
            value?.let {
                item.addProperty("value", bounded(it))
                if (it.length > 1000) item.addProperty("valueTruncated", true)
            }
            resolution?.let { item.addProperty("resolution", it) }
            resolvedLocator?.let { item.addProperty("resolvedLocator", it) }
            if (codeUnitOffset != null && anchor != null) {
                anchorIndex?.instructionLine(anchor, codeUnitOffset)?.let { item.addProperty("instructionLine", it) }
            }

            if (includeOffsets) item.add("matchOffsets", ToolkitIo.json("start" to span.first, "end" to span.second))
            hits.add(item)
        }

        outer@ for (entry in entries) {
            if (dexEntry != null && entry.name != dexEntry) continue
            for (definition in entry.classes) {
                if (!definition.type.startsWith(ownerPrefix)) continue
                if (!check()) break@outer
                val classRef = ClassRef(entry, definition)
                for (method in definition.methods) {
                    if (!check()) break@outer
                    val ref = MethodRef(classRef, method)
                    scanned++
                    scannedMembers++
                    if (scope in listOf("method", "name", "all")) {
                        match(method.name)?.let { addHit(ref, "name", method.name, it, null, null, null, null, null) }
                    }
                    val descriptor = ref.methodId.substringAfter("->")
                    if (scope in listOf("method", "descriptor", "all") && descriptor != method.name) {
                        match(descriptor)?.let { addHit(ref, "descriptor", descriptor, it, null, null, descriptor, null, null) }
                    }
                    if (scope !in listOf("string", "reference", "all")) continue
                    val implementation = method.implementation ?: continue
                    var offset = 0
                    for (instruction in implementation.instructions) {
                        if (!check()) break@outer
                        scanned++
                        scannedInstructions++
                        if (instruction is ReferenceInstruction) {
                            when (val reference = instruction.reference) {
                                is StringReference -> if (scope in listOf("string", "all")) {
                                    match(reference.string)?.let { addHit(ref, "string", reference.string, it, offset, instruction.opcode.name, reference.string, null, null) }
                                }
                                is MethodReference -> if (scope in listOf("reference", "all")) {
                                    val text = methodKey(reference)
                                    match(text)?.let {
                                        val resolution = resolveMethodReference(reference, entry.name)
                                        addHit(ref, "reference", text, it, offset, instruction.opcode.name, text,
                                            resolutionName(resolution), resolvedLocator(resolution))
                                    }
                                }
                                is MethodHandleReference -> if (scope in listOf("reference", "all")) {
                                    val member = reference.memberReference
                                    val text = when (member) {
                                        is MethodReference -> "method-handle " + methodKey(member)
                                        is FieldReference -> "method-handle " + fieldKey(member)
                                        else -> "method-handle " + member.toString()
                                    }
                                    match(text)?.let { addHit(ref, "reference", text, it, offset, instruction.opcode.name, text,
                                        methodHandleTypeName(reference.methodHandleType), null) }
                                }
                                is CallSiteReference -> if (scope in listOf("reference", "all")) {
                                    val text = "invoke-custom ${reference.name} ${reference.methodName} ${protoKey(reference.methodProto)}"
                                    match(text)?.let { addHit(ref, "reference", text, it, offset, instruction.opcode.name, text, "dynamic_unknown", null) }
                                }
                                else -> Unit
                            }
                        }
                        offset += instruction.codeUnits
                    }
                }
            }
        }

        if (scanned >= SEARCH_SCANNED_MAX) { truncated = true; stopReason = stopReason ?: "scan_budget" }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val budgetUsed = ToolkitIo.json(
            "budgetMs" to 8000,
            "elapsedMs" to elapsedMs,
            "scannedMembers" to scannedMembers,
            "scannedInstructions" to scannedInstructions,
            "scannedSteps" to scanned,
            "reason" to stopReason,
        )
        val budgetStop = stopReason == "scan_budget" || stopReason == "time_budget"
        val budgetHint = if (truncated && budgetStop) {
            "本次扫描未扫完（stopReason=" + (stopReason ?: "unknown") + "，已扫 " + scannedMembers +
                " 个成员/" + scannedInstructions + " 条指令）：count=0 不能判定「不存在」；请缩小 prefix、指定 dexEntry，" +
                "或改用 apk_search{target:\"dex_names\"} 先拿类名/locator 再搜。"
        } else null
        val limitHint = if (stopReason == "limit") {
            "本次是按调用方传入的 limit=$limit 截断，不是预算用尽；要更多结果请加大 limit 或缩小 prefix/查询范围。"
        } else null
        val nextActions = if (truncated && budgetStop) JsonArray().apply {
            add("缩小 prefix（例如只保留包名前缀）后重试，不要用 count=0 下结论")
            add("指定 dexEntry 只扫描一个 classes*.dex，减少扫描范围")
            add("改用 apk_search{target:\"dex_names\",query:\"" + query.replace("\"", "\\\"") + "\"} 再对本工具按类名/描述符定位")
        } else null
        val data = ToolkitIo.json(
            "items" to JsonArray().apply { hits.forEach { add(it) } },
            "count" to hits.size,
            "limit" to limit,
            "hasMore" to truncated,
            "partial" to truncated,
            "truncated" to truncated,
            "cancelled" to cancelled,
            "stopReason" to stopReason,
            "budgetUsed" to budgetUsed,
            "scope" to scope,
            "anchorFailureCount" to anchorFailures.size,
            "warnings" to warnings.toList(),
            "limits" to ToolkitIo.json("limitMax" to SEARCH_LIMIT_MAX, "scannedMax" to SEARCH_SCANNED_MAX,
                "timeBudgetSeconds" to 8, "snippetMaxChars" to 1000),
        )
        budgetHint?.let { data.addProperty("budgetHint", it) }
        limitHint?.let { data.addProperty("limitHint", it) }
        nextActions?.let { data.add("nextActions", it) }
        return data
    }

    // ------------------------------------------------------------- call graph

    fun callGraph(args: JsonObject): JsonObject {
        val parsed = parseLocator(args.tString("locator"), optionalString(args, "dexEntry"))
        require(parsed.raw.startsWith("dex_method:")) { "调用图需要 dex_method: 定位符" }
        val root = resolveMethodTarget(parsed)
        val direction = args.tString("direction", "callees")
        require(direction in listOf("callers", "callees", "both")) { "direction 必须为 callers/callees/both" }
        val resolution = args.tString("methodResolution", if (direction == "callees") "exact" else "dispatch")
        require(resolution in listOf("exact", "slot", "dispatch")) { "methodResolution 必须为 exact/slot/dispatch" }
        val maxDepth = args.tInt("maxDepth", 2, 0, 12)
        val maxNodes = args.tInt("maxNodes", 200, 1, 2_000)
        val maxEdges = args.tInt("maxEdges", 1_000, 1, 10_000)
        val includePotential = args.tBool("includePotential", true)
        val includeExternal = args.tBool("includeExternal", true)
        val deadline = System.nanoTime() + 20_000_000_000L
        val state = GraphState(root, direction, resolution, maxDepth, maxNodes, maxEdges, includePotential, includeExternal, deadline)
        state.addNode(NodeSpec.Local(root), 0, "root")
        val warnings = LinkedHashSet<String>()
        entries.filter { !it.modern }.forEach { warnings.add("modernOpcodesUnavailable:${it.name}") }
        if (direction in listOf("callers", "both")) {
            val index = buildReverseIndex(state)
            state.indexTruncated = index.truncated
            state.indexCancelled = index.cancelled
            state.reflectionObserved = state.reflectionObserved || index.reflectionCount > 0
            state.dynamicUnknown = state.dynamicUnknown || index.dynamicUnknown
            state.ambiguous = state.ambiguous || index.ambiguous
            state.reflectionCount += index.reflectionCount
            state.scannedMethods += index.scannedMethods
            state.scannedEdges += index.scannedEdges
            if (index.reflectionCount > 0) state.addUnresolved("reflection:index", ToolkitIo.json(
                "kind" to "reflection", "reflectionKind" to "index_scan", "count" to index.reflectionCount,
                "reason" to "反向索引在 DEX 全集中观察到反射/间接调用，无法枚举全部潜在调用者",
                "dynamicTarget" to "unknown"))
            if (index.dynamicUnknown) state.addDynamic("dynamic:index", ToolkitIo.json(
                "kind" to "dynamic_index", "reason" to "反向索引观察到 invoke-custom/invoke-polymorphic，潜在目标未知",
                "dynamicTarget" to "unknown"))
            if (index.truncated) { state.truncated = true; state.stopReason = state.stopReason ?: "max_index_edges" }
            if (index.cancelled) { state.cancelled = true; state.truncated = true; state.stopReason = "cancelled" }
            if (direction == "callers") traverseReverse(state, index)
            else if (state.check()) traverseForward(state)
            if (direction == "both" && state.check()) traverseReverse(state, index)
        } else {
            traverseForward(state)
        }
        if (state.depthLimited) warnings.add("depthLimitReached")
        if (!state.cancelled && state.dynamicUnknown) warnings.add("dynamicTargetsWereNotResolved")
        if (state.reflectionObserved) warnings.add("reflectionOrIndirectCallsObserved")
        return graphJson(state, warnings)
    }

    private inner class GraphState(
        val root: MethodRef,
        val direction: String,
        val resolution: String,
        val maxDepth: Int,
        val maxNodes: Int,
        val maxEdges: Int,
        val includePotential: Boolean,
        val includeExternal: Boolean,
        val deadline: Long,
    ) {
        val nodes = LinkedHashMap<String, NodeSpec>()
        val depths = HashMap<String, Int>()
        val reachedBy = HashMap<String, LinkedHashSet<String>>()
        val edges = LinkedHashMap<String, JsonObject>()
        val unresolved = LinkedHashMap<String, JsonObject>()
        val dynamic = LinkedHashMap<String, JsonObject>()
        val methodHandles = LinkedHashMap<String, JsonObject>()
        val methodTypes = LinkedHashMap<String, JsonObject>()
        val invokeCustom = LinkedHashMap<String, JsonObject>()
        val invokePolymorphic = LinkedHashMap<String, JsonObject>()
        val forwardCache = HashMap<String, ScanResult>()
        var truncated = false
        var cancelled = false
        var depthLimited = false
        var dynamicUnknown = false
        var reflectionObserved = false
        var ambiguous = false
        var stopReason: String? = null
        var maxDepthSeen = 0
        var reflectionCount = 0
        var scannedMethods = 0
        var scannedEdges = 0
        var indexTruncated = false
        var indexCancelled = false
        var factsTruncated = false

        fun stop(reason: String) {
            if (stopReason == null) stopReason = reason
        }

        fun check(): Boolean {
            if (cancelled) return false
            if (Thread.currentThread().isInterrupted) {
                cancelled = true; truncated = true; stop("cancelled"); return false
            }
            if (System.nanoTime() > deadline) {
                truncated = true; stop("time_budget"); return false
            }
            if (stopReason != null) return false
            return true
        }

        fun addNode(spec: NodeSpec, depth: Int, via: String): Boolean {
            val id = nodeId(spec)
            val existing = nodes[id]
            if (existing == null) {
                if (nodes.size >= maxNodes) { truncated = true; stop("max_nodes"); return false }
                nodes[id] = spec
                depths[id] = depth
                reachedBy[id] = linkedSetOf(via)
            } else {
                depths[id] = minOf(depths[id] ?: depth, depth)
                reachedBy[id]?.add(via)
            }
            maxDepthSeen = maxOf(maxDepthSeen, depth)
            return true
        }

        fun hasEdgeCapacity(edge: Edge): Boolean = edge.key in edges || edges.size < maxEdges

        fun addEdge(edge: Edge): Boolean {
            val key = edge.key
            if (key in edges) return true
            if (edges.size >= maxEdges) { truncated = true; stop("max_edges"); return false }
            edges[key] = edgeJson(edge)
            return true
        }

        fun addUnresolved(key: String, value: JsonObject) {
            if (unresolved.size >= FACTS_MAX) { factsTruncated = true; return }
            unresolved.putIfAbsent(key, value)
        }

        fun addDynamic(key: String, value: JsonObject) {
            if (dynamic.size >= FACTS_MAX) { factsTruncated = true; return }
            dynamic.putIfAbsent(key, value)
        }

        fun addMethodHandle(key: String, value: JsonObject) {
            if (methodHandles.size >= FACTS_MAX) { factsTruncated = true; return }
            methodHandles.putIfAbsent(key, value)
        }

        fun addMethodType(key: String, value: JsonObject) {
            if (methodTypes.size >= FACTS_MAX) { factsTruncated = true; return }
            methodTypes.putIfAbsent(key, value)
        }

        fun addInvokeCustom(key: String, value: JsonObject) {
            if (invokeCustom.size >= FACTS_MAX) { factsTruncated = true; return }
            invokeCustom.putIfAbsent(key, value)
        }

        fun addInvokePolymorphic(key: String, value: JsonObject) {
            if (invokePolymorphic.size >= FACTS_MAX) { factsTruncated = true; return }
            invokePolymorphic.putIfAbsent(key, value)
        }

        fun consume(result: ScanResult) {
            result.unresolved.forEach { addUnresolved(it.toString(), it) }
            result.dynamic.forEach { addDynamic(it.toString(), it) }
            result.methodHandles.forEach { addMethodHandle(it.toString(), it) }
            result.methodTypes.forEach { addMethodType(it.toString(), it) }
            result.invokeCustom.forEach { addInvokeCustom(it.toString(), it) }
            result.invokePolymorphic.forEach { addInvokePolymorphic(it.toString(), it) }
            scannedMethods++
            scannedEdges += result.edges.size
            reflectionCount += result.reflectionCount
            if (result.reflectionCount > 0) reflectionObserved = true
            if (result.dynamicUnknown) dynamicUnknown = true
            if (result.ambiguous) ambiguous = true
            if (result.truncated) truncated = true
            if (result.cancelled) { cancelled = true; truncated = true; stop("cancelled") }
        }
    }

    private fun traverseForward(state: GraphState) {
        val root = state.root
        val queue = ArrayDeque<Pair<MethodRef, Int>>()
        val visited = HashSet<String>()
        visited.add(root.uniqueLocator)
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            if (!state.check()) break
            val (node, depth) = queue.removeFirst()
            val scan = state.forwardCache.getOrPut(node.uniqueLocator) { scanMethod(node, true, state) }
            state.consume(scan)
            if (!state.check()) break
            if (depth >= state.maxDepth) {
                if (scan.edges.any { state.includePotential || !it.potential }) state.depthLimited = true
                continue
            }
            for (edge in scan.edges) {
                if (!state.includePotential && edge.potential) continue
                val target = edge.to ?: continue
                if (target is NodeSpec.External && !state.includeExternal) {
                    state.addUnresolved("excluded_external:" + edge.key, ToolkitIo.json("kind" to "external_excluded",
                        "from" to node.uniqueLocator, "reference" to edge.reference, "codeUnitOffset" to edge.codeUnitOffset))
                    continue
                }
                if (!state.hasEdgeCapacity(edge)) { state.truncated = true; state.stop("max_edges"); break }
                if (!state.addNode(target, depth + 1, "callees")) break
                if (!state.addEdge(edge)) break
                if (target is NodeSpec.Local && visited.add(target.ref.uniqueLocator)) queue.add(target.ref to depth + 1)
                if (state.resolution != "exact" && edge.virtual && !edge.opcode.contains("super")) {
                    val candidates = overrideCandidates(edge.referenceClass, edge.referenceSlot)
                    if (candidates.size >= 200) state.factsTruncated = true
                    for (candidate in candidates) {
                        if (target is NodeSpec.Local && target.ref.uniqueLocator == candidate.uniqueLocator) continue
                        val projected = edge.copy(to = NodeSpec.Local(candidate), resolution = "dispatch", potential = true)
                        if (!state.includePotential && projected.potential) continue
                        if (!state.hasEdgeCapacity(projected)) { state.truncated = true; state.stop("max_edges"); break }
                        if (!state.addNode(NodeSpec.Local(candidate), depth + 1, "callees")) break
                        if (!state.addEdge(projected)) break
                        if (visited.add(candidate.uniqueLocator)) queue.add(candidate to depth + 1)
                    }
                }
            }
        }
    }

    private fun overrideCandidates(type: String, slot: String): List<MethodRef> {
        val key = "$type|$slot"
        overrideCache[key]?.let { return it }
        val result = ArrayList<MethodRef>()
        val seenTypes = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(type)
        while (queue.isNotEmpty() && result.size < 200 && seenTypes.size < 1_000) {
            val current = queue.removeFirst()
            if (!seenTypes.add(current)) continue
            classIndex[current].orEmpty().forEach { ref ->
                ref.definition.methods.filter { isVirtual(it) && ref.entry.toolkit.methodId(it).substringAfter("->") == slot }
                    .forEach { result.add(MethodRef(ref, it)) }
            }
            childrenByType[current].orEmpty().forEach { queue.add(it) }
        }
        val distinct = result.distinctBy { it.uniqueLocator }
        overrideCache[key] = distinct
        return distinct
    }

    private fun traverseReverse(state: GraphState, index: ReverseIndex) {
        val root = state.root
        val queue = ArrayDeque<Pair<MethodRef, Int>>()
        val visited = HashSet<String>()
        visited.add(root.uniqueLocator)
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            if (!state.check()) break
            val (node, depth) = queue.removeFirst()
            val incoming = incomingEdges(node, index, state.resolution)
            if (depth >= state.maxDepth) {
                if (incoming.isNotEmpty()) state.depthLimited = true
                continue
            }
            for (edge in incoming) {
                val caller = edge.from
                if (!state.hasEdgeCapacity(edge)) { state.truncated = true; state.stop("max_edges"); break }
                if (!state.addNode(NodeSpec.Local(caller), depth + 1, "callers")) break
                if (!state.addEdge(edge)) break
                if (visited.add(caller.uniqueLocator)) queue.add(caller to depth + 1)
            }
        }
    }

    private fun incomingEdges(node: MethodRef, index: ReverseIndex, resolution: String): List<Edge> {
        val result = LinkedHashMap<String, Edge>()
        index.byTarget[node.uniqueLocator]?.forEach { edge -> result[edge.key + "|exact"] = edge }
        if (resolution == "exact" || !isVirtual(node.method)) return result.values.toList()
        val candidates = index.virtualBySlot[node.slot].orEmpty()
        for (edge in candidates.take(10_000)) {
            val exactTarget = (edge.to as? NodeSpec.Local)?.ref?.uniqueLocator
            if (exactTarget == node.uniqueLocator) continue
            val referenceClass = edge.referenceClass
            val slotMatch = isSubtype(referenceClass, node.owner.type)
            val dispatchMatch = resolution == "dispatch" && isSubtype(node.owner.type, referenceClass)
            if (!slotMatch && !dispatchMatch) continue
            val kind = if (slotMatch) "slot" else "dispatch"
            val projected = edge.copy(to = NodeSpec.Local(node), resolution = kind, potential = true)
            result[projected.key + "|" + kind + "|" + node.uniqueLocator] = projected
        }
        return result.values.toList()
    }

    private fun buildReverseIndex(state: GraphState): ReverseIndex {
        val byTarget = HashMap<String, MutableList<Edge>>()
        val virtualBySlot = HashMap<String, MutableList<Edge>>()
        var reflection = 0
        var dynamicUnknown = false
        var ambiguous = false
        var methods = 0
        var edges = 0
        var truncated = false
        var cancelled = false
        outer@ for (entry in entries) for (definition in entry.classes) for (method in definition.methods) {
            if (methods++ >= GRAPH_INDEX_METHOD_MAX) { truncated = true; break@outer }
            if (!state.check()) { cancelled = state.cancelled; truncated = true; break@outer }
            val ref = MethodRef(ClassRef(entry, definition), method)
            val scan = scanMethod(ref, false, state)
            if (scan.cancelled) { cancelled = true; truncated = true; break@outer }
            reflection += scan.reflectionCount
            if (scan.dynamicUnknown) dynamicUnknown = true
            if (scan.ambiguous) ambiguous = true
            for (edge in scan.edges) {
                if (++edges > GRAPH_INDEX_EDGE_MAX) { truncated = true; break@outer }
                if (edge.to is NodeSpec.Local) byTarget.getOrPut((edge.to as NodeSpec.Local).ref.uniqueLocator) { ArrayList() }.add(edge)
                if (edge.virtual) virtualBySlot.getOrPut(edge.referenceSlot) { ArrayList() }.add(edge)
            }
        }
        return ReverseIndex(byTarget.mapValues { it.value.toList() }, virtualBySlot.mapValues { it.value.toList() },
            reflection, dynamicUnknown, ambiguous, methods, edges, truncated, cancelled)
    }

    private fun scanMethod(source: MethodRef, facts: Boolean, state: GraphState): ScanResult {
        if (!state.check()) return ScanResult(truncated = true, cancelled = state.cancelled)
        val edges = ArrayList<Edge>()
        val unresolved = ArrayList<JsonObject>()
        val dynamic = ArrayList<JsonObject>()
        val methodHandles = ArrayList<JsonObject>()
        val methodTypes = ArrayList<JsonObject>()
        val invokeCustom = ArrayList<JsonObject>()
        val invokePolymorphic = ArrayList<JsonObject>()
        var reflection = 0
        var dynamicUnknown = false
        var ambiguous = false
        var truncated = false
        val implementation = source.method.implementation
        if (implementation == null) {
            if (facts) {
                val native = source.method.accessFlags and ACC_NATIVE != 0
                val reason = if (native) "native" else "abstract_or_interface"
                dynamic.add(ToolkitIo.json("kind" to if (native) "native_body" else "abstract_or_interface",
                    "method" to source.uniqueLocator, "reason" to "没有可直接遍历的代码，动态目标未知",
                    "dynamicTarget" to "unknown"))
                dynamicUnknown = true
            }
            return ScanResult(dynamic = dynamic, dynamicUnknown = dynamicUnknown, truncated = truncated)
        }
        val seenReflection = HashSet<String>()
        var offset = 0
        var instructionCount = 0

        fun addUnresolved(item: JsonObject) { unresolved.add(item) }

        fun addCall(reference: MethodReference, opcode: String, codeUnitOffset: Int, kind: String,
                    potential: Boolean, dynamicEdge: Boolean, handleType: Int?, callsite: String?, secondary: String?) {
            if (edges.size >= GRAPH_EDGES_PER_METHOD_MAX) { truncated = true; return }
            val referenceId = methodKey(reference)
            val reflectionKind = reflectionKind(referenceId)
            if (reflectionKind != null) {
                reflection++
                if (facts && seenReflection.add("$codeUnitOffset|$referenceId")) {
                    addUnresolved(ToolkitIo.json("kind" to "reflection", "reflectionKind" to reflectionKind,
                        "from" to source.uniqueLocator, "fromLocator" to source.locator, "api" to referenceId,
                        "codeUnitOffset" to codeUnitOffset, "opcode" to opcode,
                        "reason" to "反射或间接调用目标在静态字节码中不可确定"))
                }
            }
            when (val resolution = resolveMethodReference(reference, source.owner.entry.name)) {
                is MethodResolution.Exact -> edges.add(Edge(source, NodeSpec.Local(resolution.ref), kind, opcode, codeUnitOffset,
                    "exact", potential, dynamicEdge, referenceId, reference.definingClass, slotOf(referenceId), isVirtualCall(opcode),
                    handleType, callsite, secondary))
                is MethodResolution.Inherited -> edges.add(Edge(source, NodeSpec.Local(resolution.ref), kind, opcode, codeUnitOffset,
                    "inherited", potential, dynamicEdge, referenceId, reference.definingClass, slotOf(referenceId), isVirtualCall(opcode),
                    handleType, callsite, secondary))
                is MethodResolution.External -> {
                    if (resolution.reason == "missing_method" && facts) {
                        addUnresolved(ToolkitIo.json("kind" to "unresolved_method", "from" to source.uniqueLocator,
                            "fromLocator" to source.locator, "reference" to referenceId, "codeUnitOffset" to codeUnitOffset,
                            "opcode" to opcode, "reason" to "类在 DEX 中，但方法/可解析父类方法不存在；目标按 external 处理"))
                    }
                    edges.add(Edge(source, NodeSpec.External(reference), kind, opcode, codeUnitOffset, "external",
                        potential, dynamicEdge, referenceId, reference.definingClass, slotOf(referenceId), isVirtualCall(opcode),
                        handleType, callsite, secondary))
                }
                is MethodResolution.Ambiguous -> {
                    ambiguous = true
                    if (facts) addUnresolved(ToolkitIo.json("kind" to "ambiguous_method", "from" to source.uniqueLocator,
                        "fromLocator" to source.locator, "reference" to referenceId, "codeUnitOffset" to codeUnitOffset,
                        "opcode" to opcode, "reason" to "多个 DEX/父类型含同名方法，未伪造唯一边",
                        "candidates" to JsonArray().apply { resolution.candidates.forEach {
                            add(ToolkitIo.json("dexEntry" to it.entry.name, "type" to it.type, "locator" to it.locator,
                                "uniqueLocator" to it.uniqueLocator))
                        } }))
                }
            }
        }

        fun addMethodType(proto: MethodProtoReference, codeUnitOffset: Int, secondary: Boolean) {
            if (!facts) return
            val id = protoKey(proto)
            methodTypes.add(ToolkitIo.json("kind" to "method_type", "proto" to id,
                "parameterTypes" to proto.parameterTypes, "returnType" to proto.returnType,
                "codeUnitOffset" to codeUnitOffset, "secondaryReference" to secondary))
        }

        fun addMethodHandle(handle: MethodHandleReference, codeUnitOffset: Int, callsite: String?) {
            val member = handle.memberReference
            val memberId = when (member) {
                is MethodReference -> methodKey(member)
                is FieldReference -> fieldKey(member)
                else -> member.toString()
            }
            if (facts) methodHandles.add(ToolkitIo.json("kind" to "method_handle",
                "methodHandleType" to handle.methodHandleType, "methodHandleTypeName" to methodHandleTypeName(handle.methodHandleType),
                "member" to memberId, "memberKind" to when (member) { is MethodReference -> "method"; is FieldReference -> "field"; else -> "unknown" },
                "codeUnitOffset" to codeUnitOffset, "callsiteName" to callsite))
            if (member is MethodReference) {
                addCall(member, "const-method-handle", codeUnitOffset, "method_handle_constant", true, false,
                    handle.methodHandleType, callsite, null)
            }
        }

        fun addCallSite(callsite: CallSiteReference, codeUnitOffset: Int) {
            val bootstrap = callsite.methodHandle
            if (facts) invokeCustom.add(ToolkitIo.json("kind" to "invoke_custom", "callsiteName" to callsite.name,
                "methodName" to callsite.methodName, "prototype" to protoKey(callsite.methodProto),
                "bootstrap" to memberId(bootstrap.memberReference), "bootstrapHandleType" to bootstrap.methodHandleType,
                "extraArguments" to encodedValues(callsite.extraArguments), "codeUnitOffset" to codeUnitOffset,
                "dynamicTarget" to "unknown"))
            dynamicUnknown = true
            val member = bootstrap.memberReference
            if (member is MethodReference) {
                addCall(member, "invoke-custom", codeUnitOffset, "invoke_custom_bootstrap", false, false,
                    bootstrap.methodHandleType, callsite.name, null)
            } else if (facts) methodHandles.add(ToolkitIo.json("kind" to "method_handle",
                "methodHandleType" to bootstrap.methodHandleType, "methodHandleTypeName" to methodHandleTypeName(bootstrap.methodHandleType),
                "member" to memberId(member), "memberKind" to "field", "codeUnitOffset" to codeUnitOffset, "callsiteName" to callsite.name))
            val handleCandidates = JsonArray()
            callsite.extraArguments.forEach { value ->
                when (value) {
                    is MethodTypeEncodedValue -> addMethodType(value.value, codeUnitOffset, false)
                    is MethodHandleEncodedValue -> {
                        addMethodHandle(value.value, codeUnitOffset, callsite.name)
                        val memberHandle = value.value.memberReference
                        if (memberHandle is MethodReference) handleCandidates.add(methodKey(memberHandle))
                    }
                    else -> Unit
                }
            }
            if (facts) dynamic.add(ToolkitIo.json("kind" to "invoke_custom", "callsiteName" to callsite.name,
                "methodName" to callsite.methodName, "prototype" to protoKey(callsite.methodProto),
                "bootstrap" to memberId(bootstrap.memberReference), "candidates" to handleCandidates,
                "codeUnitOffset" to codeUnitOffset, "dynamicTarget" to "unknown",
                "reason" to "invoke-custom 的最终 MethodHandle 由 bootstrap 运行时决定"))
        }

        loop@ for (instruction in implementation.instructions) {
            if (((++instructionCount) and 255) == 0) {
                if (!state.check()) { truncated = true; break@loop }
                if (instructionCount > GRAPH_SCAN_INSTRUCTION_MAX) { truncated = true; break@loop }
            }
            if (instruction is ReferenceInstruction) {
                when (val reference = instruction.reference) {
                    is MethodReference -> {
                        if (isInvokeOpcode(instruction.opcode.name)) {
                            when {
                                instruction.opcode.name.startsWith("invoke-custom") -> Unit
                                instruction.opcode.name.startsWith("invoke-polymorphic") -> {
                                    val proto = (instruction as? DualReferenceInstruction)?.reference2 as? MethodProtoReference
                                    if (facts) invokePolymorphic.add(ToolkitIo.json("kind" to "invoke_polymorphic",
                                        "handle" to methodKey(reference), "prototype" to proto?.let { protoKey(it) },
                                        "codeUnitOffset" to offset, "dynamicTarget" to "unknown"))
                                    if (facts) dynamic.add(ToolkitIo.json("kind" to "invoke_polymorphic",
                                        "handle" to methodKey(reference), "prototype" to proto?.let { protoKey(it) },
                                        "codeUnitOffset" to offset, "dynamicTarget" to "unknown",
                                        "reason" to "invoke-polymorphic 的实际目标由 MethodHandle 运行时决定"))
                                    dynamicUnknown = true
                                    addCall(reference, instruction.opcode.name, offset, "invoke_polymorphic_handle", true, true,
                                        null, null, proto?.let { protoKey(it) })
                                    proto?.let { addMethodType(it, offset, true) }
                                }
                                else -> addCall(reference, instruction.opcode.name, offset, "invoke", false, false,
                                    null, null, null)
                            }
                        }
                    }
                    is CallSiteReference -> if (instruction.opcode.name.startsWith("invoke-custom")) addCallSite(reference, offset)
                    is MethodHandleReference -> addMethodHandle(reference, offset, null)
                    is MethodProtoReference -> addMethodType(reference, offset, false)
                    else -> Unit
                }
            }
            if (instruction is DualReferenceInstruction) {
                when (val secondary = instruction.reference2) {
                    is MethodProtoReference -> {
                        if (!instruction.opcode.name.startsWith("invoke-polymorphic")) addMethodType(secondary, offset, true)
                    }
                    is MethodHandleReference -> addMethodHandle(secondary, offset, null)
                    is CallSiteReference -> addCallSite(secondary, offset)
                    else -> Unit
                }
            }
            offset += instruction.codeUnits
        }
        return ScanResult(edges, unresolved, dynamic, methodHandles, methodTypes, invokeCustom, invokePolymorphic,
            reflection, dynamicUnknown, ambiguous, truncated, state.cancelled)
    }

    private fun graphJson(state: GraphState, warnings: Set<String>): JsonObject {
        val nodes = JsonArray()
        state.nodes.forEach { (id, spec) ->
            nodes.add(nodeJson(spec, state.depths[id] ?: 0, state.reachedBy[id].orEmpty()))
        }
        val complete = !state.truncated && !state.cancelled && !state.depthLimited && !state.dynamicUnknown &&
            !state.reflectionObserved && !state.ambiguous && !state.factsTruncated
        val summary = ToolkitIo.json(
            "direction" to state.direction,
            "methodResolution" to state.resolution,
            "nodeCount" to state.nodes.size,
            "edgeCount" to state.edges.size,
            "unresolvedCount" to state.unresolved.size,
            "dynamicCount" to state.dynamic.size,
            "methodHandleCount" to state.methodHandles.size,
            "invokeCustomCount" to state.invokeCustom.size,
            "invokePolymorphicCount" to state.invokePolymorphic.size,
            "potentialEdgeCount" to state.edges.values.count { it.get("potential")?.asBoolean == true },
            "externalNodeCount" to state.nodes.values.count { it is NodeSpec.External },
            "maxDepthReached" to state.maxDepthSeen,
            "depthLimited" to state.depthLimited,
            "truncated" to state.truncated,
            "cancelled" to state.cancelled,
            "stopReason" to state.stopReason,
            "factsTruncated" to state.factsTruncated,
            "traversalComplete" to (!state.truncated && !state.cancelled),
            "complete" to complete,
            "dynamicUnknown" to state.dynamicUnknown,
            "reflectionObserved" to state.reflectionObserved,
            "reflectionCallCount" to state.reflectionCount,
            "ambiguous" to state.ambiguous,
            "universeOnly" to true,
            "stats" to ToolkitIo.json("scannedMethods" to state.scannedMethods, "scannedEdges" to state.scannedEdges,
                "indexTruncated" to state.indexTruncated, "indexCancelled" to state.indexCancelled),
        )
        return ToolkitIo.json(
            "root" to methodJson(state.root),
            "nodes" to nodes,
            "edges" to JsonArray().apply { state.edges.values.forEach { add(it) } },
            "unresolved" to JsonArray().apply { state.unresolved.values.forEach { add(it) } },
            "dynamic" to JsonArray().apply { state.dynamic.values.forEach { add(it) } },
            "methodHandles" to JsonArray().apply { state.methodHandles.values.forEach { add(it) } },
            "methodTypes" to JsonArray().apply { state.methodTypes.values.forEach { add(it) } },
            "invokeCustom" to JsonArray().apply { state.invokeCustom.values.forEach { add(it) } },
            "invokePolymorphic" to JsonArray().apply { state.invokePolymorphic.values.forEach { add(it) } },
            "summary" to summary,
            "limits" to ToolkitIo.json("maxDepth" to state.maxDepth, "maxNodes" to state.maxNodes, "maxEdges" to state.maxEdges,
                "includePotential" to state.includePotential, "includeExternal" to state.includeExternal,
                "indexEdgeMax" to GRAPH_INDEX_EDGE_MAX),
            "warnings" to warnings.toList(),
        )
    }

    // --------------------------------------------------------------- hierarchy

    fun hierarchy(args: JsonObject): JsonObject {
        val parsed = parseLocator(args.tString("locator"), optionalString(args, "dexEntry"))
        val isMethod = parsed.raw.startsWith("dex_method:")
        val targetClass = resolveClassRef(parsed.raw.substringAfter(':').substringBefore("->"), parsed.dexEntry)
        val targetMethod = if (isMethod) {
            val key = parsed.raw.substringAfter(':')
            targetClass.definition.methods.firstOrNull { targetClass.entry.toolkit.methodId(it) == key }
                ?: throw ToolkitException("DEX_METHOD_NOT_FOUND", "DEX 方法不存在：$key", "locator")
        } else null
        val direction = args.tString("direction", "both")
        require(direction in listOf("supertypes", "subtypes", "both")) { "direction 必须为 supertypes/subtypes/both" }
        val resolution = args.tString("methodResolution", if (isMethod) "dispatch" else "not_applicable")
        require(resolution in listOf("exact", "slot", "dispatch", "not_applicable")) { "methodResolution 无效" }
        require(!isMethod || resolution != "not_applicable") { "方法层级需要 exact/slot/dispatch" }
        val includeOverrides = args.tBool("includeOverrides", true)
        val maxDepth = args.tInt("maxDepth", 32, 0, 64)
        val maxNodes = args.tInt("maxNodes", 1_000, 1, 5_000)
        val deadline = System.nanoTime() + 20_000_000_000L
        var truncated = false
        var cancelled = false
        var stopReason: String? = null
        val warnings = LinkedHashSet<String>()
        warnings.add("universeOnly")
        entries.filter { !it.modern }.forEach { warnings.add("modernOpcodesUnavailable:${it.name}") }

        fun check(): Boolean {
            if (Thread.currentThread().isInterrupted) { cancelled = true; truncated = true; stopReason = "cancelled"; return false }
            if (System.nanoTime() > deadline) { truncated = true; stopReason = "time_budget"; return false }
            return true
        }

        val superWalk = if (direction != "subtypes") collectSupertypes(targetClass.type, maxDepth, maxNodes, ::check) else WalkResult(emptyList<ClassVisit>())
        val subWalk = if (direction != "supertypes") collectSubtypes(targetClass.type, maxDepth, maxNodes, ::check) else WalkResult(emptyList<ClassVisit>())
        val supertypes = superWalk.visits
        val subtypes = subWalk.visits
        truncated = truncated || superWalk.truncated || subWalk.truncated
        cancelled = cancelled || superWalk.cancelled || subWalk.cancelled
        var depthLimited = superWalk.depthLimited || subWalk.depthLimited
        val needOverrideWalk = isMethod && includeOverrides
        var allSubtypes = subWalk.visits
        if (needOverrideWalk && direction == "supertypes") {
            val extra = collectSubtypes(targetClass.type, maxDepth, maxNodes, ::check)
            truncated = truncated || extra.truncated
            cancelled = cancelled || extra.cancelled
            depthLimited = depthLimited || extra.depthLimited
            allSubtypes = extra.visits
        }
        var allSupertypes = superWalk.visits
        if (needOverrideWalk && direction == "subtypes") {
            val extra = collectSupertypes(targetClass.type, maxDepth, maxNodes, ::check)
            truncated = truncated || extra.truncated
            cancelled = cancelled || extra.cancelled
            depthLimited = depthLimited || extra.depthLimited
            allSupertypes = extra.visits
        }

        val superJson = JsonArray().apply { supertypes.forEach { add(classVisitJson(it)) } }
        val subJson = JsonArray().apply { subtypes.forEach { add(classVisitJson(it)) } }
        val methodTarget = targetMethod?.let { MethodRef(targetClass, it) }
        val overrides = if (isMethod && targetMethod != null && includeOverrides) {
            overridesJson(methodTarget!!, allSubtypes, allSupertypes, resolution)
        } else null
        if (isMethod && resolution == "exact") {
            // keep the parameter meaningful without changing the three reported lists
            warnings.add("exactMeansOnlyTheRequestedMethod")
        }
        if (!isMethod && resolution != "not_applicable") warnings.add("methodResolutionIgnoredForClass")
        if (depthLimited) warnings.add("depthLimitReached")
        val complete = !truncated && !cancelled && !depthLimited
        return ToolkitIo.json(
            "kind" to if (isMethod) "method" else "class",
            "target" to if (methodTarget != null) methodJson(methodTarget) else classJson(targetClass, includeOccurrences = true),
            "class" to classJson(targetClass, includeOccurrences = true),
            "supertypes" to superJson,
            "subtypes" to subJson,
            "overrides" to overrides,
            "summary" to ToolkitIo.json(
                "direction" to direction,
                "methodResolution" to resolution,
                "supertypeCount" to supertypes.size,
                "subtypeCount" to subtypes.size,
                "exactCount" to (overrides?.getAsJsonArray("exact")?.size() ?: 0),
                "slotCount" to (overrides?.getAsJsonArray("slot")?.size() ?: 0),
                "dispatchCount" to (overrides?.getAsJsonArray("dispatch")?.size() ?: 0),
                "truncated" to truncated,
                "depthLimited" to depthLimited,
                "cancelled" to cancelled,
                "stopReason" to stopReason,
                "complete" to complete,
                "universeOnly" to true,
                "duplicateClassDefinitionCount" to (classIndex[targetClass.type]?.size ?: 1) - 1,
            ),
            "limits" to ToolkitIo.json("maxDepth" to maxDepth, "maxNodes" to maxNodes),
            "warnings" to warnings.toList(),
        )
    }

    private data class ClassVisit(val type: String, val depth: Int, val from: String?, val edgeType: String)

    private data class WalkResult(
        val visits: List<ClassVisit>,
        val truncated: Boolean = false,
        val cancelled: Boolean = false,
        val depthLimited: Boolean = false,
    )

    private fun collectSupertypes(start: String, maxDepth: Int, maxNodes: Int, check: () -> Boolean): WalkResult {
        if (maxDepth <= 0) return WalkResult(emptyList<ClassVisit>())
        val result = ArrayList<ClassVisit>()
        val seen = HashSet<String>(); seen.add(start)
        val queue = ArrayDeque<ClassVisit>()
        val startRefs = classIndex[start].orEmpty()
        startRefs.forEach { ref ->
            ref.definition.superclass?.let { queue.add(ClassVisit(it, 1, start, "superclass")) }
            ref.definition.interfaces.forEach { queue.add(ClassVisit(it, 1, start, "interface")) }
        }
        var truncated = false
        var cancelled = false
        var depthLimited = false
        while (queue.isNotEmpty()) {
            if (!check()) { cancelled = true; truncated = true; break }
            val visit = queue.removeFirst()
            if (!seen.add(visit.type)) continue
            if (result.size >= maxNodes) { truncated = true; break }
            result.add(visit)
            val refs = classIndex[visit.type].orEmpty()
            if (refs.isEmpty()) continue
            if (visit.depth >= maxDepth) {
                if (refs.any { it.definition.superclass != null || it.definition.interfaces.isNotEmpty() }) depthLimited = true
                continue
            }
            refs.forEach { ref ->
                ref.definition.superclass?.let { queue.add(ClassVisit(it, visit.depth + 1, visit.type, "superclass")) }
                ref.definition.interfaces.forEach { queue.add(ClassVisit(it, visit.depth + 1, visit.type, "interface")) }
            }
        }
        return WalkResult(result, truncated, cancelled, depthLimited)
    }

    private fun collectSubtypes(start: String, maxDepth: Int, maxNodes: Int, check: () -> Boolean): WalkResult {
        if (maxDepth <= 0) return WalkResult(emptyList<ClassVisit>())
        val result = ArrayList<ClassVisit>()
        val seen = HashSet<String>(); seen.add(start)
        val queue = ArrayDeque<ClassVisit>()
        childrenByType[start].orEmpty().forEach { queue.add(ClassVisit(it, 1, start, relationOf(start, it))) }
        var truncated = false
        var cancelled = false
        var depthLimited = false
        while (queue.isNotEmpty()) {
            if (!check()) { cancelled = true; truncated = true; break }
            val visit = queue.removeFirst()
            if (!seen.add(visit.type)) continue
            if (result.size >= maxNodes) { truncated = true; break }
            result.add(visit)
            if (visit.depth >= maxDepth) {
                if (childrenByType[visit.type].orEmpty().isNotEmpty()) depthLimited = true
                continue
            }
            childrenByType[visit.type].orEmpty().forEach { child ->
                queue.add(ClassVisit(child, visit.depth + 1, visit.type, relationOf(visit.type, child)))
            }
        }
        return WalkResult(result, truncated, cancelled, depthLimited)
    }

    private fun relationOf(parent: String, child: String): String {
        val refs = classIndex[child].orEmpty()
        return if (refs.any { it.definition.superclass == parent }) "superclass" else "interface"
    }

    private fun classVisitJson(visit: ClassVisit): JsonObject {
        val ref = preferredClassRef(visit.type, null)
        return if (ref == null) ToolkitIo.json("type" to visit.type, "external" to true, "dexEntry" to null,
            "locator" to "dex_class:${visit.type}", "uniqueLocator" to "external:${visit.type}",
            "depth" to visit.depth, "distance" to visit.depth, "from" to visit.from, "edgeType" to visit.edgeType)
        else classJson(ref, includeOccurrences = true).apply {
            addProperty("depth", visit.depth)
            addProperty("distance", visit.depth)
            addProperty("from", visit.from)
            addProperty("edgeType", visit.edgeType)
        }
    }

    private fun overridesJson(target: MethodRef, subtypes: List<ClassVisit>, supertypes: List<ClassVisit>, resolution: String): JsonObject {
        val exact = ArrayList<JsonObject>()
        val slot = ArrayList<JsonObject>()
        val dispatch = ArrayList<JsonObject>()
        val virtual = isVirtual(target.method)
        val targetSlot = target.slot
        classIndex[target.owner.type].orEmpty().forEach { ref ->
            ref.definition.methods.firstOrNull { ref.entry.toolkit.methodId(it) == target.methodId }?.let {
                exact.add(overrideJson(MethodRef(ref, it), "same", 0))
            }
        }
        if (virtual) {
            subtypes.forEach { visit ->
                val distance = visit.depth
                classIndex[visit.type].orEmpty().forEach { ref ->
                    ref.definition.methods.filter { isVirtual(it) && ref.entry.toolkit.methodId(it).substringAfter("->") == targetSlot }
                        .forEach { slot.add(overrideJson(MethodRef(ref, it), "subtype", distance)) }
                }
            }
            supertypes.forEach { visit ->
                val distance = visit.depth
                classIndex[visit.type].orEmpty().forEach { ref ->
                    ref.definition.methods.filter { isVirtual(it) && ref.entry.toolkit.methodId(it).substringAfter("->") == targetSlot }
                        .forEach { dispatch.add(overrideJson(MethodRef(ref, it), "supertype", distance)) }
                }
            }
            // dispatch = slot overrides + supertype declarations; exact is kept separately.
            dispatch.addAll(0, slot)
        }
        val exactDistinct = distinctOverrides(exact)
        val slotDistinct = distinctOverrides(slot)
        val dispatchDistinct = distinctOverrides(dispatch)
        val primary = when (resolution) {
            "exact" -> exactDistinct
            "slot" -> distinctOverrides(exact + slot)
            else -> distinctOverrides(exact + dispatch)
        }
        return ToolkitIo.json(
            "targetMethod" to methodJson(target),
            "virtual" to virtual,
            "slotDescriptor" to targetSlot,
            "exact" to JsonArray().apply { exactDistinct.forEach { add(it) } },
            "slot" to JsonArray().apply { slotDistinct.forEach { add(it) } },
            "dispatch" to JsonArray().apply { dispatchDistinct.forEach { add(it) } },
            "primary" to JsonArray().apply { primary.forEach { add(it) } },
        )
    }

    private fun distinctOverrides(items: List<JsonObject>): List<JsonObject> {
        val seen = LinkedHashMap<String, JsonObject>()
        items.forEach { item ->
            val key = item.get("uniqueLocator")?.asString ?: item.toString()
            if (!seen.containsKey(key)) seen[key] = item
        }
        return seen.values.toList()
    }

    private fun overrideJson(ref: MethodRef, relation: String, distance: Int): JsonObject = methodJson(ref).apply {
        addProperty("relation", relation)
        addProperty("distance", distance)
        addProperty("declaringClass", ref.owner.type)
    }

    // --------------------------------------------------------------- helpers

    private fun parseLocator(locator: String, explicitEntry: String?): ParsedLocator {
        var entry = explicitEntry
        var raw = locator
        if (locator.startsWith("dex_entry:")) {
            val separator = locator.indexOf('!')
            require(separator > "dex_entry:".length) { "dex_entry 定位符缺少 ! 分隔符" }
            val embedded = locator.substring("dex_entry:".length, separator)
            require(embedded.isNotEmpty()) { "dex_entry 定位符中的 DEX 条目为空" }
            require(entry == null || entry == embedded) { "dexEntry 参数与定位符中的 DEX 条目不匹配" }
            entry = embedded
            raw = locator.substring(separator + 1)
        }
        require(raw.startsWith("dex_class:") || raw.startsWith("dex_method:")) { "需要 dex_class: 或 dex_method: 定位符" }
        entry?.let { require(entriesByName.containsKey(it)) { "DEX 条目不存在：$it" } }
        return ParsedLocator(raw, entry)
    }

    private fun resolveClassRef(type: String, dexEntry: String?): ClassRef {
        val occurrences = classIndex[type].orEmpty()
        if (occurrences.isEmpty()) throw ToolkitException("DEX_CLASS_NOT_FOUND", "DEX 类不存在：$type", "locator")
        if (dexEntry != null) return occurrences.firstOrNull { it.entry.name == dexEntry }
            ?: throw ToolkitException("DEX_CLASS_NOT_FOUND", "DEX 条目 $dexEntry 不含类 $type", "dexEntry")
        if (occurrences.size == 1) return occurrences.single()
        throw ToolkitException("DEX_AMBIGUOUS_LOCATOR",
            "多个 DEX 含同名类 $type（${occurrences.joinToString { it.entry.name }}），请用 dexEntry 或 dex_entry:<entry>!<locator> 指定",
            "dexEntry")
    }

    private fun resolveMethodTarget(parsed: ParsedLocator): MethodRef {
        val key = parsed.raw.substringAfter(':')
        val type = key.substringBefore("->")
        val classRef = resolveClassRef(type, parsed.dexEntry)
        val method = classRef.definition.methods.firstOrNull { classRef.entry.toolkit.methodId(it) == key }
            ?: throw ToolkitException("DEX_METHOD_NOT_FOUND", "DEX 方法不存在：$key", "locator")
        return MethodRef(classRef, method)
    }

    private fun preferredClassRef(type: String, preferredDex: String?): ClassRef? {
        val occurrences = classIndex[type].orEmpty()
        if (occurrences.isEmpty()) return null
        if (preferredDex != null) occurrences.firstOrNull { it.entry.name == preferredDex }?.let { return it }
        return occurrences.first()
    }

    private fun resolveMethodReference(reference: MethodReference, sourceDex: String): MethodResolution {
        val occurrences = classIndex[reference.definingClass].orEmpty()
        if (occurrences.isEmpty()) return MethodResolution.External(reference, "external_class")
        val chosen = occurrences.firstOrNull { it.entry.name == sourceDex } ?: occurrences.singleOrNull()
            ?: return MethodResolution.Ambiguous(occurrences)
        val key = methodKey(reference)
        val exact = chosen.definition.methods.firstOrNull { chosen.entry.toolkit.methodId(it) == key }
        if (exact != null) return MethodResolution.Exact(MethodRef(chosen, exact))
        val inherited = findInheritedMethod(chosen, key)
        return when {
            inherited.size == 1 -> MethodResolution.Inherited(inherited.single())
            inherited.size > 1 -> MethodResolution.Ambiguous(inherited.map { it.owner }.distinctBy { it.uniqueLocator })
            else -> MethodResolution.External(reference, "missing_method")
        }
    }

    private fun findInheritedMethod(start: ClassRef, key: String): List<MethodRef> {
        val result = ArrayList<MethodRef>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<ClassRef>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current.uniqueLocator)) continue
            val parentTypes = ArrayList<String>()
            current.definition.superclass?.let { parentTypes.add(it) }
            parentTypes.addAll(current.definition.interfaces)
            for (parent in parentTypes) {
                val refs = classIndex[parent].orEmpty()
                if (refs.isEmpty()) continue
                refs.forEach { ref ->
                    ref.definition.methods.firstOrNull { ref.entry.toolkit.methodId(it) == key }?.let { result.add(MethodRef(ref, it)) }
                    queue.add(ref)
                }
            }
        }
        return result.distinctBy { it.uniqueLocator }
    }

    private fun isSubtype(child: String, parent: String): Boolean {
        if (child == parent) return true
        val key = "$child->$parent"
        subtypeCache[key]?.let { return it }
        val visiting = HashSet<String>()
        fun walk(current: String): Boolean {
            if (current == parent) return true
            if (!visiting.add(current)) return false
            return parentsByType[current].orEmpty().any { walk(it) }
        }
        val value = walk(child)
        subtypeCache[key] = value
        return value
    }

    private fun methodJson(ref: MethodRef): JsonObject = ToolkitIo.json(
        "kind" to "method",
        "locator" to ref.locator,
        "memberLocator" to ref.locator,
        "uniqueLocator" to ref.uniqueLocator,
        "dexEntry" to ref.owner.entry.name,
        "definingClass" to ref.owner.type,
        "name" to ref.method.name,
        "parameters" to ref.method.parameterTypes,
        "returns" to ref.method.returnType,
        "accessFlags" to ref.method.accessFlags,
        "virtual" to isVirtual(ref.method),
        "abstract" to (ref.method.accessFlags and ACC_ABSTRACT != 0),
        "native" to (ref.method.accessFlags and ACC_NATIVE != 0),
        "hasCode" to (ref.method.implementation != null),
    )

    private fun classJson(ref: ClassRef, includeOccurrences: Boolean): JsonObject {
        val json = ToolkitIo.json(
            "kind" to "class",
            "locator" to ref.locator,
            "uniqueLocator" to ref.uniqueLocator,
            "dexEntry" to ref.entry.name,
            "type" to ref.type,
            "superclass" to ref.definition.superclass,
            "interfaces" to ref.definition.interfaces,
            "accessFlags" to ref.definition.accessFlags,
            "abstract" to (ref.definition.accessFlags and ACC_ABSTRACT != 0),
            "interface" to (ref.definition.accessFlags and ACC_INTERFACE != 0),
            "external" to false,
        )
        if (includeOccurrences) {
            val occurrences = classIndex[ref.type].orEmpty()
            if (occurrences.size > 1) {
                json.add("occurrences", JsonArray().apply { occurrences.forEach {
                    add(ToolkitIo.json("dexEntry" to it.entry.name, "uniqueLocator" to it.uniqueLocator))
                } })
            }
        }
        return json
    }

    private fun nodeId(spec: NodeSpec): String = when (spec) {
        is NodeSpec.Local -> spec.ref.uniqueLocator
        is NodeSpec.External -> "external:" + methodKey(spec.reference)
    }

    private fun nodeJson(spec: NodeSpec, depth: Int, reachedBy: Set<String>): JsonObject {
        val json = when (spec) {
            is NodeSpec.Local -> methodJson(spec.ref)
            is NodeSpec.External -> ToolkitIo.json(
                "kind" to "method", "locator" to "dex_method:${methodKey(spec.reference)}",
                "uniqueLocator" to "external:${methodKey(spec.reference)}", "dexEntry" to null,
                "external" to true, "definingClass" to spec.reference.definingClass,
                "name" to spec.reference.name, "parameters" to spec.reference.parameterTypes,
                "returns" to spec.reference.returnType, "accessFlags" to 0,
                "virtual" to false, "abstract" to false, "native" to false, "hasCode" to false,
            )
        }
        json.addProperty("depth", depth)
        json.addProperty("distance", depth)
        json.add("reachedBy", JsonArray().apply { reachedBy.forEach { add(it) } })
        return json
    }

    private fun edgeJson(edge: Edge): JsonObject {
        val json = ToolkitIo.json(
            "from" to edge.from.uniqueLocator,
            "fromLocator" to edge.from.locator,
            "fromDexEntry" to edge.from.owner.entry.name,
            "kind" to edge.kind,
            "opcode" to edge.opcode,
            "codeUnitOffset" to edge.codeUnitOffset,
            "resolution" to edge.resolution,
            "potential" to edge.potential,
            "dynamic" to edge.dynamic,
            "reference" to "dex_method:${edge.reference}",
            "referenceDescriptor" to edge.reference,
            "virtual" to edge.virtual,
        )
        when (val target = edge.to) {
            is NodeSpec.Local -> {
                json.addProperty("to", target.ref.uniqueLocator)
                json.addProperty("toLocator", target.ref.locator)
                json.addProperty("toDexEntry", target.ref.owner.entry.name)
                json.addProperty("toKind", "local")
            }
            is NodeSpec.External -> {
                json.addProperty("to", "external:" + methodKey(target.reference))
                json.addProperty("toLocator", "dex_method:" + methodKey(target.reference))
                json.add("toDexEntry", JsonNull.INSTANCE)
                json.addProperty("toKind", "external")
            }
            null -> {
                json.add("to", JsonNull.INSTANCE)
                json.add("toLocator", JsonNull.INSTANCE)
                json.add("toDexEntry", JsonNull.INSTANCE)
                json.addProperty("toKind", "unresolved")
            }
        }
        edge.methodHandleType?.let {
            json.addProperty("methodHandleType", it)
            json.addProperty("methodHandleTypeName", methodHandleTypeName(it))
        }
        edge.callsiteName?.let { json.addProperty("callsiteName", it) }
        edge.secondary?.let { json.addProperty("secondaryReference", it) }
        return json
    }

    private fun methodKey(reference: MethodReference): String =
        reference.definingClass + "->" + reference.name + "(" + reference.parameterTypes.joinToString("") + ")" + reference.returnType

    private fun fieldKey(reference: FieldReference): String =
        reference.definingClass + "->" + reference.name + ":" + reference.type

    private fun protoKey(proto: MethodProtoReference): String =
        "(" + proto.parameterTypes.joinToString("") + ")" + proto.returnType

    private fun slotOf(methodId: String): String = methodId.substringAfter("->")

    private fun memberId(reference: Reference): String = when (reference) {
        is MethodReference -> methodKey(reference)
        is FieldReference -> fieldKey(reference)
        else -> reference.toString()
    }

    private fun methodHandleTypeName(type: Int): String = HANDLE_TYPES.getOrElse(type) { "unknown_$type" }

    private fun resolutionName(resolution: MethodResolution): String = when (resolution) {
        is MethodResolution.Exact -> "exact"
        is MethodResolution.Inherited -> "inherited"
        is MethodResolution.External -> resolution.reason
        is MethodResolution.Ambiguous -> "ambiguous"
    }

    private fun resolvedLocator(resolution: MethodResolution): String? = when (resolution) {
        is MethodResolution.Exact -> resolution.ref.uniqueLocator
        is MethodResolution.Inherited -> resolution.ref.uniqueLocator
        else -> null
    }

    private fun isVirtual(method: Method): Boolean =
        method.accessFlags and (ACC_PRIVATE or ACC_STATIC) == 0 && method.name !in listOf("<init>", "<clinit>")

    private fun isInvokeOpcode(name: String): Boolean = name.startsWith("invoke-")

    private fun isVirtualCall(name: String): Boolean =
        name.contains("virtual") || name.contains("interface") || name.contains("super")

    private fun reflectionKind(methodId: String): String? {
        val owner = methodId.substringBefore("->")
        val name = methodId.substringAfter("->").substringBefore('(')
        return when {
            owner == "Ljava/lang/Class;" && name in CLASS_LOOKUPS -> "class_lookup"
            owner == "Ljava/lang/reflect/Method;" && name == "invoke" -> "method_invoke"
            owner == "Ljava/lang/reflect/Constructor;" && name == "newInstance" -> "constructor_invoke"
            owner == "Ljava/lang/reflect/Field;" && (name.startsWith("get") || name.startsWith("set")) -> "field_access"
            owner == "Ljava/lang/invoke/MethodHandle;" && name in setOf("invoke", "invokeExact", "invokeWithArguments") -> "handle_invoke"
            owner == "Ljava/lang/invoke/MethodHandles\$Lookup;" && name.startsWith("find") -> "handle_lookup"
            owner == "Ljava/lang/reflect/Proxy;" && name == "newProxyInstance" -> "dynamic_proxy"
            (owner == "Ljava/lang/ClassLoader;" || owner.endsWith("ClassLoader;")) && name in CLASS_LOADER_METHODS -> "class_loader"
            else -> null
        }
    }

    private fun optionalString(args: JsonObject, key: String): String? {
        val value = args.get(key) ?: return null
        if (value.isJsonNull) return null
        return args.tString(key).takeIf { it.isNotEmpty() }
    }

    private fun encodedValues(values: List<out EncodedValue>): JsonArray = JsonArray().apply {
        values.take(20).forEach { add(encodedValue(it)) }
    }

    private fun encodedValue(value: EncodedValue): JsonElement = when (value) {
        is StringEncodedValue -> JsonPrimitive(value.value)
        is IntEncodedValue -> JsonPrimitive(value.value)
        is LongEncodedValue -> JsonPrimitive(value.value)
        is FloatEncodedValue -> JsonPrimitive(value.value)
        is DoubleEncodedValue -> JsonPrimitive(value.value)
        is BooleanEncodedValue -> JsonPrimitive(value.value)
        is ByteEncodedValue -> JsonPrimitive(value.value)
        is CharEncodedValue -> JsonPrimitive(value.value)
        is ShortEncodedValue -> JsonPrimitive(value.value)
        is NullEncodedValue -> JsonNull.INSTANCE
        is MethodTypeEncodedValue -> ToolkitIo.json("kind" to "method_type", "proto" to protoKey(value.value))
        is MethodHandleEncodedValue -> ToolkitIo.json("kind" to "method_handle",
            "methodHandleType" to value.value.methodHandleType, "member" to memberId(value.value.memberReference))
        is FieldEncodedValue -> ToolkitIo.json("kind" to "field", "field" to fieldKey(value.value))
        is EnumEncodedValue -> ToolkitIo.json("kind" to "enum", "field" to fieldKey(value.value))
        is TypeEncodedValue -> ToolkitIo.json("kind" to "type", "type" to value.value)
        is ArrayEncodedValue -> JsonArray().apply { value.value.take(20).forEach { add(encodedValue(it)) } }
        is AnnotationEncodedValue -> ToolkitIo.json("kind" to "annotation", "type" to value.type,
            "elements" to JsonArray().apply { value.elements.take(20).forEach {
                add(ToolkitIo.json("name" to it.name, "value" to encodedValue(it.value)))
            } })
        else -> ToolkitIo.json("kind" to "encoded_value", "valueType" to value.valueType)
    }
}
