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

    /**
     * 钉「夹紧边界没被放宽」，而不是钉某一行字怎么写。
     * 2026-11-13 更新：这三处改成先存 ClampResult（好在信封里回填 clamped），不再以 .value 结尾；
     * 原断言钉的是"写法字符串"，改完就误报了。这里改成钉边界数值 + 出现次数。
     */
    @Test fun adoptedClampsKeepTheirOriginalBounds() {
        val text = device()
        assertTrue("count 必须仍是 1..10000", text.contains("McpArgClamp.clampInt(count, count, 1, 10000)"))
        assertTrue("timeoutSeconds 必须仍是 1..600", text.contains("McpArgClamp.clampInt(timeoutSeconds, timeoutSeconds, 1, 600)"))
        // intervalMs 只算一次、后面复用（原来写了两次同一表达式）
        val intervalCalls = Regex("McpArgClamp\\.clampInt\\(intervalMs, intervalMs, 0, 2000\\)").findAll(text).count()
        assertTrue("intervalMs 必须仍是 0..2000 且只算一次（复用 ClampResult）", intervalCalls == 1 && text.contains("intervalClamp.value"))
    }

    /**
     * P3-1 余项钉子：夹紧信息必须进**信封顶层**，不能只写日志（消费方看不到日志）。
     * 同时给出 canSeeWhich：clamps[] 里逐条带 argument/requested/effective。
     */
    @Test fun clampingIsSurfacedInTheEnvelope() {
        val text = device()
        assertTrue("信封顶层要有 clamped 布尔", text.contains("\"clamped\" to (countClamp.clamped || timeoutClamp.clamped || intervalClamp.clamped)"))
        assertTrue("被夹紧的项要逐条列出（argument/requested/effective）",
            text.contains("\"clamps\" to JsonArray().apply") &&
                text.contains("\"argument\" to name") &&
                text.contains("\"requested\" to r.requested") &&
                text.contains("\"effective\" to r.effective"))
    }

    @Test fun geometryClampsRemainUntouched() {
        val text = device()
        assertTrue("坐标裁剪必须保持原样（依据是位图尺寸，不是固定上限）",
            text.contains("coerceIn(0, bitmap.width - 1)") && text.contains("coerceIn(0, bitmap.height - 1)") &&
                text.contains("coerceIn(it.left + 1, bitmap.width)") && text.contains("coerceIn(it.top + 1, bitmap.height)"))
    }
}
