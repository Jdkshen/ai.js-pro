package com.jdkshen.aijspro.mcp

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

object McpSettings {
    private const val NAME = "aijs_mcp"
    private const val KEY_PORT = "port"
    private const val KEY_LAN = "lan"
    private const val KEY_LAN_NO_TOKEN = "lan_no_token"
    private const val KEY_TOKEN = "token"
    private const val KEY_OPERATION_PATH = "operation_path"
    private const val KEY_HISTORY_DAYS = "history_days"
    private const val KEY_LOCAL_COMPAT = "local_compat"
    private const val KEY_WRITE_ALLOWED = "write_allowed"
    private const val KEY_EXEC_ALLOWED = "exec_allowed"
    private const val KEY_AUTO_START = "auto_start"
    const val USB_HOST_PORT = 18790

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, 8788).coerceIn(1024, 65535)
    fun setPort(context: Context, value: Int) {
        require(value in 1024..65535)
        prefs(context).edit().putInt(KEY_PORT, value).apply()
    }
    fun lanEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LAN, false)
    fun setLanEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_LAN, value).apply()

    /** 危险选项：开启后局域网请求不再校验 Bearer 令牌（仅建议纯私网临时使用）。 */
    fun allowLanWithoutToken(context: Context): Boolean = prefs(context).getBoolean(KEY_LAN_NO_TOKEN, false)
    fun setAllowLanWithoutToken(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_LAN_NO_TOKEN, value).apply()
    fun operationPath(context: Context): String = prefs(context).getString(KEY_OPERATION_PATH, "").orEmpty()
    fun setOperationPath(context: Context, value: String) {
        val clean = value.replace('\\', '/').trim('/')
        require(!clean.startsWith('/') && clean.split('/').none { it == "." || it == ".." })
        prefs(context).edit().putString(KEY_OPERATION_PATH, clean).apply()
    }
    fun historyDays(context: Context): Int = prefs(context).getInt(KEY_HISTORY_DAYS, 30).coerceIn(1, 365)
    fun setHistoryDays(context: Context, value: Int) {
        require(value in 1..365)
        prefs(context).edit().putInt(KEY_HISTORY_DAYS, value).apply()
    }
    fun localCompatibility(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCAL_COMPAT, true)
    fun setLocalCompatibility(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_LOCAL_COMPAT, value).apply()

    // 授权持久化：开启后记住，重启服务自动恢复；关闭时清除
    fun writeAllowed(context: Context): Boolean = prefs(context).getBoolean(KEY_WRITE_ALLOWED, false)
    fun setWriteAllowed(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_WRITE_ALLOWED, value).apply()
    fun executionAllowed(context: Context): Boolean = prefs(context).getBoolean(KEY_EXEC_ALLOWED, false)
    fun setExecutionAllowed(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_EXEC_ALLOWED, value).apply()

    // 跟随应用前台自动启动/后台自动停止（默认关闭，纯手动）
    fun autoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, false)
    fun setAutoStart(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_START, value).apply()

    @Synchronized fun token(context: Context): String {
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.length >= 32 }?.let { return it }
        return newToken().also { prefs(context).edit().putString(KEY_TOKEN, it).commit() }
    }

    @Synchronized fun resetToken(context: Context): String = newToken().also {
        prefs(context).edit().putString(KEY_TOKEN, it).commit()
    }

    private fun newToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
}
