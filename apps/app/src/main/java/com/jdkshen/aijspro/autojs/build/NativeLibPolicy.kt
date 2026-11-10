package com.jdkshen.aijspro.autojs.build

/**
 * task-63 ⑤⑦：产物 lib/ 里每个 .so 都必须能对上「模板必需 / 工程策略 / 声明的插件」三者之一，
 * 对不上的就是**泄漏**（这条判据本身有单测，见 PackagingPolicyTest）。
 *
 * device4 dev-1443 实测泄漏：lib/arm64-v8a/libpikafish.so(1.8MB) —— 中国象棋引擎，属于
 * 象棋自动走棋工程/xiangqi 插件，却被 ApkBuilder.RUNTIME_LIB_NAMES 与
 * assets/excluded-zip-files.json:general 当成「默认必需」塞进**每个**产物。
 */
object NativeLibPolicy {
    /** 真正与运行时无关工程的必需库（inrt 壳自己要用）。 */
    private val TEMPLATE_REQUIRED = setOf(
        "libaijscrypto.so", "libc++_shared.so", "libquickjs.so", "libquickjs_jni.so"
    )
    /** OCR 能力：我们用模板内建的 ppocr/mlkit pipeline 覆盖 Pro 的 mlkit 插件（见报告 §4）。 */
    private val OCR_LIBS = setOf("libppocr.so", "libmlkit_google_ocr_pipeline.so")
    /** OpenCV 拆库（宿主 APK 里的形态；Pro 用单个 libopencv_java4.so）。 */
    private const val OPENCV_PREFIX = "libopencv_"
    private const val TBB_PREFIX = "libtbb"
    /** 象棋引擎：**只在工程确实要它时**才带（xiangqi 插件 / 工程源码里出现 pikafish）。 */
    private const val PUZZLE_ENGINE = "libpikafish.so"
    private val PUZZLE_HINTS = listOf("pikafish", "xiangqi", "象棋")

    enum class Decision { KEEP, DROP }

    data class Verdict(val decision: Decision, val reason: String, val source: String)

    /** 工程侧上下文（全部来自 project.json / 工程源码扫描，纯数据，便于单测）。 */
    data class ProjectNeeds(
        val removeOpenCv: Boolean = false,
        val declaredPlugins: List<String> = emptyList(),
        /** 工程被复制进包的文本里是否出现 pikafish/xiangqi/象棋（由调用方扫描后传入）。 */
        val usesPuzzleEngine: Boolean = false,
        /** task-101：optimization.removeOcr（默认 false）→ 不带内建 OCR 的 so 与模型。 */
        val removeOcr: Boolean = false,
    ) {
        fun wantsPuzzleEngine(): Boolean = usesPuzzleEngine ||
            declaredPlugins.any { plugin -> PUZZLE_HINTS.any { hint -> plugin.lowercase().contains(hint) } }
    }

    @JvmStatic
    fun classify(entryName: String, needs: ProjectNeeds): Verdict {
        val name = entryName.substringAfterLast('/').lowercase()
        return when {
            name in TEMPLATE_REQUIRED ->
                Verdict(Decision.KEEP, "inrt 运行时必需（模板瘦身补齐）", "template")
            name == PUZZLE_ENGINE ->
                if (needs.wantsPuzzleEngine()) {
                    Verdict(Decision.KEEP, "工程声明/使用了象棋引擎（xiangqi 插件或源码命中 pikafish/xiangqi）", "project")
                } else {
                    Verdict(Decision.DROP, "象棋引擎与本工程无关：未声明 xiangqi/pikafish 插件、源码也未命中 → 不该带", "leak")
                }
            name.startsWith(OPENCV_PREFIX) || name.startsWith(TBB_PREFIX) ->
                if (needs.removeOpenCv) {
                    Verdict(Decision.DROP, "project.json optimization.removeOpenCv=true → 不带 OpenCV/TBB", "project")
                } else {
                    Verdict(Decision.KEEP, "工程未声明 removeOpenCv → 保留 OpenCV/TBB（宿主形态是拆库）", "project")
                }
            name in OCR_LIBS ->
                if (needs.removeOcr) {
                    Verdict(Decision.DROP, "project.json optimization.removeOcr=true → 不带内建 OCR（ppocr/mlkit pipeline）", "project")
                } else {
                    Verdict(Decision.KEEP, "内建 OCR（ppocr/mlkit pipeline）覆盖 Pro 的 mlkit 插件能力", "template")
                }
            name.startsWith("libv8") || name.contains("flutter") || name.contains("node") ->
                Verdict(Decision.DROP, "宿主里其它运行时（v8/flutter/node）不属于本工程产物", "leak")
            else ->
                Verdict(Decision.KEEP, "未识别：保守保留（若确认无关请加进模板必需或工程策略）", "unknown")
        }
    }

    /** 只按判据算「应该补进工作区」的清单。 */
    @JvmStatic
    fun filter(hostLibEntries: List<String>, needs: ProjectNeeds): List<String> =
        hostLibEntries.filter { classify(it, needs).decision == Decision.KEEP }

    /** 审计：返回不该带的（泄漏）条目与原因，用于打包日志 / 单测。 */
    @JvmStatic
    fun leaks(hostLibEntries: List<String>, needs: ProjectNeeds): List<Pair<String, String>> =
        hostLibEntries.mapNotNull { entry ->
            val verdict = classify(entry, needs)
            if (verdict.decision == Decision.DROP && verdict.source == "leak") entry to verdict.reason else null
        }

    // ---- task-63 ④（按 Lead 定夺）：插件只做「显式回显 + 不静默」，不按 Pro 形态带插件 APK ----

    data class PluginCoverage(val plugin: String, val coveredBy: String?, val note: String)

    private val OCR_PLUGIN_HINTS = listOf("mlkit", "ocr", "ppocr")

    /**
     * 工程声明的每个插件由什么能力覆盖：内建 OCR / 按工程策略带的原生库 / 未覆盖。
     * 覆盖不了的**不静默**：调用方会打 warning 并写进打包报告。
     */
    @JvmStatic
    @JvmOverloads
    fun pluginCoverage(declaredPlugins: List<String>, removeOcr: Boolean = false): List<PluginCoverage> = declaredPlugins.map { plugin ->
        val lower = plugin.lowercase()
        when {
            // task-106 复查点 3：removeOcr=true 时**不能**再声称 coveredBy=builtin-ocr（库已被剔），
            // 否则日志/报告会出现「插件声明保留但库被删」的矛盾。改成 pointed 说明 + coveredBy=null。
            OCR_PLUGIN_HINTS.any { lower.contains(it) } && removeOcr -> PluginCoverage(plugin, null,
                "被 optimization.removeOcr 移除：该插件的 OCR 能力不再随包（脚本调用 \$ocr 会点名报错）")
            OCR_PLUGIN_HINTS.any { lower.contains(it) } -> PluginCoverage(plugin, "builtin-ocr",
                "内建 libppocr.so + libmlkit_google_ocr_pipeline.so + OCR 模型已覆盖该插件能力（不按 Pro 形态带插件 APK）")
            PUZZLE_HINTS.any { lower.contains(it) } -> PluginCoverage(plugin, "native-lib:libpikafish.so",
                "按工程策略带上象棋引擎 lib（project-scoped）")
            else -> PluginCoverage(plugin, null,
                "未识别插件：本轮不带插件 APK（属模板重建那条线），请确认工程不依赖它的原生实现")
        }
    }

    /** 打包日志用的一行（测试里打印的就是这一行，保证样例与真机一致）。 */
    @JvmStatic
    fun pluginSummaryLine(declaredPlugins: List<String>): String {
        if (declaredPlugins.isEmpty()) return "plugins=[] （工程未声明插件）"
        val coverage = pluginCoverage(declaredPlugins)
        val covered = coverage.filter { it.coveredBy != null }.map { it.plugin + "=" + it.coveredBy }
        val missing = coverage.filter { it.coveredBy == null }.map { it.plugin }
        return "plugins=" + declaredPlugins + " coveredBy={" + covered.joinToString(", ") + "}" +
            (if (missing.isEmpty()) "" else " missing=" + missing)
    }
}
