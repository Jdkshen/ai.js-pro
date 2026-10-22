package com.jdkshen.aijspro.mcp

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * MCP 主进程聚合其它进程的浮窗快照（:script / :node24 各写一份 overlay-<pid>.json）。
 * 只做诊断与计数，不能直接操作其它进程的 View。
 */
internal object OverlayStateReader {
    private const val MAX_AGE_MS = 30_000L
    private const val STATE_DIR = "overlay-state"

    private fun stateDir(context: Context): File = File(context.filesDir, STATE_DIR)

    fun isAvailable(context: Context): Boolean = stateDir(context).isDirectory

    fun listAggregated(context: Context): List<JsonObject> {
        val dir = stateDir(context)
        val files = dir.listFiles { file -> file.isFile && file.name.startsWith("overlay-") && file.name.endsWith(".json") }
            ?: return emptyList()
        val now = System.currentTimeMillis()
        val result = ArrayList<JsonObject>()
        files.forEach { file ->
            try {
                val text = file.readText(Charsets.UTF_8)
                val root = JsonParser().parse(text).asJsonObject
                val updatedAt = root.get("updatedAt")?.asLong ?: file.lastModified()
                if (now - updatedAt > MAX_AGE_MS) return@forEach
                val entries = root.getAsJsonArray("entries") ?: return@forEach
                for (i in 0 until entries.size()) {
                    val element = entries.get(i)
                    if (!element.isJsonObject) continue
                    val entry = element.asJsonObject.deepCopy()
                    if (!entry.has("pid")) entry.addProperty("pid", root.get("pid")?.asInt ?: -1)
                    entry.addProperty("ageMs", (now - updatedAt).coerceAtLeast(0L))
                    result.add(entry)
                }
            } catch (_: Throwable) {
                // 快照损坏/进程正在写：跳过即可
            }
        }
        return result.sortedWith(compareBy({ it.get("pid")?.asInt ?: -1 }, { it.get("createdAt")?.asLong ?: 0L }))
    }

    fun countAggregated(context: Context): Int = listAggregated(context).size

    fun toJson(context: Context): JsonObject {
        val items = listAggregated(context)
        return JsonObject().apply {
            addProperty("count", items.size)
            addProperty("state", if (isAvailable(context)) "ok" else "unavailable")
            add("items", JsonArray().apply { items.forEach { add(it) } })
        }
    }
}
