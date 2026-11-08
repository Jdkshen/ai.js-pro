package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import java.io.File
import java.util.zip.ZipFile

/**
 * P1-4：Gadget 提前加载能力评估。
 *
 * provider 路径只有 ContentProvider onCreate，时机在 Application.attachBaseContext 之后；
 * W-E 新增受控 in-place DT_NEEDED：只覆写已有 dynstr NUL padding / dynamic spare DT_NULL，
 * 绝不改变 ELF 大小；失败明确返回原因码。静态验证成功只代表字节补丁正确，
 * 是否真的抢在加固壳前加载仍需真机验证，绝不把 provider 结果伪装成已提前加载。
 */
internal object ToolkitGadgetPreload {
    data class Plan(val applied: Boolean, val targetEntry: String?, val patched: ByteArray?, val json: JsonObject)

    /**
     * W-E：在 APK 内选择一个目标 .so 就地加 DT_NEEDED。
     * 只做预检/生成 patched bytes，不写 APK；失败不生成任何字节，交由调用方明确失败。
     */
    fun plan(input: File, targetHint: String? = null, preferredAbi: String? = null): Plan {
        val candidates = ArrayList<Triple<String, ByteArray, com.jdkshen.aijspro.mcp.ToolkitElfNeeded.Outcome>>()
        val reasons = ArrayList<String>()
        ZipFile(input).use { zip ->
            val entries = zip.entries().asSequence().filter {
                it.name.matches(Regex("lib/[^/]+/[^/]+\\.so")) &&
                    !it.name.contains("libaijsgadget") && !it.name.contains("libfrida-gadget") &&
                    !it.name.endsWith(".config.so")
            }.toList()
            val pool = if (targetHint.isNullOrBlank() && !preferredAbi.isNullOrBlank()) entries.filter { it.name.startsWith("lib/" + preferredAbi + "/") } else entries
            val selected = if (targetHint.isNullOrBlank()) pool else entries.filter { entry ->
                entry.name == targetHint || entry.name.endsWith("/" + targetHint) || File(entry.name).name == targetHint
            }
            if (selected.isEmpty()) {
                reasons.add(if (entries.isEmpty()) "PRELOAD_NO_NATIVE_LIBRARY" else if (!targetHint.isNullOrBlank()) "PRELOAD_TARGET_NOT_FOUND" else "PRELOAD_NO_NATIVE_LIBRARY_FOR_ABI:" + preferredAbi)
            }
            for (entry in selected) {
                val bytes = try {
                    zip.getInputStream(entry).use { ToolkitIo.read(it, ToolkitIo.MAX_ENTRY) }
                } catch (failure: Exception) {
                    reasons.add(entry.name + ":PRELOAD_READ_FAILED:" + (failure.message ?: failure.javaClass.simpleName))
                    continue
                }
                val outcome = ToolkitElfNeeded.patch(bytes)
                candidates.add(Triple(entry.name, bytes, outcome))
                if (!outcome.applied) reasons.add(entry.name + ":" + (outcome.reason ?: "UNKNOWN"))
            }
        }
        val chosen = candidates.firstOrNull { it.third.applied }
        val targetJson = com.google.gson.JsonArray().apply {
            candidates.forEach { (path, _, outcome) ->
                add(ToolkitIo.json(
                    "path" to path, "soname" to ToolkitElfNeeded.DEFAULT_LIBRARY,
                    "applied" to outcome.applied, "verified" to outcome.verified,
                    "strtabOffset" to outcome.strtabOffset, "dynamicIndex" to outcome.dynamicIndex,
                    "reason" to outcome.reason, "reasons" to outcome.reasons,
                    "changedRanges" to outcome.json(path).get("changedRanges"),
                ))
            }
        }
        if (chosen == null) {
            val reason = reasons.firstOrNull()?.let { raw ->
                val tail = raw.substringAfter(':', "")
                if (tail.isBlank()) raw else tail.substringBefore(':')
            } ?: "PRELOAD_NO_PATCHABLE_TARGET"
            return Plan(false, null, null, ToolkitIo.json(
                "mode" to "dt_needed", "applied" to false, "soname" to ToolkitElfNeeded.DEFAULT_LIBRARY,
                "targets" to targetJson, "reason" to reason, "reasons" to reasons, "preferredAbi" to preferredAbi,
                "earlyLoad" to false, "preloadImplemented" to false,
                "nextActions" to listOf(
                    "用外部 patchelf --add-needed libaijsgadget.so --page-size 16384 <target.so> 后重新打包",
                    "目标 .so 需要至少两个 DT_NULL 槽位，且 .dynstr 尾部有足够 NUL padding；本工具不会改变文件大小",
                ),
            ))
        }
        return Plan(true, chosen.first, chosen.third.bytes, ToolkitIo.json(
            "mode" to "dt_needed", "applied" to true, "soname" to ToolkitElfNeeded.DEFAULT_LIBRARY,
            "targets" to targetJson, "targetPath" to chosen.first, "preferredAbi" to preferredAbi,
            "selectionReason" to if(targetHint.isNullOrBlank()) "自动选择第一个可打补丁的原生库（zip 枚举顺序）；targets[] 列出全部候选及结果" else "按 preloadTarget=$targetHint 精确选择",
            "earlyLoad" to true, "preloadImplemented" to true,
            "method" to "in-place DT_NEEDED",
            "sizeUnchanged" to (chosen.third.bytes?.size == chosen.second.size),
            "verified" to chosen.third.verified,
            "verificationScope" to "static_elf_bytes_only",
            "runtimeVerified" to false,
            "note" to "DT_NEEDED 已就地写入并静态复核（大小不变、目标串/槽位/范围校验通过）；是否真的在加固壳前加载仍需真机启动/logcat 验证，本工具不伪造运行时结果。",
            "changedRanges" to chosen.third.json(chosen.first).get("changedRanges"),
            "reason" to null,
        ))
    }


    /** clinit 模式的预检结果：hook 非空才能继续注入；json 是给客户端的诚实载荷。 */
    data class ClinitPlan(val applied: Boolean, val hook: ToolkitGadget.ClinitHook?, val json: JsonObject)

    /**
     * clinit 模式的预检 + 生成（P1-4 的另一半）：只读输入 APK，生成 Application 子类 DEX
     * （不改既有 DEX、不重编资源）。失败返回 applied=false + reason=PRELOAD_CLINIT_*，
     * 调用方据此明确失败且**不产出任何 APK**。
     */
    fun planClinit(input: File, scratch: File, explicitTarget: String? = null): ClinitPlan = try {
        val hook = ToolkitGadget.prepareClinitHook(input, scratch, explicitTarget)
        ClinitPlan(true, hook, ToolkitIo.json(
            "mode" to "clinit", "applied" to true, "earlyLoad" to true, "preloadImplemented" to true,
            "soname" to ToolkitElfNeeded.DEFAULT_LIBRARY,
            "method" to "application_clinit_loadLibrary",
            "clinitClass" to hook.className, "originalApplication" to hook.originalApplication,
            "targetSource" to (if (explicitTarget.isNullOrBlank()) "application@name" else "clinitTarget"),
            "scope" to "main_process_application_class_init",
            "runtimeVerified" to false, "verificationScope" to "static_dex_manifest_only",
            "timingNote" to "保证早于 Application.attachBaseContext；父类先初始化，故不保证早于加固壳自身的 native 初始化",
            "reason" to null, "reasons" to emptyList<String>(), "nextActions" to emptyList<String>()))
    } catch (failure: ToolkitException) {
        ClinitPlan(false, null, ToolkitIo.json(
            "mode" to "clinit", "applied" to false, "earlyLoad" to false, "preloadImplemented" to true,
            "method" to "application_clinit_loadLibrary",
            "stage" to "prepare_clinit_hook",
            "reason" to failure.code, "reasons" to listOf(failure.message ?: failure.code),
            "argument" to failure.argument,
            "runtimeVerified" to false, "verificationScope" to "static_dex_manifest_only",
            "detail" to "未产出任何 APK；clinit 只改 application@name 并新增一个 DEX，不重编资源",
            "nextActions" to listOf(
                "改 preloadMode=provider（不提前加载）或 dt_needed（在 dlopen 阶段提前加载目标 .so）",
                "若目标 Application 是 final / 被壳加密或动态加载 / 声明了 appComponentFactory，clinit 路径不适用")))
    }

    fun assessment(requested: String = "provider"): com.google.gson.JsonObject {
        val mode = requested.trim().lowercase()
        val clinitImplemented = mode == "clinit"
        // 注意：这个对象**同时**挂在顶层 (classInitializer) 与 dtNeeded.classInitializer 上。
        // 历史形态（dev-1413/1414）里它嵌在 dtNeeded 内，顶层读 classInitializer 的调用方拿不到；
        // 现在两处同源，向后兼容且顶层可读。
        val classInitializer = ToolkitIo.json(
            "implemented" to true,
            "status" to "available",
            "approach" to "追加一个新 DEX：Lcom/aijs/instrumentation/AijsApplication;（.super=原 application@name）的 <clinit> 里 System.loadLibrary(\"aijsgadget\")，整段 .catch Throwable 只 Log.e；Manifest 只改 application@name 一个属性，既有 DEX 与资源一个字节不动",
            "blockers" to listOf(
                "只覆盖 Java 初始化路径；父类先初始化（JLS 12.4.2），加固壳可在 native 或自身 <clinit> 中抢跑，仍不保证早于所有 native 初始化",
                "目标 Application 是 final / 在 DEX 集里找不到（壳加密或动态加载）/ 声明了 appComponentFactory 时不适用，工具会明确失败（PRELOAD_CLINIT_UNSUPPORTED_APPLICATION）",
                "新增 DEX 与重签会改变签名；壳若自校验 DEX/签名会检测到",
            ),
            "method" to "application_clinit_loadLibrary",
            "clinitClass" to ToolkitGadget.CLINIT_CLASS,
            "runtimeVerified" to false,
            "verificationScope" to "static_dex_manifest_only",
            "nextStep" to "preloadMode=clinit（可选 clinitTarget 覆盖父类）；失败码 PRELOAD_CLINIT_UNSUPPORTED_APPLICATION / PRELOAD_CLINIT_MANIFEST_PATCH_FAILED / PRELOAD_CLINIT_DEX_ASSEMBLY_FAILED，失败不产出 APK；真机判据：Frida: Listening 早于 attachBaseContext、不崩、零资源漂移",
        )
        val dt = ToolkitIo.json(
            "implemented" to true,
            "status" to "available_in_place",
            "approach" to "在目标 App 会加载的 .so 的 DT_NEEDED 中增加 libaijsgadget.so，使 loader 在 JNI_OnLoad 前映射 Gadget",
            "requiredWrites" to listOf(".dynstr 追加以 NUL 结尾的库名", ".dynamic 增加 DT_NEEDED 项或复用空槽", "必要时新增/搬迁 PT_LOAD、更新程序头/重定位"),
            "blockers" to listOf(
                "大多数目标 .so 的 .dynstr/.dynamic 没有足够 slack；原地追加会覆盖后续节",
                "需要完整 ELF 写入器/patchelf；当前 ToolkitGadget 只读 ELF",
                "直接改页对齐/段偏移易破坏签名后加载，必须做 PT_LOAD/16K 对齐校验",
            ),
            // 历史形态保留（同一对象，见上方注释）
            "classInitializer" to classInitializer,
            "nextStep" to "提前加载有两条真实现路径：dt_needed（在目标 .so 的 dlopen 阶段，工具就地选 .so 检查 dynstr/dynamic 余量后写入并自校验，不足时明确失败并建议外部 patchelf）与 clinit（在 Application 类初始化时，新增一个 DEX + 只改 application@name，不重编资源）；provider 路径不提前加载",
        )
        return ToolkitIo.json(
            "requestedMode" to requested,
            "effectiveMode" to when { mode == "provider" -> "provider"; mode == "dt_needed" -> "dt_needed"; mode == "clinit" -> "clinit"; else -> null },
            // 只有真正实现的路径才敢说 preloadImplemented/earlyLoad=true；provider 模式保持原来的诚实语义。
            "preloadImplemented" to clinitImplemented,
            "providerModeAvailable" to (mode == "provider" && requested == "provider"),
            "earlyLoad" to clinitImplemented,
            "providerTiming" to "ContentProvider.onCreate 在 Application.attachBaseContext 之后；不是 JNI_OnLoad 前",
            "clinitTiming" to "Application 类初始化早于 attachBaseContext；父类先初始化，故不保证早于加固壳自身的 native 初始化",
            "status" to when { mode == "provider" -> "provider_only"; mode == "dt_needed" -> "dt_needed_available"; mode == "clinit" -> "clinit_available"; else -> "INVALID_PRELOAD_MODE" },
            "note" to "这是能力评估：provider 模式不提前加载（earlyLoad=false）；dt_needed 在目标 .so 的 dlopen 阶段提前加载；clinit 在 Application 类初始化时提前加载（早于 attachBaseContext，但不保证早于加固壳 native）。",
            "dtNeeded" to dt,
            // 顶层 + dtNeeded 内两处同源：顶层是本轮新增的兼容读取路径，dtNeeded 内是 dev-1413/1414 的历史位置。
            "classInitializer" to classInitializer,
            "nextActions" to listOf(
                "clinit：preloadMode=clinit（可选 clinitTarget）；成功才 earlyLoad=true，失败不产出 APK（PRELOAD_CLINIT_*）",
                "如需在加固壳初始化前介入：调用 preloadMode=dt_needed（可选 preloadTarget），成功才 earlyLoad=true",
                "provider 路径仍不提前加载；失败时不会伪造 earlyLoad=true",
            ),
        )
    }
}
