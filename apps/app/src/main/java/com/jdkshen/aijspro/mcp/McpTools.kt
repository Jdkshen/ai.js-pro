package com.jdkshen.aijspro.mcp

import android.content.Context
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

internal class McpTools(private val context: Context, private val event: (String) -> Unit) {
    private val scriptsRoot = File(Pref.getScriptDirPath()).canonicalFile
    private val root = File(scriptsRoot, McpSettings.operationPath(context)).canonicalFile.also {
        if (it != scriptsRoot && !it.path.startsWith(scriptsRoot.path + File.separator)) error("操作目录无效")
    }
    private val workspaces = McpWorkspaceStore(context, root)
    private val runs = ConcurrentHashMap<String, RunRecord>()
    private val nextRun = AtomicInteger(1)
    private val cursors = LinkedHashMap<String, CursorPage>()

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
                    listTools()
                }
                "tools/call" -> {
                    val invocation = McpRpcValidation.parseToolCall(params)
                    eventName = invocation.requestedName
                    call(invocation)
                }
                else -> error("unreachable supported method")
            }
            event("$eventName · 成功")
            rpcResult(id, result)
        } catch (e: McpInvalidParamsException) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            rpcError(id, -32602, e.message ?: "Invalid params")
        } catch (e: ToolError) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            if (method == "tools/call") rpcResult(id, toolResult(e.message ?: "工具调用失败", true))
            else rpcError(id, -32602, e.message ?: "Invalid params")
        } catch (e: IllegalArgumentException) {
            event("$eventName · 失败 · ${e.message.orEmpty().take(100)}")
            if (method == "tools/call") rpcResult(id, toolResult(e.message ?: "参数或工作区状态无效", true))
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
        runs.values.filter { it.status in ACTIVE_STATES }.forEach { record ->
            record.stopRequested = true; record.stopReason = "service_stopped"
            record.status = "STOPPED"; record.finishedAt = System.currentTimeMillis()
            record.logs = runLogs(record)
            runCatching { record.execution?.engine?.forceStop() }
        }
    }

    fun toolNames(): List<String> = listTools().getAsJsonArray("tools")
        .map { it.asJsonObject.get("name").asString }

    private fun initialize(params: JsonObject?) = JsonObject().apply {
        val requested = params?.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
        addProperty("protocolVersion", if (requested in SUPPORTED_PROTOCOLS) requested else "2025-06-18")
        add("capabilities", JsonObject().apply { add("tools", JsonObject().apply { addProperty("listChanged", false) }) })
        add("serverInfo", JsonObject().apply { addProperty("name", "AI.js Pro Script MCP"); addProperty("version", "1.3.0") })
        addProperty("instructions", "MCP 服务开启即拥有读写与运行权限（局域网访问仍需 Bearer token）。客户端可创建/修改私有工作区，并调用 workspace_request_apply 直接应用到真实脚本；应用时会校验原文件未被外部改动并保留备份，可在手机历史页回退。\n脚本错误处理：run_script 加 wait=true（或调用 wait_execution）会等待脚本结束并返回 status/result/error(type,message,stack)/logs；脚本未结束前可用 get_execution 轮询。")
    }

    private fun listTools() = JsonObject().apply {
        add("tools", JsonArray().apply {
            add(tool("get_status", "读取 MCP、脚本目录、任务和工作区状态"))
            add(tool("list_scripts", "列出脚本目录中的文件；recursive=true 时递归子目录（与 search_scripts 同一数据源）", objSchema("path", "offset", "limit", "recursive")))
            add(tool("read_script", "分块读取脚本目录中的文本文件", objSchema("path", "offset", "maxBytes", required = arrayOf("path"))))
            add(tool("search_scripts", "递归搜索脚本文件名与内容，结果可继续分页", objSchema("query", "path", "limit", required = arrayOf("query"))))
            add(tool("continue_result", "使用 cursor 继续获取搜索或大结果的下一页", objSchema("cursor", "limit", required = arrayOf("cursor"))))
            add(tool("list_samples", "列出内置示例", objSchema("path", "offset", "limit")))
            add(tool("read_sample", "分块读取内置示例", objSchema("path", "offset", "maxBytes", required = arrayOf("path"))))
            add(tool("list_executions", "列出本 MCP 发起的脚本任务状态"))
            add(tool("get_execution", "读取任务状态、结果、异常或运行日志", objSchema("executionId", required = arrayOf("executionId"))))
            add(tool("wait_execution", "等待任务结束（最长 timeoutSeconds 秒）并返回最终状态、结果、异常与运行日志；脚本报错时用这个拿错误数据", objSchema("executionId", "timeoutSeconds", required = arrayOf("executionId"))))
            add(tool("read_apk_logs", "读取 AI.js Pro 全局控制台日志", objSchema("afterId", "limit")))
            add(tool("run_script", "运行现有文件或内存源码（path/file/source 三选一）；支持双引擎、工作目录、config、等待及任务专属日志", McpToolSchema.runSchema()))
            add(tool("list_engine_api", "枚举指定引擎（quickjs/rhino）当前可用的全局 API 名称列表", objSchema("engine")))
            add(tool("probe_engine_api", "探测指定引擎中某个全局 API 的类型与成员（object/function 及 Object.keys）", objSchema("engine", "name", required = arrayOf("engine", "name"))))
            add(tool("engine_api_diff", "对比 Rhino 与 QuickJS 的全局 API 差异（common/onlyRhino/onlyQuickJs），用于迁移对照"))
            add(tool("stop_script", "停止本 MCP 发起的运行任务", objSchema("executionId", required = arrayOf("executionId"))))
            add(tool("workspace_open", "从真实脚本创建（或复用）私有工作区快照；create=true 为幂等：不存在则创建（自动建父目录）、已存在则直接打开；子目录文件请传完整相对路径", objSchema("path", "create", required = arrayOf("path"))))
            add(tool("workspace_list", "列出工作区及待确认状态"))
            add(tool("workspace_read", "读取工作区文件", objSchema("workspaceId", "path", "offset", "maxBytes", required = arrayOf("workspaceId", "path"))))
            add(tool("workspace_write", "修改工作区，不会直接覆盖真实脚本", objSchema("workspaceId", "path", "content", required = arrayOf("workspaceId", "path", "content"))))
            add(tool("workspace_delete", "在工作区中标记删除文件", objSchema("workspaceId", "path", required = arrayOf("workspaceId", "path"))))
            add(tool("workspace_diff", "查看工作区与创建时快照的 Diff", objSchema("workspaceId", required = arrayOf("workspaceId"))))
            add(tool("workspace_request_apply", "直接应用工作区到真实脚本；校验原文件、自动备份并支持历史回退（工具名为兼容保留）", objSchema("workspaceId", required = arrayOf("workspaceId"))))
            add(tool("workspace_mkdir", "在真实脚本目录下创建目录（含多级）", objSchema("path", required = arrayOf("path"))))
            add(tool("workspace_cancel", "取消待确认的应用请求，把工作区恢复为可编辑状态", objSchema("workspaceId", required = arrayOf("workspaceId"))))
            add(tool("workspace_cleanup", "清理无用的工作区私有副本：默认只删无修改的 OPEN 与已回退的；applied=true 时连已应用的也删（默认保留 keepAppliedHours=24 小时以便回退）", objSchema("applied", "olderThanHours", "keepAppliedHours")))
            add(tool("get_ui_layout", "读取当前应用窗口或指定窗口的 XML、节点属性和空树诊断；需无障碍服务", objSchema("windowId", "maxNodes", "maxDepth", "timeoutMillis")))
            add(tool("capture_screen", "返回原图或缩小截图的 MCP 图像内容；支持 png/jpg、quality、dataUri；需 Android 11+ 无障碍截图权限", objSchema("format", "quality", "dataUri", "maxWidth", "timeoutMillis")))
            add(tool("check_syntax", "按 Rhino/QuickJS 只编译检查源码，不执行任何脚本；返回 valid、错误行列和转换说明", objSchema("code", "engine", "name", required = arrayOf("code"))))
            add(tool("learn_autojs_skill", "读取 AI.js Pro MCP 使用说明、双引擎规则、权限和实际支持范围"))
            add(tool("stop_all_scripts", "停止应用全部脚本（包括非 MCP 脚本）；普通任务应使用 stop_script"))
            add(tool("get_mcp_log", "读取脚本日志尾部或 MCP 服务请求记录；executionId 可限制为指定任务；maxChars 默认 20000，最大 200000", objSchema("maxChars", "executionId", "channel")))
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
    }

    private fun call(invocation: McpToolCall): JsonObject {
        val name = invocation.canonicalName
        val args = invocation.arguments
        return when (name) {
            "get_status" -> toolJson(JsonObject().apply {
                addProperty("scriptRoot", root.path); addProperty("writeAllowed", McpService.allowWrite)
                addProperty("executionAllowed", McpService.allowExecution)
                addProperty("activeMcpRuns", runs.values.count { it.status in ACTIVE_STATES })
                addProperty("retainedExecutions", runs.size); addProperty("workspaces", workspaces.list().size)
                addProperty("pendingWorkspaceApprovals", workspaces.list().count { it.pendingApproval })
            })
            "list_scripts" -> toolJson(listFiles(resolveScript(args.stringOr("path", "")), args.intOr("offset", 0), args.intOr("limit", 100), args.booleanOr("recursive", false)))
            "read_script" -> toolJson(readBytes(readTextFile(resolveScript(args.string("path"))), args.intOr("offset", 0), args.intOr("maxBytes", 65536)))
            "search_scripts" -> toolJson(searchScripts(args.string("query"), args.stringOr("path", ""), args.intOr("limit", 50)))
            "continue_result" -> toolJson(continueResult(args.string("cursor"), args.intOr("limit", 50)))
            "list_samples" -> toolJson(listAssets(args.stringOr("path", "sample"), args.intOr("offset", 0), args.intOr("limit", 100)))
            "read_sample" -> toolJson(readAsset(args.string("path"), args.intOr("offset", 0), args.intOr("maxBytes", 65536)))
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
            "list_engine_api" -> listEngineApi(args)
            "probe_engine_api" -> probeEngineApi(args)
            "engine_api_diff" -> engineApiDiff()
            "stop_script" -> stopScript(args)
            "get_ui_layout" -> {
                requireExecution()
                toolJson(McpDeviceTools.layout(if (args.has("windowId")) args.intOr("windowId", -1) else null,
                    args.intOr("maxNodes", 3000), args.intOr("maxDepth", 64), args.intOr("timeoutMillis", 3000)))
            }
            "capture_screen" -> {
                requireExecution()
                McpDeviceTools.screenshot(args.stringOr("format", "png").lowercase(),
                    args.intOr("quality", 100), args.booleanOr("dataUri", false),
                    args.intOr("maxWidth", 0), args.intOr("timeoutMillis", 3000))
            }
            "check_syntax" -> {
                val code = args.string("code")
                val engine = args.stringOr("engine", "").lowercase().ifEmpty {
                    if (JavaScriptSource.requestsQuickJs(code)) "quickjs" else "rhino"
                }
                requireRhinoCompat(engine)
                toolJson(McpSyntaxChecker.check(code, engine, args.stringOr("name", "mcp-syntax.js")))
            }
            "learn_autojs_skill" -> toolResult(context.assets.open("mcp/AIjs-MCP-Guide.md").bufferedReader(Charsets.UTF_8).use { it.readText() })
            "stop_all_scripts" -> stopAllScripts()
            "get_mcp_log" -> toolJson(getMcpLog(args))
            "workspace_open" -> toolJson(workspaceJson(workspaces.open(args.string("path"), args.booleanOr("create", false))))
            "workspace_list" -> toolJson(JsonObject().apply { add("items", JsonArray().apply { workspaces.list().forEach { add(workspaceJson(it)) } }) })
            "workspace_read" -> toolJson(readBytes(workspaces.read(args.string("workspaceId"), args.string("path")), args.intOr("offset", 0), args.intOr("maxBytes", 65536)))
            "workspace_write" -> {
                requireWrite(); val bytes = args.string("content").toByteArray(Charsets.UTF_8)
                toolJson(workspaceJson(workspaces.write(args.string("workspaceId"), args.string("path"), bytes)).apply {
                    addProperty("needsApply", true)
                    addProperty("hint", "只写进了私有工作区；真实脚本需要调用 workspace_request_apply 才会更新（目录与文件在 apply 时一起创建）")
                })
            }
            "workspace_delete" -> { requireWrite(); toolJson(workspaceJson(workspaces.delete(args.string("workspaceId"), args.string("path"))).apply {
                addProperty("needsApply", true)
                addProperty("hint", "删除只在私有工作区生效；调用 workspace_request_apply 后才会删真实文件")
            }) }
            "workspace_diff" -> toolResult(workspaces.diff(args.string("workspaceId")))
            "workspace_request_apply" -> {
                requireWrite()
                val applied = workspaces.applyAuthorized(args.string("workspaceId"))
                toolJson(workspaceJson(applied).apply {
                    addProperty("message", "已应用到真实脚本；修改前已校验并备份，可在手机历史页回退")
                })
            }
            "workspace_mkdir" -> {
                requireWrite()
                val created = workspaces.makeDirectory(args.string("path"))
                toolJson(JsonObject().apply { addProperty("path", created); addProperty("created", true) })
            }
            "workspace_cancel" -> toolJson(workspaceJson(workspaces.cancel(args.string("workspaceId"))))
            "workspace_cleanup" -> toolJson(JsonObject().apply {
                val removed = workspaces.purge(
                    applied = args.booleanOr("applied", false),
                    olderThanHours = args.intOr("olderThanHours", 0),
                    keepAppliedHours = args.intOr("keepAppliedHours", 24)
                )
                addProperty("removed", removed)
                addProperty("remaining", workspaces.list().size)
            })
            else -> throw McpInvalidParamsException("未知工具：$name")
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
                "// @engine ${input.engine}\n" + input.source.removePrefix("\uFEFF")
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
            require(requestedEngine == "quickjs" || requestedEngine == "rhino") { "engine 必须是 quickjs 或 rhino" }
            requireRhinoCompat(requestedEngine)
            val directive = if (requestedEngine == "quickjs") "// @engine quickjs\n" else "// @engine rhino\n"
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
        if (runs.size >= 100) runs.values.filter { it.status !in ACTIVE_STATES }.minByOrNull { it.createdAt }?.let { runs.remove(it.id) }
        val record = RunRecord(key, path, System.currentTimeMillis())
        runs[key] = record
        val listener = object : ScriptExecutionListener {
            override fun onStart(execution: ScriptExecution) {
                record.execution = execution; record.engineExecutionId = execution.id
                record.engine = if (source.engineName == JavaScriptSource.ENGINE_QUICKJS) "quickjs" else "rhino"
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
                    if (record.status != "STOPPED") record.status = "SUCCEEDED"
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
                    record.errorType = error.javaClass.name
                    record.errorMessage = error.localizedMessage.orEmpty().take(2000)
                    record.errorStack = Log.getStackTraceString(error).take(16 * 1024)
                    record.finishedAt = System.currentTimeMillis()
                    record.logs = runLogs(record, execution)
                    if (record.status != "STOPPED") record.status = "FAILED"
                    event("$key · ${record.status.lowercase()} · ${record.errorMessage.take(100)}")
                } finally { cleanup() }
            }
        }
        try {
            val execution = AutoJs.getInstance().scriptEngineService.execute(
                source, listener, config)
            record.execution = execution; record.engineExecutionId = execution.id
        } catch (error: Exception) {
            cleanup()
            record.status = "FAILED"; record.finishedAt = System.currentTimeMillis()
            record.errorType = error.javaClass.name; record.errorMessage = error.localizedMessage.orEmpty()
            record.errorStack = Log.getStackTraceString(error).take(16 * 1024)
            throw ToolError("脚本启动失败：${record.errorMessage}")
        }
        if (wait) return toolJson(waitFor(record, timeoutSeconds))
        return toolJson(runJson(record))
    }

    private fun listEngineApi(args: JsonObject): JsonObject {
        val engine = args.stringOr("engine", "quickjs").lowercase()
        require(engine == "quickjs" || engine == "rhino") { "engine 必须是 quickjs 或 rhino" }
        requireRhinoCompat(engine)
        val source =
            "console.log('MCP_API_PROBE_RESULT=' + JSON.stringify(Object.keys(typeof globalThis !== 'undefined' ? globalThis : this)" +
            ".filter(function(n){return !n.startsWith('__')}).sort()))"
        val resultText = engineProbe(engine, source)
        val items = com.google.gson.JsonParser().parse(resultText).asJsonArray
        // 必须走 toolJson 包成标准 MCP tool result envelope，
        // 否则官方 Kotlin SDK 会因为顶层是 {engine,count,items} 而报
        // "Cannot determine RequestResult type from JSON"。
        return toolJson(JsonObject().apply {
            addProperty("engine", engine); addProperty("count", items.size()); add("items", items)
        })
    }

    private fun probeEngineApi(args: JsonObject): JsonObject {
        val engine = args.string("engine").lowercase()
        val name = args.string("name")
        require(engine == "quickjs" || engine == "rhino") { "engine 必须是 quickjs 或 rhino" }
        requireRhinoCompat(engine)
        require(name.matches(Regex("[A-Za-z0-9_$.]{1,120}"))) { "name 只允许字母数字下划线 $ ." }
        val source = "var x=" + name + ";" +
            "console.log('MCP_API_PROBE_RESULT=' + JSON.stringify({name:'" + name + "',type:typeof x," +
            "keys:(x&&(typeof x==='object'||typeof x==='function'))?Object.keys(x).slice(0,200):[]}))"
        val resultText = engineProbe(engine, source)
        val obj = com.google.gson.JsonParser().parse(resultText).asJsonObject
        return toolJson(JsonObject().apply {
            addProperty("engine", engine)
            addProperty("name", obj.get("name").asString)
            addProperty("type", obj.get("type").asString)
            add("keys", obj.getAsJsonArray("keys"))
        })
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
        return toolJson(JsonObject().apply {
            addProperty("quickjsCount", quickSet.size)
            addProperty("rhinoAvailable", rhino != null)
            addProperty("rhinoCount", rhinoSet.size)
            add("onlyQuickJs", JsonArray().apply { (quickSet - rhinoSet).sorted().forEach { name -> add(name) } })
            add("onlyRhino", JsonArray().apply { (rhinoSet - quickSet).sorted().forEach { name -> add(name) } })
            addProperty("commonCount", quickSet.intersect(rhinoSet).size)
        })
    }

    private fun engineApiNames(engine: String): List<String> {
        val source =
            "console.log('MCP_API_PROBE_RESULT=' + JSON.stringify(Object.keys(typeof globalThis !== 'undefined' ? globalThis : this)" +
            ".filter(function(n){return !n.startsWith('__')}).sort()))"
        val items = com.google.gson.JsonParser().parse(engineProbe(engine, source)).asJsonArray
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

    private fun requireRhinoCompat(engine: String) {
        if (engine == "rhino" && !com.jdkshen.aijspro.BuildConfig.RHINO_COMPAT) {
            throw ToolError("当前为 lite 版本，未安装 Rhino 执行引擎；请使用 compat 版本")
        }
    }

    private fun stopScript(args: JsonObject): JsonObject {
        val record = runs[args.string("executionId")] ?: throw ToolError("找不到此 MCP 任务")
        if (record.status !in ACTIVE_STATES) throw ToolError("任务已结束：${record.status}")
        record.stopRequested = true; record.stopReason = "user_stopped"
        record.status = "STOPPED"; record.finishedAt = System.currentTimeMillis()
        record.errorType = ""; record.errorMessage = ""; record.errorStack = ""
        record.logs = runLogs(record)
        record.execution?.engine?.forceStop()
        event("${record.id} · stopped · ${record.path}")
        return toolJson(runJson(record))
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
        if (run.engine.isNotEmpty()) addProperty("engine", run.engine)
        addProperty("path", run.path); addProperty("status", run.status); addProperty("createdAt", run.createdAt)
        if (run.startedAt > 0) addProperty("startedAt", run.startedAt)
        if (run.finishedAt > 0) addProperty("finishedAt", run.finishedAt)
        if (run.result.isNotEmpty()) addProperty("result", run.result)
        if (run.status == "STOPPED") addProperty("stopReason", run.stopReason.ifEmpty { "user_stopped" })
        if (run.errorType.isNotEmpty()) add("error", JsonObject().apply {
            addProperty("type", run.errorType); addProperty("message", run.errorMessage); addProperty("stack", run.errorStack)
        })
        if (includeLogs) add("logs", JsonArray().apply {
            val snapshot = run.logs ?: runLogs(run)
            snapshot.forEach { add(it) }
        })
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

    private fun stopAllScripts(): JsonObject {
        requireExecution()
        runs.values.filter { it.status in ACTIVE_STATES }.forEach { record ->
            record.stopRequested = true; record.stopReason = "all_scripts_stopped"
            record.logs = runLogs(record); record.finishedAt = System.currentTimeMillis()
            record.status = "STOPPED"
        }
        val stopped = AutoJs.getInstance().scriptEngineService.stopAll()
        event("stop_all_scripts · 停止全部脚本 · $stopped")
        return toolJson(JsonObject().apply { addProperty("success", true); addProperty("stopped", stopped); addProperty("scope", "all_scripts") })
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

    private fun searchScripts(query: String, path: String, limit: Int): JsonObject {
        val needle = query.trim()
        if (needle.isEmpty()) throw ToolError("搜索词不能为空")
        if (needle.length > 200) throw ToolError("搜索词过长")
        val start = resolveScript(path)
        if (!start.isDirectory) throw ToolError("搜索目录不存在")
        val hits = mutableListOf<JsonObject>()
        // 枚举统一走 ScriptRepository.walkFiles；过滤在计数之前（与 filter{take(n)} 同语义）。
        val candidates = ScriptRepository.walkFiles(start, MAX_SEARCH_FILES + 1) { _, name ->
            name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
        }.map { File(it.path) }
        val truncatedFiles = candidates.size > MAX_SEARCH_FILES
        var scanned = 0
        candidates.take(MAX_SEARCH_FILES).forEach { file ->
            if (hits.size >= MAX_SEARCH_HITS) return@forEach
            scanned++
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (file.name.contains(needle, ignoreCase = true)) hits += JsonObject().apply {
                addProperty("path", relative); addProperty("kind", "name"); addProperty("line", 0); addProperty("preview", file.name)
            }
            if (file.length() <= MAX_SEARCH_FILE_BYTES) {
                runCatching { file.useLines(Charsets.UTF_8) { lines -> lines.forEachIndexed { index, line ->
                    if (hits.size < MAX_SEARCH_HITS && line.contains(needle, ignoreCase = true)) hits += JsonObject().apply {
                        addProperty("path", relative); addProperty("kind", "content"); addProperty("line", index + 1)
                        addProperty("preview", line.trim().take(240))
                    }
                } } }
            }
        }
        return firstPage(hits, limit).apply { addProperty("scannedFiles", scanned); addProperty("truncatedScan", truncatedFiles || hits.size >= MAX_SEARCH_HITS) }
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
        val cursor = cursors[id] ?: throw ToolError("游标不存在或已过期")
        val size = limit.coerceIn(1, 200); val start = cursor.offset; val end = (start + size).coerceAtMost(cursor.items.size)
        cursor.offset = end
        return JsonObject().apply {
            add("items", JsonArray().apply { cursor.items.subList(start, end).forEach { add(it) } })
            addProperty("nextOffset", end); addProperty("total", cursor.items.size); addProperty("hasMore", end < cursor.items.size)
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
    private fun workspaceJson(ws: McpWorkspaceStore.Workspace) = JsonObject().apply {
        addProperty("workspaceId", ws.id); addProperty("targetPath", ws.targetPath); addProperty("createdAt", ws.createdAt)
        addProperty("updatedAt", ws.updatedAt); addProperty("state", ws.state); addProperty("pendingApproval", ws.pendingApproval)
        addProperty("changedFiles", ws.changedFiles)
    }
    /** task-118：MCP 服务开启即授权读写/运行。 */
    private fun requireWrite() { /* no-op：不再要求额外写入授权 */ }
    private fun logLevel(level: Int) = when (level) { Log.VERBOSE -> "VERBOSE"; Log.DEBUG -> "DEBUG"; Log.INFO -> "INFO"; Log.WARN -> "WARN"; Log.ERROR -> "ERROR"; Log.ASSERT -> "ASSERT"; else -> level.toString() }
    private fun <T> page(all: List<T>, offset: Int, limit: Int, convert: (T) -> JsonElement) = JsonObject().apply {
        val start = offset.coerceIn(0, all.size); val size = limit.coerceIn(1, 200); val end = (start + size).coerceAtMost(all.size)
        add("items", JsonArray().apply { all.subList(start, end).forEach { add(convert(it)) } })
        addProperty("nextOffset", end); addProperty("total", all.size); addProperty("hasMore", end < all.size)
    }
    private fun tool(name: String, description: String, schema: JsonObject = objSchema()) = JsonObject().apply { addProperty("name", name); addProperty("description", description); add("inputSchema", schema) }
    private fun objSchema(vararg names: String, required: Array<String> = emptyArray()) =
        McpToolSchema.objectSchema(*names, required = required)
    private fun toolJson(json: JsonObject) = toolResult(json.toString()).apply { add("structuredContent", json) }
    private fun toolResult(text: String, error: Boolean = false) = JsonObject().apply {
        add("content", JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) }) })
        if (error) addProperty("isError", true)
    }
    private fun rpcResult(id: JsonElement?, result: JsonObject) = JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", id ?: JsonNull.INSTANCE); add("result", result) }
    private fun rpcError(id: JsonElement?, code: Int, message: String) = JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", id ?: JsonNull.INSTANCE); add("error", JsonObject().apply { addProperty("code", code); addProperty("message", message) }) }
    private fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: throw ToolError("缺少参数 $name")
    private fun JsonObject.stringOr(name: String, fallback: String) = runCatching { string(name) }.getOrDefault(fallback)
    private fun JsonObject.intOr(name: String, fallback: Int) = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: fallback
    private fun JsonObject.booleanOr(name: String, fallback: Boolean) = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: fallback
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class ToolError(message: String) : Exception(message)

    companion object {
        private val SUPPORTED_PROTOCOLS = setOf("2024-11-05", "2025-03-26", "2025-06-18")
        private val ACTIVE_STATES = setOf("QUEUED", "RUNNING")
        private val TEXT_EXTENSIONS = setOf("js", "json", "txt", "md", "xml", "css", "html")
        private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_SEARCH_FILES = 2000
        private const val MAX_SEARCH_HITS = 2000
        private const val MAX_SEARCH_FILE_BYTES = 512L * 1024
        private const val MAX_CURSORS = 16
        private const val CURSOR_TTL_MS = 10 * 60 * 1000L
        private const val MAX_RUN_LOGS = 300
    }
}
