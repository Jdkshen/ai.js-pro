package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject

/** LF-normalized, code-point based paging which always advances across a newline. */
internal object ToolkitTextWindow {
    fun read(source: String, args: JsonObject): JsonObject {
        val text = ToolkitTextEdits.normalize(source)
        val starts = arrayListOf(0)
        text.forEachIndexed { index, char -> if(char == '\n') starts.add(index + 1) }
        val totalChars = text.codePointCount(0, text.length)
        // .4 P0-2：offset 是 Unicode 码点偏移（与 maxChars/totalChars 同一单位）；存在时优先于
        // startLine/startColumn，供 zip_entry: 等普通文本直接切正文，而不是被当成未知参数忽略。
        val useOffset = args.has("offset") && args.get("offset")?.isJsonNull == false
        val requestedOffset = if (useOffset) args.tInt("offset", 0, 0, totalChars) else 0
        val offsetChar = if (useOffset) text.offsetByCodePoints(0, requestedOffset) else 0
        val line = if (useOffset) text.substring(0, offsetChar).count { it == '\n' }.coerceAtMost(starts.lastIndex)
            else args.tInt("startLine", 0, 0, starts.lastIndex)
        val begin = if (useOffset) offsetChar else if (line < starts.size) starts[line] else text.length
        val lineEnd = if(line + 1 < starts.size) starts[line + 1] - 1 else text.length
        val columns = text.codePointCount(begin, lineEnd.coerceAtLeast(begin))
        val column = if (useOffset) text.codePointCount(starts[line], begin) else args.tInt("startColumn", 0, 0, columns)
        val offset = if (useOffset) begin else text.offsetByCodePoints(begin, column)
        val maxChars = args.tInt("maxChars", 49152, 1, 131072)
        val limit = args.tInt("limit", 500, 1, 2000)
        val charEnd = text.offsetByCodePoints(offset, minOf(maxChars, text.codePointCount(offset, text.length)))
        val linesEnd = if(line + limit < starts.size) starts[line + limit] else text.length
        val end = minOf(charEnd, linesEnd)
        val nextLine = starts.binarySearch(end).let { if(it >= 0) it else -it - 2 }.coerceAtLeast(0)
        val nextColumn = text.codePointCount(starts[nextLine], end)
        val startOffset = text.codePointCount(0, offset)
        val nextOffset = text.codePointCount(0, end)
        val value = text.substring(offset, end)
        val pagingHint = if (useOffset) "已按 offset 切正文（Unicode 码点）；续读用 nextOffset，或 startLine/startColumn。" +
            " offset 与 startLine/startColumn 二选一，同时传时 offset 优先。" else
            "续读优先用 nextOffset（Unicode 码点），也可沿用 nextLine/nextColumn；offset 与 startLine/startColumn 二选一。"
        val window = ToolkitIo.json("text" to value, "startLine" to line, "startColumn" to column,
            "offset" to startOffset, "nextOffset" to nextOffset,
            "nextLine" to nextLine, "nextColumn" to nextColumn, "hasMore" to (end < text.length),
            "returnedChars" to value.codePointCount(0, value.length), "totalLines" to starts.size)
        return ToolkitIo.json("text" to value, "textWindow" to window, "targetVersion" to ToolkitIo.version(text.toByteArray(Charsets.UTF_8)),
            "startLine" to line, "startColumn" to column, "offset" to startOffset, "nextOffset" to nextOffset,
            "nextLine" to nextLine, "nextColumn" to nextColumn, "offsetApplied" to useOffset,
            "pagingHint" to pagingHint,
            "hasMore" to (end < text.length), "limit" to limit, "totalChars" to totalChars)
    }
}
