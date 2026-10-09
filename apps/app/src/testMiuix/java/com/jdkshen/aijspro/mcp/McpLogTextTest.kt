package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Test

class McpLogTextTest {
    @Test fun returnsNewestCharactersInOriginalOrder() {
        assertEquals("abc\ndef", McpLogText.tail(listOf("abc", "def"), 20))
        assertEquals("c\ndef", McpLogText.tail(listOf("abc", "def"), 5))
    }
    @Test fun readsOnlyNecessaryLinesFromALargeConsole() {
        var reads = 0
        assertEquals("latest", McpLogText.tail(List(10000) { "latest" }, 6) { reads++; it })
        assertEquals(1, reads)
    }
    @Test fun neverSplitsSurrogatePairs() {
        assertEquals("b", McpLogText.tail(listOf("a😀b"), 2))
        assertEquals("😀b", McpLogText.tail(listOf("a😀b"), 3))
    }
    @Test fun distinguishesExactFitFromTruncation() {
        assertFalse(McpLogText.tailResult(listOf("abc"), 3) { it }.truncated)
        assertTrue(McpLogText.tailResult(listOf("older", "abc"), 3) { it }.truncated)
        assertTrue(McpLogText.tailResult(listOf("a😀b"), 2) { it }.truncated)
    }
}
