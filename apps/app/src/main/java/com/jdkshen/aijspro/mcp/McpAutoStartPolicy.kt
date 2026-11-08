package com.jdkshen.aijspro.mcp

/**
 * W4 task-10 C：App 回到前台时是否允许自动拉起 MCP 服务（纯函数，便于单测）。
 *
 * 旧行为是「本进程内启动失败过就永不再试」——McpAutoLifecycle 只看 McpService.lastError == null，
 * 而 lastError 只在 McpService.start() 里被清，自动路径永远不会再调用它；
 * 但 get_status.mcp.recovery 又承诺「打开 AI.js Pro 让服务自动拉起」→ 文案与链路矛盾。
 *
 * 现在：失败后等待 RETRY_AFTER_MS 再允许自动重试（端口释放、用户清理后能自愈）；
 * 手动开启不受限制（McpService.start() 自己会清 lastError）。
 */
internal object McpAutoStartPolicy {
    /** 启动失败后多久允许自动重试（毫秒）。 */
    const val RETRY_AFTER_MS = 30_000L

    /**
     * @param running 服务当前是否在运行（在运行就不要重复拉起）
     * @param lastError 本进程内最近一次启动失败原因；null/空 = 没有失败过
     * @param lastErrorAgeMs 距最近一次失败过去了多久（lastError 为空时无意义）
     * @param retryAfterMs 失败后的冷却时间
     */
    fun shouldAutoStart(
        running: Boolean,
        lastError: String?,
        lastErrorAgeMs: Long,
        retryAfterMs: Long = RETRY_AFTER_MS
    ): Boolean {
        if (running) return false
        if (lastError.isNullOrEmpty()) return true
        return lastErrorAgeMs >= retryAfterMs
    }
}
