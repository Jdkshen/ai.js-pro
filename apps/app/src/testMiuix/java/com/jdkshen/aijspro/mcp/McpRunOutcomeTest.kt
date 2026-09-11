package com.jdkshen.aijspro.mcp

import com.stardust.autojs.runtime.exception.ScriptInterruptedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRunOutcomeTest {

    @Test
    fun treatsScriptInterruptedAsNormalStop() {
        assertTrue(McpRunOutcome.isInterrupt(ScriptInterruptedException()))
        assertTrue(McpRunOutcome.isInterrupt(ScriptInterruptedException(InterruptedException())))
        assertTrue(McpRunOutcome.isInterrupt(IllegalStateException("boom", InterruptedException())))
    }

    @Test
    fun treatsQuickJsInterruptMessageAsNormalStop() {
        // QuickJS 引擎在中断时抛的是通用异常，message 里带 interrupted
        assertTrue(McpRunOutcome.isInterrupt(RuntimeException("Script execution interrupted")))
        assertTrue(McpRunOutcome.isInterrupt(IllegalStateException("JS execution Interrupted by user")))
    }

    @Test
    fun keepsRealErrorsAsErrors() {
        assertFalse(McpRunOutcome.isInterrupt(RuntimeException("TypeError: x is not a function")))
        assertFalse(McpRunOutcome.isInterrupt(IllegalArgumentException("找不到脚本")))
        assertFalse(McpRunOutcome.isInterrupt(RuntimeException("boom", IllegalArgumentException("cause"))))
    }
}
