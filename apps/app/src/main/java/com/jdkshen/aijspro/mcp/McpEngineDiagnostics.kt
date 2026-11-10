package com.jdkshen.aijspro.mcp

import android.content.Context
import android.app.ActivityManager
import android.os.Process
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.jdkshen.aijspro.BuildConfig
import org.autojs.source.engine.android.Node24Pool
import java.io.File

/**
 * One JSON snapshot for the three-engine / Node pool diagnosis.
 *
 * <p>This class is intentionally conservative: it only reports what the current process can
 * observe. In particular the Node pool's busy count is not faked as 0 when the slots live in
 * :script, and the Node runtime metadata is inspected without hashing or executing anything.
 * The main assistant can expose [snapshotJson] through reflection to PocIntegration/Dart.
 */
object McpEngineDiagnostics {
    const val SCHEMA_VERSION = 1

    private const val NODE_ASSET_PREFIX = "source-engine-upgrade/node24/arm64-v8a"
    private const val NODE_VERSION = "24.22.0"
    private const val NODE_CACHE_LOG_TAG = "C-NODE24"
    @Volatile private var latestFailure: JsonObject? = null

    /** Current-process evidence only; source, stack, path and error text are not copied. */
    @Synchronized
    internal fun recordFailure(engine: String, executionId: String, at: Long, type: String) {
        if ((latestFailure?.get("at")?.asLong ?: -1L) > at) return
        latestFailure = JsonObject().apply {
            addProperty("engine", if (engine in McpEngines.names) engine else "unknown")
            addProperty("executionId", executionId.take(100))
            addProperty("at", at)
            addProperty("type", type.take(160))
            addProperty("scope", "current-process-mcp")
        }
    }

    internal fun failureSnapshot(): JsonObject? = latestFailure?.deepCopy()

    internal data class NodeProcessObservation(
        val process: String,
        val slot: String,
        val pid: Int?,
        val observed: String
    )

    @JvmStatic
    fun snapshotJson(context: Context): String {
        val capturedAt = System.currentTimeMillis()
        val capacity = runCatching { Node24Pool.capacity() }.getOrDefault(3)
        val runtimePins = runtimePinCount(context)
        return buildSnapshot(
            capturedAt = capturedAt,
            processName = currentProcessName(context),
            pid = Process.myPid(),
            rhinoAvailable = BuildConfig.RHINO_COMPAT,
            poolCapacity = capacity,
            nodeProcesses = observeNodeProcesses(context, capacity),
            runtimePinCount = runtimePins,
            lastFailure = failureSnapshot(),
            nodeRuntimeForm = nodeRuntimeForm(nodeRuntimeFormInput(context, currentProcessName(context)))
        ).toString()
    }

    /**
     * §6.3：嵌入式 Node 的**真实运行形态**。复盘指出「process.execPath」在本形态下指向
     * app_process64/32，不能当独立 Node CLI 用。这里只做只读文件探测，不启动进程、不执行脚本。
     */
    internal data class NodeRuntimeFormInput(
        val processName: String,
        val hostExecutablePath: String?,
        val hostExecutablePresent: Boolean,
        val runtimeDirectoryPath: String?,
        val cliLauncherPath: String?,
        val cliLauncherPresent: Boolean,
        val cliLauncherExecutable: Boolean,
        val runtimeLibraryPath: String?,
        val runtimeLibraryPresent: Boolean,
        val toolsDirectoryPath: String?,
        val toolsDirectoryPresent: Boolean
    )

    internal fun nodeRuntimeForm(input: NodeRuntimeFormInput): JsonObject {
        val runnable = input.cliLauncherPresent && input.cliLauncherExecutable && input.runtimeLibraryPresent
        val reason = when {
            !input.cliLauncherPresent ->
                "独立 CLI 启动器尚未提取；首次使用 Node CLI/npm 时由 Node24Tools.prepare 复制到运行时目录"
            !input.cliLauncherExecutable -> "启动器存在但没有可执行位"
            !input.runtimeLibraryPresent -> "启动器缺少同目录的 libnode.so，无法独立运行"
            else -> "同目录已有 libnode.so 与可执行启动器，可按需启动独立 CLI"
        }
        return ToolkitIo.json(
            "runtimeMode" to "embedded-shared-library",
            "runtimeModeNote" to "Node 以 libnode.so 载入 :node24_N 应用进程；默认形态不是独立 node 进程。",
            "hostProcess" to input.processName,
            "hostExecutable" to input.hostExecutablePath,
            "hostExecutablePresent" to input.hostExecutablePresent,
            "hostExecutableNote" to "Android 由 zygote/app_process 启动应用进程，因此 process.execPath 指向 " +
                "app_process64/32；不能据此判断「这是 Node CLI」，也不要据此启动子脚本。",
            "standaloneCli" to ToolkitIo.json(
                "assetPath" to "node24/tools/node",
                "kind" to "elf-cli-launcher",
                "requires" to listOf("libnode.so", "libc++_shared.so"),
                "runtimeDirectory" to input.runtimeDirectoryPath,
                "cliLauncher" to input.cliLauncherPath,
                "cliLauncherPresent" to input.cliLauncherPresent,
                "cliLauncherExecutable" to input.cliLauncherExecutable,
                "runtimeLibrary" to input.runtimeLibraryPath,
                "runtimeLibraryPresent" to input.runtimeLibraryPresent,
                "toolsDirectory" to input.toolsDirectoryPath,
                "toolsDirectoryPresent" to input.toolsDirectoryPresent,
                "runnable" to runnable,
                "reason" to reason
            ),
            "canSpawnStandaloneNode" to runnable,
            "supportedChildScriptRunner" to buildList {
                add("mcp:run_script engine=nodejs（嵌入式进程池，始终可用）")
                if (runnable && input.cliLauncherPath != null) add("cli:" + input.cliLauncherPath + " <script.js>")
            },
            "processExecPathNote" to "不要用 process.execPath 判断运行形态；以本对象为准。",
            // task-99：打包期按 features.nodejs=disabled 剔除 node24 资源时，这里降级为一行（**有资源时逐字不变**：
            // 两个 resolver 读的是 assets 里的 pins，读不到才为 null ⇒ 只有「资源真没随包」才会出现 absent）。
            "node24" to if (input.runtimeDirectoryPath == null && input.toolsDirectoryPath == null)
                "absent(资源未随包)" else "present"
        )
    }

    /** 只读推导运行时目录 app_node24-runtime/<libnode.so sha256 前16位>；不创建目录、不执行文件。 */
    private fun resolveRuntimeDirectory(context: Context): File? = runCatching {
        val pins = context.assets.open("$NODE_ASSET_PREFIX/runtime.json").use { input ->
            org.json.JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
        val libnode = pins.optString("libnode.so")
        if (libnode.length < 16) return null
        val base = context.filesDir.parentFile ?: return null
        File(File(base, "app_node24-runtime"), libnode.substring(0, 16))
    }.getOrNull()

    /** CLI/npm 工具目录 app_node24-tools/<npm.zip sha256 前16位>（Node24Tools.prepare 的落点）。 */
    private fun resolveToolsDirectory(context: Context): File? = runCatching {
        val pins = context.assets.open("node24/tools/tools.json").use { input ->
            org.json.JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
        val npm = pins.optString("npm.zip")
        if (npm.length < 16) return null
        val base = context.filesDir.parentFile ?: return null
        File(File(base, "app_node24-tools"), npm.substring(0, 16))
    }.getOrNull()

    private fun nodeRuntimeFormInput(context: Context, processName: String): NodeRuntimeFormInput {
        val hostExecutable = listOf(File("/system/bin/app_process64"), File("/system/bin/app_process32"))
            .firstOrNull { it.exists() }
        val runtimeDirectory = resolveRuntimeDirectory(context)
        val cliLauncher = runtimeDirectory?.let { File(it, "node") }
        val runtimeLibrary = runtimeDirectory?.let { File(it, "libnode.so") }
        val toolsDirectory = resolveToolsDirectory(context)
        return NodeRuntimeFormInput(
            processName = processName,
            hostExecutablePath = hostExecutable?.path,
            hostExecutablePresent = hostExecutable != null,
            runtimeDirectoryPath = runtimeDirectory?.path,
            cliLauncherPath = cliLauncher?.path,
            cliLauncherPresent = cliLauncher?.isFile == true,
            cliLauncherExecutable = cliLauncher?.canExecute() == true,
            runtimeLibraryPath = runtimeLibrary?.path,
            runtimeLibraryPresent = runtimeLibrary?.isFile == true,
            toolsDirectoryPath = toolsDirectory?.path,
            toolsDirectoryPresent = toolsDirectory?.isDirectory == true
        )
    }

    /** Pure builder; tests can verify the contract without an Android Context. */
    internal fun buildSnapshot(
        capturedAt: Long,
        processName: String,
        pid: Int,
        rhinoAvailable: Boolean,
        poolCapacity: Int,
        nodeProcesses: List<NodeProcessObservation>,
        runtimePinCount: Int,
        lastFailure: JsonObject?,
        nodeRuntimeForm: JsonObject? = null
    ): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("capturedAt", capturedAt)
        add("process", JsonObject().apply {
            addProperty("name", processName)
            addProperty("pid", pid)
        })
        add("engines", JsonArray().apply {
            add(engine("rhino", rhinoAvailable))
            add(engine("quickjs", true))
            add(engine("nodejs", true))
        })
        add("nodePool", JsonObject().apply {
            addProperty("capacity", poolCapacity)
            add("busy", JsonNull.INSTANCE)
            addProperty("busyKnown", false)
            addProperty("scope", "process-local")
            addProperty("scopeDetail", "池的静态计数只在各自分配进程内可见，当前快照无法确认所有进程的脚本占用")
            addProperty("capacitySource", "Node24Pool.capacity()")
            add("knownProcesses", JsonArray().apply {
                nodeProcesses.forEach { observation ->
                    add(JsonObject().apply {
                        addProperty("process", observation.process)
                        addProperty("slot", observation.slot)
                        val observedPid = observation.pid
                        if (observedPid != null) addProperty("pid", observedPid)
                        else add("pid", JsonNull.INSTANCE)
                        addProperty("observed", observation.observed)
                    })
                }
            })
            addProperty("pidSource", "ActivityManager.runningAppProcesses（仅同 UID）")
            addProperty("note", "busy=null 表示本进程无法确认全局占用，不能当成 0 个空闲；not_visible 可能是进程未启动或系统未返回。")
        })
        add("runtime", JsonObject().apply {
            addProperty("nodeVersion", NODE_VERSION)
            addProperty("nodeVersionSource", "构建源码元数据（Node24Service），不是本轮运行探测")
            addProperty("bundledMetadataPresent", runtimePinCount > 0)
            addProperty("runtimePins", runtimePinCount)
            addProperty("assetPrefix", NODE_ASSET_PREFIX)
            addProperty("hashRead", false)
            addProperty("integrityVerified", false)
            addProperty("scriptsStarted", false)
            add("cache", JsonObject().apply {
                addProperty("metadataCache", "Node24Libraries/Node24RuntimeFileCache")
                addProperty("statsLogTag", NODE_CACHE_LOG_TAG)
                addProperty("statsHint", "adb logcat -s $NODE_CACHE_LOG_TAG（在 :node24_N 进程查看）")
                addProperty("speedMeasured", false)
            })
            addProperty("note", "只检查很小的 runtime.json 是否存在；不读取/校验 106MB 运行时，不启动新 Node 脚本，也不声称已测速。")
            if (nodeRuntimeForm != null) add("nodeRuntimeForm", nodeRuntimeForm)
        })
        if (lastFailure == null) add("lastFailure", JsonNull.INSTANCE)
        else add("lastFailure", lastFailure)
    }

    private fun engine(name: String, available: Boolean) = JsonObject().apply {
        addProperty("engine", name)
        addProperty("available", available)
        addProperty("behaviorStatus", "unverified")
        addProperty("probeEvidence", "shape")
        addProperty("surface", "global/require(module)")
        addProperty("note", if (available) {
            "构建内置/开关允许；本轮没有真实调用证据，behaviorStatus 保持 unverified"
        } else {
            "当前构建未启用（RHINO_COMPAT=false）"
        })
    }

    private fun currentProcessName(context: Context): String =
        readCmdline(File("/proc/self")) ?: context.packageName

    private fun observeNodeProcesses(context: Context, capacity: Int): List<NodeProcessObservation> {
        val packageName = context.packageName
        val running = scanProcesses(context)
        val suffixes = ArrayList<Pair<String, String>>()
        suffixes.add(":node24" to "legacy")
        for (index in 1..capacity) suffixes.add(":node24_$index" to "slot$index")
        return suffixes.map { (suffix, slot) ->
            val process = packageName + suffix
            val pid = running[process]
            NodeProcessObservation(process, slot, pid, if (pid == null) "not_visible" else "alive")
        }
    }

    private fun scanProcesses(context: Context): Map<String, Int> = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.runningAppProcesses.orEmpty().filter { it.uid == Process.myUid() }
            .associate { it.processName to it.pid }
    }.getOrDefault(emptyMap())

    private fun readCmdline(pidDir: File): String? = runCatching {
        File(pidDir, "cmdline").inputStream().use { input ->
            val buffer = ByteArray(256)
            val count = input.read(buffer)
            if (count <= 0) null
            else String(buffer, 0, count, Charsets.UTF_8).substringBefore('\u0000').takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun runtimePinCount(context: Context): Int = runCatching {
        context.assets.open("$NODE_ASSET_PREFIX/runtime.json").use { input ->
            val metadata = org.json.JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
            metadata.length()
        }
    }.getOrDefault(0)
}
