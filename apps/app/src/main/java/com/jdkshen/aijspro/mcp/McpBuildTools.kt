package com.jdkshen.aijspro.mcp

import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * get_build_tools 的工具链检测 + 编译组件缺口识别（P0-2）。
 * 只做只读 PATH 探测和内存缓存；不下载、不安装、不写盘。
 */
internal object McpBuildTools {
    private val detectedCommands = listOf(
        "sh", "clang", "clang++", "gcc", "g++", "cmake", "ninja", "make",
        "java", "javac", "keytool", "d8", "smali", "gradle", "aapt2", "zipalign", "apksigner", "app_process",
        "python3", "aidl",
    )

    private val requiredComponents = listOf("jdk17", "aapt2", "d8", "apksigner", "python3", "aidl")

    @Volatile
    private var lastReadiness: JsonObject? = null
    @Volatile
    private var lastCommandResults: Map<String, String>? = null

    private val appPrivateComponents = setOf("jdk17", "aapt2", "apksigner")
    private val failureMarkers = listOf(
        "inaccessible or not found", "command not found", "no such file or directory", "no such file",
        "not found", "cannot execute", "not executable", "permission denied", "cannot access",
        "failed to execute", "error=2",
    )
    // 以 get_build_tools 的组件表为准；python3.14 等版本后缀按 python3 归并。
    private val componentAliases = linkedMapOf(
        "python3" to "python3", "python" to "python3", "aidl" to "aidl",
        "keytool" to "jdk17", "javac" to "jdk17", "java" to "jdk17",
        "aapt2" to "aapt2", "d8" to "d8", "zipalign" to "zipalign", "apksigner" to "apksigner",
        "smali" to "smali", "gradle" to "gradle", "app_process" to "app_process",
        "clang++" to "clang", "clang" to "clang", "g++" to "gcc", "gcc" to "gcc",
        "cmake" to "cmake", "ninja" to "ninja", "make" to "make",
    )

    fun inspect(backend: McpAndroidTerminal, directory: File): JsonObject {
        val result = linkedMapOf<String, String>()
        val process = backend.launch(detectedCommands.joinToString("\n") { name ->
            "p=\$(command -v '$name' 2>/dev/null || :); if [ -n \"\$p\" ] && [ -x \"\$p\" ]; then printf '$name=%s\\n' \"\$p\"; else printf '$name=\\n'; fi"
        }, directory, emptyMap())
        try {
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

        val d8BuiltIn = Build.VERSION.SDK_INT >= 26
        val readiness = readiness(result, d8BuiltIn, true)
        lastReadiness = readiness.deepCopy()
        lastCommandResults = result.toMap()
        val missing = readiness.getAsJsonArray("missing").map { it.asString }
        return JsonObject().apply {
            addProperty("platform", "android"); addProperty("sdkInt", Build.VERSION.SDK_INT)
            addProperty("uidScope", "application"); addProperty("pty", false)
            add("commands", JsonArray().apply {
                detectedCommands.forEach { name -> add(JsonObject().apply {
                    addProperty("name", name); addProperty("path", result[name].orEmpty())
                    addProperty("found", !result[name].isNullOrEmpty())
                    addProperty("verified", false)
                }) }
            })
            add("builtIn", JsonObject().apply {
                addProperty("d8", d8BuiltIn)
                addProperty("smali", true)
                addProperty("mainClass", backend.environment.getValue("AIJS_BUILD_MAIN"))
                addProperty("d8Example", "mkdir -p out && CLASSPATH=\"\$AIJS_APK\" app_process /system/bin \"\$AIJS_BUILD_MAIN\" d8 --min-api 26 --output out input.jar")
                addProperty("smaliExample", "CLASSPATH=\"\$AIJS_APK\" app_process /system/bin \"\$AIJS_BUILD_MAIN\" smali assemble src -o classes.dex")
            })
            add("optionalComponents", backend.compilerComponents())
            add("apkComponents", backend.apkCompilerComponents())
            add("signing", ToolkitApkVerifier.signingCapability())
            add("ready", readiness.get("ready"))
            add("missing", readiness.get("missing"))
            add("present", readiness.get("present"))
            add("toolchain", readiness)
            appPrivateToolchainHint(missing)?.let { add("appPrivateToolchain", it) }
            if (missing.isNotEmpty()) {
                val missingPayload = componentMissingPayloadFor(missing)
                addProperty("code", "COMPONENT_MISSING")
                if (missingPayload != null) {
                    add("componentMissing", missingPayload)
                    add("recovery", missingPayload.get("recovery"))
                    add("nextActions", missingPayload.getAsJsonObject("recovery")?.get("nextActions") ?: JsonArray())
                }
            } else {
                add("nextActions", JsonArray())
            }
            addProperty("note", "found 只表示 PATH 中可定位，未执行编译器。ready/missing/present 是 P0-2 就绪摘要；" +
                "python3/aidl 缺失时 code=COMPONENT_MISSING，按 recovery 的 apk_component_install/localDeps 两条路处理。" +
                "C/C++可通过aijs-toolchain显式下载可选组件，主APK不内嵌编译器；内置D8转换class/JAR→DEX，Smali汇编→DEX，" +
                "均不编译Java源码；JDK/Gradle/完整Android SDK需另配。编译使用app后端，共享存储通常不能直接执行ELF。不会后台自动安装依赖。")
        }
    }

    internal fun readiness(commandResults: Map<String, String>, builtInD8: Boolean, builtInSmali: Boolean): JsonObject {
        fun found(name: String): Boolean = !commandResults[name].isNullOrBlank()
        val present = requiredComponents.filter { component ->
            when (component) {
                "jdk17" -> found("java") && found("javac")
                "aapt2" -> found("aapt2")
                "d8" -> builtInD8 || found("d8")
                "apksigner" -> found("apksigner")
                "python3" -> found("python3")
                "aidl" -> found("aidl")
                else -> false
            }
        }
        val missing = requiredComponents.filter { it !in present }
        return ToolkitIo.json(
            "ready" to missing.isEmpty(),
            "missing" to missing,
            "present" to present,
            "missingCount" to missing.size,
        )
    }

    fun cachedReadiness(): JsonObject? = lastReadiness?.deepCopy()

    internal fun detectMissingComponents(command: String, output: String): List<String> {
        val text = output
        val lower = text.lowercase(Locale.ROOT)
        if (failureMarkers.none { lower.contains(it) }) return emptyList()
        val found = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val lineLower = line.lowercase(Locale.ROOT)
            if (failureMarkers.none { lineLower.contains(it) }) continue
            for ((needle, component) in componentAliases) {
                val boundary = Regex("(?<![A-Za-z0-9_])" + Regex.escape(needle) +
                    "(?:\\.\\d+)?(?:\\[\\d+\\])?(?![A-Za-z0-9_])")
                if (boundary.containsMatchIn(lineLower)) found.add(component)
            }
            if (lineLower.contains("py-tree")) found.add("python3")
            if (lineLower.contains("aidl-tree")) found.add("aidl")
        }
        val commandName = command.trim().substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
        if (failureMarkers.any { lower.contains(it) } && commandName.isNotBlank()) {
            componentAliases[commandName]?.let { found.add(it) }
        }
        return found.toList()
    }

    internal fun componentMissingPayload(command: String, output: String,
                                              readiness: JsonObject? = cachedReadiness(),
                                              commandResults: Map<String, String>? = lastCommandResults): JsonObject? {
        val missing = detectMissingComponents(command, output)
        if (missing.isEmpty()) return null
        return componentMissingPayloadFor(missing, output, readiness, commandResults)
    }

    /** 已知缺失组件列表时直接构造（get_build_tools 的 ready 摘要用，不依赖错误文本）。 */
    internal fun componentMissingPayloadFor(missing: List<String>, evidence: String? = null,
                                             readiness: JsonObject? = cachedReadiness(),
                                             commandResults: Map<String, String>? = lastCommandResults): JsonObject? {
        if (missing.isEmpty()) return null
        val recovery = componentRecovery(missing)
        val toolchain = toolchainSummary(readiness, missing)
        val componentState = componentState(missing, readiness)
        val appPrivateHint = appPrivateToolchainHint(missing)
        val message = "缺少编译依赖：" + missing.joinToString("、") +
            "（app 后端 PATH 不可达）。这不是脚本语法错误；先按 recovery 恢复，再重试原命令。"
        val error = ToolkitIo.json(
            "code" to "COMPONENT_MISSING",
            "message" to message,
            "components" to missing,
            "recovery" to recovery,
            "toolchain" to toolchain,
            "componentState" to componentState,
        )
        appPrivateHint?.let { error.add("appPrivateToolchain", it) }
        val payload = ToolkitIo.json(
            "code" to "COMPONENT_MISSING",
            "error" to error,
            "components" to missing,
            "missing" to missing,
            "message" to message,
            "recovery" to recovery,
            "toolchain" to toolchain,
            "componentState" to componentState,
            "evidence" to (evidence ?: "").take(800),
        )
        appPrivateHint?.let { payload.add("appPrivateToolchain", it) }
        commandResults?.let { results ->
            payload.add("commandState", JsonArray().apply {
                detectedCommands.forEach { name ->
                    add(ToolkitIo.json("name" to name, "found" to !results[name].isNullOrBlank()))
                }
            })
        }
        return payload
    }

    private fun toolchainSummary(readiness: JsonObject?, detectedMissing: List<String>): JsonObject =
        if (readiness == null) ToolkitIo.json(
            "ready" to false, "missing" to detectedMissing, "present" to JsonArray(),
            "note" to "get_build_tools 缓存尚未采样；missing 来自本次报错文本；先调用 get_build_tools 取得完整 present/ready",
        ) else ToolkitIo.json(
            "ready" to readiness.get("ready"),
            "missing" to readiness.get("missing"),
            "present" to readiness.get("present"),
        )

    private fun componentState(missing: List<String>, readiness: JsonObject?): JsonArray {
        val present = readiness?.getAsJsonArray("present")?.map { it.asString }?.toSet()
        return JsonArray().apply {
            requiredComponents.forEach { component ->
                add(ToolkitIo.json(
                    "name" to component,
                    "found" to if (present == null) null else component in present,
                    "required" to true,
                    "source" to if (present == null) "unknown" else "get_build_tools",
                ))
            }
        }
    }

    /** jdk17/aapt2/apksigner 在 app 后端常不可见；提示它们是私有/APK 组件，不要只看 PATH。 */
    internal fun appPrivateToolchainHint(missing: List<String>): JsonObject? {
        val privateMissing = missing.filter { it in appPrivateComponents }
        if (privateMissing.isEmpty()) return null
        return ToolkitIo.json(
            "components" to privateMissing,
            "hint" to "jdk17/aapt2/apksigner 属于 app 私有 toolchain/APK 组件，系统 PATH 检不到不等于没装；先 apk_component_status，再看 get_build_tools.builtIn/signing 与 commands。",
            "statusTool" to "apk_component_status",
            "builtInField" to "get_build_tools.builtIn",
            "note" to "python3/aidl 不属于这个通道；按 recovery.localDeps 处理。",
        )
    }

    fun enrichComponentFailure(result: JsonObject, command: String, output: String,
                               readiness: JsonObject? = cachedReadiness(),
                               commandResults: Map<String, String>? = lastCommandResults): JsonObject {
        val payload = componentMissingPayload(command, output, readiness, commandResults) ?: return result
        if (!result.has("code")) result.addProperty("code", "COMPONENT_MISSING")
        result.add("componentMissing", payload)
        result.add("error", payload.get("error"))
        attachToolchainFields(result, payload)
        result.add("recovery", payload.get("recovery"))
        result.add("nextActions", payload.getAsJsonObject("recovery")?.get("nextActions") ?: JsonArray())
        return result
    }

    fun enrichTerminalResult(result: JsonObject, readiness: JsonObject? = cachedReadiness(),
                              commandResults: Map<String, String>? = lastCommandResults): JsonObject {
        val command = result.get("command")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val backend = result.get("backend")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        correctShellWrapperRouting(result, command)
        addAppProcessVisibilityNote(result, command, backend)
        val output = result.get("output")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: result.get("error")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return result
        if (result.has("code")) return result
        val payload = componentMissingPayload(command, output, readiness, commandResults) ?: return result
        result.addProperty("code", "COMPONENT_MISSING")
        result.add("componentMissing", payload)
        // 不覆盖 terminal job 自己的 error（字符串）；结构化错误在 componentMissing.error。
        result.add("componentError", payload.get("error"))
        attachToolchainFields(result, payload)
        result.add("recovery", payload.get("recovery"))
        result.add("nextActions", payload.getAsJsonObject("recovery")?.get("nextActions") ?: JsonArray())
        return result
    }

    /** 顶层直接给 ready/missing/present，满足“报错时直接引用 get_build_tools 清单”。 */
    private fun attachToolchainFields(result: JsonObject, payload: JsonObject) {
        val toolchain = payload.getAsJsonObject("toolchain") ?: return
        result.add("toolchain", toolchain)
        toolchain.get("ready")?.let { result.add("ready", it) }
        toolchain.get("missing")?.let { result.add("missing", it) }
        toolchain.get("present")?.let { result.add("present", it) }
        payload.get("componentState")?.let { result.add("componentState", it) }
        payload.get("commandState")?.let { result.add("commandState", it) }
        payload.get("appPrivateToolchain")?.let { result.add("appPrivateToolchain", it) }
    }

    private val shellWrapperRoutingLine = Regex(
        "(?m)^AIJS_BACKEND_ROUTING routed=false requestedBackend=app effectiveBackend=app requirement=SHELL_ONLY;[^\\n]*$",
    )

    /**
     * P1-6：sh/bash 包装脚本不是 shell-only。McpAndroidTerminal 的通用路由会把它标成 SHELL_ONLY；
     * 这里只纠正给调用方看的路由提示行，不改变命令、后端和原始输出。
     */
    private fun correctShellWrapperRouting(result: JsonObject, command: String) {
        if (!Regex("^\\s*(sh|bash|dash|ash)\\b").containsMatchIn(command)) return
        val output = result.get("output")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return
        if (!shellWrapperRoutingLine.containsMatchIn(output)) return
        val correction = "AIJS_BACKEND_ROUTING routed=false requestedBackend=app effectiveBackend=app requirement=BOTH; " +
            "sh/bash 包装脚本不是 shell-only：app 后端可执行普通构建脚本；只有 pm/am/logcat 等需要 backend=shizuku。" +
            "正确调用：{\"backend\":\"app\",\"command\":\"sh build.sh\"}"
        result.addProperty("output", shellWrapperRoutingLine.replace(output, correction))
        result.addProperty("routingCorrected", true)
        result.addProperty("routingCorrection", correction)
    }

    /** P1-7：app 后端看不到 shell 自身进程是正常限制，查进程应换 backend=shizuku。 */
    private fun addAppProcessVisibilityNote(result: JsonObject, command: String, backend: String) {
        if (backend != "app") return
        if (!Regex("(?i)(^|\\s)(pidof|pgrep|ps|top)(\\s|$)").containsMatchIn(command)) return
        result.add("processVisibility", ToolkitIo.json(
            "appBackend" to "app 后端看不到 shell/其他进程（pidof shizuku_server 空、ps 受限）属正常，不代表服务挂了",
            "hint" to "查进程请用 terminal_exec backend=shizuku",
            "correctCall" to "{\"backend\":\"shizuku\",\"command\":\"pidof shizuku_server\"}",
        ))
    }

    private fun componentRecovery(missing: List<String>): JsonObject {
        val apkComponent = ToolkitIo.json(
            "tool" to "apk_component_install",
            "hint" to "先 apk_component_status 查可安装组件；apk_component_install 只支持清单内 APK 组件（jadx/apk-resources/apktool/frida-gadget），python3/aidl 通常不在其中，不要盲试",
            "supportedComponents" to listOf("jadx", "apk-resources", "apktool", "frida-gadget"),
        )
        val localDeps = ToolkitIo.json(
            "hint" to "本机依赖树在终端 HOME（应用 files 目录，即 \${HOME}）；按 \${HOME}/<x>-deps.json 解包对应 deb，把 bin 加入 PATH 后重试。不要从 /sdcard 直接执行 ELF。",
            "commands" to listOf(
                "H=\"\${HOME}\"; ls -l \"\$H\"/*-deps.json 2>/dev/null",
                "H=\"\${HOME}\"; ls -ld \"\$H\"/py-tree \"\$H\"/aidl-tree 2>/dev/null",
                "H=\"\${HOME}\"; \"\$H\"/py-tree/*/bin/python3 --version 2>/dev/null || true",
            ),
        )
        val actions = JsonArray().apply {
            add(ToolkitIo.json("tool" to "apk_component_status", "hint" to "先看哪些 APK 组件可装；不要用 apk_component_install 装 python3/aidl"))
            add(ToolkitIo.json("tool" to "terminal_exec", "arguments" to ToolkitIo.json("backend" to "app",
                "command" to "H=\"\${HOME}\"; ls -l \"\$H\"/*-deps.json 2>/dev/null; ls -ld \"\$H\"/py-tree \"\$H\"/aidl-tree 2>/dev/null"),
                "hint" to "按本机 deps.json 解包 deb；这是本机依赖路线，不需要 shizuku"))
            add(ToolkitIo.json("tool" to "get_build_tools", "hint" to "恢复后重新采样 ready/missing/present；ready=true 才继续"))
            add(ToolkitIo.json("tool" to "terminal_exec", "arguments" to ToolkitIo.json("backend" to "app",
                "command" to "sh build.sh"), "hint" to "依赖恢复后用 app 后端重试；sh build.sh 不需要换 shizuku"))
        }
        return ToolkitIo.json(
            "apk_component_install" to apkComponent,
            "localDeps" to localDeps,
            "nextActions" to actions,
        )
    }

    internal fun baselineContent(root: String, currentProject: String?, workspaces: JsonObject,
                                 toolchain: JsonObject?, jobs: JsonObject): JsonObject = ToolkitIo.json(
        "root" to root,
        "currentProject" to currentProject,
        "outputRule" to "所有产出落 <root>/<项目名>/",
        "workspaces" to workspaces,
        "toolchain" to (toolchain ?: ToolkitIo.json("ready" to null, "missing" to JsonArray(),
            "present" to JsonArray(), "note" to "先调用 get_build_tools 采样")),
        "backends" to ToolkitIo.json(
            "app" to "普通应用 UID（私有 toolchain）",
            "shizuku" to "shell 2000（pm/am/logcat/ps）",
        ),
        "jobs" to jobs,
    )
}

internal class McpBaselineVersion {
    private var lastDigest: String? = null
    private var current = 0

    @Synchronized
    fun versionFor(content: JsonObject): Int {
        val digest = ToolkitIo.version(content.toString().toByteArray(Charsets.UTF_8)).take(16)
        if (digest != lastDigest) {
            lastDigest = digest
            current += 1
        }
        return current
    }
}
