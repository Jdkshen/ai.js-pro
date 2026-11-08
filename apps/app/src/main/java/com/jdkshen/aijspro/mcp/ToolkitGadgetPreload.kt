package com.jdkshen.aijspro.mcp

/**
 * P1-4：Gadget 提前加载能力评估。
 *
 * 当前实现只有 ContentProvider onCreate 路径：它在 Application.attachBaseContext 之后，
 * 对加固壳不够早。DT_NEEDED 方案需要写 ELF 的 .dynstr/.dynamic（或新增 PT_LOAD），
 * 当前 APK 工具链没有内置 ELF 写入器，也没有随包 patchelf；因此这里只给可验证的
 * 可行性说明，绝不把 provider 结果伪装成“已提前加载”。
 */
internal object ToolkitGadgetPreload {
    fun assessment(requested: String = "provider"): com.google.gson.JsonObject {
        val mode = requested.trim().lowercase()
        val dt = ToolkitIo.json(
            "implemented" to false,
            "status" to "feasibility_only",
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
            "nextStep" to "引入受许可的 patchelf（或实现最小 ELF 写入器）作为可选组件；对目标 .so 先 dry-run 检查 .dynstr/.dynamic slack、PT_LOAD 余量，再产出 patch 并由 apk_verify 复核",
        )
        return ToolkitIo.json(
            "requestedMode" to requested,
            "effectiveMode" to if (mode == "provider" && requested == "provider") "provider" else null,
            "preloadImplemented" to false,
            "providerModeAvailable" to (mode == "provider" && requested == "provider"),
            "earlyLoad" to false,
            "providerTiming" to "ContentProvider.onCreate 在 Application.attachBaseContext 之后；不是 JNI_OnLoad 前",
            "status" to if (mode == "provider" && requested == "provider") "provider_only" else "PRELOAD_MODE_UNAVAILABLE",
            "note" to "这是能力评估，不是提前加载实现；provider 模式仍可能在加固壳初始化后才执行。",
            "dtNeeded" to dt,
            "nextActions" to listOf(
                "如需在加固壳初始化前介入：先取得用户在目标 .so 上手工 patchelf --add-needed 的合法产物，再用 apk_inject_gadget 的 provider 模式补 DEX/配置并验证",
                "不要声称 DT_NEEDED 已由本工具实现；本工具不会伪造 earlyLoad=true",
            ),
        )
    }
}
