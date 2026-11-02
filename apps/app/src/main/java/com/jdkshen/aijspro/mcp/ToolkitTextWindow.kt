package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject

/** LF-normalized, code-point based paging which always advances across a newline. */
internal object ToolkitTextWindow {
    fun read(source: String, args: JsonObject): JsonObject {
        val text = ToolkitTextEdits.normalize(source)
        val starts = arrayListOf(0)
        text.forEachIndexed { index, char -> if(char == '\n') starts.add(index + 1) }
        val line = args.tInt("startLine", 0, 0, starts.lastIndex)
        val begin = if(line < starts.size) starts[line] else text.length
        val lineEnd = if(line + 1 < starts.size) starts[line + 1] - 1 else text.length
        val columns = text.codePointCount(begin, lineEnd.coerceAtLeast(begin))
        val column = args.tInt("startColumn", 0, 0, columns)
        val offset = text.offsetByCodePoints(begin, column)
        val maxChars = args.tInt("maxChars", 49152, 1, 131072)
        val limit = args.tInt("limit", 500, 1, 2000)
        val charEnd = text.offsetByCodePoints(offset, minOf(maxChars, text.codePointCount(offset, text.length)))
        val linesEnd = if(line + limit < starts.size) starts[line + limit] else text.length
        val end = minOf(charEnd, linesEnd)
        val nextLine = starts.binarySearch(end).let { if(it >= 0) it else -it - 2 }.coerceAtLeast(0)
        val nextColumn = text.codePointCount(starts[nextLine], end)
        val value = text.substring(offset, end)
        val window = ToolkitIo.json("text" to value, "startLine" to line, "startColumn" to column,
            "nextLine" to nextLine, "nextColumn" to nextColumn, "hasMore" to (end < text.length),
            "returnedChars" to value.codePointCount(0, value.length), "totalLines" to starts.size)
        return ToolkitIo.json("text" to value, "textWindow" to window, "targetVersion" to ToolkitIo.version(text.toByteArray(Charsets.UTF_8)),
            "startLine" to line, "startColumn" to column, "nextLine" to nextLine, "nextColumn" to nextColumn,
            "hasMore" to (end < text.length), "limit" to limit, "totalChars" to text.codePointCount(0, text.length))
    }
}
