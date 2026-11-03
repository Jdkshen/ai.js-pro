package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.*
import java.util.zip.*

/** Immutable APK snapshots and copy-on-write edit sessions. Never calls MT. */
internal class McpApkToolkit(
    private val root: File,
    private val store: File,
    private val schemas: JsonObject,
    private val signer: ((File, File) -> Unit)? = null,
    private val assembler: ((Int, Long, String, String) -> ByteArray)? = null,
    private val runtime: ToolkitRuntime? = null,
) {
    private val pages = java.util.concurrent.ConcurrentHashMap<String, Page>()
    private val workspaceLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
    @Volatile private var currentWorkspace: String? = null
    private data class Page(val workspace: String, val session: String, val revision: String, val items: List<JsonObject>, var offset: Int,
                            val kind: String, val metadata: JsonObject, val truncated: Boolean, val limitMax: Int = 200, val at: Long = System.currentTimeMillis())
    private val gson = Gson()
    init { require(store.isDirectory || store.mkdirs()) { "无法创建 APK 工作区目录" } }
    fun definitions(): JsonArray = JsonArray().apply {
        schemas.entrySet().forEach { (name, schema) -> add(ToolkitIo.json("name" to name, "category" to "toolkit", "categoryTitle" to "文件与APK工具",
            "description" to description(name), "inputSchema" to schema)) }
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
            "apk_verify" -> {
                val file=if(args.has("workspaceId"))snapshot(args.tString("workspaceId")) else ToolkitIo.resolve(root,args.tString("path"))
                ToolkitIo.ok(ToolkitApkVerifier.inspect(file,args.get("minSdk")?.asInt,args.get("maxSdk")?.asInt))
            }
            "apk_workspace_list" -> ToolkitIo.ok(ToolkitIo.json("items" to workspaces().filter { !it.tBool("temporary") }))
            "apk_list_available_apks" -> listAvailable(args)
            "apk_workspace_delete" -> {
                val workspace = workspace(args.tString("workspaceId")); val id = workspace.tString("workspaceId")
                safeRemove(directory(id), store); pages.entries.removeAll { it.value.workspace == id }
                if(currentWorkspace == id) currentWorkspace = null
                ToolkitIo.ok(ToolkitIo.json("workspaceId" to id, "deleted" to true))
            }
            "apk_continue" -> continuePage(args)
            "apk_read_signature" -> ToolkitIo.ok(readSignature(snapshot(args.tString("workspaceId"))).apply { addProperty("workspaceId", args.tString("workspaceId")) })
            else -> {
                val workspace = args.tString("workspaceId"); workspace(workspace)
                val session = args.tString("editSessionId", "")
                if (session.isNotEmpty()) sessionDir(workspace, session)
                when (name) {
                    "apk_session_create" -> ToolkitIo.ok(createSession(workspace))
                    "apk_session_diff" -> page(workspace,session,ToolkitApkSessions.diff(snapshot(workspace),sessionDir(workspace,requiredSession(session))),args.tInt("limit",50,1,200))
                    "apk_session_checkpoint" -> ToolkitIo.ok(ToolkitApkSessions.checkpoint(sessionDir(workspace,requiredSession(session)),args))
                    "apk_session_checkpoints" -> ToolkitIo.ok(ToolkitApkSessions.checkpoints(sessionDir(workspace,requiredSession(session))))
                    "apk_session_rollback" -> ToolkitIo.ok(ToolkitApkSessions.rollback(sessionDir(workspace,requiredSession(session)),args))
                    "artifact_export" -> ToolkitIo.ok(exportArtifact(workspace,session,args))
                    "apk_set_members" -> page(workspace,session,setMembers(workspace),args.tInt("limit",50,1,200))
                    "apk_set_open_member" -> ToolkitIo.ok(openSetMember(workspace,args))
                    "apk_decompile_class", "apk_decompile_method" -> ToolkitIo.ok(decompile(workspace,session,args))
                    "apk_list" -> list(workspace, session, args)
                    "apk_search" -> search(workspace, session, args)
                    "apk_read_text" -> {
                        val data = readText(workspace, session, args); val actions = JsonArray()
                        if(data.tBool("hasMore")) actions.add(action("apk_read_text", args.deepCopy().apply {
                            add("startLine", data.get("nextLine")); add("startColumn", data.get("nextColumn"))
                        }))
                        ToolkitIo.ok(data, actions)
                    }
                    "apk_read_bytes" -> ToolkitIo.ok(readBytes(workspace, session, args))
                    "apk_dex_read_class_outline" -> {
                        val (entry, dex) = dexFor(workspace, session, args.tString("locator"))
                        page(workspace, session, dex.outline(args.tString("locator")).map { it.apply { addProperty("dexEntry", entry) } }, args.tInt("limit", 200, 1, 1000), limitMax = 1000)
                    }
                    "apk_dex_search_members", "apk_dex_call_graph", "apk_dex_hierarchy" ->
                        ToolkitIo.ok(ToolkitDexNavigation(dexes(workspace,session).toList()).call(name,args))
                    "apk_dex_xref" -> {
                        val locator = args.tString("locator"); val limit = args.tInt("limit", 50, 1, 200)
                        val items = ArrayList<JsonObject>()
                        val allDex = dexes(workspace, session).toList(); val universe = allDex.flatMap { it.second.classes }
                        for ((entry, dex) in allDex) {
                            if (items.size >= 10000) break
                            items.addAll(dex.references(locator, args.tString("methodResolution", if(locator.startsWith("dex_method:")) "dispatch" else "not_applicable"), 10001 - items.size, universe).map { it.apply { addProperty("dexEntry", entry) } })
                        }; page(workspace, session, items, limit, items.size >= 10000)
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
                            metadata = ToolkitIo.json("demangleAvailable" to ToolkitNative.demangleAvailable), limitMax = 500)
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
                        page(workspace, session, items.take(10000), args.tInt("limit", 50, 1, 200), truncated, kind = "refs", metadata = result)
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
        "apk_dex_search_members" -> "按方法名/描述符/字符串/引用搜索，返回精确方法locator、dexEntry和行/指令锚点；有预算，truncated表示需要缩小查询。"
        "apk_dex_call_graph" -> "跨DEX向内/向外调用图，限定深度/节点/边；dexEntry消歧，动态和反射目标明确报告不完整。"
        "apk_dex_hierarchy" -> "查看类继承、接口实现与方法覆盖关系，使用原始descriptor定位符，跨DEX同名类需dexEntry。"
        "apk_decompile_class" -> "可选JADX类Java阅读；默认后台返回jobId，用apk_job_status/result读回。不保证反编译源码可重新构建。"
        "apk_decompile_method" -> "可选JADX精确方法Java阅读；默认后台返回jobId，需原始dex_method定位符，重载完整签名匹配。"
        "android_resources_compile" -> "可选Android AAPT2把res/Manifest按指定宿主编译为project-res.apk及sidecar；默认后台jobId，组件显式安装。"
        "apk_component_status" -> "只读查看JADX/资源编译组件安装与可用状态；不下载不运行编译器。"
        "apk_component_install" -> "显式下载并校验可选APK/逆向组件（含免Root frida-gadget）；默认后台jobId，manifestUrl可指定发布清单。"
        "apk_component_remove" -> "卸载指定可选APK组件，保留源工程和其他组件。"
        "apk_job_start" -> "后台运行指定APK分析/编译操作，立即返回jobId；任务有超时，失败不自动重放。"
        "apk_job_list" -> "读取当前工具目录的后台任务和状态。"
        "apk_job_status" -> "读取后台任务状态/阶段；CANCELLING表示正在等待实际worker结束。"
        "apk_job_result" -> "分块读取后台任务最终JSON结果，offset以返回nextOffset继续。"
        "apk_job_cancel" -> "请求取消指定后台任务；直到worker真正结束才报告CANCELLED，已提交操作可能完成。"
        "apk_session_diff" -> "预览会话相对原快照的新增/修改/删除、摘要和小文本；不覆盖源文件。"
        "apk_session_checkpoint" -> "保存编辑会话检查点，复制变更条目以支持后续回退；最多8个、总量512MiB。"
        "apk_session_checkpoints" -> "列出会话检查点及其revision。"
        "apk_session_rollback" -> "按检查点回退会话，必须提供当前expectedRevision；不覆盖原APK，返回新revision。"
        "artifact_open" -> "直接打开APK、独立DEX/SO/ELF或APKS/XAPK/APKM。独立文件使用明确标记的分析容器；分包先选择成员。"
        "artifact_export" -> "导出编辑后的独立DEX/ELF文件，不把分析容器伪装成可安装APK。"
        "apk_set_members" -> "列出分包归档APK成员、大小和base候选；不承诺自动合并或安装。"
        "apk_set_open_member" -> "选中分包成员创建独立只读APK快照，保留容器来源；可继续原APK工具链。"
        "apk_workspace_open" -> "创建 APK 只读快照。path 在工具目录内；temporary=true 为一次性工作区。后续修改先 apk_session_create。"
        "apk_build" -> "校验编辑会话，导出新 APK；不修改源包。sign=true 使用本机 AI.js Pro 签名身份，不能沿用原作者签名。"
        "apk_native_disassemble", "apk_native_xref", "apk_native_analyze_control_flow" -> "有界直接指令分析：原生库可用时覆盖 AArch64、ARM/Thumb、x86/x86-64（Capstone 5.0.6），否则回退 AArch64 常见指令子集；未知指令明确标出，间接引用与完整数据流不作成功保证。"
        "apk_native_patch_instructions" -> "会话中汇编并等长修改 AArch64、ARM/Thumb、x86/x86-64 指令，expectedHex 防旧数据。常见指令可直接处理，一般汇编可通过可选 C/C++ 组件完成。"
        else -> "AI.js Pro 本地 APK 工具：${name.removePrefix("apk_").replace('_', ' ')}；使用 tools/list 参数定义，locator 来自列表/搜索，不依赖 MT。"
    }
    private fun directory(id: String) = File(store, ToolkitIo.id(id))
    private fun snapshot(id: String): File = File(directory(id), "base.apk").also { require(it.isFile) { "APK 工作区不存在" } }
    private fun workspace(id: String): JsonObject = JsonParser().parse(File(directory(id), "workspace.json").readText()).asJsonObject
    private fun workspaces(): List<JsonObject> = store.listFiles()?.filter { it.isDirectory && File(it, "workspace.json").isFile }?.mapNotNull { runCatching { workspace(it.name) }.getOrNull() } ?: emptyList()
    private fun sessionDir(workspace: String, session: String): File = File(File(directory(workspace), "sessions"), ToolkitIo.id(session)).also { require(File(it, "session.json").isFile) { "会话不存在或不属于该工作区" } }
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
        val source = ToolkitIo.resolve(root, path); require(source.isFile && source.name.endsWith(".apk", true) && source.length() <= ToolkitIo.MAX_APK) { "需要工具目录内不超过 512 MiB 的 APK" }
        val hash = ToolkitIo.sha(source)
        if (!temporary) workspaces().firstOrNull { !it.tBool("temporary") && it.tString("sourcePath") == source.path && it.tString("sha256") == hash }?.let { currentWorkspace = it.tString("workspaceId"); return it }
        require(workspaces().size < 16) { "最多保留 16 个 APK 工作区，请删除不需要的临时工作区" }
        val existingSize = workspaces().sumOf { it.get("size").asLong }
        require(existingSize + source.length() <= 1024L * 1024 * 1024) { "APK 快照总量超过 1 GiB" }
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
            val signature = ToolkitSignatures.read(copy)
            info.add("signatureSummary", ToolkitIo.json("available" to signature.get("available"), "signaturePresent" to signature.get("signaturePresent"),
                "schemes" to signature.get("schemes"), "certificateCount" to signature.getAsJsonArray("certificates").size()))
            File(folder, "workspace.json").writeText(gson.toJson(info)); currentWorkspace = id; return info
        } catch (error: Exception) { safeRemove(folder, store); throw error }
    }
    private fun openArtifact(args:JsonObject):JsonObject {
        val source=ToolkitIo.resolve(root,args.tString("path"));require(source.isFile&&source.length()<=ToolkitIo.MAX_APK)
        val suffix=source.extension.lowercase()
        if(suffix=="apk")return open(args)
        require(suffix in setOf("dex","so","elf","apks","xapk","apkm")){"支持APK、DEX、ELF/SO及APKS/XAPK/APKM"}
        require(workspaces().size<16&&workspaces().sumOf{it.get("size").asLong}+source.length()<=1024L*1024*1024){"工作区数量或总量超过限制"}
        val type=if(suffix in setOf("apks","xapk","apkm"))"apk-set" else if(suffix=="dex")"dex" else "elf"
        val id=ToolkitIo.fresh();val dir=directory(id);require(dir.mkdir())
        try{
            val hash=ToolkitIo.sha(source);val original=File(dir,"original.bin")
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
        val output=ToolkitIo.resolve(root,args.tString("path"));require(output.path!=workspace(workspace).tString("sourcePath")){"不能覆盖源文件"}
        require(!output.exists()||args.tBool("overwrite")){"输出已存在"};ToolkitIo.atomic(output){it.writeBytes(bytes)}
        return ToolkitIo.json("path" to output.path,"sha256" to ToolkitIo.sha(output),"sourceUnchanged" to true,"artifactType" to type)
    }
    private fun decompile(workspace:String,session:String,args:JsonObject):JsonObject{
        val backend=runtime?:throw ToolkitException("COMPONENT_UNAVAILABLE","反编译组件后端未接入")
        val locator=args.tString("locator");val (_,dex)=dexFor(workspace,session,locator,args.tString("dexEntry",""));dex.find(locator)
        val input=if(session.isEmpty())snapshot(workspace)else File(sessionDir(workspace,session),"decompile-${ToolkitIo.fresh()}.apk")
        try{if(session.isNotEmpty())writeUnsigned(workspace,session,entries(workspace,session),input)
            return backend.decompile(input,locator,args).apply{addProperty("revision",revision(workspace,session))}
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
        return page("", "", items, args.tInt("limit", 50, 1, 200), truncated)
    }
    private fun createSession(workspace: String): JsonObject {
        val parent = File(directory(workspace), "sessions"); parent.mkdirs()
        require((parent.listFiles()?.size ?: 0) < 8) { "单个工作区最多 8 个编辑会话" }
        val id = ToolkitIo.fresh(); val folder = File(parent, id); require(folder.mkdir()); require(File(folder, "entries").mkdir())
        val info = ToolkitIo.json("workspaceId" to workspace, "editSessionId" to id, "revision" to ToolkitIo.fresh(), "deleted" to emptyList<String>(),
            "changedEntries" to emptyMap<String,String>(), "classChanges" to emptyMap<String,String>(), "classChangesComplete" to true)
        File(folder, "session.json").writeText(gson.toJson(info)); return info
    }
    private fun sessionInfo(workspace: String, session: String) = JsonParser().parse(File(sessionDir(workspace, session), "session.json").readText()).asJsonObject
    private fun revision(workspace: String, session: String): String = if (workspace.isEmpty()) "catalog" else if (session.isEmpty()) workspace(workspace).tString("sha256") else sessionInfo(workspace, session).tString("revision")
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
    private fun dexes(workspace: String, session: String): Sequence<Pair<String,ToolkitDex>> = entries(workspace, session).asSequence().filter { ToolkitApkTargets.isDex(it) }.map { it to ToolkitDex(raw(workspace, session, it)) }
    private fun dexFor(workspace: String, session: String, locator: String, requestedDex:String=""): Pair<String,ToolkitDex> {
        val found = dexes(workspace, session).filter { (entry, _) -> requestedDex.isEmpty() || requestedDex==entry }.filter { (_, dex) -> dex.classes.any { it.type == dex.type(locator) } }.toList()
        require(found.size == 1) { if(found.isEmpty()) "DEX 类不存在" else "多个 DEX 含同名类，暂不支持此定位符" }; return found.single()
    }
    private fun entryLocator(locator: String): String {
        require(locator.startsWith("zip_entry:")) { "需要 zip_entry: 定位符" }
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
        }; return page(workspace, session, items, args.tInt("limit", 200, 1, 1000), limitMax = 1000)
    }
    private fun page(workspace: String, session: String, items: List<JsonObject>, limit: Int, truncated: Boolean = false,
                     kind: String = "items", metadata: JsonObject = JsonObject(), limitMax: Int = 200): JsonObject {
        val key = if (items.size > limit) ToolkitIo.fresh() else ""
        if (key.isNotEmpty()) {
            require(items.size <= 80000) { "结果过大，请缩小 prefix 或搜索范围" }
            while (pages.isNotEmpty() && (pages.size >= 16 || pages.values.sumOf { it.items.size } + items.size > 80000)) pages.remove(pages.keys.first())
            pages[key] = Page(workspace, session, revision(workspace, session), items, limit, kind, metadata, truncated, limitMax)
        }
        return paged(workspace, session, items.take(limit), key, items.size, truncated, kind, metadata, limitMax)
    }
    private fun paged(workspace: String, session: String, items: List<JsonObject>, key: String, total: Int, truncated: Boolean,
                      kind: String = "items", metadata: JsonObject = JsonObject(), limitMax: Int = 200): JsonObject {
        val actions = JsonArray()
        if(key.isNotEmpty()) actions.add(action("apk_continue", ToolkitIo.json("workspaceId" to workspace, "editSessionId" to session, "nextCursor" to key, "limit" to 200)))
        val data = ToolkitIo.json("workspaceId" to workspace, "editSessionId" to session, "items" to items,
            "nextCursor" to key, "hasMore" to key.isNotEmpty(), "total" to total, "truncated" to truncated,
            "pagination" to ToolkitIo.json("hasMore" to key.isNotEmpty(), "nextCursor" to key.takeIf { it.isNotEmpty() },
                "returnedCount" to items.size, "limitMax" to limitMax, "totalAvailableCount" to if(truncated) null else total))
        if(kind == "search") data.add("hits", data.get("items"))
        if(kind == "refs") data.add("refs", data.get("items"))
        metadata.entrySet().forEach { (name, value) -> data.add(name, value) }
        return ToolkitIo.ok(data, actions)
    }
    private fun continuePage(args: JsonObject): JsonObject {
        val key = args.tString("nextCursor"); val page = pages[key] ?: error("分页游标不存在或已过期")
        require(page.workspace == args.tString("workspaceId", "") && page.session == args.tString("editSessionId", "")) { "分页游标不属于此工作区或会话" }
        require(System.currentTimeMillis() - page.at < 300000 && page.revision == revision(page.workspace, page.session)) { "内容已变更或游标已过期，请重新查询" }
        val next = minOf(page.offset + args.tInt("limit", 200, 1, page.limitMax), page.items.size); val values = page.items.subList(page.offset, next)
        page.offset = next; if (next == page.items.size) pages.remove(key)
        return paged(page.workspace, page.session, values, if(next < page.items.size) key else "", page.items.size, page.truncated, page.kind, page.metadata, page.limitMax)
    }
    private fun action(name: String, args: JsonObject) = ToolkitIo.json("tool" to name, "arguments" to args)
    private fun search(workspace: String, session: String, args: JsonObject): JsonObject {
        val result = ToolkitApkSearch(entries(workspace, session), { raw(workspace, session, it) },
            { optionalResources(workspace, session) }, { dexes(workspace, session) }).search(args)
        return page(workspace, session, result.hits, args.tInt("limit", 50, 1, 200), result.partial, "search", result.metadata)
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
    }
    private fun deleteTarget(workspace: String, session: String, args: JsonObject): JsonObject {
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
    }
    private fun patchBytes(workspace: String, session: String, args: JsonObject): JsonObject {
        val entry = entryLocator(args.tString("locator")); val bytes = raw(workspace, session, entry)
        ToolkitApkTargets.ordinary(entry, bytes)
        val patches = args.getAsJsonArray("patches") ?: error("缺少 patches"); require(patches.size() in 1..200)
        val ranges = ArrayList<IntRange>()
        patches.forEach { raw -> val patch = raw.asJsonObject; val before = ToolkitIo.unhex(patch.tString("expectedHex")); val after = ToolkitIo.unhex(patch.tString("replacementHex")); val start = patch.tInt("byteOffset", 0, 0, bytes.size)
            require(before.isNotEmpty() && before.size == after.size && before.size <= bytes.size - start) { "原始字节补丁必须等长且不越界" }
            val range = start until start + before.size; require(ranges.none { it.first <= range.last && range.first <= it.last }) { "补丁范围重叠" }; ranges.add(range)
            require(bytes.copyOfRange(start, start + before.size).contentEquals(before)) { "原始字节不匹配" }; after.copyInto(bytes, start)
        }; write(workspace, session, mapOf(entry to bytes)); return ToolkitIo.json("locator" to "zip_entry:$entry", "targetVersion" to ToolkitIo.version(bytes), "patched" to true)
    }
    private fun elf(workspace: String, session: String, args: JsonObject) = ToolkitElf(raw(workspace, session, entryLocator(args.tString("locator"))))
    private fun patchNativeString(workspace: String, session: String, args: JsonObject): JsonObject {
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
        return page(workspace, session, items, args.tInt("limit", 50, 1, 200), partial)
    }
    private fun check(workspace: String, session: String, run: Boolean): JsonObject {
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
    }
    private fun build(workspace: String, session: String, args: JsonObject): JsonObject {
        require(workspace(workspace).tString("artifactType","apk")=="apk") { "此工作区不是APK；独立文件使用artifact_export，分包请先选择成员" }
        val checked = check(workspace, session, true); require(checked.getAsJsonArray("failures").size() == 0) { "会话检查失败：${checked.get("failures")}" }
        val signing = args.tBool("sign", false)
        val requested = args.tString("outputName", "")
        val name = requested.ifEmpty { File(workspace(workspace).tString("sourcePath")).nameWithoutExtension + "_mcp_${session}_${if(signing) "sign" else "unsigned"}.apk" }
        require(File(name).isAbsolute || !name.contains('/')) { "outputName 只接受文件名或绝对路径" }
        require(name.endsWith(".apk", true)); val output = ToolkitIo.resolve(root, name)
        require(output != File(workspace(workspace).tString("sourcePath"))) { "不能覆盖源 APK" }
        require(!output.exists() || output.isFile && args.tBool("overwrite")) { "输出已存在或不是普通文件，需 overwrite=true" }
        val names = entries(workspace, session).filterNot { it.startsWith("META-INF/", true) && (it.endsWith(".SF", true) || it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true) || it.equals("META-INF/MANIFEST.MF", true)) }
        var verification:JsonObject?=null
        ToolkitIo.atomic(output) { target ->
            if (!signing) writeUnsigned(workspace, session, names, target) else {
                val callback = signer ?: error("当前环境没有 APK 签名器")
                val unsigned = File(sessionDir(workspace, session), "unsigned-${ToolkitIo.fresh()}.apk")
                try {
                    writeUnsigned(workspace, session, names, unsigned)
                    callback(unsigned, target)
                    val signature = ToolkitSignatures.read(target)
                    require(signature.tBool("available") && signature.tBool("signaturePresent") && signature.getAsJsonArray("certificates").size() > 0) { "签名器没有生成完整可读取的证书信息：${signature.get("error")}" }
                } finally { unsigned.delete() }
            }
            val verified=ToolkitApkVerifier.inspect(target)
            require(verified.getAsJsonObject("checks").getAsJsonObject("zip").tString("status")=="valid") { "导出ZIP对齐或结构检查失败：${verified.get("issues")}" }
            if(signing) require(verified.getAsJsonObject("signature").tBool("verified")) { "导出APK密码学验签失败：${verified.get("issues")}" }
            verification=verified
            require(target.length() <= ToolkitIo.MAX_APK) { "构建结果超过 512 MiB" }; ZipFile(target).use { require(it.getEntry("AndroidManifest.xml") != null) }
        }
        val signature = ToolkitSignatures.read(output)
        val result = ToolkitIo.json("path" to output.path, "size" to output.length(), "sha256" to ToolkitIo.sha(output), "signed" to signing,
            "signatureSummary" to signature, "verification" to verification,
            "signingIdentity" to if(signing) "AI.js Pro 本机身份，替换原签名" else "unsigned", "sourceUnchanged" to true)
        listOf("changedDexEntries", "changedClassCount", "deletedClassCount", "addedClassCount", "addedZipEntries", "changedZipEntries", "deletedZipEntries").forEach {
            result.add(it, checked.get(it))
        }
        return result
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
    private fun streamEntry(workspace: String, session: String, entry: String, consume: (InputStream) -> Unit) {
        if(session.isNotEmpty()) require(sessionInfo(workspace, session).getAsJsonArray("deleted").none { it.asString == entry }) { "条目已删除" }
        val changed = if(session.isEmpty()) null else sessionInfo(workspace, session).getAsJsonObject("changedEntries").get(entry)?.asString
        if(changed != null) File(File(sessionDir(workspace, session), "entries"), ToolkitIo.id(changed)).inputStream().use(consume)
        else ZipFile(snapshot(workspace)).use { zip -> val item = zip.getEntry(entry) ?: error("条目不存在"); zip.getInputStream(item).use(consume) }
    }
    private fun copyEntry(workspace: String, session: String, args: JsonObject): JsonObject {
        val entry = entryLocator(args.tString("locator")); val local = ToolkitIo.resolve(root, args.tString("localPath")); val overwrite = args.tBool("overwrite")
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
    private fun readSignature(apk: File): JsonObject {
        val signature = ToolkitSignatures.read(apk)
        return ToolkitIo.json("signature" to signature, "certificates" to signature.get("certificates"),
            "verified" to false, "coverage" to "V1/V2/V3/V3.1 certificate metadata")
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
