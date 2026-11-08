package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/** `.4` 复核 J5：status 文案判定抽成纯函数后钉住语义。 */
class McpStateStoreLabelTest {

    @Test
    fun liveListeningAlwaysWins() {
        assertEquals("running", McpStateStore.statusLabel(true, "stopped", false))
        assertEquals("running", McpStateStore.statusLabel(true, "", false))
    }

    @Test
    fun persistedStateDrivesLabelWhenNotListening() {
        assertEquals("error", McpStateStore.statusLabel(false, "error", false))
        assertEquals("starting", McpStateStore.statusLabel(false, "starting", false))
        assertEquals("running", McpStateStore.statusLabel(false, "starting", true))
        assertEquals("stopped", McpStateStore.statusLabel(false, "stopped", true))
    }

    @Test
    fun heartbeatFallbackWithoutPersistedState() {
        assertEquals("running", McpStateStore.statusLabel(false, "", true))
        assertEquals("stale", McpStateStore.statusLabel(false, "running", false))
        assertEquals("stopped", McpStateStore.statusLabel(false, "", false))
    }
}
