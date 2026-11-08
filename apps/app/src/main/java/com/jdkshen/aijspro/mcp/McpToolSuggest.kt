package com.jdkshen.aijspro.mcp

/**
 * 「未知工具」时的近邻建议（`.4` P0-1：他们调 `file_write_text` 不存在，报错只有 `not a function`，
 * 白费一轮）。纯逻辑，便于 JVM 单测。
 */
internal object McpToolSuggest {

    fun suggest(requested: String, candidates: Collection<String>, limit: Int = 5): List<String> {
        val query = requested.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        return candidates
            .distinct()
            .map { it to score(query, it.lowercase()) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
    }

    private fun score(query: String, candidate: String): Int {
        if (query == candidate) return 1000
        var score = 0
        val qTokens = query.split('_').filter { it.isNotEmpty() }
        val cTokens = candidate.split('_').filter { it.isNotEmpty() }
        score += qTokens.count { it in cTokens } * 12
        var prefix = 0
        while (prefix < query.length && prefix < candidate.length && query[prefix] == candidate[prefix]) prefix++
        score += prefix * 3
        if (candidate.contains(query) || query.contains(candidate)) score += 15
        val d = distance(query, candidate, 6)
        if (d <= 6) score += (7 - d) * 4
        return score
    }

    /** 带上限的 Levenshtein：超过 cap 直接返回 cap+1，避免长名字互相拖累。 */
    private fun distance(a: String, b: String, cap: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > cap) return cap + 1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}
