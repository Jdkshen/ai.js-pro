package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multiple edits are supposed to be resolved against the same immutable, LF-normalized original.
 * Applying them sequentially can create a match that did not exist in the caller's snapshot, and
 * rewriting the whole file with LF would silently change CRLF targets.
 */
class ToolkitTextEditsTest {
    private fun edit(vararg pairs: Pair<String, Any?>): JsonObject = ToolkitIo.json(*pairs)

    private fun edits(vararg values: JsonObject): JsonArray = JsonArray().apply {
        values.forEach { add(it) }
    }

    @Test
    fun allEditsMatchTheOriginalRatherThanEachOthersOutput() {
        val original = "one two\r\nkeep\r\n"
        val result = ToolkitTextEdits.apply(original, edits(
            edit("mode" to "replace_match", "matchText" to "one", "writeText" to "two"),
            edit("mode" to "replace_match", "matchText" to "two", "writeText" to "three"),
            edit("mode" to "replace_match", "matchText" to "keep", "writeText" to "kept")
        ), preserveLineEndings = true)

        // A sequential implementation would see two occurrences of "two" and either fail as
        // ambiguous or edit the text produced by the first edit.
        assertEquals("two three\r\nkept\r\n", result)
        assertFalse(result.replace("\r\n", "").contains('\n'))
        assertFalse(result.replace("\r\n", "").contains('\r'))
    }

    @Test
    fun replacementNewlinesUseTheTargetsOriginalStyle() {
        val result = ToolkitTextEdits.apply("a\r\nb\r\n", edits(
            edit("mode" to "replace_match", "matchText" to "a", "writeText" to "A\nB"),
            edit("mode" to "replace_match", "matchText" to "b", "writeText" to "B")
        ), preserveLineEndings = true)

        assertEquals("A\r\nB\r\nB\r\n", result)
        assertFalse(result.replace("\r\n", "").contains('\n'))
    }

    @Test
    fun insertionsUseOriginalOffsetsAndAFailedEditReturnsNoPartialResult() {
        val original = "alpha beta"
        val inserted = ToolkitTextEdits.apply(original, edits(
            edit("mode" to "insert_before_match", "matchText" to "beta", "writeText" to "["),
            edit("mode" to "insert_after_match", "matchText" to "beta", "writeText" to "]")
        ))
        assertEquals("alpha [beta]", inserted)

        val failure = try {
            ToolkitTextEdits.apply(original, edits(
                edit("mode" to "replace_match", "matchText" to "alpha", "writeText" to "A"),
                edit("mode" to "replace_match", "matchText" to "missing", "writeText" to "M")
            ))
            null
        } catch (error: ToolkitException) {
            error
        }
        assertTrue("the second edit must fail after validating the first", failure != null)
        assertEquals("TEXT_MATCH_NOT_FOUND", failure!!.code)
    }

    @Test
    fun ambiguousMatchIsRejectedInsteadOfPickingOneOccurrence() {
        val failure = try {
            ToolkitTextEdits.apply("same same", edits(
                edit("mode" to "replace_match", "matchText" to "same", "writeText" to "next")
            ))
            null
        } catch (error: ToolkitException) {
            error
        }
        assertTrue(failure != null)
        assertEquals("TEXT_MATCH_AMBIGUOUS", failure!!.code)
    }
}
