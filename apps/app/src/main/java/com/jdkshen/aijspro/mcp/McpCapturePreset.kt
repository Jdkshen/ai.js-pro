package com.jdkshen.aijspro.mcp

import com.google.gson.JsonElement
import com.stardust.app.GlobalAppContext
import com.jdkshen.aijspro.Pref

/**
 * `capture_screen` 的四种预设：fastest / fast / balanced（缺省，540 宽 JPEG60）/ quality（唯一的原图 PNG）。
 *
 * 纯逻辑（可 JVM 单测）：调用方显式传的 format/quality/maxWidth/timeoutMillis **始终覆盖**预设，
 * 所以老调用不受影响；预设只决定「没传的那些参数」。
 */
internal object McpCapturePreset {
    const val MODE_FASTEST = "fastest"
    const val MODE_FAST = "fast"
    const val MODE_BALANCED = "balanced"
    const val MODE_QUALITY = "quality"

    data class Resolved(
        val mode: String,
        val format: String,
        val quality: Int,
        val maxWidth: Int,
        val timeoutMillis: Int,
        val note: String,
    )

    fun isKnownMode(mode: String): Boolean = mode in setOf(MODE_FASTEST, MODE_FAST, MODE_BALANCED, MODE_QUALITY)

    fun resolve(
        mode: String,
        format: String? = null,
        quality: Int? = null,
        maxWidth: Int? = null,
        timeoutMillis: Int? = null,
    ): Resolved {
        val normalized = if (isKnownMode(mode)) mode else MODE_BALANCED
        val preset = when (normalized) {
            // 最快：360 宽 + JPEG40 —— 只用来「有没有/大概在哪」，<0.15s 量级。
            MODE_FASTEST -> Resolved(normalized, "jpg", 40, 360, 1500,
                "fastest：360 宽 + JPEG40，最快最省（只够看有没有/大概在哪；要读字请用 fast 或 quality）")
            // 快速：缩到 540 宽 + JPEG 60 —— 编码最快、字节最小，适合「先看一眼/定位/轮询」。
            MODE_FAST -> Resolved(normalized, "jpg", 60, 540, 2000,
                "fast：540 宽 + JPEG60，最快最小，细节会糊一点（坐标请按 sourceWidth/scale 换算）")
            // 质量：原图 + PNG（无损）+ 更长超时，适合存档/取证/像素级比对。
            MODE_QUALITY -> Resolved(normalized, "png", 100, 0, 6000,
                "quality：原图 PNG 无损，最清晰也最大最慢（16MiB 上限，必要时自己加 maxWidth）")
            // balanced = 缺省档。2026-11-13 起改成「540 宽 + JPEG60」（原来与原图 PNG 一致）：
            // ① 默认输出 PNG 让每张截图 30k~90k token，是上下文体积的主要来源；
            // ② 原来 quality 缺省 100，单独传 format:jpg 反而比 PNG 还大（空转的 quality）。
            // 要原尺寸无损请显式 mode=quality（那是唯一仍出 PNG 的入口）。
            else -> Resolved(normalized, "jpg", 60, 540, 3000,
                "balanced：540 宽 + JPEG60 ——缺省档（要原尺寸无损请用 mode=quality；要更快用 mode=fast）")
        }
        return preset.copy(
            format = format?.lowercase() ?: preset.format,
            quality = quality ?: preset.quality,
            maxWidth = maxWidth ?: preset.maxWidth,
            timeoutMillis = timeoutMillis ?: preset.timeoutMillis,
        )
    }

    /**
     * `savePath` 解析：相对路径落在脚本目录（与 file_* 工具同一套边界），目录会自动补文件名；
     * 越界或非法路径给可读错误。返回 null 表示「不回传图像、直接落盘」没被启用。
     */
    fun resolveSaveTarget(savePath: String?): java.io.File? {
        val raw = savePath?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val root = operationRoot()
        var target = McpPathRules.resolve(root, raw, "savePath 必须在脚本目录内")
        if (raw.endsWith("/") || raw.endsWith("\\") || target.isDirectory) {
            target = java.io.File(target, "capture-${System.currentTimeMillis()}.png")
        }
        return target
    }

    /** A 的归置器：把裸文件名/脚本根直下文件改写到默认项目；拿不到上下文/归置器时返回 original。 */
    fun organizeSaveTarget(target: java.io.File?, organize: Boolean = true): Pair<java.io.File?, com.google.gson.JsonObject?> {
        if (target == null || !organize) return target to null
        val organizer = runCatching { outputOrganizer() }.getOrNull() ?: return target to null
        return runCatching {
            val result = organizer.organize(target.path, true, "capture_screen")
            java.io.File(result.path) to result.organized
        }.getOrElse { target to null }
    }

    /** 脚本目录下的工具操作根；task-26 起 dev_screen_diff 也用它解析 baseline 路径（所以从 private 放宽到 internal）。 */
    internal fun operationRoot(): java.io.File {
        val scriptRoot = runCatching { java.io.File(Pref.getScriptDirPath()).canonicalFile }.getOrElse { java.io.File(".").canonicalFile }
        val context = runCatching { GlobalAppContext.get() }.getOrNull() ?: return scriptRoot
        val operation = runCatching { McpSettings.operationPath(context) }.getOrDefault("")
        if (operation.isBlank()) return scriptRoot
        return runCatching { java.io.File(scriptRoot, operation).canonicalFile }.getOrDefault(scriptRoot)
    }

    private fun outputOrganizer(): ToolkitOutputOrganizer {
        val context = GlobalAppContext.get()
        val root = operationRoot()
        val store = java.io.File(java.io.File(context.filesDir, "apk-toolkit"), ToolkitIo.version(root.path.toByteArray()).take(16))
        return ToolkitOutputOrganizers.forStore(store, root)
    }

    /** saveFormat：auto（按扩展名）/ png / jpg / raw（RGBA8888 原始像素，最快）。 */
    fun resolveSaveFormat(saveFormat: String?, hasSaveTarget: Boolean): String {
        val raw = saveFormat?.trim()?.lowercase().orEmpty()
        val normalized = when (raw) {
            "" -> if (hasSaveTarget) "auto" else "png"
            "auto", "png", "jpg", "jpeg", "raw" -> raw
            else -> throw IllegalArgumentException("saveFormat 只能是 auto、png、jpg 或 raw，收到：$saveFormat")
        }
        return if (normalized == "jpeg") "jpg" else normalized
    }

    /** 区域（屏幕坐标，right/bottom 为边界）：先裁剪再缩放，坐标换算与整屏一致。 */
    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * 解析 `region`：支持 [l,t,r,b] 数组、"[l,t][r,b]" 字符串、{left,top,right,bottom} 或 {x,y,width,height} 对象。
     * 与 ui_* 的 bounds 语义保持一致（同一套写法），非法输入给可读错误。
     */
    fun parseRegion(element: JsonElement?): Region? {
        if (element == null || element.isJsonNull) return null
        val quad: IntArray = when {
            element.isJsonArray -> {
                val array = element.asJsonArray
                require(array.size() == 4) { "region 数组必须是 [left,top,right,bottom] 四项，收到 ${array.size()} 项" }
                IntArray(4) { index -> array[index].asIntStrict("region[$index]") }
            }
            element.isJsonPrimitive -> {
                require(element.asJsonPrimitive.isString) { "region 必须是字符串、[left,top,right,bottom] 数组或对象" }
                val text = element.asString.trim()
                val match = Regex("""^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$""").matchEntire(text)
                    ?: throw IllegalArgumentException("region 字符串格式无效：$text；应为 [left,top][right,bottom]")
                IntArray(4) { index -> match.groupValues[index + 1].toInt() }
            }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                fun has(key: String) = obj.has(key) && !obj.get(key).isJsonNull
                if (has("left") || has("top") || has("right") || has("bottom")) {
                    intArrayOf(
                        obj.get("left").asIntStrict("region.left"),
                        obj.get("top").asIntStrict("region.top"),
                        obj.get("right").asIntStrict("region.right"),
                        obj.get("bottom").asIntStrict("region.bottom"),
                    )
                } else {
                    val x = obj.get("x").asIntStrict("region.x")
                    val y = obj.get("y").asIntStrict("region.y")
                    val width = obj.get("width").asIntStrict("region.width")
                    val height = obj.get("height").asIntStrict("region.height")
                    intArrayOf(x, y, x + width, y + height)
                }
            }
            else -> throw IllegalArgumentException("region 必须是字符串、[left,top,right,bottom] 数组或对象")
        }
        val region = Region(quad[0], quad[1], quad[2], quad[3])
        require(region.right > region.left && region.bottom > region.top) {
            "region 的 right/bottom 必须大于 left/top，收到 ${region.left},${region.top},${region.right},${region.bottom}"
        }
        require(region.width <= 8192 && region.height <= 8192) { "region 不能超过 8192×8192" }
        return region
    }

    private fun JsonElement?.asIntStrict(name: String): Int {
        require(this != null && isJsonPrimitive && asJsonPrimitive.isNumber) { "$name 必须是整数" }
        return asInt
    }
}
