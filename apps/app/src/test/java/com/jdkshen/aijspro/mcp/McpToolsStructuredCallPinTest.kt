package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-1 1b-② 的源码级钉子（离线可跑）：structuredCall 的兜底顺序与三态硬要求。
 *  · internal 兜底存在，且 message 带异常类名 + Log.e；
 *  · 协议层 McpInvalidParamsException 必须先于 internal 被**原样抛出**（不被改写）；
 *  · 不新造第二个失败标志（沿用既有 toolFailure 通道）。
 */
class McpToolsStructuredCallPinTest {
    private fun tools(): String {
        val file = File("src/main/java/com/jdkshen/aijspro/mcp/McpTools.kt")
        assertTrue("找不到 " + file.absolutePath, file.isFile)
        return file.readText()
    }

    @Test fun internalFallbackIsLastAndNamesTheException() {
        val body = tools().substringAfter("private inline fun structuredCall").substringBefore("private fun workspaceJson")
        assertTrue("必须有 internal 兜底", body.contains("catch (failure: Throwable)"))
        assertTrue("兜底必须 Log.e（不静默）", body.contains("android.util.Log.e("))
        assertTrue("message 必须带异常类名（simpleName）", body.contains("failure.javaClass.simpleName"))
        assertTrue("必须走既有失败通道 toolFailure（不新造第二套）", body.contains("toolFailure("))
        assertTrue("reasonCode 必须是 internal", body.contains("\"internal\""))
    }

    /** P3-1：业务参数错（McpToolArgException）必须映射 invalidArg，且**不得**用宽 catch IllegalArgumentException。 */
    @Test fun toolArgExceptionMapsToInvalidArgWithoutBroadCatch() {
        val body = tools().substringAfter("private inline fun structuredCall").substringBefore("private fun workspaceJson")
        val protocolAt = body.indexOf("catch (protocol: McpInvalidParamsException)")
        val argAt = body.indexOf("catch (failure: McpToolArgException)")
        val internalAt = body.indexOf("catch (failure: Throwable)")
        assertTrue("顺序必须是 协议层 → invalidArg → internal", protocolAt in 1 until argAt && argAt < internalAt)
        assertTrue("业务参数错必须映射 invalidArg", body.contains("\"invalidArg\""))
        assertTrue("不得用宽 catch IllegalArgumentException（会把内部不变量误标成参数错）",
            !body.contains("catch (failure: IllegalArgumentException)"))
    }

    @Test fun protocolExceptionBubblesBeforeInternal() {
        val body = tools().substringAfter("private inline fun structuredCall").substringBefore("private fun workspaceJson")
        val protocolAt = body.indexOf("catch (protocol: McpInvalidParamsException)")
        val internalAt = body.indexOf("catch (failure: Throwable)")
        assertTrue("协议层 catch 必须在 internal 之前", protocolAt in 1 until internalAt)
        assertTrue("协议层必须原样抛（throw protocol）", body.contains("throw protocol"))
    }
}
