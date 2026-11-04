package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Test

class McpAppTraceTest {

    private var now = 1_000L

    private class FakeSource(
        private var next: McpAppTrace.Snapshot = McpAppTrace.Snapshot(),
    ) : McpAppTrace.DataSource {
        var calls = 0
        override fun snapshot(packageName: String, pid: Int?, withLogs: Boolean): McpAppTrace.Snapshot {
            calls++
            return next
        }
    }

    private fun trace(
        ttlMillis: Long = 10 * 60 * 1000L,
        maxSessions: Int = 4,
        source: McpAppTrace.DataSource = McpAppTrace.DataSource.Empty,
    ): McpAppTrace = McpAppTrace(clock = { now }, ttlMillis = ttlMillis, maxSessions = maxSessions, source = source)

    // ------------------------------------------------------------------
    // classifyExit：七种 reason + SIGKILL/unknown 的保守措辞
    // ------------------------------------------------------------------

    @Test fun classifiesJavaException() {
        val result = trace().classifyExit(
            listOf(
                "05-01 10:00:00.000  123  123 E AndroidRuntime: FATAL EXCEPTION: main",
                "05-01 10:00:00.001  123  123 E AndroidRuntime: java.lang.RuntimeException: boom",
                "05-01 10:00:00.002  123  123 E AndroidRuntime: Caused by: java.lang.IllegalStateException: bad",
            ),
            pid = 123,
            sawProcessAlive = false,
        )
        assertEquals("java_exception", result.get("reason").asString)
        assertEquals("high", result.get("confidence").asString)
        assertTrue(result.getAsJsonArray("evidenceLines").size() >= 2)
    }

    @Test fun classifiesNativeCrash() {
        val result = trace().classifyExit(
            listOf(
                "05-01 10:00:00.000  123  123 F DEBUG: Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0",
                "05-01 10:00:00.001  123  123 F libc: backtrace:",
            ),
            pid = 123,
            sawProcessAlive = false,
        )
        assertEquals("native_crash", result.get("reason").asString)
        assertEquals("high", result.get("confidence").asString)
        assertTrue(result.get("explanation").asString.contains("native"))
    }

    @Test fun classifiesExplicitExit() {
        val result = trace().classifyExit(
            listOf("05-01 10:00:00.000  123  123 I App: System.exit(0) called by detector"),
            pid = 123,
            sawProcessAlive = false,
        )
        assertEquals("exit_call", result.get("reason").asString)
        assertEquals("high", result.get("confidence").asString)
    }

    @Test fun classifiesSigkillButDoesNotInferDetectionBranch() {
        val result = trace().classifyExit(
            listOf("05-01 10:00:00.000 ActivityManager: Killing 123:com.example.app/u0a1 (adj 0): force-stop"),
            pid = 123,
            sawProcessAlive = false,
        )
        assertEquals("sigkill", result.get("reason").asString)
        assertTrue(result.get("uncertainty").asString.contains("SIGKILL"))
        assertTrue(result.get("uncertainty").asString.contains("结束方式"))
        assertTrue(result.get("uncertainty").asString.contains("检测分支"))
    }

    @Test fun classifiesSystemReclaim() {
        val result = trace().classifyExit(
            listOf("05-01 10:00:00.000  123  123 I lowmemorykiller: Kill 'com.example.app' (123), uid 1000"),
            pid = 123,
            sawProcessAlive = false,
        )
        assertEquals("system_reclaim", result.get("reason").asString)
        assertEquals("medium", result.get("confidence").asString)
        assertTrue(result.get("uncertainty").asString.contains("不能据此认定"))
    }

    @Test fun classifiesLaunchFailure() {
        val result = trace().classifyExit(
            listOf(
                "05-01 10:00:00.000 ActivityManager: Unable to start activity " +
                    "ComponentInfo{com.example.app/.Main}: Activity not started",
            ),
            pid = null,
            sawProcessAlive = false,
        )
        assertEquals("launch_failure", result.get("reason").asString)
        assertTrue(result.get("explanation").asString.contains("启动失败"))
    }

    @Test fun returnsUnknownWithUncertaintyWhenEvidenceIsInsufficient() {
        val result = trace().classifyExit(emptyList(), pid = null, sawProcessAlive = null)
        assertEquals("unknown", result.get("reason").asString)
        assertEquals("low", result.get("confidence").asString)
        assertTrue(result.get("uncertainty").asString.contains("证据不足"))
        assertTrue(result.get("uncertainty").asString.contains("缺少目标 pid"))
    }

    @Test fun unknownWhileProcessStillAliveDoesNotClaimAnExit() {
        val result = trace().classifyExit(listOf("05-01 normal target log"), 123, true)
        assertEquals("unknown", result.get("reason").asString)
        assertTrue(result.get("uncertainty").asString.contains("仍存活"))
    }

    // ------------------------------------------------------------------
    // filterTargetLines：目标/无关混排
    // ------------------------------------------------------------------

    @Test fun filtersTargetAndDirectStartupLinesFromMixedLogcat() {
        val lines = listOf(
            "05-01 10:00:00.000 ActivityManager: Start proc 123:com.example.app/u0a1 for activity",
            "05-01 10:00:00.050  123  123 I App: target bootstrap",
            "05-01 10:00:00.100  999  999 E AndroidRuntime: FATAL EXCEPTION: other",
            "05-01 10:00:00.101  999  999 E AndroidRuntime: java.lang.RuntimeException: other app",
            "05-01 10:00:00.200  123  123 E AndroidRuntime: FATAL EXCEPTION: main",
            "05-01 10:00:00.201  123  123 E AndroidRuntime: \tat com.example.app.Main.onCreate(Main.java:1)",
            "05-01 10:00:00.202  123  123 E AndroidRuntime: java.lang.RuntimeException: target app",
            "05-01 10:00:00.300  777  777 I Noise: unrelated chatter",
            "05-01 10:00:00.400  123  123 I App: target finished",
        )
        val filtered = trace().filterTargetLines(lines, "com.example.app", 123)
        assertTrue(filtered.any { it.contains("target bootstrap") })
        assertTrue(filtered.any { it.contains("FATAL EXCEPTION: main") })
        assertTrue(filtered.any { it.contains("RuntimeException: target app") })
        assertTrue(filtered.any { it.contains("target finished") })
        assertFalse(filtered.any { it.contains("other app") })
        assertFalse(filtered.any { it.contains("Noise") })
        assertTrue(filtered.size < lines.size)
    }

    @Test fun filterUsesPidWhenPackageNameDoesNotAppearOnEveryLine() {
        val lines = listOf(
            "05-01 10:00:00.000  123  456 I Thread: no package here",
            "05-01 10:00:00.100  999  999 I Thread: unrelated",
        )
        val filtered = trace().filterTargetLines(lines, "com.example.app", 123)
        assertEquals(1, filtered.size)
        assertTrue(filtered[0].contains("no package here"))
    }

    // ------------------------------------------------------------------
    // 会话：start/read 分页/截断、TTL、数量上限、stop 清理
    // ------------------------------------------------------------------

    @Test fun startAndReadReturnPaginationAndExitClassification() {
        val lines = (1..5).map { "05-01 10:00:00.00$it  123  123 I App: com.example.app line $it" }
        val source = FakeSource(McpAppTrace.Snapshot(pid = 123, uid = 10001, lines = lines))
        val trace = trace(source = source)
        val started = trace.start("com.example.app", "baseline")
        assertTrue(started.get("success").asBoolean)
        val id = started.get("traceSessionId").asString
        assertEquals(123, started.getAsJsonObject("target").get("pid").asInt)

        val first = trace.read(id, 0, 2)
        assertTrue(first.get("success").asBoolean)
        assertEquals(2, first.getAsJsonArray("lines").size())
        assertEquals(0, first.get("offset").asInt)
        assertEquals(2, first.get("nextOffset").asInt)
        assertTrue(first.get("hasMore").asBoolean)
        assertEquals("unknown", first.getAsJsonObject("exitClassification").get("reason").asString)

        val last = trace.read(id, 4, 50)
        assertEquals(1, last.getAsJsonArray("lines").size())
        assertEquals(5, last.get("nextOffset").asInt)
        assertFalse(last.get("hasMore").asBoolean)

        val pastEnd = trace.read(id, 99, 10)
        assertEquals(5, pastEnd.get("offset").asInt)
        assertEquals(5, pastEnd.get("nextOffset").asInt)
        assertFalse(pastEnd.get("hasMore").asBoolean)
    }

    @Test fun startSkipsLogsWhenWithLogsIsFalseButStillTracksTarget() {
        val source = FakeSource(McpAppTrace.Snapshot(pid = 42, uid = 7, lines = listOf("target log")))
        val trace = trace(source = source)
        val id = trace.start("com.example.app", null, withLogs = false).get("traceSessionId").asString
        val read = trace.read(id, 0, 10)
        assertTrue(read.get("success").asBoolean)
        assertEquals(0, read.get("total").asInt)
        assertEquals(0, read.getAsJsonArray("lines").size())
    }

    @Test fun expiredSessionIsPurgedAndReportedAsExpired() {
        val source = FakeSource(McpAppTrace.Snapshot(pid = 1, uid = 1, lines = listOf("com.example.app")))
        val trace = trace(ttlMillis = 100, source = source)
        val id = trace.start("com.example.app", "ttl").get("traceSessionId").asString
        now += 100
        val stop = trace.stop(id)
        assertFalse(stop.get("success").asBoolean)
        assertEquals("TRACE_EXPIRED", stop.get("code").asString)
        assertEquals(0, trace.list().get("count").asInt)

        val secondId = trace.start("com.example.app", "ttl2").get("traceSessionId").asString
        now += 100
        val read = trace.read(secondId, 0, 10)
        assertFalse(read.get("success").asBoolean)
        assertEquals("TRACE_EXPIRED", read.get("code").asString)
    }

    @Test fun maxSessionsEvictsOldestAndReportsIt() {
        val trace = trace(maxSessions = 2)
        val first = trace.start("com.example.app", "one").get("traceSessionId").asString
        now += 1
        val second = trace.start("com.example.app", "two").get("traceSessionId").asString
        now += 1
        val third = trace.start("com.example.app", "three")
        val thirdId = third.get("traceSessionId").asString
        val listed = trace.list()
        assertEquals(2, listed.get("count").asInt)
        val ids = listed.getAsJsonArray("sessions").map { it.asJsonObject.get("traceSessionId").asString }
        assertFalse(ids.contains(first))
        assertTrue(ids.contains(second))
        assertTrue(ids.contains(thirdId))
        assertTrue(third.getAsJsonArray("evictedSessionIds").map { it.asString }.contains(first))

        val oldRead = trace.read(first, 0, 1)
        assertFalse(oldRead.get("success").asBoolean)
        assertEquals("TRACE_NOT_FOUND", oldRead.get("code").asString)
    }

    @Test fun stopReleasesSessionAndLogCache() {
        val source = FakeSource(McpAppTrace.Snapshot(pid = 9, uid = 3, lines = listOf("com.example.app crash")))
        val trace = trace(source = source)
        val id = trace.start("com.example.app", "stop").get("traceSessionId").asString
        val stopped = trace.stop(id)
        assertTrue(stopped.get("success").asBoolean)
        assertTrue(stopped.get("sessionReleased").asBoolean)
        assertTrue(stopped.get("logsCacheReleased").asBoolean)
        assertEquals(1, stopped.get("cachedLines").asInt)
        assertTrue(stopped.has("exitClassification"))

        val again = trace.stop(id)
        assertFalse(again.get("success").asBoolean)
        assertEquals("TRACE_NOT_FOUND", again.get("code").asString)
        val read = trace.read(id, 0, 1)
        assertFalse(read.get("success").asBoolean)
        assertEquals(0, trace.list().get("count").asInt)
    }

    @Test fun truncatedSnapshotIsPropagatedToRead() {
        val source = FakeSource(
            McpAppTrace.Snapshot(
                pid = 1,
                uid = 1,
                lines = listOf("com.example.app line"),
                logsTruncated = true,
                note = "logcat ring buffer overwritten",
            ),
        )
        val trace = trace(source = source)
        val id = trace.start("com.example.app", "trunc").get("traceSessionId").asString
        val read = trace.read(id, 0, 10)
        assertTrue(read.get("truncated").asBoolean)
        assertEquals("logcat ring buffer overwritten", read.get("sourceNote").asString)
    }

    @Test fun exitInfoRunsOneShotFilterAndClassification() {
        val source = FakeSource(
            McpAppTrace.Snapshot(
                pid = 123,
                uid = 1,
                lines = listOf(
                    "05-01 10:00:00.000  123  123 E AndroidRuntime: FATAL EXCEPTION: main",
                    "05-01 10:00:00.001  999  999 I Other: unrelated",
                ),
                processAlive = false,
            ),
        )
        val result = trace(source = source).exitInfo("com.example.app")
        assertTrue(result.get("success").asBoolean)
        assertEquals("java_exception", result.getAsJsonObject("exitClassification").get("reason").asString)
        assertEquals(1, result.get("filteredOut").asInt)
        assertEquals(1, result.getAsJsonArray("lines").size())
    }

    @Test fun rejectsBlankPackageName() {
        val result = trace().start("  ", null)
        assertFalse(result.get("success").asBoolean)
        assertEquals("INVALID_ARGUMENT", result.get("code").asString)
    }
}
