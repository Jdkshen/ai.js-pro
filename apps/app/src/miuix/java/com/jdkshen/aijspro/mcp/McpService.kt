package com.jdkshen.aijspro.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.ui.mcp.MiuixMcpActivity
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

class McpService : Service() {
    private var server: McpHttpServer? = null
    private var tools: McpTools? = null
    private lateinit var history: McpHistoryStore

    override fun onCreate() {
        super.onCreate()
        history = McpHistoryStore(applicationContext)
        startForeground(NOTIFICATION_ID, notification("正在启动脚本 MCP"))
        try {
            val router = McpTools(applicationContext, ::record)
            val endpoint = McpHttpServer(if (McpSettings.lanEnabled(this)) "0.0.0.0" else "127.0.0.1",
                McpSettings.port(this), McpSettings.token(this), McpSettings.localCompatibility(this),
                allowUnauthenticatedLan = McpSettings.allowLanWithoutToken(this),
                handler = { request ->
                    requestCount++
                    lastRequestAt = System.currentTimeMillis()
                    val method = request.get("method")?.asString.orEmpty()
                    if (method.isNotEmpty()) record("请求 · $method")
                    router.handle(request)
                },
                pageProvider = {
                    statusPage(router, McpSettings.port(this), McpSettings.lanEnabled(this), McpSettings.token(this))
                })
            endpoint.start()
            tools = router
            server = endpoint
            running = true
            lastError = null
            // 恢复持久化的授权（记住上次开启状态）
            allowWrite = McpSettings.writeAllowed(this)
            allowExecution = McpSettings.executionAllowed(this)
            val auto = if (autoStarted) " · 自动" else ""
            autoStarted = false
            record("服务已启动 · ${if (McpSettings.lanEnabled(this)) "局域网" else "仅本机"}$auto" + if (allowWrite || allowExecution) " · 已恢复授权" else "")
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(localAddress(this)))
        } catch (error: Exception) {
            lastError = error.localizedMessage ?: "端口启动失败"
            record("启动失败 · $lastError")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        allowWrite = false
        allowExecution = false
        tools?.stopRuns()
        tools = null
        server?.stop()
        server = null
        record(if (autoStopped) "服务已停止 · 自动" else "服务已停止")
        autoStopped = false
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun record(message: String) {
        val stamped = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}  $message"
        events.add(stamped)
        while (events.size > 50) events.removeAt(0)
        val failed = message.contains("失败") || message.contains("异常") || message.contains("failed")
        runCatching {
            history.record("mcp", message.substringBefore(" · "), message, if (failed) "error" else "ok")
        }
    }

    private fun statusPage(tools: McpTools, port: Int, lan: Boolean, token: String): String {
        val names = runCatching { tools.toolNames() }.getOrDefault(emptyList())
        val items = names.joinToString("") { "<li><code>${it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</code></li>" }
        val mode = if (lan) "局域网（0.0.0.0）" else "仅本机（127.0.0.1）"
        val tokenHint = if (lan) token.take(8) + "…" else "本机访问免认证，局域网需 Bearer"
        return """<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>AI.js Pro · 脚本 MCP</title></head>
<body style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;background:#F7F7F7;color:#1F2328;margin:0;padding:28px 20px;">
<div style="max-width:720px;margin:0 auto;">
<h1 style="font-size:22px;margin:0 0 6px;">AI.js Pro · 脚本 MCP</h1>
<p style="color:#57606A;margin:0 0 20px;">Model Context Protocol 端点 — ${if (lan) "0.0.0.0" else "127.0.0.1"}:$port · $mode</p>
<div style="background:#fff;border:1px solid #E1E4E8;border-radius:12px;padding:18px 20px;margin-bottom:16px;">
<p style="margin:0 0 10px;"><strong>服务运行中</strong> · 端点只接受 <code>POST /mcp</code>（JSON-RPC 2.0）。</p>
<p style="margin:0 0 4px;color:#57606A;">浏览器无法直接“对话”，请使用支持 MCP 的客户端（VS Code / Claude / Codex 等）连接；或在终端验证：</p>
<pre style="background:#1F2328;color:#E6EDF3;border-radius:8px;padding:12px;overflow-x:auto;font-size:12px;line-height:1.6;">curl -X POST http://127.0.0.1:$port/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2025-06-18" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"browser","version":"1.0"}}}'</pre>
<p style="margin:12px 0 0;color:#57606A;">认证：$tokenHint</p>
</div>
<div style="background:#fff;border:1px solid #E1E4E8;border-radius:12px;padding:18px 20px;">
<p style="margin:0 0 10px;font-weight:600;">可用工具（${names.size}）</p>
<ul style="margin:0;padding-left:20px;columns:2;column-gap:24px;font-size:13px;line-height:1.9;">$items</ul>
</div>
</div>
</body></html>"""
    }

    private fun notification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "脚本 MCP", NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI.js Pro 本地脚本开发连接"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val content = PendingIntent.getActivity(this, 11, Intent(this, MiuixMcpActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable)
        val stop = PendingIntent.getService(this, 12, Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ai_js_pro_notification)
            .setContentTitle("AI.js Pro · 脚本 MCP")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(content)
            .addAction(0, "停止", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "aijs_mcp_service"
        private const val NOTIFICATION_ID = 8788
        private const val ACTION_STOP = "com.jdkshen.aijspro.mcp.STOP"
        @Volatile var running = false
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var allowWrite = false
        @Volatile var allowExecution = false
        @Volatile var requestCount = 0
            private set
        @Volatile var autoStarted = false   // 本次启动是否为自动触发（App 回前台）
        @Volatile var autoStopped = false   // 本次停止是否为自动触发（退后台超时）
        @Volatile var lastRequestAt = 0L    // 最近一次 MCP 请求时间（用于自动停止感知活跃连接）
        private val events = CopyOnWriteArrayList<String>()

        fun start(context: Context) {
            lastError = null
            val intent = Intent(context.applicationContext, McpService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.applicationContext.startForegroundService(intent)
            else context.applicationContext.startService(intent)
        }
        fun stop(context: Context) { context.applicationContext.stopService(Intent(context, McpService::class.java)) }
        fun localAddress(context: Context) = "http://127.0.0.1:${McpSettings.port(context)}/mcp"
        fun lanAddresses(context: Context): List<String> = try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { network ->
                Collections.list(network.inetAddresses).filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                    .map { "http://${it.hostAddress}:${McpSettings.port(context)}/mcp" }
            }.distinct().sorted()
        } catch (_: Exception) { emptyList() }
        fun recentEvents(): List<String> = events.toList().takeLast(30).reversed()
        fun history(context: Context): List<McpHistoryStore.Entry> = McpHistoryStore(context).list()
        fun workspaceStore(context: Context): McpWorkspaceStore {
            val scripts = File(Pref.getScriptDirPath()).canonicalFile
            val operation = File(scripts, McpSettings.operationPath(context)).canonicalFile
            return McpWorkspaceStore(context, operation)
        }
    }
}
