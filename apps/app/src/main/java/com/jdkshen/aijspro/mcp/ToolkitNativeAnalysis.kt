package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jdkshen.aijspro.Pref
import com.stardust.app.GlobalAppContext
import java.io.File
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * P1-1：有状态 native 分析会话。
 *
 * 本类只管理分析状态，不启动 radare2/任何子进程；[r2Commands] 只生成文本，
 * 由调用方决定是否在受控 r2 会话中执行。状态持久化到：
 * - storeDir/index.json（全局索引，原子写）
 * - storeDir/<sha256前16位>/analysis-<sessionId>.json（单个会话，原子写）
 *
 * 地址输入格式（所有返回 JSON 中的地址都是 {value,kind,abi} 对象）：
 * - 0x1234 / 1234：默认 virtual
 * - vaddr:0x1234 / v:0x1234：virtual
 * - file:0x1234 / off:0x1234 / file_offset:0x1234：file_offset
 * - runtime:0x1234 / runtime_address:0x1234 / r:0x1234：runtime
 *
 * 分析覆盖层只用于分析（analysisOnly=true），不得流入构建产物或回写源文件。
 * open 会先读取 sourcePath 的实际 SHA-256；与入参不一致直接抛 ANALYSIS_STALE_INPUT 且不建会话。
 * close 把 statePath 归档为 archive-<sessionId>.json；同 sha256 重开时恢复 functions/types/noreturns/overlays。
 * 同一 sourceSha256 可复用上下文；同一 sourcePath 输入哈希变化时旧会话标记 stale，
 * 任何旧地址/状态操作抛 ANALYSIS_STALE_INPUT。
 *
 * 路径策略：绝对路径默认只允许只读根 /system、/vendor、/apex、/data/local/tmp、/storage/emulated/0
 * 与工具操作根；其它绝对路径（/data/data、/data/user、/proc、应用私有目录等）拒绝，除非显式
 * allowOutsideOperationRoot=true。相对路径仍只解析到工具操作根。
 *
 * 主要错误码：ANALYSIS_PATH_FORBIDDEN、ANALYSIS_STALE_INPUT、ANALYSIS_SESSION_NOT_FOUND、ANALYSIS_TTL_EXPIRED、
 * ANALYSIS_SESSION_LIMIT、ANALYSIS_FUNCTION_LIMIT、ANALYSIS_OVERLAY_LIMIT、
 * ANALYSIS_ADDRESS_INVALID、ANALYSIS_INPUT_INVALID、ANALYSIS_ABI_MISMATCH。
 */
internal class ToolkitNativeAnalysis(
    private val storeDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSessions: Int = 8,
    private val maxFunctions: Int = 4096,
    private val maxOverlayBytes: Long = 16L * 1024 * 1024,
    private val ttlMillis: Long = 6L * 60 * 60 * 1000,
) {
    companion object {
        const val ANALYSIS_ONLY_WARNING =
            "分析会话状态与覆盖层仅用于分析（analysisOnly=true），不得流入构建产物或回写源文件"
        private const val MAX_SOURCE_PATH = 4096
        private const val MAX_FUNCTION_SIZE = 16 * 1024 * 1024
        private const val MAX_TYPE_CHARS = 256
        private const val MAX_NOTE_CHARS = 256
        private const val MAX_ADDRESS_CHARS = 64
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val SESSION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        private val ABI = Regex("[A-Za-z0-9_.-]{1,32}")
        private val FUNCTION_NAME = Regex("[A-Za-z_.][A-Za-z0-9_.:@]{0,127}")
        private val TYPE_NAME = Regex("""[A-Za-z0-9_*()\[\], .:-]{1,256}""")
        private val ADDRESS = Regex("(?i)(0x[0-9a-f]{1,16}|[0-9]{1,20})")
    }

    /** 只读允许根；绝对路径必须落在这些根或工具操作根内（canonical 后判断）。 */
    private val ALLOWED_READ_ONLY_ROOTS = listOf("/system", "/vendor", "/apex", "/data/local/tmp", "/storage/emulated/0")

    private data class Address(val value: String, val kind: String, val abi: String) {
        fun toJson(): JsonObject = ToolkitIo.json("value" to value, "kind" to kind, "abi" to abi)
    }

    private data class FunctionEntry(val address: Address, val size: Int?, val name: String?)
    private data class TypeEntry(val address: Address, val type: String)
    private data class NoreturnEntry(val address: Address, val noreturn: Boolean)
    private data class OverlayEntry(
        val address: Address,
        val hex: String,
        val bytes: Int,
        val note: String?,
        val createdAt: Long,
    )

    private class Session(
        val sessionId: String,
        var sourcePath: String,
        val sourceSha256: String,
        val sha16: String,
        val abi: String?,
        val createdAt: Long,
        var lastAccessAt: Long,
        var stale: Boolean = false,
        var staleReason: String? = null,
        val functions: LinkedHashMap<String, FunctionEntry> = LinkedHashMap(),
        val types: LinkedHashMap<String, TypeEntry> = LinkedHashMap(),
        val noreturns: LinkedHashMap<String, NoreturnEntry> = LinkedHashMap(),
        val overlays: MutableList<OverlayEntry> = ArrayList(),
        var overlayBytes: Long = 0L,
    )

    private class IndexEntry(
        val sessionId: String,
        val sourcePath: String,
        val sourceSha256: String,
        val sha16: String,
        val abi: String?,
        val createdAt: Long,
        var lastAccessAt: Long,
        var expiresAt: Long,
        var state: String,
    )

    private val root = storeDir.canonicalFile
    private val lock = ReentrantLock()
    private val indexFile = File(root, "index.json")
    private val index = LinkedHashMap<String, IndexEntry>()
    private val cache = HashMap<String, Session>()
    private var indexLoaded = false

    init {
        require(maxSessions > 0) { "maxSessions 必须大于 0" }
        require(maxFunctions > 0) { "maxFunctions 必须大于 0" }
        require(maxOverlayBytes > 0L) { "maxOverlayBytes 必须大于 0" }
        require(ttlMillis > 0L) { "ttlMillis 必须大于 0" }
        require(root.exists() || root.mkdirs()) { "无法创建分析 store 目录" }
        require(root.isDirectory) { "分析 store 路径不是目录" }
    }

    fun open(
        sourcePath: String,
        sourceSha256: String,
        abi: String?,
        requestedSession: String? = null,
        allowOutsideOperationRoot: Boolean = false,
    ): JsonObject = lock.withLock {
        // #2：先对真实文件算哈希，再决定是否建/复用会话；绝不能把入参当实测值回显。
        val input = resolveSourceFile(sourcePath, allowOutsideOperationRoot)
        val expectedSha = normalizeSha(sourceSha256)
        val actualSha = readFileSha(input)
        if (actualSha != expectedSha) throw staleHash(expectedSha, actualSha)
        val path = input.path
        val safeAbi = normalizeAbi(abi)
        val requested = requestedSession?.let { normalizeSessionId(it) }
        ensureIndex()
        invalidateChangedSource(path, actualSha, requested)

        val requestedEntry = requested?.let { index[it] }
        if (requested != null && requestedEntry != null) {
            val existing = loadSessionLocked(requested)
            if (existing.sourceSha256 != actualSha) throw staleInput(requested)
            requireAbi(existing, safeAbi)
            existing.sourcePath = path
            touch(existing)
            return openJson(existing, reused = true)
        }

        // #3：close 后索引已移除，但 statePath 归档保留；按哈希读回，而不是静默新建。
        if (requested != null && requestedEntry == null) {
            val archive = listOf(stateFile(actualSha.take(16), requested), archiveFile(actualSha.take(16), requested))
                .firstOrNull { it.isFile }
            if (archive != null) {
                try {
                    val restored = restoreSession(archive, actualSha, requested)
                    requireAbi(restored, safeAbi)
                    promoteArchive(archive, actualSha.take(16), restored.sessionId)
                    restored.sourcePath = path
                    touch(restored)
                    return openJson(restored, reused = true, restored = true, restoredFrom = archive.path)
                } catch (failure: Exception) {
                    return createNewSession(path, actualSha, safeAbi, requested, failure.message)
                }
            }
        }

        if (requested == null) {
            val now = clock()
            val reusable = index.values
                .filter { it.state == "open" && it.expiresAt > now && it.sourceSha256 == actualSha &&
                    (safeAbi == null || it.abi == safeAbi) }
                .sortedByDescending { it.lastAccessAt }
                .firstOrNull()
            if (reusable != null) {
                val existing = loadSessionLocked(reusable.sessionId)
                if (existing.sourceSha256 == actualSha) {
                    existing.sourcePath = path
                    touch(existing)
                    return openJson(existing, reused = true)
                }
            }
        }

        val archived = if (requested == null) findArchivedSession(actualSha, safeAbi)
            else ArchiveLookup(null, null, null)
        if (archived.session != null) {
            val restored = archived.session
            archived.file?.let { promoteArchive(it, actualSha.take(16), restored.sessionId) }
            restored.sourcePath = path
            touch(restored)
            return openJson(restored, reused = true, restored = true, restoredFrom = archived.file?.path)
        }

        return createNewSession(path, actualSha, safeAbi, requested, archived.error)
    }

    private fun createNewSession(
        path: String,
        sha: String,
        abi: String?,
        requestedSession: String?,
        restoreError: String?,
    ): JsonObject {
        purgeExpiredSilently()
        val now = clock()
        val liveCount = index.values.count { it.state == "open" && it.expiresAt > now }
        if (liveCount >= maxSessions) {
            throw ToolkitException(
                "ANALYSIS_SESSION_LIMIT",
                "分析会话已达上限 " + maxSessions + "；请先 close 旧会话",
                "maxSessions",
            )
        }
        val sessionId = requestedSession ?: ToolkitIo.fresh()
        val session = Session(
            sessionId = sessionId,
            sourcePath = path,
            sourceSha256 = sha,
            sha16 = sha.take(16),
            abi = abi,
            createdAt = now,
            lastAccessAt = now,
        )
        persistLocked(session)
        return openJson(session, reused = false, restored = false, restoreError = restoreError)
    }

    fun status(sessionId: String): JsonObject = lock.withLock {
        val session = loadSessionLocked(normalizeSessionId(sessionId))
        touch(session)
        return statusJson(session)
    }

    fun defineFunction(sessionId: String, address: String, size: Int?, name: String?): JsonObject =
        lock.withLock {
            val session = loadSessionLocked(normalizeSessionId(sessionId))
            val target = parseAddress(address, session.abi)
            val safeSize = size?.also {
                if (it !in 0..MAX_FUNCTION_SIZE) {
                    throw ToolkitException(
                        "ANALYSIS_INPUT_INVALID",
                        "size 必须在 0.." + MAX_FUNCTION_SIZE,
                        "size",
                    )
                }
            }
            val safeName = name?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeFunctionName(it) }
            val key = addressKey(target)
            if (!session.functions.containsKey(key) && session.functions.size >= maxFunctions) {
                throw ToolkitException(
                    "ANALYSIS_FUNCTION_LIMIT",
                    "函数数量已达上限 " + maxFunctions,
                    "maxFunctions",
                )
            }
            session.functions[key] = FunctionEntry(target, safeSize, safeName)
            touch(session)
            return ToolkitIo.json(
                "sessionId" to session.sessionId,
                "analysisOnly" to true,
                "address" to target.toJson(),
                "function" to ToolkitIo.json(
                    "address" to target.toJson(),
                    "size" to safeSize,
                    "name" to safeName,
                ),
                "functionCount" to session.functions.size,
                "maxFunctions" to maxFunctions,
            )
        }

    fun setType(sessionId: String, target: String, type: String): JsonObject = lock.withLock {
        val session = loadSessionLocked(normalizeSessionId(sessionId))
        val address = resolveTarget(session, target)
        val safeType = normalizeType(type)
        session.types[addressKey(address)] = TypeEntry(address, safeType)
        touch(session)
        return ToolkitIo.json(
            "sessionId" to session.sessionId,
            "analysisOnly" to true,
            "target" to address.toJson(),
            "address" to address.toJson(),
            "type" to safeType,
            "typeCount" to session.types.size,
        )
    }

    fun setNoreturn(sessionId: String, address: String, noreturn: Boolean): JsonObject =
        lock.withLock {
            val session = loadSessionLocked(normalizeSessionId(sessionId))
            val target = resolveTarget(session, address)
            session.noreturns[addressKey(target)] = NoreturnEntry(target, noreturn)
            touch(session)
            return ToolkitIo.json(
                "sessionId" to session.sessionId,
                "analysisOnly" to true,
                "address" to target.toJson(),
                "noreturn" to noreturn,
                "noreturnCount" to session.noreturns.values.count { it.noreturn },
            )
        }

    fun overlay(sessionId: String, address: String, hexBytes: String, note: String?): JsonObject =
        lock.withLock {
            val session = loadSessionLocked(normalizeSessionId(sessionId))
            val target = parseAddress(address, session.abi)
            val hex = hexBytes.trim().lowercase(Locale.ROOT)
            if (!(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in "0123456789abcdef" })) {
                throw ToolkitException("ANALYSIS_INPUT_INVALID", "hexBytes 必须是非空的偶数长度十六进制", "hexBytes")
            }
            if (hex.length > maxOverlayBytes * 2) {
                throw ToolkitException("ANALYSIS_OVERLAY_LIMIT", "覆盖层字节超过上限 " + maxOverlayBytes, "maxOverlayBytes")
            }
            val bytes = hex.length / 2
            if (bytes <= 0) throw ToolkitException("ANALYSIS_INPUT_INVALID", "hexBytes 不能为空", "hexBytes")
            if (session.overlayBytes + bytes > maxOverlayBytes) {
                throw ToolkitException(
                    "ANALYSIS_OVERLAY_LIMIT",
                    "覆盖层字节超过上限 " + maxOverlayBytes + "（当前 " + session.overlayBytes + "，新增 " + bytes + "）",
                    "maxOverlayBytes",
                )
            }
            val safeNote = note?.trim()?.takeIf { it.isNotEmpty() }?.also {
                if (it.length > MAX_NOTE_CHARS) {
                    throw ToolkitException("ANALYSIS_INPUT_INVALID", "note 不能超过 " + MAX_NOTE_CHARS + " 字符", "note")
                }
            }
            val entry = OverlayEntry(target, hex, bytes, safeNote, clock())
            session.overlays.add(entry)
            session.overlayBytes += bytes.toLong()
            touch(session)
            return ToolkitIo.json(
                "sessionId" to session.sessionId,
                "address" to target.toJson(),
                "bytes" to bytes,
                "overlayBytes" to session.overlayBytes,
                "maxOverlayBytes" to maxOverlayBytes,
                "overlayCount" to session.overlays.size,
                "analysisOnly" to true,
                "buildSafe" to false,
                "notForBuild" to true,
                "warning" to ANALYSIS_ONLY_WARNING,
            )
        }

    fun save(sessionId: String): JsonObject = lock.withLock {
        val session = loadSessionLocked(normalizeSessionId(sessionId))
        touch(session)
        return ToolkitIo.json(
            "sessionId" to session.sessionId,
            "saved" to true,
            "analysisOnly" to true,
            "path" to stateFile(session.sha16, session.sessionId).path,
            "lastAccessAt" to session.lastAccessAt,
            "expiresAt" to expiresAt(session),
            "statePath" to stateFile(session.sha16, session.sessionId).path,
            "indexPath" to indexFile.path,
        )
    }

    fun close(sessionId: String): JsonObject = lock.withLock {
        val id = normalizeSessionId(sessionId)
        ensureIndex()
        val entry = index[id] ?: throw sessionNotFound(id)
        cache.remove(id)
        index.remove(id)
        persistIndexLocked()
        val file = stateFile(entry.sha16, id)
        val archive = archiveFile(entry.sha16, id)
        val releasedBytes = if (file.isFile) file.length() else 0L
        var archived = false
        if (file.isFile) {
            archive.delete()
            archived = file.renameTo(archive)
            if (!archived) {
                // 跨文件系统/异常回退到复制；原 statePath 仍删除，releasedBytes 保持旧语义。
                file.inputStream().use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
                if (!file.delete()) throw ToolkitException("ANALYSIS_CLOSE_FAILED", "无法删除会话文件：" + file.path, "sessionId")
                archived = true
            }
        }
        cleanupSourceDir(entry.sha16)
        // #3：close 后仍保留归档供同 sha256 重开恢复；旧字段 releasedBytes 仍是原 statePath 字节数，
        // 新增 archived/archivePath/retainedStateBytes，不改变旧调用方的字段形状。
        return ToolkitIo.json(
            "sessionId" to id,
            "closed" to true,
            "archived" to archived,
            "analysisOnly" to true,
            "statePath" to file.path,
            "archivePath" to archive.path,
            "releasedBytes" to releasedBytes,
            "retainedStateBytes" to if (archived) archive.length() else 0L,
        )
    }

    fun list(): JsonObject = lock.withLock {
        ensureIndex()
        purgeExpiredSilently()
        val now = clock()
        val sessions = JsonArray()
        var functionTotal = 0
        var overlayBytesTotal = 0L
        var openCount = 0
        var staleCount = 0
        for (entry in index.values.sortedWith(compareBy({ it.createdAt }, { it.sessionId }))) {
            val row = ToolkitIo.json(
                "sessionId" to entry.sessionId,
                "sha256" to entry.sourceSha256,
                "sourceSha256" to entry.sourceSha256,
                "sha16" to entry.sha16,
                "sourcePath" to entry.sourcePath,
                "abi" to entry.abi,
                "createdAt" to entry.createdAt,
                "lastAccessAt" to entry.lastAccessAt,
                "expiresAt" to entry.expiresAt,
                "state" to entry.state,
            )
            if (entry.state == "stale") {
                staleCount++
            } else if (entry.state == "open" && entry.expiresAt > now) {
                openCount++
                runCatching {
                    val session = loadSessionLocked(entry.sessionId)
                    row.addProperty("functionCount", session.functions.size)
                    row.addProperty("typeCount", session.types.size)
                    row.addProperty("noreturnCount", session.noreturns.values.count { it.noreturn })
                    row.addProperty("overlayCount", session.overlays.size)
                    row.addProperty("overlayBytes", session.overlayBytes)
                    functionTotal += session.functions.size
                    overlayBytesTotal += session.overlayBytes
                }.onFailure { error ->
                    if (error is ToolkitException &&
                        (error.code == "ANALYSIS_SESSION_NOT_FOUND" || error.code == "ANALYSIS_STALE_INPUT" ||
                            error.code == "ANALYSIS_TTL_EXPIRED")) {
                        // 索引与实际状态不一致时，列表不因单个坏条目失败。
                    } else {
                        throw error
                    }
                }
            }
            sessions.add(row)
        }
        return ToolkitIo.json(
            "sessions" to sessions,
            "items" to sessions,
            "count" to sessions.size(),
            "total" to sessions.size(),
            "openCount" to openCount,
            "staleCount" to staleCount,
            "maxSessions" to maxSessions,
            "maxFunctions" to maxFunctions,
            "maxOverlayBytes" to maxOverlayBytes,
            "ttlMillis" to ttlMillis,
            "functionTotal" to functionTotal,
            "totalFunctions" to functionTotal,
            "overlayBytesTotal" to overlayBytesTotal,
            "totalOverlayBytes" to overlayBytesTotal,
            "analysisOnly" to true,
            "warning" to ANALYSIS_ONLY_WARNING,
        )
    }

    /**
     * 只生成可核对的 r2 命令文本，不执行、不启动进程。
     * af/afn 对应函数起点/名称，tl 对应类型；noreturn 以行尾注释形式携带，
     * 覆盖层用 wx（调用方必须确保只在分析会话中执行）。
     */
    fun r2Commands(sessionId: String): List<String> = lock.withLock {
        val session = loadSessionLocked(normalizeSessionId(sessionId))
        val commands = ArrayList<String>()
        val functions = session.functions.values.sortedBy { it.address.value }
        for (function in functions) {
            val address = function.address.value
            commands.add("af @ " + address)
            commands.add("afn " + (function.name ?: autoName(function.address)) + " @ " + address)
        }
        for (entry in session.types.values.sortedBy { it.address.value }) {
            commands.add("tl " + entry.type + " @ " + entry.address.value)
        }
        for (entry in session.noreturns.values.filter { it.noreturn }.sortedBy { it.address.value }) {
            commands.add("af @ " + entry.address.value + " # noreturn")
        }
        for (entry in session.overlays.sortedBy { it.address.value }) {
            commands.add("wx " + entry.hex + " @ " + entry.address.value)
        }
        commands
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    private fun ensureIndex() {
        if (indexLoaded) return
        indexLoaded = true
        if (!indexFile.isFile) return
        val parsed = runCatching { JsonParser().parse(indexFile.readText(Charsets.UTF_8)).asJsonObject }
            .getOrElse { throw ToolkitException("ANALYSIS_INDEX_INVALID", "index.json 不是有效 JSON：" + indexFile.path) }
        val sessions = parsed.getAsJsonObject("sessions") ?: return
        for ((key, value) in sessions.entrySet()) {
            val objectValue = value.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val id = objectValue.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString ?: key
            if (!SESSION_ID.matches(id)) continue
            val sha = objectValue.get("sha256")?.takeIf { it.isJsonPrimitive }?.asString
                ?: objectValue.get("sourceSha256")?.takeIf { it.isJsonPrimitive }?.asString
                ?: continue
            if (!SHA256.matches(sha)) continue
            val sha16 = objectValue.get("sha16")?.takeIf { it.isJsonPrimitive }?.asString ?: sha.take(16)
            if (!Regex("[0-9a-fA-F]{16}").matches(sha16)) continue
            val sourcePath = objectValue.get("sourcePath")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            val abi = objectValue.get("abi")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val createdAt = objectValue.get("createdAt")?.asLong ?: clock()
            val lastAccessAt = objectValue.get("lastAccessAt")?.asLong ?: createdAt
            val expiresAt = objectValue.get("expiresAt")?.asLong ?: (lastAccessAt + ttlMillis)
            val state = objectValue.get("state")?.takeIf { it.isJsonPrimitive }?.asString ?: "open"
            index[id] = IndexEntry(
                sessionId = id,
                sourcePath = sourcePath,
                sourceSha256 = sha.lowercase(Locale.ROOT),
                sha16 = sha16,
                abi = abi,
                createdAt = createdAt,
                lastAccessAt = lastAccessAt,
                expiresAt = expiresAt,
                state = state,
            )
        }
    }

    private fun persistLocked(session: Session) {
        val dir = File(root, session.sha16)
        require(dir.exists() || dir.mkdirs()) { "无法创建分析源码目录：" + dir.path }
        val file = stateFile(session.sha16, session.sessionId)
        writeJsonAtomic(file, sessionJson(session))
        index[session.sessionId] = IndexEntry(
            sessionId = session.sessionId,
            sourcePath = session.sourcePath,
            sourceSha256 = session.sourceSha256,
            sha16 = session.sha16,
            abi = session.abi,
            createdAt = session.createdAt,
            lastAccessAt = session.lastAccessAt,
            expiresAt = expiresAt(session),
            state = if (session.stale) "stale" else "open",
        )
        cache[session.sessionId] = session
        persistIndexLocked()
    }

    private fun persistIndexLocked() {
        val sessions = JsonObject()
        for (entry in index.values.sortedBy { it.sessionId }) {
            sessions.add(entry.sessionId, ToolkitIo.json(
                "sessionId" to entry.sessionId,
                "sha256" to entry.sourceSha256,
                "sourceSha256" to entry.sourceSha256,
                "sha16" to entry.sha16,
                "sourcePath" to entry.sourcePath,
                "abi" to entry.abi,
                "createdAt" to entry.createdAt,
                "lastAccessAt" to entry.lastAccessAt,
                "expiresAt" to entry.expiresAt,
                "state" to entry.state,
                "file" to stateFile(entry.sha16, entry.sessionId).path,
                "analysisOnly" to true,
            ))
        }
        writeJsonAtomic(indexFile, ToolkitIo.json(
            "schemaVersion" to 1,
            "updatedAt" to clock(),
            "analysisOnly" to true,
            "warning" to ANALYSIS_ONLY_WARNING,
            "sessions" to sessions,
        ))
    }

    private fun writeJsonAtomic(file: File, value: JsonObject) {
        file.parentFile?.let { require(it.exists() || it.mkdirs()) { "无法创建目录：" + it.path } }
        ToolkitIo.atomic(file) { temp -> temp.writeText(value.toString(), Charsets.UTF_8) }
    }

    private fun stateFile(sha16: String, sessionId: String): File =
        File(File(root, sha16), "analysis-" + sessionId + ".json")

    private fun archiveFile(sha16: String, sessionId: String): File =
        File(File(root, sha16), "archive-" + sessionId + ".json")

    private fun sessionIdFromStateFile(file: File): String? {
        val name = file.name
        return when {
            name.startsWith("analysis-") && name.endsWith(".json") ->
                name.removePrefix("analysis-").removeSuffix(".json")
            name.startsWith("archive-") && name.endsWith(".json") ->
                name.removePrefix("archive-").removeSuffix(".json")
            else -> null
        }
    }

    private fun promoteArchive(file: File, sha16: String, sessionId: String) {
        if (!file.name.startsWith("archive-")) return
        val target = stateFile(sha16, sessionId)
        if (target.exists() && !target.delete()) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "无法清理旧 statePath：" + target.path)
        }
        if (file.renameTo(target)) return
        file.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (!file.delete()) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "无法提升归档：" + file.path)
        }
    }

    private fun sessionJson(session: Session): JsonObject {
        val functions = JsonArray()
        for (entry in session.functions.values.sortedBy { it.address.value }) {
            functions.add(ToolkitIo.json(
                "address" to entry.address.toJson(),
                "size" to entry.size,
                "name" to entry.name,
            ))
        }
        val types = JsonArray()
        for (entry in session.types.values.sortedBy { it.address.value }) {
            types.add(ToolkitIo.json("address" to entry.address.toJson(), "type" to entry.type))
        }
        val noreturns = JsonArray()
        for (entry in session.noreturns.values.sortedBy { it.address.value }) {
            noreturns.add(ToolkitIo.json("address" to entry.address.toJson(), "noreturn" to entry.noreturn))
        }
        val overlays = JsonArray()
        for (entry in session.overlays) {
            overlays.add(ToolkitIo.json(
                "address" to entry.address.toJson(),
                "hex" to entry.hex,
                "bytes" to entry.bytes,
                "note" to entry.note,
                "createdAt" to entry.createdAt,
                "analysisOnly" to true,
            ))
        }
        return ToolkitIo.json(
            "schemaVersion" to 1,
            "sessionId" to session.sessionId,
            "sourcePath" to session.sourcePath,
            "sha256" to session.sourceSha256,
            "sourceSha256" to session.sourceSha256,
            "sha16" to session.sha16,
            "abi" to session.abi,
            "createdAt" to session.createdAt,
            "lastAccessAt" to session.lastAccessAt,
            "expiresAt" to expiresAt(session),
            "stale" to session.stale,
            "staleReason" to session.staleReason,
            "state" to if (session.stale) "stale" else "open",
            "analysisOnly" to true,
            "warning" to ANALYSIS_ONLY_WARNING,
            "functions" to functions,
            "types" to types,
            "noreturns" to noreturns,
            "overlays" to overlays,
            "overlayBytes" to session.overlayBytes,
        )
    }

    private fun parseSession(value: JsonObject): Session {
        val schema = value.get("schemaVersion")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0
        if (schema != 1) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "不支持的 state schemaVersion=" + schema + "（支持 1）")
        }
        val id = value.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw ToolkitException("ANALYSIS_STATE_INVALID", "会话状态缺少 sessionId")
        val path = value.get("sourcePath")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val sha = value.get("sourceSha256")?.takeIf { it.isJsonPrimitive }?.asString
            ?: value.get("sha256")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw ToolkitException("ANALYSIS_STATE_INVALID", "会话状态缺少 sourceSha256")
        if (!SHA256.matches(sha) || !SESSION_ID.matches(id)) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "会话状态字段无效：" + id)
        }
        val abi = value.get("abi")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val createdAt = value.get("createdAt")?.asLong ?: clock()
        val lastAccessAt = value.get("lastAccessAt")?.asLong ?: createdAt
        val stale = value.get("stale")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        val staleReason = value.get("staleReason")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val session = Session(
            sessionId = id,
            sourcePath = path,
            sourceSha256 = sha.lowercase(Locale.ROOT),
            sha16 = sha.take(16),
            abi = abi,
            createdAt = createdAt,
            lastAccessAt = lastAccessAt,
            stale = stale,
            staleReason = staleReason,
        )
        value.getAsJsonArray("functions")?.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val objectValue = item.asJsonObject
            val address = parseAddressJson(objectValue.getAsJsonObject("address"), abi)
            val size = objectValue.get("size")?.takeIf { !it.isJsonNull }?.asInt
            val name = objectValue.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            session.functions[addressKey(address)] = FunctionEntry(address, size, name)
        }
        value.getAsJsonArray("types")?.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val objectValue = item.asJsonObject
            val address = parseAddressJson(objectValue.getAsJsonObject("address"), abi)
            val type = objectValue.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            session.types[addressKey(address)] = TypeEntry(address, type)
        }
        value.getAsJsonArray("noreturns")?.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val objectValue = item.asJsonObject
            val address = parseAddressJson(objectValue.getAsJsonObject("address"), abi)
            val noreturn = objectValue.get("noreturn")?.asBoolean ?: false
            session.noreturns[addressKey(address)] = NoreturnEntry(address, noreturn)
        }
        value.getAsJsonArray("overlays")?.forEach { item ->
            if (!item.isJsonObject) return@forEach
            val objectValue = item.asJsonObject
            val address = parseAddressJson(objectValue.getAsJsonObject("address"), abi)
            val hex = objectValue.get("hex")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val bytes = objectValue.get("bytes")?.asInt ?: (hex.length / 2)
            val note = objectValue.get("note")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val created = objectValue.get("createdAt")?.asLong ?: clock()
            session.overlays.add(OverlayEntry(address, hex, bytes, note, created))
            session.overlayBytes += bytes.toLong()
        }
        return session
    }

    // ------------------------------------------------------------------
    // 会话查找 / 生命周期
    // ------------------------------------------------------------------

    private fun loadSessionLocked(id: String): Session {
        ensureIndex()
        val entry = index[id] ?: throw sessionNotFound(id)
        if (entry.state == "stale") throw staleInput(id)
        val now = clock()
        if (entry.expiresAt <= now) expireLocked(id, entry)
        val cached = cache[id]
        val session = cached ?: runCatching {
            val file = stateFile(entry.sha16, id)
            if (!file.isFile) {
                index.remove(id)
                persistIndexLocked()
                throw sessionNotFound(id)
            }
            parseSession(JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject)
        }.getOrElse { error ->
            if (error is ToolkitException) throw error
            throw ToolkitException("ANALYSIS_STATE_INVALID", "无法恢复会话 " + id + "：" + (error.message ?: error.javaClass.simpleName))
        }
        if (session.stale) throw staleInput(id)
        cache[id] = session
        return session
    }

    private fun touch(session: Session) {
        session.lastAccessAt = clock()
        persistLocked(session)
    }

    private fun expireLocked(id: String, entry: IndexEntry) {
        cache.remove(id)
        index.remove(id)
        stateFile(entry.sha16, id).delete()
        persistIndexLocked()
        cleanupSourceDir(entry.sha16)
        throw ToolkitException(
            "ANALYSIS_TTL_EXPIRED",
            "分析会话 " + id + " 已超过 TTL " + ttlMillis + "ms，已失效并清理",
            "sessionId",
        )
    }

    private fun purgeExpiredSilently() {
        ensureIndex()
        val now = clock()
        val expired = index.values.filter { it.state == "open" && it.expiresAt <= now }
        if (expired.isEmpty()) return
        for (entry in expired) {
            cache.remove(entry.sessionId)
            index.remove(entry.sessionId)
            stateFile(entry.sha16, entry.sessionId).delete()
            cleanupSourceDir(entry.sha16)
        }
        persistIndexLocked()
    }

    private fun invalidateChangedSource(path: String, newSha: String, requested: String?) {
        val changed = index.values.filter {
            it.sourcePath == path && it.sourceSha256 != newSha && it.state != "stale"
        }
        for (entry in changed) {
            markStaleLocked(entry.sessionId, "源文件哈希已变化；旧地址/旧分析状态不可沿用")
        }
        if (requested != null && index[requested]?.state == "stale") {
            throw staleInput(requested)
        }
    }

    private fun markStaleLocked(id: String, reason: String) {
        val entry = index[id] ?: return
        entry.state = "stale"
        entry.lastAccessAt = clock()
        cache[id]?.let {
            it.stale = true
            it.staleReason = reason
        }
        val file = stateFile(entry.sha16, id)
        if (file.isFile) {
            runCatching {
                val parsed = JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject
                val session = parseSession(parsed)
                session.stale = true
                session.staleReason = reason
                writeJsonAtomic(file, sessionJson(session))
            }
        }
        persistIndexLocked()
    }

    private fun cleanupSourceDir(sha16: String) {
        val dir = File(root, sha16)
        if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
    }

    // ------------------------------------------------------------------
    // 地址 / 输入
    // ------------------------------------------------------------------

    private fun parseAddress(raw: String, sessionAbi: String?): Address {
        val input = raw.trim()
        if (input.isEmpty() || input.length > MAX_ADDRESS_CHARS) {
            throw ToolkitException("ANALYSIS_ADDRESS_INVALID", "address 不能为空或过长", "address")
        }
        var kind = "virtual"
        var numberText = input
        val lower = input.lowercase(Locale.ROOT)
        for ((prefix, value) in listOf(
            "file_offset:" to "file_offset", "file:" to "file_offset", "off:" to "file_offset",
            "runtime_address:" to "runtime", "runtime:" to "runtime", "r:" to "runtime",
            "vaddr:" to "virtual", "virtual:" to "virtual", "v:" to "virtual",
        )) {
            if (lower.startsWith(prefix)) {
                kind = value
                numberText = input.substring(prefix.length)
                break
            }
        }
        if (!ADDRESS.matches(numberText)) {
            throw ToolkitException("ANALYSIS_ADDRESS_INVALID", "address 必须是无符号十进制或 0x 十六进制：" + raw, "address")
        }
        val number = runCatching {
            if (numberText.startsWith("0x", true)) numberText.substring(2).toULong(16)
            else numberText.toULong()
        }.getOrElse {
            throw ToolkitException("ANALYSIS_ADDRESS_INVALID", "address 超出范围：" + raw, "address")
        }
        return Address("0x" + number.toString(16), kind, sessionAbi ?: "unknown")
    }

    private fun parseAddressJson(value: JsonObject?, sessionAbi: String?): Address {
        if (value == null) throw ToolkitException("ANALYSIS_STATE_INVALID", "会话地址缺失")
        val raw = value.get("value")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw ToolkitException("ANALYSIS_STATE_INVALID", "会话地址缺少 value")
        val parsed = parseAddress(raw, sessionAbi)
        val kind = value.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
        val abi = value.get("abi")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return Address(parsed.value, kind ?: parsed.kind, abi ?: parsed.abi)
    }

    private fun resolveTarget(session: Session, raw: String): Address {
        val direct = runCatching { parseAddress(raw, session.abi) }.getOrNull()
        if (direct != null) return direct
        val byName = session.functions.values.firstOrNull { it.name == raw.trim() }
        if (byName != null) return byName.address
        throw ToolkitException("ANALYSIS_ADDRESS_INVALID", "target 不是有效地址或函数名：" + raw, "target")
    }

    private fun addressKey(address: Address): String = address.kind + "|" + address.value
    private fun expiresAt(session: Session): Long = session.lastAccessAt + ttlMillis
    private fun autoName(address: Address): String = "fcn_" + address.value.removePrefix("0x")

    private fun staleHash(expected: String, actual: String): ToolkitException = ToolkitException(
        "ANALYSIS_STALE_INPUT",
        "sourceSha256 与文件实际哈希不一致：expected=" + expected +
            "，actual=" + actual +
            "（前16位 expected=" + expected.take(16) + "…，actual=" + actual.take(16) + "…）。" +
            "路径策略：artifact_fingerprint/file_stat 只接受工具目录内的路径；如果 sourcePath 在工具目录外（如 /system/lib64/**），" +
            "它们会拒绝，请直接用上面的 actual 全量哈希作为 sourceSha256 重试 native_analysis_open。",
        "sha256",
    )

    private fun requireAbi(session: Session, requestedAbi: String?) {
        if (requestedAbi != null && session.abi != null && session.abi != requestedAbi) {
            throw ToolkitException(
                "ANALYSIS_ABI_MISMATCH",
                "会话 " + session.sessionId + " 的 ABI 为 " + session.abi + "，与请求的 " + requestedAbi + " 不一致",
                "abi",
            )
        }
    }

    private fun resolveSourceFile(value: String, allowOutsideOperationRoot: Boolean = false): File {
        val path = normalizeSourcePath(value)
        val original = File(path)
        val candidate = try {
            if (original.isAbsolute) original else ToolkitIo.resolve(defaultOperationRoot(), path)
        } catch (failure: Exception) {
            throw ToolkitException("ANALYSIS_INPUT_INVALID", "sourcePath 解析失败：" +
                (failure.message ?: failure.javaClass.simpleName), "path")
        }
        val canonical = try {
            candidate.canonicalFile
        } catch (failure: Exception) {
            throw ToolkitException("ANALYSIS_INPUT_INVALID", "sourcePath 规范化失败：" +
                (failure.message ?: failure.javaClass.simpleName), "path")
        }
        // 符号链接已在 canonical 中解析；绝对路径再按 canonical 结果做只读白名单判定。
        if (original.isAbsolute && !allowOutsideOperationRoot) {
            val roots = allowedReadOnlyRoots()
            if (roots.none { within(canonical, it) }) {
                throw ToolkitException(
                    "ANALYSIS_PATH_FORBIDDEN",
                    "sourcePath 在只读允许根之外：" + canonical.path +
                        "；允许根：" + allowedReadOnlyRootLabels().joinToString("，") +
                        "；如确需分析允许根之外的文件，请显式传 allowOutsideOperationRoot=true（会绕过白名单，只在确需时使用）",
                    "path",
                )
            }
        }
        if (!canonical.isFile || !canonical.canRead()) {
            throw ToolkitException("ANALYSIS_INPUT_INVALID", "sourcePath 不是可读文件：" + path, "path")
        }
        return canonical
    }

    /** 只读允许根 + 当前工具操作根；全部 canonical，避免用相对段/链接绕过。 */
    private fun allowedReadOnlyRoots(): List<File> {
        val paths = LinkedHashSet<String>()
        for (raw in ALLOWED_READ_ONLY_ROOTS) {
            runCatching { File(raw).canonicalFile.path }.getOrNull()?.let { paths.add(it) }
        }
        runCatching { ToolkitIo.resolve(defaultOperationRoot(), "", true).path }.getOrNull()?.let { paths.add(it) }
        return paths.map { File(it) }
    }

    /** 错误文案使用配置的原始根名（Android 上即 /system 等），操作根用 canonical 路径。 */
    private fun allowedReadOnlyRootLabels(): List<String> {
        val labels = LinkedHashSet(ALLOWED_READ_ONLY_ROOTS)
        runCatching { ToolkitIo.resolve(defaultOperationRoot(), "", true).path }.getOrNull()?.let { labels.add(it) }
        return labels.toList()
    }

    private fun within(path: File, root: File): Boolean {
        val target = path.path
        val base = root.path.trimEnd(File.separatorChar)
        return target == base || target.startsWith(base + File.separator)
    }

    /** 生产环境用与 McpTools 相同的 operation root；绝对路径不触发这里，保持 JVM 测试可注入临时文件。 */
    private fun defaultOperationRoot(): File {
        val scriptRoot = File(Pref.getScriptDirPath())
        val operation = runCatching { McpSettings.operationPath(GlobalAppContext.get()) }.getOrDefault("")
        return (if (operation.isBlank()) scriptRoot else File(scriptRoot, operation)).canonicalFile
    }

    private fun readFileSha(file: File): String = try {
        ToolkitIo.sha(file)
    } catch (failure: Exception) {
        throw ToolkitException("ANALYSIS_INPUT_INVALID", "无法读取 sourcePath 哈希：" +
            (failure.message ?: failure.javaClass.simpleName), "path")
    }

    private class ArchiveLookup(val session: Session?, val file: File?, val error: String?)

    private fun restoreSession(file: File, expectedSha: String, expectedId: String?): Session {
        val parsed = try {
            JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject
        } catch (failure: Exception) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "状态归档不是有效 JSON：" + file.name)
        }
        val session = parseSession(parsed)
        if (session.sourceSha256 != expectedSha) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "状态归档哈希与当前输入不一致：" + file.name)
        }
        if (session.stale) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "状态归档已 stale，不能恢复：" + file.name)
        }
        if (expectedId != null && session.sessionId != expectedId) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "状态归档 sessionId 不匹配：" + file.name)
        }
        val expectedFileId = sessionIdFromStateFile(file)
        if (expectedFileId == null || expectedFileId != session.sessionId) {
            throw ToolkitException("ANALYSIS_STATE_INVALID", "状态归档文件名与 sessionId 不匹配：" + file.name)
        }
        return session
    }

    private fun findArchivedSession(sha: String, safeAbi: String?): ArchiveLookup {
        val dir = File(root, sha.take(16))
        val files = dir.listFiles { file ->
            file.isFile && (file.name.startsWith("analysis-") || file.name.startsWith("archive-")) &&
                file.name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        var firstError: String? = null
        for (file in files) {
            try {
                val id = sessionIdFromStateFile(file)
                    ?: throw ToolkitException("ANALYSIS_STATE_INVALID", "状态归档文件名非法：" + file.name)
                if (!SESSION_ID.matches(id)) throw ToolkitException("ANALYSIS_STATE_INVALID",
                    "状态归档 sessionId 非法：" + file.name)
                val session = restoreSession(file, sha, id)
                if (safeAbi != null && session.abi != safeAbi) continue
                return ArchiveLookup(session, file, null)
            } catch (failure: Exception) {
                if (firstError == null) firstError = failure.message ?: failure.javaClass.simpleName
            }
        }
        return ArchiveLookup(null, null, firstError)
    }

    private fun normalizeSourcePath(value: String): String {
        val path = value.trim()
        if (path.isEmpty() || path.length > MAX_SOURCE_PATH || path.contains('\u0000') ||
            path.contains('\n') || path.contains('\r')) {
            throw ToolkitException("ANALYSIS_INPUT_INVALID", "sourcePath 无效", "sourcePath")
        }
        return path
    }

    private fun normalizeSha(value: String): String {
        if (!SHA256.matches(value)) {
            throw ToolkitException("ANALYSIS_INPUT_INVALID", "sourceSha256 必须是 64 位十六进制", "sourceSha256")
        }
        return value.lowercase(Locale.ROOT)
    }

    private fun normalizeAbi(value: String?): String? {
        val abi = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!ABI.matches(abi)) {
            throw ToolkitException("ANALYSIS_INPUT_INVALID", "abi 只允许字母数字下划线点和横线，长度 1..32", "abi")
        }
        return abi
    }

    private fun normalizeSessionId(value: String): String {
        val id = value.trim()
        if (!SESSION_ID.matches(id)) {
            throw ToolkitException("ANALYSIS_INVALID_SESSION_ID", "sessionId 格式无效", "sessionId")
        }
        return id
    }

    private fun normalizeFunctionName(value: String): String {
        val name = value.trim()
        if (!FUNCTION_NAME.matches(name)) {
            throw ToolkitException("ANALYSIS_INPUT_INVALID", "函数名格式无效", "name")
        }
        return name
    }

    private fun normalizeType(value: String): String {
        val type = value.trim()
        if (type.isEmpty() || type.length > MAX_TYPE_CHARS || !TYPE_NAME.matches(type) ||
            type.contains('\n') || type.contains('\r') || type.contains(';')) {
            throw ToolkitException("ANALYSIS_INPUT_INVALID", "type 格式无效", "type")
        }
        return type
    }

    private fun staleInput(id: String): ToolkitException = ToolkitException(
        "ANALYSIS_STALE_INPUT",
        "源文件哈希已变化或会话已 stale；旧地址/旧分析状态不可沿用：" + id,
        "sessionId",
    )

    private fun sessionNotFound(id: String): ToolkitException = ToolkitException(
        "ANALYSIS_SESSION_NOT_FOUND",
        "分析会话不存在：" + id,
        "sessionId",
    )

    // ------------------------------------------------------------------
    // JSON 输出
    // ------------------------------------------------------------------

    private fun openJson(
        session: Session,
        reused: Boolean,
        restored: Boolean = false,
        restoredFrom: String? = null,
        restoreError: String? = null,
    ): JsonObject = ToolkitIo.json(
        "sessionId" to session.sessionId,
        "reused" to reused,
        "restored" to restored,
        "restoredFrom" to restoredFrom,
        "restoreError" to restoreError,
        "status" to "open",
        "state" to "open",
        "sourcePath" to session.sourcePath,
        "sha256" to session.sourceSha256,
        "sourceSha256" to session.sourceSha256,
        "sha16" to session.sha16,
        "abi" to session.abi,
        "createdAt" to session.createdAt,
        "lastAccessAt" to session.lastAccessAt,
        "expiresAt" to expiresAt(session),
        "ttlMillis" to ttlMillis,
        "maxSessions" to maxSessions,
        "maxFunctions" to maxFunctions,
        "maxOverlayBytes" to maxOverlayBytes,
        "functionCount" to session.functions.size,
        "typeCount" to session.types.size,
        "noreturnCount" to session.noreturns.values.count { it.noreturn },
        "overlayCount" to session.overlays.size,
        "overlayBytes" to session.overlayBytes,
        "analysisOnly" to true,
        "buildSafe" to false,
        "buildOutputAllowed" to false,
        "warning" to ANALYSIS_ONLY_WARNING,
        "statePath" to stateFile(session.sha16, session.sessionId).path,
        "indexPath" to indexFile.path,
    )

    private fun statusJson(session: Session): JsonObject = ToolkitIo.json(
        "sessionId" to session.sessionId,
        "status" to if (session.stale) "stale" else "open",
        "state" to if (session.stale) "stale" else "open",
        "sourcePath" to session.sourcePath,
        "sha256" to session.sourceSha256,
        "sourceSha256" to session.sourceSha256,
        "sha16" to session.sha16,
        "abi" to session.abi,
        "createdAt" to session.createdAt,
        "lastAccessAt" to session.lastAccessAt,
        "expiresAt" to expiresAt(session),
        "ttlMillis" to ttlMillis,
        "stale" to session.stale,
        "staleReason" to session.staleReason,
        "analysisOnly" to true,
        "buildSafe" to false,
        "buildOutputAllowed" to false,
        "warning" to ANALYSIS_ONLY_WARNING,
        "functionCount" to session.functions.size,
        "typeCount" to session.types.size,
        "noreturnCount" to session.noreturns.values.count { it.noreturn },
        "overlayCount" to session.overlays.size,
        "overlayBytes" to session.overlayBytes,
        "functions" to functionsJson(session),
        "types" to typesJson(session),
        "noreturns" to noreturnsJson(session),
        "overlays" to overlaysJson(session),
        "limits" to ToolkitIo.json(
            "maxSessions" to maxSessions,
            "maxFunctions" to maxFunctions,
            "maxOverlayBytes" to maxOverlayBytes,
            "ttlMillis" to ttlMillis,
        ),
        "statePath" to stateFile(session.sha16, session.sessionId).path,
        "indexPath" to indexFile.path,
    )

    private fun functionsJson(session: Session): JsonArray = JsonArray().apply {
        session.functions.values.sortedBy { it.address.value }.forEach {
            add(ToolkitIo.json("address" to it.address.toJson(), "size" to it.size, "name" to it.name))
        }
    }

    private fun typesJson(session: Session): JsonArray = JsonArray().apply {
        session.types.values.sortedBy { it.address.value }.forEach {
            add(ToolkitIo.json("address" to it.address.toJson(), "type" to it.type))
        }
    }

    private fun noreturnsJson(session: Session): JsonArray = JsonArray().apply {
        session.noreturns.values.sortedBy { it.address.value }.forEach {
            add(ToolkitIo.json("address" to it.address.toJson(), "noreturn" to it.noreturn))
        }
    }

    private fun overlaysJson(session: Session): JsonArray = JsonArray().apply {
        session.overlays.forEach {
            add(ToolkitIo.json(
                "address" to it.address.toJson(),
                "hex" to it.hex,
                "bytes" to it.bytes,
                "note" to it.note,
                "createdAt" to it.createdAt,
                "analysisOnly" to true,
            ))
        }
    }
}
