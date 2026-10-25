package com.jdkshen.aijspro.mcp

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A private session/process group per job: stop cannot kill another engine or the app. */
internal class McpAndroidTerminal(context: Context) {
    private val cache = File(context.cacheDir, "mcp-terminal").apply { mkdirs() }
    val environment = mapOf(
        "HOME" to context.filesDir.absolutePath,
        "TMPDIR" to context.cacheDir.absolutePath,
        "TERM" to "dumb",
        "AIJS_APK" to context.applicationInfo.sourceDir,
        "AIJS_BUILD_MAIN" to "com.stardust.autojs.runtime.tool.AndroidBuildTool"
    )

    fun launch(command: String, directory: File, env: Map<String, String>): McpTerminalJobs.RunningCommand {
        check(File("/system/bin/setsid").canExecute()) { "当前系统缺少 setsid，不能创建可独立停止的终端" }
        val marker = File(cache, "${UUID.randomUUID()}.pid")
        val wrapper = "umask 077; printf '%s' \"\$\$\" > ${quote(marker.path)}; exec /system/bin/sh -c ${quote(command)}"
        val process = ProcessBuilder("/system/bin/setsid", "-w", "/system/bin/sh", "-c", wrapper)
            .directory(directory).redirectErrorStream(true).apply {
                environment().putAll(environment)
                environment().putAll(env)
            }.start()
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

    companion object {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
