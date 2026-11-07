package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Field
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.SwitchPayload
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.value.IntEncodedValue
import java.io.File
import java.util.zip.ZipFile

/**
 * Binder/ AIDL 接口号表、DEX 类/方法分页，以及 implementations/overrides xref。
 *
 * <p>本类只做纯 DEX 读取，不访问工作区磁盘；Lead 的 dispatch 可用
 * ToolkitDexInterface(dexes(workspace, session).toList()) 构造，path 分支可用
 * ToolkitDexInterface.fromPath(root, path)。所有输出是 data JsonObject，不含 ok 包络。</p>
 *
 * <p>transactCode 推导有两条路：canonical descriptor 路径优先读 Stub/接口类里的 TRANSACTION_xxx
 * 静态字段；接口类不存在、方法表为空或没有静态字段时 auto 走 string_const 兜底：
 * 字符串池命中 writeInterfaceToken + 同一方法的 transact 立即数，或 onTransact 的 packed/sparse-switch。
 * 两条路径都不会执行 Binder、不会调用 onTransact，也不承诺 runtime 实际 transact 号；返回值必须同时
 * 带 transactSource、evidence、confidence 和 warnings。</p>
 */
internal class ToolkitDexInterface(dexes: List<Pair<String, ToolkitDex>>) {

    private data class Entry(
        val name: String,
        val toolkit: ToolkitDex,
        val classes: List<ClassDef>,
    )

    private data class ClassRef(val entry: Entry, val definition: ClassDef) {
        val type: String get() = definition.type
    }

    private data class ChainNode(
        val type: String,
        val entry: Entry?,
        val definition: ClassDef?,
        val depth: Int,
        val external: Boolean,
    )

    private data class FieldValue(val owner: String, val fieldName: String, val value: Int)

    private data class MethodRow(
        val key: String,
        val name: String,
        val descriptor: String,
        val params: List<String>,
        val returnType: String,
        val declaredIn: String,
        var isOverride: Boolean,
        var fallbackCode: Int,
        var staticField: FieldValue? = null,
        var code: Int = 0,
        var source: String = "declaration_order",
        var oneway: Boolean? = null,
        var onewaySource: String = "unknown",
        var proxyTransactCode: Int? = null,
    )

    private data class TransactCall(val code: Int, val methodName: String)

    private data class FallbackHit(
        val code: Int,
        val methodName: String?,
        val methodDescriptor: String?,
        val callSiteLocator: String,
        val dexEntry: String,
        val evidence: String,
        val confidence: String,
        val token: String?,
        val transactMethod: String?,
        val side: String,
    )

    private val entries: List<Entry> = dexes.map { (name, toolkit) ->
        Entry(name, toolkit, ToolkitDexSupport.classes(toolkit).sortedBy { it.type })
    }

    private val classIndex: Map<String, List<ClassRef>> = LinkedHashMap<String, MutableList<ClassRef>>().apply {
        this@ToolkitDexInterface.entries.forEach { entry ->
            entry.classes.forEach { definition ->
                getOrPut(definition.type) { ArrayList() }.add(ClassRef(entry, definition))
            }
        }
    }

    private val navigation by lazy {
        ToolkitDexNavigation(entries.map { it.name to it.toolkit })
    }

    private fun allClassRefs(): Sequence<ClassRef> = entries.asSequence().flatMap { entry ->
        entry.classes.asSequence().map { ClassRef(entry, it) }
    }

    // ------------------------------------------------------------ interface table

    /**
     * dex_interface_table 数据。
     *
     * params: descriptor（Lpkg/IFoo; 或 pkg.IFoo）、dexEntry、mode（auto|descriptor|string_const，默认 auto）。
     * canonical 模式返回 methods + server(Stub) + client(Proxy) + comparison + transactSource；
     * string_const 模式返回 code/callSiteLocator/evidence/confidence 候选。
     */
    fun interfaceTable(args: JsonObject): JsonObject {
        val raw = optionalString(args, "descriptor")
            ?: optionalString(args, "locator")
            ?: throw ToolkitException(
                "INVALID_ARGUMENT",
                "缺少 descriptor。nextActions: 先 apk_dex_list_classes 或 apk_search 找到接口 descriptor，" +
                    "再传 descriptor=Lpkg/IFoo;（也接受 pkg.IFoo）。",
                "descriptor",
            )
        val mode = optionalString(args, "mode")?.lowercase() ?: "auto"
        if (mode !in setOf("auto", "descriptor", "string_const")) {
            throw ToolkitException(
                "INVALID_ARGUMENT",
                "mode 必须是 auto、descriptor 或 string_const（收到 " + mode + "）。nextActions: 默认 auto 即可。",
                "mode",
            )
        }
        if (mode == "descriptor") return descriptorInterfaceTable(args, raw)
        if (mode == "string_const") return stringConstInterfaceTable(args, raw)

        val canonical = runCatching { descriptorInterfaceTable(args, raw) }.getOrNull()
        val canonicalComplete = canonical != null &&
            (canonical.getAsJsonArray("methods")?.size() ?: 0) > 0 &&
            canonical.get("transactSource")?.asString == "static_field"
        if (canonicalComplete) return canonical
        val fallback = stringConstInterfaceTable(args, raw)
        if ((fallback.getAsJsonArray("methods")?.size() ?: 0) > 0) {
            fallback.addProperty("canonicalTried", canonical != null)
            return fallback
        }
        if (canonical != null && (canonical.getAsJsonArray("methods")?.size() ?: 0) > 0) {
            // canonical 至少有方法；兜底没有证据时保留 canonical（declaration_order），
            // 并把 fallback 的 warnings/nextActions 一并交给调用方。
            canonical.addProperty("fallbackAttempted", true)
            canonical.add("fallbackWarnings", fallback.getAsJsonArray("warnings") ?: JsonArray())
            canonical.add("fallbackNextActions", fallback.getAsJsonArray("nextActions") ?: JsonArray())
            return canonical
        }
        return fallback
    }

    private fun descriptorInterfaceTable(args: JsonObject, raw: String): JsonObject {
        val type = normalizeType(raw, "descriptor")
        val dexEntry = optionalString(args, "dexEntry")
        val target = requireClass(type, dexEntry, "descriptor")
        if (target.definition.accessFlags and ACC_INTERFACE == 0) {
            throw ToolkitException(
                "NOT_AN_INTERFACE",
                "类不是 interface：" + type + "。nextActions: 先用 apk_dex_hierarchy 确认类类型；" +
                    "本工具只对 Binder/AIDL interface 推导事务号。",
                "descriptor",
            )
        }
        val warnings = LinkedHashSet<String>()
        val chain = collectChain(target, warnings)
        val byName = collectTransactionFields(chain, warnings)
        val rows = LinkedHashMap<String, MethodRow>()
        chain.forEach nodeLoop@ { node ->
            val definition = node.definition ?: return@nodeLoop
            definition.methods.forEach methodLoop@ { method ->
                if (method.name.startsWith("<")) return@methodLoop
                val key = methodSignature(method)
                val existing = rows[key]
                if (existing == null) {
                    rows[key] = MethodRow(
                        key = key,
                        name = method.name,
                        descriptor = methodId(node.type, method),
                        params = method.parameterTypes.map { it.toString() },
                        returnType = method.returnType,
                        declaredIn = node.type,
                        isOverride = false,
                        fallbackCode = 0,
                    )
                } else {
                    // 目标接口先入表，父接口的同签名声明只把子接口行标成 override。
                    existing.isOverride = true
                }
            }
        }
        // fallback 用父接口在前、子接口在后的声明顺序；AIDL 继承时父接口事务通常先编号。
        // 这只是无法读取静态字段时的下界，仍由 transactSource 和 warnings 明确标注。
        val fallbackOrder = LinkedHashMap<String, Int>()
        chain.asReversed().forEach nodeLoop@ { node ->
            val definition = node.definition ?: return@nodeLoop
            definition.methods.forEach methodLoop@ { method ->
                if (method.name.startsWith("<")) return@methodLoop
                val key = methodSignature(method)
                if (!fallbackOrder.containsKey(key)) fallbackOrder[key] = FIRST_CALL_TRANSACTION + fallbackOrder.size
            }
        }
        rows.values.forEachIndexed { index, row ->
            row.fallbackCode = fallbackOrder[row.key] ?: (FIRST_CALL_TRANSACTION + index)
            val field = byName[row.name]
            if (field != null) {
                row.staticField = field
                row.code = field.value
                row.source = "static_field"
            } else {
                row.code = row.fallbackCode
                row.source = "declaration_order"
            }
        }

        val stubType = type.removeSuffix(";") + "\$Stub;"
        val proxyType = stubType.removeSuffix(";") + "\$Proxy;"
        val stub = findOptional(stubType, target.entry.name)
        val proxy = findOptional(proxyType, target.entry.name)
        if (stub == null) warnings.add("STUB_NOT_FOUND: 没有找到 " + stubType + "，server 侧号表为空")
        if (proxy == null) warnings.add("PROXY_NOT_FOUND: 没有找到 " + proxyType + "，client 侧号表为空")
        val proxyMethods = proxy?.definition?.methods?.associateBy { methodSignature(it) }.orEmpty()
        rows.values.forEach { row ->
            val proxyMethod = proxyMethods[row.key]
            val oneway = onewayOf(proxyMethod)
            row.oneway = oneway
            row.onewaySource = if (oneway == null) "unknown" else "proxy_transact_flags"
            row.proxyTransactCode = proxyTransactCode(proxyMethod)
        }

        val server = side("server", stub, rows)
        val client = side("client", proxy, rows)
        val comparison = compare(rows, stub, proxy)
        val sources = rows.values.map { it.source }.toSet()
        val topSource = when {
            rows.isEmpty() -> "declaration_order"
            sources.size == 1 -> sources.first()
            else -> "mixed"
        }
        val methods = JsonArray().apply { rows.values.forEach { add(methodRowJson(it)) } }
        return ToolkitIo.json(
            "interface" to type,
            "descriptor" to type,
            "dexEntry" to target.entry.name,
            "firstCallTransaction" to FIRST_CALL_TRANSACTION,
            "transactSource" to topSource,
            "transactSourceNote" to transactSourceNote(topSource),
            "transactSourceWarnings" to transactWarnings(rows.values),
            "chain" to chainJson(chain),
            "methods" to methods,
            "methodCount" to rows.size,
            "server" to server,
            "client" to client,
            "comparison" to comparison,
            "warnings" to warnings.toList(),
            "notes" to listOf(
                "transactCode 不是运行时断言：优先 static_field，缺失时才是 declaration_order。",
                "server/client 只列出能在 DEX 中匹配到接口签名的 Stub/Proxy 方法；缺失不会伪造。",
                "oneway 仅从 Proxy 的 transact 调用 flags 读取；读不到时为 null，不猜测。",
            ),
        )
    }

    // ------------------------------------------------------------ string_const fallback

    /**
     * 裸 transact / 字符串常量兜底：不要求接口类存在。
     * 只看同一方法内的 writeInterfaceToken(token) + transact(N) 立即数，以及 onTransact 的 packed/sparse-switch。
     */
    private fun stringConstInterfaceTable(args: JsonObject, raw: String): JsonObject {
        val tokens = tokenCandidates(raw)
        val dexEntry = optionalString(args, "dexEntry")
        if (dexEntry != null && entries.none { it.name == dexEntry }) {
            throw ToolkitException("DEX_ENTRY_NOT_FOUND", "DEX 条目不存在：" + dexEntry, "dexEntry")
        }
        val warnings = LinkedHashSet<String>()
        val hits = ArrayList<FallbackHit>()
        var stringPoolHits = 0
        var tokenMentions = 0
        entries.forEach entryLoop@ { entry ->
            if (dexEntry != null && entry.name != dexEntry) return@entryLoop
            val stringReferences = try {
                ToolkitDexSupport.dexFile(entry.toolkit).stringReferences
            } catch (failure: Exception) {
                warnings.add("STRING_POOL_READ_FAILED: " + entry.name + "：" + (failure.message ?: failure.javaClass.simpleName))
                emptyList()
            }
            val poolMatches = stringReferences.count { it.string in tokens }
            stringPoolHits += poolMatches
            if (poolMatches == 0) return@entryLoop
            entry.classes.forEach classLoop@ { definition ->
                definition.methods.forEach methodLoop@ { method ->
                    val methodTokens = interfaceTokenStrings(method).filter { it in tokens }
                    if (methodTokens.isEmpty()) return@methodLoop
                    tokenMentions += methodTokens.size
                    val calls = transactCalls(method)
                    if (calls.isEmpty()) {
                        warnings.add("WRITE_INTERFACE_TOKEN_WITHOUT_CONST_TRANSACT: " + methodId(definition.type, method))
                    } else {
                        calls.forEach { call ->
                            hits.add(FallbackHit(
                                code = call.code,
                                methodName = method.name,
                                methodDescriptor = methodId(definition.type, method),
                                callSiteLocator = "dex_method:" + methodId(definition.type, method),
                                dexEntry = entry.name,
                                evidence = "writeInterfaceToken+transact",
                                confidence = "high",
                                token = methodTokens.first(),
                                transactMethod = call.methodName,
                                side = "client",
                            ))
                        }
                    }
                }
                definition.methods.filter { it.name == "onTransact" }.forEach { onTransact ->
                    val mapped = onTransactHits(definition, onTransact, entry, tokens, warnings)
                    hits.addAll(mapped)
                }
            }
        }
        if (stringPoolHits == 0) {
            warnings.add("STRING_POOL_TOKEN_NOT_FOUND: 没有 DEX 字符串池命中 " + tokens.joinToString())
        } else if (tokenMentions == 0) {
            warnings.add("STRING_POOL_ONLY: 字符串池命中 " + stringPoolHits + " 处，但没有可读的 writeInterfaceToken 参数")
        }
        val distinct = LinkedHashMap<String, FallbackHit>()
        hits.forEach { hit ->
            val key = listOf(hit.code, hit.callSiteLocator, hit.methodDescriptor ?: "", hit.evidence, hit.side).joinToString("|")
            distinct.putIfAbsent(key, hit)
        }
        val sorted = distinct.values.sortedWith(compareBy({ it.code }, { it.callSiteLocator }, { it.evidence }))
        if (sorted.isEmpty()) {
            warnings.add("NO_STRING_CONST_TRANSACT_CANDIDATE: 没有同时命中 writeInterfaceToken+transact 或 onTransact-packed-switch 的候选")
        }
        val clientHits = sorted.filter { it.side == "client" }
        val serverHits = sorted.filter { it.side == "server" }
        val methods = JsonArray().apply { sorted.forEach { add(fallbackHitJson(it)) } }
        return ToolkitIo.json(
            "interface" to raw,
            "descriptor" to raw,
            "mode" to "string_const",
            "firstCallTransaction" to FIRST_CALL_TRANSACTION,
            "transactSource" to "string_const+transact_const",
            "transactSourceNote" to "仅来自字符串常量 + transact/onTransact 立即数，不含 canonical descriptor 号表",
            "stringTokens" to tokens.toList(),
            "stringPoolHits" to stringPoolHits,
            "methods" to methods,
            "methodCount" to sorted.size,
            "codes" to byCodeJson(sorted),
            "server" to fallbackSide("server", serverHits),
            "client" to fallbackSide("client", clientHits),
            "warnings" to warnings.toList(),
            "nextActions" to fallbackNextActions(raw),
            "notes" to listOf(
                "兜底模式不要求接口类存在；结果不是 canonical static_field 号表。",
                "callSiteLocator 可直接交给 apk_decompile_method/apk_read_text 复核。",
                "同一 code 多来源会在 methods 和 codes[].sources 中全部保留。",
            ),
        )
    }

    private fun tokenCandidates(raw: String): Set<String> {
        val result = LinkedHashSet<String>()
        val trimmed = raw.trim()
        result.add(trimmed)
        val type = runCatching { normalizeType(trimmed, "descriptor") }.getOrNull()
        if (type != null) {
            result.add(type)
            val slash = type.removePrefix("L").removeSuffix(";")
            result.add(slash)
            result.add(slash.replace('/', '.'))
        }
        result.removeIf { it.isEmpty() }
        return result
    }

    private fun interfaceTokenStrings(method: Method): Set<String> {
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val result = LinkedHashSet<String>()
        for (index in instructions.indices) {
            val instruction = instructions[index]
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (!reference.name.equals("writeInterfaceToken", ignoreCase = true)) continue
            val tokenRegister = argumentRegister(instruction, 0) ?: continue
            val token = stringWrittenToRegisterBefore(instructions, index, tokenRegister) ?: continue
            result.add(token)
        }
        return result
    }

    private fun stringWrittenToRegisterBefore(instructions: List<org.jf.dexlib2.iface.instruction.Instruction>, index: Int, register: Int): String? {
        for (previous in (index - 1) downTo maxOf(0, index - 64)) {
            val candidate = instructions[previous]
            if (candidate !is OneRegisterInstruction || candidate.registerA != register) continue
            val reference = (candidate as? ReferenceInstruction)?.reference as? StringReference ?: continue
            return reference.string
        }
        return null
    }

    private fun transactCalls(method: Method): List<TransactCall> {
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val result = ArrayList<TransactCall>()
        for (index in instructions.indices) {
            val instruction = instructions[index]
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (!(reference.name.equals("transact", ignoreCase = true) || reference.name.equals("transactRemote", ignoreCase = true))) continue
            val codeRegister = argumentRegister(instruction, 0) ?: continue
            val code = literalAssignedBefore(instructions, index, codeRegister) ?: continue
            result.add(TransactCall(code, reference.name))
        }
        return result
    }

    private fun literalAssignedBefore(instructions: List<org.jf.dexlib2.iface.instruction.Instruction>, index: Int, register: Int): Int? {
        for (previous in (index - 1) downTo maxOf(0, index - 64)) {
            val candidate = instructions[previous]
            if (candidate !is OneRegisterInstruction || candidate.registerA != register) continue
            val literal = (candidate as? WideLiteralInstruction)?.wideLiteral ?: continue
            return literal.toInt()
        }
        return null
    }

    private fun onTransactHits(
        definition: ClassDef,
        method: Method,
        entry: Entry,
        tokens: Set<String>,
        warnings: MutableSet<String>,
    ): List<FallbackHit> {
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        if (instructions.isEmpty()) return emptyList()
        val offsets = IntArray(instructions.size)
        var cursor = 0
        instructions.forEachIndexed { index, instruction ->
            offsets[index] = cursor
            cursor += instruction.codeUnits
        }
        val offsetToIndex = HashMap<Int, Int>()
        offsets.forEachIndexed { index, value -> offsetToIndex.putIfAbsent(value, index) }
        val hits = ArrayList<FallbackHit>()
        for (index in instructions.indices) {
            val instruction = instructions[index]
            val opcode = instruction.opcode.name
            if (!opcode.startsWith("packed-switch") && !opcode.startsWith("sparse-switch")) continue
            if (instruction !is OffsetInstruction) continue
            val payloadIndex = offsetToIndex[offsets[index] + instruction.codeOffset] ?: continue
            val payload = instructions[payloadIndex] as? SwitchPayload ?: continue
            payload.switchElements.forEach { element ->
                val targetIndex = offsetToIndex[offsets[index] + element.offset] ?: return@forEach
                val target = caseTargetInfo(instructions, targetIndex, tokens)
                val confidence = when {
                    target.first == null -> "low"
                    target.third != null -> "high"
                    else -> "medium"
                }
                hits.add(FallbackHit(
                    code = element.key,
                    methodName = target.first,
                    methodDescriptor = target.second,
                    callSiteLocator = "dex_method:" + methodId(definition.type, method),
                    dexEntry = entry.name,
                    evidence = "onTransact-packed-switch",
                    confidence = confidence,
                    token = target.third,
                    transactMethod = null,
                    side = "server",
                ))
            }
        }
        if (hits.isEmpty()) warnings.add("ONTRANSACT_SWITCH_NOT_FOUND: " + methodId(definition.type, method))
        return hits
    }

    private fun caseTargetInfo(
        instructions: List<org.jf.dexlib2.iface.instruction.Instruction>,
        start: Int,
        tokens: Set<String>,
    ): Triple<String?, String?, String?> {
        var token: String? = null
        val end = minOf(instructions.size, start + 48)
        for (index in start until end) {
            val instruction = instructions[index]
            if (instruction is SwitchPayload) break
            val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
            if (reference is StringReference && reference.string in tokens) token = reference.string
            if (reference is MethodReference) {
                val name = reference.name
                if (name.startsWith("<")) continue
                if (name in IGNORED_ONTTRANSACT_CALLS) continue
                return Triple(name, referenceId(reference), token)
            }
        }
        return Triple(null, null, token)
    }

    private fun referenceId(reference: MethodReference): String =
        reference.definingClass + "->" + reference.name + "(" +
            reference.parameterTypes.joinToString("") + ")" + reference.returnType

    private fun fallbackHitJson(hit: FallbackHit): JsonObject = ToolkitIo.json(
        "code" to hit.code,
        "transactCode" to hit.code,
        "method" to hit.methodName,
        "methodDescriptor" to hit.methodDescriptor,
        "callSiteLocator" to hit.callSiteLocator,
        "dexEntry" to hit.dexEntry,
        "evidence" to hit.evidence,
        "confidence" to hit.confidence,
        "token" to hit.token,
        "transactMethod" to hit.transactMethod,
        "side" to hit.side,
    )

    private fun byCodeJson(hits: List<FallbackHit>): JsonArray = JsonArray().apply {
        hits.groupBy { it.code }.toSortedMap().forEach { (code, values) ->
            add(ToolkitIo.json(
                "code" to code,
                "sources" to values.map { fallbackHitJson(it) },
                "method" to values.firstNotNullOfOrNull { it.methodName },
                "confidence" to values.maxByOrNull { confidenceRank(it.confidence) }?.confidence,
            ))
        }
    }

    private fun confidenceRank(value: String): Int = when (value) {
        "high" -> 3
        "medium" -> 2
        else -> 1
    }

    private fun fallbackSide(label: String, hits: List<FallbackHit>): JsonObject = ToolkitIo.json(
        "side" to label,
        "present" to hits.isNotEmpty(),
        "methods" to hits.map { fallbackHitJson(it) },
        "methodCount" to hits.size,
    )

    private fun fallbackNextActions(raw: String): List<String> = listOf(
        "先用 apk_search 搜字符串 " + raw + "，确认 token 在 DEX 字符串池",
        "对返回的 callSiteLocator 用 apk_decompile_method 或 apk_read_text 复核 transact 常量",
        "若接口类其实存在，传 mode=descriptor 或 apk_dex_hierarchy 校验 canonical 号表",
    )

    private fun transactSourceNote(source: String): String = when (source) {
        "static_field" -> "所有行都用 TRANSACTION_xxx 静态字段值"
        "declaration_order" -> "没有可用静态字段；按当前接口到父接口的声明顺序从 1 开始推导，需用 onTransact/真实调用复核"
        "string_const+transact_const" -> "接口类不可用；从字符串池 writeInterfaceToken + transact 立即数/onTransact switch 提取，证据见 evidence"
        else -> "部分行用静态字段、部分行退化为声明顺序；mixed 结果不保证完整"
    }

    private fun transactWarnings(rows: Collection<MethodRow>): List<String> {
        if (rows.isEmpty()) return listOf("接口没有可推导的方法")
        val result = ArrayList<String>()
        if (rows.any { it.source == "declaration_order" }) {
            result.add("存在 declaration_order 行；AIDL 编号可能随父接口/编译器变化")
        }
        if (rows.any { it.staticField != null && it.code != it.fallbackCode }) {
            result.add("静态字段值与声明顺序不一致，说明必须使用 static_field 而不是猜顺序")
        }
        return result
    }

    private fun chainJson(chain: List<ChainNode>): JsonArray = JsonArray().apply {
        chain.forEach { node ->
            add(ToolkitIo.json(
                "type" to node.type,
                "dexEntry" to node.entry?.name,
                "depth" to node.depth,
                "external" to node.external,
                "methodCount" to (node.definition?.methods?.count { !it.name.startsWith("<") } ?: 0),
            ))
        }
    }

    private fun methodRowJson(row: MethodRow): JsonObject = ToolkitIo.json(
        "method" to row.name,
        "descriptor" to row.descriptor,
        "transactCode" to row.code,
        "transactSource" to row.source,
        "oneway" to row.oneway,
        "onewaySource" to row.onewaySource,
        "params" to row.params,
        "returnType" to row.returnType,
        "isOverride" to row.isOverride,
        "declaredIn" to row.declaredIn,
        "fallbackTransactCode" to row.fallbackCode,
        "transactField" to row.staticField?.fieldName,
        "proxyTransactCode" to row.proxyTransactCode,
    )

    private fun side(label: String, ref: ClassRef?, rows: Map<String, MethodRow>): JsonObject {
        if (ref == null) {
            return ToolkitIo.json(
                "side" to label,
                "present" to false,
                "type" to null,
                "dexEntry" to null,
                "methods" to JsonArray(),
                "methodCount" to 0,
                "note" to "没有找到对应的 Stub/Proxy 类；side 不伪造",
            )
        }
        val methods = ref.definition.methods.associateBy { methodSignature(it) }
        val ownFields = transactionFieldsFor(ref)
        val items = JsonArray()
        rows.values.forEach { row ->
            val method = methods[row.key] ?: return@forEach
            val ownField = ownFields[row.name]
            val code = ownField?.value ?: row.code
            val source = if (ownField != null) "static_field" else row.source
            items.add(ToolkitIo.json(
                "method" to row.name,
                "descriptor" to methodId(ref.type, method),
                "transactCode" to code,
                "transactSource" to source,
                "oneway" to row.oneway,
                "isOverride" to true,
                "declaredIn" to row.declaredIn,
                "locator" to "dex_method:" + methodId(ref.type, method),
                "dexEntry" to ref.entry.name,
                "transactField" to (ownField?.fieldName ?: row.staticField?.fieldName),
                "proxyTransactCode" to if (label == "client") proxyTransactCode(method) else null,
            ))
        }
        return ToolkitIo.json(
            "side" to label,
            "present" to true,
            "type" to ref.type,
            "dexEntry" to ref.entry.name,
            "methods" to items,
            "methodCount" to items.size(),
            "onTransact" to (label == "server" && ref.definition.methods.any { it.name == "onTransact" }),
        )
    }

    private fun compare(rows: Map<String, MethodRow>, stub: ClassRef?, proxy: ClassRef?): JsonObject {
        val stubMethods = stub?.definition?.methods?.associateBy { methodSignature(it) }.orEmpty()
        val proxyMethods = proxy?.definition?.methods?.associateBy { methodSignature(it) }.orEmpty()
        val missingOnServer = rows.values.filter { it.key !in stubMethods }.map { it.name }
        val missingOnClient = rows.values.filter { it.key !in proxyMethods }.map { it.name }
        val orderMismatches = rows.values.filter { it.staticField != null && it.code != it.fallbackCode }.map {
            ToolkitIo.json(
                "method" to it.name,
                "staticFieldCode" to it.code,
                "declarationOrderCode" to it.fallbackCode,
                "field" to it.staticField?.fieldName,
                "owner" to it.staticField?.owner,
            )
        }
        val proxyCodeMismatches = rows.values.filter { it.proxyTransactCode != null && it.proxyTransactCode != it.code }.map {
            ToolkitIo.json(
                "method" to it.name,
                "tableTransactCode" to it.code,
                "proxyTransactCode" to it.proxyTransactCode,
            )
        }
        return ToolkitIo.json(
            "serverMethodCount" to stubMethods.size,
            "clientMethodCount" to proxyMethods.size,
            "missingOnServer" to missingOnServer,
            "missingOnClient" to missingOnClient,
            "orderMismatches" to orderMismatches,
            "proxyCodeMismatches" to proxyCodeMismatches,
            "serverClientSameCode" to (missingOnServer.isEmpty() && missingOnClient.isEmpty() && proxyCodeMismatches.isEmpty()),
        )
    }

    private fun collectChain(target: ClassRef, warnings: MutableSet<String>): List<ChainNode> {
        val result = ArrayList<ChainNode>()
        val seen = LinkedHashSet<String>()
        fun visit(type: String, preferredEntry: String?, depth: Int) {
            if (!seen.add(type)) return
            val ref = findOptional(type, preferredEntry)
            if (ref == null) {
                result.add(ChainNode(type, null, null, depth, external = true))
                warnings.add("INTERFACE_CHAIN_EXTERNAL: " + type + " 不在当前 DEX 集合，父接口方法无法展开")
                return
            }
            result.add(ChainNode(type, ref.entry, ref.definition, depth, external = false))
            ref.definition.interfaces.forEach { parent -> visit(parent, ref.entry.name, depth + 1) }
        }
        visit(target.type, target.entry.name, 0)
        return result
    }

    private fun transactionFieldsFor(ref: ClassRef): Map<String, FieldValue> {
        val result = LinkedHashMap<String, FieldValue>()
        ref.definition.fields.forEach { field ->
            if (!field.name.startsWith("TRANSACTION_")) return@forEach
            if (field.accessFlags and ACC_STATIC == 0) return@forEach
            val value = (field.initialValue as? IntEncodedValue)?.value ?: return@forEach
            result[field.name.removePrefix("TRANSACTION_")] = FieldValue(ref.type, field.name, value)
        }
        return result
    }

    private fun collectTransactionFields(chain: List<ChainNode>, warnings: MutableSet<String>): Map<String, FieldValue> {
        val result = LinkedHashMap<String, FieldValue>()
        chain.forEach nodeLoop@ { node ->
            val definition = node.definition ?: return@nodeLoop
            val owners = ArrayList<ClassRef>()
            findOptional(node.type.removeSuffix(";") + "\$Stub;", node.entry?.name)?.let { owners.add(it) }
            findOptional(node.type.removeSuffix(";") + "\$Stub;\$Proxy;", node.entry?.name)?.let { owners.add(it) }
            findOptional(node.type, node.entry?.name)?.let { owners.add(it) }
            owners.forEach ownerLoop@ { owner ->
                owner.definition.fields.forEach fieldLoop@ { field ->
                    if (!field.name.startsWith("TRANSACTION_")) return@fieldLoop
                    if (field.accessFlags and ACC_STATIC == 0) return@fieldLoop
                    val value = (field.initialValue as? IntEncodedValue)?.value ?: return@fieldLoop
                    val methodName = field.name.removePrefix("TRANSACTION_")
                    if (!result.containsKey(methodName)) {
                        result[methodName] = FieldValue(owner.type, field.name, value)
                    }
                }
            }
        }
        if (result.isEmpty()) {
            warnings.add("TRANSACTION_FIELDS_NOT_FOUND: 没有可用的 TRANSACTION_xxx 静态字段，号表退化为声明顺序")
        }
        return result
    }

    private fun findOptional(type: String, preferredEntry: String?): ClassRef? {
        val candidates = classIndex[type].orEmpty()
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.entry.name == preferredEntry } ?: candidates.first()
    }

    private fun requireClass(type: String, dexEntry: String?, argument: String): ClassRef {
        if (dexEntry != null && entries.none { it.name == dexEntry }) {
            throw ToolkitException(
                "DEX_ENTRY_NOT_FOUND",
                "DEX 条目不存在：" + dexEntry + "。nextActions: 用 apk_dex_list_classes 或 apk_search 查 dexEntry，" +
                    "再重试；不要传文件路径冒充 dexEntry。",
                "dexEntry",
            )
        }
        val occurrences = classIndex[type].orEmpty().filter { dexEntry == null || it.entry.name == dexEntry }
        if (occurrences.isEmpty()) {
            throw ToolkitException(
                "LOCATOR_NOT_FOUND",
                "找不到接口 " + type + "。nextActions: 先 apk_dex_list_classes 用 prefix=<包名> 或 " +
                    "apk_search 找 descriptor；descriptor 形状示例 Lpkg/IFoo;；跨 DEX 同名类传 dexEntry 或 uniqueLocator。",
                argument,
            )
        }
        if (occurrences.size > 1) {
            throw ToolkitException(
                "LOCATOR_AMBIGUOUS",
                "多个 DEX 含接口 " + type + "（" + occurrences.joinToString { it.entry.name } +
                    "）。nextActions: 传 dexEntry，或先用 apk_dex_list_classes 看 dexEntry。",
                "dexEntry",
            )
        }
        return occurrences.single()
    }

    // ------------------------------------------------------------ list classes/methods

    fun listClasses(args: JsonObject): JsonObject {
        val dexEntry = optionalString(args, "dexEntry")
        if (dexEntry != null && entries.none { it.name == dexEntry }) {
            throw ToolkitException("DEX_ENTRY_NOT_FOUND", "DEX 条目不存在：" + dexEntry, "dexEntry")
        }
        val prefixRaw = optionalString(args, "prefix")
        val packageRaw = optionalString(args, "packageFilter")
        val prefix = prefixRaw?.let { typePrefix(it) }
        val packageType = packageRaw?.let { typePrefix(it) }
        // McpApkToolkit 内部注入 _scope/_revision：游标只对同 workspace/session、同 revision、同过滤条件有效。
        val scope = optionalString(args, "_scope").orEmpty()
        val scopeRevision = optionalString(args, "_revision").orEmpty()
        val filter = "classes|" + (prefix ?: "") + "|" + (packageType ?: "") + "|" + (dexEntry ?: "") + "|" +
            entries.joinToString(",") { it.name } + "|" + scope + "|" + scopeRevision
        val offset = cursorOffset(args, "classes", filter)
        val limit = args.tInt("limit", 200, 1, 2000)
        val all = ArrayList<JsonObject>()
        allClassRefs().forEach { ref ->
            if (dexEntry != null && ref.entry.name != dexEntry) return@forEach
            if (prefix != null && !ref.type.startsWith(prefix)) return@forEach
            if (packageType != null && !ref.type.startsWith(packageType)) return@forEach
            all.add(ToolkitIo.json(
                "type" to ref.type,
                "descriptor" to ref.type,
                "name" to simpleName(ref.type),
                "packageName" to packageName(ref.type),
                "superclass" to ref.definition.superclass,
                "interfaces" to ref.definition.interfaces,
                "accessFlags" to ref.definition.accessFlags,
                "access" to accessString(ref.definition.accessFlags),
                "isInterface" to ((ref.definition.accessFlags and ACC_INTERFACE) != 0),
                "dexEntry" to ref.entry.name,
                "locator" to "dex_class:" + ref.type,
                "uniqueLocator" to "dex_entry:" + ref.entry.name + "!dex_class:" + ref.type,
            ))
        }
        val total = all.size
        val page = all.drop(offset).take(limit)
        val next = if (offset + page.size < total) makeCursor("classes", offset + page.size, filter) else null
        return ToolkitIo.json(
            "items" to page,
            "total" to total,
            "returned" to page.size,
            "limit" to limit,
            "offset" to offset,
            "nextCursor" to next,
            "hasMore" to (next != null),
            "truncated" to (next != null),
            "cursorTool" to "apk_dex_list_classes",
            "nextCursorHint" to "把 nextCursor 原样传给 apk_dex_list_classes 的 cursor 字段；这是同工具游标，不能用于 apk_continue。",
            "cursorScope" to "workspace/session/revision/filter",
            "dexEntries" to entries.map { it.name },
            "filters" to ToolkitIo.json("prefix" to prefixRaw, "packageFilter" to packageRaw, "dexEntry" to dexEntry),
        )
    }

    fun listMethods(args: JsonObject): JsonObject {
        val dexEntry = optionalString(args, "dexEntry")
        if (dexEntry != null && entries.none { it.name == dexEntry }) {
            throw ToolkitException("DEX_ENTRY_NOT_FOUND", "DEX 条目不存在：" + dexEntry, "dexEntry")
        }
        val ownerRaw = optionalString(args, "owner")
        val prefixRaw = optionalString(args, "prefix")
        val owner = ownerRaw?.let { ownerMatcher(it) }
        val prefix = prefixRaw?.let { typePrefix(it) }
        val scope = optionalString(args, "_scope").orEmpty()
        val scopeRevision = optionalString(args, "_revision").orEmpty()
        val filter = "methods|" + (ownerRaw ?: "") + "|" + (prefix ?: "") + "|" + (dexEntry ?: "") + "|" +
            entries.joinToString(",") { it.name } + "|" + scope + "|" + scopeRevision
        val limit = args.tInt("limit", 200, 1, 5000)
        val offset = cursorOffset(args, "methods", filter)
        val all = ArrayList<JsonObject>()
        allClassRefs().forEach { ref ->
            if (dexEntry != null && ref.entry.name != dexEntry) return@forEach
            if (owner != null && !owner(ref.type)) return@forEach
            if (prefix != null && !ref.type.startsWith(prefix)) return@forEach
            ref.definition.methods.forEach { method ->
                all.add(ToolkitIo.json(
                    "name" to method.name,
                    "descriptor" to methodId(ref.type, method),
                    "access" to accessString(method.accessFlags),
                    "accessFlags" to method.accessFlags,
                    "returnType" to method.returnType,
                    "paramTypes" to method.parameterTypes.map { it.toString() },
                    "owner" to ref.type,
                    "dexEntry" to ref.entry.name,
                    "locator" to "dex_method:" + methodId(ref.type, method),
                    "uniqueLocator" to "dex_entry:" + ref.entry.name + "!dex_method:" + methodId(ref.type, method),
                ))
            }
        }
        val total = all.size
        val page = all.drop(offset).take(limit)
        val next = if (offset + page.size < total) makeCursor("methods", offset + page.size, filter) else null
        return ToolkitIo.json(
            "items" to page,
            "total" to total,
            "returned" to page.size,
            "limit" to limit,
            "offset" to offset,
            "nextCursor" to next,
            "hasMore" to (next != null),
            "truncated" to (next != null),
            "cursorTool" to "apk_dex_list_methods",
            "nextCursorHint" to "把 nextCursor 原样传给 apk_dex_list_methods 的 cursor 字段；这是同工具游标，不能用于 apk_continue。",
            "cursorScope" to "workspace/session/revision/filter",
            "dexEntries" to entries.map { it.name },
            "filters" to ToolkitIo.json("owner" to ownerRaw, "prefix" to prefixRaw, "dexEntry" to dexEntry),
        )
    }

    private fun cursorOffset(args: JsonObject, kind: String, filter: String): Int {
        val cursor = optionalString(args, "cursor") ?: return 0
        val parts = cursor.split(':')
        val tool = if (kind == "classes") "apk_dex_list_classes" else "apk_dex_list_methods"
        if (parts.size != 4 || parts[0] != "dxi1" || parts[1] != kind) {
            throw ToolkitException("INVALID_CURSOR", "分页 cursor 无效：" + cursor + "。该游标只属于 " + tool +
                "（同工具、同过滤条件、同 workspace/session/revision）；请不带 cursor 重新查询。nextActions: 不传 cursor 重新查询。", "cursor")
        }
        val expected = cursorHash(filter)
        if (parts[3] != expected) {
            throw ToolkitException("INVALID_CURSOR", "分页 cursor 与当前过滤条件不匹配：游标只对产生它的工具（同过滤条件、同 workspace/session/revision）有效；请不带 cursor 重新查询。nextActions: 不传 cursor 重新查询。", "cursor")
        }
        val offset = parts[2].toIntOrNull()
        if (offset == null || offset < 0) {
            throw ToolkitException("INVALID_CURSOR", "分页 cursor 偏移无效：" + cursor, "cursor")
        }
        return offset
    }

    private fun makeCursor(kind: String, offset: Int, filter: String): String =
        "dxi1:" + kind + ":" + offset + ":" + cursorHash(filter)

    private fun cursorHash(filter: String): String =
        ToolkitIo.version(filter.toByteArray(Charsets.UTF_8)).take(12)

    // ------------------------------------------------------------ xref

    fun implementations(args: JsonObject): JsonObject {
        val raw = args.tString("locator")
        val locator = normalizeLocator(raw)
        val result = hierarchy(normalizeXrefArgs(args, locator, false))
        val implementations = result.getAsJsonArray("subtypes") ?: JsonArray()
        val direct = JsonArray().apply {
            implementations.forEach { element ->
                val item = element.asJsonObject
                if (item.get("depth")?.asInt == 1) add(item)
            }
        }
        return ToolkitIo.json(
            "locator" to locator,
            "kind" to if (locator.startsWith("dex_method:")) "method" else "class",
            "target" to result.get("target"),
            "implementations" to implementations,
            "directImplementations" to direct,
            "summary" to result.get("summary"),
            "warnings" to result.get("warnings"),
        )
    }

    fun overrides(args: JsonObject): JsonObject {
        val raw = args.tString("locator")
        val locator = normalizeLocator(raw)
        if (!locator.startsWith("dex_method:")) {
            throw ToolkitException(
                "INVALID_ARGUMENT",
                "overrides 需要 dex_method locator（可传 type->name(params)return，或 dex_method:...）。" +
                    "nextActions: 先用 apk_dex_list_methods/apk_dex_search_members 找到精确方法，再调用。",
                "locator",
            )
        }
        val result = hierarchy(normalizeXrefArgs(args, locator, true))
        val overrides = result.getAsJsonObject("overrides")
        return ToolkitIo.json(
            "locator" to locator,
            "kind" to "method",
            "target" to result.get("target"),
            "overrides" to overrides,
            "summary" to result.get("summary"),
            "warnings" to result.get("warnings"),
        )
    }

    /** 合并入口：includeImplementations / includeOverrides 由调用方显式打开。 */
    fun xref(args: JsonObject): JsonObject {
        val includeImplementations = args.tBool("includeImplementations", false)
        val includeOverrides = args.tBool("includeOverrides", false)
        if (!includeImplementations && !includeOverrides) {
            throw ToolkitException(
                "INVALID_ARGUMENT",
                "需要 includeImplementations=true 或 includeOverrides=true。" +
                    "nextActions: 类实现者用 includeImplementations；方法重写者用 includeOverrides（必须 dex_method locator）。",
                "includeImplementations",
            )
        }
        return JsonObject().apply {
            if (includeImplementations) add("implementations", implementations(args))
            if (includeOverrides) add("overrides", overrides(args))
        }
    }

    private fun normalizeXrefArgs(args: JsonObject, locator: String, overrides: Boolean): JsonObject {
        val result = args.deepCopy()
        result.addProperty("locator", locator)
        result.addProperty("direction", "subtypes")
        result.addProperty("includeOverrides", overrides)
        if (!overrides) {
            result.addProperty("methodResolution", if (locator.startsWith("dex_method:")) "dispatch" else "not_applicable")
        } else if (!result.has("methodResolution")) {
            result.addProperty("methodResolution", "dispatch")
        }
        return result
    }

    private fun hierarchy(args: JsonObject): JsonObject {
        return try {
            navigation.hierarchy(args)
        } catch (failure: ToolkitException) {
            when (failure.code) {
                "DEX_CLASS_NOT_FOUND", "DEX_METHOD_NOT_FOUND" -> throw ToolkitException(
                    "LOCATOR_NOT_FOUND",
                    failure.message + "。nextActions: 先用 apk_dex_list_classes/apk_dex_list_methods 校验 locator，" +
                        "跨 DEX 同名类传 dexEntry。",
                    failure.argument ?: "locator",
                )
                "DEX_AMBIGUOUS_LOCATOR" -> throw ToolkitException(
                    "LOCATOR_AMBIGUOUS",
                    failure.message + "。nextActions: 传 dexEntry 或 uniqueLocator。",
                    failure.argument ?: "dexEntry",
                )
                else -> throw failure
            }
        }
    }

    private fun normalizeLocator(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("dex_class:") || value.startsWith("dex_method:") || value.startsWith("dex_entry:")) return value
        if (value.contains("->") || value.contains("(")) return "dex_method:" + value
        return "dex_class:" + normalizeType(value, "locator")
    }

    // ------------------------------------------------------------ path helpers

    companion object {
        const val FIRST_CALL_TRANSACTION = 1
        private const val ACC_PRIVATE = 0x2
        private const val ACC_STATIC = 0x8
        private const val ACC_FINAL = 0x10
        private const val ACC_NATIVE = 0x100
        private const val ACC_INTERFACE = 0x200
        private const val ACC_ABSTRACT = 0x400
        private const val ACC_SYNTHETIC = 0x1000
        private const val ACC_ANNOTATION = 0x2000
        private const val ACC_ENUM = 0x4000

        private val IGNORED_ONTTRANSACT_CALLS = setOf(
            "writeInterfaceToken", "enforceInterface", "writeNoException", "readException",
            "writeString", "readString", "writeInt", "readInt", "onTransact", "asBinder",
            "getInterfaceDescriptor", "toString", "equals", "hashCode", "readStrongBinder",
            "writeStrongBinder",
        )

        /** 给接线层生成结构化 nextActions；错误本身仍通过 ToolkitException.code/message 进入 error 对象。 */
        fun nextActionsFor(code: String): List<String> = when (code) {
            "LOCATOR_NOT_FOUND", "LOCATOR_AMBIGUOUS" -> listOf(
                "用 apk_dex_list_classes 或 apk_search 找 descriptor/dexEntry",
                "跨 DEX 同名类传 dexEntry 或 uniqueLocator",
                "确认 descriptor 形状为 Lpkg/IFoo;（也接受 pkg.IFoo）",
            )
            "DEX_ENTRY_NOT_FOUND" -> listOf("用 apk_dex_list_classes 查 dexEntry 后重试")
            "INVALID_CURSOR" -> listOf(
                "不带 cursor 重新查询，按返回的 nextCursor 顺序翻页",
                "apk_dex_list_classes/list_methods 的 nextCursor 是原工具 cursor，不能传给 apk_continue；反之 apk_continue 的 nextCursor 也不能回传这两个工具",
            )
            "NOT_AN_INTERFACE" -> listOf("用 apk_dex_hierarchy 确认类类型；只对 Binder/AIDL interface 推导事务号")
            "DEX_CONTAINER_READ_FAILED", "DEX_NOT_FOUND" -> listOf("用 apk_workspace_open 打开 APK，或传操作根内 .dex/.apk")
            else -> listOf("减小查询范围或检查 descriptor/owner 参数后重试")
        }

        /** 操作根内的 .dex 或 .apk；APK 只取 classes*.dex，不展开 resources。 */
        fun fromPath(root: File, path: String): ToolkitDexInterface = fromFile(ToolkitIo.resolve(root, path))

        fun fromFile(file: File): ToolkitDexInterface {
            require(file.isFile && file.length() <= ToolkitIo.MAX_APK) {
                "需要不超过 512 MiB 的 .dex 或 .apk 文件"
            }
            val lower = file.name.lowercase()
            if (lower.endsWith(".dex")) {
                val bytes = file.inputStream().use { ToolkitIo.read(it) }
                return ToolkitDexInterface(listOf(file.name to ToolkitDex(bytes)))
            }
            if (!lower.endsWith(".apk") && !lower.endsWith(".zip")) {
                throw ToolkitException(
                    "UNSUPPORTED_DEX_CONTAINER",
                    "只支持 .dex 或 .apk（收到 " + file.name + "）。nextActions: 先用 artifact_open 生成工作区，或传 .apk/.dex。",
                    "path",
                )
            }
            val dexes = ArrayList<Pair<String, ToolkitDex>>()
            try {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.name.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) }
                        .sortedBy { it.name }.forEach { entry ->
                            val bytes = zip.getInputStream(entry).use { ToolkitIo.read(it) }
                            dexes.add(entry.name to ToolkitDex(bytes))
                        }
                }
            } catch (failure: Exception) {
                throw ToolkitException(
                    "DEX_CONTAINER_READ_FAILED",
                    "无法读取 APK/DEX 容器：" + file.name + "：" + (failure.message ?: failure.javaClass.simpleName) +
                        "。nextActions: 用 apk_workspace_open 打开 APK，或确认文件是 ZIP/DEX。",
                    "path",
                )
            }
            if (dexes.isEmpty()) {
                throw ToolkitException(
                    "DEX_NOT_FOUND",
                    "容器里没有 classes*.dex：" + file.name + "。nextActions: 用 apk_workspace_open 后在 DEX 工具中指定 dexEntry。",
                    "path",
                )
            }
            return ToolkitDexInterface(dexes)
        }

        fun normalizeType(raw: String, argument: String): String {
            var value = raw.trim()
            if (value.startsWith("dex_class:")) value = value.substringAfter(':')
            if (value.startsWith("dex_entry:")) value = value.substringAfter('!')
            if (value.startsWith("[")) {
                throw ToolkitException("INVALID_ARGUMENT", "descriptor 不能是数组类型：" + raw, argument)
            }
            if (!value.startsWith("L") || !value.endsWith(";")) {
                value = value.removePrefix("L").removeSuffix(";")
                if (value.contains(';') || value.isBlank() || value.contains('[')) {
                    throw ToolkitException("INVALID_ARGUMENT", "descriptor 无效：" + raw + "；示例 Lpkg/IFoo;", argument)
                }
                value = "L" + value.replace('.', '/') + ";"
            }
            if (!value.matches(Regex("L[^;\\[.]+;"))) {
                throw ToolkitException("INVALID_ARGUMENT", "descriptor 无效：" + raw + "；示例 Lpkg/IFoo;", argument)
            }
            return value
        }

        private fun typePrefix(raw: String): String {
            var value = raw.trim()
            if (value.startsWith("dex_class:")) value = value.substringAfter(':')
            if (value.startsWith("L")) return value.removeSuffix(";").let { if (it.endsWith("/")) it else it }
            value = value.replace('.', '/')
            if (value.endsWith("/")) return "L" + value
            return "L" + value
        }

        private fun ownerMatcher(raw: String): (String) -> Boolean {
            val value = raw.trim()
            if (value.isEmpty()) return { true }
            if (value.startsWith("L") && value.endsWith(";")) {
                val normalized = normalizeType(value, "owner")
                return { it == normalized }
            }
            if (value.startsWith("L") || value.contains("/") || value.contains(".")) {
                val normalized = normalizeType(value, "owner")
                return { it == normalized }
            }
            return { it.endsWith("/" + value + ";") }
        }

        private fun methodSignature(method: Method): String {
            return method.name + "(" + method.parameterTypes.joinToString("") + ")" + method.returnType
        }

        private fun methodId(owner: String, method: Method): String = owner + "->" + methodSignature(method)

        private fun simpleName(type: String): String = type.substringAfterLast('/').removeSuffix(";")

        private fun packageName(type: String): String = type.substringAfter("L").substringBeforeLast('/').replace('/', '.')

        private fun accessString(flags: Int): String {
            val result = ArrayList<String>()
            if (flags and 0x1 != 0) result.add("public")
            if (flags and ACC_PRIVATE != 0) result.add("private")
            if (flags and 0x4 != 0) result.add("protected")
            if (flags and ACC_STATIC != 0) result.add("static")
            if (flags and ACC_FINAL != 0) result.add("final")
            if (flags and ACC_NATIVE != 0) result.add("native")
            if (flags and ACC_INTERFACE != 0) result.add("interface")
            if (flags and ACC_ABSTRACT != 0) result.add("abstract")
            if (flags and ACC_SYNTHETIC != 0) result.add("synthetic")
            if (flags and ACC_ANNOTATION != 0) result.add("annotation")
            if (flags and ACC_ENUM != 0) result.add("enum")
            return if (result.isEmpty()) "package-private" else result.joinToString(" ")
        }

        private fun optionalString(args: JsonObject, key: String): String? {
            val element = args.get(key) ?: return null
            if (element.isJsonNull) return null
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                throw ToolkitException("INVALID_ARGUMENT", key + " 必须是字符串", key)
            }
            return element.asString.trim().takeIf { it.isNotEmpty() }
        }

        /** 从 Proxy 的 transact 调用的第一个业务参数寄存器回溯 code；读不出返回 null。 */
        private fun proxyTransactCode(method: Method?): Int? {
            val instructions = method?.implementation?.instructions?.toList() ?: return null
            for (index in instructions.indices.reversed()) {
                val instruction = instructions[index]
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (reference.name != "transact" || reference.definingClass != "Landroid/os/IBinder;") continue
                val codeRegister = codeRegister(instruction) ?: continue
                for (previous in (index - 1) downTo maxOf(0, index - 48)) {
                    val candidate = instructions[previous]
                    if (candidate !is OneRegisterInstruction || candidate.registerA != codeRegister) continue
                    val literal = (candidate as? WideLiteralInstruction)?.wideLiteral ?: continue
                    return literal.toInt()
                }
            }
            return null
        }

        private fun codeRegister(instruction: org.jf.dexlib2.iface.instruction.Instruction): Int? =
            argumentRegister(instruction, 0)

        /** argumentIndex 不含 receiver；writeInterfaceToken/transact 都从第 0 个业务参数开始。 */
        private fun argumentRegister(instruction: org.jf.dexlib2.iface.instruction.Instruction, argumentIndex: Int): Int? {
            return when (instruction) {
                is FiveRegisterInstruction -> {
                    val registers = intArrayOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG)
                    registers.getOrNull(argumentIndex + 1)
                }
                is RegisterRangeInstruction -> {
                    if (instruction.registerCount > argumentIndex + 1) instruction.startRegister + argumentIndex + 1 else null
                }
                else -> null
            }
        }

        /**
         * 从 Proxy 方法里的 IBinder.transact(...) 最后一个参数读取 flags；返回 null 表示无法读出。
         * 只做方法内相邻常量回溯，不跨基本块，不猜测未知值。
         */
        private fun onewayOf(method: Method?): Boolean? {
            val instructions = method?.implementation?.instructions?.toList() ?: return null
            for (index in instructions.indices.reversed()) {
                val instruction = instructions[index]
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (reference.name != "transact" || reference.definingClass != "Landroid/os/IBinder;") continue
                val flagsRegister = lastRegister(instruction) ?: continue
                for (previous in (index - 1) downTo maxOf(0, index - 48)) {
                    val candidate = instructions[previous]
                    if (candidate !is OneRegisterInstruction || candidate.registerA != flagsRegister) continue
                    val literal = (candidate as? WideLiteralInstruction)?.wideLiteral ?: continue
                    return when (literal.toInt()) {
                        1 -> true
                        0 -> false
                        else -> null
                    }
                }
            }
            return null
        }

        private fun lastRegister(instruction: org.jf.dexlib2.iface.instruction.Instruction): Int? {
            return when (instruction) {
                is FiveRegisterInstruction -> when (instruction.registerCount) {
                    5 -> instruction.registerG
                    4 -> instruction.registerF
                    3 -> instruction.registerE
                    2 -> instruction.registerD
                    1 -> instruction.registerC
                    else -> null
                }
                is RegisterRangeInstruction -> instruction.startRegister + instruction.registerCount - 1
                else -> null
            }
        }
    }
}
