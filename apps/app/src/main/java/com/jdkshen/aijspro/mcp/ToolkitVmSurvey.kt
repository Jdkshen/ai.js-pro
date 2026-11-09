package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
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

    /** 权重来源：default=内置默认表（历史行为）；custom=调用方用 weights 覆盖过至少一项。 */
    const val WEIGHTS_SOURCE_DEFAULT = "default"
    const val WEIGHTS_SOURCE_CUSTOM = "custom"
    /** 单个权重上限（防溢出的无意义巨值）。 */
    const val MAX_WEIGHT = 1_000_000

    /**
     * 可调权重的 7 个特征名（= 候选 features 里的键）。weights 只接受这些键，未知键直接 INVALID_ARGUMENT；
     * 顺序固定，回显的权重表按这个顺序输出。
     */
    val ADJUSTABLE_WEIGHTS = listOf(
        "switchCount", "switchCases", "dispatchLoopBackEdges", "arrayIndexOps",
        "invokeCount", "instructionCount", "classPayloadBytes",
    )

    /** 默认权重（与 dev-1422 起的硬编码系数逐项一致；**冻结**：改这里必须同步改冻结断言）。 */
    val DEFAULT_WEIGHTS: Map<String, Int> = linkedMapOf(
        "switchCount" to 18,
        "switchCases" to 2,
        "dispatchLoopBackEdges" to 5,
        "arrayIndexOps" to 2,
        "invokeCount" to 1,
        "instructionCount" to 1,
        "classPayloadBytes" to 1,
    )

    /** big_literal_consts 的系数固定：bigLiterals 特征在 survey 里恒为 0，调它没有意义，故不进 weights。 */
    private const val FIXED_BIG_LITERALS_WEIGHT = 2

    /** 归一化说明（contribution = normalized * weight；归一化的封顶/除数是历史实现的一部分，不随 weights 变）。 */
    private val NORMALIZATIONS: Map<String, String> = linkedMapOf(
        "switch_dispatch" to "min(switchCount, 4)",
        "switch_cases" to "min(switchCases / 4, 20)",
        "dispatch_loop_backedge" to "min(dispatchLoopBackEdges, 4)",
        "large_constant_payload" to "classPayloadBytes >= payloadMinBytes ? 12 + min(classPayloadBytes / 4096, 12) : 0",
        "array_index_ops" to "min(arrayIndexOps, 16)",
        "big_literal_consts" to "min(bigLiterals, 8)（系数固定 2，不参与 weights）",
        "method_size" to "min(instructionCount / 32, 8)",
        "call_fanout" to "min(invokeCount, 8)",
    )

    /**
     * 生效权重。[provided] 记录用户显式给过的键：显式给 0 的项要在 contributions 里以 0 出现（不静默消失）。
     */
    data class Weights(val values: Map<String, Int>, val source: String, val provided: Set<String> = emptySet()) {
        fun weightFor(key: String): Int = if (key == "bigLiterals") FIXED_BIG_LITERALS_WEIGHT else values[key] ?: 0
        /** 实际生效的权重表（只含 7 个可调键，顺序固定）。 */
        fun table(): JsonObject = JsonObject().apply { ADJUSTABLE_WEIGHTS.forEach { addProperty(it, values[it] ?: 0) } }
    }

    fun defaultWeights(): Weights = Weights(DEFAULT_WEIGHTS, WEIGHTS_SOURCE_DEFAULT)

    /**
     * 解析 weights 参数（纯函数）：
     * - null / 未给 -> 默认表（weightsSource=default）；空对象同样按默认处理；
     * - 必须是对象；键必须是 [ADJUSTABLE_WEIGHTS] 之一；值必须是非负整数（0 合法 = 关闭该项）；
     * - 未知键 / 负数 / 非整数 / 全 0 -> INVALID_ARGUMENT（消息里列出可用键）。
     */
    fun weightsFrom(raw: JsonElement?): Weights {
        if (raw == null || raw.isJsonNull) return defaultWeights()
        if (!raw.isJsonObject) throw ToolkitException("INVALID_ARGUMENT",
            "weights 必须是对象，例如 {\"classPayloadBytes\": 50}；可用键：" + ADJUSTABLE_WEIGHTS.joinToString(", "), "weights")
        val provided = raw.asJsonObject
        val unknown = provided.keySet().filterNot { it in ADJUSTABLE_WEIGHTS }
        if (unknown.isNotEmpty()) throw ToolkitException("INVALID_ARGUMENT",
            "weights 有未知键：" + unknown.joinToString(", ") + "；可用键：" + ADJUSTABLE_WEIGHTS.joinToString(", "), "weights")
        val overrides = LinkedHashMap<String, Int>()
        provided.entrySet().forEach { (key, value) ->
            val primitive = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?: throw ToolkitException("INVALID_ARGUMENT", "weights." + key + " 必须是整数（非负）", "weights")
            val text = primitive.asString
            if (!text.matches(Regex("-?\\d+"))) throw ToolkitException("INVALID_ARGUMENT",
                "weights." + key + " 必须是整数（当前=" + text + "）", "weights")
            val number = text.toLong()
            if (number < 0) throw ToolkitException("INVALID_ARGUMENT",
                "weights." + key + " 不能为负数（当前=" + number + "）；要关闭该项请给 0", "weights")
            if (number > MAX_WEIGHT) throw ToolkitException("INVALID_ARGUMENT",
                "weights." + key + " 超过上限 " + MAX_WEIGHT + "（当前=" + number + "）", "weights")
            overrides[key] = number.toInt()
        }
        // 注意：只在**生效表**（默认值 + 覆盖）全为 0 时才拒绝。
        // 只给 {switchCount: 0} 这类单项覆盖是合法用法（= 关闭该项），未给的键仍用默认权重。
        val effective = LinkedHashMap<String, Int>()
        ADJUSTABLE_WEIGHTS.forEach { key -> effective[key] = overrides[key] ?: DEFAULT_WEIGHTS.getValue(key) }
        if (effective.values.all { it == 0 }) throw ToolkitException("INVALID_ARGUMENT",
            "weights 生效后全为 0：所有候选都会得 0 分、无法区分", "weights")
        return Weights(effective, if (overrides.isEmpty()) WEIGHTS_SOURCE_DEFAULT else WEIGHTS_SOURCE_CUSTOM, overrides.keys.toSet())
    }

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

    /** 打分结果：score 封顶 100（历史口径），rawScore 不封顶（权重校准用）。 */
    data class Score(val score: Int, val rawScore: Int, val contributions: List<Pair<String, Int>>)

    /**
     * 纯函数：可解释打分 = **显式线性组合**。
     *
     * ```
     * contribution(term) = normalizedValue(term) * weight(term)   // weight 来自 weights（默认见 DEFAULT_WEIGHTS）
     * rawScore            = Σ contribution(term)
     * score               = min(100, rawScore)                    // 与历史实现一致的封顶
     * ```
     *
     * contributions 一律用**实际权重**算（权重改了 contributions 一定跟着变）。权重 0 的项：用户显式给过的
     * 仍出现在 contributions 里（=0，不静默消失）；没给过的保持历史行为——normalized=0 的项不出现。
     * 权重是启发式的，不是「已识别 VMP」的判定。
     */
    fun scoreWeighted(features: MethodFeatures, payloadMinBytes: Int, weights: Weights = defaultWeights()): Score {
        val contributions = ArrayList<Pair<String, Int>>()
        fun term(name: String, weightKey: String, normalized: Int) {
            val value = normalized * weights.weightFor(weightKey)
            if (value != 0 || weightKey in weights.provided) contributions.add(name to value)
        }
        term("switch_dispatch", "switchCount", minOf(features.switchCount, 4))
        term("switch_cases", "switchCases", minOf(features.switchCases / 4, 20))
        term("dispatch_loop_backedge", "dispatchLoopBackEdges", minOf(features.backBranches, 4))
        term("large_constant_payload", "classPayloadBytes",
            if (features.classPayloadBytes >= payloadMinBytes) 12 + minOf(features.classPayloadBytes / 4096, 12) else 0)
        term("array_index_ops", "arrayIndexOps", minOf(features.arrayOps, 16))
        term("big_literal_consts", "bigLiterals", minOf(features.bigLiterals, 8))
        term("method_size", "instructionCount", minOf(features.instructionCount / 32, 8))
        term("call_fanout", "invokeCount", minOf(features.invokeCount, 8))
        val raw = contributions.sumOf { it.second }
        return Score(minOf(100, raw), raw, contributions)
    }

    /**
     * 纯函数：默认权重下的打分（历史 API，签名与返回形状保持不变）。
     * 默认权重下与改造前逐字段一致（有冻结断言）。
     */
    fun scoreOf(features: MethodFeatures, payloadMinBytes: Int): Pair<Int, List<Pair<String, Int>>> {
        val scored = scoreWeighted(features, payloadMinBytes)
        return scored.score to scored.contributions
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
        weights: JsonElement? = null,
    ): JsonObject {
        // 权重：未给 -> 默认表（与改造前逐字段一致）；给了 -> 校验后覆盖（未知键/负数/全 0 直接 INVALID_ARGUMENT）。
        val effectiveWeights = weightsFrom(weights)
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
                    val scored0 = scoreWeighted(features, payloadMinBytes, effectiveWeights)
                    // 0 分 = 没有任何 dispatch 特征（无 switch/无大常量表/无索引运算），不进候选：
                    // 这样「0 候选」是一个真实可复现的结果，而不是被一堆 0 分方法淹没。
                    if (scored0.score <= 0 || scored0.score < minScore) continue
                    val row = ToolkitIo.json(
                        "dexEntry" to entry.first,
                        "class" to definition.type,
                        "method" to id.substringAfter("->"),
                        "evidenceRef" to ("dex_method:" + id),
                        "score" to scored0.score,
                        // score 封顶 100（历史口径）；rawScore 不封顶，权重校准时用它看区分度。
                        "rawScore" to scored0.rawScore,
                        "features" to ToolkitIo.json(
                            "instructionCount" to features.instructionCount,
                            "switchCount" to features.switchCount,
                            "switchCases" to features.switchCases,
                            "dispatchLoopBackEdges" to features.backBranches,
                            "arrayIndexOps" to features.arrayOps,
                            "invokeCount" to features.invokeCount,
                            "classPayloadBytes" to features.classPayloadBytes,
                        ),
                        "contributions" to JsonObject().apply { scored0.contributions.forEach { addProperty(it.first, it.second) } },
                        "confidence" to CONFIDENCE,
                    )
                    scored.add(scored0.score to row)
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
        notes.add("权重可按真样本校准：用 weights{...} 只试不同权重、不发版（不给就用默认表）。返回的 weights/weightsSource 是**实际生效**的表；" +
            "contributions 一律按生效权重算；score 封顶 100，rawScore 不封顶——校准时看 rawScore 的区分度。")
        if (effectiveWeights.source == WEIGHTS_SOURCE_CUSTOM) notes.add("本次 weightsSource=custom：候选排序与 scoreBuckets 都按这组权重重算；要复现历史结果请不带 weights 再跑一次。")
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
            "weightsSource" to effectiveWeights.source,
            "weights" to effectiveWeights.table(),
            "weightsAdjustable" to ADJUSTABLE_WEIGHTS,
            "weightsFixed" to ToolkitIo.json("big_literal_consts" to FIXED_BIG_LITERALS_WEIGHT,
                "note" to "bigLiterals 特征在 survey 里恒为 0（本工具不统计它），系数固定、不参与 weights。"),
            "scoreFormula" to "contribution(term) = normalizedValue(term) * weights[term]；score = min(100, Σ contribution)；rawScore = Σ contribution（不封顶）。score 封顶是历史口径，权重校准请看 rawScore。",
            "normalizations" to JsonObject().apply { NORMALIZATIONS.forEach { (key, value) -> addProperty(key, value) } },
            "weightsNote" to "weights 只接受非负整数对象，键必须是 7 个可调特征之一；未给的项用默认值；未知键 / 负数 / 全 0 -> INVALID_ARGUMENT；权重 0 = 关闭该项（显式给 0 的项仍出现在 contributions 里，值为 0）。",
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



