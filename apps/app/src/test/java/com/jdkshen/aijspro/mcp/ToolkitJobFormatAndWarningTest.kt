package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * task-36：① P0-4 文本型任务默认 format=text（顶层 content/text 直接是正文，不再 JSON-in-JSON），
 * 显式 json 保持旧形状并回显 formatDefaulted；② P1-5 SUCCEEDED_WITH_WARNINGS 只对真 warning，
 * JADX INFO/全局计数只进 notes（notesCount），并回显 warningSource/warningCriteria。
 */
class ToolkitJobFormatAndWarningTest {
    @get:Rule val temp = TemporaryFolder()

    private fun body(): String = "package com.example;\npublic class A {\n    void noop() {}\n}\n"

    private fun jobsFor(folder: File, data: JsonObject): ToolkitJobs =
        ToolkitJobs(folder) { _, _ -> ToolkitIo.ok(data) }

    private fun startJob(jobs: ToolkitJobs, operation: String): String =
        jobs.start(ToolkitIo.json(
            "operation" to operation, "arguments" to JsonObject(), "timeoutSeconds" to 10,
        )).get("jobId").asString

    private fun waitFinal(jobs: ToolkitJobs, jobId: String, timeoutMillis: Long = 5000L): JsonObject {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < end) {
            val status = jobs.status(ToolkitIo.json("jobId" to jobId))
            if (status.get("state").asString in setOf("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS", "FAILED", "CANCELLED", "INTERRUPTED")) {
                return status
            }
            Thread.sleep(5)
        }
        throw AssertionError("任务未在超时前结束")
    }

    private fun result(jobs: ToolkitJobs, jobId: String, vararg extra: Pair<String, Any?>): JsonObject =
        jobs.result(ToolkitIo.json("jobId" to jobId, *extra))

    /** ① 文本型任务默认 text；显式 json 时保持旧形状。 */
    @Test fun textJobDefaultsToTextFormatAndExplicitJsonKeepsLegacyShape() {
        val jobs = jobsFor(temp.newFolder("jobs-text"), ToolkitIo.json("content" to body(), "nextOffset" to body().length))
        val jobId = startJob(jobs, "apk_decompile_class")
        assertEquals("SUCCEEDED", waitFinal(jobs, jobId).get("state").asString)

        val text = result(jobs, jobId)
        assertEquals("text", text.get("format").asString)
        assertTrue("默认值必须回显以便审计", text.get("formatDefaulted").asBoolean)
        assertEquals("default", text.get("formatSource").asString)
        assertEquals("text", text.get("pagingSource").asString)
        assertTrue("content 应直接是正文首行，实际=" + text.get("content").asString.take(40), text.get("content").asString.startsWith("package "))
        assertFalse("不能再是 JSON-in-JSON", text.get("content").asString.startsWith("{\"ok\":"))
        assertEquals(text.get("content").asString, text.get("text").asString)
        assertEquals(body().length, text.get("totalChars").asInt)
        assertEquals(body().length, text.get("nextOffset").asInt)
        assertFalse(text.get("hasMore").asBoolean)

        val legacy = result(jobs, jobId, "format" to "json")
        assertEquals("json", legacy.get("format").asString)
        assertFalse("显式传值不算 default", legacy.get("formatDefaulted").asBoolean)
        assertEquals("explicit", legacy.get("formatSource").asString)
        assertEquals("raw_json", legacy.get("pagingSource").asString)
        assertTrue(
            "显式 json 必须保持旧的原始结果形状，实际=" + legacy.get("content").asString.take(40),
            legacy.get("content").asString.startsWith("{\"ok\":"),
        )
    }

    /** ② 只有 INFO 注释/全局计数 → SUCCEEDED + notes（notesCount）。 */
    @Test fun infoCommentsAndGlobalCountsBecomeNotesWithoutRaisingState() {
        val content = body() + "/* JADX INFO: loaded from: classes.dex */\n"
        val jobs = jobsFor(temp.newFolder("jobs-info"), ToolkitIo.json(
            "content" to content, "decompileIncomplete" to true, "errorsCount" to 1, "warningsCount" to 2,
        ))
        val jobId = startJob(jobs, "apk_decompile_class")
        val status = waitFinal(jobs, jobId)
        assertEquals("INFO/全局计数不能抬状态", "SUCCEEDED", status.get("state").asString)
        assertTrue("notesCount 应 > 0", status.get("notesCount").asInt > 0)
        assertEquals(status.get("notesCount").asInt, status.get("noteCount").asInt)
        assertTrue(status.get("warningSource").isJsonNull)
        assertTrue(status.get("warningCode").isJsonNull)
        val notes = status.getAsJsonArray("notes").joinToString(" ") { it.asString }
        assertTrue(notes, notes.contains("JADX INFO"))

        val payload = result(jobs, jobId)
        assertEquals("SUCCEEDED", payload.get("state").asString)
        assertTrue(payload.get("notesCount").asInt > 0)
    }

    /** ③ 真 warning（解码失败/空正文/截断）仍然是 SUCCEEDED_WITH_WARNINGS，并回显判据来源。 */
    @Test fun realWarningsKeepSucceededWithWarningsAndEchoCriteria() {
        val cases = listOf(
            Triple("decode_error", "/* JADX ERROR: Method dump skipped, instruction units count: 1 */", "DECOMPILE_INCOMPLETE"),
            Triple("empty_result", "", "EMPTY_RESULT"),
            Triple("truncated", body(), "RESULT_TRUNCATED"),
        )
        cases.forEachIndexed { index, (expectedSource, content, warningCode) ->
            val jobs = jobsFor(temp.newFolder("jobs-warn-" + index), ToolkitIo.json(
                "content" to content, "decompileIncomplete" to true, "warningCode" to warningCode,
            ))
            val jobId = startJob(jobs, "apk_decompile_class")
            val status = waitFinal(jobs, jobId)
            assertEquals("case=" + expectedSource + " 必须是 SUCCEEDED_WITH_WARNINGS", "SUCCEEDED_WITH_WARNINGS", status.get("state").asString)
            assertEquals("case=" + expectedSource, expectedSource, status.get("warningSource").asString)
            assertTrue(status.get("warningReason").asString.isNotBlank())
            assertEquals(ToolkitJobs.WARNING_CRITERIA, status.get("warningCriteria").asString)
            val payload = result(jobs, jobId)
            assertEquals(expectedSource, payload.get("warningSource").asString)
            assertEquals(ToolkitJobs.WARNING_CRITERIA, payload.get("warningCriteria").asString)
            assertTrue(payload.get("notesCount").asInt >= 0)
        }
    }

    /** ④ text 形态分页自洽：nextOffset 续读拼回完整正文；raw* 另记原始结果偏移。 */
    @Test fun textPagingIsSelfConsistentAndRawOffsetsStaySeparate() {
        val longBody = "package com.example;\n" + "x".repeat(180)
        val jobs = jobsFor(temp.newFolder("jobs-page"), ToolkitIo.json("content" to longBody))
        val jobId = startJob(jobs, "apk_decompile_class")
        assertEquals("SUCCEEDED", waitFinal(jobs, jobId).get("state").asString)

        val first = result(jobs, jobId, "maxChars" to 50)
        assertEquals(50, first.get("content").asString.length)
        assertEquals(50, first.get("nextOffset").asInt)
        assertTrue(first.get("hasMore").asBoolean)
        assertTrue(first.get("truncated").asBoolean)
        assertEquals(longBody.length, first.get("totalChars").asInt)
        assertTrue("raw* 必须另记原始结果长度", first.get("rawTotalChars").asInt > first.get("totalChars").asInt)

        val second = result(jobs, jobId, "maxChars" to 50, "offset" to first.get("nextOffset").asInt)
        assertEquals(first.get("content").asString + second.get("content").asString, longBody.substring(0, 100))
        assertEquals(100, second.get("nextOffset").asInt)

        // 一直续读到结束，拼回来必须与正文完全一致
        val builder = StringBuilder()
        var offset = 0
        var guard = 0
        while (guard++ < 50) {
            val page = result(jobs, jobId, "maxChars" to 50, "offset" to offset)
            builder.append(page.get("content").asString)
            offset = page.get("nextOffset").asInt
            if (!page.get("hasMore").asBoolean) break
        }
        assertEquals(longBody, builder.toString())
    }
}
