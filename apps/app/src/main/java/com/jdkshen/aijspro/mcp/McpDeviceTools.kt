package com.jdkshen.aijspro.mcp

import android.graphics.Bitmap
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
        require(McpCapturePreset.isKnownMode(mode)) { "mode 必须是 fastest、fast、balanced 或 quality" }
        val requestedRegion = McpCapturePreset.parseRegion(region)
        val requestedSaveTarget = McpCapturePreset.resolveSaveTarget(savePath)
        val (saveTarget, organized) = McpCapturePreset.organizeSaveTarget(requestedSaveTarget, organize)
        val resolvedSaveFormat = McpCapturePreset.resolveSaveFormat(saveFormat, saveTarget != null)
        // saveFormat=auto 时按 savePath 扩展名决定编码；raw 表示写原始像素（不看 format）。
        val autoFormat = when {
            resolvedSaveFormat != "auto" && resolvedSaveFormat != "raw" -> resolvedSaveFormat
            resolvedSaveFormat == "auto" && saveTarget != null ->
                if (saveTarget.extension.lowercase() in setOf("jpg", "jpeg")) "jpg" else "png"
            else -> null
        }
        val effective = McpCapturePreset.resolve(mode, format ?: autoFormat, quality, maxWidth, timeoutMillis)
        val resolvedFormat = effective.format
        require(resolvedFormat in setOf("png", "jpg", "jpeg")) { "format 必须是 png、jpg 或 jpeg" }
        require(effective.quality in 1..100 && effective.maxWidth in 0..4096 && effective.timeoutMillis in 500..15000)
        if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("直接无障碍截图需要 Android 11+；旧系统请通过 run_script 使用 requestScreenCapture/captureScreen")
        val startedAt = System.currentTimeMillis()
        val bitmap = AccessibilityScreenCapturer().capture(effective.timeoutMillis.toLong())
        val captureMs = System.currentTimeMillis() - startedAt
        var scaled: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            // 区域优先：先按屏幕坐标裁剪（越界自动夹取），再决定是否缩小 —— 只截一小块时
            // 既能把编码成本降到极低，又能保留 1:1 像素（比整屏缩小更清楚）。
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
            val output = if (effective.maxWidth > 0 && croppedBitmap.width > effective.maxWidth) {
                Bitmap.createScaledBitmap(croppedBitmap, effective.maxWidth,
                    (croppedBitmap.height.toLong() * effective.maxWidth / croppedBitmap.width).toInt().coerceAtLeast(1), true).also { scaled = it }
            } else croppedBitmap
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
            check(bytes.size <= 16 * 1024 * 1024) { "截图过大，请使用 jpg 或 maxWidth 缩小尺寸" }
            val encoded = if (saveTarget == null || dataUri) Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
            val metadata = JsonObject().apply {
                addProperty("success", true); addProperty("width", output.width); addProperty("height", output.height)
                addProperty("sourceWidth", bitmap.width); addProperty("sourceHeight", bitmap.height)
                addProperty("scale", output.width.toDouble() / (if (requestedRegion == null) bitmap.width else requestedRegion.width))
                if (requestedRegion != null) {
                    addProperty("cropped", true)
                    // 裁剪区域（已按屏幕边界夹取）与块内坐标换算用这两个字段
                    addProperty("region", "[${requestedRegion.left},${requestedRegion.top}][${requestedRegion.right},${requestedRegion.bottom}]")
                    addProperty("regionClamped", requestedRegion.left != requestedRegion.left.coerceIn(0, bitmap.width - 1) ||
                        requestedRegion.top != requestedRegion.top.coerceIn(0, bitmap.height - 1) ||
                        requestedRegion.right != requestedRegion.right.coerceIn(requestedRegion.left + 1, bitmap.width) ||
                        requestedRegion.bottom != requestedRegion.bottom.coerceIn(requestedRegion.top + 1, bitmap.height))
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
            scaled?.takeIf { it !== bitmap && it !== cropped }?.recycle()
            cropped?.takeIf { it !== bitmap }?.recycle()
            bitmap.recycle()
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
