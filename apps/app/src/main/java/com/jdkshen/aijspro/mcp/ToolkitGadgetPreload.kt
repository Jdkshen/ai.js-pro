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


    fun assessment(requested: String = "provider"): com.google.gson.JsonObject {
        val mode = requested.trim().lowercase()
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
            "classInitializer" to ToolkitIo.json(
                "implemented" to false,
                "status" to "feasibility_only",
                "approach" to "改目标 DEX 中 Application/<clinit> 的 smali，插入 System.loadLibrary(\"aijsgadget\")",
                "blockers" to listOf("只覆盖 Java 初始化路径；加固壳可在 native 中抢跑，仍不保证早于所有 native 初始化", "需要改 DEX/重签，且目标类可能被壳加密/动态加载", "必须保留原 <clinit> 语义，否则可能直接启动失败"),
                "nextStep" to "若必须尝试，作为独立高风险选项：用现有 apk_edit_text(dex_class/dex_method) 生成候选包，apk_verify + 真机启动验证；验证通过前标 unverified",
            ),
            "nextStep" to "使用 preloadMode=dt_needed；工具会在 APK 内就地选择目标 .so，检查 dynstr/dynamic 余量后写入并自校验；不足时明确失败并建议外部 patchelf",
        )
        return ToolkitIo.json(
            "requestedMode" to requested,
            "effectiveMode" to when { mode == "provider" -> "provider"; mode == "dt_needed" -> "dt_needed"; else -> null },
            "preloadImplemented" to false,
            "providerModeAvailable" to (mode == "provider" && requested == "provider"),
            "earlyLoad" to false,
            "providerTiming" to "ContentProvider.onCreate 在 Application.attachBaseContext 之后；不是 JNI_OnLoad 前",
            "status" to when { mode == "provider" -> "provider_only"; mode == "dt_needed" -> "dt_needed_available"; else -> "INVALID_PRELOAD_MODE" },
            "note" to "这是能力评估，不是提前加载实现；provider 模式仍可能在加固壳初始化后才执行。",
            "dtNeeded" to dt,
            "nextActions" to listOf(
                "如需在加固壳初始化前介入：调用 preloadMode=dt_needed（可选 preloadTarget），成功才 earlyLoad=true",
                "provider 路径仍不提前加载；失败时不会伪造 earlyLoad=true",
            ),
        )
    }
}
