package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File
import java.util.zip.ZipFile

/**
 * entry_（入口分析）：**编排 + 归类**，不新增解析能力。
 *
 * 复用：Manifest 的 AXML 解析（[ToolkitGadget.readApplicationDetails] / [ToolkitGadget.readComponents]）、
 * DEX 类表（[ToolkitGadget.assessClinit] 的同一检查）、ELF 只读解析（[ToolkitElf]）、
 * 提前加载能力判定（[ToolkitGadgetPreload.plan] + [ToolkitGadget.assessClinit]）。
 *
 * 诚实边界（同时写进返回的 notes/limits）：
 * - 只做**静态**分析：组件可能被壳动态注册/替换，Manifest 静态声明 != 运行时真实组件表；
 * - preloadRecommendation 是**建议**：它读的就是 apk_inject_gadget 用的同一份预检（reasonSource 回显函数名），
 *   但注入是否成功仍以 apk_inject_gadget 的 PRELOAD_* 失败码为准；
 * - nativeEntryHints 找不到 JNI_OnLoad **不代表**没有 native 初始化（可能在 .init_array/DT_INIT、
 *   由壳 dlopen、被加密，或导出符号被裁剪）；
 * - 不做运行时验证：runtimeVerified 恒为 false。
 */
internal object ToolkitEntryAnalysis {

    /** 单次最多报告的 .so 数（按 JNI_OnLoad -> .init_array -> 其它 排序后截断）。 */
    const val MAX_NATIVE_HINTS = 40
    const val MANIFEST_BYTES = 4 * 1024 * 1024

    const val NATIVE_NOTE =
        "nativeEntryHints 只覆盖静态可见的 .so：没找到 JNI_OnLoad 不等于没有 native 初始化（可能在 .init_array/DT_INIT、由壳自己 dlopen、被加密/加固，或导出符号被裁剪）。"

    private val SO_ENTRY = Regex("lib/[^/]+/[^/]+\\.so")

    /**
     * 静态入口分析。
     *
     * @param apk           已定位到磁盘的完整单 APK
     * @param preferredAbi  设备首选 ABI（与 apk_inject_gadget 传给 ToolkitGadgetPreload.plan 的值一致）；
     *                      null 时 dt_needed 预检会在全部 ABI 里挑
     */
    fun analyze(apk: File, preferredAbi: String? = null): JsonObject {
        require(apk.isFile) { "APK 不存在：" + apk.path }
        require(apk.length() <= ToolkitIo.MAX_APK) { "APK 超过 512MiB" }
        val manifest = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw ToolkitException("ENTRY_MANIFEST_MISSING", "APK 缺少 AndroidManifest.xml（不是完整单 APK？）", "path")
            zip.getInputStream(entry).use { ToolkitIo.read(it, MANIFEST_BYTES) }
        }
        val details = try {
            ToolkitGadget.readApplicationDetails(manifest)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            throw ToolkitException("ENTRY_MANIFEST_UNREADABLE",
                "无法解析 AndroidManifest.xml：" + (failure.message ?: failure.javaClass.simpleName), "path")
        }
        val components = try {
            ToolkitGadget.readComponents(manifest)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            throw ToolkitException("ENTRY_MANIFEST_UNREADABLE",
                "无法解析组件清单：" + (failure.message ?: failure.javaClass.simpleName), "path")
        }
        val clinit = ToolkitGadget.assessClinit(apk, null)
        val dtOutcome = runCatching { ToolkitGadgetPreload.plan(apk, null, preferredAbi) }
        val native = nativeHints(apk, preferredAbi)

        val launcher = components.filter { it.launcher }
        val exported = components.filter { it.exported == true }
        val applicationName = details.applicationName?.let { ToolkitGadget.expandClassName(details.packageName, it) }
            ?: "android.app.Application"
        val applicationSource = if (details.applicationName == null) "default" else "manifest"

        return ToolkitIo.json(
            "package" to details.packageName,
            "apk" to ToolkitIo.json(
                "path" to apk.path, "sizeBytes" to apk.length(), "sha256" to ToolkitIo.sha(apk),
                "preferredAbi" to preferredAbi,
            ),
            "application" to applicationJson(details, applicationName, applicationSource, clinit),
            "launcher" to launcher.map { componentJson(it, details.packageName, launchReason = true) },
            "launcherCount" to launcher.size,
            "exportedComponents" to exported.map { componentJson(it, details.packageName, launchReason = it.launcher) },
            "exportedCount" to exported.size,
            "componentCounts" to componentCounts(components),
            "componentNote" to "exported=null 表示 Manifest 未显式声明 android:exported（或该属性不是可解析的布尔值；Android 12+ 带 intent-filter 的组件必须显式声明）；未声明时能否被外部启动还取决于 intent-filter 与系统版本，本工具不猜。",
            "nativeEntryHints" to native.get("items"),
            "nativeLibraries" to native.get("summary"),
            "nativeNote" to NATIVE_NOTE,
            "preloadRecommendation" to recommendation(dtOutcome, clinit, preferredAbi, apk),
            "staticConclusions" to staticConclusions(launcher, exported, native, details),
            "needsDynamicConfirmation" to JsonArray().apply {
                add("组件表可能被壳动态注册/替换：Manifest 静态声明不等于运行时真实组件（要用 dumpsys/运行时枚举确认）")
                add("Gadget 是否真的在加固壳 native 初始化之前加载，需要真机 logcat 验证（runtimeVerified=false）")
                add("native 初始化可能发生在 .init_array/DT_INIT、由壳自解密 dlopen 或符号被裁剪，静态扫描看不到")
                add("注入 Provider 的真实生效进程（providerProcessSource=manifest 时按入口 activity 的 android:process 推断）需真机确认")
                add("preloadRecommendation 是静态建议，注入时以 apk_inject_gadget 的 PRELOAD_* 失败码为准")
            },
            "evidenceSources" to listOf(
                "manifest（AXML）: package / application@name / application@appComponentFactory / 组件 exported+process+intent-filter",
                "dex（classes*.dex 类表）: 父类是否在 DEX 集里可继承（ToolkitGadget.assessClinit 的同一检查）",
                "elf（lib/**/*.so）: DT_SONAME / DT_NEEDED / 符号表 JNI_OnLoad / .init_array（ToolkitElf 只读解析）",
                "preload: ToolkitGadgetPreload.plan（dt_needed 字节级预检） + ToolkitGadget.assessClinit（clinit 预检）",
            ),
            "notes" to JsonArray().apply {
                add("本工具只做静态分析，不执行、不安装、不改任何字节；runtimeVerified 恒为 false。")
                add(NATIVE_NOTE)
                add("preloadRecommendation 复用 apk_inject_gadget 的同一套判定（reasonSource 回显函数名），不是另写一套规则。")
                add("Split APK 只按传入的单个 APK 分析：base 之外的 split 里的组件/库看不到。")
            },
            "limits" to ToolkitIo.json(
                "apkBytes" to ToolkitIo.MAX_APK, "manifestBytes" to MANIFEST_BYTES,
                "elfBytesPerLibrary" to ToolkitIo.MAX_ENTRY, "nativeHintsMax" to MAX_NATIVE_HINTS,
                "notScanned" to listOf(
                    "DEX 方法体里的 System.loadLibrary 调用点（只做类表级检查，不做方法体扫描）",
                    "resources.arsc 里引用的组件字符串",
                    "壳加密/动态加载的组件与 native 库",
                    "split APK 的其它 split",
                    "每个 .so 的 sha256（体积/耗时；只报 soname/needed/symbols 摘要）",
                ),
            ),
            "runtimeVerified" to false,
        )
    }

    // ------------------------------------------------------------------ application

    private fun applicationJson(
        details: ToolkitGadget.ManifestApplication,
        applicationName: String,
        applicationSource: String,
        clinit: ToolkitGadget.ClinitAssessment,
    ): JsonObject = ToolkitIo.json(
        "class" to applicationName,
        "classSource" to applicationSource,
        "declaredClass" to details.applicationName,
        "appComponentFactory" to details.appComponentFactory,
        "appComponentFactoryDeclared" to (details.appComponentFactory != null),
        "appComponentFactoryImpact" to if (details.appComponentFactory == null) {
            "未声明 appComponentFactory：Application 实例由 application@name 决定（clinit 路径可用性另见 classInitializer）。"
        } else {
            "声明了 appComponentFactory（Android 10+）：Application 实例由它创建，application@name 不可靠 -> clinit（改 application@name）路径会被明确拒绝。"
        },
        "classInitializer" to ToolkitIo.json(
            "clinitPathAvailable" to clinit.available,
            "parentClass" to clinit.originalApplication,
            "parentSource" to clinit.targetSource,
            "parentClassUsable" to clinit.parentClassUsable,
            "parentIsFinal" to clinit.parentIsFinal,
            "parentIsInterface" to clinit.parentIsInterface,
            "clinitClassPresent" to clinit.clinitClassPresent,
            "dexEntriesScanned" to clinit.dexEntryCount,
            "failureCode" to clinit.failureCode,
            "failureMessage" to clinit.failureMessage,
            "checkedBy" to "ToolkitGadget.assessClinit(apk, explicitTarget=null)",
        ),
        "evidence" to listOf(
            "manifest:application@name=" + (details.applicationName ?: "(未声明 -> android.app.Application)"),
            "manifest:application@appComponentFactory=" + (details.appComponentFactory ?: "(未声明)"),
            "dex:classes*.dex 类表（clinit 预检的同一检查，扫到 " + clinit.dexEntryCount + " 个 dex）",
        ),
        "confidence" to "high",
    )

    // ------------------------------------------------------------------ 组件

    private fun componentJson(
        component: ToolkitGadget.ManifestComponent,
        packageName: String,
        launchReason: Boolean,
    ): JsonObject = ToolkitIo.json(
        "kind" to component.kind,
        "name" to component.name,
        "declaredName" to component.declaredName,
        "targetActivity" to component.targetActivity?.let { ToolkitGadget.expandClassName(packageName, it) },
        "targetActivityDeclared" to component.targetActivity,
        "process" to component.process,
        "processEffective" to (component.process ?: "继承 application 的 android:process（未声明时=默认进程）"),
        "exported" to component.exported,
        "hasIntentFilter" to component.hasIntentFilter,
        "launcher" to launchReason,
        "evidence" to JsonArray().apply {
            add("manifest:" + component.kind + "@name=" + component.declaredName)
            if (component.launcher) add("manifest:intent-filter action=android.intent.action.MAIN + category=android.intent.category.LAUNCHER")
            add("manifest:android:process=" + (component.process ?: "(未声明)"))
            add("manifest:android:exported=" + (component.exported?.toString() ?: "(未声明)"))
            if (component.kind == "activity-alias") add("manifest:activity-alias@targetActivity=" + (component.targetActivity ?: "(未声明)"))
        },
        "confidence" to "high",
    )

    private fun componentCounts(components: List<ToolkitGadget.ManifestComponent>): JsonObject {
        val counts = JsonObject()
        components.groupBy { it.kind }.forEach { (kind, list) -> counts.addProperty(kind, list.size) }
        counts.addProperty("total", components.size)
        return counts
    }

    // ------------------------------------------------------------------ native

    private fun nativeHints(apk: File, preferredAbi: String?): JsonObject {
        val hints = ArrayList<JsonObject>()
        var total = 0
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().filter { SO_ENTRY.matches(it.name) }.toList()
            total = entries.size
            for (entry in entries) {
                val abi = entry.name.split('/').getOrNull(1)
                val hint = runCatching {
                    val bytes = zip.getInputStream(entry).use { ToolkitIo.read(it, ToolkitIo.MAX_ENTRY) }
                    val elf = ToolkitElf(bytes)
                    val jni = elf.symbols.firstOrNull { it.name == "JNI_OnLoad" }
                    val initArray = elf.sections.firstOrNull { it.type == 14L }
                    val hasDynsym = elf.sections.any { it.type == 11L }
                    val stripped = elf.sections.none { it.type == 2L }
                    val needed = elf.needed()
                    val soname = elf.soname()
                    val evidence = ArrayList<String>()
                    if (jni != null) evidence.add("dynsym/symtab 导出符号 JNI_OnLoad@" + ToolkitElf.hex(jni.address) + "（size=" + jni.size + "）")
                    if (soname != null) evidence.add("DT_SONAME=" + soname)
                    if (needed.isNotEmpty()) evidence.add("DT_NEEDED=[" + needed.joinToString(",") + "]")
                    if (initArray != null) evidence.add("存在 .init_array（" + (initArray.size / (if (elf.wide) 8 else 4)) + " 个槽；槽值需重定位解析，未解析）")
                    if (stripped) evidence.add("没有 .symtab（已 strip，仅 .dynsym）")
                    if (evidence.isEmpty()) evidence.add("没有 JNI_OnLoad、没有 .init_array、没有 DT_NEEDED 记录（可能是纯资源库/壳数据）")
                    val confidence = when {
                        jni != null -> "high"
                        initArray != null -> "medium"
                        else -> "low"
                    }
                    ToolkitIo.json(
                        "path" to entry.name, "abi" to abi, "isPreferredAbi" to (abi != null && abi == preferredAbi),
                        "sizeBytes" to bytes.size, "architecture" to elf.inspect().get("architecture").asString,
                        "soname" to soname, "needed" to needed, "neededCount" to needed.size,
                        "jniOnLoad" to ToolkitIo.json(
                            "found" to (jni != null), "address" to jni?.let { ToolkitElf.hex(it.address) },
                            "size" to jni?.size, "binding" to jni?.binding, "type" to jni?.type,
                        ),
                        "initArray" to ToolkitIo.json(
                            "present" to (initArray != null),
                            "slotCount" to initArray?.let { (it.size / (if (elf.wide) 8 else 4)).toInt() },
                        ),
                        "stripped" to stripped, "hasDynsym" to hasDynsym, "symbolCount" to elf.symbols.size,
                        "evidence" to evidence, "confidence" to confidence,
                    )
                }.getOrElse { failure ->
                    if (failure is VirtualMachineError) throw failure
                    ToolkitIo.json(
                        "path" to entry.name, "abi" to abi, "isPreferredAbi" to (abi != null && abi == preferredAbi),
                        "sizeBytes" to runCatching { entry.size }.getOrDefault(-1L),
                        "parseError" to (failure.message ?: failure.javaClass.simpleName),
                        "evidence" to listOf("ELF 解析失败：可能是加密/加固/非标准 ELF；不代表没有 native 初始化"),
                        "confidence" to "low",
                    )
                }
                hints.add(hint)
            }
        }
        hints.sortWith(compareByDescending<JsonObject> { confidenceRank(it) }.thenBy { it.get("path").asString })
        val items = JsonArray().apply { hints.take(MAX_NATIVE_HINTS).forEach { add(it) } }
        val withJni = hints.count { it.getAsJsonObject("jniOnLoad")?.get("found")?.asBoolean == true }
        val summary = ToolkitIo.json(
            "total" to total, "reported" to items.size(), "truncated" to (hints.size > MAX_NATIVE_HINTS),
            "withJniOnLoad" to withJni, "maxHints" to MAX_NATIVE_HINTS,
            "preferredAbi" to preferredAbi,
            "verdict" to if (withJni == 0) "no_static_jni_onload_found" else "jni_onload_found",
            "librariesWithJniOnLoad" to withJni,
        )
        return ToolkitIo.json("items" to items, "summary" to summary)
    }

    private fun confidenceRank(hint: JsonObject): Int =
        when (hint.get("confidence")?.takeIf { it.isJsonPrimitive }?.asString) {
            "high" -> 3
            "medium" -> 2
            else -> 1
        }

    // ------------------------------------------------------------------ 提前加载建议

    /**
     * 提前加载建议：**只读 apk_inject_gadget 用的同一份预检**。
     * - dt_needed：ToolkitGadgetPreload.plan(...).applied（字节级：.dynstr 余量 + .dynamic 空槽）
     * - clinit：ToolkitGadget.assessClinit(...).available（Application 可继承 + 未声明 appComponentFactory）
     * - 都不行：provider（不提前加载，earlyLoad=false）
     */
    private fun recommendation(
        dtOutcome: Result<ToolkitGadgetPreload.Plan>,
        clinit: ToolkitGadget.ClinitAssessment,
        preferredAbi: String?,
        apk: File,
    ): JsonObject {
        val dtJson = dtOutcome.getOrNull()?.json
        val dtApplied = dtJson?.get("applied")?.asBoolean == true
        val clinitAvailable = clinit.available
        val mode = when {
            dtApplied -> "dt_needed"
            clinitAvailable -> "clinit"
            else -> "provider"
        }
        val dtFailure = dtOutcome.exceptionOrNull()?.message
            ?: dtJson?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
            ?: "PRELOAD_NO_PATCHABLE_TARGET"
        val reason = when (mode) {
            "dt_needed" -> "APK 内存在可打补丁的原生库（.dynstr 尾部有 NUL 余量、.dynamic 有空槽），可在目标 .so 的 dlopen 阶段提前加载；判定与 apk_inject_gadget{preloadMode:dt_needed} 完全相同。"
            "clinit" -> "Application 可继承（非 final/接口、在 APK 的 DEX 集里可解析）且未声明 appComponentFactory，可在 Application 类初始化时提前加载（早于 attachBaseContext，但不保证早于加固壳自身 native 初始化）；判定与 apk_inject_gadget{preloadMode:clinit} 完全相同。"
            else -> "两条提前加载通道都不可用：dt_needed=" + dtFailure + "；clinit=" + (clinit.failureCode ?: "") + " " + (clinit.failureMessage ?: "") + "。Provider 路径不提前加载（earlyLoad=false），但最稳妥、不改任何 ELF/DEX。"
        }
        val modeJson = ToolkitIo.json(
            "dt_needed" to ToolkitIo.json(
                "available" to dtApplied,
                "checkedBy" to "ToolkitGadgetPreload.plan(apk, targetHint=null, preferredAbi=" + (preferredAbi ?: "null") + ")",
                "reason" to (dtJson?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString),
                "reasons" to (dtJson?.get("reasons") ?: JsonArray()),
                "targetPath" to (dtJson?.get("targetPath") ?: JsonNull.INSTANCE),
                "targets" to (dtJson?.getAsJsonArray("targets") ?: JsonArray()),
                "runtimeVerified" to false,
            ),
            "clinit" to ToolkitIo.json(
                "available" to clinitAvailable,
                "checkedBy" to "ToolkitGadget.assessClinit(apk, explicitTarget=null)",
                "parentClass" to clinit.originalApplication,
                "parentSource" to clinit.targetSource,
                "parentClassUsable" to clinit.parentClassUsable,
                "parentIsFinal" to clinit.parentIsFinal,
                "parentIsInterface" to clinit.parentIsInterface,
                "appComponentFactory" to clinit.appComponentFactory,
                "clinitClassPresent" to clinit.clinitClassPresent,
                "failureCode" to clinit.failureCode,
                "failureMessage" to clinit.failureMessage,
                "runtimeVerified" to false,
            ),
            "provider" to ToolkitIo.json(
                "available" to true,
                "checkedBy" to "固定可用：PatchManifest 只新增一个 Provider 条目（ToolkitGadget.patchManifest）",
                "earlyLoad" to false,
                "note" to "Provider.onCreate 在 Application.attachBaseContext 之后，不提前加载。",
            ),
        )
        return ToolkitIo.json(
            "mode" to mode,
            "reason" to reason,
            "reasonSource" to modeJson.getAsJsonObject(
                when (mode) { "dt_needed" -> "dt_needed"; "clinit" -> "clinit"; else -> "provider" }
            ).get("checkedBy").asString,
            "earlyLoad" to (mode != "provider"),
            "runtimeVerified" to false,
            "advice" to "这是静态建议、不是保证：注入时请以 apk_inject_gadget 返回的实际结果/失败码（PRELOAD_*）为准。",
            "suggestedArguments" to ToolkitIo.json(
                "tool" to "apk_inject_gadget",
                "preloadMode" to mode,
                "preloadTarget" to if (mode == "dt_needed") dtJson?.get("targetPath") else null,
                "clinitTarget" to if (mode == "clinit") clinit.originalApplication else null,
            ),
            "capabilityAssessmentStatus" to ToolkitGadgetPreload.assessment(mode).get("status").asString,
            "checkedAgainstSameFunctionsAsApkInjectGadget" to true,
            "modes" to modeJson,
            "evidence" to listOf(
                "ToolkitGadgetPreload.plan(" + apk.name + ", null, " + (preferredAbi ?: "null") + ").applied=" + dtApplied,
                "ToolkitGadget.assessClinit(" + apk.name + ").failureCode=" + (clinit.failureCode ?: "null"),
            ),
            "confidence" to when (mode) {
                "dt_needed" -> "high"
                "clinit" -> "medium"
                else -> "high"
            },
        )
    }

    // ------------------------------------------------------------------ 结论分层

    private fun staticConclusions(
        launcher: List<ToolkitGadget.ManifestComponent>,
        exported: List<ToolkitGadget.ManifestComponent>,
        native: JsonObject,
        details: ToolkitGadget.ManifestApplication,
    ): JsonArray = JsonArray().apply {
        add("Application 类=" + (details.applicationName ?: "(默认 android.app.Application)") + "（来源=manifest）")
        add("appComponentFactory=" + (details.appComponentFactory ?: "(未声明)") + "（声明时 clinit 路径不可用）")
        add("launcher 入口数=" + launcher.size + "（含 activity-alias；targetActivity 已展开）")
        add("显式 exported=true 的组件数=" + exported.size)
        add("native: " + native.getAsJsonObject("summary").get("verdict").asString +
            "（扫到 " + native.getAsJsonObject("summary").get("total").asInt + " 个 .so）")
    }
}
