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

    fun screenshot(format: String, quality: Int, dataUri: Boolean, maxWidth: Int, timeoutMillis: Int): JsonObject {
        require(format in setOf("png", "jpg", "jpeg")) { "format 必须是 png、jpg 或 jpeg" }
        require(quality in 1..100 && maxWidth in 0..4096 && timeoutMillis in 500..15000)
        if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("直接无障碍截图需要 Android 11+；旧系统请通过 run_script 使用 requestScreenCapture/captureScreen")
        val bitmap = AccessibilityScreenCapturer().capture(timeoutMillis.toLong())
        var scaled: Bitmap? = null
        try {
            val output = if (maxWidth > 0 && bitmap.width > maxWidth) {
                Bitmap.createScaledBitmap(bitmap, maxWidth,
                    (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(1), true).also { scaled = it }
            } else bitmap
            val mime = if (format == "png") "image/png" else "image/jpeg"
            val bytes = ByteArrayOutputStream().use { out ->
                check(output.compress(if (format == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, out)) { "截图编码失败" }
                out.toByteArray()
            }
            check(bytes.size <= 16 * 1024 * 1024) { "截图过大，请使用 jpg 或 maxWidth 缩小尺寸" }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val metadata = JsonObject().apply {
                addProperty("success", true); addProperty("width", output.width); addProperty("height", output.height)
                addProperty("sourceWidth", bitmap.width); addProperty("sourceHeight", bitmap.height)
                addProperty("scale", output.width.toDouble() / bitmap.width)
                addProperty("mimeType", mime); addProperty("format", if (format == "png") "png" else "jpg")
                addProperty("size", bytes.size); addProperty("method", "accessibility")
                if (dataUri) addProperty("dataUri", "data:$mime;base64,$encoded")
            }
            return JsonObject().apply {
                add("structuredContent", metadata)
                add("content", JsonArray().apply {
                    add(JsonObject().apply { addProperty("type", "image"); addProperty("data", encoded); addProperty("mimeType", mime) })
                    add(JsonObject().apply { addProperty("type", "text"); addProperty("text", metadata.toString()) })
                })
            }
        } finally { scaled?.takeIf { it !== bitmap }?.recycle(); bitmap.recycle() }
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
