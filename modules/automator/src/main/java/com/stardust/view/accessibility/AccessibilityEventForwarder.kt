package com.stardust.view.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * task-156：把主进程的无障碍事件以同 UID 广播转发给 :node24 进程，供 Node 的
 * accessibility.on(...) 事件订阅使用。
 *
 * 通路：AccessibilityService.onAccessibilityEvent -> 本 delegate -> sendBroadcast
 * -> :node24 Node24HostBridge 的 receiver -> Node 轮询队列 -> prelude accessibility.on。
 *
 * 默认关闭；:node24 通过 NODE_ACCESSIBILITY_SUBSCRIBE/UNSUBSCRIBE 广播控制开关，
 * 避免没人订阅时把事件广播出去。
 */
object AccessibilityEventForwarder {
    const val ACTION_SUBSCRIBE = "com.jdkshen.aijspro.NODE_ACCESSIBILITY_SUBSCRIBE"
    const val ACTION_UNSUBSCRIBE = "com.jdkshen.aijspro.NODE_ACCESSIBILITY_UNSUBSCRIBE"
    const val ACTION_EVENT = "com.jdkshen.aijspro.NODE_ACCESSIBILITY_EVENT"
    // task-171：Node accessibility.enableKeyEvents / lockScreen
    const val ACTION_KEY_EVENTS = "com.jdkshen.aijspro.NODE_ACCESSIBILITY_KEY_EVENTS"
    const val ACTION_LOCK_SCREEN = "com.jdkshen.aijspro.NODE_ACCESSIBILITY_LOCK_SCREEN"
    private const val TAG = "AccEventForwarder"
    private const val PRIORITY = 9101

    @Volatile
    private var subscribed = false
    private var receiver: BroadcastReceiver? = null
    private var context: Context? = null
    private var attached = false

    private val noopDelegate = object : AccessibilityDelegate {
        override val eventTypes: Set<Int>? = null
        override fun onAccessibilityEvent(service: android.accessibilityservice.AccessibilityService, event: AccessibilityEvent) = false
    }

    private val delegate = object : AccessibilityDelegate {
        override val eventTypes: Set<Int>? = setOf(
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
        override fun onAccessibilityEvent(service: android.accessibilityservice.AccessibilityService, event: AccessibilityEvent): Boolean {
            if (!subscribed) {
                return false
            }
            try {
                forward(service, event)
            } catch (error: Throwable) {
                Log.w(TAG, "forward accessibility event failed", error)
            }
            return false
        }
    }

    fun attach(context: Context) {
        synchronized(this) {
            if (attached) {
                return
            }
            attached = true
            this.context = context.applicationContext
            val filter = IntentFilter().apply {
                addAction(ACTION_SUBSCRIBE)
                addAction(ACTION_UNSUBSCRIBE)
                addAction(ACTION_KEY_EVENTS)
                addAction(ACTION_LOCK_SCREEN)
            }
            val r = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ACTION_SUBSCRIBE -> {
                            subscribed = true
                            Log.i(TAG, "accessibility event forwarding enabled")
                        }
                        ACTION_UNSUBSCRIBE -> {
                            subscribed = false
                            Log.i(TAG, "accessibility event forwarding disabled")
                        }
                        ACTION_KEY_EVENTS -> setKeyEvents(intent?.getBooleanExtra("enabled", true) == true)
                        ACTION_LOCK_SCREEN -> lockScreen()
                    }
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(r, filter)
                }
                receiver = r
            } catch (error: Throwable) {
                Log.w(TAG, "register subscribe receiver failed", error)
            }
            AccessibilityService.addDelegate(PRIORITY, delegate)
        }
    }

    fun detach() {
        synchronized(this) {
            if (!attached) {
                return
            }
            attached = false
            subscribed = false
            try {
                receiver?.let { context?.unregisterReceiver(it) }
            } catch (error: Throwable) {
                Log.w(TAG, "unregister subscribe receiver failed", error)
            }
            receiver = null
            context = null
            AccessibilityService.addDelegate(PRIORITY, noopDelegate)
        }
    }

    /** task-171：通过主进程 AccessibilityService 的 serviceInfo flags 开关按键事件。 */
    private fun setKeyEvents(enabled: Boolean) {
        try {
            val service = com.stardust.view.accessibility.AccessibilityService.instance
            if (service == null) {
                Log.w(TAG, "enableKeyEvents ignored: accessibility service not running")
                return
            }
            val info = service.serviceInfo ?: return
            val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            info.flags = if (enabled) info.flags or flag else info.flags and flag.inv()
            service.serviceInfo = info
            Log.i(TAG, "accessibility key events enabled=" + enabled)
        } catch (error: Throwable) {
            Log.w(TAG, "set accessibility key events failed", error)
        }
    }

    /** task-171：GLOBAL_ACTION_LOCK_SCREEN（API 28+）。 */
    private fun lockScreen() {
        try {
            val service = com.stardust.view.accessibility.AccessibilityService.instance
            if (service == null) {
                Log.w(TAG, "lockScreen ignored: accessibility service not running")
                return
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                Log.i(TAG, "lockScreen global action result=" + ok)
            } else {
                Log.w(TAG, "lockScreen requires API 28")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "lockScreen failed", error)
        }
    }

    private fun forward(context: Context, event: AccessibilityEvent) {
        val payload = JSONObject()
        payload.put("kind", kindOf(event))
        payload.put("eventType", event.eventType)
        payload.put("packageName", event.packageName?.toString() ?: "")
        payload.put("className", event.className?.toString() ?: "")
        payload.put("windowId", event.windowId)
        payload.put("eventTime", event.eventTime)
        val texts = event.text
        payload.put("text", if (texts == null) "" else texts.joinToString(" "))
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            payload.put("contentChangeTypes", event.contentChangeTypes)
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            payload.put("windowChangeTypes", event.windowChanges)
        }
        context.sendBroadcast(Intent(ACTION_EVENT)
                .setPackage(context.packageName)
                .putExtra("json", payload.toString()))
    }

    private fun kindOf(event: AccessibilityEvent): String = when (event.eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "view_clicked"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "view_long_clicked"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows_changed"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state_changed"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "window_content_changed"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
            if ((event.className?.toString() ?: "").contains("Toast", true)) "toast" else "notification"
        else -> "accessibility_event"
    }
}
