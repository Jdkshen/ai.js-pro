package com.jdkshen.aijspro.mcp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * 跟随应用前台自动启停 MCP 服务（需 McpSettings.autoStart 开启）：
 * - 应用回到前台：自动启动服务（手动停止后 1 分钟内不自动重启，启动失败后不再重试）
 * - 应用退到后台：60 秒后自动停止（期间回到前台则取消）
 * 自动启停会像手动启停一样写入历史记录（标记「自动」）。
 */
class McpAutoLifecycle(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var started = 0

    private val autoStop = Runnable {
        if (started == 0 && McpService.running && McpSettings.autoStart(app)) {
            McpService.autoStopped = true
            McpService.stop(app)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1) {
            handler.removeCallbacks(autoStop)
            val now = System.currentTimeMillis()
            if (McpSettings.autoStart(app) && !McpService.running && McpService.lastError == null &&
                now - McpService.manualStopAt > RESTART_BLOCK_MS) {
                McpService.autoStarted = true
                McpService.start(app)
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && McpSettings.autoStart(app) && McpService.running) {
            handler.removeCallbacks(autoStop)
            handler.postDelayed(autoStop, BACKGROUND_STOP_MS)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        private const val BACKGROUND_STOP_MS = 60_000L
        private const val RESTART_BLOCK_MS = 60_000L
    }
}
