package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.SwitchPayload
import org.jf.dexlib2.iface.value.ArrayEncodedValue
import org.jf.dexlib2.iface.value.ByteEncodedValue
import org.jf.dexlib2.iface.value.IntEncodedValue
import org.jf.dexlib2.iface.value.ShortEncodedValue
import org.jf.dexlib2.iface.value.StringEncodedValue
import org.jf.dexlib2.iface.reference.MethodReference
import java.security.MessageDigest

/**
 * 11.2 VM 辅助的第一刀：dispatch 壳的静态启发式调查。
 *
 * 诚实边界（同时写进工具描述与返回 notes）：
 *  - 只做静态启发式：候选打分、同签名聚类、候选负载、引用表映射、用户 opcode 规则下的线性反汇编、
 *    以及只到「基本块起点 + 覆盖计数」的 CFG；
 *  - 不做反编译、不承诺任意 VMP 一键还原；不出现「已解密/已还原」这类措辞；
 *  - 0 候选是合法结果（没有 switch、没有大常量表、被 R8 内联/裁剪都可能为 0），返回里说明扫了多少。
 *
 * 可单测部分都是纯函数：直方图签名/聚类（含近似阈值）、线性反汇编（覆盖与停点）、块起点导出、打分。
 */
internal object ToolkitVmSurvey {
    const val VERSION = "vm-survey/1"
    const val CONFIDENCE = "static_heuristic"

    /** 用户提供的 opcode 规则：index=首字节(0..255)，length=该指令字节数。 */
    data class OpcodeRule(val index: Int, val mnemonic: String, val length: Int)

    data class LinearInstruction(val offset: Int, val index: Int, val mnemonic: String, val bytesHex: String)

    data class LinearDisassembly(
        val instructions: List<LinearInstruction>,
        val coveredBytes: Int,
        val totalBytes: Int,
        val coveragePercent: Double,
        val stopsAt: Int?,
        val stopReason: String?,
        val ruleWarnings: List<String>,
    )

    /**
     * 纯函数：按「首字节 = 规则 index」线性推进。遇到未知 index / 指令被截断 / 超过 maxInstructions 就停下，
     * 并把停点与原因如实返回；coveragePercent = 覆盖字节 / 总字节（四舍五入到 0.01）。
     */
    fun disassemble(bytes: ByteArray, rules: List<OpcodeRule>, maxInstructions: Int = 512, startOffset: Int = 0): LinearDisassembly {
        val warnings = ArrayList<String>()
        val byIndex = HashMap<Int, OpcodeRule>()
        for (rule in rules) {
            if (rule.index !in 0..255) { warnings.add("规则 index 超出 0..255：" + rule.index + "（" + rule.mnemonic + "）"); continue }
            if (rule.length !in 1..64) { warnings.add("规则 length 非法（应 1..64）：" + rule.mnemonic + "=" + rule.length); continue }
            if (byIndex.containsKey(rule.index)) warnings.add("index " + rule.index + " 重复定义，后者覆盖前者：" + rule.mnemonic)
            byIndex[rule.index] = rule
        }
        val start = startOffset.coerceAtLeast(0)
        val instructions = ArrayList<LinearInstruction>()
        var offset = start
        var stopsAt: Int? = null
        var stopReason: String? = null
        while (true) {
            if (offset >= bytes.size) { stopReason = "end_of_blob"; break }
            if (instructions.size >= maxInstructions) { stopsAt = offset; stopReason = "max_instructions"; break }
            val index = bytes[offset].toInt() and 0xff
            val rule = byIndex[index]
            if (rule == null) { stopsAt = offset; stopReason = "unknown_opcode_index:" + index; break }
            if (offset + rule.length > bytes.size) { stopsAt = offset; stopReason = "truncated_instruction"; break }
            val slice = bytes.copyOfRange(offset, offset + rule.length)
            instructions.add(LinearInstruction(offset, index, rule.mnemonic, slice.joinToString("") { "%02x".format(it) }))
            offset += rule.length
        }
        val total = (bytes.size - start).coerceAtLeast(0)
        val covered = (offset - start).coerceAtLeast(0)
        val percent = if (total == 0) 0.0 else covered.toDouble() * 100.0 / total.toDouble()
        return LinearDisassembly(instructions, covered, total, Math.round(percent * 100.0) / 100.0, stopsAt, stopReason, warnings)
    }

    /**
     * 纯函数：从「分支目标」与「分支/返回/抛出之后的落点」导出基本块起点。
     * 只接受落在已知指令起点上的目标；对不上的（或越界的）单独返回，便于调用方如实报告。
     */
    fun blockStarts(codeSize: Int, instructionOffsets: List<Int>, branchTargets: List<Int>, terminatingOffsets: List<Int>): Pair<List<Int>, List<Int>> {
        val offsets = instructionOffsets.toSortedSet()
        val starts = sortedSetOf<Int>()
        val dropped = ArrayList<Int>()
        if (offsets.contains(0)) starts.add(0)
        for (target in branchTargets) {
            if (target < 0 || target >= codeSize) { dropped.add(target); continue }
            if (offsets.contains(target)) starts.add(target) else dropped.add(target)
        }
        for (offset in terminatingOffsets) {
            val next = offsets.firstOrNull { it > offset }
            if (next != null && next < codeSize) starts.add(next)
        }
        return starts.toList() to dropped
    }

    data class HistogramMember(val id: String, val dexEntry: String, val histogram: Map<String, Int>)
    data class Cluster(val clusterId: String, val signature: String, val approximate: Boolean, val members: List<HistogramMember>)

    /** 精确签名：按「次数降序、opcode 名升序」拼 count，可逐字符比对。 */
    fun exactSignature(histogram: Map<String, Int>): String =
        histogram.entries.filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(",") { it.key + ":" + it.value }

    /** 归一化占比（0..100）。 */
    fun shares(histogram: Map<String, Int>): Map<String, Double> {
        val total = histogram.values.sum().toDouble()
        if (total <= 0.0) return emptyMap()
        return histogram.filterValues { it > 0 }.mapValues { it.value * 100.0 / total }
    }

    /** 总变差距离（0..100）：对指令总条数不敏感、对称；0 表示占比分布一致。 */
    fun distancePercent(a: Map<String, Int>, b: Map<String, Int>): Double {
        val sa = shares(a); val sb = shares(b)
        val keys = sa.keys + sb.keys
        val l1 = keys.sumOf { kotlin.math.abs((sa[it] ?: 0.0) - (sb[it] ?: 0.0)) }
        return Math.round(l1 / 2.0 * 100.0) / 100.0
    }

    /**
     * 纯函数：先按精确同签名聚类；approxPercent>0 时按「代表之间的距离 <= approxPercent」贪心合并
     * （阈值边界：<= 合并、> 不合并）。合并后 approximate=true 仅当簇内确实含多种签名。
     */
    fun cluster(members: List<HistogramMember>, approxPercent: Int = 0): List<Cluster> {
        val bySignature = LinkedHashMap<String, MutableList<HistogramMember>>()
        members.forEach { bySignature.getOrPut(exactSignature(it.histogram)) { ArrayList() }.add(it) }
        val exact = bySignature.entries.map { Cluster("c0", it.key, false, it.value.toList()) }
        val threshold = approxPercent.coerceIn(0, 100)
        val merged = ArrayList<Cluster>()
        val signatureSets = ArrayList<MutableSet<String>>()
        for (cluster in exact) {
            var targetIndex = -1
            if (threshold > 0) {
                for (index in merged.indices) {
                    if (distancePercent(merged[index].members.first().histogram, cluster.members.first().histogram) <= threshold) {
                        targetIndex = index; break
                    }
                }
            }
            if (targetIndex < 0) {
                merged.add(cluster); signatureSets.add(mutableSetOf(cluster.signature))
            } else {
                merged[targetIndex] = merged[targetIndex].copy(members = merged[targetIndex].members + cluster.members)
                signatureSets[targetIndex].add(cluster.signature)
            }
        }
        return merged.mapIndexed { index, cluster ->
            cluster.copy(
                clusterId = "c" + index,
                approximate = signatureSets[index].size > 1,
                members = cluster.members.sortedBy { it.id },
            )
        }
    }

    data class MethodFeatures(
        val instructionCount: Int,
        val switchCount: Int,
        val switchCases: Int,
        val backBranches: Int,
        val arrayOps: Int,
        val invokeCount: Int,
        val bigLiterals: Int,
        val classPayloadBytes: Int,
    )

    /**
     * 纯函数：可解释打分。每项贡献都在 contributions 里列出（特征名 -> 分数），总和封顶 100。
     * 权重是启发式的，不是「已识别 VMP」的判定。
     */
    fun scoreOf(features: MethodFeatures, payloadMinBytes: Int): Pair<Int, List<Pair<String, Int>>> {
        val contributions = ArrayList<Pair<String, Int>>()
        fun add(name: String, value: Int) { if (value != 0) contributions.add(name to value) }
        add("switch_dispatch", minOf(features.switchCount, 4) * 18)
        add("switch_cases", minOf(features.switchCases / 4, 20) * 2)
        add("dispatch_loop_backedge", minOf(features.backBranches, 4) * 5)
        if (features.classPayloadBytes >= payloadMinBytes) {
            add("large_constant_payload", 12 + minOf(features.classPayloadBytes / 4096, 12))
        }
        add("array_index_ops", minOf(features.arrayOps, 16) * 2)
        add("big_literal_consts", minOf(features.bigLiterals, 8) * 2)
        add("method_size", minOf(features.instructionCount / 32, 8))
        add("call_fanout", minOf(features.invokeCount, 8))
        return minOf(100, contributions.sumOf { it.second }) to contributions
    }

    // ---------------------------------------------------------------- DEX 侧（结构断言级；不做反编译）

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Payload(val owner: String, val name: String, val type: String, val kind: String, val bytes: ByteArray)

    private fun bytesOfArray(value: ArrayEncodedValue): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        for (element in value.value) {
            when (element) {
                is ByteEncodedValue -> out.write(element.value.toInt() and 0xff)
                is IntEncodedValue -> {
                    val v = element.value
                    out.write(v and 0xff); out.write((v ushr 8) and 0xff); out.write((v ushr 16) and 0xff); out.write((v ushr 24) and 0xff)
                }
                is ShortEncodedValue -> {
                    val v = element.value.toInt()
                    out.write(v and 0xff); out.write((v ushr 8) and 0xff)
                }
                else -> return null
            }
        }
        return out.toByteArray()
    }

    private fun payloadOf(owner: String, field: org.jf.dexlib2.iface.Field): Payload? =
        // Field 本身即 FieldReference（没有 .reference）。
        when (val value = field.initialValue) {
            is StringEncodedValue -> Payload(owner, field.name, field.type, "string", value.value.toByteArray(Charsets.UTF_8))
            is ArrayEncodedValue -> bytesOfArray(value)?.let { Payload(owner, field.name, field.type, "array", it) }
            else -> null
        }

    /** 供工具层解析 payloadRef：按 field id / 结尾匹配 / 字段名匹配取回负载字节。 */
    fun payloadBytesOf(dexes: List<Pair<String, List<ClassDef>>>, reference: String): ByteArray? {
        for ((_, classes) in dexes) {
            for (definition in classes) {
                for (field in definition.fields) {
                    val payload = payloadOf(definition.type, field) ?: continue
                    val id = payload.owner + "->" + payload.name + ":" + payload.type
                    if (id == reference || id.endsWith(reference) || payload.name == reference) return payload.bytes
                }
            }
        }
        return null
    }

    private fun intEntries(value: ArrayEncodedValue): List<Int>? {
        val out = ArrayList<Int>()
        for (element in value.value) {
            when (element) {
                is IntEncodedValue -> out.add(element.value)
                is ShortEncodedValue -> out.add(element.value.toInt())
                is ByteEncodedValue -> out.add(element.value.toInt())
                else -> return null
            }
        }
        return out
    }

    /** opcode.name 是 smali 拼写（小写）；判 switch 用枚举常量，避免大小写/拼写假设。 */
    private fun isSwitchOpcode(opcode: Opcode): Boolean =
        opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH ||
            opcode == Opcode.PACKED_SWITCH_PAYLOAD || opcode == Opcode.SPARSE_SWITCH_PAYLOAD

    private fun methodId(method: Method): String {
        val reference: MethodReference = method
        return reference.definingClass + "->" + reference.name + "(" + reference.parameterTypes.joinToString("") + ")" + reference.returnType
    }

    /** 引用表映射：把选定（或最大的 int/short/byte）数组字段摊平成 index -> 元素，便于逐项核对。 */
    private fun dispatchArraySection(dexes: List<Pair<String, List<ClassDef>>>, requested: String?): JsonObject? {
        var pickedId: String? = null
        var pickedClass: String? = null
        var pickedEntries: List<Int>? = null
        for ((_, classes) in dexes) {
            for (definition in classes) {
                for (field in definition.fields) {
                    val value = field.initialValue as? ArrayEncodedValue ?: continue
                    val entries = intEntries(value) ?: continue
                    val id = definition.type + "->" + field.name + ":" + field.type
                    if (requested != null) {
                        if (id == requested || id.endsWith(requested) || field.name == requested) {
                            pickedId = id; pickedClass = definition.type; pickedEntries = entries
                        }
                    } else if (pickedEntries == null || entries.size > pickedEntries.size) {
                        pickedId = id; pickedClass = definition.type; pickedEntries = entries
                    }
                }
                if (requested != null && pickedId != null) break
            }
            if (requested != null && pickedId != null) break
        }
        val entries = pickedEntries ?: return null
        val bounded = entries.take(64)
        val array = JsonArray(); bounded.forEach { array.add(it) }
        val raw = java.io.ByteArrayOutputStream()
        bounded.forEach { v ->
            raw.write(v and 0xff); raw.write((v ushr 8) and 0xff); raw.write((v ushr 16) and 0xff); raw.write((v ushr 24) and 0xff)
        }
        return ToolkitIo.json(
            "arrayRef" to pickedId,
            "class" to pickedClass,
            "length" to entries.size,
            "indexBase" to 0,
            "indexBaseSource" to "default_0（未从 packed-switch 反推首键；索引按数组下标核对）",
            "entries" to array,
            "truncated" to (entries.size > bounded.size),
            "entryLimit" to 64,
            "entriesSha256" to sha256(raw.toByteArray()),
            "confidence" to CONFIDENCE,
        )
    }

    /** CFG：只到「基本块起点 + 覆盖计数」，不做伪代码、不推调用关系。 */
    private fun cfgSection(dexes: List<Pair<String, List<ClassDef>>>, locator: String?): JsonObject? {
        if (locator.isNullOrBlank()) return null
        val wanted = locator.substringAfter(':')
        for ((entry, classes) in dexes) {
            for (definition in classes) {
                for (method in definition.methods) {
                    val id = methodId(method)
                    if (id != wanted && id.substringAfter("->") != locator) continue
                    val instructions = method.implementation?.instructions?.toList() ?: emptyList()
                    // dexlib2 的 iface Instruction 没有 codeOffset：按 codeUnits 累加，单位与分支 offset 一致（code units）。
                    val offsets = ArrayList<Int>()
                    var cursor = 0
                    for (instruction in instructions) { offsets.add(cursor); cursor += instruction.codeUnits }
                    val indexByOffset = HashMap<Int, Int>()
                    offsets.forEachIndexed { index, value -> indexByOffset[value] = index }
                    val targets = ArrayList<Int>()
                    val terminators = ArrayList<Int>()
                    var switchBaseSource = "no_switch"
                    for (index in instructions.indices) {
                        val instruction = instructions[index]
                        val opcode = instruction.opcode.name
                        val at = offsets[index]
                        // OffsetInstruction.codeOffset = 指令里编码的**相对分支偏移**（接口只被分支格式实现）；
                        // 绝对位置按 codeUnits 累加得到，两者相加才是目标。
                        // opcode.name 是 smali 拼写（小写，如 "if-eqz"/"goto"），不是枚举常量名。
                        if (instruction is OffsetInstruction && (opcode.startsWith("if-") || opcode.startsWith("goto"))) {
                            targets.add(at + instruction.codeOffset)
                        }
                        if (instruction is SwitchPayload) {
                            // 载荷里的分支 offset 相对「引用它的 switch 指令」；先按引用关系找出该指令，找不到才退回 payload 自身并标注来源。
                            var referenced = -1
                            for (candidateIndex in instructions.indices) {
                                val candidate = instructions[candidateIndex]
                                if (candidate is OffsetInstruction && isSwitchOpcode(candidate.opcode) &&
                                    offsets[candidateIndex] + candidate.codeOffset == at) { referenced = candidateIndex; break }
                            }
                            val base = if (referenced >= 0) offsets[referenced] else at
                            switchBaseSource = if (referenced >= 0) "switch_instruction" else "payload_self"
                            instruction.switchElements.forEach { targets.add(base + it.offset) }
                        }
                        if (opcode.startsWith("return") || opcode == "throw" || opcode.startsWith("goto")) terminators.add(at)
                    }
                    val codeSize = cursor
                    val (starts, dropped) = blockStarts(codeSize, offsets, targets, terminators)
                    val startSet = starts.toHashSet()
                    var covered = 0
                    for (index in instructions.indices) {
                        val at = offsets[index]
                        val inBlock = if (startSet.contains(at)) true else {
                            val owner = starts.lastOrNull { it <= at }
                            owner != null && at < (starts.firstOrNull { it > owner } ?: Int.MAX_VALUE)
                        }
                        if (inBlock) covered++
                    }
                    val blocks = JsonArray(); starts.forEach { blocks.add(it) }
                    val droppedJson = JsonArray(); dropped.distinct().forEach { droppedJson.add(it) }
                    return ToolkitIo.json(
                        "method" to id,
                        "dexEntry" to entry,
                        "blockStarts" to blocks,
                        "blockCount" to starts.size,
                        "instructionCount" to instructions.size,
                        "coverage" to ToolkitIo.json(
                            "blocks" to starts.size,
                            "instructions" to covered,
                            "instructionPercent" to (if (instructions.isEmpty()) 0.0 else Math.round(covered * 10000.0 / instructions.size) / 100.0),
                            "codeUnits" to codeSize,
                        ),
                        "droppedTargets" to droppedJson,
                        "offsetUnit" to "code_units",
                        "switchTargetBaseSource" to switchBaseSource,
                        "branchDeltaSource" to "OffsetInstruction.codeOffset（指令内编码的相对偏移）+ 累加得到的绝对位置",
                        "confidence" to CONFIDENCE,
                        "note" to "只导出基本块起点与覆盖计数：不做伪代码、不推调用关系；异常表覆盖的字节不在统计内；偏移单位是 code units（dexlib2 iface Instruction 不提供字节偏移）。",
                    )
                }
            }
        }
        return ToolkitIo.json(
            "method" to locator,
            "error" to "METHOD_NOT_FOUND",
            "note" to "在本次 dex 集里找不到该方法；先用 apk_dex_list_methods/apk_dex_search_members 拿准确 locator。",
            "confidence" to CONFIDENCE,
        )
    }

    /**
     * 主入口：对给定 dex 集合做静态调查。dexes = (dex 条目名 -> 类列表)，便于用合成夹具单测。
     */
    fun survey(
        dexes: List<Pair<String, List<ClassDef>>>,
        limit: Int = 20,
        minScore: Int = 0,
        payloadMinBytes: Int = 64,
        approxPercent: Int = 0,
        opcodeRules: List<OpcodeRule> = emptyList(),
        blob: ByteArray? = null,
        blobSource: String = "none",
        maxInstructions: Int = 512,
        cfgMethodLocator: String? = null,
        dispatchArrayRef: String? = null,
        maxMethods: Int = 20000,
        blobOnly: Boolean = false,
    ): JsonObject {
        val boundedLimit = limit.coerceIn(1, 200)
        var classCount = 0
        var methodCount = 0
        var truncated = false
        val members = ArrayList<HistogramMember>()
        val scored = ArrayList<Pair<Int, JsonObject>>()
        val payloads = ArrayList<Payload>()
        val scan = ArrayList<Pair<String, List<ClassDef>>>()
        for (entry in dexes) {
            scan.add(entry)
            for (definition in entry.second) {
                classCount++
                val classPayloads = ArrayList<Payload>()
                for (field in definition.fields) payloadOf(definition.type, field)?.let { classPayloads.add(it) }
                payloads.addAll(classPayloads)
                val classPayloadBytes = classPayloads.maxOfOrNull { it.bytes.size } ?: 0
                for (method in definition.methods) {
                    if (methodCount >= maxMethods) { truncated = true; break }
                    methodCount++
                    val instructions = method.implementation?.instructions ?: continue
                    var instructionCount = 0
                    var switchCount = 0
                    var switchCases = 0
                    var backBranches = 0
                    var arrayOps = 0
                    var invokeCount = 0
                    val histogram = HashMap<String, Int>()
                    for (instruction in instructions) {
                        instructionCount++
                        val opcode = instruction.opcode.name
                        histogram[opcode] = (histogram[opcode] ?: 0) + 1
                        if (isSwitchOpcode(instruction.opcode)) {
                            switchCount++
                            (instruction as? SwitchPayload)?.switchElements?.let { switchCases += it.size }
                        }
                        if (opcode.startsWith("aget") || opcode.startsWith("aput")) arrayOps++
                        if (opcode.startsWith("invoke")) invokeCount++
                        // 分支偏移 <= 0 = 向后跳（回边），dispatch 循环的强特征。
                        if (instruction is OffsetInstruction && instruction.codeOffset <= 0) backBranches++
                    }
                    val id = methodId(method)
                    members.add(HistogramMember(id, entry.first, histogram))
                    val features = MethodFeatures(instructionCount, switchCount, switchCases, backBranches, arrayOps, invokeCount, 0, classPayloadBytes)
                    val scored0 = scoreOf(features, payloadMinBytes)
                    // 0 分 = 没有任何 dispatch 特征（无 switch/无大常量表/无索引运算），不进候选：
                    // 这样「0 候选」是一个真实可复现的结果，而不是被一堆 0 分方法淹没。
                    if (scored0.first <= 0 || scored0.first < minScore) continue
                    val row = ToolkitIo.json(
                        "dexEntry" to entry.first,
                        "class" to definition.type,
                        "method" to id.substringAfter("->"),
                        "evidenceRef" to ("dex_method:" + id),
                        "score" to scored0.first,
                        "features" to ToolkitIo.json(
                            "instructionCount" to features.instructionCount,
                            "switchCount" to features.switchCount,
                            "switchCases" to features.switchCases,
                            "dispatchLoopBackEdges" to features.backBranches,
                            "arrayIndexOps" to features.arrayOps,
                            "invokeCount" to features.invokeCount,
                            "classPayloadBytes" to features.classPayloadBytes,
                        ),
                        "contributions" to JsonObject().apply { scored0.second.forEach { addProperty(it.first, it.second) } },
                        "confidence" to CONFIDENCE,
                    )
                    scored.add(scored0.first to row)
                }
                if (truncated) break
            }
            if (truncated) break
        }

        // 分数分布摘要：candidateCount 只说明「有多少方法带了任一特征」，桶才看得出「有多少真像壳」。
        var bucket100 = 0; var bucket80 = 0; var bucket60 = 0; var bucket40 = 0; var bucket1 = 0
        for ((value, _) in scored) {
            when {
                value >= 100 -> bucket100++
                value >= 80 -> bucket80++
                value >= 60 -> bucket60++
                value >= 40 -> bucket40++
                else -> bucket1++
            }
        }

        val candidates = JsonArray()
        scored.sortedWith(compareByDescending<Pair<Int, JsonObject>> { it.first }.thenBy { it.second.get("evidenceRef").asString })
            .take(boundedLimit).forEach { candidates.add(it.second) }

        val clusters = JsonArray()
        val clustered = cluster(members, approxPercent).sortedWith(compareByDescending<Cluster> { it.members.size }.thenBy { it.clusterId })
        clustered.forEach { c ->
            val memberJson = JsonArray()
            c.members.take(boundedLimit).forEach { m -> memberJson.add(ToolkitIo.json("method" to m.id, "dexEntry" to m.dexEntry)) }
            clusters.add(ToolkitIo.json(
                "clusterId" to c.clusterId,
                "size" to c.members.size,
                "signature" to c.signature,
                "approximate" to c.approximate,
                "members" to memberJson,
                "memberTruncated" to (c.members.size > boundedLimit),
            ))
        }

        val payloadJson = JsonArray()
        payloads.filter { it.bytes.size >= payloadMinBytes }.sortedByDescending { it.bytes.size }.take(boundedLimit).forEach { payload ->
            payloadJson.add(ToolkitIo.json(
                "class" to payload.owner,
                "field" to (payload.owner + "->" + payload.name + ":" + payload.type),
                "kind" to payload.kind,
                "length" to payload.bytes.size,
                "sha256" to sha256(payload.bytes),
                "first16Hex" to payload.bytes.take(16).joinToString("") { "%02x".format(it) },
                "addr" to null,
                "addrNote" to "常量池初始值没有稳定的字节码地址；用 field + sha256 核对，不要当成已定位的字节码起点",
            ))
        }

        val arraySection = dispatchArraySection(scan, dispatchArrayRef)
        var disassembly: JsonObject? = null
        if (blob != null && opcodeRules.isNotEmpty()) {
            val result = disassemble(blob, opcodeRules, maxInstructions)
            val list = JsonArray()
            result.instructions.forEach { list.add(ToolkitIo.json("offset" to it.offset, "index" to it.index, "mnemonic" to it.mnemonic, "bytesHex" to it.bytesHex)) }
            val warnings = JsonArray(); result.ruleWarnings.forEach { warnings.add(it) }
            disassembly = ToolkitIo.json(
                "blobSource" to blobSource,
                "confidence" to "user_supplied_rules",
                "ruleCount" to opcodeRules.size,
                "instructions" to list,
                "coveragePercent" to result.coveragePercent,
                "coveredBytes" to result.coveredBytes,
                "totalBytes" to result.totalBytes,
                "stopsAt" to result.stopsAt,
                "stopReason" to result.stopReason,
                "ruleWarnings" to warnings,
            )
        }
        val cfg = cfgSection(scan, cfgMethodLocator)

        val notes = JsonArray()
        notes.add("静态启发式调查（confidence=" + CONFIDENCE + "）：不做反编译、不承诺任意 VMP 一键还原，也不会给出「解密完成 / 还原完成」这类结论。")
        notes.add("0 候选是合法结果：本轮扫了 " + dexes.size + " 个 dex / " + classCount + " 个类 / " + methodCount + " 个方法" +
            (if (truncated) "（达到 maxMethods=" + maxMethods + " 提前截断）" else "") +
            "；没有 packed/sparse-switch、没有大常量负载、或被 R8 内联/裁剪/壳加密后不可见，都可能为 0。")
        notes.add("分数是可解释启发式：逐项看 contributions，不要当成「已识别 VMP」的判定。")
        if (blobOnly) notes.add("blob-only 模式：只按你给的 opcodeRules 对 blobHex 做线性反汇编（规则校验 + 覆盖 + 停点），**没有扫描任何 DEX**（sources 为空、candidates/clusters/payloads 都为空）。要顺带找 dispatch 壳候选，请给 workspaceId 或 path。")
        if (scored.size > 0) notes.add("候选数量大不等于壳多：candidateCount=" + scored.size + " 只是「带了任一特征的方法数」，先看 scoreBuckets 的高分桶与 clusters 的规模，再决定要不要逐个读 smali。")
        if (payloads.none { it.bytes.size >= payloadMinBytes }) notes.add("未发现大于等于 " + payloadMinBytes + " 字节的 byte[]/String 常量负载（payloadMinBytes 可调小）。")
        if (arraySection == null) notes.add("未找到可映射的 dispatch 数组（可用 dispatchArray 指定字段，如 Lpkg/Foo;->table:[I）。")

        val nextActions = JsonArray()
        if (candidates.size() > 0) {
            val ref = candidates.get(0).asJsonObject.get("evidenceRef").asString
            nextActions.add("看最高分候选的方法体：apk_read_text{locator:\"" + ref + "\"}（smali 视图，只读）")
        }
        if (disassembly == null && !blobOnly) nextActions.add("要线性反汇编字节码 blob：给 opcodeRules[{index,mnemonic,length}] + blobHex，或用 payloadRef 指向上面某个 payload 字段。")
        if (blobOnly && opcodeRules.isEmpty()) nextActions.add("blob-only 模式还缺 opcodeRules[{index,mnemonic,length}]：规则按「首字节 index -> 长度」线性推进，遇到未定义 index 会停在 stopsAt 并给原因。")
        if (cfg == null && !blobOnly) nextActions.add("要看基本块起点：给 method=\"dex_method:Lpkg/Foo;->bar()V\"（只做块边界与覆盖计数）。")
        nextActions.add("本工具只给静态证据；真实壳的运行期行为请用运行期工具或外部框架核对，不要据此下「还原完成」结论。")

        return ToolkitIo.json(
            "version" to VERSION,
            "confidence" to CONFIDENCE,
            "scanned" to ToolkitIo.json(
                "dexCount" to dexes.size, "classCount" to classCount, "methodCount" to methodCount,
                "truncated" to truncated, "maxMethods" to maxMethods,
            ),
            "blobOnly" to blobOnly,
            "candidateCount" to scored.size,
            "scoreBuckets" to ToolkitIo.json(
                "100" to bucket100, "80-99" to bucket80, "60-79" to bucket60,
                "40-59" to bucket40, "1-39" to bucket1,
            ),
            "scoreBucketsNote" to "与 candidateCount 同口径（受 minScore 影响、不受 limit 截断）；先看高分桶再逐个读 smali",
            "minScore" to minScore,
            "candidates" to candidates,
            "clusterCount" to clustered.size,
            "clusters" to clusters,
            "payloadCount" to payloadJson.size(),
            "payloads" to payloadJson,
            "dispatchArray" to arraySection,
            "disassembly" to disassembly,
            "cfg" to cfg,
            "notes" to notes,
            "nextActions" to nextActions,
        )
    }
}



