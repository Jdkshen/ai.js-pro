package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    private fun assertSelectorRejected(expectedFragment: String, json: JsonObject.() -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            McpUiSelector.parse(JsonObject().apply(json))
        }
        assertTrue(
            "应拒绝并提示 '$expectedFragment'，实际：" + error.message,
            error.message?.contains(expectedFragment) == true,
        )
    }

    private fun actionArgs(json: JsonObject.() -> Unit): JsonObject = JsonObject().apply(json)

    private fun assertActionRejected(action: String, args: JsonObject?, expectedFragment: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            McpUiNodeActionPlanner.plan(action, args)
        }
        assertTrue(
            "应拒绝并提示 '$expectedFragment'，实际：" + error.message,
            error.message?.contains(expectedFragment) == true,
        )
    }

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
            JsonPrimitive("[1,2][3,4]")))
        assertEquals(UiBounds(1, 2, 3, 4), McpUiSelector.parseBounds(
            JsonArray().apply { add(1); add(2); add(3); add(4) }))
        assertEquals(UiBounds(1, 2, 13, 24), McpUiSelector.parseBounds(
            JsonObject().apply {
                addProperty("x", 1); addProperty("y", 2)
                addProperty("width", 12); addProperty("height", 22)
            }))
        assertNull(McpUiSelector.parseBounds(JsonPrimitive("not-a-bounds")))
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

    @Test fun indexIsParsedAndInvalidValuesAreRejected() {
        assertNull(selector { }.index)
        assertEquals(0, selector { }.requestedIndex)
        assertEquals(255, selector {
            addProperty("text", "设置")
            addProperty("index", 255)
        }.index!!)

        val indexed = selector {
            addProperty("text", "设置")
            addProperty("index", 2)
        }
        assertEquals(2, indexed.index!!)
        assertEquals(2, indexed.requestedIndex)
        assertTrue(indexed.matches(node(text = "设置")))

        val indexOnly = selector { addProperty("index", 1) }
        assertTrue("index 只是匹配序号，不能单独作为节点条件", indexOnly.isEmpty)

        assertSelectorRejected("index") { addProperty("text", "设置"); addProperty("index", -1) }
        assertSelectorRejected("index") { addProperty("text", "设置"); addProperty("index", 256) }
        assertSelectorRejected("32 位整数") { addProperty("index", 2147483648L) }
        assertSelectorRejected("index") { addProperty("index", 1.5) }
        assertSelectorRejected("index") { addProperty("index", "1") }
        assertSelectorRejected("index") { addProperty("index", true) }
        assertSelectorRejected("index") { add("index", JsonNull.INSTANCE) }
    }

    @Test fun matchCursorSelectsOnlyRequestedOrdinal() {
        val first = UiMatchCursor(0)
        assertTrue("省略 index 时仍取第 0 个", first.acceptMatch())
        assertFalse(first.acceptMatch())

        val cursor = UiMatchCursor(2)
        assertEquals(listOf(false, false, true, false, false), (0 until 5).map { cursor.acceptMatch() })
        assertEquals(5, cursor.matchCount)
        assertThrows(IllegalArgumentException::class.java) { UiMatchCursor(-1) }
    }

    @Test fun nestedSelectorReceivesTopLevelIndex() {
        val source = JsonObject().apply {
            add("selector", JsonObject().apply { addProperty("text", "确认") })
            addProperty("index", 1)
        }
        val parsed = McpUiSelector.parse(selectorFromArgs(source))
        assertEquals(1, parsed.index!!)
        assertTrue(parsed.matches(node(text = "确认")))
    }

    @Test fun duplicateIndexInNestedSelectorIsRejected() {
        val source = JsonObject().apply {
            add("selector", JsonObject().apply {
                addProperty("text", "确认")
                addProperty("index", 0)
            })
            addProperty("index", 1)
        }
        assertThrows(IllegalArgumentException::class.java) { selectorFromArgs(source) }
    }

    @Test fun xAndYMustBeProvidedTogetherAndBeIntegers() {
        assertSelectorRejected("必须同时提供") {
            addProperty("text", "确认")
            addProperty("x", 10)
        }
        assertSelectorRejected("必须同时提供") {
            addProperty("text", "确认")
            addProperty("y", 20)
        }
        assertSelectorRejected("整数") {
            addProperty("x", "10")
            addProperty("y", 20)
        }
        assertSelectorRejected("整数") {
            addProperty("x", 10)
            addProperty("y", 2.5)
        }
        assertSelectorRejected("整数") {
            add("x", JsonNull.INSTANCE)
            addProperty("y", 20)
        }
    }

    @Test fun invalidBoundsAreRejectedEvenWhenTextMatches() {
        assertSelectorRejected("bounds") {
            addProperty("text", "确认")
            addProperty("bounds", "not-a-bounds")
        }
        assertSelectorRejected("bounds") {
            addProperty("text", "确认")
            add("bounds", JsonArray().apply { add(1); add(2); add(3) })
        }
        assertSelectorRejected("bounds") {
            addProperty("text", "确认")
            add("bounds", JsonArray().apply { add(1); add(2); add(JsonNull.INSTANCE); add(4) })
        }
        assertSelectorRejected("bounds") {
            addProperty("text", "确认")
            add("bounds", JsonPrimitive(12))
        }
        assertSelectorRejected("bounds") {
            addProperty("text", "确认")
            add("bounds", JsonNull.INSTANCE)
        }
        assertSelectorRejected("bounds") {
            addProperty("text", "确认")
            addProperty("bounds", "[10,20][5,30]")
        }
        assertSelectorRejected("bounds") {
            addProperty("text", "确认")
            add("bounds", JsonObject().apply {
                addProperty("left", 1)
                addProperty("top", 2)
            })
        }
        assertSelectorRejected("bounds") {
            add("bounds", JsonObject().apply {
                addProperty("x", Int.MAX_VALUE)
                addProperty("y", 0)
                addProperty("width", 1)
                addProperty("height", 1)
            })
        }
    }

    @Test fun setSelectionRequiresOrderedIntegerArguments() {
        assertEquals(
            UiNodeActionPlan.SetSelection("setSelection", 2, 7),
            McpUiNodeActionPlanner.plan("setSelection", actionArgs {
                addProperty("start", 2)
                addProperty("end", 7)
            }),
        )
        assertEquals(
            UiNodeActionPlan.SetSelection("setSelection", 0, 0),
            McpUiNodeActionPlanner.plan("setSelection", actionArgs {
                addProperty("start", 0)
                addProperty("end", 0)
            }),
        )
        assertActionRejected("setSelection", null, "需要 args")
        assertActionRejected("setSelection", actionArgs { addProperty("start", 0) }, "缺少 args.end")
        assertActionRejected("setSelection", actionArgs { addProperty("end", 1) }, "缺少 args.start")
        assertActionRejected("setSelection", actionArgs {
            addProperty("start", 5)
            addProperty("end", 4)
        }, "start <= end")
        assertActionRejected("setSelection", actionArgs {
            addProperty("start", -1)
            addProperty("end", 0)
        }, "不能为负数")
        assertActionRejected("setSelection", actionArgs {
            addProperty("start", "0")
            addProperty("end", 1)
        }, "必须是整数")
        assertActionRejected("setSelection", actionArgs {
            addProperty("start", 0.5)
            addProperty("end", 1)
        }, "32 位整数")
        assertActionRejected("setSelection", actionArgs {
            addProperty("start", 0)
            addProperty("end", 2147483648L)
        }, "32 位整数")
        assertActionRejected("setSelection", actionArgs {
            add("start", JsonNull.INSTANCE)
            addProperty("end", 1)
        }, "整数")
    }

    @Test fun scrollToRequiresIntegerRowAndColumn() {
        assertEquals(
            UiNodeActionPlan.ScrollTo("scrollTo", 3, 4),
            McpUiNodeActionPlanner.plan("scrollTo", actionArgs {
                addProperty("row", 3)
                addProperty("column", 4)
            }),
        )
        assertActionRejected("scrollTo", null, "需要 args")
        assertActionRejected("scrollTo", actionArgs { addProperty("row", 0) }, "缺少 args.column")
        assertActionRejected("scrollTo", actionArgs { addProperty("column", 0) }, "缺少 args.row")
        assertActionRejected("scrollTo", actionArgs {
            addProperty("row", -1)
            addProperty("column", 0)
        }, "不能为负数")
        assertActionRejected("scrollTo", actionArgs {
            addProperty("row", 0)
            addProperty("column", 1.5)
        }, "32 位整数")
        assertActionRejected("scrollTo", actionArgs {
            addProperty("row", "0")
            addProperty("column", 1)
        }, "必须是整数")
    }

    @Test fun setProgressRequiresFiniteFloatValue() {
        assertEquals(
            UiNodeActionPlan.SetProgress("setProgress", 0.25f),
            McpUiNodeActionPlanner.plan("setProgress", actionArgs {
                addProperty("value", 0.25)
            }),
        )
        assertActionRejected("setProgress", null, "需要 args")
        assertActionRejected("setProgress", actionArgs { }, "缺少 args.value")
        assertActionRejected("setProgress", actionArgs { addProperty("value", "0.5") }, "必须是数字")
        assertActionRejected("setProgress", actionArgs { addProperty("value", true) }, "必须是数字")
        assertActionRejected("setProgress", actionArgs { add("value", JsonNull.INSTANCE) }, "必须是数字")
        assertActionRejected("setProgress", actionArgs { addProperty("value", Double.NaN) }, "有限数字")
        assertActionRejected("setProgress", actionArgs {
            addProperty("value", Double.POSITIVE_INFINITY)
        }, "有限数字")
        assertActionRejected("setProgress", actionArgs {
            addProperty("value", Double.MAX_VALUE)
        }, "float")
    }

    @Test fun simpleUnknownAndEmptyActionsAreValidated() {
        assertEquals(UiNodeActionPlan.Simple("copy"), McpUiNodeActionPlanner.plan("copy", null))
        assertEquals(UiNodeActionPlan.Simple("scrollDown"), McpUiNodeActionPlanner.plan(" scrollDown ", null))
        assertActionRejected("bogus", null, "不支持 action")
        assertActionRejected("", null, "缺少 action")
        assertActionRejected("   ", actionArgs { addProperty("value", 1.0) }, "缺少 action")
    }

    @Test fun selectorConditionWrongTypesAreRejected() {
        assertSelectorRejected("text") { addProperty("text", 123) }
        assertSelectorRejected("resourceId") { addProperty("resourceId", true) }
        assertSelectorRejected("className") { add("className", JsonNull.INSTANCE) }
    }
    @Test fun inputValueDoesNotBecomeTargetText() {
        val flat = JsonObject().apply { addProperty("text", "new"); addProperty("resourceId", "editor") }
        val target = McpUiSelector.inputTarget(flat)!!
        assertFalse(target.has("text"))
        assertEquals("editor", target.get("resourceId").asString)
        assertTrue(flat.has("text"))
        val nested = JsonObject().apply { add("selector", JsonObject().apply { addProperty("text", "old") }); addProperty("text", "new") }
        assertEquals("old", McpUiSelector.inputTarget(nested)!!.getAsJsonObject("selector").get("text").asString)
    }

    @Test fun nestedSelectorMustBeAnObject() {
        for (bad in listOf(JsonNull.INSTANCE, JsonPrimitive("bad"))) {
            val args = JsonObject().apply { add("selector", bad); addProperty("x", 10); addProperty("y", 20) }
            assertThrows(IllegalArgumentException::class.java) { selectorFromArgs(args) }
        }
    }
    @Test fun exactFalseEnablesTextAndDescriptionContainsMatching() {
        val node = UiNodeSnapshot(text="prefix needle suffix", contentDesc="prefix icon suffix", bounds=UiBounds(0,0,20,20))
        val args = JsonObject().apply { addProperty("text", "needle"); addProperty("contentDesc", "icon") }
        assertFalse(McpUiSelector.parse(args).matches(node))
        args.addProperty("exact", false)
        assertTrue(McpUiSelector.parse(args).matches(node))
        val nested = JsonObject().apply { add("selector", args.deepCopy().apply { remove("exact") }); addProperty("exact",false) }
        assertTrue(McpUiSelector.parse(selectorFromArgs(nested)).matches(node))
        args.addProperty("exact", "false")
        assertThrows(IllegalArgumentException::class.java) { McpUiSelector.parse(args) }
    }

}
