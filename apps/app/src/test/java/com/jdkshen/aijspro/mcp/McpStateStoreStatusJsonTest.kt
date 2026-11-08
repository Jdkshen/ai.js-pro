package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W4 task-10 B：`get_status.mcp` 的字段契约（走纯函数重载，不依赖 Android）。
 * 重点是「顶层 running 与真实监听同源」——进程未监听时不得为 true。
 */
class McpStateStoreStatusJsonTest {

    private fun state(
        running: Boolean,
        updatedAt: Long,
        label: String,
        startReason: String = "manual",
        error: String = ""
    ) = McpStateStore.State(
        running = running,
        updatedAt = updatedAt,
        port = 8765,
        localUrl = "http://127.0.0.1:8765/mcp",
        lanUrl = "http://192.168.10.5:8765/mcp",
        error = error,
        state = label,
        startReason = startReason
    )

    @Test
    fun runningIsTrueOnlyWhileActuallyListening() {
        val now = 1_000_000L
        // autoStarted, liveListening, livePort, listenerAlive, failureCount, lastFailure
        val json = McpStateStore.statusJson(state(true, now, "running", "auto"), now, true, true, 8765, true, 0, null)
        assertEquals(true, json.get("running").asBoolean)
        assertEquals(true, json.get("listening").asBoolean)
        assertEquals(true, json.get("persistedRunning").asBoolean)
        assertEquals("running", json.get("status").asString)
        assertEquals(true, json.get("listener").asJsonObject.get("alive").asBoolean)
        assertFalse(json.has("recovery"))
    }

    @Test
    fun persistedRunningCannotMasqueradeAsListeningAfterProcessDeath() {
        val now = 1_000_000L
        // 进程被系统回收：prefs 里 running=true/state=running，但心跳过期、进程内没有服务实例
        val json = McpStateStore.statusJson(state(true, now - 60_000, "running", "auto"), now, true, false, 0, false, 0, null)
        assertEquals(false, json.get("running").asBoolean)
        assertEquals(false, json.get("listening").asBoolean)
        assertEquals("stale", json.get("status").asString)
        assertEquals(true, json.get("persistedRunning").asBoolean)
        assertFalse(json.get("listener").asJsonObject.get("alive").asBoolean)
        assertTrue(json.has("recovery"))
    }

    @Test
    fun deadAcceptLoopIsDegradedAndNotRunning() {
        val now = 1_000_000L
        // 服务实例还在（心跳新鲜）但 accept 循环已死（W4 task-10 A 的场景）
        val json = McpStateStore.statusJson(
            state(true, now, "running", "auto"), now, true, true, 8765, false, 3,
            "respond_503: OutOfMemoryError: boom"
        )
        assertEquals(false, json.get("running").asBoolean)
        assertEquals(false, json.get("listening").asBoolean)
        assertEquals("degraded", json.get("status").asString)
        val listener = json.get("listener").asJsonObject
        assertFalse(listener.get("alive").asBoolean)
        assertEquals(3, listener.get("failureCount").asInt)
        assertTrue(listener.get("degraded").asBoolean)
        assertTrue(listener.get("lastFailure").asString.contains("OutOfMemoryError"))
    }

    @Test
    fun listenerFailuresStayVisibleWhileStillListening() {
        val now = 1_000_000L
        val json = McpStateStore.statusJson(
            state(true, now, "running", "manual"), now, true, true, 8765, true, 1,
            "accept: SocketException: EMFILE"
        )
        assertEquals("running", json.get("status").asString)
        assertEquals(true, json.get("listening").asBoolean)
        assertEquals(true, json.get("running").asBoolean)
        val listener = json.get("listener").asJsonObject
        assertTrue(listener.get("degraded").asBoolean)
        assertTrue(listener.get("lastFailure").asString.contains("EMFILE"))
    }

    @Test
    fun startReasonFallsBackToLiveAutoFlag() {
        val now = 1_000_000L
        assertEquals("auto", McpStateStore.statusJson(state(false, now, "starting", ""), now, true, false, 0, false, 0, null).get("startReason").asString)
        assertEquals("manual", McpStateStore.statusJson(state(false, now, "starting", ""), now, false, false, 0, false, 0, null).get("startReason").asString)
    }

    @Test
    fun listenerAliveDefaultsToLiveListeningForOldCallers() {
        assertEquals("running", McpStateStore.statusLabel(true, "running", true))
        assertEquals("degraded", McpStateStore.statusLabel(true, "running", true, false))
        assertEquals("stale", McpStateStore.statusLabel(false, "running", false))
    }
}
