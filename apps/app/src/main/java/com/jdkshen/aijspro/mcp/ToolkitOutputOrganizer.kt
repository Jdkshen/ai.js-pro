package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 产出归置器：给 MCP 的文件写入提供稳定的项目目录，并让 project_folder 显式管理它。
 *
 * 设计约束：
 * - 只操作操作根（root）内路径，不删除任何文件；tidy 只用 rename，失败写 skipped。
 * - 默认项目按操作根持久化在 store/project.json，不依赖某次对话。
 * - 归置只在目标是“根目录直下文件/裸文件名”时生效；显式子目录一律尊重调用方。
 */
internal class ToolkitOutputOrganizer(
    private val store: File,
    private val root: File,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** 文件工具消费的稳定签名：path 是最终实际路径；organized 仅在发生归置时非空。 */
    data class Result(val path: String, val organized: JsonObject?)

    private data class Project(val dir: File, val name: String)

    private val rootDir: File = root.canonicalFile
    private val storeDir: File = store.canonicalFile
    private val projectFile: File = File(storeDir, PROJECT_CONFIG)
    private val incomingName = "_incoming"

    init {
        require(rootDir.isDirectory) { "操作根必须是已存在目录：" + rootDir.path }
        require(storeDir.isDirectory || storeDir.mkdirs()) { "项目配置目录不可用：" + storeDir.path }
    }

    // ------------------------------------------------------------------
    // Lead 定稿 API
    // ------------------------------------------------------------------

    /**
     * 裸文件名/落在操作根 -> 改写进默认项目（无则自动建 mcp-out-YYYYMMDD）；
     * 显式子目录或 organize=false 不改写。rawPath 为空时原样返回空路径。
     */
    @Synchronized
    fun organize(rawPath: String?, organize: Boolean, label: String? = null): Result {
        val raw = rawPath ?: return Result("", null)
        if (raw.isBlank()) return Result(raw, null)
        val resolved = resolveInRoot(raw)
        if (!organize) return Result(resolved.path, null)
        // 只有直接落在操作根下的文件才归置；显式子目录/项目内路径不动。
        if (resolved.parentFile?.canonicalFile != rootDir) return Result(resolved.path, null)
        val hadProject = readProject()?.dir?.isDirectory == true
        val projectDir = ensureProject(null)
        val target = File(projectDir, resolved.name)
        if (target == resolved) return Result(resolved.path, null)
        val info = ToolkitIo.json(
            "from" to resolved.path,
            "to" to target.path,
            "projectDir" to projectDir.path,
            "reason" to if (hadProject) "default-project" else "auto-project",
            "label" to label,
        )
        return Result(target.path, info)
    }

    /**
     * 兼容重载（队友 C 的调用点用 File + args）——内部统一走上面的字符串实现，行为只有一份。
     * 读 args.organize（默认 true）与 args.label。
     */
    @Synchronized
    fun organize(path: File, args: JsonObject): OrganizedPath {
        val result = organize(
            path.path,
            args.tBool("organize", true),
            args.tString("label", "").ifEmpty { null },
        )
        return OrganizedPath(File(result.path), result.organized)
    }

    data class OrganizedPath(val path: File, val info: JsonObject?) {
        /** 与 Result.organized 同义，方便两种调用点共用。 */
        val organized: JsonObject? get() = info
    }
    /** 当前默认项目目录（没有就 null；不自动创建）。 */
    @Synchronized
    fun projectDir(): File? = readProject()?.dir

    /** 建/取项目目录并设为默认；name=null 表示当前默认或自动 mcp-out-YYYYMMDD。 */
    @Synchronized
    fun ensureProject(name: String?): File {
        if (!name.isNullOrBlank()) {
            val dir = File(rootDir, safeProjectName(name.trim()))
            require(!dir.exists() || dir.isDirectory) { "项目路径已存在但不是目录：" + dir.path }
            require(dir.isDirectory || dir.mkdirs()) { "无法创建项目目录：" + dir.path }
            val project = Project(dir.canonicalFile, name.trim())
            writeProject(project)
            return project.dir
        }
        val configured = readProject()
        if (configured != null) {
            require(!configured.dir.exists() || configured.dir.isDirectory) { "默认项目路径不是目录：" + configured.dir.path }
            require(configured.dir.isDirectory || configured.dir.mkdirs()) { "无法创建默认项目目录：" + configured.dir.path }
            return configured.dir
        }
        val dir = autoProjectDir()
        require(dir.isDirectory || dir.mkdirs()) { "无法创建默认项目目录：" + dir.path }
        val project = Project(dir.canonicalFile, autoProjectName())
        writeProject(project)
        return project.dir
    }

    /** project_folder state 返回。 */
    @Synchronized
    fun state(): JsonObject {
        val project = readProject()
        val exists = project?.dir?.isDirectory == true
        return ToolkitIo.json(
            "projectDir" to project?.dir?.path,
            "name" to project?.name,
            "isDefault" to (project != null),
            "exists" to exists,
            "candidates" to candidates(),
            "note" to if (project == null)
                "尚无默认项目；project_folder{action:create,name:...} 创建，或直接写裸文件时自动创建 mcp-out-日期 项目"
            else null,
        )
    }

    /** project_folder tidy：把 moves 移到项目 _incoming/，默认不动盘由调用方控制 dryRun。 */
    @Synchronized
    fun tidy(moves: List<String>, dryRun: Boolean): JsonObject {
        val project = if (dryRun) {
            readProject() ?: Project(autoProjectDir(), autoProjectName())
        } else {
            val dir = ensureProject(null)
            Project(dir, readProject()?.name ?: dir.name)
        }
        val incoming = File(project.dir, incomingName)
        val moved = JsonArray()
        val skipped = JsonArray()
        if (!dryRun && moves.isNotEmpty()) {
            require(incoming.isDirectory || incoming.mkdirs()) { "无法创建 _incoming：" + incoming.path }
        }
        for (raw in moves) {
            val sourceResult = runCatching { resolveInRoot(raw) }
            val source = sourceResult.getOrNull()
            if (source == null) {
                skipped.add(ToolkitIo.json("path" to raw, "reason" to (sourceResult.exceptionOrNull()?.message ?: "路径无效")))
                continue
            }
            if (!source.isFile) {
                skipped.add(ToolkitIo.json("path" to source.path, "reason" to "不是普通文件"))
                continue
            }
            if (isUnder(project.dir, source) || source.canonicalFile == project.dir) {
                skipped.add(ToolkitIo.json("path" to source.path, "reason" to "已在项目目录内"))
                continue
            }
            val target = uniqueTarget(incoming, source.name)
            if (dryRun) {
                moved.add(ToolkitIo.json("from" to source.path, "to" to target.path))
            } else if (source.renameTo(target)) {
                moved.add(ToolkitIo.json("from" to source.path, "to" to target.path))
            } else {
                skipped.add(ToolkitIo.json("path" to source.path, "reason" to "移动失败（未删除源文件）"))
            }
        }
        return ToolkitIo.json(
            "projectDir" to project.dir.path,
            "dryRun" to dryRun,
            "moved" to moved,
            "skipped" to skipped,
        )
    }

    // ------------------------------------------------------------------
    // project_folder 工具入口（state/create/set/tidy）
    // ------------------------------------------------------------------

    @Synchronized
    fun call(args: JsonObject): JsonObject {
        val action = args.tString("action", "state").lowercase()
        return when (action) {
            "state" -> state()
            "create", "set" -> createOrSet(args)
            "tidy" -> {
                val dryRun = args.tBool("dryRun", true)
                val moves = LinkedHashSet<String>()
                val move = args.get("move")
                if (move != null && !move.isJsonNull) {
                    require(move.isJsonArray) { "move 必须是路径数组" }
                    for (item in move.asJsonArray) {
                        require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "move 每项必须是字符串路径" }
                        moves.add(item.asString)
                    }
                }
                val pattern = args.tString("pattern", "").trim()
                if (pattern.isNotEmpty()) {
                    val regex = globRegex(pattern)
                    rootDir.listFiles()?.filter { it.isFile && regex.matches(it.name) }
                        ?.sortedBy { it.name }?.forEach { moves.add(it.name) }
                }
                tidy(moves.toList(), dryRun)
            }
            else -> throw IllegalArgumentException("action 必须是 state/create/set/tidy，收到：" + action)
        }
    }

    fun definition(): JsonObject = ToolkitIo.json(
        "name" to "project_folder",
        "description" to "管理 MCP 产出项目目录。state 查看当前默认项目与候选；create/set 在操作根下创建并记为默认项目；tidy 默认 dryRun=true，把指定散落文件移到项目 _incoming/，只移动不删除。默认项目会持久化，后续裸文件名写入自动归置到这里。",
        "category" to "toolkit",
        "categoryTitle" to "文件与APK工具",
        "inputSchema" to ToolkitIo.json(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to ToolkitIo.json(
                "action" to ToolkitIo.json("type" to "string", "enum" to listOf("state", "create", "set", "tidy"), "default" to "state"),
                "name" to ToolkitIo.json("type" to "string", "description" to "项目名，例如 云镜分析；create/set 时创建 <操作根>/<name>/。"),
                "path" to ToolkitIo.json("type" to "string", "description" to "可选：create/set 指定操作根内的项目目录；与 name 二选一。"),
                "move" to ToolkitIo.json("type" to "array", "items" to ToolkitIo.json("type" to "string"), "description" to "tidy 要移动的文件相对路径列表。"),
                "pattern" to ToolkitIo.json("type" to "string", "description" to "tidy 的可选根目录文件名 glob（* 和 ?）。"),
                "dryRun" to ToolkitIo.json("type" to "boolean", "default" to true, "description" to "tidy 默认只预览，不移动。"),
            ),
            "required" to emptyList<String>(),
        ),
    )

    // ------------------------------------------------------------------
    // create/set 与内部工具
    // ------------------------------------------------------------------

    private fun createOrSet(args: JsonObject): JsonObject {
        val name = args.tString("name", "").trim()
        val pathText = args.tString("path", "").trim()
        val dir = when {
            name.isNotEmpty() -> File(rootDir, safeProjectName(name))
            pathText.isNotEmpty() -> resolveInRoot(pathText)
            else -> throw IllegalArgumentException("create/set 需要 name 或 path")
        }
        val existed = dir.isDirectory
        require(!dir.exists() || dir.isDirectory) { "项目路径已存在但不是目录：" + dir.path }
        if (!dir.exists()) require(dir.mkdirs() || dir.isDirectory) { "无法创建项目目录：" + dir.path }
        val project = Project(dir.canonicalFile, name.ifEmpty { dir.name })
        writeProject(project)
        return ToolkitIo.json(
            "projectDir" to project.dir.path,
            "name" to project.name,
            "created" to !existed,
            "isDefault" to true,
            "candidates" to candidates(),
        )
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val suffix = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, base + "-" + index + suffix)
            index++
        }
        return candidate
    }

    private fun globRegex(pattern: String): Regex {
        val out = StringBuilder("^")
        for (c in pattern) {
            when (c) {
                '*' -> out.append(".*")
                '?' -> out.append('.')
                else -> out.append(Regex.escape(c.toString()))
            }
        }
        out.append("$")
        return Regex(out.toString(), RegexOption.IGNORE_CASE)
    }

    private fun readProject(): Project? {
        if (!projectFile.isFile) return null
        val source = runCatching { JsonParser().parse(projectFile.readText(Charsets.UTF_8)).asJsonObject }.getOrNull() ?: return null
        val path = runCatching { source.get("projectDir")?.asString }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val dir = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!isSameOrChild(rootDir, dir)) return null
        val name = runCatching { source.get("name")?.asString }.getOrNull()?.takeIf { it.isNotBlank() } ?: dir.name
        return Project(dir, name)
    }

    private fun writeProject(project: Project) {
        val data = ToolkitIo.json(
            "version" to 1,
            "projectDir" to project.dir.path,
            "name" to project.name,
            "isDefault" to true,
            "updatedAt" to now(),
        )
        ToolkitIo.atomic(projectFile) { it.writeText(data.toString(), Charsets.UTF_8) }
    }

    private fun autoProjectName(): String = "mcp-out-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now()))
    private fun autoProjectDir(): File {
        var candidate = File(rootDir, autoProjectName())
        var suffix = 1
        while (candidate.exists() && !candidate.isDirectory) {
            candidate = File(rootDir, autoProjectName() + "-" + suffix)
            suffix++
        }
        return candidate
    }

    private fun candidates(): JsonArray = JsonArray().apply {
        rootDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("__") }
            ?.sortedBy { it.name }?.forEach { add(it.name) }
    }

    private fun resolveInRoot(raw: String): File {
        require(raw.isNotBlank() && raw.length <= 4096 && !raw.contains('\u0000')) { "路径无效" }
        val candidate = if (File(raw).isAbsolute) File(raw) else File(rootDir, raw)
        val canonical = candidate.canonicalFile
        require(isSameOrChild(rootDir, canonical)) { "路径超出操作根：" + raw }
        return canonical
    }

    private fun safeProjectName(name: String): String {
        require(name.length <= 128 && name != "." && name != ".." && name.none {
            it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f
        }) { "项目名不能包含路径分隔符/控制字符，且不能是 . 或 .." }
        return name
    }

    private fun isSameOrChild(base: File, child: File): Boolean {
        val b = base.canonicalFile
        val c = child.canonicalFile
        return c == b || c.path.startsWith(b.path + File.separator)
    }

    private fun isUnder(base: File, child: File): Boolean = child.canonicalFile.path.startsWith(base.canonicalFile.path + File.separator)

    companion object {
        const val PROJECT_CONFIG = "project.json"
    }
}
