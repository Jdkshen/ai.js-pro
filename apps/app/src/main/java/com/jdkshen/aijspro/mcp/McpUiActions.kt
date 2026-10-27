package com.jdkshen.aijspro.mcp

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService as AccessibilityServiceApi
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.view.accessibility.AccessibilityService
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MCP 无障碍 UI 自动化纯函数（供 Lead 从 McpTools 接入）。
 *
 * 只使用主进程 AccessibilityService.instance / rootInActiveWindow，不需要 root，
 * 不依赖 Node / Rhino 脚本运行时。选择器解析与匹配在 McpUiSelector（纯 JVM 可单测）。
 *
 * 返回统一 JsonObject：成功 {success:true,...}，失败 {success:false,error:"明确原因"}。
 */
internal object McpUiActions {
    private const val MAX_TRAVERSE_NODES = 5000
    private const val MAX_FIND_RESULTS = 200
    private const val MAX_ANCESTOR_DEPTH = 6
    private const val GESTURE_CALLBACK_TIMEOUT_MS = 2000L

    fun find(selectorArg: JsonObject?, maxResults: Int = 20): JsonObject {
        val selector = try {
            McpUiSelector.parse(selectorFromArgs(selectorArg))
        } catch (error: Throwable) {
            return fail("选择器参数解析失败：" + (error.message ?: error.javaClass.simpleName))
        }
        if (selector.isEmpty) {
            return fail("find 需要至少一个选择器条件（text/resourceId/contentDesc/className/bounds 或 x,y）")
        }
        val service = AccessibilityService.instance
            ?: return fail("需要开启 AI.js Pro 无障碍服务")
        return try {
            val root = service.rootInActiveWindow
                ?: return fail("当前没有可读的窗口根节点（目标页面可能未打开或无无障碍节点）")
            val limit = maxResults.coerceIn(1, MAX_FIND_RESULTS)
            val matches = collectMatches(root, selector, limit)
            JsonObject().apply {
                addProperty("success", true)
                addProperty("count", matches.size)
                addProperty("selector", selector.describe())
                add("matches", JsonArray().apply {
                    matches.forEachIndexed { index, snapshot ->
                        add(snapshotJson(index, snapshot))
                    }
                })
            }
        } catch (error: Throwable) {
            fail("find 失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    fun click(selectorArg: JsonObject?): JsonObject {
        val selector = parseSelector(selectorArg) ?: return fail("选择器参数解析失败")
        if (selector.isEmpty) return fail("click 需要选择器条件或 x,y 坐标")
        val service = AccessibilityService.instance ?: return fail("需要开启 AI.js Pro 无障碍服务")
        if (selector.isCoordinateOnly) return tapAt(service, selector.x!!, selector.y!!, false)
        val located = try {
            locateNode(service, selector)
        } catch (error: Throwable) {
            return fail("查找节点失败：" + (error.message ?: error.javaClass.simpleName))
        } ?: return fail("没有找到匹配节点（" + selector.describe() + "）")
        return try {
            if (!located.node.isEnabled) {
                return fail("匹配节点不可用（enabled=false）：" + located.snapshot.bounds)
            }
            var clicked = try {
                located.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Throwable) {
                false
            }
            if (!clicked) clicked = tryClickableAncestor(located.node)
            if (!clicked) {
                fail("节点点击失败（节点 clickable=" + located.snapshot.clickable
                    + "，performAction(ACTION_CLICK) 返回 false；可能是系统限制/节点已失效）")
            } else {
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("clicked", true)
                    addProperty("method", "accessibility-node")
                    add("node", snapshotJson(0, located.snapshot))
                }
            }
        } finally {
            runCatching { located.node.recycle() }
        }
    }

    fun longClick(selectorArg: JsonObject?): JsonObject {
        val selector = parseSelector(selectorArg) ?: return fail("选择器参数解析失败")
        if (selector.isEmpty) return fail("longClick 需要选择器条件或 x,y 坐标")
        val service = AccessibilityService.instance ?: return fail("需要开启 AI.js Pro 无障碍服务")
        if (selector.isCoordinateOnly) return tapAt(service, selector.x!!, selector.y!!, true)
        val located = try {
            locateNode(service, selector)
        } catch (error: Throwable) {
            return fail("查找节点失败：" + (error.message ?: error.javaClass.simpleName))
        } ?: return fail("没有找到匹配节点（" + selector.describe() + "）")
        return try {
            if (!located.node.isEnabled) {
                return fail("匹配节点不可用（enabled=false）：" + located.snapshot.bounds)
            }
            var clicked = try {
                located.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } catch (_: Throwable) {
                false
            }
            if (!clicked) clicked = tryLongClickableAncestor(located.node)
            if (!clicked) {
                fail("节点长按失败（节点 longClickable=" + located.snapshot.longClickable
                    + "，performAction(ACTION_LONG_CLICK) 返回 false；可能是系统限制/节点已失效）")
            } else {
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("longClicked", true)
                    addProperty("method", "accessibility-node")
                    add("node", snapshotJson(0, located.snapshot))
                }
            }
        } finally {
            runCatching { located.node.recycle() }
        }
    }

    /**
     * 通用节点动作（与 Pro UiObject 同名）：copy/cut/paste/select/collapse/expand/dismiss/show/
     * scrollForward/Backward/Up/Down/Left/Right/contextClick/focus/clearFocus/
     * accessibilityFocus/clearAccessibilityFocus/setSelection/scrollTo/setProgress。
     */
    fun nodeAction(selectorArg: JsonObject?, actionArg: String, args: JsonObject? = null): JsonObject {
        val selector = parseSelector(selectorArg) ?: return fail("选择器参数解析失败")
        if (selector.isEmpty) return fail("nodeAction 需要选择器条件")
        val action = actionArg.trim()
        val service = AccessibilityService.instance ?: return fail("需要开启 AI.js Pro 无障碍服务")
        val located = try {
            locateNode(service, selector)
        } catch (error: Throwable) {
            return fail("查找节点失败：" + (error.message ?: error.javaClass.simpleName))
        } ?: return fail("没有找到匹配节点（" + selector.describe() + "）")
        return try {
            if (!located.node.isEnabled) return fail("匹配节点不可用（enabled=false）：" + located.snapshot.bounds)
            var actionId = 0
            var bundle: Bundle? = null
            when (action) {
                "copy" -> actionId = AccessibilityNodeInfo.ACTION_COPY
                "cut" -> actionId = AccessibilityNodeInfo.ACTION_CUT
                "paste" -> actionId = AccessibilityNodeInfo.ACTION_PASTE
                "select" -> actionId = AccessibilityNodeInfo.ACTION_SELECT
                "collapse" -> actionId = AccessibilityNodeInfo.ACTION_COLLAPSE
                "expand" -> actionId = AccessibilityNodeInfo.ACTION_EXPAND
                "dismiss" -> actionId = AccessibilityNodeInfo.ACTION_DISMISS
                "show" -> actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                "scrollForward" -> actionId = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "scrollBackward" -> actionId = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                "scrollUp" -> actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                "scrollDown" -> actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                "scrollLeft" -> actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                "scrollRight" -> actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                "contextClick" -> actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.id
                "focus" -> actionId = AccessibilityNodeInfo.ACTION_FOCUS
                "clearFocus" -> actionId = AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
                "accessibilityFocus" -> actionId = AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                "clearAccessibilityFocus" -> actionId = AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                "setSelection" -> {
                    actionId = AccessibilityNodeInfo.ACTION_SET_SELECTION
                    bundle = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, args?.intOrNull("start") ?: 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, args?.intOrNull("end") ?: 0)
                    }
                }
                "scrollTo" -> {
                    actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id
                    bundle = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, args?.intOrNull("row") ?: 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, args?.intOrNull("column") ?: 0)
                    }
                }
                "setProgress" -> {
                    actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
                    bundle = Bundle().apply {
                        putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                            (args?.get("value")?.let { runCatching { it.asFloat }.getOrNull() } ?: 0f))
                    }
                }
                else -> return fail("nodeAction 不支持 action=" + actionArg
                    + "；可选 copy/cut/paste/select/collapse/expand/dismiss/show/scroll*/contextClick/focus/accessibilityFocus/setSelection/scrollTo/setProgress")
            }
            val performed = try {
                if (bundle == null) located.node.performAction(actionId) else located.node.performAction(actionId, bundle)
            } catch (_: Throwable) {
                false
            }
            if (performed) {
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("action", action)
                    add("node", snapshotJson(0, located.snapshot))
                }
            } else {
                fail("节点动作失败（" + action + "，performAction 返回 false；节点可能不支持该动作或页面已变化）")
            }
        } finally {
            runCatching { located.node.recycle() }
        }
    }

    fun input(selectorArg: JsonObject?, text: String): JsonObject {
        val selector = parseSelector(selectorArg) ?: return fail("选择器参数解析失败")
        if (selector.isEmpty) return fail("input 需要可编辑节点的选择器")
        if (selector.isCoordinateOnly) return fail("input 不能只用坐标；需要 text/resourceId/... 定位可编辑节点")
        val service = AccessibilityService.instance ?: return fail("需要开启 AI.js Pro 无障碍服务")
        val located = try {
            locateNode(service, selector)
        } catch (error: Throwable) {
            return fail("查找节点失败：" + (error.message ?: error.javaClass.simpleName))
        } ?: return fail("没有找到匹配节点（" + selector.describe() + "）")
        return try {
            if (!located.node.isEditable) {
                return fail("匹配节点不可编辑（editable=false，class=" + located.snapshot.className + "）")
            }
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val changed = try {
                located.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            } catch (_: Throwable) {
                false
            }
            if (!changed) {
                fail("输入文本失败（节点 editable=true，但 performAction(ACTION_SET_TEXT) 返回 false）")
            } else {
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("input", true)
                    addProperty("text", text)
                    add("node", snapshotJson(0, located.snapshot))
                }
            }
        } finally {
            runCatching { located.node.recycle() }
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Int): JsonObject {
        val service = AccessibilityService.instance ?: return fail("需要开启 AI.js Pro 无障碍服务")
        if (durationMillis !in 1..5000) {
            return fail("swipe 的 durationMillis 必须在 1..5000，收到 " + durationMillis)
        }
        return try {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            if (!dispatchGesture(service, path, durationMillis.toLong())) {
                fail("swipe 手势未被系统接受或执行失败（" + x1 + "," + y1 + " -> " + x2 + "," + y2 + "）")
            } else {
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("swiped", true)
                    addProperty("x1", x1); addProperty("y1", y1)
                    addProperty("x2", x2); addProperty("y2", y2)
                    addProperty("duration", durationMillis)
                }
            }
        } catch (error: Throwable) {
            fail("swipe 失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    fun swipe(args: JsonObject): JsonObject {
        val x1 = args.intOrNull("x1") ?: args.intOrNull("startX")
        val y1 = args.intOrNull("y1") ?: args.intOrNull("startY")
        val x2 = args.intOrNull("x2") ?: args.intOrNull("endX")
        val y2 = args.intOrNull("y2") ?: args.intOrNull("endY")
        val duration = args.intOrNull("durationMillis") ?: args.intOrNull("duration") ?: 300
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return fail("swipe 需要 x1/y1/x2/y2（或 startX/startY/endX/endY）")
        }
        return swipe(x1, y1, x2, y2, duration)
    }

    fun global(action: String): JsonObject {
        val key = action.trim().lowercase()
        val code = when (key) {
            "back" -> AccessibilityServiceApi.GLOBAL_ACTION_BACK
            "home" -> AccessibilityServiceApi.GLOBAL_ACTION_HOME
            "recents", "recent" -> AccessibilityServiceApi.GLOBAL_ACTION_RECENTS
            "notifications", "notification" -> AccessibilityServiceApi.GLOBAL_ACTION_NOTIFICATIONS
            else -> return fail("global 不支持 action=" + action + "；可选 back/home/recents/notifications")
        }
        val service = AccessibilityService.instance ?: return fail("需要开启 AI.js Pro 无障碍服务")
        return try {
            if (service.performGlobalAction(code)) {
                JsonObject().apply {
                    addProperty("success", true)
                    addProperty("action", key)
                }
            } else {
                fail("系统拒绝全局动作 " + key + "（performGlobalAction 返回 false）")
            }
        } catch (error: Throwable) {
            fail("global(" + key + ") 失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    /** 按方向滚动/翻页；作为手势包装，不要求节点 scrollable。 */
    fun scroll(args: JsonObject): JsonObject {
        val direction = (args.get("direction")?.let { runCatching { it.asString }.getOrNull() } ?: "up")
            .trim().lowercase()
        val service = AccessibilityService.instance ?: return fail("需要开启 AI.js Pro 无障碍服务")
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val cx = w / 2
        val cy = h / 2
        val distance = (minOf(w, h) * 0.6).toInt().coerceAtLeast(100)
        val (x1, y1, x2, y2) = when (direction) {
            "up", "forward", "down_list" -> intArrayOf(cx, cy + distance, cx, cy - distance)
            "down", "backward", "up_list" -> intArrayOf(cx, cy - distance, cx, cy + distance)
            "left" -> intArrayOf(cx + distance, cy, cx - distance, cy)
            "right" -> intArrayOf(cx - distance, cy, cx + distance, cy)
            else -> return fail("scroll 不支持 direction=" + direction + "；可选 up/down/left/right")
        }
        val result = swipe(x1, y1, x2, y2, 320)
        if (result.get("success")?.asBoolean == true) result.addProperty("direction", direction)
        return result
    }

    fun waitFor(selectorArg: JsonObject?, timeoutMillis: Int, intervalMillis: Int = 100): JsonObject {
        val selector = parseSelector(selectorArg) ?: return fail("选择器参数解析失败")
        if (selector.isEmpty) return fail("waitFor 需要至少一个选择器条件")
        val timeout = timeoutMillis.coerceIn(0, 15_000)
        val interval = intervalMillis.coerceIn(20, 1000)
        val startedAt = System.currentTimeMillis()
        while (true) {
            val result = find(selectorArg, 20)
            if (result.get("success")?.asBoolean == true && (result.get("count")?.asInt ?: 0) > 0) {
                result.addProperty("waitedMs", System.currentTimeMillis() - startedAt)
                return result
            }
            if (result.get("success")?.asBoolean != true) {
                result.addProperty("waitedMs", System.currentTimeMillis() - startedAt)
                return result
            }
            if (System.currentTimeMillis() - startedAt >= timeout) {
                val timedOut = fail("等待选择器超时（" + timeout + "ms，" + selector.describe() + "）")
                timedOut.addProperty("waitedMs", System.currentTimeMillis() - startedAt)
                timedOut.addProperty("count", 0)
                return timedOut
            }
            try {
                Thread.sleep(interval.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return fail("等待被中断（" + selector.describe() + "）")
            }
        }
    }

    private class Located(val node: AccessibilityNodeInfo, val snapshot: UiNodeSnapshot)

    private fun collectMatches(root: AccessibilityNodeInfo, selector: McpUiSelector,
                               limit: Int): List<UiNodeSnapshot> {
        val result = mutableListOf<UiNodeSnapshot>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var inspected = 0
        try {
            while (queue.isNotEmpty() && result.size < limit && inspected < MAX_TRAVERSE_NODES) {
                val node = queue.removeFirst()
                inspected++
                val owned = node !== root
                try {
                    val snapshot = snapshot(node)
                    if (selector.matches(snapshot)) result.add(snapshot)
                    val childCount = node.childCount
                    for (index in 0 until childCount) {
                        val child = runCatching { node.getChild(index) }.getOrNull()
                        if (child != null) queue.add(child)
                    }
                } catch (_: Throwable) {
                    // 单个节点读取失败不影响其它节点。
                } finally {
                    if (owned) runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                val leftover = queue.removeFirst()
                if (leftover !== root) runCatching { leftover.recycle() }
            }
        }
        return result
    }

    private fun locateNode(service: AccessibilityService, selector: McpUiSelector): Located? {
        val root = service.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var inspected = 0
        try {
            while (queue.isNotEmpty() && inspected < MAX_TRAVERSE_NODES) {
                val node = queue.removeFirst()
                inspected++
                val owned = node !== root
                var keep = false
                try {
                    val snapshot = snapshot(node)
                    if (selector.matches(snapshot)) {
                        keep = true
                        return Located(node, snapshot)
                    }
                    val childCount = node.childCount
                    for (index in 0 until childCount) {
                        val child = runCatching { node.getChild(index) }.getOrNull()
                        if (child != null) queue.add(child)
                    }
                } catch (_: Throwable) {
                    // 单个节点读取失败继续。
                } finally {
                    if (owned && !keep) runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                val leftover = queue.removeFirst()
                if (leftover !== root) runCatching { leftover.recycle() }
            }
        }
        return null
    }

    private fun tryClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isEnabled && current.isClickable) {
                val result = runCatching { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                if (result) {
                    current.recycle()
                    return true
                }
            }
            val next = runCatching { current.parent }.getOrNull()
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()
        return false
    }

    private fun tryLongClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isEnabled && current.isLongClickable) {
                val result = runCatching { current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
                    .getOrDefault(false)
                if (result) {
                    current.recycle()
                    return true
                }
            }
            val next = runCatching { current.parent }.getOrNull()
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()
        return false
    }

    private fun tapAt(service: AccessibilityService, x: Int, y: Int, longPress: Boolean): JsonObject {
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val duration = if (longPress) 600L else 60L
            if (!dispatchGesture(service, path, duration)) {
                fail((if (longPress) "长按" else "点击") + "坐标手势未被系统接受（" + x + "," + y + "）")
            } else {
                JsonObject().apply {
                    addProperty("success", true)
                    if (longPress) addProperty("longClicked", true) else addProperty("clicked", true)
                    addProperty("method", "coordinate")
                    addProperty("x", x); addProperty("y", y)
                }
            }
        } catch (error: Throwable) {
            fail("坐标手势失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun dispatchGesture(service: AccessibilityService, path: Path, durationMillis: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMillis.coerceIn(1L, 5000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var completed = false
        val callback = object : AccessibilityServiceApi.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        val accepted = try {
            service.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {
            false
        }
        if (!accepted) return false
        return try {
            latch.await(GESTURE_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            completed
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun parseSelector(selectorArg: JsonObject?): McpUiSelector? {
        return try {
            McpUiSelector.parse(selectorFromArgs(selectorArg))
        } catch (_: Throwable) {
            null
        }
    }

    private fun snapshot(node: AccessibilityNodeInfo): UiNodeSnapshot {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val left = minOf(rect.left, rect.right)
        val top = minOf(rect.top, rect.bottom)
        val right = maxOf(rect.left, rect.right)
        val bottom = maxOf(rect.top, rect.bottom)
        return UiNodeSnapshot(
            text = node.text?.toString().orEmpty().take(2048),
            resourceId = node.viewIdResourceName.orEmpty(),
            contentDesc = node.contentDescription?.toString().orEmpty().take(2048),
            className = node.className?.toString().orEmpty(),
            bounds = UiBounds(left, top, right, bottom),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            focused = node.isFocused,
        )
    }

    private fun snapshotJson(index: Int, snapshot: UiNodeSnapshot): JsonObject {
        return JsonObject().apply {
            addProperty("index", index)
            addProperty("text", snapshot.text)
            addProperty("resourceId", snapshot.resourceId)
            addProperty("contentDesc", snapshot.contentDesc)
            addProperty("className", snapshot.className)
            addProperty("bounds", snapshot.bounds.toString())
            addProperty("clickable", snapshot.clickable)
            addProperty("longClickable", snapshot.longClickable)
            addProperty("editable", snapshot.editable)
            addProperty("enabled", snapshot.enabled)
            addProperty("focused", snapshot.focused)
            addProperty("x", snapshot.bounds.centerX)
            addProperty("y", snapshot.bounds.centerY)
            addProperty("width", snapshot.bounds.width)
            addProperty("height", snapshot.bounds.height)
        }
    }

    private fun JsonObject.intOrNull(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun fail(message: String): JsonObject = JsonObject().apply {
        addProperty("success", false)
        addProperty("error", message)
    }
}
