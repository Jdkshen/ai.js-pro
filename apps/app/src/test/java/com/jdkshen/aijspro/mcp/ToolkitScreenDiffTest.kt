package com.jdkshen.aijspro.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays

/**
 * W4 task-26：`dev_screen_diff` 的纯比较核心单测（不依赖 Android）。
 *
 * 覆盖：相同图为 0、改一块矩形 bbox 精确、容差边界、尺寸不一致的明确行为、无基线失败、掩码像素数与 changedPixels 一致。
 */
class ToolkitScreenDiffTest {

    @After
    fun clearFrameStore() {
        ToolkitScreenFrameStore.clear()
    }

    private fun frame(width: Int, height: Int, fill: Int = 0xFF102030.toInt()): ToolkitScreenDiff.Frame {
        val pixels = IntArray(width * height)
        Arrays.fill(pixels, fill)
        return ToolkitScreenDiff.Frame(width, height, pixels)
    }

    private fun fillRect(frame: ToolkitScreenDiff.Frame, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top until bottom) {
            for (x in left until right) frame.pixels[y * frame.width + x] = color
        }
    }

    @Test
    fun identicalFramesReportNoChange() {
        val baseline = frame(16, 8)
        val current = frame(16, 8)
        val result = ToolkitScreenDiff.compare(baseline, current, 0)
        assertEquals(0, result.changedPixels)
        assertEquals(128, result.comparedPixels)
        assertEquals(0.0, result.ratio, 0.0)
        assertNull("无变化时 bbox 必须是 null", result.bbox)
        assertEquals(0, result.maxChannelDelta)
        assertEquals(baseline.sha256, current.sha256)
    }

    @Test
    fun changedRectangleReportsExactBboxAndRatio() {
        val baseline = frame(16, 8)
        val current = frame(16, 8)
        // 改动 x=2..4（不含 5）、y=1..2（不含 3）→ 3x2 = 6 像素
        fillRect(current, 2, 1, 5, 3, 0xFFFFFFFF.toInt())
        val result = ToolkitScreenDiff.compare(baseline, current, 0)
        assertEquals(6, result.changedPixels)
        assertEquals(6.0 / 128.0, result.ratio, 1e-9)
        assertNotNull(result.bbox)
        assertEquals(2, result.bbox!![0])
        assertEquals(1, result.bbox!![1])
        assertEquals(5, result.bbox!![2])
        assertEquals(3, result.bbox!![3])
        // JSON 里是 {x,y,w,h}
        val json = ToolkitScreenDiff.metricsJson(result, current, baseline.sha256, "previous_frame", 0, "lossless_default(0)", "bbox")
        val bbox = json.getAsJsonObject("bbox")
        assertEquals(2, bbox.get("x").asInt)
        assertEquals(1, bbox.get("y").asInt)
        assertEquals(3, bbox.get("w").asInt)
        assertEquals(2, bbox.get("h").asInt)
        assertEquals(6, json.get("changedPixels").asInt)
    }

    @Test
    fun toleranceBoundaryIsStrictlyGreaterThan() {
        val baseline = frame(4, 1, 0xFF101010.toInt())
        val current = frame(4, 1, 0xFF101010.toInt())
        current.pixels[0] = 0xFF111111.toInt() // 每个通道差 1
        val zero = ToolkitScreenDiff.compare(baseline, current, 0)
        assertEquals("tolerance=0 时差 1 灰阶要计入", 1, zero.changedPixels)
        assertEquals(1, zero.maxChannelDelta)
        val one = ToolkitScreenDiff.compare(baseline, current, 1)
        assertEquals("tolerance=1 时差 1 灰阶不计入", 0, one.changedPixels)
        assertNull(one.bbox)
        assertEquals("maxChannelDelta 仍如实报告实际差值", 1, one.maxChannelDelta)
        // 差 2、tolerance=1 → 计入
        current.pixels[1] = 0xFF121212.toInt()
        assertEquals(1, ToolkitScreenDiff.compare(baseline, current, 1).changedPixels)
        // alpha 变化同样算（每通道含 alpha）
        val alpha = frame(1, 1, 0xFF000000.toInt())
        val alphaChanged = frame(1, 1, 0xFE000000.toInt())
        assertEquals(1, ToolkitScreenDiff.compare(alpha, alphaChanged, 0).changedPixels)
    }

    @Test
    fun sizeMismatchIsExplicitlyRejected() {
        val baseline = frame(8, 4)
        val current = frame(16, 8)
        val error = runCatching { ToolkitScreenDiff.compare(baseline, current, 0) }.exceptionOrNull()
        assertTrue("尺寸不一致必须抛 SizeMismatchException，实际 " + error, error is ToolkitScreenDiff.SizeMismatchException)
        val mismatch = error as ToolkitScreenDiff.SizeMismatchException
        assertEquals(8, mismatch.baselineWidth)
        assertEquals(4, mismatch.baselineHeight)
        assertEquals(16, mismatch.currentWidth)
        assertEquals(8, mismatch.currentHeight)
        // 工具层给结构化失败 + 可执行 nextActions（不裁剪/不缩放）
        val failure = ToolkitScreenDiff.sizeMismatchFailure(baseline, current)
        assertEquals(true, failure.get("isError").asBoolean)
        assertEquals(ToolkitScreenDiff.CODE_SIZE_MISMATCH, failure.get("errorCode").asString)
        val envelope = failure.getAsJsonObject("structuredContent")
        assertEquals(false, envelope.get("ok").asBoolean)
        assertEquals(ToolkitScreenDiff.CODE_SIZE_MISMATCH, envelope.getAsJsonObject("error").get("code").asString)
        assertEquals(2, envelope.getAsJsonArray("nextActions").size())
        val details = envelope.getAsJsonObject("error").getAsJsonObject("details")
        assertEquals(8, details.getAsJsonObject("baselineSize").get("w").asInt)
        assertEquals(16, details.getAsJsonObject("currentSize").get("w").asInt)
        // mask 同样拒绝（不能悄悄截断到较小尺寸）
        assertTrue(runCatching { ToolkitScreenDiff.mask(baseline, current, 0) }.exceptionOrNull() is ToolkitScreenDiff.SizeMismatchException)
    }

    @Test
    fun missingBaselineFailsInsteadOfUsingABlackFrame() {
        assertTrue(ToolkitScreenDiff.resolveBaseline(null, null) is ToolkitScreenDiff.Baseline.Missing)
        val failure = ToolkitScreenDiff.noBaselineFailure()
        assertEquals(true, failure.get("isError").asBoolean)
        assertEquals(ToolkitScreenDiff.CODE_NO_BASELINE, failure.get("errorCode").asString)
        val envelope = failure.getAsJsonObject("structuredContent")
        assertEquals(ToolkitScreenDiff.CODE_NO_BASELINE, envelope.getAsJsonObject("error").get("code").asString)
        assertEquals("baseline", envelope.getAsJsonObject("error").get("argument").asString)
        val actions = envelope.getAsJsonArray("nextActions")
        assertEquals(2, actions.size())
        // task-28：第一次失败但已记帧，所以第一条 nextAction 是「再调一次」（不再要求先 capture_screen）
        assertTrue(actions[0].asString.contains("再调一次"))
        assertEquals(true, envelope.getAsJsonObject("error").getAsJsonObject("details").get("frameRemembered").asBoolean)
        // 没记进缓存时（超像素上限）说法要不一样
        val unremembered = ToolkitScreenDiff.noBaselineFailure(false)
            .getAsJsonObject("structuredContent")
        assertEquals(false, unremembered.getAsJsonObject("error").getAsJsonObject("details").get("frameRemembered").asBoolean)
        assertTrue(unremembered.getAsJsonArray("nextActions")[0].asString.contains("maxDims"))
        // 有上一帧就不再是 Missing；显式 baseline 优先
        val previous = frame(4, 4)
        val explicit = frame(4, 4, 0xFF00FF00.toInt())
        assertTrue(ToolkitScreenDiff.resolveBaseline(null, previous) is ToolkitScreenDiff.Baseline.PreviousFrame)
        val resolved = ToolkitScreenDiff.resolveBaseline(explicit, previous)
        assertTrue(resolved is ToolkitScreenDiff.Baseline.ExplicitPath)
        assertTrue((resolved as ToolkitScreenDiff.Baseline.ExplicitPath).frame === explicit)
    }

    /**
     * W4 task-28（②）：第一次调用虽然 NO_BASELINE，但帧必须已进缓存，第二次才能拿到 previous_frame 基线。
     * 这是 K40 实测的失败场景（call1/call2 都 NO_BASELINE）的回归钉子。
     */
    @Test
    fun firstCallRemembersTheFrameSoTheSecondCallHasABaseline() {
        ToolkitScreenFrameStore.clear()
        val first = frame(8, 8)
        val firstResolution = ToolkitScreenDiff.bootstrapBaseline(null, ToolkitScreenFrameStore.frameOrNull()) {
            ToolkitScreenFrameStore.remember(first, "dev_screen_diff", "png")
        }
        assertTrue("第一次没有基线就该 Missing", firstResolution is ToolkitScreenDiff.Baseline.Missing)
        assertTrue("第一次失败也必须记住这一帧", ToolkitScreenFrameStore.frameOrNull() === first)
        // 第二次：上一帧就是第一次那一帧 → PreviousFrame，ratio 可算
        val second = frame(8, 8, 0xFF00FF00.toInt())
        val secondResolution = ToolkitScreenDiff.bootstrapBaseline(null, ToolkitScreenFrameStore.frameOrNull()) {
            ToolkitScreenFrameStore.remember(second, "dev_screen_diff", "png")
        }
        assertTrue(secondResolution is ToolkitScreenDiff.Baseline.PreviousFrame)
        assertEquals(first.sha256, (secondResolution as ToolkitScreenDiff.Baseline.PreviousFrame).frame.sha256)
        assertEquals(64, ToolkitScreenDiff.compare(first, second, 0).changedPixels)
        assertTrue("第二帧要覆盖第一帧（仍然只留一帧）", ToolkitScreenFrameStore.frameOrNull() === second)
    }

    /**
     * W4 task-28（①a）：显式 maxDims > 沿用上一帧宽度 > 预设默认；并验证「capture_screen{fast} 540 宽 + 本工具沿用 540」能对上尺寸。
     */
    @Test
    fun previousFrameWidthIsReusedSoPresetCaptureDoesNotMismatch() {
        assertEquals(540 to "previous_frame", ToolkitScreenDiff.resolveMaxWidth(null, 540, 0))
        assertEquals(360 to "previous_frame", ToolkitScreenDiff.resolveMaxWidth(null, 360, 540))
        assertEquals(720 to "explicit_maxDims", ToolkitScreenDiff.resolveMaxWidth(720, 540, 0))
        assertEquals(0 to "explicit_maxDims", ToolkitScreenDiff.resolveMaxWidth(0, 540, 0))
        assertEquals(540 to "preset", ToolkitScreenDiff.resolveMaxWidth(null, null, 540))
        assertEquals(0 to "native", ToolkitScreenDiff.resolveMaxWidth(null, null, 0))
        // 场景：capture_screen{mode:"fast"} 的 540x960 基线 + 按上一帧宽度缩到 540 的当前帧 → 尺寸一致、可比
        val baseline = frame(540, 960, 0xFF101010.toInt())
        val current = frame(540, 960, 0xFF101010.toInt())
        assertTrue(ToolkitScreenDiff.isProportional(frame(720, 1280), baseline))
        assertEquals(0, ToolkitScreenDiff.compare(baseline, current, 0).changedPixels)
    }

    /**
     * W4 task-28（①c）：SIZE_MISMATCH 必须给**可抄**的补救参数（等比时给 {maxDims:<基线宽度>}），且不静默裁剪/拉伸。
     */
    @Test
    fun sizeMismatchSuggestsCopyableRemedy() {
        val baseline = frame(540, 960)
        val current = frame(720, 1280)
        val failure = ToolkitScreenDiff.sizeMismatchFailure(baseline, current)
        val envelope = failure.getAsJsonObject("structuredContent")
        assertEquals(ToolkitScreenDiff.CODE_SIZE_MISMATCH, envelope.getAsJsonObject("error").get("code").asString)
        val details = envelope.getAsJsonObject("error").getAsJsonObject("details")
        assertEquals(true, details.get("proportional").asBoolean)
        assertEquals(540, details.getAsJsonObject("suggestedArguments").get("maxDims").asInt)
        assertEquals("dev_screen_diff{maxDims:540}", details.get("copyableCommand").asString)
        assertEquals(540, details.getAsJsonObject("baselineSize").get("w").asInt)
        assertEquals(720, details.getAsJsonObject("currentSize").get("w").asInt)
        val actions = envelope.getAsJsonArray("nextActions")
        assertTrue("第一条 nextAction 要能直接抄", actions[0].asString.contains("dev_screen_diff{maxDims:540}"))
        // 宽高比不同 → 不能用 maxDims 对齐，必须重新抓基线（并说明原因）
        val odd = frame(720, 720)
        val oddFailure = ToolkitScreenDiff.sizeMismatchFailure(baseline, odd)
            .getAsJsonObject("structuredContent")
        val oddDetails = oddFailure.getAsJsonObject("error").getAsJsonObject("details")
        assertEquals(false, oddDetails.get("proportional").asBoolean)
        assertFalse(oddDetails.has("suggestedArguments"))
        assertFalse(oddDetails.has("copyableCommand"))
        assertTrue(oddFailure.getAsJsonArray("nextActions").toString().contains("等比"))
    }

    @Test
    fun maskWhitesMatchChangedPixelsExactly() {
        val baseline = frame(10, 5)
        val current = frame(10, 5)
        fillRect(current, 1, 1, 4, 4, 0xFFFFFFFF.toInt())
        current.pixels[9] = 0xFF000000.toInt()
        val result = ToolkitScreenDiff.compare(baseline, current, 0)
        val mask = ToolkitScreenDiff.mask(baseline, current, 0)
        assertEquals(baseline.pixelCount, mask.size)
        assertEquals(result.changedPixels, mask.count { it == ToolkitScreenDiff.MASK_CHANGED })
        assertEquals(ToolkitScreenDiff.MASK_UNCHANGED, mask[0])
        assertEquals(ToolkitScreenDiff.MASK_CHANGED, mask[1 * 10 + 1])
        // 容差同样作用于掩码：差 1 灰阶在 tolerance=1 下掩码应全黑
        val near = frame(10, 5)
        near.pixels[0] = 0xFF102031.toInt()
        assertEquals(0, ToolkitScreenDiff.mask(baseline, near, 1).count { it == ToolkitScreenDiff.MASK_CHANGED })
        assertEquals(1, ToolkitScreenDiff.mask(baseline, near, 0).count { it == ToolkitScreenDiff.MASK_CHANGED })
    }

    @Test
    fun metricsJsonCarriesTheHonestNotesAndRoundedRatio() {
        val baseline = frame(3, 3)
        val current = frame(3, 3)
        current.pixels[0] = 0xFFFFFFFF.toInt()
        val result = ToolkitScreenDiff.compare(baseline, current, 0)
        val json = ToolkitScreenDiff.metricsJson(result, current, baseline.sha256, "explicit_path", 0, "explicit", "mask")
        assertEquals(1, json.get("changedPixels").asInt)
        assertEquals(ToolkitScreenDiff.roundRatio(1.0 / 9.0), json.get("ratio").asDouble, 0.0)
        assertEquals(true, json.get("changed").asBoolean)
        assertEquals(baseline.sha256, json.get("baselineSha256").asString)
        assertEquals(current.sha256, json.get("frameSha256").asString)
        assertEquals("explicit_path", json.get("baselineSource").asString)
        assertEquals("mask", json.get("mode").asString)
        assertEquals(3, json.getAsJsonObject("size").get("w").asInt)
        val notes = json.getAsJsonArray("notes")[0].asString
        assertTrue(notes.contains("像素级"))
        assertTrue(notes.contains("不代表界面变化程度"))
        // 无变化的帧：bbox=null（Gson 序列化时给 JsonNull）
        val same = ToolkitScreenDiff.compare(baseline, frame(3, 3), 0)
        val sameJson = ToolkitScreenDiff.metricsJson(same, frame(3, 3), baseline.sha256, "previous_frame", 0, "explicit", "count")
        assertTrue(sameJson.get("bbox").isJsonNull)
        assertEquals(0.0, sameJson.get("ratio").asDouble, 0.0)
        assertFalse(sameJson.get("changed").asBoolean)
    }

    @Test
    fun frameStoreKeepsExactlyOneFrameAndEnforcesThePixelCap() {
        assertNull(ToolkitScreenFrameStore.frameOrNull())
        val first = frame(4, 4)
        assertTrue(ToolkitScreenFrameStore.remember(first, "capture_screen", "png"))
        assertTrue(ToolkitScreenFrameStore.frameOrNull() === first)
        assertEquals("capture_screen", ToolkitScreenFrameStore.lastOrNull()!!.source)
        val second = frame(4, 4, 0xFF00FF00.toInt())
        assertTrue(ToolkitScreenFrameStore.remember(second, "dev_screen_diff", "png"))
        assertTrue("只保留一帧：新帧必须覆盖旧帧", ToolkitScreenFrameStore.frameOrNull() === second)
        // 超过 4M 像素的帧不记（返回 false，调用方据此在返回里说明）
        val tooBig = ToolkitScreenDiff.Frame(2001, 2000, IntArray(2001 * 2000))
        assertFalse(ToolkitScreenFrameStore.remember(tooBig, "dev_screen_diff", "png"))
        assertTrue(ToolkitScreenFrameStore.frameOrNull() === second)
        ToolkitScreenFrameStore.clear()
        assertNull(ToolkitScreenFrameStore.frameOrNull())
    }
}
