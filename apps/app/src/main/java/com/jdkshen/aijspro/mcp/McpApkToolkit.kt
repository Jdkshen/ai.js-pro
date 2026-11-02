package com.jdkshen.aijspro.mcp

import com.google.gson.*
import java.io.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.*

/** Immutable APK snapshots and copy-on-write edit sessions. Never calls MT. */
internal class McpApkToolkit(
    private val root: File,
    private val store: File,
    private val schemas: JsonObject,
    private val signer: ((File, File) -> Unit)? = null,
    private val assembler: ((Int, Long, String) -> ByteArray)? = null,
) {
    private val pages = LinkedHashMap<String, Page>()
    private data class Page(val workspace: String, val session: String, val revision: String, val items: List<JsonObject>, var offset: Int, val at: Long = System.currentTimeMillis())
    private val gson = Gson()
    init { require(store.isDirectory || store.mkdirs()) { "无法创建 APK 工作区目录" } }
    fun definitions(): JsonArray = JsonArray().apply {
        schemas.entrySet().forEach { (name, schema) -> add(ToolkitIo.json("name" to name, "category" to "toolkit", "categoryTitle" to "文件与APK工具",
            "description" to description(name), "inputSchema" to schema)) }
    }
    @Synchronized fun call(name: String, args: JsonObject): JsonObject {
        return when (name) {
            "apk_workspace_open" -> ToolkitIo.ok(open(args))
            "apk_workspace_list" -> ToolkitIo.ok(ToolkitIo.json("items" to workspaces().filter { !it.tBool("temporary") }))
            "apk_list_available_apks" -> listAvailable(args)
            "apk_workspace_delete" -> {
                val workspace = workspace(args.tString("workspaceId")); val id = workspace.tString("workspaceId")
                safeRemove(directory(id), store); pages.entries.removeAll { it.value.workspace == id }
                ToolkitIo.ok(ToolkitIo.json("workspaceId" to id, "deleted" to true))
            }
            "apk_continue" -> continuePage(args)
            "apk_read_signature" -> ToolkitIo.ok(readSignature(snapshot(args.tString("workspaceId"))))
            else -> {
                val workspace = args.tString("workspaceId"); workspace(workspace)
                val session = args.tString("editSessionId", "")
                if (session.isNotEmpty()) sessionDir(workspace, session)
                when (name) {
                    "apk_session_create" -> ToolkitIo.ok(createSession(workspace))
                    "apk_list" -> list(workspace, session, args)
                    "apk_search" -> search(workspace, session, args)
                    "apk_read_text" -> ToolkitIo.ok(readText(workspace, session, args))
                    "apk_read_bytes" -> ToolkitIo.ok(readBytes(workspace, session, args))
                    "apk_dex_read_class_outline" -> {
                        val (entry, dex) = dexFor(workspace, session, args.tString("locator"))
                        page(workspace, session, dex.outline(args.tString("locator")).map { it.apply { addProperty("dexEntry", entry) } }, args.tInt("limit", 200, 1, 200))
                    }
                    "apk_dex_xref" -> {
                        val locator = args.tString("locator"); val limit = args.tInt("limit", 50, 1, 200)
                        val items = ArrayList<JsonObject>()
                        for ((entry, dex) in dexes(workspace, session)) {
                            if (items.size >= 10000) break
                            items.addAll(dex.references(locator, args.tString("methodResolution", "exact"), 10001 - items.size).map { it.apply { addProperty("dexEntry", entry) } })
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
                        val data = page(workspace, session, e.items(args.tString("itemKind"), args.tString("namePrefix", "")), args.tInt("limit", 50, 1, 200))
                        data.getAsJsonObject("data").addProperty("demangleAvailable", false); data
                    }
                    "apk_native_map_address" -> ToolkitIo.ok(elf(workspace, session, args).map(args))
                    "apk_native_disassemble" -> {
                        val elf = elf(workspace, session, args); val instructions = elf.instructions(args)
                        ToolkitIo.ok(ToolkitIo.json("items" to instructions.map { elf.instructionJson(it) }, "decodedInstructions" to instructions.count { it.decoded },
                            "undecodedInstructions" to instructions.count { !it.decoded }, "complete" to instructions.all { it.decoded }))
                    }
                    "apk_native_xref" -> ToolkitIo.ok(elf(workspace, session, args).xref(args))
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
        }
    }
    private fun description(name: String): String = when(name) {
        "apk_workspace_open" -> "创建 APK 只读快照。path 在工具目录内；temporary=true 为一次性工作区。后续修改先 apk_session_create。"
        "apk_build" -> "校验编辑会话，导出新 APK；不修改源包。sign=true 使用本机 AI.js Pro 签名身份，不能沿用原作者签名。"
        "apk_native_disassemble", "apk_native_xref", "apk_native_analyze_control_flow" -> "有界直接指令分析：原生库可用时覆盖 AArch64、ARM/Thumb、x86/x86-64（Capstone 5.0.6），否则回退 AArch64 常见指令子集；未知指令明确标出，间接引用与完整数据流不作成功保证。"
        "apk_native_patch_instructions" -> "会话中汇编并等长修改 AArch64 指令，expectedHex 防旧数据。内置 nop/ret/br/blr/b/bl/.inst；其它指令需可选 C/C++ 组件。"
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
        if (path.startsWith("aijs://workspace/") || path.startsWith("mt://workspace/")) {
            require(!temporary) { "已保存工作区需 temporary=false" }; return workspace(path.substringAfterLast('/'))
        }
        val source = ToolkitIo.resolve(root, path); require(source.isFile && source.name.endsWith(".apk", true) && source.length() <= ToolkitIo.MAX_APK) { "需要工具目录内不超过 512 MiB 的 APK" }
        val hash = ToolkitIo.sha(source)
        if (!temporary) workspaces().firstOrNull { !it.tBool("temporary") && it.tString("sourcePath") == source.path && it.tString("sha256") == hash }?.let { return it }
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
            File(folder, "workspace.json").writeText(gson.toJson(info)); return info
        } catch (error: Exception) { safeRemove(folder, store); throw error }
    }
    private fun listAvailable(args: JsonObject): JsonObject {
        val prefix = args.tString("prefix", ""); var visited = 0; var truncated = false; val items = ArrayList<JsonObject>()
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
        val info = ToolkitIo.json("workspaceId" to workspace, "editSessionId" to id, "revision" to ToolkitIo.fresh(), "deleted" to emptyList<String>(), "changedEntries" to emptyMap<String,String>())
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
    private fun write(workspace: String, session: String, changes: Map<String,ByteArray>, deletions: Set<String> = emptySet()) {
        val folder = sessionDir(workspace, session); val info = sessionInfo(workspace, session)
        val changed = info.getAsJsonObject("changedEntries"); val deleted = info.getAsJsonArray("deleted").map { it.asString }.toMutableSet()
        val created = ArrayList<File>(); val superseded = ArrayList<File>()
        try {
            changes.forEach { (path, bytes) ->
                ToolkitIo.entry(path); require(bytes.size <= ToolkitIo.MAX_ENTRY)
                val file = File(File(folder, "entries"), ToolkitIo.fresh()); file.writeBytes(bytes); created.add(file)
                changed.get(path)?.asString?.let { superseded.add(File(file.parentFile, ToolkitIo.id(it))) }
                changed.addProperty(path, file.name); deleted.remove(path)
            }
            deletions.forEach { path -> ToolkitIo.entry(path); changed.remove(path)?.asString?.let { superseded.add(File(File(folder, "entries"), ToolkitIo.id(it))) }; deleted.add(path) }
            require(changed.size() <= 2000 && File(folder, "entries").listFiles().orEmpty().sumOf { it.length() } <= 512L * 1024 * 1024) { "编辑会话超过容量限制" }
            info.add("deleted", gson.toJsonTree(deleted)); info.addProperty("revision", ToolkitIo.fresh())
            ToolkitIo.atomic(File(folder, "session.json")) { it.writeText(gson.toJson(info)) }
            superseded.forEach { it.delete() }
        } catch (error: Exception) { created.forEach { it.delete() }; throw error }
    }
    private fun resourceTable(workspace: String, session: String): ToolkitResources = ToolkitResources(raw(workspace, session, "resources.arsc"))
    private fun optionalResources(workspace: String, session: String): ToolkitResources? = if (entries(workspace, session).contains("resources.arsc")) resourceTable(workspace, session) else null
    private fun dexes(workspace: String, session: String): Sequence<Pair<String,ToolkitDex>> = entries(workspace, session).asSequence().filter { it.matches(Regex("classes(?:[2-9][0-9]*)?\\.dex")) }.map { it to ToolkitDex(raw(workspace, session, it)) }
    private fun dexFor(workspace: String, session: String, locator: String): Pair<String,ToolkitDex> {
        val found = dexes(workspace, session).filter { (_, dex) -> dex.classes.any { it.type == dex.type(locator) } }.toList()
        require(found.size == 1) { if(found.isEmpty()) "DEX 类不存在" else "多个 DEX 含同名类，暂不支持此定位符" }; return found.single()
    }
    private fun entryLocator(locator: String): String { require(locator.startsWith("zip_entry:")) { "需要 zip_entry: 定位符" }; return ToolkitIo.entry(locator.removePrefix("zip_entry:")) }
    private fun text(workspace: String, session: String, locator: String): String = when {
        locator.startsWith("dex_") -> dexFor(workspace, session, locator).second.render(locator)
        locator.startsWith("resource:") -> resourceTable(workspace, session).let { it.xml(it.find(locator, "")) }
        else -> {
            val bytes = raw(workspace, session, entryLocator(locator), ToolkitIo.MAX_TEXT)
            if (ToolkitResources.isBinaryXml(bytes)) ToolkitResources.decodeXml(bytes, optionalResources(workspace, session)) else ToolkitIo.text(bytes)
        }
    }
    private fun readText(workspace: String, session: String, args: JsonObject): JsonObject {
        val locator = args.tString("locator"); val content = text(workspace, session, locator).replace("\r\n", "\n").replace('\r', '\n')
        val lines = content.split('\n'); val line = args.tInt("startLine", 0, 0, lines.size); val column = args.tInt("startColumn", 0, 0, if(line < lines.size) lines[line].length else 0)
        val maxChars = args.tInt("maxChars", 20000, 1, 65536); val limit = args.tInt("limit", 500, 1, 2000)
        val offset = lines.take(line).sumOf { it.length + 1 } + column
        val value = content.substring(minOf(offset, content.length)).lineSequence().take(limit).joinToString("\n").take(maxChars)
        val end = minOf(content.length, offset + value.length); val prefix = content.substring(0, end)
        val nextLine = prefix.count { it == '\n' }; val nextColumn = prefix.length - (prefix.lastIndexOf('\n') + 1)
        return ToolkitIo.json("locator" to locator, "text" to value, "targetVersion" to ToolkitIo.version(content.toByteArray(Charsets.UTF_8)), "startLine" to line, "startColumn" to column,
            "nextLine" to nextLine, "nextColumn" to nextColumn, "hasMore" to (end < content.length), "totalChars" to content.length)
    }
    private fun readBytes(workspace: String, session: String, args: JsonObject): JsonObject {
        val locator = args.tString("locator"); val bytes = raw(workspace, session, entryLocator(locator))
        val offset = args.tInt("byteOffset", 0, 0, bytes.size); val count = minOf(args.tInt("maxBytes", 256, 1, 65536), bytes.size - offset)
        return ToolkitIo.json("locator" to locator, "targetVersion" to ToolkitIo.version(bytes), "byteWindow" to ToolkitIo.json("byteOffset" to offset, "hex" to ToolkitIo.hex(bytes.copyOfRange(offset, offset + count)), "byteCount" to count),
            "nextByteOffset" to offset + count, "hasMore" to (offset + count < bytes.size), "size" to bytes.size)
    }
    private fun list(workspace: String, session: String, args: JsonObject): JsonObject {
        val prefix = args.tString("prefix", ""); val view = args.tString("view", "zip_entries")
        val items = when (view) {
            "zip_entries" -> entries(workspace, session).filter { it.startsWith(prefix) }.map { ToolkitIo.json("locator" to "zip_entry:$it", "path" to it) }
            "dex_classes" -> dexes(workspace, session).flatMap { (entry, dex) -> dex.classes.asSequence().filter { it.type.startsWith(prefix) }.map { ToolkitIo.json("locator" to "dex_class:${it.type}", "type" to it.type, "dexEntry" to entry) } }.toList()
            "resource_table" -> resourceTable(workspace, session).let { resources -> resources.entries().filter { it.name.startsWith(prefix) || resources.locator(it).startsWith(prefix) }.map { resources.item(it) } }
            else -> throw IllegalArgumentException("view 无效")
        }; return page(workspace, session, items, args.tInt("limit", 200, 1, 200))
    }
    private fun page(workspace: String, session: String, items: List<JsonObject>, limit: Int, truncated: Boolean = false): JsonObject {
        val key = if (items.size > limit) ToolkitIo.fresh() else ""
        if (key.isNotEmpty()) {
            require(items.size <= 80000) { "结果过大，请缩小 prefix 或搜索范围" }
            while (pages.isNotEmpty() && (pages.size >= 16 || pages.values.sumOf { it.items.size } + items.size > 80000)) pages.remove(pages.keys.first())
            pages[key] = Page(workspace, session, revision(workspace, session), items, limit)
        }
        return paged(workspace, session, items.take(limit), key, items.size, truncated)
    }
    private fun paged(workspace: String, session: String, items: List<JsonObject>, key: String, total: Int, truncated: Boolean): JsonObject {
        val actions = JsonArray()
        if(key.isNotEmpty()) actions.add(action("apk_continue", ToolkitIo.json("workspaceId" to workspace, "editSessionId" to session, "nextCursor" to key, "limit" to 200)))
        return ToolkitIo.ok(ToolkitIo.json("items" to items, "nextCursor" to key, "hasMore" to key.isNotEmpty(), "total" to total, "truncated" to truncated), actions)
    }
    private fun continuePage(args: JsonObject): JsonObject {
        val key = args.tString("nextCursor"); val page = pages[key] ?: error("分页游标不存在或已过期")
        require(page.workspace == args.tString("workspaceId", "") && page.session == args.tString("editSessionId", "")) { "分页游标不属于此工作区或会话" }
        require(System.currentTimeMillis() - page.at < 300000 && page.revision == revision(page.workspace, page.session)) { "内容已变更或游标已过期，请重新查询" }
        val next = minOf(page.offset + args.tInt("limit", 200, 1, 200), page.items.size); val values = page.items.subList(page.offset, next)
        page.offset = next; if (next == page.items.size) pages.remove(key)
        return paged(page.workspace, page.session, values, if(next < page.items.size) key else "", page.items.size, false)
    }
    private fun action(name: String, args: JsonObject) = ToolkitIo.json("tool" to name, "arguments" to args)
    private fun search(workspace: String, session: String, args: JsonObject): JsonObject {
        val query = args.tString("query"); require(query.isNotEmpty() && query.length <= 512) { "搜索词长度必须为 1..512" }
        val target = args.tString("target", "overview"); val prefix = args.tString("prefix", ""); val exact = args.tString("matchMode", "contains") == "exact"
        val queryType = args.tString("queryType", "literal"); require(queryType in listOf("literal", "regex"))
        val ignore = !args.tBool("caseSensitive")
        val pattern = if(queryType == "regex") com.google.re2j.Pattern.compile(query, if(ignore) com.google.re2j.Pattern.CASE_INSENSITIVE else 0) else null
        val result = ArrayList<JsonObject>(); var scanned = 0; var truncated = false
        val max = 10000; val snippet = args.tInt("snippetMaxChars", 240, 1, 2000)
        fun add(item: JsonObject, value: String) {
            if (++scanned > 300000 || result.size >= max) { truncated = true; return }
            val matcher = pattern?.matcher(value)
            val matched = if(matcher != null) { if(exact) matcher.matches() else matcher.find() } else if(exact) value.equals(query, ignore) else value.contains(query, ignore)
            val at = if(matcher != null && matched) matcher.start() else value.indexOf(query, ignoreCase = ignore)
            if (matched) {
                val copy = item.deepCopy(); copy.addProperty("snippet", value.take(snippet))
                if (args.tBool("includeMatchOffsets")) copy.addProperty("matchOffset", at); result.add(copy)
            }
        }
        val names = entries(workspace, session)
        if (target in listOf("overview", "files")) names.filter { it.startsWith(prefix) }.forEach { add(ToolkitIo.json("locator" to "zip_entry:$it", "kind" to "file"), it) }
        if (target in listOf("overview", "resource_table_names", "resource_table_values", "resource_table_file_paths") && "resources.arsc" in names) {
            val resources = resourceTable(workspace, session)
            resources.entries().forEach { e -> if (e.name.startsWith(prefix)) add(resources.item(e), if (target == "resource_table_names") e.typeName + "/" + e.name else if(target == "overview") e.name + " " + resources.xml(e) else resources.xml(e)) }
        }
        if (target in listOf("overview", "dex_names", "dex_strings", "dex_string_members", "smali")) for ((entry, dex) in dexes(workspace, session)) {
            if (truncated) break
            if (target in listOf("overview", "dex_names", "smali")) dex.classes.filter { it.type.startsWith(prefix) }.forEach { c -> if (!truncated) add(ToolkitIo.json("locator" to "dex_class:${c.type}", "dexEntry" to entry), if (target == "smali") dex.renderClass(c) else c.type) }
            if (target in listOf("dex_strings", "dex_string_members")) dex.strings(100001).forEach { item -> if(!truncated && item.tString("locator").substringAfter(':').startsWith(prefix.removePrefix("dex_method:"), false)) add(item, item.tString("value")) }
        }
        if (target in listOf("native_symbols", "native_strings")) names.filter { it.endsWith(".so") && it.startsWith(prefix) }.forEach { entry ->
            if(!truncated) {
                val elf = ToolkitElf(raw(workspace, session, entry)); val items = if(target == "native_symbols") elf.items("symbols", "") else elf.strings(100001)
                items.forEach { item -> if(!truncated) add(item.apply { addProperty("locator", "zip_entry:$entry") }, item.get("value")?.asString ?: item.tString("name", "")) }
            }
        }
        require(target in listOf("overview","files","resource_table_names","resource_table_values","resource_table_file_paths","dex_names","dex_strings","dex_string_members","smali","native_symbols","native_strings")) { "搜索范围无效" }
        return page(workspace, session, result, args.tInt("limit", 50, 1, 200), truncated)
    }
    private fun readResources(workspace: String, session: String, args: JsonObject): JsonObject {
        val resources = resourceTable(workspace, session); val reads = args.getAsJsonArray("reads") ?: error("缺少 reads"); require(reads.size() in 1..200)
        val maxValue = args.tInt("maxValueChars", 4096, 1, 65536); val maxXml = args.tInt("maxValueXmlChars", 16384, 1, 65536)
        val values = reads.map { read ->
            val request = read.asJsonObject; val e = resources.find(request.tString("locator"), request.tString("variant", "")); val full = resources.item(e, true)
            val value = full.get("value")?.takeIf { !it.isJsonNull }?.asString.orEmpty(); val xml = full.tString("valueXml")
            full.addProperty("value", value.take(maxValue)); full.addProperty("valueXml", xml.take(maxXml)); full.addProperty("valueXmlComplete", xml.length <= maxXml)
            full.addProperty("valueComplete", value.length <= maxValue); full.addProperty("referencesResolved", false); full
        }; return ToolkitIo.json("items" to values, "note" to "返回确切资源 XML；引用保留，不将 resolveDepth 当作已完成引用展开")
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
                values.forEach { item ->
                    val v = item.asJsonObject; val entry = resources.add(if(existing != null) "0x%02x".format(existing.resourceId ushr 24) else edit.tString("packageId"), v.tString("variant", ""), v.tString("valueXml"))
                    if(existing != null) require(entry.resourceId == existing.resourceId) { "新增配置的资源名/类型必须与定位符一致" }; results.add(resources.item(entry))
                }
            }
        }
        write(workspace, session, mapOf("resources.arsc" to resources.bytes())); return ToolkitIo.json("items" to results, "savedToSession" to true)
    }
    private fun editText(workspace: String, session: String, args: JsonObject): JsonObject {
        val locator = args.tString("locator"); val old = runCatching { text(workspace, session, locator).replace("\r\n", "\n").replace('\r', '\n') }.getOrElse { if(args.tString("targetVersion") == "missing" && locator.startsWith("zip_entry:")) "" else throw it }
        val exists = !(args.tString("targetVersion") == "missing")
        require(!exists || ToolkitIo.version(old.toByteArray()) == args.tString("targetVersion")) { "内容已改变，请重新读取" }
        if(!exists) require(entryLocator(locator) !in entries(workspace, session)) { "条目已经存在" }
        val edits = args.getAsJsonArray("edits") ?: error("缺少 edits"); require(edits.size() in 1..200); var content = old
        edits.forEach { raw ->
            val edit = raw.asJsonObject; val mode = edit.tString("mode"); val match = edit.tString("matchText", ""); val value = edit.tString("writeText")
            if(mode == "write_target") content = value else {
                require(match.isNotEmpty()) { "匹配文本不能为空" }; val at = content.indexOf(match); require(at >= 0 && content.indexOf(match, at + match.length) < 0) { "匹配文本必须唯一命中" }
                content = when(mode) { "replace_match" -> content.replaceRange(at, at + match.length, value); "insert_before_match" -> content.replaceRange(at, at, value); "insert_after_match" -> content.replaceRange(at + match.length, at + match.length, value); else -> error("编辑模式无效") }
            }; require(content.toByteArray().size <= ToolkitIo.MAX_TEXT) { "文本超过 10 MiB" }
        }
        if(locator.startsWith("dex_")) {
            val (entry, dex) = dexFor(workspace, session, locator); write(workspace, session, mapOf(entry to dex.update(locator, content, sessionDir(workspace, session))))
        } else {
            val entry = entryLocator(locator); require(entry != "resources.arsc" && !entry.endsWith(".dex")) { "资源表用资源接口，DEX 用 Smali 定位符" }
            val bytes = if(exists && ToolkitResources.isBinaryXml(raw(workspace, session, entry))) ToolkitResources.encodeXml(content, optionalResources(workspace, session)) else content.toByteArray(Charsets.UTF_8)
            write(workspace, session, mapOf(entry to bytes))
        }
        return ToolkitIo.json("locator" to locator, "targetVersion" to ToolkitIo.version(content.toByteArray()), "savedToSession" to true)
    }
    private fun deleteTarget(workspace: String, session: String, args: JsonObject): JsonObject {
        val locator = args.tString("locator")
        if(locator.startsWith("dex_")) { val (entry, dex) = dexFor(workspace, session, locator); write(workspace, session, mapOf(entry to dex.update(locator, null, sessionDir(workspace, session)))) }
        else { val entry = entryLocator(locator); require(entry in entries(workspace, session)); write(workspace, session, emptyMap(), setOf(entry)) }
        return ToolkitIo.json("deleted" to true, "locator" to locator)
    }
    private fun patchBytes(workspace: String, session: String, args: JsonObject): JsonObject {
        val entry = entryLocator(args.tString("locator")); require(!entry.endsWith(".dex")) { "DEX 修改请使用 Smali，避免破坏签名和校验和" }
        val bytes = raw(workspace, session, entry); val patches = args.getAsJsonArray("patches") ?: error("缺少 patches"); require(patches.size() in 1..200)
        val ranges = ArrayList<IntRange>()
        patches.forEach { raw -> val patch = raw.asJsonObject; val before = ToolkitIo.unhex(patch.tString("expectedHex")); val after = ToolkitIo.unhex(patch.tString("replacementHex")); val start = patch.tInt("byteOffset", 0, 0, bytes.size)
            require(before.isNotEmpty() && before.size == after.size && before.size <= bytes.size - start) { "原始字节补丁必须等长且不越界" }
            val range = start until start + before.size; require(ranges.none { it.first <= range.last && range.first <= it.last }) { "补丁范围重叠" }; ranges.add(range)
            require(bytes.copyOfRange(start, start + before.size).contentEquals(before)) { "原始字节不匹配" }; after.copyInto(bytes, start)
        }; write(workspace, session, mapOf(entry to bytes)); return ToolkitIo.json("locator" to "zip_entry:$entry", "targetVersion" to ToolkitIo.version(bytes), "patched" to true)
    }
    private fun elf(workspace: String, session: String, args: JsonObject) = ToolkitElf(raw(workspace, session, entryLocator(args.tString("locator"))))
    private fun patchNativeString(workspace: String, session: String, args: JsonObject): JsonObject {
        val elf = elf(workspace, session, args); val offset = elf.fileOffset(elf.address(args)) ?: error("地址没有文件内容")
        val old = args.tString("expectedText").toByteArray(Charsets.UTF_8); val replacement = args.tString("replacementText").toByteArray(Charsets.UTF_8)
        require(old.isNotEmpty() && replacement.size <= old.size && old.size <= elf.bytes.size - offset) { "字符串补丁不能增长或越界" }
        val mode = args.tString("paddingMode", "none"); require(mode in listOf("none","null_pad","space_pad")); require(mode != "none" || old.size == replacement.size)
        val output = ByteArray(old.size) { if(mode == "space_pad") 32 else 0 }; replacement.copyInto(output)
        return patchBytes(workspace, session, ToolkitIo.json("locator" to args.tString("locator"), "patches" to listOf(ToolkitIo.json("byteOffset" to offset, "expectedHex" to ToolkitIo.hex(old), "replacementHex" to ToolkitIo.hex(output)))))
    }
    private fun patchInstructions(workspace: String, session: String, args: JsonObject): JsonObject {
        val elf = elf(workspace, session, args); require(elf.machine == 183 && elf.little) { "当前指令补丁支持 AArch64" }
        val address = elf.address(args); val offset = elf.fileOffset(address) ?: error("地址没有文件内容")
        require(address % 4 == 0L && elf.segments.any { it.type == 1L && it.flags and 1 != 0L && address >= it.address && address - it.address < it.size }) { "指令必须在对齐的可执行区段" }
        val expected = ToolkitIo.unhex(args.tString("expectedHex")); require(expected.isNotEmpty() && expected.size % 4 == 0)
        val source = args.tString("replacementAssembly"); require(source.length <= 65536 && !Regex("(?m)^\\s*\\.(?!inst\\b)[A-Za-z]").containsMatchIn(source)) { "汇编仅接受指令、标签与 .inst，不允许文件或宏指令" }
        val compiled = ToolkitElf.assembleSmall(source, address) ?: assembler?.invoke(elf.machine, address, source) ?: error("该汇编指令需安装可选 C/C++ 组件：应用终端运行 aijs-toolchain install cpp")
        require(compiled.isNotEmpty() && compiled.size <= expected.size && compiled.size % 4 == 0) { "汇编结果不能增长，且须为完整指令" }
        val mode = args.tString("paddingMode", "none"); require(mode in listOf("none", "nop")); require(mode != "none" || compiled.size == expected.size)
        val bytes = ByteArray(expected.size); compiled.copyInto(bytes)
        for (i in compiled.size until expected.size step 4) byteArrayOf(0x1f,0x20,0x03,0xd5.toByte()).copyInto(bytes, i)
        return patchBytes(workspace, session, ToolkitIo.json("locator" to args.tString("locator"), "patches" to listOf(ToolkitIo.json("byteOffset" to offset, "expectedHex" to ToolkitIo.hex(expected), "replacementHex" to ToolkitIo.hex(bytes)))))
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
        val info = sessionInfo(workspace, session); val changed = info.getAsJsonObject("changedEntries").keySet(); val failures = ArrayList<JsonObject>()
        if(run) {
            val names = entries(workspace, session)
            if("AndroidManifest.xml" !in names) failures.add(ToolkitIo.json("locator" to "zip_entry:AndroidManifest.xml", "error" to "缺少 Manifest"))
            changed.forEach { entry -> runCatching {
                val bytes = raw(workspace, session, entry)
                when { entry.endsWith(".dex") -> ToolkitDex(bytes).classes.forEach { it.methods.forEach { method -> method.implementation?.instructions?.count() } }
                    entry == "resources.arsc" -> ToolkitResources(bytes).entries()
                    entry.endsWith(".so") -> ToolkitElf(bytes)
                    ToolkitResources.isBinaryXml(bytes) -> ToolkitResources.decodeXml(bytes, optionalResources(workspace, session))
                }
            }.onFailure { failures.add(ToolkitIo.json("locator" to "zip_entry:$entry", "error" to it.message)) } }
        }
        return ToolkitIo.json("workspaceId" to workspace, "editSessionId" to session, "changedEntries" to changed, "deleted" to info.getAsJsonArray("deleted"),
            "buildChecksRun" to run, "valid" to if(run) failures.isEmpty() else null, "failures" to failures, "note" to "结构检查不保证应用业务行为或原签名兼容")
    }
    private fun build(workspace: String, session: String, args: JsonObject): JsonObject {
        val checked = check(workspace, session, true); require(checked.getAsJsonArray("failures").size() == 0) { "会话检查失败：${checked.get("failures")}" }
        val name = args.tString("outputName"); require(name.endsWith(".apk", true)); val output = ToolkitIo.resolve(root, name)
        require(output != File(workspace(workspace).tString("sourcePath"))) { "不能覆盖源 APK" }
        require(!output.exists() || args.tBool("overwrite")) { "输出已存在，需 overwrite=true" }
        val names = entries(workspace, session).filterNot { it.startsWith("META-INF/", true) && (it.endsWith(".SF", true) || it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true) || it.equals("META-INF/MANIFEST.MF", true)) }
        val signing = args.tBool("sign", false)
        ToolkitIo.atomic(output) { target ->
            if (!signing) ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
                names.forEach { entry -> zip.putNextEntry(ZipEntry(entry)); streamEntry(workspace, session, entry) { copyBounded(it, zip, ToolkitIo.MAX_APK) }; zip.closeEntry() }
            } else {
                val callback = signer ?: error("当前环境没有 APK 签名器")
                val folder = File(sessionDir(workspace, session), "sign-${ToolkitIo.fresh()}"); require(folder.mkdir())
                try {
                    var total = 0L
                    names.forEach { entry -> val file = ToolkitIo.resolve(folder, entry); require(file.parentFile.isDirectory || file.parentFile.mkdirs()); file.outputStream().use { out -> streamEntry(workspace, session, entry) { input -> total += copyBounded(input,out,ToolkitIo.MAX_APK-total) } } }
                    callback(folder, target)
                } finally { safeRemove(folder, sessionDir(workspace, session)) }
            }
            if(!signing) ToolkitArchiveAlignment.align(target)
            require(target.length() <= ToolkitIo.MAX_APK) { "构建结果超过 512 MiB" }; ZipFile(target).use { require(it.getEntry("AndroidManifest.xml") != null) }
        }
        return ToolkitIo.json("path" to output.path, "size" to output.length(), "sha256" to ToolkitIo.sha(output), "signed" to signing,
            "signingIdentity" to if(signing) "AI.js Pro 本机身份，替换原签名" else "unsigned", "sourceUnchanged" to true)
    }
    private fun streamEntry(workspace: String, session: String, entry: String, consume: (InputStream) -> Unit) {
        val changed = if(session.isEmpty()) null else sessionInfo(workspace, session).getAsJsonObject("changedEntries").get(entry)?.asString
        if(changed != null) File(File(sessionDir(workspace, session), "entries"), ToolkitIo.id(changed)).inputStream().use(consume)
        else ZipFile(snapshot(workspace)).use { zip -> val item = zip.getEntry(entry) ?: error("条目不存在"); zip.getInputStream(item).use(consume) }
    }
    private fun copyEntry(workspace: String, session: String, args: JsonObject): JsonObject {
        val entry = entryLocator(args.tString("locator")); val local = ToolkitIo.resolve(root, args.tString("localPath")); val overwrite = args.tBool("overwrite")
        when(args.tString("direction")) {
            "workspace_to_local" -> { require(!local.exists() || overwrite) { "本地文件已存在" }; ToolkitIo.atomic(local) { file -> file.outputStream().use { out -> streamEntry(workspace, session, entry) { copyBounded(it,out,ToolkitIo.MAX_APK) } } } }
            "local_to_edit_session" -> { requiredSession(session); require(local.isFile && local.length() <= ToolkitIo.MAX_ENTRY); require(entry !in entries(workspace, session) || overwrite) { "会话条目已存在" }; write(workspace, session, mapOf(entry to local.inputStream().use { ToolkitIo.read(it) })) }
            else -> error("复制方向无效")
        }; return ToolkitIo.json("copied" to true, "locator" to "zip_entry:$entry", "localPath" to local.path)
    }
    private fun readSignature(apk: File): JsonObject {
        // Read v1 certificates without asserting verification of the whole APK.
        val certs = ArrayList<JsonObject>()
        ZipFile(apk).use { zip -> zip.entries().asSequence().filter { it.name.startsWith("META-INF/", true) && (it.name.endsWith(".RSA", true) || it.name.endsWith(".DSA", true) || it.name.endsWith(".EC", true)) }.take(16).forEach { entry ->
            val bytes = ToolkitIo.read(zip.getInputStream(entry), 1024 * 1024)
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>().forEach { cert -> certs.add(ToolkitIo.json("subject" to cert.subjectX500Principal.name, "issuer" to cert.issuerX500Principal.name,
                "notBefore" to cert.notBefore.time, "notAfter" to cert.notAfter.time, "algorithm" to cert.sigAlgName, "sha256" to ToolkitIo.version(cert.encoded))) }
        } }
        return ToolkitIo.json("certificates" to certs, "verified" to false, "coverage" to "v1 certificate metadata", "note" to "未验证 APK 签名；只含 v2/v3 的包需使用独立签名校验器")
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
