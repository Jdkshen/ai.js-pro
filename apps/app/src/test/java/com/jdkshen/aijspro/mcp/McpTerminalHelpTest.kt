package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTerminalHelpTest {
    @Test fun supplementDocumentsTheAsyncRishJobAndBackendContract() {
        val merged = McpTerminalHelp.merge("# Guide\n")
        assertTrue("shell_rish" in merged)
        assertTrue("backend=shizuku" in merged)
        assertTrue("jobId" in merged)
        assertTrue("terminal_read" in merged)
        assertTrue("terminal_stop" in merged)
        assertTrue("不会回退 app" in merged)
    }

    @Test fun anAlreadyUpdatedGuideIsNotAppendedTwice() {
        val guide = "# Guide\nshell_rish 已在此维护\n"
        assertEquals(guide, McpTerminalHelp.merge(guide))
    }
}
