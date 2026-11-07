package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File
import java.io.InterruptedIOException
import java.text.SimpleDateFormat
import java.util.*
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
        var locator: String? = null,
    ) {
        val cancelled = AtomicBoolean()
        @Volatile var state = "QUEUED"
        @Volatile var thread: Thread? = null
        @Volatile var started = 0L
        @Volatile var finished = 0L
        @Volatile var reason = ""
        // 反馈 #11：失败原因的结构化码要能在 apk_job_status/list 里看到，而不是只藏在结果正文。
        @Volatile var errorCode: String? = null
        @Volatile var warningCode: String? = null
        /** P1-5：提示性 JADX INFO/常规注释；不抬状态，只做 notes。 */
        @Volatile var notes: List<String> = emptyList()
        /** P1-5：真正被判成 warning 的原因；SUCCEEDED 时为 null。 */
        @Volatile var warningReason: String? = null
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
    /** locator -> 最新 jobId；配套 by-locator.index.json，避免调用方按目录扫到旧结果。 */
    private val latestByLocator = ConcurrentHashMap<String, String>()
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
            val queueDetails = ToolkitIo.json(
                "active" to active,
                "maxActive" to maxActive,
                "readOnlyFastPath" to ToolkitReverseTools.readOnlyFastTools.toList(),
                "nextActions" to listOf(
                    ToolkitIo.json("tool" to "apk_job_list", "hint" to "查看 QUEUED/RUNNING 任务与 operation/locator"),
                    ToolkitIo.json("tool" to "apk_job_status", "hint" to "等现有任务到终态后再提交"),
                    ToolkitIo.json("tool" to "apk_job_cancel", "hint" to "取消不再需要的重分析任务"),
                ),
            )
            throw ToolkitException(
                "JOB_QUEUE_FULL",
                "最多排队 " + maxActive + " 个 APK 任务；只读状态工具（frida_status/frida_processes）已走不占槽快路径；" +
                    "请先 apk_job_list 查看谁在跑、等终态或 apk_job_cancel 取消不需要的任务。details=" + queueDetails,
                "operation",
            )
        }
        val values = args.getAsJsonObject("arguments")?.deepCopy() ?: JsonObject()
        val job = Job(
            ToolkitIo.fresh(),
            operation,
            values,
            args.tInt("timeoutSeconds", DEFAULT_JOB_TIMEOUT_SECONDS, 1, McpLimits.MAX_TIMEOUT_SECONDS),
            clock(),
            identity(args, "owner"),
            identity(args, "clientId"),
            identity(args, "sessionId"),
            jobLocator(args),
        )
        jobs[job.id] = job
        job.locator?.let { locator ->
            latestByLocator[locator] = job.id
            persistLocatorIndex()
        }
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
        val operation = optionalString(args, "operation")
        val state = optionalString(args, "state")
        val locator = optionalString(args, "locator")
        fun matchesFilters(job: Job): Boolean =
            matches(job, owner, clientId, sessionId) &&
                (operation == null || job.operation == operation) &&
                (state == null || job.state == state) &&
                (locator == null || job.locator == locator)
        val matched = jobs.values.filter { matchesFilters(it) }.sortedByDescending { it.created }
        // 默认 limit 不传=全部，保持旧行为；显式 limit 才分页。cursor 是不透明 jobs1-<offset>。
        val limit = if (args.has("limit") && !args.get("limit").isJsonNull) {
            args.tInt("limit", 20, 1, 200)
        } else Int.MAX_VALUE
        val cursor = args.tString("cursor", "").trim()
        val offset = parseListCursor(cursor, matched.size)
        val page = matched.drop(offset).take(limit)
        val items = page.map { job ->
            status(job).apply {
                addProperty("locator", job.locator)
                addProperty("latestForLocator", job.locator != null && latestByLocator[job.locator] == job.id)
            }
        }
        val evictedMatches = tombstones.values.filter {
            matches(it, owner, clientId, sessionId) && (operation == null || it.operation == operation)
        }.sortedByDescending { it.recordedAt }
        // 默认仍按旧上限返回墓碑；显式 limit 时也限制 evicted，避免 68+ 条里另一半墓碑完全不过滤。
        val evictedLimit = if (limit == Int.MAX_VALUE) MAX_LISTED_TOMBSTONES else minOf(limit, MAX_LISTED_TOMBSTONES)
        val evicted = evictedMatches.take(evictedLimit).map { tombstoneJson(it) }
        val hasMore = offset + page.size < matched.size
        val latestEntries = latestByLocator.entries.mapNotNull { (key, id) ->
            jobs[id]?.let { job -> key to job }
        }.sortedByDescending { it.second.created }
        return ToolkitIo.json(
            "items" to items,
            "count" to items.size,
            "total" to matched.size,
            "matched" to matched.size,
            "offset" to offset,
            "limit" to if (limit == Int.MAX_VALUE) null else limit,
            "cursor" to cursor.ifEmpty { null },
            "nextCursor" to if (hasMore) "jobs1-" + (offset + page.size) else null,
            "hasMore" to hasMore,
            "activeJobs" to jobs.values.count { it.finished == 0L },
            "maxActive" to maxActive,
            "effectiveConcurrency" to EFFECTIVE_CONCURRENCY,
            "effectiveConcurrencyScope" to "per-workspace",
            "workerThreads" to WORKER_THREADS,
            "concurrencyModel" to "serialized-per-workspace",
            "maxActiveMeaning" to "maxActive 是允许同时排队/登记的任务数（QUEUED+RUNNING），不是实际并行执行路数；同一 workspace 的任务由 McpApkToolkit.call 的 workspace 锁串行，因此 effectiveConcurrency=1，workerThreads=2 只是线程池上限。",
            "maxActiveNote" to "maxActive=" + maxActive + " 只表示最多 " + maxActive + " 个任务可同时登记/排队，不代表 " + maxActive + " 个任务并行跑；同 workspace 实际串行，effectiveConcurrency=1；frida_status/frida_processes 等只读工具不占这个槽。",
            "retention" to retentionJson(),
            "retentionNote" to retentionNote(),
            "service" to serviceJson(),
            "evicted" to evicted,
            "evictedTotal" to evictedMatches.size,
            "evictedHasMore" to (evictedMatches.size > evicted.size),
            "latestByLocator" to JsonArray().apply {
                latestEntries.forEach { (key, job) ->
                    add(ToolkitIo.json(
                        "locator" to key,
                        "jobId" to job.id,
                        "operation" to job.operation,
                        "state" to job.state,
                        "createdAt" to job.created,
                        "finishedAt" to job.finished.takeIf { it > 0 },
                        "resultAvailable" to job.resultReady,
                    ))
                }
            },
            "locatorIndexFile" to LOCATOR_INDEX_FILE,
            "filter" to ToolkitIo.json(
                "owner" to owner, "clientId" to clientId, "sessionId" to sessionId,
                "operation" to operation, "state" to state, "locator" to locator,
            ),
            "nextActions" to JsonArray().apply {
                add("要留档：apk_job_export{jobId,outPath} 立刻把结果原样原子导出到工具操作目录；结果只保留 6 小时或最多 64 条已完成任务，超出即删除输出缓存")
                add("按 locator 取最新：items[].latestForLocator=true 或 top-level latestByLocator；也可直接读 by-locator.index.json")
                add("apk_job_list.evicted[] 是 JOB_EVICTED 墓碑；JOB_EVICTED 表示保留窗口/数量淘汰，不等于升级丢失的 SERVICE_RESTARTED")
            },
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
        // 结果文件本身从不受 16 KiB 限制（writeResult 最多 24 MiB）；此前只读窗口默认 16 KiB，
        // 会让一次 apk_job_result 看起来像“盘上只有 16 KiB”。默认提高到 1 MiB，分页字段/语义不变。
        val max = args.tInt("maxChars", DEFAULT_RESULT_CHARS, 1, MAX_RESULT_CHARS)
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
        val format = args.tString("format", "json").lowercase()
        require(format in setOf("json", "text")) { "format 必须是 json 或 text，收到：$format" }
        val rawContent = text.substring(offset, end)
        val textResult = format == "text"
        val suggestedPath = suggestedExportPath(job, text = textResult || isTextOperation(job.operation))
        val result = ToolkitIo.json(
            "jobId" to job.id,
            "operation" to job.operation,
            "state" to job.state,
            "format" to format,
            "content" to rawContent,
            "nextOffset" to end,
            "rawNextOffset" to end,
            "hasMore" to (end < text.length),
            "totalChars" to text.length,
            "resultChars" to text.length,
            "maxChars" to max,
            "defaultMaxChars" to DEFAULT_RESULT_CHARS,
            "maxResultChars" to MAX_RESULT_CHARS,
            "maxResultBytes" to MAX_RESULT_BYTES,
            "resultBytes" to job.resultBytes,
            "owner" to job.owner,
            "clientId" to job.clientId,
            "sessionId" to job.sessionId,
            "lostByRestart" to job.lostByRestart,
            "warningCode" to job.warningCode,
            "warningReason" to job.warningReason,
            "notes" to job.notes,
            "noteCount" to job.notes.size,
            "suggestedExportPath" to suggestedPath,
            "exportHint" to exportHint(job, suggestedPath),
            "retention" to retentionJson(),
        )
        if (textResult) {
            val fullText = extractTextContent(text)
            require(offset <= fullText.length) { "结果 offset 超出正文范围（text 长度 " + fullText.length + "）" }
            require(
                offset == 0 || offset == fullText.length || !Character.isLowSurrogate(fullText[offset]) ||
                    !Character.isHighSurrogate(fullText[offset - 1]),
            ) { "结果offset不能切开UTF-16代理对，请使用nextOffset" }
            var textEnd = minOf(fullText.length, offset + max)
            if (textEnd in (offset + 1) until fullText.length && Character.isHighSurrogate(fullText[textEnd - 1]) &&
                Character.isLowSurrogate(fullText[textEnd])
            ) textEnd--
            require(textEnd > offset || offset == fullText.length) { "maxChars太小，不能容纳当前Unicode字符，请至少使用2" }
            val page = fullText.substring(offset, textEnd)
            result.addProperty("text", page)
            result.addProperty("chars", page.length)
            result.addProperty("truncated", textEnd < fullText.length)
            result.addProperty("nextOffset", textEnd)
            result.addProperty("textOffset", offset)
            result.addProperty("textTotalChars", fullText.length)
            result.addProperty("textHasMore", textEnd < fullText.length)
        }
        return result
    }

    /**
     * 把任务结果缓存原样导出到工具目录内的 outPath（原子写）。
     *
     * 导出内容是 resultFile 的原始 JSON 文本，不是再次包一层 MCP JSON 的转义字符串；
     * offset/maxChars 与 apk_job_result 一样按 UTF-16 字符分页，truncated 表示还有下一页。
     * 保留窗口/墓碑仍是唯一来源：已被淘汰的任务继续报 JOB_EVICTED，重启丢失报 SERVICE_RESTARTED。
     */
    /** 解析任务结果 envelope 的正文；兼容纯文本/旧格式，无法解析时原样返回。 */
    private fun extractTextContent(raw: String): String {
        val root = runCatching { JsonParser().parse(raw) }.getOrNull() ?: return raw
        if (!root.isJsonObject) return raw
        val obj = root.asJsonObject
        val data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        fun textValue(candidate: com.google.gson.JsonElement?): String? =
            candidate?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return textValue(data?.get("content"))
            ?: textValue(data?.get("text"))
            ?: textValue(obj.get("content"))
            ?: raw
    }

    private fun isTextOperation(operation: String): Boolean =
        operation.contains("decompile") || operation.contains("read_text") ||
            operation.contains("smali") || operation.contains("log")

    private fun exportKind(operation: String): String = when (operation) {
        "apk_decompile_class" -> "class"
        "apk_decompile_method" -> "method"
        else -> operation.removePrefix("apk_")
            .replace(Regex("[^A-Za-z0-9_-]"), "-")
            .ifBlank { "result" }
    }

    /** 默认项目来自 project_folder 的 project.json；没有项目时给 root 相对建议，不伪造绝对 root。 */
    private fun defaultProjectDir(): File? {
        val store = folder.absoluteFile.parentFile ?: folder.absoluteFile
        val config = File(store, "project.json")
        if (!config.isFile) return null
        val parsed = runCatching { JsonParser().parse(config.readText(Charsets.UTF_8)).asJsonObject }.getOrNull() ?: return null
        val raw = parsed.get("projectDir")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: parsed.get("path")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return null
        val dir = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        return dir.takeIf { it.isDirectory }
    }

    private fun suggestedExportPath(job: Job, text: Boolean): String {
        val extension = if (text) "txt" else "json"
        val name = job.id + "-" + exportKind(job.operation) + "." + extension
        val project = defaultProjectDir()
        if (project != null) return File(project, name).path
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "mcp-out-" + date + "/" + name
    }

    private fun exportHint(job: Job, suggestedPath: String): String =
        "apk_job_export(" + ToolkitIo.json("jobId" to job.id, "outPath" to suggestedPath).toString() + ")"

    @Synchronized
    fun export(root: File, args: JsonObject): JsonObject {
        val job = find(args)
        if (job.lostByRestart && !job.resultReady) {
            throw ToolkitException("SERVICE_RESTARTED", restartLostMessage(job.id), "jobId")
        }
        require(job.resultReady) { "任务尚无结果，请读取状态" }
        val file = resultFile(job.id)
        require(file.isFile) { "任务结果缓存不存在，可能已被淘汰；请用 apk_job_list 对账" }
        require(file.length() <= MAX_RESULT_BYTES) { "任务结果超过导出预算" }
        val text = file.readText(Charsets.UTF_8)
        val offset = args.tInt("offset", 0, 0, MAX_RESULT_BYTES.toInt())
        require(offset <= text.length) { "结果偏移超出范围" }
        require(
            offset == 0 || offset == text.length || !Character.isLowSurrogate(text[offset]) ||
                !Character.isHighSurrogate(text[offset - 1]),
        ) { "结果offset不能切开UTF-16代理对，请使用nextOffset" }
        // 未显式传 maxChars 时默认导出全部结果；显式分页时与 apk_job_result 同一 1 MiB 上限。
        val max = if (args.has("maxChars") && !args.get("maxChars").isJsonNull) {
            args.tInt("maxChars", DEFAULT_RESULT_CHARS, 1, MAX_RESULT_CHARS)
        } else text.length
        var end = minOf(text.length, offset + max)
        if (end in (offset + 1) until text.length && Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) end--
        require(end > offset || offset == text.length) { "maxChars太小，不能容纳当前Unicode字符，请至少使用2" }
        val chunk = text.substring(offset, end)
        // C：apk_job_export 也是产出；与 apk_*/zip_* 共用同一个默认项目（store 为本 jobs 目录的父目录）。
        val organizer = ToolkitOutputOrganizers.forStore(folder.absoluteFile.parentFile ?: folder, root)
        val organized = organizer.organize(ToolkitIo.resolve(root, args.tString("outPath")), args)
        val target = organized.path
        require(!target.isDirectory) { "outPath 必须是文件，不能是目录" }
        val parent = target.parentFile
        require(parent != null && (parent.isDirectory || parent.mkdirs())) {
            "outPath 父目录不存在或无法创建：" + (parent?.path ?: "")
        }
        ToolkitIo.atomic(target) { it.writeText(chunk, Charsets.UTF_8) }
        return ToolkitIo.json(
            "jobId" to job.id,
            "outPath" to target.path,
            "bytes" to target.length(),
            "chars" to chunk.length,
            "sha256" to ToolkitIo.sha(target),
            "truncated" to (end < text.length),
            "retention" to retentionJson(),
            "offset" to offset,
            "nextOffset" to end,
            "totalChars" to text.length,
            "hasMore" to (end < text.length),
        ).withOrganized(organized)
    }

    // ------------------------------------------------------------------
    // 执行与状态
    // ------------------------------------------------------------------

    private data class SuccessClassification(
        val state: String,
        val warningCode: String?,
        val warningReason: String?,
        val notes: List<String>,
    )

    /**
     * P1-5 判定规则（只改作业状态，不改结果文件里的 data）：
     * - 空 warningCode 视为无 warning（旧组件常回 ""，之前会被误判）。
     * - 提示性：正文非空且无 JADX ERROR/Method dump skipped；decompileIncomplete 仅因全局 errorsCount
     *   或提示注释时，状态 SUCCEEDED，只进 notes/noteCount；JADX INFO 注释也只进 notes。
     * - 真 warning：内容为空、EMPTY_RESULT/RESULT_TRUNCATED、正文含 JADX ERROR/Method dump skipped，
     *   或其它非 DECOMPILE_INCOMPLETE 的 warningCode；才 SUCCEEDED_WITH_WARNINGS + warningReason。
     */
    private fun classifySuccess(data: JsonObject?, operation: String): SuccessClassification {

        // 没有 data 对象（例如旧调用方回的是裸 JSON）时不做判断：不能凭空造出「正文为空」的 warning。
        if (data == null) return SuccessClassification("SUCCEEDED", null, null, emptyList())
        val textOperation = isTextOperation(operation)
        val content = data?.get("content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: data?.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: ""
        val incomplete = data?.get("decompileIncomplete")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val warningCode = data?.get("warningCode")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
        val warningHint = data?.get("warningHint")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val incompleteReason = data?.get("incompleteReason")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val errorsCount = data?.get("errorsCount")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val warningsCount = data?.get("warningsCount")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val infoCount = content.lineSequence()
            .count { line -> line.trimStart().contains("JADX INFO") }
            .coerceAtMost(100)
        val fatal = content.contains("JADX ERROR", ignoreCase = true) ||
            content.contains("Method dump skipped", ignoreCase = true)
        val notes = ArrayList<String>()
        if (infoCount > 0) notes.add("正文含 JADX INFO 提示性注释 " + infoCount + " 条；属于常规提示，不改变 SUCCEEDED")
        if ((incomplete || warningCode == "DECOMPILE_INCOMPLETE") && !fatal && content.isNotBlank()) {
            notes.add("decompileIncomplete/warningCode 仅由全局 errorsCount/提示触发（正文无 JADX ERROR/Method dump skipped）；按提示保留 SUCCEEDED")
        }
        if (errorsCount > 0) notes.add("JADX errorsCount=" + errorsCount + " 是全局计数；不等于当前类/方法正文异常")
        if (warningsCount > 0) notes.add("JADX warningsCount=" + warningsCount + "；提示性计数，已与真 warning 分开")
        if (fatal && content.contains("JADX ERROR", ignoreCase = true)) notes.add("正文含 JADX ERROR")
        if (fatal && content.contains("Method dump skipped", ignoreCase = true)) notes.add("正文含 Method dump skipped")
        if (textOperation && content.isBlank()) notes.add("正文为空，无法确认已完整反编译")
        val hardWarning = ((textOperation || incomplete) && content.isBlank()) ||
            warningCode == "EMPTY_RESULT" || warningCode == "RESULT_TRUNCATED" ||
            (warningCode != null && warningCode != "DECOMPILE_INCOMPLETE") ||
            (incomplete && fatal)
        if (!hardWarning) {
            return SuccessClassification("SUCCEEDED", null, null, notes.take(8).map { it.take(500) })
        }
        val code = warningCode ?: if (incomplete) "DECOMPILE_INCOMPLETE" else "RESULT_INCOMPLETE"
        val reason = warningHint.ifBlank { incompleteReason.ifBlank {
            when {
                warningCode == "EMPTY_RESULT" -> "反编译返回空内容"
                warningCode == "RESULT_TRUNCATED" -> "结果被 maxChars 截断"
                fatal -> "反编译正文含 JADX ERROR/Method dump skipped"
                else -> "任务完成但结果不完整（" + code + "）"
            }
        } }
        return SuccessClassification("SUCCEEDED_WITH_WARNINGS", code, reason.take(1000), notes.take(8).map { it.take(500) })
    }

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
            val responseData = response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            val classification = classifySuccess(responseData, job.operation)
            job.state = if (job.cancelled.get() && (
                    response.get("ok")?.asBoolean == false || responseData?.get("cancelled")?.asBoolean == true
                    )
            ) "CANCELLED"
            else if (response.get("ok")?.asBoolean == false) "FAILED"
            else classification.state
            if (job.state == "SUCCEEDED" || job.state == "SUCCEEDED_WITH_WARNINGS") {
                job.notes = classification.notes
                job.warningCode = classification.warningCode
                job.warningReason = classification.warningReason
                if (job.state == "SUCCEEDED_WITH_WARNINGS") {
                    job.reason = classification.warningReason
                        ?: "任务完成但结果不完整（" + (classification.warningCode ?: "RESULT_INCOMPLETE") + "）"
                }
            }
        } catch (failure: Exception) {
            job.state =
                if (job.cancelled.get() || failure is InterruptedIOException || failure is InterruptedException) "CANCELLED"
                else "FAILED"
            job.reason = job.reason.ifEmpty { failure.message ?: failure.javaClass.simpleName }
            Thread.interrupted()
            val typed = failure as? ToolkitException
            val code = if (job.state == "CANCELLED") "CANCELLED"
            else typed?.code ?: if (failure is IllegalArgumentException) "INVALID_ARGUMENT" else "TOOL_FAILED"
            job.errorCode = code
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
            "locator" to job.locator,
            "latestForLocator" to (job.locator != null && latestByLocator[job.locator] == job.id),
            "lostByRestart" to job.lostByRestart,
            // 反馈 #11：以前只在 lostByRestart 时填 errorCode，操作级失败（如 Frida 连不上）虽然记了 code 却不暴露，
            // 调用方只能从 reason 里猜。现在统一暴露：丢失优先 SERVICE_RESTARTED，否则用本次失败捕获到的码。
            "errorCode" to if (lost) "SERVICE_RESTARTED" else job.errorCode,
            "warningCode" to if (lost) null else job.warningCode,
            "warningReason" to if (lost) null else job.warningReason,
            "notes" to job.notes,
            "noteCount" to job.notes.size,
            "errorHint" to if (lost) "任务因工具进程重启丢失，未自动重放；请确认需要后重新提交"
                else job.errorCode?.let { "任务失败的结构化码；详细原因见 reason 与 apk_job_result 正文" },
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
        // 这是 jobId，不是工作区/会话 id：必须传 label，否则形状不对时文案会串位（反馈 bug#10）。
        val id = ToolkitIo.id(args.tString("jobId"), "jobId")
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
            "任务结果已被淘汰（JOB_EVICTED）：" + tombstone.jobId +
                "；原因=" + evictionReasonText(tombstone.reason) + "；" + whenText +
                "。输出缓存已删除，无法再读取；请重新提交任务"
        } else {
            "任务在工具进程重启时丢失（SERVICE_RESTARTED）：" + tombstone.jobId +
                "；原因=" + tombstone.reason + "；" + whenText + "。任务未自动重放，请重新提交"
        }
    }
    private fun evictionReasonText(reason: String): String {
        val window = if (retentionWindowMillis >= 0) (retentionWindowMillis / 3_600_000L).toString() + " 小时" else "不自动过期"
        return when (reason) {
            "retentionLimit" -> "超出保留窗口（" + window + "）或数量上限（" + maxRetainedFinished + " 条已完成任务）（retentionLimit）"
            "retentionWindow" -> "超出保留窗口（" + window + "）（retentionWindow）"
            else -> reason
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
    private fun locatorIndexFile(): File = File(folder, LOCATOR_INDEX_FILE)

    /** 持久化 locator -> 最新 jobId，供跨进程/重启后按 locator 取最新。 */
    private fun persistLocatorIndex() = synchronized(registryLock) {
        val entries = latestByLocator.entries.mapNotNull { (locator, id) ->
            val job = jobs[id] ?: return@mapNotNull null
            ToolkitIo.json(
                "locator" to locator,
                "jobId" to id,
                "operation" to job.operation,
                "state" to job.state,
                "createdAt" to job.created,
                "finishedAt" to job.finished.takeIf { it > 0 },
                "resultAvailable" to job.resultReady,
            )
        }
        val data = ToolkitIo.json(
            "version" to 1,
            "updatedAt" to clock(),
            "entries" to JsonArray().apply { entries.forEach { add(it) } },
        )
        runCatching { ToolkitIo.atomic(locatorIndexFile()) { it.writeText(data.toString(), Charsets.UTF_8) } }
    }

    private fun loadLocatorIndex() {
        val stored = runCatching {
            val file = locatorIndexFile()
            if (!file.isFile) null
            else JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject
        }.getOrNull()
        latestByLocator.clear()
        // 以内存中已恢复的任务为准，createdAt 最大的为最新。
        jobs.values.filter { !it.locator.isNullOrBlank() }
            .sortedBy { it.created }
            .forEach { job -> latestByLocator[job.locator!!] = job.id }
        // 元数据里没有 locator（旧版本/仅结果恢复）时，尽量保留 by-locator.index.json 里的映射。
        stored?.getAsJsonArray("entries")?.forEach { element ->
            runCatching {
                val item = element.asJsonObject
                val locator = item.optString("locator") ?: return@runCatching
                val id = item.optString("jobId") ?: return@runCatching
                val job = jobs[id] ?: return@runCatching
                if (job.locator == null) job.locator = locator
                if (!latestByLocator.containsKey(locator)) latestByLocator[locator] = id
            }
        }
        persistLocatorIndex()
    }

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
            "locator" to job.locator,
            "lostByRestart" to job.lostByRestart,
            "warningCode" to job.warningCode,
            "warningReason" to job.warningReason,
            "notes" to job.notes,
            "timeoutSeconds" to job.timeout,
        )
        runCatching { ToolkitIo.atomic(metadataFile(job.id)) { it.writeText(metadata.toString(), Charsets.UTF_8) } }
        // locator 索引随任务状态/结果更新，避免 by-locator.index.json 永远停在 QUEUED。
        job.locator?.let { locator ->
            if (latestByLocator[locator] == job.id) persistLocatorIndex()
        }
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
            "locator" to job.locator,
            "resultAvailable" to job.resultReady,
            "resultBytes" to job.resultBytes,
            "lostByRestart" to job.lostByRestart,
            "warningCode" to job.warningCode,
            "warningReason" to job.warningReason,
            "notes" to job.notes,
            "noteCount" to job.notes.size,
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

    /** get_status / toolkit_status 用的可发现摘要；不改保留策略本身。 */
    @Synchronized
    fun summary(): JsonObject {
        trim()
        val evicted = tombstones.values.filter { it.code == "JOB_EVICTED" }
        val last = evicted.maxByOrNull { it.recordedAt }
        return ToolkitIo.json(
            "total" to jobs.size,
            "active" to jobs.values.count { it.finished == 0L },
            "finished" to jobs.values.count { it.finished > 0L },
            "evictedCount" to evicted.size,
            "retention" to ToolkitIo.json(
                "windowMillis" to retentionWindowMillis,
                "maxRetainedFinished" to maxRetainedFinished,
                "maxActive" to maxActive,
                "maxResultBytes" to MAX_RESULT_BYTES,
            ),
            "lastEviction" to last?.let {
                ToolkitIo.json("jobId" to it.jobId, "operation" to it.operation, "reason" to it.reason, "recordedAt" to it.recordedAt)
            },
            "note" to retentionNote(),
        )
    }

    private fun retentionNote(): String {
        val window = if (retentionWindowMillis >= 0)
            (retentionWindowMillis / 3_600_000L).toString() + " 小时"
        else "不自动过期"
        val count = if (maxRetainedFinished >= 0)
            "最多 " + maxRetainedFinished + " 条已完成任务"
        else "不限制已完成任务数量"
        return "结果只保留 " + window + "或" + count +
            "，超出即删除输出缓存（JOB_EVICTED/retentionLimit）；要长期留档请在任务完成后立刻把内容导出到自己的目录"
    }

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
            it.isFile && it.name.endsWith(".json") && !it.name.endsWith(".result.json") &&
                it.name != INDEX_FILE && it.name != LOCATOR_INDEX_FILE
        }?.forEach { file ->
            runCatching {
                val source = JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject
                val id = source.optString("jobId") ?: return@runCatching
                ToolkitIo.id(id, "jobId")
                val job = Job(
                    id,
                    source.optString("operation") ?: return@runCatching,
                    JsonObject(),
                    source.optLong("timeoutSeconds")
                        ?.coerceIn(1L, McpLimits.MAX_TIMEOUT_SECONDS.toLong())
                        ?.toInt() ?: DEFAULT_JOB_TIMEOUT_SECONDS,
                    source.optLong("createdAt") ?: clock(),
                    source.optString("owner"),
                    source.optString("clientId"),
                    source.optString("sessionId"),
                    source.optString("locator"),
                )
                job.state = source.optString("state") ?: "INTERRUPTED"
                job.started = source.optLong("startedAt") ?: 0L
                job.finished = source.optLong("finishedAt") ?: 0L
                job.reason = source.optString("reason") ?: ""
                job.warningCode = source.optString("warningCode")?.takeIf { it.isNotEmpty() }
                job.warningReason = source.optString("warningReason")?.takeIf { it.isNotEmpty() }
                job.notes = source.getAsJsonArray("notes")?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()
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
                ToolkitIo.id(id, "jobId")
                val result = resultFile(id)
                val resultBytes = if (result.isFile) result.length() else 0L
                if (resultBytes > 0L) {
                    val recordedState = record.optString("state") ?: "INTERRUPTED"
                    val job = Job(
                        id,
                        record.optString("operation") ?: "unknown",
                        JsonObject(),
                        DEFAULT_JOB_TIMEOUT_SECONDS,
                        record.optLong("createdAt") ?: clock(),
                        record.optString("owner"),
                        record.optString("clientId"),
                        record.optString("sessionId"),
                        record.optString("locator"),
                    )
                    job.state = if (recordedState in ACTIVE_STATES) "INTERRUPTED" else recordedState
                    job.finished = record.optLong("finishedAt") ?: clock()
                    job.lostByRestart = job.state == "INTERRUPTED"
                    job.reason = if (job.lostByRestart) RESTART_REASON else ""
                    job.warningCode = record.optString("warningCode")?.takeIf { it.isNotEmpty() }
                    job.warningReason = record.optString("warningReason")?.takeIf { it.isNotEmpty() }
                    job.notes = record.getAsJsonArray("notes")?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList()
                    // 反馈实测（K40 dev-1374）：恢复时只改内存状态、不回写元数据 → 元数据文件里仍是 RUNNING/QUEUED，
                    // 于是 workspace 清单的 scanActiveJobs() 永远认为有 activeJobs，apk_workspace_gc 永久保守跳过所有临时工作区
                    // （现象：10 个临时快照 1.0 GiB 一个都回收不掉）。这里把终态写回元数据，幂等。
                    persist(job)
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
                    // 反馈实测（K40 dev-1375）：这条分支只加墓碑、**不清理陈旧元数据文件**，于是 workspace 清单的
                    // scanActiveJobs() 继续把已死的任务当 active，apk_workspace_gc 永久保守跳过全部临时工作区。
                    // 墓碑已经记录了这次重启丢失，元数据文件可以安全删除。
                    runCatching { metadataFile(id).delete() }
                }
            }
        }
        loadLocatorIndex()
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
        val locator = job.locator
        if (locator != null && latestByLocator[locator] == id) {
            latestByLocator.remove(locator)
            jobs.values.filter { it.locator == locator }.maxByOrNull { it.created }
                ?.let { latestByLocator[locator] = it.id }
            persistLocatorIndex()
        }
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

    /** list 的可选字符串过滤条件；空串/null 视为未过滤。 */
    private fun optionalString(args: JsonObject, key: String): String? {
        val value = args.get(key) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { key + " 必须是字符串" }
        val text = value.asString
        if (text.isEmpty()) return null
        require(text.length <= 4096 && text.none { it.code < 0x20 || it.code == 0x7f }) { key + " 含控制字符或过长" }
        return text
    }

    /** 从 apk_job_start 的 arguments.locator 提取 locator；仅用于 by-locator 索引，不改变任务执行。 */
    private fun jobLocator(args: JsonObject): String? {
        val arguments = args.getAsJsonObject("arguments") ?: return null
        val value = arguments.get("locator") ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        val text = value.asString
        if (text.isEmpty()) return null
        return text.takeIf { it.length <= 4096 && it.none { c -> c.code < 0x20 || c.code == 0x7f } }
    }

    /** list 游标：jobs1-<offset>，不透明、只允许从本接口 nextCursor 取得。 */
    private fun parseListCursor(raw: String, total: Int): Int {
        if (raw.isEmpty()) return 0
        require(raw.startsWith("jobs1-")) { "cursor 无效：请使用上次 apk_job_list 返回的 nextCursor" }
        val offset = raw.removePrefix("jobs1-").toIntOrNull()
            ?: throw IllegalArgumentException("cursor 无效：偏移不是整数")
        require(offset in 0..total) { "cursor 超出当前结果范围（total=" + total + "）" }
        return offset
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
        /** timeoutSeconds 未提供时的默认值（与历史行为一致；上界统一由 McpLimits.MAX_TIMEOUT_SECONDS 控制）。 */
        const val DEFAULT_JOB_TIMEOUT_SECONDS = 300
        /** 原 4 个槽在四个重分析后会把 frida_status/frida_processes 这类状态工具也挤成 JOB_QUEUE_FULL；
         * 只读状态工具现已走不占槽快路径，剩余槽位给真正需要后台执行的重任务。
         * 上限取 8（2× 原值）：workerThreads=2、同 workspace 仍串行（effectiveConcurrency=1），
         * 8 只增加“排队/登记”容量，不增加实际并行；结果缓存另有 24MiB/条与 6h/64 条保留约束。 */
        const val DEFAULT_MAX_ACTIVE = 8
        const val DEFAULT_RETENTION_WINDOW_MILLIS = 6L * 60L * 60L * 1000L
        const val DEFAULT_MAX_RETAINED_FINISHED = 64
        const val MAX_TOMBSTONES = 256
        const val MAX_RESULT_BYTES = 24L * 1024 * 1024
        /** 单次结果读取默认窗口；不是落盘上限（落盘上限为 MAX_RESULT_BYTES）。 */
        const val DEFAULT_RESULT_CHARS = 1_048_576
        /** 单次结果读取可请求上限；分页 offset/nextOffset 语义不变。 */
        const val MAX_RESULT_CHARS = 1_048_576
        const val MAX_LISTED_TOMBSTONES = 32
        const val INDEX_FILE = "jobs.index.json"
        const val LOCATOR_INDEX_FILE = "by-locator.index.json"
        /** 实际执行模型：同 workspace 由 McpApkToolkit.call 的 workspace 锁串行；线程池只是上限，不是并行路数。 */
        const val EFFECTIVE_CONCURRENCY = 1
        const val WORKER_THREADS = 2
        const val REGISTRY_VERSION = 1
        const val RESTART_REASON = "工具进程已重启，任务没有自动重放"
        private val ACTIVE_STATES = setOf("QUEUED", "RUNNING", "CANCELLING")
        private val TERMINAL_STATES = setOf("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS", "FAILED", "CANCELLED", "INTERRUPTED")
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
