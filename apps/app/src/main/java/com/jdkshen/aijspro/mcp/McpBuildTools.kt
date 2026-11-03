package com.jdkshen.aijspro.mcp

import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

internal object McpBuildTools {
    fun inspect(backend: McpAndroidTerminal, directory: File): JsonObject {
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
            add("optionalComponents", backend.compilerComponents())
            add("apkComponents", backend.apkCompilerComponents())
            addProperty("note", "found 只表示 PATH 中可定位，未执行编译器。C/C++可通过aijs-toolchain显式下载可选组件，主APK不内嵌编译器；optionalComponents提供状态和安装命令。内置D8转换class/JAR→DEX，Smali汇编→DEX，均不编译Java源码；JDK/Gradle/完整Android SDK需另配。编译使用app后端，共享存储通常不能直接执行ELF。不会后台自动安装依赖。")
        }
    }
}
