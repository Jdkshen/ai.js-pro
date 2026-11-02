package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray

/** All ranges refer to one immutable LF-normalized original target. */
internal object ToolkitTextEdits {
    private data class Edit(val start: Int, val end: Int, val replacement: String)
    fun normalize(value: String) = value.replace("\r\n", "\n").replace('\r', '\n')

    fun apply(original: String, edits: JsonArray, preserveLineEndings: Boolean = false): String {
        require(edits.size() in 1..200) { "编辑数量必须为 1..200" }
        val old = normalize(original)
        var budget = 0L
        val changes = edits.map { value ->
            val edit = value.asJsonObject
            val mode = edit.tString("mode"); val match = normalize(edit.tString("matchText", ""))
            val replacement = normalize(edit.tString("writeText"))
            budget += match.codePointCount(0, match.length).toLong() + replacement.codePointCount(0, replacement.length)
            require(budget <= 524288) { "编辑文本总量超过 524288 码点" }
            if (mode == "write_target") {
                require(edits.size() == 1 && match.isEmpty()) { "write_target 必须单独使用且 matchText 为空" }
                Edit(0, old.length, replacement)
            } else {
                require(match.isNotEmpty()) { "匹配文本不能为空" }
                val at = old.indexOf(match)
                if (at < 0) throw ToolkitException("TEXT_MATCH_NOT_FOUND", "匹配文本不存在")
                if (old.indexOf(match, at + 1) >= 0) throw ToolkitException("TEXT_MATCH_AMBIGUOUS", "匹配文本必须唯一，请增加上下文")
                when (mode) {
                    "replace_match" -> Edit(at, at + match.length, replacement)
                    "insert_before_match" -> Edit(at, at, replacement)
                    "insert_after_match" -> Edit(at + match.length, at + match.length, replacement)
                    else -> throw IllegalArgumentException("编辑模式无效")
                }
            }
        }.sortedWith(compareBy<Edit> { it.start }.thenBy { it.end })
        changes.zipWithNext().forEach { (a, b) ->
            require(a.end <= b.start && !(a.start == a.end && a.start == b.start)) { "编辑区间重叠或插入位置冲突" }
        }
        val positions = if (preserveLineEndings) IntArray(old.length + 1) else null
        if (positions != null) {
            var raw = 0; var normalized = 0
            while (raw < original.length) {
                positions[normalized++] = raw
                raw += if (original[raw] == '\r' && raw + 1 < original.length && original[raw + 1] == '\n') 2 else 1
            }
            positions[normalized] = raw
        }
        val newline = when {
            !preserveLineEndings -> "\n"
            else -> Regex("\r\n|\r|\n").find(original)?.value ?: "\n"
        }
        val result = StringBuilder(if (preserveLineEndings) original else old)
        changes.asReversed().forEach { change ->
            val begin = positions?.get(change.start) ?: change.start
            val end = positions?.get(change.end) ?: change.end
            result.replace(begin, end, change.replacement.replace("\n", newline))
        }
        require(result.toString().toByteArray(Charsets.UTF_8).size <= ToolkitIo.MAX_TEXT) { "编辑结果超过 10 MiB" }
        return result.toString()
    }
}
