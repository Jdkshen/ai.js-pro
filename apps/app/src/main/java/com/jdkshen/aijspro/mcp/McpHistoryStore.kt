package com.jdkshen.aijspro.mcp

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent audit log used by the MCP history page. It never stores access tokens. */
class McpHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "mcp/history.jsonl")

    data class Entry(
        val time: Long,
        val kind: String,
        val title: String,
        val detail: String,
        val state: String
    ) {
        val displayTime: String
            get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }

    fun record(kind: String, title: String, detail: String = "", state: String = "ok") = synchronized(LOCK) {
        file.parentFile?.mkdirs()
        val line = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("kind", kind.take(32))
            put("title", title.take(160))
            put("detail", detail.take(2000))
            put("state", state.take(24))
        }.toString()
        file.appendText(line + "\n", Charsets.UTF_8)
        trimLocked()
    }

    fun list(limit: Int = 200): List<Entry> = synchronized(LOCK) {
        if (!file.isFile) return@synchronized emptyList()
        val cutoff = System.currentTimeMillis() - McpSettings.historyDays(appContext).toLong() * 86_400_000L
        file.readLines(Charsets.UTF_8).asReversed().asSequence().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                Entry(json.getLong("time"), json.optString("kind"), json.optString("title"),
                    json.optString("detail"), json.optString("state", "ok"))
            }.getOrNull()
        }.filter { it.time >= cutoff }.take(limit.coerceIn(1, 500)).toList()
    }

    private fun trimLocked() {
        if (!file.isFile || file.length() < 256 * 1024) return
        val kept = file.readLines(Charsets.UTF_8).takeLast(500)
        file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"), Charsets.UTF_8)
    }

    companion object { private val LOCK = Any() }
}
