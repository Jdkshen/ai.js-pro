package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.regex.Pattern

/**
 * The toolkit used to compile searches with RE2, which rejected Java features such as
 * lookaround. These tests pin the user-visible regex dialect and the budget that must stop
 * pathological backtracking instead of hanging the MCP request.
 */
class ToolkitRegexTest {
    @Test
    fun javaLookaroundBackReferencesAndFlagsAreSupported() {
        val ahead = ToolkitRegex.compile("(?=needle)needle")
        val aheadMatcher = ToolkitRegex.matcher(ahead, "hay needle")
        assertTrue(ToolkitRegex.find(aheadMatcher))
        assertEquals(4, aheadMatcher.start())
        assertEquals(10, aheadMatcher.end())

        val behind = ToolkitRegex.compile("(?<=hay )needle")
        val behindMatcher = ToolkitRegex.matcher(behind, "hay needle")
        assertTrue(ToolkitRegex.find(behindMatcher))
        assertEquals(4, behindMatcher.start())

        val repeated = ToolkitRegex.compile("(\\w+)-\\1")
        val repeatedMatcher = ToolkitRegex.matcher(repeated, "alpha-beta alpha-alpha")
        assertTrue(ToolkitRegex.find(repeatedMatcher))
        assertEquals("alpha-alpha", repeatedMatcher.group())

        val insensitive = ToolkitRegex.compile("needle", Pattern.CASE_INSENSITIVE)
        val insensitiveMatcher = ToolkitRegex.matcher(insensitive, "A NEEDLE IN A HAYSTACK")
        assertTrue(ToolkitRegex.find(insensitiveMatcher))
        assertEquals(2, insensitiveMatcher.start())
    }

    @Test
    fun catastrophicBacktrackingHitsTheBudgetInsteadOfHanging() {
        val pattern = ToolkitRegex.compile("(a+)+$")
        val input = "a".repeat(40) + "!"
        val started = System.nanoTime()
        try {
            ToolkitRegex.find(ToolkitRegex.matcher(pattern, input, 50))
            fail("catastrophic backtracking should have been stopped")
        } catch (expected: ToolkitRegex.BudgetExceeded) {
            assertNotNull(expected.message)
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < 5_000)
    }

    @Test
    fun interruptedRequestIsReportedAsBudgetExceeded() {
        Thread.currentThread().interrupt()
        try {
            try {
                ToolkitRegex.find(ToolkitRegex.matcher(ToolkitRegex.compile("a"), "a"))
                fail("an interrupted matcher must not continue")
            } catch (expected: ToolkitRegex.BudgetExceeded) {
                assertNotNull(expected.message)
            }
        } finally {
            Thread.interrupted()
        }
    }
}
