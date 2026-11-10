package com.jdkshen.aijspro.autojs.build

/**
 * task-63 ③（按 Lead 定夺改成两档）：project.json 声明的 abis 必须被尊重，但**不能**因为模板能力缺口
 * 把用户的工程挡死。
 *
 * 两档：
 *  - 声明的 ABI 里**至少有一个可产出** → 产出可用的那几个 + **显式 warning 并回显**
 *    （abisDeclared / abisProduced / abisMissing + 可抄的处置建议）。不静默，但不挡打包。
 *  - 声明的 ABI **一个都产不出**（真不可满足）→ 硬报错（消息保留原有风格）。
 *
 * 背景：device4 dev-1443 的工程声明 abis:[arm64-v8a,armeabi-v7a]，而我们的模板/宿主只有 arm64
 * （v7a 缺失是**模板能力缺口**，属于「打包模板重建」那条线，不是工程配置错误）。
 */
object AbiPolicy {
    const val ARM64 = "arm64-v8a"
    const val ARMV7 = "armeabi-v7a"

    data class Resolution(
        val declared: List<String>,
        val available: List<String>,
        val produced: List<String>,
        val missing: List<String>,
        val warning: String?,
        val note: String,
    ) {
        /** 打包日志用的一行（测试里打印的就是这一行，保证样例与真机一致）。 */
        fun logLine(): String =
            "abis declared=" + declared + " available=" + available + " produced=" + produced + " missing=" + missing
    }

    @JvmStatic
    fun resolve(declaredAbis: List<String>?, availableAbis: Collection<String>): Resolution {
        val available = availableAbis.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        val declared = declaredAbis.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (declared.isEmpty()) {
            return Resolution(declared, available, available, emptyList(), null,
                if (available.isEmpty()) "工程未声明 abis，且没有任何 ABI 的 lib 可用" else "工程未声明 abis，按实际可用产出：$available")
        }
        val produced = declared.filter { it in available }
        val missing = declared.filter { it !in available }
        if (produced.isEmpty()) {
            // 真不可满足：一个都产不出，必须报错（不产出 ABI 全错的包）。
            throw IllegalArgumentException(
                "工程声明了 abis=" + declared + "，但产物里一个都产不出（当前可用：" + available + "）。" +
                    "请把 project.json 的 abis 改成 " + available + "，或先给模板/宿主补齐这些 ABI 的 lib —— 不允许静默少出。")
        }
        val warning = if (missing.isEmpty()) null else buildWarning(missing, produced, available)
        val note = if (missing.isEmpty()) "按声明产出：" + produced
        else "按能力产出：" + produced + "（声明里缺：" + missing + "）"
        return Resolution(declared, available, produced, missing, warning, note)
    }

    /** 同一条 ABI 结论的签名（declared/produced/missing）——用于「同一 warning 只打一次」的降噪判据。 */
    @JvmStatic
    fun signature(resolution: Resolution): String =
        "declared=" + resolution.declared + " produced=" + resolution.produced + " missing=" + resolution.missing

    /**
     * task-85 ②（降噪）：同一条 ABI 结论在**同一进程**里重复出现时，不必每次都 W。
     *  - 没有缺失 → NONE（没话可说）；
     *  - 首次出现该签名 → WARN（完整 warning + 可抄修法）；
     *  - 与上一次签名相同 → INFO（短行，说明是重复且已提示过）。
     * 注意：**降的是日志级别/重复，不是事实** —— 打包报告里 abisDeclared/abisProduced/abisMissing 每次都写，
     * AbiPolicy「不静默」的语义不变（缺失的 ABI 永远出现在结果里）。
     */
    @JvmStatic
    fun warningLevel(previousSignature: String?, resolution: Resolution): String {
        if (resolution.missing.isEmpty()) return "NONE"
        return if (previousSignature != null && previousSignature == signature(resolution)) "INFO" else "WARN"
    }

    /** 可抄的处置建议（同时进打包日志与打包报告）。 */
    @JvmStatic
    fun buildWarning(missing: List<String>, produced: List<String>, available: List<String>): String {
        val hint = if (missing.contains(ARMV7)) "，32 位设备将无法安装" else ""
        return "declared " + missing.joinToString("/") + " 未产出（当前模板/宿主只有 " + available + "）" + hint +
            "；不会静默忽略。二选一：① 在模板/宿主补齐这些 ABI 的 lib（等「打包模板重建」）；" +
            "② 从 project.json 的 abis 里移除它，例如把 abis 改成 " + produced + "（移除前请确认目标设备架构）。"
    }
}
