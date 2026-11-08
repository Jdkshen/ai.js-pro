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
            // W4 task-5 B：新一次启动尝试开始，上一轮的 start_reason 必须失效——否则这次启动
            // 在 saveRunning 之前就失败时，get_status.mcp 会把上一轮的 auto/manual 报成这一次的。
            .remove(KEY_START_REASON)
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
        // 有意不清 KEY_START_REASON（W4 task-5 B 复核）：停止后没有「本次尝试」，保留上一轮的真实
        // 启动原因比清掉更准确；清掉反而会让 statusJson 的 ifEmpty 回退去伪造 "manual"
        // （McpService.autoStarted 在启动成功时已清零）。进程被系统回收的路径不会走到这里，
        // 与 J3 的告警（启动失败/重启后仍报上一轮）无关。
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
            // W4 task-5 B：启动失败（例如 8765..8795 全被占）不能继续报上一轮的 start_reason。
            // 清掉后 statusJson 的 ifEmpty 回退会用 McpService.autoStarted —— 那是本次尝试的实时标志，
            // 成功前不会被清零（McpService.onCreate 只在 start 成功后置 false），所以回退值是正确的。
            .remove(KEY_START_REASON)
            .apply()
        Log.w(TAG, "C-MCP: state=error heartbeat=" + now + " error=" + error)
    }

    /**
     * 只读摘要给 `get_status.mcp`：`.4` P1-G。
     *
     * 语义（`.4` 复核 J5 修正 + W4 task-10 A/B）：
     * - `listening`：**进程内真实监听** = 服务实例活着 && accept 循环在跑（`McpHttpServer.acceptLoopAlive`）；
     * - `running`：与 `listening` **同源**（旧实现直接回持久化布尔，进程被回收后仍是 true → 只看 running 的
     *   调用方会误判）；旧值仍可从 `persistedRunning` 读到，信息不丢；
     * - `fresh` / `heartbeatAgeMs`：心跳新鲜度（心跳跑在主 looper 上，主线程被阻塞时可能短暂为 false）；
     * - `status`：服务活着但 accept 循环已死 → `degraded`（不许变静默僵尸）；否则优先进程内监听，
     *   其次看持久化的显式 state（starting/running/stopped/error）；
     * - `listener`：accept 循环的 alive / failureCount / degraded / lastFailure。
     */
    @Synchronized
    fun statusJson(
        context: Context,
        autoStarted: Boolean = false,
        liveListening: Boolean = false,
        livePort: Int = 0,
        listenerAlive: Boolean = liveListening,
        listenerFailureCount: Int = 0,
        listenerLastFailure: String? = null
    ): com.google.gson.JsonObject = statusJson(
        read(context), System.currentTimeMillis(), autoStarted, liveListening, livePort,
        listenerAlive, listenerFailureCount, listenerLastFailure)

    /** 纯函数重载（不碰 Android）：便于单测钉住 `get_status.mcp` 的字段契约（W4 task-10 B）。 */
    fun statusJson(
        state: State,
        now: Long,
        autoStarted: Boolean,
        liveListening: Boolean,
        livePort: Int,
        listenerAlive: Boolean,
        listenerFailureCount: Int,
        listenerLastFailure: String?
    ): com.google.gson.JsonObject {
        val age = if (state.updatedAt > 0) now - state.updatedAt else -1L
        val fresh = state.fresh(now)
        val port = if (livePort > 0) livePort else state.port
        // B：真实监听 = 服务实例活着 + accept 循环在跑；顶层 running 与它同源，不得在未监听时为 true。
        val listening = liveListening && listenerAlive
        return com.google.gson.JsonObject().apply {
            addProperty("status", statusLabel(liveListening, state.state, fresh, listenerAlive))
            addProperty("listening", listening)
            addProperty("fresh", fresh)
            addProperty("running", listening)
            addProperty("persistedRunning", state.running)
            add("listener", com.google.gson.JsonObject().apply {
                addProperty("alive", listenerAlive)
                addProperty("failureCount", listenerFailureCount)
                addProperty("degraded", listenerFailureCount > 0)
                addProperty("lastFailure", listenerLastFailure)
            })
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
            if (!listening) {
                addProperty("recovery",
                    "本次进程内没有在监听的 MCP 服务实例：打开 AI.js Pro 让服务自动拉起" +
                    "（前台服务 + START_STICKY 也会重建），或稍等 1~2 秒重试。" +
                    "若刚刚启动失败过（例如端口被占），自动拉起会在 " +
                    (McpAutoStartPolicy.RETRY_AFTER_MS / 1000) + " 秒后重试，期间请在 MCP 页面手动开启。" +
                    "注意：能拿到本响应说明服务其实是活的；连接被拒/超时通常表示 App 进程没在跑" +
                    "（也可能是端口占用或网络问题）。")
            }
        }
    }

    /**
     * status 文案判定（纯函数，便于单测）：优先进程内监听，其次显式 state，最后看心跳。
     * W4 task-10 A：服务实例还在但 accept 循环已死 → `degraded`，不能继续报 running（静默僵尸）。
     * `acceptLoopAlive` 默认 true，老调用方（3 参）语义不变。
     */
    fun statusLabel(liveListening: Boolean, persistedState: String, fresh: Boolean, acceptLoopAlive: Boolean = true): String {
        if (liveListening && !acceptLoopAlive) return "degraded"
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
