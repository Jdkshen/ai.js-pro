package com.jdkshen.aijspro.mcp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** UX-1 pure JVM tests for ToolkitR2Session (no r2 process, no Android, no gradle). */
class ToolkitR2SessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun errorCode(block: () -> Unit): String {
        val error = runCatching(block).exceptionOrNull()
        assertNotNull("expected exception", error)
        assertTrue("expected ToolkitException, got: " + error, error is ToolkitException)
        return (error as ToolkitException).code
    }

    private fun input(bytes: String = "ELF-test"): File =
        temp.newFile("input-" + System.nanoTime() + ".so").apply { writeText(bytes, Charsets.UTF_8) }

    private fun sha(file: File): String = ToolkitIo.sha(file)

    private fun argvStrings(json: com.google.gson.JsonObject): List<String> =
        json.getAsJsonArray("argv").map { it.asString }

    @Test fun openAndPlanUseProjectCommandsAndFirstRelocsFlag() {
        val file = input()
        val store = temp.newFolder("shapes")
        var now = 1_700_000_000_000L
        val session = ToolkitR2Session(store, { now })
        val opened = session.open(file.path, sha(file), relocsApply = true)
        val id = opened.get("sessionId").asString
        val project = opened.get("projectPath").asString
        assertEquals(sha(file).take(16), opened.get("sha16").asString)
        assertTrue(project.startsWith(File(store, sha(file).take(16)).path))
        assertTrue(project.endsWith(".project"))
        assertTrue(opened.get("relocsApply").asBoolean)

        val firstArgv = argvStrings(opened)
        assertTrue(firstArgv.contains("bin.relocs.apply=true"))
        val first = session.plan(id, null)
        assertTrue(first.get("first").asBoolean)
        assertEquals("create", first.get("phase").asString)
        assertTrue(first.get("command").asString.startsWith("aaa;Ps "))
        assertTrue(argvStrings(first).contains("bin.relocs.apply=true"))

        now += 5
        val second = session.plan(id, "aflj")
        assertFalse(second.get("first").asBoolean)
        assertEquals("query", second.get("phase").asString)
        assertEquals("Po " + project + ";aflj", second.get("command").asString)
        assertFalse(argvStrings(second).contains("bin.relocs.apply=true"))
        assertEquals(1, session.list().get("total").asInt)
    }

    @Test fun changedInputIsStaleBeforeRunner() {
        val file = input("original")
        val store = temp.newFolder("stale")
        var calls = 0
        val runner = R2Runner { _, _, _ ->
            calls++
            R2RunnerResult("ok")
        }
        val session = ToolkitR2Session(store, { 1_700_000_000_000L }, runner = runner)
        val id = session.open(file.path, sha(file)).get("sessionId").asString
        file.writeText("changed", Charsets.UTF_8)
        assertEquals("SESSION_STALE_INPUT", errorCode { session.query(id, "aflj") })
        assertEquals(0, calls)
    }

    @Test fun shellEscapeAndOversizedCommandsAreRejected() {
        val file = input()
        val store = temp.newFolder("commands")
        val session = ToolkitR2Session(store, { 1_700_000_000_000L }, maxCommandChars = 12)
        val id = session.open(file.path, sha(file)).get("sessionId").asString
        assertEquals("COMMAND_REJECTED", errorCode { session.plan(id, "aflj;!sh") })
        assertEquals("COMMAND_REJECTED", errorCode { session.query(id, "!ls") })
        assertEquals("COMMAND_TOO_LONG", errorCode { session.plan(id, "a".repeat(13)) })
    }

    @Test fun outputLimitAndTimeoutAreExplicit() {
        val file = input()
        val store = temp.newFolder("limits")
        val tooBig = ToolkitR2Session(
            store, { 1_700_000_000_000L }, maxOutputBytes = 8,
            runner = R2Runner { _, _, _ -> R2RunnerResult("0123456789") },
        )
        val id = tooBig.open(file.path, sha(file)).get("sessionId").asString
        assertEquals("OUTPUT_LIMIT", errorCode { tooBig.query(id, "aflj") })

        val timedOut = ToolkitR2Session(
            store, { 1_700_000_000_000L },
            runner = R2Runner { _, _, _ -> R2RunnerResult("", timedOut = true) },
        )
        val id2 = timedOut.open(file.path, sha(file)).get("sessionId").asString
        assertEquals("TIMEOUT", errorCode { timedOut.query(id2, "aflj", timeoutSeconds = 1) })

        val slow = ToolkitR2Session(
            store, { 1_700_000_000_000L },
            runner = R2Runner { _, _, _ ->
                Thread.sleep(2000)
                R2RunnerResult("late")
            },
        )
        val id3 = slow.open(file.path, sha(file)).get("sessionId").asString
        assertEquals("TIMEOUT", errorCode { slow.query(id3, "aflj", timeoutSeconds = 1) })
    }

    @Test fun ttlAndSessionLimitAreEnforced() {
        val file = input()
        val store = temp.newFolder("lifecycle")
        var now = 1_700_000_000_000L
        val session = ToolkitR2Session(store, { now }, ttlMillis = 1000, maxSessions = 1)
        val id = session.open(file.path, sha(file)).get("sessionId").asString
        assertEquals("SESSION_LIMIT", errorCode { session.open(file.path, sha(file)) })
        now += 1001
        assertEquals("SESSION_EXPIRED", errorCode { session.plan(id, null) })
        assertEquals(0, session.list().get("total").asInt)
        assertNotNull(session.open(file.path, sha(file)))
    }

    @Test fun closeReportsProjectDeletionAndRemainingSessions() {
        val file = input()
        val store = temp.newFolder("close")
        val session = ToolkitR2Session(store, { 1_700_000_000_000L })
        val opened = session.open(file.path, sha(file))
        val id = opened.get("sessionId").asString
        val project = File(opened.get("projectPath").asString)
        assertTrue(project.mkdirs())
        File(project, "r2-project-data").writeText("saved", Charsets.UTF_8)

        val closed = session.close(id)
        assertTrue(closed.get("projectExisted").asBoolean)
        assertTrue(closed.get("projectDeleted").asBoolean)
        assertFalse(project.exists())
        assertEquals(0, closed.get("remainingSessions").asInt)
        assertEquals(0, session.list().get("total").asInt)
        assertEquals("SESSION_NOT_FOUND", errorCode { session.close(id) })
    }

    @Test fun queryExecutesCreateThenLoadAndPassesBoundedOutputAndEnv() {
        val file = input()
        val store = temp.newFolder("query")
        val calls = ArrayList<List<String>>()
        val envs = ArrayList<Map<String, String>>()
        val timeouts = ArrayList<Int>()
        val runner = R2Runner { args, env, timeout ->
            calls.add(args)
            envs.add(env)
            timeouts.add(timeout)
            R2RunnerResult("result-" + calls.size)
        }
        val session = ToolkitR2Session(
            store, { 1_700_000_000_000L }, maxOutputBytes = 1024, runner = runner,
        )
        val id = session.open(
            file.path, sha(file), extraEnv = mapOf("FOO" to "bar"),
        ).get("sessionId").asString

        val first = session.query(id, "aflj", timeoutSeconds = 7)
        assertTrue(first.get("executed").asBoolean)
        assertEquals("result-1", first.get("output").asString)
        assertEquals("create+query", first.get("phase").asString)
        val firstArgv = first.get("argv").asJsonArray.map { it.asString }
        assertTrue(firstArgv.contains("bin.relocs.apply=true"))
        assertTrue(first.get("inputHashVerified").asBoolean)
        assertEquals("bar", envs.single()["FOO"])
        assertEquals(7, timeouts.single())
        assertEquals(file.path, calls.single().last())

        val second = session.query(id, "ij", timeoutSeconds = 3)
        assertEquals("query", second.get("phase").asString)
        assertEquals("result-2", second.get("output").asString)
        assertFalse(calls[1].contains("bin.relocs.apply=true"))
        val joined = calls[1].joinToString(" ")
        assertTrue(joined.contains("Po "))
        assertTrue(joined.contains(";ij;q"))
        assertEquals(3, timeouts[1])
    }

    @Test fun dryQueryWithoutRunnerReturnsPlanOnly() {
        val file = input()
        val store = temp.newFolder("dry")
        val session = ToolkitR2Session(store, { 1_700_000_000_000L })
        val id = session.open(file.path, sha(file)).get("sessionId").asString
        val dry = session.query(id, "aflj")
        assertFalse(dry.get("executed").asBoolean)
        assertEquals("create+query", dry.get("phase").asString)
        assertTrue(dry.get("command").asString.startsWith("aaa;Ps "))
    }
}
