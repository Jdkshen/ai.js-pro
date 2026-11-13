package com.jdkshen.aijspro.mcp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.autojs.core.image.capture.AccessibilityScreenCapturer
import com.stardust.view.accessibility.AccessibilityService
import com.stardust.view.accessibility.LayoutCaptureSelector
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap

internal object McpDeviceTools {
    fun layout(windowId: Int?, maxNodes: Int, maxDepth: Int, timeoutMillis: Int): JsonObject {
        val service = AccessibilityService.instance
            ?: throw IllegalStateException("节点读取需要开启 AI.js Pro 无障碍服务")
        service.clearNodeCache()
        val roots = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        val candidates = mutableListOf<LayoutCaptureSelector.Window<AccessibilityNodeInfo>>()
        val windows = JsonArray()
        val diagnostic = JsonArray()
        var active: AccessibilityNodeInfo? = null
        try {
            active = service.rootInActiveWindow?.also { roots.add(it) }
            runCatching { service.windows }.onSuccess { available ->
                available.forEach { window ->
                    try {
                        val root = window.root?.also { roots.add(it) }
                        candidates.add(LayoutCaptureSelector.Window(root, window.id,
                            window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                            window.isActive, window.isFocused, window.layer))
                        windows.add(JsonObject().apply {
                            addProperty("id", window.id); addProperty("type", window.type)
                            addProperty("active", window.isActive); addProperty("focused", window.isFocused)
                            addProperty("layer", window.layer); addProperty("packageName", root?.packageName?.toString())
                            addProperty("rawChildren", root?.childCount ?: 0)
                            // task-76：Pro 的 WindowInfo.getTitle() 是 API>=24；低版本**给 null，不伪造**。
                            // 放在最后，保持既有字段与顺序不变（客户端可能按字段名读）。
                            addProperty("title", if (Build.VERSION.SDK_INT >= 24) window.title?.toString() else null)
                        })
                    } catch (error: RuntimeException) { diagnostic.add(error.message.orEmpty()) }
                    finally { window.recycle() }
                }
            }.onFailure { diagnostic.add("窗口枚举失败：${it.message}") }
            val targetPackage = active?.packageName?.toString().takeIf {
                candidates.firstOrNull { it.id == active?.windowId }?.application != false
            }
            val selected = if (windowId != null) {
                candidates.firstOrNull { it.id == windowId }?.root
                    ?: active?.takeIf { it.windowId == windowId }
                    ?: throw IllegalArgumentException("找不到窗口 $windowId")
            } else {
                LayoutCaptureSelector.select(candidates, active, active?.windowId ?: -1) { root ->
                    root != null && (targetPackage == null || root.packageName?.toString() == targetPackage) &&
                        (root.childCount > 0 || !root.text.isNullOrBlank() || !root.contentDescription.isNullOrBlank() ||
                            root.isClickable || root.isLongClickable || root.isScrollable || root.isEditable)
                } ?: active
            }
            val result = if (selected == null) JsonObject().apply {
                addProperty("success", false); addProperty("readable", false)
                addProperty("status", "NO_ROOT"); addProperty("nodeCount", 0)
                addProperty("xml", "<hierarchy/>"); add("nodes", JsonArray())
            } else {
                // Root ownership stays here; the serializer owns child handles only.
                McpUiTree.capture(AndroidNode(selected, false), maxNodes, maxDepth, timeoutMillis).apply {
                    addProperty("windowId", selected.windowId)
                    addProperty("packageName", selected.packageName?.toString())
                }
            }
            result.add("windows", windows)
            result.add("diagnostics", JsonObject().apply {
                addProperty("sdk", Build.VERSION.SDK_INT)
                addProperty("service", service.javaClass.name)
                addProperty("flags", service.serviceInfo?.flags ?: 0)
                addProperty("capabilities", service.serviceInfo?.capabilities ?: 0)
                if (Build.VERSION.SDK_INT >= 31) addProperty("isAccessibilityTool", service.serviceInfo?.isAccessibilityTool)
                addProperty("activePackage", active?.packageName?.toString())
                add("warnings", diagnostic)
                if (result.get("readable")?.asBoolean != true) {
                    addProperty("hint", "系统没有返回可读节点；请确认目标页面和无障碍权限，可使用 capture_screen 检查自绘或受保护页面")
                }
            })
            return result
        } finally { roots.forEach { it.recycle() } }
    }

    /**
     * 截图。`mode` 提供三种预设：fast（快、小、够看）/ balanced（与旧默认一致）/ quality（原图无损）。
     * 调用方显式传的 format/quality/maxWidth/timeoutMillis 覆盖预设；返回里给出真实耗时与生效参数，
     * 便于调用方自己权衡「质量 vs 速度」，而不是猜。
     */
    fun screenshot(mode: String, format: String?, quality: Int?, dataUri: Boolean,
                   maxWidth: Int?, timeoutMillis: Int?, region: com.google.gson.JsonElement? = null,
                   savePath: String? = null, saveFormat: String? = null, organize: Boolean = true): JsonObject {
        if (!McpCapturePreset.isKnownMode(mode)) throw McpToolArgException("mode 必须是 fastest、fast、balanced 或 quality")
        val requestedRegion = McpCapturePreset.parseRegion(region)
        val requestedSaveTarget = McpCapturePreset.resolveSaveTarget(savePath)
        val (saveTarget, organized) = McpCapturePreset.organizeSaveTarget(requestedSaveTarget, organize)
        val resolvedSaveFormat = McpCapturePreset.resolveSaveFormat(saveFormat, saveTarget != null)
        // saveFormat 是**落盘格式**：只有真要写文件时才参与 inline 编码格式的决策。
        // ★ 之前它在「没给 savePath」时也返回 png（resolveSaveFormat 的缺省分支），
        //   于是 format ?: autoFormat 永远拿到 "png"，把 mode 预设想给的 jpg 覆盖掉
        //   ⇒ 表现为「quality/maxWidth 生效、format 不生效」（2026-11-13 真机 7 条验收暴露）。
        //   raw 仍独立（不看 format）；auto 仍按扩展名；没落盘目标时交给 preset。
        val autoFormat = when {
            saveTarget == null -> null
            resolvedSaveFormat == "raw" -> null
            resolvedSaveFormat == "auto" ->
                if (saveTarget.extension.lowercase() in setOf("jpg", "jpeg")) "jpg" else "png"
            else -> resolvedSaveFormat
        }
        val effective = McpCapturePreset.resolve(mode, format ?: autoFormat, quality, maxWidth, timeoutMillis)
        val resolvedFormat = effective.format
        if (resolvedFormat !in setOf("png", "jpg", "jpeg")) throw McpToolArgException("format 必须是 png、jpg 或 jpeg")
        if (effective.quality !in 1..100 || effective.maxWidth !in 0..4096 || effective.timeoutMillis !in 500..15000) {
            throw McpToolArgException("quality 必须在 1～100、maxWidth 在 0～4096、timeoutMillis 在 500～15000")
        }
        if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("直接无障碍截图需要 Android 11+；旧系统请通过 run_script 使用 requestScreenCapture/captureScreen")
        val startedAt = System.currentTimeMillis()
        // W4 task-26：截图/裁剪/缩放抽成 captureFrame —— capture_screen 与 dev_screen_diff 共用同一条管线。
        val captured = captureFrame(effective.timeoutMillis, requestedRegion, effective.maxWidth)
        val bitmap = captured.source
        val output = captured.output
        val captureMs = captured.captureMs
        try {
            val rawRequested = resolvedSaveFormat == "raw"
            val mime = if (rawRequested) "application/octet-stream" else if (resolvedFormat == "png") "image/png" else "image/jpeg"
            val encodeStartedAt = System.currentTimeMillis()
            val bytes = if (rawRequested) {
                // CV 流水线要的就是原始像素：RGBA8888 行优先、stride = width*4、无文件头，
                // 与脚本侧 image.exportPixels / readFileSync 的布局一致；省掉 PNG/JPEG 编码（约 550ms）。
                val buffer = ByteBuffer.allocate(output.width * output.height * 4)
                output.copyPixelsToBuffer(buffer)
                buffer.array()
            } else {
                ByteArrayOutputStream().use { out ->
                    check(output.compress(if (resolvedFormat == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, effective.quality, out)) { "截图编码失败" }
                    out.toByteArray()
                }
            }
            val encodeMs = System.currentTimeMillis() - encodeStartedAt
            var writeMs = 0L
            if (saveTarget != null) {
                val writeStartedAt = System.currentTimeMillis()
                saveTarget.parentFile?.mkdirs()
                FileOutputStream(saveTarget).use { it.write(bytes) }
                writeMs = System.currentTimeMillis() - writeStartedAt
            }
            if (bytes.size > 16 * 1024 * 1024) throw McpToolArgException("截图过大，请使用 jpg 或 maxWidth 缩小尺寸")
            // W4 task-26：把这一帧记进进程内帧缓存（只留一帧），供 dev_screen_diff 的「上一帧」基线使用。
            val previousFrameRemembered = rememberFrame(output, "capture_screen", if (resolvedFormat == "png") "png" else "jpg")
            val encoded = if (saveTarget == null || dataUri) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
            val metadata = JsonObject().apply {
                addProperty("success", true); addProperty("width", output.width); addProperty("height", output.height)
                addProperty("sourceWidth", bitmap.width); addProperty("sourceHeight", bitmap.height)
                addProperty("scale", output.width.toDouble() / (if (requestedRegion == null) bitmap.width else requestedRegion.width))
                if (requestedRegion != null) {
                    addProperty("cropped", true)
                    // 裁剪区域（已按屏幕边界夹取）与块内坐标换算用这两个字段
                    addProperty("region", "[${requestedRegion.left},${requestedRegion.top}][${requestedRegion.right},${requestedRegion.bottom}]")
                    addProperty("regionClamped", captured.regionClamped)
                    addProperty("regionNote", "输出图内坐标 = 屏幕坐标 - 区域左上角，再乘 scale；用 get_ui_layout 的节点 bounds 可直接减区域左上角")
                }
                addProperty("mimeType", mime); addProperty("format", if (resolvedFormat == "png") "png" else "jpg")
                addProperty("size", bytes.size); addProperty("method", "accessibility")
                addProperty("mode", effective.mode)
                addProperty("modeNote", effective.note)
                if (saveTarget != null) {
                    addProperty("saved", true)
                    addProperty("path", saveTarget.absolutePath)
                    if (organized != null) add("organized", organized)
                    addProperty("saveFormat", resolvedSaveFormat)
                    addProperty("writeMs", writeMs)
                    if (rawRequested) addProperty("rawLayout", "RGBA8888 行优先，stride=width*4，无文件头（${output.width}x${output.height}，共 ${bytes.size} 字节）")
                }
                addProperty("captureMs", captureMs); addProperty("encodeMs", encodeMs)
                addProperty("previousFrameRemembered", previousFrameRemembered)
                addProperty("totalMs", System.currentTimeMillis() - startedAt)
                add("effective", JsonObject().apply {
                    addProperty("format", if (resolvedFormat == "png") "png" else "jpg")
                    addProperty("quality", effective.quality); addProperty("maxWidth", effective.maxWidth)
                    addProperty("timeoutMillis", effective.timeoutMillis)
                })
                if (dataUri) addProperty("dataUri", "data:$mime;base64,$encoded")
            }
            // 只落盘（savePath 且没显式要 dataUri）时不回传图像：省掉 base64 与 JSON 传输，
            // 这正是逐帧 CV 循环想要的「给个路径就行」。
            val embedImage = saveTarget == null || dataUri
            return JsonObject().apply {
                if (organized != null) add("organized", organized)
                add("structuredContent", metadata)
                add("content", JsonArray().apply {
                    if (embedImage) {
                        add(JsonObject().apply { addProperty("type", "image"); addProperty("data", encoded); addProperty("mimeType", mime) })
                    }
                    add(JsonObject().apply { addProperty("type", "text"); addProperty("text", metadata.toString()) })
                })
            }
        } finally {
            captured.recycle.forEach { runCatching { it.recycle() } }
        }
    }

    /** capture_screen 与 dev_screen_diff 共用的截图结果（output 已裁剪+缩放；recycle 是待回收的原始/中间位图）。 */
    private class CapturedFrame(
        val source: Bitmap,
        val output: Bitmap,
        val captureMs: Long,
        val regionClamped: Boolean,
        val recycle: List<Bitmap>,
    )

    /**
     * W4 task-26：**唯一**的截图管线（capture_screen 与 dev_screen_diff 共用，不另起一套）。
     * 原始位图 →（region 越界自动夹取）裁剪 →（maxWidth>0 时）等比缩小；调用方负责回收 [CapturedFrame.recycle]。
     */
    private fun captureFrame(timeoutMillis: Int, requestedRegion: McpCapturePreset.Region?, maxWidth: Int): CapturedFrame {
        if (Build.VERSION.SDK_INT < 30) {
            throw IllegalStateException("直接无障碍截图需要 Android 11+；旧系统请通过 run_script 使用 requestScreenCapture/captureScreen")
        }
        val startedAt = System.currentTimeMillis()
        val bitmap = AccessibilityScreenCapturer().capture(timeoutMillis.toLong())
        val captureMs = System.currentTimeMillis() - startedAt
        var scaled: Bitmap? = null
        var cropped: Bitmap? = null
        val region = requestedRegion?.let {
            McpCapturePreset.Region(
                it.left.coerceIn(0, bitmap.width - 1),
                it.top.coerceIn(0, bitmap.height - 1),
                it.right.coerceIn(it.left + 1, bitmap.width),
                it.bottom.coerceIn(it.top + 1, bitmap.height),
            )
        }
        val croppedBitmap = if (region == null) bitmap else Bitmap.createBitmap(
            bitmap, region.left, region.top, region.width, region.height).also { cropped = it }
        val output = if (maxWidth > 0 && croppedBitmap.width > maxWidth) {
            Bitmap.createScaledBitmap(croppedBitmap, maxWidth,
                (croppedBitmap.height.toLong() * maxWidth / croppedBitmap.width).toInt().coerceAtLeast(1), true).also { scaled = it }
        } else croppedBitmap
        val recycle = ArrayList<Bitmap>()
        scaled?.takeIf { it !== bitmap && it !== cropped }?.let { recycle.add(it) }
        cropped?.takeIf { it !== bitmap }?.let { recycle.add(it) }
        if (bitmap !in recycle) recycle.add(bitmap)
        val regionClamped = requestedRegion != null && region != null && (
            region.left != requestedRegion.left || region.top != requestedRegion.top ||
                region.right != requestedRegion.right || region.bottom != requestedRegion.bottom)
        return CapturedFrame(bitmap, output, captureMs, regionClamped, recycle)
    }

    /** 位图 → ARGB 像素帧（拷贝一份，位图随后可回收）。 */
    private fun frameOf(bitmap: Bitmap): ToolkitScreenDiff.Frame {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ToolkitScreenDiff.Frame(bitmap.width, bitmap.height, pixels)
    }

    /** 记入进程内帧缓存（只留一帧）；超过像素上限返回 false（不占内存）。 */
    private fun rememberFrame(bitmap: Bitmap, source: String, format: String): Boolean {
        if (bitmap.width.toLong() * bitmap.height > ToolkitScreenDiff.MAX_REMEMBERED_PIXELS) return false
        return runCatching { ToolkitScreenFrameStore.remember(frameOf(bitmap), source, format) }.getOrDefault(false)
    }

    /**
     * W4 task-26：`dev_screen_diff` —— 逐帧**像素级**差异。
     *
     * 当前帧走 captureFrame（与 capture_screen 同一条管线，支持 region 与 maxDims 缩小）；
     * 基线优先用显式路径（脚本目录内的 PNG/JPEG），否则用进程内上一帧；两者都没有时**明确失败**（不拿黑图当基线）。
     * 比较是纯函数（ToolkitScreenDiff.compare），尺寸不一致直接拒绝（SIZE_MISMATCH），不裁剪/不缩放。
     */
    /**
     * task-43（①量泄漏）：手势回调表泄漏探针 —— report 只读；stress 会**真的派发** count 次 1px/1ms 手势。
     *
     * 只读部分用 best-effort 反射读框架隐藏字段（读不到给 supported=false + reason，绝不伪造数字、绝不抛异常）；
     * 派发次数来自跨进程计数器 GestureDispatchProbe（三处调用点 + 本工具的压测都会计入）。
     */
    fun gestureProbe(action: String, count: Int, timeoutSeconds: Int, withCallback: Boolean,
                     intervalMs: Int, reset: Boolean): JsonObject {
        val normalized = action.trim().lowercase().ifEmpty { "report" }
        if (normalized !in setOf("report", "stress")) {
            return ToolkitIo.json("ok" to false, "error" to ToolkitIo.json(
                "code" to "INVALID_ARGUMENT",
                "message" to "action 只能是 report 或 stress，收到：" + action,
                "argument" to "action"))
        }
        val service = AccessibilityService.instance
        val resetFiles = if (reset) com.stardust.automator.GestureDispatchProbe.reset() else 0
        if (normalized == "report") {
            val payload = ToolkitGestureProbe.report(service)
            payload.addProperty("ok", true)
            payload.addProperty("action", "report")
            payload.addProperty("serviceAvailable", service != null)
            payload.addProperty("serviceInstanceClass", service?.javaClass?.name)
            payload.addProperty("resetFiles", resetFiles)
            payload.addProperty("counterDir", com.stardust.automator.GestureDispatchProbe.directoryPath())
            payload.add("nextActions", JsonArray().apply {
                add("要看增长：先 report 记下 table.size，再 stress{count:2000}，最后再 report 对比（表条目数是否随之线性增长）。")
                add("stress 会真的派发手势：不要在业务脚本关键路径上跑，量级建议 ≤2000 次。")
            })
            return payload
        }
        if (service == null) {
            return ToolkitIo.json("ok" to false, "error" to ToolkitIo.json(
                "code" to "SERVICE_UNAVAILABLE",
                "message" to "stress 需要无障碍服务实例（当前进程没有 AccessibilityService.instance）：先开启 AI.js Pro 无障碍服务",
                "argument" to "action"))
        }
        // P3-1 A 类归一（行为逐字不变：仍是 count.coerceIn(1, 10000)）：非空入参 ⇒ default 参数不生效，仅借用统一入口。
        // P3-1 余项：保留完整 ClampResult，把「发生过夹紧」带进信封顶层（消费方看不到日志，必须进结构化返回）。
        val countClamp = McpArgClamp.clampInt(count, count, 1, 10000)
        val timeoutClamp = McpArgClamp.clampInt(timeoutSeconds, timeoutSeconds, 1, 600)
        val intervalClamp = McpArgClamp.clampInt(intervalMs, intervalMs, 0, 2000)
        val requested = countClamp.value
        val timeout = timeoutClamp.value
        val outcome = ToolkitGestureProbe.stress(
            count = requested,
            timeoutSeconds = timeout,
            withCallback = withCallback,
            intervalMs = intervalClamp.value,
            probe = { ToolkitGestureProbe.snapshot(service) },
            dispatch = { withCb -> dispatchTinyGesture(service, withCb) },
        )
        val counters = com.stardust.automator.GestureDispatchProbe.snapshot()
        val notes = JsonArray()
        notes.add("stress 是诊断动作：真的派发了手势（1px、1ms、不改变界面语义），不是只读。")
        notes.add("这里是「派发次数 vs 框架回调表条目数」并排显示，不声称因果；关闭无障碍服务、系统取消手势等都会影响读数。")
        notes.add("stress 自己也会被计数器记入 bySite=dev_gesture_probe，便于把压测与业务派发分开。")
        if (!outcome.before.supported || !outcome.after.supported) {
            notes.add("表读数不可用（supported=false）：本次只给出派发次数，表条目请用其它手段（或换 Android 版本）核对。")
        }
        return ToolkitIo.json(
            "ok" to true,
            "action" to "stress",
            "requested" to outcome.requested,
            "dispatched" to outcome.dispatched,
            "failed" to outcome.failed,
            "withCallback" to outcome.withCallback,
            "intervalMs" to intervalClamp.value,
            "clamped" to (countClamp.clamped || timeoutClamp.clamped || intervalClamp.clamped),
            "clamps" to JsonArray().apply {
                listOf(
                    "count" to countClamp,
                    "timeoutSeconds" to timeoutClamp,
                    "intervalMs" to intervalClamp,
                ).filter { it.second.clamped }.forEach { (name, r) ->
                    add(ToolkitIo.json("argument" to name, "requested" to r.requested, "effective" to r.effective))
                }
            },
            "timeoutSeconds" to timeout,
            "stoppedBy" to outcome.stoppedBy,
            "elapsedMs" to outcome.elapsedMs,
            "before" to outcome.before.toJson(),
            "after" to outcome.after.toJson(),
            "tableDelta" to (if (outcome.before.size != null && outcome.after.size != null) outcome.after.size - outcome.before.size else null),
            "counters" to ToolkitIo.json(
                "initialized" to counters.initialized,
                "dispatched" to counters.dispatched,
                "withCallback" to counters.withCallback,
                "withoutCallback" to counters.withoutCallback,
                "files" to counters.files,
            ),
            "notes" to notes,
        )
    }

    /** 1px、1ms 的极小手势；withCallback=true 走「会进框架回调表」的那条路径（泄漏相关）。 */
    private fun dispatchTinyGesture(service: AccessibilityService, withCallback: Boolean): Boolean {
        val path = android.graphics.Path().apply { moveTo(1f, 1f) }
        val description = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 1L))
            .build()
        com.stardust.automator.GestureDispatchProbe.record("dev_gesture_probe", withCallback)
        return if (withCallback) {
            service.dispatchGesture(description,
                object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {}, null)
        } else {
            service.dispatchGesture(description, null, null)
        }
    }

    fun screenDiff(
        baseline: String?,
        region: com.google.gson.JsonElement?,
        mode: String,
        tolerance: Int?,
        saveDiffTo: String?,
        maxDims: Int?,
        captureMode: String?,
        organize: Boolean = true,
    ): JsonObject {
        val normalizedMode = mode.trim().lowercase().ifEmpty { ToolkitScreenDiff.MODE_COUNT }
        if (normalizedMode !in ToolkitScreenDiff.MODES) {
            return ToolkitScreenDiff.invalidArgumentFailure("mode 只能是 count、bbox 或 mask，收到：$mode", "mode")
        }
        if (maxDims != null && maxDims !in 0..4096) return ToolkitScreenDiff.invalidArgumentFailure("maxDims 必须在 0..4096（0=不缩放）", "maxDims")
        if (tolerance != null && tolerance !in 0..255) return ToolkitScreenDiff.invalidArgumentFailure("tolerance 必须在 0..255", "tolerance")
        val normalizedCaptureMode = captureMode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: McpCapturePreset.MODE_BALANCED
        if (!McpCapturePreset.isKnownMode(normalizedCaptureMode)) {
            return ToolkitScreenDiff.invalidArgumentFailure("captureMode 必须是 fastest、fast、balanced 或 quality（与 capture_screen 的 mode 同一套预设），收到：$captureMode", "captureMode")
        }
        val preset = McpCapturePreset.resolve(normalizedCaptureMode)
        val requestedRegion = runCatching { McpCapturePreset.parseRegion(region) }
            .getOrElse { return ToolkitScreenDiff.invalidArgumentFailure(it.message ?: "region 无效", "region") }
        val previous = ToolkitScreenFrameStore.lastOrNull()
        // ①a：显式 maxDims > 沿用上一帧宽度（避免 capture_screen{fast} 之后默认抓全尺寸直接 SIZE_MISMATCH）> 预设默认。
        val (effectiveMaxWidth, sizeSource) = ToolkitScreenDiff.resolveMaxWidth(maxDims, previous?.frame?.width, preset.maxWidth)
        val captured = runCatching { captureFrame(preset.timeoutMillis, requestedRegion, effectiveMaxWidth) }
            .getOrElse { error ->
                return ToolkitScreenDiff.failure(
                    "CAPTURE_FAILED",
                    "截图失败：${error.message ?: error.javaClass.simpleName}",
                    null,
                    listOf("确认已开启 AI.js Pro 无障碍服务（Android 11+ 才支持无障碍截图）", "或先用 capture_screen 验证截图可用"),
                )
            }
        val capturedFormat = if (preset.format == "png") "png" else "jpg"
        try {
            val current = frameOf(captured.output)
            // ② task-28：**先记当前帧再解析基线** —— 第一次调用虽然 NO_BASELINE，但帧已留下，
            // 所以「连调两次即可 diff」成立（K40 实测修前 call1/call2 都失败）。
            val remembered = runCatching { ToolkitScreenFrameStore.remember(current, "dev_screen_diff", capturedFormat) }.getOrDefault(false)
            val rawBaseline = baseline?.trim().takeIf { !it.isNullOrEmpty() }
            val explicitFrame = if (rawBaseline == null) null else {
                val file = runCatching { McpPathRules.resolve(McpCapturePreset.operationRoot(), rawBaseline, "baseline 必须在脚本目录内") }
                    .getOrElse { return ToolkitScreenDiff.invalidArgumentFailure(it.message ?: "baseline 路径无效", "baseline") }
                if (!file.isFile) return ToolkitScreenDiff.baselineUnreadableFailure(file.path, "文件不存在")
                val decoded = BitmapFactory.decodeFile(file.path)
                    ?: return ToolkitScreenDiff.baselineUnreadableFailure(file.path, "不是可解码的 PNG/JPEG（saveFormat=raw 的原始像素文件不支持）")
                try { frameOf(decoded) } finally { decoded.recycle() }
            }
            // bootstrapBaseline 的契约：先读帧缓存里的当前帧（上面已 remember），再决定基线。
            val resolution = ToolkitScreenDiff.bootstrapBaseline(explicitFrame, previous?.frame) { remembered }
            val baselineFrame = when (resolution) {
                is ToolkitScreenDiff.Baseline.ExplicitPath -> resolution.frame
                is ToolkitScreenDiff.Baseline.PreviousFrame -> resolution.frame
                ToolkitScreenDiff.Baseline.Missing -> return ToolkitScreenDiff.noBaselineFailure(remembered)
            }
            val explicitPath = resolution is ToolkitScreenDiff.Baseline.ExplicitPath
            val baselineIsJpeg = explicitPath && rawBaseline.orEmpty().lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
            // tolerance 默认：任一侧是 JPEG（压缩噪声）→ 8；两侧都无损 → 0。
            val jpegSources = ArrayList<String>()
            if (baselineIsJpeg) jpegSources.add("baseline")
            if (capturedFormat == "jpg") jpegSources.add("capture:" + normalizedCaptureMode)
            val effectiveTolerance = tolerance ?: if (jpegSources.isNotEmpty()) ToolkitScreenDiff.DEFAULT_JPEG_TOLERANCE else 0
            val toleranceSource = when {
                tolerance != null -> "explicit"
                jpegSources.isNotEmpty() -> "jpeg_default(${ToolkitScreenDiff.DEFAULT_JPEG_TOLERANCE}):" + jpegSources.joinToString("+")
                else -> "lossless_default(0)"
            }
            val result = try {
                ToolkitScreenDiff.compare(baselineFrame, current, effectiveTolerance)
            } catch (mismatch: ToolkitScreenDiff.SizeMismatchException) {
                return ToolkitScreenDiff.sizeMismatchFailure(baselineFrame, current)
            }
            val metrics = ToolkitScreenDiff.metricsJson(
                result, current, baselineFrame.sha256,
                if (explicitPath) "explicit_path" else "previous_frame",
                effectiveTolerance, toleranceSource, normalizedMode,
            ).apply {
                addProperty("captureMs", captured.captureMs)
                addProperty("regionClamped", captured.regionClamped)
                // task-28（①a）：把「这一次是按什么预设/什么尺寸抓的」写出来，调用方据此对齐两次调用。
                addProperty("captureMode", normalizedCaptureMode)
                addProperty("captureFormat", capturedFormat)
                addProperty("sizeSource", sizeSource)
                addProperty("captureMaxWidth", effectiveMaxWidth)
                addProperty("sourceWidth", captured.source.width)
                addProperty("sourceHeight", captured.source.height)
                addProperty("scale", captured.output.width.toDouble() / captured.source.width)
                if (requestedRegion != null) {
                    addProperty("region", "[${requestedRegion.left},${requestedRegion.top}][${requestedRegion.right},${requestedRegion.bottom}]")
                    addProperty("regionNote", "两次调用必须用同一个 region/maxDims，否则尺寸不一致会被拒绝（SIZE_MISMATCH）")
                }
                addProperty("baselineAgeMs", if (explicitPath) -1L else previous?.let { System.currentTimeMillis() - it.capturedAt } ?: -1L)
                if (!explicitPath) {
                    addProperty("previousFrameSource", previous?.source.orEmpty())
                    addProperty("previousFrameAt", previous?.capturedAt ?: 0L)
                }
                addProperty("previousFrameRemembered", remembered)
                if (!remembered) {
                    addProperty("previousFrameNote", "这一帧超过 ${ToolkitScreenDiff.MAX_REMEMBERED_PIXELS} 像素，未记入进程内帧缓存；下次请显式传 baseline 或加 maxDims")
                }
            }
            var maskBytes: ByteArray? = null
            var organized: JsonObject? = null
            var writtenPath: String? = null
            if (normalizedMode == ToolkitScreenDiff.MODE_MASK) {
                val mask = ToolkitScreenDiff.mask(baselineFrame, current, effectiveTolerance)
                metrics.addProperty("maskChangedPixels", mask.count { it == ToolkitScreenDiff.MASK_CHANGED })
                val maskBitmap = Bitmap.createBitmap(mask, current.width, current.height, Bitmap.Config.ARGB_8888)
                maskBytes = try {
                    ByteArrayOutputStream().use { out ->
                        check(maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "掩码 PNG 编码失败" }
                        out.toByteArray()
                    }
                } finally { maskBitmap.recycle() }
                metrics.addProperty("maskBytes", maskBytes.size)
                val requestedTarget = McpCapturePreset.resolveSaveTarget(saveDiffTo)
                if (requestedTarget != null && requestedTarget.extension.lowercase() !in setOf("png", "")) {
                    return ToolkitScreenDiff.invalidArgumentFailure("saveDiffTo 必须是 .png 文件或目录（掩码固定写 PNG），收到：$saveDiffTo", "saveDiffTo")
                }
                val target = McpCapturePreset.organizeSaveTarget(requestedTarget, organize)
                if (target.first != null) {
                    val file = target.first!!
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { it.write(maskBytes) }
                    organized = target.second
                    writtenPath = file.absolutePath
                    metrics.addProperty("written", true)
                    metrics.addProperty("path", file.absolutePath)
                    metrics.addProperty("bytes", maskBytes.size)
                    if (organized != null) metrics.add("organized", organized)
                } else {
                    metrics.addProperty("written", false)
                    metrics.addProperty("maskInline", true)
                    val notes = metrics.getAsJsonArray("notes") ?: JsonArray().also { metrics.add("notes", it) }
                    notes.add("掩码内联返回（base64 PNG）：像素不变=黑、变化=白；传 saveDiffTo 可改为落盘。")
                }
            } else if (!saveDiffTo.isNullOrBlank()) {
                metrics.addProperty("saveDiffToIgnored", "mode=$normalizedMode 不生成掩码；要落盘请传 mode=mask")
            }
            return JsonObject().apply {
                if (organized != null) add("organized", organized)
                add("structuredContent", metrics)
                add("content", JsonArray().apply {
                    if (maskBytes != null && writtenPath == null) {
                        add(JsonObject().apply {
                            addProperty("type", "image")
                            addProperty("data", Base64.encodeToString(maskBytes, Base64.NO_WRAP))
                            addProperty("mimeType", "image/png")
                        })
                    }
                    add(JsonObject().apply { addProperty("type", "text"); addProperty("text", metrics.toString()) })
                })
            }
        } finally {
            captured.recycle.forEach { runCatching { it.recycle() } }
        }
    }

    private class AndroidNode(private val node: AccessibilityNodeInfo, private val owned: Boolean = true) : McpUiTree.Node {
        override val childCount: Int get() = node.childCount
        override fun child(index: Int): McpUiTree.Node? = node.getChild(index)?.let { AndroidNode(it) }
        override fun close() { if (owned) node.recycle() }
        override fun attributes(): Map<String, String> {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            return linkedMapOf(
                "text" to node.text?.toString().orEmpty().take(4096),
                "resource-id" to node.viewIdResourceName.orEmpty(), "class" to node.className?.toString().orEmpty(),
                "package" to node.packageName?.toString().orEmpty(), "content-desc" to node.contentDescription?.toString().orEmpty().take(4096),
                "bounds" to "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
                "clickable" to node.isClickable.toString(), "long-clickable" to node.isLongClickable.toString(),
                "checkable" to node.isCheckable.toString(), "checked" to node.isChecked.toString(),
                "enabled" to node.isEnabled.toString(), "focusable" to node.isFocusable.toString(),
                "focused" to node.isFocused.toString(), "scrollable" to node.isScrollable.toString(),
                "selected" to node.isSelected.toString(), "password" to node.isPassword.toString(),
                "editable" to node.isEditable.toString(), "visible-to-user" to node.isVisibleToUser.toString()
            ).apply { if (Build.VERSION.SDK_INT >= 34) put("accessibility-data-sensitive", node.isAccessibilityDataSensitive.toString()) }
        }
    }
}
