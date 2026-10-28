package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 屏幕坐标矩形（右/下为 Exclusive）；纯 JVM 类型，不依赖 Android。 */
data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right >= left) { "bounds.right 必须 >= left" }
        require(bottom >= top) { "bounds.bottom 必须 >= top" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom

    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    override fun toString(): String = "[$left,$top][$right,$bottom]"
}

/** 一个节点在纯 JVM 侧的可匹配快照；由 McpUiActions 从 AccessibilityNodeInfo 转换。 */
data class UiNodeSnapshot(
    val text: String = "",
    val resourceId: String = "",
    val contentDesc: String = "",
    val className: String = "",
    val bounds: UiBounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
)

/**
 * MCP 无障碍选择器。支持 text/resourceId/contentDesc/className/bounds 与坐标 x/y；
 * 所有条件同时满足才匹配。index 只表示「匹配结果中的序号（0 起）」，不参与节点条件，
 * 由 McpUiActions.locateNode 在动作前据此选择第 N 个匹配节点。
 *
 * 解析失败会抛 IllegalArgumentException（带明确字段/原因），调用方必须在访问无障碍服务
 * 与定位节点之前完成解析，避免非法输入先触碰手机。
 */
class McpUiSelector private constructor(
    val text: String?,
    val resourceId: String?,
    val contentDesc: String?,
    val className: String?,
    val bounds: UiBounds?,
    val x: Int?,
    val y: Int?,
    val index: Int?,
    val exact: Boolean = true,
) {
    init {
        require(index == null || index in 0..MAX_NODE_INDEX) {
            "index 必须在 0..$MAX_NODE_INDEX"
        }
    }

    val isEmpty: Boolean
        get() = text == null && resourceId == null && contentDesc == null &&
            className == null && bounds == null && x == null && y == null

    /** 只给了 x/y，没有其它 selector 条件：动作可直接按屏幕坐标执行。 */
    val isCoordinateOnly: Boolean
        get() = x != null && y != null && text == null && resourceId == null &&
            contentDesc == null && className == null && bounds == null

    /** 请求的匹配序号；省略 index 时沿用历史行为取第 0 个。 */
    val requestedIndex: Int
        get() = index ?: 0

    fun matches(node: UiNodeSnapshot): Boolean {
        if (text != null && !(if (exact) node.text == text else node.text.contains(text))) return false
        if (resourceId != null && !resourceIdMatches(node.resourceId, resourceId)) return false
        if (contentDesc != null && !(if (exact) node.contentDesc == contentDesc else node.contentDesc.contains(contentDesc))) return false
        if (className != null && !classNameMatches(node.className, className)) return false
        if (bounds != null && node.bounds != bounds) return false
        if (x != null && y != null && !node.bounds.contains(x, y)) return false
        return true
    }

    fun describe(): String {
        val parts = mutableListOf<String>()
        if (text != null) parts.add("text=$text")
        if (resourceId != null) parts.add("resourceId=$resourceId")
        if (contentDesc != null) parts.add("contentDesc=$contentDesc")
        if (className != null) parts.add("className=$className")
        if (bounds != null) parts.add("bounds=$bounds")
        if (x != null && y != null) parts.add("坐标=$x,$y")
        return if (parts.isEmpty()) "(空选择器)" else parts.joinToString(", ")
    }

    private fun resourceIdMatches(nodeValue: String, wanted: String): Boolean {
        if (nodeValue == wanted) return true
        if (nodeValue.isBlank()) return false
        // 支持 MCP 常用的短 resource-id（button）和 :id/button 写法。
        if (wanted.startsWith(":id/")) return nodeValue.endsWith(wanted)
        if (!wanted.contains('/')) return nodeValue.substringAfterLast('/') == wanted
        return false
    }

    private fun classNameMatches(nodeValue: String, wanted: String): Boolean {
        return nodeValue == wanted || nodeValue.substringAfterLast('.') == wanted
    }

    companion object {
        /** 与 Node24 请求级 index 上限一致，避免异常大的序号逼着遍历整棵树。 */
        const val MAX_NODE_INDEX = 255

        /** Flat ui_input uses text as the new value; nested selector.text remains a locator. */
        fun inputTarget(arguments: JsonObject?): JsonObject? {
            if (arguments == null || arguments.has("selector")) return arguments
            return arguments.deepCopy().apply { remove("text") }
        }

        fun parse(selector: JsonObject?): McpUiSelector {
            val objectValue = selector
                ?: return McpUiSelector(null, null, null, null, null, null, null, null)

            val x = objectValue.get("x")?.let { requiredJsonInt(it, "x") }
            val y = objectValue.get("y")?.let { requiredJsonInt(it, "y") }
            if ((x == null) != (y == null)) {
                throw IllegalArgumentException(
                    "x 和 y 必须同时提供；收到 x=${x ?: "缺省"}, y=${y ?: "缺省"}"
                )
            }

            return McpUiSelector(
                text = objectValue.selectorStringOrNull("text"),
                resourceId = objectValue.selectorStringOrNull("resourceId"),
                contentDesc = objectValue.selectorStringOrNull("contentDesc")
                    ?: objectValue.selectorStringOrNull("contentDescription"),
                className = objectValue.selectorStringOrNull("className"),
                bounds = parseBoundsField(objectValue),
                x = x,
                y = y,
                index = parseIndexField(objectValue),
                exact = objectValue.get("exact")?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "exact 必须是布尔值" }
                    it.asBoolean
                } ?: true,
            )
        }

        /**
         * 宽松版 bounds 解析（保留旧 API）：顶层 null 或非法输入返回 null。
         * 选择器 parse 不使用它，而是走 parseBoundsField/parseBoundsStrict 明确拒绝非法 bounds。
         */
        fun parseBounds(element: JsonElement?): UiBounds? {
            if (element == null || element.isJsonNull) return null
            return try {
                parseBoundsStrict(element)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun parseIndexField(objectValue: JsonObject): Int? {
            val element = objectValue.get("index") ?: return null
            val index = requiredJsonInt(element, "index")
            if (index !in 0..MAX_NODE_INDEX) {
                throw IllegalArgumentException(
                    "index 必须在 0..$MAX_NODE_INDEX（从0开始），收到 $index"
                )
            }
            return index
        }

        private fun parseBoundsField(objectValue: JsonObject): UiBounds? {
            val element = objectValue.get("bounds") ?: return null
            if (element.isJsonNull) {
                throw IllegalArgumentException(
                    "bounds 不能为 null；如不需要匹配 bounds，请省略该字段"
                )
            }
            return parseBoundsStrict(element)
        }

        private fun parseBoundsStrict(element: JsonElement): UiBounds {
            if (element.isJsonPrimitive) {
                if (!element.asJsonPrimitive.isString) {
                    throw IllegalArgumentException(
                        "bounds 必须是字符串、[left,top,right,bottom] 数组或对象，收到：" +
                            jsonTypeName(element)
                    )
                }
                val match = UI_BOUNDS_PATTERN.matchEntire(element.asString)
                    ?: throw IllegalArgumentException(
                        "bounds 字符串格式无效：" + element.asString +
                            "；应为 [left,top][right,bottom]"
                    )
                return checkedBounds(
                    match.groupValues[1].toBoundInt("left"),
                    match.groupValues[2].toBoundInt("top"),
                    match.groupValues[3].toBoundInt("right"),
                    match.groupValues[4].toBoundInt("bottom"),
                )
            }
            if (element.isJsonArray) {
                val array = element.asJsonArray
                if (array.size() != 4) {
                    throw IllegalArgumentException(
                        "bounds 数组必须恰好包含 4 个整数 [left,top,right,bottom]，收到 " +
                            array.size() + " 个"
                    )
                }
                return checkedBounds(
                    requiredJsonInt(array.get(0), "bounds[0]"),
                    requiredJsonInt(array.get(1), "bounds[1]"),
                    requiredJsonInt(array.get(2), "bounds[2]"),
                    requiredJsonInt(array.get(3), "bounds[3]"),
                )
            }
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                if (BOUNDS_RECT_KEYS.all { obj.has(it) }) {
                    return checkedBounds(
                        requiredJsonInt(obj.get("left"), "bounds.left"),
                        requiredJsonInt(obj.get("top"), "bounds.top"),
                        requiredJsonInt(obj.get("right"), "bounds.right"),
                        requiredJsonInt(obj.get("bottom"), "bounds.bottom"),
                    )
                }
                if (BOUNDS_XYWH_KEYS.all { obj.has(it) }) {
                    val left = requiredJsonInt(obj.get("x"), "bounds.x")
                    val top = requiredJsonInt(obj.get("y"), "bounds.y")
                    val width = requiredJsonInt(obj.get("width"), "bounds.width")
                    val height = requiredJsonInt(obj.get("height"), "bounds.height")
                    if (width < 0 || height < 0) {
                        throw IllegalArgumentException(
                            "bounds.width/height 必须 >= 0，收到 width=$width, height=$height"
                        )
                    }
                    val right = try {
                        Math.addExact(left, width)
                    } catch (_: ArithmeticException) {
                        throw IllegalArgumentException("bounds.x + width 超出 32 位整数范围")
                    }
                    val bottom = try {
                        Math.addExact(top, height)
                    } catch (_: ArithmeticException) {
                        throw IllegalArgumentException("bounds.y + height 超出 32 位整数范围")
                    }
                    return checkedBounds(left, top, right, bottom)
                }
                throw IllegalArgumentException(
                    "bounds 对象必须包含 left/top/right/bottom 或 x/y/width/height " +
                        "四个整数，收到字段：" + obj.keySet()
                )
            }
            throw IllegalArgumentException("bounds 类型不支持：" + jsonTypeName(element))
        }

        private fun JsonObject.selectorStringOrNull(key: String): String? {
            val value = get(key) ?: return null
            if (value.isJsonNull) {
                throw IllegalArgumentException("$key 不能为 null；如不需要匹配 $key，请省略该字段")
            }
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                throw IllegalArgumentException("$key 必须是字符串，收到：" + jsonTypeName(value))
            }
            return value.asString.takeIf { it.isNotEmpty() }
        }
    }
}

/** 纯 JVM 的匹配序号游标：定位时把遍历到的匹配依次编号，只有第 N 个（0 起）被选中。 */
internal class UiMatchCursor(private val requestedIndex: Int) {
    init {
        require(requestedIndex >= 0) { "index 不能为负数" }
    }

    var matchCount: Int = 0
        private set

    /** 记录一次匹配；返回 true 表示它就是请求的第 N 个。 */
    fun acceptMatch(): Boolean {
        matchCount++
        return matchCount - 1 == requestedIndex
    }
}

/**
 * 节点动作参数校验结果。McpUiActions 只负责把计划映射成 Android actionId/Bundle，
 * 这样缺参、错误类型、32 位溢出和非有限 float 都能在访问无障碍服务前被纯参数测试覆盖。
 */
internal sealed class UiNodeActionPlan {
    abstract val action: String

    data class Simple(override val action: String) : UiNodeActionPlan()
    data class SetSelection(override val action: String, val start: Int, val end: Int) : UiNodeActionPlan()
    data class ScrollTo(override val action: String, val row: Int, val column: Int) : UiNodeActionPlan()
    data class SetProgress(override val action: String, val value: Float) : UiNodeActionPlan()
}

/** `ui_node_action` 的 action/args 纯 JVM 校验与计划构建。 */
internal object McpUiNodeActionPlanner {
    const val SUPPORTED_ACTIONS =
        "copy/cut/paste/select/collapse/expand/dismiss/show/scrollForward/scrollBackward/" +
            "scrollUp/scrollDown/scrollLeft/scrollRight/contextClick/focus/clearFocus/" +
            "accessibilityFocus/clearAccessibilityFocus/setSelection/scrollTo/setProgress"

    private val SIMPLE_ACTIONS = setOf(
        "copy", "cut", "paste", "select", "collapse", "expand", "dismiss", "show",
        "scrollForward", "scrollBackward", "scrollUp", "scrollDown", "scrollLeft", "scrollRight",
        "contextClick", "focus", "clearFocus", "accessibilityFocus", "clearAccessibilityFocus",
    )

    fun plan(actionArg: String?, args: JsonObject?): UiNodeActionPlan {
        val action = actionArg?.trim().orEmpty()
        if (action.isEmpty()) {
            throw IllegalArgumentException("nodeAction 缺少 action")
        }
        if (action in SIMPLE_ACTIONS) return UiNodeActionPlan.Simple(action)
        return when (action) {
            "setSelection" -> {
                val values = requireActionArgs(action, args, "start/end")
                val start = requireActionInt(values, "start", action)
                val end = requireActionInt(values, "end", action)
                if (start < 0) {
                    throw IllegalArgumentException("setSelection.start 不能为负数，收到 $start")
                }
                if (end < start) {
                    throw IllegalArgumentException(
                        "setSelection 要求 0 <= start <= end，收到 start=$start, end=$end"
                    )
                }
                UiNodeActionPlan.SetSelection(action, start, end)
            }
            "scrollTo" -> {
                val values = requireActionArgs(action, args, "row/column")
                val row = requireActionInt(values, "row", action)
                val column = requireActionInt(values, "column", action)
                if (row < 0 || column < 0) {
                    throw IllegalArgumentException(
                        "scrollTo.row/column 不能为负数，收到 row=$row, column=$column"
                    )
                }
                UiNodeActionPlan.ScrollTo(action, row, column)
            }
            "setProgress" -> {
                val values = requireActionArgs(action, args, "value")
                UiNodeActionPlan.SetProgress(action, requireFiniteFloat(values, "value", action))
            }
            else -> throw IllegalArgumentException(
                "nodeAction 不支持 action=$action；可选 $SUPPORTED_ACTIONS"
            )
        }
    }

    private fun requireActionArgs(action: String, args: JsonObject?, fields: String): JsonObject {
        return args ?: throw IllegalArgumentException("$action 需要 args 对象（缺少 $fields）")
    }

    private fun requireActionInt(args: JsonObject, key: String, action: String): Int {
        if (!args.has(key)) {
            throw IllegalArgumentException("$action 缺少 args.$key（必须是整数）")
        }
        return requiredJsonInt(args.get(key), "$action.args.$key")
    }

    private fun requireFiniteFloat(args: JsonObject, key: String, action: String): Float {
        if (!args.has(key)) {
            throw IllegalArgumentException("$action 缺少 args.$key（必须是有限数字）")
        }
        val element = args.get(key)
        if (element == null || element.isJsonNull || !element.isJsonPrimitive ||
            !element.asJsonPrimitive.isNumber
        ) {
            throw IllegalArgumentException(
                "$action.args.$key 必须是数字，收到：" + jsonTypeName(element)
            )
        }
        val number = try {
            element.asDouble
        } catch (_: RuntimeException) {
            throw IllegalArgumentException(
                "$action.args.$key 必须是有限数字，收到：" + element
            )
        }
        if (!number.isFinite()) {
            throw IllegalArgumentException(
                "$action.args.$key 必须是有限数字，收到：" + element
            )
        }
        val value = number.toFloat()
        if (!value.isFinite()) {
            throw IllegalArgumentException(
                "$action.args.$key 超出 32 位 float 范围，收到：" + element
            )
        }
        return value
    }
}

private val UI_BOUNDS_PATTERN = Regex(
    """^\s*\[?(-?\d+)\s*,\s*(-?\d+)\]?\s*[\[\(]?\s*(-?\d+)\s*,\s*(-?\d+)\]?\s*$"""
)
private val BOUNDS_RECT_KEYS = arrayOf("left", "top", "right", "bottom")
private val BOUNDS_XYWH_KEYS = arrayOf("x", "y", "width", "height")

private fun checkedBounds(left: Int, top: Int, right: Int, bottom: Int): UiBounds {
    if (right < left) {
        throw IllegalArgumentException(
            "bounds.right 必须 >= left，收到 [$left,$top][$right,$bottom]"
        )
    }
    if (bottom < top) {
        throw IllegalArgumentException(
            "bounds.bottom 必须 >= top，收到 [$left,$top][$right,$bottom]"
        )
    }
    if (right.toLong() - left.toLong() > Int.MAX_VALUE) {
        throw IllegalArgumentException("bounds 宽度超出 32 位整数范围")
    }
    if (bottom.toLong() - top.toLong() > Int.MAX_VALUE) {
        throw IllegalArgumentException("bounds 高度超出 32 位整数范围")
    }
    return UiBounds(left, top, right, bottom)
}

private fun String.toBoundInt(label: String): Int {
    return toIntOrNull()
        ?: throw IllegalArgumentException("bounds.$label 超出 32 位整数范围：$this")
}

private fun requiredJsonInt(element: JsonElement?, label: String): Int {
    if (element == null || element.isJsonNull) {
        throw IllegalArgumentException("$label 必须是整数，收到：" + jsonTypeName(element))
    }
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw IllegalArgumentException("$label 必须是整数，收到：" + jsonTypeName(element))
    }
    return try {
        element.asBigDecimal.intValueExact()
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("$label 必须是 32 位整数，收到：" + element)
    }
}

private fun jsonTypeName(value: JsonElement?): String {
    if (value == null) return "缺省"
    if (value.isJsonNull) return "null"
    if (value.isJsonPrimitive) {
        val primitive = value.asJsonPrimitive
        return when {
            primitive.isString -> "字符串"
            primitive.isBoolean -> "布尔值"
            primitive.isNumber -> "数字"
            else -> "原始值"
        }
    }
    if (value.isJsonArray) return "数组"
    if (value.isJsonObject) return "对象"
    return value.javaClass.simpleName
}

internal fun selectorFromArgs(args: JsonObject?): JsonObject? {
    val source = args ?: return null
    val nested = source.get("selector") ?: return source
    require(nested.isJsonObject) { "selector 必须是 JSON 对象" }
    val selector = nested.asJsonObject
    val merged = selector.deepCopy()
    for (key in listOf("index", "exact")) {
        val value = source.get(key) ?: continue
        require(!selector.has(key)) { "$key 不能同时出现在顶层和嵌套 selector 中" }
        merged.add(key, value)
    }
    return merged
}
