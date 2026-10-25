package com.jdkshen.aijspro.mcp

import android.accessibilityservice.AccessibilityService as AndroidAccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.view.accessibility.AccessibilityService
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * task-245：手机终端 aijs-ui 的命令解析与无障碍执行。
 *
 * 命令通过同应用 receiver（AijsUiReceiver）进入；本对象不直接暴露 Binder/网络。
 * 广播格式（显式）：
 *   am broadcast --user 0 \
 *     -n com.jdkshen.aijspro/.mcp.AijsUiReceiver \
 *     -a com.jdkshen.aijspro.AIJS_UI \
 *     --es command click --ei x 500 --ei y 900
 *
 * 支持：ping/status/click/tap/longClick/swipe/back/home/recents/notifications/input。
 * 结果：setResultCode（0 成功/1 失败）+ app-private last-result.json；
 * am broadcast 普通广播不展示 result，终端可用 read 方式或 logcat 查看。
 *
 * 安全边界：receiver 必须 exported=false；本对象不假设调用方可以跨 UID。
 * 坐标限制在当前显示尺寸；文本最多 4096 字符；不接受 shell 命令。
 */
object AijsUiBridge {
    const val ACTION = "com.jdkshen.aijspro.AIJS_UI"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_X = "x"
    const val EXTRA_Y = "y"
    const val EXTRA_X1 = "x1"
    const val EXTRA_Y1 = "y1"
    const val EXTRA_X2 = "x2"
    const val EXTRA_Y2 = "y2"
    const val EXTRA_DURATION = "duration"
    const val EXTRA_TEXT = "text"
    const val EXTRA_RESOURCE_ID = "resourceId"

    private const val TAG = "AijsUi"
    private const val RESULT_DIR = "aijs-ui"
    private const val RESULT_FILE = "last-result.json"
    private const val MAX_TEXT = 4096
    private const val GESTURE_TIMEOUT_MS = 3000L
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aijs-ui-bridge").apply { isDaemon = true }
    }

    /** receiver 的 goAsync 入口；onReceive 立即返回，执行/写结果在 daemon 线程完成。 */
    @JvmStatic
    fun handleAsync(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult) {
        executor.execute {
            val result = try {
                execute(context, intent)
            } catch (error: Throwable) {
                Log.w(TAG, "command failed", error)
                failure(intent, error.message ?: error.javaClass.simpleName)
            }
            try {
                writeResult(context, result)
            } catch (error: Throwable) {
                Log.w(TAG, "write result failed", error)
            }
            val ok = result.optBoolean("ok", false)
            pending.setResultCode(if (ok) 0 else 1)
            pending.setResultData(result.toString())
            pending.finish()
        }
    }

    /** 当前结果文件；测试脚本/终端可读取。 */
    @JvmStatic
    fun resultFile(context: Context): File = File(File(context.filesDir, RESULT_DIR), RESULT_FILE)

    private fun execute(context: Context, intent: Intent): JSONObject {
        if (!ACTION.equals(intent.action)) return failure(intent, "未知 action：${intent.action}")
        val command = intent.getStringExtra(EXTRA_COMMAND)?.trim()?.lowercase()
            ?: return failure(intent, "缺少 command")
        val started = SystemClock.elapsedRealtime()
        val service = AccessibilityService.instance
        val response = JSONObject()
            .put("ok", false)
            .put("command", command)
            .put("at", System.currentTimeMillis())
        if (command == "ping" || command == "status") {
            response.put("ok", true)
                .put("serviceConnected", service != null)
                .put("serviceEnabled", service != null)
                .put("message", if (service == null) "无障碍服务未连接" else "无障碍服务已连接")
            return finish(response, started)
        }
        if (service == null) {
            return failure(intent, "无障碍服务未连接（请在系统设置开启 AI.js Pro 无障碍）")
                .put("command", command)
        }
        val ok = when (command) {
            "click", "tap" -> click(service, intent)
            "longclick", "long_click" -> longClick(service, intent)
            "swipe" -> swipe(service, intent)
            "back" -> globalAction(service, AndroidAccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> globalAction(service, AndroidAccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> globalAction(service, AndroidAccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> globalAction(service, AndroidAccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "input", "settext" -> inputText(service, intent)
            else -> return failure(intent, "未知 command：$command")
        }
        response.put("ok", ok)
        if (!ok) response.put("error", "命令执行失败：$command")
        return finish(response, started)
    }

    private fun click(service: AccessibilityService, intent: Intent): Boolean {
        val x = intent.getIntExtra(EXTRA_X, -1)
        val y = intent.getIntExtra(EXTRA_Y, -1)
        if (!validPoint(service, x, y)) return false
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        return dispatch(service, path, 50L)
    }

    private fun longClick(service: AccessibilityService, intent: Intent): Boolean {
        val x = intent.getIntExtra(EXTRA_X, -1)
        val y = intent.getIntExtra(EXTRA_Y, -1)
        if (!validPoint(service, x, y)) return false
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        return dispatch(service, path, 900L)
    }

    private fun swipe(service: AccessibilityService, intent: Intent): Boolean {
        val x1 = intent.getIntExtra(EXTRA_X1, -1)
        val y1 = intent.getIntExtra(EXTRA_Y1, -1)
        val x2 = intent.getIntExtra(EXTRA_X2, -1)
        val y2 = intent.getIntExtra(EXTRA_Y2, -1)
        if (!validPoint(service, x1, y1) || !validPoint(service, x2, y2)) return false
        val path = Path()
        path.moveTo(x1.toFloat(), y1.toFloat())
        path.lineTo(x2.toFloat(), y2.toFloat())
        val duration = intent.getIntExtra(EXTRA_DURATION, 300).coerceIn(1, 5000).toLong()
        return dispatch(service, path, duration)
    }

    private fun dispatch(service: AccessibilityService, path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val dispatched = try {
            service.dispatchGesture(gesture, object : AndroidAccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (error: Throwable) {
            Log.w(TAG, "dispatchGesture failed", error)
            false
        }
        if (!dispatched) return false
        try {
            return done.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    private fun globalAction(service: AccessibilityService, action: Int): Boolean =
        try { service.performGlobalAction(action) } catch (error: Throwable) {
            Log.w(TAG, "globalAction failed: $action", error); false
        }

    private fun inputText(service: AccessibilityService, intent: Intent): Boolean {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return false
        if (text.length > MAX_TEXT) return false
        val resourceId = intent.getStringExtra(EXTRA_RESOURCE_ID)
        var node = if (!resourceId.isNullOrBlank()) {
            service.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(resourceId)
                ?.firstOrNull { it.isEditable }
        } else {
            service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }
        if (node == null) return false
        try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            try { node.recycle() } catch (ignored: Throwable) { }
        }
    }

    private fun validPoint(service: AccessibilityService, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        val metrics = service.resources.displayMetrics
        return x < metrics.widthPixels && y < metrics.heightPixels
    }

    private fun finish(response: JSONObject, started: Long): JSONObject {
        response.put("elapsedMs", SystemClock.elapsedRealtime() - started)
        return response
    }

    private fun failure(intent: Intent?, error: String): JSONObject = JSONObject()
        .put("ok", false)
        .put("command", intent?.getStringExtra(EXTRA_COMMAND) ?: "")
        .put("at", System.currentTimeMillis())
        .put("error", error)

    private fun writeResult(context: Context, result: JSONObject) {
        val dir = File(context.filesDir, RESULT_DIR)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建结果目录")
        val target = File(dir, RESULT_FILE)
        val tmp = File(dir, "$RESULT_FILE.tmp")
        tmp.writeText(result.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("结果文件替换失败")
        }
        Log.i(TAG, "result=$result")
    }
}
