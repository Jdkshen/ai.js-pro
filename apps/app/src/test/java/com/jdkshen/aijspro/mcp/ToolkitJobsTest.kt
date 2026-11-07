package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P0-D 后台任务：保留窗口/上限、JOB_NOT_FOUND / JOB_EVICTED / SERVICE_RESTARTED 区分、
 * 可选 owner/clientId/sessionId 与过滤、元数据与输出缓存分离。
 */
class ToolkitJobsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        pairs.forEach { (name, value) ->
            when (value) {
                null -> add(name, com.google.gson.JsonNull.INSTANCE)
                is com.google.gson.JsonElement -> add(name, value)
                is String -> addProperty(name, value)
                is Int -> addProperty(name, value)
                is Long -> addProperty(name, value)
                is Boolean -> addProperty(name, value)
                else -> throw IllegalArgumentException("unsupported test value: " + name)
            }
        }
    }

    private fun immediateJobs(folder: File, value: String = "abcdef"): ToolkitJobs =
        ToolkitJobs(folder) { _, _ -> ToolkitIo.ok(ToolkitIo.json("value" to value)) }

    private fun waitFinal(jobs: ToolkitJobs, jobId: String, timeoutMillis: Long = 5000L): JsonObject {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < end) {
            val state = jobs.status(obj("jobId" to jobId)).get("state").asString
            if (state in setOf("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS", "FAILED", "CANCELLED", "INTERRUPTED")) {
                return jobs.status(obj("jobId" to jobId))
            }
            Thread.sleep(5)
        }
        throw AssertionError("任务未在超时前结束")
    }

    /** registry/结果文件由 worker 的 finally 原子替换，读之前轮询等待落盘（rename 瞬间会有空窗）。 */
    private fun readTextEventually(file: File, timeoutMillis: Long = 3000L): String {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < end) {
            if (file.isFile) return file.readText()
            Thread.sleep(5)
        }
        throw AssertionError("文件未在超时前落盘：" + file.path)
    }

    private fun codeOf(error: Throwable?): String {
        assertTrue("expected ToolkitException, got " + error, error is ToolkitException)
        return (error as ToolkitException).code
    }

    @Test
    fun statusExposesIdentityAndSeparatedStorage() {
        val folder = temp.newFolder("jobs")
        val jobs = immediateJobs(folder)
        val started = jobs.start(
            obj(
                "operation" to "apk_search",
                "arguments" to ToolkitIo.json("query" to "x"),
                "timeoutSeconds" to 10,
                "owner" to "alice",
                "clientId" to "client-1",
                "sessionId" to "session-1",
            ),
        )
        val jobId = started.get("jobId").asString
        assertNotNull(jobId)
        val status = waitFinal(jobs, jobId)
        assertEquals("alice", status.get("owner").asString)
        assertEquals("client-1", status.get("clientId").asString)
        assertEquals("session-1", status.get("sessionId").asString)
        assertEquals("SUCCEEDED", status.get("state").asString)
        assertTrue(status.get("resultAvailable").asBoolean)
        assertTrue(status.get("resultBytes").asLong > 0L)
        assertFalse(status.get("lostByRestart").asBoolean)
        val storage = status.getAsJsonObject("storage")
        assertEquals(jobId + ".json", storage.get("metadataFile").asString)
        assertEquals(jobId + ".result.json", storage.get("resultFile").asString)
        assertTrue(storage.get("metadataAndResultSeparated").asBoolean)
        assertTrue(File(folder, jobId + ".json").isFile)
        assertTrue(File(folder, jobId + ".result.json").isFile)

        val metadata = File(folder, jobId + ".json").readText()
        assertFalse("metadata must not contain the output payload", metadata.contains("abcdef"))
        val result = File(folder, jobId + ".result.json").readText()
        assertTrue(result.contains("abcdef"))
        val registry = readTextEventually(File(folder, ToolkitJobs.INDEX_FILE))
        assertTrue(registry.contains(jobId))
        assertFalse("registry must stay lightweight", registry.contains("abcdef"))
    }

    @Test
    fun listFiltersByOwnerWithoutChangingLegacyBehavior() {
        val folder = temp.newFolder("jobs")
        val jobs = immediateJobs(folder)
        val alice = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10, "owner" to "alice",
        )).get("jobId").asString
        waitFinal(jobs, alice)
        val bob = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10, "owner" to "bob",
        )).get("jobId").asString
        waitFinal(jobs, bob)

        val all = jobs.list()
        assertEquals(2, all.get("count").asInt)
        assertTrue(all.has("retention"))
        assertTrue(all.has("service"))
        assertTrue(all.has("evicted"))

        val onlyAlice = jobs.list(obj("owner" to "alice"))
        assertEquals(1, onlyAlice.get("count").asInt)
        assertEquals("alice", onlyAlice.getAsJsonArray("items").get(0).asJsonObject.get("owner").asString)

        val nobody = jobs.list(obj("owner" to "nobody"))
        assertEquals(0, nobody.get("count").asInt)
    }

    @Test
    fun ownerMismatchOnStatusAndCancelIsJobNotFound() {
        val folder = temp.newFolder("jobs")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobs = ToolkitJobs(folder, { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            ToolkitIo.ok(ToolkitIo.json("value" to "cancelled"))
        }, ToolkitJobs.DEFAULT_RETENTION_WINDOW_MILLIS, ToolkitJobs.DEFAULT_MAX_RETAINED_FINISHED, 4,
            { System.currentTimeMillis() })
        val jobId = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10, "owner" to "alice",
        )).get("jobId").asString
        assertTrue(started.await(3, TimeUnit.SECONDS))

        assertEquals("JOB_NOT_FOUND", codeOf(runCatching {
            jobs.status(obj("jobId" to jobId, "owner" to "bob"))
        }.exceptionOrNull()))
        assertEquals("JOB_NOT_FOUND", codeOf(runCatching {
            jobs.cancel(obj("jobId" to jobId, "owner" to "bob"))
        }.exceptionOrNull()))
        assertEquals("alice", jobs.status(obj("jobId" to jobId, "owner" to "alice")).get("owner").asString)
        assertTrue(jobs.cancel(obj("jobId" to jobId, "owner" to "alice")).get("cancellationRequested").asBoolean)
        release.countDown()
        waitFinal(jobs, jobId)
    }

    @Test
    fun unknownJobIdIsJobNotFound() {
        val jobs = immediateJobs(temp.newFolder("jobs"))
        assertEquals("JOB_NOT_FOUND", codeOf(runCatching {
            jobs.status(obj("jobId" to ToolkitIo.fresh()))
        }.exceptionOrNull()))
        assertEquals("JOB_NOT_FOUND", codeOf(runCatching {
            jobs.result(obj("jobId" to ToolkitIo.fresh()))
        }.exceptionOrNull()))
    }

    @Test
    fun retentionWindowEvictsToJobEvictedAndSurvivesRestart() {
        val folder = temp.newFolder("jobs")
        var now = 1000L
        val jobs = ToolkitJobs(folder, { _, _ -> ToolkitIo.ok(ToolkitIo.json("value" to "abcdef")) },
            100L, 10, 4) { now }
        val jobId = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, jobId)
        assertTrue(File(folder, jobId + ".result.json").isFile)

        now += 1000L
        val listing = jobs.list()
        assertEquals(0, listing.get("count").asInt)
        val evicted = listing.getAsJsonArray("evicted")
        assertTrue(evicted.size() >= 1)
        assertEquals("JOB_EVICTED", evicted.get(0).asJsonObject.get("code").asString)
        assertFalse(File(folder, jobId + ".result.json").isFile)
        assertEquals("JOB_EVICTED", codeOf(runCatching {
            jobs.status(obj("jobId" to jobId))
        }.exceptionOrNull()))

        // registry 持久化后，新实例仍能区分「被淘汰」与「从未存在」。
        val restarted = ToolkitJobs(folder, { _, _ -> ToolkitIo.json() })
        assertEquals("JOB_EVICTED", codeOf(runCatching {
            restarted.status(obj("jobId" to jobId))
        }.exceptionOrNull()))
        assertEquals("JOB_NOT_FOUND", codeOf(runCatching {
            restarted.status(obj("jobId" to ToolkitIo.fresh()))
        }.exceptionOrNull()))
    }

    @Test
    fun retentionCountCapEvictsOldestAndKeepsNewest() {
        val folder = temp.newFolder("jobs")
        var now = 1000L
        val jobs = ToolkitJobs(folder, { _, _ -> ToolkitIo.ok(ToolkitIo.json("value" to "abcdef")) },
            ToolkitJobs.DEFAULT_RETENTION_WINDOW_MILLIS, 1, 4) { now }
        val oldest = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, oldest)
        now += 100L
        val newest = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, newest)

        assertEquals(1, jobs.list().get("count").asInt)
        assertEquals("JOB_EVICTED", codeOf(runCatching {
            jobs.status(obj("jobId" to oldest))
        }.exceptionOrNull()))
        assertEquals("SUCCEEDED", jobs.status(obj("jobId" to newest)).get("state").asString)
    }

    @Test
    fun queueLimitRejectsWithExplicitCode() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jobs = ToolkitJobs(temp.newFolder("jobs"), { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            ToolkitIo.ok(ToolkitIo.json("value" to "x"))
        }, ToolkitJobs.DEFAULT_RETENTION_WINDOW_MILLIS, ToolkitJobs.DEFAULT_MAX_RETAINED_FINISHED, 1,
            { System.currentTimeMillis() })
        val first = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        ))
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val queueError = runCatching {
            jobs.start(obj("operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10))
        }.exceptionOrNull()
        assertEquals("JOB_QUEUE_FULL", codeOf(queueError))
        val queueMessage = queueError?.message.orEmpty()
        assertTrue(queueMessage, queueMessage.contains("apk_job_list"))
        assertTrue(queueMessage, queueMessage.contains("details="))
        assertTrue(queueMessage, queueMessage.contains("nextActions"))
        release.countDown()
        waitFinal(jobs, first.get("jobId").asString)
    }

    @Test
    fun serviceRestartMarksUnfinishedJobAsServiceRestarted() {
        val folder = temp.newFolder("jobs")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = ToolkitJobs(folder, { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            ToolkitIo.ok(ToolkitIo.json("value" to "late"))
        })
        val jobId = first.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 30,
        )).get("jobId").asString
        assertTrue(started.await(3, TimeUnit.SECONDS))
        assertEquals("RUNNING", first.status(obj("jobId" to jobId)).get("state").asString)

        // 模拟工具进程重启：同一目录新建实例，旧实例的 RUNNING 元数据被恢复为 INTERRUPTED。
        val restarted = ToolkitJobs(folder, { _, _ -> fail("restarted service must not replay jobs"); ToolkitIo.json() })
        val status = restarted.status(obj("jobId" to jobId))
        assertEquals("INTERRUPTED", status.get("state").asString)
        assertTrue(status.get("lostByRestart").asBoolean)
        assertEquals("SERVICE_RESTARTED", status.get("errorCode").asString)
        assertNotNull(status.get("errorHint"))
        assertEquals("SERVICE_RESTARTED", codeOf(runCatching {
            restarted.result(obj("jobId" to jobId))
        }.exceptionOrNull()))
        assertEquals("JOB_NOT_FOUND", codeOf(runCatching {
            restarted.status(obj("jobId" to ToolkitIo.fresh()))
        }.exceptionOrNull()))
        assertTrue(restarted.list().getAsJsonObject("service").get("restarted").asBoolean)

        release.countDown()
        waitFinal(first, jobId)
    }

    @Test
    fun registryOnlyJobWithoutOutputBecomesServiceRestartedTombstone() {
        val folder = temp.newFolder("jobs")
        val jobId = ToolkitIo.fresh()
        val registry = ToolkitIo.json(
            "version" to ToolkitJobs.REGISTRY_VERSION,
            "serviceInstance" to "previous-service",
            "serviceStartedAt" to 1L,
            "previousServiceInstance" to null,
            "updatedAt" to 2L,
            "jobs" to listOf(ToolkitIo.json(
                "jobId" to jobId,
                "operation" to "apk_search",
                "state" to "RUNNING",
                "createdAt" to 1L,
                "owner" to "alice",
                "lostByRestart" to false,
            )),
            "tombstones" to emptyList<Any>(),
        )
        File(folder, ToolkitJobs.INDEX_FILE).writeText(registry.toString())

        val jobs = ToolkitJobs(folder) { _, _ -> ToolkitIo.json() }
        assertEquals("SERVICE_RESTARTED", codeOf(runCatching {
            jobs.status(obj("jobId" to jobId))
        }.exceptionOrNull()))
        val listing = jobs.list()
        assertEquals(0, listing.get("count").asInt)
        val evicted = listing.getAsJsonArray("evicted")
        assertTrue(evicted.size() >= 1)
        val record = evicted.get(0).asJsonObject
        assertEquals("SERVICE_RESTARTED", record.get("code").asString)
        assertEquals(jobId, record.get("jobId").asString)
        assertEquals("alice", record.get("owner").asString)
        assertTrue(listing.getAsJsonObject("service").get("restarted").asBoolean)
        assertEquals("JOB_NOT_FOUND", codeOf(runCatching {
            jobs.status(obj("jobId" to ToolkitIo.fresh()))
        }.exceptionOrNull()))
    }

    @Test
    fun resultPagingAndFieldsRemainCompatible() {
        val jobs = immediateJobs(temp.newFolder("jobs"))
        val started = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        ))
        val jobId = started.get("jobId").asString
        waitFinal(jobs, jobId)
        val first = jobs.result(obj("jobId" to jobId, "maxChars" to 5))
        val second = jobs.result(obj("jobId" to jobId, "offset" to first.get("nextOffset").asInt))
        val combined = first.get("content").asString + second.get("content").asString
        assertEquals("abcdef", JsonParser().parse(combined).asJsonObject.getAsJsonObject("data").get("value").asString)
        assertEquals(jobId, first.get("jobId").asString)
        assertEquals("SUCCEEDED", first.get("state").asString)
        assertTrue(first.has("totalChars"))
        assertTrue(first.get("hasMore").asBoolean)
        assertFalse(second.get("hasMore").asBoolean)
    }

    @Test
    fun noOwnerKeepsLegacyBehaviour() {
        val jobs = immediateJobs(temp.newFolder("jobs"))
        val started = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        ))
        val jobId = started.get("jobId").asString
        val status = waitFinal(jobs, jobId)
        assertTrue(status.get("owner").isJsonNull)
        assertTrue(status.get("clientId").isJsonNull)
        assertTrue(status.get("sessionId").isJsonNull)
        assertEquals("SUCCEEDED", status.get("state").asString)
    }

    @Test
    fun listFiltersAndPaginatesWithoutChangingLegacyDefaults() {
        val folder = temp.newFolder("jobs")
        val jobs = immediateJobs(folder)
        fun start(operation: String, owner: String) = jobs.start(obj(
            "operation" to operation, "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10, "owner" to owner,
        )).get("jobId").asString
        val a = start("apk_search", "alice")
        val b = start("apk_search", "bob")
        val c = start("apk_verify", "alice")
        listOf(a, b, c).forEach { waitFinal(jobs, it) }

        val all = jobs.list()
        assertEquals(3, all.get("total").asInt)
        assertEquals(3, all.get("count").asInt)
        assertFalse(all.get("hasMore").asBoolean)
        assertEquals(1, all.get("effectiveConcurrency").asInt)
        assertTrue(all.get("maxActiveMeaning").asString.contains("不是实际并行"))
        assertTrue(all.get("maxActiveNote").asString.contains("不代表"))

        val byOperation = jobs.list(obj("operation" to "apk_search"))
        assertEquals(2, byOperation.get("total").asInt)
        assertEquals(2, byOperation.get("count").asInt)
        byOperation.getAsJsonArray("items").forEach { assertEquals("apk_search", it.asJsonObject.get("operation").asString) }

        val byOwner = jobs.list(obj("owner" to "alice"))
        assertEquals(2, byOwner.get("total").asInt)
        val byState = jobs.list(obj("state" to "SUCCEEDED"))
        assertEquals(3, byState.get("total").asInt)
        assertEquals(0, jobs.list(obj("state" to "FAILED")).get("total").asInt)

        val first = jobs.list(obj("limit" to 2))
        assertEquals(3, first.get("total").asInt)
        assertEquals(2, first.get("count").asInt)
        assertTrue(first.get("hasMore").asBoolean)
        assertNotNull(first.get("nextCursor"))
        val cursor = first.get("nextCursor").asString
        val second = jobs.list(obj("limit" to 2, "cursor" to cursor))
        assertEquals(1, second.get("count").asInt)
        assertFalse(second.get("hasMore").asBoolean)
        val ids = (first.getAsJsonArray("items") + second.getAsJsonArray("items"))
            .map { it.asJsonObject.get("jobId").asString }.toSet()
        assertEquals(setOf(a, b, c), ids)

        assertTrue(runCatching { jobs.list(obj("cursor" to "bad")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun latestResultForLocatorIsIndexedAndMarked() {
        val folder = temp.newFolder("jobs")
        var now = 1000L
        val jobs = ToolkitJobs(folder, { _, _ -> ToolkitIo.json("content" to "latest") },
            ToolkitJobs.DEFAULT_RETENTION_WINDOW_MILLIS, ToolkitJobs.DEFAULT_MAX_RETAINED_FINISHED, 4) { now++ }
        val locator = "dex_class:Lcom/example/Foo;"
        val first = jobs.start(obj(
            "operation" to "apk_decompile_class",
            "arguments" to ToolkitIo.json("locator" to locator), "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, first)
        val second = jobs.start(obj(
            "operation" to "apk_decompile_class",
            "arguments" to ToolkitIo.json("locator" to locator), "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, second)

        val listed = jobs.list(obj("locator" to locator))
        assertEquals(2, listed.get("total").asInt)
        val entries = listed.getAsJsonArray("latestByLocator").map { it.asJsonObject }
        val latest = entries.single { it.get("locator").asString == locator }
        assertEquals(second, latest.get("jobId").asString)
        val items = listed.getAsJsonArray("items").map { it.asJsonObject }.associateBy { it.get("jobId").asString }
        assertFalse(items.getValue(first).get("latestForLocator").asBoolean)
        assertTrue(items.getValue(second).get("latestForLocator").asBoolean)
        val index = File(folder, ToolkitJobs.LOCATOR_INDEX_FILE)
        assertTrue(index.isFile)
        val indexEntry = JsonParser().parse(index.readText()).asJsonObject
            .getAsJsonArray("entries").map { it.asJsonObject }.single { it.get("locator").asString == locator }
        assertEquals(second, indexEntry.get("jobId").asString)
    }

    @Test
    fun resultExposesTopLevelTextAndSuggestedExportPath() {
        val folder = temp.newFolder("jobs")
        val root = folder.parentFile ?: error("jobs parent")
        val project = File(root, "MCP测试").apply { mkdirs() }
        File(root, "project.json").writeText(ToolkitIo.json(
            "projectDir" to project.path,
            "name" to "MCP测试",
        ).toString())
        val content = "package defpackage;\n\npublic class Demo {}\n"
        val jobs = ToolkitJobs(folder) { _, _ ->
            ToolkitIo.ok(ToolkitIo.json(
                "content" to content,
                "decompileIncomplete" to false,
                "warningCode" to "",
                "errorsCount" to 0,
            ))
        }
        val jobId = jobs.start(obj(
            "operation" to "apk_decompile_class",
            "arguments" to ToolkitIo.json(),
            "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, jobId)
        val data = jobs.result(obj("jobId" to jobId, "format" to "text"))
        assertEquals(content, data.get("text").asString)
        assertEquals(content.length, data.get("chars").asInt)
        assertEquals(content.length, data.get("nextOffset").asInt)
        assertFalse(data.get("truncated").asBoolean)
        val suggested = data.get("suggestedExportPath").asString
        assertTrue(suggested.startsWith(project.path))
        assertTrue(suggested.endsWith(".txt"))
        assertTrue(data.get("exportHint").asString.contains("apk_job_export"))
        assertTrue(data.get("exportHint").asString.contains(jobId))
        val page = jobs.result(obj("jobId" to jobId, "format" to "text", "maxChars" to 5))
        assertEquals(5, page.get("chars").asInt)
        assertTrue(page.get("truncated").asBoolean)
        assertEquals(5, page.get("nextOffset").asInt)
        assertTrue(page.get("text").asString.length <= 5)
    }

    @Test
    fun infoCommentsAreNotesButRealIncompleteIsWarning() {
        val folder = temp.newFolder("jobs")
        fun jobs(content: String, incomplete: Boolean, warningCode: String = ""): ToolkitJobs =
            ToolkitJobs(folder) { _, _ ->
                ToolkitIo.ok(ToolkitIo.json(
                    "content" to content,
                    "decompileIncomplete" to incomplete,
                    "warningCode" to warningCode,
                    "errorsCount" to if (incomplete) 1 else 0,
                    "warningsCount" to 1,
                    "incompleteReason" to if (incomplete) "反编译结果不完整：errorsCount=1" else "",
                ))
            }
        fun start(jobs: ToolkitJobs): String = jobs.start(obj(
            "operation" to "apk_decompile_class",
            "arguments" to ToolkitIo.json(),
            "timeoutSeconds" to 10,
        )).get("jobId").asString

        val info = jobs("JADX INFO: regular comment\nclass Demo {}\n", incomplete = true)
        val infoStatus = waitFinal(info, start(info))
        assertEquals("SUCCEEDED", infoStatus.get("state").asString)
        assertTrue(infoStatus.get("warningCode").isJsonNull)
        assertTrue(infoStatus.get("warningReason").isJsonNull)
        assertTrue(infoStatus.get("notes").asJsonArray.any { it.asString.contains("JADX INFO") })

        val real = jobs("JADX ERROR: failed to decompile\n", incomplete = true, warningCode = "DECOMPILE_INCOMPLETE")
        val realId = start(real)
        val realStatus = waitFinal(real, realId)
        assertEquals("SUCCEEDED_WITH_WARNINGS", realStatus.get("state").asString)
        assertEquals("DECOMPILE_INCOMPLETE", realStatus.get("warningCode").asString)
        assertTrue(realStatus.get("warningReason").asString.isNotBlank())
        assertTrue(realStatus.get("notes").asJsonArray.any { it.asString.contains("JADX ERROR") })
        val realResult = real.result(obj("jobId" to realId))
        assertEquals(realStatus.get("warningReason").asString, realResult.get("warningReason").asString)
    }

    @Test
    fun exportWritesExactRawResultAndMetadata() {
        val folder = temp.newFolder("jobs")
        val root = temp.newFolder("root")
        val jobs = immediateJobs(folder, "abcdef")
        val jobId = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, jobId)
        val raw = jobs.result(obj("jobId" to jobId)).get("content").asString
        val out = File(root, "archive.json")
        val exported = jobs.export(root, obj("jobId" to jobId, "outPath" to "archive.json", "organize" to false))
        assertEquals(raw, out.readText())
        assertEquals(jobId, exported.get("jobId").asString)
        assertEquals(out.path, exported.get("outPath").asString)
        assertEquals(out.length().toLong(), exported.get("bytes").asLong)
        assertEquals(raw.length, exported.get("chars").asInt)
        assertEquals(ToolkitIo.sha(out), exported.get("sha256").asString)
        assertFalse(exported.get("truncated").asBoolean)
        assertTrue(exported.getAsJsonObject("retention").has("windowMillis"))
    }

    @Test
    fun exportOrganizesBareOutPathButKeepsExplicitSubdir() {
        val folder = temp.newFolder("jobs")
        val root = folder.parentFile ?: error("jobs parent")
        val jobs = immediateJobs(folder)
        val jobId = jobs.start(obj(
            "operation" to "apk_search",
            "arguments" to ToolkitIo.json("query" to "x"),
            "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, jobId)

        val organized = jobs.export(root, obj("jobId" to jobId, "outPath" to "exported.json", "organize" to true))
        val organizedPath = File(organized.get("outPath").asString)
        assertTrue(organizedPath.parentFile.name.startsWith("mcp-out-"))
        assertTrue(organized.has("organized"))
        // 首次归置会自动建 mcp-out-YYYYMMDD，因此 reason 是 auto-project。
        assertEquals("auto-project", organized.getAsJsonObject("organized").get("reason").asString)
        assertFalse(File(root, "exported.json").exists())

        val explicitDir = File(root, "explicit"); assertTrue(explicitDir.mkdirs())
        val kept = jobs.export(root, obj("jobId" to jobId, "outPath" to "explicit/kept.json", "organize" to true))
        assertEquals(File(explicitDir, "kept.json").path, kept.get("outPath").asString)
        assertFalse(kept.has("organized"))
    }

    @Test
    fun exportPagedLargeResultReassemblesExactly() {
        val folder = temp.newFolder("jobs")
        val root = temp.newFolder("root")
        val expectedText = "x".repeat(50_000)
        val jobs = ToolkitJobs(folder) { _, _ -> ToolkitIo.json("content" to expectedText) }
        val jobId = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, jobId)
        val full = jobs.result(obj("jobId" to jobId)).get("content").asString
        val joined = StringBuilder()
        var offset = 0
        var pageIndex = 0
        while (true) {
            val page = jobs.export(root, obj(
                "jobId" to jobId, "outPath" to "page-" + pageIndex + ".json", "organize" to false,
                "offset" to offset, "maxChars" to 1000,
            ))
            val part = File(root, "page-" + pageIndex + ".json").readText()
            joined.append(part)
            assertEquals(part.length, page.get("chars").asInt)
            assertTrue(page.get("chars").asInt <= 1000)
            if (!page.get("truncated").asBoolean) break
            offset = page.get("nextOffset").asInt
            assertTrue(offset > 0)
            pageIndex++
            assertTrue(pageIndex < 100)
        }
        assertTrue(pageIndex > 0)
        assertEquals(full, joined.toString())
    }

    @Test
    fun exportEvictedJobReportsTombstoneAndCreatesNoFile() {
        val folder = temp.newFolder("jobs")
        val root = temp.newFolder("root")
        val jobs = immediateJobs(folder)
        val jobId = jobs.start(obj(
            "operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10,
        )).get("jobId").asString
        waitFinal(jobs, jobId)
        val kept = File(root, "kept.json")
        jobs.export(root, obj("jobId" to jobId, "outPath" to "kept.json", "organize" to false))
        val keptText = kept.readText()
        assertTrue(kept.isFile)
        // Reload with zero finished retention: constructor trim must evict the completed job.
        val evicting = ToolkitJobs(
            folder, { _, _ -> ToolkitIo.json() }, ToolkitJobs.DEFAULT_RETENTION_WINDOW_MILLIS, 0, 4,
        ) { System.currentTimeMillis() }
        val error = runCatching {
            evicting.export(root, obj("jobId" to jobId, "outPath" to "evicted.json", "organize" to false))
        }.exceptionOrNull()
        assertEquals("JOB_EVICTED", codeOf(error))
        assertFalse(File(root, "evicted.json").exists())
        // The exported file is outside the jobs cache; eviction must not touch it.
        assertTrue(kept.isFile)
        assertEquals(keptText, kept.readText())
    }

}
