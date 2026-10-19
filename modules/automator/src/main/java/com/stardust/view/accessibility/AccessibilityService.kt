package com.stardust.view.accessibility

import android.os.Build
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.event.EventDispatcher

import java.util.HashSet
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Created by Stardust on 2017/5/2.
 */


open class AccessibilityService : android.accessibilityservice.AccessibilityService() {

    interface GestureListener {
        fun onGesture(gestureId: Int)
    }

    val onKeyObserver = OnKeyListener.Observer()
    val keyInterrupterObserver = KeyInterceptor.Observer()
    val gestureEventDispatcher = EventDispatcher<GestureListener>()
    private var fingerprintBackend: FingerprintGestureDispatcher.Backend? = null
    private var mEventExecutor: ExecutorService? = null
    private var mFastRootInActiveWindow: AccessibilityNodeInfo? = null
    private val eventExecutor: ExecutorService
        get() {
            return mEventExecutor ?: {
                val executor = Executors.newSingleThreadExecutor()
                mEventExecutor = executor
                executor
            }()
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        instance = this
        // Log.v(TAG, "onAccessibilityEvent: $event");
        if (!containsAllEventTypes && !eventTypes.contains(event.eventType))
            return
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val root = rootInActiveWindow
            if (root != null) {
                synchronized(this) {
                    mFastRootInActiveWindow = root
                }
            }
        }

        for ((_, delegate) in mDelegates) {
            val types = delegate.eventTypes
            if (types != null && !delegate.eventTypes!!.contains(event.eventType))
                continue
            //long start = System.currentTimeMillis();
            if (delegate.onAccessibilityEvent(this@AccessibilityService, event))
                break
            //Log.v(TAG, "millis: " + (System.currentTimeMillis() - start) + " delegate: " + entry.getValue().getClass().getName());
        }
    }


    override fun onInterrupt() {

    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // 诊断日志：HyperOS 上无障碍设置被系统重置时这条日志根本不出现（收不到键）。
            Log.i(TAG, "onKeyEvent: volume key received, keyCode=" + event.keyCode + ", action=" + event.action)
        }
        eventExecutor.execute {
            stickOnKeyObserver.onKeyEvent(event.keyCode, event)
            onKeyObserver.onKeyEvent(event.keyCode, event)
        }
        return keyInterrupterObserver.onInterceptKeyEvent(event)
    }

    override fun onGesture(gestureId: Int): Boolean {
        eventExecutor.execute {
            gestureEventDispatcher.dispatchEvent {
                onGesture(gestureId)
            }
        }
        return false
    }

    override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        return try {
            super.getRootInActiveWindow()
        } catch (e: Exception) {
            null
        }

    }

    override fun onDestroy() {
        Log.v(TAG, "onDestroy: $instance")
        AccessibilityEventForwarder.detach()
        disconnectFingerprintGestures()
        if (instance === this) instance = null
        mEventExecutor?.shutdownNow()
        super.onDestroy()
    }


    override fun onServiceConnected() {
        Log.v(TAG, "onServiceConnected: " + serviceInfo.toString())
        instance = this
        super.onServiceConnected()
        AccessibilityEventForwarder.attach(this)
        ensureKeyEventFiltering()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            fingerprintBackend = FingerprintGestureBackend(this, fingerprintGestureDispatcher).also {
                fingerprintGestureDispatcher.attach(it)
            }
        }
        LOCK.lock()
        ENABLED.signalAll()
        LOCK.unlock()
        // FIXME: 2017/2/12 有时在无障碍中开启服务后这里不会调用服务也不会运行，安卓的BUG???
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityEventForwarder.detach()
        disconnectFingerprintGestures()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    /**
     * 运行时补「按键事件过滤」flag（FLAG_REQUEST_FILTER_KEY_EVENTS）。
     *
     * 为什么需要：无障碍服务首次开启、或被系统重置过（HyperOS 上 adb 写入的
     * enabled_accessibility_services 会被清掉）时，serviceInfo.flags 里可能没有这个 flag，
     * onKeyEvent 就永远收不到音量键——全局音量上键停止脚本因此失效。这里显式补 flag 再
     * setServiceInfo，并把补之前/之后的 flags 打进日志，便于后续排查。
     */
    private fun ensureKeyEventFiltering() {
        try {
            val info = serviceInfo ?: run {
                Log.w(TAG, "ensureKeyEventFiltering: serviceInfo is null")
                return
            }
            val filterKeyEvents =
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            val before = info.flags
            if ((before and filterKeyEvents) == 0) {
                info.flags = before or filterKeyEvents
                setServiceInfo(info)
                Log.i(TAG, "ensureKeyEventFiltering: added FLAG_REQUEST_FILTER_KEY_EVENTS, flags=0x"
                        + Integer.toHexString(before) + " -> 0x" + Integer.toHexString(info.flags))
            } else {
                Log.i(TAG, "ensureKeyEventFiltering: FLAG_REQUEST_FILTER_KEY_EVENTS already present, flags=0x"
                        + Integer.toHexString(before))
            }
        } catch (error: Throwable) {
            Log.w(TAG, "ensureKeyEventFiltering failed", error)
        }
    }

    private fun disconnectFingerprintGestures() {
        fingerprintBackend?.let { fingerprintGestureDispatcher.detach(it) }
        fingerprintBackend = null
    }

    fun fastRootInActiveWindow(): AccessibilityNodeInfo? {
        synchronized(this) {
            mFastRootInActiveWindow?.let { return it }
        }
        // A cache refresh must work even when no new accessibility event arrives.
        val root = rootInActiveWindow
        return synchronized(this) {
            mFastRootInActiveWindow ?: root.also { mFastRootInActiveWindow = it }
        }
    }

    /**
     * PDF §2.1.4 `auto.clearCache()`（v9.0.14 新增）：清空无障碍缓存。
     * Clear both our fast root and the framework cache, as Pro does before inspection.
     */
    fun clearNodeCache() {
        synchronized(this) {
            mFastRootInActiveWindow = null
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                super.clearCache()
            } else {
                val clientClass = Class.forName("android.view.accessibility.AccessibilityInteractionClient")
                val client = clientClass.getMethod("getInstance").invoke(null)
                clientClass.getMethod("clearCache").invoke(client)
            }
        } catch (error: Exception) {
            // Older ROMs may block the hidden method; capture must still be attempted.
            Log.d(TAG, "Framework accessibility cache clear unavailable", error)
        }
    }

    companion object {

        private val TAG = "AccessibilityService"

        private val mDelegates = TreeMap<Int, AccessibilityDelegate>()
        private val LOCK = ReentrantLock()
        private val ENABLED = LOCK.newCondition()
        var instance: AccessibilityService? = null
            private set
        val stickOnKeyObserver = OnKeyListener.Observer()
        @JvmField
        val fingerprintGestureDispatcher = FingerprintGestureDispatcher()
        private var containsAllEventTypes = false
        private val eventTypes = HashSet<Int>()

        fun addDelegate(uniquePriority: Int, delegate: AccessibilityDelegate) {
            mDelegates[uniquePriority] = delegate
            val set = delegate.eventTypes
            if (set == null)
                containsAllEventTypes = true
            else
                eventTypes.addAll(set)
        }

        fun disable(): Boolean {
            if (instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                instance!!.disableSelf()
                return true
            }
            return false
        }

        fun waitForEnabled(timeOut: Long): Boolean {
            if (instance != null)
                return true
            LOCK.lock()
            try {
                if (instance != null)
                    return true
                if (timeOut == -1L) {
                    ENABLED.await()
                    return true
                }
                return ENABLED.await(timeOut, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                e.printStackTrace()
                return false
            } finally {
                LOCK.unlock()
            }
        }
    }


}
