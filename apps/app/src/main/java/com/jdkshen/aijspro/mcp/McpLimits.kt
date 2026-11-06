package com.jdkshen.aijspro.mcp

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
        addProperty("maxTimeoutSeconds", MAX_TIMEOUT_SECONDS)
        addProperty("note", "工程文件数/展开字节/子进程堆/逆向工具超时上限；改这些常量后重建即可生效，可用本字段核对")
    }
}
