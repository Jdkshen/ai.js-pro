package com.stardust.view.accessibility

import android.accessibilityservice.*
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent

import com.stardust.notification.Notification

import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Created by Stardust on 2017/11/3.
 */

class AccessibilityNotificationObserver(private val mContext: Context) : NotificationListener, AccessibilityDelegate {
    private val mNotificationListeners = CopyOnWriteArrayList<NotificationListener>()
    private val mToastListeners = CopyOnWriteArrayList<ToastListener>()

    override val eventTypes: Set<Int>?
        get() = EVENT_TYPES

    inner class Toast(val packageName: String, texts: List<CharSequence>) {
        val texts: MutableList<String>

        val text: String?
            get() {
                return if (texts.isEmpty()) {
                    null
                } else texts[0]
            }

        init {
            this.texts = ArrayList(texts.size)
            for (t in texts) {
                this.texts.add(t.toString())
            }
        }

        override fun toString(): String {
            return "Toast{" +
                    "texts=" + texts +
                    ", packageName='" + packageName + '\''.toString() +
                    '}'.toString()
        }
    }

    interface ToastListener {
        fun onToast(toast: Toast)
    }


    fun addNotificationListener(listener: NotificationListener) {
        mNotificationListeners.add(listener)
    }

    fun removeNotificationListener(listener: NotificationListener): Boolean {
        return mNotificationListeners.remove(listener)
    }


    fun addToastListener(listener: ToastListener) {
        mToastListeners.add(listener)
    }

    fun removeToastListener(listener: ToastListener): Boolean {
        return mToastListeners.remove(listener)
    }

    override fun onAccessibilityEvent(service: android.accessibilityservice.AccessibilityService, event: AccessibilityEvent): Boolean {
        if (event.parcelableData is Notification) {
            val notification = event.parcelableData as android.app.Notification
            Log.d(TAG, "onNotification: $notification; $event")
            onNotification(Notification.create(notification, event.packageName.toString()))
        } else {
            val list = event.text
            Log.d(TAG, "onNotification: $list; $event")
            // task-69：同包（自身）事件**有意跳过**——这条判据是**共享**的：
            // 引擎侧 Events 的「kind 通道避重」必须用同一个判据（否则观察者不投递、kind 通道又被跳过 → 事件静默丢失）。
            if (!willDeliver(event.packageName, mContext.packageName)) {
                return false
            }
            if (list != null) {
                onToast(event, Toast(event.packageName.toString(), list))
            }
        }

        return false
    }

    private fun onToast(event: AccessibilityEvent, toast: Toast) {
        for (listener in mToastListeners) {
            try {
                listener.onToast(toast)
            } catch (e: Exception) {
                Log.e(TAG, "Error onNotification: $toast Listener: $listener", e)
            }

        }
    }

    override fun onNotification(notification: Notification) {
        for (listener in mNotificationListeners) {
            try {
                listener.onNotification(notification)
            } catch (e: Exception) {
                Log.e(TAG, "Error onNotification: $notification Listener: $listener", e)
            }

        }
    }

    companion object {

        /**
         * task-69：本观察者**是否会把这条事件投递给 ToastListener**（当前规则：同包/自身事件有意跳过）。
         *
         * <p>抽成纯函数的理由：`Events` 的本地 delegate 用它决定「要不要让 kind 通道照发」——
         * 观察者不投递时必须由 kind 通道兜住（实测：脚本自己的 `toast()` 是同包事件）。
         */
        @JvmStatic
        fun willDeliver(eventPackageName: CharSequence?, ownPackageName: String?): Boolean {
            if (ownPackageName == null || ownPackageName.isEmpty()) {
                return true
            }
            return eventPackageName == null || eventPackageName.toString() != ownPackageName
        }

        private val EVENT_TYPES = setOf(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)

        private val TAG = "NotificationObserver"
        private val EMPTY = arrayOfNulls<String>(0)
    }
}
