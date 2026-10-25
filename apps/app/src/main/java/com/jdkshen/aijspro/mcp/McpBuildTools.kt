package com.jdkshen.aijspro.mcp

import android.content.Context
import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.TimeUnit

internal object McpBuildTools {
    fun inspect(backend: McpAndroidTerminal, directory: File, context: Context): JsonObject {
        val commands = listOf("sh", "clang", "clang++", "gcc", "g++", "cmake", "ninja", "make",
            "java", "javac", "d8", "smali", "gradle", "aapt2", "zipalign", "apksigner", "app_process")
        val result = linkedMapOf<String, String>()
        val process = backend.launch(commands.joinToString("\n") { name ->
            "printf '$name=%s\\n' \"\$(command -v '$name' || :)\""
        }, directory, emptyMap())
        try {
            // Fixed-size output from a fixed command set. No user command/version scripts are executed.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (runCatching { process.process.exitValue(); true }.getOrDefault(false).not()) {
                check(System.nanoTime() < deadline) { "工具链检测超时" }
                Thread.sleep(10)
            }
            process.process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val pair = line.split('=', limit = 2)
                    if (pair.size == 2) result[pair[0]] = pair[1].trim()
                }
            }
        } finally { process.terminate() }
        return JsonObject().apply {
            addProperty("platform", "android"); addProperty("sdkInt", Build.VERSION.SDK_INT)
            addProperty("uidScope", "application"); addProperty("pty", false)
            add("commands", JsonArray().apply {
                commands.forEach { name -> add(JsonObject().apply {
                    addProperty("name", name); addProperty("path", result[name].orEmpty())
                    addProperty("found", !result[name].isNullOrEmpty())
                    addProperty("verified", false)
                }) }
            })
            add("builtIn", JsonObject().apply {
                addProperty("d8", Build.VERSION.SDK_INT >= 26)
                addProperty("smali", true)
                addProperty("mainClass", backend.environment.getValue("AIJS_BUILD_MAIN"))
                addProperty("d8Example", "mkdir -p out && CLASSPATH=\"\$AIJS_APK\" app_process /system/bin \"\$AIJS_BUILD_MAIN\" d8 --min-api 26 --output out input.jar")
                addProperty("smaliExample", "CLASSPATH=\"\$AIJS_APK\" app_process /system/bin \"\$AIJS_BUILD_MAIN\" smali assemble src -o classes.dex")
            })
            add("toolchain", inspectToolchain(context))
            addProperty("note", "found 只表示 PATH 中可定位，未执行编译器。内置 D8 转换 class/JAR→DEX，Smali 汇编→DEX；均不编译 Java 源码。C/C++、Java 源码及完整 Android 工程需另装兼容本机 ABI/Android 的工具链和 SDK；电脑 NDK 二进制不能直接在手机运行。shell 使用应用权限，共享存储通常不能直接执行 ELF。终端不会自动安装依赖。")
        }
    }

    /** Optional buildtools plugin descriptor; cross-package asset read, never executes plugin code. */
    private fun inspectToolchain(context: Context): JsonObject {
        val packageName = "com.jdkshen.buildtools.plugin"
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            val resources = context.packageManager.getResourcesForApplication(info)
            val text = resources.assets.open("buildtools/descriptor.json")
                .bufferedReader().use { it.readText() }
            val descriptor = JsonParser().parse(text) as JsonObject
            JsonObject().apply {
                addProperty("installed", true)
                addProperty("packageName", packageName)
                add("descriptor", descriptor)
            }
        } catch (error: Exception) {
            JsonObject().apply {
                addProperty("installed", false)
                addProperty("packageName", packageName)
                addProperty("error", "未安装或无法读取可选 buildtools 插件")
            }
        }
    }
}
