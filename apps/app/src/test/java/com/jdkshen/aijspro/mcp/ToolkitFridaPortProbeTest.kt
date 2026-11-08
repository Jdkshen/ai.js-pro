package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * [ToolkitFridaPortProbe] 纯 JVM 单测（设备4 Bug3 ★①：端口探测不再依赖 /proc/net/tcp）。
 *
 * 覆盖：
 * - 四态分类（注入假连接器，不碰网络）：listening_reachable / refused / timeout / error:<类名>；
 * - **超时与拒绝必须分开**：SocketTimeoutException=timeout，ConnectException("Connection refused")=refused，
 *   ConnectException("Connection timed out")=timeout（ETIMEDOUT 的平台差异）；
 * - timeoutMs 边界夹取、endpoint 解析与非法 endpoint；
 * - 两例**真连接**：本机 ServerSocket → listening_reachable；刚关闭的端口 → refused。
 */
class ToolkitFridaPortProbeTest {

    private fun failing(error: Exception?): ToolkitFridaPortProbe.Connector =
        ToolkitFridaPortProbe.Connector { _, _, _ -> if (error != null) throw error }

    @Test
    fun successfulConnectIsListeningReachable() {
        val probe = ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 800, failing(null))
        assertEquals("listening_reachable", probe.get("probeResult").asString)
        assertEquals("reachable", probe.get("resultFamily").asString)
        assertTrue(probe.get("reachable").asBoolean)
        assertTrue(probe.get("portListening").asBoolean)
        assertFalse(probe.get("timeoutObserved").asBoolean)
        assertFalse(probe.get("refusedObserved").asBoolean)
        assertEquals(800, probe.get("timeoutMs").asInt)
        assertEquals("69A2", probe.get("portHex").asString)
        assertEquals("active_socket_connect", probe.get("source").asString)
        assertTrue(probe.get("interpretation").asString.contains("监听"))
        assertTrue(probe.get("elapsedMs").asLong >= 0L)
    }

    @Test
    fun connectionRefusedIsRefusedNotTimeout() {
        val probe = ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 800,
            failing(ConnectException("Connection refused")))
        assertEquals("refused", probe.get("probeResult").asString)
        assertEquals("refused", probe.get("resultFamily").asString)
        assertTrue(probe.get("refusedObserved").asBoolean)
        assertFalse(probe.get("timeoutObserved").asBoolean)
        assertFalse(probe.get("reachable").asBoolean)
        assertEquals("java.net.ConnectException", probe.get("errorClass").asString)
        assertTrue(probe.get("interpretation").asString.contains("立刻拒绝"))
    }

    @Test
    fun socketTimeoutIsTimeoutNotRefused() {
        val probe = ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 800,
            failing(SocketTimeoutException("connect timed out")))
        assertEquals("timeout", probe.get("probeResult").asString)
        assertEquals("timeout", probe.get("resultFamily").asString)
        assertTrue(probe.get("timeoutObserved").asBoolean)
        assertFalse(probe.get("refusedObserved").asBoolean)
        assertFalse(probe.get("reachable").asBoolean)
        assertTrue(probe.get("interpretation").asString.contains("静默"))
    }

    @Test
    fun classifyFailureSeparatesTimeoutAndRefused() {
        assertEquals("listening_reachable", ToolkitFridaPortProbe.classifyFailure(null))
        // 有些平台把 ETIMEDOUT 包成 ConnectException（不是 SocketTimeoutException）→ 仍必须归 timeout。
        assertEquals("timeout", ToolkitFridaPortProbe.classifyFailure(ConnectException("Connection timed out")))
        // 无消息的 ConnectException 按 ECONNREFUSED 处理（Linux loopback 关闭端口的形态）。
        assertEquals("refused", ToolkitFridaPortProbe.classifyFailure(ConnectException()))
        assertEquals("error:java.io.IOException", ToolkitFridaPortProbe.classifyFailure(IOException("boom")))
        assertEquals("timeout", ToolkitFridaPortProbe.classifyFailure(IOException("read timed out")))
    }

    @Test
    fun otherIoExceptionIsErrorWithClassName() {
        val probe = ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 800, failing(IOException("boom")))
        assertEquals("error:java.io.IOException", probe.get("probeResult").asString)
        assertEquals("error", probe.get("resultFamily").asString)
        assertEquals("java.io.IOException", probe.get("errorClass").asString)
        assertEquals("boom", probe.get("errorMessage").asString)
        assertFalse(probe.get("reachable").asBoolean)
        assertFalse(probe.get("timeoutObserved").asBoolean)
        assertFalse(probe.get("refusedObserved").asBoolean)
    }

    @Test
    fun invalidEndpointBecomesErrorResultNotException() {
        val probe = ToolkitFridaPortProbe.probeEndpoint("127.0.0.1:0", 800, failing(null))
        assertEquals("error:java.lang.IllegalArgumentException", probe.get("probeResult").asString)
        assertFalse(probe.get("reachable").asBoolean)
        assertEquals(0, probe.get("port").asInt)
    }

    @Test
    fun timeoutIsClampedToBounds() {
        assertEquals(ToolkitFridaPortProbe.MIN_TIMEOUT_MS,
            ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 1, failing(null)).get("timeoutMs").asInt)
        assertEquals(ToolkitFridaPortProbe.MAX_TIMEOUT_MS,
            ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 999_999, failing(null)).get("timeoutMs").asInt)
        assertEquals(ToolkitFridaPortProbe.DEFAULT_TIMEOUT_MS,
            ToolkitFridaPortProbe.probe("127.0.0.1", 27042, connector = failing(null)).get("timeoutMs").asInt)
    }

    @Test
    fun parseEndpointAcceptsHostPortOnly() {
        assertEquals("127.0.0.1" to 27042, ToolkitFridaPortProbe.parseEndpoint("127.0.0.1:27042"))
        assertNull(ToolkitFridaPortProbe.parseEndpoint("127.0.0.1"))
        assertNull(ToolkitFridaPortProbe.parseEndpoint("127.0.0.1:0"))
        assertNull(ToolkitFridaPortProbe.parseEndpoint("127.0.0.1:65536"))
        assertNull(ToolkitFridaPortProbe.parseEndpoint(":27042"))
    }

    /** 结果读取器与诊断侧用法一致（reachable/timeoutObserved/refusedObserved/inconclusive）。 */
    @Test
    fun resultReadersMatchProbeResult() {
        val reachable = ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 800, failing(null))
        assertTrue(ToolkitFridaPortProbe.reachable(reachable))
        assertEquals("listening_reachable", ToolkitFridaPortProbe.probeResultOf(reachable))
        assertTrue(ToolkitFridaPortProbe.timeoutObserved(
            ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 800, failing(SocketTimeoutException("t")))))
        assertTrue(ToolkitFridaPortProbe.refusedObserved(
            ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 800, failing(ConnectException("refused")))))
        assertTrue(ToolkitFridaPortProbe.inconclusive(
            ToolkitFridaPortProbe.probe("127.0.0.1", 27042, 800, failing(IOException("boom")))))
        assertNull(ToolkitFridaPortProbe.probeResultOf(null))
    }

    // ---- 真连接（不注入连接器）------------------------------------------------

    @Test
    fun realListeningServerSocketIsReachable() {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val probe = ToolkitFridaPortProbe.probe("127.0.0.1", server.localPort, 800)
            assertEquals("listening_reachable", probe.get("probeResult").asString)
            assertTrue(probe.get("reachable").asBoolean)
            assertEquals(800, probe.get("timeoutMs").asInt)
        }
    }

    @Test
    fun realClosedPortIsRefusedNotTimeout() {
        // 取一个刚关闭的 loopback 端口：再 connect 会立刻 ECONNREFUSED（这正是 probe 与「静默 DROP」的判据差异）。
        val port = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val probe = ToolkitFridaPortProbe.probe("127.0.0.1", port, 800)
        assertEquals("refused", probe.get("probeResult").asString)
        assertTrue(probe.get("refusedObserved").asBoolean)
        assertFalse(probe.get("timeoutObserved").asBoolean)
        assertTrue("refused 必须远快于超时（elapsedMs=" + probe.get("elapsedMs").asLong + "）",
            probe.get("elapsedMs").asLong < 800L)
    }
}
