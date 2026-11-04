package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** ToolkitProcNetTcp 纯 JVM 单测：真实 /proc/net/tcp 行、LISTEN/SYN_SENT、坏行、IPv6、hexPort 边界。 */
class ToolkitProcNetTcpTest {
    private val header =
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

    private fun row(
        local: String,
        remote: String,
        state: String,
        uid: String = "1000",
        inode: String = "12345",
    ): String = "   0: " + local + " " + remote + " " + state +
        " 00000000:00000000 00:00000000 00000000  " + uid + "        0 " + inode + " 1 0000000000000000 100 0 0 10 0"

    private val listenLine = row("0100007F:69A2", "00000000:0000", "0A", "1000", "12345")
    private val synSentLine = row("0100007F:8A88", "0100007F:69A2", "02", "10123", "54321")

    @Test
    fun parseRealProcNetTcpSamples() {
        val result = ToolkitProcNetTcp.parseResult(header + "\n" + listenLine + "\n" + synSentLine)
        assertEquals(2, result.entries.size)
        assertEquals(1, result.headerLines)
        assertEquals(0, result.skippedLines)

        val listen = result.entries[0]
        assertEquals("0100007F", listen.localAddressHex)
        assertEquals("69A2", listen.localPortHex)
        assertEquals("00000000", listen.remoteAddressHex)
        assertEquals("0000", listen.remotePortHex)
        assertEquals("0A", listen.state)
        assertEquals(1000, listen.uid)
        assertEquals("12345", listen.inode)

        val syn = result.entries[1]
        assertEquals("0100007F", syn.localAddressHex)
        assertEquals("8A88", syn.localPortHex)
        assertEquals("0100007F", syn.remoteAddressHex)
        assertEquals("69A2", syn.remotePortHex)
        assertEquals("02", syn.state)
        assertEquals(10123, syn.uid)
        assertEquals("54321", syn.inode)
    }

    @Test
    fun listenPresentSupportsWildcardLoopbackAndRejectsOtherAddresses() {
        val entries = ToolkitProcNetTcp.parse(header + "\n" + listenLine + "\n" + synSentLine)
        assertTrue(ToolkitProcNetTcp.listenPresent(entries, 27042))
        assertFalse(ToolkitProcNetTcp.listenPresent(entries, 12345))
        assertFalse(ToolkitProcNetTcp.listenPresent(entries, 27042 + 1))

        val wildcard = ToolkitProcNetTcp.parse(header + "\n" + row("00000000:69A2", "00000000:0000", "0A"))
        assertTrue(ToolkitProcNetTcp.listenPresent(wildcard, 27042))

        val otherLoopback = ToolkitProcNetTcp.parse(header + "\n" + row("0200007F:69A2", "00000000:0000", "0A"))
        assertFalse(ToolkitProcNetTcp.listenPresent(otherLoopback, 27042))

        // 只有 SYN_SENT / 别的 state 不能当作 LISTEN。
        val synOnly = ToolkitProcNetTcp.parse(header + "\n" + synSentLine)
        assertFalse(ToolkitProcNetTcp.listenPresent(synOnly, 27042))
    }

    @Test
    fun synSentToMatchesRemotePortAndStateOnly() {
        val entries = ToolkitProcNetTcp.parse(header + "\n" + listenLine + "\n" + synSentLine)
        assertTrue(ToolkitProcNetTcp.synSentTo(entries, 27042))
        assertFalse(ToolkitProcNetTcp.synSentTo(entries, 8080))

        val established = ToolkitProcNetTcp.parse(
            header + "\n" + row("0100007F:8A88", "0100007F:69A2", "01"),
        )
        assertFalse(ToolkitProcNetTcp.synSentTo(established, 27042))

        val listenOnly = ToolkitProcNetTcp.parse(header + "\n" + listenLine)
        assertFalse(ToolkitProcNetTcp.synSentTo(listenOnly, 27042))
    }

    @Test
    fun hexPortBoundaries() {
        assertEquals("0001", ToolkitProcNetTcp.hexPort(1))
        assertEquals("69A2", ToolkitProcNetTcp.hexPort(27042))
        assertEquals("FFFF", ToolkitProcNetTcp.hexPort(65535))
        assertThrows(IllegalArgumentException::class.java) { ToolkitProcNetTcp.hexPort(0) }
        assertThrows(IllegalArgumentException::class.java) { ToolkitProcNetTcp.hexPort(65536) }
        assertThrows(IllegalArgumentException::class.java) { ToolkitProcNetTcp.hexPort(-1) }
    }

    @Test
    fun emptyTextAndNoHeaderAreTolerated() {
        assertTrue(ToolkitProcNetTcp.parse("").isEmpty())
        assertTrue(ToolkitProcNetTcp.parse("\n\n   \n").isEmpty())
        val noHeader = ToolkitProcNetTcp.parse(listenLine)
        assertEquals(1, noHeader.size)
        assertEquals("69A2", noHeader[0].localPortHex)
    }

    @Test
    fun badLinesAreSkippedAndCounted() {
        val text = header + "\n" +
            "garbage line\n" +
            "0: 0100007F:69A2 00000000:0000\n" +
            "0: ZZZZ:69A2 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1\n" +
            "0: 0100007F:XYZW 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1\n" +
            "0: 0100007F:69A2 00000000:0000 ZZ 00000000:00000000 00:00000000 00000000 1000 0 1\n" +
            listenLine
        val result = ToolkitProcNetTcp.parseResult(text)
        assertEquals(1, result.headerLines)
        assertEquals(1, result.entries.size)
        assertEquals("69A2", result.entries[0].localPortHex)
        assertEquals(5, result.skippedLines)
    }

    @Test
    fun ipv6RawHexIsParsedAndLoopbackWildcardWork() {
        val v6Listen = row(
            "00000000000000000000000001000000:1F90",
            "00000000000000000000000000000000:0000",
            "0A",
        )
        val v6Syn = row(
            "00000000000000000000000000000000:9C40",
            "00000000000000000000000001000000:1F90",
            "02",
        )
        val entries = ToolkitProcNetTcp.parse(header + "\n" + v6Listen + "\n" + v6Syn)
        assertEquals(2, entries.size)
        assertEquals(32, entries[0].localAddressHex.length)
        assertEquals("00000000000000000000000001000000", entries[0].localAddressHex)
        assertTrue(ToolkitProcNetTcp.listenPresent(entries, 8080))
        assertTrue(ToolkitProcNetTcp.synSentTo(entries, 8080))

        val v6Wildcard = ToolkitProcNetTcp.parse(
            header + "\n" + row("00000000000000000000000000000000:0050", "00000000000000000000000000000000:0000", "0A"),
        )
        assertTrue(ToolkitProcNetTcp.listenPresent(v6Wildcard, 80))
    }

    @Test
    fun summarizeIncludesStructuredEvidence() {
        val entries = ToolkitProcNetTcp.parse(header + "\n" + listenLine + "\n" + synSentLine)
        val summary = ToolkitProcNetTcp.summarize(entries, 27042)
        assertEquals(27042, summary.get("port").asInt)
        assertEquals("69A2", summary.get("portHex").asString)
        assertTrue(summary.get("listenPresent").asBoolean)
        assertTrue(summary.get("synSentTo").asBoolean)
        assertEquals(1, summary.get("listenerCount").asInt)
        assertEquals(1, summary.get("synSentCount").asInt)

        val listener = summary.getAsJsonArray("listeners").get(0).asJsonObject
        assertEquals("0100007F", listener.get("localAddressHex").asString)
        assertEquals("69A2", listener.get("localPortHex").asString)
        assertEquals("0A", listener.get("state").asString)
        assertEquals(1000, listener.get("uid").asInt)
        assertEquals("12345", listener.get("inode").asString)

        val syn = summary.getAsJsonArray("synSent").get(0).asJsonObject
        assertEquals("02", syn.get("state").asString)
        assertEquals("69A2", syn.get("remotePortHex").asString)
    }

    @Test
    fun forDiagnosisCombinesTcpAndTcp6Evidence() {
        val tcpText = header + "\n" + listenLine
        val tcp6Text = header + "\n" + row(
            "00000000000000000000000000000000:9C40",
            "00000000000000000000000001000000:69A2",
            "02",
        )
        // 用 tcp6 的 SYN_SENT 行模拟客户端侧；remote 端口仍是 69A2。
        val evidence = ToolkitProcNetTcp.forDiagnosis(tcpText, tcp6Text, 27042)
        assertEquals("/proc/net/tcp", evidence.get("source").asString)
        assertEquals("69A2", evidence.get("portHex").asString)
        assertTrue(evidence.get("portListening").asBoolean)
        assertTrue(evidence.get("synSentObserved").asBoolean)
        assertEquals(2, evidence.get("parsedEntries").asInt)
        assertEquals(0, evidence.get("skippedLines").asInt)
        assertTrue(evidence.get("sameUidKnown").asBoolean.not())
        assertTrue(evidence.get("sameUidRequired").asBoolean)
        assertTrue(evidence.get("sameUid").isJsonNull)
        val args = evidence.getAsJsonObject("classifyConnectFailureArgs")
        assertTrue(args.get("portListening").asBoolean)
        assertTrue(args.get("synSentObserved").asBoolean)
        assertTrue(args.get("sameUid").isJsonNull)
        assertTrue(evidence.get("note").asString.contains("sameUid"))
        assertTrue(evidence.getAsJsonObject("tcp").get("parsedEntries").asInt == 1)
        assertTrue(evidence.getAsJsonObject("tcp6").get("parsedEntries").asInt == 1)
    }

    @Test
    fun forDiagnosisWithoutTcp6AndWithoutSynSent() {
        val evidence = ToolkitProcNetTcp.forDiagnosis(header + "\n" + listenLine, null, 27042)
        assertTrue(evidence.get("portListening").asBoolean)
        assertFalse(evidence.get("synSentObserved").asBoolean)
        assertTrue(evidence.get("tcp6").isJsonNull)
        assertFalse(evidence.get("classifyConnectFailureArgs").asJsonObject.get("synSentObserved").asBoolean)
    }

    @Test
    fun whitespaceCrlfTabsAndLowercaseStateAreTolerated() {
        val text = "sl\tlocal_address\trem_address\tst\tuid\tinode\r\n" +
            "\r\n" +
            "   0:\t0100007f:69a2\t00000000:0000\t0a\t1000\t12345\r\n"
        val result = ToolkitProcNetTcp.parseResult(text)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.headerLines)
        assertEquals(0, result.skippedLines)
        assertEquals("0A", result.entries[0].state)
        assertEquals("0100007F", result.entries[0].localAddressHex)
        assertEquals("69A2", result.entries[0].localPortHex)
        // 只有 6 列：uid/inode 列缺失时保持 null，不抛异常。
        assertNull(result.entries[0].uid)
        assertNull(result.entries[0].inode)
    }

    @Test
    fun shortButValidLineKeepsOptionalFieldsNull() {
        val entries = ToolkitProcNetTcp.parse(header + "\n" + " 0: 0100007F:69A2 00000000:0000 0A")
        assertEquals(1, entries.size)
        assertNull(entries[0].uid)
        assertNull(entries[0].inode)
        assertTrue(ToolkitProcNetTcp.listenPresent(entries, 27042))
    }
}
