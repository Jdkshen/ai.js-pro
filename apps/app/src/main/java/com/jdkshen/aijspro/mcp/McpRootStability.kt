package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject

/**
 * §2.7 MCP root 变化感知：用户的工作目录被外部重组后，旧脚本会集体失效，但调用方看不到「root 变了」。
 *
 * 这里只在**本进程内**记录首次见到的 root 与变化时刻，并把变化如实返回；不做文件监听、不猜测原因。
 * 进程重启后记录会重置（返回 restarted=true 语义上的 note）。
 */
internal object McpRootStability {
    private val lock = Any()
    private var firstPath: String? = null
    private var firstSeenAt: Long = 0L
    private var lastPath: String? = null
    private var changedAt: Long = 0L
    private var previousPath: String? = null
    private var observations: Int = 0

    fun observe(path: String, now: Long = System.currentTimeMillis()): JsonObject = synchronized(lock) {
        observations++
        if (firstPath == null) {
            firstPath = path; firstSeenAt = now; lastPath = path
        } else if (lastPath != path) {
            previousPath = lastPath
            lastPath = path
            changedAt = now
        }
        ToolkitIo.json(
            "path" to path,
            "firstPath" to firstPath,
            "firstSeenAt" to firstSeenAt,
            "previousPath" to previousPath,
            "changedAt" to (if (changedAt == 0L) null else changedAt),
            "changedThisProcess" to (changedAt != 0L),
            "observations" to observations,
            "stableRoot" to (changedAt == 0L),
            "note" to if (changedAt == 0L) {
                "本进程内 root 未变；若脚本引用的绝对路径被外部移动，仍会失效——用相对路径或 list_scripts 重新确认"
            } else {
                "本进程内 root 已变化：旧脚本里的绝对路径可能已失效，请用新的 scriptRoot 重新解析路径后再运行"
            }
            ,
            "scope" to "current-process"
        )
    }

    /** 仅供单测/诊断：清空进程内记录。 */
    fun resetForTests() = synchronized(lock) {
        firstPath = null; firstSeenAt = 0L; lastPath = null; changedAt = 0L; previousPath = null; observations = 0
    }
}
