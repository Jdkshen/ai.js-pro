package com.jdkshen.aijspro.mcp

/**
 * `capture_screen` 的三种预设：一个**质量好**、一个**快速**，外加保持现状的 balanced。
 *
 * 纯逻辑（可 JVM 单测）：调用方显式传的 format/quality/maxWidth/timeoutMillis **始终覆盖**预设，
 * 所以老调用不受影响；预设只决定「没传的那些参数」。
 */
internal object McpCapturePreset {
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

    fun isKnownMode(mode: String): Boolean = mode in setOf(MODE_FAST, MODE_BALANCED, MODE_QUALITY)

    fun resolve(
        mode: String,
        format: String? = null,
        quality: Int? = null,
        maxWidth: Int? = null,
        timeoutMillis: Int? = null,
    ): Resolved {
        val normalized = if (isKnownMode(mode)) mode else MODE_BALANCED
        val preset = when (normalized) {
            // 快速：缩到 540 宽 + JPEG 60 —— 编码最快、字节最小，适合「先看一眼/定位/轮询」。
            MODE_FAST -> Resolved(normalized, "jpg", 60, 540, 2000,
                "fast：540 宽 + JPEG60，最快最小，细节会糊一点（坐标请按 sourceWidth/scale 换算）")
            // 质量：原图 + PNG（无损）+ 更长超时，适合存档/取证/像素级比对。
            MODE_QUALITY -> Resolved(normalized, "png", 100, 0, 6000,
                "quality：原图 PNG 无损，最清晰也最大最慢（16MiB 上限，必要时自己加 maxWidth）")
            else -> Resolved(normalized, "png", 100, 0, 3000,
                "balanced：原图 PNG ——**与历史默认完全一致**（兼容保留）；要快要小用 mode=fast，" +
                    "要更长超时/更明确的质量语义用 mode=quality（输出同样是原图 PNG）")
        }
        return preset.copy(
            format = format?.lowercase() ?: preset.format,
            quality = quality ?: preset.quality,
            maxWidth = maxWidth ?: preset.maxWidth,
            timeoutMillis = timeoutMillis ?: preset.timeoutMillis,
        )
    }
}
