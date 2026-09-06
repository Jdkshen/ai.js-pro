package com.jdkshen.aijspro.mcp

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 跟随应用自动启动 MCP 服务（需 McpSettings.autoStart 开启）：
 * - 应用回到前台：服务未运行时自动启动（启动失败后不再重试）
 * - 启动后常驻运行，退后台不会停止；只有手动停止或系统回收进程才会停止
 * - 手动停止后重新打开 App（回到前台）就会再次自动启动
 * 自动启动会像手动启停一样写入历史记录（标记「自动」）。
 */
class McpAutoLifecycle(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1) {
            if (McpSettings.autoStart(app) && !McpService.running && McpService.lastError == null) {
                McpService.autoStarted = true
                McpService.start(app)
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
