package com.stardust.view.accessibility

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.IdentityHashMap
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/**
 * Created by Stardust on 2017/3/10.
 */

class LayoutInspector(private val mContext: Context) {
    @Volatile
    var capture: NodeInfo? = null
        private set
    data class CapturedWindow(val id: Int, val type: Int, val active: Boolean,
                              val focused: Boolean, val layer: Int, val root: NodeInfo?)
    @Volatile
    var capturedWindows: List<CapturedWindow> = emptyList()
        private set
    @Volatile
    var isDumping = false
        private set
    @Volatile
    var captureLog = "尚未进行节点分析。请打开目标页面后点击悬浮窗的布局分析。"
        private set
    private val mExecutor = Executors.newSingleThreadExecutor()
    private val mCaptureAvailableListeners = CopyOnWriteArrayList<CaptureAvailableListener>()

    interface CaptureAvailableListener {
        fun onCaptureAvailable(capture: NodeInfo?)
    }

    interface WindowsAvailableListener {
        fun onWindowsAvailable(windows: List<CapturedWindow>)
    }

    /** Refresh the picker independently of the current (possibly imported) layout. */
    fun captureVisibleWindows(listener: WindowsAvailableListener) {
        mExecutor.execute {
            val result = ArrayList<CapturedWindow>()
            val service = AccessibilityService.instance
            if (service != null) {
                try {
                    service.clearNodeCache()
                    for (window in service.windows) {
                        try {
                            val root = window.root ?: continue
                            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                                root.packageName?.toString() == mContext.packageName) continue
                            val snapshot = NodeInfo.capture(mContext, root)
                            result.add(CapturedWindow(window.id, window.type, window.isActive,
                                window.isFocused, window.layer, snapshot))
                        } catch (error: RuntimeException) {
                            Log.d(LOG_TAG, "Window disappeared during picker refresh", error)
                        }
                    }
                } catch (error: RuntimeException) {
                    Log.w(LOG_TAG, "Window picker refresh failed", error)
                }
            }
            listener.onWindowsAvailable(result.sortedByDescending { it.layer })
        }
    }

    @Synchronized
    fun captureCurrentWindow(): Boolean {
        if (isDumping) return false
        val details = newCaptureLog()
        trace(details, "status=CAPTURING")
        val service = AccessibilityService.instance
        if (service == null) {
            trace(details, "status=FAILED reason=ACCESSIBILITY_SERVICE_NOT_CONNECTED")
            capture = null
            capturedWindows = emptyList()
            return false
        }
        capture = null
        capturedWindows = emptyList()
        isDumping = true
        mExecutor.execute {
            val started = SystemClock.uptimeMillis()
            var result: NodeInfo? = null
            try {
                val info = service.serviceInfo
                trace(details, "service=${service.javaClass.name} id=${info?.id}")
                trace(details, "flags=${info?.flags} feedback=${info?.feedbackType} capabilities=${info?.capabilities}")
                if (Build.VERSION.SDK_INT >= 31) trace(details, "isAccessibilityTool=${info?.isAccessibilityTool}")
                // Never query a remote app on the floating menu's UI thread.
                for (attempt in 0..2) {
                    trace(details, "attempt=${attempt + 1}")
                    service.clearNodeCache()
                    result = captureApplicationWindow(service, details)
                    if (result != null) break
                    if (attempt < 2) SystemClock.sleep(120)
                }
            } catch (error: Exception) {
                trace(details, "exception=${error.javaClass.name}: ${error.message}")
                error.stackTrace.take(6).forEach { trace(details, "  at $it") }
                Log.w(LOG_TAG, "Layout capture failed", error)
            } finally {
                capture = result
            }
            trace(details, "status=${if (result == null) "FAILED" else "SUCCESS"} package=${result?.packageName} nodes=${result?.let { countNodes(it) } ?: 0} elapsedMs=${SystemClock.uptimeMillis() - started}")
            try {
                for (l in mCaptureAvailableListeners) {
                    try {
                        l.onCaptureAvailable(result)
                    } catch (error: Exception) {
                        Log.w(LOG_TAG, "Capture listener failed", error)
                    }
                }
            } finally {
                isDumping = false
            }
        }
        return true
    }

    fun addCaptureAvailableListener(l: CaptureAvailableListener) {
        mCaptureAvailableListeners.add(l)
    }

    fun removeCaptureAvailableListener(l: CaptureAvailableListener): Boolean {
        return mCaptureAvailableListeners.remove(l)
    }

    private fun captureApplicationWindow(service: AccessibilityService, details: StringBuilder): NodeInfo? {
        val activeRoot = service.rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString()
        trace(details, "activeRoot window=${activeRoot?.windowId} package=$activePackage")
        if (activeRoot != null) traceRoot(details, "activeRoot", activeRoot)
        if (activePackage != null && !details.contains("targetApp=$activePackage ")) {
            try {
                val pkg = mContext.packageManager.getPackageInfo(activePackage, 0)
                trace(details, "targetApp=$activePackage version=${pkg.versionName} code=${pkg.versionCode}")
            } catch (error: Exception) {
                trace(details, "targetApp=$activePackage version=UNKNOWN reason=${error.javaClass.simpleName}")
            }
        }
        val candidates = ArrayList<LayoutCaptureSelector.Window<AccessibilityNodeInfo>>()
        val windowMetadata = ArrayList<CapturedWindow>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for (window in service.windows) {
                    try {
                        val root = window.root
                        trace(details, "window id=${window.id} type=${window.type} active=${window.isActive} focused=${window.isFocused} layer=${window.layer} package=${root?.packageName} rawChildren=${root?.childCount}")
                        if (root != null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) traceRoot(details, "windowRoot", root)
                        candidates.add(LayoutCaptureSelector.Window(
                            root, window.id, window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                            window.isActive, window.isFocused, window.layer))
                        windowMetadata.add(CapturedWindow(window.id, window.type, window.isActive,
                            window.isFocused, window.layer, null))
                    } catch (error: RuntimeException) {
                        trace(details, "windowException=${error.javaClass.name}: ${error.message}")
                        Log.d(LOG_TAG, "Window unavailable during capture", error)
                    }
                }
            } catch (error: RuntimeException) {
                trace(details, "windowsException=${error.javaClass.name}: ${error.message}")
                Log.d(LOG_TAG, "Window enumeration unavailable", error)
            }
        }
        // A translucent launcher or system overlay can sit above the target app.
        // For a real target app, keep fallback roots within that package.
        val activeWindow = candidates.firstOrNull { it.id == activeRoot?.windowId }
        val targetPackage = activePackage?.takeIf {
            it != mContext.packageName && (activeWindow == null || activeWindow.application)
        }
        val snapshots = IdentityHashMap<AccessibilityNodeInfo, NodeInfo?>()
        val selected = LayoutCaptureSelector.select(candidates, activeRoot, activeRoot?.windowId ?: -1) { root ->
            if (!snapshots.containsKey(root)) {
                snapshots[root] = try {
                    if (targetPackage != null && root.packageName?.toString() != targetPackage) {
                        trace(details, "skip window=${root.windowId} package=${root.packageName} reason=OTHER_APPLICATION")
                        null
                    } else {
                        NodeInfo.capture(mContext, root).takeIf { snapshot ->
                            // A sized FrameLayout with no children or accessible content is only a shell.
                            val readable = snapshot.getChildren().isNotEmpty() || snapshot.text.isNotBlank() ||
                                !snapshot.desc.isNullOrBlank() || snapshot.clickable || snapshot.longClickable ||
                                snapshot.scrollable || snapshot.editable
                            trace(details, "snapshot window=${root.windowId} package=${snapshot.packageName} class=${snapshot.className} nodes=${countNodes(snapshot)} readable=$readable")
                            readable
                        }
                    }
                } catch (error: RuntimeException) {
                    trace(details, "rootException=${error.javaClass.name}: ${error.message}")
                    Log.d(LOG_TAG, "Root unavailable during capture", error)
                    null
                }
            }
            snapshots[root] != null
        }
        val result = snapshots[selected]
        // Keep all windows from this capture, so the picker switches snapshots without Binder
        // queries on the UI thread. Exclude our own inspector/menu overlays.
        capturedWindows = candidates.mapIndexed { index, candidate ->
            val root = candidate.root
            val snapshot = if (root == null || (!candidate.application && root.packageName?.toString() == mContext.packageName)) null
                else snapshots[root] ?: try { NodeInfo.capture(mContext, root) }
                catch (error: RuntimeException) { null }
            windowMetadata[index].copy(root = snapshot)
        }.filter { it.root != null }.toList()
        return result
    }

    private fun traceRoot(details: StringBuilder, source: String, root: AccessibilityNodeInfo) {
        try {
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            val sensitive = if (Build.VERSION.SDK_INT >= 34) root.isAccessibilityDataSensitive.toString() else "UNSUPPORTED"
            trace(details, "$source window=${root.windowId} class=${root.className} children=${root.childCount} visible=${root.isVisibleToUser} enabled=${root.isEnabled} bounds=${bounds.toShortString()} dataSensitive=$sensitive")
        } catch (error: RuntimeException) {
            trace(details, "rootMetadataException=${error.javaClass.simpleName}")
        }
    }

    private fun newCaptureLog(): StringBuilder {
        val details = StringBuilder("AI.js Pro 节点分析诊断\n")
        details.append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())).append('\n')
        details.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
        try {
            val pkg = mContext.packageManager.getPackageInfo(mContext.packageName, 0)
            details.append("app=").append(mContext.packageName).append(" version=").append(pkg.versionName)
                .append(" code=").append(pkg.versionCode).append('\n')
            details.append("enabledServices=").append(android.provider.Settings.Secure.getString(
                mContext.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)).append('\n')
        } catch (error: Exception) {
            details.append("environmentException=").append(error.javaClass.name).append('\n')
        }
        return details
    }

    private fun trace(details: StringBuilder, message: String) {
        Log.d(LOG_TAG, message)
        details.append(message.take(2000)).append('\n')
        if (details.length > 24000) {
            details.delete(4000, details.length - 16000)
            details.insert(4000, "\n[... earlier lines omitted ...]\n")
        }
        captureLog = details.toString()
    }

    private fun countNodes(root: NodeInfo): Int {
        val queue = ArrayDeque<NodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty()) {
            count++
            queue.addAll(queue.removeFirst().getChildren())
        }
        return count
    }

    fun clearCapture() {
        capture = null
        capturedWindows = emptyList()
    }

    companion object {

        private val LOG_TAG = LayoutInspector::class.java.simpleName
    }
}
