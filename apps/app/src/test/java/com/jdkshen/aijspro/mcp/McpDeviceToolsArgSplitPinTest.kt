package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-1：McpDeviceTools 的「参数校验 vs 内部不变量」分离钉子（本批只改参数校验那几处）。
 *  · 业务参数校验 ⇒ McpToolArgException（⇒ invalidArg）✓
 *  · 内部不变量/编码失败 ⇒ 保持 check(...)（⇒ 既有 WORKSPACE_STATE_CONFLICT/内部路径）✗ **不得**被标成 invalidArg
 */
class McpDeviceToolsArgSplitPinTest {
    private fun device(): String {
        val file = File("src/main/java/com/jdkshen/aijspro/mcp/McpDeviceTools.kt")
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun businessArgChecksUseTheDedicatedException() {
        val text = device()
        assertTrue("mode 校验必须走 McpToolArgException", text.contains("throw McpToolArgException(\"mode 必须是"))
        assertTrue("format 校验必须走 McpToolArgException", text.contains("throw McpToolArgException(\"format 必须是"))
        assertTrue("截图过大（用户可用 jpg/maxWidth 修）也必须走 McpToolArgException",
            text.contains("throw McpToolArgException(\"截图过大"))
    }

    @Test fun internalInvariantsAreNotReclassified() {
        val text = device()
        // 编码失败不是用户参数问题 ⇒ 必须保持 check(...)（否则就是我们在把内部错误误标成参数错）
        assertTrue("截图编码失败必须保持 check(...)", text.contains("check(output.compress("))
        assertTrue("掩码编码失败必须保持 check(...)", text.contains("check(maskBitmap.compress("))
        assertTrue("内部编码路径不得被改成 McpToolArgException",
            !text.contains("throw McpToolArgException(\"截图编码失败") && !text.contains("throw McpToolArgException(\"掩码 PNG 编码失败"))
    }
}
