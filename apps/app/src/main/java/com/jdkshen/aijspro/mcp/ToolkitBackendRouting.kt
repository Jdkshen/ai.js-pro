package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * UX-2：终端后端能力路由（纯 JVM 逻辑，便于单测）。
 *
 * 用户侧痛点：javac/java 只有 app UID 可达，pm/am/logcat 等只有 shell（shizuku/adb）可达；
 * 过去用错后端只看到 "javac: inaccessible or not found"，容易被误认为「工具没装」。
 * 本对象只负责判定与建议，不执行命令、不改系统设置。
 */
internal object ToolkitBackendRouting {

    enum class Requirement { APP_ONLY, SHELL_ONLY, BOTH }

    /** 可选 autoRoute 的注入键；McpAndroidTerminal 会读取并从子进程环境中剥离。 */
    const val AUTO_ROUTE_ENV = "AIJS_MCP_TERMINAL_AUTO_ROUTE"

    private val APP_ONLY = setOf("javac", "java", "keytool", "d8", "aapt2", "zipalign", "apksigner")
    private val SHELL_ONLY = setOf("pm", "am", "logcat", "uiautomator", "dumpsys", "su", "shizuku", "input", "settings")
    private val SHELL_WRAPPERS = setOf("sh", "bash", "dash", "ash")

    fun requirement(command: String): Requirement = requirement(command, 0)

    /**
     * P1-6：shell 包装器不能一律判 SHELL_ONLY。
     *
     * <p>`sh build.sh` 只是执行一个本地脚本，**app 后端完全能跑**（过去被判 SHELL_ONLY，
     * 于是调用方看到「该命令需要 shizuku 后端」的误判提示）；只有把 **shell 专属命令内联**
     * 进 `-c` 时（如 `sh -c "pm list packages"`）才真的需要 shell。
     *
     * <p>规则：`sh [-x] -c "<inner>"` → 递归按 inner 判定（深度上限 2）；
     * `sh script.sh` / 裸 `sh` → BOTH。
     */
    private fun requirement(command: String, depth: Int): Requirement {
        val tokens = normalizedTokens(command)
        val executable = tokens.firstOrNull()?.let { basename(it) }?.lowercase() ?: return Requirement.BOTH
        return when {
            executable in APP_ONLY -> Requirement.APP_ONLY
            executable in SHELL_ONLY -> Requirement.SHELL_ONLY
            executable in SHELL_WRAPPERS -> wrapperRequirement(tokens, depth)
            else -> Requirement.BOTH
        }
    }

    /** 见 {@link #requirement(String, int)}：按 `-c` 内联脚本判定，否则两种后端都可以。 */
    private fun wrapperRequirement(tokens: List<String>, depth: Int): Requirement {
        var index = 1
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "-c") {
                val inline = tokens.getOrNull(index + 1) ?: return Requirement.BOTH
                if (depth >= 2 || inline.isBlank()) return Requirement.BOTH
                return requirement(inline, depth + 1)
            }
            if (token.startsWith("-")) {
                index++
                continue
            }
            break // 第一个非选项参数是脚本文件：本地脚本，app 后端也能执行。
        }
        return Requirement.BOTH
    }

    /** 校验 requestedBackend 是否能直接执行 command；不能时给出明确 hint/nextActions。 */
    fun check(requestedBackend: String, command: String): JsonObject {
        val requested = requestedBackend.trim().lowercase()
        val requirement = requirement(command)
        val requiredBackend = when (requirement) {
            Requirement.APP_ONLY -> "app"
            Requirement.SHELL_ONLY -> "shizuku"
            Requirement.BOTH -> null
        }
        val backendValid = requested == "app" || requested == "shizuku"
        val ok = backendValid && (requiredBackend == null || requested == requiredBackend)
        val target = if (!ok || requiredBackend != null) autoRouteTarget(requested, command) else null
        val hint: String
        val actions = JsonArray()
        if (!backendValid) {
            hint = "未知终端后端 \"" + requestedBackend + "\"；必须使用 backend=app 或 backend=shizuku。"
            actions.add("把 backend 设为 app 或 shizuku")
        } else if (ok) {
            hint = ""
        } else {
            val required = requiredBackend ?: "app|shizuku"
            val backendLabel = if (required == "app") "app（普通应用 UID）" else "shizuku（shell/授权 UID）"
            hint = "该命令需要 " + backendLabel + " 后端，当前是 " + requested + " 后端；只是换了后端，不是工具没装。" +
                "建议改用 backend=" + required + " 重试；原命令与原始错误输出仍会保留。"
            actions.add("改用 backend=" + required + " 重试该命令")
            if (requirement == Requirement.SHELL_ONLY) {
                actions.add("pm/am/logcat/dumpsys/uiautomator/settings 等 shell 工具应走 backend=shizuku")
            } else {
                actions.add("javac/java/keytool/d8/aapt2/zipalign/apksigner 通常只在 app 后端可达")
            }
            actions.add("如需自动切换只读命令，可显式设置 autoRoute=true（默认 false）")
        }
        return JsonObject().apply {
            addProperty("ok", ok)
            addProperty("requirement", requirement.name)
            addProperty("requestedBackend", requested)
            if (requiredBackend != null) addProperty("requiredBackend", requiredBackend)
            addProperty("hint", hint)
            add("nextActions", actions)
            addProperty("autoRouteAllowed", target != null)
            if (target != null) addProperty("autoRouteTarget", target)
        }
    }

    /** 只读、无副作用白名单命令可自动切换；写操作永远不自动切。 */
    fun autoRouteTarget(requestedBackend: String, command: String): String? {
        val requested = requestedBackend.trim().lowercase()
        val requirement = requirement(command)
        val required = when (requirement) {
            Requirement.APP_ONLY -> "app"
            Requirement.SHELL_ONLY -> "shizuku"
            Requirement.BOTH -> return null
        }
        if (requested == required) return null
        if (!isReadOnlyCommand(command)) return null
        return required
    }

    /** 能力矩阵：给 terminal_exec 描述/排障面板直接使用；不依赖设备状态。 */
    fun capabilityMatrix(): JsonObject = JsonObject().apply {
        addProperty("version", 1)
        add("requirements", JsonObject().apply {
            add("APP_ONLY", JsonArray().apply { APP_ONLY.sorted().forEach { add(it) } })
            add("SHELL_ONLY", JsonArray().apply { SHELL_ONLY.sorted().forEach { add(it) } })
            addProperty("BOTH", "其它普通命令（如 ls/cat/tar/node）按当前后端执行；两侧都可能可用")
        })
        add("backends", JsonObject().apply {
            add("app", JsonObject().apply {
                addProperty("label", "普通应用 UID（默认）")
                add("available", JsonArray().apply {
                    APP_ONLY.sorted().forEach { add(it) }
                    add("应用私有目录/内置 toolchain")
                })
                add("unavailable", JsonArray().apply {
                    listOf("pm", "am", "logcat", "dumpsys", "uiautomator").forEach { add(it) }
                    add("完整 shell/adb 权限")
                })
            })
            add("shizuku", JsonObject().apply {
                addProperty("label", "Shizuku UserService 授权的 shell UID")
                add("available", JsonArray().apply {
                    SHELL_ONLY.sorted().forEach { add(it) }
                    add("adb 级别的 shell 工具")
                })
                add("unavailable", JsonArray().apply {
                    APP_ONLY.sorted().forEach { add(it) }
                    add("javac/java 的应用私有 classpath/toolchain 路径")
                })
            })
        })
        add("shellCapabilities", JsonObject().apply {
            addProperty("grep", "不支持 \\b 和 \\s；改用 grep -E 或 node/awk 等价写法")
            addProperty("cat", "不支持 -n/-A；改用 sed -n / grep -n")
            addProperty("zip", "不可用；只有 tar 可用于归档")
            addProperty("jar", "不可用")
            addProperty("7z", "不可用")
            addProperty("python", "PATH 中 python 可能链接损坏，优先使用 node")
            addProperty("node", "可用（Node24），适合替代复杂 shell 文本处理")
        })
        add("autoRoute", JsonObject().apply {
            addProperty("default", false)
            addProperty("optInParameter", "autoRoute=true")
            add("readOnlyWhitelist", JsonArray().apply {
                listOf(
                    "logcat -d（禁止 -c/--clear/-f）",
                    "pm list / pm path / pm dump",
                    "dumpsys",
                    "settings get / settings list",
                    "am get-* / am stack list / am task list",
                    "java -version / javac -version / aapt2 dump / apksigner verify / zipalign -c",
                ).forEach { add(it) }
            })
            addProperty("note", "仅只读、无副作用命令自动切换；写操作、输入、启动等不会自动路由")
        })
        addProperty("summary", capabilitySummary())
    }

    /** 可直接拼进 terminal_exec 描述/状态返回的一段摘要。 */
    fun capabilitySummary(): String =
        "后端能力：app 后端可用 javac/java/keytool/d8/aapt2/zipalign/apksigner（应用私有 toolchain）；" +
            "shizuku 后端可用 pm/am/logcat/dumpsys/uiautomator/settings/input 等 shell 工具；" +
            "用错时先看提示的 backend，不要误以为工具没装。shell 限制：grep 无 \\b/\\s；cat 无 -n/-A；" +
            "无 zip/jar/7z（只有 tar）；python 链接可能坏，node 可用。autoRoute 默认 false，只对 logcat -d/pm list/dumpsys 等只读命令自动切换。"

    // ------------------------------------------------------------------
    // command normalization
    // ------------------------------------------------------------------

    private fun normalizedTokens(command: String): List<String> {
        var text = command.trim()
        repeat(8) {
            val tokens = tokenize(text)
            if (tokens.isEmpty()) return emptyList()
            var index = 0
            while (index < tokens.size && isAssignment(tokens[index])) index++
            if (index >= tokens.size) return emptyList()
            val executable = basename(tokens[index]).lowercase()
            when {
                executable == "env" -> {
                    index++
                    while (index < tokens.size) {
                        val token = tokens[index]
                        when {
                            token == "-u" -> index += 2
                            token == "-i" || token == "--" || token == "-" -> index += 1
                            token.startsWith("-") -> index += 1
                            isAssignment(token) -> index += 1
                            else -> break
                        }
                    }
                    if (index >= tokens.size) return emptyList()
                    text = tokens.drop(index).joinToString(" ")
                }
                executable in SHELL_WRAPPERS -> {
                    val cIndex = tokens.drop(index + 1).indexOfFirst { it == "-c" || (it.startsWith("-") && it.contains('c')) }
                    if (cIndex < 0) return tokens.drop(index)
                    val payloadIndex = index + 1 + cIndex + 1
                    if (payloadIndex >= tokens.size) return emptyList()
                    text = tokens[payloadIndex]
                }
                executable == "busybox" -> {
                    if (index + 1 >= tokens.size) return emptyList()
                    text = tokens.drop(index + 1).joinToString(" ")
                }
                executable in setOf("command", "exec", "nohup") -> {
                    if (index + 1 >= tokens.size) return emptyList()
                    text = tokens.drop(index + 1).joinToString(" ")
                }
                executable == "timeout" -> {
                    var next = index + 1
                    while (next < tokens.size && tokens[next].startsWith("-")) next++
                    if (next < tokens.size) next++ // duration
                    if (next >= tokens.size) return emptyList()
                    text = tokens.drop(next).joinToString(" ")
                }
                else -> return tokens.drop(index)
            }
        }
        return tokenize(text)
    }

    private fun tokenize(input: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false
        input.forEach { ch ->
            if (escaped) {
                current.append(ch)
                escaped = false
                started = true
                return@forEach
            }
            when {
                ch == '\\' && quote != '\'' -> { escaped = true; started = true }
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                    started = true
                }
                ch == '\'' || ch == '"' -> { quote = ch; started = true }
                ch.isWhitespace() -> {
                    if (started) {
                        result.add(current.toString())
                        current.setLength(0)
                        started = false
                    }
                }
                else -> { current.append(ch); started = true }
            }
        }
        if (started) result.add(current.toString())
        return result
    }

    private fun isAssignment(token: String): Boolean =
        Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(token)

    private fun basename(token: String): String =
        token.substringAfterLast('/').substringAfterLast('\\')

    private fun isReadOnlyCommand(command: String): Boolean {
        val tokens = normalizedTokens(command)
        if (tokens.isEmpty()) return false
        val executable = basename(tokens[0]).lowercase()
        val args = tokens.drop(1).map { it.lowercase() }
        val firstArg = args.firstOrNull { !it.startsWith("-") } ?: ""
        return when (executable) {
            "logcat" -> args.any { it == "-d" || it == "--dump" } &&
                args.none { it == "-c" || it == "--clear" || it == "-f" }
            "pm" -> firstArg in setOf("list", "path", "dump")
            "dumpsys" -> true
            "settings" -> firstArg in setOf("get", "list")
            "am" -> firstArg.startsWith("get-") ||
                (firstArg in setOf("stack", "task") && args.dropWhile { it != firstArg }.drop(1).firstOrNull() == "list")
            "java", "javac" -> args.any { it == "-version" || it == "--version" }
            "aapt2" -> firstArg == "dump"
            "apksigner" -> firstArg == "verify"
            "zipalign" -> args.any { it == "-c" }
            else -> false
        }
    }
}
