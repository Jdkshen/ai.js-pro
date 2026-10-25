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
 * 所有条件同时满足才匹配。纯逻辑集中在 matches()/parse()，可直接 JVM 单测。
 */
class McpUiSelector private constructor(
    val text: String?,
    val resourceId: String?,
    val contentDesc: String?,
    val className: String?,
    val bounds: UiBounds?,
    val x: Int?,
    val y: Int?,
) {
    val isEmpty: Boolean
        get() = text == null && resourceId == null && contentDesc == null &&
            className == null && bounds == null && x == null && y == null

    /** 只给了 x/y，没有其它 selector 条件：动作可直接按屏幕坐标执行。 */
    val isCoordinateOnly: Boolean
        get() = x != null && y != null && text == null && resourceId == null &&
            contentDesc == null && className == null && bounds == null

    fun matches(node: UiNodeSnapshot): Boolean {
        if (text != null && node.text != text) return false
        if (resourceId != null && !resourceIdMatches(node.resourceId, resourceId)) return false
        if (contentDesc != null && node.contentDesc != contentDesc) return false
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
        private val BOUNDS_PATTERN = Regex(
            """^\s*\[?(-?\d+)\s*,\s*(-?\d+)\]?\s*[\[\(]?\s*(-?\d+)\s*,\s*(-?\d+)\]?\s*$"""
        )

        fun parse(selector: JsonObject?): McpUiSelector {
            val objectValue = selector ?: return McpUiSelector(null, null, null, null, null, null, null)
            return McpUiSelector(
                text = objectValue.stringOrNull("text"),
                resourceId = objectValue.stringOrNull("resourceId"),
                contentDesc = objectValue.stringOrNull("contentDesc")
                    ?: objectValue.stringOrNull("contentDescription"),
                className = objectValue.stringOrNull("className"),
                bounds = parseBounds(objectValue.get("bounds")),
                x = objectValue.intOrNull("x"),
                y = objectValue.intOrNull("y"),
            )
        }

        fun parseBounds(element: JsonElement?): UiBounds? {
            if (element == null || element.isJsonNull) return null
            try {
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    val match = BOUNDS_PATTERN.matchEntire(element.asString) ?: return null
                    return UiBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
                }
                if (element.isJsonArray) {
                    val array = element.asJsonArray
                    if (array.size() != 4) return null
                    return UiBounds(array.get(0).asInt, array.get(1).asInt,
                        array.get(2).asInt, array.get(3).asInt)
                }
                if (element.isJsonObject) {
                    val obj = element.asJsonObject
                    if (obj.has("left") && obj.has("top") && obj.has("right") && obj.has("bottom")) {
                        return UiBounds(obj.get("left").asInt, obj.get("top").asInt,
                            obj.get("right").asInt, obj.get("bottom").asInt)
                    }
                    if (obj.has("x") && obj.has("y") && obj.has("width") && obj.has("height")) {
                        val x = obj.get("x").asInt
                        val y = obj.get("y").asInt
                        return UiBounds(x, y, x + obj.get("width").asInt, y + obj.get("height").asInt)
                    }
                }
            } catch (_: RuntimeException) {
                return null
            }
            return null
        }

        private fun JsonObject.stringOrNull(key: String): String? {
            val value = get(key) ?: return null
            if (value.isJsonNull) return null
            return try {
                value.asString.takeIf { it.isNotEmpty() }
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun JsonObject.intOrNull(key: String): Int? {
            val value = get(key) ?: return null
            if (value.isJsonNull || !value.isJsonPrimitive) return null
            return try {
                value.asInt
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}

internal fun selectorFromArgs(args: JsonObject?): JsonObject? {
    val nested = args?.get("selector")
    return if (nested != null && nested.isJsonObject) nested.asJsonObject else args
}
