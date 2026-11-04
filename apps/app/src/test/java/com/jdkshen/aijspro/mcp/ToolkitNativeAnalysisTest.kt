package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * task-254：ToolkitNativeAnalysis 纯 JVM 测试（不依赖 Android / radare2 / gradle）。
 * 覆盖：open/复用、哈希变化 STALE、函数/类型/noreturn、覆盖层 analysisOnly、
 * 上限拒绝、save+新实例恢复、close 清理、list 统计、r2Commands 形状。
 */
class ToolkitNativeAnalysisTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun sha(ch: Char): String = ch.code.toString(16).padStart(2, '0').repeat(32)
    private fun sha16(ch: Char): String = sha(ch).take(16)

    private fun errorCode(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("应抛出异常", error)
        assertTrue("应抛出 ToolkitException，实际：" + error, error is ToolkitException)
        return (error as ToolkitException).code
    }

    private fun stateFile(store: File, shaChar: Char, sessionId: String): File =
        File(File(store, sha16(shaChar)), "analysis-" + sessionId + ".json")

    private fun indexSessions(store: File): Map<String, com.google.gson.JsonObject> {
        val root = JsonParser().parse(File(store, "index.json").readText(Charsets.UTF_8)).asJsonObject
        return root.getAsJsonObject("sessions").entrySet()
            .associate { it.key to it.value.asJsonObject }
    }

    @Test fun openReusesSameShaAndPersistsStateAndIndex() {
        val store = temp.newFolder("reuse")
        var now = 1_700_000_000_000L
        val analysis = ToolkitNativeAnalysis(store, { now })
        val first = analysis.open("/data/local/tmp/lib.so", sha('a'), "arm64", "sess-a")
        assertFalse(first.get("reused").asBoolean)
        assertTrue(first.get("analysisOnly").asBoolean)
        assertEquals("sess-a", first.get("sessionId").asString)
        val id = first.get("sessionId").asString

        now += 10
        val second = analysis.open("/data/local/tmp/lib.so", sha('a'), "arm64")
        assertTrue(second.get("reused").asBoolean)
        assertEquals(id, second.get("sessionId").asString)
        assertEquals(sha16('a'), second.get("sha16").asString)
        assertTrue(stateFile(store, 'a', id).isFile)
        assertTrue(File(store, "index.json").isFile)
        assertEquals(1, analysis.list().get("total").asInt)
        assertEquals(1, indexSessions(store).size)
    }

    @Test fun changedSourceShaInvalidatesOldSessionWithStaleInput() {
        val store = temp.newFolder("stale")
        var now = 1_700_000_000_000L
        val analysis = ToolkitNativeAnalysis(store, { now })
        val first = analysis.open("/lib/a.so", sha('a'), "arm64", "old")
        val oldId = first.get("sessionId").asString
        now += 1
        val second = analysis.open("/lib/a.so", sha('b'), "arm64")
        assertNotEquals(oldId, second.get("sessionId").asString)
        assertEquals("ANALYSIS_STALE_INPUT", errorCode { analysis.status(oldId) })
        assertEquals("ANALYSIS_STALE_INPUT", errorCode {
            analysis.open("/lib/a.so", sha('b'), "arm64", requestedSession = oldId)
        })
    }

    @Test fun defineFunctionTypeAndNoreturnUpdatesStatusAndCommands() {
        val store = temp.newFolder("mutations")
        val now = 1_700_000_000_000L
        val analysis = ToolkitNativeAnalysis(store, { now })
        val id = analysis.open("/lib/libx.so", sha('c'), "aarch64").get("sessionId").asString

        val function = analysis.defineFunction(id, "0x1000", 32, "sub_1000")
        val address = function.getAsJsonObject("address")
        assertEquals("0x1000", address.get("value").asString)
        assertEquals("virtual", address.get("kind").asString)
        assertEquals("aarch64", address.get("abi").asString)
        assertEquals(1, function.get("functionCount").asInt)

        analysis.defineFunction(id, "file:0x40", 8, null)
        analysis.setType(id, "0x1000", "int")
        analysis.setNoreturn(id, "0x1000", true)
        val status = analysis.status(id)
        assertEquals(2, status.get("functionCount").asInt)
        assertEquals(1, status.get("typeCount").asInt)
        assertEquals(1, status.get("noreturnCount").asInt)
        val fileOffset = status.getAsJsonArray("functions").map { it.asJsonObject }
            .first { it.getAsJsonObject("address").get("value").asString == "0x40" }
        assertEquals("file_offset", fileOffset.getAsJsonObject("address").get("kind").asString)
        val commands = analysis.r2Commands(id)
        assertTrue(commands.contains("af @ 0x1000"))
        assertTrue(commands.contains("afn sub_1000 @ 0x1000"))
        assertTrue(commands.contains("tl int @ 0x1000"))
        assertTrue(commands.any { it.contains("noreturn") })
    }

    @Test fun overlayIsAnalysisOnlyAndNeverBuildSafe() {
        val store = temp.newFolder("overlay")
        val analysis = ToolkitNativeAnalysis(store, { 1_700_000_000_000L })
        val id = analysis.open("/lib/o.so", sha('d'), "arm64").get("sessionId").asString
        val result = analysis.overlay(id, "runtime:0x2000", "01020304", "probe")
        assertEquals(4, result.get("bytes").asInt)
        assertEquals(4L, result.get("overlayBytes").asLong)
        assertTrue(result.get("analysisOnly").asBoolean)
        assertFalse(result.get("buildSafe").asBoolean)
        assertTrue(result.get("warning").asString.contains("不得流入构建产物"))
        val overlay = analysis.status(id).getAsJsonArray("overlays").single().asJsonObject
        assertTrue(overlay.get("analysisOnly").asBoolean)
        assertEquals("runtime", overlay.getAsJsonObject("address").get("kind").asString)
        assertTrue(analysis.r2Commands(id).contains("wx 01020304 @ 0x2000"))
    }

    @Test fun limitsAreExplicitForFunctionsOverlaysAndSessions() {
        val store = temp.newFolder("limits")
        var now = 1_700_000_000_000L
        val analysis = ToolkitNativeAnalysis(
            store, { now }, maxSessions = 2, maxFunctions = 2, maxOverlayBytes = 4, ttlMillis = 1000,
        )
        val id = analysis.open("/lib/limit.so", sha('e'), "arm64").get("sessionId").asString
        analysis.defineFunction(id, "0x1", null, null)
        analysis.defineFunction(id, "0x2", null, null)
        assertEquals("ANALYSIS_FUNCTION_LIMIT", errorCode { analysis.defineFunction(id, "0x3", null, null) })
        analysis.defineFunction(id, "0x1", 16, "updated")
        analysis.overlay(id, "0x10", "0102", null)
        analysis.overlay(id, "0x11", "0304", null)
        assertEquals("ANALYSIS_OVERLAY_LIMIT", errorCode { analysis.overlay(id, "0x12", "05", null) })

        analysis.open("/lib/second.so", sha('f'), "arm64")
        assertEquals("ANALYSIS_SESSION_LIMIT", errorCode { analysis.open("/lib/third.so", sha('g'), "arm64") })

        now += 1001
        assertEquals("ANALYSIS_TTL_EXPIRED", errorCode { analysis.status(id) })
    }

    @Test fun saveThenNewInstanceRecoversFunctionsTypesNoreturnAndOverlay() {
        val store = temp.newFolder("recover")
        var now = 1_700_000_000_000L
        val first = ToolkitNativeAnalysis(store, { now })
        val id = first.open("/lib/resume.so", sha('h'), "arm64", "resume").get("sessionId").asString
        first.defineFunction(id, "0x1000", 12, "resume_fn")
        first.setType(id, "0x1000", "char *")
        first.setNoreturn(id, "0x1000", true)
        first.overlay(id, "0x1000", "90", "patch")
        first.save(id)
        now += 50

        val second = ToolkitNativeAnalysis(store, { now })
        val status = second.status(id)
        assertEquals(1, status.get("functionCount").asInt)
        assertEquals(1, status.get("typeCount").asInt)
        assertEquals(1, status.get("noreturnCount").asInt)
        assertEquals(1L, status.get("overlayBytes").asLong)
        assertEquals("resume_fn", status.getAsJsonArray("functions").single().asJsonObject.get("name").asString)
        assertTrue(second.r2Commands(id).contains("afn resume_fn @ 0x1000"))
    }

    @Test fun closeRemovesStateFileAndIndexEntry() {
        val store = temp.newFolder("close")
        val analysis = ToolkitNativeAnalysis(store, { 1_700_000_000_000L })
        val id = analysis.open("/lib/close.so", sha('i'), "arm64", "close-me").get("sessionId").asString
        assertTrue(stateFile(store, 'i', id).isFile)
        val result = analysis.close(id)
        assertTrue(result.get("closed").asBoolean)
        assertTrue(result.get("releasedBytes").asLong > 0L)
        assertFalse(stateFile(store, 'i', id).exists())
        assertFalse(indexSessions(store).containsKey(id))
        assertEquals(0, analysis.list().get("total").asInt)
        assertEquals("ANALYSIS_SESSION_NOT_FOUND", errorCode { analysis.status(id) })
    }

    @Test fun listReportsSessionStatsAndLimits() {
        val store = temp.newFolder("list")
        val analysis = ToolkitNativeAnalysis(store, { 1_700_000_000_000L }, maxSessions = 3, maxFunctions = 10)
        val first = analysis.open("/lib/one.so", sha('j'), "arm64").get("sessionId").asString
        analysis.open("/lib/two.so", sha('k'), "x86_64")
        analysis.defineFunction(first, "0x10", null, null)
        analysis.overlay(first, "0x10", "ff", null)
        val listed = analysis.list()
        assertEquals(2, listed.get("total").asInt)
        assertEquals(2, listed.get("openCount").asInt)
        assertEquals(3, listed.get("maxSessions").asInt)
        assertEquals(1, listed.get("functionTotal").asInt)
        assertEquals(1L, listed.get("overlayBytesTotal").asLong)
        assertTrue(listed.get("analysisOnly").asBoolean)
        val found = listed.getAsJsonArray("sessions").map { it.asJsonObject }
            .first { it.get("sessionId").asString == first }
        assertEquals(1, found.get("functionCount").asInt)
        assertEquals(1, found.get("overlayBytes").asInt)
    }
}
