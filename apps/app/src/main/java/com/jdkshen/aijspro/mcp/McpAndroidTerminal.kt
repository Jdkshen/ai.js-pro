package com.jdkshen.aijspro.mcp

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.stardust.autojs.runtime.api.ShizukuClient
import com.stardust.autojs.runtime.api.ShizukuShell
import com.stardust.autojs.runtime.api.shizuku.RishRuntime
import com.stardust.autojs.runtime.tool.ToolchainRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared terminal launcher for the in-app terminal and MCP.
 *
 * - app (default): ordinary application UID, one setsid process group per job. RishRuntime only
 *   injects the `rish`/`i` function entry points into that same app-UID shell; commands are not
 *   elevated and RISH_APPLICATION_ID is always the runtime package name.
 * - shizuku: a raw command created by ShizukuShell in the Shizuku UserService. It is not wrapped
 *   again, stderr is merged, and terminate() calls process.destroy(); that destroy is the frozen
 *   contract for killing the remote process group.
 */
internal class McpAndroidTerminal(private val context: Context) {
    private val cache = File(context.cacheDir, "mcp-terminal").apply { mkdirs() }
    val environment = mapOf(
        "HOME" to context.filesDir.absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "TERM" to "dumb",
        "AIJS_APK" to context.applicationInfo.sourceDir,
        "AIJS_BUILD_MAIN" to "com.stardust.autojs.runtime.tool.AndroidBuildTool"
    )

    fun launch(command: String, directory: File, env: Map<String, String>): McpTerminalJobs.RunningCommand {
        val requestedBackend = env[McpTerminalJobs.INTERNAL_BACKEND_ENV] ?: McpTerminalJobs.BACKEND_APP
        if (requestedBackend != McpTerminalJobs.BACKEND_APP && requestedBackend != McpTerminalJobs.BACKEND_SHIZUKU) {
            throw IllegalArgumentException("未知终端后端：$requestedBackend")
        }
        // UX-2：该标记由 terminal_exec 的 autoRoute=true 注入（不传给子进程）。默认关闭；
        // 只有 ToolkitBackendRouting 的只读白名单才允许自动切换。
        val autoRoute = env[ToolkitBackendRouting.AUTO_ROUTE_ENV].equals("true", ignoreCase = true)
        val commandEnv = env.filterKeys {
            it != McpTerminalJobs.INTERNAL_BACKEND_ENV && it != ToolkitBackendRouting.AUTO_ROUTE_ENV
        }
        val routing = ToolkitBackendRouting.check(requestedBackend, command)
        var backend = requestedBackend
        var routingNote: String? = null
        if (!routing.get("ok").asBoolean) {
            val target = if (autoRoute) {
                routing.get("autoRouteTarget")?.takeIf { !it.isJsonNull }?.asString
            } else null
            val requirement = routing.get("requirement").asString
            if (target != null) {
                backend = target
                routingNote = "AIJS_BACKEND_ROUTING routed=true routedFrom=$requestedBackend routedTo=$target " +
                    "requirement=$requirement autoRoute=true（只读白名单命令）"
            } else {
                val hint = routing.get("hint").asString
                routingNote = "AIJS_BACKEND_ROUTING routed=false requestedBackend=$requestedBackend " +
                    "requirement=$requirement; $hint"
            }
        }
        // 保留原始命令及其原始输出：路由提示只作为前置一行写进同一 stdout/stderr 流，
        // 不提前终止进程，因此调用方仍能读到原来的 "inaccessible or not found"。
        val effectiveCommand = routingNote?.let { withRoutingNote(it, command) } ?: command
        return when (backend) {
            McpTerminalJobs.BACKEND_APP -> launchApp(effectiveCommand, directory, commandEnv)
            McpTerminalJobs.BACKEND_SHIZUKU -> launchShizuku(effectiveCommand, directory, commandEnv)
            else -> throw IllegalArgumentException("未知终端后端：$backend")
        }
    }

    private fun withRoutingNote(note: String, command: String): String =
        "printf '%s\n' " + quote(note) + "; " + command

    private fun launchApp(command: String, directory: File, env: Map<String, String>): McpTerminalJobs.RunningCommand {
        check(File("/system/bin/setsid").canExecute()) { "当前系统缺少 setsid，不能创建可独立停止的终端" }
        val marker = File(cache, "${UUID.randomUUID()}.pid")
        // The ordinary app terminal still owns its own process group. RishRuntime only injects
        // function definitions/environment into this app-UID shell so `rish`/`i` work without
        // requiring an executable file in PATH.
        val wrapped = AndroidToolkitRuntime.wrapTerminal(context, ToolchainRuntime.wrapCommand(context, RishRuntime.wrapCommand(context, command)))
        val wrapper = "umask 077; printf '%s' \"\$\$\" > ${quote(marker.path)}; exec /system/bin/sh -c ${quote(wrapped)}"
        val builder = ProcessBuilder("/system/bin/setsid", "-w", "/system/bin/sh", "-c", wrapper)
            .directory(directory).redirectErrorStream(true)
        val processEnvironment = builder.environment()
        // Preserve the existing base environment as the authoritative HOME/TMPDIR/AIJS values;
        // RishRuntime contributes PATH/RISH entries, then the caller's env keeps its old priority.
        processEnvironment.putAll(RishRuntime.environment(context))
        processEnvironment.putAll(ToolchainRuntime.environment(context))
        processEnvironment.putAll(environment)
        processEnvironment.putAll(env)
        // RishRuntime must be told the real runtime package (including .phonebuild suffixes).
        // User env is applied above, but this variable is never replaceable.
        processEnvironment[RISH_APPLICATION_ID] = context.packageName
        val process = builder.start()
        var group: Int? = null
        try {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (group == null && System.nanoTime() < deadline) {
                group = runCatching { marker.readText().trim().toInt().takeIf { it > 1 } }.getOrNull()
                if (group == null) Thread.sleep(5)
            }
            check(group != null) { "终端进程未完成初始化" }
            val processGroup = group
            val terminated = AtomicBoolean()
            return McpTerminalJobs.RunningCommand(process) {
                if (terminated.compareAndSet(false, true)) {
                    // setsid owns this group; killing only the shell would leave clang/Gradle children behind.
                    runCatching { Os.kill(-processGroup, OsConstants.SIGKILL) }
                    // Do not close stdout on an already-exited shell before its reader drains the tail.
                    if (runCatching { process.exitValue(); false }.getOrDefault(true)) process.destroy()
                    marker.delete()
                }
            }
        } catch (error: Exception) {
            group?.let { runCatching { Os.kill(-it, OsConstants.SIGKILL) } }
            process.destroy()
            marker.delete()
            throw error
        }
    }

    private fun launchShizuku(command: String, directory: File, env: Map<String, String>): McpTerminalJobs.RunningCommand {
        val processEnvironment = LinkedHashMap<String, String>()
        processEnvironment.putAll(environment)
        processEnvironment.putAll(env)
        processEnvironment[RISH_APPLICATION_ID] = context.packageName
        val process = try {
            // No second rish wrapper here: the UserService already runs the raw command with the
            // authorized identity. mergeError=true keeps the existing one-stream job contract.
            ShizukuShell.start(command, directory, processEnvironment, true)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Shizuku 后端启动失败：${error.message ?: error.javaClass.simpleName}", error)
        }
        val terminated = AtomicBoolean()
        return McpTerminalJobs.RunningCommand(process) {
            // The frozen ShizukuShell contract makes destroy() terminate the remote process group.
            if (terminated.compareAndSet(false, true)) runCatching { process.destroy() }
        }
    }

    fun apkCompilerComponents(): JsonObject = AndroidToolkitRuntime.terminalStatus(context)

    fun compilerComponents(): JsonObject =
        com.google.gson.JsonParser().parse(ToolchainRuntime.status(context).toString()).asJsonObject

    companion object {
        private const val RISH_APPLICATION_ID = "RISH_APPLICATION_ID"

        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

        /** Real Shizuku state for the terminal UI; never assumes a fixed shell UID (e.g. 2000). */
        @JvmStatic
        fun shizukuState(@Suppress("UNUSED_PARAMETER") context: Context): JsonObject =
            McpShizukuStatus.read()
    }
}

/**
 * Converts ShizukuClient.state() (org.json) into the Gson shape used by MCP/UI helpers. Unknown
 * values stay JSON null; backendUid is shown only when the engine actually reported it.
 */
private object McpShizukuStatus {
    private val STATE_KEYS = setOf(
        "sdkSupported", "binderAlive", "binderReady", "available", "permissionGranted", "granted",
        "serverVersion", "backendUid", "uid", "processName", "lastError", "error"
    )

    fun read(): JsonObject {
        val state = runCatching { ShizukuClient.state() }.getOrNull()
        if (state != null) {
            val converted = state.toGson()
            if (converted.size() > 0) {
                return converted.apply { addProperty("source", "ShizukuClient") }
            }
        }
        return fallback()
    }

    private fun JSONObject.toGson(): JsonObject = JsonObject().apply {
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key in STATE_KEYS) add(key, toGsonValue(opt(key)))
        }
    }

    private fun toGsonValue(value: Any?): JsonElement {
        if (value == null || JSONObject.NULL == value) return JsonNull.INSTANCE
        if (value is Boolean) return JsonPrimitive(value)
        if (value is Number) return JsonPrimitive(value)
        if (value is String) return JsonPrimitive(value)
        if (value is JSONObject) return value.toGson()
        if (value is JSONArray) {
            val result = JsonArray()
            for (index in 0 until value.length()) result.add(toGsonValue(value.opt(index)))
            return result
        }
        return JsonPrimitive(value.toString())
    }

    private fun fallback(): JsonObject = JsonObject().apply {
        addProperty("sdkSupported", android.os.Build.VERSION.SDK_INT >= 23)
        addProperty("binderAlive", runCatching { ShizukuShell.isAvailable() }.getOrDefault(false))
        addProperty("permissionGranted", runCatching { ShizukuShell.hasPermission() }.getOrDefault(false))
        addProperty("source", "ShizukuShell")
    }
}
