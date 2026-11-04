package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 长 APK 后台任务：元数据、输出缓存与轻量 registry 分层管理。
 *
 * 文件布局（都在 [folder] 下，互不混写）：
 * - `<jobId>.json`：任务元数据（状态/owner/时间戳/结果字节数等，**不含输出正文**）；
 * - `<jobId>.result.json`：输出缓存（大 JSON/文本，单独读取、可随淘汰单独删除）；
 * - `jobs.index.json`：轻量 registry（已知 jobId + 保留窗口/淘汰墓碑 + service 实例信息），
 *   用于服务重启后区分 JOB_NOT_FOUND / JOB_EVICTED / SERVICE_RESTARTED。
 *
 * 错误码（通过 [ToolkitException] 的 code 返回，provider 会原样映射）：
 * - JOB_NOT_FOUND：registry 从未记录过的 jobId，或请求的 owner/clientId/sessionId 不匹配；
 * - JOB_EVICTED：任务曾经存在，但结果超出保留窗口/数量上限已被淘汰（输出缓存已删除）；
 * - SERVICE_RESTARTED：任务在工具进程重启时丢失（未自动重放），或重启后元数据/输出不可恢复；
 * - JOB_QUEUE_FULL：活动任务达到上限，明确拒绝新任务。
 *
 * 兼容性：保留原有公开方法 start/list/status/result/cancel 及参数；
 * owner/clientId/sessionId 均为可选参数，未提供时行为与旧版一致。
 */
internal class ToolkitJobs internal constructor(
    private val folder: File,
    private val execute: (String, JsonObject) -> JsonObject,
    private val retentionWindowMillis: Long,
    private val maxRetainedFinished: Int,
    private val maxActive: Int,
    private val clock: () -> Long,
) {
    /** 仅用于测试/默认值的两个构造器；execute 保持在第二位，兼容旧调用 ToolkitJobs(folder, ::executeOperation)。 */
    constructor(folder: File, execute: (String, JsonObject) -> JsonObject) : this(
        folder, execute, DEFAULT_RETENTION_WINDOW_MILLIS, DEFAULT_MAX_RETAINED_FINISHED, DEFAULT_MAX_ACTIVE,
        { System.currentTimeMillis() },
    ) { }

    private class Job(
        val id: String,
        val operation: String,
        val arguments: JsonObject,
        val timeout: Int,
        val created: Long,
        val owner: String?,
        val clientId: String?,
        val sessionId: String?,
    ) {
        val cancelled = AtomicBoolean()
        @Volatile var state = "QUEUED"
        @Volatile var thread: Thread? = null
        @Volatile var started = 0L
        @Volatile var finished = 0L
        @Volatile var reason = ""
        @Volatile var resultReady = false
        @Volatile var resultBytes = 0L
        @Volatile var lostByRestart = false
        @Volatile var metadataFromIndex = false
    }

    private data class Tombstone(
        val jobId: String,
        val code: String,
        val reason: String,
        val operation: String?,
        val owner: String?,
        val clientId: String?,
        val sessionId: String?,
        val createdAt: Long,
        val finishedAt: Long,
        val recordedAt: Long,
    )

    private val jobs = ConcurrentHashMap<String, Job>()
    private val tombstones = ConcurrentHashMap<String, Tombstone>()
    private val registryLock = Any()
    private val serviceInstance = ToolkitIo.fresh()
    private val serviceStartedAt = clock()
    @Volatile private var previousServiceInstance: String? = null

    init {
        require(folder.isDirectory || folder.mkdirs()) { "后台任务目录不可用" }
        recover()
        trim()
    }

    // ------------------------------------------------------------------
    // 公开操作（保持旧参数兼容）
    // ------------------------------------------------------------------

    @Synchronized
    fun start(args: JsonObject): JsonObject {
        trim()
        val operation = args.tString("operation")
        require(operation in OPERATIONS) { "此操作不支持后台任务：" + operation }
        val active = jobs.values.count { it.finished == 0L }
        if (active >= maxActive) {
            throw ToolkitException(
                "JOB_QUEUE_FULL",
                "最多排队 " + maxActive + " 个 APK 任务；请先 apk_job_list/apk_job_result 读取或 apk_job_cancel 取消现有任务",
                "operation",
            )
        }
        val values = args.getAsJsonObject("arguments")?.deepCopy() ?: JsonObject()
        val job = Job(
            ToolkitIo.fresh(),
            operation,
            values,
            args.tInt("timeoutSeconds", 300, 1, 1800),
            clock(),
            identity(args, "owner"),
            identity(args, "clientId"),
            identity(args, "sessionId"),
        )
        jobs[job.id] = job
        persist(job)
        persistRegistry()
        trim()
        val timeout = TIMER.schedule({ requestCancel(job, "timeout") }, job.timeout.toLong(), TimeUnit.SECONDS)
        WORKERS.execute { runWork(job, timeout) }
        return status(job)
    }

    @Synchronized
    fun list(args: JsonObject = JsonObject()): JsonObject {
        trim()
        val owner = identity(args, "owner")
        val clientId = identity(args, "clientId")
        val sessionId = identity(args, "sessionId")
        val items = jobs.values.filter { matches(it, owner, clientId, sessionId) }
            .sortedByDescending { it.created }.map { status(it) }
        val evicted = tombstones.values.filter { matches(it, owner, clientId, sessionId) }
            .sortedByDescending { it.recordedAt }.take(MAX_LISTED_TOMBSTONES).map { tombstoneJson(it) }
        return ToolkitIo.json(
            "items" to items,
            "count" to items.size,
            "activeJobs" to jobs.values.count { it.finished == 0L },
            "maxActive" to maxActive,
            "retention" to retentionJson(),
            "service" to serviceJson(),
            "evicted" to evicted,
            "filter" to ToolkitIo.json("owner" to owner, "clientId" to clientId, "sessionId" to sessionId),
        )
    }

    @Synchronized
    fun status(args: JsonObject): JsonObject = status(find(args))

    @Synchronized
    fun cancel(args: JsonObject): JsonObject {
        val job = find(args)
        requestCancel(job, "user")
        return status(job)
    }

    @Synchronized
    fun result(args: JsonObject): JsonObject {
        val job = find(args)
        if (job.lostByRestart && !job.resultReady) {
            throw ToolkitException(
                "SERVICE_RESTARTED",
                restartLostMessage(job.id),
                "jobId",
            )
        }
        require(job.resultReady) { "任务尚无结果，请读取状态" }
        val file = resultFile(job.id)
        require(file.length() <= MAX_RESULT_BYTES) { "任务结果超过读取预算" }
        val offset = args.tInt("offset", 0, 0, MAX_RESULT_BYTES.toInt())
        val max = args.tInt("maxChars", 16384, 1, 200000)
        val text = file.readText(Charsets.UTF_8)
        require(offset <= text.length) { "结果偏移超出范围" }
        require(
            offset == 0 || offset == text.length || !Character.isLowSurrogate(text[offset]) ||
                !Character.isHighSurrogate(text[offset - 1]),
        ) { "结果offset不能切开UTF-16代理对，请使用nextOffset" }
        var end = minOf(text.length, offset + max)
        if (end in (offset + 1) until text.length && Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) end--
        require(end > offset || offset == text.length) { "maxChars太小，不能容纳当前Unicode字符，请至少使用2" }
        return ToolkitIo.json(
            "jobId" to job.id,
            "operation" to job.operation,
            "state" to job.state,
            "content" to text.substring(offset, end),
            "nextOffset" to end,
            "hasMore" to (end < text.length),
            "totalChars" to text.length,
            "resultBytes" to job.resultBytes,
            "owner" to job.owner,
            "clientId" to job.clientId,
            "sessionId" to job.sessionId,
            "lostByRestart" to job.lostByRestart,
            "retention" to retentionJson(),
        )
    }

    // ------------------------------------------------------------------
    // 执行与状态
    // ------------------------------------------------------------------

    private fun runWork(job: Job, timeout: ScheduledFuture<*>) {
        job.thread = Thread.currentThread()
        job.started = clock()
        try {
            if (job.cancelled.get()) throw InterruptedIOException("任务已取消")
            job.state = "RUNNING"
            persist(job)
            persistRegistry()
            val response = execute(job.operation, job.arguments)
            // A committed operation can finish just before cancellation; its real response is retained.
            Thread.interrupted()
            writeResult(job, response)
            job.state = if (job.cancelled.get() && (
                    response.get("ok")?.asBoolean == false ||
                        response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("cancelled")?.asBoolean == true
                    )
            ) "CANCELLED"
            else if (response.get("ok")?.asBoolean == false) "FAILED" else "SUCCEEDED"
        } catch (failure: Exception) {
            job.state =
                if (job.cancelled.get() || failure is InterruptedIOException || failure is InterruptedException) "CANCELLED"
                else "FAILED"
            job.reason = job.reason.ifEmpty { failure.message ?: failure.javaClass.simpleName }
            Thread.interrupted()
            val typed = failure as? ToolkitException
            val code = if (job.state == "CANCELLED") "CANCELLED"
            else typed?.code ?: if (failure is IllegalArgumentException) "INVALID_ARGUMENT" else "TOOL_FAILED"
            writeResult(
                job,
                ToolkitIo.json(
                    "ok" to false,
                    "data" to null,
                    "error" to ToolkitIo.json(
                        "code" to code,
                        "message" to job.reason,
                        "argument" to typed?.argument,
                        "retrySameArguments" to false,
                    ),
                    "nextActions" to if (code == "COMPONENT_NOT_INSTALLED") listOf(
                        ToolkitIo.json("tool" to "apk_component_status"),
                        ToolkitIo.json("tool" to "apk_component_install", "hint" to "使用错误message中的组件id，先核对平台/ABI"),
                    ) else emptyList<Any>(),
                ),
            )
        } finally {
            Thread.interrupted()
            synchronized(job) {
                job.finished = clock()
                job.thread = null
                timeout.cancel(false)
                persist(job)
            }
            persistRegistry()
            trim()
        }
    }

    private fun requestCancel(job: Job, reason: String) {
        val changed = synchronized(job) {
            if (job.finished != 0L || job.state in TERMINAL_STATES) return@synchronized false
            job.reason = reason
            job.cancelled.set(true)
            job.state = "CANCELLING"
            job.thread?.interrupt()
            persist(job)
            true
        }
        if (changed) persistRegistry()
    }

    private fun status(job: Job): JsonObject = synchronized(job) {
        val lost = job.lostByRestart && !job.resultReady
        ToolkitIo.json(
            "jobId" to job.id,
            "operation" to job.operation,
            "state" to job.state,
            "createdAt" to job.created,
            "startedAt" to job.started.takeIf { it > 0 },
            "finishedAt" to job.finished.takeIf { it > 0 },
            "cancellationRequested" to job.cancelled.get(),
            "reason" to job.reason,
            "resultAvailable" to job.resultReady,
            "resultBytes" to job.resultBytes,
            "progress" to ToolkitIo.json(
                "phase" to job.state.lowercase(),
                "percent" to if (job.finished > 0) 100 else null,
            ),
            "owner" to job.owner,
            "clientId" to job.clientId,
            "sessionId" to job.sessionId,
            "lostByRestart" to job.lostByRestart,
            "errorCode" to if (lost) "SERVICE_RESTARTED" else null,
            "errorHint" to if (lost) "任务因工具进程重启丢失，未自动重放；请确认需要后重新提交" else null,
            "metadataFromIndex" to job.metadataFromIndex,
            "retention" to ToolkitIo.json(
                "windowMillis" to retentionWindowMillis,
                "retainUntil" to if (job.finished > 0 && retentionWindowMillis >= 0) job.finished + retentionWindowMillis else null,
                "maxRetainedFinished" to maxRetainedFinished,
                "maxActive" to maxActive,
                "maxTombstones" to MAX_TOMBSTONES,
                "maxResultBytes" to MAX_RESULT_BYTES,
            ),
            "storage" to ToolkitIo.json(
                "metadataFile" to metadataFile(job.id).name,
                "resultFile" to if (job.resultReady) resultFile(job.id).name else null,
                "metadataAndResultSeparated" to true,
            ),
            "service" to serviceJson(),
        )
    }

    // ------------------------------------------------------------------
    // 查找与错误码
    // ------------------------------------------------------------------

    private fun find(args: JsonObject): Job {
        val id = ToolkitIo.id(args.tString("jobId"))
        val job = jobs[id] ?: throw missingJob(id)
        requireIdentity(job, args)
        return job
    }

    private fun requireIdentity(job: Job, args: JsonObject) {
        val owner = identity(args, "owner")
        val clientId = identity(args, "clientId")
        val sessionId = identity(args, "sessionId")
        if (owner == null && clientId == null && sessionId == null) return
        if (!matches(job, owner, clientId, sessionId)) {
            throw ToolkitException(
                "JOB_NOT_FOUND",
                "任务不存在或不属于提供的 owner/clientId/sessionId：" + job.id,
                "jobId",
            )
        }
    }

    private fun missingJob(id: String): Nothing {
        val tombstone = tombstones[id]
        if (tombstone != null) {
            throw ToolkitException(tombstone.code, tombstoneMessage(tombstone), "jobId")
        }
        throw ToolkitException(
            "JOB_NOT_FOUND",
            "任务不存在：" + id + "。registry 中没有该 jobId 的记录；若刚创建就读不到，可能服务已重启或该 id 从未成功登记，可调用 apk_job_list 查看当前任务",
            "jobId",
        )
    }

    private fun tombstoneMessage(tombstone: Tombstone): String {
        val whenText = "记录于 " + tombstone.recordedAt
        return if (tombstone.code == "JOB_EVICTED") {
            "任务结果已超出保留窗口或数量上限并被淘汰（JOB_EVICTED）：" + tombstone.jobId +
                "；原因=" + tombstone.reason + "；" + whenText + "。输出缓存已删除，无法再读取；请重新提交任务"
        } else {
            "任务在工具进程重启时丢失（SERVICE_RESTARTED）：" + tombstone.jobId +
                "；原因=" + tombstone.reason + "；" + whenText + "。任务未自动重放，请重新提交"
        }
    }

    private fun restartLostMessage(id: String): String =
        "任务因工具进程重启丢失（SERVICE_RESTARTED）：" + id + "。未自动重放，也没有可用输出缓存；请重新提交任务"

    private fun matches(job: Job, owner: String?, clientId: String?, sessionId: String?): Boolean =
        (owner == null || job.owner == owner) &&
            (clientId == null || job.clientId == clientId) &&
            (sessionId == null || job.sessionId == sessionId)

    private fun matches(tombstone: Tombstone, owner: String?, clientId: String?, sessionId: String?): Boolean =
        (owner == null || tombstone.owner == owner) &&
            (clientId == null || tombstone.clientId == clientId) &&
            (sessionId == null || tombstone.sessionId == sessionId)

    // ------------------------------------------------------------------
    // 持久化：元数据 / 输出缓存 / registry
    // ------------------------------------------------------------------

    private fun metadataFile(id: String): File = File(folder, id + ".json")
    private fun resultFile(id: String): File = File(folder, id + ".result.json")
    private fun indexFile(): File = File(folder, INDEX_FILE)

    private fun persist(job: Job) = synchronized(job) {
        val metadata = ToolkitIo.json(
            "version" to 1,
            "jobId" to job.id,
            "operation" to job.operation,
            "state" to job.state,
            "createdAt" to job.created,
            "startedAt" to job.started.takeIf { it > 0 },
            "finishedAt" to job.finished.takeIf { it > 0 },
            "cancellationRequested" to job.cancelled.get(),
            "reason" to job.reason,
            "resultAvailable" to job.resultReady,
            "resultBytes" to job.resultBytes,
            "owner" to job.owner,
            "clientId" to job.clientId,
            "sessionId" to job.sessionId,
            "lostByRestart" to job.lostByRestart,
            "timeoutSeconds" to job.timeout,
        )
        runCatching { ToolkitIo.atomic(metadataFile(job.id)) { it.writeText(metadata.toString(), Charsets.UTF_8) } }
    }

    private fun writeResult(job: Job, response: JsonObject) {
        val data = response.toString()
        require(data.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) { "任务结果过大，请缩小预算" }
        ToolkitIo.atomic(resultFile(job.id)) { it.writeText(data, Charsets.UTF_8) }
        job.resultReady = true
        job.resultBytes = resultFile(job.id).length()
        persist(job)
    }

    private fun persistRegistry() = synchronized(registryLock) {
        val data = ToolkitIo.json(
            "version" to REGISTRY_VERSION,
            "serviceInstance" to serviceInstance,
            "serviceStartedAt" to serviceStartedAt,
            "previousServiceInstance" to previousServiceInstance,
            "updatedAt" to clock(),
            "jobs" to JsonArray().apply {
                jobs.values.sortedBy { it.created }.forEach { add(recordJson(it)) }
            },
            "tombstones" to JsonArray().apply {
                tombstones.values.sortedBy { it.recordedAt }.forEach { add(tombstoneJson(it)) }
            },
        )
        runCatching { ToolkitIo.atomic(indexFile()) { it.writeText(data.toString(), Charsets.UTF_8) } }
    }

    private fun recordJson(job: Job): JsonObject = synchronized(job) {
        ToolkitIo.json(
            "jobId" to job.id,
            "operation" to job.operation,
            "state" to job.state,
            "createdAt" to job.created,
            "startedAt" to job.started.takeIf { it > 0 },
            "finishedAt" to job.finished.takeIf { it > 0 },
            "owner" to job.owner,
            "clientId" to job.clientId,
            "sessionId" to job.sessionId,
            "resultAvailable" to job.resultReady,
            "resultBytes" to job.resultBytes,
            "lostByRestart" to job.lostByRestart,
        )
    }

    private fun tombstoneJson(tombstone: Tombstone): JsonObject = ToolkitIo.json(
        "jobId" to tombstone.jobId,
        "code" to tombstone.code,
        "reason" to tombstone.reason,
        "operation" to tombstone.operation,
        "owner" to tombstone.owner,
        "clientId" to tombstone.clientId,
        "sessionId" to tombstone.sessionId,
        "createdAt" to tombstone.createdAt,
        "finishedAt" to tombstone.finishedAt,
        "recordedAt" to tombstone.recordedAt,
    )

    private fun retentionJson(): JsonObject = ToolkitIo.json(
        "windowMillis" to retentionWindowMillis,
        "maxRetainedFinished" to maxRetainedFinished,
        "maxActive" to maxActive,
        "maxTombstones" to MAX_TOMBSTONES,
        "maxResultBytes" to MAX_RESULT_BYTES,
        "tombstoneCount" to tombstones.size,
    )

    private fun serviceJson(): JsonObject = ToolkitIo.json(
        "instance" to serviceInstance,
        "startedAt" to serviceStartedAt,
        "previousInstance" to previousServiceInstance,
        "restarted" to (previousServiceInstance != null),
        "indexFile" to INDEX_FILE,
        "metadataAndResultSeparated" to true,
    )

    // ------------------------------------------------------------------
    // 恢复与保留窗口/淘汰
    // ------------------------------------------------------------------

    private fun recover() {
        val registry = runCatching {
            val file = indexFile()
            if (file.isFile) JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject else null
        }.getOrNull()
        previousServiceInstance = registry?.get("serviceInstance")?.takeIf { it.isJsonPrimitive }?.asString
        registry?.getAsJsonArray("tombstones")?.forEach { element ->
            runCatching {
                val item = element.asJsonObject
                val id = item.optString("jobId") ?: return@runCatching
                val code = item.optString("code") ?: return@runCatching
                if (code != "JOB_EVICTED" && code != "SERVICE_RESTARTED") return@runCatching
                tombstones[id] = Tombstone(
                    id,
                    code,
                    item.optString("reason") ?: "",
                    item.optString("operation"),
                    item.optString("owner"),
                    item.optString("clientId"),
                    item.optString("sessionId"),
                    item.optLong("createdAt") ?: 0L,
                    item.optLong("finishedAt") ?: 0L,
                    item.optLong("recordedAt") ?: clock(),
                )
            }
        }
        // 1) 以元数据文件为准恢复；未完成的旧任务标记 INTERRUPTED + lostByRestart（不自动重放）。
        folder.listFiles()?.filter {
            it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".result.json") && it.name != INDEX_FILE
        }?.forEach { file ->
            runCatching {
                val source = JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject
                val id = source.optString("jobId") ?: return@runCatching
                ToolkitIo.id(id)
                val job = Job(
                    id,
                    source.optString("operation") ?: return@runCatching,
                    JsonObject(),
                    source.optLong("timeoutSeconds")?.toInt() ?: 300,
                    source.optLong("createdAt") ?: clock(),
                    source.optString("owner"),
                    source.optString("clientId"),
                    source.optString("sessionId"),
                )
                job.state = source.optString("state") ?: "INTERRUPTED"
                job.started = source.optLong("startedAt") ?: 0L
                job.finished = source.optLong("finishedAt") ?: 0L
                job.reason = source.optString("reason") ?: ""
                job.lostByRestart = source.get("lostByRestart")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                val result = resultFile(id)
                job.resultReady = result.isFile && result.length() > 0L
                job.resultBytes = if (job.resultReady) result.length() else 0L
                if (job.state in ACTIVE_STATES) {
                    job.state = "INTERRUPTED"
                    job.lostByRestart = true
                    if (job.finished == 0L) job.finished = clock()
                    if (job.reason.isEmpty()) job.reason = RESTART_REASON
                }
                jobs[id] = job
            }
        }
        // 2) registry 中有、但元数据文件缺失：能凭结果缓存恢复就恢复，否则记为 SERVICE_RESTARTED 墓碑。
        registry?.getAsJsonArray("jobs")?.forEach { element ->
            runCatching {
                val record = element.asJsonObject
                val id = record.optString("jobId") ?: return@runCatching
                if (jobs.containsKey(id) || tombstones.containsKey(id)) return@runCatching
                ToolkitIo.id(id)
                val result = resultFile(id)
                val resultBytes = if (result.isFile) result.length() else 0L
                if (resultBytes > 0L) {
                    val recordedState = record.optString("state") ?: "INTERRUPTED"
                    val job = Job(
                        id,
                        record.optString("operation") ?: "unknown",
                        JsonObject(),
                        300,
                        record.optLong("createdAt") ?: clock(),
                        record.optString("owner"),
                        record.optString("clientId"),
                        record.optString("sessionId"),
                    )
                    job.state = if (recordedState in ACTIVE_STATES) "INTERRUPTED" else recordedState
                    job.finished = record.optLong("finishedAt") ?: clock()
                    job.lostByRestart = job.state == "INTERRUPTED"
                    job.reason = if (job.lostByRestart) RESTART_REASON else ""
                    job.resultReady = true
                    job.resultBytes = resultBytes
                    job.metadataFromIndex = true
                    jobs[id] = job
                } else {
                    addTombstone(
                        Tombstone(
                            id,
                            "SERVICE_RESTARTED",
                            "工具进程重启后任务元数据与输出缓存都不可恢复",
                            record.optString("operation"),
                            record.optString("owner"),
                            record.optString("clientId"),
                            record.optString("sessionId"),
                            record.optLong("createdAt") ?: 0L,
                            record.optLong("finishedAt") ?: 0L,
                            clock(),
                        ),
                    )
                }
            }
        }
        persistRegistry()
    }

    @Synchronized
    private fun trim() {
        if (retentionWindowMillis >= 0) {
            val now = clock()
            jobs.values.filter { it.finished > 0L }
                .filter { now - it.finished > retentionWindowMillis }
                .forEach { evict(it.id, "retentionWindow") }
        }
        if (maxRetainedFinished >= 0) {
            val finished = jobs.values.filter { it.finished > 0L }.sortedByDescending { it.created }
            if (finished.size > maxRetainedFinished) {
                finished.drop(maxRetainedFinished).forEach { evict(it.id, "retentionLimit") }
            }
        }
        pruneTombstones()
        persistRegistry()
    }

    private fun evict(id: String, reason: String) {
        val job = jobs.remove(id) ?: return
        // 先在 job 锁内复制墓碑字段，再释放 job 锁写 registry，避免 job→registry 与 registry→job 锁倒置。
        val tombstone = synchronized(job) {
            Tombstone(
                job.id,
                "JOB_EVICTED",
                reason,
                job.operation,
                job.owner,
                job.clientId,
                job.sessionId,
                job.created,
                job.finished,
                clock(),
            )
        }
        addTombstone(tombstone)
        runCatching { metadataFile(id).delete() }
        runCatching { resultFile(id).delete() }
        persistRegistry()
    }

    private fun addTombstone(tombstone: Tombstone) = synchronized(registryLock) {
        tombstones[tombstone.jobId] = tombstone
        while (tombstones.size > MAX_TOMBSTONES) {
            val oldest = tombstones.values.minByOrNull { it.recordedAt } ?: break
            tombstones.remove(oldest.jobId)
        }
    }

    private fun pruneTombstones() = synchronized(registryLock) {
        while (tombstones.size > MAX_TOMBSTONES) {
            val oldest = tombstones.values.minByOrNull { it.recordedAt } ?: break
            tombstones.remove(oldest.jobId)
        }
    }

    // ------------------------------------------------------------------
    // 参数与工具函数
    // ------------------------------------------------------------------

    private fun identity(args: JsonObject, key: String): String? {
        val value = args.get(key) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { key + " 必须是字符串" }
        val text = value.asString
        if (text.isEmpty()) return null
        require(text.length <= 128 && text.none { it.code < 0x20 || it.code == 0x7f }) {
            key + " 长度不能超过128且不能包含控制字符"
        }
        return text
    }

    private fun JsonObject.optString(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive) return null
        return runCatching { value.asString }.getOrNull()
    }

    private fun JsonObject.optLong(key: String): Long? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return runCatching { value.asLong }.getOrNull()
    }

    companion object {
        const val DEFAULT_MAX_ACTIVE = 4
        const val DEFAULT_RETENTION_WINDOW_MILLIS = 6L * 60L * 60L * 1000L
        const val DEFAULT_MAX_RETAINED_FINISHED = 64
        const val MAX_TOMBSTONES = 256
        const val MAX_RESULT_BYTES = 24L * 1024 * 1024
        const val MAX_LISTED_TOMBSTONES = 32
        const val INDEX_FILE = "jobs.index.json"
        const val REGISTRY_VERSION = 1
        const val RESTART_REASON = "工具进程已重启，任务没有自动重放"
        private val ACTIVE_STATES = setOf("QUEUED", "RUNNING", "CANCELLING")
        private val TERMINAL_STATES = setOf("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED")
        private val WORKERS = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "apk-tool-job").apply { isDaemon = true }
        }
        private val TIMER = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "apk-job-deadline").apply { isDaemon = true }
        }
        val OPERATIONS = setOf(
            "apk_search", "apk_build", "apk_verify", "apk_dex_call_graph", "apk_dex_search_members",
            "apk_dex_hierarchy", "apk_decompile_class", "apk_decompile_method", "android_resources_compile",
            "apk_component_install", "artifact_open", "artifact_export",
        ) + ToolkitReverseTools.operations
    }
}
