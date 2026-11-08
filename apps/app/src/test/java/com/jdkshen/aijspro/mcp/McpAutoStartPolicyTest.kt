package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4 task-10 C：自动拉起策略（纯函数）。
 * 旧链路「本进程启动失败过就永不再试」与 get_status.mcp.recovery 的承诺矛盾，这里钉住新语义。
 */
class McpAutoStartPolicyTest {

    @Test
    fun startsWhenThereWasNoFailure() {
        assertTrue(McpAutoStartPolicy.shouldAutoStart(running = false, lastError = null, lastErrorAgeMs = 0))
        assertTrue(McpAutoStartPolicy.shouldAutoStart(running = false, lastError = "", lastErrorAgeMs = 0))
    }

    @Test
    fun neverStartsTwice() {
        assertFalse(McpAutoStartPolicy.shouldAutoStart(running = true, lastError = null, lastErrorAgeMs = 0))
        assertFalse(McpAutoStartPolicy.shouldAutoStart(running = true, lastError = "端口被占", lastErrorAgeMs = 1_000_000))
    }

    @Test
    fun freshFailureIsNotRetried() {
        assertFalse(McpAutoStartPolicy.shouldAutoStart(false, "端口 8765..8795 均被占用", 0))
        assertFalse(McpAutoStartPolicy.shouldAutoStart(false, "端口 8765..8795 均被占用", McpAutoStartPolicy.RETRY_AFTER_MS - 1))
    }

    @Test
    fun staleFailureIsRetried() {
        assertTrue(McpAutoStartPolicy.shouldAutoStart(false, "端口 8765..8795 均被占用", McpAutoStartPolicy.RETRY_AFTER_MS))
        assertTrue(McpAutoStartPolicy.shouldAutoStart(false, "端口 8765..8795 均被占用", 10 * McpAutoStartPolicy.RETRY_AFTER_MS))
    }

    @Test
    fun customCooldownIsHonoured() {
        assertTrue(McpAutoStartPolicy.shouldAutoStart(false, "boom", 500, retryAfterMs = 500))
        assertFalse(McpAutoStartPolicy.shouldAutoStart(false, "boom", 499, retryAfterMs = 500))
    }
}
