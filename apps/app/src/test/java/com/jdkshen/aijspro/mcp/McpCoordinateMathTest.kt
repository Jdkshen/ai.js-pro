package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/** 坐标换算的纯数学：一点（位移）、两点（缩放+位移）、整块尺寸比。 */
class McpCoordinateMathTest {
    @Test
    fun onePointGivesTranslationOnly() {
        val affine = McpCoordinateMath.fromOnePoint(10.0, 20.0, 162.0, 20.0)
        assertEquals(1.0, affine.scaleX, 1e-9)
        assertEquals(1.0, affine.scaleY, 1e-9)
        assertEquals(152.0, affine.offsetX, 1e-9)
        assertEquals(0.0, affine.offsetY, 1e-9)
        assertEquals("translation_only", affine.source)
    }

    @Test
    fun twoPointsSolveScaleAndOffsetLikeThePokerFeedback() {
        // 反馈里实测标定出来的关系：截图 = 浮窗 × (1.0000, 1.0009) + (152, 0)
        val affine = McpCoordinateMath.fromTwoPoints(
            0.0, 0.0, 152.0, 0.0,
            1000.0, 1000.0, 1152.0, 1000.9,
        )
        assertEquals(1.0, affine.scaleX, 1e-6)
        assertEquals(1.0009, affine.scaleY, 1e-6)
        assertEquals(152.0, affine.offsetX, 1e-6)
        assertEquals(0.0, affine.offsetY, 1e-6)
        val (x, y) = affine.apply(2620.0, 1215.0)
        assertEquals(2772.0, x, 1e-3)
        assertEquals(1216.09, y, 1e-2)
    }

    @Test
    fun degenerateAxisFallsBackToTranslation() {
        // X 轴上两点同值（退化）→ 该轴只能给位移；Y 轴正常求缩放。
        val affine = McpCoordinateMath.fromTwoPoints(5.0, 0.0, 10.0, 0.0, 5.0, 100.0, 10.0, 200.0)
        assertEquals(1.0, affine.scaleX, 1e-9)
        assertEquals(5.0, affine.offsetX, 1e-9)
        assertEquals(2.0, affine.scaleY, 1e-9)
        assertEquals(0.0, affine.offsetY, 1e-9)
    }

    @Test
    fun sizeRatioMapsWholeSpaces() {
        val affine = McpCoordinateMath.fromSizes(2620, 1215, 2772, 1280)
        assertEquals(2772.0 / 2620.0, affine.scaleX, 1e-9)
        assertEquals(1280.0 / 1215.0, affine.scaleY, 1e-9)
        assertEquals(0.0, affine.offsetX, 1e-9)
    }

    @Test
    fun roundingIsHalfUpOnPositives() {
        assertEquals(3, McpCoordinateMath.round(2.5))
        assertEquals(2, McpCoordinateMath.round(2.4))
        assertEquals(0, McpCoordinateMath.round(0.0))
    }
}
