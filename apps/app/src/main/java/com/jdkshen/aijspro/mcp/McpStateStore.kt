package com.jdkshen.aijspro.mcp

import android.content.Context
import android.util.Log

/**
 * task-105：MCP 运行状态持久化 + 心跳（对齐 Pro 9.3.11 的 aiagent_mcp_state 契约）。
 *
 * 键：running / updated_at / port / local_url / lan_url / error。
 * UI 只有在 running 且 now - updated_at < HEARTBEAT_TIMEOUT_MS 时才显示「运行中」，
 * 进程被系统回收/服务异常退出后会自然变成「已停止」，避免假运行中。
 */
object McpStateStore {

    const val PREFS_NAME = "aiagent_mcp_state"
    const val HEARTBEAT_INTERVAL_MS = 5_000L
    const val HEARTBEAT_TIMEOUT_MS = 10_000L
    private const val TAG = "McpService"

    private const val KEY_RUNNING = "running"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_PORT = "port"
    private const val KEY_LOCAL_URL = "local_url"
    private const val KEY_LAN_URL = "lan_url"
    private const val KEY_ERROR = "error"
    /** 显式状态：starting / running / stopped / error（`.4` 复核 J4：不再靠三元组猜）。 */
    private const val KEY_STATE = "state"
    /** 本次启动原因：auto / manual（`.4` 复核 J3：autoStarted 不再恒为 false）。 */
    private const val KEY_START_REASON = "start_reason"

    data class State(
        val running: Boolean,
        val updatedAt: Long,
        val port: Int,
        val localUrl: String,
        val lanUrl: String,
        val error: String,
        val state: String = "",
        val startReason: String = ""
    ) {
        fun fresh(now: Long = System.currentTimeMillis()): Boolean =
            running && updatedAt > 0 && now - updatedAt in 0..HEARTBEAT_TIMEOUT_MS
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun saveStarting(context: Context) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putLong(KEY_UPDATED_AT, now)
            .putString(KEY_STATE, "starting")
            .remove(KEY_ERROR)
            .apply()
        Log.i(TAG, "C-MCP: state=starting heartbeat=" + now)
    }

    @Synchronized
    fun saveRunning(context: Context, port: Int, localUrl: String, lanUrl: String, autoStarted: Boolean = false) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_UPDATED_AT, now)
            .putInt(KEY_PORT, port)
            .putString(KEY_LOCAL_URL, localUrl)
            .putString(KEY_LAN_URL, lanUrl)
            .putString(KEY_STATE, "running")
            .putString(KEY_START_REASON, if (autoStarted) "auto" else "manual")
            .remove(KEY_ERROR)
            .apply()
        Log.i(TAG, "C-MCP: state=running port=" + port + " heartbeat=" + now
            + " localUrl=" + localUrl + " lanUrl=" + lanUrl)
    }

    @Synchronized
    fun saveHeartbeat(context: Context, port: Int, localUrl: String, lanUrl: String) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_UPDATED_AT, now)
            .putInt(KEY_PORT, port)
            .putString(KEY_LOCAL_URL, localUrl)
            .putString(KEY_LAN_URL, lanUrl)
            .putString(KEY_STATE, "running")
            .apply()
        Log.i(TAG, "C-MCP: heartbeat state=running port=" + port + " updated_at=" + now)
    }

    @Synchronized
    fun saveStopped(context: Context, error: String? = null) {
        val now = System.currentTimeMillis()
        val editor = prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putLong(KEY_UPDATED_AT, now)
            .putString(KEY_STATE, "stopped")
        if (error.isNullOrEmpty()) {
            editor.remove(KEY_ERROR)
        } else {
            editor.putString(KEY_ERROR, error)
        }
        editor.apply()
        Log.i(TAG, "C-MCP: state=stopped heartbeat=" + now + " error=" + (error ?: ""))
    }

    @Synchronized
    fun saveError(context: Context, error: String) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putLong(KEY_UPDATED_AT, now)
            .putString(KEY_ERROR, error)
            .putString(KEY_STATE, "error")
            .apply()
        Log.w(TAG, "C-MCP: state=error heartbeat=" + now + " error=" + error)
    }

    /**
     * 只读摘要给 `get_status.mcp`：`.4` P1-G。
     *
     * 语义（`.4` 复核 J5 修正）：
     * - `listening`：**进程内真实监听**（服务实例活着 + 已绑到端口），不是心跳推断；
     * - `fresh` / `heartbeatAgeMs`：心跳新鲜度（心跳跑在主 looper 上，主线程被阻塞时可能短暂为 false）；
     * - `status`：优先看 `listening`，其次看持久化的显式 state（starting/running/stopped/error）。
     */
    @Synchronized
    fun statusJson(
        context: Context,
        autoStarted: Boolean = false,
        liveListening: Boolean = false,
        livePort: Int = 0
    ): com.google.gson.JsonObject {
        val state = read(context)
        val now = System.currentTimeMillis()
        val age = if (state.updatedAt > 0) now - state.updatedAt else -1L
        val fresh = state.fresh(now)
        val port = if (livePort > 0) livePort else state.port
        return com.google.gson.JsonObject().apply {
            addProperty("status", statusLabel(liveListening, state.state, fresh))
            addProperty("listening", liveListening)
            addProperty("fresh", fresh)
            addProperty("running", state.running)
            addProperty("port", port)
            addProperty("localUrl", if (port > 0) "http://127.0.0.1:" + port + "/mcp" else state.localUrl)
            addProperty("lanUrl", state.lanUrl)
            addProperty("updatedAt", state.updatedAt)
            addProperty("heartbeatAgeMs", age)
            addProperty("heartbeatIntervalMs", HEARTBEAT_INTERVAL_MS)
            addProperty("heartbeatTimeoutMs", HEARTBEAT_TIMEOUT_MS)
            addProperty("lastError", state.error)
            addProperty("startReason", state.startReason.ifEmpty { if (autoStarted) "auto" else "manual" })
            addProperty("autoStarted", autoStarted || state.startReason == "auto")
            addProperty("foregroundService", true)
            addProperty("sticky", true)
            addProperty("state", state.state.ifEmpty { if (state.running) "running" else "stopped" })
            if (!liveListening) {
                addProperty("recovery",
                    "本次进程内没有在监听的 MCP 服务实例：打开 AI.js Pro 让服务自动拉起" +
                    "（前台服务 + START_STICKY 也会重建），或稍等 1~2 秒重试。" +
                    "注意：能拿到本响应说明服务其实是活的；连接被拒/超时通常表示 App 进程没在跑" +
                    "（也可能是端口占用或网络问题）。")
            }
        }
    }

    /**
     * status 文案判定（纯函数，便于单测）：优先进程内监听，其次显式 state，最后看心跳。
     */
    fun statusLabel(liveListening: Boolean, persistedState: String, fresh: Boolean): String {
        if (liveListening) return "running"
        return when {
            persistedState == "error" -> "error"
            persistedState == "starting" && fresh -> "running"
            persistedState == "starting" -> "starting"
            persistedState == "stopped" -> "stopped"
            fresh -> "running"
            else -> if (persistedState.isEmpty()) "stopped" else "stale"
        }
    }

    fun read(context: Context): State {
        val prefs = prefs(context)
        return State(
            running = prefs.getBoolean(KEY_RUNNING, false),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
            port = prefs.getInt(KEY_PORT, 0),
            localUrl = prefs.getString(KEY_LOCAL_URL, "").orEmpty(),
            lanUrl = prefs.getString(KEY_LAN_URL, "").orEmpty(),
            error = prefs.getString(KEY_ERROR, "").orEmpty(),
            state = prefs.getString(KEY_STATE, "").orEmpty(),
            startReason = prefs.getString(KEY_START_REASON, "").orEmpty()
        )
    }

    fun freshRunning(context: Context): Boolean = read(context).fresh()
}
