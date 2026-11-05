package com.jdkshen.aijspro.mcp

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 坐标换算的纯数学部分（可 JVM 单测）：空间 → 空间的仿射（缩放 + 平移）与点/矩形换算。
 *
 * 约定：`dst = src * scale + offset`，逐轴独立（X/Y 可能不同比例 —— 反馈里实测就是 (1.0000, 1.0009)）。
 */
internal object McpCoordinateMath {
    data class Affine(val scaleX: Double, val scaleY: Double, val offsetX: Double, val offsetY: Double, val source: String) {
        fun apply(x: Double, y: Double): Pair<Double, Double> = (x * scaleX + offsetX) to (y * scaleY + offsetY)
    }

    /** 只有一个对应点：只能求平移（缩放取 1），并明确标注 source=translation_only。 */
    fun fromOnePoint(srcX: Double, srcY: Double, dstX: Double, dstY: Double): Affine =
        Affine(1.0, 1.0, dstX - srcX, dstY - srcY, "translation_only")

    /** 两个对应点：逐轴求缩放与平移；两点在某轴上相同则退化为平移。 */
    fun fromTwoPoints(
        src1X: Double, src1Y: Double, dst1X: Double, dst1Y: Double,
        src2X: Double, src2Y: Double, dst2X: Double, dst2Y: Double,
    ): Affine {
        fun axis(s1: Double, d1: Double, s2: Double, d2: Double): Pair<Double, Double> {
            val ds = s2 - s1
            if (kotlin.math.abs(ds) < 1e-9) return 1.0 to (d1 - s1)
            val scale = (d2 - d1) / ds
            return scale to (d1 - s1 * scale)
        }
        val (sx, ox) = axis(src1X, dst1X, src2X, dst2X)
        val (sy, oy) = axis(src1Y, dst1Y, src2Y, dst2Y)
        return Affine(sx, sy, ox, oy, "two_point")
    }

    /** 由「整块空间的边界」推导：假设两边都从各自原点开始按比例铺满（截图 = 面板）。 */
    fun fromSizes(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Affine {
        val sx = if (srcW > 0) dstW.toDouble() / srcW else 1.0
        val sy = if (srcH > 0) dstH.toDouble() / srcH else 1.0
        return Affine(sx, sy, 0.0, 0.0, "size_ratio")
    }

    /** 坐标取整用 half-up（Math.round）：像素坐标 2.5 → 3，比 half-even 更符合直觉。 */
    fun round(value: Double): Int = Math.round(value).toInt()
}

/**
 * `coordinate_convert`：一次回答「截图 / 显示 / 浮窗」三套坐标系的关系，并把换算做成可复用的仿射。
 *
 * 为什么需要它：.4 上的扑克识别项目实测同一时刻存在三套坐标——截图 2772×1280、显示 2620×1215、
 * 浮窗与显示同尺度但整体差 (152, 0)，为了把框画对不得不自己写两个标定脚本。
 */
internal object McpCoordinateTools {
    private const val PREF_KEY = "mcp.coordinate.affine"

    fun call(context: Context, args: JsonObject): JsonObject {
        val action = args.coordStringOr("action", "state").lowercase()
        return when (action) {
            "state" -> state(context, args.coordBoolOr("measureScreenshot", false))
            "calibrate" -> calibrate(context, args)
            "convert" -> convert(context, args)
            "clear" -> {
                prefs(context).edit().remove(PREF_KEY).apply()
                ToolkitIo.ok(JsonObject().apply { addProperty("cleared", true) })
            }
            else -> throw IllegalArgumentException("action 只能是 state、calibrate、convert 或 clear")
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    /** 已标定的仿射（没有就返回 null）。格式：scaleX,scaleY,offsetX,offsetY,points,at。 */
    private fun stored(context: Context): McpCoordinateMath.Affine? {
        val raw = prefs(context).getString(PREF_KEY, null) ?: return null
        val parts = raw.split(",").mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size < 4) return null
        return McpCoordinateMath.Affine(parts[0], parts[1], parts[2], parts[3], "calibrated")
    }

    private fun state(context: Context, measureScreenshot: Boolean): JsonObject {
        val spaces = spaces(context, measureScreenshot)
        val panel = spaces.panel
        val app = spaces.app
        val calibrated = stored(context)
        val derived = if (panel.width > 0 && app.width > 0 && (panel.width != app.width || panel.height != app.height)) {
            McpCoordinateMath.fromSizes(app.width, app.height, panel.width, panel.height)
        } else null
        val body = JsonObject().apply {
            add("spaces", spaces.toJson(measureScreenshot))
            addProperty("screenshotEqualsPanel", panel.width > 0 && spaces.screenshot?.width == panel.width)
            calibrated?.let {
                add("affine", affineJson(it))
                addProperty("affineSource", "calibrated")
            } ?: derived?.let {
                add("affine", affineJson(it))
                addProperty("affineSource", "derived_from_panel_vs_app")
                addProperty("affineAssumed", true)
            } ?: addProperty("affineSource", "no_mapping_needed")
            addProperty("hint", "浮窗/无障碍用同一套（显示 → appDisplay/window）；截图空间可能更大（面板分辨率）。" +
                "需要精确映射时用 action=calibrate 传两组对应点（同一时刻的浮窗坐标与截图坐标），之后 convert 就用标定值。" +
                "浮窗里根 View 的 layout_* 会被 WindowManager 无视：根必须 match_parent，子 View 用固定 px。")
            add("calibrationHowTo", JsonArray().apply {
                add("1) 在浮窗坐标空间取两个明显不同的点（例如把浮窗拖到左上与右下，读 getX/getY）")
                add("2) 同一次截图里量出这两点的截图坐标")
                add("3) coordinate_convert{action:\"calibrate\", windowPoint1:[x,y], screenshotPoint1:[x,y], windowPoint2:[x,y], screenshotPoint2:[x,y]}")
            })
        }
        return ToolkitIo.ok(body)
    }

    private fun calibrate(context: Context, args: JsonObject): JsonObject {
        val w1 = args.coordIntArray("windowPoint1") ?: throw IllegalArgumentException("缺少 windowPoint1（[x,y]）")
        val s1 = args.coordIntArray("screenshotPoint1") ?: throw IllegalArgumentException("缺少 screenshotPoint1（[x,y]）")
        val w2 = args.coordIntArray("windowPoint2")
        val s2 = args.coordIntArray("screenshotPoint2")
        val affine = if (w2 != null && s2 != null) {
            McpCoordinateMath.fromTwoPoints(w1[0], w1[1], s1[0], s1[1], w2[0], w2[1], s2[0], s2[1])
        } else {
            McpCoordinateMath.fromOnePoint(w1[0], w1[1], s1[0], s1[1])
        }
        prefs(context).edit().putString(PREF_KEY,
            "${affine.scaleX},${affine.scaleY},${affine.offsetX},${affine.offsetY}").apply()
        return ToolkitIo.ok(JsonObject().apply {
            add("affine", affineJson(affine))
            addProperty("points", if (w2 != null && s2 != null) 2 else 1)
            addProperty("note", if (w2 != null && s2 != null) "已按两点标定（逐轴缩放+平移）"
                else "只有一组对应点：只能求平移（缩放按 1）；给第二组点可同时求缩放")
        })
    }

    private fun convert(context: Context, args: JsonObject): JsonObject {
        val from = args.coordStringOr("from", "window").lowercase()
        val to = args.coordStringOr("to", "screenshot").lowercase()
        val spaces = spaces(context)
        val affine = resolveAffine(context, from, to, spaces)
            ?: throw IllegalArgumentException("$from → $to 没有可用映射：请先 action=calibrate 传两组对应点")
        val point = args.coordIntArray("point")
        val rect = args.coordIntArray("rect")
        require(point != null || rect != null) { "convert 需要 point:[x,y] 或 rect:[l,t,r,b]" }
        if (rect != null) require(rect.size >= 4) { "rect 必须四个数字 [l,t,r,b]" }
        val body = JsonObject().apply {
            addProperty("from", from); addProperty("to", to)
            add("affine", affineJson(affine))
            addProperty("affineSource", affine.source)
            if (point != null) {
                val (x, y) = affine.apply(point[0], point[1])
                add("point", JsonArray().apply { add(McpCoordinateMath.round(x)); add(McpCoordinateMath.round(y)) })
            }
            if (rect != null) {
                val (l, t) = affine.apply(rect[0], rect[1])
                val (r, b) = affine.apply(rect[2], rect[3])
                add("rect", JsonArray().apply {
                    add(McpCoordinateMath.round(l)); add(McpCoordinateMath.round(t))
                    add(McpCoordinateMath.round(r)); add(McpCoordinateMath.round(b))
                })
            }
        }
        return ToolkitIo.ok(body)
    }

    private fun resolveAffine(context: Context, from: String, to: String, spaces: Spaces): McpCoordinateMath.Affine? {
        if (from == to) return McpCoordinateMath.Affine(1.0, 1.0, 0.0, 0.0, "identity")
        stored(context)?.let { return it }
        val windowLike = setOf("window", "appdisplay", "display", "overlay")
        val src = when (from) { in windowLike -> spaces.app; "panel" -> spaces.panel; "screenshot" -> spaces.screenshot ?: spaces.panel; else -> spaces.app }
        val dst = when (to) { in windowLike -> spaces.app; "panel" -> spaces.panel; "screenshot" -> spaces.screenshot ?: spaces.panel; else -> spaces.app }
        return McpCoordinateMath.fromSizes(src.width, src.height, dst.width, dst.height)
    }

    private fun affineJson(affine: McpCoordinateMath.Affine): JsonObject = JsonObject().apply {
        addProperty("scaleX", affine.scaleX); addProperty("scaleY", affine.scaleY)
        addProperty("offsetX", affine.offsetX); addProperty("offsetY", affine.offsetY)
        addProperty("formula", "dst = src * (scaleX,scaleY) + (offsetX,offsetY)")
        addProperty("source", affine.source)
    }

    private data class Size(val width: Int, val height: Int)
    private class Spaces(val panel: Size, val app: Size, val window: Size?, val screenshot: Size?, val density: Double, val rotation: Int) {
        fun toJson(measureScreenshot: Boolean): JsonObject = JsonObject().apply {
            add("panel", sizeJson(panel, "物理面板（通常等于截图尺寸）"))
            add("appDisplay", sizeJson(app, "getDisplayMetrics / WindowMetrics：无障碍与浮窗都用这套"))
            window?.let { add("windowBounds", sizeJson(it, "当前活动窗口 bounds（可能小于 appDisplay）")) }
            if (screenshot != null) add("screenshot", sizeJson(screenshot, "实测截图（本次真的截了一张）"))
            else addProperty("screenshotSource", if (measureScreenshot) "unavailable" else "not_measured（要实测加 measureScreenshot=true）")
            addProperty("density", density); addProperty("rotation", rotation)
        }
        private fun sizeJson(size: Size, note: String): JsonObject = JsonObject().apply {
            addProperty("width", size.width); addProperty("height", size.height); addProperty("note", note)
        }
    }

    private fun spaces(context: Context, measureScreenshot: Boolean = false): Spaces {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val panel = Size(metrics.widthPixels, metrics.heightPixels)
        val appMetrics = context.resources.displayMetrics
        val app = Size(appMetrics.widthPixels, appMetrics.heightPixels)
        val rotation = runCatching { @Suppress("DEPRECATION") wm.defaultDisplay.rotation }.getOrDefault(0)
        val screenshot = if (measureScreenshot) {
            runCatching {
                val shot = com.stardust.autojs.core.image.capture.AccessibilityScreenCapturer().capture(3000)
                try { Size(shot.width, shot.height) } finally { shot.recycle() }
            }.getOrNull()
        } else null
        return Spaces(panel, app, null, screenshot, appMetrics.density.toDouble(), rotation)
    }
}

private fun JsonObject.coordStringOr(name: String, fallback: String): String {
    val element = get(name) ?: return fallback
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else fallback
}

private fun JsonObject.coordBoolOr(name: String, fallback: Boolean): Boolean {
    val element = get(name) ?: return fallback
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) element.asBoolean else fallback
}

/** [x,y] 或 [l,t,r,b]：按数组解析，长度不限（point 取前 2 个，rect 取 4 个）。 */
private fun JsonObject.coordIntArray(name: String): List<Double>? {
    val element: JsonElement = get(name) ?: return null
    if (!element.isJsonArray) throw IllegalArgumentException("$name 必须是数字数组")
    val array = element.asJsonArray
    require(array.size() >= 2) { "$name 至少要有两个数字" }
    return array.map {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "$name 只能包含数字" }
        it.asDouble
    }
}
