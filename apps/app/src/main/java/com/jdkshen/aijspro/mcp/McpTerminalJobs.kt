package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Asynchronous pipe terminals. No Android dependencies: lifecycle/races are tested on the JVM. */
// 反馈 #8 给构造函数加了带默认值的新参数；Kotlin 的默认参数**不会**为 Java 调用方生成重载，
// 于是 ui/terminal/EmbeddedTerminalActivity.java 的 5 参调用直接编译失败。@JvmOverloads 保住旧调用形状。
internal class McpTerminalJobs @JvmOverloads constructor(
    private val root: File,
    private val launcher: (String, File, Map<String, String>) -> RunningCommand,
    private val maxRunning: Int = 3,
    private val maxRetained: Int = 16,
    private val outputCapacity: Int = 256 * 1024,
    /** 保留窗口；与 apk_job 的 6h 默认一致。 */
    private val retentionWindowMillis: Long = DEFAULT_RETENTION_WINDOW_MILLIS,
    /** 墓碑上限；与 apk_job 的 MAX_TOMBSTONES 一致。 */
    private val maxTombstones: Int = MAX_TOMBSTONES,
) : Closeable {
    class RunningCommand(val process: Process, val terminate: () -> Unit)

    /** 解析自首行 AIJS_BACKEND_ROUTING 标注的结构化路由信息；未路由时 effective=requested。 */
    data class RoutingInfo(
        val routed: Boolean,
        val requestedBackend: String,
        val effectiveBackend: String,
        val requirement: String = "",
        val hint: String = "",
    )

    private class Job(val id: String, val command: String, val directory: File, val backend: String) {
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

        // UX/T2：只由首行 AIJS_BACKEND_ROUTING 标注更新；未标注时保持 requested/effective 相同、routed=false。
        val requestedBackend: String = backend
        var effectiveBackend: String = backend
        var routed: Boolean = false
        var routingRequirement: String = ""
        var routingHint: String = ""
        var routingParsed: Boolean = false

        val input = Executors.newSingleThreadExecutor { r -> Thread(r, "mcp-terminal-input").apply { isDaemon = true } }
    }

    /** 与 apk_job 同构的墓碑：job 被保留窗口/数量淘汰，或服务换实例后输出不可恢复。 */
    private data class Tombstone(
        val jobId: String,
        val code: String,
        val reason: String,
        val command: String?,
        val backend: String?,
        val createdAt: Long,
        val finishedAt: Long,
        val recordedAt: Long,
    )

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
    private val tombstones = LinkedHashMap<String, Tombstone>()
    private val registryFile = File(root, REGISTRY_FILE)
    private val serviceInstance = UUID.randomUUID().toString()
    private val serviceStartedAt = System.currentTimeMillis()
    private var previousServiceInstance: String? = null
    private var closed = false

    init {
        recoverRegistry()
        trim()
        persistRegistry()
    }

    @JvmOverloads
    @Synchronized
    fun execute(command: String, workingDirectory: String = "", environment: Map<String, String> = emptyMap(),
                timeoutSeconds: Int = 300, backend: String = BACKEND_APP): JsonObject {
        check(!closed) { "终端服务已停止" }
        require(backend == BACKEND_APP || backend == BACKEND_SHIZUKU) { "backend 必须是 app 或 shizuku" }
        require(command.isNotBlank() && command.length <= 65536 && '\u0000' !in command) { "command 必须为 1..65536 字符且不能包含 NUL" }
        require(timeoutSeconds in 1..3600) { "timeoutSeconds 必须为 1..3600（任务超时会终止进程组）" }
        require(environment.size <= 64 && environment.all { (key, value) ->
            key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && value.length <= 8192 && '\u0000' !in value
        }) { "env 无效：最多 64 个合法变量，值最多 8192 字符且不能包含 NUL" }
        require(INTERNAL_BACKEND_ENV !in environment) {
            "env 不能包含内部保留变量 $INTERNAL_BACKEND_ENV"
        }
        val directory = McpPathRules.resolve(root, workingDirectory)
        require(directory.isDirectory) { "workingDirectory 目录不存在" }
        trim()
        check(jobs.values.count { synchronized(it.lock) { it.finishedAt == 0L } } < maxRunning) { "终端任务已满（最多 $maxRunning 个），请先读取或停止任务" }
        while (jobs.size >= maxRetained) {
            val old = jobs.values.firstOrNull { synchronized(it.lock) { it.finishedAt != 0L } }
                ?: error("终端任务已满")
            evict(old.id, "retentionLimit")
        }
        val job = Job("terminal-${UUID.randomUUID()}", command, directory, backend)
        jobs[job.id] = job
        persistRegistry()
        // The launcher receives the internal backend marker in its env. User-supplied env is
        // rejected above, so a caller cannot forge shizuku/app state; the real child environment
        // is rebuilt by McpAndroidTerminal after it strips this key.
        val launchEnvironment = LinkedHashMap(environment).apply { put(INTERNAL_BACKEND_ENV, backend) }
        val timeout = timer.schedule({ stopJob(job, "TIMED_OUT") }, timeoutSeconds.toLong(), TimeUnit.SECONDS)
        workers.execute {
            var running: RunningCommand? = null
            try {
                running = launcher(command, directory, launchEnvironment)
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
                    // 若命令没有输出换行，也必须用完整首行做最后一次路由解析。
                    parseRoutingIfReady(job, force = true)
                    job.finishedAt = System.currentTimeMillis()
                    job.running = null
                    job.lock.notifyAll()
                }
                persistRegistry()
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

    /** 兼容旧调用：只返回当前任务；terminal_list 的结构化元数据用 listReport()。 */
    @Synchronized fun list(): List<JsonObject> {
        trim()
        return jobs.values.map { job -> snapshot(job, Long.MAX_VALUE, 1, 0).apply { remove("output") } }
    }

    /**
     * terminal_list 的结构化响应（与 apk_job_list 同构）。
     *
     * 保留旧的 list(): List<JsonObject> 是为了不破坏既有调用方；Lead 接线
     * terminal_list 时改为返回本对象即可拿到 evicted/retention/tombstoneCount。
     */
    @Synchronized fun listReport(): JsonObject {
        trim()
        val items = JsonArray()
        jobs.values.forEach { job -> items.add(snapshot(job, Long.MAX_VALUE, 1, 0).apply { remove("output") }) }
        val evicted = JsonArray()
        tombstones.values.sortedByDescending { it.recordedAt }.take(MAX_LISTED_TOMBSTONES).forEach { evicted.add(tombstoneJson(it)) }
        return ToolkitIo.json(
            "items" to items,
            "count" to jobs.size,
            "activeJobs" to jobs.values.count { synchronized(it.lock) { it.finishedAt == 0L } },
            "retention" to retentionJson(),
            "evicted" to evicted,
            "tombstoneCount" to tombstones.size,
            "ownershipEnforced" to false,
            "ownerFilterSupported" to false,
            "owner" to null,
            "clientId" to null,
            "ownershipNote" to "terminal_* 目前没有 owner/clientId 参数，也没有跨调用方隔离；owner/clientId 仅作为兼容字段返回 null，不应当作安全边界。",
            "service" to serviceJson(),
        )
    }

    @Synchronized private fun find(id: String): Job {
        trim()
        jobs[id]?.let { return it }
        val tombstone = tombstones[id]
        if (tombstone != null) {
            val prefix = if (tombstone.code == SERVICE_RESTARTED) {
                "终端任务在服务重启后已丢失"
            } else {
                "终端任务已被保留策略淘汰"
            }
            throw ToolkitException(
                tombstone.code,
                prefix + "：$id（code=" + tombstone.code + "，reason=" + tombstone.reason + "）；terminal 输出只在内存，已不可恢复，请重新执行。",
                "jobId",
            )
        }
        throw ToolkitException(
            JOB_NOT_FOUND,
            "$JOB_NOT_FOUND：找不到终端任务：$id。job registry 中没有该 id 的记录；若服务刚重启，旧任务应报 SERVICE_RESTARTED；可调用 terminal_list 查看当前任务与 evicted 记录。",
            "jobId",
        )
    }

    private fun tombstoneJson(tombstone: Tombstone): JsonObject = ToolkitIo.json(
        "jobId" to tombstone.jobId,
        "code" to tombstone.code,
        "reason" to tombstone.reason,
        "command" to tombstone.command,
        "backend" to tombstone.backend,
        "createdAt" to tombstone.createdAt,
        "finishedAt" to tombstone.finishedAt,
        "recordedAt" to tombstone.recordedAt,
    )

    private fun retentionJson(): JsonObject = ToolkitIo.json(
        "windowMillis" to retentionWindowMillis,
        "maxRetainedFinished" to maxRetained,
        "maxActive" to maxRunning,
        "maxTombstones" to maxTombstones,
        "maxListedTombstones" to MAX_LISTED_TOMBSTONES,
        "tombstoneCount" to tombstones.size,
    )

    private fun serviceJson(): JsonObject = ToolkitIo.json(
        "instance" to serviceInstance,
        "startedAt" to serviceStartedAt,
        "previousInstance" to previousServiceInstance,
        "restarted" to (previousServiceInstance != null && previousServiceInstance != serviceInstance),
    )

    /** 清理超过保留窗口或数量的已完成任务；只淘汰 finished job，运行中的永不淘汰。 */
    @Synchronized private fun trim() {
        if (retentionWindowMillis >= 0L) {
            val now = System.currentTimeMillis()
            val expired = jobs.values.filter { job ->
                synchronized(job.lock) { job.finishedAt > 0L && now - job.finishedAt > retentionWindowMillis }
            }.map { it.id }
            expired.forEach { evict(it, "retentionWindow") }
        }
        while (maxRetained >= 0 && jobs.size > maxRetained) {
            val old = jobs.values.firstOrNull { job -> synchronized(job.lock) { job.finishedAt != 0L } } ?: break
            evict(old.id, "retentionLimit")
        }
        pruneTombstones()
        persistRegistry()
    }

    private fun evict(id: String, reason: String) {
        val job = jobs.remove(id) ?: return
        val now = System.currentTimeMillis()
        val tombstone = synchronized(job.lock) {
            Tombstone(
                jobId = job.id,
                code = JOB_EVICTED,
                reason = reason,
                command = job.command,
                backend = job.backend,
                createdAt = job.createdAt,
                finishedAt = job.finishedAt,
                recordedAt = now,
            )
        }
        addTombstone(tombstone)
    }

    private fun addTombstone(tombstone: Tombstone) {
        tombstones[tombstone.jobId] = tombstone
        pruneTombstones()
    }

    private fun pruneTombstones() {
        while (tombstones.size > maxTombstones) {
            val oldest = tombstones.values.minByOrNull { it.recordedAt } ?: break
            tombstones.remove(oldest.jobId)
        }
    }

    /**
     * 从上次实例的 registry 恢复「已知 jobId」的墓碑；terminal 输出只在内存，
     * 所以服务换实例后旧 id 一律报 SERVICE_RESTARTED，而不是 JOB_NOT_FOUND。
     */
    private fun recoverRegistry() {
        if (!registryFile.isFile || registryFile.length() > MAX_REGISTRY_BYTES) return
        val source = runCatching { registryFile.readText(Charsets.UTF_8) }.getOrNull() ?: return
        val root = runCatching { JsonParser().parse(source).asJsonObject }.getOrNull() ?: return
        previousServiceInstance = root.get("serviceInstance")?.takeIf { it.isJsonPrimitive }?.asString
        val priorTombstones = root.get("tombstones")?.takeIf { it.isJsonArray }?.asJsonArray
        priorTombstones?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val item = element.asJsonObject
            val id = item.get("jobId")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val code = item.get("code")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            if (code != JOB_EVICTED && code != SERVICE_RESTARTED) return@forEach
            addTombstone(Tombstone(
                jobId = id,
                code = code,
                reason = item.get("reason")?.takeIf { it.isJsonPrimitive }?.asString ?: "registry tombstone",
                command = item.get("command")?.takeIf { it.isJsonPrimitive }?.asString,
                backend = item.get("backend")?.takeIf { it.isJsonPrimitive }?.asString,
                createdAt = item.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                finishedAt = item.get("finishedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                recordedAt = item.get("recordedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: System.currentTimeMillis(),
            ))
        }
        val priorJobs = root.get("jobs")?.takeIf { it.isJsonArray }?.asJsonArray
        priorJobs?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val item = element.asJsonObject
            val id = item.get("jobId")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            if (tombstones.containsKey(id)) return@forEach
            addTombstone(Tombstone(
                jobId = id,
                code = SERVICE_RESTARTED,
                reason = "终端服务实例已更换；terminal 任务/输出只在内存，旧任务不恢复",
                command = item.get("command")?.takeIf { it.isJsonPrimitive }?.asString,
                backend = item.get("backend")?.takeIf { it.isJsonPrimitive }?.asString,
                createdAt = item.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                finishedAt = item.get("finishedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                recordedAt = System.currentTimeMillis(),
            ))
        }
    }

    @Synchronized private fun persistRegistry() {
        val jobsArray = JsonArray()
        jobs.values.forEach { job ->
            synchronized(job.lock) {
                jobsArray.add(ToolkitIo.json(
                    "jobId" to job.id,
                    "command" to job.command,
                    "backend" to job.backend,
                    "createdAt" to job.createdAt,
                    "finishedAt" to job.finishedAt,
                    "state" to job.status,
                ))
            }
        }
        val tombstonesArray = JsonArray()
        tombstones.values.forEach { tombstonesArray.add(tombstoneJson(it)) }
        val payload = ToolkitIo.json(
            "version" to REGISTRY_VERSION,
            "serviceInstance" to serviceInstance,
            "previousServiceInstance" to previousServiceInstance,
            "startedAt" to serviceStartedAt,
            "jobs" to jobsArray,
            "tombstones" to tombstonesArray,
        )
        runCatching {
            ToolkitIo.atomic(registryFile) { file -> file.writeText(payload.toString(), Charsets.UTF_8) }
        }
    }

    private fun append(job: Job, text: String) = synchronized(job.lock) {
        job.output.append(text)
        parseRoutingIfReady(job, force = false)
        val drop = job.output.length - outputCapacity
        if (drop > 0) { job.output.delete(0, drop); job.outputOffset += drop }
        job.lock.notifyAll()
    }

    /** 只读取输出首行；首行完整（有换行）或任务结束时解析一次，之后不再被后续行覆盖。 */
    private fun parseRoutingIfReady(job: Job, force: Boolean) {
        if (job.routingParsed) return
        val output = job.output
        if (output.isEmpty()) return
        val newline = output.indexOf('\n')
        if (newline < 0 && !force) return
        val firstLine = if (newline >= 0) output.substring(0, newline) else output.toString()
        val info = parseRoutingAnnotation(firstLine, job.requestedBackend)
        job.effectiveBackend = info.effectiveBackend
        job.routed = info.routed
        job.routingRequirement = info.requirement
        job.routingHint = info.hint
        job.routingParsed = true
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
            addProperty("backend", job.backend)
            addProperty("requestedBackend", job.requestedBackend)
            addProperty("effectiveBackend", job.effectiveBackend)
            addProperty("routed", job.routed)
            addProperty("routingRequirement", job.routingRequirement)
            addProperty("routingHint", job.routingHint)
            addProperty("status", job.status); addProperty("running", job.status in ACTIVE)
            add("owner", JsonNull.INSTANCE); add("clientId", JsonNull.INSTANCE)
            addProperty("ownershipEnforced", false)
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
        persistRegistry()
    }

    companion object {
        const val BACKEND_APP = "app"
        const val BACKEND_SHIZUKU = "shizuku"

        /** Reserved launcher-only env key; never accepted from a tool caller. */
        const val INTERNAL_BACKEND_ENV = "AIJS_MCP_TERMINAL_BACKEND"

        /** McpAndroidTerminal 写在输出首行的路由标注前缀。 */
        const val ROUTING_MARKER = "AIJS_BACKEND_ROUTING"

        /**
         * 解析首行 AIJS_BACKEND_ROUTING 标注；纯函数，不访问 Job/系统。
         *
         * 支持 routedFrom/routedTo、requestedBackend/effectiveBackend 两套键，
         * 键顺序任意；只有第一行以 marker 开头才解析，非首行/缺字段按未路由兜底。
         * routed=true 但没有有效目标时保守地返回 routed=false，effective=requested。
         */
        fun parseRoutingAnnotation(line: String, requestedBackend: String): RoutingInfo {
            val fallback = requestedBackend.trim().lowercase().ifEmpty { BACKEND_APP }
            val firstLine = line.lineSequence().firstOrNull()?.trim().orEmpty()
            if (!firstLine.startsWith(ROUTING_MARKER)) return RoutingInfo(false, fallback, fallback)
            val body = firstLine.removePrefix(ROUTING_MARKER).trim()
            val semicolon = body.indexOf(';')
            val keyValuePart = if (semicolon >= 0) body.substring(0, semicolon) else body
            val suffixHint = if (semicolon >= 0) body.substring(semicolon + 1).trim() else ""
            val values = LinkedHashMap<String, String>()
            Regex("""([A-Za-z][A-Za-z0-9_]*)=([^\s;]+)""").findAll(keyValuePart).forEach { match ->
                values[match.groupValues[1]] = match.groupValues[2]
            }
            val fromRaw = values["routedFrom"] ?: values["requestedBackend"] ?: fallback
            val from = fromRaw.trim().lowercase().ifEmpty { fallback }
            val requested = if (from == BACKEND_APP || from == BACKEND_SHIZUKU) from else fallback
            val toRaw = values["routedTo"] ?: values["effectiveBackend"] ?: ""
            val to = toRaw.trim().lowercase()
            val target = if (to == BACKEND_APP || to == BACKEND_SHIZUKU) to else requested
            val routedFlag = values["routed"]?.equals("true", ignoreCase = true) == true
            val routed = routedFlag && target != requested
            val effective = if (routed) target else requested
            val requirement = (values["requirement"] ?: values["routingRequirement"] ?: "").trim().uppercase()
            val hint = (values["routingHint"] ?: values["hint"] ?: suffixHint).trim()
            return RoutingInfo(routed, requested, effective, requirement, hint)
        }

        /** 与 apk_job 对齐：6h 保留窗口、256 个墓碑、最多列 32 个淘汰项。 */
        const val DEFAULT_RETENTION_WINDOW_MILLIS = 6L * 60L * 60L * 1000L
        const val MAX_TOMBSTONES = 256
        const val MAX_LISTED_TOMBSTONES = 32
        const val JOB_NOT_FOUND = "JOB_NOT_FOUND"
        const val JOB_EVICTED = "JOB_EVICTED"
        const val SERVICE_RESTARTED = "SERVICE_RESTARTED"
        private const val REGISTRY_FILE = ".mcp-terminal-jobs.json"
        private const val REGISTRY_VERSION = 1
        private const val MAX_REGISTRY_BYTES = 4L * 1024L * 1024L

        private val ACTIVE = setOf("STARTING", "RUNNING")
    }
}
