package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Asynchronous pipe terminals. No Android dependencies: lifecycle/races are tested on the JVM. */
internal class McpTerminalJobs(
    private val root: File,
    private val launcher: (String, File, Map<String, String>) -> RunningCommand,
    private val maxRunning: Int = 3,
    private val maxRetained: Int = 16,
    private val outputCapacity: Int = 256 * 1024
) : Closeable {
    class RunningCommand(val process: Process, val terminate: () -> Unit)

    private class Job(val id: String, val command: String, val directory: File) {
        val lock = Object()
        val createdAt = System.currentTimeMillis()
        var status = "STARTING"
        var running: RunningCommand? = null
        var exitCode: Int? = null
        var finishedAt = 0L
        var error: String? = null
        var inputError: String? = null
        var inputClosed = false
        var pendingInput = 0
        var outputOffset = 0L
        val output = StringBuilder()
        val input = Executors.newSingleThreadExecutor { r -> Thread(r, "mcp-terminal-input").apply { isDaemon = true } }
    }

    private val jobs = LinkedHashMap<String, Job>()
    private val workers = Executors.newFixedThreadPool(maxRunning) { r ->
        Thread(r, "mcp-terminal-job").apply { isDaemon = true }
    }
    private val readers = Executors.newFixedThreadPool(maxRunning) { r ->
        Thread(r, "mcp-terminal-output").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mcp-terminal-timeout").apply { isDaemon = true }
    }
    private var closed = false

    @Synchronized
    fun execute(command: String, workingDirectory: String = "", environment: Map<String, String> = emptyMap(),
                timeoutSeconds: Int = 300): JsonObject {
        check(!closed) { "终端服务已停止" }
        require(command.isNotBlank() && command.length <= 65536 && '\u0000' !in command) { "command 必须为 1..65536 字符且不能包含 NUL" }
        require(timeoutSeconds in 1..3600) { "timeoutSeconds 必须为 1..3600（任务超时会终止进程组）" }
        require(environment.size <= 64 && environment.all { (key, value) ->
            key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && value.length <= 8192 && '\u0000' !in value
        }) { "env 无效：最多 64 个合法变量，值最多 8192 字符且不能包含 NUL" }
        val directory = McpPathRules.resolve(root, workingDirectory)
        require(directory.isDirectory) { "workingDirectory 目录不存在" }
        check(jobs.values.count { synchronized(it.lock) { it.finishedAt == 0L } } < maxRunning) { "终端任务已满（最多 $maxRunning 个），请先读取或停止任务" }
        while (jobs.size >= maxRetained) {
            val old = jobs.values.firstOrNull { synchronized(it.lock) { it.finishedAt != 0L } }
                ?: error("终端任务已满")
            jobs.remove(old.id)
        }
        val job = Job("terminal-${UUID.randomUUID()}", command, directory)
        jobs[job.id] = job
        val timeout = timer.schedule({ stopJob(job, "TIMED_OUT") }, timeoutSeconds.toLong(), TimeUnit.SECONDS)
        workers.execute {
            var running: RunningCommand? = null
            try {
                running = launcher(command, directory, environment)
                synchronized(job.lock) {
                    job.running = running
                    if (job.status in ACTIVE) job.status = "RUNNING"
                    job.lock.notifyAll()
                }
                if (synchronized(job.lock) { job.status !in ACTIVE }) running.terminate()
                // Read chunks, not lines: compiler progress and lines without newlines are visible immediately.
                val process = running.process
                val output = readers.submit {
                    process.inputStream.reader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val size = reader.read(buffer)
                            if (size < 0) break
                            append(job, String(buffer, 0, size))
                        }
                    }
                }
                val code = process.waitFor()
                // The command shell has exited. Reap background children before waiting for pipe EOF.
                running.terminate()
                output.get(5, TimeUnit.SECONDS)
                synchronized(job.lock) {
                    job.exitCode = code
                    if (job.status in ACTIVE) job.status = if (code == 0) "SUCCEEDED" else "FAILED"
                }
            } catch (error: Exception) {
                synchronized(job.lock) {
                    if (job.status in ACTIVE) {
                        job.status = "FAILED"
                        job.error = error.message ?: error.javaClass.simpleName
                    }
                }
            } finally {
                timeout.cancel(false)
                // Also reap background children after a shell exits, or they can retain stdout forever.
                runCatching { running?.terminate() }
                runCatching { running?.process?.outputStream?.close() }
                job.input.shutdownNow()
                synchronized(job.lock) {
                    job.finishedAt = System.currentTimeMillis()
                    job.running = null
                    job.lock.notifyAll()
                }
            }
        }
        return snapshot(job, 0, 16384, 0)
    }

    fun read(jobId: String, offset: Long = 0, maxChars: Int = 16384, waitMillis: Int = 0): JsonObject {
        require(offset >= 0) { "offset 必须 >= 0（UTF-16 字符偏移）" }
        require(maxChars in 1..65536) { "maxChars 必须为 1..65536" }
        require(waitMillis in 0..10000) { "waitMillis 必须为 0..10000" }
        return snapshot(find(jobId), offset, maxChars, waitMillis)
    }

    /** Queue input so a compiler that is not reading stdin cannot block an HTTP request. */
    fun write(jobId: String, input: String, eof: Boolean): JsonObject {
        require(input.length <= 65536) { "input 最多 65536 字符" }
        require(input.isNotEmpty() || eof) { "input 为空时必须 eof=true" }
        val job = find(jobId)
        synchronized(job.lock) {
            check(job.status == "RUNNING" && job.running != null) { "任务尚未运行或已结束" }
            check(!job.inputClosed) { "标准输入已关闭" }
            check(job.pendingInput < 8) { "标准输入队列已满，请等待任务读取" }
            if (eof) job.inputClosed = true
            job.pendingInput++
            job.input.execute {
                try {
                    val stream = synchronized(job.lock) { job.running?.process?.outputStream }
                        ?: error("任务已结束")
                    stream.write(input.toByteArray(Charsets.UTF_8))
                    stream.flush()
                    if (eof) stream.close()
                } catch (error: Exception) {
                    synchronized(job.lock) { job.inputError = error.message ?: "标准输入写入失败" }
                } finally {
                    synchronized(job.lock) { job.pendingInput--; job.lock.notifyAll() }
                }
            }
        }
        return JsonObject().apply { addProperty("jobId", jobId); addProperty("accepted", true) }
    }

    fun stop(jobId: String): JsonObject {
        val job = find(jobId)
        stopJob(job, "STOPPED")
        return snapshot(job, 0, 16384, 0)
    }

    @Synchronized fun list(): List<JsonObject> = jobs.values.map { job ->
        snapshot(job, Long.MAX_VALUE, 1, 0).apply { remove("output") }
    }

    @Synchronized private fun find(id: String) = jobs[id] ?: throw IllegalArgumentException("找不到终端任务：$id（服务重启后任务不保留）")

    private fun append(job: Job, text: String) = synchronized(job.lock) {
        job.output.append(text)
        val drop = job.output.length - outputCapacity
        if (drop > 0) { job.output.delete(0, drop); job.outputOffset += drop }
        job.lock.notifyAll()
    }

    private fun snapshot(job: Job, requestedOffset: Long, maxChars: Int, waitMillis: Int): JsonObject = synchronized(job.lock) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis.toLong())
        while (job.finishedAt == 0L && job.outputOffset + job.output.length <= requestedOffset) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            TimeUnit.NANOSECONDS.timedWait(job.lock, remaining)
        }
        val end = job.outputOffset + job.output.length
        val start = requestedOffset.coerceIn(job.outputOffset, end)
        val count = minOf(maxChars.toLong(), end - start).toInt()
        JsonObject().apply {
            addProperty("jobId", job.id); addProperty("command", job.command)
            addProperty("workingDirectory", job.directory.relativeTo(root).invariantSeparatorsPath)
            addProperty("status", job.status); addProperty("running", job.status in ACTIVE)
            addProperty("processActive", job.running != null || job.finishedAt == 0L)
            addProperty("createdAt", job.createdAt); addProperty("finishedAt", job.finishedAt)
            job.exitCode?.let { addProperty("exitCode", it) }
            job.error?.let { addProperty("error", it) }
            job.inputError?.let { addProperty("inputError", it) }
            addProperty("inputClosed", job.inputClosed); addProperty("pendingInput", job.pendingInput)
            addProperty("output", job.output.substring((start - job.outputOffset).toInt(), (start - job.outputOffset).toInt() + count))
            addProperty("offset", start); addProperty("nextOffset", start + count)
            addProperty("outputStart", job.outputOffset); addProperty("outputEnd", end)
            addProperty("truncated", requestedOffset < job.outputOffset); addProperty("hasMore", start + count < end)
            addProperty("offsetUnit", "utf16"); addProperty("pty", false)
        }
    }

    private fun stopJob(job: Job, status: String) {
        val process = synchronized(job.lock) {
            if (job.status !in ACTIVE) return
            job.status = status
            job.lock.notifyAll()
            job.running
        }
        runCatching { process?.terminate() }
    }

    override fun close() {
        val all = synchronized(this) {
            if (closed) return
            closed = true
            jobs.values.toList()
        }
        all.forEach { stopJob(it, "STOPPED") }
        // Allow already submitted STARTING jobs to observe cancellation and reap their process.
        workers.shutdown()
        // Output tasks may still be submitted by a STARTING worker; shut readers down after workers drain.
        Thread({ workers.awaitTermination(10, TimeUnit.SECONDS); readers.shutdown() }, "mcp-terminal-close")
            .apply { isDaemon = true; start() }
        timer.shutdownNow()
    }

    companion object { private val ACTIVE = setOf("STARTING", "RUNNING") }
}
