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
        val live = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                ?.runningAppProcesses?.filter { it.uid == android.os.Process.myUid() }
                ?.associate { it.pid to it.processName }
        }.getOrNull()
        val result = ArrayList<JsonObject>()
        files.forEach { file ->
            try {
                val text = file.readText(Charsets.UTF_8)
                val root = JsonParser().parse(text).asJsonObject
                val pid = root.get("pid")?.asInt ?: -1
                if (live == null && !File("/proc/$pid").isDirectory) return@forEach
                result.addAll(decode(root, now, live))
            } catch (_: Throwable) {
                // 快照损坏/进程正在写：跳过即可
            }
        }
        return result.sortedWith(compareBy({ it.get("pid")?.asInt ?: -1 }, { it.get("createdAt")?.asLong ?: 0L }))
    }

    fun countAggregated(context: Context): Int = listAggregated(context).size

    /** Snapshots update on view changes, not a heartbeat. Age alone cannot mean the view is gone. */
    internal fun decode(root: JsonObject, now: Long, live: Map<Int, String>?): List<JsonObject> {
        val pid = root.get("pid")?.asInt ?: -1
        if (pid <= 0 || (live != null && pid !in live)) return emptyList()
        val updatedAt = root.get("updatedAt")?.asLong ?: now
        val age = (now - updatedAt).coerceAtLeast(0L)
        return root.getAsJsonArray("entries")?.filter { it.isJsonObject }?.map { element ->
            element.asJsonObject.deepCopy().apply {
                addProperty("pid", pid)
                addProperty("ageMs", age)
                addProperty("snapshotOld", age > MAX_AGE_MS)
                live?.get(pid)?.let { addProperty("process", it) }
            }
        } ?: emptyList()
    }

    fun toJson(context: Context): JsonObject {
        val items = listAggregated(context)
        return JsonObject().apply {
            addProperty("count", items.size)
            addProperty("state", if (isAvailable(context)) "ok" else "unavailable")
            add("items", JsonArray().apply { items.forEach { add(it) } })
        }
    }
}
