package com.jdkshen.aijspro.mcp

import com.google.gson.*

/**
 * Frida 连接失败诊断（BUG-3，用户侧复盘「三个真 bug」之三）。
 *
 * 实测证据：Gadget 注入/安装/启动成功，27042 在 /proc/net/tcp 为 state=0A(LISTEN)；
 * 但 frida_status/frida CLI connect 超时；连接侧 /proc/<pid>/net/tcp 出现
 * 127.0.0.1:<随机端口> -> 127.0.0.1:69A2 state=02(SYN_SENT)，SYN 被静默丢弃
 * （若是防火墙拒绝会收到 RST/CLOSE）⇒ 跨 UID loopback 被 SELinux 拦，换 UID 也救不了。
 *
 * 本对象只做纯逻辑判定/文案/可执行动作，不读 /proc、不调用客户端、不执行命令；
 * 调用方（frida_status / apk_inject_gadget / 诊断工具）把观测值传进来，按返回的
 * nextActions / evidenceCommands 继续排查。
 *
 * 措辞原则：
 * - 不能把 connect 超时当成「组件没装」；SYN_SENT 反而证明客户端已成功发出连接；
 * - 证据不足时返回 unknown 并按 unknown 处理，不猜 SELinux；
 * - 推荐改走 Gadget interaction.type="script"（不碰网络），或把执行端放进同 UID。
 */
internal object ToolkitFridaDiagnosis {
    const val CLASSIFICATION_SELINUX_SUSPECT = "selinux_loopback_suspect"
    const val CLASSIFICATION_PORT_NOT_LISTENING = "port_not_listening"
    const val CLASSIFICATION_COMPONENT_MISSING = "component_missing_or_command_error"
    const val CLASSIFICATION_SAME_UID = "same_uid_connect_failure"
    const val CLASSIFICATION_CONNECTION_REFUSED = "connection_refused"
    const val CLASSIFICATION_UNKNOWN = "unknown"

    /** #11：frida_status 失败时给 job/工具层使用的结构化错误码；reason 仍保留原文。 */
    const val FRIDA_CONNECT_FAILED = "FRIDA_CONNECT_FAILED"
    const val FRIDA_SERVER_MISSING = "FRIDA_SERVER_MISSING"
    const val FRIDA_COMPONENT_MISSING = "FRIDA_COMPONENT_MISSING"
    const val FRIDA_UNKNOWN = "FRIDA_UNKNOWN"

    const val TCP_STATE_ESTABLISHED = "01"
    const val TCP_STATE_SYN_SENT = "02"
    const val TCP_STATE_TIME_WAIT = "06"
    const val TCP_STATE_CLOSE_WAIT = "08"
    const val TCP_STATE_LISTEN = "0A"

    const val DEFAULT_GADGET_PORT = 27042
    /** 27042 的十六进制表示，用于 /proc/net/tcp 里的 :69A2。 */
    const val DEFAULT_GADGET_PORT_HEX = "69A2"

    private val TIMEOUT_PATTERN = Regex(
        "(?i)(timed?\\s*out|timeout|etimedout|timedout\\s*=\\s*true|operation was cancelled|cancelled.*timeout)",
    )
    private val COMPONENT_MISSING_PATTERN = Regex(
        "(?i)(not found|no such file|command not found|cannot find|ENOENT|missing (?:frida|component)|component.*(?:not installed|missing))",
    )
    private val REFUSED_PATTERN = Regex("(?i)(connection refused|econnrefused|reset by peer|\\brst\\b)")
    private val SERVER_MISSING_PATTERN = Regex(
        "(?i)(frida[- ]?server.*(?:not found|no such file|missing|not running|not started|not installed)|(?:not found|no such file|not running).*(?:frida[- ]?server))",
    )
    private val CONNECT_FAILED_PATTERN = Regex(
        "(?i)(unable to connect|failed to connect|cannot connect|connection refused|econnrefused|connection reset|timed? out|timeout|operation was cancelled|ehostunreach|enetunreach)",
    )

    private const val NOT_COMPONENT_MISSING =
        "connect 超时/SYN_SENT 不能直接当成「组件没装」：超时是连接层现象；若已看到 SYN_SENT，说明客户端存在并成功发出了 connect，只是 SYN 被静默丢弃。"

    /**
     * 判定一次 connect 失败更像什么原因。
     *
     * selinuxLoopbackSuspect 的条件（与任务定义一致）：portListening=true 且
     * (synSentObserved=true 或 错误文本是 connect 超时) 且 sameUid=false。
     * 证据不足时返回 unknown，不猜 SELinux；错误文本明确是「not found/命令不存在」
     * 且没有 SYN_SENT 时按组件/命令问题处理，不判 SELinux。
     */
    fun classifyConnectFailure(
        errorText: String,
        portListening: Boolean,
        synSentObserved: Boolean,
        sameUid: Boolean,
    ): JsonObject {
        val text = errorText.trim().take(2000)
        val timeoutObserved = TIMEOUT_PATTERN.containsMatchIn(text)
        val componentMissingHint = COMPONENT_MISSING_PATTERN.containsMatchIn(text)
        val refusedObserved = REFUSED_PATTERN.containsMatchIn(text)
        val synSentDropSignature = portListening && synSentObserved && !sameUid
        val suspect = portListening && (synSentObserved || timeoutObserved) && !sameUid
        val evidenceConflict = sameUid && synSentObserved

        val classification: String
        val confidence: String
        var reason: String
        when {
            suspect -> {
                classification = CLASSIFICATION_SELINUX_SUSPECT
                confidence = if (synSentObserved) "high" else "medium"
                reason = "端口处于 LISTEN" +
                    (if (synSentObserved) "，且连接侧看到 SYN_SENT：SYN 被静默丢弃（防火墙拒绝通常是 RST/CLOSE）" else "，跨 UID 的 connect 超时") +
                    "；符合跨 UID loopback 被 SELinux DROP 的特征。这是结构性网络限制，不是组件没装，换 UID 也救不了。"
            }
            componentMissingHint && !synSentObserved -> {
                classification = CLASSIFICATION_COMPONENT_MISSING
                confidence = "medium"
                reason = "错误文本更像客户端/命令缺失（not found/no such file 等）；先核对组件或可执行文件。若文本其实指目标进程不存在，请先检查 pid/进程存活，不要据此判断 SELinux。"
            }
            !portListening && text.isNotEmpty() -> {
                classification = CLASSIFICATION_PORT_NOT_LISTENING
                confidence = "medium"
                reason = "端口未 LISTEN：先确认 Gadget 注入/启动是否成功、应用默认进程是否存活、是否被系统重启；这不是 loopback 连通性问题，也不能仅凭超时判定组件没装。"
            }
            refusedObserved -> {
                classification = CLASSIFICATION_CONNECTION_REFUSED
                confidence = "medium"
                reason = "出现 connection refused/RST 类错误：说明对端明确拒绝，而不是 SYN 被静默丢弃；先核对端口 LISTEN 状态、端口十六进制、目标进程/命名空间与注入是否最新。"
            }
            sameUid && (timeoutObserved || synSentObserved) -> {
                classification = CLASSIFICATION_SAME_UID
                confidence = "medium"
                reason = "同 UID 仍 connect 超时：通常指向客户端组件/版本、目标应用 INTERNET 权限、endpoint/token 或 Gadget 尚未就绪；同 UID 不符合跨 UID SELinux 的典型条件。超时本身不能证明组件没装。"
            }
            text.isEmpty() -> {
                classification = CLASSIFICATION_UNKNOWN
                confidence = "unknown"
                reason = "证据不足：没有 connect 错误文本，也没有 SYN_SENT 证据；无法区分组件/注入、端口未就绪与 SELinux DROP，不猜 SELinux。"
            }
            else -> {
                classification = CLASSIFICATION_UNKNOWN
                confidence = "unknown"
                reason = "证据不足或错误文本不典型：无法可靠归因；按 nextActions 采集端口 LISTEN、SYN_SENT 与 UID 证据后再判定，不猜 SELinux。"
            }
        }
        if (!suspect && evidenceConflict) {
            reason += " 注意：sameUid=true 与 SYN_SENT 证据冲突，请先核对目标进程/客户端进程的真实 UID。"
        }

        val componentVerdict = when {
            suspect -> "not_the_cause"
            classification == CLASSIFICATION_COMPONENT_MISSING -> "possible_missing"
            else -> "unknown"
        }
        return ToolkitIo.json(
            "classification" to classification,
            "selinuxLoopbackSuspect" to suspect,
            "confidence" to confidence,
            "portListening" to portListening,
            "synSentObserved" to synSentObserved,
            "sameUid" to sameUid,
            "timeoutObserved" to timeoutObserved,
            "componentMissingHint" to componentMissingHint,
            "rstOrRefusedObserved" to refusedObserved,
            "componentVerdict" to componentVerdict,
            "evidenceConflict" to evidenceConflict,
            "errorCode" to errorCodeForClassification(classification),
            "reasonCode" to errorCodeForClassification(classification),
            "reason" to reason,
            "wordingGuidance" to NOT_COMPONENT_MISSING,
            "evidence" to ToolkitIo.json(
                "synSentDropSignature" to synSentDropSignature,
                "listenStateHint" to TCP_STATE_LISTEN,
                "synSentStateHint" to TCP_STATE_SYN_SENT,
                "interpretation" to "LISTEN(0A)+连接侧 SYN_SENT(02) 表示 SYN 被静默丢弃；若是防火墙拒绝会看到 RST/连接被拒绝，而不是停在 SYN_SENT。",
                "errorText" to text,
            ),
            "nextActions" to actionsFor(classification),
            "evidenceCommands" to defaultEvidenceCommands(),
        )
    }

    /** 把 connect 失败分类映射成 #11 的结构化 errorCode；未知分类保守归为 CONNECT_FAILED。 */
    fun errorCodeForClassification(classification: String): String = when (classification) {
        CLASSIFICATION_COMPONENT_MISSING -> FRIDA_COMPONENT_MISSING
        else -> FRIDA_CONNECT_FAILED
    }

    /**
     * #11：按 frida_status 的 reason 文本给结构化 errorCode。
     *
     * 顺序：frida-server 明确缺失 → 客户端/命令缺失 → connect 失败 → 未知。
     * “Unable to connect to remote frida-server” 归 FRIDA_CONNECT_FAILED（文本没有证明 server 文件缺失，
     * 不能把 connect 失败说成 server 未安装）；只有文本明确 “server not found/not running” 才归 FRIDA_SERVER_MISSING。
     */
    fun errorCodeForReason(reason: String): String {
        val text = reason.trim()
        if (text.isEmpty()) return FRIDA_UNKNOWN
        return when {
            SERVER_MISSING_PATTERN.containsMatchIn(text) -> FRIDA_SERVER_MISSING
            COMPONENT_MISSING_PATTERN.containsMatchIn(text) -> FRIDA_COMPONENT_MISSING
            CONNECT_FAILED_PATTERN.containsMatchIn(text) -> FRIDA_CONNECT_FAILED
            else -> FRIDA_CONNECT_FAILED
        }
    }

    /**
     * #11 失败信封：给 frida_status 失败结果（以及 job 层失败项）一个可直接合并的结构。
     * 保留 reason 原文；error 对象同时带 code，兼容只看 error.code 的调用方。
     */
    fun failureEnvelope(reason: String, classification: String? = null): JsonObject {
        val code = if (classification != null) errorCodeForClassification(classification) else errorCodeForReason(reason)
        return ToolkitIo.json(
            "ok" to false,
            "errorCode" to code,
            "reasonCode" to code,
            "reason" to reason,
            "classification" to classification,
            "error" to ToolkitIo.json("code" to code, "message" to reason),
            "note" to "errorCode 按 reason/classification 分类；reason 保留原文。job 层若只展示状态，需要把该字段写入 job 状态/结果（见 ToolkitJobs.status）。",
        )
    }

    /**
     * 推荐替代：Gadget interaction.type="script"（启动即加载脚本，不监听端口、不碰网络）。
     * 注意：当前 apk_inject_gadget 固定生成 listen 配置，需要接线支持脚本模式；也可手工替换
     * APK 内 lib/<abi>/libaijsgadget.config.so 后重签（ToolkitGadget.isConfiguration 已接受 script）。
     */
    fun gadgetScriptModeGuidance(): JsonObject {
        return ToolkitIo.json(
            "mode" to "script",
            "tool" to "apk_inject_gadget",
            "recommended" to true,
            "why" to "Gadget interaction.type=script 在应用启动时直接加载脚本，不监听端口、不经过 loopback，因此不受跨 UID loopback 的 SELinux 静默 DROP 影响；这是免 Root Gadget 的推荐正路。",
            "configSnippet" to ToolkitIo.json(
                "interaction" to ToolkitIo.json(
                    "type" to "script",
                    "path" to "<目标应用 UID 可读的绝对路径>",
                    "on_load" to "resume",
                ),
            ),
            "currentImplementation" to "当前 apk_inject_gadget/ToolkitGadget.prepare 固定生成 interaction.type=listen；需要 Lead 接线增加 interactionType=script 与 scriptPath 参数。ToolkitGadget.isConfiguration 已接受 script/script-directory，因此手工替换 APK 内 lib/<abi>/libaijsgadget.config.so 后重签也可行，但不建议作为常规路径。",
            "scriptPathRequirements" to JsonArray().apply {
                add("path 必须是目标应用 UID 能读取的绝对路径（优先应用内部 files 目录或首启时由应用自身落盘的脚本；/data/local/tmp 通常不是其他 UID 可直接读的）")
                add("脚本在应用启动早期由 Gadget 加载，path 必须在启动前就存在")
                add("写入脚本后需要重新启动目标应用（或由注入配置的 on_load 控制时机）")
            },
            "outputContract" to "script 模式没有 Frida 网络通道：脚本必须把结果写到 MCP 可读的文件（例如目标应用 files 目录下约定文件，或 MCP root 下的输出文件），再用 read_text/file_* 读取；不要指望 frida_eval attach script 模式端口。",
            "limits" to JsonArray().apply {
                add("不能在运行中交互 eval（无客户端连接）；适合启动期一次性 hook/采集")
                add("脚本路径不可读或脚本报错时，应用可能照常启动但只留 logcat 线索，需要检查 logcat 中 Frida 相关行")
                add("需要重新注入/重装/重启应用；已有 listen 包不能直接切 script，需重新生成配置")
            },
            "verificationSteps" to JsonArray().apply {
                add("重启目标应用后看 logcat 是否出现 Gadget 加载脚本/脚本错误")
                add("检查脚本约定输出文件是否生成、内容是否完整")
                add("用 read_text/file_stat 读取输出文件并核对摘要，不依赖网络端口")
            },
            "nextActions" to JsonArray().apply {
                add(ToolkitIo.json("action" to "switch_to_gadget_script_mode", "tool" to "apk_inject_gadget",
                    "argsHint" to ToolkitIo.json("interactionType" to "script", "scriptPath" to "<目标应用可读的脚本路径>"),
                    "hint" to "重新生成注入包时使用 script 模式；需先有输出文件约定，且目标应用可读该脚本路径", "priority" to 1))
                add(ToolkitIo.json("action" to "check_listen_path_first", "tool" to "terminal_exec", "backend" to "app|shizuku",
                    "command" to "grep -i ':69A2' /proc/net/tcp",
                    "hint" to "若仍想用 listen：先确认 0A(LISTEN) 与连接侧 02(SYN_SENT) 证据，再判定是否为跨 UID SELinux", "priority" to 2))
            },
        )
    }

    /**
     * listen 模式的边界与排查步骤：默认配置、loopback 依赖、跨 UID SELinux 风险、
     * /proc/net/tcp 的 0A/02 证据读法，以及可执行的下一步。
     */
    fun listenModeLimits(): JsonObject {
        return ToolkitIo.json(
            "mode" to "listen",
            "defaultConfig" to ToolkitIo.json(
                "interaction" to ToolkitIo.json(
                    "type" to "listen",
                    "address" to "127.0.0.1",
                    "port" to DEFAULT_GADGET_PORT,
                    "on_port_conflict" to "fail",
                    "on_load" to "resume",
                ),
            ),
            "networkPath" to "客户端进程 -> 127.0.0.1:<port> -> 目标应用默认进程内的 Gadget；连接必须穿过 loopback 与 UID 边界。",
            "limits" to JsonArray().apply {
                add("跨 UID loopback 在部分 SELinux 策略下被默认 DROP（SYN 被静默丢弃，不会收到 RST/CLOSE）；这是结构性限制，换 UID 也救不了。")
                add("listen 只绑定 127.0.0.1，不监听外部网卡；远程访问需 adb forward 或改为 connect 模式。")
                add("端口冲突按 on_port_conflict=fail 处理；端口未 LISTEN 时要先查注入/启动/默认进程存活，而不是先怀疑网络。")
                add("token 由注入结果提供；token 不匹配会表现为连接失败，不能归因 SELinux。")
                add("Gadget 只在应用默认进程加载；应用被杀/未启动时端口不存在。")
            },
            "tcpStates" to ToolkitIo.json(
                "LISTEN" to TCP_STATE_LISTEN,
                "SYN_SENT" to TCP_STATE_SYN_SENT,
                "ESTABLISHED" to TCP_STATE_ESTABLISHED,
                "CLOSE_WAIT" to TCP_STATE_CLOSE_WAIT,
                "TIME_WAIT" to TCP_STATE_TIME_WAIT,
            ),
            "observationCommands" to JsonArray().apply {
                add(ToolkitIo.json("command" to "cat /proc/net/tcp", "purpose" to "查看本 UID 可见的 loopback LISTEN；目标端口在 local_address 里显示为 0100007F:69A2"))
                add(ToolkitIo.json("command" to "grep -i ':69A2' /proc/net/tcp", "purpose" to "只看 27042；state=0A 才是 LISTEN，换端口时把 69A2 换成端口十六进制"))
                add(ToolkitIo.json("command" to "cat /proc/<target-pid>/net/tcp", "purpose" to "从目标 pid 视角看 LISTEN 是否真的存在（不同 UID/命名空间视图可能不同）"))
                add(ToolkitIo.json("command" to "cat /proc/<client-pid>/net/tcp", "purpose" to "连接卡住时看是否出现 127.0.0.1:69A2 且 state=02(SYN_SENT)；这是 SYN 被静默丢弃的决定性证据"))
                add(ToolkitIo.json("command" to "cat /proc/<target-pid>/status | grep -E '^(Name|Uid):'", "purpose" to "读取目标进程 UID；与客户端/MCP 进程 UID 对比，判断是否跨 UID"))
            },
            "decisionTable" to JsonArray().apply {
                add(ToolkitIo.json("portListening" to true, "synSentObserved" to true, "sameUid" to false,
                    "conclusion" to "跨 UID SELinux DROP 嫌疑高；改 Gadget script 模式或同 UID 执行，不要当成组件没装"))
                add(ToolkitIo.json("portListening" to true, "synSentObserved" to false, "sameUid" to false,
                    "conclusion" to "仍可疑：超时本身证据较弱；先抓连接侧 /proc/<pid>/net/tcp 的 SYN_SENT 与 UID 再判定"))
                add(ToolkitIo.json("portListening" to true, "synSentObserved" to false, "sameUid" to true,
                    "conclusion" to "同 UID 超时：查客户端组件/权限/endpoint/token，不判跨 UID SELinux"))
                add(ToolkitIo.json("portListening" to false, "synSentObserved" to false, "sameUid" to false,
                    "conclusion" to "端口未 LISTEN：先查注入/启动/默认进程，组件或注入问题优先"))
            },
            "troubleshootingSteps" to JsonArray().apply {
                add(ToolkitIo.json("step" to 1, "action" to "确认端口真在 LISTEN",
                    "detail" to "在目标应用启动后读 /proc/net/tcp 或 /proc/<target-pid>/net/tcp，找 0100007F:<port-hex> state=0A；不是 0A 就先查注入/启动。"))
                add(ToolkitIo.json("step" to 2, "action" to "复现失败并抓 SYN_SENT",
                    "detail" to "发起 connect 时读 /proc/<client-pid>/net/tcp，若目标 :69A2 的 state=02(SYN_SENT) 且长时间不变，说明 SYN 被静默丢弃。"))
                add(ToolkitIo.json("step" to 3, "action" to "区分拒绝与丢弃",
                    "detail" to "立刻变成 CLOSE/connection refused 是 RST/明确拒绝；停在 SYN_SENT 才符合 SELinux DROP 特征。"))
                add(ToolkitIo.json("step" to 4, "action" to "核对 UID",
                    "detail" to "比较目标进程 Uid 与客户端/MCP 进程 Uid；跨 UID 才符合本诊断的主要嫌疑。"))
                add(ToolkitIo.json("step" to 5, "action" to "改走不碰网络的路径",
                    "detail" to "优先 Gadget interaction.type=script；或把执行端放进同 UID；或复用 frida_eval 一次性路径并注明它同样受跨 UID 限制。"))
            },
            "notComponentMissingNote" to "connect 超时、0 命中或 SYN_SENT 都不能直接得出「组件没装」；端口未 LISTEN/not found 类文本才需要优先怀疑注入或组件，请按 evidence 与本对象 classification 区分。",
            "nextActions" to JsonArray().apply {
                add(ToolkitIo.json("action" to "switch_to_gadget_script_mode", "tool" to "apk_inject_gadget",
                    "argsHint" to ToolkitIo.json("interactionType" to "script", "scriptPath" to "<目标应用可读的脚本路径>"),
                    "hint" to "首推：不监听端口、不经过 loopback，绕开跨 UID SELinux DROP", "priority" to 1))
                add(ToolkitIo.json("action" to "run_client_in_same_uid", "priority" to 2,
                    "hint" to "把执行端放进与目标应用相同 UID 的进程（目标应用内加载客户端/同包组件）；不要从 MCP :tools 或 shizuku shell 跨 UID connect"))
                add(ToolkitIo.json("action" to "use_frida_eval_one_shot", "tool" to "frida_eval", "priority" to 3,
                    "argsHint" to ToolkitIo.json("endpoint" to "127.0.0.1:27042", "token" to "<token>", "pid" to "<pid>", "script" to "<script>"),
                    "hint" to "已有一次性执行路径可复用；但若同样从工具进程跨 UID 发起连接，仍可能 SYN_SENT，最终仍需 script 模式或同 UID"))
                add(ToolkitIo.json("action" to "confirm_listen_state", "tool" to "terminal_exec", "backend" to "app|shizuku", "priority" to 4,
                    "command" to "grep -i ':69A2' /proc/net/tcp",
                    "hint" to "先确认端口是否真在 LISTEN（0A）；再抓 /proc/<client-pid>/net/tcp 的 SYN_SENT(02)"))
            },
        )
    }

    private fun actionsFor(classification: String): JsonArray = JsonArray().apply {
        when (classification) {
            CLASSIFICATION_SELINUX_SUSPECT -> {
                add(ToolkitIo.json("action" to "switch_to_gadget_script_mode", "tool" to "apk_inject_gadget",
                    "argsHint" to ToolkitIo.json("interactionType" to "script", "scriptPath" to "<目标应用可读的脚本路径>"),
                    "hint" to "首推：Gadget interaction.type=script 启动即加载脚本，不监听端口/不碰 loopback，不受跨 UID SELinux 影响", "priority" to 1))
                add(ToolkitIo.json("action" to "run_client_in_same_uid", "priority" to 2,
                    "hint" to "把执行端放进目标应用相同 UID 的进程（目标应用内加载客户端/同包组件），不要从 MCP :tools 或 shizuku shell 跨 UID connect"))
                add(ToolkitIo.json("action" to "use_frida_eval_one_shot", "tool" to "frida_eval", "priority" to 3,
                    "argsHint" to ToolkitIo.json("endpoint" to "127.0.0.1:27042", "token" to "<token>", "pid" to "<pid>", "script" to "<script>"),
                    "hint" to "已有一次性执行路径可复用；但若仍由工具进程跨 UID 发起，SYN_SENT 会重现"))
                addAll(defaultListenActions())
            }
            CLASSIFICATION_SAME_UID -> {
                add(ToolkitIo.json("action" to "check_client_component", "tool" to "apk_component_status",
                    "argsHint" to ToolkitIo.json("id" to "frida"),
                    "hint" to "同 UID 超时先核对 frida 客户端组件/版本与目标应用 INTERNET 权限；超时本身不能证明组件没装，只是排查方向", "priority" to 1))
                add(ToolkitIo.json("action" to "retry_frida_status", "tool" to "frida_status", "priority" to 2,
                    "argsHint" to ToolkitIo.json("endpoint" to "127.0.0.1:27042", "token" to "<token>"),
                    "hint" to "确认 endpoint/端口/token 与注入结果一致，可加大超时后重试"))
                addAll(defaultListenActions())
            }
            CLASSIFICATION_PORT_NOT_LISTENING -> {
                add(ToolkitIo.json("action" to "check_gadget_component_and_injection", "tool" to "apk_component_status",
                    "argsHint" to ToolkitIo.json("id" to "frida-gadget"),
                    "hint" to "端口未 LISTEN 优先查组件/注入/启动，而不是网络：确认注入包来自最新 outPath、已安装并启动应用默认进程", "priority" to 1))
                add(ToolkitIo.json("action" to "check_process_alive", "tool" to "terminal_exec", "backend" to "app|shizuku",
                    "command" to "ps -A | grep <target-package>",
                    "hint" to "应用被杀/未启动时端口不存在；Gadget 只在应用默认进程加载", "priority" to 2))
                addAll(defaultListenActions())
            }
            CLASSIFICATION_COMPONENT_MISSING -> {
                add(ToolkitIo.json("action" to "check_component_status", "tool" to "apk_component_status",
                    "argsHint" to ToolkitIo.json("id" to "frida"),
                    "hint" to "错误文本显示 not found/命令缺失：先核对 frida 客户端组件是否安装/可执行；若文本指目标进程不存在，则先修 pid/进程存活", "priority" to 1))
                add(ToolkitIo.json("action" to "install_or_update_component", "tool" to "apk_component_install",
                    "argsHint" to ToolkitIo.json("id" to "frida"),
                    "hint" to "组件缺失/版本过旧时安装或更新；结合 apk_component_status 的结果判断", "priority" to 2))
            }
            CLASSIFICATION_CONNECTION_REFUSED -> {
                add(ToolkitIo.json("action" to "verify_listen_and_port", "priority" to 1,
                    "hint" to "RST/refused 是明确拒绝：核对 LISTEN 状态、端口十六进制（27042=69A2）、目标进程命名空间与注入是否最新；这不是 SYN 静默丢弃"))
                add(ToolkitIo.json("action" to "recheck_injection_result", "tool" to "apk_inject_gadget", "priority" to 2,
                    "hint" to "重新确认注入输出 APK 的 endpoint/port 与 token，避免连到旧包/旧端口"))
            }
            else -> {
                add(ToolkitIo.json("action" to "collect_connect_evidence", "priority" to 1,
                    "hint" to "先补齐证据：connect 错误原文与 timedOut、/proc/net/tcp 的 LISTEN(0A)、连接侧 SYN_SENT(02)、目标/客户端 UID；再重新调用诊断"))
                addAll(defaultListenActions())
            }
        }
    }

    private fun defaultListenActions(): JsonArray = JsonArray().apply {
        add(ToolkitIo.json("action" to "confirm_listen_state", "tool" to "terminal_exec", "backend" to "app|shizuku",
            "command" to "grep -i ':69A2' /proc/net/tcp",
            "hint" to "state=0A 才是 LISTEN；换端口时把 69A2 换成端口十六进制", "priority" to 10))
        add(ToolkitIo.json("action" to "capture_syn_sent", "tool" to "terminal_exec", "backend" to "app|shizuku",
            "command" to "cat /proc/<client-pid>/net/tcp",
            "hint" to "复现连接卡住时找目标 127.0.0.1:69A2 且 state=02(SYN_SENT)，这是 SYN 被静默丢弃的决定性证据", "priority" to 11))
        add(ToolkitIo.json("action" to "compare_uid", "tool" to "terminal_exec", "backend" to "app|shizuku",
            "command" to "cat /proc/<target-pid>/status | grep -E '^(Name|Uid):'",
            "hint" to "对比目标进程与客户端/MCP 进程 UID，确认是否跨 UID", "priority" to 12))
    }

    private fun defaultEvidenceCommands(): JsonArray = JsonArray().apply {
        add("grep -i ':69A2' /proc/net/tcp")
        add("cat /proc/<target-pid>/net/tcp")
        add("cat /proc/<client-pid>/net/tcp")
        add("cat /proc/<target-pid>/status | grep -E '^(Name|Uid):'")
    }
}
