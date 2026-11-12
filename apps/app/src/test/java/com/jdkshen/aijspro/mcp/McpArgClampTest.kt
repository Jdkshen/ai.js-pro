package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-1 分页层（裁决 a）的单测：行为与 coerceIn 一致 + 夹紧可告知（clamped/requested/effective）。 */
class McpArgClampTest {

    @Test fun missingValueUsesDefaultAndIsNotFlaggedAsClamped() {
        val r = McpArgClamp.clampInt(null, default = 16384, lo = 1, hi = 65536)
        assertEquals(16384, r.value)
        assertFalse("缺省不算夹紧", r.clamped)
        assertEquals(null, r.requested)
        assertEquals(16384, r.effective)
    }

    @Test fun inRangeValuePassesThrough() {
        val r = McpArgClamp.clampInt(600, default = 30, lo = 1, hi = 600)
        assertEquals(600, r.value)
        assertFalse("界内不算夹紧", r.clamped)
    }

    @Test fun aboveLimitIsClampedAndToldToTheCaller() {
        val r = McpArgClamp.clampInt(99999, default = 30, lo = 1, hi = 600)
        assertEquals("行为与既有 coerceIn 一致：夹到上限", 600, r.value)
        assertTrue("必须告知被夹过", r.clamped)
        assertEquals(99999, r.requested)
        assertEquals(600, r.effective)
    }

    @Test fun belowLimitIsClampedToo() {
        val r = McpArgClamp.clampInt(0, default = 30, lo = 1, hi = 600)
        assertEquals(1, r.value)
        assertTrue(r.clamped)
    }
}
