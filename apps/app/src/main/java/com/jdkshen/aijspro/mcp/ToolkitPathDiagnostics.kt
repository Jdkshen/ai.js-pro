package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.File
import java.io.IOException

/** 诊断后端注册表：`:tools` 进程里的 AndroidToolkitRuntime 安装真实探测实现。 */
internal typealias PathDiagnosticsProvider = (File, String, String, Boolean) -> JsonObject

/**
 * 路径访问诊断（P0-B §5.1）：解释「app UID 得到 ENOENT，而 Shizuku shell 能访问同一路径」这类现象。
 *
 * 原则：
 * - 后端必须显式指定 app 或 shizuku，绝不静默切换权限身份；
 * - 返回实际 UID、原始 errno、是否在允许根内、是否可能受 scoped storage 限制；
 * - 明确 shizuku 是否可用、本轮是否真的执行过访问检查；
 * - 系统无法区分「不存在」与「不可见」时返回 uncertainty，不编造结论。
 *
 * 本文件只包含纯 JVM 逻辑（路径归一化、errno 名称、结论判定、JSON 报告、shell 脚本与解析）
 * 与后端注册表；Android 侧探测（Os.lstat / ShizukuShell）在 AndroidToolkitRuntime 中实现。
 */
internal object ToolkitPathDiagnostics {
    const val BACKEND_APP = "app"
    const val BACKEND_SHIZUKU = "shizuku"

    const val ACCESSIBLE = "ACCESSIBLE"
    const val INVISIBLE_TO_APP = "INVISIBLE_TO_APP"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val UNCERTAIN = "UNCERTAIN"
    const val BACKEND_UNAVAILABLE = "BACKEND_UNAVAILABLE"

    private const val EPERM = 1
    private const val ENOENT = 2
    private const val EACCES = 13
    private const val ENOTDIR = 20
    private const val ELOOP = 40
    private const val ENAMETOOLONG = 36

    data class Probe(
        val backend: String,
        val executed: Boolean = false,
        val available: Boolean? = null,
        val uid: Long? = null,
        val errno: Int? = null,
        val errnoName: String? = null,
        val message: String? = null,
        val exists: Boolean? = null,
        val type: String? = null,
        val size: Long? = null,
        val modifiedTime: Long? = null,
        val readable: Boolean? = null,
        val writable: Boolean? = null,
        val notes: List<String> = emptyList(),
    )

    data class Requested(
        val requested: String,
        val path: File,
        val canonicalPath: String?,
        val insideAllowedRoot: Boolean,
        val warnings: List<String>,
    )

    data class ScopedStorage(val likely: Boolean, val reasons: List<String>)

    data class Decision(
        val conclusion: String,
        val detail: String,
        val distinguishable: Boolean,
        val uncertainty: String?,
        val visibleTo: List<String>,
        val invisibleTo: List<String>,
        val notes: List<String>,
    )

    @Volatile
    private var provider: PathDiagnosticsProvider? = null

    fun installProvider(value: PathDiagnosticsProvider?) {
        provider = value
    }

    fun activeProvider(): PathDiagnosticsProvider? = provider

    fun resetProviderForTests() {
        provider = null
    }

    /** 解析调用方路径：接受 root 内相对路径、任意绝对路径；不做权限切换，只用于只读诊断。 */
    fun resolveRequested(root: File, raw: String): Requested {
        require(raw.isNotEmpty() && raw.length <= 4096) { "path 必须为 1..4096 字符" }
        require(raw.indexOf('\u0000') < 0) { "path 不能包含 NUL" }
        val slashed = raw.replace('\\', '/')
        if (!isWindowsDrive(raw) && looksLikeUri(raw)) {
            throw IllegalArgumentException("不接受 URI 形式的路径：" + raw)
        }
        val rootCanonical = try {
            root.canonicalFile
        } catch (_: IOException) {
            root.absoluteFile
        }
        val candidate = if (slashed.startsWith("/") || File(slashed).isAbsolute) {
            File(slashed)
        } else {
            File(rootCanonical, slashed)
        }
        val lexical = lexicalNormalize(candidate)
        val canonicalPath = try {
            lexical.canonicalPath
        } catch (_: IOException) {
            null
        }
        val rootText = normalizeText(rootCanonical.path)
        val pathText = normalizeText(lexical.path)
        val inside = pathText == rootText || pathText.startsWith(rootText.trimEnd('/') + "/")
        val warnings = ArrayList<String>()
        if (!inside) {
            warnings.add("目标不在 MCP 允许目录内：诊断只做只读检查，不会因此切换权限身份；要操作请使用终端或授权后端并显式指定目录。")
        }
        return Requested(raw, lexical, canonicalPath, inside, warnings)
    }

    /**
     * scoped storage 可能性判断：只看路径形状与 SDK 版本，明确标注为「可能」而非结论。
     * 实际是否受限还取决于 MANAGE_EXTERNAL_STORAGE、设备厂商策略与后端身份。
     */
    fun scopedStorageAssessment(path: String, sdkInt: Int, appPackage: String?): ScopedStorage {
        val reasons = ArrayList<String>()
        var likely = false
        if (sdkInt < 30) return ScopedStorage(false, emptyList())
        val normalized = if (path.startsWith("/")) path else "/" + path
        if (!normalized.startsWith("/storage/emulated/")) return ScopedStorage(false, emptyList())
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        val androidIndex = segments.indexOf("Android")
        if (androidIndex < 0 || androidIndex + 2 >= segments.size) {
            return ScopedStorage(false, emptyList())
        }
        val area = segments[androidIndex + 1]
        if (area != "data" && area != "obb") return ScopedStorage(false, emptyList())
        val ownerPackage = segments[androidIndex + 2]
        if (appPackage != null && ownerPackage == appPackage) {
            reasons.add("目标位于本应用自己的 Android/" + area + " 目录，普通 app UID 通常可直接访问；若仍失败请检查具体 errno。")
        } else {
            likely = true
            reasons.add("Android 11+ 对 Android/" + area + " 下其它应用目录有额外隔离：普通 app UID 常见 ENOENT/EACCES，Shizuku 授权身份可能可见。")
            reasons.add("该判断只是路径形状启发式；应用是否持有 MANAGE_EXTERNAL_STORAGE/厂商豁免仍需结合实测。")
        }
        return ScopedStorage(likely, reasons)
    }

    fun errnoName(errno: Int): String? = when (errno) {
        EPERM -> "EPERM"
        ENOENT -> "ENOENT"
        EACCES -> "EACCES"
        ENOTDIR -> "ENOTDIR"
        ELOOP -> "ELOOP"
        ENAMETOOLONG -> "ENAMETOOLONG"
        17 -> "EEXIST"
        21 -> "EISDIR"
        30 -> "EROFS"
        5 -> "EIO"
        else -> null
    }

    /** POSIX 单引号转义；路径里出现单引号也能安全放进 shell 脚本。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Shizuku 后端探测脚本：只用授权 shell 的 test/stat 布尔输出，不依赖 toybox 特定扩展；
     * 不返回原始 errno（所以解析结果里 errno 为 null，报告会显式标注不确定性）。
     */
    fun shizukuProbeScript(path: String): String {
        val quoted = shellQuote(path)
        val lines = listOf(
            "set -u",
            "printf 'probe=1\\n'",
            "printf 'uid='; id -u 2>/dev/null || printf unknown; printf '\\n'",
            "if [ -e " + quoted + " ]; then printf 'exists=1\\n'; else printf 'exists=0\\n'; fi",
            "if [ -L " + quoted + " ]; then printf 'type=link\\n'; elif [ -d " + quoted + " ]; then printf 'type=dir\\n'; elif [ -f " + quoted + " ]; then printf 'type=file\\n'; else printf 'type=other\\n'; fi",
            "if [ -r " + quoted + " ]; then printf 'readable=1\\n'; else printf 'readable=0\\n'; fi",
            "if [ -w " + quoted + " ]; then printf 'writable=1\\n'; else printf 'writable=0\\n'; fi",
            "stat -c 'size=%s' " + quoted + " 2>/dev/null || true",
            "stat -c 'mtime=%Y' " + quoted + " 2>/dev/null || true",
            "exit 0",
        )
        return lines.joinToString("\n") + "\n"
    }

    /** 解析 shizuku 探测脚本输出；缺少 probe 标记视为「没有真正执行检查」。 */
    fun parseShizukuProbe(output: String): Probe {
        var marked = false
        var uid: Long? = null
        var exists: Boolean? = null
        var type: String? = null
        var readable: Boolean? = null
        var writable: Boolean? = null
        var size: Long? = null
        var modifiedTime: Long? = null
        for (rawLine in output.split('\n')) {
            val line = rawLine.trim()
            when {
                line == "probe=1" -> marked = true
                line.startsWith("uid=") -> uid = line.substring(4).trim().toLongOrNull()
                line == "exists=1" -> exists = true
                line == "exists=0" -> exists = false
                line.startsWith("type=") -> type = line.substring(5).trim()
                line == "readable=1" -> readable = true
                line == "readable=0" -> readable = false
                line == "writable=1" -> writable = true
                line == "writable=0" -> writable = false
                line.startsWith("size=") -> size = line.substring(5).trim().toLongOrNull()
                line.startsWith("mtime=") -> modifiedTime = line.substring(6).trim().toLongOrNull()?.times(1000L)
            }
        }
        if (!marked) {
            return Probe(
                backend = BACKEND_SHIZUKU,
                executed = false,
                available = true,
                message = output.take(400),
                notes = listOf("shizuku 探测输出缺少 probe=1 标记：无法确认本轮真的执行了访问检查"),
            )
        }
        return Probe(
            backend = BACKEND_SHIZUKU,
            executed = true,
            available = true,
            uid = uid,
            errno = null,
            errnoName = null,
            exists = exists,
            type = type,
            size = size,
            modifiedTime = modifiedTime,
            readable = readable,
            writable = writable,
            notes = listOf("shizuku 后端通过授权 shell 的 test/stat 布尔输出判断，没有原始 errno；结论按不确定性处理"),
        )
    }

    fun probeJson(probe: Probe): JsonObject = JsonObject().apply {
        addProperty("backend", probe.backend)
        addProperty("executed", probe.executed)
        addProperty("available", probe.available)
        addProperty("uid", probe.uid)
        addProperty("errno", probe.errno)
        addProperty("errnoName", probe.errnoName)
        addProperty("message", probe.message)
        addProperty("exists", probe.exists)
        addProperty("type", probe.type)
        addProperty("size", probe.size)
        addProperty("modifiedTime", probe.modifiedTime)
        addProperty("readable", probe.readable)
        addProperty("writable", probe.writable)
        add("notes", JsonArray().apply { probe.notes.forEach { add(it) } })
    }

    /** 结论判定：只有可被证据支持时才给出确定结论，否则返回 UNCERTAIN 与 uncertainty 原因。 */
    fun decide(
        requestedBackend: String,
        appProbe: Probe?,
        shizukuProbe: Probe?,
        shizukuAvailable: Boolean,
        compareWithShizuku: Boolean,
    ): Decision {
        val visibleTo = ArrayList<String>()
        val invisibleTo = ArrayList<String>()
        val notes = ArrayList<String>()
        if (appProbe?.executed == true) {
            if (appProbe.exists == true) visibleTo.add(BACKEND_APP) else invisibleTo.add(BACKEND_APP)
        }
        if (shizukuProbe?.executed == true) {
            if (shizukuProbe.exists == true) visibleTo.add(BACKEND_SHIZUKU) else invisibleTo.add(BACKEND_SHIZUKU)
        }
        if (requestedBackend == BACKEND_SHIZUKU) {
            notes.add("请求后端为 shizuku：没有回退到 app UID 的权限身份。")
            if (!shizukuAvailable || shizukuProbe?.executed != true) {
                return Decision(
                    BACKEND_UNAVAILABLE,
                    "请求 shizuku 后端，但 Shizuku 不可用或本轮没有真正执行检查；未回退 app UID。",
                    false,
                    "shizuku 后端不可用或未执行：无法以授权身份检查目标。",
                    visibleTo,
                    invisibleTo,
                    notes,
                )
            }
            if (shizukuProbe.exists == true) {
                return Decision(
                    ACCESSIBLE,
                    "以 shizuku 授权身份检查：目标可见。",
                    false,
                    null,
                    visibleTo,
                    invisibleTo,
                    notes,
                )
            }
            return Decision(
                UNCERTAIN,
                "以 shizuku 授权身份执行了检查但未看到目标；shizuku 后端没有原始 errno。",
                true,
                "exists=false 可能是路径不存在，也可能是授权 shell 仍无访问权限；需要 app 后端或原始 errno 交叉验证。",
                visibleTo,
                invisibleTo,
                notes,
            )
        }
        notes.add("请求后端为 app：普通应用 UID，未静默切换到 shizuku 身份。")
        if (appProbe?.executed != true) {
            return Decision(
                UNCERTAIN,
                "app 后端没有完成访问检查（探测异常或未执行）。",
                false,
                appProbe?.message ?: "app 探测未执行",
                visibleTo,
                invisibleTo,
                notes,
            )
        }
        if (appProbe.exists == true) {
            return Decision(
                ACCESSIBLE,
                "app UID 检查：目标可见。",
                true,
                null,
                visibleTo,
                invisibleTo,
                notes,
            )
        }
        val comparable = compareWithShizuku && shizukuProbe?.executed == true
        if (!comparable && compareWithShizuku) {
            notes.add("compareWithShizuku=true，但 shizuku 检查未执行或不可用。")
        }
        val errno = appProbe.errno
        if (errno == EACCES || errno == EPERM) {
            return Decision(
                PERMISSION_DENIED,
                "app UID 访问被拒绝（errno=" + errno + " " + (appProbe.errnoName ?: "") + "）。",
                true,
                "权限拒绝只能说明不可读，不能据此断言目标不存在。",
                visibleTo,
                invisibleTo,
                notes,
            )
        }
        if (errno == ENOENT) {
            if (comparable && shizukuProbe?.exists == true) {
                notes.add("两个后端对同一路径的可见性不同：app=ENOENT，shizuku=可见。")
                return Decision(
                    INVISIBLE_TO_APP,
                    "app UID 得到 ENOENT，但同一路径以 shizuku 授权身份可见：属于可见性/存储隔离，不是路径不存在。",
                    true,
                    null,
                    visibleTo,
                    invisibleTo,
                    notes,
                )
            }
            if (comparable) {
                return Decision(
                    UNCERTAIN,
                    "app UID 得到 ENOENT；shizuku 身份也未看到目标，但 shizuku 后端没有原始 errno。",
                    true,
                    "两个身份都未看到，更可能是路径不存在，但缺少 shizuku 原始 errno，不能作为确定性结论。",
                    visibleTo,
                    invisibleTo,
                    notes,
                )
            }
            return Decision(
                UNCERTAIN,
                "app UID 得到 ENOENT；本轮没有执行第二身份检查。",
                false,
                "仅凭 app UID 的 ENOENT 不能区分「不存在」与「不可见」；可用 compareWithShizuku=true 再查，或显式用 shizuku 后端。",
                visibleTo,
                invisibleTo,
                notes,
            )
        }
        if (errno == ENOTDIR || errno == ELOOP) {
            return Decision(
                UNCERTAIN,
                "app UID 探测得到 errno=" + errno + "（路径某段不是目录或链接层数过多），目标状态未确认。",
                true,
                null,
                visibleTo,
                invisibleTo,
                notes,
            )
        }
        return Decision(
            UNCERTAIN,
            "app UID 未看到目标，errno=" + (errno?.toString() ?: "未知") + "；没有可判定的结论。",
            errno != null,
            "缺少明确 errno 或 errno 不在已知判定表中。",
            visibleTo,
            invisibleTo,
            notes,
        )
    }

    /** 组装 file_access_diagnose 的返回数据（不含外层 ok/data/nextActions）。 */
    fun report(
        root: File,
        requested: String,
        requestedBackend: String,
        compareWithShizuku: Boolean,
        appProbe: Probe?,
        shizukuProbe: Probe?,
        shizukuAvailable: Boolean,
        shizukuAvailabilityKnown: Boolean,
        sdkInt: Int,
        appPackage: String?,
    ): JsonObject {
        require(requestedBackend == BACKEND_APP || requestedBackend == BACKEND_SHIZUKU) {
            "backend 必须显式指定 app 或 shizuku（不会静默切换权限身份）"
        }
        val info = resolveRequested(root, requested)
        val scoped = scopedStorageAssessment(info.path.path, sdkInt, appPackage)
        val decision = decide(requestedBackend, appProbe, shizukuProbe, shizukuAvailable, compareWithShizuku)
        val executedBackends = ArrayList<String>()
        if (appProbe?.executed == true) executedBackends.add(BACKEND_APP)
        if (shizukuProbe?.executed == true) executedBackends.add(BACKEND_SHIZUKU)
        val primary = when (requestedBackend) {
            BACKEND_SHIZUKU -> shizukuProbe
            else -> appProbe
        }
        val allNotes = ArrayList<String>()
        allNotes.addAll(info.warnings)
        allNotes.addAll(decision.notes)
        (appProbe?.notes ?: emptyList()).forEach { allNotes.add("app: " + it) }
        (shizukuProbe?.notes ?: emptyList()).forEach { allNotes.add("shizuku: " + it) }
        return JsonObject().apply {
            addProperty("path", info.path.path)
            addProperty("requestedPath", requested)
            addProperty("canonicalPath", info.canonicalPath)
            addProperty("allowedRoot", safeCanonical(root))
            addProperty("insideAllowedRoot", info.insideAllowedRoot)
            addProperty("backend", requestedBackend)
            addProperty("requestedBackend", requestedBackend)
            addProperty("compareWithShizuku", compareWithShizuku)
            addProperty("conclusion", decision.conclusion)
            addProperty("conclusionDetail", decision.detail)
            addProperty("distinguishable", decision.distinguishable)
            addProperty("uncertainty", decision.uncertainty)
            addProperty("uid", primary?.uid)
            addProperty("scopedStorageLikely", scoped.likely)
            add("scopedStorageReasons", JsonArray().apply { scoped.reasons.forEach { add(it) } })
            addProperty("shizukuAvailable", shizukuAvailable)
            addProperty("shizukuAvailabilityKnown", shizukuAvailabilityKnown)
            addProperty("shizukuExecuted", shizukuProbe?.executed == true)
            add("executedBackends", JsonArray().apply { executedBackends.forEach { add(it) } })
            add("app", if (appProbe == null) JsonNull.INSTANCE else probeJson(appProbe))
            add("shizuku", if (shizukuProbe == null) JsonNull.INSTANCE else probeJson(shizukuProbe))
            add("errno", JsonObject().apply {
                addProperty("code", primary?.errno)
                addProperty("name", primary?.errnoName)
                addProperty("message", primary?.message)
            })
            add("visibleTo", JsonArray().apply { decision.visibleTo.forEach { add(it) } })
            add("invisibleTo", JsonArray().apply { decision.invisibleTo.forEach { add(it) } })
            add("notes", JsonArray().apply { allNotes.forEach { add(it) } })
        }
    }

    private fun safeCanonical(file: File): String = try {
        file.canonicalPath
    } catch (_: IOException) {
        file.absolutePath
    }

    private fun isWindowsDrive(value: String): Boolean = value.length >= 2 && value[1] == ':' && value[0].isLetter()

    private fun looksLikeUri(value: String): Boolean {
        val colon = value.indexOf(':')
        if (colon <= 0 || colon > 20) return false
        for (index in 0 until colon) {
            val c = value[index]
            val valid = c.isLetterOrDigit() || c == '+' || c == '-' || c == '.'
            if (!valid) return false
        }
        return value[0].isLetter()
    }

    private fun normalizeText(path: String): String {
        val text = path.replace('\\', '/')
        return if (text.length > 1) text.trimEnd('/') else text
    }

    private fun lexicalNormalize(file: File): File {
        val absolute = file.absolutePath.replace('\\', '/')
        val drive = if (absolute.length >= 2 && absolute[1] == ':') absolute.substring(0, 2) else ""
        var rest = if (drive.isEmpty()) absolute else absolute.substring(2)
        if (!rest.startsWith("/")) rest = "/" + rest
        val output = ArrayList<String>()
        for (segment in rest.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (output.isNotEmpty()) output.removeAt(output.size - 1)
                else -> output.add(segment)
            }
        }
        val suffix = output.joinToString("/")
        val text = if (drive.isEmpty()) {
            if (suffix.isEmpty()) "/" else "/" + suffix
        } else {
            drive + "/" + suffix
        }
        return File(text)
    }
}
