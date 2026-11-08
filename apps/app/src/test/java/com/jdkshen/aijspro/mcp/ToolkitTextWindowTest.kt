package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paging is code-point based and must always make progress. A window ending at a line delimiter
 * may return only the newline on the next call; that is acceptable as long as the following call
 * advances to the next line instead of returning the same position forever.
 */
class ToolkitTextWindowTest {
    private fun page(source: String, line: Int, column: Int, maxChars: Int): JsonObject =
        ToolkitTextWindow.read(source, ToolkitIo.json(
            "startLine" to line,
            "startColumn" to column,
            "maxChars" to maxChars,
            "limit" to 500
        ))

    @Test
    fun codePointColumnsAndPagingNeverSplitSurrogatePairs() {
        val middle = page("😀abc", 0, 1, 2)
        assertEquals("ab", middle.tString("text"))
        assertEquals(0, middle.get("nextLine").asInt)
        assertEquals(3, middle.get("nextColumn").asInt)

        val source = "😀😀😀\nabc\n"
        val expected = ToolkitTextEdits.normalize(source)
        val collected = StringBuilder()
        var line = 0
        var column = 0
        var pages = 0
        while (true) {
            val result = page(source, line, column, maxChars = 2)
            val chunk = result.tString("text")
            assertFalse("a page with more content must not be empty", chunk.isEmpty())
            assertEquals(chunk.codePointCount(0, chunk.length),
                result.getAsJsonObject("textWindow").get("returnedChars").asInt)
            assertTrue(chunk.codePointCount(0, chunk.length) <= 2)
            assertNoLoneSurrogate(chunk)
            collected.append(chunk)
            pages++
            assertTrue("paging did not terminate", pages < 64)
            if (!result.get("hasMore").asBoolean) break
            line = result.get("nextLine").asInt
            column = result.get("nextColumn").asInt
        }
        assertEquals(expected, collected.toString())
    }

    @Test
    fun offsetOverridesLineAndSlicesByUnicodeCodePoint() {
        val source = "😀中文\nsecond\n"
        val first = ToolkitTextWindow.read(source, ToolkitIo.json(
            "offset" to 1, "startLine" to 1, "startColumn" to 2, "maxChars" to 2, "limit" to 500,
        ))
        assertEquals("中文", first.tString("text"))
        assertTrue(first.get("offsetApplied").asBoolean)
        assertEquals(1, first.get("offset").asInt)
        assertEquals(3, first.get("nextOffset").asInt)
        assertTrue(first.tString("pagingHint").contains("offset"))
        val window = first.getAsJsonObject("textWindow")
        assertEquals(1, window.get("offset").asInt)
        assertEquals(3, window.get("nextOffset").asInt)

        val second = ToolkitTextWindow.read(source, ToolkitIo.json(
            "offset" to first.get("nextOffset").asInt, "maxChars" to 100, "limit" to 500,
        ))
        assertEquals("\nsecond\n", second.tString("text"))
        assertEquals(first.get("nextOffset").asInt, second.get("offset").asInt)
    }

    @Test
    fun pagingAdvancesAcrossConsecutiveNewlinesWithoutRepeatingAPosition() {
        val source = "a\n\nb\n"
        val expected = ToolkitTextEdits.normalize(source)
        val collected = StringBuilder()
        var line = 0
        var column = 0
        var pages = 0
        while (true) {
            val result = page(source, line, column, maxChars = 1)
            val chunk = result.tString("text")
            assertFalse("newline paging must not stall on an empty window", chunk.isEmpty())
            collected.append(chunk)
            pages++
            assertTrue("newline paging did not terminate", pages <= source.length + 1)
            if (!result.get("hasMore").asBoolean) break
            line = result.get("nextLine").asInt
            column = result.get("nextColumn").asInt
        }
        assertEquals(expected, collected.toString())
        assertTrue("newline paging should need more than one non-empty window", pages > 1)
    }

    private fun assertNoLoneSurrogate(value: String) {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (Character.isHighSurrogate(current)) {
                assertTrue("page ended after a high surrogate", index + 1 < value.length)
                assertTrue("high surrogate was not followed by a low surrogate",
                    Character.isLowSurrogate(value[index + 1]))
                index += 2
            } else {
                assertFalse("page contains a lone low surrogate", Character.isLowSurrogate(current))
                index++
            }
        }
    }
}
