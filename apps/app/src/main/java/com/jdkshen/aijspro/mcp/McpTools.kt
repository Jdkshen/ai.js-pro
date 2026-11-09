package com.jdkshen.aijspro.mcp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.model.sample.SampleRepository
import com.jdkshen.aijspro.model.script.ScriptFile
import com.jdkshen.aijspro.model.script.ScriptRepository
import com.stardust.autojs.core.console.ConsoleImpl
import com.stardust.autojs.engine.JavaScriptEngine
import com.stardust.autojs.overlay.OverlayRegistry
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.autojs.script.ScriptSource
import com.stardust.autojs.script.StringScriptSource
import com.stardust.autojs.script.JavaScriptSource
import com.stardust.autojs.runtime.exception.ScriptInterruptedException
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Help supplement kept in code because the bundled guide asset is owned by the integration pass. */
internal object McpTerminalHelp {
    private const val MARKER = "shell_rish"
    private val SUPPLEMENT = """
        ## 终端后端补充（Shizuku）
        - terminal_exec 支持 backend=app（默认，普通应用 UID）或 backend=shizuku（需 Shizuku 已运行并授权；不会回退 app）。
        - shell_rish 是 shizuku 后端的异步兼容入口，立即返回 jobId；与 terminal_exec 共用任务表，用 terminal_read/terminal_write/terminal_stop 管理，不进行同步 600 秒等待。
        - app 后端仍为每条命令独立 setsid 进程组，并注入 rish/i 函数入口；RISH_APPLICATION_ID 固定为当前应用包名。shizuku 后端的 raw 命令不二次包装，停止时由 Shizuku UserService 销毁远端进程组。
        - terminal_read/terminal_list 返回的 backend 字段标明 app 或 shizuku；普通应用权限的任务默认仍为 app。
    """.trimIndent()

    fun merge(base: String): String =
        if (MARKER in base) base else base.trimEnd() + "\n\n" + SUPPLEMENT
}

internal class McpTools(private val context: Context, private val event: (String) -> Unit) {
    private val scriptsRoot = File(Pref.getScriptDirPath()).canonicalFile
    private val root = File(scriptsRoot, McpSettings.operationPath(context)).canonicalFile.also {
        if (it != scriptsRoot && !it.path.startsWith(scriptsRoot.path + File.separator)) error("操作目录无效")
    }
    private val toolkitStore = File(File(context.filesDir, "apk-toolkit"), ToolkitIo.version(root.path.toByteArray()).take(16))
    private val baselineVersion = McpBaselineVersion()
    /** §6.4：首个 tools/call（或 get_status）成功后就不再重复挂 baseline（省上下文）。 */
    private val entryBaselineDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var baselineCache: JsonObject? = null
    @Volatile private var baselineCacheAt = 0L
    @Volatile private var jobsSummaryCache: JsonObject? = null
    @Volatile private var jobsSummaryCacheAt = 0L
    /** task-38：工具链采样尝试时间（30s 内不重复探测）。 */
    @Volatile private var toolchainSampleAt = 0L
    /** baseline 缓存：失败/入口调用很频繁，15s 内复用同一份（内容变才变 version）。 */
    private val baselineTtlMillis = 15_000L
    private val workspaces = McpWorkspaceStore(context, root)
    private val terminalBackend = McpAndroidTerminal(context)
    private val terminals = McpTerminalJobs(root, terminalBackend::launch)
    private val runs = ConcurrentHashMap<String, RunRecord>()
    private val nextRun = AtomicInteger(1)
    private val cursors = LinkedHashMap<String, CursorPage>()
    private val apiDocs = McpApiDocs {
        context.assets.open("docs/api-reference-data.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private val reverseMarkdown by lazy { context.assets.open("mcp/AIjs-Reverse-Guide.md").bufferedReader(Charsets.UTF_8).use{it.readText()} }
    private val reverseWorkflows by lazy { context.assets.open("mcp/reverse-workflows.json").bufferedReader(Charsets.UTF_8).use{com.google.gson.JsonParser().parse(it).asJsonObject} }
    private val reverseGuide by lazy { ToolkitReverseGuide(reverseMarkdown,reverseWorkflows,McpToolCatalog.names.toSet()) }
    private val knowledge by lazy { ToolkitKnowledge(reverseMarkdown,reverseWorkflows) }

    private class RunRecord(val id: String, val path: String, val createdAt: Long) {
        @Volatile var status = "QUEUED"
        @Volatile var startedAt = 0L
        @Volatile var finishedAt = 0L
        @Volatile var engineExecutionId = -1
        @Volatile var result = ""
        @Volatile var engine = ""
        @Volatile var errorType = ""
        @Volatile var errorMessage = ""
        @Volatile var errorStack = ""
        @Volatile var logs: List<JsonObject>? = null
        @Volatile var execution: ScriptExecution? = null
        /** 用户主动 stop（用于区分“正常停止”与“真出错”）。 */
        @Volatile var stopRequested = false
        @Volatile var stopReason = ""
    }
    private data class CursorPage(val items: List<JsonObject>, var offset: Int, val createdAt: Long)

    fun handle(request: JsonObject): JsonObject? {
        val method = request.get("method")?.asString.orEmpty()
        if (!request.has("id")) return null
        val id = request.get("id")
        // Unknown methods remain -32601; parameter shape is only inspected for supported methods.
        if (!McpRpcValidation.isSupportedMethod(method)) return rpcError(id, -32601, "Method not found")
        var eventName = method
        return try {
            val params = request.get("params")
            val result = when (method) {
                "initialize" -> initialize(McpRpcValidation.optionalParams(params))
                "ping" -> {
                    McpRpcValidation.optionalParams(params)
                    JsonObject()
                }
                "tools/list" -> {
                    McpRpcValidation.optionalParams(params)
                    listTools(McpRpcValidation.optionalParams(params))
                }
                "tools/call" -> {
                    val invocation = McpRpcValidation.parseToolCall(params)
                    eventName = invocation.requestedName
                    // W4 task-21：主进程 / 工具桥 / 自动化三条路径都收敛到这里；给扁平结果统一补 data 镜像
                    // （含纯标量工具），>64KiB 不补并写 dataOmitted 说明原因；已是信封或已有 data 的不动。
                    // §6.4：首个 tools/call 成功也带一条 baseline（失败由 toolFailure/toolResult/bridge 三条路径带）。
                    withEntryBaseline(McpToolResultShapes.mirrorFlatResult(
                        applyScalarMirror(invocation.requestedName, call(invocation)),
                        // task-27（c）：用 canonical 名判定摘要名单（别名 captureScreen 之类也能命中）。
                        McpToolCatalog.canonicalName(invocation.requestedName),
                    ))
                }
                "resources/list"->knowledge.resourcesList(McpRpcValidation.optionalParams(params)?:JsonObject())
                "resources/templates/list"->knowledge.resourceTemplatesList(McpRpcValidation.optionalParams(params)?:JsonObject())
                "resources/read"->knowledge.resourcesRead(McpRpcValidation.optionalParams(params)?:JsonObject())
                "prompts/list"->knowledge.promptsList(McpRpcValidation.optionalParams(params)?:JsonObject())
                "prompts/get"->knowledge.promptsGet(McpRpcValidation.optionalParams(params)?:JsonObject())
                else -> error("unreachable supported method")
            }
            event("$eventName · ${if (result.get("isError")?.asBoolean == true) "失败" else "成功"}")
            rpcResult(id, result)
        } catch (e: McpInvalidParamsException) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            rpcError(id, -32602, e.message ?: "Invalid params")
        } catch (e: ToolError) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            if (method == "tools/call") rpcResult(id, toolFailure(withArgumentHint(eventName, e.message ?: "工具调用失败"), "TOOL_FAILED", details = argumentDetailsOrNull(eventName, e.message)))
            else rpcError(id, -32602, e.message ?: "Invalid params")
        } catch (e: ToolkitException) {
            // 必须先于 IllegalArgumentException 捕获（ToolkitException 是它的子类），否则 code 会被丢成纯文本。
            event("$eventName · 失败 · ${e.code} · ${e.message.orEmpty().take(100)}")
            if (method == "tools/call") rpcResult(id, toolFailure(withArgumentHint(eventName, e.message ?: "工具调用失败"), e.code, e.argument, argumentDetailsOrNull(eventName, e.message)))
            else rpcError(id, -32602, e.message ?: "Invalid params")
        } catch (e: IllegalArgumentException) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            if (method == "tools/call") rpcResult(id, toolFailure(withArgumentHint(eventName, e.message ?: "参数或工作区状态无效"), "INVALID_ARGUMENT", details = argumentDetailsOrNull(eventName, e.message)))
            else rpcError(id, -32602, e.message ?: "Invalid params")
        } catch (e: IllegalStateException) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            if (method == "tools/call") rpcResult(id, toolResult(e.message ?: "工作区状态冲突", true))
            else rpcError(id, -32602, e.message ?: "Invalid state")
        } catch (e: SecurityException) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            if (method == "tools/call") rpcResult(id, toolResult(e.message ?: "缺少手机权限", true))
            else rpcError(id, -32602, e.message ?: "Permission denied")
        } catch (e: IOException) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            if (method == "tools/call") rpcResult(id, toolResult(e.message ?: "文件操作失败", true))
            else rpcError(id, -32603, e.message ?: "File operation failed")
        } catch (e: Exception) {
            event("$eventName · 异常 · ${e.localizedMessage.orEmpty().take(100)}")
            rpcError(id, -32603, "Internal error")
        }
    }

    fun stopRuns() {
        terminals.close()
        runs.values.filter { it.status in ACTIVE_STATES }.forEach { record ->
            record.stopRequested = true; record.stopReason = "service_stopped"
            record.status = "STOPPING"
            record.logs = runLogs(record)
            runCatching { record.execution?.engine?.forceStop() }
        }
        OverlayRegistry.closeAll("mcp_service_stop")
    }

    fun toolNames(): List<String> = listTools().getAsJsonArray("tools")
        .map { it.asJsonObject.get("name").asString }

    private fun initialize(params: JsonObject?) = JsonObject().apply {
        val requested = params?.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
        addProperty("protocolVersion", if (requested in SUPPORTED_PROTOCOLS) requested else "2025-06-18")
        add("capabilities", JsonObject().apply { add("tools", JsonObject().apply { addProperty("listChanged", true) });add("resources",JsonObject());add("prompts",JsonObject()) })
        add("serverInfo", McpServerMetadata.toJson())

        addProperty("instructions", listOf(
            "AI.js Pro MCP（Rhino / QuickJS / Node24）。首次连接先调用 learn_autojs_skill 读取完整说明；参数以 tools/list 的 inputSchema 为准。",
            McpOutputConventions.INSTRUCTION,
            "逆向任务首次连接：reverse_guide({topic:index}) → reverse_capabilities({}) → reverse_plan({goal:inspect|decode|rebuild|native|dynamic|fingerprint|emulate|compare})。指南与步骤也可通过 resources/list/read、prompts/list/get 发现；URI aijs://reverse/guide、aijs://reverse/workflows。",
            "逆向组件按需安装，工具注册不代表已安装；apk_component_status查询后显式apk_component_install。后台返回jobId后apk_job_status到终态，再apk_job_result按nextOffset读完；失败保留error.code，不要把排队/启动当成功。免Root Frida先apk_inject_gadget生成重签APK，安装打开后用返回的endpoint/token连接；无需Root/Server。",
            "",
            "通用规则：",
            "- JSON 的布尔/数字用原生类型（true / 100，不要字符串 \"true\" / \"100\"）。",
            "- 写代码前先 search_api_docs(query,engine) 查接口，再 read_api_doc(id,indexSha256) 读参数/兼容范围/示例；与 App API 文档共用离线索引。传统 Rhino 参考不能当作 QuickJS/Node 的支持证明。",
            "- path 是相对操作目录的路径，不要写 /sdcard 绝对路径；先用 list_scripts 查看。",
            "- run_script 的 path/file/source 三选一；要结果和错误日志必须 wait:true。timeoutSeconds 是 MCP 等待上限（1..600 秒），不是脚本生命周期：path 脚本在 Node 宿主可运行最长 1 小时；等待超过 600 秒请 wait:false 后用 wait_execution/get_execution 继续。",
            "- wait 超时返回 timedOut=true，任务仍可能在跑：用 wait_execution/get_execution 继续，不要当成成功。",
            "- 脚本工程修改：workspace_open → workspace_read/write/delete → workspace_diff → workspace_request_apply。独立文件工具 file_* 通过 targetVersion 防旧写，直接作用于操作目录；APK 工具先建不可变快照/编辑会话，再导出新包。",
            "- 文件与APK工具全部由 AI.js Pro 自己实现，不需要 MT。先 toolkit_status；文件/ZIP用 file_*/zip_*，APK先 apk_list_available_apks → apk_workspace_open → apk_session_create。读取后用返回的 locator/targetVersion 修改，apk_session_check 后再 apk_build；有 nextCursor 用 apk_continue，修改后重查。签名读取是元数据，不是密码学验签。旧 mt_* 名称仅为本地兼容别名；多架构汇编按需安装终端 C/C++ 组件，能力与限制见 toolkit_status 和本指南。",
            "- locator 必须带命名空间前缀，缺前缀会被拒：zip_entry:（ZIP 条目）、dex_class:（类）、dex_method:（方法，原始 descriptor）、resource:（资源）。示例：zip_entry:assets/project/main.js、dex_method:LVShell;->open([BLjava/lang/String;Ljava/lang/String;)[B。跨 DEX 同名类用返回的 uniqueLocator（形如 dex_entry:classes.dex!dex_class:…）。apk_workspace_open 只接受 APK 结构（ZIP 魔数 + AndroidManifest.xml）；独立 DEX/SO/ELF/APKS 用 artifact_open（会建 syntheticContainer，artifactType=dex/elf）。",
            "- job 层错误码：JOB_NOT_FOUND（registry 无该记录）、JOB_EVICTED（结果因保留窗口被淘汰，清单见 apk_job_list.evicted[]，带 code/reason/recordedAt）；service.restarted + service.previousInstance 可直接判定服务是否换过实例，lostByRestart 表示结果因此丢失；tombstoneCount/retention{…} 说明保留策略；storage.metadataAndResultSeparated 表示输出缓存与元数据分开存放。errorCode/errorHint 只在基础设施级失败时填——操作级失败（如 Frida 连不上）时它们为 null，真原因在 reason 字段里，不要因为 errorCode=null 就当成功。",
            "- 以下返回失败是保护机制生效，不是工具坏了：file_edit_text/file_patch_bytes 的 targetVersion/expectedHex 不一致会直接拒绝写入；binary_transform 的 expectMagic/expectCrc32 不通过会回 verified=false（只说明证据不足，不等于算法或密钥被证伪）；apk_verify 对未签名产出回 verdict=invalid 是预期结果；file_access_diagnose 在无法区分“不存在/不可见”时回 UNCERTAIN 并给 uncertainty 文本，不要当成结论。",
            "- 读取控件/截图需无障碍服务；capture_screen 缩小后坐标按 sourceWidth/scale 换算。",
            "- 手机终端：terminal_exec（backend 默认 app，可选 shizuku）→ terminal_read(offset=nextOffset) → terminal_write/terminal_stop；shell_rish 是 Shizuku 后端的异步兼容入口，同样返回 jobId，不阻塞等待。app 为普通应用 UID，shizuku 需服务已运行并授权且不回退；两者共用同一任务表。先 get_build_tools 确认工具链，非 PTY。",
            "- 冷进程：换 .so / 做对照实验前先停止自己拥有的执行；全部停止需用户任务范围包含其他运行任务。脚本进程会复用，已 dlopen 的库和 isAvailable() 结果会粘住；不要在其他任务运行时擅自重启整个应用。",
            "- terminal 大输出：按 terminal_read(offset=上次 nextOffset, maxChars≤65536) 轮询到 running=false 且 nextOffset==outputEnd；二进制/特殊后缀文件用 base64 分块写入，workspace 只收部分文本类型。",
            "- 借目标 App 的解密/校验函数时优先选无状态自包含入口；长度/字节序等关键数字先实测再信旧文档；两次结果不同先怀疑进程复用与 native 全局状态。更多实战要点与坑表见 learn_autojs_skill 末尾「MCP 实战要点」。",
            "",
            "常用示例：",
            "run_script 内存脚本：{\"name\":\"run_script\",\"arguments\":{\"engine\":\"quickjs\",\"source\":\"console.log(1)\",\"wait\":true,\"timeoutSeconds\":30}}",
            "run_script 现有文件：{\"name\":\"run_script\",\"arguments\":{\"file\":\"main.js\",\"wait\":true,\"timeoutSeconds\":60}}",
            "查看文件：{\"name\":\"list_scripts\",\"arguments\":{}}；{\"name\":\"read_script\",\"arguments\":{\"path\":\"main.js\",\"maxBytes\":65536}}",
            "语法检查：{\"name\":\"check_syntax\",\"arguments\":{\"engine\":\"nodejs\",\"code\":\"console.log(1)\"}}",
            "",
            "重分析（native_analyze/native_functions/native_compare/yara_scan 等）占 30s 级后台槽位，连续调用前先 apk_job_list；frida_status/frida_processes 是只读快路径，不占槽。",
            "分页游标只对产生它的工具（同工具、同 revision）有效：拿 A 工具的 nextCursor 去调 apk_continue 会报错，请用原工具继续翻页。",
            "调用失败时先看错误指出的参数名/类型，再调用 learn_autojs_skill 查对应章节；不要猜字段。",
            "族前缀别名（长期共存、只派发）：dex_* = apk_dex_*（例 dex_xref = apk_dex_xref、dex_list_classes = apk_dex_list_classes）、so_* = apk_native_*（例 so_list_items = apk_native_list_items、so_xref = apk_native_xref）；新旧名等价都接受，别名不出现在 tools/list（省上下文），参数用 tool_schema{tool:\"dex_xref\"} 查。",
        ).joinToString("\n"))

        // 第七章（上下文成本）：把分组暴露给客户端，便于用 tools/list{groups:[...]} 或 {tier:"core"} 裁剪。
        add("toolCategories", JsonArray().apply {
            McpToolCategories.all.sortedBy { it.order }.forEach { category ->
                add(JsonObject().apply {
                    addProperty("id", category.id)
                    addProperty("title", category.title)
                    addProperty("summary", category.summary)
                    addProperty("count", McpToolCategories.toolNamesIn(category.id).size)
                })
            }
        })
        addProperty("toolFilterHint", "tools/list 支持 params.groups=[组id] 或 params.tier=core 裁剪本次返回；不带参数则返回全部工具。")

        // 始终暴露目录指纹（便于诊断宿主到底有没有刷新）；只有"相对上次 initialize 变了"才插提示行。
        val recordedFingerprint = catalogPrefs().getString("fingerprint", null)
        val catalogChange = toolCatalogChange()
        val catalogMeta = toolCatalogStatus(recordedFingerprint)
        catalogChange?.let { change ->
            catalogMeta.addProperty("changed", true)
            catalogMeta.addProperty("previous", change.get("previous").asString)
            catalogMeta.addProperty("current", change.get("current").asString)
        }
        if (catalogChange == null) catalogMeta.addProperty("changed", false)
        add("_meta", JsonObject().apply { add("aiJsProToolCatalog", catalogMeta) })
        catalogChange?.let { change ->
            // instructions 在结果里是**单个字符串**（多行用换行连接），不是数组。
            val instructions = get("instructions")
            val prefix = McpToolCatalogSignal.instructionLine(change)
            when {
                instructions == null -> addProperty("instructions", prefix)
                instructions.isJsonArray -> {
                    val merged = com.google.gson.JsonArray()
                    merged.add(com.google.gson.JsonPrimitive(prefix))
                    instructions.asJsonArray.forEach { merged.add(it) }
                    add("instructions", merged)
                }
                else -> addProperty("instructions", prefix + "\n" + instructions.asString)
            }
        }
    }

    private fun listTools(params: JsonObject? = null) = JsonObject().apply {
        add("tools", JsonArray().apply {
            add(tool("get_status", "读取 MCP、脚本目录、任务和工作区状态"))
            add(tool("get_baseline", "运行时就绪基线（自检，只读）：root/currentProject、workspaces{script,apk{used,quota,temporary,ttlHint}}、toolchain{ready,missing,present}、jobs{retention,advice}，有缺失时给一条最短 recovery。数字与 get_build_tools / apk_workspace_inventory / toolkit_status 同源（不另算一份），15s 内复用同一份缓存；内容不变时 version 不变（可去重）；总量 ≤512 字节，超了按「先丢统计、保 version/missing/recovery」裁剪并标注 trimmed。同一条 baseline 也会自动出现在首个 tools/call（或 get_status）与所有失败信封里；tools/list 与普通工具返回不带（省上下文）。refresh:true 强制重建。成功路径**以 structuredContent（ok/data）/结果顶层为准**；`content[0].text` **不保证包含** baseline（`get_status` 的扁平 text 会含，普通工具的文本镜像不含）；**失败路径三处同值**（顶层 / structuredContent / content[0].text）。", objSchema("refresh")))
            add(tool("list_scripts", "列出脚本目录中的文件；recursive=true 时递归子目录（与 search_scripts 同一数据源）；示例：{\"path\":\"\",\"recursive\":true}", objSchema("path", "offset", "limit", "recursive")))
            add(tool("read_script", "分块读取脚本目录中的文本文件；示例：{\"path\":\"main.js\",\"maxBytes\":65536}", objSchema("path", "offset", "maxBytes", required = arrayOf("path"))))
            add(tool("search_scripts", "递归搜索脚本文件名与内容；截断时返回 scanCursor（可传回本工具继续扫描）和分页 cursor（用于继续分页取出已返回的 items）", objSchema("query", "path", "limit", "scanCursor", "resumeCursor", "cursor", required = arrayOf("query")).apply {
                getAsJsonObject("properties").getAsJsonObject("scanCursor").addProperty("description", "上一次返回的 scanCursor；原样传回即可从已扫位置继续，query/path 必须一致；不要传分页 cursor。省略=从头扫描。")
                getAsJsonObject("properties").getAsJsonObject("resumeCursor").addProperty("description", "scanCursor 的别名；原样传回即可从已扫位置继续。")
                getAsJsonObject("properties").getAsJsonObject("cursor").addProperty("description", "scanCursor 的兼容别名（只接受 scan1- 开头的续扫游标；分页 cursor 请用 continue_result）。")
            }))
            add(tool("continue_result", "使用 cursor 继续获取搜索或大结果的下一页", objSchema("cursor", "limit", required = arrayOf("cursor"))))
            add(tool("list_samples", "列出内置示例", objSchema("path", "offset", "limit")))
            add(tool("read_sample", "分块读取内置示例", objSchema("path", "offset", "maxBytes", required = arrayOf("path"))))
            add(tool("list_executions", "列出本 MCP 发起的脚本任务状态"))
            add(tool("get_execution", "读取任务状态、结果、异常或运行日志", objSchema("executionId", required = arrayOf("executionId"))))
            add(tool("wait_execution", "等待任务结束（最长 timeoutSeconds 秒）并返回最终状态、结果、异常与运行日志；脚本报错时用这个拿错误数据；示例：{\"executionId\":\"mcp-1\",\"timeoutSeconds\":60}", objSchema("executionId", "timeoutSeconds", required = arrayOf("executionId"))))
            add(tool("read_apk_logs", "读取 AI.js Pro 全局控制台日志", objSchema("afterId", "limit")))
            add(tool("run_script", "运行现有文件或内存源码（path/file/source 三选一；source 传源码字符串）；需要结果/错误日志时 wait=true。timeoutSeconds 是 MCP 等待上限（1..600 秒，默认 60），不是脚本生命周期：path 脚本在 Node 宿主最长 1 小时；超过 600 秒请 wait=false 后轮询 wait_execution/get_execution。Node 执行池并发上限 3 个、最多排队 5 个/30 秒，超过会返回带「并发上限 3 个」的启动失败，请串行或错峰。示例：{\"engine\":\"quickjs\",\"source\":\"console.log(1)\",\"wait\":true,\"timeoutSeconds\":30}", McpToolSchema.runSchema()))
            add(tool("list_engine_api", "枚举三引擎实际全局或 require(module) 的 API；Node 同时返回实际内置模块目录。**每项还会给出 documented 对账**：原 items 保持不变，另附 documentedItems[{name,documented}] 与 documentedCount/undocumentedCount/build/note（文档是否提到该导出；文档落后于构建时这里会直接暴露）", objSchema("engine", "module")))
            add(tool("probe_engine_api", "按 descriptor/shape 探测三引擎 API 的 present/accessor/missing/unverified、成员和函数 arity；module 可选，includeInherited 默认 true；探针不执行 getter（call_engine_api 才真实求值与调用）", objSchema("engine", "module", "name", "includeInherited", required = arrayOf("engine", "name"))))
            add(tool("engine_api_diff", "对比三个引擎的全局 API；保留旧 Rhino/QuickJS 字段，增加 Node 与三引擎交集"))
            add(tool("stop_script", "停止本 MCP 发起的运行任务", objSchema("executionId", required = arrayOf("executionId"))))
            add(tool("workspace_open", "从真实脚本创建（或复用）私有工作区快照；create=true 为幂等：不存在则创建（自动建父目录）、已存在则直接打开；子目录文件请传完整相对路径；示例：{\"path\":\"main.js\",\"create\":true}", objSchema("path", "create", required = arrayOf("path"))))
            add(tool("workspace_list", "列出工作区及待确认状态"))
            add(tool("workspace_read", "读取工作区文件", objSchema("workspaceId", "path", "offset", "maxBytes", required = arrayOf("workspaceId", "path"))))
            add(tool("workspace_write", "修改工作区，不会直接覆盖真实脚本；示例：{\"workspaceId\":\"...\",\"path\":\"main.js\",\"content\":\"...\"}", objSchema("workspaceId", "path", "content", required = arrayOf("workspaceId", "path", "content"))))
            add(tool("workspace_delete", "在工作区中标记删除文件", objSchema("workspaceId", "path", required = arrayOf("workspaceId", "path"))))
            add(tool("workspace_diff", "查看工作区与创建时快照的 Diff。返回纯文本 unified diff（content[0].text）；机器可读同一份正文在 structuredContent.data.diff（另有 workspaceId）", objSchema("workspaceId", required = arrayOf("workspaceId"))))
            add(tool("workspace_request_apply", "直接应用工作区到真实脚本；校验原文件、自动备份并支持历史回退（工具名为兼容保留）；示例：{\"workspaceId\":\"...\"}", objSchema("workspaceId", required = arrayOf("workspaceId"))))
            add(tool("workspace_mkdir", "在真实脚本目录下创建目录（含多级）", objSchema("path", required = arrayOf("path"))))
            add(tool("workspace_cancel", "取消待确认的应用请求，把工作区恢复为可编辑状态", objSchema("workspaceId", required = arrayOf("workspaceId"))))
            add(tool("workspace_cleanup", "策略式清理工作区私有副本：默认 dryRun=true 只预览不动手；keepLatest 保留最近 N 个，olderThanHours 只清理更旧的，protectApplied 默认保护已应用（可在历史页回退）。只删除无修改的 OPEN 与已回退；applied/saved/被引用/未知 owner 一律保护并列出原因。旧参数 applied/keepAppliedHours 仍兼容。示例：{\"dryRun\":false,\"keepLatest\":20,\"olderThanHours\":24,\"protectApplied\":true}", objSchema("dryRun", "keepLatest", "olderThanHours", "protectApplied", "applied", "keepAppliedHours")))
            add(tool("get_ui_layout", "读取当前应用窗口或指定窗口的 XML、节点属性和空树诊断；需无障碍服务；示例：{\"maxNodes\":300,\"maxDepth\":64}", objSchema("windowId", "maxNodes", "maxDepth", "timeoutMillis")))
            add(tool("capture_screen", "截屏并返回 MCP 图像内容；mode 四种预设（MI8 1080×2160 实测）：fastest = 360 宽 + JPEG40（只够看有没有/大概在哪）；fast = 540 宽 + JPEG60，约 0.22~0.28s / 36KB（适合定位、轮询、先看一眼）；balanced = 原图 PNG，约 0.61~0.77s / 217KB（**与历史默认一致**）；quality = 原图 PNG + 更长超时，约 0.63~0.65s / 217KB（适合存档、像素级比对）。耗时主因是 PNG 编码（约 550ms），fast 靠缩小 + JPEG 把它省掉；显式传的 format/quality/maxWidth/timeoutMillis 覆盖预设；返回含 captureMs/encodeMs/totalMs/bytes/scale/effective，便于自己权衡质量与速度；region 可只截一块（[left,top,right,bottom] / \"[l,t][r,b]\" / {x,y,width,height}，越界自动夹取）——只截一块时**先裁后缩**，编码成本极低且保留 1:1 像素，比整屏缩小更清楚；缩小或裁剪后块内坐标 = 屏幕坐标 − 区域左上角，再乘 scale（返回里有 region/scale/regionNote）。**savePath（逐帧 CV 的关键）**：只要路径不要图像 —— 写入脚本目录内文件并只返回 {path,width,height,bytes,captureMs/encodeMs/writeMs/totalMs}，不回传 base64/JSON；saveFormat=raw 直接写 RGBA8888 原始像素（stride=width*4、无文件头，与 image.exportPixels 布局一致），省掉 PNG 编码（约 550ms）→ 适合逐帧循环；saveFormat 也可 auto（按扩展名）/png/jpg。需 Android 11+ 无障碍截图权限。示例：{\"mode\":\"fastest\"}、{\"savePath\":\"poker-recog/calib/live-6.png\",\"saveFormat\":\"raw\"}、{\"mode\":\"fast\"} / {\"mode\":\"quality\",\"maxWidth\":1080} / {\"mode\":\"fast\",\"region\":[0,1000,1080,1400]}", objSchema("mode", "format", "quality", "dataUri", "maxWidth", "timeoutMillis", "region", "savePath", "saveFormat")))
            add(tool("dev_screen_diff", "逐帧像素级差异：截当前帧（与 capture_screen 同一条管线，支持 region；captureMode 与 capture_screen 的 mode 是**同一套预设** fastest/fast/balanced/quality，默认 balanced=原图 PNG；maxDims=最大宽度，显式给了以你为准，省略时**沿用上一帧宽度**（这样 capture_screen{mode:\"fast\"} 之后再调本工具不会因 540 vs 720 而 SIZE_MISMATCH），都没有才用预设默认）并与基线比。baseline 可给脚本目录内的 PNG/JPEG 路径；不给则与进程内上一帧（本工具或 capture_screen 最近一帧）比 —— **第一次调用会明确失败 NO_BASELINE，但会把这一帧记为上一帧，所以用同样参数再调一次就能拿到 ratio**；不会拿黑图当基线。输出 changedPixels/ratio/bbox{x,y,w,h}/comparedPixels/frameSha256/baselineSha256/baselineSource/maxChannelDelta/tolerance；mode=mask 另生成掩码（变化=白/不变=黑），saveDiffTo 落盘 PNG（走既有 organize 归置），不传则内联返回。tolerance 是每通道灰度容差（基线是 JPEG 时默认 8，否则 0），用来吸收 JPEG 压缩噪声。诚实边界：这是像素级差异，不做语义/模板匹配（模板匹配用 matchTemplate 系列）；ratio=变化像素/比较像素，不等于界面变化程度；两次调用的 region/captureMode/maxDims 要一致，尺寸不一致直接 SIZE_MISMATCH（不裁剪、不缩放），等比时 nextActions 会给出可抄的 dev_screen_diff{maxDims:<基线宽度>} 与 suggestedArguments。示例：{\"mode\":\"count\"} 连续调两次看 ratio（第一次 NO_BASELINE 属正常，已记帧）；{\"baseline\":\"shot.png\",\"mode\":\"mask\",\"saveDiffTo\":\"diff/\"}；capture_screen{mode:\"fast\"} 之后直接 dev_screen_diff{mode:\"count\"} 会自动沿用 540 宽", objSchema("baseline", "region", "mode", "tolerance", "saveDiffTo", "maxDims", "captureMode")))
            add(tool("coordinate_convert", "截图/显示/浮窗三套坐标系的换算与标定：action=state 给出 panel（物理面板≈截图尺寸）、appDisplay（无障碍与浮窗同一套）、可选 measureScreenshot 实测截图尺寸，并给出仿射 dst=src*(scaleX,scaleY)+(offsetX,offsetY)；action=calibrate 传两组对应点（windowPoint1/screenshotPoint1[/2]）持久化标定值；action=convert 传 from/to/point|rect 换算；action=clear 清除标定。示例：{\"action\":\"state\",\"measureScreenshot\":true} 或 {\"action\":\"convert\",\"from\":\"window\",\"to\":\"screenshot\",\"rect\":[100,200,300,400]}", objSchema("action", "measureScreenshot", "windowPoint1", "screenshotPoint1", "windowPoint2", "screenshotPoint2", "from", "to", "point", "rect")))
            add(tool("check_syntax", "按 Rhino/QuickJS/Node.js 只编译检查源码，不执行任何脚本；Node 使用同版本真实编译器；示例：{\"engine\":\"nodejs\",\"code\":\"console.log(1)\"}", objSchema("code", "engine", "name", required = arrayOf("code"))))
            add(tool("learn_autojs_skill", "读取 AI.js Pro MCP 完整使用说明、三引擎规则、权限和实际支持范围；首次连接或调用报错前先调用一次。返回纯文本正文（content[0].text）；同一份正文也在 structuredContent.data.text"))
            add(tool("stop_all_scripts", "停止应用全部脚本（包括非 MCP 脚本）；普通任务应使用 stop_script"))
            add(tool("get_mcp_log", "读取脚本日志尾部或 MCP 服务请求记录；executionId 可限制为指定任务；maxChars 默认 20000，最大 200000", objSchema("maxChars", "executionId", "channel")))
            add(tool("list_engines", "列出三个引擎与 MCP 支持的运行、查询、调用及语法检查能力"))
            add(tool("call_engine_api", "在指定真实引擎调用全局或模块函数，支持 JSON args 和 Promise；回调、UI、Java 对象组合使用 run_script", McpToolSchema.callApiSchema()))
            add(tool("list_overlays", "列出各进程登记中的浮窗（pid/ownerEngineId/engine/kind/touchable/ageMs），用于诊断 stop 后是否有 View 残留"))
            add(tool("close_overlays", "关闭浮窗：指定 executionId 等同 stop_script；省略则等同 stop_all_scripts；返回剩余 overlayCount", objSchema("executionId")))
            add(tool("terminal_exec", "在手机终端底层异步运行 shell 命令，立即返回 jobId（同时在 structuredContent.data.jobId 给一份镜像；顶层 jobId 保留）；backend=app（默认，普通应用 UID，注入 rish/i 函数入口）或 shizuku（需 Shizuku 已运行并授权，以授权身份执行且不回退 app）。非 PTY，stdout/stderr 合并。workingDirectory 在操作目录内；命令本身可访问应用有权限的文件。env 为字符串对象，不能包含内部后端变量。timeoutSeconds 默认 300、最大 3600，到期终止进程组（shizuku 由远端 UserService 清理）；先 get_build_tools 查编译器。" + ToolkitBackendRouting.capabilitySummary() + "；用错后端时输出首行会写明「该命令需要 X 后端」（autoRoute=true 时仅对只读白名单自动切换并标注 routedFrom/routedTo）", McpToolSchema.terminalSchema()))
            add(tool("terminal_read", "读取终端状态/输出（structuredContent.data 同时镜像 jobId/offset/output 等字段，顶层字段保留）；offset=上次 nextOffset（UTF-16 字符偏移），maxChars 默认 16384/最大 65536；waitMillis=0..10000 长轮询。仅保留最近 256Ki 字符，truncated=true 表示旧输出已淘汰；processActive=false 才表示清理完成（shizuku 任务需远端进程组销毁）", objSchema("jobId", "offset", "maxChars", "waitMillis", required = arrayOf("jobId"))))
            add(tool("terminal_write", "向运行中的终端排队写入 stdin；input 不自动添加换行，eof=true 关闭输入。返回 accepted 仅表示入队，写入失败见 terminal_read.inputError", objSchema("jobId", "input", "eof", required = arrayOf("jobId"))))
            add(tool("terminal_stop", "停止指定终端任务及其同组编译子进程（structuredContent.data 镜像 jobId/status，顶层保留）；不会停止三个脚本引擎。shizuku 任务调用 ShizukuShell 的 process.destroy() 由 UserService 终止远端进程组；processActive 反映清理状态", objSchema("jobId", required = arrayOf("jobId"))))
            add(tool("terminal_list", "列出本 MCP 服务创建的终端任务（最多 3 个并发、保留 16 条，服务停止会终止任务；structuredContent.data.items 镜像顶层 items）；每项 backend 为 app 或 shizuku"))
            add(tool("shell_rish", "兼容工具：以 Shizuku 后端异步运行 shell 命令并立即返回 jobId（等价 terminal_exec backend=shizuku；structuredContent.data.jobId 镜像顶层 jobId）；需 Shizuku 已运行并授权，不回退应用 UID。与 terminal_exec 共用任务表，用 terminal_read/terminal_write/terminal_stop 管理，timeoutSeconds 默认 300、最大 3600，不做同步 600 秒等待", McpToolSchema.shellRishSchema()))
            add(tool("get_build_tools", "检测手机终端 PATH 的编译工具，返回可选 C/C++ 组件状态及终端安装命令、内置 D8/Smali 入口；此查询不会下载或运行编译器"))
            add(tool("app_list", "列出已安装应用（PackageManager，无 root）；query 过滤 label/包名，includeSystem 默认 true，limit 最大 200", McpToolSchema.appListSchema()))
            add(tool("app_info", "读取单个应用版本、SDK、安装路径与启动入口", McpToolSchema.appInfoSchema()))
            add(tool("app_launch", "按包名启动应用；activity 可选（.MainActivity 简写）", McpToolSchema.appLaunchSchema()))
            add(tool("app_open_settings", "打开系统设置首页，或指定 packageName 的应用详情页", McpToolSchema.appSettingsSchema()))
            add(tool("app_open_url", "用系统浏览器/应用打开 http(s) 链接", McpToolSchema.appUrlSchema()))
            add(tool("apk_install", "打开系统 APK 安装器（需用户确认，不是静默安装）；path 必须是可读 APK", McpToolSchema.apkInstallSchema()))
            add(tool("apk_uninstall", "打开系统卸载确认页（需用户确认，不是静默卸载）", McpToolSchema.apkUninstallSchema()))
            add(tool("ui_find", "按 text/resourceId/contentDesc/className/bounds 或坐标查找无障碍节点；返回 matches（含中心坐标）", McpToolSchema.uiFindSchema()))
            add(tool("ui_click", "点击匹配节点或坐标；节点 ACTION_CLICK 失败时尝试可点击祖先", McpToolSchema.uiClickSchema()))
            add(tool("ui_long_click", "长按匹配节点或坐标", McpToolSchema.uiLongClickSchema()))
            add(tool("ui_node_action", "对匹配节点执行通用 UiObject 动作（copy/cut/paste/select/collapse/expand/dismiss/show/scroll*/contextClick/focus/accessibilityFocus/setSelection/scrollTo/setProgress）；需无障碍服务", McpToolSchema.uiNodeActionSchema()))
            add(tool("ui_input", "向匹配的 editable 节点写入文本；text 为要写入的内容", McpToolSchema.uiInputSchema()))
            add(tool("ui_swipe", "在屏幕上滑动，坐标单位为屏幕像素", McpToolSchema.uiSwipeSchema()))
            add(tool("ui_global", "全局动作：back/home/recents/notifications", McpToolSchema.uiGlobalSchema()))
            add(tool("ui_wait", "等待匹配节点出现（阻塞轮询，上限 15s）", McpToolSchema.uiWaitSchema()))
            add(tool("ui_scroll", "按 up/down/left/right 执行滑动手势滚动", McpToolSchema.uiScrollSchema()))
            add(tool("search_api_docs", "搜索 App 同源离线 API 文档，不启动脚本；query 可省略以浏览目录，engine=all/rhino/quickjs/nodejs（兼容 node/node24），category 精确匹配返回的 categories，kind=all/guide/legacy。offset 默认 0，limit 默认 10/最大 20；返回 id、摘要、来源、引擎标注和 indexSha256；再用 read_api_doc 读说明与示例。文档不是运行验证", McpToolSchema.searchApiDocsSchema()))
            add(tool("read_api_doc", "读取文档 id 的 Markdown、sectionTitles/sections 目录及 examples 代码数组；section 可按目录只读参数/返回值/权限等，includeExamples 默认 true，完整页才返回原文代码和 language；分段时 examplesOmittedReason=paged_content。建议带 indexSha256 防旧 ID 错配；offset/nextOffset 为 UTF-16 字符，maxChars 默认16000、128..32000；不执行示例", McpToolSchema.readApiDocSchema()))
            add(tool("app_exit_info", "一次性读取目标应用的进程/日志证据并分类退出原因（java 异常/native 崩溃/主动 exit/SIGKILL/系统回收/启动失败/无法判断）；不建立会话，不改系统设置", objSchema("packageName", "pid", "withLogs", required = arrayOf("packageName"))))
            add(tool("app_trace_start", "建立应用启动/日志/退出 trace 会话（TTL 10 分钟，最多 4 个）；普通应用 UID 只能看到有限日志，证据不足会返回 unknown", objSchema("packageName", "reason", "withLogs", required = arrayOf("packageName"))))
            add(tool("app_trace_read", "分页读取 trace 日志与退出分类；会话过期/不存在时明确报错", objSchema("traceSessionId", "offset", "limit", required = arrayOf("traceSessionId"))))
            add(tool("app_trace_stop", "停止 trace 会话并返回真实清理计数", objSchema("traceSessionId", required = arrayOf("traceSessionId"))))
            add(tool("app_trace_list", "列出未过期的 trace 会话及其上限/TTL"))
            add(tool("app_launch_trace", "先建立 trace 再启动应用；启动失败也保留 traceSessionId 便于诊断", McpToolSchema.appLaunchSchema()))
            add(tool("floating_menu", "应用自身悬浮球（悬浮窗）的状态/显示/关闭：action=state|show|close。close 走 FloatyWindowManger，主线程执行并回读真实状态，用于「悬浮球关不掉」时可靠关闭", objSchema("action")))
            add(tool("shell_capabilities", "列出手机 shell 的实测能力清单与替代写法（grep 无 \\b/\\s、cat 无 -n/-A、无 zip/jar/7z 只有 tar、python 链接可能坏、node 可用），并指向 MCP 自身的替代工具", objSchema()))
            add(tool("shell_classify_failure", "把 shell 失败输出分类成能力缺口/后端问题/文件不存在/未知，并给出替代写法与所需后端；判断保守，未知不猜", objSchema("command", "output", required = arrayOf("command", "output"))))
            add(tool("node_script_command", "给出「写脚本文件再运行」的推荐命令（避免 node -e 双重转义），并说明 argv[0]=node、argv[1]=脚本、argv[2] 才是参数", objSchema("scriptPath", "args", required = arrayOf("scriptPath"))))
            add(tool("crash_buffer_semantics", "解释 logcat -b crash -c 的清理语义：只清 crash 环形缓冲、不是同步屏障、旧条目可能仍被读到；给出可核对的时间窗/PID 过滤方法", objSchema()))
            McpToolkitBridge.definitions(context, root).forEach { add(it) }
            // P1-6/P1-7：终端描述把「权限不足」与「需要换后端」分开，并说明 app 后端看不到 shell 进程是正常现象。
            val terminalDescriptionSuffixes = mapOf(
                "terminal_exec" to "；后端提示是路由建议，不等于权限结论：app 后端出现「权限不足」（如 pm/am/logcat）才考虑 backend=shizuku；普通构建脚本 sh build.sh 用 app 后端，正确调用：{\"backend\":\"app\",\"command\":\"sh build.sh\"}。若报缺少已知编译组件（python3/aidl/java/javac/keytool/aapt2/d8/zipalign/apksigner…），返回顶层 code=COMPONENT_MISSING，并直接附上 get_build_tools 的 ready/missing/present 与逐组件 found（复用缓存，不新增探测）；恢复见 recovery。app 后端看不到 shell/其他进程（pidof shizuku_server 空、ps 受限）是正常现象；查进程请用 backend=shizuku，不要据此判断服务挂了。",
                "terminal_read" to "；若输出命中已知编译组件缺失，顶层返回 code=COMPONENT_MISSING + get_build_tools 的 ready/missing/present/componentState；未命中不追加失败语义。",
                "terminal_list" to "；app 后端看不到 shell/其他进程（pidof/ps 受限）是正常现象；查进程请用 terminal_exec backend=shizuku。",
                "get_build_tools" to "；返回 ready/missing/present 就绪摘要；缺组件时 code=COMPONENT_MISSING，recovery 给 apk_component_install/localDeps 两条可执行路；jdk17/aapt2/apksigner 缺失时 appPrivateToolchain 提示它们属于 app 私有/APK 组件，先 apk_component_status，不要只看 PATH。",
                "shell_classify_failure" to "；输出同时含已知编译组件名与 inaccessible/command not found/No such file 等失败短语时，顶层 code=COMPONENT_MISSING，并附 get_build_tools 的 ready/missing/present/componentState/recovery；只当已知组件命中才判，普通文件缺失不误报。",
            )
            forEach { element ->
                val tool = element.asJsonObject
                terminalDescriptionSuffixes[tool.get("name")?.asString]?.let { suffix ->
                    tool.addProperty("description", tool.get("description").asString + suffix)
                }
            }
            // 所有会写文件的工具统一追加同一句产出目录 pointer（只改文案，不改逻辑）。
            forEach { element ->
                val tool = element.asJsonObject
                if (tool.get("name")?.asString in McpOutputConventions.TOOLS_WITH_ORGANIZE) {
                    tool.addProperty("description", McpOutputConventions.appendPointer(tool.get("description").asString))
                }
            }
            // `.4` P0-1：参数 schema 自描述入口（inputSchema 太大不便预读时，用它单查一个工具）。
            add(tool("tool_schema", "查看某个工具的完整参数表：描述、每个参数的类型/说明/默认值、必填项与别名。" +
                "工具名写错时返回最接近的名字；等价于从 tools/list 里取该工具的 inputSchema",
                objSchema("tool", required = arrayOf("tool"))))
            McpToolCatalog.aliases.forEach { (alias, canonical) ->
                val original = first { it.asJsonObject.get("name").asString == canonical }.asJsonObject
                add(original.deepCopy().apply {
                    addProperty("name", alias)
                    addProperty("description", "高级版兼容名称：" + original.get("description").asString)
                })
            }
            check(map { it.asJsonObject.get("name").asString } == McpToolCatalog.names) {
                "MCP 工具定义与公开目录不一致"
            }
        })
        add("serverInfo", McpServerMetadata.toJson())
        // 第七章：允许客户端按组裁剪本次 tools/list（token 紧张时只拉需要的组）。
        applyToolFilter(params)
    }

    /**
     * `tools/list` 的可选裁剪：`params.groups`（组 id 数组或逗号分隔字符串）与 `params.tier=core`。
     * 只影响本次返回，服务端不记忆；被裁掉的工具仍可 `tools/call`（但请按需重新 list）。
     */
    private fun JsonObject.applyToolFilter(params: JsonObject?) {
        val tools = getAsJsonArray("tools") ?: return
        val total = tools.size()
        val groups = LinkedHashSet<String>()
        val raw = params?.get("groups")
        when {
            raw == null -> {}
            raw.isJsonArray -> raw.asJsonArray.forEach { if (it.isJsonPrimitive) it.asString.trim().takeIf { id -> id.isNotEmpty() }?.let(groups::add) }
            raw.isJsonPrimitive -> raw.asString.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(groups::add)
        }
        val tier = params?.get("tier")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()
        val valid = McpToolCategories.groupIds().toSet()
        val unknown = groups.filter { it !in valid }
        val keep: (String) -> Boolean = when {
            tier == "core" -> { name -> name in McpToolCategories.coreToolNames }
            groups.isNotEmpty() -> { name -> McpToolCategories.categoryFor(name).id in groups }
            else -> { _ -> true }
        }
        if (tier != "core" && groups.isEmpty()) {
            add("toolFilter", JsonObject().apply {
                addProperty("applied", false)
                addProperty("total", total)
                addProperty("returned", total)
                add("availableGroups", JsonArray().apply { McpToolCategories.groupIds().forEach { add(it) } })
                addProperty("note", "可用 params.groups=[组id] 或 params.tier=core 裁剪本次 tools/list（只影响本次返回）。")
            })
            return
        }
        val kept = JsonArray()
        tools.forEach { element ->
            val name = element.asJsonObject.get("name")?.asString ?: return@forEach
            if (keep(name)) kept.add(element)
        }
        add("tools", kept)
        add("toolFilter", JsonObject().apply {
            addProperty("applied", true)
            if (tier != null) addProperty("tier", tier)
            add("groups", JsonArray().apply { groups.forEach { add(it) } })
            add("unknownGroups", JsonArray().apply { unknown.forEach { add(it) } })
            add("availableGroups", JsonArray().apply { McpToolCategories.groupIds().forEach { add(it) } })
            addProperty("total", total)
            addProperty("returned", kept.size())
            addProperty("note", "过滤只影响本次 tools/list；被裁掉的工具仍可 tools/call（按需重新 list 即可）。")
        })
    }

    /** 工具目录指纹 = 工具数 + build（用于识别"升级后宿主未刷新工具表"）。 */
    private fun toolCatalogFingerprint(): String = McpToolCatalogSignal.fingerprint(
        McpToolCatalog.names.size, com.jdkshen.aijspro.BuildConfig.VERSION_CODE,
        com.jdkshen.aijspro.BuildConfig.VERSION_NAME)

    private fun catalogPrefs() = context.getSharedPreferences("mcp-tool-catalog", Context.MODE_PRIVATE)

    /**
     * 只读状态（供 get_status / initialize 暴露）。
     *
     * @param recordedFingerprint 本次 initialize **之前**记录的值（get_status 传 null 表示只报当前值）。
     */
    private fun toolCatalogStatus(recordedFingerprint: String? = null): JsonObject = JsonObject().apply {
        val current = toolCatalogFingerprint()
        addProperty("toolCount", McpToolCatalog.names.size)
        addProperty("build", com.jdkshen.aijspro.BuildConfig.VERSION_NAME)
        addProperty("fingerprint", current)
        if (recordedFingerprint != null) {
            addProperty("recordedFingerprintBeforeInitialize", recordedFingerprint)
            addProperty("changedSinceRecorded", recordedFingerprint != current)
        }
    }

    /** initialize 时比对并记录；目录变化时返回提示对象（客户端应重新 tools/list）。 */
    private fun toolCatalogChange(): JsonObject? {
        val current = toolCatalogFingerprint()
        val prefs = catalogPrefs()
        val previous = prefs.getString("fingerprint", null)
        prefs.edit().putString("fingerprint", current).apply()
        return McpToolCatalogSignal.notice(previous, current, McpToolCatalog.names.size,
            com.jdkshen.aijspro.BuildConfig.VERSION_NAME)
    }

    private fun call(invocation: McpToolCall): JsonObject {
        val name = invocation.canonicalName
        val args = invocation.arguments
        if(name=="reverse_guide")return toolJson(ToolkitIo.ok(reverseGuide.read(args)))
        if(name=="reverse_plan")return toolJson(ToolkitIo.ok(reverseGuide.plan(args)))
        if (name in McpToolkitCatalog.names) {
            val result = McpToolkitBridge.call(context, root, name, args)
            // `.4` P0-1：桥接（:tools 进程）里的参数类报错不经过外层 catch，这里补同一份提示。
            enrichBridgedFailure(name, result)
            return toolJson(result).apply {
                if (result.get("ok")?.asBoolean == false) {
                    addProperty("isError", true)
                    // task-38（①）：工具桥（:tools 进程）的失败也带**失败专用裁剪版** baseline。
                    add("baseline", failureBaselineView())
                    // `.4` 终检 minor：错误码要能不解析正文就取到；桥接结果的 code 在 payload 顶层或 error.code。
                    val code = result.get("errorCode") ?: result.getAsJsonObject("error")?.get("code")
                    code?.takeIf { it.isJsonPrimitive }?.asString?.let { addProperty("errorCode", it) }
                    result.getAsJsonObject("error")?.get("argument")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.let { addProperty("argument", it) }
                    // 参数明细也镜像到结果顶层（主进程路径本来就放在顶层；冒烟 arg.details 两边都断言）。
                    (result.getAsJsonObject("error")?.getAsJsonObject("details") ?: result.getAsJsonObject("details"))
                        ?.let { add("details", it) }
                }
            }
        }
        if (McpAutomationTransport.supports(name)) return McpAutomationTransport.call(context, name, args)
        return when (name) {
            "tool_schema" -> toolJson(toolSchema(args))
            "get_baseline" -> toolJson(baselineSnapshot(force = args.booleanOr("refresh", false)))
            "get_status" -> toolJson(JsonObject().apply {
                // P1-9：直接回答「这是哪个 build、有多少工具、更新了什么」，不用再去 app_info 绕。
                add("toolCatalog", toolCatalogStatus())
                // `.4` P1-G：把 MCP 服务自身状态也报出来（监听地址/端口/心跳/最后错误/恢复建议）。
                // W4 task-10 A/B：把进程内监听侧健康（accept 循环是否还活着 / 失败次数 / 最近失败）带进状态，
                // 顶层 running/listening 由它决定，避免「服务实例在、accept 循环已死」被报成健康。
                val listenerHealth = com.jdkshen.aijspro.mcp.McpService.listenerHealth()
                add("mcp", com.jdkshen.aijspro.mcp.McpStateStore.statusJson(
                    context,
                    com.jdkshen.aijspro.mcp.McpService.autoStarted,
                    com.jdkshen.aijspro.mcp.McpService.running,
                    com.jdkshen.aijspro.mcp.McpService.listeningPort,
                    listenerHealth?.alive ?: false,
                    listenerHealth?.failureCount ?: 0,
                    listenerHealth?.lastFailure))
                // `.4` 的坑：打包会优先用 /sdcard/template.apk 或宿主缓存，可能仍是旧引擎。
                // 这里只读摘要：最终会用哪份模板、它的 sha256、以及是否含 dev-1401 的 require 修复标记。
                runCatching {
                    // templateSummary 返回 org.json.JSONObject（打包侧用），这里转成 Gson JsonElement。
                    val templateJson = com.jdkshen.aijspro.build.ApkBuilderPluginHelper
                        .templateSummary(context, false).toString()
                    add("template", com.google.gson.JsonParser().parse(templateJson))
                }
                val packageInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                    else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
                }.getOrNull()
                add("app", JsonObject().apply {
                    addProperty("packageName", context.packageName)
                    addProperty("versionName", packageInfo?.versionName ?: com.jdkshen.aijspro.BuildConfig.VERSION_NAME)
                    addProperty("build", packageInfo?.versionName ?: com.jdkshen.aijspro.BuildConfig.VERSION_NAME)
                    val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo?.longVersionCode else packageInfo?.versionCode?.toLong()
                    addProperty("versionCode", versionCode ?: com.jdkshen.aijspro.BuildConfig.VERSION_CODE.toLong())
                    addProperty("lastUpdateTime", packageInfo?.lastUpdateTime ?: 0L)
                })
                add("limits", McpLimits.describe())
                addProperty("toolCount", McpToolCatalog.names.size)
                val outputConventions = McpOutputConventions.describeFor(root, toolkitStore)
                add("outputConventions", outputConventions)
                addProperty("canonicalToolCount", McpToolCatalog.canonicalNames.size)
                addProperty("scriptRoot", root.path); addProperty("writeAllowed", McpService.allowWrite)
                addProperty("executionAllowed", McpService.allowExecution)
                addProperty("activeMcpRuns", runs.values.count { it.status in ACTIVE_STATES })
                addProperty("retainedExecutions", runs.size); addProperty("workspaces", workspaces.list().size)
                addProperty("pendingWorkspaceApprovals", workspaces.list().count { it.pendingApproval })
                addProperty("activeTerminalJobs", terminals.list().count { it.get("processActive").asBoolean })
                // jobs 的保留窗口/淘汰在地 3 进程（:tools）里，主进程 get_status 通过 toolkit_status 取摘要。
                val jobsSummary = runCatching {
                    McpToolkitBridge.call(context, root, "toolkit_status", JsonObject())
                        .getAsJsonObject("data")?.getAsJsonObject("jobs")
                }.getOrNull()
                if (jobsSummary != null) add("jobs", jobsSummary) else add("jobs", JsonObject().apply {
                    addProperty("unavailable", true)
                    addProperty("note", "工具进程未就绪，无法读取任务保留统计；请稍后重试 get_status 或先调用 toolkit_status")
                })
                // 第六章 §6.4 基线自检：与 get_baseline 同一份实现（同源、15s 缓存、≤512 字节预算）。
                entryBaselineDelivered.set(true)
                add("baseline", baselineSnapshot(jobsSummary))
                addProperty("overlayCount", OverlayStateReader.countAggregated(context))
                addProperty("overlayState", if (OverlayStateReader.isAvailable(context)) "ok" else "unavailable")
                add("engineDiagnostics", engineDiagnostics())
                add("rootStability", McpRootStability.observe(root.path))
                add("toolCategories", JsonObject().apply { McpToolCategories.counts().forEach { (id, count) -> addProperty(id, count) } })
                // P2-10：工作区残留到 193 个时应该主动给可执行的清理建议，而不是只报数字。
                val workspaceCount = workspaces.list().size
                val pendingApprovals = workspaces.list().count { it.pendingApproval }
                if (workspaceCount > WORKSPACE_ALERT_THRESHOLD || pendingApprovals > 0) {
                    add("nextActions", JsonArray().apply {
                        add("workspace_cleanup{\"dryRun\":true,\"keepLatest\":20,\"olderThanHours\":24,\"protectApplied\":true}")
                        if (pendingApprovals > 0) add("有 $pendingApprovals 个工作区等待确认：workspace_list 查看后在 App 内确认或回退")
                        add("APK 工作区另可用 apk_workspace_inventory / apk_workspace_gc 单独回收")
                    })
                }
            })
            "list_scripts" -> toolJson(listFiles(resolveScript(args.stringOr("path", "")), args.intOr("offset", 0), args.intOr("limit", 100), args.booleanOr("recursive", false)))
            "read_script" -> structuredCall { toolOk(readBytes(readTextFile(resolveScript(args.string("path"))), args.intOr("offset", 0), args.intOr("maxBytes", 65536))) }
            "search_scripts" -> toolJson(searchScripts(args.string("query"), args.stringOr("path", ""), args.intOr("limit", 50),
                args.stringOr("scanCursor", args.stringOr("resumeCursor", args.stringOr("cursor", "")))))
            "continue_result" -> toolJson(continueResult(args.string("cursor"), args.intOr("limit", 50)))
            "list_samples" -> toolJson(listAssets(args.stringOr("path", "sample"), args.intOr("offset", 0), args.intOr("limit", 100)))
            "read_sample" -> toolJson(readAsset(args.string("path"), args.intOr("offset", 0), args.intOr("maxBytes", 65536)))
            "search_api_docs" -> toolJson(apiDocs.search(args))
            "read_api_doc" -> toolJson(apiDocs.read(args))
            "list_executions" -> toolJson(JsonObject().apply { add("items", JsonArray().apply {
                runs.values.sortedByDescending { it.createdAt }.take(100).forEach { add(runJson(it)) }
            }) })
            "get_execution" -> toolJson(runJson(runs[args.string("executionId")] ?: throw ToolError("找不到此 MCP 任务"), true))
            "wait_execution" -> {
                val record = runs[args.string("executionId")] ?: throw ToolError("找不到此 MCP 任务")
                toolJson(waitFor(record, args.intOr("timeoutSeconds", 60)))
            }
            "read_apk_logs" -> toolJson(readApkLogs(args.intOr("afterId", -1), args.intOr("limit", 100)))
            "run_script" -> runScript(args)
            "list_engines" -> toolJson(JsonObject().apply { add("items", JsonArray().apply {
                McpEngines.names.forEach { engine -> add(JsonObject().apply {
                    addProperty("engine", engine)
                    addProperty("available", engine != "rhino" || com.jdkshen.aijspro.BuildConfig.RHINO_COMPAT)
                    addProperty("run", true); addProperty("apiQuery", true); addProperty("apiCall", true); addProperty("syntaxCheck", true)
                    addProperty("probeEvidence", "shape")
                    addProperty("behaviorStatus", "unverified")
                }) }
            }) })
            "call_engine_api" -> callEngineApi(args)
            "list_overlays" -> listOverlays()
            "close_overlays" -> closeOverlays(args)
            "list_engine_api" -> listEngineApi(args)
            "probe_engine_api" -> probeEngineApi(args)
            "engine_api_diff" -> engineApiDiff()
            "stop_script" -> stopScript(args)
            "coordinate_convert" -> toolJson(McpCoordinateTools.call(context, args)).apply {
                val envelope = getAsJsonObject("structuredContent")
                if (envelope?.get("ok")?.asBoolean == false) {
                    addProperty("isError", true)
                    envelope.getAsJsonObject("error")?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.let { addProperty("errorCode", it) }
                }
            }
            "check_syntax" -> {
                val code = args.string("code")
                val engine = McpEngines.normalize(args.stringOr("engine", "")).ifEmpty {
                    McpEngines.actual(StringScriptSource("mcp-syntax", code))
                }
                requireRhinoCompat(engine)
                val name = args.stringOr("name", "mcp-syntax.js")
                toolJson(if (engine == "nodejs") McpNodeSyntax.check(context, code, name)
                    else McpSyntaxChecker.check(code, engine, name))
            }
            "learn_autojs_skill" -> structuredCall { McpToolResultShapes.textResult("text", helpText()) }
            "stop_all_scripts" -> stopAllScripts()
            "get_mcp_log" -> toolJson(getMcpLog(args))
            "terminal_exec" -> {
                requireExecution()
                terminalJson(McpBuildTools.enrichTerminalResult(terminals.execute(args.string("command"), args.stringOr("workingDirectory", ""),
                    terminalEnvironment(args), terminalInt(args, "timeoutSeconds", 300), parseTerminalBackend(args))))
            }
            "terminal_read" -> terminalJson(McpBuildTools.enrichTerminalResult(terminals.read(args.string("jobId"), terminalLong(args, "offset", 0),
                terminalInt(args, "maxChars", 16384), terminalInt(args, "waitMillis", 0))))
            "terminal_write" -> {
                val eof = args.get("eof")?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "eof 必须是布尔值" }
                    it.asBoolean
                } ?: false
                structuredCall { toolOk(terminals.write(args.string("jobId"), if (args.has("input")) args.string("input") else "", eof)) }
            }
            "terminal_stop" -> terminalJson(terminals.stop(args.string("jobId")))
            // 反馈 #8：terminal_* 之前没有 job 层错误码/保留/所有权语义。
            // listReport() 带 retention / evicted[] / tombstoneCount / ownershipEnforced / service（旧 list() 仍在，供 get_status 计数用）。
            "terminal_list" -> terminalJson(terminals.listReport())
            "shell_rish" -> {
                requireExecution()
                require(!args.has("backend")) { "shell_rish 固定使用 shizuku 后端；如需 app 后端请用 terminal_exec" }
                // shell_rish is only a compatibility name for the same asynchronous Shizuku job
                // system. It must never block the MCP request or pretend to be a sync result.
                terminalJson(McpBuildTools.enrichTerminalResult(terminals.execute(args.string("command"), args.stringOr("workingDirectory", ""),
                    terminalEnvironment(args), terminalInt(args, "timeoutSeconds", 300),
                    McpTerminalJobs.BACKEND_SHIZUKU)))
            }
            "get_build_tools" -> toolJson(McpBuildTools.inspect(terminalBackend, root))
            "workspace_open" -> structuredCall { toolOk(workspaceJson(workspaces.open(args.string("path"), args.booleanOr("create", false)))) }
            "workspace_list" -> structuredCall { toolOk(JsonObject().apply { add("items", JsonArray().apply { workspaces.list().forEach { add(workspaceJson(it)) } }) }) }
            "workspace_read" -> structuredCall { toolOk(readBytes(workspaces.read(args.string("workspaceId"), args.string("path")), args.intOr("offset", 0), args.intOr("maxBytes", 65536))) }
            "workspace_write" -> structuredCall {
                requireWrite(); val bytes = args.string("content").toByteArray(Charsets.UTF_8)
                toolOk(workspaceJson(workspaces.write(args.string("workspaceId"), args.string("path"), bytes)).apply {
                    addProperty("needsApply", true)
                    addProperty("hint", "只写进了私有工作区；真实脚本需要调用 workspace_request_apply 才会更新（目录与文件在 apply 时一起创建）")
                })
            }
            "workspace_delete" -> structuredCall { requireWrite(); toolOk(workspaceJson(workspaces.delete(args.string("workspaceId"), args.string("path"))).apply {
                addProperty("needsApply", true)
                addProperty("hint", "删除只在私有工作区生效；调用 workspace_request_apply 后才会删真实文件")
            }) }
            "workspace_diff" -> structuredCall {
                val workspaceId = args.string("workspaceId")
                val diff = workspaces.diff(workspaceId)
                McpToolResultShapes.success(
                    ToolkitIo.json("workspaceId" to workspaceId, "diff" to diff),
                    legacyText = diff,
                )
            }
            "workspace_request_apply" -> structuredCall {
                requireWrite()
                val applied = workspaces.applyAuthorized(args.string("workspaceId"))
                toolOk(workspaceJson(applied).apply {
                    addProperty("message", "已应用到真实脚本；修改前已校验并备份，可在手机历史页回退")
                })
            }
            "workspace_mkdir" -> structuredCall {
                requireWrite()
                val created = workspaces.makeDirectory(args.string("path"))
                toolOk(JsonObject().apply { addProperty("path", created); addProperty("created", true) })
            }
            "workspace_cancel" -> structuredCall { toolOk(workspaceJson(workspaces.cancel(args.string("workspaceId")))) }
            "workspace_cleanup" -> structuredCall { toolOk(workspaces.cleanupPolicy(
                dryRun = args.booleanOr("dryRun", true),
                keepLatest = args.intOr("keepLatest", McpWorkspaceStore.DEFAULT_CLEANUP_KEEP_LATEST),
                olderThanHours = args.intOr("olderThanHours", 0),
                // 旧参数 applied=true 仍表示“允许清已应用”，新参数 protectApplied 显式优先。
                protectApplied = if (args.has("protectApplied")) args.booleanOr("protectApplied", true)
                    else !args.booleanOr("applied", false),
                keepAppliedHours = args.intOr("keepAppliedHours", 24),
            )) }
            "app_list", "app_info", "app_launch", "app_open_settings", "app_open_url", "apk_install", "apk_uninstall" ->
                toolJson(McpAppTools.handle(context, name, args) ?: throw McpInvalidParamsException("未知工具：$name"))
            in McpAppTraceTools.names -> toolJson(McpAppTraceTools.call(context, name, args))
            "shell_capabilities" -> toolJson(ToolkitShellCapabilities.matrix())
            // 辅助工具不能让参数形状问题变成 MCP「Internal error」：一律捕获并返回结构化错误。
            "shell_classify_failure" -> {
                val classified = runCatching {
                    ToolkitShellCapabilities.classifyFailure(args.string("command"), args.string("output"))
                }.getOrElse { failure -> helperError("shell_classify_failure", failure) }
                toolJson(McpBuildTools.enrichComponentFailure(classified, args.string("command"), args.string("output")))
            }
            "node_script_command" -> toolJson(runCatching {
                // 库层已容忍「字符串 / 数组 / null」三种 args 形状（实测曾因字符串 args 报 Internal error）。
                ToolkitShellCapabilities.nodeScriptCommandFromRaw(args.string("scriptPath"), args.get("args"))
            }.getOrElse { failure -> helperError("node_script_command", failure) })
            "floating_menu" -> toolJson(McpFloatingTools.call(context, name, args))
            "crash_buffer_semantics" -> toolJson(runCatching {
                ToolkitShellCapabilities.crashBufferSemantics()
            }.getOrElse { failure -> helperError("crash_buffer_semantics", failure) })
            else -> McpAppTools.handle(context, name, args)?.let { toolJson(it) }
                ?: throw McpInvalidParamsException("未知工具：$name")
        }
    }

    private fun runScript(args: JsonObject): JsonObject {
        requireExecution()
        val input = McpRunInput(args)
        requireRhinoCompat(input.engine)
        val file = input.path?.let { path -> resolveScript(path).also {
            if (!it.isFile || !it.name.endsWith(".js", true)) throw ToolError("只能运行存在的 .js 文件")
        } }
        val workingDirectory = input.workingDirectory?.let { resolveScript(it) }
            ?: file?.parentFile ?: root
        require(workingDirectory.isDirectory) { "工作目录不存在" }
        val config = ExecutionConfig(workingDirectory = workingDirectory.path, delay = input.delay.toLong(),
            interval = input.interval.toLong(), loopTimes = input.loopTimes,
            path = input.searchPaths().map { resolveScript(it).also { dir ->
                require(dir.isDirectory) { "config.path 目录不存在" }
            }.path }.toTypedArray())
        @Suppress("UNCHECKED_CAST")
        val arguments = config.arguments as MutableMap<String, Any?>
        input.config.getAsJsonObject("arguments")?.entrySet()?.forEach {
            arguments[it.key] = McpRunInput.argumentValue(it.value)
        }
        if (input.source != null) {
            val name = input.name.removeSuffix(".js")
            val body = if (input.engine.isEmpty()) input.source else
                McpEngines.directive(input.engine) + "\n" + input.source.removePrefix("\uFEFF")
            val source = StringScriptSource(File(workingDirectory, name).path, body)
            return executeScript("$name.js (source)", source, workingDirectory.path,
                input.wait, input.timeoutSeconds, config)
        }
        requireNotNull(file)
        // engine=quickjs/rhino：不改原文件，临时副本保留到脚本实际结束（等待超时不会删）。
        val requestedEngine = input.engine
        var tempFile: File? = null
        val source: ScriptSource = if (requestedEngine.isEmpty()) {
            ScriptFile(file).toSource()
        } else {
            requireRhinoCompat(requestedEngine)
            val directive = McpEngines.directive(requestedEngine) + "\n"
            val body = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            tempFile = File.createTempFile(".${file.nameWithoutExtension}.mcp-engine-", ".js", file.parentFile)
            try {
                tempFile.writeText(directive + body, Charsets.UTF_8)
            } catch (error: Exception) {
                tempFile.delete()
                throw error
            }
            ScriptFile(tempFile).toSource()
        }
        return executeScript(file.relativeTo(root).invariantSeparatorsPath, source,
            workingDirectory.path, input.wait, input.timeoutSeconds, config) { tempFile?.delete() }
    }

    private fun executeScript(path: String, source: ScriptSource, workingDirectory: String,
                              wait: Boolean, timeoutSeconds: Int,
                              config: ExecutionConfig = ExecutionConfig(workingDirectory = workingDirectory),
                              cleanup: () -> Unit = {}): JsonObject {
        val key = "mcp-${nextRun.getAndIncrement()}"
        if (runs.values.count { it.status in ACTIVE_STATES } >= 16) throw ToolError("活动脚本过多，请先停止部分脚本")
        if (runs.size >= 100) runs.values.filter { it.status !in ACTIVE_STATES }.minByOrNull { it.createdAt }?.let { runs.remove(it.id) }
        val record = RunRecord(key, path, System.currentTimeMillis()).apply {
            // init (including pool admission) can fail before onStart is called.
            engine = (source as? JavaScriptSource)?.let { McpEngines.actual(it) }.orEmpty()
        }
        runs[key] = record
        val listener = object : ScriptExecutionListener {
            override fun onStart(execution: ScriptExecution) {
                record.execution = execution; record.engineExecutionId = execution.id
                record.engine = (execution.source as? JavaScriptSource)?.let { McpEngines.actual(it) }.orEmpty()
                if (record.stopRequested) throw ScriptInterruptedException()
                // Rhino's legacy engines module copies top-level values only. JSON maps/lists
                // must be native JS containers to support nested property access and Array APIs.
                val argumentTarget = (execution.engine as? JavaScriptEngine)?.execArgv as? Scriptable
                val rhinoContext = RhinoContext.getCurrentContext()
                if (argumentTarget != null && rhinoContext != null) {
                    @Suppress("UNCHECKED_CAST")
                    McpRhinoArguments.install(rhinoContext, argumentTarget, config.arguments as Map<String, Any?>)
                }
                record.status = "RUNNING"; record.startedAt = System.currentTimeMillis()
                event("$key · 开始 · ${record.path}")
            }
            override fun onSuccess(execution: ScriptExecution, result: Any?) {
                try {
                    record.result = result?.toString().orEmpty().take(4096)
                    record.logs = runLogs(record, execution)
                    record.finishedAt = System.currentTimeMillis()
                    // 先保存结果和日志，再发布结束状态，避免等待方拿到空日志。
                    record.status = if (record.stopRequested) "STOPPED" else "SUCCEEDED"
                    event("$key · ${record.status.lowercase()} · ${record.path}")
                } finally { cleanup() }
            }
            override fun onException(execution: ScriptExecution, error: Throwable) {
                try {
                    // 主动停止造成的中断按 STOPPED 上报；等待超时本身不会中止脚本。
                    if (record.stopRequested || isInterrupt(error)) {
                        record.stopReason = record.stopReason.ifEmpty { if (record.stopRequested) "user_stopped" else "interrupted" }
                        record.errorType = ""; record.errorMessage = ""; record.errorStack = ""
                        record.finishedAt = System.currentTimeMillis()
                        record.logs = runLogs(record, execution)
                        record.status = "STOPPED"
                        event("$key · stopped · ${record.path}")
                        return
                    }
                    record.errorType = execution.engine.getTag("host.errorType") as? String ?: error.javaClass.name
                    record.errorMessage = error.localizedMessage.orEmpty().take(2000)
                    record.errorStack = (execution.engine.getTag("host.errorStack") as? String
                        ?: Log.getStackTraceString(error)).take(16 * 1024)
                    record.finishedAt = System.currentTimeMillis()
                    record.logs = runLogs(record, execution)
                    if (record.status != "STOPPED") {
                        record.status = "FAILED"
                        McpEngineDiagnostics.recordFailure(record.engine, record.id, record.finishedAt, record.errorType)
                    }
                    event("$key · ${record.status.lowercase()} · ${record.errorMessage.take(100)}")
                } finally { cleanup() }
            }
        }
        try {
            val execution = com.jdkshen.aijspro.external.HostScripts.execute(
                com.stardust.app.GlobalAppContext.get(), source, listener, config)
            record.execution = execution; record.engineExecutionId = execution.id
            if (record.stopRequested) execution.engine.forceStop()
        } catch (error: Exception) {
            cleanup()
            record.status = "FAILED"; record.finishedAt = System.currentTimeMillis()
            record.errorType = error.javaClass.name; record.errorMessage = error.localizedMessage.orEmpty()
            record.errorStack = Log.getStackTraceString(error).take(16 * 1024)
            McpEngineDiagnostics.recordFailure(record.engine, record.id, record.finishedAt, record.errorType)
            throw ToolError("脚本启动失败：${record.errorMessage}")
        }
        if (wait) return toolJson(waitFor(record, timeoutSeconds))
        return toolJson(runJson(record))
    }

    private fun listEngineApi(args: JsonObject): JsonObject {
        val engine = McpEngines.normalize(args.stringOr("engine", "quickjs"))
        requireRhinoCompat(engine)
        val module = args.stringOr("module", "")
        val data = com.google.gson.JsonParser().parse(engineProbe(engine, McpEngineApi.list(module))).asJsonObject
        val items = data.getAsJsonArray("items")
        // P0-2（.4 反馈）：每项加 documented 对账——「有实现没文档 / 有文档没实现」，
        // 省得使用者像他们那样「靠真跑才发现文档写着未接入的 matchTemplateMulti 其实可用」。
        // 兼容优先：**保留原 items（字符串数组）不变**，另外给 documentedItems 明细与计数。
        val annotated = McpEngineApi.documentedItems(items, engine, module, apiDocs)
        // 必须走 toolJson 包成标准 MCP tool result envelope，
        // 否则官方 Kotlin SDK 会因为顶层是 {engine,count,items} 而报
        // "Cannot determine RequestResult type from JSON"。
        return toolJson(JsonObject().apply {
            addProperty("engine", engine); addProperty("count", items.size()); add("items", items)
            addProperty("module", module); add("modules", data.getAsJsonArray("modules"))
            add("documentedItems", annotated.getAsJsonArray("items"))
            addProperty("documentedCount", annotated.get("documentedCount").asInt)
            addProperty("undocumentedCount", annotated.get("undocumentedCount").asInt)
            addProperty("build", annotated.get("build").asString)
            addProperty("documentedNote", annotated.get("note").asString)
        })
    }

    private fun probeEngineApi(args: JsonObject): JsonObject {
        val engine = McpEngines.normalize(args.string("engine"))
        val name = args.string("name")
        requireRhinoCompat(engine)
        val source = McpEngineApi.probe(args.stringOr("module", ""), name, args.booleanOr("includeInherited", true))
        val resultText = engineProbe(engine, source)
        val obj = com.google.gson.JsonParser().parse(resultText).asJsonObject
        return toolJson(obj.deepCopy().apply { addProperty("engine", engine) })
    }

    private fun callEngineApi(args: JsonObject): JsonObject {
        val engine = McpEngines.normalize(args.string("engine"))
        require(engine.isNotEmpty()) { "engine 不能为空" }
        requireRhinoCompat(engine)
        val parameters = args.get("args")?.also { require(it.isJsonArray) { "args 必须是数组" } }?.asJsonArray ?: JsonArray()
        val result = com.google.gson.JsonParser().parse(engineProbe(engine,
            McpEngineApi.call(args.stringOr("module", ""), args.string("name"), parameters),
            args.intOr("timeoutSeconds", 30).coerceIn(1, 600))).asJsonObject
        result.addProperty("engine", engine)
        return toolJson(result).apply { if (!result.get("ok").asBoolean) addProperty("isError", true) }
    }

    /** 对比两引擎的全局 API：一次调用就能看出迁移缺口（lite 版无 Rhino 时只返回 QuickJS）。 */
    private fun engineApiDiff(): JsonObject {
        val quickjs = runCatching { engineApiNames("quickjs") }.getOrElse { throw ToolError("QuickJS 探针失败：${it.localizedMessage.orEmpty()}") }
        val rhino = if (com.jdkshen.aijspro.BuildConfig.RHINO_COMPAT) {
            runCatching { engineApiNames("rhino") }.getOrNull()
        } else {
            null
        }
        val quickSet = quickjs.toSet()
        val rhinoSet = rhino?.toSet().orEmpty()
        val nodeSet = engineApiNames("nodejs").toSet()
        return toolJson(JsonObject().apply {
            addProperty("quickjsCount", quickSet.size)
            addProperty("rhinoAvailable", rhino != null)
            addProperty("rhinoCount", rhinoSet.size)
            add("onlyQuickJs", JsonArray().apply { (quickSet - rhinoSet).sorted().forEach { name -> add(name) } })
            add("onlyRhino", JsonArray().apply { (rhinoSet - quickSet).sorted().forEach { name -> add(name) } })
            addProperty("commonCount", quickSet.intersect(rhinoSet).size)
            addProperty("nodejsCount", nodeSet.size)
            add("onlyNodejs", JsonArray().apply { (nodeSet - quickSet - rhinoSet).sorted().forEach { add(it) } })
            add("commonAll", JsonArray().apply { quickSet.intersect(rhinoSet).intersect(nodeSet).sorted().forEach { add(it) } })
            add("engines", JsonArray().apply { McpEngines.names.forEach { add(it) } })
        })
    }

    private fun engineApiNames(engine: String): List<String> {
        val items = com.google.gson.JsonParser().parse(engineProbe(engine, McpEngineApi.list(""))).asJsonObject.getAsJsonArray("items")
        return items.map { it.asString }
    }

    private fun engineProbe(engine: String, source: String, timeout: Int = 30): String {
        requireRhinoCompat(engine)
        val probe = McpEngineProbe(engine, source)
        // 用内存源码执行；不会覆盖用户文件，并发探针也不会争用同一个临时文件。
        val response = executeScript("${probe.name}.js", StringScriptSource(probe.name, probe.source),
            root.path, wait = true, timeoutSeconds = timeout)
        val text = response.getAsJsonArray("content").first().asJsonObject.get("text").asString
        val run = com.google.gson.JsonParser().parse(text).asJsonObject
        if (run.get("status")?.asString == "FAILED") {
            val err = run.getAsJsonObject("error")
            throw ToolError("探针执行失败：" + err.get("message")?.asString.orEmpty())
        }
        if (run.get("status")?.asString != "SUCCEEDED") {
            throw ToolError("探针尚未成功结束：${run.get("status")?.asString}；executionId=${run.get("executionId")?.asString}")
        }
        val logs = run.getAsJsonArray("logs") ?: JsonArray()
        return probe.result(logs.map { it.asJsonObject })
            ?: throw ToolError("探针无输出（未捕获到本次请求的结果日志）")
    }

    private fun engineDiagnostics(): JsonObject = runCatching {
        com.google.gson.JsonParser().parse(McpEngineDiagnostics.snapshotJson(context)).asJsonObject
    }.getOrElse {
        JsonObject().apply {
            addProperty("schemaVersion", McpEngineDiagnostics.SCHEMA_VERSION)
            addProperty("capturedAt", System.currentTimeMillis())
            addProperty("unavailable", true)
        }
    }

    private fun requireRhinoCompat(engine: String) {
        if (engine == "rhino" && !com.jdkshen.aijspro.BuildConfig.RHINO_COMPAT) {
            throw ToolError("当前为 lite 版本，未安装 Rhino 执行引擎；请使用 compat 版本")
        }
    }

    private fun stopScript(args: JsonObject): JsonObject {
        val record = runs[args.string("executionId")] ?: throw ToolError("找不到此 MCP 任务")
        if (record.status !in ACTIVE_STATES) throw ToolError("任务已结束：${record.status}")
        val engineId = record.execution?.engine?.id ?: -1
        record.stopRequested = true; record.stopReason = "user_stopped"
        record.status = "STOPPING"
        record.errorType = ""; record.errorMessage = ""; record.errorStack = ""
        record.logs = runLogs(record)
        record.execution?.engine?.forceStop()
        // 本进程兜底；其它进程由各自 engine.destroy()/closeUiAfterExecution() 清理。
        val overlayClosed = if (engineId >= 0 && record.execution?.engine?.getTag("host.process") == null)
            OverlayRegistry.closeByOwner(engineId, "mcp_stop_script") else 0
        event("${record.id} · stopped · ${record.path} · overlay=$overlayClosed")
        return toolJson(runJson(record).apply { addProperty("overlayClosed", overlayClosed) })
    }

    /** 中止类异常（QuickJS 的 interrupted / Rhino 的 ScriptInterruptedException）。 */
    private fun isInterrupt(error: Throwable): Boolean = McpRunOutcome.isInterrupt(error)

    private fun waitFor(record: RunRecord, timeoutSeconds: Int): JsonObject {
        val timeout = timeoutSeconds.coerceIn(1, 600)
        val deadline = System.currentTimeMillis() + timeout * 1000L
        while (record.status in ACTIVE_STATES && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
        }
        return runJson(record, true).apply { addProperty("timedOut", record.status in ACTIVE_STATES) }
    }

    private fun runJson(run: RunRecord, includeLogs: Boolean = false) = JsonObject().apply {
        addProperty("executionId", run.id); addProperty("engineExecutionId", run.engineExecutionId)
        run.execution?.engine?.let { engine ->
            addProperty("executionState", engine.executionState)
            (engine.getTag("host.process") as? String)?.let { addProperty("hostProcess", it) }
            (engine.getTag("host.pid") as? Number)?.let { addProperty("hostPid", it) }
        }
        if (run.engine.isNotEmpty()) addProperty("engine", run.engine)
        addProperty("path", run.path); addProperty("status", run.status); addProperty("createdAt", run.createdAt)
        if (run.startedAt > 0) addProperty("startedAt", run.startedAt)
        if (run.finishedAt > 0) addProperty("finishedAt", run.finishedAt)
        if (run.result.isNotEmpty()) addProperty("result", run.result)
        if (run.status == "STOPPED") addProperty("stopReason", run.stopReason.ifEmpty { "user_stopped" })
        if (run.errorType.isNotEmpty()) add("error", JsonObject().apply {
            addProperty("type", run.errorType); addProperty("message", run.errorMessage); addProperty("stack", run.errorStack)
        })
        if (includeLogs) {
            val snapshot = run.logs ?: runLogs(run)
            add("logs", JsonArray().apply { snapshot.forEach { add(it) } })
            // P2-11：结构化 logs[] 本来就是干净的；再给一份可直接读的文本，省掉调用方自己剥控制前缀。
            val cleanText = snapshot.mapNotNull { entry ->
                val obj = entry.asJsonObject
                (obj.get("content") ?: obj.get("text"))?.takeIf { it.isJsonPrimitive }?.asString
            }.joinToString("\n")
            addProperty("logsText", cleanText)
        }
    }

    private fun readApkLogs(afterId: Int, limit: Int): JsonObject {
        val console = console() ?: throw ToolError("全局日志不可用")
        val all = console.allLogs
        val snapshot = synchronized(all) { all.filter { it.id > afterId }.take(limit.coerceIn(1, 500)) }
        return JsonObject().apply {
            add("items", JsonArray().apply { snapshot.forEach { entry -> add(JsonObject().apply {
                addProperty("id", entry.id); addProperty("level", logLevel(entry.level))
                addProperty("content", entry.content?.toString().orEmpty().take(16 * 1024)); addProperty("newLine", entry.newLine)
            }) } })
            addProperty("nextAfterId", snapshot.lastOrNull()?.id ?: afterId)
            addProperty("hasMore", synchronized(all) { all.lastOrNull()?.id?.let { it > (snapshot.lastOrNull()?.id ?: afterId) } == true })
        }
    }

    private fun console(): ConsoleImpl? = runCatching { AutoJs.getInstance().scriptEngineService.globalConsole as? ConsoleImpl }.getOrNull()
    private fun runLogs(record: RunRecord, execution: ScriptExecution? = record.execution): List<JsonObject> {
        val remote = execution?.engine?.getTag("host.logs") as? List<*>
        if (remote != null) return remote.takeLast(MAX_RUN_LOGS).mapNotNull { value ->
            val row = value as? Map<*, *> ?: return@mapNotNull null
            JsonObject().apply {
                addProperty("id", row["id"] as? Number)
                addProperty("level", logLevel((row["level"] as? Number)?.toInt() ?: Log.INFO))
                addProperty("content", row["content"]?.toString().orEmpty().take(16 * 1024))
            }
        }
        val ownConsole = runCatching { (execution?.engine as? JavaScriptEngine)?.runtime?.console as? ConsoleImpl }.getOrNull()
            ?: return emptyList()
        val all = ownConsole.allLogs
        return synchronized(all) { all.takeLast(MAX_RUN_LOGS) }.map { entry -> JsonObject().apply {
            addProperty("id", entry.id); addProperty("level", logLevel(entry.level))
            addProperty("content", entry.content?.toString().orEmpty().take(16 * 1024))
        } }
    }

    /** task-118：MCP 服务开启即授权读写/运行（安全边界在网络层：局域网需 Bearer token）。 */
    private fun requireExecution() { /* no-op：不再要求额外运行授权 */ }

    private fun listOverlays(): JsonObject = toolJson(OverlayStateReader.toJson(context))

    private fun closeOverlays(args: JsonObject): JsonObject {
        val executionId = args.stringOr("executionId", "")
        var stopped = 0
        if (executionId.isNotEmpty()) {
            val record = runs[executionId] ?: throw ToolError("找不到此 MCP 任务")
            val engineId = record.execution?.engine?.id ?: -1
            if (record.status in ACTIVE_STATES) {
                stopScript(JsonObject().apply { addProperty("executionId", executionId) })
                stopped = 1
            }
            if (engineId >= 0 && record.execution?.engine?.getTag("host.process") == null)
                OverlayRegistry.closeByOwner(engineId, "mcp_close_overlays")
        } else {
            stopped = stopAllScripts().get("stopped")?.asInt ?: 0
        }
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
        val remaining = OverlayStateReader.countAggregated(context)
        return toolJson(JsonObject().apply {
            addProperty("success", true)
            addProperty("stopped", stopped)
            addProperty("overlayCount", remaining)
            add("overlays", OverlayStateReader.toJson(context))
        })
    }

    private fun stopAllScripts(): JsonObject {
        requireExecution()
        runs.values.filter { it.status in ACTIVE_STATES }.forEach { record ->
            record.stopRequested = true; record.stopReason = "all_scripts_stopped"
            record.logs = runLogs(record)
            record.status = "STOPPING"
        }
        val stopped = com.jdkshen.aijspro.external.HostScripts.runningNotificationTasks().length()
        com.jdkshen.aijspro.external.HostScripts.stopAllScripts(context)
        val overlayClosed = OverlayRegistry.closeAll("mcp_stop_all")
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
        val overlayCount = OverlayStateReader.countAggregated(context)
        event("stop_all_scripts · 停止全部脚本 · $stopped · overlay=$overlayClosed/$overlayCount")
        return toolJson(JsonObject().apply { addProperty("success", true); addProperty("stopped", stopped); addProperty("scope", "all_scripts"); addProperty("overlayClosed", overlayClosed); addProperty("overlayCount", overlayCount) })
    }

    private fun getMcpLog(args: JsonObject): JsonObject {
        val maxChars = args.intOr("maxChars", 20000).also { require(it in 1..200000) { "maxChars 必须是 1～200000" } }
        val channel = args.stringOr("channel", "script").also { require(it in setOf("script", "service", "all")) { "channel 必须是 script、service 或 all" } }
        val executionId = args.stringOr("executionId", "")
        val scriptText = if (executionId.isNotEmpty()) {
            val record = runs[executionId] ?: throw ToolError("找不到此 MCP 任务")
            McpLogText.tailResult((record.logs ?: runLogs(record)).map {
                "${it.get("level").asString}: ${it.get("content").asString}"
            }, maxChars) { it }
        } else console()?.let { c -> synchronized(c.allLogs) {
            McpLogText.tailResult(c.allLogs, maxChars) { it.content.toString() }
        } } ?: McpLogText.Tail("", false)
        val text = buildString {
            if (channel != "service") append(scriptText.text)
            if (channel != "script") {
                if (isNotEmpty()) append('\n')
                append(McpService.recentEvents().asReversed().joinToString("\n"))
            }
        }
        val combined = McpLogText.tailResult(listOf(text), maxChars) { it }
        return JsonObject().apply {
            addProperty("log", combined.text); addProperty("maxChars", maxChars)
            addProperty("truncated", combined.truncated || channel != "service" && scriptText.truncated); addProperty("channel", channel)
            if (executionId.isNotEmpty()) addProperty("executionId", executionId)
        }
    }

    private fun searchScripts(query: String, path: String, limit: Int, scanCursor: String = ""): JsonObject {
        val needle = query.trim()
        if (needle.isEmpty()) throw ToolError("搜索词不能为空")
        if (needle.length > 200) throw ToolError("搜索词过长")
        val start = resolveScript(path)
        if (!start.isDirectory) throw ToolError("搜索目录不存在")
        val startPath = start.relativeTo(root).invariantSeparatorsPath
        val resume = scanCursor.takeIf { it.isNotEmpty() }?.let { decodeScanCursor(it, startPath, needle) }
        val nextFileIndex = resume?.nextFileIndex ?: 0
        if (nextFileIndex > MAX_SCAN_WALK_FILES) invalidScanCursor("扫描位置超过本次上限")
        val walkLimit = if (resume == null) MAX_SEARCH_FILES + 1
            else (nextFileIndex + MAX_SEARCH_FILES + 1).coerceAtMost(MAX_SCAN_WALK_FILES)
        // 枚举统一走 ScriptRepository.walkFiles；过滤在计数之前（与 filter{take(n)} 同语义）。
        val candidates = ScriptRepository.walkFiles(start, walkLimit) { _, name ->
            name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
        }.map { File(it.path) }
        if (resume != null) {
            if (candidates.size < resume.prefixCount) invalidScanCursor("扫描目录内容已变化（文件数减少）")
            if (scanGeneration(candidates, resume.prefixCount) != resume.generation)
                invalidScanCursor("扫描代次已变化（目录或文件内容已变）")
        }
        val hits = mutableListOf<JsonObject>()
        var fileIndex = nextFileIndex
        var lineIndex = resume?.nextLine ?: 0
        var nameChecked = resume?.nameChecked ?: false
        var scanned = 0
        var scannedBytes = 0L
        var lastScannedPath: String? = null
        var hitLimitReached = false
        var fileLimitReached = false
        while (fileIndex < candidates.size && scanned < MAX_SEARCH_FILES && hits.size < MAX_SEARCH_HITS) {
            val file = candidates[fileIndex]
            val relative = file.relativeTo(root).invariantSeparatorsPath
            lastScannedPath = relative
            if (!nameChecked) {
                scanned++; scannedBytes += file.length()
                if (file.name.contains(needle, ignoreCase = true)) hits += JsonObject().apply {
                    addProperty("path", relative); addProperty("kind", "name"); addProperty("line", 0); addProperty("preview", file.name)
                }
                nameChecked = true
                if (hits.size >= MAX_SEARCH_HITS) { hitLimitReached = true; break }
            }
            if (file.length() <= MAX_SEARCH_FILE_BYTES) {
                runCatching { file.useLines(Charsets.UTF_8) { lines ->
                    var current = 0
                    for (line in lines) {
                        if (current < lineIndex) { current++; continue }
                        if (hits.size >= MAX_SEARCH_HITS) break
                        if (line.contains(needle, ignoreCase = true)) hits += JsonObject().apply {
                            addProperty("path", relative); addProperty("kind", "content"); addProperty("line", current + 1)
                            addProperty("preview", line.trim().take(240))
                        }
                        current++; lineIndex = current
                        if (hits.size >= MAX_SEARCH_HITS) break
                    }
                    if (hits.size < MAX_SEARCH_HITS) lineIndex = current
                } }
            }
            if (hits.size >= MAX_SEARCH_HITS) { hitLimitReached = true; break }
            fileIndex++; lineIndex = 0; nameChecked = false
        }
        if (!hitLimitReached && scanned >= MAX_SEARCH_FILES && fileIndex < candidates.size) fileLimitReached = true
        val walkLimitReached = !hitLimitReached && !fileLimitReached && fileIndex >= candidates.size && candidates.size >= walkLimit
        val truncated = hitLimitReached || fileLimitReached || walkLimitReached
        val stopReason = when {
            hitLimitReached -> "hit_limit_reached"
            fileLimitReached -> "file_scan_limit_reached"
            walkLimitReached -> "walk_limit_reached"
            else -> "completed"
        }
        val prefixCount = if (hitLimitReached) (fileIndex + 1).coerceAtMost(candidates.size) else fileIndex.coerceAtMost(candidates.size)
        val cursor = if (truncated) encodeScanCursor(startPath, needle, fileIndex, lineIndex, nameChecked, prefixCount,
            scanGeneration(candidates, prefixCount)) else ""
        val stoppedAt = if (truncated) ToolkitIo.json("path" to lastScannedPath,
            "line" to lineIndex.takeIf { it > 0 }, "reason" to stopReason) else null
        return firstPage(hits, limit).apply {
            addProperty("scannedFiles", scanned)
            addProperty("scannedEntries", scanned)
            addProperty("scannedBytes", scannedBytes)
            addProperty("truncatedScan", truncated)
            addProperty("hasMoreScan", truncated)
            add("stoppedAt", stoppedAt)
            // P1-8：截断时必须能区分「扫完没有」和「没扫完」，否则 total=0 会被误读成「项目里没有」。
            addProperty("stopReason", stopReason)
            add("scanLimits", JsonObject().apply {
                addProperty("maxFiles", MAX_SEARCH_FILES); addProperty("maxHits", MAX_SEARCH_HITS)
                addProperty("scannedFiles", scanned); addProperty("scannedEntries", scanned)
                addProperty("scannedBytes", scannedBytes); addProperty("hits", hits.size)
            })
            if (truncated) {
                addProperty("scanCursor", cursor)
                addProperty("resumeCursor", cursor)
                addProperty("warning", "扫描未完成（stopReason=$stopReason）：**未扫完≠不存在，不能据此判定「不存在」**；可用 scanCursor 继续")
                addProperty("resumeHint", "把返回的 scanCursor 原样传给 search_scripts（query/path 保持不变），从已扫位置继续；不要用它推断剩余内容为空")
            }
        }
    }
    private data class ScanResume(
        val nextFileIndex: Int,
        val nextLine: Int,
        val nameChecked: Boolean,
        val prefixCount: Int,
        val generation: String,
    )
    private fun invalidScanCursor(reason: String): Nothing =
        throw ToolError("扫描游标失效：$reason。请不带 scanCursor 重新开始（保留原 query/path）")
    private fun scanGeneration(candidates: List<File>, count: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 0 until count.coerceAtMost(candidates.size)) {
            val file = candidates[i]; val relative = file.relativeTo(root).invariantSeparatorsPath
            digest.update(relative.toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
            digest.update(file.length().toString().toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
            digest.update(file.lastModified().toString().toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun encodeScanCursor(startPath: String, query: String, nextFileIndex: Int, nextLine: Int,
                                 nameChecked: Boolean, prefixCount: Int, generation: String): String {
        val payload = ToolkitIo.json("v" to 1, "root" to root.canonicalPath, "path" to startPath,
            "query" to query, "nextFileIndex" to nextFileIndex, "nextLine" to nextLine,
            "nameChecked" to nameChecked, "prefixCount" to prefixCount, "generation" to generation)
        return SCAN_CURSOR_PREFIX + ToolkitIo.hex(payload.toString().toByteArray(Charsets.UTF_8)).lowercase()
    }
    private fun decodeScanCursor(raw: String, startPath: String, query: String): ScanResume {
        if (raw.length > 4096 || !raw.startsWith(SCAN_CURSOR_PREFIX)) invalidScanCursor("格式不正确")
        val bytes = try { ToolkitIo.unhex(raw.removePrefix(SCAN_CURSOR_PREFIX)) }
            catch (_: IllegalArgumentException) { invalidScanCursor("编码损坏") }
        val payload = try { com.google.gson.JsonParser().parse(bytes.toString(Charsets.UTF_8)).asJsonObject }
            catch (_: Exception) { invalidScanCursor("内容损坏") }
        fun text(key: String): String = payload.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: invalidScanCursor("缺少 $key")
        fun number(key: String): Int = payload.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            ?: invalidScanCursor("缺少 $key")
        if (number("v") != 1) invalidScanCursor("版本不支持")
        if (text("root") != root.canonicalPath) invalidScanCursor("脚本根路径已变化")
        if (text("path") != startPath) invalidScanCursor("搜索根路径已变化")
        if (text("query") != query) invalidScanCursor("query 已变化（续扫必须使用同一搜索词）")
        val nextFileIndex = number("nextFileIndex"); val nextLine = number("nextLine")
        val prefixCount = number("prefixCount")
        if (nextFileIndex < 0 || nextLine < 0 || prefixCount < 0 || prefixCount < nextFileIndex) invalidScanCursor("位置字段非法")
        val nameChecked = payload.get("nameChecked")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            ?: invalidScanCursor("缺少 nameChecked")
        val generation = text("generation")
        if (generation.isEmpty()) invalidScanCursor("缺少代次")
        return ScanResume(nextFileIndex, nextLine, nameChecked, prefixCount, generation)
    }

    @Synchronized private fun firstPage(items: List<JsonObject>, limit: Int): JsonObject {
        cleanupCursors()
        val size = limit.coerceIn(1, 200)
        val result = JsonObject().apply {
            add("items", JsonArray().apply { items.take(size).forEach { add(it) } }); addProperty("total", items.size)
        }
        if (items.size > size) {
            while (cursors.size >= MAX_CURSORS) cursors.remove(cursors.keys.first())
            val id = UUID.randomUUID().toString(); cursors[id] = CursorPage(items, size, System.currentTimeMillis())
            result.addProperty("cursor", id); result.addProperty("hasMore", true)
        } else result.addProperty("hasMore", false)
        return result
    }

    @Synchronized private fun continueResult(id: String, limit: Int): JsonObject {
        cleanupCursors()
        val cursor = cursors[id] ?: throw ToolError(
            "游标不存在或已过期：分页 cursor 只对产生它的工具（当前仅 search_scripts）有效，且 5 分钟内有效；" +
                "apk_* 的 nextCursor 请用 apk_continue（或 apk_dex_list_classes/list_methods 原工具的 cursor），不能传给 continue_result。",
        )
        val size = limit.coerceIn(1, 200); val start = cursor.offset; val end = (start + size).coerceAtMost(cursor.items.size)
        cursor.offset = end
        return JsonObject().apply {
            add("items", JsonArray().apply { cursor.items.subList(start, end).forEach { add(it) } })
            addProperty("nextOffset", end); addProperty("total", cursor.items.size); addProperty("hasMore", end < cursor.items.size)
            addProperty("cursorTool", "search_scripts")
            if (end < cursor.items.size) addProperty("cursor", id) else cursors.remove(id)
        }
    }

    private fun cleanupCursors() {
        val cutoff = System.currentTimeMillis() - CURSOR_TTL_MS
        cursors.entries.removeAll { it.value.createdAt < cutoff }
    }

    private fun resolveScript(path: String): File =
        McpPathRules.resolve(root, path, "路径超出脚本目录")
    private fun listFiles(dir: File, offset: Int, limit: Int, recursive: Boolean = false): JsonObject {
        if (!dir.isDirectory) throw ToolError("目录不存在：${dir.relativeTo(root).invariantSeparatorsPath}")
        // 枚举统一走 ScriptRepository（含隐藏——MCP 与文件页的可见性语义不同）。
        val entries = if (recursive) {
            ScriptRepository.walkFiles(dir, 0).map { File(it.path) }
                .sortedBy { it.relativeTo(dir).invariantSeparatorsPath.lowercase() }
        } else {
            ScriptRepository.list(dir, null, true).map { File(it.path) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        return page(entries, offset, limit) { file -> JsonObject().apply {
            addProperty("path", file.relativeTo(root).invariantSeparatorsPath); addProperty("name", file.name)
            addProperty("directory", file.isDirectory); addProperty("size", if (file.isFile) file.length() else 0)
            addProperty("modifiedAt", file.lastModified())
        } }
    }
    private fun listAssets(path: String, offset: Int, limit: Int): JsonObject {
        val safe = safeSamplePath(path)
        val nodes = SampleRepository.list(
            SampleRepository.AssetLister { p -> context.assets.list(p) }, safe)
        return page(nodes, offset, limit) { node -> JsonObject().apply {
            addProperty("path", node.path); addProperty("name", node.name)
            addProperty("directory", node.isDirectory)
        } }
    }
    private fun readTextFile(file: File): ByteArray {
        // 分开报错：以前“文件不存在”也会被归成“仅支持文本文件”，
        // 会让人误以为是扩展名问题（·用户反馈：路径写错时提示误导）。
        if (!file.exists()) throw ToolError("文件不存在：${file.relativeTo(root).invariantSeparatorsPath}")
        if (file.isDirectory) throw ToolError("这是目录，不是文件：${file.relativeTo(root).invariantSeparatorsPath}")
        if (!file.isFile || file.extension.lowercase() !in TEXT_EXTENSIONS) throw ToolError("仅支持文本文件")
        if (file.length() > MAX_READ_FILE_BYTES) throw ToolError("文件过大，请在工作区外分析")
        return file.readBytes()
    }
    private fun readAsset(path: String, offset: Int, max: Int): JsonObject {
        val safe = safeSamplePath(path)
        val bytes = try {
            SampleRepository.readBytes(SampleRepository.AssetOpener { p -> context.assets.open(p) }, safe)
        } catch (_: IOException) { throw ToolError("示例不存在") }
        return readBytes(bytes, offset, max)
    }
    private fun readBytes(bytes: ByteArray, offset: Int, max: Int): JsonObject {
        val start = offset.coerceIn(0, bytes.size); val count = max.coerceIn(1, 256 * 1024).coerceAtMost(bytes.size - start)
        return JsonObject().apply {
            addProperty("content", String(bytes, start, count, Charsets.UTF_8)); addProperty("offset", start)
            addProperty("nextOffset", start + count); addProperty("size", bytes.size); addProperty("sha256", sha256(bytes))
            addProperty("truncated", start + count < bytes.size)
        }
    }
    /** 示例路径防护：统一走 [SampleRepository.sanitizePath]，错误语义仍是 MCP 的 ToolError。 */
    private fun safeSamplePath(path: String): String = try {
        SampleRepository.sanitizePath(path)
    } catch (error: IllegalArgumentException) {
        throw ToolError(error.message ?: "路径无效")
    }
    /**
     * P1-A：workspace/text 成功结果统一 {ok,data}；content 文本保留旧扁平 JSON，
     * data 内保留原字段，避免只读 content 或只读 data 的调用方同时断掉。
     */
    private fun toolOk(data: JsonObject, legacyText: String = data.toString()): JsonObject =
        McpToolResultShapes.success(data, legacyText)

    /**
     * P1-A：workspace/text 分支内部失败也必须带 structuredContent。
     * Lead 的全局 catch 已覆盖 ToolError/IllegalArgumentException；这里补 IllegalState/IOException/Security
     * 以及 workspace 内抛出的异常，避免这些分支只留纯文本。
     */
    private inline fun structuredCall(block: () -> JsonObject): JsonObject = try {
        block()
    } catch (failure: ToolError) {
        toolFailure(failure.message ?: "工具调用失败", "TOOL_FAILED")
    } catch (failure: IllegalStateException) {
        toolFailure(failure.message ?: "工作区状态冲突", "WORKSPACE_STATE_CONFLICT")
    } catch (failure: SecurityException) {
        toolFailure(failure.message ?: "缺少手机权限", "PERMISSION_DENIED")
    } catch (failure: IOException) {
        toolFailure(failure.message ?: "文件操作失败", "IO_ERROR")
    }

    private fun workspaceJson(ws: McpWorkspaceStore.Workspace) = JsonObject().apply {
        addProperty("workspaceId", ws.id); addProperty("targetPath", ws.targetPath); addProperty("createdAt", ws.createdAt)
        addProperty("updatedAt", ws.updatedAt); addProperty("state", ws.state); addProperty("pendingApproval", ws.pendingApproval)
        addProperty("changedFiles", ws.changedFiles)
    }
    /** task-118：MCP 服务开启即授权读写/运行。 */
    private fun requireWrite() { /* no-op：不再要求额外写入授权 */ }
    private fun helpText(): String {
        val base = context.assets.open("mcp/AIjs-MCP-Guide.md")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        // 实战要点（冷进程/dlopen、terminal 大输出、借壳解密流程等）单独成篇，
        // 追加在完整说明之后；缺资源时不能让 learn_autojs_skill 整体失败。
        val playbook = runCatching {
            context.assets.open("mcp/AIjs-MCP-Playbook.md")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        val merged = McpTerminalHelp.merge(base)
        return if (playbook.isNullOrBlank()) merged else merged.trimEnd() + "\n\n---\n\n" + playbook.trimEnd() + "\n"
    }
    /** 辅助工具的统一结构化错误（避免把 Kotlin 异常栈暴露成 MCP Internal error）。 */
    private fun helperError(tool: String, failure: Throwable): JsonObject = ToolkitIo.json(
        "ok" to false,
        "error" to ToolkitIo.json(
            "code" to "INVALID_ARGUMENT",
            "tool" to tool,
            "message" to (failure.message ?: failure.javaClass.simpleName),
            "recoverable" to true
        )
    )

    /**
     * 兼容 args 的三种形状：数组 / 单个字符串（按空白拆分并尊重引号）/ null。
     * 用户在实测里传 "--out /path/a.txt" 时旧实现直接 getAsJsonArray 抛异常 → MCP Internal error。
     */
    private fun scriptArgs(args: JsonObject): List<String> {
        val element = args.get("args") ?: return emptyList()
        if (element.isJsonNull) return emptyList()
        if (element.isJsonArray) {
            return element.asJsonArray.mapNotNull { item -> item?.takeIf { it.isJsonPrimitive }?.asString }
        }
        if (!element.isJsonPrimitive) return emptyList()
        val raw = element.asString.trim()
        if (raw.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        val current = StringBuilder()
        var quote = ' '
        raw.forEach { ch ->
            when {
                quote != ' ' -> if (ch == quote) quote = ' ' else current.append(ch)
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> if (current.isNotEmpty()) { result.add(current.toString()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun terminalEnvironment(args: JsonObject): LinkedHashMap<String, String> {
        val env = linkedMapOf<String, String>()
        args.get("env")?.let { element ->
            require(element.isJsonObject) { "env 必须是字符串对象" }
            element.asJsonObject.entrySet().forEach { (key, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "env.$key 必须是字符串" }
                env[key] = value.asString
            }
        }
        // §2.1：用错后端时不再只说 inaccessible or not found；autoRoute 需要显式开启（默认 false）。
        if (args.booleanOr("autoRoute", false)) env[ToolkitBackendRouting.AUTO_ROUTE_ENV] = "true"
        return env
    }
    private fun parseTerminalBackend(args: JsonObject): String {
        val value = args.get("backend") ?: return McpTerminalJobs.BACKEND_APP
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "backend 必须是 app 或 shizuku" }
        val backend = value.asString
        require(backend == McpTerminalJobs.BACKEND_APP || backend == McpTerminalJobs.BACKEND_SHIZUKU) {
            "backend 必须是 app 或 shizuku"
        }
        return backend
    }
    private fun terminalLong(args: JsonObject, key: String, fallback: Long): Long {
        val value = args.get(key) ?: return fallback
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key 必须是整数" }
        return try { value.asBigDecimal.longValueExact() } catch (_: ArithmeticException) { throw IllegalArgumentException("$key 必须是整数且不能超出范围") }
    }
    private fun terminalInt(args: JsonObject, key: String, fallback: Int): Int {
        val value = terminalLong(args, key, fallback.toLong())
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key 超出范围" }
        return value.toInt()
    }
    private fun logLevel(level: Int) = when (level) { Log.VERBOSE -> "VERBOSE"; Log.DEBUG -> "DEBUG"; Log.INFO -> "INFO"; Log.WARN -> "WARN"; Log.ERROR -> "ERROR"; Log.ASSERT -> "ASSERT"; else -> level.toString() }
    /**
     * baseline 的 APK 工作区统计：**同一份** ToolkitWorkspaceInventory 输出做投影（不另算一份），
     * 本地只读清单、不新增 MCP 往返、不写盘。
     */
    private fun apkWorkspacesBaseline(): JsonObject = runCatching {
        val inventory = ToolkitWorkspaceInventory(toolkitStore).inventory(ToolkitIo.json("limit" to 1))
        McpBaseline.apkWorkspaces(inventory)
    }.getOrElse {
        McpBaseline.apkWorkspacesUnavailable("APK 工作区统计不可用；用 apk_workspace_inventory 查询")
    }

    /** jobs retention 摘要：走 get_status 用过的同一条 toolkit_status 桥（30s 缓存，失败/入口调用不额外探测）。 */
    private fun toolkitJobsSummary(force: Boolean = false): JsonObject? {
        val cached = jobsSummaryCache
        if (!force && cached != null && System.currentTimeMillis() - jobsSummaryCacheAt < 30_000L) return cached
        return runCatching {
            McpToolkitBridge.call(context, root, "toolkit_status", JsonObject())
                .getAsJsonObject("data")?.getAsJsonObject("jobs")
        }.getOrNull()?.also { jobsSummaryCache = it.deepCopy(); jobsSummaryCacheAt = System.currentTimeMillis() }
    }

    private fun currentProjectName(): String? = runCatching {
        // 注意：get_status 里那个 outputConventions 是函数内局部量，这里必须自己取（同一实现，不另算规则）。
        McpOutputConventions.describeFor(root, toolkitStore).get("defaultProject")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.getOrNull()

    /**
     * §6.4 基线快照：**唯一**的 baseline 组装点（get_status / get_baseline / 首个 tools/call / 失败信封都走它）。
     * 数字全部来自既有实现（ToolkitWorkspaceInventory、McpBuildTools.cachedReadiness、toolkit_status 的 retention），
     * 15s 内复用同一份；内容不变时 version 不变（客户端可去重）；最后过 [McpBaseline.fitBudget]。
     */
    @Synchronized
    private fun baselineSnapshot(jobsSummary: JsonObject? = toolkitJobsSummary(), force: Boolean = false,
                                 sampleIfMissing: Boolean = true): JsonObject {
        val now = System.currentTimeMillis()
        if (!force) baselineCache?.let { if (now - baselineCacheAt < baselineTtlMillis) return it.deepCopy() }
        val workspaces = runCatching {
            ToolkitIo.json("script" to workspaces.list().size, "apk" to apkWorkspacesBaseline())
        }.getOrElse {
            ToolkitIo.json("script" to null, "apk" to McpBaseline.apkWorkspacesUnavailable("脚本工作区统计不可用"))
        }
        // task-38（②）：未采样就触发一次与 get_build_tools **同一条** readiness（30s 内不重试）；失败路径不触发。
        val readiness = McpBuildTools.cachedReadiness() ?: if (sampleIfMissing) sampleToolchain() else null
        val toolchain = runCatching { McpBaseline.toolchain(readiness) }
            .getOrElse { McpBaseline.toolchain(null) }
        val jobs = McpBaseline.jobs(
            runCatching { retentionSummary(jobsSummary ?: toolkitJobsSummary()) }.getOrDefault("不可用"),
            "终态立刻 result 或 export",
        )
        // outputRule 用**短**形式（设备4 §6.4 样例的那句）：完整 RULE 文案 ~200B，会把 512B 预算撑爆而被裁掉。
        val content = McpBaseline.assemble(0, root.path, currentProjectName(), workspaces, toolchain, jobs,
            "所有产出落 <root>/<项目名>/")
        val version = runCatching { baselineVersion.versionFor(content) }.getOrDefault(0)
        content.addProperty("version", version)
        val fitted = runCatching { McpBaseline.fitBudget(content) }.getOrElse { content }
        baselineCache = fitted.deepCopy()
        baselineCacheAt = now
        return fitted
    }

    /**
     * task-38（②）：未采样时触发一次采样（复用 get_build_tools 的 inspect → 同一份 readiness），30s 内不重试。
     */
    private fun sampleToolchain(): JsonObject? {
        val now = System.currentTimeMillis()
        if (now - toolchainSampleAt < 30_000L) return McpBuildTools.cachedReadiness()
        toolchainSampleAt = now
        return runCatching { McpBuildTools.inspect(terminalBackend, root); McpBuildTools.cachedReadiness() }.getOrNull()
    }

    /**
     * task-38（①）：**失败专用**基线 —— 不受 latch 影响、只带 version(+recovery/missing)、体积远小于完整版。
     */
    private fun failureBaselineView(): JsonObject = runCatching {
        McpBaseline.failureView(baselineSnapshot(sampleIfMissing = false))
    }.getOrElse {
        ToolkitIo.json("version" to -1, "trimmed" to "failure",
            "trimNote" to "失败专用裁剪：基线暂不可用（完整基线用 get_baseline）")
    }

    /**
     * task-38（①）兜底：**任何** isError 结果都必须带失败裁剪版 baseline（覆盖桥接与将来新增的失败路径）。
     */
    private fun withFailureBaseline(result: JsonObject): JsonObject {
        if (result.get("isError")?.asBoolean != true) return result
        val view = result.get("baseline")?.takeIf { it.isJsonObject }?.asJsonObject ?: failureBaselineView()
        result.add("baseline", view)
        result.getAsJsonObject("structuredContent")?.let { if (!it.has("baseline")) it.add("baseline", view.deepCopy()) }
        // task-39：文本镜像（content[].text）也要带**同一份** baseline —— 只在序列化单点（这里）重写，
        // 因为有的客户端只读 content[0].text，拿不到 recovery 就失去自救信息。
        val errorCode = result.get("errorCode")?.takeIf { it.isJsonPrimitive }?.asString
        result.getAsJsonArray("content")?.forEach { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val text = obj.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return@forEach
            if (text.contains("\"baseline\"")) return@forEach
            obj.addProperty("text", McpBaseline.failureText(text, view, errorCode, null))
        }
        return result
    }

    /**
     * §6.4 入口策略：baseline 只挂「入口型」返回 —— 首个 tools/call 成功、或**任何**失败信封；
     * tools/list 与普通工具返回不带（省上下文）。
     */
    private fun withEntryBaseline(result: JsonObject): JsonObject {
        if (result.has("baseline")) { entryBaselineDelivered.set(true); return result }
        val failed = result.get("isError")?.asBoolean == true
        if (failed) return result.apply { add("baseline", baselineSnapshot()) }
        if (entryBaselineDelivered.compareAndSet(false, true)) result.add("baseline", baselineSnapshot())
        return result
    }

    /** 6h / 64 条；jobs 摘要不可用时仍给产品默认值。 */
    private fun retentionSummary(jobs: JsonObject?): String {
        val retention = jobs?.getAsJsonObject("retention")
        val window = retention?.get("windowMillis")?.takeIf { it.isJsonPrimitive }?.asLong ?: (6L * 60 * 60 * 1000)
        val max = retention?.get("maxRetainedFinished")?.takeIf { it.isJsonPrimitive }?.asInt ?: 64
        val hours = if (window < 0) "不自动过期" else (window / 3_600_000L).toString() + "h"
        return hours + " / " + max + " 条"
    }

    private fun <T> page(all: List<T>, offset: Int, limit: Int, convert: (T) -> JsonElement) = JsonObject().apply {
        val start = offset.coerceIn(0, all.size); val size = limit.coerceIn(1, 200); val end = (start + size).coerceAtMost(all.size)
        add("items", JsonArray().apply { all.subList(start, end).forEach { add(convert(it)) } })
        addProperty("nextOffset", end); addProperty("total", all.size); addProperty("hasMore", end < all.size)
    }
    private fun tool(name: String, description: String, schema: JsonObject = objSchema()) = JsonObject().apply {
        val category = McpToolCategories.categoryFor(name)
        addProperty("name", name)
        addProperty("category", category.id)
        addProperty("categoryTitle", category.title)
        addProperty("description", "[" + category.title + "] " + description)
        add("inputSchema", schema)
    }
    private fun objSchema(vararg names: String, required: Array<String> = emptyArray()) =
        McpToolSchema.objectSchema(*names, required = required)
    private fun toolJson(json: JsonObject) = toolResult(json.toString()).apply {
        // P0-2：统一返回结构。扁平 structuredContent 中带 jobId/items/listing 的工具保留旧顶层字段，
        // 同时补 data 镜像；已有 {ok,data} 信封的 toolOk 结果不受影响。
        val structured = if (!json.has("data") && (json.has("jobId") || json.has("items") || json.has("listing")))
            McpToolResultShapes.flatWithDataMirror(json) else json
        add("structuredContent", structured)
    }
    /** P0-2：terminal_* 扁平 structuredContent 保留旧顶层字段，同时补 data 镜像（data.jobId/data.items…）。 */
    private fun terminalJson(json: JsonObject) = toolJson(McpToolResultShapes.flatWithDataMirror(json))
    /** 扁平标量工具：`content[0].text` 保留旧扁平 JSON，`structuredContent` 补 `{ok,data}`（`.4` 要求）。 */
    private val scalarMirrorTools = setOf("get_status", "get_build_tools")

    private fun applyScalarMirror(toolName: String, result: JsonObject): JsonObject {
        if (result.get("isError")?.asBoolean == true) return result
        val canonical = runCatching { McpToolCatalog.canonicalName(toolName) }.getOrDefault(toolName)
        if (canonical !in scalarMirrorTools) return result
        val structured = result.getAsJsonObject("structuredContent") ?: return result
        if (structured.has("ok")) return result
        result.add("structuredContent", ToolkitIo.json(
            "ok" to true, "data" to structured, "error" to null, "nextActions" to emptyList<Any>()))
        return result
    }

    /** 参数类报错的结构化明细（`.4`：机读用 details，别解析 message 文本）。 */
    private fun argumentDetailsOrNull(toolName: String, message: String?): JsonObject? {
        if (message == null || !looksLikeArgumentProblem(message)) return null
        val canonical = runCatching { McpToolCatalog.canonicalName(toolName) }.getOrDefault(toolName)
        val definition = toolDefinitionsByName[toolName] ?: toolDefinitionsByName[canonical] ?: return null
        val schema = definition.getAsJsonObject("inputSchema") ?: return null
        val properties = schema.getAsJsonObject("properties")?.keySet()?.toList().orEmpty()
        val required = schema.getAsJsonArray("required")?.map { it.asString }.orEmpty()
        if (properties.isEmpty()) return null
        val name = definition.get("name").asString
        return JsonObject().apply {
            addProperty("tool", name)
            add("availableParameters", JsonArray().apply { properties.forEach { add(it) } })
            add("required", JsonArray().apply { required.forEach { add(it) } })
            addProperty("schemaTool", "tool_schema")
            addProperty("schemaArguments", "{\"tool\":\"$name\"}")
        }
    }

    /** 名称 → 工具定义（首次使用时构建；定义在单个 build 内是静态的）。 */
    private val toolDefinitionsByName: Map<String, JsonObject> by lazy {
        listTools(null).getAsJsonArray("tools")
            .map { it.asJsonObject }
            .associateBy { it.get("name").asString }
    }

    /** 桥接失败（`:tools` 进程）同样补参数提示：那里抛的错不会经过 handle() 的 catch。 */
    private fun enrichBridgedFailure(toolName: String, payload: JsonObject) {
        if (payload.get("ok")?.asBoolean != false) return
        val error = payload.getAsJsonObject("error") ?: return
        val message = error.get("message")?.asString ?: return
        val enriched = withArgumentHint(toolName, message)
        if (enriched != message) error.addProperty("message", enriched)
        argumentDetailsOrNull(toolName, message)?.let { details ->
            if (!error.has("details")) error.add("details", details)
            payload.add("details", details)
        }
    }

    /** `.4` P0-1：参数类报错附「可用参数 / 必填 / schema 入口」，省掉穷举试错。 */
    private fun withArgumentHint(toolName: String, message: String): String {
        if (!looksLikeArgumentProblem(message)) return message
        val canonical = runCatching { McpToolCatalog.canonicalName(toolName) }.getOrDefault(toolName)
        val definition = toolDefinitionsByName[toolName] ?: toolDefinitionsByName[canonical] ?: return message
        val schema = definition.getAsJsonObject("inputSchema") ?: return message
        val properties = schema.getAsJsonObject("properties")?.keySet()?.toList().orEmpty()
        val required = schema.getAsJsonArray("required")?.map { it.asString }.orEmpty()
        if (properties.isEmpty()) return message
        return buildString {
            append(message)
            append("\n可用参数：").append(properties.joinToString("、"))
            if (required.isNotEmpty()) append("\n必填：").append(required.joinToString("、"))
            append("\n提示：tool_schema{\"tool\":\"").append(definition.get("name").asString)
            append("\"} 可看每个参数的说明与默认值。")
        }
    }

    private fun looksLikeArgumentProblem(message: String): Boolean =
        message.contains("参数") || message.contains("缺少") || message.contains("必须") ||
            message.contains("未知") || message.contains("required")

    /** `tool_schema{tool}`：单查一个工具的 schema（含别名解析与近邻建议）。 */
    private fun toolSchema(args: JsonObject): JsonObject {
        val requested = args.string("tool").trim()
        val canonical = runCatching { McpToolCatalog.canonicalName(requested) }.getOrDefault(requested)
        val definition = toolDefinitionsByName[requested] ?: toolDefinitionsByName[canonical]
        if (definition == null) {
            // task-33：近邻池含族前缀别名（dex_*/so_*）。
            val near = McpToolSuggest.suggest(requested, McpToolCatalog.names + McpToolCatalog.familyAliases.keys)
            throw ToolError(buildString {
                append("未知工具：").append(requested)
                if (near.isNotEmpty()) append("。最接近的：").append(near.joinToString("、"))
            })
        }
        val name = definition.get("name").asString
        val schema = definition.getAsJsonObject("inputSchema")
        val required = schema?.getAsJsonArray("required")?.map { it.asString }.orEmpty()
        return ToolkitIo.ok(JsonObject().apply {
            addProperty("tool", name)
            addProperty("canonical", McpToolCatalog.canonicalName(name))
            addProperty("description", definition.get("description")?.asString.orEmpty())
            add("aliases", JsonArray().apply {
                McpToolCatalog.aliases.filterValues { it == McpToolCatalog.canonicalName(name) }.keys.forEach { add(it) }
            })
            // task-33：族前缀别名是「只派发」的，不进 tools/list，但在 schema 查询里点名，便于发现。
            add("familyAliases", JsonArray().apply {
                McpToolCatalog.familyAliasesOf(McpToolCatalog.canonicalName(name)).forEach { add(it) }
            })
            if (schema != null) add("inputSchema", schema)
            add("required", JsonArray().apply { required.forEach { add(it) } })
            add("parameters", JsonArray().apply {
                schema?.getAsJsonObject("properties")?.entrySet()?.forEach { (paramName, spec) ->
                    add(JsonObject().apply {
                        addProperty("name", paramName)
                        addProperty("type", spec.asJsonObject.get("type")?.asString ?: "object")
                        spec.asJsonObject.get("description")?.asString?.let { addProperty("description", it) }
                        spec.asJsonObject.get("default")?.let { add("default", it) }
                        addProperty("required", paramName in required)
                    })
                }
            })
        })
    }

    /**
     * 失败结果：既给 content[0].text（人读），也给 structuredContent（机读）。
     * `.4` 总报告 P1-E：以前 ToolError/IllegalArgumentException 只回纯文本，标准客户端要再写一个解析器。
     */
    private fun toolFailure(message: String, code: String, argument: String? = null, details: JsonObject? = null): JsonObject {
        // `.4` P1-A：失败路径的 content[0].text 也必须是同一个 `{ok:false,error:{…}}` 信封，
        // 这样"把 text 当 JSON 解析"的客户端不会再撞到纯文本（人读的 message 就在 error.message 里）。
        val error = ToolkitIo.json("code" to code, "message" to message,
            "argument" to argument, "recoverable" to true, "retrySameArguments" to false)
        // `.4` v8 后续：把「可用参数/必填」做成结构化字段，机读不用解析 message。
        if (details != null) error.add("details", details)
        // task-38（①）：失败信封**始终**带 baseline（失败专用裁剪版：version + recovery/missing），
        // 不受「首个 tools/call」latch 影响；同时放进信封（客户端常只解析 structuredContent）。
        val failureBaseline = failureBaselineView()
        val envelope = ToolkitIo.json(
            "ok" to false, "data" to null, "error" to error, "nextActions" to emptyList<Any>())
        envelope.add("baseline", failureBaseline.deepCopy())
        return toolResult(envelope.toString(), true, errorCode = code, argument = argument).apply {
            add("structuredContent", envelope)
            if (details != null) add("details", details)
            add("baseline", failureBaseline)
        }
    }

    private fun toolResult(text: String, error: Boolean = false, errorCode: String? = null, argument: String? = null) = JsonObject().apply {
        add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) }) })
        if (error) addProperty("isError", true)
        // task-38（①）：失败信封带**失败专用裁剪版** baseline（IllegalState/Security/IOException 这几条 catch 走这里）。
        if (error && !has("baseline")) add("baseline", failureBaselineView())
        // 反馈 #8/#11：结构化错误码不能只在正文里；让调用方在不解析文本的情况下也能判定失败类型。
        if (errorCode != null) addProperty("errorCode", errorCode)
        if (argument != null) addProperty("argument", argument)
    }
    private fun rpcResult(id: JsonElement?, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id ?: JsonNull.INSTANCE)
        // task-38（①）兜底：任何 isError 结果都补失败裁剪版 baseline（一条覆盖所有失败路径）。
        add("result", withFailureBaseline(result))
    }
    private fun rpcError(id: JsonElement?, code: Int, message: String) = JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", id ?: JsonNull.INSTANCE); add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) }) }
    private fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: throw ToolError("缺少参数 $name")
    private fun JsonObject.stringOr(name: String, fallback: String) = runCatching { string(name) }.getOrDefault(fallback)
    private fun JsonObject.intOr(name: String, fallback: Int) = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: fallback
    private fun JsonObject.booleanOr(name: String, fallback: Boolean) = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: fallback
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class ToolError(message: String) : Exception(message)

    companion object {
        private val SUPPORTED_PROTOCOLS = setOf("2024-11-05", "2025-03-26", "2025-06-18")
        private val ACTIVE_STATES = setOf("QUEUED", "RUNNING", "STOPPING")
        private val TEXT_EXTENSIONS = setOf("js", "json", "txt", "md", "xml", "css", "html", "c", "h", "cc", "cpp", "hpp", "java", "kt", "smali", "gradle", "kts", "properties", "cmake", "sh")
        private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_SEARCH_FILES = 2000
        private const val MAX_SEARCH_HITS = 2000
        private const val MAX_SEARCH_FILE_BYTES = 512L * 1024
        private const val MAX_CURSORS = 16
        private const val SCAN_CURSOR_PREFIX = "scan1-"
        private const val MAX_SCAN_WALK_FILES = 200_000
        /** P2-10：工作区超过这个数就在 get_status 里直接给清理建议。 */
        private const val WORKSPACE_ALERT_THRESHOLD = 50
        private const val CURSOR_TTL_MS = 10 * 60 * 1000L
        private const val MAX_RUN_LOGS = 300
    }
}
