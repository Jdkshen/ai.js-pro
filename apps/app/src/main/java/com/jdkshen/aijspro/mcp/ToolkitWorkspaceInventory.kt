package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.File

/**
 * task-250：APK 工作区清单、配额解释和受控 GC。
 *
 * 只读扫描 app-private 的 APK 工具 store，不接触 Android UI、Binder 或网络。
 * 配额只统计 store 下存在有效 workspace.json 的工作区目录；保存和临时都计入 16 个配额。
 * jobs/ 目录与 orphanDirs 不占工作区配额。实际字节是本目录下所有普通文件之和（含
 * base.apk、sessions、检查点）；declaredBytes 来自旧 workspace.json 的 size 字段，只用于
 * 解释历史快照，不保证等于实际占用。
 *
 * owner/clientId/sessionId 仅从现有 workspace.json 读取；旧工作区没有这些字段时返回 JSON
 * null，不伪造客户端身份。lastAccessAt 优先使用显式字段；旧记录回退到文件系统 mtime 并
 * 标记 lastAccessSource=filesystem_mtime。
 */
internal class ToolkitWorkspaceInventory(private val store: File) {
    companion object {
        const val QUOTA = 16
        const val DEFAULT_EXPIRED_AFTER_MINUTES = 24 * 60
        const val DEFAULT_MIN_AGE_MINUTES = 60
        private const val MAX_SCAN = 512
        private const val JOBS_DIR = "jobs"
    }

    /** 进程内活动引用：currentWorkspace 和分页 pages 的 workspace 计数。 */
    data class ActiveReferences(
        val currentWorkspaceId: String? = null,
        val pageReferences: Map<String, Int> = emptyMap(),
        val callerOwner: String? = null,
        val callerClientId: String? = null,
        val callerSessionId: String? = null,
    ) {
        fun refsFor(id: String): Int =
            (pageReferences[id] ?: 0) + if (id == currentWorkspaceId) 1 else 0
    }

    private data class Entry(
        val id: String,
        val directory: File,
        val info: JsonObject,
        val bytes: Long,
        val declaredBytes: Long,
        val createdAt: Long?,
        val lastAccessAt: Long?,
        val lastAccessSource: String,
        val temporary: Boolean,
        val state: String,
        val sessionCount: Int,
        val baseApkPresent: Boolean,
        val owner: String?,
        val clientId: String?,
        val sessionId: String?,
    )

    private data class Scan(
        val entries: MutableList<Entry> = mutableListOf(),
        val orphanDirs: MutableList<JsonObject> = mutableListOf(),
        val danglingEntries: MutableList<JsonObject> = mutableListOf(),
        var activeJobs: Int = 0,
        val warnings: MutableList<String> = mutableListOf(),
    )

    /** 列出全部工作区并给出与配额一致的统计。 */
    fun inventory(args: JsonObject, active: ActiveReferences = ActiveReferences()): JsonObject {
        val scan = scan()
        val now = System.currentTimeMillis()
        val expiredAfter = args.tInt("expiredAfterMinutes", DEFAULT_EXPIRED_AFTER_MINUTES, 1, 525600)
        val limit = args.tInt("limit", 256, 1, MAX_SCAN)
        val items = scan.entries.map { toItem(it, active, now, expiredAfter) }
            .sortedWith(compareByDescending<JsonObject> {
                it.get("createdAt")?.takeIf { value -> !value.isJsonNull }?.asLong ?: 0L
            }.thenBy { it.tString("workspaceId") })
        val byState = JsonObject()
        listOf("saved", "temporary", "expired", "dangling", "corrupt").forEach { state ->
            byState.addProperty(state, items.count { it.tString("state") == state })
        }
        val totalBytes = items.sumOf { it.get("bytes")?.asLong ?: 0L }
        val totalDeclared = items.sumOf { it.get("declaredBytes")?.asLong ?: 0L }
        val warnings = JsonArray()
        scan.warnings.forEach { warnings.add(JsonPrimitive(it)) }
        if (scan.activeJobs > 0) warnings.add(JsonPrimitive(
            "存在 activeJobs=" + scan.activeJobs +
                "；旧任务记录不含 workspaceId，GC 会保守跳过所有临时工作区"))
        return ToolkitIo.json(
            "schemaVersion" to 1,
            "generatedAt" to now,
            "quota" to QUOTA,
            "quotaScope" to
                "只统计 store 下含有效 workspace.json 的保存/临时 APK 工作区；jobs/ 与 orphanDirs 不占工作区配额",
            "totalCount" to items.size,
            "totalBytes" to totalBytes,
            "totalDeclaredBytes" to totalDeclared,
            "quotaUsedCount" to items.size,
            "quotaUsedBytes" to totalBytes,
            "quotaRemaining" to (QUOTA - items.size).coerceAtLeast(0),
            "byState" to byState,
            "activeWorkspaceCount" to items.count { (it.get("activeRefCount")?.asInt ?: 0) > 0 },
            "activeReferenceCount" to items.sumOf { it.get("activeRefCount")?.asInt ?: 0 },
            "pendingCleanupCount" to items.count { it.get("pendingCleanup")?.asBoolean == true },
            "orphanCount" to scan.orphanDirs.size,
            "orphanBytes" to scan.orphanDirs.sumOf { it.get("bytes")?.asLong ?: 0L },
            "danglingCount" to scan.danglingEntries.size,
            "danglingBytes" to scan.danglingEntries.sumOf { it.get("bytes")?.asLong ?: 0L },
            "activeJobs" to scan.activeJobs,
            "items" to items.take(limit),
            "itemsReturned" to minOf(limit, items.size),
            "itemsTruncated" to (items.size > limit),
            "orphanDirs" to scan.orphanDirs,
            "danglingEntries" to scan.danglingEntries,
            "warnings" to warnings,
            "notes" to JsonArray().apply {
                add(JsonPrimitive("owner/clientId/sessionId 只从现有 workspace.json 读取；旧工作区缺失时返回 null"))
                add(JsonPrimitive("lastAccessAt 旧记录回退 filesystem_mtime，不是真实读取时间"))
                add(JsonPrimitive("orphanDirs/danglingEntries 只报告，不自动删除"))
                add(JsonPrimitive("配额报满时应先查本清单，再用 apk_workspace_gc dryRun=true 预览"))
            }
        )
    }

    /**
     * 受控 GC。默认 dryRun=true 只出计划；dryRun=false 才删除。
     * 只清理满足全部条件的过期临时工作区：保存/current/page/编辑会话/protectedIds/
     * 未知或其它 owner/client/未知年龄/活动任务/dangling/corrupt 全部跳过。
     * orphanDirs 与 danglingEntries 永不删除。
     */
    fun gc(args: JsonObject, active: ActiveReferences = ActiveReferences()): JsonObject {
        val dryRun = args.tBool("dryRun", true)
        val includeTemporary = args.tBool("includeTemporary", false)
        val includeUnknownOwner = args.tBool("includeUnknownOwner", false)
        val includeUnknownAge = args.tBool("allowUnknownAge", false)
        val expiredAfter = args.tInt("expiredAfterMinutes", DEFAULT_EXPIRED_AFTER_MINUTES, 1, 525600)
        val minAge = args.tInt("minAgeMinutes", DEFAULT_MIN_AGE_MINUTES, 0, 525600)
        val protectedIds = stringSet(args, "protectedIds")
        val callerOwner = optionalString(args, "owner")
        val callerClientId = optionalString(args, "clientId")
        val inv = inventory(ToolkitIo.json("expiredAfterMinutes" to expiredAfter,
            "limit" to MAX_SCAN), active)
        val now = System.currentTimeMillis()
        val planned = JsonArray()
        val skipped = JsonArray()
        val wouldDelete = ArrayList<String>()
        var wouldReclaim = 0L
        val activeJobs = inv.get("activeJobs")?.asInt ?: 0
        for (raw in inv.getAsJsonArray("items")) {
            val item = raw.asJsonObject
            val id = item.tString("workspaceId")
            val state = item.tString("state")
            val itemOwner = optionalString(item, "owner")
            val itemClient = optionalString(item, "clientId")
            val reasons = ArrayList<String>()
            if (id in protectedIds) reasons.add("protected_ids")
            if (item.get("current")?.asBoolean == true) reasons.add("current_workspace")
            if ((item.get("activeRefCount")?.asInt ?: 0) > 0) reasons.add("active_reference")
            if ((item.get("sessionCount")?.asInt ?: 0) > 0) reasons.add("edit_session")
            if (state == "saved") reasons.add("saved_workspace")
            if (state == "dangling" || state == "corrupt") reasons.add(state)
            if (state != "expired" && !(includeTemporary && state == "temporary")) reasons.add("not_expired")
            if (activeJobs > 0) reasons.add("active_jobs_workspace_unknown")
            if (callerOwner == null) {
                if (itemOwner != null) reasons.add("other_owner")
                else if (!includeUnknownOwner) reasons.add("unknown_owner")
            } else if (itemOwner != callerOwner) {
                if (itemOwner == null) {
                    if (!includeUnknownOwner) reasons.add("unknown_owner")
                } else reasons.add("other_owner")
            }
            if (callerClientId == null) {
                if (itemClient != null) reasons.add("other_client")
                else if (!includeUnknownOwner) reasons.add("unknown_client")
            } else if (itemClient != callerClientId) {
                if (itemClient == null) {
                    if (!includeUnknownOwner) reasons.add("unknown_client")
                } else reasons.add("other_client")
            }
            val createdAt = item.get("createdAt")?.takeIf { !it.isJsonNull }?.asLong
            if (createdAt == null && !includeUnknownAge) reasons.add("unknown_age")
            else if (createdAt != null && now - createdAt < minAge * 60_000L) reasons.add("too_young")
            val unique = reasons.distinct()
            val canDelete = unique.isEmpty()
            val row = ToolkitIo.json("workspaceId" to id,
                "bytes" to (item.get("bytes")?.asLong ?: 0L),
                "state" to state, "action" to if (canDelete) "delete" else "skip",
                "protectedReasons" to unique, "owner" to itemOwner, "clientId" to itemClient)
            if (canDelete) {
                planned.add(row); wouldDelete.add(id)
                wouldReclaim += item.get("bytes")?.asLong ?: 0L
            } else skipped.add(row)
        }
        val deleted = ArrayList<String>()
        var reclaimed = 0L
        val failures = JsonArray()
        if (!dryRun) {
            val base = store.canonicalFile
            for (id in wouldDelete) {
                val dir = File(store, id)
                val target = runCatching { dir.canonicalFile }.getOrNull()
                if (target == null || target.parentFile != base || target.name != id || target == base) {
                    failures.add(ToolkitIo.json("workspaceId" to id, "error" to "路径校验失败，拒绝删除"))
                    continue
                }
                val size = runCatching { directoryBytes(target) }.getOrDefault(0L)
                val removal = runCatching { deleteDirectory(target) }
                if (removal.isSuccess) {
                    deleted.add(id); reclaimed += size
                } else {
                    failures.add(ToolkitIo.json("workspaceId" to id,
                        "error" to (removal.exceptionOrNull()?.message ?: "删除失败")))
                }
            }
        }
        return ToolkitIo.json(
            "dryRun" to dryRun,
            "applied" to (!dryRun && deleted.isNotEmpty()),
            "generatedAt" to now,
            "quota" to QUOTA,
            "totalCountBefore" to inv.get("totalCount")?.asInt,
            "totalBytesBefore" to inv.get("totalBytes")?.asLong,
            "expiredAfterMinutes" to expiredAfter,
            "minAgeMinutes" to minAge,
            "protectedIds" to protectedIds.toList(),
            "callerOwner" to callerOwner,
            "callerClientId" to callerClientId,
            "wouldDeleteCount" to wouldDelete.size,
            "wouldDeleteIds" to wouldDelete,
            "wouldReclaimBytes" to wouldReclaim,
            "planned" to planned,
            "skipped" to skipped,
            "deletedCount" to deleted.size,
            "deletedIds" to deleted,
            "reclaimedBytes" to reclaimed,
            "failures" to failures,
            "orphanDirs" to inv.get("orphanDirs"),
            "danglingEntries" to inv.get("danglingEntries"),
            "warnings" to inv.get("warnings"),
            "note" to "只删除 dryRun=false 且 canDelete=true 的过期临时工作区；orphan/dangling 永不自动删除"
        )
    }

    private fun scan(): Scan {
        val result = Scan()
        val dirs = store.listFiles()?.filter {
            it.isDirectory && it.name != JOBS_DIR && !it.name.startsWith(".")
        } ?: emptyList()
        for (dir in dirs.take(MAX_SCAN)) {
            val workspaceJson = File(dir, "workspace.json")
            if (!workspaceJson.isFile) {
                result.orphanDirs.add(ToolkitIo.json("directory" to dir.name,
                    "bytes" to directoryBytes(dir), "reason" to "缺少 workspace.json",
                    "modifiedAt" to dir.lastModified()))
                continue
            }
            val parsed = runCatching {
                JsonParser().parse(workspaceJson.readText(Charsets.UTF_8)).asJsonObject
            }.getOrNull()
            if (parsed == null) {
                result.danglingEntries.add(ToolkitIo.json("workspaceId" to dir.name,
                    "directory" to dir.name, "bytes" to directoryBytes(dir),
                    "reason" to "workspace.json 不是有效 JSON"))
                continue
            }
            val bytes = directoryBytes(dir)
            val basePresent = File(dir, "base.apk").isFile
            val idInJson = optionalString(parsed, "workspaceId")
            if (!basePresent) result.danglingEntries.add(ToolkitIo.json(
                "workspaceId" to (idInJson ?: dir.name), "directory" to dir.name,
                "bytes" to bytes, "reason" to "workspace.json 存在但 base.apk 缺失"))
            if (idInJson != null && idInJson != dir.name) result.warnings.add(
                "目录 " + dir.name + " 的 workspaceId " + idInJson + " 与目录名不一致")
            val temporary = boolOrNull(parsed, "temporary") ?: true
            result.entries.add(Entry(
                id = dir.name,
                directory = dir,
                info = parsed,
                bytes = bytes,
                declaredBytes = longOrNull(parsed, "size") ?: 0L,
                createdAt = longOrNull(parsed, "createdAt") ?: dir.lastModified(),
                lastAccessAt = longOrNull(parsed, "lastAccessAt") ?: maxModified(dir),
                lastAccessSource = if (longOrNull(parsed, "lastAccessAt") != null)
                    "workspace_json" else "filesystem_mtime",
                temporary = temporary,
                state = if (!basePresent) "dangling" else if (!temporary) "saved" else "temporary",
                sessionCount = countSessions(dir),
                baseApkPresent = basePresent,
                owner = optionalString(parsed, "owner"),
                clientId = optionalString(parsed, "clientId"),
                sessionId = optionalString(parsed, "sessionId"),
            ))
        }
        if (dirs.size > MAX_SCAN) result.warnings.add(
            "工作区目录超过 " + MAX_SCAN + " 个，只扫描前 " + MAX_SCAN + " 个；配额统计可能不完整")
        result.activeJobs = scanActiveJobs()
        return result
    }

    private fun toItem(entry: Entry, active: ActiveReferences, now: Long,
                       expiredAfterMinutes: Int): JsonObject {
        val activeRefs = active.refsFor(entry.id)
        val current = entry.id == active.currentWorkspaceId
        val createdAt = entry.createdAt
        val expired = entry.temporary && entry.state != "dangling" &&
            createdAt != null && now - createdAt >= expiredAfterMinutes * 60_000L
        val state = when {
            entry.state == "dangling" -> "dangling"
            entry.state == "saved" -> "saved"
            expired -> "expired"
            else -> entry.state
        }
        val reasons = ArrayList<String>()
        if (state == "saved") reasons.add("saved_workspace")
        if (current) reasons.add("current_workspace")
        if (activeRefs > 0) reasons.add("active_reference")
        if (entry.sessionCount > 0) reasons.add("edit_session")
        if (state == "dangling" || !entry.baseApkPresent) reasons.add("dangling")
        val pending = state == "expired" && reasons.isEmpty()
        return ToolkitIo.json(
            "workspaceId" to entry.id,
            "state" to state,
            "status" to state,
            "temporary" to entry.temporary,
            "bytes" to entry.bytes,
            "declaredBytes" to entry.declaredBytes,
            "createdAt" to entry.createdAt,
            "lastAccessAt" to entry.lastAccessAt,
            "lastAccessSource" to entry.lastAccessSource,
            "owner" to entry.owner,
            "clientId" to entry.clientId,
            "sessionId" to entry.sessionId,
            "activeRefCount" to activeRefs,
            "current" to current,
            "sessionCount" to entry.sessionCount,
            "baseApkPresent" to entry.baseApkPresent,
            "pendingCleanup" to pending,
            "protectedReasons" to reasons,
            "sourcePath" to optionalString(entry.info, "sourcePath"),
            "sha256" to optionalString(entry.info, "sha256"),
            "artifactType" to optionalString(entry.info, "artifactType"),
        )
    }

    private fun scanActiveJobs(): Int {
        val jobs = File(store, JOBS_DIR)
        if (!jobs.isDirectory) return 0
        return (jobs.listFiles() ?: emptyArray()).count { file ->
            if (!file.isFile || !file.name.endsWith(".json") ||
                file.name.endsWith(".result.json")) return@count false
            val state = runCatching {
                JsonParser().parse(file.readText(Charsets.UTF_8)).asJsonObject
                    .get("state")?.asString
            }.getOrNull()
            state in setOf("QUEUED", "RUNNING", "CANCELLING")
        }
    }

    private fun directoryBytes(dir: File): Long {
        var total = 0L
        runCatching {
            dir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        }
        return total
    }

    private fun maxModified(dir: File): Long {
        var max = dir.lastModified()
        runCatching {
            dir.walkTopDown().forEach { if (it.lastModified() > max) max = it.lastModified() }
        }
        return max
    }

    private fun countSessions(dir: File): Int {
        val sessions = File(dir, "sessions")
        return sessions.listFiles()?.count { File(it, "session.json").isFile } ?: 0
    }

    private fun deleteDirectory(dir: File) {
        if (java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            throw IllegalStateException("拒绝删除符号链接工作区")
        }
        val children = dir.listFiles() ?: throw IllegalStateException("无法读取目录")
        for (child in children) {
            if (child.isDirectory) deleteDirectory(child)
            else if (!child.delete()) throw IllegalStateException("无法删除 " + child.name)
        }
        if (!dir.delete()) throw IllegalStateException("无法删除工作区目录")
    }

    private fun stringSet(args: JsonObject, key: String): Set<String> {
        val value = args.get(key) ?: return emptySet()
        require(value.isJsonArray) { key + " 必须为字符串数组" }
        val result = LinkedHashSet<String>()
        value.asJsonArray.forEach { item ->
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                key + " 必须为字符串数组"
            }
            result.add(item.asString)
        }
        return result
    }

    private fun optionalString(obj: JsonObject?, key: String): String? {
        if (obj == null || !obj.has(key)) return null
        val value = obj.get(key) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return if (value.asJsonPrimitive.isString) value.asString else null
    }

    private fun boolOrNull(obj: JsonObject, key: String): Boolean? {
        val value = obj.get(key) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
    }

    private fun longOrNull(obj: JsonObject, key: String): Long? {
        val value = obj.get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return runCatching { value.asLong }.getOrNull()
    }
}
