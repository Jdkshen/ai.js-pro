package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** r2 executor injected by the Android wiring layer; owns process creation and termination. */
internal fun interface R2Runner {
    fun run(args: List<String>, env: Map<String, String>, timeoutSeconds: Int): R2RunnerResult
}

/** Minimal bounded result returned by an injected r2 runner. */
internal data class R2RunnerResult(
    val output: String = "",
    val exitCode: Int = 0,
    val timedOut: Boolean = false,
)

/**
 * UX-1: persistent r2-project analysis session without a long-lived pipe.
 *
 * First plan/query builds the project with "aaa;Ps <project>" in one r2 process;
 * later queries use one independent "Po <project>;<cmd>" process. The first argv
 * always carries -e bin.relocs.apply=<bool> (true by default) so raw listings can
 * resolve adrp/add relocations. This class never starts a process itself: execution
 * is delegated to the optional [runner] supplied by the wiring layer. Without a
 * runner, query returns a plan with executed=false rather than pretending to run.
 */
internal class ToolkitR2Session(
    private val storeDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 30L * 60 * 1000,
    private val maxSessions: Int = 4,
    private val maxCommandChars: Int = 4096,
    private val maxOutputBytes: Long = 8L * 1024 * 1024,
    private val runner: R2Runner? = null,
) {
    companion object {
        private const val MAX_INPUT_PATH = 4096
        private const val MAX_TIMEOUT_SECONDS = 300
        private const val MAX_ENV_ENTRIES = 64
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    }

    private class Session(
        val sessionId: String,
        val inputPath: String,
        val sha256: String,
        val sha16: String,
        val project: File,
        val relocsApply: Boolean,
        val extraEnv: Map<String, String>,
        val createdAt: Long,
        var lastAccessAt: Long,
        var initialized: Boolean = false,
    )

    private data class InputVerification(val verified: Boolean, val actualSha256: String?)

    private val root = storeDir.canonicalFile
    private val lock = ReentrantLock()
    private val sessions = LinkedHashMap<String, Session>()

    init {
        require(storeDir.exists() || storeDir.mkdirs()) { "cannot create r2 session store" }
        require(root.isDirectory) { "r2 session store is not a directory" }
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
        require(maxSessions > 0) { "maxSessions must be positive" }
        require(maxCommandChars > 0) { "maxCommandChars must be positive" }
        require(maxOutputBytes > 0L) { "maxOutputBytes must be positive" }
    }

    fun open(
        inputPath: String,
        sha256: String,
        relocsApply: Boolean = true,
        extraEnv: Map<String, String> = emptyMap(),
    ): JsonObject = lock.withLock {
        purgeExpiredLocked()
        val path = normalizeInputPath(inputPath)
        val sha = normalizeSha(sha256)
        val env = normalizeEnv(extraEnv)
        if (sessions.size >= maxSessions) {
            throw ToolkitException(
                "SESSION_LIMIT",
                "r2 analysis sessions reached limit " + maxSessions + "; close a session first",
                "maxSessions",
            )
        }
        val sessionId = ToolkitIo.fresh()
        val sha16 = sha.take(16)
        val sourceDir = File(root, sha16)
        require(sourceDir.exists() || sourceDir.mkdirs()) { "cannot create r2 project directory" }
        val project = File(sourceDir, "r2-" + sessionId + ".project")
        val now = clock()
        val session = Session(
            sessionId = sessionId,
            inputPath = path,
            sha256 = sha,
            sha16 = sha16,
            project = project,
            relocsApply = relocsApply,
            extraEnv = env,
            createdAt = now,
            lastAccessAt = now,
        )
        sessions[sessionId] = session
        return openJson(session)
    }

    fun plan(sessionId: String, command: String?): JsonObject = lock.withLock {
        val session = loadLiveLocked(sessionId)
        val requested = normalizeUserCommand(command, allowNull = true)
        val result = planJson(session, requested, markInitialized = true)
        touch(session)
        return result
    }

    fun query(sessionId: String, command: String, timeoutSeconds: Int = 60): JsonObject = lock.withLock {
        val session = loadLiveLocked(sessionId)
        val requested = normalizeUserCommand(command, allowNull = false)
            ?: throw ToolkitException("COMMAND_REJECTED", "command must not be empty", "command")
        if (timeoutSeconds !in 1..MAX_TIMEOUT_SECONDS) {
            throw ToolkitException(
                "INPUT_INVALID",
                "timeoutSeconds must be in 1.." + MAX_TIMEOUT_SECONDS,
                "timeoutSeconds",
            )
        }
        val verification = verifyInputHash(session)
        val first = !session.initialized
        val phase = if (first) "create+query" else "query"
        val commandText = if (first) {
            "aaa;Ps " + session.project.path + ";" + requested
        } else {
            "Po " + session.project.path + ";" + requested
        }
        val argv = r2Argv(session, commandText, includeRelocs = first)
        if (runner == null) {
            return dryQueryJson(session, phase, commandText, argv, timeoutSeconds, verification)
        }
        val result = executeBounded(session, argv, timeoutSeconds)
        if (result.timedOut) {
            throw ToolkitException(
                "TIMEOUT",
                "r2 query exceeded " + timeoutSeconds + "s (TIMEOUT); caller must terminate the process group",
                "timeoutSeconds",
            )
        }
        val outputBytes = result.output.toByteArray(Charsets.UTF_8).size.toLong()
        if (outputBytes > maxOutputBytes) {
            throw ToolkitException(
                "OUTPUT_LIMIT",
                "r2 output " + outputBytes + " bytes exceeds limit " + maxOutputBytes,
                "maxOutputBytes",
            )
        }
        if (result.exitCode != 0) {
            throw ToolkitException(
                "COMMAND_FAILED",
                "r2 exit=" + result.exitCode + ": " + result.output.takeLast(2000),
                "command",
            )
        }
        session.initialized = true
        touch(session)
        return executedQueryJson(session, phase, commandText, argv, timeoutSeconds, verification, result, outputBytes)
    }

    fun close(sessionId: String): JsonObject = lock.withLock {
        validateSessionId(sessionId)
        val session = sessions.remove(sessionId)
            ?: throw ToolkitException("SESSION_NOT_FOUND", "r2 session not found: " + sessionId, "sessionId")
        val existed = session.project.exists()
        val releasedBytes = if (existed) fileBytes(session.project) else 0L
        val deleted = deleteProject(session.project)
        return ToolkitIo.json(
            "sessionId" to sessionId,
            "closed" to true,
            "projectPath" to session.project.path,
            "projectExisted" to existed,
            "projectDeleted" to deleted,
            "releasedBytes" to releasedBytes,
            "remainingSessions" to sessions.size,
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
                "sha16" to session.sha16,
                "projectPath" to session.project.path,
                "project" to session.project.path,
                "input" to session.inputPath,
                "projectExists" to session.project.exists(),
                "relocsApply" to session.relocsApply,
                "createdAt" to session.createdAt,
                "lastAccessAt" to session.lastAccessAt,
                "expiresAt" to expiresAt(session),
                "initialized" to session.initialized,
                "envKeys" to envKeys(session.extraEnv),
            ))
        }
        return ToolkitIo.json(
            "sessions" to array,
            "items" to array,
            "count" to array.size(),
            "total" to array.size(),
            "maxSessions" to maxSessions,
            "ttlMillis" to ttlMillis,
            "maxCommandChars" to maxCommandChars,
            "maxOutputBytes" to maxOutputBytes,
            "runnerConfigured" to (runner != null),
        )
    }

    private fun planJson(session: Session, command: String?, markInitialized: Boolean): JsonObject {
        val first = !session.initialized
        val base = if (first) "aaa;Ps " + session.project.path else "Po " + session.project.path
        val commandText = if (command == null) base else base + ";" + command
        val argv = r2Argv(session, commandText, includeRelocs = first)
        if (markInitialized) session.initialized = true
        return ToolkitIo.json(
            "sessionId" to session.sessionId,
            "phase" to if (first) "create" else if (command == null) "load" else "query",
            "first" to first,
            "command" to commandText,
            "cmd" to commandText,
            "commandLine" to r2CommandLine(argv),
            "fullCommand" to r2CommandLine(argv),
            "commands" to JsonArray().apply { add(commandText) },
            "argv" to argv,
            "args" to argv,
            "r2Args" to argv,
            "executable" to "radare2",
            "inputPath" to session.inputPath,
            "sha256" to session.sha256,
            "sha16" to session.sha16,
            "projectPath" to session.project.path,
            "project" to session.project.path,
            "input" to session.inputPath,
            "relocsApply" to session.relocsApply,
            "relocsFlag" to ("bin.relocs.apply=" + if (session.relocsApply) "true" else "false"),
            "includesRelocsFlag" to first,
            "initialized" to session.initialized,
            "initializedAfter" to session.initialized,
            "projectExists" to session.project.exists(),
            "envKeys" to envKeys(session.extraEnv),
            "maxCommandChars" to maxCommandChars,
            "maxOutputBytes" to maxOutputBytes,
        )
    }

    private fun openJson(session: Session): JsonObject {
        val firstPlan = planJson(session, null, markInitialized = false)
        return ToolkitIo.json(
            "sessionId" to session.sessionId,
            "inputPath" to session.inputPath,
            "sha256" to session.sha256,
            "sourceSha256" to session.sha256,
            "sha16" to session.sha16,
            "projectPath" to session.project.path,
            "project" to session.project.path,
            "input" to session.inputPath,
            "projectExists" to session.project.exists(),
            "relocsApply" to session.relocsApply,
            "relocsFlag" to ("bin.relocs.apply=" + if (session.relocsApply) "true" else "false"),
            "extraEnvKeys" to envKeys(session.extraEnv),
            "createdAt" to session.createdAt,
            "lastAccessAt" to session.lastAccessAt,
            "expiresAt" to expiresAt(session),
            "ttlMillis" to ttlMillis,
            "maxSessions" to maxSessions,
            "maxCommandChars" to maxCommandChars,
            "maxOutputBytes" to maxOutputBytes,
            "firstPlan" to firstPlan,
            "firstCommand" to firstPlan.get("command").asString,
            "firstCommandLine" to firstPlan.get("commandLine").asString,
            "firstArgv" to firstPlan.getAsJsonArray("argv"),
            "argv" to firstPlan.getAsJsonArray("argv"),
        )
    }

    private fun r2CommandLine(argv: List<String>): String = argv.joinToString(" ")

    private fun r2Argv(session: Session, command: String, includeRelocs: Boolean): List<String> {
        val args = ArrayList<String>()
        args.add("-N")
        args.add("-2")
        args.add("-q")
        args.add("-e")
        args.add("scr.color=0")
        args.add("-e")
        args.add("scr.interactive=false")
        if (includeRelocs) {
            args.add("-e")
            args.add("bin.relocs.apply=" + if (session.relocsApply) "true" else "false")
        }
        args.add("-c")
        args.add(command + ";q")
        args.add(session.inputPath)
        return args
    }

    private fun verifyInputHash(session: Session): InputVerification {
        val file = File(session.inputPath)
        if (!file.isFile) return InputVerification(false, null)
        val actual = runCatching { ToolkitIo.sha(file) }.getOrElse {
            throw ToolkitException("INPUT_UNREADABLE", "cannot read input file: " + session.inputPath, "inputPath")
        }
        if (actual != session.sha256) {
            throw ToolkitException(
                "SESSION_STALE_INPUT",
                "input hash changed: session " + session.sha256.take(16) + ", current " + actual.take(16),
                "inputPath",
            )
        }
        return InputVerification(true, actual)
    }

    private fun executeBounded(session: Session, argv: List<String>, timeoutSeconds: Int): R2RunnerResult {
        val active = runner ?: throw ToolkitException("RUNNER_REQUIRED", "no r2 runner configured", "runner")
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "r2-session-query").apply { isDaemon = true }
        }
        val future = executor.submit<R2RunnerResult> {
            active.run(argv, session.extraEnv, timeoutSeconds)
        }
        return try {
            future.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw ToolkitException(
                "TIMEOUT",
                "r2 query exceeded " + timeoutSeconds + "s (TIMEOUT); caller must terminate the process group",
                "timeoutSeconds",
            )
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is ToolkitException) {
                if (cause.code == "COMPONENT_TIMEOUT" || cause.code == "TIMEOUT") {
                    throw ToolkitException(
                        "TIMEOUT",
                        cause.message ?: "r2 query timed out",
                        "timeoutSeconds",
                    )
                }
                throw cause
            }
            if (cause is TimeoutException) {
                throw ToolkitException(
                    "TIMEOUT",
                    "r2 query exceeded " + timeoutSeconds + "s (TIMEOUT); caller must terminate the process group",
                    "timeoutSeconds",
                )
            }
            throw ToolkitException(
                "COMMAND_FAILED",
                "r2 execution failed: " + (cause?.message ?: cause?.javaClass?.simpleName ?: "unknown"),
                "command",
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun dryQueryJson(
        session: Session,
        phase: String,
        command: String,
        argv: List<String>,
        timeoutSeconds: Int,
        verification: InputVerification,
    ): JsonObject = ToolkitIo.json(
        "sessionId" to session.sessionId,
        "executed" to false,
        "runnerConfigured" to false,
        "phase" to phase,
        "command" to command,
        "cmd" to command,
        "commandLine" to r2CommandLine(argv),
        "argv" to argv,
        "args" to argv,
        "r2Args" to argv,
        "executable" to "radare2",
        "inputPath" to session.inputPath,
        "projectPath" to session.project.path,
        "project" to session.project.path,
        "input" to session.inputPath,
        "sha256" to session.sha256,
        "sha16" to session.sha16,
        "inputHashVerified" to verification.verified,
        "inputSha256" to (verification.actualSha256 ?: session.sha256),
        "relocsApply" to session.relocsApply,
        "relocsFlag" to ("bin.relocs.apply=" + if (session.relocsApply) "true" else "false"),
        "timeoutSeconds" to timeoutSeconds,
        "outputLimitBytes" to maxOutputBytes,
        "maxOutputBytes" to maxOutputBytes,
        "projectExists" to session.project.exists(),
        "note" to "no R2Runner configured: returning executable plan without running r2 or fabricating output",
    )

    private fun executedQueryJson(
        session: Session,
        phase: String,
        command: String,
        argv: List<String>,
        timeoutSeconds: Int,
        verification: InputVerification,
        result: R2RunnerResult,
        outputBytes: Long,
    ): JsonObject = ToolkitIo.json(
        "sessionId" to session.sessionId,
        "executed" to true,
        "phase" to phase,
        "command" to command,
        "cmd" to command,
        "commandLine" to r2CommandLine(argv),
        "argv" to argv,
        "args" to argv,
        "r2Args" to argv,
        "executable" to "radare2",
        "exitCode" to result.exitCode,
        "output" to result.output,
        "outputBytes" to outputBytes,
        "outputLimitBytes" to maxOutputBytes,
        "maxOutputBytes" to maxOutputBytes,
        "timeoutSeconds" to timeoutSeconds,
        "inputPath" to session.inputPath,
        "projectPath" to session.project.path,
        "project" to session.project.path,
        "input" to session.inputPath,
        "sha256" to session.sha256,
        "sha16" to session.sha16,
        "inputHashVerified" to verification.verified,
        "inputSha256" to (verification.actualSha256 ?: session.sha256),
        "relocsApply" to session.relocsApply,
        "relocsFlag" to ("bin.relocs.apply=" + if (session.relocsApply) "true" else "false"),
        "initialized" to session.initialized,
        "projectExists" to session.project.exists(),
        "envKeys" to envKeys(session.extraEnv),
    )

    private fun loadLiveLocked(sessionId: String): Session {
        validateSessionId(sessionId)
        val session = sessions[sessionId]
            ?: throw ToolkitException("SESSION_NOT_FOUND", "r2 session not found: " + sessionId, "sessionId")
        if (clock() >= expiresAt(session)) {
            sessions.remove(sessionId)
            deleteProject(session.project)
            throw ToolkitException(
                "SESSION_EXPIRED",
                "r2 session exceeded TTL " + ttlMillis + "ms; project and session were removed",
                "sessionId",
            )
        }
        return session
    }

    private fun purgeExpiredLocked() {
        val now = clock()
        val expired = sessions.values.filter { now >= expiresAt(it) }
        for (session in expired) {
            sessions.remove(session.sessionId)
            deleteProject(session.project)
        }
    }

    private fun touch(session: Session) {
        session.lastAccessAt = clock()
    }

    private fun expiresAt(session: Session): Long = session.lastAccessAt + ttlMillis

    private fun fileBytes(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0L
        return file.listFiles()?.sumOf { fileBytes(it) } ?: 0L
    }

    private fun deleteProject(project: File): Boolean {
        val target = runCatching { project.canonicalFile }.getOrNull() ?: return !project.exists()
        val base = root.canonicalFile
        if (target == base || !target.path.startsWith(base.path + File.separator)) return false
        if (target.isDirectory) {
            val children = target.listFiles() ?: return false
            for (child in children) {
                if (!deleteProjectUnder(child, base)) return false
            }
        }
        return target.delete() || !target.exists()
    }

    private fun deleteProjectUnder(file: File, base: File): Boolean {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return !file.exists()
        if (target == base || !target.path.startsWith(base.path + File.separator)) return false
        if (target.isDirectory) {
            val children = target.listFiles() ?: return false
            for (child in children) {
                if (!deleteProjectUnder(child, base)) return false
            }
        }
        return target.delete() || !target.exists()
    }

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

    private fun validateSessionId(value: String) {
        val id = value.trim()
        if (!SESSION_ID.matches(id)) {
            throw ToolkitException("SESSION_NOT_FOUND", "r2 sessionId format invalid: " + value, "sessionId")
        }
    }

    private fun normalizeUserCommand(value: String?, allowNull: Boolean): String? {
        val command = value?.trim()?.takeIf { it.isNotEmpty() }
        if (command == null) {
            if (allowNull) return null
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

    private fun normalizeEnv(value: Map<String, String>): Map<String, String> {
        if (value.size > MAX_ENV_ENTRIES) {
            throw ToolkitException("INPUT_INVALID", "extraEnv has more than " + MAX_ENV_ENTRIES + " entries", "extraEnv")
        }
        val result = LinkedHashMap<String, String>()
        for ((key, raw) in value) {
            if (!ENV_KEY.matches(key)) {
                throw ToolkitException("INPUT_INVALID", "extraEnv key invalid: " + key, "extraEnv")
            }
            if (raw.any { it == '\u0000' || it == '\n' || it == '\r' }) {
                throw ToolkitException("INPUT_INVALID", "extraEnv value has control characters: " + key, "extraEnv")
            }
            result[key] = raw
        }
        return result
    }

    private fun envKeys(env: Map<String, String>): JsonArray = JsonArray().apply {
        env.keys.sorted().forEach { add(JsonPrimitive(it)) }
    }
}
