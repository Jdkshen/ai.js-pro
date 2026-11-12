package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-1 A 类归一的「原值核对」钉子：McpDeviceTools 的 4 处用户参数夹紧改走 McpArgClamp，
 * 但**上下限必须逐字照抄原值**（1..10000 / 1..600 / 0..2000 ×2）—— 不许统一成一个数。
 * B 类（坐标/偏移/剩余时间等运行时尺寸夹紧）**不得**被改（本文件里就是 :232-235 那四处坐标裁剪）。
 */
class McpDeviceToolsClampPinTest {
    private fun device(): String = File("src/main/java/com/jdkshen/aijspro/mcp/McpDeviceTools.kt").readText()

    @Test fun adoptedClampsKeepTheirOriginalBounds() {
        val text = device()
        assertTrue("count 必须仍是 1..10000", text.contains("McpArgClamp.clampInt(count, count, 1, 10000).value"))
        assertTrue("timeoutSeconds 必须仍是 1..600", text.contains("McpArgClamp.clampInt(timeoutSeconds, timeoutSeconds, 1, 600).value"))
        assertTrue("intervalMs 必须仍是 0..2000（两处）", Regex("McpArgClamp\\.clampInt\\(intervalMs, intervalMs, 0, 2000\\)\\.value").findAll(text).count() == 2)
    }

    @Test fun geometryClampsRemainUntouched() {
        val text = device()
        assertTrue("坐标裁剪必须保持原样（依据是位图尺寸，不是固定上限）",
            text.contains("coerceIn(0, bitmap.width - 1)") && text.contains("coerceIn(0, bitmap.height - 1)") &&
                text.contains("coerceIn(it.left + 1, bitmap.width)") && text.contains("coerceIn(it.top + 1, bitmap.height)"))
    }
}
