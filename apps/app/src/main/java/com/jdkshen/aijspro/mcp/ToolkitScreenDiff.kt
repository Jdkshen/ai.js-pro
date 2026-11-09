package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import java.security.MessageDigest

/**
 * W4 task-26：`dev_screen_diff` 的**纯比较核心**（不依赖 Android，可 JVM 单测）。
 *
 * 语义边界（写进工具描述与返回 notes）：这是**像素级**差异 —— 不做语义/模板匹配（模板匹配用 matchTemplate 系列）；
 * JPEG 预设下截图自带压缩噪声，[DEFAULT_JPEG_TOLERANCE] 就是给它用的；`ratio` 是「变化像素/比较像素」，
 * **不等于**「界面变化程度」。
 *
 * 尺寸不一致的明确行为：**拒绝**（[SizeMismatchException] / [sizeMismatchFailure]），不裁剪、不缩放、不猜对应关系 ——
 * 两张不同尺寸的帧之间没有可信的像素对应，硬比会给出「看起来没变」的假结论；调用方用同样的 region/maxDims 重截即可。
 */
internal object ToolkitScreenDiff {
    const val CODE_NO_BASELINE = "NO_BASELINE"
    const val CODE_SIZE_MISMATCH = "SIZE_MISMATCH"
    const val CODE_BASELINE_UNREADABLE = "BASELINE_UNREADABLE"
    const val CODE_INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val CODE_CAPTURE_FAILED = "CAPTURE_FAILED"
    const val MODE_COUNT = "count"
    const val MODE_BBOX = "bbox"
    const val MODE_MASK = "mask"
    val MODES = setOf(MODE_COUNT, MODE_BBOX, MODE_MASK)

    /** JPEG 压缩噪声的默认每通道灰度容差；PNG 无损帧默认 0。 */
    const val DEFAULT_JPEG_TOLERANCE = 8

    /** 进程内只保留一帧，且只保留不会把内存顶爆的尺寸（4M 像素 ≈ 16MiB ARGB）。 */
    const val MAX_REMEMBERED_PIXELS = 4_000_000

    const val MASK_CHANGED = -0x1
    const val MASK_UNCHANGED = -0x1000000

    const val PIXEL_LEVEL_NOTE =
        "像素级差异：不做语义/模板匹配（模板匹配用 matchTemplate 系列）；ratio=变化像素/比较像素，不代表界面变化程度。"

    /** RGBA_8888 像素帧（ARGB int，行优先）。 */
    data class Frame(val width: Int, val height: Int, val pixels: IntArray) {
        val pixelCount: Int get() = width * height
        /** 帧身份：对原始像素取 sha256（同一屏重复截图在 PNG 下应得到同一个值）。 */
        val sha256: String by lazy { sha256OfPixels(pixels) }
    }

    /** bbox 用 [left, top, rightExclusive, bottomExclusive]；无变化时为 null。 */
    data class Result(
        val comparedPixels: Int,
        val changedPixels: Int,
        val ratio: Double,
        val bbox: IntArray?,
        val maxChannelDelta: Int,
    )

    class SizeMismatchException(
        val baselineWidth: Int,
        val baselineHeight: Int,
        val currentWidth: Int,
        val currentHeight: Int,
    ) : IllegalArgumentException(
        "基线帧 ${baselineWidth}x${baselineHeight} 与当前帧 ${currentWidth}x${currentHeight} 尺寸不一致；请用同样的 region/maxDims 重截，或换一张同尺寸的 baseline"
    )

    fun maxChannelDelta(a: Int, b: Int): Int {
        val da = Math.abs((a ushr 24 and 0xff) - (b ushr 24 and 0xff))
        val dr = Math.abs((a ushr 16 and 0xff) - (b ushr 16 and 0xff))
        val dg = Math.abs((a ushr 8 and 0xff) - (b ushr 8 and 0xff))
        val db = Math.abs((a and 0xff) - (b and 0xff))
        return maxOf(da, dr, dg, db)
    }

    /** 变化判定：每通道灰度差**严格大于** tolerance 才算变化（差 1、tolerance=1 → 不算）。 */
    fun isChanged(a: Int, b: Int, tolerance: Int): Boolean = maxChannelDelta(a, b) > tolerance

    fun compare(baseline: Frame, current: Frame, tolerance: Int): Result {
        require(tolerance >= 0) { "tolerance 不能为负" }
        requireSameSize(baseline, current)
        var changed = 0
        var maxDelta = 0
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        for (index in 0 until current.pixelCount) {
            val delta = maxChannelDelta(baseline.pixels[index], current.pixels[index])
            if (delta > maxDelta) maxDelta = delta
            if (delta <= tolerance) continue
            changed++
            val x = index % current.width
            val y = index / current.width
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        val bbox = if (changed == 0) null else intArrayOf(left, top, right + 1, bottom + 1)
        val ratio = if (current.pixelCount == 0) 0.0 else changed.toDouble() / current.pixelCount
        return Result(current.pixelCount, changed, ratio, bbox, maxDelta)
    }

    /** 掩码：变化像素=不透明白，其余=不透明黑；与 [compare] 同一判定（白点数量恒等于 changedPixels）。 */
    fun mask(baseline: Frame, current: Frame, tolerance: Int): IntArray {
        require(tolerance >= 0) { "tolerance 不能为负" }
        requireSameSize(baseline, current)
        val out = IntArray(current.pixelCount)
        for (index in out.indices) {
            out[index] = if (isChanged(baseline.pixels[index], current.pixels[index], tolerance)) MASK_CHANGED else MASK_UNCHANGED
        }
        return out
    }

    sealed class Baseline {
        data class ExplicitPath(val frame: Frame) : Baseline()
        data class PreviousFrame(val frame: Frame) : Baseline()
        object Missing : Baseline()
    }

    /** 基线决策：显式 baseline 优先；否则用进程内上一帧；都没有 → Missing（调用方必须明确失败）。 */
    fun resolveBaseline(explicit: Frame?, previous: Frame?): Baseline = when {
        explicit != null -> Baseline.ExplicitPath(explicit)
        previous != null -> Baseline.PreviousFrame(previous)
        else -> Baseline.Missing
    }

    /**
     * W4 task-28（②）：**先记当前帧，再解析基线**。
     *
     * 顺序是关键：第一次调用本来就没有基线（NO_BASELINE 是设计内的失败），但如果失败路径不记帧，
     * 「连调两次就能 diff」永远不成立（K40 实测 call1 / call2 都 NO_BASELINE）。所以无论有没有基线都先记这一帧。
     */
    fun bootstrapBaseline(explicit: Frame?, previous: Frame?, rememberCurrent: () -> Boolean): Baseline {
        rememberCurrent()
        return resolveBaseline(explicit, previous)
    }

    /**
     * W4 task-28（①a）：当前帧的宽度来源决策 —— 显式 [explicitMaxDims] > **沿用上一帧尺寸** > 截图预设默认。
     *
     * 「沿用上一帧尺寸」是为了让 `capture_screen{mode:"fast"}`（540 宽）之后的 `dev_screen_diff` 不再默认抓全尺寸
     * 而直接 SIZE_MISMATCH；显式给了 maxDims 就以调用方为准（0 = 明确要求不缩放）。
     * 返回 (maxWidth, source)；maxWidth=0 表示不缩放。
     */
    fun resolveMaxWidth(explicitMaxDims: Int?, previousWidth: Int?, presetMaxWidth: Int): Pair<Int, String> = when {
        explicitMaxDims != null -> explicitMaxDims to "explicit_maxDims"
        previousWidth != null && previousWidth > 0 -> previousWidth to "previous_frame"
        presetMaxWidth > 0 -> presetMaxWidth to "preset"
        else -> 0 to "native"
    }

    /** 两个尺寸是否只是等比缩放关系（容差 1%）—— 只有等比时 `maxDims` 才能对齐尺寸。 */
    fun isProportional(a: Frame, b: Frame): Boolean {
        if (a.height == 0 || b.height == 0) return false
        val left = a.width.toDouble() * b.height
        val right = b.width.toDouble() * a.height
        val larger = maxOf(left, right)
        if (larger <= 0.0) return false
        return Math.abs(left - right) / larger <= 0.01
    }

    fun metricsJson(
        result: Result,
        current: Frame,
        baselineSha256: String,
        baselineSource: String,
        tolerance: Int,
        toleranceSource: String,
        mode: String,
    ): JsonObject = ToolkitIo.json(
        "success" to true,
        "mode" to mode,
        "width" to current.width,
        "height" to current.height,
        "size" to ToolkitIo.json("w" to current.width, "h" to current.height),
        "comparedPixels" to result.comparedPixels,
        "changedPixels" to result.changedPixels,
        "ratio" to roundRatio(result.ratio),
        "changed" to (result.changedPixels > 0),
        "bbox" to result.bbox?.let { ToolkitIo.json("x" to it[0], "y" to it[1], "w" to it[2] - it[0], "h" to it[3] - it[1]) },
        "maxChannelDelta" to result.maxChannelDelta,
        "tolerance" to tolerance,
        "toleranceSource" to toleranceSource,
        "frameSha256" to current.sha256,
        "baselineSha256" to baselineSha256,
        "baselineSource" to baselineSource,
        "notes" to listOf(PIXEL_LEVEL_NOTE, "无变化时 bbox=null；bbox 是变化像素的外接矩形，不是变化区域列表。"),
    )

    /**
     * W4 task-28（②）：无基线失败 —— 但**这一帧已经记入帧缓存**，所以再调一次就能拿到 ratio（不再要求先 capture_screen）。
     */
    fun noBaselineFailure(remembered: Boolean = true): JsonObject = failure(
        CODE_NO_BASELINE,
        if (remembered) {
            "没有基线可比：既没有传 baseline，进程内也没有上一帧（本工具与 capture_screen 都只保留最近一帧）。" +
                "**这一帧已记入帧缓存** —— 用同样的参数再调一次就能得到与它的 ratio"
        } else {
            "没有基线可比：既没有传 baseline，进程内也没有上一帧；且这一帧超过像素上限、未记入帧缓存（下次请加 maxDims 或传 baseline）"
        },
        "baseline",
        if (remembered) {
            listOf(
                "再调一次 dev_screen_diff（同一 region/captureMode/maxDims）—— 上一次调用已经把这帧记为上一帧",
                "或显式传 baseline: 脚本目录内的一张 PNG/JPEG（可先用 capture_screen 存一张；两次的尺寸/region 要一致，否则会 SIZE_MISMATCH）",
            )
        } else {
            listOf(
                "加 maxDims 把当前帧缩到像素上限（${MAX_REMEMBERED_PIXELS}）以内，再连调两次",
                "或显式传 baseline: 脚本目录内的一张 PNG/JPEG",
            )
        },
        ToolkitIo.json("frameRemembered" to remembered),
    )

    /**
     * W4 task-28（①c）：尺寸不一致时给出**可直接抄的**补救参数（等比时给 `{maxDims:<基线宽度>}`），
     * 并把 `suggestedArguments` 放进 error.details 供机读；仍**不**静默裁剪/拉伸。
     */
    fun sizeMismatchFailure(baseline: Frame, current: Frame): JsonObject {
        val proportional = isProportional(baseline, current)
        val copyable = if (proportional) "dev_screen_diff{maxDims:${baseline.width}}" else null
        val actions = ArrayList<String>()
        if (copyable != null) {
            actions.add("按基线宽度缩小当前帧再比：$copyable（region/captureMode 也要与基线那次一致）")
        }
        actions.add("或让基线跟着当前帧走：不要另存缩放过的截图当 baseline，直接用「上一帧」模式（同一 region/captureMode 连调两次）")
        if (!proportional) {
            actions.add("两个尺寸不是等比关系（宽高比不同），maxDims 对不齐；请用同一个 region 重新抓基线")
        }
        val details = ToolkitIo.json(
            "baselineSize" to ToolkitIo.json("w" to baseline.width, "h" to baseline.height),
            "currentSize" to ToolkitIo.json("w" to current.width, "h" to current.height),
            "proportional" to proportional,
        )
        if (copyable != null) {
            details.add("suggestedArguments", ToolkitIo.json("maxDims" to baseline.width))
            details.addProperty("copyableCommand", copyable)
        }
        return failure(
            CODE_SIZE_MISMATCH,
            "基线帧 ${baseline.width}x${baseline.height} 与当前帧 ${current.width}x${current.height} 尺寸不一致；本工具不裁剪/不缩放，避免给出假的低 ratio" +
                (if (copyable != null) "（等比关系，可用 $copyable 对齐）" else ""),
            "baseline",
            actions,
            details,
        )
    }

    fun baselineUnreadableFailure(path: String, reason: String): JsonObject = failure(
        CODE_BASELINE_UNREADABLE,
        "baseline 读不出像素：$reason（$path）",
        "baseline",
        listOf(
            "baseline 用 capture_screen 的 savePath 产物（PNG/JPEG）；saveFormat=raw 的原始像素文件不可直接 decode",
            "或删掉 baseline 参数，改用「上一帧」模式（先跑一次 capture_screen）",
        ),
    )

    fun invalidArgumentFailure(message: String, argument: String?): JsonObject = failure(
        ToolkitScreenDiff.CODE_INVALID_ARGUMENT, message, argument, emptyList())

    /** 与项目其它工具同一套失败信封：content[0].text 是 JSON 信封 + isError + 顶层 errorCode。 */
    fun failure(code: String, message: String, argument: String?, nextActions: List<String>, details: JsonObject? = null): JsonObject {
        val error = ToolkitIo.json(
            "code" to code,
            "message" to message,
            "argument" to argument,
            "recoverable" to true,
            "retrySameArguments" to false,
        )
        details?.let { error.add("details", it) }
        val envelope = ToolkitIo.json(
            "ok" to false,
            "data" to null,
            "error" to error,
            "nextActions" to nextActions,
        )
        return JsonObject().apply {
            add("content", com.google.gson.JsonArray().apply { add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", envelope.toString())
            }) })
            addProperty("isError", true)
            addProperty("errorCode", code)
            if (argument != null) addProperty("argument", argument)
            add("structuredContent", envelope)
        }
    }

    /** ratio 取 6 位小数：避免把 0.14285714285714285 这种浮点噪声直接回给调用方。 */
    fun roundRatio(ratio: Double): Double = Math.round(ratio * 1_000_000.0) / 1_000_000.0

    private fun requireSameSize(baseline: Frame, current: Frame) {
        if (baseline.width != current.width || baseline.height != current.height) {
            throw SizeMismatchException(baseline.width, baseline.height, current.width, current.height)
        }
    }

    private fun sha256OfPixels(pixels: IntArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(4)
        for (pixel in pixels) {
            buffer[0] = (pixel ushr 24 and 0xff).toByte()
            buffer[1] = (pixel ushr 16 and 0xff).toByte()
            buffer[2] = (pixel ushr 8 and 0xff).toByte()
            buffer[3] = (pixel and 0xff).toByte()
            digest.update(buffer)
        }
        return digest.digest().joinToString("") { byte -> String.format("%02x", byte) }
    }
}

/**
 * W4 task-26：进程内**只保留一帧**的帧缓存（`dev_screen_diff` 与 `capture_screen` 共用）。
 * 不落盘、不缓存编码图，只存原始像素 + sha256；超过 [ToolkitScreenDiff.MAX_REMEMBERED_PIXELS] 的帧不记（返回 false，由调用方在返回里说明）。
 */
internal object ToolkitScreenFrameStore {
    data class Stored(
        val frame: ToolkitScreenDiff.Frame,
        val capturedAt: Long,
        val source: String,
        val format: String,
    )

    private var last: Stored? = null

    @Synchronized
    fun remember(frame: ToolkitScreenDiff.Frame, source: String, format: String, now: Long = System.currentTimeMillis()): Boolean {
        if (frame.pixelCount > ToolkitScreenDiff.MAX_REMEMBERED_PIXELS) return false
        last = Stored(frame, now, source, format)
        return true
    }

    @Synchronized
    fun lastOrNull(): Stored? = last

    @Synchronized
    fun frameOrNull(): ToolkitScreenDiff.Frame? = last?.frame

    @Synchronized
    fun clear() {
        last = null
    }
}
