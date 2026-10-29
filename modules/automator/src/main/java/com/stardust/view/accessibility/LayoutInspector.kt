package com.stardust.view.accessibility

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.IdentityHashMap
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/** Captures bounded offline snapshots without querying providers on the UI thread. */
class LayoutInspector(private val mContext: Context) {
    @Volatile var capture: NodeInfo? = null
        private set
    data class CapturedWindow(val id: Int, val type: Int, val active: Boolean,
                              val focused: Boolean, val layer: Int, val root: NodeInfo?)
    @Volatile var capturedWindows: List<CapturedWindow> = emptyList()
        private set
    @Volatile var isDumping = false
        private set
    @Volatile var captureLog = "尚未进行节点分析。请打开目标页面后点击悬浮窗的布局分析。"
        private set

    private val gate = CaptureRequestGate()
    // The gate admits at most one task. A stuck Binder call does not spawn replacements or queue requests.
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { r -> Thread(r, "layout-capture").apply { isDaemon = true } })
    private val deadlines = ScheduledThreadPoolExecutor(1,
        { r -> Thread(r, "layout-capture-deadline").apply { isDaemon = true } }).apply { removeOnCancelPolicy = true }
    private val mCaptureAvailableListeners = CopyOnWriteArrayList<CaptureAvailableListener>()
    @Volatile private var active: Job? = null

    interface CaptureAvailableListener { fun onCaptureAvailable(capture: NodeInfo?) }
    interface WindowsAvailableListener { fun onWindowsAvailable(windows: List<CapturedWindow>) }
    interface CaptureRequest { val id: Long; fun cancel() }
    interface CaptureTransport {
        fun capture(windows: Boolean, budget: NodeCaptureBudget): RemoteCapture
    }
    data class RemoteCapture(val root: NodeInfo?, val windows: List<CapturedWindow>)

    private data class CaptureResult(val root: NodeInfo?, val windows: List<CapturedWindow>)
    private inner class Job(val ticket: CaptureRequestGate.Ticket,
                            val listener: WindowsAvailableListener?,
                            val listeners: List<CaptureAvailableListener>,
                            val once: CaptureAvailableListener?) : CaptureRequest {
        override val id: Long get() = ticket.id
        val budget = NodeCaptureBudget(CAPTURE_TIMEOUT_MS, MAX_CAPTURE_NODES, MAX_CAPTURE_DEPTH)
        val started = SystemClock.uptimeMillis()
        val details = StringBuilder("AI.js Pro 节点分析诊断\nrequest=$id kind=${if (listener == null) "CURRENT" else "WINDOWS"}\n")
        @Volatile var deadline: ScheduledFuture<*>? = null
        override fun cancel() {
            budget.cancel()
            finish(this, null, "CANCELLED")
        }
    }
    private object RejectedRequest : CaptureRequest {
        override val id = 0L
        override fun cancel() {}
    }

    fun captureVisibleWindows(listener: WindowsAvailableListener) {
        captureVisibleWindowsRequest(listener)
    }

    /** The returned handle only cancels its own request, never a newer capture. */
    fun captureVisibleWindowsRequest(listener: WindowsAvailableListener): CaptureRequest {
        val request = start(listener)
        if (request == null) {
            listener.onWindowsAvailable(emptyList())
            return RejectedRequest
        }
        return request
    }

    fun captureCurrentWindow(): Boolean = captureCurrentWindowRequest() != null

    fun captureCurrentWindowRequest(): CaptureRequest? = start(null)

    fun captureCurrentWindowRequest(listener: CaptureAvailableListener): CaptureRequest? = start(null, listener)

    fun cancelCurrentCapture() {
        val request = active
        if (request != null && request.listener == null) request.cancel()
    }

    @Synchronized private fun start(listener: WindowsAvailableListener?, once: CaptureAvailableListener? = null): Job? {
        val ticket = gate.begin()
        if (ticket == null) {
            captureLog = "status=BUSY\n上一轮系统节点调用尚未退出。请稍后重试；取消等待不会强杀 Android Binder。"
            return null
        }
        val listeners = mCaptureAvailableListeners.toMutableList()
        if (once != null && !listeners.contains(once)) listeners.add(once)
        val request = Job(ticket, listener, listeners, once)
        active = request
        if (listener == null) { isDumping = true; capture = null; capturedWindows = emptyList() }
        trace(request.details, "status=CAPTURING")
        captureLog = request.details.toString()
        request.deadline = deadlines.schedule({
            request.budget.cancel()
            finish(request, null, "TIMEOUT")
        }, CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        try {
            worker.execute { execute(request) }
        } catch (error: RuntimeException) {
            finish(request, null, "EXECUTOR_REJECTED")
            synchronized(this) {
                gate.workerExited(ticket)
                if (active === request) active = null
            }
        }
        return request
    }

    private fun execute(request: Job) {
        try {
            val transport = remoteTransport
            if (transport != null) {
                val result = transport.capture(request.listener != null, request.budget)
                request.budget.check()
                finish(request, CaptureResult(result.root, result.windows), "SUCCESS")
                return
            }
            trace(request.details, newCaptureLog().toString())
            request.budget.check()
            val service = AccessibilityService.instance
                ?: throw IllegalStateException("ACCESSIBILITY_SERVICE_NOT_CONNECTED")
            if (request.listener != null) {
                val result = ArrayList<CapturedWindow>()
                service.clearNodeCache()
                request.budget.check()
                for (window in service.windows.take(MAX_WINDOWS)) {
                    request.budget.check()
                    try {
                        val root = window.root ?: continue
                        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                            root.packageName?.toString() == mContext.packageName) continue
                        val snapshot = NodeInfo.capture(mContext, root, request.budget)
                        result.add(CapturedWindow(window.id, window.type, window.isActive,
                            window.isFocused, window.layer, snapshot))
                    } catch (error: RuntimeException) {
                        rethrowBudget(error, request.budget)
                        Log.d(LOG_TAG, "Window disappeared during picker refresh", error)
                    }
                }
                request.budget.check()
                finish(request, CaptureResult(null, result.sortedByDescending { it.layer }), "SUCCESS")
            } else {
                val info = service.serviceInfo
                trace(request.details, "service=${service.javaClass.name} id=${info?.id}")
                trace(request.details, "flags=${info?.flags} feedback=${info?.feedbackType} capabilities=${info?.capabilities}")
                if (Build.VERSION.SDK_INT >= 31) trace(request.details, "isAccessibilityTool=${info?.isAccessibilityTool}")
                var result = CaptureResult(null, emptyList())
                for (attempt in 0..2) {
                    request.budget.check()
                    trace(request.details, "attempt=${attempt + 1}")
                    service.clearNodeCache()
                    result = captureApplicationWindow(service, request.details, request.budget)
                    if (result.root != null) break
                    if (attempt < 2) SystemClock.sleep(120)
                }
                request.budget.check()
                finish(request, result, if (result.root == null) "NO_READABLE_ROOT" else "SUCCESS")
            }
        } catch (error: Exception) {
            trace(request.details, "exception=${error.javaClass.name}: ${error.message}")
            error.stackTrace.take(6).forEach { trace(request.details, "  at $it") }
            finish(request, null, error.message ?: error.javaClass.simpleName)
            Log.w(LOG_TAG, "Layout capture failed", error)
        } finally {
            synchronized(this) {
                gate.workerExited(request.ticket)
                if (active === request) active = null
            }
        }
    }

    private fun finish(request: Job, result: CaptureResult?, status: String) {
        val accepted = synchronized(this) {
            if (!gate.complete(request.ticket)) false else {
                request.deadline?.cancel(false)
                if (request.listener == null) {
                    capture = result?.root
                    capturedWindows = result?.windows ?: emptyList()
                    isDumping = false
                }
                trace(request.details, "status=$status elapsedMs=${SystemClock.uptimeMillis() - request.started} visited=${request.budget.visitedCount}")
                if (status == "TIMEOUT") trace(request.details, "hint=抓取超时，已退出等待；系统节点调用尚未退出时请稍后重试。")
                captureLog = synchronized(request.details) { request.details.toString() }
                true
            }
        }
        if (!accepted) return
        // Listener code runs outside the state lock, so cancellation never waits on a UI callback.
        if (request.listener != null) {
            try { request.listener.onWindowsAvailable(result?.windows ?: emptyList()) }
            catch (error: Exception) { Log.w(LOG_TAG, "Window capture listener failed", error) }
        } else {
            for (listener in request.listeners) {
                if (listener !== request.once && !mCaptureAvailableListeners.contains(listener)) continue
                try { listener.onCaptureAvailable(result?.root) }
                catch (error: Exception) { Log.w(LOG_TAG, "Capture listener failed", error) }
            }
        }
    }

    private fun rethrowBudget(error: RuntimeException, budget: NodeCaptureBudget) {
        if (error is NodeCaptureBudget.CaptureLimitException) throw error
        budget.check()
    }

    fun addCaptureAvailableListener(l: CaptureAvailableListener) {
        mCaptureAvailableListeners.add(l)
    }

    fun removeCaptureAvailableListener(l: CaptureAvailableListener): Boolean {
        return mCaptureAvailableListeners.remove(l)
    }

    private fun captureApplicationWindow(service: AccessibilityService, details: StringBuilder, budget: NodeCaptureBudget): CaptureResult {
        budget.check()
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
                for (window in service.windows.take(MAX_WINDOWS)) {
                    budget.check()
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
                    rethrowBudget(error, budget)
                        trace(details, "windowException=${error.javaClass.name}: ${error.message}")
                        Log.d(LOG_TAG, "Window unavailable during capture", error)
                    }
                }
            } catch (error: RuntimeException) {
                    rethrowBudget(error, budget)
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
                        NodeInfo.capture(mContext, root, budget).takeIf { snapshot ->
                            // A sized FrameLayout with no children or accessible content is only a shell.
                            val readable = snapshot.getChildren().isNotEmpty() || snapshot.text.isNotBlank() ||
                                !snapshot.desc.isNullOrBlank() || snapshot.clickable || snapshot.longClickable ||
                                snapshot.scrollable || snapshot.editable
                            trace(details, "snapshot window=${root.windowId} package=${snapshot.packageName} class=${snapshot.className} nodes=${countNodes(snapshot)} readable=$readable")
                            readable
                        }
                    }
                } catch (error: RuntimeException) {
                    rethrowBudget(error, budget)
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
        val windows = candidates.mapIndexed { index, candidate ->
            budget.check()
            val root = candidate.root
            val snapshot = if (root == null || (!candidate.application && root.packageName?.toString() == mContext.packageName)) null
                else snapshots[root] ?: try { NodeInfo.capture(mContext, root, budget) }
                catch (error: RuntimeException) { rethrowBudget(error, budget); null }
            windowMetadata[index].copy(root = snapshot)
        }.filter { it.root != null }.toList()
        budget.check()
        return CaptureResult(result, windows)
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
        synchronized(details) {
            details.append(message.take(2000)).append('\n')
            if (details.length > 24000) {
                details.delete(4000, details.length - 16000)
                details.insert(4000, "\n[... earlier lines omitted ...]\n")
            }
        }
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
        cancelCurrentCapture()
        synchronized(this) {
            capture = null
            capturedWindows = emptyList()
        }
    }

    companion object {

        @Volatile private var remoteTransport: CaptureTransport? = null
        @JvmStatic fun setCaptureTransport(transport: CaptureTransport?) { remoteTransport = transport }

        private val LOG_TAG = LayoutInspector::class.java.simpleName
        private const val CAPTURE_TIMEOUT_MS = 8000L
        private const val MAX_CAPTURE_NODES = 15000
        private const val MAX_CAPTURE_DEPTH = 256
        private const val MAX_WINDOWS = 32
    }
}
