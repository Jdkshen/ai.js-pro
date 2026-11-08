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
 * null，不伪造客户端身份。新 open/openArtifact 会落 owner=mcp-host、ownerSource=server-default；
 * 旧 null 记录仍必须显式 includeUnknownOwner=true 才会被 GC 回收。lastAccessAt 优先使用显式
 * 字段；旧记录回退到文件系统 mtime 并标记 lastAccessSource=filesystem_mtime。
 */
internal class ToolkitWorkspaceInventory(private val store: File) {
    companion object {
        const val QUOTA = 16
        const val DEFAULT_EXPIRED_AFTER_MINUTES = 24 * 60
        const val DEFAULT_MIN_AGE_MINUTES = 60
        /** 服务端默认身份：未显式传 owner 时的 caller 身份，也是新工作区写入的 owner。 */
        const val SERVER_DEFAULT_OWNER = "mcp-host"
        /** 与 SERVER_DEFAULT_OWNER 配套的 clientId 语义：与任何 caller 相容（只表示「没记客户端身份」）。 */
        const val SERVER_DEFAULT_CLIENT_ID = "server-default"
        /** 一次能真正生效的放宽命令（.4 的 Bug2：旧提示照抄仍是 0 命中）。 */
        const val GC_RELAXED_PREVIEW =
            "apk_workspace_gc{\"dryRun\":true,\"includeTemporary\":true,\"minAgeMinutes\":0,\"includeEditSessions\":true}"
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
        val ownerSource: String?,
        val clientId: String?,
        val sessionId: String?,
        val sessionIds: List<String>,
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
                add(JsonPrimitive("owner/clientId/sessionId 只从现有 workspace.json 读取；旧工作区缺失时返回 null；新 open 由服务端写 owner=mcp-host/ownerSource=server-default"))
                add(JsonPrimitive("旧记录 owner=null 时 GC 必须显式 includeUnknownOwner=true；clientId 仍可选"))
                add(JsonPrimitive("lastAccessAt 旧记录回退 filesystem_mtime，不是真实读取时间"))
                add(JsonPrimitive("orphanDirs/danglingEntries 只报告，不自动删除"))
                add(JsonPrimitive("配额报满时先查本清单；能真正生效的放宽预览（含编辑会话在内）：" + GC_RELAXED_PREVIEW +
                    "，确认后把 dryRun 改 false；只释放会话不删快照用 apk_session_release{\"workspaceId\":\"<id>\"}"))
                add(JsonPrimitive("旧记录 owner=null 时还需 includeUnknownOwner=true；owner=mcp-host/ownerSource=server-default 的服务端记录不需要它"))
                add(JsonPrimitive("GC 仍删不动就逐个删：apk_workspace_delete{\"workspaceId\":\"<id>\"}（会连编辑会话一起删，确认无用再用）"))
            }
        )
    }

    /**
     * 受控 GC。默认 dryRun=true 只出计划；dryRun=false 才删除。
     * 默认只清理满足全部条件的过期临时工作区；保存/current/page/protectedIds/未知或
     * 其它 owner/client/未知年龄/活动任务/dangling/corrupt 均保护。显式同时传
     * includeUnknownOwner=true、includeTemporary=true、minAgeMinutes=0 时，expired 且
     * pendingCleanup=true 的工作区可连同其编辑会话一起释放（返回 releasedEditSessions）。
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
        val includeEditSessions = args.tBool("includeEditSessions", false)
        // Bug2（.4）：不传 owner/clientId 时 caller 就是**服务端默认身份**，否则我们自己的
        // owner=mcp-host/ownerSource=server-default 记录会被判 other_owner、clientId=null 再判
        // unknown_client —— 「照提示跑 dryRun」永远是 0 命中。显式传值时下面的判定与旧实现逐条一致。
        val explicitOwner = optionalString(args, "owner")
        val explicitClientId = optionalString(args, "clientId")
        val callerOwner = explicitOwner ?: SERVER_DEFAULT_OWNER
        val callerClientId = explicitClientId ?: SERVER_DEFAULT_CLIENT_ID
        val callerIdentitySource = if (explicitOwner == null && explicitClientId == null) "server-default" else "explicit"
        val inv = inventory(ToolkitIo.json("expiredAfterMinutes" to expiredAfter,
            "limit" to MAX_SCAN), active)
        val now = System.currentTimeMillis()
        val planned = JsonArray()
        val skipped = JsonArray()
        val wouldDelete = ArrayList<String>()
        val wouldReleaseCounts = LinkedHashMap<String, Int>()
        var wouldReclaim = 0L
        var wouldReleaseEditSessions = 0
        val activeJobs = inv.get("activeJobs")?.asInt ?: 0
        val relaxedCleanup = includeUnknownOwner && includeTemporary && minAge == 0
        for (raw in inv.getAsJsonArray("items")) {
            val item = raw.asJsonObject
            val id = item.tString("workspaceId")
            val state = item.tString("state")
            val itemOwner = optionalString(item, "owner")
            val itemOwnerSource = optionalString(item, "ownerSource")
            val itemClient = optionalString(item, "clientId")
            val sessionCount = item.get("sessionCount")?.asInt ?: 0
            val pendingCleanup = item.get("pendingCleanup")?.asBoolean == true
            // 默认保守：只有显式 includeEditSessions=true 才会「连编辑会话一起释放」；
            // 覆盖 temporary 与 expired 两种可回收态（expired 就是「超过 TTL 的临时工作区」，
            // 只认字面 temporary 会让最常见的 expired 仍然删不掉——.4 的 Bug2 就是这样卡住的）。
            // 旧的 relaxedCleanup（includeUnknownOwner+includeTemporary+minAge=0）仍只覆盖 expired+pendingCleanup。
            val releaseEditSessions = (relaxedCleanup && state == "expired" && pendingCleanup) ||
                (includeEditSessions && (state == "temporary" || state == "expired"))
            val reasons = ArrayList<String>()
            if (id in protectedIds) reasons.add("protected_ids")
            if (item.get("current")?.asBoolean == true) reasons.add("current_workspace")
            if ((item.get("activeRefCount")?.asInt ?: 0) > 0) reasons.add("active_reference")
            if (sessionCount > 0 && !releaseEditSessions) reasons.add("edit_session")
            if (state == "saved") reasons.add("saved_workspace")
            if (state == "dangling" || state == "corrupt") reasons.add(state)
            if (state != "expired" && !(includeTemporary && state == "temporary")) reasons.add("not_expired")
            if (activeJobs > 0) reasons.add("active_jobs_workspace_unknown")
            val serverDefaultOwner = itemOwner == SERVER_DEFAULT_OWNER && itemOwnerSource == "server-default"
            if (itemOwner == null) {
                if (!includeUnknownOwner) reasons.add("unknown_owner")
            } else if (explicitOwner != null) {
                // 显式传 owner：行为与旧实现逐条一致（只有 exact 命中或 includeUnknownOwner 才放行）
                if (itemOwner == explicitOwner) {
                    // exact caller-owned workspace
                } else if (serverDefaultOwner && includeUnknownOwner) {
                    // server-default owner: explicit includeUnknownOwner opts in
                } else {
                    reasons.add("other_owner")
                }
            } else if (itemOwner == SERVER_DEFAULT_OWNER || serverDefaultOwner) {
                // 未显式传 owner：caller 即服务端默认身份，服务端写下的记录就是「本方的」
            } else {
                reasons.add("other_owner")
            }
            val ownerIsMine = itemOwner != null && (
                (explicitOwner != null && itemOwner == explicitOwner) ||
                    (explicitOwner == null && (itemOwner == SERVER_DEFAULT_OWNER || serverDefaultOwner))
                )
            if (itemClient == null) {
                // clientId 本来就是可选的（见类注释）：归属已确认是本人时不再重复拦截；
                // 归属不明/他人时保持旧的保守语义。
                if (!ownerIsMine && !includeUnknownOwner) reasons.add("unknown_client")
            } else if (explicitClientId != null) {
                // 显式传 clientId：行为与旧实现逐条一致
                if (itemClient != explicitClientId) reasons.add("other_client")
            } else if (itemClient == SERVER_DEFAULT_CLIENT_ID) {
                // server-default 只表示「没记客户端身份」，与任何 caller 相容
            } else {
                reasons.add("other_client")
            }
            val createdAt = item.get("createdAt")?.takeIf { !it.isJsonNull }?.asLong
            if (createdAt == null && !includeUnknownAge) reasons.add("unknown_age")
            else if (createdAt != null && now - createdAt < minAge * 60_000L) reasons.add("too_young")
            val unique = reasons.distinct()
            val canDelete = unique.isEmpty()
            val row = ToolkitIo.json("workspaceId" to id,
                "bytes" to (item.get("bytes")?.asLong ?: 0L),
                "state" to state, "action" to if (canDelete) "delete" else "skip",
                "protectedReasons" to unique, "owner" to itemOwner, "ownerSource" to itemOwnerSource,
                "clientId" to itemClient, "sessionCount" to sessionCount,
                "sessionIds" to (item.getAsJsonArray("sessionIds") ?: JsonArray()),
                "wouldReleaseEditSessions" to if (canDelete) sessionCount else 0)
            if (canDelete) {
                planned.add(row); wouldDelete.add(id)
                wouldReleaseCounts[id] = sessionCount
                wouldReleaseEditSessions += sessionCount
                wouldReclaim += item.get("bytes")?.asLong ?: 0L
            } else skipped.add(row)
        }
        // ---- C：把「为什么被挡」和「接下来具体跑什么」变成可执行载荷 ----
        val blockedCounts = LinkedHashMap<String, Int>()
        skipped.forEach { row ->
            row.asJsonObject.getAsJsonArray("protectedReasons")?.forEach { reason ->
                val key = reason.asString
                blockedCounts[key] = (blockedCounts[key] ?: 0) + 1
            }
        }
        val blockedBy = JsonObject()
        blockedCounts.forEach { (reason, count) -> blockedBy.addProperty(reason, count) }
        val nextActions = JsonArray()
        val skippedRows = skipped.map { it.asJsonObject }
        fun reasonOf(row: JsonObject) = row.getAsJsonArray("protectedReasons")?.map { it.asString } ?: emptyList()
        if (skippedRows.isNotEmpty()) {
            val withSessions = skippedRows.filter { "edit_session" in reasonOf(it) }
            if (withSessions.isNotEmpty()) {
                nextActions.add(JsonPrimitive("要连同编辑会话一起释放：" + GC_RELAXED_PREVIEW +
                    "（确认后把 dryRun 改 false；被挡会话见 skipped[].sessionIds）"))
                withSessions.take(5).forEach { row ->
                    val id = row.tString("workspaceId")
                    nextActions.add(JsonPrimitive("apk_session_release{\"workspaceId\":\"" + id +
                        "\"} → 释放该工作区全部编辑会话（不删快照），之后重跑同一 dryRun 即可回收"))
                }
            }
            val foreign = skippedRows.filter { row ->
                reasonOf(row).any { it in setOf("other_owner", "other_client", "unknown_owner", "unknown_client") }
            }
            if (foreign.isNotEmpty()) {
                nextActions.add(JsonPrimitive("归属不同的工作区：" + foreign.take(5).joinToString("、") { it.tString("workspaceId") } +
                    "；确认要动别人的记录就显式传 owner/clientId（apk_workspace_gc{\"dryRun\":true,\"owner\":\"<owner>\",\"clientId\":\"<clientId>\",\"includeTemporary\":true,\"minAgeMinutes\":0}）；旧记录 owner=null 需要 includeUnknownOwner=true"))
            }
            if (skippedRows.any { "too_young" in reasonOf(it) }) {
                nextActions.add(JsonPrimitive("有工作区还没到 minAgeMinutes（当前 " + minAge +
                    "）：确认无用就把 minAgeMinutes 设为 0 重跑"))
            }
            if (skippedRows.any { row -> reasonOf(row).any { it in setOf("saved_workspace", "current_workspace", "active_reference", "dangling", "corrupt", "protected_ids", "active_jobs_workspace_unknown") } }) {
                nextActions.add(JsonPrimitive("保存/当前/活动引用/dangling/corrupt/protectedIds/活动任务相关的工作区不会被 GC 删除；确认无用再逐个删：apk_workspace_delete{\"workspaceId\":\"<id>\"}（会连编辑会话一起删）"))
            }
            // 兜底：只要还有被挡的，就必须给一条一定能走通的路（.4 的诉求：别让人原地打转）
            if (nextActions.none { it.asString.contains("apk_workspace_delete") }) {
                nextActions.add(JsonPrimitive("GC 不肯删的（被上面任一条保护）确认无用就逐个删：apk_workspace_delete{\"workspaceId\":\"<skipped[].workspaceId>\"}（会连编辑会话一起删）"))
            }
        }
        if (wouldDelete.isNotEmpty()) {
            nextActions.add(JsonPrimitive("本次计划可回收 " + wouldDelete.size + " 个工作区（" + wouldReclaim +
                " 字节）：把 dryRun 改成 false 执行"))
        }
        if (nextActions.size() == 0) {
            nextActions.add(JsonPrimitive("当前没有可回收也没有被挡的工作区（可能都被删掉了或配额本来就空）；用 apk_workspace_inventory{} 复查"))
        }
        val deleted = ArrayList<String>()
        var reclaimed = 0L
        var releasedEditSessions = 0
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
                    releasedEditSessions += (wouldReleaseCounts[id] ?: 0)
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
            "includeEditSessions" to includeEditSessions,
            "callerOwner" to callerOwner,
            "callerClientId" to callerClientId,
            "callerIdentitySource" to callerIdentitySource,
            "explicitOwner" to explicitOwner,
            "explicitClientId" to explicitClientId,
            "blockedCount" to skipped.size(),
            "blockedBy" to blockedBy,
            "nextActions" to nextActions,
            "wouldDeleteCount" to wouldDelete.size,
            "wouldDeleteIds" to wouldDelete,
            "wouldReleaseEditSessions" to wouldReleaseEditSessions,
            "wouldReclaimBytes" to wouldReclaim,
            "planned" to planned,
            "skipped" to skipped,
            "deletedCount" to deleted.size,
            "deletedIds" to deleted,
            "releasedEditSessions" to releasedEditSessions,
            "reclaimedBytes" to reclaimed,
            "failures" to failures,
            "orphanDirs" to inv.get("orphanDirs"),
            "danglingEntries" to inv.get("danglingEntries"),
            "warnings" to inv.get("warnings"),
            "note" to "只删除 dryRun=false 且 canDelete=true 的工作区；默认保守（includeTemporary/includeUnknownOwner/includeEditSessions 默认 false）；" +
                "owner/clientId 不传时按服务端默认身份（" + SERVER_DEFAULT_OWNER + "/" + SERVER_DEFAULT_CLIENT_ID + "）比较；orphan/dangling 永不自动删除"
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
                sessionCount = sessionIds(dir).size,
                sessionIds = sessionIds(dir),
                baseApkPresent = basePresent,
                owner = optionalString(parsed, "owner"),
                ownerSource = optionalString(parsed, "ownerSource"),
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
        val pending = state == "expired" && !current && activeRefs == 0 &&
            entry.baseApkPresent && state != "saved"
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
            "ownerSource" to entry.ownerSource,
            "clientId" to entry.clientId,
            "sessionId" to entry.sessionId,
            "activeRefCount" to activeRefs,
            "current" to current,
            "sessionCount" to entry.sessionCount,
            "sessionIds" to entry.sessionIds,
            "baseApkPresent" to entry.baseApkPresent,
            "pendingCleanup" to pending,
            "protectedReasons" to reasons,
            "sourcePath" to optionalString(entry.info, "sourcePath"),
            "sha256" to optionalString(entry.info, "sha256"),
            "artifactType" to optionalString(entry.info, "artifactType"),
        )
    }

    /** 超过这个时长没更新过的 active 元数据视为陈旧（最长任务超时 1800s 的 2 倍）。 */
    private val STALE_ACTIVE_METADATA_MILLIS = 2L * 3600_000L

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
            if (state !in setOf("QUEUED", "RUNNING", "CANCELLING")) return@count false
            // 兜底：元数据若长时间未更新（超过最长超时的 2 倍），说明写下它的实例早已不在（崩溃/被杀/重启），
            // 不能让它永久把 GC 挡住（实测：一个陈旧 RUNNING 就导致 10 个临时快照 1.0 GiB 全部无法回收）。
            // 活跃任务会在状态迁移时 persist()，mtime 会持续刷新，所以正常长任务不会被误判。
            val fresh = runCatching { System.currentTimeMillis() - file.lastModified() <= STALE_ACTIVE_METADATA_MILLIS }.getOrDefault(true)
            fresh
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

    /** 编辑会话 id（sessions/<id>/session.json），GC 的 nextActions 要点名到具体会话。 */
    private fun sessionIds(dir: File): List<String> {
        val sessions = File(dir, "sessions")
        return sessions.listFiles()
            ?.filter { it.isDirectory && File(it, "session.json").isFile }
            ?.map { it.name }?.sorted() ?: emptyList()
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
