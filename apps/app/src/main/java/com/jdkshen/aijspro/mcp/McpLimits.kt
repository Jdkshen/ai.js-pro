package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * MCP 的可调规模上限（集中一处，便于诊断与后续开放成设置项）。
 *
 * 背景：一次真实使用（用户在手机上用 MCP 回编 57,008 文件的 apktool 工程）暴露出四个硬闸门——
 * 工程文件数 30000、子进程堆 256MB、工具超时 300s、工程展开 512MiB。
 * 这些数字本身是「防爆」用的，但对真实大工程来说直接等于「不可用」，所以统一在这里放宽并**可诊断**：
 * `get_status.limits` / `toolkit_status.limits` 会回报当前生效值，改没改一看便知（不用反汇编）。
 */
internal object McpLimits {
    /** 一次工程复制/发布允许的最大条目数（目录+文件）。原 30_000。 */
    const val MAX_PROJECT_ENTRIES = 200_000

    /** 一次工程复制/发布允许的最大展开字节数（原受 ToolkitIo.MAX_APK=512MiB 约束）。 */
    const val MAX_PROJECT_BYTES = 2L * 1024 * 1024 * 1024

    /** 组件子进程（apktool/jadx）的堆上限，单位 MB。未设置时吃系统默认 256MB。 */
    const val CHILD_HEAP_MB = 2048

    /** 逆向工具 timeoutSeconds 的上界（原 300）。 */
    const val MAX_TIMEOUT_SECONDS = 1800

    fun describe(): JsonObject = JsonObject().apply {
        addProperty("maxProjectEntries", MAX_PROJECT_ENTRIES)
        addProperty("maxProjectBytes", MAX_PROJECT_BYTES)
        addProperty("childHeapMb", CHILD_HEAP_MB)
        // 用户反馈（dev-1365 §三.6）：jadx 实测撞在 OutOfMemoryError: target footprint 268435456（256MB），
        // 与这里报的 2048 不一致 —— 因为该值只代表**本应用拼装子进程命令时传入的 -Xmx**，
        // 子工具若自己再起一个 JVM / 自带脚本覆盖 -Xmx，实际堆就不是这个数。用 appliesTo + 观测值说清，
        // 不要让人以为“所有子进程都是 2048m”。
        add("childHeapMbAppliesTo", JsonArray().apply { add("apktool"); add("jadx（app_process 直接加载时的命令行 -Xmx）") })
        addProperty("childHeapMbNote", "这是本应用传给子进程的 -Xmx（VM 选项，放在 /system/bin 之前）。" +
            "若子工具自行再启动 JVM 或用自带脚本覆盖 -Xmx，实际堆可能更小 —— 已知观测：jadx 曾以 268435456（256MB）为上限报 OutOfMemoryError。")
        add("observedChildHeapBytes", JsonObject().apply { addProperty("jadx", 268435456L) })
        addProperty("maxTimeoutSeconds", MAX_TIMEOUT_SECONDS)
        addProperty("note", "工程文件数/展开字节/子进程堆/逆向工具超时上限；改这些常量后重建即可生效，可用本字段核对")
    }
}
