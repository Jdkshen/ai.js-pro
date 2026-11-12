package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-1 1b：既有领域码**登记 + 钉住**（不改码值）。
 *  · 4 个既有码必须仍在 McpTools.structuredCall 里（防被悄悄改名 ⇒ Dart 侧判断失配）；
 *  · 新增的 internal 兜底必须存在且带 Log.e。
 */
class McpExistingFailureCodesPinTest {
    private fun tools(): String {
        val file = File("src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt")
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun existingDomainCodesAreUnchanged() {
        val text = tools()
        for (code in listOf("TOOL_FAILED", "WORKSPACE_STATE_CONFLICT", "PERMISSION_DENIED", "IO_ERROR")) {
            assertTrue("既有领域码必须原样保留（只增不删）：" + code, text.contains("\"" + code + "\""))
        }
    }

    @Test fun permissionDeniedIsTheOnlyEquivalence() {
        val text = tools()
        // 只登记、不映射：PERMISSION_DENIED 仍是原码值；本轮不得出现把既有码替换成骨架码的写法。
        assertTrue("PERMISSION_DENIED 不得被改成 permission", text.contains("\"PERMISSION_DENIED\""))
        assertTrue("IO_ERROR 不得被改成 network", text.contains("\"IO_ERROR\""))
    }
}
