package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** McpUiSelector 的纯 JVM 单测：不依赖 Android/Robolectric。 */
class McpUiSelectorTest {

    private fun node(
        text: String = "",
        resourceId: String = "",
        contentDesc: String = "",
        className: String = "",
        bounds: UiBounds = UiBounds(0, 0, 100, 200),
        clickable: Boolean = false,
        longClickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
    ) = UiNodeSnapshot(
        text = text, resourceId = resourceId, contentDesc = contentDesc, className = className,
        bounds = bounds, clickable = clickable, longClickable = longClickable,
        editable = editable, enabled = enabled,
    )

    private fun selector(json: JsonObject.() -> Unit): McpUiSelector =
        McpUiSelector.parse(JsonObject().apply(json))

    @Test fun textMatchesExactly() {
        assertTrue(selector { addProperty("text", "设置") }.matches(node(text = "设置")))
        assertFalse(selector { addProperty("text", "设置") }.matches(node(text = "设置页")))
        assertFalse(selector { addProperty("text", "设置") }.matches(node(text = "")))
    }

    @Test fun resourceIdMatchesFullShortAndIdPrefix() {
        val full = selector { addProperty("resourceId", "com.example:id/button") }
        assertTrue(full.matches(node(resourceId = "com.example:id/button")))
        val short = selector { addProperty("resourceId", "button") }
        assertTrue(short.matches(node(resourceId = "com.example:id/button")))
        val idPrefix = selector { addProperty("resourceId", ":id/button") }
        assertTrue(idPrefix.matches(node(resourceId = "com.example:id/button")))
        assertFalse(short.matches(node(resourceId = "com.example:id/button_extra")))
    }

    @Test fun contentDescAndClassNameMatch() {
        val content = selector { addProperty("contentDesc", "返回") }
        assertTrue(content.matches(node(contentDesc = "返回")))
        assertFalse(content.matches(node(contentDesc = "返回键")))

        val fullClass = selector { addProperty("className", "android.widget.TextView") }
        assertTrue(fullClass.matches(node(className = "android.widget.TextView")))
        val simpleClass = selector { addProperty("className", "TextView") }
        assertTrue(simpleClass.matches(node(className = "android.widget.TextView")))
        assertFalse(simpleClass.matches(node(className = "android.widget.Button")))
    }

    @Test fun boundsMatchesExactRectangle() {
        val bounds = UiBounds(10, 20, 110, 220)
        val exact = selector { addProperty("bounds", "[10,20][110,220]") }
        assertTrue(exact.matches(node(bounds = bounds)))
        assertFalse(exact.matches(node(bounds = UiBounds(10, 20, 111, 220))))
    }

    @Test fun coordinatesMatchContainingNodeAndCoordinateOnly() {
        val point = selector {
            addProperty("x", 50)
            addProperty("y", 100)
        }
        assertTrue(point.matches(node(bounds = UiBounds(0, 0, 100, 200))))
        assertFalse(point.matches(node(bounds = UiBounds(100, 0, 200, 200))))
        assertTrue(point.isCoordinateOnly)

        val mixed = selector {
            addProperty("text", "目标")
            addProperty("x", 50)
            addProperty("y", 100)
        }
        assertFalse(mixed.isCoordinateOnly)
        assertTrue(mixed.matches(node(text = "目标", bounds = UiBounds(0, 0, 100, 200))))
        assertFalse(mixed.matches(node(text = "其它", bounds = UiBounds(0, 0, 100, 200))))
    }

    @Test fun boundsParsesStringArrayAndObject() {
        assertEquals(UiBounds(1, 2, 3, 4), McpUiSelector.parseBounds(
            com.google.gson.JsonPrimitive("[1,2][3,4]")))
        assertEquals(UiBounds(1, 2, 3, 4), McpUiSelector.parseBounds(
            JsonArray().apply { add(1); add(2); add(3); add(4) }))
        assertEquals(UiBounds(1, 2, 13, 24), McpUiSelector.parseBounds(
            JsonObject().apply {
                addProperty("x", 1); addProperty("y", 2)
                addProperty("width", 12); addProperty("height", 22)
            }))
        assertNull(McpUiSelector.parseBounds(com.google.gson.JsonPrimitive("not-a-bounds")))
    }

    @Test fun emptyAndCombinedSelectorBehavior() {
        val empty = McpUiSelector.parse(JsonObject())
        assertTrue(empty.isEmpty)
        assertTrue(empty.matches(node()))

        val combined = selector {
            addProperty("text", "确认")
            addProperty("className", "Button")
            addProperty("bounds", "[0,0][100,200]")
        }
        assertTrue(combined.matches(node(text = "确认", className = "android.widget.Button")))
        assertFalse(combined.matches(node(text = "取消", className = "android.widget.Button")))
    }

    @Test fun describeContainsConfiguredConditions() {
        val description = selector {
            addProperty("text", "登录")
            addProperty("resourceId", "login")
            addProperty("x", 5)
            addProperty("y", 6)
        }.describe()
        assertTrue(description.contains("text=登录"))
        assertTrue(description.contains("resourceId=login"))
        assertTrue(description.contains("5,6"))
    }
}
