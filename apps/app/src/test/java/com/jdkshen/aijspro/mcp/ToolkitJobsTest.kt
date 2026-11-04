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
            if (state in setOf("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED")) {
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
        assertEquals("JOB_QUEUE_FULL", codeOf(runCatching {
            jobs.start(obj("operation" to "apk_search", "arguments" to ToolkitIo.json(), "timeoutSeconds" to 10))
        }.exceptionOrNull()))
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
}
