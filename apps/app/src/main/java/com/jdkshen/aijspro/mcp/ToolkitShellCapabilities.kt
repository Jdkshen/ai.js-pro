package com.jdkshen.aijspro.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

/**
 * Shell 能力清单、失败分类、Node 脚本调用与 crash buffer 语义（纯逻辑）。
 *
 * 清单来自用户侧实测的 busybox 风格环境（docs/plans/2026-11-04_AIjsPro-MCP使用体验反馈（用户侧）.md §2.4）；
 * 具体设备可能不同，调用方应先看 matrix() 再选替代写法。本对象不执行 shell、不读 logcat。
 */
internal object ToolkitShellCapabilities {
    private const val SOURCE = "用户侧实战复盘 §2.4（busybox 风格实测）"

    fun matrix(): JsonObject {
        val grep = entry(
            name = "grep",
            available = true,
            unsupported = listOf("\\b", "\\s"),
            alternatives = listOf(
                mcp("file_search_text", "用 MCP 搜索单个文本文件，避免 busybox grep 不支持 \\b/\\s 与复杂转义"),
                mcp("file_search", "在目录内做有界文件搜索，适合多文件/递归场景"),
                shell("node <已写入的脚本>", "把复杂正则、过滤和统计写成 .js 文件再用 node 运行；不要用 node -e 拼复杂字符串"),
                shell("grep -E <POSIX ERE>", "仅当表达式是基本 POSIX ERE 且不依赖 \\b/\\s 时可用"),
            ),
            notes = listOf(
                "busybox grep 实测不支持 \\b 与 \\s；不要把这些 Perl/PCRE 记号当作可用能力",
                "需要 Unicode/中文边界时优先用 node 或 file_search_text",
            ),
        )
        val cat = entry(
            name = "cat",
            available = true,
            unsupported = listOf("-n", "-A"),
            alternatives = listOf(
                mcp("file_read_text", "读取文本文件（带编码与分页），避免 cat -n/-A"),
                shell("sed -n '1,200p' <file>", "需要行范围时用 sed -n；不要依赖 cat -n"),
                shell("grep -n '' <file>", "需要行号并查看少量内容时可用 grep -n"),
                mcp("file_read_bytes", "需要十六进制/不可见字节时使用字节读取工具"),
            ),
            notes = listOf(
                "cat -n / -A 实测不可用；-A 还涉及不可见字符显示，MCP 工具更可靠",
            ),
        )
        val zip = entry(
            name = "zip",
            available = false,
            status = "unavailable",
            alternatives = listOf(
                mcp("zip_create", "MCP 创建 ZIP；不要依赖 busybox zip"),
                mcp("zip_extract", "MCP 解压/读取 ZIP 条目"),
                mcp("zip_list", "MCP 列出 ZIP 条目"),
                shell("tar -cf <out.tar> <files>", "只需归档时用 tar；tar 实测可用"),
                shell("node <pack.js>", "需要自定义 APK/ZIP 打包时写 pack.js 并用 node 运行"),
            ),
            notes = listOf("zip/jar/7z 均不可用；只有 tar 可用"),
        )
        val jar = entry(
            name = "jar",
            available = false,
            status = "unavailable",
            alternatives = listOf(
                mcp("zip_list", "用 ZIP 工具查看 jar/zip 条目"),
                mcp("zip_extract", "用 ZIP 工具解出条目"),
                mcp("zip_create", "生成 ZIP 后用系统可识别方式重命名/复制"),
                shell("tar -cf <out.tar> <dir>", "只需要归档时用 tar"),
            ),
            notes = listOf("jar 不可用；不要用 jar 命令打包 APK"),
        )
        val sevenZip = entry(
            name = "7z",
            available = false,
            status = "unavailable",
            alternatives = listOf(
                mcp("zip_list", "7z 不可用；先判断是否其实是 ZIP，再用 ZIP 工具"),
                mcp("zip_extract", "ZIP 解压"),
                shell("tar -tf <file>", "tar 归档用 tar 读取"),
            ),
            notes = listOf("7z 不可用；不要假设能解 7z 格式"),
        )
        val tar = entry(
            name = "tar",
            available = true,
            unsupported = emptyList(),
            alternatives = listOf(
                shell("tar -tf <file>.tar", "列出 tar 内容"),
                shell("tar -xf <file>.tar", "解压 tar"),
            ),
            notes = listOf("tar 实测可用；但 tar 不是 zip/jar/7z 的替代格式"),
        )
        val python = entry(
            name = "python",
            available = false,
            status = "broken-symlink",
            alternatives = listOf(
                mcp("terminal_exec", "先确认后端与 PATH；不要依赖损坏的 python 链接", args = mapOf("backend" to "app")),
                shell("node <已写入的脚本>", "python 链接坏时用 node；先写 .js 文件再运行，避免 -e 双重转义"),
            ),
            notes = listOf("python 链接实测损坏（inaccessible/broken symlink）；node 可用"),
        )
        val node = entry(
            name = "node",
            available = true,
            unsupported = listOf("node -e '复杂脚本'"),
            alternatives = listOf(
                shell("node <script.js> [args...]", "写文件再运行；nodeScriptCommand() 会给出带引号的命令和 argv 语义"),
                mcp("file_write_text", "先写脚本文件，再用 terminal_exec 运行 node"),
            ),
            notes = listOf(
                "node 可用，但复杂脚本不要用 node -e：shell 与 JS 双重转义会破坏引号/反斜杠/中文引号",
                "argv[0]=node，argv[1]=脚本路径，argv[2] 才是第一个脚本参数",
            ),
        )
        val sed = entry(
            name = "sed",
            available = true,
            unsupported = emptyList(),
            alternatives = listOf(
                shell("sed -n '1,200p' <file>", "按行范围读取"),
            ),
            notes = listOf("可用作 cat -n 的简单替代；复杂替换仍建议写文件后用 node"),
        )
        val commands = JsonObject()
        for (item in listOf(grep, cat, zip, jar, sevenZip, tar, python, node, sed)) {
            commands.add(item.tString("name"), item)
        }
        val capabilities = JsonArray()
        commands.entrySet().forEach { capabilities.add(it.value.deepCopy()) }
        return ToolkitIo.json(
            "backend" to "shell",
            "shellStyle" to "busybox-like",
            "source" to SOURCE,
            "commands" to commands,
            "matrix" to commands.deepCopy(),
            "capabilities" to capabilities,
            "list" to capabilities.deepCopy(),
            "commandList" to capabilities.deepCopy(),
            "mcpAlternatives" to ToolkitIo.json(
                "file_read_text" to "读取文本（编码/分页）；替代 cat",
                "file_search_text" to "文本搜索；替代 grep 不支持 \\b/\\s 的场景",
                "file_search" to "目录内搜索；替代 find/grep -r 的简单场景",
                "zip_list/zip_extract/zip_create" to "替代 zip/jar/7z 的 ZIP 操作",
                "terminal_exec" to "后端明确：app 或 shizuku；先看 backend 与 PATH",
                "node" to "复杂脚本先写 .js 再运行，避免 node -e 双重转义",
            ),
            "capabilityNotes" to jsonArray(
                "不同设备 busybox/ROM 能力可能不同；矩阵是实测基线，不是所有设备保证",
                "遇到 unrecognized option/not found/inaccessible 先用 classifyFailure() 分类，不要猜",
                "优先使用 MCP 文件/压缩工具，减少 shell 引号与转义问题",
            ),
            "probeHint" to "可先运行 get_shell_capabilities（接线后的 MCP 工具）或 terminal_exec 的 help 输出核对本机能力",
        )
    }

    /** 只依据输出中的明确标记分类；无法判断时返回 unknown，不做能力缺口猜测。 */
    fun classifyFailure(command: String, output: String): JsonObject {
        val cmd = commandName(command)
        val lower = output.lowercase(Locale.ROOT)
        val optionMarker = OPTION_MARKERS.firstOrNull { lower.contains(it) }
        val inaccessibleMarker = INACCESSIBLE_MARKERS.firstOrNull { lower.contains(it) }
        val fileMissing = lower.contains("no such file or directory") || lower.contains("no such file")
        val commandMissing = lower.contains("command not found") ||
            lower.contains(": not found") ||
            lower.contains("inaccessible or not found") ||
            (lower.contains("not found") && cmd != null && !fileMissing)

        val category: String
        val scope: String?
        val capabilityGap: Boolean
        val confidence: String
        val matched: String?
        when {
            optionMarker != null -> {
                category = "unrecognized_option"
                scope = "option"
                capabilityGap = true
                confidence = "high"
                matched = optionMarker
            }
            inaccessibleMarker != null -> {
                category = "inaccessible"
                scope = "backend_or_command"
                capabilityGap = true
                confidence = "medium"
                matched = inaccessibleMarker
            }
            commandMissing -> {
                category = "not_found"
                scope = "command"
                capabilityGap = true
                confidence = "high"
                matched = when {
                    lower.contains("command not found") -> "command not found"
                    lower.contains("inaccessible or not found") -> "inaccessible or not found"
                    lower.contains(": not found") -> ": not found"
                    else -> "not found"
                }
            }
            fileMissing -> {
                category = "not_found"
                scope = "file"
                capabilityGap = false
                confidence = "medium"
                matched = "no such file"
            }
            else -> {
                category = "unknown"
                scope = null
                capabilityGap = false
                confidence = "low"
                matched = null
            }
        }
        val alternatives = alternativesFor(cmd, category, scope)
        val firstHint = if (category == "unknown") {
            "无法从输出判断；请提供退出码、command 与完整错误行，不要据此认定命令不可用"
        } else {
            alternatives.firstOrNull()?.asJsonObject?.get("hint")?.asString
                ?: "请按 alternatives 检查，不要只凭错误文本猜测能力缺口"
        }
        return ToolkitIo.json(
            "command" to command.take(500),
            "commandName" to cmd,
            "category" to category,
            "kind" to category,
            "type" to category,
            "scope" to scope,
            "capabilityGap" to capabilityGap,
            "confidence" to confidence,
            "matchedPattern" to matched,
            "backendHint" to backendHint(cmd, category),
            "alternatives" to alternatives,
            "suggestions" to alternatives.deepCopy(),
            "alternative" to firstHint,
            "suggestion" to firstHint,
            "suggestedAction" to firstHint,
            "outputExcerpt" to output.take(800),
            "notes" to jsonArray(
                "仅在输出含明确错误标记时分类；unknown 表示不能判断，不代表命令不可用",
                "能力缺口不等同于命令一定缺失：可能是 busybox 选项、后端权限或路径问题",
                "替代方案优先用 MCP 文件/压缩工具；若必须 shell，先写脚本文件再运行 node",
            ),
        )
    }

    /** 推荐把脚本写入文件再运行 node；明确 argv 索引，避免 node -e 双重转义。 */
    fun nodeScriptCommand(scriptPath: String, args: List<String>): JsonObject {
        require(scriptPath.isNotBlank() && scriptPath.none { it.code == 0 }) { "scriptPath 不能为空且不能含 NUL" }
        require(args.none { it.any { ch -> ch.code == 0 } }) { "参数不能含 NUL" }
        val argv = ArrayList<String>(args.size + 2)
        argv.add("node")
        argv.add(scriptPath)
        argv.addAll(args)
        val argvJson = JsonArray()
        argv.forEach { argvJson.add(it) }
        val argsJson = JsonArray()
        args.forEach { argsJson.add(it) }
        val command = argv.joinToString(" ") { shellQuote(it) }
        return ToolkitIo.json(
            "mode" to "file",
            "recommended" to true,
            "writeFileFirst" to true,
            "scriptPath" to scriptPath,
            "args" to argsJson,
            "scriptArgs" to argsJson.deepCopy(),
            "command" to command,
            "commandLine" to command,
            "argv" to argvJson,
            "processArgv" to argvJson,
            "argvSemantics" to ToolkitIo.json(
                "0" to "node",
                "1" to scriptPath,
                "2" to args.firstOrNull(),
                "argv0" to "node",
                "argv1" to scriptPath,
                "argv2" to args.firstOrNull(),
                "scriptArgsStartIndex" to 2,
                "startIndex" to 2,
                "note" to "process.argv[0] 是 node，argv[1] 是脚本路径，argv[2] 及之后才是脚本参数",
            ),
            "writeFirst" to ToolkitIo.json(
                "recommended" to true,
                "mode" to "file",
                "tool" to "file_write_text",
                "path" to scriptPath,
                "encoding" to "utf-8",
                "note" to "先把脚本内容写入文件，再执行 node <file>；不要用 node -e 传复杂脚本",
            ),
            "avoidNodeE" to true,
            "doNotUseNodeE" to true,
            "avoid" to jsonArray(
                "node -e '含引号/反斜杠/中文引号的复杂脚本'：shell 与 JS 双重转义易坏",
                "node -p '复杂表达式'：同样受引号和转义影响",
                "在 shell heredoc 中嵌复杂 JS：可读性和转义都容易出错",
            ),
            "notes" to jsonArray(
                "推荐顺序：file_write_text 写脚本 -> nodeScriptCommand 生成命令 -> terminal_exec 运行",
                "argv[0]=node，argv[1]=脚本，argv[2] 才是第一个参数；不要按 argv[1] 取业务参数",
                "脚本路径或参数含空格时命令会自动加单引号；内容无法安全内联时应拒绝 node -e 写法",
            ),
        )
    }

    /** logcat -b crash -c 的清缓冲语义、旧条目原因和可验证采集窗口。 */
    fun crashBufferSemantics(): JsonObject {
        val why = jsonArray(
            "logcat -b crash -c 只请求清理 crash buffer；不会清 main/system/events 等其它 buffer。之后读取其它 buffer 或 crash 条目被复制/转发时仍可能看到旧崩溃",
            "-c 不是同步屏障：清理与并发写入有竞态，clear 返回成功不代表读取开始时缓冲里一定没有旧条目",
            "部分 ROM/logd 对 crash buffer 的 clear 可能忽略、延迟或只清部分；厂商持久化日志/logpersist 也可能保留旧记录",
            "采集客户端可能在 clear 之前已经读取/缓存了旧行；这不是缓冲中仍有新内容",
            "读取时若没有指定 -b crash 或用了更宽的过滤，容易把 main buffer 里的旧崩溃当成本次新崩溃",
        )
        val steps = jsonArray(
            "记录采集窗口开始时间（epoch ms，如 date +%s%3N）与目标 PID/包名",
            "清前先读取一次 crash buffer，记录旧条目的 timestamp/tag/pid，作为对照指纹",
            "执行 logcat -b crash -c，并记录退出码与执行时间（退出码 0 不代表已彻底清空）",
            "清后立即以固定窗口读取：logcat -b crash -d -v threadtime（需要时加 -T <windowStart> 与 --pid=<pid>）",
            "逐条核对 timestamp 是否 >= 窗口开始、pid 是否为目标 PID；窗口外或非目标 PID 的条目是旧条目，不能算新崩溃",
            "需要完整隔离时再用 logcat -b all -c（会清所有 buffer，影响其它采集，需用户授权）或在 MCP 层按 tag/package/pid/时间窗过滤",
            "把采集窗口、清缓冲命令、读取命令、PID、时间戳和原始行一起留档；不要把 SIGKILL/旧条目当作新崩溃",
        )
        val doNot = jsonArray(
            "不要把 SIGKILL、进程被杀、系统回收直接当作崩溃原因或某个检测分支的证据",
            "不要把清缓冲前就存在、或 timestamp 早于采集窗口的条目当作本次新崩溃",
            "不要只执行 logcat -b crash -c 就宣称设备没有崩溃或已完全清空",
            "不要用旧的 crash 文本推断当前进程状态；需要同时记录 PID/时间窗/采集命令",
        )
        return ToolkitIo.json(
            "buffer" to "crash",
            "clearCommand" to "logcat -b crash -c",
            "clearAllCommand" to "logcat -b all -c",
            "clearAllCaveat" to "会清 main/system/events/crash 等所有 buffer，影响其它采集，需用户/任务授权；仍有清理竞态与厂商持久化日志问题",
            "readCommand" to "logcat -b crash -d -v threadtime",
            "clearScope" to "crash-only",
            "clearOnlyCrashBuffer" to true,
            "clearCommandOnlyCrashBuffer" to true,
            "doesNotClearMainBuffer" to true,
            "doesNotClearOtherBuffers" to true,
            "clearIsNotBarrier" to true,
            "canStillReadOldEntries" to true,
            "semantics" to jsonArray(
                "logcat -b crash -c 目标只是 crash ring buffer，不是整个 logcat 的所有 buffer",
                "成功的清理命令不是同步屏障；旧条目可能来自其它 buffer、持久化日志、清理竞态或清前已采集的缓存",
                "判断新崩溃必须绑定采集窗口时间戳与 PID，而不是只看是否还能 grep 到 crash 行",
                "SIGKILL 不是崩溃（也不是 Java/native 异常栈）；不能据此推断检测分支",
            ),
            "whyStillOldEntries" to why,
            "whyOldEntries" to why.deepCopy(),
            "why" to why.deepCopy(),
            "oldEntriesMayComeFrom" to jsonArray(
                "main/system/events buffer 中仍保留的旧条目",
                "crash buffer 清理竞态或厂商 logd 未真正清空",
                "logpersist/持久化日志或其它采集器已保存的旧记录",
                "客户端在 clear 前已读取/缓存的内容",
                "读取命令未限定 -b crash / 时间窗 / PID 导致的混入",
            ),
            "verification" to ToolkitIo.json(
                "requiredFields" to jsonArray(
                    "buffer",
                    "clearCommand",
                    "readCommand",
                    "windowStartEpochMs",
                    "windowEndEpochMs",
                    "pid",
                    "timestamp",
                    "tag",
                    "message",
                ),
                "timeFormat" to "epoch milliseconds（可用 logcat -v epoch 或 date +%s%3N）",
                "steps" to steps,
                "doNot" to doNot,
                "windowCommands" to ToolkitIo.json(
                    "start" to "date +%s%3N",
                    "clear" to "logcat -b crash -c",
                    "read" to "logcat -b crash -d -v threadtime",
                    "readSinceWindow" to "logcat -b crash -d -v epoch -T <windowStartEpochMs>",
                    "readTargetPid" to "logcat -b crash -d -v threadtime --pid=<pid>",
                ),
            ),
            "howToVerify" to ToolkitIo.json(
                "steps" to steps.deepCopy(),
                "doNot" to doNot.deepCopy(),
                "note" to "与 verification 等价；保留字段名兼容",
            ),
            "verify" to ToolkitIo.json(
                "steps" to steps.deepCopy(),
                "doNot" to doNot.deepCopy(),
                "note" to "与 verification 等价；保留字段名兼容",
            ),
            "sigkill" to "SIGKILL/进程被杀不是崩溃原因；不能当作新崩溃或检测分支证据",
            "notEvidenceOfNewCrash" to jsonArray(
                "SIGKILL",
                "timestamp 早于采集窗口的 crash 行",
                "PID 不是目标进程的 crash 行",
                "清缓冲前已采集/缓存的旧行",
            ),
            "notCrashEvidence" to jsonArray(
                "SIGKILL",
                "timestamp 早于采集窗口的 crash 行",
                "PID 不是目标进程的 crash 行",
                "清缓冲前已采集/缓存的旧行",
            ),
            "sigkillNotCrash" to true,
            "doNot" to doNot.deepCopy(),
            "pitfalls" to doNot.deepCopy(),
            "recommended" to "记录采集窗口、clear/read 命令、PID 与时间戳；用 -b crash 加 --pid/-T 过滤，只把窗口内且 PID 匹配的条目当新证据",
            "notes" to jsonArray(
                "logcat -b all -c 会清 main/system/events/crash 等所有 buffer；是否允许清全部由用户/任务决定",
                "crash buffer 的清理成功不等于设备没有崩溃；必须证明采集窗口覆盖了目标动作",
                "本对象只返回语义和方法，不执行 logcat，也不修改设备缓冲",
            ),
        )
    }

    // ------------------------------------------------------------------
    // 内部构造
    // ------------------------------------------------------------------

    private fun entry(
        name: String,
        available: Boolean,
        status: String = if (available) "available" else "unavailable",
        unsupported: List<String> = emptyList(),
        alternatives: List<JsonObject>,
        notes: List<String>,
    ): JsonObject {
        val unsupportedJson = JsonArray()
        unsupported.forEach { unsupportedJson.add(it) }
        val alternativesJson = JsonArray()
        alternatives.forEach { alternativesJson.add(it) }
        val firstHint = alternatives.firstOrNull()?.get("hint")?.asString ?: ""
        return ToolkitIo.json(
            "name" to name,
            "command" to name,
            "available" to available,
            "status" to status,
            "unsupported" to unsupportedJson,
            "unsupportedOptions" to unsupportedJson.deepCopy(),
            "alternatives" to alternativesJson,
            "replacements" to alternativesJson.deepCopy(),
            "alternative" to firstHint,
            "fallback" to firstHint,
            "notes" to jsonArray(*notes.toTypedArray()),
        )
    }

    private fun mcp(tool: String, hint: String, args: Map<String, String>? = null): JsonObject {
        val result = ToolkitIo.json("kind" to "mcp_tool", "tool" to tool, "hint" to hint)
        if (args != null) {
            val arguments = JsonObject()
            args.forEach { (key, value) -> arguments.addProperty(key, value) }
            result.add("args", arguments)
        }
        return result
    }

    private fun shell(command: String, hint: String): JsonObject =
        ToolkitIo.json("kind" to "shell", "command" to command, "hint" to hint)

    private fun commandName(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        val first = trimmed.takeWhile { !it.isWhitespace() }
        return first.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT).ifEmpty { null }
    }

    private fun alternativesFor(cmd: String?, category: String, scope: String?): JsonArray {
        val result = JsonArray()
        fun add(action: JsonObject) { result.add(action) }
        when (cmd) {
            "grep" -> {
                add(mcp("file_search_text", "MCP 文本搜索；替代 busybox grep 的 \\b/\\s"))
                add(mcp("file_search", "目录内文件搜索；替代简单 grep -r"))
                add(shell("node <script.js>", "复杂正则/Unicode 处理写成 .js 文件再运行"))
                add(shell("grep -E <POSIX ERE>", "仅基本 POSIX ERE；不要用 \\b/\\s"))
            }
            "cat" -> {
                add(mcp("file_read_text", "读取文本文件，支持编码/分页；替代 cat -n/-A"))
                add(shell("sed -n '1,200p' <file>", "按行范围读取；替代 cat -n"))
                add(shell("grep -n '' <file>", "带行号读取少量内容"))
            }
            "zip", "jar", "7z" -> {
                add(mcp("zip_list", "列出 ZIP/jar 条目（7z 先确认格式）"))
                add(mcp("zip_extract", "解压 ZIP 条目"))
                add(mcp("zip_create", "创建 ZIP；不要把 tar 当 zip"))
                add(shell("tar -cf <out.tar> <files>", "只有 tar 归档可用"))
                add(shell("node <pack.js>", "自定义 pack.js 打包，避免依赖 zip/jar/7z"))
            }
            "python", "python3" -> {
                add(mcp("terminal_exec", "先核对后端/PATH；python 链接损坏时不要依赖", args = mapOf("backend" to "app")))
                add(shell("node <script.js>", "改写为 Node 脚本；python 链接坏时 node 可用"))
            }
            "pm", "am", "logcat", "uiautomator", "settings", "cmd", "dumpsys" -> {
                add(mcp("terminal_exec", "通常只能由 Shizuku/ADB shell 后端执行；用 backend=shizuku", args = mapOf("backend" to "shizuku")))
                add(mcp("file_access_diagnose", "若涉及文件访问/权限，先诊断实际后端与 errno"))
            }
            "javac", "java", "d8", "aapt2", "apksigner" -> {
                add(mcp("terminal_exec", "通常只在该应用的私有工具链/后端可达；先确认 backend 与 PATH", args = mapOf("backend" to "app")))
            }
            else -> {
                if (category == "unrecognized_option") {
                    add(mcp("terminal_exec", "先查 help/版本并改用兼容选项；不要猜测选项可用"))
                    add(shell("node <script.js>", "需要复杂文本处理时写文件再用 node"))
                } else if (category == "inaccessible") {
                    add(mcp("terminal_exec", "确认后端：app 或 shizuku；权限/命令可达性不同", args = mapOf("backend" to "shizuku")))
                    add(mcp("file_access_diagnose", "文件权限/errno 诊断"))
                } else if (category == "not_found" && scope == "command") {
                    add(mcp("terminal_exec", "先确认命令是否在当前后端 PATH；不要直接断定设备没装"))
                    add(mcp("get_shell_capabilities", "查看当前 shell 能力清单与替代写法"))
                } else if (category == "not_found" && scope == "file") {
                    add(mcp("file_stat", "先确认文件存在与可读性"))
                    add(mcp("file_list", "查看父目录内容"))
                } else {
                    add(mcp("terminal_exec", "请补充退出码/完整 stderr；不要根据不明确输出猜测能力缺口"))
                }
            }
        }
        return result
    }

    private fun backendHint(cmd: String?, category: String): String? {
        if (cmd in setOf("pm", "am", "logcat", "uiautomator", "settings", "cmd", "dumpsys")) {
            return "该命令通常只在 Shizuku/ADB shell 后端可达；terminal_exec 用 backend=shizuku（需已运行授权），不要默认 app 后端"
        }
        if (cmd in setOf("javac", "java", "d8", "aapt2", "apksigner")) {
            return "该命令通常在应用私有工具链/后端可达；先确认 terminal_exec backend=app 与 PATH"
        }
        if (category == "inaccessible") {
            return "可能是后端权限或文件权限问题；先确认 terminal_exec backend 与目标路径权限，不要只当成命令未安装"
        }
        if (category == "unrecognized_option") {
            return "选项可能是 busybox/旧版工具不支持；先查 help，再改用 MCP 工具或写 node 脚本"
        }
        return null
    }

    private fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        if (value.all { it.isLetterOrDigit() || it in "_-./:=+,@" }) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun jsonArray(vararg values: String): JsonArray {
        val array = JsonArray()
        values.forEach { array.add(it) }
        return array
    }
}

private val OPTION_MARKERS = listOf(
    "unrecognized option",
    "invalid option",
    "illegal option",
    "unknown option",
    "option not recognized",
    "bad option",
    "不支持该选项",
    "无效选项",
)

private val INACCESSIBLE_MARKERS = listOf(
    "inaccessible",
    "permission denied",
    "operation not permitted",
    "not permitted",
    "access denied",
    "eacces",
)
