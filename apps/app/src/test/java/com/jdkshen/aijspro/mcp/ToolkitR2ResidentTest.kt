package com.jdkshen.aijspro.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeoutException

/** T4：常驻 r2 会话（framing/有界/生命周期）——用假 pipe，不需要真机。 */
class ToolkitR2ResidentTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeHandle : R2PipeHandle {
        val written = ArrayList<String>()
        var aliveFlag = true
        var timeoutOnce = false
        // 真实 r2 语义：写 `<cmd>;?e <marker>`，读端读到 marker 行才算本次命令结束。
        var responder: (String) -> String = { line -> line.substringAfter(";?e ").trim() }
        override fun write(line: String) {
            if (!aliveFlag) throw IllegalStateException("process is dead")
            written.add(line)
        }
        override fun readUntil(marker: String, timeoutMillis: Long, maxBytes: Long): String {
            if (!aliveFlag) throw IllegalStateException("process is dead")
            if (timeoutOnce) { timeoutOnce = false; throw TimeoutException("timeout") }
            return responder(written.last())
        }
        override fun alive() = aliveFlag
        override fun close() { aliveFlag = false }
    }

    private class FakePipe(val handle: FakeHandle) : R2Pipe {
        override fun open(inputPath: String, args: List<String>): R2PipeHandle = handle
    }

    private val sha = "d7b3a2510389094723abbe3e504f5029e97bd19ba5c297a940033a7ac21bccaa"
    private fun resident(pipe: R2Pipe?, handle: FakeHandle? = null, now: () -> Long = { 0L }) =
        ToolkitR2Resident(temp.newFolder("resident-" + System.nanoTime()), pipe, now)

    private fun markerOf(line: String) = line.substringAfter(";?e ").trim()

    @Test
    fun openExposesFramingAndRelocsFlag() {
        val handle = FakeHandle()
        val r2 = resident(FakePipe(handle))
        val opened = r2.open("/data/libyj.so", sha, relocsApply = true)
        assertEquals(true, opened.get("ok").asBoolean)
        assertNotNull(opened.get("sessionId").asString)
        assertTrue(opened.get("marker").asString.isNotBlank())
        assertEquals("bin.relocs.apply=true", opened.get("relocsFlag").asString)
        assertTrue(opened.get("argv").toString().contains("bin.relocs.apply=true"))
        assertTrue(opened.get("argv").toString().contains("/data/libyj.so"))
        // 未发命令前不谎报已执行；但句柄确实已建立。
        assertFalse(opened.get("executed").asBoolean)
        assertTrue(opened.get("runnerConfigured").asBoolean)
        // open 会先跑一条无副作用的预热命令（?e ready），把 r2 启动成本提前消化。
        assertEquals(true, opened.get("warmedUp").asBoolean)
        assertEquals(1, handle.written.size)
        assertTrue(handle.written[0].startsWith("?e ready;?e "))
    }

    @Test
    fun commandStripsMarkerAndReusesTheSameProcess() {
        val handle = FakeHandle()
        handle.responder = { line ->
            val marker = line.substringAfter(";?e ").trim()
            if (line.startsWith("?e ready")) "ready\n" + marker + "\n" else "arch arm\n" + marker + "\n"
        }
        val r2 = resident(FakePipe(handle))
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        val first = r2.command(sid, "ij")
        assertEquals(true, first.get("executed").asBoolean)
        assertEquals("arch arm", first.get("output").asString.trim())
        assertFalse(first.get("output").asString.contains("?e"))
        r2.command(sid, "iI")
        // written[0] 是 open 的预热命令，随后才是 ij / iI。
        assertEquals(3, handle.written.size)
        assertTrue(handle.written[1].startsWith("ij;?e "))
        assertTrue(handle.written[2].startsWith("iI;?e "))
    }

    @Test
    fun batchIsolatesRejectedAndTimedOutCommands() {
        val handle = FakeHandle()
        handle.responder = { line ->
            if (line.contains("slow")) throw TimeoutException("timeout")
            val marker = line.substringAfter(";?e ").trim()
            if (line.startsWith("?e ready")) "ready\n" + marker + "\n" else "ok-marker\n" + marker + "\n"
        }
        val r2 = resident(FakePipe(handle))
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        val batch = r2.batch(sid, listOf("ij", "!ls", "slow"))
        assertEquals(3, batch.get("total").asInt)
        assertEquals(1, batch.get("okCount").asInt)
        assertEquals(2, batch.get("errorCount").asInt)
        assertFalse(batch.get("ok").asBoolean)
        val items = batch.getAsJsonArray("results")
        assertEquals("COMMAND_REJECTED", items[1].asJsonObject.get("errorCode").asString)
        assertEquals("TIMEOUT", items[2].asJsonObject.get("errorCode").asString)
        assertEquals("ok-marker", items[0].asJsonObject.get("output").asString.trim())
    }

    @Test
    fun commandRejectsInjectedShellEscape() {
        val handle = FakeHandle()
        val r2 = resident(FakePipe(handle))
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        try {
            r2.command(sid, "!id")
            fail("expected rejection")
        } catch (failure: ToolkitException) {
            assertEquals("COMMAND_REJECTED", failure.code)
        }
        // 只有 open 的预热命令，注入命令本身没有被写出。
        assertEquals(1, handle.written.size)
        assertTrue(handle.written[0].startsWith("?e ready"))
    }

    @Test
    fun oversizedOutputReportsOutputLimit() {
        val handle = FakeHandle()
        handle.responder = { line ->
            val marker = line.substringAfter(";?e ").trim()
            if (line.startsWith("?e ready")) "ready\n" + marker + "\n" else "x".repeat(4096) + "\n" + marker + "\n"
        }
        val r2 = ToolkitR2Resident(temp.newFolder("limit"), FakePipe(handle), maxOutputBytes = 64L)
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        try {
            r2.command(sid, "ij")
            fail("expected OUTPUT_LIMIT")
        } catch (failure: ToolkitException) {
            assertEquals("OUTPUT_LIMIT", failure.code)
        }
    }

    @Test
    fun missingMarkerIsReportedAsTimeout() {
        // 没有 marker 行 = 本次命令的结尾没到，不能把半截输出当结果。
        val handle = FakeHandle()
        handle.responder = { "no marker here\n" }
        val r2 = resident(FakePipe(handle))
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        try {
            r2.command(sid, "ij")
            fail("expected TIMEOUT")
        } catch (failure: ToolkitException) {
            assertEquals("TIMEOUT", failure.code)
        }
    }

    @Test
    fun readTimeoutIsReportedAsTimeout() {
        val handle = FakeHandle()
        handle.responder = { line -> if (line == "?e ready") "ready\n" else throw TimeoutException("timeout") }
        val r2 = resident(FakePipe(handle))
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        try {
            r2.command(sid, "ij")
            fail("expected TIMEOUT")
        } catch (failure: ToolkitException) {
            assertEquals("TIMEOUT", failure.code)
        }
    }

    @Test
    fun deadProcessReportsSessionDead() {
        val handle = FakeHandle()
        val r2 = resident(FakePipe(handle))
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        handle.aliveFlag = false
        try {
            r2.command(sid, "ij")
            fail("expected SESSION_DEAD")
        } catch (failure: ToolkitException) {
            assertEquals("SESSION_DEAD", failure.code)
        }
    }

    @Test
    fun withoutPipeCommandsAreDryPlans() {
        val r2 = resident(null)
        val opened = r2.open("/data/libyj.so", sha)
        assertFalse(opened.get("runnerConfigured").asBoolean)
        val sid = opened.get("sessionId").asString
        val plan = r2.command(sid, "ij")
        assertFalse(plan.get("executed").asBoolean)
        assertFalse(plan.get("runnerConfigured").asBoolean)
        assertTrue(plan.get("note").asString.contains("未注入"))
        assertTrue(plan.get("line").asString.startsWith("ij;?e "))
        assertFalse(r2.list().get("runnerConfigured").asBoolean)
    }

    @Test
    fun expiredSessionIsPurged() {
        val handle = FakeHandle()
        var now = 0L
        val r2 = ToolkitR2Resident(temp.newFolder("ttl"), FakePipe(handle), { now }, ttlMillis = 1000L)
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        now = 5000L
        try {
            r2.command(sid, "ij")
            fail("expected purge")
        } catch (failure: ToolkitException) {
            assertTrue(failure.code in setOf("SESSION_NOT_FOUND", "SESSION_EXPIRED"))
        }
    }

    @Test
    fun closeReleasesSessionAndRejectsFurtherUse() {
        val handle = FakeHandle()
        val r2 = resident(FakePipe(handle))
        val sid = r2.open("/data/libyj.so", sha).get("sessionId").asString
        val closed = r2.close(sid)
        assertEquals(true, closed.get("closed").asBoolean)
        assertEquals(0, closed.get("remainingSessions").asInt)
        assertEquals(true, closed.get("releasedProcess").asBoolean)
        assertFalse(handle.aliveFlag)
        try {
            r2.command(sid, "ij")
            fail("expected SESSION_NOT_FOUND")
        } catch (failure: ToolkitException) {
            assertEquals("SESSION_NOT_FOUND", failure.code)
        }
    }

    @Test
    fun sessionLimitIsEnforced() {
        val r2 = ToolkitR2Resident(temp.newFolder("limit2"), null, maxSessions = 1)
        r2.open("/data/a.so", sha)
        try {
            r2.open("/data/b.so", sha)
            fail("expected SESSION_LIMIT")
        } catch (failure: ToolkitException) {
            assertEquals("SESSION_LIMIT", failure.code)
        }
    }
}
