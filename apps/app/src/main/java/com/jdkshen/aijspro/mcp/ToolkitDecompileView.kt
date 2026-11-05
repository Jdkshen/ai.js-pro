package com.jdkshen.aijspro.mcp

import com.google.gson.*

/**
 * 反编译结果投影（P0/P1 §8）：纯 JVM、无 Android 依赖、不执行任何工具。
 *
 * 目的：
 * - 默认不返回注解（r2ghidra 的注解会把几 KB 伪代码放大到上百 KB），只给 code/warnings/evidence
 *   与明确体积字段；需要时显式 includeAnnotations=true；
 * - 明确函数边界来源（user_specified/symbol_range/cfg/auto_candidate）与置信度，避免把
 *   「地址显示为 JNI_OnLoad+偏移」直接当作属于该函数；
 * - 交叉引用按证据类型分级：直接 call/jump、重定位指针、静态数据引用、推测间接、未覆盖动态；
 *   任何情况下都明确「没有直接引用 ≠ 没有调用者」。
 *
 * 该对象只做数据整形/分级，不读文件、不启动进程；由主链路（如 AndroidReverseRuntime）在拿到
 * r2ghidra/radare2 的原始 JSON 后调用。
 */
internal object ToolkitDecompileView {
    const val PROJECTION_VERSION = 1
    const val DEFAULT_MAX_CODE_CHARS = 131072
    const val MAX_CODE_CHARS_LIMIT = 4 * 1024 * 1024

    /** projectSegments 的段数硬上限（超出夹取，不抛异常）。 */
    const val MAX_SEGMENT_COUNT = 64

    /** 伪代码体量超过该值时，summarizeBigFunction 建议改用 projectSegments 分段阅读。 */
    const val SEGMENT_RECOMMENDED_CHARS = 16 * 1024

    /** 一条反编译原始结果（来自 r2ghidra/其它后端的最小公共形状）。 */
    data class RawDecompile(
        val code: String,
        val annotations: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val coverage: String? = null,
        val functionAddress: String? = null,
        val functionSize: Int? = null,
        val detectedSize: Int? = null,
        val symbolName: String? = null,
    )

    private const val NO_DIRECT_XREF_NOTE =
        "没有直接引用不等于没有调用者：间接调用、函数指针、重定位指针、静态数据引用以及未覆盖的动态调用都可能存在调用关系"

    /**
     * 投影反编译结果。默认剔除注解并报告 annotationsOmitted/annotationBytes；
     * bytes/charCount/truncated 都明确给出（bytes/charCount 以实际返回的 code 计）。
     */
    fun project(
        raw: RawDecompile,
        includeAnnotations: Boolean = false,
        maxCodeChars: Int = DEFAULT_MAX_CODE_CHARS,
    ): JsonObject {
        require(maxCodeChars >= 0) { "maxCodeChars 不能为负数" }
        require(maxCodeChars <= MAX_CODE_CHARS_LIMIT) { "maxCodeChars 不能超过 " + MAX_CODE_CHARS_LIMIT }

        val code = raw.code
        val end = safeEnd(code, maxCodeChars)
        val visible = code.substring(0, end)
        val truncated = end < code.length
        var annotationBytes = 0
        for (annotation in raw.annotations) annotationBytes += annotation.toByteArray(Charsets.UTF_8).size
        val annotationsOmitted = !includeAnnotations

        val warnings = ArrayList<String>()
        warnings.addAll(raw.warnings)
        if (annotationsOmitted && raw.annotations.isNotEmpty()) {
            warnings.add(
                "已省略 " + raw.annotations.size + " 条注解（" + annotationBytes +
                    " 字节）；需要时传 includeAnnotations=true",
            )
        }

        val annotationsJson: JsonElement = if (includeAnnotations) {
            JsonArray().apply { raw.annotations.forEach { add(it) } }
        } else {
            JsonNull.INSTANCE
        }
        return ToolkitIo.json(
            "projectionVersion" to PROJECTION_VERSION,
            "code" to visible,
            "bytes" to visible.toByteArray(Charsets.UTF_8).size,
            "charCount" to visible.length,
            "originalBytes" to code.toByteArray(Charsets.UTF_8).size,
            "originalCharCount" to code.length,
            "truncated" to truncated,
            "truncatedAtChar" to (if (truncated) end else null),
            "maxCodeChars" to maxCodeChars,
            "annotationsIncluded" to includeAnnotations,
            "annotationsOmitted" to annotationsOmitted,
            "annotationCount" to raw.annotations.size,
            "annotationBytes" to (if (annotationsOmitted) annotationBytes else 0),
            "annotations" to annotationsJson,
            "warnings" to JsonArray().apply { warnings.forEach { add(it) } },
            "coverage" to raw.coverage,
            "functionAddress" to raw.functionAddress,
            "functionSize" to raw.functionSize,
            "detectedSize" to raw.detectedSize,
            "symbolName" to raw.symbolName,
        )
    }

    /**
     * 函数边界来源分级。优先级（按任务约定）：user_specified > symbol_range > cfg > auto_candidate。
     *
     * 注意：symbol_range 只代表符号声明范围，地址显示成「JNI_OnLoad+偏移」不能证明指令属于
     * JNI_OnLoad；note 会写清依据，调用方不能把它当成已确认的函数归属。
     */
    fun boundary(
        forceFunction: Boolean,
        forcedSize: Int?,
        cfgSize: Int?,
        symbolRange: Int?,
        detectedSize: Int?,
    ): JsonObject {
        val invalid = ArrayList<String>()
        fun normalize(name: String, value: Int?): Int? {
            if (value == null) return null
            if (value <= 0) {
                invalid.add(name + "=" + value + " 无效，已忽略（必须为正整数）")
                return null
            }
            return value
        }

        val forced = normalize("forcedSize", forcedSize)
        val cfg = normalize("cfgSize", cfgSize)
        val symbol = normalize("symbolRange", symbolRange)
        val detected = normalize("detectedSize", detectedSize)

        val source: String
        val confidence: String
        val size: Int?
        val sizeSource: String
        val ownership: String
        var note: String
        if (forceFunction || forced != null) {
            source = "user_specified"
            confidence = "high"
            ownership = "yes_explicit"
            size = forced ?: cfg ?: symbol ?: detected
            sizeSource = when {
                forced != null -> "user_specified"
                cfg != null -> "cfg"
                symbol != null -> "symbol_range"
                detected != null -> "auto_candidate"
                else -> "none"
            }
            note = if (forced != null) {
                "函数边界由调用方显式指定（forceFunction/forcedSize）；显式指定不等于已证明覆盖完整函数，仍需结合 CFG/实测确认"
            } else {
                "调用方显式指定了函数起点（forceFunction=true）但未指定长度；长度回退采用 " + sizeSource +
                    " 来源，不能因此认为边界已被证明"
            }
        } else if (symbol != null) {
            source = "symbol_range"
            confidence = "low"
            ownership = "unproven_symbol_range"
            size = symbol
            sizeSource = "symbol_range"
            note = "函数边界仅来自符号声明范围（symbolRange=" + symbol +
                "）；地址显示为 JNI_OnLoad+偏移 之类的形式只说明地址落在该符号声明范围内，" +
                "不能证明这条指令属于 JNI_OnLoad 函数，也不能证明覆盖了完整函数；需要 CFG/实测确认后再使用"
        } else if (cfg != null) {
            source = "cfg"
            confidence = "medium"
            ownership = "probably_cfg"
            size = cfg
            sizeSource = "cfg"
            note = "函数边界来自 CFG（控制流图）可达的基本块范围；不是调用方显式指定，" +
                "仍可能包含共享尾部/异常块或遗漏不可达块"
        } else if (detected != null) {
            source = "auto_candidate"
            confidence = "low"
            ownership = "unproven_heuristic"
            size = detected
            sizeSource = "auto_candidate"
            note = "函数边界来自自动识别的候选范围（detectedSize），未经过 CFG/实测验证，只能当作候选"
        } else {
            source = "auto_candidate"
            confidence = "none"
            ownership = "unknown"
            size = null
            sizeSource = "none"
            note = "没有可用的函数边界信息；地址归属与函数长度均未确认，" +
                "不能把符号相对地址（如 JNI_OnLoad+偏移）当作函数归属证据"
        }

        return ToolkitIo.json(
            "source" to source,
            "confidence" to confidence,
            "functionSize" to size,
            "sizeSource" to sizeSource,
            "addressOwnership" to ownership,
            "requestedStartForced" to forceFunction,
            "evidence" to ToolkitIo.json(
                "forcedSize" to forcedSize,
                "cfgSize" to cfgSize,
                "symbolRange" to symbolRange,
                "detectedSize" to detectedSize,
                "normalizedForcedSize" to forced,
                "normalizedCfgSize" to cfg,
                "normalizedSymbolRange" to symbol,
                "normalizedDetectedSize" to detected,
                "invalidInputs" to JsonArray().apply { invalid.forEach { add(it) } },
            ),
            "note" to note,
        )
    }

    /**
     * 交叉引用分类。覆盖 direct_call/direct_jump/relocation_pointer/static_data_reference/
     * inferred_indirect_reference/uncovered_dynamic_reference；未知输入返回 unknown（原值由
     * [xrefEvidence] 的 rawKind 保留）。
     */
    fun classifyXref(rawKind: String): String {
        val normalized = rawKind.trim().lowercase().replace('-', '_').replace(' ', '_')
            .replace(Regex("_+"), "_")
        return when (normalized) {
            "direct_call", "directcall", "call", "call_direct", "xcall" -> "direct_call"
            "direct_jump", "directjump", "jump", "jmp", "jump_direct", "code", "code_ref",
            "flow", "code_flow" -> "direct_jump"
            "relocation_pointer", "relocationpointer", "relocation", "reloc", "reloc_pointer",
            "pointer_relocation", "relocation_ref", "ptr", "pointer" -> "relocation_pointer"
            "static_data_reference", "staticdatareference", "static_data", "data_reference",
            "static_reference", "data_ref", "data", "string", "strn", "strings" -> "static_data_reference"
            "inferred_indirect_reference", "inferredindirectreference", "inferred_indirect",
            "indirect_reference", "indirect", "inferred" -> "inferred_indirect_reference"
            "uncovered_dynamic_reference", "uncovereddynamicreference", "uncovered_dynamic",
            "dynamic_reference", "dynamic", "uncovered" -> "uncovered_dynamic_reference"
            else -> "unknown"
        }
    }

    /**
     * 交叉引用证据分级。输出 evidenceKind/confidence/notes，并显式声明
     * 「没有直接引用 ≠ 没有调用者」；不会把静态证据当成调用者覆盖完整。
     */
    fun xrefEvidence(
        kind: String,
        hasRelocation: Boolean,
        staticallyResolvable: Boolean,
        dynamicCovered: Boolean,
    ): JsonObject {
        val evidenceKind = classifyXref(kind)
        val directReference = evidenceKind == "direct_call" || evidenceKind == "direct_jump"
        val confidence: String
        val notes = ArrayList<String>()
        when (evidenceKind) {
            "direct_call" -> {
                confidence = "high"
                notes.add("直接 call 指令：静态证据已确认该引用指向目标。")
            }
            "direct_jump" -> {
                confidence = "high"
                notes.add("直接 jump 引用：可能是尾调用或同函数内跳转，需结合 CFG 判断是否为调用关系。")
            }
            "relocation_pointer" -> {
                confidence = if (hasRelocation) "medium" else "low"
                notes.add(
                    if (hasRelocation) {
                        "存在重定位指针指向目标；指针引用可证明「有指针指向」，但不能证明它被当作函数调用。"
                    } else {
                        "引用被归类为重定位指针，但没有 hasRelocation 证据；置信度下调。"
                    },
                )
                if (!staticallyResolvable) notes.add("该重定位指针尚未被静态解析，目标可能仍需运行期计算。")
            }
            "static_data_reference" -> {
                confidence = if (staticallyResolvable) "low" else "none"
                notes.add("静态数据引用不等于调用：数据表/字符串/函数指针槽位都可能引用目标，不能据此判定调用关系。")
                if (!staticallyResolvable) notes.add("该静态数据引用当前不可静态解析。")
            }
            "inferred_indirect_reference" -> {
                confidence = "low"
                notes.add("间接引用为静态推断结果，可能是函数指针、数据结构或非调用用途；需要动态确认。")
            }
            "uncovered_dynamic_reference" -> {
                confidence = "none"
                notes.add("动态覆盖未完成：该引用类型没有被当前证据覆盖，不能据此得出调用者是否存在。")
            }
            else -> {
                confidence = "unknown"
                notes.add("未知引用类型（原值保留在 rawKind）；不能据此判断调用关系。")
            }
        }
        if (dynamicCovered) {
            notes.add("本轮动态覆盖已执行，但仍不能保证覆盖全部运行路径。")
        } else {
            notes.add("本轮没有可用的动态覆盖证据：静态清单之外仍可能存在间接/动态调用。")
        }
        notes.add(NO_DIRECT_XREF_NOTE)
        return ToolkitIo.json(
            "rawKind" to kind,
            "evidenceKind" to evidenceKind,
            "confidence" to confidence,
            "directReference" to directReference,
            "hasRelocation" to hasRelocation,
            "staticallyResolvable" to staticallyResolvable,
            "dynamicCovered" to dynamicCovered,
            "callerAbsenceConclusive" to false,
            "noDirectReferenceConclusion" to if (directReference) "not_applicable" else "cannot_conclude_absent",
            "notes" to JsonArray().apply { notes.forEach { add(it) } },
        )
    }

    /**
     * 大函数伪代码的有界分段投影（为 vmInterpret 这类 82,684 B 函数准备）。
     *
     * 按行切分 pdgText，每段总字符数不超过 maxCodeChars；段数不超过 maxSegments（夹取到 1..64）。
     * 单行/单段超过 maxCodeChars 时只在该段内截断并标 truncated=true，然后停止（剩余用 nextOffset 续读）；
     * 续读时把 nextOffset 作为 offset 再次调用（默认 0）：段、行号都是相对原 pdgText，保持连续。
     *
     * 输出证据等级固定为 static_inferred（伪代码是静态推断，不是运行时确认）；pdg 的 relocAware=true。
     * 本视图只投影伪代码文本，不包含 r2ghidra 注解（omittedAnnotations=true）。
     */
    fun projectSegments(
        pdgText: String,
        address: String,
        maxCodeChars: Int = DEFAULT_MAX_CODE_CHARS,
        maxSegments: Int = 8,
        offset: Int = 0,
    ): JsonObject {
        val charBudget = maxCodeChars.coerceIn(1, MAX_CODE_CHARS_LIMIT)
        val segmentBudget = maxSegments.coerceIn(1, MAX_SEGMENT_COUNT)
        val start = offset.coerceIn(0, pdgText.length)

        // 行边界与行号前缀；行号 1-based。lineRanges 的 end 含行尾换行符（若有）。
        val newlinePrefix = IntArray(pdgText.length + 1)
        for (i in pdgText.indices) {
            newlinePrefix[i + 1] = newlinePrefix[i] + (if (pdgText[i] == '\n') 1 else 0)
        }
        val lineRanges = ArrayList<Pair<Int, Int>>()
        var linePos = 0
        while (linePos < pdgText.length) {
            val newline = pdgText.indexOf('\n', linePos)
            val end = if (newline < 0) pdgText.length else newline + 1
            lineRanges.add(linePos to end)
            linePos = end
        }

        val segments = JsonArray()
        val notes = ArrayList<String>()
        notes.add("伪代码是静态推断（evidenceLevel=static_inferred），不是运行时确认。")
        notes.add("pdg 会解析重定位（relocAware=true）；能看到 adrp/add 目标地址，但重定位可见性不等于运行时行为。")
        notes.add("本视图只投影伪代码文本，不含 r2ghidra 注解（omittedAnnotations=true）；需要注解请用 project(includeAnnotations=true)。")

        var cursor = start
        var index = 0
        var anyTruncated = false
        var returnedChars = 0
        while (cursor < pdgText.length && index < segmentBudget) {
            val firstLine = lineIndexFor(lineRanges, cursor)
            val segmentStart = cursor
            var segmentEnd = cursor
            var truncated = false
            var endsMidLine = false
            var lineIndex = firstLine
            while (lineIndex < lineRanges.size) {
                val range = lineRanges[lineIndex]
                val to = range.second
                if (to - cursor > charBudget) {
                    if (segmentEnd == segmentStart) {
                        val available = pdgText.substring(cursor, to)
                        segmentEnd = cursor + safeSegmentEnd(available, charBudget)
                        truncated = true
                        endsMidLine = true
                    }
                    break
                }
                segmentEnd = to
                lineIndex++
            }
            if (segmentEnd <= segmentStart) break // 防御：预算非法/异常时避免死循环
            val code = pdgText.substring(segmentStart, segmentEnd)
            returnedChars += code.length
            anyTruncated = anyTruncated || truncated
            segments.add(
                ToolkitIo.json(
                    "index" to index,
                    "startLine" to (newlinePrefix[segmentStart] + 1),
                    "endLine" to if (pdgText[segmentEnd - 1] == '\n') {
                        newlinePrefix[segmentEnd]
                    } else {
                        newlinePrefix[segmentEnd] + 1
                    },
                    "chars" to code.length,
                    "truncated" to truncated,
                    "endsMidLine" to endsMidLine,
                    "code" to code,
                ),
            )
            cursor = segmentEnd
            index++
            if (truncated) break // 单个超长行：本段截断后停止，剩余交给 nextOffset 续读。
        }

        val hasMore = cursor < pdgText.length
        val remaining = pdgText.length - cursor
        if (hasMore) {
            notes.add("还有 " + remaining + " 字符未返回；把 nextOffset 作为 offset 再次调用 projectSegments 可续读（同一 pdgText）。")
        }
        if (anyTruncated) {
            notes.add("存在超过 maxCodeChars 的单个行/段，已在本段内截断（truncated=true）；下一段从 nextOffset 续读。")
        }
        val boundaryInfo = boundary(false, null, null, null, null)
        return ToolkitIo.json(
            "view" to "segmented",
            "source" to "pdg",
            "projectionVersion" to PROJECTION_VERSION,
            "address" to address,
            "functionAddress" to address,
            "offset" to start,
            "offsetRequested" to offset,
            "offsetClamped" to (start != offset),
            "maxCodeChars" to charBudget,
            "maxCodeCharsRequested" to maxCodeChars,
            "maxCodeCharsClamped" to (charBudget != maxCodeChars),
            "maxSegments" to segmentBudget,
            "maxSegmentsRequested" to maxSegments,
            "maxSegmentsClamped" to (segmentBudget != maxSegments),
            "totalChars" to pdgText.length,
            "returnedChars" to returnedChars,
            "remainingChars" to remaining,
            "segmentCount" to segments.size(),
            "hasMore" to hasMore,
            "nextOffset" to (if (hasMore) cursor else null),
            "truncated" to (hasMore || anyTruncated),
            "segments" to segments,
            "omittedAnnotations" to true,
            "annotationsIncluded" to false,
            "boundarySource" to boundaryInfo.get("source").asString,
            "boundaryConfidence" to boundaryInfo.get("confidence").asString,
            "boundary" to boundaryInfo,
            "evidenceLevel" to "static_inferred",
            "relocAware" to true,
            "notes" to JsonArray().apply { notes.forEach { add(it) } },
        )
    }

    /**
     * 大函数体量摘要：用 pdfj（raw 反汇编 JSON）的 blocks/ops 统计 + pdg 行数/字符数。
     * pdfj 缺失或不可解析时 instructionCount/basicBlockCount 保持 null，并在 notes 说明（不猜）。
     * 伪代码仍是 static_inferred，不是运行时确认；pdg 才具备 relocAware=true。
     */
    fun summarizeBigFunction(pdgText: String, pdfjText: String?, address: String): JsonObject {
        val notes = ArrayList<String>()
        notes.add("伪代码（pdg）是静态推断，不是运行时确认；evidenceLevel=static_inferred。")
        notes.add("pdg 会解析重定位（relocAware=true）；能看到 adrp/add 目标地址，但重定位可见性不等于运行时行为。")
        var instructionCount: Int? = null
        var basicBlockCount: Int? = null
        var pdfjPresent = false
        var pdfjParsable = false
        var pdfjNote: String? = null
        if (!pdfjText.isNullOrBlank()) {
            pdfjPresent = true
            // 本仓库 gson 没有 JsonParser.parseString 静态方法（编译期实测 Unresolved reference），用实例方法。
            val parsed = runCatching { JsonParser().parse(pdfjText) }.getOrNull()
            if (parsed == null) {
                pdfjNote = "pdfj 文本不是合法 JSON：instructionCount/basicBlockCount 留 null（不猜）。"
            } else {
                pdfjParsable = true
                if (parsed.isJsonObject) {
                    val root = parsed.asJsonObject
                    val blocks = root.getAsJsonArray("blocks")
                    if (blocks != null) {
                        basicBlockCount = blocks.size()
                        var ops = 0
                        for (element in blocks) {
                            if (element.isJsonObject) {
                                ops += element.asJsonObject.getAsJsonArray("ops")?.size() ?: 0
                            }
                        }
                        instructionCount = ops
                    } else {
                        val ops = root.getAsJsonArray("ops")
                        if (ops != null) instructionCount = ops.size()
                        val nbbs = root.get("nbbs")?.takeIf { it.isJsonPrimitive }?.asInt
                        if (nbbs != null) basicBlockCount = nbbs
                        if (ops != null) notes.add("pdfj 没有 blocks 字段：basicBlockCount 留 null；instructionCount 来自顶层 ops 统计。")
                    }
                    if (instructionCount == null) {
                        val nops = root.get("nops")?.takeIf { it.isJsonPrimitive }?.asInt
                        if (nops != null) instructionCount = nops
                    }
                    if (instructionCount == null && basicBlockCount == null) {
                        pdfjNote = "pdfj 可解析但未找到 ops/blocks 统计字段：instructionCount/basicBlockCount 留 null（不猜）。"
                    }
                } else if (parsed.isJsonArray) {
                    instructionCount = parsed.asJsonArray.size()
                    notes.add("pdfj 顶层是数组：instructionCount 取数组长度，basicBlockCount 留 null（不猜）。")
                } else {
                    pdfjNote = "pdfj 顶层不是对象/数组：instructionCount/basicBlockCount 留 null（不猜）。"
                }
            }
        } else {
            pdfjNote = "pdfj 缺失（null 或空文本）：instructionCount/basicBlockCount 留 null（不猜）。"
        }
        pdfjNote?.let { notes.add(it) }

        val lines = pseudoCodeLines(pdgText)
        val chars = pdgText.length
        if (chars == 0) notes.add("pdg 为空：pseudoCodeLines/pseudoCodeChars 为 0，不能据此判断函数体量。")
        val recommended = chars > SEGMENT_RECOMMENDED_CHARS || lines > 2000
        if (recommended) {
            notes.add(
                "函数体量较大（pseudoCodeLines=" + lines + "，pseudoCodeChars=" + chars +
                    "）：建议用 projectSegments 分段阅读；vmInterpret 这类 82,684 B 函数不要一次性全量返回。",
            )
        }
        return ToolkitIo.json(
            "view" to "big-function-summary",
            "address" to address,
            "instructionCount" to instructionCount,
            "basicBlockCount" to basicBlockCount,
            "pseudoCodeLines" to lines,
            "pseudoCodeChars" to chars,
            "evidenceLevel" to "static_inferred",
            "relocAware" to true,
            "pdfjPresent" to pdfjPresent,
            "pdfjParsable" to pdfjParsable,
            "pdfjNote" to pdfjNote,
            "segmentedReadRecommended" to recommended,
            "notes" to JsonArray().apply { notes.forEach { add(it) } },
        )
    }

    private fun pseudoCodeLines(text: String): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        for (character in text) if (character == '\n') lines++
        if (text.endsWith('\n')) lines--
        return lines
    }

    /** 二分查找包含 offset 的行区间（offset 必须落在某个行区间内）。 */
    private fun lineIndexFor(lineRanges: List<Pair<Int, Int>>, offset: Int): Int {
        var low = 0
        var high = lineRanges.size - 1
        var result = 0
        while (low <= high) {
            val middle = (low + high) / 2
            if (lineRanges[middle].first <= offset) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result.coerceIn(0, lineRanges.size - 1)
    }

    /** 段内截断：不切断 UTF-16 代理对；极端情况下（代理对起点 + 预算 1）至少返回 2 个单元，保证前进。 */
    private fun safeSegmentEnd(code: String, maxChars: Int): Int {
        if (maxChars >= code.length) return code.length
        var end = maxChars.coerceAtLeast(1)
        if (end < code.length && Character.isHighSurrogate(code[end - 1]) && Character.isLowSurrogate(code[end])) {
            end--
        }
        if (end <= 0) end = if (code.length >= 2) 2 else code.length
        return end.coerceAtMost(code.length)
    }

    /** 截断到 maxCodeChars，但不切断 UTF-16 代理对（emoji 等不会变成半个字符）。 */
    private fun safeEnd(code: String, maxCodeChars: Int): Int {
        if (maxCodeChars >= code.length) return code.length
        var end = maxCodeChars.coerceAtLeast(0)
        if (end > 0 && end < code.length &&
            Character.isHighSurrogate(code[end - 1]) && Character.isLowSurrogate(code[end])
        ) {
            end--
        }
        return end
    }
}
