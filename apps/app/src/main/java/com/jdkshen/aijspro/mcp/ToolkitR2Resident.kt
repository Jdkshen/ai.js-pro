package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 由接线层实现的常驻 r2 进程工厂（ProcessBuilder + stdin/stdout）。 */
internal interface R2Pipe {
    fun open(inputPath: String, args: List<String>): R2PipeHandle
}

/** 常驻 r2 进程句柄。readUntil 必须读到 marker 行才返回。 */
internal interface R2PipeHandle {
    fun write(line: String)
    fun readUntil(marker: String, timeoutMillis: Long, maxBytes: Long): String
    fun alive(): Boolean
    fun close()
}

/**
 * 常驻 r2 进程会话：一次 open 加载输入文件，后续 command/batch 复用同一个 stdin/stdout，
 * 不再为每条查询启动新进程或 "Po" project。执行由注入的 [R2Pipe] 完成，本类保持纯 JVM 逻辑。
 *
 * framing: 写入 "<command>;?e <MARKER>"，读取到 MARKER 行即认为命令返回；MARKER 每会话唯一。
 * 未注入 pipe 时只返回可执行计划（executed=false），不伪造输出。
 */
internal class ToolkitR2Resident(
    storeDir: File,
    private val pipe: R2Pipe?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 30L * 60 * 1000,
    private val maxSessions: Int = 2,
    private val maxCommandChars: Int = 4096,
    private val maxOutputBytes: Long = 2L * 1024 * 1024,
    private val defaultTimeoutMillis: Long = 30_000,
) {
    companion object {
        const val MAX_INPUT_PATH = 4096
        // 上界统一到 McpLimits（原 300s）
    const val MAX_TIMEOUT_MILLIS = McpLimits.MAX_TIMEOUT_SECONDS * 1000L
        private const val WARMUP_TIMEOUT_MILLIS = 15_000L
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        private val MARKER_SAFE = Regex("[^A-Za-z0-9_]")
    }

    private enum class RawStatus {
        OK,
        TIMEOUT,
        OUTPUT_LIMIT,
        SESSION_DEAD,
        WRITE_ERROR,
        READ_ERROR,
    }

    private data class RawResult(
        val status: RawStatus,
        val output: String = "",
        val bytes: Long = 0L,
        val truncated: Boolean = false,
        val message: String = "",
    )

    private class Session(
        val sessionId: String,
        val inputPath: String,
        val sha256: String,
        val relocsApply: Boolean,
        val argv: List<String>,
        val marker: String,
        val handle: R2PipeHandle?,
        val createdAt: Long,
        var lastAccessAt: Long,
        var commandCount: Long = 0L,
        var warmedUp: Boolean = false,
    )

    private val root = storeDir.canonicalFile
    private val lock = ReentrantLock()
    private val sessions = LinkedHashMap<String, Session>()

    init {
        require(storeDir.exists() || storeDir.mkdirs()) { "cannot create r2 resident store" }
        require(root.isDirectory) { "r2 resident store is not a directory" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
        require(maxSessions > 0) { "maxSessions must be positive" }
        require(maxCommandChars > 0) { "maxCommandChars must be positive" }
        require(maxOutputBytes > 0L) { "maxOutputBytes must be positive" }
        require(defaultTimeoutMillis > 0L) { "defaultTimeoutMillis must be positive" }
    }

    fun open(
        inputPath: String,
        sha256: String,
        relocsApply: Boolean = true,
    ): JsonObject = lock.withLock {
        purgeExpiredLocked()
        if (sessions.size >= maxSessions) {
            throw ToolkitException(
                "SESSION_LIMIT",
                "resident r2 sessions reached limit " + maxSessions + "; close a session first",
                "maxSessions",
            )
        }
        val path = normalizeInputPath(inputPath)
        val sha = normalizeSha(sha256)
        val argv = buildArgv(path, relocsApply)
        val sessionId = newSessionId()
        val marker = newMarker(sessionId)
        val activePipe = pipe
        val handle = if (activePipe == null) null else runCatching {
            activePipe.open(path, argv)
        }.getOrElse { failure ->
            throw ToolkitException(
                "PIPE_OPEN_FAILED",
                "cannot start resident r2 process: " + (failure.message ?: failure.javaClass.simpleName),
                "pipe",
            )
        }
        val now = clock()
        val session = Session(
            sessionId = sessionId,
            inputPath = path,
            sha256 = sha,
            relocsApply = relocsApply,
            argv = argv,
            marker = marker,
            handle = handle,
            createdAt = now,
            lastAccessAt = now,
        )
        sessions[sessionId] = session
        // 预热：r2 进程启动/首次刷新会吃掉第一条命令的超时预算（真机实测首条 ij 会 TIMEOUT），
        // 这里在 open 里先跑一条无副作用命令（?e）把启动成本提前消化；失败不影响 open 成功。
        if (handle != null) {
            val warmup = runCatching { executeRaw(session, framedLine("?e ready", marker), WARMUP_TIMEOUT_MILLIS) }.getOrNull()
            // 只有真正读到 NUL（status=OK）才算预热成功；超时/读错不能谎报 warmedUp。
            if (warmup != null && warmup.status == RawStatus.OK) session.warmedUp = true
        }
        return openJson(session)
    }

    fun command(
        sessionId: String,
        command: String,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): JsonObject = lock.withLock {
        val timeout = normalizeTimeout(timeoutMillis)
        val session = liveSessionLocked(sessionId)
        val normalized = normalizeUserCommand(command)
        val line = framedLine(normalized, session.marker)
        session.lastAccessAt = clock()
        if (session.handle == null) {
            return planJson(session, normalized, line, timeout)
        }
        val raw = executeRaw(session, line, timeout)
        session.lastAccessAt = clock()
        when (raw.status) {
            RawStatus.OK -> {
                session.commandCount++
                return commandJson(session, normalized, line, timeout, raw)
            }
            RawStatus.TIMEOUT -> throw ToolkitException(
                "TIMEOUT",
                "resident r2 command exceeded " + timeout + "ms (TIMEOUT; marker=" + session.marker + ")",
                "timeoutMillis",
            )
            RawStatus.OUTPUT_LIMIT -> throw ToolkitException(
                "OUTPUT_LIMIT",
                "resident r2 output " + raw.bytes + " bytes exceeds limit " + maxOutputBytes +
                    " (truncated=true; marker=" + session.marker + ")",
                "maxOutputBytes",
            )
            RawStatus.SESSION_DEAD -> {
                removeDeadLocked(session)
                throw ToolkitException("SESSION_DEAD", "resident r2 process is dead; reopen the session", "sessionId")
            }
            RawStatus.WRITE_ERROR -> throw ToolkitException(
                "WRITE_ERROR",
                "cannot write to resident r2 process: " + raw.message,
                "pipe",
            )
            RawStatus.READ_ERROR -> {
                if (!safeAlive(session.handle)) {
                    removeDeadLocked(session)
                    throw ToolkitException("SESSION_DEAD", "resident r2 process is dead; reopen the session", "sessionId")
                }
                throw ToolkitException("READ_ERROR", "cannot read from resident r2 process: " + raw.message, "pipe")
            }
        }
    }

    fun batch(
        sessionId: String,
        commands: List<String>,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): JsonObject = lock.withLock {
        val timeout = normalizeTimeout(timeoutMillis)
        val session = liveSessionLocked(sessionId)
        session.lastAccessAt = clock()
        val results = JsonArray()
        var okCount = 0
        var errorCount = 0
        var totalOutputBytes = 0L
        var truncated = false
        var executedAny = false
        var remaining = maxOutputBytes
        var sessionDead = false
        for ((index, rawCommand) in commands.withIndex()) {
            if (sessionDead) {
                results.add(commandItem(index, rawCommand, false, false, "SESSION_DEAD",
                    "resident r2 process is dead; reopen the session", "", 0L, true))
                errorCount++
                continue
            }
            var rejection: ToolkitException? = null
            val normalized = runCatching { normalizeUserCommand(rawCommand) }.getOrElse { failure ->
                rejection = (failure as? ToolkitException)
                    ?: ToolkitException("COMMAND_REJECTED", failure.message ?: "command rejected", "command")
                null
            }
            if (normalized == null) {
                val failure = rejection!!
                results.add(commandItem(index, rawCommand, false, false, failure.code ?: "COMMAND_REJECTED",
                    failure.message ?: "command rejected", "", 0L, false))
                errorCount++
                continue
            }
            val line = framedLine(normalized, session.marker)
            if (session.handle == null) {
                results.add(commandItem(index, normalized, true, false, null, null, "", 0L, false,
                    line = line))
                okCount++
                continue
            }
            executedAny = true
            val raw = executeRaw(session, line, timeout)
            session.commandCount++
            session.lastAccessAt = clock()
            when (raw.status) {
                RawStatus.SESSION_DEAD -> {
                    sessionDead = true
                    results.add(commandItem(index, normalized, false, true, "SESSION_DEAD",
                        "resident r2 process is dead; reopen the session", "", 0L, false, line = line))
                    errorCount++
                    removeDeadLocked(session)
                }
                RawStatus.WRITE_ERROR -> {
                    results.add(commandItem(index, normalized, false, false, "WRITE_ERROR",
                        raw.message, "", 0L, false, line = line))
                    errorCount++
                }
                RawStatus.READ_ERROR -> {
                    results.add(commandItem(index, normalized, false, true, "READ_ERROR",
                        raw.message, "", 0L, false, line = line))
                    errorCount++
                }
                RawStatus.OK, RawStatus.TIMEOUT, RawStatus.OUTPUT_LIMIT -> {
                    val keep = minOf(raw.bytes, remaining)
                    val keptOutput = truncateUtf8(raw.output, keep)
                    val keptBytes = utf8Bytes(keptOutput)
                    remaining -= keptBytes
                    totalOutputBytes += keptBytes
                    val budgetTruncated = keep < raw.bytes
                    val itemTruncated = raw.truncated || budgetTruncated
                    if (itemTruncated) truncated = true
                    val itemCode = when {
                        raw.status == RawStatus.TIMEOUT -> "TIMEOUT"
                        itemTruncated -> "OUTPUT_LIMIT"
                        else -> null
                    }
                    val itemOk = itemCode == null
                    if (itemOk) okCount++ else errorCount++
                    results.add(commandItem(
                        index = index,
                        command = normalized,
                        ok = itemOk,
                        executed = true,
                        errorCode = itemCode,
                        message = when (itemCode) {
                            "TIMEOUT" -> "resident r2 command exceeded " + timeout + "ms"
                            "OUTPUT_LIMIT" -> "batch output truncated=true; total limit " + maxOutputBytes
                            else -> null
                        },
                        output = keptOutput,
                        bytes = keptBytes,
                        truncated = itemTruncated,
                        line = line,
                    ))
                }
            }
        }
        session.lastAccessAt = clock()
        return ToolkitIo.json(
            "ok" to (errorCount == 0),
            "sessionId" to sessionId,
            "executed" to executedAny,
            "runnerConfigured" to (session.handle != null),
            "total" to commands.size,
            "okCount" to okCount,
            "errorCount" to errorCount,
            "totalOutputBytes" to totalOutputBytes,
            "truncated" to truncated,
            "timeoutMillis" to timeout,
            "results" to results,
        )
    }

    fun close(sessionId: String): JsonObject = lock.withLock {
        validateSessionId(sessionId)
        val session = sessions.remove(sessionId)
            ?: throw ToolkitException("SESSION_NOT_FOUND", "resident r2 session not found: " + sessionId, "sessionId")
        val wasAlive = session.handle != null && safeAlive(session.handle)
        var closeError: String? = null
        if (session.handle != null) {
            runCatching { session.handle.close() }.onFailure { failure ->
                closeError = failure.message ?: failure.javaClass.simpleName
            }
        }
        return ToolkitIo.json(
            "sessionId" to session.sessionId,
            "closed" to (closeError == null),
            "wasAlive" to wasAlive,
            "releasedProcess" to (session.handle != null),
            "releasedBytes" to 0L,
            "remainingSessions" to sessions.size,
            "errorCode" to if (closeError == null) null else "CLOSE_ERROR",
            "message" to closeError,
        )
    }

    fun list(): JsonObject = lock.withLock {
        purgeExpiredLocked()
        val array = JsonArray()
        for (session in sessions.values.sortedBy { it.createdAt }) {
            array.add(ToolkitIo.json(
                "sessionId" to session.sessionId,
                "inputPath" to session.inputPath,
                "sha256" to session.sha256,
                "relocsApply" to session.relocsApply,
                "createdAt" to session.createdAt,
                "lastAccessAt" to session.lastAccessAt,
                "expiresAt" to (session.lastAccessAt + ttlMillis),
                "commandCount" to session.commandCount,
                "marker" to session.marker,
                "alive" to (session.handle != null && safeAlive(session.handle)),
                "runnerConfigured" to (session.handle != null),
            ))
        }
        return ToolkitIo.json(
            "ok" to true,
            "total" to sessions.size,
            "sessions" to array,
            "maxSessions" to maxSessions,
            "ttlMillis" to ttlMillis,
            "maxCommandChars" to maxCommandChars,
            "maxOutputBytes" to maxOutputBytes,
            "defaultTimeoutMillis" to defaultTimeoutMillis,
            "runnerConfigured" to (pipe != null),
            "storeDir" to root.path,
        )
    }

    // ------------------------------------------------------------------
    // framing / bounded execution
    // ------------------------------------------------------------------

    private fun executeRaw(session: Session, line: String, timeoutMillis: Long): RawResult {
        val handle = session.handle
            ?: return RawResult(RawStatus.READ_ERROR, message = "no resident handle")
        try {
            handle.write(line)
        } catch (failure: Exception) {
            return if (!safeAlive(handle)) RawResult(RawStatus.SESSION_DEAD, message = "write failed")
            else RawResult(RawStatus.WRITE_ERROR, message = failure.message ?: failure.javaClass.simpleName)
        }
        val raw = try {
            handle.readUntil(session.marker, timeoutMillis, maxOutputBytes)
        } catch (timeout: TimeoutException) {
            return RawResult(RawStatus.TIMEOUT, message = "timeout")
        } catch (failure: Exception) {
            return if (!safeAlive(handle)) RawResult(RawStatus.SESSION_DEAD, message = "read failed")
            else RawResult(RawStatus.READ_ERROR, message = failure.message ?: failure.javaClass.simpleName)
        }
        return interpretRaw(raw, session.marker)
    }

    private fun interpretRaw(raw: String, marker: String): RawResult {
        val normalized = raw.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        // framing：写端发 `<cmd>;?e <marker>`，读端一直读到 marker 行才返回（r2 以 -0 启动，
        // NUL 保证每条命令后立即刷新，避免非 TTY 的块缓冲把 marker 卡住）。
        // 没有 marker 就是没读到本次命令的结尾，不能把半截输出当成结果。
        val found = lines.any { it.trim() == marker }
        if (!found) {
            val clean = truncateUtf8(normalized, maxOutputBytes)
            return RawResult(
                status = RawStatus.TIMEOUT,
                output = clean,
                bytes = utf8Bytes(clean),
                truncated = utf8Bytes(normalized) > maxOutputBytes,
                message = "marker not observed before timeout",
            )
        }
        val output = lines.filter { it.trim() != marker }.joinToString("\n")
        val bytes = utf8Bytes(output)
        if (bytes > maxOutputBytes) {
            val truncated = truncateUtf8(output, maxOutputBytes)
            return RawResult(
                status = RawStatus.OUTPUT_LIMIT,
                output = truncated,
                bytes = utf8Bytes(truncated),
                truncated = true,
                message = "output exceeds maxOutputBytes",
            )
        }
        return RawResult(RawStatus.OK, output = output, bytes = bytes)
    }

    /**
     * framing 说明：r2 以 `-0` 启动，每条 stdin 命令的输出后跟一个 NUL 字节——NUL 就是终止符，
     * 读端（R2PipeHandle.readUntil）读到 NUL 即返回。
     * 历史上这里用 `<cmd>;?e <marker>` 做哨兵，但 `;` 会让 r2 把它拆成两条命令并各打印一个 NUL，
     * 导致输出错位（真机实测：首条命令读到上一条的残留）。所以现在只写命令本身。
     */
    private fun framedLine(command: String, marker: String): String = command + ";?e " + marker

    // ------------------------------------------------------------------
    // sessions
    // ------------------------------------------------------------------

    private fun liveSessionLocked(sessionId: String): Session {
        purgeExpiredLocked()
        validateSessionId(sessionId)
        val session = sessions[sessionId]
            ?: throw ToolkitException("SESSION_NOT_FOUND", "resident r2 session not found: " + sessionId, "sessionId")
        val handle = session.handle
        if (handle != null && !safeAlive(handle)) {
            removeDeadLocked(session)
            throw ToolkitException("SESSION_DEAD", "resident r2 process is dead; reopen the session", "sessionId")
        }
        return session
    }

    private fun removeDeadLocked(session: Session) {
        sessions.remove(session.sessionId)
        if (session.handle != null) runCatching { session.handle.close() }
    }

    private fun purgeExpiredLocked() {
        val now = clock()
        val expired = sessions.values.filter { now >= it.lastAccessAt + ttlMillis }
        for (session in expired) {
            sessions.remove(session.sessionId)
            if (session.handle != null) runCatching { session.handle.close() }
        }
    }

    private fun newSessionId(): String {
        var candidate: String
        do {
            candidate = "r2r-" + UUID.randomUUID().toString().replace("-", "").take(24)
        } while (sessions.containsKey(candidate))
        return candidate
    }

    private fun newMarker(sessionId: String): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        return "__R2R_" + MARKER_SAFE.replace(sessionId, "_") + "_" + suffix + "__"
    }

    // ------------------------------------------------------------------
    // JSON shapes
    // ------------------------------------------------------------------

    private fun buildArgv(inputPath: String, relocsApply: Boolean): List<String> = listOf(
        "-N",
        "-2",
        "-q",
        // -0：每条 stdin 命令的输出后打印 NUL；没有它 r2 的 stdout 在非 TTY 下会被块缓冲，
        // 常驻会话读不到 marker（真机实测每条命令都变成 TIMEOUT）。
        "-0",
        "-e",
        "scr.color=0",
        "-e",
        "scr.interactive=false",
        "-e",
        "bin.relocs.apply=" + if (relocsApply) "true" else "false",
        inputPath,
    )

    private fun openJson(session: Session): JsonObject = ToolkitIo.json(
        "ok" to true,
        "sessionId" to session.sessionId,
        "inputPath" to session.inputPath,
        "sha256" to session.sha256,
        "relocsApply" to session.relocsApply,
        "relocsFlag" to ("bin.relocs.apply=" + if (session.relocsApply) "true" else "false"),
        "marker" to session.marker,
        "argv" to session.argv,
        "commandLine" to session.argv.joinToString(" "),
        "runnerConfigured" to (pipe != null),
        "executed" to false,
        "warmedUp" to session.warmedUp,
        "createdAt" to session.createdAt,
        "lastAccessAt" to session.lastAccessAt,
        "expiresAt" to (session.lastAccessAt + ttlMillis),
        "ttlMillis" to ttlMillis,
        "maxSessions" to maxSessions,
        "maxCommandChars" to maxCommandChars,
        "maxOutputBytes" to maxOutputBytes,
        "defaultTimeoutMillis" to defaultTimeoutMillis,
        "note" to if (pipe == null) {
            "pipe 未注入：command/batch 返回可执行计划（executed=false），不会伪造输出"
        } else {
            "常驻 r2 进程已启动；command/batch 复用同一 stdin/stdout"
        },
    )

    private fun commandJson(
        session: Session,
        command: String,
        line: String,
        timeoutMillis: Long,
        raw: RawResult,
    ): JsonObject = ToolkitIo.json(
        "ok" to true,
        "executed" to true,
        "sessionId" to session.sessionId,
        "command" to command,
        "line" to line,
        "marker" to session.marker,
        "output" to raw.output,
        "bytes" to raw.bytes,
        "truncated" to false,
        "timeoutMillis" to timeoutMillis,
        "runnerConfigured" to true,
    )

    private fun planJson(session: Session, command: String, line: String, timeoutMillis: Long): JsonObject =
        ToolkitIo.json(
            "ok" to true,
            "executed" to false,
            "sessionId" to session.sessionId,
            "command" to command,
            "line" to line,
            "marker" to session.marker,
            "output" to "",
            "bytes" to 0L,
            "truncated" to false,
            "timeoutMillis" to timeoutMillis,
            "runnerConfigured" to false,
            "note" to "pipe 未注入：这是可执行计划，未执行也不伪造输出",
        )

    private fun commandItem(
        index: Int,
        command: String,
        ok: Boolean,
        executed: Boolean,
        errorCode: String?,
        message: String?,
        output: String,
        bytes: Long,
        truncated: Boolean,
        line: String = "",
    ): JsonObject = ToolkitIo.json(
        "index" to index,
        "ok" to ok,
        "executed" to executed,
        "errorCode" to errorCode,
        "message" to message,
        "command" to command,
        "line" to line,
        "output" to output,
        "bytes" to bytes,
        "truncated" to truncated,
    )

    // ------------------------------------------------------------------
    // validation / bounded helpers
    // ------------------------------------------------------------------

    private fun normalizeInputPath(value: String): String {
        val path = value.trim()
        if (path.isEmpty() || path.length > MAX_INPUT_PATH || path.contains('\u0000') ||
            path.contains('\n') || path.contains('\r')) {
            throw ToolkitException("INPUT_INVALID", "inputPath is invalid", "inputPath")
        }
        return path
    }

    private fun normalizeSha(value: String): String {
        if (!SHA256.matches(value)) {
            throw ToolkitException("INPUT_INVALID", "sha256 must be 64 hex chars", "sha256")
        }
        return value.lowercase(Locale.ROOT)
    }

    private fun normalizeTimeout(value: Long): Long {
        if (value !in 1..MAX_TIMEOUT_MILLIS) {
            throw ToolkitException(
                "INPUT_INVALID",
                "timeoutMillis must be in 1.." + MAX_TIMEOUT_MILLIS,
                "timeoutMillis",
            )
        }
        return value
    }

    private fun normalizeUserCommand(value: String): String {
        val command = value.trim()
        if (command.isEmpty()) {
            throw ToolkitException("COMMAND_REJECTED", "command must not be empty", "command")
        }
        if (command.length > maxCommandChars) {
            throw ToolkitException(
                "COMMAND_TOO_LONG",
                "command length " + command.length + " exceeds limit " + maxCommandChars,
                "command",
            )
        }
        if (command.contains('!')) {
            throw ToolkitException("COMMAND_REJECTED", "shell escape ! is not allowed in r2 commands", "command")
        }
        if (command.contains('\u0000') || command.contains('\n') || command.contains('\r')) {
            throw ToolkitException("COMMAND_REJECTED", "command contains control characters", "command")
        }
        return command
    }

    private fun validateSessionId(value: String) {
        if (!SESSION_ID.matches(value)) {
            throw ToolkitException("SESSION_NOT_FOUND", "resident r2 sessionId format invalid: " + value, "sessionId")
        }
    }

    private fun safeAlive(handle: R2PipeHandle?): Boolean =
        handle != null && runCatching { handle.alive() }.getOrDefault(false)

    private fun utf8Bytes(value: String): Long = value.toByteArray(Charsets.UTF_8).size.toLong()

    private fun truncateUtf8(value: String, maxBytes: Long): String {
        if (maxBytes <= 0L) return ""
        if (utf8Bytes(value) <= maxBytes) return value
        val builder = StringBuilder()
        var used = 0L
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val piece = String(value.toCharArray(index, index + chars))
            val size = utf8Bytes(piece)
            if (used + size > maxBytes) break
            builder.append(piece)
            used += size
            index += chars
        }
        return builder.toString()
    }
}
