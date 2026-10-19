package com.jdkshen.aijspro.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        history = McpHistoryStore(applicationContext)
        McpStateStore.saveStarting(this)
        startForeground(NOTIFICATION_ID, notification("正在启动本地 MCP"))
        try {
            val router = McpTools(applicationContext, ::record)
            val requestedPort = McpSettings.port(this)
            var startedEndpoint: McpHttpServer? = null
            var boundPort = 0
            var lastFailure: String? = null
            // task-105：对齐 Pro：默认 8765，被占用时 8765..8795 顺延；始终绑 0.0.0.0（仍保留 token 鉴权）。
            val portEnd = if (requestedPort in McpSettings.MIN_PORT..McpSettings.MAX_PORT) {
                McpSettings.MAX_PORT
            } else {
                requestedPort
            }
            for (candidate in requestedPort..portEnd) {
                var endpoint: McpHttpServer? = null
                try {
                    endpoint = McpHttpServer("0.0.0.0", candidate, McpSettings.token(this),
                        McpSettings.localCompatibility(this),
                        allowUnauthenticatedLan = McpSettings.allowLanWithoutToken(this),
                        handler = { request ->
                            requestCount++
                            lastRequestAt = System.currentTimeMillis()
                            val method = request.get("method")?.asString.orEmpty()
                            if (method.isNotEmpty()) record("请求 · $method")
                            router.handle(request)
                        },
                        pageProvider = {
                            statusPage(router, candidate, true, McpSettings.token(this))
                        })
                    endpoint.start()
                    startedEndpoint = endpoint
                    boundPort = candidate
                    Log.i(LOG_TAG, "C-MCP: bind ok port=" + candidate + " host=0.0.0.0")
                    break
                } catch (bindError: Exception) {
                    lastFailure = bindError.localizedMessage ?: bindError.javaClass.simpleName
                    Log.w(LOG_TAG, "C-MCP: bind failed port=" + candidate + ": " + lastFailure)
                    runCatching { endpoint?.stop() }
                }
            }
            if (startedEndpoint == null) {
                val message = "端口 " + requestedPort + ".." + McpSettings.MAX_PORT + " 均被占用" +
                    (lastFailure?.let { "（" + it + "）" } ?: "")
                lastError = message
                McpStateStore.saveError(this, message)
                record("启动失败 · " + message)
                stopSelf()
                return
            }
            if (boundPort != requestedPort) {
                McpSettings.setPort(this, boundPort)
                record("端口 " + requestedPort + " 被占用，已顺延到 " + boundPort)
            }
            tools = router
            server = startedEndpoint
            val localUrl = "http://127.0.0.1:" + boundPort + "/mcp"
            val lanUrl = lanAddress(boundPort)
            McpStateStore.saveRunning(this, boundPort, localUrl, lanUrl)
            running = true
            lastError = null
            // 恢复持久化的授权（记住上次开启状态）
            allowWrite = McpSettings.writeAllowed(this)
            allowExecution = McpSettings.executionAllowed(this)
            startHeartbeat()
            val auto = if (autoStarted) " · 自动" else ""
            autoStarted = false
            record("服务已启动 · 局域网 · 端口 " + boundPort + auto + (if (allowWrite || allowExecution) " · 已恢复授权" else ""))
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(localUrl))
            Log.i(LOG_TAG, "C-MCP: state=running port=" + boundPort + " localUrl=" + localUrl + " lanUrl=" + lanUrl)
        } catch (error: Exception) {
            lastError = error.localizedMessage ?: "端口启动失败"
            McpStateStore.saveError(this, lastError!!)
            record("启动失败 · " + lastError)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            McpStateStore.saveStopped(this)
            stopSelf()
            return START_NOT_STICKY
        }
        // task-105：对齐 Pro，进程被系统回收后 START_STICKY 重建服务（onCreate 会重新绑定端口）。
        return START_STICKY
    }

    override fun onDestroy() {
        stopHeartbeat()
        running = false
        allowWrite = false
        allowExecution = false
        tools?.stopRuns()
        tools = null
        server?.stop()
        server = null
        McpStateStore.saveStopped(this, lastError)
        record(if (autoStopped) "服务已停止 · 自动" else "服务已停止")
        autoStopped = false
        stopForeground(true)
        Log.i(LOG_TAG, "C-MCP: state=stopped")
        super.onDestroy()
    }

    private fun lanAddress(port: Int): String =
        lanAddresses(this, port).firstOrNull().orEmpty()

    private fun startHeartbeat() {
        if (heartbeatRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                if (!running) return
                val port = server?.localPort?.takeIf { it > 0 } ?: McpSettings.port(this@McpService)
                val localUrl = "http://127.0.0.1:" + port + "/mcp"
                McpStateStore.saveHeartbeat(this@McpService, port, localUrl, lanAddress(port))
                heartbeatHandler.postDelayed(this, McpStateStore.HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatRunnable = runnable
        heartbeatHandler.postDelayed(runnable, McpStateStore.HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
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
            // task-105：对齐 Pro：渠道 autojs_mcp / 名称「本地 MCP」/ IMPORTANCE_LOW。
            val channel = NotificationChannel(CHANNEL, "本地 MCP", NotificationManager.IMPORTANCE_LOW).apply {
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
            .setContentTitle("AI.js Pro · 本地 MCP")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(content)
            .addAction(0, "停止", stop)
            .build()
    }

    companion object {
        private const val LOG_TAG = "McpService"
        private const val CHANNEL = "autojs_mcp"
        private const val NOTIFICATION_ID = 1001
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
        fun stop(context: Context) {
            McpStateStore.saveStopped(context)
            context.applicationContext.stopService(Intent(context, McpService::class.java))
        }

        // task-105：状态以持久化 + 心跳为准（UI 不再只信 running 内存布尔）。
        fun readState(context: Context): McpStateStore.State = McpStateStore.read(context)
        fun isRunningFresh(context: Context): Boolean = McpStateStore.freshRunning(context)
        fun activePort(context: Context): Int =
            readState(context).port.takeIf { it > 0 } ?: McpSettings.port(context)
        fun localAddress(context: Context) = "http://127.0.0.1:" + activePort(context) + "/mcp"
        fun lanAddresses(context: Context): List<String> = lanAddresses(context, activePort(context))
        fun lanAddresses(context: Context, port: Int): List<String> = try {
            // 只收真实 LAN：排除 down/回环、蜂窝、VPN、P2P 和虚拟网卡；优先 WiFi/热点/有线。
            val excludedPrefixes = listOf("rmnet", "ccmni", "p2p", "tun", "dummy")
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { network -> network.isUp && !network.isLoopback }
                .filter { network ->
                    val name = network.name?.lowercase(java.util.Locale.ROOT).orEmpty()
                    excludedPrefixes.none { prefix -> name.startsWith(prefix) }
                }
                .flatMap { network ->
                    val name = network.name?.lowercase(java.util.Locale.ROOT).orEmpty()
                    val preferred = name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")
                    Collections.list(network.inetAddresses).mapNotNull { address ->
                        val host = address.hostAddress ?: return@mapNotNull null
                        if (address !is java.net.Inet4Address || address.isLoopbackAddress ||
                            address.isLinkLocalAddress || !address.isSiteLocalAddress) return@mapNotNull null
                        Pair(preferred, host)
                    }
                }
                .sortedWith(compareByDescending<Pair<Boolean, String>> { it.first }.thenBy { it.second })
                .distinctBy { it.second }
                .map { "http://" + it.second + ":" + port + "/mcp" }
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
