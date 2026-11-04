package com.jdkshen.aijspro.mcp

import android.app.ActivityManager
import android.content.Context
import com.google.gson.JsonObject
import java.io.File

/**
 * §9 应用启动/日志/退出原因关联采集的 MCP 入口。
 *
 * 纯逻辑在 [McpAppTrace]（可单测）；这里只做两件事：
 *  1. 接一个**普通应用 UID 能拿到**的真实数据源（同 UID 进程列表 + 本应用可见的 logcat 环形缓冲）；
 *  2. 把结果交给 McpAppTrace 做过滤与退出原因分类。
 *
 * 诚实边界：普通应用读不到其它应用的完整日志；拿不到证据时 McpAppTrace 会返回 unknown + uncertainty，
 * 这里不会用猜测填满字段，也不改任何系统设置。
 */
internal object McpAppTraceTools {
    val names = setOf(
        "app_exit_info",
        "app_trace_start",
        "app_trace_read",
        "app_trace_stop",
        "app_trace_list",
        "app_launch_trace"
    )

    private val traces = HashMap<String, McpAppTrace>()

    @Synchronized
    private fun trace(context: Context): McpAppTrace {
        val key = context.packageName
        return traces.getOrPut(key) { McpAppTrace(source = AndroidSource(context.applicationContext)) }
    }

    fun call(context: Context, name: String, args: JsonObject): JsonObject = when (name) {
        "app_exit_info" -> trace(context).exitInfo(
            args.tString("packageName"),
            args.get("pid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt,
            args.tBool("withLogs", true)
        )
        "app_trace_start" -> trace(context).start(
            args.tString("packageName"),
            args.tString("reason", "").ifEmpty { null },
            args.tBool("withLogs", true)
        )
        "app_trace_read" -> trace(context).read(
            args.tString("traceSessionId"),
            args.tInt("offset", 0, 0, 1_000_000),
            args.tInt("limit", 100, 1, 500)
        )
        "app_trace_stop" -> trace(context).stop(args.tString("traceSessionId"))
        "app_trace_list" -> trace(context).list()
        "app_launch_trace" -> {
            val started = trace(context).start(
                args.tString("packageName"),
                args.tString("reason", "").ifEmpty { null },
                args.tBool("withLogs", true)
            )
            // 启动失败也要保留 traceSessionId，方便随后读日志/退出原因。
            val launched = try {
                McpAppTools.launch(context, args)
            } catch (failure: Exception) {
                ToolkitIo.json("success" to false, "error" to (failure.message ?: "启动失败"))
            }
            started.add("launch", launched)
            started
        }
        else -> throw IllegalArgumentException("未知应用追踪工具：$name")
    }

    /** 只读数据源：同 UID 进程列表 + 本进程可见的 logcat 环形缓冲。 */
    private class AndroidSource(private val context: Context) : McpAppTrace.DataSource {
        override fun snapshot(packageName: String, pid: Int?, withLogs: Boolean): McpAppTrace.Snapshot {
            val processes = processes()
            val resolvedPid = pid ?: processes[packageName]
            val uid = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).uid
            }.getOrNull()
            val lines = if (withLogs) logcat() else emptyList()
            return McpAppTrace.Snapshot(
                pid = resolvedPid,
                uid = uid,
                lines = lines,
                processAlive = processes.containsKey(packageName),
                logsTruncated = lines.size >= LOGCAT_LINE_LIMIT,
                note = "logcat -d 环形缓冲；普通应用 UID 只能看到有限日志，无证据时按 unknown 处理"
            )
        }

        private fun processes(): Map<String, Int> = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses.orEmpty().associate { it.processName to it.pid }
        }.getOrDefault(emptyMap())

        private fun logcat(): List<String> = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINE_LIMIT.toString())
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text.lines().filter { it.isNotBlank() }.take(MAX_LOGCAT_LINES)
        }.getOrDefault(emptyList())

        private companion object {
            const val LOGCAT_LINE_LIMIT = 400
            const val MAX_LOGCAT_LINES = 2000
        }
    }
}
