package com.jdkshen.aijspro.mcp

import com.google.gson.*

/**
 * 反汇编证据分级（UX-3，对应用户侧复盘 §2.3：pdf 看不到 adrp/add 解析出的地址，批扫 0 命中白跑一轮）。
 *
 * 背景：r2 的 raw 反汇编（pdf/pdfj）默认不应用重定位，看不到 adrp/add 计算出的目标地址；
 * 交叉引用（axtj）同样可能缺这些引用。只有 Ghidra 反编译（pdg）会解析重定位，但更慢。
 * 必须把这一能力差异写进返回，避免把「这条命令 0 命中」当成「目标不存在/没有引用」。
 *
 * 证据等级统一采用 §12 的 documented/static_inferred/static_confirmed/runtime_confirmed/
 * post_change_verified（与 ToolkitEvidenceExport.levels() 保持一致），具体含义复用
 * ToolkitEvidenceExport.level(code)。
 *
 * 纯 JVM：只做字符串/JSON 整形，不执行 radare2/不读文件。
 */
internal object ToolkitDisasmEvidence {
    const val LEVEL_DOCUMENTED = "documented"
    const val LEVEL_STATIC_INFERRED = "static_inferred"
    const val LEVEL_STATIC_CONFIRMED = "static_confirmed"
    const val LEVEL_RUNTIME_CONFIRMED = "runtime_confirmed"
    const val LEVEL_POST_CHANGE_VERIFIED = "post_change_verified"

    /** 与 §12 一致的五级证据（顺序固定，便于机读与测试）。 */
    val EVIDENCE_LEVELS: List<String> = listOf(
        LEVEL_DOCUMENTED,
        LEVEL_STATIC_INFERRED,
        LEVEL_STATIC_CONFIRMED,
        LEVEL_RUNTIME_CONFIRMED,
        LEVEL_POST_CHANGE_VERIFIED,
    )

    /** r2 中让 raw 反汇编/交叉引用应用重定位的配置命令（需重跑原命令）。 */
    const val RELOC_HARDENING_COMMAND = "e bin.relocs.apply=true"

    data class View(
        val kind: String,
        val relocAware: Boolean,
        val evidenceLevel: String,
        val notes: List<String>,
    )

    private const val NO_RELOC_NOTE =
        "未启用 bin.relocs.apply=true 时，adrp/add 计算出的目标地址不会出现在反汇编/引用结果里；" +
            "按地址/字符串批搜出现 0 命中只代表这条命令没有解析出重定位，不能当成「目标不存在或没有引用」。"
    private const val RELOC_HINT =
        "需要重定位可见时：先执行 " + RELOC_HARDENING_COMMAND + " 后重新运行当前命令，或改用 pdg（Ghidra 反编译会解析重定位，但更慢）。"

    /**
     * 描述一种反汇编/分析形式的能力与证据等级。
     * 支持 canonical 名与 r2 命令/别名：raw_disasm(pdf)、decompiled(pdg)、function_json(pdfj)、
     * xrefs(axtj)、functions(aflj)；未知类型按 documented 保守处理。
     */
    fun describe(kind: String): View {
        val canonical = normalize(kind)
        return when (canonical) {
            "raw_disasm" -> View(
                canonical,
                false,
                LEVEL_STATIC_CONFIRMED,
                listOf(
                    "这是 raw 反汇编（r2 pdf）：指令字节属于静态确认，但默认不应用重定位，看不到 adrp/add 计算出的目标地址。",
                    NO_RELOC_NOTE,
                    RELOC_HINT,
                ),
            )
            "decompiled" -> View(
                canonical,
                true,
                LEVEL_STATIC_INFERRED,
                listOf(
                    "Ghidra 反编译（r2 pdg）会解析重定位，能看到 adrp/add 对应的目标地址/符号；但伪代码语义是静态推断，不是运行时确认。",
                    "pdg 通常明显慢于 pdf；批扫时先小批量确认「命中依据」再扩大范围。",
                ),
            )
            "function_json" -> View(
                canonical,
                false,
                LEVEL_STATIC_CONFIRMED,
                listOf(
                    "函数 JSON 反汇编（pdfj）按指令/基本块返回，指令字节属于静态确认；默认同样不应用重定位。",
                    NO_RELOC_NOTE,
                    "需要解析后的重定位地址：" + RELOC_HARDENING_COMMAND + " 后重跑，或改用 pdg。",
                ),
            )
            "xrefs" -> View(
                canonical,
                false,
                LEVEL_STATIC_CONFIRMED,
                listOf(
                    "交叉引用（axtj）来自静态分析；未应用重定位时，adrp/add 产生的地址不会出现在引用列表中。",
                    NO_RELOC_NOTE,
                    "0 条引用不等于没有调用者：间接调用、函数指针、数据结构引用与未覆盖路径都可能存在；需要 pdg 或 relocs.apply=true 补重定位证据。",
                ),
            )
            "functions" -> View(
                canonical,
                false,
                LEVEL_STATIC_INFERRED,
                listOf(
                    "函数列表（aflj）是静态启发式结果；heuristic 标记说明函数边界未经 CFG/运行时确认，不能当成完整函数集合。",
                ),
            )
            else -> View(
                "unknown",
                false,
                LEVEL_DOCUMENTED,
                listOf(
                    "未知反汇编类型：" + kind.take(80) + "；按最低可信处理（documented），不能据此判断重定位可见性或 0 命中结论。",
                    "请确认 r2 命令类型（pdf/pdg/pdfj/axtj/aflj）或显式声明输出格式，避免把「未覆盖」当成「没有」。",
                ),
            )
        }
    }

    /**
     * 两种形式之间的能力差异；重点是 pdf/raw 与 pdg 的重定位差异。
     * 不能因为 pdf 0 命中就下「没有」结论，必须写明需要 pdg 或 relocs.apply=true。
     */
    fun capabilityGap(fromKind: String, toKind: String): JsonObject {
        val from = normalize(fromKind)
        val to = normalize(toKind)
        val fromView = describe(fromKind)
        val toView = describe(toKind)
        val fromReloc = fromView.relocAware
        val toReloc = toView.relocAware
        val unknown = from == "unknown" || to == "unknown"
        val missing = ArrayList<String>()
        val requirements = ArrayList<String>()
        val notes = ArrayList<String>()
        var gap: Boolean
        if (unknown) {
            gap = true
            missing.add("无法确认的能力差异（存在未知类型）")
            notes.add("未知类型（from=" + from + ", to=" + to + "）：无法可靠比较能力，按保守处理；请先确认输出命令/格式（pdf/pdg/pdfj/axtj）。")
        } else if (fromReloc && !toReloc) {
            gap = true
            missing.add("重定位可见性（adrp/add 解析出的目标地址）")
            requirements.add(RELOC_HARDENING_COMMAND)
            notes.add(
                "从 " + from + " 切到 " + to + " 会丢失重定位可见性（能力降级）；如必须用 " + to +
                    "，先执行 " + RELOC_HARDENING_COMMAND + " 后重跑，否则 0 命中很容易被误读成没有目标。",
            )
        } else if (!fromReloc && toReloc) {
            gap = true
            missing.add("重定位解析出的目标地址（adrp/add）")
            requirements.add("pdg")
            requirements.add(RELOC_HARDENING_COMMAND)
            notes.add(
                "从 " + from + " 切到 " + to + " 可以补上重定位信息：需要 pdg 或 relocs.apply=true；" +
                    "pdg 会解析重定位，relocs.apply 让 raw 反汇编/交叉引用看到解析后的地址。",
            )
        } else if (!fromReloc) {
            gap = false
            missing.add("重定位解析出的目标地址（adrp/add）")
            requirements.add("pdg")
            requirements.add(RELOC_HARDENING_COMMAND)
            notes.add("两种形式都不能解析重定位：0 命中不能当作「没有地址/没有引用」；需要 pdg 或 relocs.apply=true 才能补上。")
        } else {
            gap = false
            notes.add("两种形式都具备重定位可见能力，差异不在重定位维度；仍需按证据等级区分静态/伪代码结果。")
        }
        if (!unknown && from == to) {
            gap = false
            missing.clear()
            requirements.clear()
            notes.clear()
            notes.add("同一反汇编形式（" + from + "）：能力相同；结论仍受证据等级与覆盖范围限制。")
        }
        return ToolkitIo.json(
            "fromKind" to from,
            "toKind" to to,
            "requestedFromKind" to fromKind,
            "requestedToKind" to toKind,
            "fromRelocAware" to fromReloc,
            "toRelocAware" to toReloc,
            "gap" to gap,
            "missingEvidence" to JsonArray().apply { missing.forEach { add(it) } },
            "requirements" to JsonArray().apply { requirements.forEach { add(it) } },
            "note" to notes.joinToString(" "),
            "noHitIsNotConclusion" to (!unknown && !fromReloc),
        )
    }

    /**
     * 针对某种形式的硬化命令。relocsApply=true 表示调用方已应用重定位，返回空列表。
     * 未知类型不给硬化命令（无法确认输出形式），由 capabilityNotes 提示按最低可信处理。
     */
    fun hardeningCommands(kind: String, relocsApply: Boolean): List<String> {
        val canonical = normalize(kind)
        if (relocsApply) return emptyList()
        return when (canonical) {
            "raw_disasm", "function_json", "xrefs" -> listOf(RELOC_HARDENING_COMMAND)
            else -> emptyList()
        }
    }

    /**
     * 给工具返回加上 disasmKind/relocAware/evidenceLevel/capabilityNotes 等字段（原地修改并返回）。
     * relocsApply 是调用方声明的本轮是否真的启用重定位；annotate 不执行任何命令，只标注能力与证据。
     */
    fun annotate(result: JsonObject, kind: String, relocsApply: Boolean): JsonObject {
        val canonical = normalize(kind)
        val view = describe(kind)
        val effectiveRelocAware = when (canonical) {
            "decompiled" -> true
            "raw_disasm", "function_json", "xrefs" -> relocsApply
            else -> false
        }
        val notes = ArrayList<String>()
        notes.addAll(view.notes)
        if (canonical == "raw_disasm" || canonical == "function_json" || canonical == "xrefs") {
            if (relocsApply) {
                notes.add(
                    "本轮 relocsApply=true：adrp/add 等重定位目标地址应可见；若仍 0 命中，请确认该设置在 aaa 分析前生效、" +
                        "且命令没有另起进程丢失配置。",
                )
            } else {
                notes.add("本轮 relocsApply=false：不能把 0 命中当作否定结论；先执行 " + RELOC_HARDENING_COMMAND + " 后重跑当前命令。")
            }
        } else if (canonical == "unknown" && relocsApply) {
            notes.add("未知类型即使 relocsApply=true 也不能确认重定位是否真的生效；按最低可信处理。")
        }
        val noHitIsNotConclusion = canonical == "unknown" ||
            ((canonical == "raw_disasm" || canonical == "function_json" || canonical == "xrefs") && !relocsApply)
        result.addProperty("disasmKind", view.kind)
        result.addProperty("requestedKind", kind)
        result.addProperty("relocAware", effectiveRelocAware)
        result.addProperty("evidenceLevel", view.evidenceLevel)
        result.add("evidenceLevelDetail", ToolkitEvidenceExport.level(view.evidenceLevel))
        result.addProperty("relocsApply", relocsApply)
        result.addProperty("noHitIsNotConclusion", noHitIsNotConclusion)
        result.add("capabilityNotes", JsonArray().apply { notes.forEach { add(it) } })
        result.add("hardeningCommands", JsonArray().apply { hardeningCommands(kind, relocsApply).forEach { add(it) } })
        return result
    }

    /** 规范化 kind：兼容 canonical 名与 r2 命令/别名，大小写、空格、连字符不敏感。 */
    private fun normalize(kind: String): String {
        val raw = kind.trim().lowercase().replace('-', '_').replace(' ', '_').replace(Regex("_+"), "_")
        return when (raw) {
            "pdf", "raw", "raw_disasm", "disasm", "pd", "asm", "pdf_disasm", "raw_asm" -> "raw_disasm"
            "pdg", "decompiled", "decompile", "ghidra", "pseudo", "pseudocode", "decompilation" -> "decompiled"
            "pdfj", "function_json", "function_detail", "json_disasm", "function", "fn_json" -> "function_json"
            "axtj", "xrefs", "xref", "crossrefs", "references", "refs" -> "xrefs"
            "aflj", "functions", "function_list", "fn_list" -> "functions"
            else -> "unknown"
        }
    }
}
