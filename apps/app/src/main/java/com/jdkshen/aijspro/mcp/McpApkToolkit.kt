package com.jdkshen.aijspro.mcp

import com.android.apksig.ApkSigner
import com.google.gson.*
import com.jdkshen.aijspro.autojs.build.sign.AutoSigningIdentity
import com.jdkshen.aijspro.autojs.build.sign.KeyStoreApkSigner
import java.io.*
import java.util.zip.*

/**
 * P0-1: expiry diagnostics shared by workspace and edit-session failures.
 *
 * <p>The outer MCP error envelope only exposes code/message/argument, so the machine-readable
 * ttl/expiry/recovery fields are embedded in the message as compact details={...}. The
 * authoritative error code is still [ToolkitException.code]; callers must not treat this as
 * INVALID_ARGUMENT.</p>
 */
internal object ToolkitApkExpiry {
    const val TTL_MINUTES: Int = ToolkitWorkspaceInventory.DEFAULT_EXPIRED_AFTER_MINUTES

    data class Details(
        val code: String,
        val workspaceId: String,
        val sessionId: String?,
        val temporary: Boolean?,
        val createdAt: Long?,
        val expiredAt: Long?,
        val ttlRemainingMs: Long?,
        val ttlNote: String,
        val runState: String,
        val reason: String,
        val quota: Int,
        val quotaUsed: Int,
        val recovery: String,
        val nextActions: List<JsonObject>,
    ) {
        fun toJson(): JsonObject = ToolkitIo.json(
            "code" to code,
            "workspaceId" to workspaceId,
            "sessionId" to sessionId,
            "ttlRemainingMs" to ttlRemainingMs,
            "expiredAt" to expiredAt,
            "ttlMinutes" to if (temporary == true) TTL_MINUTES else null,
            "ttlNote" to ttlNote,
            "workspaceState" to runState,
            "reason" to reason,
            "quota" to quota,
            "quotaUsed" to quotaUsed,
            "recovery" to recovery,
            "nextActions" to nextActions,
        )

        fun message(): String {
            val subject = if (code == "SESSION_EXPIRED") "编辑会话不存在或已过期" else "APK 工作区不存在或已过期"
            return subject + "（" + code + "）：workspaceId=" + workspaceId +
                (if (sessionId == null) "" else "，sessionId=" + sessionId) +
                "；reason=" + reason +
                "；ttlRemainingMs=" + (ttlRemainingMs?.toString() ?: "null") +
                "；expiredAt=" + (expiredAt?.toString() ?: "null") +
                "；" + ttlNote +
                "；recovery=" + recovery +
                "；quota=" + quotaUsed + "/" + quota +
                "；details=" + toJson()
        }
    }

    fun isTtlExpired(temporary: Boolean?, createdAt: Long?, now: Long = System.currentTimeMillis()): Boolean =
        temporary == true && createdAt != null && now - createdAt >= TTL_MINUTES * 60_000L

    fun details(
        code: String,
        workspaceId: String,
        sessionId: String?,
        temporary: Boolean?,
        createdAt: Long?,
        reason: String,
        quotaUsed: Int,
        now: Long = System.currentTimeMillis(),
    ): Details {
        val expiredAt = if (temporary == true && createdAt != null) createdAt + TTL_MINUTES * 60_000L else null
        val remaining = expiredAt?.let { maxOf(0L, it - now) }
        val ttlNote = when {
            temporary == null -> "workspace.json 不存在，无法计算 TTL（可能已被 GC 或手动删除）。"
            !temporary -> "保存工作区没有 TTL；会话可能被 apk_session_release 释放或手动删除。"
            createdAt == null -> "workspace.json 缺少 createdAt，无法计算 TTL。"
            remaining == 0L -> "临时工作区 TTL 已用尽（默认 " + TTL_MINUTES + " 分钟）。"
            else -> "临时工作区 TTL 剩余 " + remaining + " ms（默认 " + TTL_MINUTES + " 分钟）。"
        }
        val runState = when {
            temporary == null -> "unknown"
            !temporary -> "saved"
            expiredAt != null && now >= expiredAt -> "expired"
            else -> "temporary"
        }
        val recovery = if (code == "SESSION_EXPIRED") {
            "先 artifact_open 或 apk_workspace_open 确认/重建工作区；再 apk_session_create {workspaceId:\"" + workspaceId + "\"} 建新会话，不要重试旧 editSessionId。"
        } else {
            "先 apk_workspace_open {path:\"<源APK路径>\",temporary:true}（或 artifact_open {path:\"<源APK路径>\"}）重新打开；同路径+同 sha256 会幂等复用/重建，然后 apk_session_create 建新会话。"
        }
        val actions = ArrayList<JsonObject>()
        actions.add(ToolkitIo.json(
            "tool" to "artifact_open",
            "reason" to "EXPIRED_OR_MISSING",
            "arguments" to ToolkitIo.json("path" to "<源APK路径>", "temporary" to true),
            "hint" to "同路径+同 sha256 会复用未过期快照并续期；旧工作区已被 GC 时重建。"
        ))
        actions.add(ToolkitIo.json(
            "tool" to "apk_workspace_inventory",
            "reason" to "SEE_TTL_AND_QUOTA",
            "arguments" to JsonObject(),
            "hint" to "已在错误 details 中给出 ttlRemainingMs/expiredAt/quota；需要完整清单时可再查。"
        ))
        if (code == "SESSION_EXPIRED") {
            actions.add(ToolkitIo.json(
                "tool" to "apk_session_create",
                "reason" to "RECREATE_SESSION",
                "arguments" to ToolkitIo.json("workspaceId" to workspaceId),
                "hint" to "旧 editSessionId 已失效；新建后再重试原操作。"
            ))
        }
        return Details(code, workspaceId, sessionId, temporary, createdAt, expiredAt, remaining,
            ttlNote, runState, reason, ToolkitWorkspaceInventory.QUOTA, quotaUsed, recovery, actions)
    }
}

/** P0-3: classify a possibly-budget-cut search so zero hits are not reported as "not found". */
internal object ToolkitApkSearchDiagnostics {
    data class Classification(val fields: JsonObject, val nextActions: JsonArray)

    private fun strings(value: JsonElement?): List<String> =
        value?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf { item -> item.isJsonPrimitive && item.asJsonPrimitive.isString }?.asString
        } ?: emptyList()

    fun classify(
        partial: Boolean,
        elapsedMs: Long,
        hitCount: Int,
        metadata: JsonObject,
        args: JsonObject,
        budgetMillis: Long = 8_000L,
    ): Classification {
        val partialTargets = strings(metadata.get("partialTargets"))
        val notSearched = strings(metadata.get("notSearchedTargets"))
        val target = args.tString("target", "overview")
        val stopReason = when {
            !partial -> "COMPLETE"
            elapsedMs >= budgetMillis - 500L -> "TIME_BUDGET"
            partialTargets.isNotEmpty() || notSearched.isNotEmpty() -> "BUDGET"
            else -> "INCOMPLETE"
        }
        val zero = hitCount == 0
        val budgetNote = if (!partial) null else
            "搜索尚未完成（stopReason=" + stopReason + "，elapsedMs=" + elapsedMs + "/" + budgetMillis + "）：" +
                "partialTargets=" + partialTargets + "，notSearchedTargets=" + notSearched + "。" +
                "total=" + hitCount + " 不能判为“不存在”；请缩小 target/prefix 或重试，只有 stopReason=COMPLETE 的 0 命中才可视为没有。"
        val actions = JsonArray()
        if (partial) {
            val narrower = partialTargets.firstOrNull() ?: notSearched.firstOrNull()
                ?: if (target == "overview") "files" else target
            val retryArgs = args.deepCopy().apply {
                addProperty("target", narrower)
                if (narrower != "overview" && !has("limit")) addProperty("limit", 50)
            }
            actions.add(ToolkitIo.json(
                "tool" to "apk_search",
                "reason" to "BUDGET_EXHAUSTED",
                "arguments" to retryArgs,
                "hint" to "用具体 target 重试；结果不完整时不要用 total 判不存在，可继续按 nextActions 收窄 prefix。"
            ))
        }
        val fields = ToolkitIo.json(
            "stopReason" to stopReason,
            "complete" to !partial,
            "budgetExhausted" to partial,
            "searchBudgetMillis" to budgetMillis,
            "searchElapsedMs" to elapsedMs,
            "partialTargets" to partialTargets,
            "notSearchedTargets" to notSearched,
            "zeroResult" to zero,
            "emptyResult" to (zero && !partial),
            "zeroResultMeaning" to when {
                !zero -> null
                !partial -> "COMPLETE_ZERO_HITS"
                else -> "UNKNOWN_BUDGET_EXHAUSTED"
            },
            "budgetNote" to budgetNote,
            "nextActions" to actions,
        )
        return Classification(fields, actions)
    }
}

/** W1: atomically write decompiled source when outPath is explicitly requested. */
internal fun writeDecompiledOutput(
    organizer: ToolkitOutputOrganizer,
    root: File,
    args: JsonObject,
    result: JsonObject,
): JsonObject {
    val raw = args.get("outPath")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()
    if (raw.isEmpty()) return result
    val content = result.get("content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: result.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: throw ToolkitException("DECOMPILE_OUTPUT_EMPTY", "反编译结果没有 content/text，无法按 outPath 落盘", "outPath")
    val organized = organizer.organize(ToolkitIo.resolve(root, raw), args)
    val target = organized.path
    require(!target.exists()) { "outPath 已存在，请使用新文件名：" + target.path }
    require(target.parentFile?.isDirectory == true) { "outPath 父目录不存在或无法创建：" + (target.parentFile?.path ?: "") }
    ToolkitIo.atomic(target) { it.writeText(content, Charsets.UTF_8) }
    result.addProperty("savedPath", target.path)
    result.addProperty("outPath", target.path)
    result.addProperty("savedChars", content.length)
    result.addProperty("savedBytes", target.length())
    result.addProperty("savedSha256", ToolkitIo.sha(target))
    result.addProperty("outputTruncated", result.tBool("truncated") || result.tBool("hasMore"))
    organized.info?.let { result.add("organized", it) }
    val actions = result.get("nextActions")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { result.add("nextActions", it) }
    actions.add(ToolkitIo.json(
        "tool" to "file_read_text",
        "arguments" to ToolkitIo.json("path" to target.path),
        "hint" to "反编译正文已原子落盘，可直接读取/归档。"
    ))
    return result
}

/** Immutable APK snapshots and copy-on-write edit sessions. Never calls MT. */
internal class McpApkToolkit(
    private val root: File,
    private val store: File,
    private val schemas: JsonObject,
    private val signer: ((File, File) -> Unit)? = null,
    private val assembler: ((Int, Long, String, String) -> ByteArray)? = null,
    private val runtime: ToolkitRuntime? = null,
) {
    private val outputOrganizer by lazy { ToolkitOutputOrganizers.forStore(store, root) }
    private val pages = java.util.concurrent.ConcurrentHashMap<String, Page>()
    private val workspaceLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
    @Volatile private var currentWorkspace: String? = null
    private data class Page(val workspace: String, val session: String, val revision: String, val items: List<JsonObject>, var offset: Int,
                            val kind: String, val metadata: JsonObject, val truncated: Boolean, val limitMax: Int = 200,
                            val producer: String = "apk_page", val at: Long = System.currentTimeMillis())
    private val gson = Gson()
    /** #5：locator 前缀清单与跨 DEX uniqueLocator 的统一提示；错误和 inputSchema 共用。 */
    private val locatorHint = "locator 前缀：zip_entry:（如 zip_entry:assets/project/main.js）、" +
        "dex_class:（如 dex_class:Lcom/example/Foo;）、" +
        "dex_method:（如 dex_method:Lcom/example/Foo;->bar()V）、" +
        "resource:（如 resource:0x7f010001）；" +
        "跨 DEX 同名类请使用搜索结果返回的 uniqueLocator（格式 dex_entry:classes.dex!dex_class:Lcom/example/Foo;）。"
    init { require(store.isDirectory || store.mkdirs()) { "无法创建 APK 工作区目录" } }
    fun definitions(): JsonArray = JsonArray().apply {
        schemas.entrySet().forEach { (name, schema) -> add(ToolkitIo.json("name" to name, "category" to "toolkit", "categoryTitle" to "文件与APK工具",
            "description" to description(name), "inputSchema" to schemaWithOrganize(name, schemaWithLocatorHint(schema)))) }
    }

    /** 产出工具统一暴露 organize=true 默认；显式子目录/绝对子目录仍不移动。 */
    private fun schemaWithOrganize(name: String, schema: JsonElement): JsonElement {
        val organizeTools = setOf("apk_build", "artifact_export", "apk_copy_entry")
        val decompileOutputTools = setOf("apk_decompile_class", "apk_decompile_method")
        val needsOrganize = name in organizeTools || name in decompileOutputTools
        if (!needsOrganize || !schema.isJsonObject) return schema
        val copy = schema.deepCopy().asJsonObject
        val properties = copy.getAsJsonObject("properties") ?: return copy
        if (name in decompileOutputTools) {
            properties.add("outPath", JsonObject().apply {
                addProperty("type", "string")
                addProperty("description", "可选：把反编译正文（data.content）原子写入该路径；裸文件名/操作根直下会按 organize 归置到默认项目。写入成功返回 savedPath/organized，避免 job_result → file_write 三段式。")
            })
        }
        properties.add("organize", JsonObject().apply {
            addProperty("type", "boolean")
            addProperty("default", true)
            addProperty("description", "默认 true：裸文件名或直接落在操作根的文件会改写进默认项目 mcp-out-YYYYMMDD；显式子目录/项目内路径不动。false 保持原路径。")
        })
        if (name in decompileOutputTools) {
            val desc = copy.get("description")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: ""
            if (desc.isNotBlank() && !desc.contains("outPath")) copy.addProperty("description", desc + " 可选 outPath 直接原子落盘正文。")
        }
        return copy
    }
    /** 给含 locator 参数的 inputSchema 补前缀说明；只加 description，不改 type/required/其它约束。 */
    private fun schemaWithLocatorHint(schema: JsonElement): JsonElement {
        if (!schema.isJsonObject) return schema
        val copy = schema.deepCopy()
        val obj = copy.asJsonObject
        val locator = obj.getAsJsonObject("properties")?.get("locator")?.takeIf { it.isJsonObject }?.asJsonObject ?: return copy
        val old = locator.get("description")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: ""
        if (!old.contains("uniqueLocator")) locator.addProperty("description", if (old.isBlank()) locatorHint else old + "；" + locatorHint)
        if (!obj.has("description")) obj.addProperty("description", locatorHint)
        return copy
    }
    fun call(name: String, args: JsonObject): JsonObject {
        val key=args.tString("workspaceId","").ifEmpty { if(name=="apk_continue") pages[args.tString("nextCursor","")]?.workspace?:"catalog" else "catalog" }
        val lock=workspaceLocks.getOrPut(key){java.util.concurrent.locks.ReentrantLock()}
        lock.lockInterruptibly()
        try { return dispatch(name,args) } finally { lock.unlock() }
    }
    private fun dispatch(name: String, args: JsonObject): JsonObject {
        ToolkitIo.ensureActive()
        return when (name) {
            "apk_workspace_open" -> ToolkitIo.ok(open(args))
            "artifact_open" -> ToolkitIo.ok(openArtifact(args))
            in ToolkitExtraTools.names -> ToolkitIo.ok(ToolkitExtraTools.call(root, store, name, args))
            "apk_verify" -> {
                val file=if(args.has("workspaceId"))snapshot(args.tString("workspaceId")) else ToolkitIo.resolve(root,args.tString("path"))
                ToolkitIo.ok(ToolkitApkVerifier.inspect(file,args.get("minSdk")?.asInt,args.get("maxSdk")?.asInt))
            }
            "apk_workspace_list" -> ToolkitIo.ok(ToolkitIo.json("items" to workspaces().filter { !it.tBool("temporary") }))
            "apk_workspace_inventory" -> ToolkitIo.ok(workspaceInventory(args))
            "apk_workspace_gc" -> ToolkitIo.ok(workspaceGc(args))
            "apk_session_release" -> ToolkitIo.ok(releaseEditSessions(args))
            "apk_list_available_apks" -> listAvailable(args)
            "apk_workspace_delete" -> {
                val workspace = workspace(args.tString("workspaceId")); val id = workspace.tString("workspaceId")
                safeRemove(directory(id), store); pages.entries.removeAll { it.value.workspace == id }
                if(currentWorkspace == id) currentWorkspace = null
                ToolkitIo.ok(ToolkitIo.json("workspaceId" to id, "deleted" to true))
            }
            "apk_continue" -> continuePage(args)
            "apk_read_signature" -> ToolkitIo.ok(readSignature(snapshot(args.tString("workspaceId")), args.tBool("verify", false)).apply { addProperty("workspaceId", args.tString("workspaceId")) })
            else -> {
                val workspace = args.tString("workspaceId"); workspace(workspace)
                val session = args.tString("editSessionId", "")
                if (session.isNotEmpty()) sessionDir(workspace, session)
                when (name) {
                    "apk_session_create" -> ToolkitIo.ok(createSession(workspace))
                    "apk_session_diff" -> {
                        val required = requiredSession(session); val beforeRevision = revision(workspace, required)
                        val result = page(workspace, required, ToolkitApkSessions.diff(snapshot(workspace), sessionDir(workspace, required)), args.tInt("limit", 50, 1, 200), producer = "apk_session_diff")
                        result.getAsJsonObject("data").withRevision(workspace, required, beforeRevision)
                        result
                    }
                    "apk_session_checkpoint" -> ToolkitIo.ok(ToolkitApkSessions.checkpoint(sessionDir(workspace,requiredSession(session)),args))
                    "apk_session_checkpoints" -> ToolkitIo.ok(ToolkitApkSessions.checkpoints(sessionDir(workspace,requiredSession(session))))
                    "apk_session_rollback" -> ToolkitIo.ok(ToolkitApkSessions.rollback(sessionDir(workspace,requiredSession(session)),args))
                    "artifact_export" -> ToolkitIo.ok(exportArtifact(workspace,session,args))
                    "apk_set_members" -> page(workspace,session,setMembers(workspace),args.tInt("limit",50,1,200), producer = "apk_set_members")
                    "apk_set_open_member" -> ToolkitIo.ok(openSetMember(workspace,args))
                    "apk_decompile_class", "apk_decompile_method" -> ToolkitIo.ok(decompile(workspace,session,args))
                    "apk_list" -> list(workspace, session, args)
                    "apk_search" -> search(workspace, session, args)
                    "apk_read_text" -> {
                        val data = readText(workspace, session, args); val actions = JsonArray()
                        if(data.tBool("hasMore")) actions.add(action("apk_read_text", args.deepCopy().apply {
                            add("offset", data.get("nextOffset"))
                            add("startLine", data.get("nextLine")); add("startColumn", data.get("nextColumn"))
                        }))
                        ToolkitIo.ok(data, actions)
                    }
                    "apk_read_bytes" -> ToolkitIo.ok(readBytes(workspace, session, args))
                    "apk_dex_read_class_outline" -> {
                        val (entry, dex) = dexFor(workspace, session, args.tString("locator"))
                        page(workspace, session, dex.outline(args.tString("locator")).map { it.apply { addProperty("dexEntry", entry) } }, args.tInt("limit", 200, 1, 1000), limitMax = 1000, producer = "apk_dex_read_class_outline")
                    }
                    "apk_dex_search_members", "apk_dex_call_graph", "apk_dex_hierarchy" ->
                        ToolkitIo.ok(ToolkitDexNavigation(dexes(workspace,session).toList()).call(name,args))
                    // dex 接口族（商家建议 §4.3）：AIDL/Binder 事务号表 + 类/方法清单分页。
                    "apk_dex_interface_table", "apk_dex_list_classes", "apk_dex_list_methods" ->
                        ToolkitIo.ok(dexInterfaceValue(workspace, session) { dex -> when (name) {
                            "apk_dex_interface_table" -> dex.interfaceTable(args)
                            "apk_dex_list_classes" -> dex.listClasses(scopedDexPagingArgs(workspace, session, args))
                            else -> dex.listMethods(scopedDexPagingArgs(workspace, session, args))
                        } })
                    "apk_dex_xref" -> {
                        val locator = args.tString("locator"); val limit = args.tInt("limit", 50, 1, 200)
                        val items = ArrayList<JsonObject>()
                        val allDex = dexes(workspace, session).toList(); val universe = allDex.flatMap { it.second.classes }
                        for ((entry, dex) in allDex) {
                            if (items.size >= 10000) break
                            items.addAll(dex.references(locator, args.tString("methodResolution", if(locator.startsWith("dex_method:")) "dispatch" else "not_applicable"), 10001 - items.size, universe).map { it.apply { addProperty("dexEntry", entry) } })
                        }
                        val envelope = page(workspace, session, items, limit, items.size >= 10000, producer = "apk_dex_xref")
                        // 可选：合并「接口实现者 / 方法重写者」（includeImplementations/includeOverrides）。
                        if (args.tBool("includeImplementations") || args.tBool("includeOverrides")) {
                            // includeOverrides 只对 dex_method 定位符有意义：给了 class 定位符时跳过并说明，
                            // 而不是让整个 xref 调用失败（调用方本来只是想多要一段信息）。
                            val locator = args.tString("locator")
                            val wantOverrides = args.tBool("includeOverrides")
                            val overrideLocatorOk = locator.startsWith("dex_method:")
                            val extraArgs = args.deepCopy().apply {
                                addProperty("includeOverrides", wantOverrides && overrideLocatorOk)
                            }
                            val extra = dexInterfaceValue(workspace, session) { dex -> dex.xref(extraArgs) }
                            envelope.getAsJsonObject("data")?.let { data ->
                                extra.entrySet().forEach { entryValue -> data.add(entryValue.key, entryValue.value) }
                                if (wantOverrides && !overrideLocatorOk) {
                                    data.addProperty("overridesNote",
                                        "includeOverrides 需要 dex_method 定位符（本次 locator 不是），已跳过；" +
                                            "如需重写者，请先用 apk_dex_list_methods 拿到 dex_method 定位符再调用。")
                                }
                            }
                        }
                        envelope
                    }
                    "apk_resource_read" -> ToolkitIo.ok(readResources(workspace, session, args))
                    "apk_resource_xref" -> resourceXref(workspace, session, args)
                    "apk_native_inspect" -> {
                        val elf = elf(workspace, session, args); val data = elf.inspect()
                        val actions = JsonArray(); listOf("sections", "segments", "symbols", "imports", "neededLibraries", "relocations").forEach { kind ->
                            actions.add(action("apk_native_list_items", args.deepCopy().apply { addProperty("itemKind", kind); addProperty("namePrefix", ""); addProperty("demangle", false); addProperty("limit", 50) }))
                        }; ToolkitIo.ok(data, actions)
                    }
                    "apk_native_list_items" -> {
                        val e = elf(workspace, session, args)
                        val items = e.items(args.tString("itemKind"), args.tString("namePrefix", "")).map { item ->
                            if(args.tBool("demangle") && item.get("name")?.isJsonPrimitive == true) item.addProperty("demangledName", ToolkitNative.demangle(item.tString("name")))
                            item
                        }
                        page(workspace, session, items, args.tInt("limit", 100, 1, 500),
                            metadata = ToolkitIo.json("demangleAvailable" to ToolkitNative.demangleAvailable), limitMax = 500,
                            producer = "apk_native_list_items")
                    }
                    "apk_native_map_address" -> ToolkitIo.ok(elf(workspace, session, args).map(args))
                    "apk_native_disassemble" -> {
                        val elf = elf(workspace, session, args); val instructions = elf.instructions(args)
                        ToolkitIo.ok(ToolkitIo.json("items" to instructions.map { elf.instructionJson(it) }, "decodedInstructions" to instructions.count { it.decoded },
                            "undecodedInstructions" to instructions.count { !it.decoded }, "complete" to instructions.all { it.decoded }))
                    }
                    "apk_native_xref" -> {
                        val result = elf(workspace, session, args).xref(args, resultLimit = 10001)
                        val items = result.remove("items").asJsonArray.map { it.asJsonObject }
                        val truncated = result.remove("truncated").asBoolean || items.size > 10000
                        page(workspace, session, items.take(10000), args.tInt("limit", 50, 1, 200), truncated, kind = "refs", metadata = result, producer = "apk_native_xref")
                    }
                    "apk_native_analyze_control_flow" -> ToolkitIo.ok(elf(workspace, session, args).cfg(args))
                    "apk_edit_text" -> ToolkitIo.ok(editText(workspace, requiredSession(session), args))
                    "apk_delete_target" -> ToolkitIo.ok(deleteTarget(workspace, requiredSession(session), args))
                    "apk_patch_bytes" -> ToolkitIo.ok(patchBytes(workspace, requiredSession(session), args))
                    "apk_resource_edit", "apk_resource_add" -> ToolkitIo.ok(editResources(workspace, requiredSession(session), args, name.endsWith("_add")))
                    "apk_native_patch_string" -> ToolkitIo.ok(patchNativeString(workspace, requiredSession(session), args))
                    "apk_native_patch_instructions" -> ToolkitIo.ok(patchInstructions(workspace, requiredSession(session), args))
                    "apk_session_check" -> ToolkitIo.ok(check(workspace, requiredSession(session), args.tBool("runBuildChecks", true)))
                    "apk_build" -> ToolkitIo.ok(build(workspace, requiredSession(session), args))
                    "apk_copy_entry" -> ToolkitIo.ok(copyEntry(workspace, session, args))
                    else -> throw IllegalArgumentException("未知 APK 工具：$name")
                }
            }
        }.also { response ->
            response.getAsJsonObject("data")?.let { data ->
                args.get("workspaceId")?.let { data.add("workspaceId", it) }
                args.get("editSessionId")?.let { data.add("editSessionId", it) }
            }
        }
    }
    private fun description(name: String): String = when(name) {
        "apk_verify" -> "只读检查真实APK：密码学验签、普通Stored4B/原生库ZIP16KB、ELF LOAD段及Manifest。返回独立检查证据，不保证安装/业务行为。"
        "apk_read_signature" -> "只读 APK 的 V1/V2/V3/V3.1 证书与方案元数据；**默认不做密码学验签**：此时 verified=false 且 verification=not_run（=「没跑验签」，不是「验签失败」）、verificationReason 说明原因、verifyWith 指向真正能验签的调用。要密码学验签与逐方案状态用 apk_verify{workspaceId}（同一 ToolkitApkVerifier 的完整报告），或本工具 verify=true 在同一次调用里跑验签并返回 signature.schemes/errors/backendDiagnosis。"
        "apk_dex_search_members" -> "按方法名/描述符/字符串/引用搜索，返回精确方法locator、dexEntry和行/指令锚点；有预算，truncated表示需要缩小查询。"
        "apk_search" -> "跨 ZIP/resources/dex/native 语义目标搜索；返回 stopReason（COMPLETE/TIME_BUDGET/BUDGET）、partialTargets、budgetNote 与 zeroResultMeaning。只有 stopReason=COMPLETE 且 total=0 才是“没有命中”。"
        "apk_dex_call_graph" -> "跨DEX向内/向外调用图，限定深度/节点/边；dexEntry消歧，动态和反射目标明确报告不完整。"
        "apk_dex_hierarchy" -> "查看类继承、接口实现与方法覆盖关系，使用原始descriptor定位符，跨DEX同名类需dexEntry。"
        "apk_dex_interface_table" -> "Binder/AIDL 接口事务号表：输入接口 descriptor（Lpkg/IFoo; 或 pkg.IFoo）→ 方法/transactCode/参数/返回/oneway，并给 Stub 与 Proxy 两侧对照（missingOnServer/missingOnClient/orderMismatches/proxyCodeMismatches）。transactSource=static_field 才是权威编号；declaration_order 只是静态字段缺失时的推导（父接口在前），不保证等于 AIDL 编译器编号。只读 DEX 静态结构，不执行 Binder。"
        "apk_dex_list_classes" -> "分页列出 DEX 里的类（替代盲搜）：prefix/packageFilter/dexEntry/cursor/limit → items/total/returned/nextCursor/hasMore/truncated。"
        "apk_dex_list_methods" -> "分页列出 DEX 里的方法（可按 owner 过滤，owner 可给 descriptor 或简单类名）：name/descriptor/access/returnType/paramTypes/locator + nextCursor。"
        "apk_decompile_class" -> "可选JADX类Java阅读；默认后台返回jobId，用apk_job_status/result读回。不保证反编译源码可重新构建。输出含 JADX ERROR/Method dump skipped/errorsCount>0 时 decompileIncomplete=true，job state=SUCCEEDED_WITH_WARNINGS，可按 incompleteReason 改用 smali。可选 outPath 直接原子落盘正文（返回 savedPath/organized），省掉 job_result→file_write。"
        "apk_decompile_method" -> "可选JADX精确方法Java阅读；默认后台返回jobId，需原始dex_method定位符，重载完整签名匹配。输出含 JADX ERROR/Method dump skipped/errorsCount>0 时 decompileIncomplete=true，job state=SUCCEEDED_WITH_WARNINGS，按 incompleteReason 改用 apk_read_text(locator=同一 dex_method) 读 smali。可选 outPath 直接原子落盘正文（返回 savedPath/organized）。"
        "android_resources_compile" -> "可选Android AAPT2把res/Manifest按指定宿主编译为project-res.apk及sidecar；默认后台jobId，组件显式安装。"
        "apk_component_status" -> "只读查看JADX/资源编译组件安装与可用状态；不下载不运行编译器。"
        "apk_component_install" -> "显式下载并校验可选APK/逆向组件（含免Root frida-gadget）；默认后台jobId，manifestUrl可指定发布清单。"
        "apk_component_remove" -> "卸载指定可选APK组件，保留源工程和其他组件。"
        "project_folder" -> "管理产出项目目录：state 查看默认项目与候选；create/set 建并持久化为默认；tidy 默认 dryRun=true 把散落文件移到项目 _incoming/，只移动不删除。裸文件名/操作根直下的 file_write_bytes/file_edit_text 默认自动归置到默认项目。"
        "apk_job_start" -> "后台运行指定APK分析/编译操作，立即返回jobId；任务有超时，失败不自动重放。"
        "apk_job_list" -> "读取当前工具目录的后台任务和状态；支持 operation/state/owner/locator 过滤与 limit/cursor 分页（默认全部）。latestByLocator/items[].latestForLocator 指向同一 locator 的最新任务；effectiveConcurrency=1，maxActive 只是排队/登记上限。"
        "apk_job_status" -> "读取后台任务状态/阶段；CANCELLING表示正在等待实际worker结束。SUCCEEDED_WITH_WARNINGS 表示完成但有反编译不完整（warningCode=DECOMPILE_INCOMPLETE，详见结果 decompileIncomplete/incompleteReason）。"
        "apk_job_result" -> "分块读取后台任务最终JSON结果，offset以返回nextOffset继续。"
        "apk_job_export" -> "把后台任务结果原样原子导出到工具目录内的 outPath；省略 offset/maxChars 时导出全部，超大结果可用 offset/maxChars 分页。导出后内容归用户，任务缓存仍按保留策略淘汰。"
        "apk_job_cancel" -> "请求取消指定后台任务；直到worker真正结束才报告CANCELLED，已提交操作可能完成。"
        "apk_session_diff" -> "预览会话相对原快照的新增/修改/删除、摘要和小文本；不覆盖源文件。"
        "apk_session_checkpoint" -> "保存编辑会话检查点，复制变更条目以支持后续回退；最多8个、总量512MiB。"
        "apk_session_checkpoints" -> "列出会话检查点及其revision。"
        "apk_session_rollback" -> "按检查点回退会话，必须提供当前expectedRevision；不覆盖原APK，返回新revision。"
        "artifact_open" -> "直接打开APK、独立DEX/SO/ELF或APKS/XAPK/APKM。独立文件使用明确标记的分析容器；分包先选择成员。同路径+同sha256幂等复用（返回 reused/reopened/reuseReason），过期临时快照会续期而不是报错。"
        "artifact_export" -> "导出编辑后的独立DEX/ELF文件，不把分析容器伪装成可安装APK。"
        "apk_set_members" -> "列出分包归档APK成员、大小和base候选；不承诺自动合并或安装。"
        "apk_set_open_member" -> "选中分包成员创建独立只读APK快照，保留容器来源；可继续原APK工具链。"
        "apk_workspace_open" -> "创建 APK 只读快照。path 在工具目录内；temporary=true 为一次性工作区（默认 TTL 24h）。同路径+同sha256幂等复用并续期（reused/reopened/reuseReason）；后续修改先 apk_session_create。"
        "apk_workspace_inventory" -> "解释 APK 工作区配额占用：列出保存/临时/过期/dangling 工作区、实际字节、owner/clientId/sessionId、活动引用、orphanDirs 与 danglingEntries；配额 16，统计范围见 quotaScope。"
        "apk_workspace_gc" -> "受控清理 APK 工作区：默认 dryRun=true 只预览；保存/当前/活动引用/编辑会话/dangling/未知所有权默认全部保护。放宽要显式传：includeTemporary=true 才动临时工作区，includeEditSessions=true 才允许连同编辑会话一起释放（默认 false，返回 releasedEditSessions/releasedSessionIds），includeUnknownOwner=true 才动 owner=null 的旧记录，minAgeMinutes 控制最年轻可删。owner/clientId 不传时按服务端默认身份（mcp-host/server-default）比较——服务端自己写下的记录不再被判 other_owner；显式传值时与旧行为一致。返回 blockedBy（按 protectedReasons 计数）+ 点名 workspaceId/sessionIds 的 nextActions；orphan/dangling 永不自动删除。"
        "apk_session_release" -> "释放 APK 工作区的编辑会话（workspaceId 或 all=true）；只删除 sessions/ 下会话，不删除工作区/快照；返回 releasedSessions/releasedBytes/remainingSessions，重复调用幂等。"
        "apk_read_text" -> "读取文本：普通条目；DEX 类/方法（smali）；axml: 或 zip_entry: 的二进制 XML/Manifest 会解码成可读 XML。分页字段统一为 Unicode 码点：offset/nextOffset 与 startLine/startColumn/nextLine/nextColumn 二选一，同时传时 offset 优先；普通 zip_entry 文本传 offset 会真正切正文，续读用返回的 nextOffset（不要自己猜 startLine）。AXML 读取会返回 text/targetVersion，下一步用同一个 axml: locator 调 apk_edit_text。"
        "apk_edit_text" -> "按 targetVersion 原子编辑会话文本：普通条目、DEX 类/方法 smali、axml: 或 zip_entry: 的二进制 XML/Manifest（内部 ToolkitResources.encodeXml，拒绝 DOCTYPE/ENTITY）；AXML 先 apk_read_text(locator=axml:<entry>) 再传完整 XML 到 write_target。"
        "apk_patch_bytes" -> "等长原始字节补丁，只适用于普通条目；classes*.dex、resources.arsc、二进制 AXML/AndroidManifest.xml 会被拒绝。改 AXML 用 apk_read_text + apk_edit_text（axml: locator），或 apk_decode_project → file_edit_text → apk_build_project。"
        "apk_resource_read", "apk_resource_edit", "apk_resource_add" -> "只操作 resources.arsc 的资源表/资源 XML，不包含 AXML/AndroidManifest.xml；AXML 请用 apk_read_text/apk_edit_text 的 axml: locator。"
        "apk_build" -> "校验编辑会话，导出新 APK；不修改源包。sign=true 使用本机 AI.js Pro 签名身份，不能沿用原作者签名。"
        "apk_native_disassemble", "apk_native_xref", "apk_native_analyze_control_flow" -> "有界直接指令分析：原生库可用时覆盖 AArch64、ARM/Thumb、x86/x86-64（Capstone 5.0.6），否则回退 AArch64 常见指令子集；未知指令明确标出，间接引用与完整数据流不作成功保证。"
        "apk_native_patch_instructions" -> "会话中汇编并等长修改 AArch64、ARM/Thumb、x86/x86-64 指令，expectedHex 防旧数据。常见指令可直接处理，一般汇编可通过可选 C/C++ 组件完成。"
        "apk_read_bytes", "apk_delete_target", "apk_copy_entry" ->
            "读取/编辑 APK 条目、DEX 或资源；$locatorHint"
        else -> "AI.js Pro 本地 APK 工具：${name.removePrefix("apk_").replace('_', ' ')}；使用 tools/list 参数定义，locator 来自列表/搜索，不依赖 MT。"
    }
    /** task-250：当前进程内可用于 GC 保护的活动引用。 */
    private fun activeWorkspaceReferences(): ToolkitWorkspaceInventory.ActiveReferences {
        val refs = HashMap<String, Int>()
        pages.values.forEach { page ->
            if (page.workspace.isNotEmpty()) refs[page.workspace] = (refs[page.workspace] ?: 0) + 1
        }
        return ToolkitWorkspaceInventory.ActiveReferences(
            currentWorkspaceId = currentWorkspace,
            pageReferences = refs,
        )
    }

    /** task-250：工作区清单（不修改磁盘）。 */
    private fun workspaceInventory(args: JsonObject): JsonObject =
        ToolkitWorkspaceInventory(store).inventory(args, activeWorkspaceReferences())

    /** task-250：受控 GC；dryRun=false 时删除计划内目录并清理进程内 page/current 引用。 */
    private fun workspaceGc(args: JsonObject): JsonObject {
        val result = ToolkitWorkspaceInventory(store).gc(args, activeWorkspaceReferences())
        val deleted = result.getAsJsonArray("deletedIds") ?: JsonArray()
        if (deleted.size() > 0) {
            val ids = deleted.map { it.asString }.toSet()
            pages.entries.removeAll { it.value.workspace in ids }
            val current = currentWorkspace
            if (current != null && current in ids) currentWorkspace = null
            ids.forEach { workspaceLocks.remove(it) }
        }
        return result
    }

    /**
     * 释放编辑会话（不删除工作区快照）。workspaceId 指定单个；all=true 释放所有
     * store 工作区。重复调用返回 releasedSessions=0，便于幂等重试。
     */
    private fun releaseEditSessions(args: JsonObject): JsonObject {
        val all = args.tBool("all", false)
        val workspaceId = args.tString("workspaceId", "").trim()
        if (!all && workspaceId.isEmpty()) {
            throw ToolkitException("INVALID_ARGUMENT", "需要 workspaceId 或 all=true", "workspaceId")
        }
        val targets = if (all) {
            store.listFiles()?.filter { it.isDirectory && it.name != "jobs" && !it.name.startsWith(".") } ?: emptyList()
        } else listOf(workspaceDirectoryForRelease(workspaceId))
        val releasedIds = JsonArray()
        val releasedWorkspaces = LinkedHashSet<String>()
        var releasedSessions = 0
        var releasedBytes = 0L
        for (workspace in targets) {
            val sessionsDir = File(workspace, "sessions")
            val sessions = sessionsDir.listFiles()?.filter { it.isDirectory && File(it, "session.json").isFile } ?: emptyList()
            for (session in sessions) {
                val bytes = directoryBytes(session)
                safeRemove(session, store)
                releasedSessions++
                releasedBytes += bytes
                releasedIds.add(session.name)
                releasedWorkspaces.add(workspace.name)
            }
            if (sessionsDir.isDirectory && sessionsDir.listFiles()?.isEmpty() == true) sessionsDir.delete()
        }
        return ToolkitIo.json(
            "workspaceId" to if (all) null else workspaceId,
            "all" to all,
            "releasedSessions" to releasedSessions,
            "releasedBytes" to releasedBytes,
            "releasedSessionIds" to releasedIds,
            "releasedWorkspaces" to releasedWorkspaces.toList(),
            "remainingSessions" to countRemainingSessions(),
            "idempotent" to (releasedSessions == 0),
        )
    }

    private fun workspaceDirectoryForRelease(id: String): File {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            throw ToolkitException("INVALID_ARGUMENT", "workspaceId 格式无效", "workspaceId")
        }
        val base = store.canonicalFile
        val target = File(store, id).canonicalFile
        if (target.parentFile != base || target.name != id) {
            throw ToolkitException("INVALID_ARGUMENT", "workspaceId 路径无效", "workspaceId")
        }
        return target
    }

    private fun countRemainingSessions(): Int =
        (store.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name != "jobs" && !it.name.startsWith(".") }
            .sumOf { workspace -> File(workspace, "sessions").listFiles()?.count { File(it, "session.json").isFile } ?: 0 }

    private fun directoryBytes(file: File): Long {
        if (file.isFile) return file.length()
        if (!file.isDirectory) return 0L
        return file.listFiles()?.sumOf { directoryBytes(it) } ?: 0L
    }

    private fun addClientMetadata(args: JsonObject, info: JsonObject) {
        val owner = optionalArgument(args, "owner")
        if (owner != null) {
            info.addProperty("owner", owner)
            info.addProperty("ownerSource", "caller")
        } else {
            // 服务端默认身份：新记录不再落入 unknown_owner 保护桶。
            info.addProperty("owner", "mcp-host")
            info.addProperty("ownerSource", "server-default")
        }
        optionalArgument(args, "clientId")?.let { info.addProperty("clientId", it) }
        optionalArgument(args, "sessionId")?.let { info.addProperty("sessionId", it) }
    }

    private fun optionalArgument(args: JsonObject, key: String): String? =
        args.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 配额报满时的可执行建议。旧记录 owner=null 必须显式 includeUnknownOwner；
     * 新记录 owner=mcp-host/ownerSource=server-default，可用同一命令回收。
     */
    private fun workspaceQuotaHint(): String =
        "；可复制执行：apk_workspace_inventory {}；" +
            "apk_workspace_gc {\"dryRun\":true,\"includeTemporary\":true,\"minAgeMinutes\":0,\"includeEditSessions\":true} 先预览（编辑会话也算进可回收），" +
            "确认后执行 apk_workspace_gc {\"dryRun\":false,\"includeTemporary\":true,\"minAgeMinutes\":0,\"includeEditSessions\":true}；" +
            "只想释放会话不删快照：apk_session_release {\"workspaceId\":\"<id>\"}（或 {\"all\":true}）；" +
            "GC 仍删不动就逐个删：apk_workspace_delete {\"workspaceId\":\"<id>\"}（会连编辑会话一起删）；" +
            "owner/clientId 不传时按服务端默认身份（mcp-host/server-default）比较，服务端记录不再需要 includeUnknownOwner；" +
            "旧记录 owner=null 仍必须带 includeUnknownOwner=true；返回里的 blockedBy/nextActions 会点名具体 workspaceId/sessionId。"

    private fun directory(id: String) = File(store, ToolkitIo.id(id))
    private fun workspaceMetadata(id: String): JsonObject? = runCatching {
        val file = File(directory(id), "workspace.json")
        if (file.isFile) JsonParser().parse(file.readText()).asJsonObject else null
    }.getOrNull()
    private fun snapshot(id: String): File = File(directory(id), "base.apk").also {
        if (!it.isFile) throw workspaceExpired(id, "snapshot_missing")
    }
    private fun workspace(id: String): JsonObject {
        val file = File(directory(id), "workspace.json")
        if (!file.isFile) throw workspaceExpired(id, "workspace_metadata_missing")
        return JsonParser().parse(file.readText()).asJsonObject
    }

    private fun workspaceExpired(id: String, reason: String = "workspace_missing"): ToolkitException =
        expiryException("WORKSPACE_EXPIRED", id, null, reason, workspaceMetadata(id))
    private fun sessionExpired(workspace: String, session: String): ToolkitException {
        val info = workspaceMetadata(workspace)
        if (info == null) return workspaceExpired(workspace, "workspace_metadata_missing")
        val temporary = jsonBooleanOrNull(info, "temporary")
        val createdAt = jsonLongOrNull(info, "createdAt")
        val reason = if (ToolkitApkExpiry.isTtlExpired(temporary, createdAt)) "workspace_ttl_expired" else "session_missing_or_released"
        return expiryException("SESSION_EXPIRED", workspace, session, reason, info)
    }
    private fun expiryException(
        code: String,
        workspace: String,
        session: String?,
        reason: String,
        info: JsonObject?,
    ): ToolkitException {
        val details = ToolkitApkExpiry.details(
            code = code,
            workspaceId = workspace,
            sessionId = session,
            temporary = info?.let { jsonBooleanOrNull(it, "temporary") },
            createdAt = info?.let { jsonLongOrNull(it, "createdAt") },
            reason = reason,
            quotaUsed = workspaces().size,
        )
        return ToolkitException(code, details.message(), if (session == null) "workspaceId" else "editSessionId")
    }
    private fun jsonBooleanOrNull(obj: JsonObject, key: String): Boolean? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun jsonLongOrNull(obj: JsonObject, key: String): Long? =
        obj.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    private fun workspaces(): List<JsonObject> = store.listFiles()
        ?.filter { it.isDirectory && File(it, "workspace.json").isFile }
        ?.mapNotNull { workspaceMetadata(it.name) } ?: emptyList()
    private fun sessionDir(workspace: String, session: String): File {
        if (!File(directory(workspace), "workspace.json").isFile) throw workspaceExpired(workspace, "workspace_metadata_missing")
        val folder = File(File(directory(workspace), "sessions"), ToolkitIo.id(session))
        if (!File(folder, "session.json").isFile) throw sessionExpired(workspace, session)
        return folder
    }
    /**
     * P0-1 idempotent reopen: same sourcePath + sha256 + temporary/artifactType is the same
     * immutable snapshot. If it is still on disk (even past its TTL) renew it instead of
     * allocating another quota slot. Returns null when the old directory/base is gone/corrupt,
     * in which case the caller safely rebuilds a new snapshot.
     */
    private fun reuseWorkspace(
        sourcePath: String,
        sha256: String,
        temporary: Boolean,
        artifactType: String? = null,
    ): JsonObject? {
        val now = System.currentTimeMillis()
        val candidate = workspaces()
            .filter { it.tString("sourcePath", "") == sourcePath }
            .filter { it.tString("sha256", "") == sha256 }
            .filter { it.tBool("temporary", true) == temporary }
            .filter { artifactType == null || it.tString("artifactType", "") == artifactType }
            .filter { File(directory(it.tString("workspaceId")), "base.apk").isFile }
            .maxByOrNull { jsonLongOrNull(it, "createdAt") ?: 0L }
            ?: return null
        val id = candidate.tString("workspaceId")
        val previousCreatedAt = jsonLongOrNull(candidate, "createdAt")
        val expired = ToolkitApkExpiry.isTtlExpired(temporary, previousCreatedAt, now)
        val reopened = candidate.deepCopy().asJsonObject
        reopened.addProperty("reused", true)
        reopened.addProperty("reopened", true)
        reopened.addProperty("reuseReason", when {
            expired -> "expired_ttl_renewed"
            temporary -> "same_path_sha256_snapshot"
            else -> "saved_workspace_same_path_sha256"
        })
        reopened.addProperty("reusedAt", now)
        reopened.addProperty("lastAccessAt", now)
        if (previousCreatedAt != null) {
            reopened.addProperty("previousCreatedAt", previousCreatedAt)
            if (expired) reopened.addProperty("expiredAt", previousCreatedAt + ToolkitApkExpiry.TTL_MINUTES * 60_000L)
        }
        if (temporary) reopened.addProperty("createdAt", now)
        ToolkitIo.atomic(File(directory(id), "workspace.json")) { it.writeText(gson.toJson(reopened), Charsets.UTF_8) }
        currentWorkspace = id
        return reopened
    }

    /**
     * If the old directory/metadata still exists but the snapshot payload is gone, the caller
     * must rebuild; mark that rebuild so clients see reopened=true and a concrete reason.
     */
    private fun markRebuiltFromUnusableSnapshot(
        info: JsonObject,
        sourcePath: String,
        sha256: String,
        temporary: Boolean,
        artifactType: String? = null,
    ) {
        val prior = workspaces()
            .filter { it.tString("sourcePath", "") == sourcePath }
            .filter { it.tString("sha256", "") == sha256 }
            .filter { it.tBool("temporary", true) == temporary }
            .filter { artifactType == null || it.tString("artifactType", "") == artifactType }
            .filter { !File(directory(it.tString("workspaceId")), "base.apk").isFile }
            .maxByOrNull { jsonLongOrNull(it, "createdAt") ?: 0L }
            ?: return
        info.addProperty("reopened", true)
        info.addProperty("reused", false)
        info.addProperty("reuseReason", "previous_snapshot_missing_rebuilt")
        info.addProperty("previousWorkspaceId", prior.tString("workspaceId"))
        jsonLongOrNull(prior, "createdAt")?.let { info.addProperty("previousCreatedAt", it) }
    }

    private fun requiredSession(session: String) = session.also { require(it.isNotEmpty()) { "此操作必须在编辑会话中执行" } }
    private fun open(args: JsonObject): JsonObject {
        val path = args.tString("path"); val temporary = args.tBool("temporary", true)
        if(path == "aijs://current-apk" || path == "mt://current-apk") {
            val current = currentWorkspace ?: throw ToolkitException("NO_APK_CONTEXT", "当前没有 APK 上下文，请先在工具页或通过路径打开一个 APK")
            return workspace(current)
        }
        if (path.startsWith("aijs://workspace/") || path.startsWith("mt://workspace/")) {
            require(!temporary) { "已保存工作区需 temporary=false" }
            val info = workspace(path.substringAfterLast('/')); currentWorkspace = info.tString("workspaceId"); return info
        }
        val source = ToolkitIo.resolve(root, path)
        require(source.isFile && source.length() <= ToolkitIo.MAX_APK) { "需要工具目录内不超过 512 MiB 的 APK" }
        // §5.2：扩展名只作初筛，ZIP 魔数 + APK 结构才是判据；非 ZIP 在复制前就拦下，
        // 避免把 java.util.zip.ZipException 直接抛给调用方。
        val probe = runCatching { ToolkitDiscovery.probe(source) }.getOrNull()
        if (!source.name.endsWith(".apk", true)) {
            require(probe?.candidate == true) {
                "文件不是 APK 结构（扩展名 " + (source.extension.ifEmpty { "无" }) + "：" +
                    (probe?.let { describeProbe(it) } ?: "探测失败") + "）；先用 file_discover_apk 扫描"
            }
        } else {
            require(probe == null || probe.zipMagic) {
                "文件扩展名是 .apk 但不是 ZIP（" + (probe?.let { describeProbe(it) } ?: "未探测") +
                    "）；确认候选请用 file_discover_apk"
            }
        }
        val hash = ToolkitIo.sha(source)
        reuseWorkspace(source.path, hash, temporary)?.let { return it }
        var workspaceCount = workspaces().size
        val quotaHint = workspaceQuotaHint()
        var autoGc:JsonObject?=null
        if(workspaceCount >= 16 && args.tBool("autoGc", true)) {
            // P2-3：配额满时先按 apk_workspace_gc 的判定回收「过期/临时/无主」；活动引用、当前工作区、
            // 编辑会话仍由 inventory 保护。失败不假装清理成功，原样把错误带回。
            autoGc=runCatching {
                workspaceGc(ToolkitIo.json(
                    "dryRun" to false,
                    "includeTemporary" to true,
                    "includeUnknownOwner" to true,
                    "minAgeMinutes" to 0,
                    "expiredAfterMinutes" to 1440,
                    "allowUnknownAge" to true,
                ))
            }.getOrElse { failure ->
                ToolkitIo.json("attempted" to true,"error" to (failure.message ?: failure.javaClass.simpleName),
                    "note" to "autoGc 执行失败；未删除任何未知内容，下面仍按配额报错",
                    "nextActions" to listOf("apk_workspace_inventory 查看",
                        "apk_workspace_gc{dryRun:true,includeTemporary:true,minAgeMinutes:0,includeEditSessions:true} 先预览，确认后把 dryRun 改 false",
                        "旧记录 owner=null 再加 includeUnknownOwner=true；仍删不动用 apk_workspace_delete{workspaceId} 逐个删"))
            }
            workspaceCount = workspaces().size
        }
        require(workspaceCount < 16) {
            "最多保留 16 个 APK 工作区，当前 " + workspaceCount + " 个" + quotaHint +
                autoGc?.let { "；已尝试 autoGc=" + it.toString().take(1200) } .orEmpty()
        }
        val existingSize = workspaces().sumOf { it.get("size").asLong }
        require(existingSize + source.length() <= 1024L * 1024 * 1024) {
            "APK 快照总量超过 1 GiB（当前 " + existingSize + " 字节，新增 " + source.length() + " 字节）" + quotaHint
        }
        val id = ToolkitIo.fresh(); val folder = directory(id); require(folder.mkdir())
        try {
            val copy = File(folder, "base.apk"); source.inputStream().use { input -> copy.outputStream().use { output -> copyBounded(input, output, ToolkitIo.MAX_APK) } }
            require(ToolkitIo.sha(copy) == hash) { "读取时源 APK 已改变，请重试" }
            ZipFile(copy).use { zip ->
                require(zip.getEntry("AndroidManifest.xml") != null) { "APK 缺少 AndroidManifest.xml" }
                val names = HashSet<String>(); var count = 0
                zip.entries().asSequence().forEach { entry -> ToolkitIo.entry(entry.name); require(names.add(entry.name)) { "APK 含重复条目" }; require(++count <= 100000) { "APK 条目超过 100000" } }
            }
            val info = ToolkitIo.json("workspaceId" to id, "path" to "aijs://workspace/$id", "sourcePath" to source.path,
                "temporary" to temporary, "sha256" to hash, "size" to copy.length(), "createdAt" to System.currentTimeMillis())
            autoGc?.let { info.add("autoGc", it) }
            addClientMetadata(args, info)
            markRebuiltFromUnusableSnapshot(info, source.path, hash, temporary)
            val signature = ToolkitSignatures.read(copy)
            info.add("signatureSummary", ToolkitIo.json("available" to signature.get("available"), "signaturePresent" to signature.get("signaturePresent"),
                "schemes" to signature.get("schemes"), "certificateCount" to signature.getAsJsonArray("certificates").size()))
            File(folder, "workspace.json").writeText(gson.toJson(info)); currentWorkspace = id; return info
        } catch (error: Exception) { safeRemove(folder, store); throw error }
    }
    /** §5.2：制品发现的短提示，放进错误里，避免「只报不支持」而让调用方无处可去。 */
    private fun describeProbe(probe: ToolkitDiscovery.Probe): String = buildString {
        append("zipStatus=").append(probe.zipStatus)
        append("，zipMagic=").append(probe.zipMagic)
        append("，entryCount=").append(probe.entryCount)
        append("，AndroidManifest=").append(probe.hasAndroidManifest)
        append("，confidence=").append(probe.confidence)
    }

    /** §5.2：目录不是制品；用有界扫描列出候选，并给出下一步。 */
    private fun discoveryHintForDirectory(dir: File): String {
        val scan = runCatching {
            ToolkitDiscovery.scan(dir, ToolkitDiscovery.Limits(maxEntries = 500, maxProbes = 200, limit = 5), false)
        }.getOrNull()
        val candidates = scan?.candidates.orEmpty()
        val listed = if (candidates.isEmpty()) "该目录（含子目录，已按预算扫描）没有结构上像 APK 的文件"
        else "候选：" + candidates.take(5).joinToString("；") { it.path + "(" + it.confidence + ")" }
        val truncated = if (scan?.truncated == true) "；结果被预算截断（stopReason=" + scan.stopReason + "），可放宽 file_discover_apk 预算重扫" else ""
        return "路径是目录，不是可打开的制品。$listed$truncated"
    }

    private fun openArtifact(args:JsonObject):JsonObject {
        val source=ToolkitIo.resolve(root,args.tString("path"))
        if(source.isDirectory)throw IllegalArgumentException(discoveryHintForDirectory(source))
        require(source.isFile&&source.length()<=ToolkitIo.MAX_APK){ "需要不超过 512 MiB 的制品文件，当前路径：" + source.path }
        val suffix=source.extension.lowercase()
        // §5.2：先按结构判断 APK（覆盖 .apk.1/.bak/无扩展名），扩展名只作旧行为兼容。
        val probe=runCatching{ToolkitDiscovery.probe(source)}.getOrNull()
        if(suffix=="apk"||probe?.candidate==true)return open(args)
        require(suffix in setOf("dex","so","elf","apks","xapk","apkm")){"支持APK、DEX、ELF/SO及APKS/XAPK/APKM；该文件按结构不是 APK（"+(probe?.let{describeProbe(it)}?:"未探测")+"），确认候选请用 file_discover_apk"}
        val type=if(suffix in setOf("apks","xapk","apkm"))"apk-set" else if(suffix=="dex")"dex" else "elf"
        val hash=ToolkitIo.sha(source)
        reuseWorkspace(source.path,hash,args.tBool("temporary",true),type)?.let{return it}
        val workspaceCount = workspaces().size
        val existingSize = workspaces().sumOf { it.get("size").asLong }
        val quotaHint = workspaceQuotaHint()
        require(workspaceCount < 16) { "最多保留 16 个 APK 工作区，当前 " + workspaceCount + " 个" + quotaHint }
        require(existingSize + source.length() <= 1024L * 1024 * 1024) {
            "APK 快照总量超过 1 GiB（当前 " + existingSize + " 字节，新增 " + source.length() + " 字节）" + quotaHint
        }
        val id=ToolkitIo.fresh();val dir=directory(id);require(dir.mkdir())
        try{
            val original=File(dir,"original.bin")
            source.inputStream().use{input->original.outputStream().use{copyBounded(input,it,ToolkitIo.MAX_APK)}}
            require(ToolkitIo.sha(original)==hash){"读取期间源文件已改变"}
            val target=File(dir,"base.apk")
            if(type=="apk-set")require(original.renameTo(target)){"无法保留分包快照"} else {
                val bytes=original.inputStream().use{ToolkitIo.read(it)}
                if(type=="dex")ToolkitDex(bytes) else ToolkitElf(bytes)
                ZipOutputStream(target.outputStream()).use{zip->zip.putNextEntry(ZipEntry(if(type=="dex")"classes.dex" else "lib/standalone.so"));zip.write(bytes);zip.closeEntry()};original.delete()
            }
            val info=ToolkitIo.json("workspaceId" to id,"artifactType" to type,"syntheticContainer" to (type!="apk-set"),
                "sourcePath" to source.path,"sha256" to hash,"analysisSha256" to ToolkitIo.sha(target),"size" to target.length(),"sourceSize" to source.length(),"createdAt" to System.currentTimeMillis(),
                "temporary" to args.tBool("temporary",true),"path" to "aijs://workspace/$id")
            addClientMetadata(args, info)
            markRebuiltFromUnusableSnapshot(info, source.path, hash, args.tBool("temporary",true), type)
            File(dir,"workspace.json").writeText(info.toString(),Charsets.UTF_8)
            if(type=="apk-set")info.addProperty("memberCount",setMembers(id).size)
            currentWorkspace=id;return info
        }catch(e:Exception){safeRemove(dir,store);throw e}
    }
    private fun setMembers(workspace:String):List<JsonObject>{
        require(workspace(workspace).tString("artifactType","")=="apk-set"){"不是分包工作区"}
        return ZipFile(snapshot(workspace)).use{zip->
            val result=ArrayList<JsonObject>();val names=HashSet<String>()
            zip.entries().asSequence().filter{!it.isDirectory&&it.name.endsWith(".apk",true)}.forEach{entry->
                ToolkitIo.entry(entry.name);require(names.add(entry.name)&&result.size<256){"分包含重复成员或超过256个"}
                require(entry.size in 0..ToolkitIo.MAX_APK){"分包成员过大"}
                result.add(ToolkitIo.json("member" to entry.name,"size" to entry.size,"compressedSize" to entry.compressedSize,"crc" to entry.crc,
                    "baseCandidate" to (File(entry.name).name.lowercase() in setOf("base.apk","base-master.apk")),"requiresGroupValidation" to true))
            };require(result.isNotEmpty()){"归档没有APK成员"};result
        }
    }
    private fun openSetMember(workspace:String,args:JsonObject):JsonObject{
        val member=args.tString("member");require(setMembers(workspace).any{it.tString("member")==member}){"分包成员不存在"}
        // A private child snapshot retains explicit provenance; no extract-to-user-directory side effect.
        val temp=File(directory(workspace),"member-${ToolkitIo.fresh()}.apk")
        try{
            ZipFile(snapshot(workspace)).use{zip->zip.getInputStream(zip.getEntry(member)).use{input->temp.outputStream().use{copyBounded(input,it,ToolkitIo.MAX_APK)}}}
            val visible=File(root,".aijs-member-${ToolkitIo.fresh()}.apk")
            try{temp.inputStream().use{input->visible.outputStream().use{copyBounded(input,it,ToolkitIo.MAX_APK)}}
                val info=open(ToolkitIo.json("path" to visible.path,"temporary" to true))
                info.addProperty("containerWorkspaceId",workspace);info.addProperty("containerMember",member);info.addProperty("sourcePath",workspace(workspace).tString("sourcePath")+"!"+member)
                ToolkitIo.atomic(File(directory(info.tString("workspaceId")),"workspace.json")){it.writeText(info.toString(),Charsets.UTF_8)};return info
            }finally{visible.delete()}
        }finally{temp.delete()}
    }
    private fun exportArtifact(workspace:String,session:String,args:JsonObject):JsonObject{
        val type=workspace(workspace).tString("artifactType","");require(type in setOf("dex","elf")){"仅独立DEX/ELF使用artifact_export"}
        val entry=if(type=="dex")"classes.dex" else "lib/standalone.so";val bytes=raw(workspace,session,entry)
        if(type=="dex")ToolkitDex(bytes)else ToolkitElf(bytes)
        val organized=outputOrganizer.organize(ToolkitIo.resolve(root,args.tString("path")),args)
        val output=organized.path;require(output.path!=workspace(workspace).tString("sourcePath")){"不能覆盖源文件"}
        require(!output.exists()||args.tBool("overwrite")){"输出已存在"};ToolkitIo.atomic(output){it.writeBytes(bytes)}
        return ToolkitIo.json("path" to output.path,"sha256" to ToolkitIo.sha(output),"sourceUnchanged" to true,"artifactType" to type).withOrganized(organized)
    }
    private fun decompile(workspace:String,session:String,args:JsonObject):JsonObject{
        val backend=runtime?:throw ToolkitException("COMPONENT_UNAVAILABLE","反编译组件后端未接入")
        val locator=args.tString("locator");val (_,dex)=dexFor(workspace,session,locator,args.tString("dexEntry",""));dex.find(locator)
        val input=if(session.isEmpty())snapshot(workspace)else File(sessionDir(workspace,session),"decompile-${ToolkitIo.fresh()}.apk")
        // JADX 组件的 maxChars 默认只有 16 KiB；调用方不传时一次性只返回 16 KiB，
        // 结果落盘也就只有这一段。这里在工具边界补足组件允许的最大窗口（200000），
        // offset/nextOffset/hasMore 分页仍由组件和 apk_job_result 保持。
        val effectiveArgs=args.deepCopy()
        if(!effectiveArgs.has("maxChars")) effectiveArgs.addProperty("maxChars",200000)
        try{if(session.isNotEmpty())writeUnsigned(workspace,session,entries(workspace,session),input)
            val decompiled = backend.decompile(input,locator,effectiveArgs).apply{addProperty("revision",revision(workspace,session))}
            return writeDecompiledOutput(outputOrganizer, root, args, decompiled)
        }finally{if(session.isNotEmpty())input.delete()}
    }
    private fun listAvailable(args: JsonObject): JsonObject {
        val prefix = args.tString("prefix", ""); var visited = 0; var truncated = false; val items = ArrayList<JsonObject>()
        currentWorkspace?.let { id -> if("aijs://current-apk".startsWith(prefix)) items.add(ToolkitIo.json("path" to "aijs://current-apk",
            "name" to File(workspace(id).tString("sourcePath")).name, "kind" to "current_apk", "workspaceId" to id)) }
        fun scan(folder: File, depth: Int) {
            if (depth > 16 || visited >= 20000 || items.size >= 10000) { truncated = true; return }
            for (file in folder.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                if (++visited > 20000) { truncated = true; return }
                if (file.canonicalFile != file.absoluteFile.normalize()) continue
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (file.isDirectory && !file.name.startsWith('.')) scan(file, depth + 1)
                else if (file.isFile && file.name.endsWith(".apk", true) && relative.startsWith(prefix)) items.add(ToolkitIo.json("path" to relative, "name" to file.name, "size" to file.length()))
            }
        }; scan(root.canonicalFile, 0)
        return page("", "", items, args.tInt("limit", 50, 1, 200), truncated, producer = "apk_list_available_apks")
    }
    private fun createSession(workspace: String): JsonObject {
        val parent = File(directory(workspace), "sessions"); parent.mkdirs()
        require((parent.listFiles()?.size ?: 0) < 8) { "单个工作区最多 8 个编辑会话" }
        val id = ToolkitIo.fresh(); val folder = File(parent, id); require(folder.mkdir()); require(File(folder, "entries").mkdir())
        val info = ToolkitIo.json("workspaceId" to workspace, "editSessionId" to id, "revision" to ToolkitIo.fresh(), "deleted" to emptyList<String>(),
            "changedEntries" to emptyMap<String,String>(), "classChanges" to emptyMap<String,String>(), "classChangesComplete" to true)
        File(folder, "session.json").writeText(gson.toJson(info)); return info
    }
    private fun sessionInfo(workspace: String, session: String): JsonObject {
        val folder = sessionDir(workspace, session)
        return try {
            JsonParser().parse(File(folder, "session.json").readText()).asJsonObject
        } catch (error: ToolkitException) {
            throw error
        } catch (error: Exception) {
            // File can vanish between the check and the read (GC/release race); still a session
            // expiry, never an INVALID_ARGUMENT / parameter error.
            throw sessionExpired(workspace, session)
        }
    }
    private fun revision(workspace: String, session: String): String = if (workspace.isEmpty()) "catalog" else if (session.isEmpty()) workspace(workspace).tString("sha256") else sessionInfo(workspace, session).tString("revision")
    /** #4：成功返回统一补当前 revision；revisionChanged 表示本次调用后是否与调用前不同（只读工具为 false）。 */
    private fun JsonObject.withRevision(workspace: String, session: String, before: String): JsonObject {
        val current = revision(workspace, session)
        addProperty("revision", current)
        addProperty("revisionChanged", current != before)
        return this
    }
    private fun entries(workspace: String, session: String): List<String> {
        val names = ZipFile(snapshot(workspace)).use { zip -> zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toMutableSet() }
        if (session.isNotEmpty()) {
            val info = sessionInfo(workspace, session); info.getAsJsonArray("deleted").forEach { names.remove(it.asString) }
            info.getAsJsonObject("changedEntries").keySet().forEach { names.add(it) }
        }; return names.sorted()
    }
    private fun raw(workspace: String, session: String, entry: String, max: Int = ToolkitIo.MAX_ENTRY): ByteArray {
        ToolkitIo.entry(entry)
        if (session.isNotEmpty()) {
            val info = sessionInfo(workspace, session)
            require(info.getAsJsonArray("deleted").none { it.asString == entry }) { "条目已删除" }
            info.getAsJsonObject("changedEntries").get(entry)?.asString?.let { name -> return File(File(sessionDir(workspace, session), "entries"), ToolkitIo.id(name)).inputStream().use { ToolkitIo.read(it, max) } }
        }
        return ZipFile(snapshot(workspace)).use { zip ->
            val item = zip.getEntry(entry) ?: error("APK 条目不存在：$entry"); require(!item.isDirectory && item.size <= max) { "APK 条目过大或不是普通文件" }
            ToolkitIo.read(zip.getInputStream(item), max)
        }
    }
    private fun write(workspace: String, session: String, changes: Map<String,ByteArray>, deletions: Set<String> = emptySet(),
                      streaming: Map<String, (File) -> Unit> = emptyMap(), classChange: Pair<String, String>? = null) {
        val folder = sessionDir(workspace, session); val info = sessionInfo(workspace, session)
        val changed = info.getAsJsonObject("changedEntries"); val deleted = info.getAsJsonArray("deleted").map { it.asString }.toMutableSet()
        val created = ArrayList<File>(); val superseded = ArrayList<File>()
        try {
            require(changes.keys.intersect(streaming.keys).isEmpty()) { "写入目标重复" }
            val writers = linkedMapOf<String, (File) -> Unit>()
            changes.forEach { (path, bytes) -> require(bytes.size <= ToolkitIo.MAX_ENTRY); writers[path] = { it.writeBytes(bytes) } }
            writers.putAll(streaming)
            writers.forEach { (path, writer) ->
                ToolkitIo.entry(path)
                val file = File(File(folder, "entries"), ToolkitIo.fresh()); created.add(file); writer(file)
                require(file.isFile && file.length() <= ToolkitIo.MAX_APK) { "编辑条目过大或写入失败" }
                changed.get(path)?.asString?.let { superseded.add(File(file.parentFile, ToolkitIo.id(it))) }
                changed.addProperty(path, file.name); deleted.remove(path)
            }
            deletions.forEach { path -> ToolkitIo.entry(path); changed.remove(path)?.asString?.let { superseded.add(File(File(folder, "entries"), ToolkitIo.id(it))) }; deleted.add(path) }
            if(classChange != null) {
                val classes = info.getAsJsonObject("classChanges") ?: JsonObject().also { info.add("classChanges", it); info.addProperty("classChangesComplete", false) }
                val (owner, action) = classChange; val prior = classes.get(owner)?.asString
                when {
                    action == "deleted" && prior == "added" -> classes.remove(owner)
                    prior == "added" -> classes.addProperty(owner, "added")
                    action == "added" && prior == "deleted" -> classes.addProperty(owner, "changed")
                    else -> classes.addProperty(owner, action)
                }
            }
            require(changed.size() <= 2000 && changed.entrySet().sumOf { File(File(folder, "entries"), ToolkitIo.id(it.value.asString)).length() } <= 512L * 1024 * 1024) { "编辑会话超过容量限制" }
            info.add("deleted", gson.toJsonTree(deleted)); info.addProperty("revision", ToolkitIo.fresh())
            ToolkitIo.atomic(File(folder, "session.json")) { it.writeText(gson.toJson(info)) }
            superseded.forEach { it.delete() }
        } catch (error: Exception) { created.forEach { it.delete() }; throw error }
    }
    private fun resourceTable(workspace: String, session: String): ToolkitResources = ToolkitResources(raw(workspace, session, "resources.arsc"))
    private fun optionalResources(workspace: String, session: String): ToolkitResources? = if (entries(workspace, session).contains("resources.arsc")) resourceTable(workspace, session) else null
    /**
     * dex 接口族调用包装：把 {@link ToolkitDexInterface.nextActionsFor} 的可执行建议
     * 以 `details={...}` 附加到消息里，由 McpToolkitProvider 提升为 error.nextActions（MCP 更新建议 P0-1 的一级字段）。
     */
    /** 给 dxi1 游标补内部作用域/修订，让同工具 cursor 在 workspace/session 或 revision 变化后失效。 */
    private fun scopedDexPagingArgs(workspace: String, session: String, args: JsonObject): JsonObject =
        args.deepCopy().apply {
            addProperty("_scope", workspace + "/" + session)
            addProperty("_revision", revision(workspace, session))
        }
    private inline fun dexInterfaceValue(workspace: String, session: String,
                                         block: (ToolkitDexInterface) -> JsonObject): JsonObject = try {
        block(ToolkitDexInterface(dexes(workspace, session).toList()))
    } catch (error: ToolkitException) {
        val actions = ToolkitDexInterface.nextActionsFor(error.code)
        if (actions.isEmpty()) throw error
        throw ToolkitException(error.code,
            (error.message ?: "") + " details=" + ToolkitIo.json("nextActions" to actions).toString(),
            error.argument)
    }

    private fun dexes(workspace: String, session: String): Sequence<Pair<String,ToolkitDex>> = entries(workspace, session).asSequence().filter { ToolkitApkTargets.isDex(it) }.map { it to ToolkitDex(raw(workspace, session, it)) }
    private fun dexFor(workspace: String, session: String, locator: String, requestedDex:String=""): Pair<String,ToolkitDex> {
        val found = dexes(workspace, session).filter { (entry, _) -> requestedDex.isEmpty() || requestedDex==entry }.filter { (_, dex) -> dex.classes.any { it.type == dex.type(locator) } }.toList()
        if (found.isEmpty()) {
            throw ToolkitException(
                "LOCATOR_NOT_FOUND",
                "DEX 类不存在（收到：$locator）。这是定位符/参数问题，不是反编译失败；" +
                    "正确形状示例：dex_class:Lcom/example/Foo;、dex_method:Lcom/example/Foo;->bar()V；" +
                    "跨 DEX 同名类请使用搜索结果里的 uniqueLocator（dex_entry:classes.dex!dex_class:...）。$locatorHint",
                "locator",
            )
        }
        if (found.size > 1) {
            throw ToolkitException(
                "LOCATOR_AMBIGUOUS",
                "多个 DEX 含同名类（" + found.joinToString { it.first } + "），无法仅凭裸 locator 消歧；" +
                    "请传 dexEntry，或使用搜索结果里的 uniqueLocator（dex_entry:classes.dex!dex_class:...）。$locatorHint",
                "locator",
            )
        }
        return found.single()
    }
    private fun entryLocator(locator: String): String {
        require(locator.startsWith("zip_entry:")) { "需要带命名空间前缀的 locator（收到：$locator）。$locatorHint" }
        return ToolkitIo.entry(locator.removePrefix("zip_entry:")).also { require(!it.endsWith('/')) { "目录不是普通文件目标" } }
    }
    private fun text(workspace: String, session: String, locator: String): String = when {
        locator.startsWith("dex_") -> dexFor(workspace, session, locator).second.render(locator)
        locator.startsWith("axml:") -> {
            val bytes = raw(workspace, session, ToolkitApkTargets.axmlPath(locator))
            require(ToolkitResources.isBinaryXml(bytes)) { "目标不是 AXML" }
            ToolkitResources.decodeXml(bytes, optionalResources(workspace, session))
        }
        locator.startsWith("resource:") -> resourceTable(workspace, session).let { it.xml(it.find(locator, "")) }
        else -> {
            val bytes = raw(workspace, session, entryLocator(locator), ToolkitIo.MAX_TEXT)
            if (ToolkitResources.isBinaryXml(bytes)) ToolkitResources.decodeXml(bytes, optionalResources(workspace, session)) else ToolkitIo.text(bytes)
        }
    }
    private fun readText(workspace: String, session: String, args: JsonObject): JsonObject {
        val locator = args.tString("locator")
        var truncated = false
        val content = if(locator.startsWith("zip_entry:")) {
            val path = entryLocator(locator)
            val size = entryLength(workspace, session, path)
            if(size > ToolkitIo.MAX_TEXT && !ToolkitResources.isBinaryXml(prefix(workspace, session, path, 8))) {
                truncated = true
                val bytes = prefix(workspace, session, path, ToolkitIo.MAX_TEXT)
                var decoded: String? = null
                for(trim in 0..3) {
                    if(decoded != null) break
                    decoded = runCatching { ToolkitIo.text(bytes.copyOf(bytes.size - trim)) }.getOrNull()
                }
                decoded ?: throw IllegalArgumentException("条目不是有效 UTF-8 文本，请使用 apk_read_bytes")
            } else text(workspace, session, locator)
        } else text(workspace, session, locator)
        return ToolkitTextWindow.read(content, args).apply {
            addProperty("locator", locator)
            addProperty("editable", !truncated && !locator.startsWith("dex_field:"))
            addProperty("textSourceKind", if(truncated) "prefix" else "full_source")
            addProperty("sourceTruncated", truncated)
            if(truncated) addProperty("editableReason", "TEXT_SOURCE_TRUNCATED")
        }
    }
    private fun readBytes(workspace: String, session: String, args: JsonObject): JsonObject {
        val locator = args.tString("locator"); val entry = entryLocator(locator)
        val size = entryLength(workspace, session, entry); require(size <= ToolkitIo.MAX_APK) { "原始条目读取最大512MiB" }
        val offset = args.tInt("byteOffset", 0, 0, size.toInt())
        val count = minOf(args.tInt("maxBytes", 256, 1, 65536), size.toInt() - offset)
        val value = ByteArray(count)
        val deadline = System.nanoTime() + 8_000_000_000L
        streamEntry(workspace, session, entry) { input ->
            var remaining = offset.toLong()
            while(remaining > 0) {
                require(System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) { "原始字节定位超过预算，请使用较小偏移或导出条目读取" }
                val skipped = input.skip(minOf(remaining, 1024 * 1024L))
                if(skipped > 0) remaining -= skipped else { require(input.read() >= 0) { "条目提前结束" }; remaining-- }
            }
            var read = 0
            while(read < count) { val next = input.read(value, read, count - read); require(next >= 0) { "条目提前结束" }; read += next }
        }
        val stamp = if(session.isEmpty()) null else sessionInfo(workspace, session).getAsJsonObject("changedEntries").get(entry)?.asString
        val version = ToolkitIo.version(("${stamp ?: workspace(workspace).tString("sha256")}:$entry:$size").toByteArray(Charsets.UTF_8))
        return ToolkitIo.json("locator" to locator, "targetVersion" to version,
            "byteWindow" to ToolkitIo.json("byteOffset" to offset, "hex" to ToolkitIo.hex(value), "byteCount" to count),
            "nextByteOffset" to offset + count, "hasMore" to (offset + count < size), "size" to size)
    }
    private fun list(workspace: String, session: String, args: JsonObject): JsonObject {
        val prefix = args.tString("prefix", ""); val view = args.tString("view", "zip_entries")
        val items = when (view) {
            "zip_entries" -> entries(workspace, session).filter { it.startsWith(prefix) }.map { ToolkitIo.json("locator" to "zip_entry:$it", "path" to it) }
            "dex_classes" -> dexes(workspace, session).flatMap { (entry, dex) -> dex.classes.asSequence().filter { it.type.startsWith(ToolkitDex.ownerPrefix(prefix)) }.map { ToolkitIo.json("locator" to "dex_class:${it.type}", "type" to it.type, "dexEntry" to entry) } }.toList()
            "resource_table" -> resourceTable(workspace, session).let { resources -> resources.entries().filter {
                it.name.startsWith(prefix) || "${it.typeName}/${it.name}".startsWith(prefix) ||
                    resources.locator(it).removePrefix("resource:").startsWith(prefix.removePrefix("resource:"), ignoreCase = true)
            }.map { resources.item(it) } }
            else -> throw IllegalArgumentException("view 无效")
        }; return page(workspace, session, items, args.tInt("limit", 200, 1, 1000), limitMax = 1000, producer = "apk_list")
    }
    private fun page(workspace: String, session: String, items: List<JsonObject>, limit: Int, truncated: Boolean = false,
                     kind: String = "items", metadata: JsonObject = JsonObject(), limitMax: Int = 200,
                     extraActions: JsonArray = JsonArray(), producer: String = "apk_page"): JsonObject {
        val key = if (items.size > limit) ToolkitIo.fresh() else ""
        if (key.isNotEmpty()) {
            require(items.size <= 80000) { "结果过大，请缩小 prefix 或搜索范围" }
            while (pages.isNotEmpty() && (pages.size >= 16 || pages.values.sumOf { it.items.size } + items.size > 80000)) pages.remove(pages.keys.first())
            pages[key] = Page(workspace, session, revision(workspace, session), items, limit, kind, metadata, truncated, limitMax, producer)
        }
        return paged(workspace, session, items.take(limit), key, items.size, truncated, kind, metadata, limitMax, extraActions, producer)
    }
    private fun paged(workspace: String, session: String, items: List<JsonObject>, key: String, total: Int, truncated: Boolean,
                      kind: String = "items", metadata: JsonObject = JsonObject(), limitMax: Int = 200,
                      extraActions: JsonArray = JsonArray(), producer: String = "apk_page"): JsonObject {
        val actions = JsonArray()
        if(key.isNotEmpty()) actions.add(action("apk_continue", ToolkitIo.json("workspaceId" to workspace, "editSessionId" to session, "nextCursor" to key, "limit" to 200)))
        extraActions.forEach { actions.add(it) }
        val data = ToolkitIo.json("workspaceId" to workspace, "editSessionId" to session, "items" to items,
            "nextCursor" to key, "hasMore" to key.isNotEmpty(), "total" to total, "truncated" to truncated,
            "cursorTool" to producer,
            "cursorSemantics" to "nextCursor 仅在 5 分钟内、同 workspaceId/editSessionId、同 revision 有效；APK 分页游标请用 apk_continue；apk_dex_list_classes/list_methods 的 nextCursor 属于原工具（回传 cursor），不能混用。",
            "pagination" to ToolkitIo.json("hasMore" to key.isNotEmpty(), "nextCursor" to key.takeIf { it.isNotEmpty() },
                "returnedCount" to items.size, "limitMax" to limitMax, "totalAvailableCount" to if(truncated) null else total))
        if(kind == "search") data.add("hits", data.get("items"))
        if(kind == "refs") data.add("refs", data.get("items"))
        metadata.entrySet().forEach { (name, value) -> data.add(name, value) }
        return ToolkitIo.ok(data, actions)
    }
    private fun continuePage(args: JsonObject): JsonObject {
        val key = args.tString("nextCursor").trim()
        if (key.isEmpty()) throw ToolkitException(
            "INVALID_CURSOR", "nextCursor 不能为空：请传产生它的工具返回的 nextCursor；不要自己构造。", "nextCursor",
        )
        val page = pages[key] ?: throw ToolkitException("INVALID_CURSOR", cursorOwnerMessage(key), "nextCursor")
        if (page.workspace != args.tString("workspaceId", "") || page.session != args.tString("editSessionId", "")) {
            throw ToolkitException(
                "INVALID_CURSOR",
                "分页游标属于 " + page.producer + "，不属于当前 workspaceId/editSessionId；请用 " + page.producer +
                    " 在原来的工作区/会话重新查询，再用它返回的 nextCursor 调 apk_continue。",
                "nextCursor",
            )
        }
        if (System.currentTimeMillis() - page.at >= 300000 || page.revision != revision(page.workspace, page.session)) {
            throw ToolkitException(
                "INVALID_CURSOR",
                "分页游标属于 " + page.producer + "，但工作区内容已变更或游标超过 5 分钟；请用 " + page.producer +
                    " 按原参数重新查询第一页，不要复用旧 nextCursor。",
                "nextCursor",
            )
        }
        val next = minOf(page.offset + args.tInt("limit", 200, 1, page.limitMax), page.items.size); val values = page.items.subList(page.offset, next)
        page.offset = next; if (next == page.items.size) pages.remove(key)
        return paged(page.workspace, page.session, values, if(next < page.items.size) key else "", page.items.size, page.truncated,
            page.kind, page.metadata, page.limitMax, producer = page.producer)
    }
    /** 跨工具游标的最短可执行提示：能识别 dxi1/jobs1/scan1 前缀就点名归属，不能识别也说明边界。 */
    private fun cursorOwnerMessage(key: String): String {
        val owner = when {
            key.startsWith("dxi1:classes:") -> "apk_dex_list_classes"
            key.startsWith("dxi1:methods:") -> "apk_dex_list_methods"
            key.startsWith("jobs1-") -> "apk_job_list"
            key.startsWith("scan1-") -> "search_scripts / 扫描类工具"
            else -> null
        }
        if (owner != null) {
            return "该游标属于 " + owner + "，不能用于 apk_continue；请把 nextCursor 原样传给 " + owner +
                " 的 cursor 字段（同工具、同过滤条件、同 workspace/session/revision 才有效）。"
        }
        return "分页游标不存在或已过期：APK 分页 nextCursor 只在 5 分钟内、同 workspaceId/editSessionId、同 revision 有效，" +
            "且只能用于产生它的工具；apk_dex_list_classes/list_methods 的 nextCursor 请回传给原工具的 cursor，不能传给 apk_continue，反之亦然。"
    }
    private fun action(name: String, args: JsonObject) = ToolkitIo.json("tool" to name, "arguments" to args)
    private fun search(workspace: String, session: String, args: JsonObject): JsonObject {
        val startedAt = System.nanoTime()
        val result = ToolkitApkSearch(entries(workspace, session), { raw(workspace, session, it) },
            { optionalResources(workspace, session) }, { dexes(workspace, session) }).search(args)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val diagnostics = ToolkitApkSearchDiagnostics.classify(
            partial = result.partial,
            elapsedMs = elapsedMs,
            hitCount = result.hits.size,
            metadata = result.metadata,
            args = args,
        )
        diagnostics.fields.entrySet().forEach { (name, value) -> result.metadata.add(name, value) }
        // B16：axml: 是搜索结果的预览 locator；真正读取 AXML 条目要用 zip_entry:。
        // locator 保持原语义，只增加可直接喂给 apk_read_text/apk_read_bytes 的 readLocator。
        result.hits.forEach { hit ->
            val locator = hit.tString("locator")
            if (locator.startsWith("axml:")) {
                val entry = locator.removePrefix("axml:")
                hit.addProperty("readLocator", "zip_entry:" + entry)
                hit.addProperty("locatorNote", "locator 是 AXML 预览定位符，不能直接用于 apk_read_text/apk_read_bytes；请改用 readLocator（zip_entry:$entry），或把 locator 换成 zip_entry:$entry。")
            } else {
                hit.addProperty("readLocator", locator)
            }
        }
        return page(workspace, session, result.hits, args.tInt("limit", 50, 1, 200), result.partial, "search",
            result.metadata, extraActions = diagnostics.nextActions, producer = "apk_search")
    }
    private fun readResources(workspace: String, session: String, args: JsonObject): JsonObject {
        return ToolkitResourceReader(resourceTable(workspace, session)).read(args)
    }
    private fun editResources(workspace: String, session: String, args: JsonObject, add: Boolean): JsonObject {
        val resources = resourceTable(workspace, session); val changes = args.getAsJsonArray(if(add) "adds" else "edits") ?: error("缺少编辑列表"); require(changes.size() in 1..200)
        val results = ArrayList<JsonObject>()
        changes.forEach { value ->
            val edit = value.asJsonObject
            if (!add) {
                val entry = resources.find(edit.tString("locator"), edit.tString("variant", "")); require(ToolkitIo.version(entry.bytes) == edit.tString("targetVersion")) { "资源已改变，请重新读取" }
                resources.write(entry, edit.tString("valueXml")); results.add(resources.item(entry))
            } else {
                val values = edit.getAsJsonArray("values") ?: error("缺少 values"); require(values.size() in 1..200)
                val kind = edit.tString("kind"); require(kind in listOf("resource", "variant"))
                val existing = if(kind == "variant") resources.find(edit.tString("locator"), resources.entries().firstOrNull { resources.locator(it) == edit.tString("locator") }?.resConfig?.qualifiers ?: "") else null
                val descriptions = values.map { ToolkitResources.describe(it.asJsonObject.tString("valueXml")) }
                require(descriptions.distinct().size == 1) { "同一新增资源的所有配置必须使用相同 name/type" }
                val (newName, newType) = descriptions.first()
                if(existing != null) require(existing.name == newName && existing.typeName == newType) { "新增配置的 name/type 与目标不一致" }
                else {
                    val packageId = ToolkitResources.parseId(edit.tString("packageId"))
                    require(resources.entries().none { it.resourceId ushr 24 == packageId && it.name == newName && it.typeName == newType }) { "资源名称已存在，请使用 kind=variant" }
                }
                values.forEach { item ->
                    val v = item.asJsonObject; val entry = resources.add(if(existing != null) "0x%02x".format(existing.resourceId ushr 24) else edit.tString("packageId"), v.tString("variant", ""), v.tString("valueXml"))
                    if(existing != null) require(entry.resourceId == existing.resourceId) { "新增配置的资源名/类型必须与定位符一致" }; results.add(resources.item(entry))
                }
            }
        }
        write(workspace, session, mapOf("resources.arsc" to resources.bytes())); return ToolkitIo.json("items" to results, "results" to results, "savedToSession" to true)
    }
    private fun editText(workspace: String, session: String, args: JsonObject): JsonObject {
        val beforeRevision = revision(workspace, session)
        val locator = args.tString("locator"); val version = args.tString("targetVersion")
        require(!locator.startsWith("dex_field:")) { "dex_field 只读" }
        val names = entries(workspace, session)
        val missing = version == "missing"
        val dexTarget = locator.startsWith("dex_")
        val entry = if(dexTarget) null else if(locator.startsWith("axml:")) ToolkitApkTargets.axmlPath(locator) else entryLocator(locator)
        val present = if(dexTarget) dexes(workspace, session).any { it.second.classes.any { c -> c.type == it.second.type(locator) } } else entry in names
        if(missing) require(!present && !locator.startsWith("dex_method:")) { "目标已存在或不支持新建此类型" }
        else require(present) { "目标不存在，请先重新读取" }
        val old = if(missing) "" else text(workspace, session, locator)
        require(missing || ToolkitIo.version(ToolkitTextEdits.normalize(old).toByteArray(Charsets.UTF_8)) == version) { "内容已改变，请重新读取" }
        val edits = args.getAsJsonArray("edits") ?: error("缺少 edits")
        if(missing) require(edits.size() == 1 && edits[0].asJsonObject.tString("mode") == "write_target") { "新建目标需要单个 write_target" }
        val originalBytes = if(entry != null && !missing) raw(workspace, session, entry) else null
        if(originalBytes != null && !ToolkitResources.isBinaryXml(originalBytes)) require(originalBytes.size <= ToolkitIo.MAX_TEXT) { "普通文本编辑最大10MiB，不能编辑截断预览" }
        val axml = locator.startsWith("axml:") || originalBytes?.let { ToolkitResources.isBinaryXml(it) } == true
        val content = ToolkitTextEdits.apply(old, edits, preserveLineEndings = !dexTarget && !axml)
        val changes: Map<String, ByteArray>
        val savedText: String
        if(dexTarget) {
            val selected = if(missing) dexes(workspace, session).firstOrNull() ?: ("classes.dex" to ToolkitDex.empty()) else dexFor(workspace, session, locator)
            val bytes = selected.second.update(locator, content, sessionDir(workspace, session), allowCreate = missing)
            savedText = ToolkitDex(bytes).render(locator)
            changes = mapOf(selected.first to bytes)
        } else {
            require(entry != null && entry != "resources.arsc" && !ToolkitApkTargets.isDex(entry)) { "资源表/DEX 请使用语义编辑接口" }
            val bytes = if(axml) ToolkitResources.encodeXml(content, optionalResources(workspace, session)) else content.toByteArray(Charsets.UTF_8)
            savedText = if(axml) ToolkitResources.decodeXml(bytes, optionalResources(workspace, session)) else content
            changes = mapOf(entry to bytes)
        }
        val next = ToolkitIo.version(ToolkitTextEdits.normalize(savedText).toByteArray(Charsets.UTF_8))
        write(workspace, session, changes, classChange = if(dexTarget) locator.substringAfter(':').substringBefore("->") to if(missing) "added" else "changed" else null)
        return ToolkitIo.json("workspaceId" to workspace, "editSessionId" to session, "locator" to locator,
            "targetVersion" to next, "newTargetVersion" to next, "savedToSession" to true)
            .withRevision(workspace, session, beforeRevision)
    }
    private fun deleteTarget(workspace: String, session: String, args: JsonObject): JsonObject {
        val beforeRevision = revision(workspace, session)
        val locator = args.tString("locator")
        if(locator.startsWith("dex_")) {
            val (entry, dex) = dexFor(workspace, session, locator)
            write(workspace, session, mapOf(entry to dex.update(locator, null, sessionDir(workspace, session))),
                classChange = dex.type(locator) to if(locator.startsWith("dex_class:")) "deleted" else "changed")
        }
        else {
            val entry = if(locator.startsWith("axml:")) ToolkitApkTargets.axmlPath(locator) else entryLocator(locator)
            require(entry in entries(workspace, session)) { "目标不存在或已删除" }
            ToolkitApkTargets.ordinary(entry, allowAxml = true)
            if(locator.startsWith("axml:")) require(ToolkitResources.isBinaryXml(raw(workspace, session, entry))) { "目标不是 AXML" }
            write(workspace, session, emptyMap(), setOf(entry))
        }
        return ToolkitIo.json("deleted" to true, "locator" to locator)
            .withRevision(workspace, session, beforeRevision)
    }
    private fun patchBytes(workspace: String, session: String, args: JsonObject): JsonObject {
        val beforeRevision = revision(workspace, session)
        val entry = entryLocator(args.tString("locator")); val bytes = raw(workspace, session, entry)
        ToolkitApkTargets.ordinary(entry, bytes)
        val patches = args.getAsJsonArray("patches") ?: error("缺少 patches"); require(patches.size() in 1..200)
        val ranges = ArrayList<IntRange>()
        patches.forEach { raw -> val patch = raw.asJsonObject; val before = ToolkitIo.unhex(patch.tString("expectedHex")); val after = ToolkitIo.unhex(patch.tString("replacementHex")); val start = patch.tInt("byteOffset", 0, 0, bytes.size)
            require(before.isNotEmpty() && before.size == after.size && before.size <= bytes.size - start) { "原始字节补丁必须等长且不越界" }
            val range = start until start + before.size; require(ranges.none { it.first <= range.last && range.first <= it.last }) { "补丁范围重叠" }; ranges.add(range)
            require(bytes.copyOfRange(start, start + before.size).contentEquals(before)) { "原始字节不匹配" }; after.copyInto(bytes, start)
        }; write(workspace, session, mapOf(entry to bytes))
        return ToolkitIo.json("locator" to "zip_entry:$entry", "targetVersion" to ToolkitIo.version(bytes), "patched" to true)
            .withRevision(workspace, session, beforeRevision)
    }
    private fun elf(workspace: String, session: String, args: JsonObject) = ToolkitElf(raw(workspace, session, entryLocator(args.tString("locator"))))
    private fun patchNativeString(workspace: String, session: String, args: JsonObject): JsonObject {
        // Schema marked replacementText optional with default ""; this is the tool entry point,
        // so fill the default before the shared patcher (which still requires the key).
        if (!args.has("replacementText")) args.addProperty("replacementText", "")
        return applyNativePatch(workspace, session, args, ToolkitNativePatches.string(elf(workspace, session, args), args))
    }
    private fun patchInstructions(workspace: String, session: String, args: JsonObject): JsonObject {
        return applyNativePatch(workspace, session, args, ToolkitNativePatches.instructions(elf(workspace, session, args), args, assembler))
    }
    private fun applyNativePatch(workspace: String, session: String, args: JsonObject, patch: ToolkitNativePatches.Patch): JsonObject {
        val result = patchBytes(workspace, session, ToolkitIo.json("locator" to args.tString("locator"),
            "patches" to listOf(ToolkitIo.json("byteOffset" to patch.byteOffset, "expectedHex" to ToolkitIo.hex(patch.expected), "replacementHex" to ToolkitIo.hex(patch.replacement)))))
        patch.metadata.entrySet().forEach { (name, value) -> result.add(name, value) }
        return result
    }
    private fun resourceXref(workspace: String, session: String, args: JsonObject): JsonObject {
        val id = ToolkitResources.parseId(args.tString("locator").substringAfter(':')); val scopes = args.getAsJsonArray("scopes")?.map { it.asString } ?: listOf("dex","axml","resource_table")
        require(scopes.all { it in listOf("dex","axml","resource_table") }); val items = ArrayList<JsonObject>(); var partial = false
        if("dex" in scopes) for ((entry, dex) in dexes(workspace, session)) { if(items.size > 10000) { partial = true; break }; items.addAll(dex.resourceXref(id, 10001 - items.size).map { it.apply { addProperty("dexEntry", entry); addProperty("scope", "dex") } }) }
        val wanted = "0x%08x".format(id)
        if("resource_table" in scopes && "resources.arsc" in entries(workspace, session)) {
            val resources = resourceTable(workspace, session)
            resources.entries().forEach { e ->
                if(items.size >= 10000) partial = true else if(e.resValue?.let { it.valueType.name in listOf("REFERENCE","ATTRIBUTE","DYNAMIC_REFERENCE","DYNAMIC_ATTRIBUTE") && it.data == id } == true ||
                    (e.resValueMapArray?.listItems()?.any { (it.valueType.name in listOf("REFERENCE","ATTRIBUTE","DYNAMIC_REFERENCE","DYNAMIC_ATTRIBUTE") && it.data == id) || it.nameId == id } == true))
                    items.add(resources.item(e).apply { addProperty("scope", "resource_table") })
            }
        }
        if("axml" in scopes) entries(workspace, session).filter { it.endsWith(".xml") }.take(2000).forEach { entry ->
            if(items.size < 10000) {
                val bytes = raw(workspace, session, entry, ToolkitIo.MAX_TEXT)
                if(ToolkitResources.isBinaryXml(bytes)) {
                    val needle = byteArrayOf(id.toByte(),(id ushr 8).toByte(),(id ushr 16).toByte(),(id ushr 24).toByte())
                    // Exact typed-value cells only, not arbitrary matching text/byte strings.
                    for (p in 0 until bytes.size - 7 step 4) if(bytes[p] == 8.toByte() && bytes[p+1] == 0.toByte() && bytes[p+2] == 0.toByte() && bytes[p+3] in byteArrayOf(1,2,7,8) && bytes.copyOfRange(p+4,p+8).contentEquals(needle))
                        items.add(ToolkitIo.json("locator" to "zip_entry:$entry", "byteOffset" to p+4, "scope" to "axml", "resourceId" to wanted))
                }
            } else partial = true
        }
        return page(workspace, session, items, args.tInt("limit", 50, 1, 200), partial, producer = "apk_resource_xref")
    }
    private fun check(workspace: String, session: String, run: Boolean): JsonObject {
        val beforeRevision = revision(workspace, session)
        val info = sessionInfo(workspace, session); val changed = info.getAsJsonObject("changedEntries").keySet().toSet(); val failures = ArrayList<JsonObject>()
        val names = entries(workspace, session).toSet()
        val base = ZipFile(snapshot(workspace)).use { it.entries().asSequence().map { entry -> entry.name }.toSet() }
        val deleted = info.getAsJsonArray("deleted").map { it.asString }.toSet()
        if(run) {
            fun failure(entry: String, code: String, message: String?) {
                failures.add(ToolkitIo.json("locator" to "zip_entry:$entry", "errorCode" to code, "message" to message, "error" to message))
            }
            if(workspace(workspace).tString("artifactType","apk")=="apk" && "AndroidManifest.xml" !in names) failure("AndroidManifest.xml", "MANIFEST_MISSING", "缺少 Manifest")
            base.filter { (ToolkitApkTargets.isDex(it) || it == "resources.arsc") && it !in names }.forEach {
                failure(it, "RESERVED_ENTRY_MISSING", "编辑会话移除了保留条目，请撤销此变更")
            }
            val validate = changed + names.filter { ToolkitApkTargets.isDex(it) || it == "resources.arsc" || it == "AndroidManifest.xml" }
            validate.forEach { entry -> runCatching {
                val semantic = ToolkitApkTargets.isDex(entry) || entry == "resources.arsc" || entry == "AndroidManifest.xml" ||
                    entry.endsWith(".so") || ToolkitResources.isBinaryXml(prefix(workspace, session, entry, 8))
                if(!semantic) return@runCatching
                val bytes = raw(workspace, session, entry)
                when { ToolkitApkTargets.isDex(entry) -> ToolkitDex(bytes).classes.forEach { it.methods.forEach { method -> method.implementation?.instructions?.count() } }
                    entry == "resources.arsc" -> ToolkitResources(bytes).entries()
                    ToolkitGadget.isConfiguration(entry,bytes) -> Unit
                    entry.endsWith(".so") -> ToolkitElf(bytes)
                    entry == "AndroidManifest.xml" -> {
                        val text = if(ToolkitResources.isBinaryXml(bytes)) ToolkitResources.decodeXml(bytes, optionalResources(workspace, session)) else ToolkitIo.text(bytes)
                        ToolkitResources.safeXml(text)
                        require(com.reandroid.xml.XMLDocument.load(text).documentElement?.name == "manifest") { "Manifest 不是有效 XML" }
                    }
                    ToolkitResources.isBinaryXml(bytes) -> ToolkitResources.decodeXml(bytes, optionalResources(workspace, session))
                }
            }.onFailure { failure(entry, "BUILD_CHECK_FAILED", it.message) } }
        }
        val classes = info.getAsJsonObject("classChanges")
        val completeCounts = info.get("classChangesComplete")?.asBoolean == true
        fun count(kind: String) = if(completeCounts) classes?.entrySet()?.count { it.value.asString == kind } ?: 0 else null
        return ToolkitIo.json("workspaceId" to workspace, "editSessionId" to session, "changedEntries" to changed, "deleted" to deleted,
            "buildChecksRun" to run, "runBuildChecks" to run, "valid" to if(run) failures.isEmpty() else null,
            "buildCheckStatus" to if(!run) "not_requested" else if(failures.isEmpty()) "passed" else "failed",
            "pendingCompileCount" to 0, "failedCount" to failures.size, "failures" to failures,
            "changedDexEntries" to (changed + deleted).filter { ToolkitApkTargets.isDex(it) },
            "changedClassCount" to count("changed"), "deletedClassCount" to count("deleted"), "addedClassCount" to count("added"),
            "classCountsComplete" to completeCounts, "addedZipEntries" to changed.filter { it !in base },
            "changedZipEntries" to changed.filter { it in base }, "deletedZipEntries" to deleted,
            "note" to "所有语义编辑先编译再原子保存；结构检查不保证应用业务行为、安装性或原签名兼容")
            .withRevision(workspace, session, beforeRevision)
    }
    private fun build(workspace: String, session: String, args: JsonObject): JsonObject {
        require(workspace(workspace).tString("artifactType","apk")=="apk") { "此工作区不是APK；独立文件使用artifact_export，分包请先选择成员" }
        val checked = check(workspace, session, true); require(checked.getAsJsonArray("failures").size() == 0) { "会话检查失败：${checked.get("failures")}" }
        val signing = args.tBool("sign", false)
        val requested = args.tString("outputName", "")
        val name = requested.ifEmpty { File(workspace(workspace).tString("sourcePath")).nameWithoutExtension + "_mcp_${session}_${if(signing) "sign" else "unsigned"}.apk" }
        require(name.endsWith(".apk", true)) { "outputName 必须写完整的 .apk 文件名" }
        val organized = outputOrganizer.organize(ToolkitIo.resolve(root, name), args)
        val output = organized.path
        require(output != File(workspace(workspace).tString("sourcePath"))) { "不能覆盖源 APK" }
        require(!output.exists() || output.isFile && args.tBool("overwrite")) { "输出已存在或不是普通文件，需 overwrite=true" }
        val names = entries(workspace, session).filterNot { it.startsWith("META-INF/", true) && (it.endsWith(".SF", true) || it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true) || it.equals("META-INF/MANIFEST.MF", true)) }
        var verification:JsonObject?=null
        var signerMode = if (signing) "" else "unsigned"
        var signerFallbackReason: String? = null
        ToolkitIo.atomic(output) { target ->
            if (!signing) writeUnsigned(workspace, session, names, target) else {
                val callback = signer ?: error("当前环境没有 APK 签名器")
                val unsigned = File(sessionDir(workspace, session), "unsigned-${ToolkitIo.fresh()}.apk")
                val apksigOutput = File(sessionDir(workspace, session), "apksig-${ToolkitIo.fresh()}.apk")
                try {
                    writeUnsigned(workspace, session, names, unsigned)
                    try {
                        signWithApksig(unsigned, apksigOutput, target.absoluteFile.parentFile ?: root)
                        validateApksigOutput(apksigOutput)
                        copyFile(apksigOutput, target)
                        signerMode = "apksig"
                    } catch (failure: Throwable) {
                        if (failure is VirtualMachineError) throw failure
                        if (failure is InterruptedIOException || Thread.currentThread().isInterrupted) throw failure
                        signerFallbackReason = (failure.message ?: failure.javaClass.simpleName).take(1000)
                        callback(unsigned, target)
                        signerMode = "keystore-legacy"
                    }
                    val signature = ToolkitSignatures.read(target)
                    require(signature.tBool("available") && signature.tBool("signaturePresent") && signature.getAsJsonArray("certificates").size() > 0) { "签名器没有生成完整可读取的证书信息：" + signature.get("error") }
                } finally {
                    unsigned.delete()
                    apksigOutput.delete()
                }
            }
            val verified=ToolkitApkVerifier.inspect(target)
            require(verified.getAsJsonObject("checks").getAsJsonObject("zip").tString("status")=="valid") { "导出ZIP对齐或结构检查失败：" + verified.get("issues") }
            if(signing) require(verified.getAsJsonObject("signature").tBool("verified")) { "导出APK密码学验签失败：" + verified.get("issues") }
            verification=verified
            require(target.length() <= ToolkitIo.MAX_APK) { "构建结果超过 512 MiB" }; ZipFile(target).use { require(it.getEntry("AndroidManifest.xml") != null) }
        }
        val signature = ToolkitSignatures.read(output)
        // 两个概念要分开，别混：
        //  - schemesApplied：**实际产出**的方案（V1 由 META-INF 存在性判定；apksig/verifier 在 minSdk≥24 时
        //    不会单独验 V1，所以不能靠 verified 判定它有没有产出）；
        //  - schemesVerified：verifier 真正验过的方案。
        // 之前只报 schemesApplied=verified，导致产出了 V1 却显示 ["V2","V3"]（实测 dev-1364）。
        val schemesVerified = verifiedSchemes(verification)
        val v1Present = signing && output.isFile && hasV1Signature(output)
        val appliedSchemes = (if (v1Present) listOf("V1") else emptyList()) + schemesVerified
        val signatureInfo = ToolkitIo.json(
            "available" to signature.tBool("available"),
            "signaturePresent" to signature.tBool("signaturePresent"),
            "schemes" to appliedSchemes,
            "schemesVerified" to schemesVerified,
            "verified" to (verification?.getAsJsonObject("signature")?.tBool("verified") ?: false),
            "certificateCount" to (signature.get("certificates")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0)
        )
        val result = ToolkitIo.json("path" to output.path, "size" to output.length(), "sha256" to ToolkitIo.sha(output), "signed" to signing,
            "signer" to signerMode, "signerFallbackReason" to signerFallbackReason,
            "schemesApplied" to appliedSchemes, "schemesVerified" to schemesVerified, "signature" to signatureInfo,
            "signatureSummary" to signature, "verification" to verification,
            // 只读对齐诊断：不改变导出流程；计数来自最终 APK 的 verifier 报告（见对齐/签名路径）。
            "alignment" to alignmentDiagnostics(verification, true),
            "signingIdentity" to if(signing) "AI.js Pro 本机身份，替换原签名" else "unsigned", "sourceUnchanged" to true)
        listOf("changedDexEntries", "changedClassCount", "deletedClassCount", "addedClassCount", "addedZipEntries", "changedZipEntries", "deletedZipEntries").forEach {
            result.add(it, checked.get(it))
        }
        result.withOrganized(organized)
        // 云镜反馈：apk_build 返回太大，调用方常只要路径/哈希/签名/改动计数。detail=summary 只留关键字段（默认 full，老调用不变）。
        if (args.tString("detail", "full").equals("summary", true)) {
            val keep = listOf("path", "size", "sha256", "signed", "signer", "signerFallbackReason", "schemesApplied", "schemesVerified",
                "changedDexEntries", "changedClassCount", "deletedClassCount", "addedClassCount", "addedZipEntries", "changedZipEntries", "deletedZipEntries",
                "signingIdentity", "sourceUnchanged", "organized")
            val summary = com.google.gson.JsonObject()
            keep.forEach { key -> result.get(key)?.let { value -> summary.add(key, value) } }
            result.getAsJsonObject("signature")?.let { signature ->
                summary.add("signature", com.google.gson.JsonObject().apply {
                    addProperty("verified", signature.tBool("verified"))
                    add("schemes", signature.get("schemes"))
                })
            }
            result.getAsJsonObject("alignment")?.let { alignment ->
                summary.add("alignment", com.google.gson.JsonObject().apply {
                    addProperty("checked", alignment.tBool("checked"))
                    add("misalignedEntries", alignment.get("misalignedEntries"))
                    addProperty("zipStatus", alignment.tString("zipStatus", ""))
                })
            }
            summary.addProperty("detail", "summary")
            summary.addProperty("detailNote", "detail=summary 只回关键字段；要 verification/alignment 全量诊断请用 detail=full（默认）")
            return summary
        }
        return result
    }
    /**
     * 新路径：apksig 生成 V1+V2+V3。只在 apk_build(sign=true) 内部使用；
     * 失败时由调用方回退旧 KeyStoreApkSigner，不改变 apk_build_project/apk_inject_gadget 调用。
     */
    private fun signWithApksig(input: File, output: File, signingOutputDir: File) {
        requireApksigSignerClasses()
        val resolved = AutoSigningIdentity.signer(signingOutputDir, "APK工具")
        val keySigner = resolved as? KeyStoreApkSigner
            ?: error("当前签名身份不是 KeyStoreApkSigner，无法使用 apksig")
        val key = keySigner.signingKey
        val config = ApkSigner.SignerConfig.Builder(
            keySigner.label.ifBlank { "aijspro" },
            key.privateKey,
            key.certificateChain.toList()
        ).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .setAlignmentPreserved(true)
            .setLibraryPageAlignmentBytes(16384)
            .build()
            .sign()
    }

    private fun requireApksigSignerClasses() {
        val loader = McpApkToolkit::class.java.classLoader
        listOf(
            "com.android.apksig.ApkSigner",
            "com.android.apksig.ApkSigner\$Builder",
            "com.android.apksig.ApkSigner\$SignerConfig",
            "com.android.apksig.ApkSigner\$SignerConfig\$Builder"
        ).forEach { name -> Class.forName(name, false, loader) }
    }

    private fun validateApksigOutput(file: File) {
        val report = ToolkitApkVerifier.inspect(file)
        val zip = report.getAsJsonObject("checks")?.getAsJsonObject("zip")
        require(zip?.tString("status") == "valid") {
            "apksig 输出 ZIP 对齐或结构检查失败：" + report.get("issues").toString().take(800)
        }
        val signature = report.getAsJsonObject("signature")
            ?: error("apksig 输出缺少签名检查结果")
        require(signature.tBool("verified")) {
            "apksig 输出未通过密码学验签：" + signature.get("issues").toString().take(800)
        }
        val schemes = signature.getAsJsonObject("schemes")
            ?: error("apksig 输出缺少 schemes 结果")
        // V2/V3：我们的 verifier 真会验，必须 verified。
        listOf("V2", "V3").forEach { scheme ->
            require(schemes.getAsJsonObject(scheme)?.tBool("verified") == true) {
                "apksig 输出 " + scheme + " 未通过验证；要求 V1+V2+V3，准备回退"
            }
        }
        // V1：apksig 的 ApkVerifier 在 minSdk≥24 时**不会**单独验 JAR 签名（报告 status=not_run），
        // 所以不能要求它 verified——之前那条规则会让 apksig 路径永远回退（实测 signerFallbackReason=
        // “apksig 输出 V1 未通过验证”）。这里只要求：V1 没被判 invalid，且归档里**真的存在** META-INF 签名。
        val v1 = schemes.getAsJsonObject("V1")
        require(v1 == null || v1.tString("status") != "invalid") {
            "apksig 输出 V1 被判为 invalid，准备回退：" + signature.get("issues").toString().take(400)
        }
        require(hasV1Signature(file)) { "apksig 输出没有 META-INF（V1）签名，准备回退" }
    }

    /** V1（JAR）签名存在性：只看 META-INF 里的签名文件，不冒充“已验签”。 */
    private fun hasV1Signature(file: File): Boolean = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            zip.entries().asSequence().any { entry ->
                val name = entry.name.uppercase()
                name.startsWith("META-INF/") &&
                    (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
            }
        }
    }.getOrDefault(false)

    private fun copyFile(source: File, target: File) {
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun verifiedSchemes(verification: JsonObject?): List<String> {
        val schemes = verification?.getAsJsonObject("signature")?.getAsJsonObject("schemes")
            ?: return emptyList()
        return listOf("V1", "V2", "V3", "V3.1").filter { scheme ->
            schemes.get(scheme)?.takeIf { it.isJsonObject }?.asJsonObject?.tBool("verified") == true
        }
    }

    private fun writeUnsigned(workspace: String, session: String, names: List<String>, target: File) {
        val changed = sessionInfo(workspace, session).getAsJsonObject("changedEntries").keySet()
        ZipFile(snapshot(workspace)).use { base ->
            ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
                var total = 0L
                names.forEach { name ->
                    val original = base.getEntry(name); val entry = ZipEntry(name)
                    if(original != null && original.time >= 0) entry.time = original.time
                    entry.method = original?.method ?: if(name.endsWith(".so") || name == "resources.arsc") ZipEntry.STORED else ZipEntry.DEFLATED
                    if(entry.method == ZipEntry.STORED) {
                        val crc = CRC32(); var count = 0L
                        streamEntry(workspace, session, name) { input ->
                            val buffer = ByteArray(65536)
                            while(true) { ToolkitIo.ensureActive(); val read = input.read(buffer); if(read < 0) break; count += read
                                require(count <= ToolkitIo.MAX_APK) { "ZIP 条目过大" }; crc.update(buffer, 0, read) }
                        }
                        entry.size = count; entry.compressedSize = count; entry.crc = crc.value
                        require(name in changed || original == null || original.crc == crc.value) { "源条目 CRC 不符：$name" }
                    }
                    zip.putNextEntry(entry)
                    val crc = CRC32()
                    streamEntry(workspace, session, name) { input ->
                        val buffer = ByteArray(65536)
                        while(true) { ToolkitIo.ensureActive(); val read = input.read(buffer); if(read < 0) break; total += read
                            require(total <= ToolkitIo.MAX_APK) { "展开后内容超过512MiB" }; crc.update(buffer, 0, read); zip.write(buffer, 0, read) }
                    }
                    require(name in changed || original == null || original.crc == crc.value) { "源条目 CRC 不符：$name" }
                    zip.closeEntry()
                }
            }
        }
        ToolkitArchiveAlignment.align(target)
    }
    /**
     * 只读对齐诊断（任务 4B）：从最终 APK 的 [ToolkitApkVerifier] 报告中提取 ZIP 对齐计数。
     *
     * 不修改导出流程、不重写/重新签名 APK。当前 [writeUnsigned] 写出未签名 APK 后已经调用
     * [ToolkitArchiveAlignment.align]（Stored 非目录 4B、Stored .so 16KiB）；签名路径再对齐并插入 V2，
     * 最后由 build() 内的 verifier 复核。这里只把最终文件的真实偏移统计摊平给调用方，并明确
     * “fixed=true”不等于“本次一定改动过字节”，因为 align 不返回逐条 padding 计数。
     */
    private fun alignmentDiagnostics(verification: JsonObject?, alignerApplied: Boolean): JsonObject =
        alignmentDiagnosticsJson(verification, alignerApplied)

    private fun streamEntry(workspace: String, session: String, entry: String, consume: (InputStream) -> Unit) {
        if(session.isNotEmpty()) require(sessionInfo(workspace, session).getAsJsonArray("deleted").none { it.asString == entry }) { "条目已删除" }
        val changed = if(session.isEmpty()) null else sessionInfo(workspace, session).getAsJsonObject("changedEntries").get(entry)?.asString
        if(changed != null) File(File(sessionDir(workspace, session), "entries"), ToolkitIo.id(changed)).inputStream().use(consume)
        else ZipFile(snapshot(workspace)).use { zip -> val item = zip.getEntry(entry) ?: error("条目不存在"); zip.getInputStream(item).use(consume) }
    }
    private fun copyEntry(workspace: String, session: String, args: JsonObject): JsonObject {
        val beforeRevision = revision(workspace, session)
        val entry = entryLocator(args.tString("locator")); val overwrite = args.tBool("overwrite")
        var organized: ToolkitOutputOrganizer.OrganizedPath? = null
        val local = if (args.tString("direction") == "workspace_to_local") {
            outputOrganizer.organize(ToolkitIo.resolve(root, args.tString("localPath")), args).also { organized = it }.path
        } else ToolkitIo.resolve(root, args.tString("localPath"))
        when(args.tString("direction")) {
            "workspace_to_local" -> {
                ToolkitApkTargets.ordinary(entry, prefix(workspace, session, entry, 8))
                require(local != File(workspace(workspace).tString("sourcePath"))) { "不能覆盖源 APK" }
                require(!local.exists() || local.isFile && overwrite) { "本地目标已存在或不是普通文件" }
                ToolkitIo.atomic(local) { file -> file.outputStream().use { out -> streamEntry(workspace, session, entry) { copyBounded(it,out,ToolkitIo.MAX_APK) } } }
            }
            "local_to_edit_session" -> {
                requiredSession(session); require(local.isFile && local.length() <= ToolkitIo.MAX_APK)
                val exists = entry in entries(workspace, session)
                require(!exists || overwrite) { "会话条目已存在" }
                if(exists) ToolkitApkTargets.ordinary(entry, prefix(workspace, session, entry, 8))
                val head = local.inputStream().use { input -> ByteArray(8).let { bytes -> val count = input.read(bytes); if(count < 0) byteArrayOf() else bytes.copyOf(count) } }
                ToolkitApkTargets.ordinary(entry, head)
                val hash = ToolkitIo.sha(local)
                write(workspace, session, emptyMap(), streaming = mapOf(entry to { file ->
                    local.inputStream().use { input -> file.outputStream().use { output -> copyBounded(input, output, ToolkitIo.MAX_APK) } }
                    require(ToolkitIo.sha(file) == hash && ToolkitIo.sha(local) == hash) { "复制期间本地源文件已变化" }
                }))
            }
            else -> error("复制方向无效")
        }; return ToolkitIo.json("copied" to true, "locator" to "zip_entry:$entry", "localPath" to local.path)
            .apply { organized?.let { this.withOrganized(it) } }
            .withRevision(workspace, session, beforeRevision)
    }
    private fun prefix(workspace: String, session: String, entry: String, max: Int): ByteArray {
        var value = byteArrayOf()
        streamEntry(workspace, session, entry) { input ->
            val output = ByteArrayOutputStream(minOf(max, 65536)); val buffer = ByteArray(8192)
            while(output.size() < max) {
                val read = input.read(buffer, 0, minOf(buffer.size, max - output.size()))
                if(read < 0) break
                if(read == 0) continue
                output.write(buffer, 0, read)
            }
            value = output.toByteArray()
        }
        return value
    }
    private fun entryLength(workspace: String, session: String, entry: String): Long {
        if(session.isNotEmpty()) {
            val info = sessionInfo(workspace, session)
            require(info.getAsJsonArray("deleted").none { it.asString == entry }) { "条目已删除" }
            info.getAsJsonObject("changedEntries").get(entry)?.asString?.let {
                return File(File(sessionDir(workspace, session), "entries"), ToolkitIo.id(it)).length()
            }
        }
        return ZipFile(snapshot(workspace)).use { zip ->
            val found = zip.getEntry(entry) ?: error("APK 条目不存在：$entry")
            require(!found.isDirectory && found.size >= 0) { "需要普通 ZIP 文件条目" }; found.size
        }
    }
    /**
     * 读取签名信息。默认**只读证书元数据**（ToolkitSignatures），此时 verified=false 表示「没跑验签」而不是
     * 「验签失败」——用 verification=not_run/verificationReason/verifyWith 把这件事说清楚（.4 §4.2 的入口误导）。
     * verify=true 才在同一次调用里复用 [ToolkitApkVerifier] 跑密码学验签，并把逐方案状态/errors/backendDiagnosis 一并返回。
     */
    private fun readSignature(apk: File, verify: Boolean = false): JsonObject {
        val signature = ToolkitSignatures.read(apk)
        val metadata = ToolkitIo.json("signature" to signature, "certificates" to signature.get("certificates"),
            "coverage" to "V1/V2/V3/V3.1 certificate metadata")
        if (!verify) {
            return metadata.apply {
                addProperty("verified", false)
                addProperty("verification", "not_run")
                addProperty("verificationReason", "apk_read_signature 只读证书元数据，不做密码学验签；verified=false 表示「没有跑验签」，不是「验签失败」")
                addProperty("verifyWith", "apk_verify{workspaceId}（同一 ToolkitApkVerifier 的完整报告：逐方案状态 + backendDiagnosis），或本工具 verify=true")
            }
        }
        val report = runCatching { ToolkitApkVerifier.inspect(apk) }.getOrElse { failure ->
            return metadata.apply {
                addProperty("verified", false)
                addProperty("verification", "backend_error")
                addProperty("verificationReason", "ToolkitApkVerifier 不可用/失败：" + (failure.message ?: failure.javaClass.simpleName))
                addProperty("verifyWith", "apk_verify{workspaceId}")
            }
        }
        val section = runCatching { report.getAsJsonObject("signature") }.getOrNull()
        val verified = section?.get("verified")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        return metadata.apply {
            addProperty("verified", verified)
            addProperty("verification", "executed")
            addProperty("verificationBackend", "com.android.apksig.ApkVerifier（与 apk_verify 同一实现）")
            addProperty("verifyWith", "apk_verify{workspaceId}（同类报告，含 ZIP/ELF/Manifest 检查）")
            addProperty("verificationStatus", section?.get("status")?.takeIf { it.isJsonPrimitive }?.asString)
            addProperty("verdict", section?.get("verdict")?.takeIf { it.isJsonPrimitive }?.asString)
            addProperty("coverage", section?.get("coverage")?.takeIf { it.isJsonPrimitive }?.asString
                ?: "apksig 密码学验证（未提供 coverage 字段）")
            section?.get("schemes")?.let { add("schemes", it) }
            section?.get("errors")?.let { add("errors", it) }
            section?.get("backendDiagnosis")?.let { add("backendDiagnosis", it) }
            section?.get("checkedPlatform")?.let { add("checkedPlatform", it) }
            report.get("issues")?.let { add("issues", it) }
        }
    }
    private fun copyBounded(input: InputStream, output: OutputStream, max: Long): Long {
        val buffer = ByteArray(65536); var copied = 0L
        while(true) { val count = input.read(buffer); if(count < 0) break; require(copied + count <= max) { "流式处理超过容量限制" }; output.write(buffer,0,count); copied += count }; return copied
    }
    private fun safeRemove(file: File, parent: File) {
        val target = file.canonicalFile; val base = parent.canonicalFile
        require(target != base && target.path.startsWith(base.path + File.separator)) { "拒绝删除工作区之外的路径" }
        require(file.absoluteFile.normalize() == target) { "拒绝递归处理符号链接" }
        file.listFiles()?.forEach { child -> safeRemove(child, base) }
        require(!file.exists() || file.delete()) { "无法清理 ${file.name}" }
    }
}


/**
 * 只读对齐诊断（apk_build / apk_build_project / apk_inject_gadget 共用）。
 *
 * 不修改导出流程、不重写/重新签名：只把最终 APK 的真实本地头偏移统计摊平，并明确
 * “fixed=true” 不等于“本次一定改过字节”（align 不返回逐条 padding 计数）。
 */
internal fun alignmentDiagnosticsJson(verification: JsonObject?, alignerApplied: Boolean): JsonObject {
    val zip = verification?.get("zip")?.takeIf { it.isJsonObject }?.asJsonObject
    val alignment = zip?.get("alignment")?.takeIf { it.isJsonObject }?.asJsonObject
    if (zip == null || alignment == null) {
        return ToolkitIo.json(
            "checked" to false,
            "misalignedEntries" to null,
            "fixed" to false,
            "alignerApplied" to alignerApplied,
            "paddedEntries" to null,
            "note" to "没有可用的最终 APK ZIP 验证报告，不能声称对齐；导出流程未变。",
        )
    }
    val status = zip.tString("status", "unavailable")
    fun count(name: String): Int? =
        alignment.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    val storedMisaligned = count("storedEntriesMisaligned4Bytes")
    val nativeMisaligned = count("nativeLibrariesMisaligned16Kb")
    val checked = status == "valid"
    val misaligned = if (checked) (storedMisaligned ?: 0) + (nativeMisaligned ?: 0) else null
    val fixed = checked && alignerApplied && misaligned == 0
    return ToolkitIo.json(
        "checked" to checked,
        "misalignedEntries" to misaligned,
        "fixed" to fixed,
        "alignerApplied" to alignerApplied,
        "aligner" to "ToolkitArchiveAlignment.align",
        "paddedEntries" to null,
        "paddedEntriesNote" to "ToolkitArchiveAlignment.align 当前不返回逐条补 padding 计数；不伪造该数字",
        "zipStatus" to status,
        "requiredBytes" to alignment.get("requiredBytes"),
        "storedEntriesChecked" to count("storedEntriesChecked"),
        "storedEntriesMisaligned4Bytes" to storedMisaligned,
        "deflatedEntryCount" to count("deflatedEntryCount"),
        "nativeLibrariesChecked16Kb" to count("nativeLibrariesChecked16Kb"),
        "nativeLibrariesMisaligned16Kb" to nativeMisaligned,
        "nativeLibrariesCompressed" to count("nativeLibrariesCompressed"),
        "note" to (if (!checked) {
            "最终 APK ZIP 未完整通过解析（zipStatus=" + status + "），不能给对齐结论；详见 verification.zip.issues。"
        } else {
            "只读诊断：计数来自最终输出文件本地头的真实数据偏移（ToolkitApkVerifier）；" +
                "fixed=true 表示本次导出执行了对齐器且最终为 0 条 misaligned，不代表文件一定被改动过字节；" +
                "Stored 非目录要求 4B、Stored .so 要求 16KiB，Deflate 条目不参与对齐。"
        }),
    )
}

/**
 * 产出目录约定的只读投影。归置逻辑在 [ToolkitOutputOrganizer]（A 负责 project_folder/自动归置）；
 * 这里只把当前默认项目与规则整理成 MCP 可读字段，供 get_status / toolkit_status 共用。
 */
internal object McpOutputConventions {
    const val RULE = "所有产出必须放在操作根下的项目目录 <root>/<项目名>/；没有先 project_folder{action:\"create\",name:\"<项目名>\"}，不要直接写脚本根目录或 tmp/out/test 这类一次性名字。"
    const val INSTRUCTION = "产出目录（强制）：所有 APK/ZIP/反编译/截图/导出/证据必须落在操作根下的项目目录 <root>/<项目名>/；没有就先 project_folder{action:\"create\",name:\"<项目名>\"}（需要默认再 set）；不要写 <root> 根目录，也不要用 tmp/out/test/__xxx 一次性名字。工具返回 organized 说明它已帮你归位——看到就说明你原本写错了位置，下次直接传项目目录。"
    const val POINTER = "产出请放项目目录，见 project_folder"

    val TOOLS_WITH_ORGANIZE: List<String> = listOf(
        "capture_screen", "evidence_export",
        "file_edit_text", "file_write_bytes", "file_patch_bytes", "file_copy", "file_move", "file_delete",
        "file_create_directory", "file_delete_directory", "file_replace_text", "file_truncate_bytes",
        "file_append_bytes", "file_append_text",
        "zip_extract", "zip_create", "zip_update",
        "apk_build", "apk_build_project", "apk_decode_project", "apk_inject_gadget", "apk_job_export",
        "artifact_export",
    )

    fun appendPointer(description: String): String =
        if (description.contains(POINTER)) description else description.trimEnd() + "；" + POINTER

    /** 无 A 的归置器时也返回完整形状：projectDir/defaultProject 为 null，note 明说未就绪。 */
    fun describe(organizerState: JsonObject?): JsonObject {
        val projectDir = organizerState?.get("projectDir")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
        val defaultProject = organizerState?.get("name")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
        return ToolkitIo.json(
            "projectDir" to projectDir,
            "defaultProject" to defaultProject,
            "rule" to RULE,
            "toolsWithOrganize" to TOOLS_WITH_ORGANIZE,
            "note" to if (projectDir == null)
                "归置器尚未就绪或尚无默认项目：当前只提醒，不会自动归置；先 project_folder{action:\"create\",name:\"<项目名>\"} 再 set。"
            else organizerState?.get("note")?.takeIf { it.isJsonPrimitive && it.asString.isNotBlank() }?.asString,
        )
    }

    /** 读取 A 的归置器 state；A 的类/API 未就绪时返回 null（调用方给 null+note）。 */
    fun readState(root: File, store: File): JsonObject? = runCatching {
        ToolkitOutputOrganizers.forStore(store, root).state()
    }.getOrNull()

    fun describeFor(root: File, store: File): JsonObject = describe(readState(root, store))
}
