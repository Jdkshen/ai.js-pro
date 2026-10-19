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

    data class State(
        val running: Boolean,
        val updatedAt: Long,
        val port: Int,
        val localUrl: String,
        val lanUrl: String,
        val error: String
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
            .remove(KEY_ERROR)
            .apply()
        Log.i(TAG, "C-MCP: state=starting heartbeat=" + now)
    }

    @Synchronized
    fun saveRunning(context: Context, port: Int, localUrl: String, lanUrl: String) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_UPDATED_AT, now)
            .putInt(KEY_PORT, port)
            .putString(KEY_LOCAL_URL, localUrl)
            .putString(KEY_LAN_URL, lanUrl)
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
            .apply()
        Log.i(TAG, "C-MCP: heartbeat state=running port=" + port + " updated_at=" + now)
    }

    @Synchronized
    fun saveStopped(context: Context, error: String? = null) {
        val now = System.currentTimeMillis()
        val editor = prefs(context).edit()
            .putBoolean(KEY_RUNNING, false)
            .putLong(KEY_UPDATED_AT, now)
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
            .apply()
        Log.w(TAG, "C-MCP: state=error heartbeat=" + now + " error=" + error)
    }

    fun read(context: Context): State {
        val prefs = prefs(context)
        return State(
            running = prefs.getBoolean(KEY_RUNNING, false),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
            port = prefs.getInt(KEY_PORT, 0),
            localUrl = prefs.getString(KEY_LOCAL_URL, "").orEmpty(),
            lanUrl = prefs.getString(KEY_LAN_URL, "").orEmpty(),
            error = prefs.getString(KEY_ERROR, "").orEmpty()
        )
    }

    fun freshRunning(context: Context): Boolean = read(context).fresh()
}
