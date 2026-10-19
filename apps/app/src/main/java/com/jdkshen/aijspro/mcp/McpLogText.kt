package com.jdkshen.aijspro.mcp

/** Reads only the suffix needed by the client, without copying an entire console. */
internal object McpLogText {
    data class Tail(val text: String, val truncated: Boolean)
    fun tail(lines: List<String>, maxChars: Int): String = tail(lines, maxChars) { it }

    fun <T> tail(lines: List<T>, maxChars: Int, text: (T) -> String): String = tailResult(lines, maxChars, text).text

    fun <T> tailResult(lines: List<T>, maxChars: Int, text: (T) -> String): Tail {
        require(maxChars > 0)
        val suffix = ArrayList<String>()
        var remaining = maxChars
        var truncated = false
        for (index in lines.lastIndex downTo 0) {
            if (remaining <= 0) { truncated = true; break }
            val line = text(lines[index])
            var start = (line.length - remaining).coerceAtLeast(0)
            if (start > 0 && start < line.length && Character.isLowSurrogate(line[start]) && Character.isHighSurrogate(line[start - 1])) start++
            val part = line.substring(start)
            suffix.add(part)
            if (start > 0) truncated = true
            remaining -= part.length + 1
        }
        return Tail(suffix.asReversed().joinToString("\n"), truncated)
    }
}
