package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpTerminalJobsTest {
    @get:Rule val temp = TemporaryFolder()

    private class Child : Process() {
        val stdout = PipedInputStream(65536)
        val producer = PipedOutputStream(stdout)
        val stdin = ByteArrayOutputStream()
        val ended = CountDownLatch(1)
        var code = 0
        fun print(text: String) { producer.write(text.toByteArray(Charsets.UTF_8)); producer.flush() }
        fun finish(exit: Int = 0) { code = exit; producer.close(); ended.countDown() }
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = stdin
        override fun waitFor(): Int { ended.await(); return code }
        override fun exitValue(): Int { if (ended.count != 0L) throw IllegalThreadStateException(); return code }
        override fun destroy() { if (ended.count != 0L) finish(137) }
    }

    private fun id(json: JsonObject) = json.get("jobId").asString
    private fun done(jobs: McpTerminalJobs, id: String): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (true) {
            val state = jobs.read(id, waitMillis = 100)
            if (!state.get("processActive").asBoolean) return state
            assertTrue("job did not finish: $state", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }
    private fun running(jobs: McpTerminalJobs, id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (jobs.read(id).get("status").asString == "STARTING") {
            assertTrue(System.nanoTime() < deadline); Thread.sleep(5)
        }
    }

    @Test fun streamsUnicodeWithoutNewlinesBeforeExitAndPaginates() {
        val child = Child()
        McpTerminalJobs(temp.root, { _, _, _ -> McpTerminalJobs.RunningCommand(child, child::destroy) }).use { jobs ->
            val id = id(jobs.execute("compile")); running(jobs, id)
            child.print("编译中…"); Thread.sleep(30)
            val first = jobs.read(id, maxChars = 2, waitMillis = 500)
            assertEquals("编译", first.get("output").asString)
            assertTrue(first.get("running").asBoolean)
            val second = jobs.read(id, first.get("nextOffset").asLong)
            assertEquals("中…", second.get("output").asString)
            child.finish(7)
            val final = done(jobs, id)
            assertEquals("FAILED", final.get("status").asString)
            assertEquals(7, final.get("exitCode").asInt)
        }
    }

    @Test fun outputIsBoundedAndOldOffsetsReportLoss() {
        val child = Child()
        McpTerminalJobs(temp.root, { _, _, _ -> McpTerminalJobs.RunningCommand(child, child::destroy) }, outputCapacity = 32).use { jobs ->
            val id = id(jobs.execute("verbose")); running(jobs, id)
            child.print("A".repeat(1000) + "end"); child.finish()
            val state = done(jobs, id)
            assertEquals(32, state.get("output").asString.length)
            assertEquals(971, state.get("outputStart").asInt)
            assertEquals(1003, state.get("nextOffset").asInt)
            assertTrue(state.get("truncated").asBoolean)
            assertTrue(state.get("output").asString.endsWith("end"))
        }
    }

    @Test fun stdinIsQueuedWithoutAddingNewlineAndEofPreventsFurtherInput() {
        val child = Child()
        McpTerminalJobs(temp.root, { _, _, _ -> McpTerminalJobs.RunningCommand(child, child::destroy) }).use { jobs ->
            val id = id(jobs.execute("read")); running(jobs, id)
            assertTrue(jobs.write(id, "hello\n", true).get("accepted").asBoolean)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (jobs.read(id).get("pendingInput").asInt > 0) {
                assertTrue(System.nanoTime() < deadline); Thread.sleep(5)
            }
            assertEquals("hello\n", child.stdin.toString("UTF-8"))
            try { jobs.write(id, "more", false); fail("closed stdin accepted data") } catch (_: IllegalStateException) {}
            jobs.stop(id); assertEquals("STOPPED", done(jobs, id).get("status").asString)
        }
    }

    @Test fun stopOnlyTerminatesTheChosenJob() {
        val children = mutableListOf<Child>()
        McpTerminalJobs(temp.root, { _, _, _ ->
            val child = Child(); synchronized(children) { children.add(child) }
            McpTerminalJobs.RunningCommand(child, child::destroy)
        }).use { jobs ->
            val first = id(jobs.execute("one")); running(jobs, first)
            val second = id(jobs.execute("two")); running(jobs, second)
            jobs.stop(first)
            assertEquals("STOPPED", done(jobs, first).get("status").asString)
            assertTrue(jobs.read(second).get("running").asBoolean)
            jobs.stop(second); done(jobs, second)
        }
    }

    @Test fun timeoutKillsTheCommandAndRemainsDistinctFromReadWait() {
        val child = Child()
        McpTerminalJobs(temp.root, { _, _, _ -> McpTerminalJobs.RunningCommand(child, child::destroy) }).use { jobs ->
            val id = id(jobs.execute("long-build", timeoutSeconds = 1))
            assertTrue(jobs.read(id, waitMillis = 50).get("running").asBoolean)
            assertEquals("TIMED_OUT", done(jobs, id).get("status").asString)
            assertEquals(0, child.ended.count.toInt())
        }
    }

    @Test fun closeDuringLaunchCannotLeakAProcessOrReopenJobs() {
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = Child()
        val jobs = McpTerminalJobs(temp.root, { _, _, _ ->
            admitted.countDown(); release.await()
            McpTerminalJobs.RunningCommand(child, child::destroy)
        })
        val id = id(jobs.execute("starting"))
        assertTrue(admitted.await(2, TimeUnit.SECONDS))
        jobs.close(); release.countDown()
        assertEquals("STOPPED", done(jobs, id).get("status").asString)
        assertEquals(0, child.ended.count.toInt())
        try { jobs.execute("late"); fail("closed manager accepted work") } catch (_: IllegalStateException) {}
    }

    @Test fun capacityIsNotReleasedUntilCancelledLaunchFinishes() {
        val release = CountDownLatch(1)
        val jobs = McpTerminalJobs(temp.root, { _, _, _ ->
            release.await(); val child = Child(); McpTerminalJobs.RunningCommand(child, child::destroy)
        }, maxRunning = 1)
        try {
            val id = id(jobs.execute("first")); jobs.stop(id)
            try { jobs.execute("second"); fail("starting cancellation bypassed limit") } catch (_: IllegalStateException) {}
            release.countDown(); done(jobs, id)
        } finally { release.countDown(); jobs.close() }
    }

    @Test fun launchFailureIsRecordedAndDoesNotExhaustCapacity() {
        McpTerminalJobs(temp.root, { _, _, _ -> error("exec denied") }, maxRunning = 1).use { jobs ->
            repeat(3) {
                val final = done(jobs, id(jobs.execute("compiler")))
                assertEquals("FAILED", final.get("status").asString)
                assertEquals("exec denied", final.get("error").asString)
            }
        }
    }

    @Test fun rejectsCwdEscapeAndInvalidInputBeforeLaunching() {
        val launches = AtomicInteger()
        McpTerminalJobs(temp.root, { _, _, _ -> launches.incrementAndGet(); error("unexpected") }).use { jobs ->
            val invalid = listOf<() -> Unit>(
                { jobs.execute("x", "../") }, { jobs.execute("x", "missing") },
                { jobs.execute("") }, { jobs.execute("x\u0000") },
                { jobs.execute("x", timeoutSeconds = 0) }, { jobs.execute("x", timeoutSeconds = 3601) },
                { jobs.execute("x", environment = mapOf("A=B" to "x")) },
                { jobs.execute("x", environment = mapOf("A" to "x\u0000")) }
            )
            invalid.forEach { attempt -> try { attempt(); fail("invalid argument accepted") } catch (_: IllegalArgumentException) {} }
            assertEquals(0, launches.get())
        }
    }
}
