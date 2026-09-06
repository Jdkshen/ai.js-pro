package com.jdkshen.aijspro.mcp

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.model.script.ScriptFile
import com.stardust.autojs.core.console.ConsoleImpl
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
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
        @Volatile var errorType = ""
        @Volatile var errorMessage = ""
        @Volatile var errorStack = ""
        @Volatile var logStartId = -1
        @Volatile var logs: List<JsonObject>? = null
        @Volatile var execution: ScriptExecution? = null
    }
    private data class CursorPage(val items: List<JsonObject>, var offset: Int, val createdAt: Long)

    fun handle(request: JsonObject): JsonObject? {
        val method = request.get("method")?.asString.orEmpty()
        if (!request.has("id")) return null
        val id = request.get("id")
        var eventName = method
        return try {
            val result = when (method) {
                "initialize" -> initialize(request.getAsJsonObject("params"))
                "ping" -> JsonObject()
                "tools/list" -> listTools()
                "tools/call" -> {
                    val params = request.getAsJsonObject("params") ?: JsonObject()
                    eventName = params.stringOr("name", "tools/call")
                    call(params)
                }
                else -> return rpcError(id, -32601, "Method not found")
            }
            event("$eventName · 成功")
            rpcResult(id, result)
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
            record.status = "STOPPED"; record.finishedAt = System.currentTimeMillis()
            record.logs = consoleLogsSince(record.logStartId, MAX_RUN_LOGS)
            runCatching { record.execution?.engine?.forceStop() }
        }
    }

    fun toolNames(): List<String> = listTools().getAsJsonArray("tools")
        .map { it.asJsonObject.get("name").asString }

    private fun initialize(params: JsonObject?) = JsonObject().apply {
        val requested = params?.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
        addProperty("protocolVersion", if (requested in SUPPORTED_PROTOCOLS) requested else "2025-06-18")
        add("capabilities", JsonObject().apply { add("tools", JsonObject().apply { addProperty("listChanged", false) }) })
        add("serverInfo", JsonObject().apply { addProperty("name", "AI.js Pro Script MCP"); addProperty("version", "1.2.0") })
        addProperty("instructions", "默认只读。开启编辑授权后：创建/修改工作区，调用 workspace_request_apply 直接应用到真实脚本（免审批；会校验原文件未被外部改动并保留备份）。\n脚本错误处理：run_script 加 wait=true（或调用 wait_execution）会等待脚本结束并返回 status/result/error(type,message,stack)/logs；脚本未结束前可用 get_execution 轮询。")
    }

    private fun listTools() = JsonObject().apply {
        add("tools", JsonArray().apply {
            add(tool("get_status", "读取 MCP、脚本目录、任务和工作区状态"))
            add(tool("list_scripts", "列出脚本目录中的文件", objSchema("path", "offset", "limit")))
            add(tool("read_script", "分块读取脚本目录中的文本文件", objSchema("path", "offset", "maxBytes", required = arrayOf("path"))))
            add(tool("search_scripts", "递归搜索脚本文件名与内容，结果可继续分页", objSchema("query", "path", "limit", required = arrayOf("query"))))
            add(tool("continue_result", "使用 cursor 继续获取搜索或大结果的下一页", objSchema("cursor", "limit", required = arrayOf("cursor"))))
            add(tool("list_samples", "列出内置示例", objSchema("path", "offset", "limit")))
            add(tool("read_sample", "分块读取内置示例", objSchema("path", "offset", "maxBytes", required = arrayOf("path"))))
            add(tool("list_executions", "列出本 MCP 发起的脚本任务状态"))
            add(tool("get_execution", "读取任务状态、结果、异常或运行日志", objSchema("executionId", required = arrayOf("executionId"))))
            add(tool("wait_execution", "等待任务结束（最长 timeoutSeconds 秒）并返回最终状态、结果、异常与运行日志；脚本报错时用这个拿错误数据", objSchema("executionId", "timeoutSeconds", required = arrayOf("executionId"))))
            add(tool("read_apk_logs", "读取 AI.js Pro 全局控制台日志", objSchema("afterId", "limit")))
            add(tool("run_script", "运行脚本；需手机端开启运行授权；wait=true 时等待结束并返回结果、错误与运行日志", objSchema("path", "wait", "timeoutSeconds", required = arrayOf("path"))))
            add(tool("list_engine_api", "枚举指定引擎（quickjs/rhino）当前可用的全局 API 名称列表", objSchema("engine")))
            add(tool("probe_engine_api", "探测指定引擎中某个全局 API 的类型与成员（object/function 及 Object.keys）", objSchema("engine", "name", required = arrayOf("engine", "name"))))
            add(tool("stop_script", "停止本 MCP 发起的运行任务", objSchema("executionId", required = arrayOf("executionId"))))
            add(tool("workspace_open", "从真实脚本创建私有工作区快照", objSchema("path", required = arrayOf("path"))))
            add(tool("workspace_list", "列出工作区及待确认状态"))
            add(tool("workspace_read", "读取工作区文件", objSchema("workspaceId", "path", "offset", "maxBytes", required = arrayOf("workspaceId", "path"))))
            add(tool("workspace_write", "修改工作区，不会直接覆盖真实脚本；需写入授权", objSchema("workspaceId", "path", "content", required = arrayOf("workspaceId", "path", "content"))))
            add(tool("workspace_delete", "在工作区中标记删除文件；需写入授权", objSchema("workspaceId", "path", required = arrayOf("workspaceId", "path"))))
            add(tool("workspace_diff", "查看工作区与创建时快照的 Diff", objSchema("workspaceId", required = arrayOf("workspaceId"))))
            add(tool("workspace_request_apply", "将工作区修改直接应用到真实脚本（免审批；原文件被外部改动时会拒绝，应用前自动备份）", objSchema("workspaceId", required = arrayOf("workspaceId"))))
        })
    }

    private fun call(params: JsonObject): JsonObject {
        val name = params.string("name")
        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        return when (name) {
            "get_status" -> toolJson(JsonObject().apply {
                addProperty("scriptRoot", root.path); addProperty("writeAllowed", McpService.allowWrite)
                addProperty("executionAllowed", McpService.allowExecution)
                addProperty("activeMcpRuns", runs.values.count { it.status in ACTIVE_STATES })
                addProperty("retainedExecutions", runs.size); addProperty("workspaces", workspaces.list().size)
                addProperty("pendingWorkspaceApprovals", workspaces.list().count { it.pendingApproval })
            })
            "list_scripts" -> toolJson(listFiles(resolveScript(args.stringOr("path", "")), args.intOr("offset", 0), args.intOr("limit", 100)))
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
            "stop_script" -> stopScript(args)
            "workspace_open" -> toolJson(workspaceJson(workspaces.open(args.string("path"))))
            "workspace_list" -> toolJson(JsonObject().apply { add("items", JsonArray().apply { workspaces.list().forEach { add(workspaceJson(it)) } }) })
            "workspace_read" -> toolJson(readBytes(workspaces.read(args.string("workspaceId"), args.string("path")), args.intOr("offset", 0), args.intOr("maxBytes", 65536)))
            "workspace_write" -> {
                requireWrite(); val bytes = args.string("content").toByteArray(Charsets.UTF_8)
                toolJson(workspaceJson(workspaces.write(args.string("workspaceId"), args.string("path"), bytes)))
            }
            "workspace_delete" -> { requireWrite(); toolJson(workspaceJson(workspaces.delete(args.string("workspaceId"), args.string("path")))) }
            "workspace_diff" -> toolResult(workspaces.diff(args.string("workspaceId")))
            "workspace_request_apply" -> {
                requireWrite()
                val ws = workspaces.requestApply(args.string("workspaceId"))
                val applied = workspaces.applyConfirmed(ws.id)
                toolJson(workspaceJson(applied).apply { addProperty("message", "已直接应用到真实脚本（免审批），修改前已备份，可在历史页回退") })
            }
            else -> throw ToolError("未知工具：$name")
        }
    }

    private fun runScript(args: JsonObject): JsonObject {
        if (!McpService.allowExecution) throw ToolError("运行授权未开启")
        val file = resolveScript(args.string("path"))
        if (!file.isFile || !file.name.endsWith(".js", true)) throw ToolError("只能运行存在的 .js 文件")
        val key = "mcp-${nextRun.getAndIncrement()}"
        if (runs.size >= 100) runs.values.filter { it.status !in ACTIVE_STATES }.minByOrNull { it.createdAt }?.let { runs.remove(it.id) }
        val record = RunRecord(key, file.relativeTo(root).invariantSeparatorsPath, System.currentTimeMillis())
        runs[key] = record
        val listener = object : ScriptExecutionListener {
            override fun onStart(execution: ScriptExecution) {
                record.execution = execution; record.engineExecutionId = execution.id
                record.status = "RUNNING"; record.startedAt = System.currentTimeMillis()
                record.logStartId = currentConsoleMaxId()
                event("$key · 开始 · ${record.path}")
            }
            override fun onSuccess(execution: ScriptExecution, result: Any?) {
                if (record.status != "STOPPED") record.status = "SUCCEEDED"
                record.result = result?.toString().orEmpty().take(4096); record.finishedAt = System.currentTimeMillis()
                record.logs = consoleLogsSince(record.logStartId, MAX_RUN_LOGS)
                event("$key · ${record.status.lowercase()} · ${record.path}")
            }
            override fun onException(execution: ScriptExecution, error: Throwable) {
                if (record.status != "STOPPED") record.status = "FAILED"
                record.errorType = error.javaClass.name; record.errorMessage = error.localizedMessage.orEmpty().take(2000)
                record.errorStack = Log.getStackTraceString(error).take(16 * 1024); record.finishedAt = System.currentTimeMillis()
                record.logs = consoleLogsSince(record.logStartId, MAX_RUN_LOGS)
                event("$key · ${record.status.lowercase()} · ${record.errorMessage.take(100)}")
            }
        }
        try {
            val execution = AutoJs.getInstance().scriptEngineService.execute(
                ScriptFile(file).toSource(), listener, ExecutionConfig(workingDirectory = file.parent.orEmpty()))
            record.execution = execution; record.engineExecutionId = execution.id
        } catch (error: Exception) {
            record.status = "FAILED"; record.finishedAt = System.currentTimeMillis()
            record.errorType = error.javaClass.name; record.errorMessage = error.localizedMessage.orEmpty()
            record.errorStack = Log.getStackTraceString(error).take(16 * 1024)
            throw ToolError("脚本启动失败：${record.errorMessage}")
        }
        if (args.booleanOr("wait", false)) return toolJson(waitFor(record, args.intOr("timeoutSeconds", 60)))
        return toolJson(runJson(record))
    }

    private fun listEngineApi(args: JsonObject): JsonObject {
        val engine = args.stringOr("engine", "quickjs").lowercase()
        require(engine == "quickjs" || engine == "rhino") { "engine 必须是 quickjs 或 rhino" }
        val header = if (engine == "quickjs") "// @engine quickjs\n" else ""
        val source = header +
            "console.log('MCP_API_PROBE_RESULT=' + JSON.stringify(Object.keys(typeof globalThis !== 'undefined' ? globalThis : this)" +
            ".filter(function(n){return !n.startsWith('__')}).sort()))"
        val resultText = engineProbe(engine, source)
        val items = com.google.gson.JsonParser().parse(resultText).asJsonArray
        return JsonObject().apply { addProperty("engine", engine); addProperty("count", items.size()); add("items", items) }
    }

    private fun probeEngineApi(args: JsonObject): JsonObject {
        val engine = args.string("engine").lowercase()
        val name = args.string("name")
        require(engine == "quickjs" || engine == "rhino") { "engine 必须是 quickjs 或 rhino" }
        require(name.matches(Regex("[A-Za-z0-9_$.]{1,120}"))) { "name 只允许字母数字下划线 $ ." }
        val header = if (engine == "quickjs") "// @engine quickjs\n" else ""
        val source = header + "var x=" + name + ";" +
            "console.log('MCP_API_PROBE_RESULT=' + JSON.stringify({name:'" + name + "',type:typeof x," +
            "keys:(x&&typeof x==='object')?Object.keys(x).slice(0,200):[]}))"
        val resultText = engineProbe(engine, source)
        val obj = com.google.gson.JsonParser().parse(resultText).asJsonObject
        return JsonObject().apply {
            addProperty("engine", engine)
            addProperty("name", obj.get("name").asString)
            addProperty("type", obj.get("type").asString)
            add("keys", obj.getAsJsonArray("keys"))
        }
    }

    private fun engineProbe(engine: String, source: String, timeout: Int = 30): String {
        if (!McpService.allowExecution) throw ToolError("运行授权未开启")
        val file = File(root, "mcp_api_probe_tmp.js")
        file.writeText(source)
        try {
            val response = runScript(JsonObject().apply {
                addProperty("path", file.name)
                addProperty("wait", true)
                addProperty("timeoutSeconds", timeout)
            })
            val text = response.getAsJsonArray("content").first().asJsonObject.get("text").asString
            val run = com.google.gson.JsonParser().parse(text).asJsonObject
            if (run.get("status")?.asString == "FAILED") {
                val err = run.getAsJsonObject("error")
                throw ToolError("探针执行失败：" + err.get("message")?.asString.orEmpty())
            }
            val logs = run.getAsJsonArray("logs") ?: com.google.gson.JsonArray()
            val line = logs.firstOrNull { it.asJsonObject.get("content")?.asString.orEmpty().contains("MCP_API_PROBE_RESULT=") }
                ?: throw ToolError("探针无输出（未捕获到结果日志）")
            return line.asJsonObject.get("content").asString.substringAfter("MCP_API_PROBE_RESULT=")
        } finally {
            file.delete()
        }
    }

    private fun stopScript(args: JsonObject): JsonObject {
        if (!McpService.allowExecution) throw ToolError("运行授权未开启")
        val record = runs[args.string("executionId")] ?: throw ToolError("找不到此 MCP 任务")
        if (record.status !in ACTIVE_STATES) throw ToolError("任务已结束：${record.status}")
        record.status = "STOPPED"; record.finishedAt = System.currentTimeMillis()
        record.logs = consoleLogsSince(record.logStartId, MAX_RUN_LOGS)
        record.execution?.engine?.forceStop()
        event("${record.id} · stopped · ${record.path}")
        return toolJson(runJson(record))
    }

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
        addProperty("path", run.path); addProperty("status", run.status); addProperty("createdAt", run.createdAt)
        if (run.startedAt > 0) addProperty("startedAt", run.startedAt)
        if (run.finishedAt > 0) addProperty("finishedAt", run.finishedAt)
        if (run.result.isNotEmpty()) addProperty("result", run.result)
        if (run.errorType.isNotEmpty()) add("error", JsonObject().apply {
            addProperty("type", run.errorType); addProperty("message", run.errorMessage); addProperty("stack", run.errorStack)
        })
        if (includeLogs) add("logs", JsonArray().apply {
            val snapshot = run.logs ?: if (run.logStartId >= 0 && run.status in ACTIVE_STATES) consoleLogsSince(run.logStartId, MAX_RUN_LOGS) else emptyList()
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
    private fun currentConsoleMaxId(): Int = console()?.let { c -> synchronized(c.allLogs) { c.allLogs.lastOrNull()?.id ?: -1 } } ?: -1
    private fun consoleLogsSince(fromId: Int, limit: Int): List<JsonObject> {
        if (fromId < 0) return emptyList()
        val console = console() ?: return emptyList()
        val all = console.allLogs
        return synchronized(all) { all.filter { it.id > fromId }.take(limit.coerceIn(1, MAX_RUN_LOGS)) }.map { entry -> JsonObject().apply {
            addProperty("id", entry.id); addProperty("level", logLevel(entry.level))
            addProperty("content", entry.content?.toString().orEmpty().take(16 * 1024))
        } }
    }

    private fun searchScripts(query: String, path: String, limit: Int): JsonObject {
        val needle = query.trim()
        if (needle.isEmpty()) throw ToolError("搜索词不能为空")
        if (needle.length > 200) throw ToolError("搜索词过长")
        val start = resolveScript(path)
        if (!start.isDirectory) throw ToolError("搜索目录不存在")
        val hits = mutableListOf<JsonObject>()
        val candidates = start.walkTopDown().filter { it.isFile && it.extension.lowercase() in TEXT_EXTENSIONS }
            .take(MAX_SEARCH_FILES + 1).toList()
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

    private fun resolveScript(path: String): File {
        if (path.indexOf('\u0000') >= 0) throw ToolError("路径无效")
        val file = File(root, path).canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) throw ToolError("路径超出脚本目录")
        return file
    }
    private fun listFiles(dir: File, offset: Int, limit: Int): JsonObject {
        if (!dir.isDirectory) throw ToolError("目录不存在")
        val entries = dir.listFiles().orEmpty().sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        return page(entries, offset, limit) { file -> JsonObject().apply {
            addProperty("path", file.relativeTo(root).invariantSeparatorsPath); addProperty("name", file.name)
            addProperty("directory", file.isDirectory); addProperty("size", if (file.isFile) file.length() else 0)
            addProperty("modifiedAt", file.lastModified())
        } }
    }
    private fun listAssets(path: String, offset: Int, limit: Int): JsonObject {
        val safe = safeAsset(path); val names = context.assets.list(safe)?.sorted().orEmpty()
        return page(names, offset, limit) { name -> val child = "$safe/$name"; JsonObject().apply {
            addProperty("path", child); addProperty("name", name); addProperty("directory", !context.assets.list(child).isNullOrEmpty())
        } }
    }
    private fun readTextFile(file: File): ByteArray {
        if (!file.isFile || file.extension.lowercase() !in TEXT_EXTENSIONS) throw ToolError("仅支持文本文件")
        if (file.length() > MAX_READ_FILE_BYTES) throw ToolError("文件过大，请在工作区外分析")
        return file.readBytes()
    }
    private fun readAsset(path: String, offset: Int, max: Int): JsonObject {
        val safe = safeAsset(path)
        val bytes = try { context.assets.open(safe).use { it.readBytes() } } catch (_: IOException) { throw ToolError("示例不存在") }
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
    private fun safeAsset(path: String): String {
        val clean = path.replace('\\', '/').trim('/')
        if (clean != "sample" && !clean.startsWith("sample/")) throw ToolError("只能访问 sample 目录")
        if (clean.split('/').any { it == "." || it == ".." || it.isEmpty() }) throw ToolError("路径无效")
        return clean
    }
    private fun workspaceJson(ws: McpWorkspaceStore.Workspace) = JsonObject().apply {
        addProperty("workspaceId", ws.id); addProperty("targetPath", ws.targetPath); addProperty("createdAt", ws.createdAt)
        addProperty("updatedAt", ws.updatedAt); addProperty("state", ws.state); addProperty("pendingApproval", ws.pendingApproval)
        addProperty("changedFiles", ws.changedFiles)
    }
    private fun requireWrite() { if (!McpService.allowWrite) throw ToolError("写入授权未开启") }
    private fun logLevel(level: Int) = when (level) { Log.VERBOSE -> "VERBOSE"; Log.DEBUG -> "DEBUG"; Log.INFO -> "INFO"; Log.WARN -> "WARN"; Log.ERROR -> "ERROR"; Log.ASSERT -> "ASSERT"; else -> level.toString() }
    private fun <T> page(all: List<T>, offset: Int, limit: Int, convert: (T) -> JsonElement) = JsonObject().apply {
        val start = offset.coerceIn(0, all.size); val size = limit.coerceIn(1, 200); val end = (start + size).coerceAtMost(all.size)
        add("items", JsonArray().apply { all.subList(start, end).forEach { add(convert(it)) } })
        addProperty("nextOffset", end); addProperty("total", all.size); addProperty("hasMore", end < all.size)
    }
    private fun tool(name: String, description: String, schema: JsonObject = objSchema()) = JsonObject().apply { addProperty("name", name); addProperty("description", description); add("inputSchema", schema) }
    private fun objSchema(vararg names: String, required: Array<String> = emptyArray()) = JsonObject().apply {
        addProperty("type", "object"); add("properties", JsonObject().apply { names.forEach { n -> add(n, JsonObject().apply {
            addProperty("type", if (n in INTEGER_PARAMS) "integer" else "string")
        }) } }); if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it) } }); addProperty("additionalProperties", false)
    }
    private fun toolJson(json: JsonObject) = toolResult(json.toString())
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
        private val INTEGER_PARAMS = setOf("offset", "limit", "maxBytes", "afterId", "timeoutSeconds")
        private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_SEARCH_FILES = 2000
        private const val MAX_SEARCH_HITS = 2000
        private const val MAX_SEARCH_FILE_BYTES = 512L * 1024
        private const val MAX_CURSORS = 16
        private const val CURSOR_TTL_MS = 10 * 60 * 1000L
        private const val MAX_RUN_LOGS = 300
    }
}
