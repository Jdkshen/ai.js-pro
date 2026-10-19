package com.jdkshen.aijspro.mcp

import com.stardust.autojs.runtime.exception.ScriptInterruptedException

/**
 * 运行结果归类：主动停止 / 超时会用**中断**来终止脚本（QuickJS 在中断点必然抛异常），
 * 这属于正常控制流，不能当成脚本错误上报（·用户反馈：STOPPED 记录带着异常，误导排查）。
 */
internal object McpRunOutcome {

    /** 是否是“中止类”异常（QuickJS 的 interrupted / Rhino 的 ScriptInterruptedException）。 */
    fun isInterrupt(error: Throwable): Boolean {
        if (ScriptInterruptedException.causedByInterrupted(error)) return true
        val message = error.localizedMessage ?: error.message
        return message != null && message.contains("interrupted", ignoreCase = true)
    }
}
