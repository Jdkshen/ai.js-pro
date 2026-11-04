package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.util.LinkedHashMap

/**
 * MCP §9：应用启动 / 日志 / 退出原因的会话式关联采集（纯逻辑，数据源可注入）。
 *
 * 这里不直接读 logcat / ActivityManager，也不修改任何系统设置：真实采集由宿主实现
 * [DataSource] 后注入；单测使用 fake source + fake clock，见 McpAppTraceTest。
 *
 * 设计约束：
 * - 会话有 TTL 与数量上限；过期会话在 start/read/stop/list 时惰性清理；
 * - [read] 返回 offset/limit/nextOffset/hasMore/truncated，截断来源可解释；
 * - [classifyExit] 只能基于实际日志证据给 reason，SIGKILL 只说明结束方式，
 *   不推断是哪个检测分支；证据不足必须 unknown + uncertainty；
 * - [filterTargetLines] 默认只保留目标包/pid 及启动直接相关行，并报告过滤数量。
 */
internal class McpAppTrace(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val maxSessions: Int = 4,
    private val source: DataSource = DataSource.Empty,
) {

    init {
        require(ttlMillis > 0L) { "ttlMillis 必须大于 0" }
        require(maxSessions > 0) { "maxSessions 必须大于 0" }
    }

    /** 目标进程身份；pid/uid 未知时用 null，不能伪造。 */
    data class Target(
        val packageName: String,
        val pid: Int?,
        val uid: Int?,
    )

    /**
     * 可注入的数据源。宿主可用 logcat/ActivityManager 实现；测试注入固定快照。
     *
     * [snapshot] 应返回从 trace 开始到现在目标相关的有界日志窗口（不是无限全量）；
     * [logsTruncated] 表示宿主已因日志覆盖/权限/预算丢弃过内容。
     */
    internal interface DataSource {
        fun snapshot(packageName: String, pid: Int?, withLogs: Boolean): Snapshot

        object Empty : DataSource {
            override fun snapshot(packageName: String, pid: Int?, withLogs: Boolean): Snapshot = Snapshot()
        }
    }

    data class Snapshot(
        val pid: Int? = null,
        val uid: Int? = null,
        val lines: List<String> = emptyList(),
        val processAlive: Boolean? = null,
        val logsTruncated: Boolean = false,
        val note: String = "",
    )

    private data class Session(
        val id: String,
        val packageName: String,
        val reason: String,
        val withLogs: Boolean,
        val startedAt: Long,
        val expiresAt: Long,
        var target: Target,
        var lines: MutableList<String>,
        var filteredOut: Int,
        var truncated: Boolean,
        var processAlive: Boolean?,
        var sourceNote: String,
        var lastReadOffset: Int,
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private var sequence = 0L

    // ------------------------------------------------------------------
    // 会话 API
    // ------------------------------------------------------------------

    /**
     * 一次性退出诊断（不进 live 会话；供 app_exit_info 使用）。
     * 数据源只抓一次；日志仍先经过 filterTargetLines，SIGKILL/unknown 的保守措辞由 classifyExit 负责。
     */
    fun exitInfo(packageName: String, pid: Int? = null, withLogs: Boolean = true): JsonObject {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return error("INVALID_ARGUMENT", "packageName 不能为空")
        val snapshot = try {
            source.snapshot(pkg, pid, withLogs)
        } catch (error: Throwable) {
            return error("SOURCE_ERROR", "采集数据源失败：" + describe(error))
        }
        val filtered = filterWithStats(snapshot.lines, pkg, snapshot.pid ?: pid)
        val capped = capLines(filtered.lines)
        val target = Target(pkg, snapshot.pid ?: pid, snapshot.uid)
        return JsonObject().apply {
            addProperty("success", true)
            addProperty("packageName", pkg)
            add("target", targetJson(target))
            addProperty("filteredOut", filtered.filteredOut)
            addProperty("truncated", capped.truncated || snapshot.logsTruncated)
            add("exitClassification", classifyExit(capped.lines, target.pid, snapshot.processAlive))
            add("lines", array(capped.lines))
            if (snapshot.note.isNotEmpty()) addProperty("sourceNote", snapshot.note)
            addProperty("note", "一次性退出诊断；未创建 trace 会话，不保留日志缓存")
        }
    }

    /** 建立一次 trace 会话；到达 maxSessions 时淘汰最旧会话，结果里明确返回 evictedSessionIds。 */
    fun start(packageName: String, reason: String?, withLogs: Boolean = true): JsonObject {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return error("INVALID_ARGUMENT", "packageName 不能为空")
        synchronized(lock) {
            val now = clock()
            val evicted = purgeExpired(now)
            if (sessions.size >= maxSessions) {
                val oldest = sessions.entries.minByOrNull { it.value.startedAt }
                if (oldest != null) {
                    sessions.remove(oldest.key)
                    evicted.add(oldest.key)
                }
            }
            val snapshot = try {
                source.snapshot(pkg, null, withLogs)
            } catch (error: Throwable) {
                return error("SOURCE_ERROR", "采集数据源失败：" + describe(error))
            }
            val filtered = filterWithStats(snapshot.lines, pkg, snapshot.pid)
            val capped = capLines(filtered.lines)
            val session = Session(
                id = "trace-" + (++sequence),
                packageName = pkg,
                reason = reason?.trim().orEmpty(),
                withLogs = withLogs,
                startedAt = now,
                expiresAt = now + ttlMillis,
                target = Target(pkg, snapshot.pid, snapshot.uid),
                lines = capped.lines.toMutableList(),
                filteredOut = filtered.filteredOut,
                truncated = capped.truncated || snapshot.logsTruncated,
                processAlive = snapshot.processAlive,
                sourceNote = snapshot.note,
                lastReadOffset = 0,
            )
            sessions[session.id] = session
            return JsonObject().apply {
                addProperty("success", true)
                addProperty("traceSessionId", session.id)
                addProperty("packageName", session.packageName)
                addProperty("reason", session.reason)
                addProperty("startedAt", session.startedAt)
                addProperty("expiresAt", session.expiresAt)
                addProperty("ttlMillis", ttlMillis)
                addProperty("withLogs", session.withLogs)
                add("target", targetJson(session.target))
                addProperty("lineCount", session.lines.size)
                addProperty("filteredOut", session.filteredOut)
                addProperty("truncated", session.truncated)
                add("evictedSessionIds", array(evicted))
                if (session.sourceNote.isNotEmpty()) addProperty("sourceNote", session.sourceNote)
                addProperty("note", "会话已建立；read 返回目标相关日志与退出分类，stop 释放会话与日志缓存")
            }
        }
    }

    /** 读取会话日志分页；每次读取会刷新数据源，但不会让旧日志无解释消失。 */
    fun read(traceSessionId: String, offset: Int, limit: Int): JsonObject {
        synchronized(lock) {
            val now = clock()
            val expired = purgeExpired(now)
            val session = sessions[traceSessionId]
                ?: return if (expired.contains(traceSessionId)) {
                    error("TRACE_EXPIRED", "trace 会话已过期并已清理：" + traceSessionId)
                } else {
                    error("TRACE_NOT_FOUND", "找不到 trace 会话（可能已 stop 或已过期）：" + traceSessionId)
                }
            if (session.withLogs) {
                try {
                    val snapshot = source.snapshot(session.packageName, session.target.pid, true)
                    refresh(session, snapshot)
                } catch (error: Throwable) {
                    return error("SOURCE_ERROR", "读取日志失败：" + describe(error))
                }
            }
            val total = session.lines.size
            val actualOffset = offset.coerceIn(0, total)
            val actualLimit = if (limit <= 0) DEFAULT_READ_LIMIT else limit.coerceAtMost(MAX_READ_LIMIT)
            val end = (actualOffset + actualLimit).coerceAtMost(total)
            val page = if (actualOffset >= total) emptyList() else session.lines.subList(actualOffset, end).toList()
            session.lastReadOffset = end
            return JsonObject().apply {
                addProperty("success", true)
                addProperty("traceSessionId", session.id)
                addProperty("packageName", session.packageName)
                add("target", targetJson(session.target))
                addProperty("withLogs", session.withLogs)
                addProperty("startedAt", session.startedAt)
                addProperty("expiresAt", session.expiresAt)
                addProperty("offset", actualOffset)
                addProperty("limit", actualLimit)
                addProperty("nextOffset", end)
                addProperty("total", total)
                addProperty("hasMore", end < total)
                addProperty("truncated", session.truncated)
                addProperty("filteredOut", session.filteredOut)
                addProperty("lineCount", page.size)
                add("lines", array(page))
                add("exitClassification", classifyExit(session.lines, session.target.pid, session.processAlive))
                if (session.sourceNote.isNotEmpty()) addProperty("sourceNote", session.sourceNote)
            }
        }
    }

    /** 停止会话；真实返回会话与日志缓存释放结果，并附带停止前的退出分类。 */
    fun stop(traceSessionId: String): JsonObject {
        synchronized(lock) {
            val now = clock()
            val expired = purgeExpired(now)
            val session = sessions.remove(traceSessionId)
                ?: return if (expired.contains(traceSessionId)) {
                    error("TRACE_EXPIRED", "trace 会话已过期并已清理：" + traceSessionId)
                } else {
                    error("TRACE_NOT_FOUND", "trace 会话不存在或已停止：" + traceSessionId)
                }
            val classification = classifyExit(session.lines, session.target.pid, session.processAlive)
            return JsonObject().apply {
                addProperty("success", true)
                addProperty("traceSessionId", session.id)
                addProperty("packageName", session.packageName)
                addProperty("stoppedAt", now)
                addProperty("sessionReleased", true)
                addProperty("logsCacheReleased", true)
                addProperty("cachedLines", session.lines.size)
                addProperty("filteredOut", session.filteredOut)
                addProperty("truncated", session.truncated)
                addProperty("withLogs", session.withLogs)
                add("exitClassification", classification)
                addProperty("note", "会话与内存日志缓存已释放；系统日志本身不会被修改或删除")
            }
        }
    }

    /** 列出未过期的 live 会话（不含历史/已释放会话）。 */
    fun list(): JsonObject {
        synchronized(lock) {
            val now = clock()
            val evicted = purgeExpired(now)
            val items = JsonArray()
            sessions.values.forEach { session ->
                items.add(JsonObject().apply {
                    addProperty("traceSessionId", session.id)
                    addProperty("packageName", session.packageName)
                    addProperty("reason", session.reason)
                    addProperty("withLogs", session.withLogs)
                    addProperty("startedAt", session.startedAt)
                    addProperty("expiresAt", session.expiresAt)
                    add("target", targetJson(session.target))
                    addProperty("lineCount", session.lines.size)
                    addProperty("filteredOut", session.filteredOut)
                    addProperty("truncated", session.truncated)
                    addProperty("lastReadOffset", session.lastReadOffset)
                })
            }
            return JsonObject().apply {
                addProperty("success", true)
                addProperty("count", items.size())
                addProperty("maxSessions", maxSessions)
                addProperty("ttlMillis", ttlMillis)
                addProperty("now", now)
                add("sessions", items)
                add("purgedSessionIds", array(evicted))
            }
        }
    }

    // ------------------------------------------------------------------
    // 纯函数：退出原因分类
    // ------------------------------------------------------------------

    /**
     * 将一批目标相关日志分类为退出原因。
     *
     * 注意：SIGKILL 只能说明进程结束方式，不能自动归因为某个检测分支/调用者；
     * 证据不足时返回 unknown + uncertainty，而不是猜测。
     */
    fun classifyExit(lines: List<String>, pid: Int?, sawProcessAlive: Boolean?): JsonObject {
        val all = lines.filter { it.isNotBlank() }
        val javaLines = all.filter { line -> JAVA_MARKERS.any { it.containsMatchIn(line) } }
        val nativeLines = all.filter { line -> NATIVE_MARKERS.any { it.containsMatchIn(line) } }
        val exitLines = all.filter { line -> EXIT_MARKERS.any { it.containsMatchIn(line) } }
        val systemLines = all.filter { line -> SYSTEM_MARKERS.any { it.containsMatchIn(line) } }
        val sigkillLines = all.filter { line -> SIGKILL_MARKERS.any { it.containsMatchIn(line) } }
        val launchLines = all.filter { line -> LAUNCH_MARKERS.any { it.containsMatchIn(line) } }

        val reason: String
        val confidence: String
        val explanation: String
        val uncertainty: String
        val evidence: List<String>
        when {
            nativeLines.isNotEmpty() -> {
                reason = "native_crash"
                confidence = "high"
                evidence = nativeLines.take(MAX_EVIDENCE)
                explanation = "日志包含 native 崩溃特征（Fatal signal/SIGSEGV/SIGABRT/tombstone/backtrace）；" +
                    "只能确认发生了 native crash，是否由启动阶段某检测分支触发仍需调用栈与业务时序证据。"
                uncertainty = if (javaLines.isEmpty()) "" else "同时存在 Java 异常行，需确认是否为同一进程/同一时序。"
            }
            javaLines.isNotEmpty() -> {
                reason = "java_exception"
                confidence = "high"
                evidence = javaLines.take(MAX_EVIDENCE)
                explanation = "日志包含 Java 未捕获异常特征（FATAL EXCEPTION/AndroidRuntime/Caused by）；" +
                    "可按堆栈继续定位，但异常发生在退出前还是退出路径上仍需时间戳对齐。"
                uncertainty = if (sawProcessAlive == true) "日志出现 Java 异常但读取时进程仍存活，异常未必是本次退出的原因。" else ""
            }
            exitLines.isNotEmpty() -> {
                reason = "exit_call"
                confidence = "high"
                evidence = exitLines.take(MAX_EVIDENCE)
                explanation = "日志包含进程主动退出/exit code 特征；请结合退出前后的业务日志确认调用方。"
                uncertainty = ""
            }
            systemLines.isNotEmpty() -> {
                reason = "system_reclaim"
                confidence = "medium"
                evidence = systemLines.take(MAX_EVIDENCE)
                explanation = "日志包含系统内存回收/低内存 kill 特征（lowmemorykiller/LMK/am_low_memory 或带 low_memory 的 am_kill）。"
                uncertainty = "系统回收通常由资源压力触发；不能据此认定应用自身某个检测分支有缺陷。"
            }
            sigkillLines.isNotEmpty() -> {
                reason = "sigkill"
                confidence = "medium"
                evidence = sigkillLines.take(MAX_EVIDENCE)
                explanation = "日志显示进程收到 SIGKILL/signal 9 或系统 Killing；这只说明结束方式，不能推断是哪个检测分支、用户操作或系统策略。"
                uncertainty = "SIGKILL 只解释结束方式，不能自动归因为某个检测分支；需要 kill 来源、更早调用栈或系统日志才能归因。"
            }
            launchLines.isNotEmpty() -> {
                reason = "launch_failure"
                confidence = "medium"
                evidence = launchLines.take(MAX_EVIDENCE)
                explanation = "日志包含启动失败特征（Activity 无法启动/Application 实例化失败/Package 缺失/无法解析入口）。"
                uncertainty = "仅凭当前日志无法区分安装损坏、权限拒绝或代码初始化失败；需要启动阶段完整堆栈。"
            }
            else -> {
                reason = "unknown"
                confidence = "low"
                evidence = all.takeLast(MAX_EVIDENCE)
                explanation = "没有足够证据把退出归类为 Java 异常、native crash、主动 exit、SIGKILL、系统回收或启动失败。"
                uncertainty = buildUncertainty(all, pid, sawProcessAlive)
            }
        }
        return JsonObject().apply {
            addProperty("reason", reason)
            addProperty("confidence", confidence)
            add("evidenceLines", array(evidence))
            addProperty("explanation", explanation)
            addProperty("uncertainty", uncertainty)
            if (pid != null) addProperty("pid", pid) else add("pid", JsonNull.INSTANCE)
            if (sawProcessAlive != null) addProperty("sawProcessAlive", sawProcessAlive) else add("sawProcessAlive", JsonNull.INSTANCE)
            addProperty("lineCount", all.size)
        }
    }

    /**
     * 默认过滤：只保留目标包/pid 行，以及目标崩溃块里的直接启动/崩溃行；
     * 其它应用的大量普通日志会被丢弃（见 start/read 的 filteredOut）。
     */
    fun filterTargetLines(lines: List<String>, packageName: String, pid: Int?): List<String> =
        filterWithStats(lines, packageName, pid).lines

    private data class FilterResult(val lines: List<String>, val filteredOut: Int)

    private fun filterWithStats(lines: List<String>, packageName: String, pid: Int?): FilterResult {
        if (lines.isEmpty()) return FilterResult(emptyList(), 0)
        val out = ArrayList<String>()
        var crashBlock: MutableList<String>? = null
        var crashTarget = false

        fun flushCrashBlock() {
            val block = crashBlock ?: return
            if (crashTarget) out.addAll(block)
            crashBlock = null
            crashTarget = false
        }

        lines.forEach { line ->
            val targetHit = lineMentionsTarget(line, packageName, pid)
            if (crashBlock != null) {
                // 新的崩溃头（例如先出现无关应用 FATAL，再出现目标应用的 FATAL）要切开两个块。
                if (CRASH_HEADER_MARKERS.any { it.containsMatchIn(line) }) {
                    flushCrashBlock()
                    crashBlock = mutableListOf(line)
                    crashTarget = targetHit
                    return@forEach
                }
                if (isCrashBody(line) || line.isBlank()) {
                    crashBlock!!.add(line)
                    if (targetHit) crashTarget = true
                    if (line.isBlank()) flushCrashBlock()
                    return@forEach
                }
                // 崩溃堆栈结束，后续是不同的日志记录；先结算当前块，再按普通行处理。
                flushCrashBlock()
            }
            if (CRASH_HEADER_MARKERS.any { it.containsMatchIn(line) }) {
                crashBlock = mutableListOf(line)
                crashTarget = targetHit
                return@forEach
            }
            if (targetHit) {
                out.add(line)
            }
        }
        flushCrashBlock()
        val filteredOut = (lines.size - out.size).coerceAtLeast(0)
        return FilterResult(out, filteredOut)
    }

    private fun isCrashBody(line: String): Boolean =
        CRASH_HEADER_MARKERS.any { it.containsMatchIn(line) } ||
            CRASH_BODY_MARKERS.any { it.containsMatchIn(line) }

    private fun lineMentionsTarget(line: String, packageName: String, pid: Int?): Boolean {
        if (packageName.isNotEmpty() && line.contains(packageName, ignoreCase = false)) return true
        if (pid != null) {
            val boundary = Regex("(?<![0-9])" + pid + "(?![0-9])")
            if (boundary.containsMatchIn(line)) return true
        }
        return false
    }

    private fun refresh(session: Session, snapshot: Snapshot) {
        val hasInfo = snapshot.pid != null || snapshot.uid != null || snapshot.processAlive != null ||
            snapshot.lines.isNotEmpty() || snapshot.logsTruncated || snapshot.note.isNotEmpty()
        if (!hasInfo) return
        if (snapshot.pid != null || snapshot.uid != null) {
            session.target = Target(
                session.packageName,
                snapshot.pid ?: session.target.pid,
                snapshot.uid ?: session.target.uid,
            )
        }
        if (session.withLogs && (snapshot.lines.isNotEmpty() || snapshot.logsTruncated)) {
            val filtered = filterWithStats(snapshot.lines, session.packageName, session.target.pid)
            val capped = capLines(filtered.lines)
            session.lines = capped.lines.toMutableList()
            session.filteredOut = filtered.filteredOut
            session.truncated = capped.truncated || snapshot.logsTruncated
        }
        session.processAlive = snapshot.processAlive ?: session.processAlive
        session.sourceNote = snapshot.note
    }

    private fun capLines(lines: List<String>): CapResult {
        if (lines.size <= MAX_CACHED_LINES) return CapResult(lines, false)
        return CapResult(lines.takeLast(MAX_CACHED_LINES), true)
    }

    private data class CapResult(val lines: List<String>, val truncated: Boolean)

    private fun purgeExpired(now: Long): MutableList<String> {
        val removed = ArrayList<String>()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now >= entry.value.expiresAt) {
                removed.add(entry.key)
                iterator.remove()
            }
        }
        return removed
    }

    private fun targetJson(target: Target): JsonObject = JsonObject().apply {
        addProperty("packageName", target.packageName)
        if (target.pid != null) addProperty("pid", target.pid) else add("pid", JsonNull.INSTANCE)
        if (target.uid != null) addProperty("uid", target.uid) else add("uid", JsonNull.INSTANCE)
    }

    private fun buildUncertainty(lines: List<String>, pid: Int?, sawProcessAlive: Boolean?): String {
        val reasons = ArrayList<String>()
        if (lines.isEmpty()) reasons.add("没有目标日志行（日志为空、权限不足或采集窗口未覆盖退出时刻）")
        if (pid == null) reasons.add("缺少目标 pid")
        when (sawProcessAlive) {
            true -> reasons.add("读取时进程仍存活，当前不是一次已确认的退出")
            false -> reasons.add("读取时进程已不存活，但没有可识别的退出证据")
            null -> reasons.add("缺少进程存活状态")
        }
        if (reasons.isEmpty()) reasons.add("日志中没有可识别的退出原因特征")
        return "证据不足： " + reasons.joinToString("；") + "。不能据此推断具体退出原因。"
    }

    private fun error(code: String, message: String): JsonObject = JsonObject().apply {
        addProperty("success", false)
        addProperty("code", code)
        addProperty("error", message)
    }

    private fun array(values: List<String>): JsonArray = JsonArray().apply {
        values.forEach { value -> add(value) }
    }

    private fun describe(error: Throwable): String =
        error.javaClass.simpleName + ":" + (error.message ?: "")

    private companion object {
        const val DEFAULT_READ_LIMIT = 100
        const val MAX_READ_LIMIT = 500
        const val MAX_CACHED_LINES = 4000
        const val MAX_EVIDENCE = 8

        val JAVA_MARKERS = listOf(
            Regex("""FATAL EXCEPTION""", RegexOption.IGNORE_CASE),
            Regex("""E/AndroidRuntime"""),
            Regex("""Caused by:"""),
            Regex("""\b(?:java|kotlin)\.[A-Za-z0-9_.$]+(?:Exception|Error)\b"""),
        )
        val NATIVE_MARKERS = listOf(
            Regex("""Fatal signal \d+""", RegexOption.IGNORE_CASE),
            Regex("""\bSIG(?:SEGV|ABRT|BUS|ILL|TRAP)\b""", RegexOption.IGNORE_CASE),
            Regex("""beginning of crash""", RegexOption.IGNORE_CASE),
            Regex("""\btombstone\b""", RegexOption.IGNORE_CASE),
            Regex("""\bbacktrace:\b""", RegexOption.IGNORE_CASE),
        )
        val EXIT_MARKERS = listOf(
            Regex("""\b(?:System|Runtime\.getRuntime\(\)|Process)\.exit\b""", RegexOption.IGNORE_CASE),
            Regex("""\bexit\(\s*-?\d+\s*\)"""),
            Regex("""\bexit_call\b""", RegexOption.IGNORE_CASE),
            Regex("""finished with exit code""", RegexOption.IGNORE_CASE),
            Regex("""process exited with (?:code|status)""", RegexOption.IGNORE_CASE),
        )
        val SYSTEM_MARKERS = listOf(
            Regex("""lowmemorykiller""", RegexOption.IGNORE_CASE),
            Regex("""\bLMK\b""", RegexOption.IGNORE_CASE),
            Regex("""am_low_memory""", RegexOption.IGNORE_CASE),
            Regex("""system[_ ]reclaim""", RegexOption.IGNORE_CASE),
            Regex("""am_kill.*(?:low_memory|empty|excessive|memory)""", RegexOption.IGNORE_CASE),
        )
        val SIGKILL_MARKERS = listOf(
            Regex("""\bSIGKILL\b""", RegexOption.IGNORE_CASE),
            Regex("""\bsignal 9\b""", RegexOption.IGNORE_CASE),
            Regex("""\bam_kill\b""", RegexOption.IGNORE_CASE),
            Regex("""\bKilling \d+:"""),
            Regex("""\bKilled by signal 9\b""", RegexOption.IGNORE_CASE),
        )
        val LAUNCH_MARKERS = listOf(
            Regex("""Unable to start activity""", RegexOption.IGNORE_CASE),
            Regex("""Unable to instantiate application""", RegexOption.IGNORE_CASE),
            Regex("""Error launching""", RegexOption.IGNORE_CASE),
            Regex("""Activity not started""", RegexOption.IGNORE_CASE),
            Regex("""Package .* not found""", RegexOption.IGNORE_CASE),
        )
        val CRASH_HEADER_MARKERS = listOf(
            Regex("""FATAL EXCEPTION""", RegexOption.IGNORE_CASE),
            Regex("""beginning of crash""", RegexOption.IGNORE_CASE),
            Regex("""Fatal signal \d+""", RegexOption.IGNORE_CASE),
            Regex("""\bSIG(?:SEGV|ABRT|BUS|ILL|TRAP|KILL)\b""", RegexOption.IGNORE_CASE),
        )
        val CRASH_BODY_MARKERS = listOf(
            Regex("""^\s*(?:at |Caused by:|Suppressed:|\.\.\.\s+\d+\s+more)"""),
            Regex("""AndroidRuntime\(.*?\):\s*(?:java\.|kotlin\.|Process:|at |Caused by:)"""),
            Regex("""\bDEBUG\b""", RegexOption.IGNORE_CASE),
            Regex("""\bbacktrace:""", RegexOption.IGNORE_CASE),
            Regex("""^\s*#\d+\s+pc\b"""),
            Regex("""\blibc\b""", RegexOption.IGNORE_CASE),
        )
    }
}
