package com.jdkshen.aijspro.mcp

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ToolkitNativeAnalysis 纯 JVM 测试（不依赖 radare2 / 真机）。
 * 覆盖：真实文件哈希校验、open/复用、close 后归档恢复、损坏归档明确报错、
 * 函数/类型/noreturn/覆盖层、上限、save+新实例恢复、list 统计、r2Commands 形状。
 *
 * 注意：所有 sourcePath 都使用绝对临时文件；生产相对路径由 McpSettings.operationPath
 * 解析，不依赖本测试的 Android 环境。
 */
class ToolkitNativeAnalysisTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun input(name: String, content: String): File =
        temp.newFile(name).apply { writeText(content, Charsets.UTF_8) }

    private fun sha(file: File): String = ToolkitIo.sha(file)

    private fun stateFile(store: File, sha256: String, sessionId: String): File =
        File(File(store, sha256.take(16)), "analysis-" + sessionId + ".json")

    private fun archiveFile(store: File, sha256: String, sessionId: String): File =
        File(File(store, sha256.take(16)), "archive-" + sessionId + ".json")

    private fun indexSessions(store: File): Map<String, com.google.gson.JsonObject> {
        val root = JsonParser().parse(File(store, "index.json").readText(Charsets.UTF_8)).asJsonObject
        return root.getAsJsonObject("sessions").entrySet()
            .associate { it.key to it.value.asJsonObject }
    }

    private fun failure(block: () -> Unit): ToolkitException {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("应抛出异常", error)
        assertTrue("应抛出 ToolkitException，实际：" + error, error is ToolkitException)
        return error as ToolkitException
    }

    private fun errorCode(block: () -> Unit): String = failure(block).code

    @Test fun openReusesSameShaAndPersistsStateAndIndex() {
        val store = temp.newFolder("reuse")
        var now = 1_700_000_000_000L
        val file = input("reuse.so", "reuse-content")
        val sha = sha(file)
        val analysis = ToolkitNativeAnalysis(store, { now })

        val first = analysis.open(file.path, sha, "arm64", "sess-a")
        assertFalse(first.get("reused").asBoolean)
        assertFalse(first.get("restored").asBoolean)
        assertTrue(first.get("analysisOnly").asBoolean)
        assertEquals("sess-a", first.get("sessionId").asString)
        assertEquals(sha, first.get("sourceSha256").asString)
        assertEquals(sha.take(16), first.get("sha16").asString)
        assertEquals(0, first.get("functionCount").asInt)
        val id = first.get("sessionId").asString

        now += 10
        val second = analysis.open(file.path, sha, "arm64")
        assertTrue(second.get("reused").asBoolean)
        assertEquals(id, second.get("sessionId").asString)
        assertEquals(sha, second.get("sourceSha256").asString)
        assertTrue(stateFile(store, sha, id).isFile)
        assertTrue(File(store, "index.json").isFile)
        assertEquals(1, analysis.list().get("total").asInt)
        assertEquals(1, indexSessions(store).size)
    }

    @Test fun rejectsHashMismatchAndDoesNotCreateStateDir() {
        val store = temp.newFolder("hash-reject")
        val file = input("real.so", "real-content")
        val actual = sha(file)
        val wrong = "0".repeat(64)
        val analysis = ToolkitNativeAnalysis(store, { 1_700_000_000_000L })

        val rejected = failure { analysis.open(file.path, wrong, "arm64") }
        assertEquals("ANALYSIS_STALE_INPUT", rejected.code)
        assertTrue("错误应带 expected 前 16 位",
            rejected.message.orEmpty().contains("expected=" + wrong.take(16)))
        assertTrue("错误应带 actual 前 16 位",
            rejected.message.orEmpty().contains("actual=" + actual.take(16)))
        assertTrue("错误应提示 artifact_fingerprint",
            rejected.message.orEmpty().contains("artifact_fingerprint"))
        assertFalse("哈希不匹配不能创建全 0 的 state 目录",
            File(store, wrong.take(16)).exists())
        assertFalse("哈希不匹配不能创建 index", File(store, "index.json").exists())
        assertEquals(0, analysis.list().get("total").asInt)
    }

    @Test fun changedSourceShaInvalidatesOldSessionWithStaleInput() {
        val store = temp.newFolder("stale")
        var now = 1_700_000_000_000L
        val file = input("stale.so", "one")
        val firstSha = sha(file)
        val analysis = ToolkitNativeAnalysis(store, { now })
        val oldId = analysis.open(file.path, firstSha, "arm64", "old").get("sessionId").asString

        file.writeText("two", Charsets.UTF_8)
        val secondSha = sha(file)
        now += 1
        val second = analysis.open(file.path, secondSha, "arm64")
        assertNotEquals(oldId, second.get("sessionId").asString)
        assertEquals("ANALYSIS_STALE_INPUT", errorCode { analysis.status(oldId) })
        assertEquals("ANALYSIS_STALE_INPUT", errorCode {
            analysis.open(file.path, secondSha, "arm64", requestedSession = oldId)
        })
    }

    @Test fun defineFunctionTypeAndNoreturnUpdatesStatusAndCommands() {
        val store = temp.newFolder("mutations")
        val now = 1_700_000_000_000L
        val file = input("libx.so", "mutations")
        val sha = sha(file)
        val analysis = ToolkitNativeAnalysis(store, { now })
        val id = analysis.open(file.path, sha, "aarch64").get("sessionId").asString

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
        val file = input("o.so", "overlay")
        val analysis = ToolkitNativeAnalysis(store, { 1_700_000_000_000L })
        val id = analysis.open(file.path, sha(file), "arm64").get("sessionId").asString
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
        val first = input("limit.so", "limit-first")
        val second = input("second.so", "limit-second")
        val third = input("third.so", "limit-third")
        val id = analysis.open(first.path, sha(first), "arm64").get("sessionId").asString
        analysis.defineFunction(id, "0x1", null, null)
        analysis.defineFunction(id, "0x2", null, null)
        assertEquals("ANALYSIS_FUNCTION_LIMIT", errorCode { analysis.defineFunction(id, "0x3", null, null) })
        analysis.defineFunction(id, "0x1", 16, "updated")
        analysis.overlay(id, "0x10", "0102", null)
        analysis.overlay(id, "0x11", "0304", null)
        assertEquals("ANALYSIS_OVERLAY_LIMIT", errorCode { analysis.overlay(id, "0x12", "05", null) })

        analysis.open(second.path, sha(second), "arm64")
        assertEquals("ANALYSIS_SESSION_LIMIT", errorCode {
            analysis.open(third.path, sha(third), "arm64")
        })

        now += 1001
        assertEquals("ANALYSIS_TTL_EXPIRED", errorCode { analysis.status(id) })
    }

    @Test fun saveThenNewInstanceRecoversFunctionsTypesNoreturnAndOverlay() {
        val store = temp.newFolder("recover")
        var now = 1_700_000_000_000L
        val file = input("resume.so", "resume")
        val sha = sha(file)
        val first = ToolkitNativeAnalysis(store, { now })
        val id = first.open(file.path, sha, "arm64", "resume").get("sessionId").asString
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

    @Test fun closeRetainsArchiveAndReopenRestoresCounts() {
        val store = temp.newFolder("close-restore")
        var now = 1_700_000_000_000L
        val file = input("close.so", "close-restore")
        val sha = sha(file)
        val first = ToolkitNativeAnalysis(store, { now })
        val id = first.open(file.path, sha, "arm64", "close-me").get("sessionId").asString
        first.defineFunction(id, "0x1000", 12, "kept_fn")
        first.setType(id, "0x1000", "int")
        first.setNoreturn(id, "0x1000", true)
        first.overlay(id, "0x1000", "90", "kept")
        first.save(id)

        val archive = archiveFile(store, sha, id)
        assertTrue(stateFile(store, sha, id).isFile)
        val closed = first.close(id)
        assertTrue(closed.get("closed").asBoolean)
        assertTrue(closed.get("archived").asBoolean)
        assertTrue("releasedBytes 保持旧语义", closed.get("releasedBytes").asLong > 0L)
        assertTrue(closed.get("retainedStateBytes").asLong > 0L)
        assertTrue("close 必须保留归档", archive.isFile)
        assertFalse("旧 statePath 已归档移走", stateFile(store, sha, id).exists())
        assertEquals(archive.canonicalPath, File(closed.get("archivePath").asString).canonicalPath)
        assertFalse(indexSessions(store).containsKey(id))
        assertEquals(0, first.list().get("total").asInt)
        assertEquals("ANALYSIS_SESSION_NOT_FOUND", errorCode { first.status(id) })

        now += 100
        val second = ToolkitNativeAnalysis(store, { now })
        val reopened = second.open(file.path, sha, "arm64")
        assertTrue(reopened.get("reused").asBoolean)
        assertTrue(reopened.get("restored").asBoolean)
        assertEquals(archive.canonicalPath, File(reopened.get("restoredFrom").asString).canonicalPath)
        assertTrue(reopened.get("restoreError").isJsonNull)
        assertEquals(id, reopened.get("sessionId").asString)
        assertEquals(1, reopened.get("functionCount").asInt)
        assertEquals(1, reopened.get("typeCount").asInt)
        assertEquals(1, reopened.get("noreturnCount").asInt)
        assertEquals(1, reopened.get("overlayCount").asInt)
        val status = second.status(id)
        assertEquals(1, status.get("functionCount").asInt)
        assertEquals("kept_fn", status.getAsJsonArray("functions").single().asJsonObject.get("name").asString)
    }

    @Test fun requestedSessionIdRestoresClosedArchive() {
        val store = temp.newFolder("close-requested")
        val file = input("close-requested.so", "close-requested")
        val sha = sha(file)
        val first = ToolkitNativeAnalysis(store, { 1_700_000_000_000L })
        val id = first.open(file.path, sha, "arm64", "requested-close").get("sessionId").asString
        first.defineFunction(id, "0x2000", 8, "requested_fn")
        first.save(id)
        first.close(id)

        val second = ToolkitNativeAnalysis(store, { 1_700_000_000_100L })
        val reopened = second.open(file.path, sha, "arm64", requestedSession = id)
        assertTrue(reopened.get("reused").asBoolean)
        assertTrue(reopened.get("restored").asBoolean)
        assertEquals(1, reopened.get("functionCount").asInt)
        val restoredStatus = second.status(id)
        assertEquals("requested_fn", restoredStatus.getAsJsonArray("functions").single().asJsonObject.get("name").asString)
    }

    @Test fun invalidArchiveReportsRestoreErrorInsteadOfSilentZero() {
        val store = temp.newFolder("bad-archive")
        val file = input("bad.so", "bad-archive")
        val sha = sha(file)
        val dir = File(store, sha.take(16))
        assertTrue(dir.exists() || dir.mkdirs())
        File(dir, "analysis-bad.json").writeText(
            "{\"schemaVersion\":2,\"sessionId\":\"bad\"}", Charsets.UTF_8,
        )
        val analysis = ToolkitNativeAnalysis(store, { 1_700_000_000_000L })

        val opened = analysis.open(file.path, sha, "arm64")
        assertFalse(opened.get("restored").asBoolean)
        assertFalse("坏归档不能静默恢复", opened.get("restoreError").isJsonNull)
        assertTrue(opened.get("restoreError").asString.contains("schemaVersion"))
        assertEquals(0, opened.get("functionCount").asInt)
        assertEquals(0, opened.get("typeCount").asInt)
        assertEquals(0, opened.get("noreturnCount").asInt)
        assertEquals(0, opened.get("overlayCount").asInt)
    }

    @Test fun listReportsSessionStatsAndLimits() {
        val store = temp.newFolder("list")
        val one = input("one.so", "list-one")
        val two = input("two.so", "list-two")
        val analysis = ToolkitNativeAnalysis(store, { 1_700_000_000_000L }, maxSessions = 3, maxFunctions = 10)
        val first = analysis.open(one.path, sha(one), "arm64").get("sessionId").asString
        analysis.open(two.path, sha(two), "x86_64")
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
