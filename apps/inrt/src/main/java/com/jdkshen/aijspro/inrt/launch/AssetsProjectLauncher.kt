package com.jdkshen.aijspro.inrt.launch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log

import com.jdkshen.aijspro.inrt.BuildConfig
import com.jdkshen.aijspro.inrt.LogActivity
import com.jdkshen.aijspro.inrt.Pref
import com.jdkshen.aijspro.inrt.autojs.AutoJs
import com.jdkshen.aijspro.inrt.script.InrtScriptThreadHost
import com.stardust.autojs.engine.encryption.ScriptEncryption
import com.stardust.autojs.engine.encryption.ScriptPayloadV2
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecutionTask
import com.stardust.autojs.core.ui.inflater.CompiledProjectResources
import com.stardust.autojs.execution.ScriptHostRouting
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.project.AppSignature
import com.stardust.autojs.project.ProjectConfig
import com.stardust.autojs.project.ScriptKeyDerivation
import com.stardust.autojs.project.ScriptProtection
import com.stardust.autojs.script.EncryptedScripts
import com.stardust.autojs.script.JavaScriptFileSource
import com.stardust.autojs.script.JavaScriptSource
import com.stardust.autojs.script.ScriptSource
import com.stardust.autojs.script.StringScriptSource
import com.stardust.pio.PFiles
import com.stardust.pio.UncheckedIOException

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Field
import java.util.zip.ZipInputStream

/**
 * Created by Stardust on 2018/1/24.
 */

open class AssetsProjectLauncher(private val mAssetsProjectDir: String, private val mActivity: Context) {
        /**
         * task-140: the last staged-resource warning (our actionable sentence), for whoever prints the
         * first console line of the packaged app (LogActivity). Empty when nothing was wrong.
         */
        // 无 @JvmStatic：本类的这个字段只有同包 Kotlin 用它（`@JvmStatic` 只能在 companion/object 里，会编译不过）。
        var lastStagedResourceWarning: String? = null
            private set

        /** task-150: 悬浮窗未授权时的可执行提示（打印后清空；已授权/普通脚本恒为 null）。 */
        var overlayPermissionWarning: String? = null
            private set

    private val mProjectDir: String = File(mActivity.filesDir, "project/").path
    private val mProjectConfig: ProjectConfig = ProjectConfig.fromAssets(mActivity, ProjectConfig.configFileOfDir(mAssetsProjectDir))
    private val mMainScriptFile: File = File(mProjectDir, mProjectConfig.mainScriptFile)
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var mScriptExecution: ScriptExecution? = null

    /**
     * 打包版启动打印「引擎指纹」：`.4` 报 `require("./x.js") outside of a module` 时，
     * 第一件事就是确认产物里带的引擎有没有 dev-1401 的 require 根修复。
     */
    private fun logEngineFingerprint() {
        try {
            val bytes = mActivity.assets.open("init.js").use { it.readBytes() }
            val text = String(bytes, Charsets.UTF_8)
            val hasFix = text.contains("__ajsSetRequireRoot")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            val sha = digest.joinToString("") { String.format("%02x", it) }.take(16)
            val storage = mProjectConfig.scriptStorage ?: "default"
            Log.i(TAG, "C-INRT: engine requireRootFix=$hasFix initJsSha256=$sha scriptStorage=$storage")
        } catch (e: Throwable) {
            Log.w(TAG, "C-INRT: engine fingerprint unavailable: $e")
        }
    }

    init {
        android.util.Log.e("CINRT-PROBE", "init enter -> calling prepare()")
        logEngineFingerprint()
        prepare()
        android.util.Log.e("CINRT-PROBE", "init -> prepare() returned ok")
        // task-113 B：工程解压后、在脚本线程上把工程 dex/native 清单准备好（Pro 顺序 dex->so）。
        prepareProjectRuntime()
    }

    private fun prepareProjectRuntime() {
        try {
            if (InrtScriptThreadHost.isInitialized() && InrtScriptThreadHost.isScriptThread()) {
                InrtScriptThreadHost.prepareProjectOnScriptThread(mActivity)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "C-INRT: prepareProjectOnScriptThread failed (continue)", e)
        }
    }

    /** task-160：定时任务/前台服务里直接运行工程入口脚本（无 Activity）。 */
    fun launchHeadless() {
        launchHeadlessScript(null)
    }

    /** task-162：按任务 path 执行工程内具体脚本；null/空/main 时仍执行工程入口。 */
    fun launchHeadlessScript(scriptPath: String?) {
        // 定时任务每次独立执行；LogActivity 可能已持有上一次 ScriptExecution，
        // 不复位会被 runScript 的 isDestroyed 守卫直接 return，导致到点"拉起"实际没跑。
        mScriptExecution = null
        val normalized = scriptPath?.trim().orEmpty()
        if (normalized.isEmpty() || normalized == mProjectConfig.mainScriptFile) {
            runScript(null)
            return
        }
        try {
            val file = File(normalized).let { if (it.isAbsolute) it else File(mProjectDir, normalized) }
            if (!file.isFile) {
                AutoJs.instance?.globalConsole?.error("[task] 找不到任务脚本：" + file.absolutePath)
                return
            }
            Log.i(TAG, "C-INRT: run task script=" + file.absolutePath)
            val source = JavaScriptFileSource("task", file)
            // task-78：headless（定时任务/服务）没有宿主 Activity ⇒ 脚本里的 `activity.*` 会 ReferenceError。
            // 这里给**明确**文案，不伪造 activity 对象（界面启动路径：SplashActivity/LogActivity/InrtScriptActivity）。
            if (ScriptHostRouting.textUsesActivityApi(readScriptText(source))) {
                Log.w(TAG, "C-INRT: headless 执行没有宿主 Activity，脚本里的 activity.* 会 ReferenceError；"
                        + "如需 activity 请用界面启动。script=" + file.absolutePath)
            }
            val config = ExecutionConfig(
                workingDirectory = mProjectDir,
                scriptConfig = mProjectConfig.getScriptConfig(normalized))
            reportOverlayPermissionIfNeeded(source)
            mScriptExecution = executeSource(source, config)
        } catch (error: Throwable) {
            AutoJs.instance?.globalConsole?.error(error)
        }
    }

    fun launch(activity: Activity) {
        //如果需要隐藏日志界面，则直接运行脚本
        if (mProjectConfig.launchConfig.shouldHideLogs() || Pref.shouldHideLogs()) {
            runScript(activity)
        } else {
            //如果不隐藏日志界面
            //如果当前已经是日志界面则直接运行脚本
            if (activity is LogActivity) {
                runScript(null)
            } else {
                //否则显示日志界面并在日志界面中运行脚本
                mHandler.post {
                    activity.startActivity(Intent(mActivity, LogActivity::class.java)
                            .putExtra(LogActivity.EXTRA_LAUNCH_SCRIPT, true))
                    activity.finish()
                }
            }
        }
    }

    private fun runScript(activity: Activity?) {
        if (mScriptExecution != null && mScriptExecution!!.engine != null &&
                !mScriptExecution!!.engine.isDestroyed) {
            return
        }
        try {
            // 入口脚本可能是加密/编译产物：用统一入口还原成可执行的脚本源。
            // 以前解密只做在 Rhino 引擎里，QuickJS 引擎的打包应用会拿密文当源码解析。
            val source = if (ScriptProtection.usesNativeStorage(mProjectConfig.scriptStorage)) {
                // 产物里没有脚本文件：载荷嵌在原生库尾部，由原生代码读自己的尾部取回并解密。
                EncryptedScripts.toSourceFromLibrary("main")
                        ?: throw IllegalStateException("产物里读不到内嵌脚本（原生库缺少脚本载荷或被截断）")
            } else {
                EncryptedScripts.toSource(mMainScriptFile, "main")
                        ?: JavaScriptFileSource("main", mMainScriptFile)
            }
            source.setPreferredEngine(mProjectConfig.getEngine(mProjectConfig.mainScriptFile))
            val config = ExecutionConfig(
                    workingDirectory = mProjectDir,
                    scriptConfig = mProjectConfig.getScriptConfig(mProjectConfig.mainScriptFile))
            if (source.executionMode and JavaScriptSource.EXECUTION_MODE_UI != 0) {
                config.intentFlags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME
            } else {
                activity?.finish()
            }
            mScriptExecution = executeSource(source, config)
            // task-133/140：把「资源包被丢弃/不匹配」写进**用户能看到的控制台**（LogActivity），
            // 而不只是 logcat —— 否则用户只看到白屏，比原来那句难懂报错更难排查（K40 dev-1479 实测）。
            // task-150：启动期要给用户看的可执行提示（资源包 stale / 悬浮窗未授权），逐条进控制台。
            for (startupWarning in listOfNotNull(lastStagedResourceWarning, overlayPermissionWarning)) {
                runCatching {
                    // inrt 的包装只有 `instance`（companion 里的 lateinit var），没有 getInstance()；
                    // 与同文件既有写法（AutoJs.instance?.globalConsole?.error(...)）保持一致。
                    com.jdkshen.aijspro.inrt.autojs.AutoJs.instance.globalConsole
                            ?.println(android.util.Log.WARN, startupWarning)
                }
            }
            lastStagedResourceWarning = null
            overlayPermissionWarning = null
            // task-115：ui.layout 项目改由 :script 内的 ScriptExecuteActivity 承载，透明 trampoline
            // 退场（否则脚本结束/失败后露出空白底）。headless 的 ImGui/浮窗项目保持 trampoline 存活。
            if (InrtScriptThreadHost.isScriptThread() && wantsScriptExecuteActivity(source)) {
                activity?.finish()
            }
        } catch (e: Exception) {
            // task-159：无条件留痕（即使 console 不可用也永远有 logcat）。
            // 注意：`?.` 保护不了 lateinit —— AutoJs.instance 未初始化时 `AutoJs.instance?.` 照样抛
            // UninitializedPropertyAccessException，所以这里用 runCatching（与 :189-194 同口径，消灭两套写法）。
            // logcat 会把 TAG 渲染成 `C-INRT  :`（冒号前有空格）⇒ grep 请搜正文 `CINRT runScript failed`。
            android.util.Log.e(TAG, "CINRT runScript failed", e)
            runCatching { AutoJs.instance.globalConsole?.error(e) }
                    .onFailure { android.util.Log.w(TAG, "CINRT console delivery failed", it) }
        }

    }

    /**
     * task-113 B：:script 进程里把引擎初始化/执行放到脚本线程（= :script 进程主 Looper）。
     *
     * <p>在 :script 进程内用引擎的 executeUiOnMainThread（等价于 Pro 打包运行时里跑在脚本进程
     * 主线程的脚本宿主，但不启动 Activity）：engine.init()、工程 dex/so、第三方 native
     * （libImGuiBridge.so 的 JNI_OnLoad / ImGui context）与脚本执行因此全部同线程，GImGui 不会跨线程。
     * 主进程/旧入口（非脚本线程）保持原来的后台线程执行路径。
     */
    private fun executeSource(source: ScriptSource, config: ExecutionConfig): ScriptExecution {
        val service = AutoJs.instance.scriptEngineService
        val process = InrtScriptThreadHost.getProcessName(mActivity)
        val thread = Thread.currentThread()
        val uiMode = isUiModeScript(source)
        val route = when {
            !InrtScriptThreadHost.isScriptThread() -> "legacy"
            wantsScriptExecuteActivity(source) -> "ScriptExecuteActivity"
            else -> "headless"
        }
        Log.i(TAG, "C-INRT: route=" + route + " process=" + process + " thread=" + thread.name + "/" + thread.id
                + " engine=" + source.engineName + " uiMode=" + uiMode
                + " uiLayout=" + hasUiLayoutCall(source) + " resLayout=" + hasLayoutResources()
                + " activityApi=" + ScriptHostRouting.textUsesActivityApi(readScriptText(source)))
        if (route == "ScriptExecuteActivity") {
            // task-115：:script 内启动引擎 UI 宿主（inrt manifest 已把 ScriptExecuteActivity 挂 :script）。
            // ui.layout 的布局由 Activity 承载，引擎按引擎原路径在 Activity 自己的线程执行（与主进程
            // 旧 inrt 路径一致）；不能走 headless main Looper，否则没有 content view 会白屏。
            return service.execute(source, config)
        }
        if (route == "headless") {
            try {
                return service.executeUiOnMainThread(ScriptExecutionTask(source, null, config))
            } catch (e: Throwable) {
                Log.w(TAG, "C-INRT: headless execute failed, fallback background execution", e)
            }
        }
        return service.execute(source, config)
    }

    private fun isUiModeScript(source: ScriptSource): Boolean {
        return source is JavaScriptSource &&
                (source.executionMode and JavaScriptSource.EXECUTION_MODE_UI) != 0
    }

    /**
     * 是否需要 ScriptExecuteActivity 承载 UI。
     *
     * <p>不能只看 executionMode：ImGui/浮窗项目同样写 {@code "ui";}（例如 AutoJS_ImGui 的
     * main.js），但它们必须留在 :script 脚本线程 headless 执行，否则 ImGui context 又跨线程。
     * 判据是主脚本正文里是否调用 ui.layout / ui.layoutFile / ui.inflate（模块里的调用不算）；
     * 文本不可读（Rhino 编译类 / QuickJS 字节码）时退化为工程特征：带 ImGui 原生库的一律
     * headless，有 res/layout 资源的才当 ui.layout 项目。
     */
    private fun wantsScriptExecuteActivity(source: ScriptSource): Boolean {
        if (!isUiModeScript(source)) {
            return false
        }
        val text = readScriptText(source)
        if (text != null) {
            // task-78：判据抽到 engine 的 ScriptHostRouting（纯函数、有单测）。`ui.layout` 家族 **或** `activity.*`
            // 都需要 Activity 宿主 —— 后者以前被判 headless ⇒ 打包版没有 `activity` 全局（ReferenceError），
            // 用户实报的「请求悬浮窗权限出错」就是这条（它的 catch 把 ReferenceError 吞了）。
            return ScriptHostRouting.needsActivityHost(
                    isUiMode = true,
                    hasUiLayoutCall = ScriptHostRouting.textUsesUiLayout(text),
                    usesActivityApi = ScriptHostRouting.textUsesActivityApi(text),
                    hasImGuiMarker = hasImGuiMarker())
        }
        if (hasImGuiMarker()) {
            return false
        }
        return hasLayoutResources()
    }

    private fun hasUiLayoutCall(source: ScriptSource): Boolean {
        val text = readScriptText(source) ?: return false
        return UI_LAYOUT_PATTERN.containsMatchIn(text)
    }

    private fun readScriptText(source: ScriptSource): String? {
        return when (source) {
            is StringScriptSource -> source.script
            is JavaScriptFileSource -> try {
                source.file?.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "C-INRT: read main script text failed", e)
                null
            }
            else -> null
        }
    }

    private fun hasLayoutResources(): Boolean {
        val layoutDir = File(mProjectDir, "res/layout")
        if (!layoutDir.isDirectory) {
            return false
        }
        return layoutDir.listFiles()?.any { it.isFile && it.name.endsWith(".xml") } == true
    }

    /** ImGui/浮窗类项目的特征文件（工程 lib 下）。 */
    private fun hasImGuiMarker(): Boolean {
        return findProjectFile(File(mProjectDir), 0, setOf("libImGuiBridge.so", "ImGuiBridge.dex"))
    }

    private fun findProjectFile(dir: File, depth: Int, names: Set<String>): Boolean {
        if (depth > 4 || !dir.isDirectory) {
            return false
        }
        val children = dir.listFiles() ?: return false
        for (child in children) {
            if (child.isFile && names.contains(child.name)) {
                return true
            }
        }
        for (child in children) {
            if (child.isDirectory && findProjectFile(child, depth + 1, names)) {
                return true
            }
        }
        return false
    }

    private fun prepare() {
        val projectConfigPath = PFiles.join(mProjectDir, ProjectConfig.CONFIG_FILE_NAME)
        val projectConfig = ProjectConfig.fromFile(projectConfigPath)
        if (!BuildConfig.DEBUG && projectConfig != null &&
                TextUtils.equals(projectConfig.buildInfo.buildId, mProjectConfig.buildInfo.buildId)) {
            initKey(projectConfig)
            return
        }
        initKey(mProjectConfig)
        PFiles.deleteRecursively(File(mProjectDir))
        try {
            PFiles.copyAssetDir(mActivity.assets, mAssetsProjectDir, mProjectDir, null)
        // task-133/140: stale project resource pack must not be handed to the script; say so where users look.
        reportStaleResourcePackIfAny()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
        android.util.Log.e("CINRT-PROBE", "prepare -> about to extractScriptPackIfPresent()")
        extractScriptPackIfPresent()
        android.util.Log.e("CINRT-PROBE", "prepare -> extractScriptPackIfPresent() returned ok")
    }

    /**
     * 展开目录工程的脚本容器（{@code assets/project/scripts.pack}）。
     *
     * <p>打包端把除入口脚本外的所有脚本打成 zip、整体加密成一个文件，所以解包 APK
     * 看不到任何脚本文件；这里在复制完资产后解密并展开到私有目录，之后模块加载
     * （require）看到的目录布局与原来的打包方式完全一致。
     */
    private fun extractScriptPackIfPresent() {
        val pack = File(mProjectDir, ScriptProtection.SCRIPT_PACK_FILE)
        if (!pack.isFile) {
            return
        }
        val payload = try {
            EncryptedScripts.decryptPayload(PFiles.readBytes(pack.path))
        } catch (error: Exception) {
            android.util.Log.e("CINRT-PROBE", "script pack decrypt failed: " + pack.path, error)
            throw IllegalStateException("脚本容器无法解密：${pack.path}", error)
        } ?: throw IllegalStateException("脚本容器不完整（不是有效的加密载荷）：${pack.path}")
        unzipInto(payload, File(mProjectDir))
        //ignore delete
        pack.delete()
    }

    private fun unzipInto(bytes: ByteArray, targetDir: File) {
        val root = targetDir.canonicalPath + File.separator
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(targetDir, entry.name)
                if (!target.canonicalPath.startsWith(root)) {
                    throw IllegalStateException("脚本容器包含非法路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    /** task-126：产物主脚本头是不是 v2（读明文头 24 字节；三级判定与 ScriptPayloadV2.isV2 同款）。 */
    private fun isV2Payload(projectConfig: ProjectConfig): Boolean = runCatching {
        val main = File(mProjectDir, projectConfig.mainScriptFile)
        if (!main.isFile) return@runCatching false
        val head = ByteArray(ScriptPayloadV2.HEADER_LENGTH)
        val read = main.inputStream().use { it.read(head) }
        read > 0 && ScriptPayloadV2.isV2(if (read == head.size) head else head.copyOf(read))
    }.getOrElse { error ->
        Log.w(TAG, "C-INRT: 读主脚本头判断 v2 失败，按 v1 处理：$error")
        false
    }

    /**
     * task-133/140: if the staged build/project-res.apk sidecar was compiled for another host, drop it
     * (do NOT hand it to the script) and say so with OUR actionable message. Same digest + same sentence
     * as CompiledProjectResources (single source). Default: sidecar missing or matching => do nothing.
     */
    private fun reportStaleResourcePackIfAny() {
        // task-140：打包期「已丢弃 stale 包」这条路径产物里没有资源包文件，只留一个极小标记；
        // 存在就把同一句写进控制台（并删标记），否则用户仍然只看到白屏（K40 dev-1479 实测）。
        val resApk = File(mProjectDir, "build/project-res.apk")
        val staleMarker = File(mProjectDir, "build/project-res.stale.json")
        if (staleMarker.isFile) {
            val marker = runCatching { org.json.JSONObject(staleMarker.readText()) }.getOrNull()
            // task-141：标记里**只有两个摘要**（没有 message —— 打包期文案带的是打包机工作区路径，
            // 用户在设备上看到会困惑）⇒ 一律用**运行时**路径 + 同一个函数现拼（不抄第二份）。
            val expectedDigest = marker?.optString("expectedHostDigest", marker?.optString("expected", null))
                ?.takeIf { it.isNotBlank() }
            val actualDigest = marker?.optString("actualHostDigest", marker?.optString("actual", null))
                ?.takeIf { it.isNotBlank() }
            val message = if (expectedDigest != null && actualDigest != null) {
                CompiledProjectResources.staleResourcePackMessage(expectedDigest, actualDigest, resApk)
            } else {
                // 摘要不全 ⇒ 不猜（沿用「判不出来就别动」的口径），但仍要点名 + 给可执行修法。
                "工程资源包不可用：打包期已丢弃 stale 资源包，且标记里摘要不全（无法给出期望/当前宿主）。" +
                        "已忽略该资源包（回落到无工程资源）；修法：对当前设备上的宿主重新编译 project-res.apk" +
                        "（MCP: android_resources_compile），再重新打包。"
            }
            Log.w(TAG, message)
            lastStagedResourceWarning = message
            Log.w(TAG, "C-INRT: stale project-res marker found (packaging-time drop), reported to console")
            staleMarker.delete()
            return
        }
        val resJson = File(mProjectDir, "build/project-res.json")
        if (!resApk.isFile || !resJson.isFile) {
            return
        }
        val expected = runCatching {
            org.json.JSONObject(resJson.readText()).optString("appResourcesSha256", null)
        }.getOrNull()?.trim()?.lowercase()
        if (expected.isNullOrEmpty()) {
            return
        }
        val actual = CompiledProjectResources.hostResourcesSha256OrNull(mActivity.applicationInfo.sourceDir)
        // Lead 口径（与打包期一致）：**判不出来就别动** ——
        // actual 取不到时宁可放行（维持今天行为），只在「确定不一致」时丢弃，避免误伤可用的资源包。
        if (actual == null || actual.equals(expected, ignoreCase = true)) {
            return
        }
        val message = CompiledProjectResources.staleResourcePackMessage(expected, actual, resApk)
        Log.w(TAG, message)
        lastStagedResourceWarning = message
        val droppedApk = quarantine(resApk)
        val droppedJson = quarantine(resJson)
        val dropped = droppedApk && droppedJson
        Log.w(TAG, "C-INRT: stale project-res pack quarantine apk=" + droppedApk + " json=" + droppedJson)
        if (!dropped) {
            // 如实：没移走就不能说「已丢弃」——否则提示与事实自相矛盾（用户看到提示仍白屏）。
            val warningSuffix = "（注意：资源包未能移走，可能仍会被脚本读到；请手动清理 " + resApk + "）"
            lastStagedResourceWarning = (message + warningSuffix)
        }
    }

    /**
     * task-150：**只在确实需要悬浮窗时**检查一次 `Settings.canDrawOverlays`；未授权就把我们的可执行文案
     * 放进待打印列表（控制台）。判据走 engine 的纯函数 ScriptHostRouting（有单测）。
     * 不跳系统设置页、不伪造权限、已授权或普通脚本 **零行为变化**。
     */
    private fun reportOverlayPermissionIfNeeded(source: JavaScriptSource) {
        val needed = runCatching {
            val text = readScriptText(source)
            val isUiMode = (source.executionMode and JavaScriptSource.EXECUTION_MODE_UI) != 0
            // Node UI 产物：主脚本名形如 *.node.js（样例证据 main.node.js / dist/main.node.js）。
            val isNodeUiProject = mProjectConfig.mainScriptFile.lowercase().contains(".node.js")
            ScriptHostRouting.needsOverlayPermission(isUiMode,
                    ScriptHostRouting.textUsesOverlayApi(text),
                    ScriptHostRouting.textUsesImGui(text),
                    isNodeUiProject)
        }.getOrElse { error ->
            Log.w(TAG, "C-INRT: overlay 判据失败（忽略）：$error")
            false
        }
        if (!needed) {
            return
        }
        val granted = runCatching {
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                    android.provider.Settings.canDrawOverlays(mActivity)
        }.getOrDefault(true)
        if (granted) {
            return
        }
        val message = "本产物需要「显示在其他应用上层」权限：请在 系统设置 → 应用 → " +
                mActivity.applicationInfo.loadLabel(mActivity.packageManager) +
                " → 显示在其他应用上层 打开后重试；脚本里也可用 floaty.checkPermission() 引导用户授权。"
        Log.w(TAG, "C-INRT: overlay permission missing => " + message)
        overlayPermissionWarning = message
    }

    /**
     * task-152 (B级【6】): 丢弃 stale 资源包 = 先 delete，失败则**改名**到同目录 .stale-<ts>；
     * 只有「文件确实不在原路径了」才算丢弃成功（否则提示必须如实说清）。
     */
    private fun quarantine(file: File): Boolean {
        if (!file.exists()) {
            return true
        }
        if (file.delete()) {
            return true
        }
        val renamed = File(file.parentFile, file.name + ".stale-" + System.currentTimeMillis())
        return runCatching { file.renameTo(renamed) }.getOrDefault(false)
    }

    private fun initKey(projectConfig: ProjectConfig) {
        // 新方案（有随机盐 + 签名指纹）：密钥绑定到打包时的签名身份。
        // 用别的证书重签（重打包/二次修改）时指纹对不上，直接拒绝运行，
        // 而不是解密失败后抛一堆看不懂的 padding 错误。
        val salt = projectConfig.scriptSalt
        val fingerprint = projectConfig.signatureFingerprint
        val key: String
        val vector: String
        if (!salt.isNullOrEmpty() && !fingerprint.isNullOrEmpty()) {
            val actual = AppSignature.certificateFingerprint(mActivity)
            if (actual != null && !actual.equals(fingerprint, ignoreCase = true)) {
                throw IllegalStateException(
                        "签名与打包时不一致，无法解密脚本（打包时=$fingerprint，当前=$actual）")
            }
            key = ScriptKeyDerivation.deriveKey(projectConfig.packageName, salt, fingerprint)
            vector = ScriptKeyDerivation.deriveVector(salt, fingerprint)
            // task-126：v2（AES-256-GCM）密钥 —— 与打包端**同一份派生**（真 32 字节）。
            ScriptEncryption.setKeyV2(
                    ScriptPayloadV2.deriveKeyV2(projectConfig.packageName, salt, fingerprint))
            // 产物是 v2 时**不能**交给原生：script_crypto.c 只有 SHA-256 + AES-CBC，不含 GCM。
            if (isV2Payload(projectConfig)) {
                Log.i(TAG, "C-INRT: payload=v2 (AES-256-GCM) → JVM 路径（原生只认 v1）")
            } else {
                // v1：告诉解密入口「这个产物是老方案的」，优先交给原生库解密（密钥不落到 Java 侧）。
                EncryptedScripts.setHardenedParams(projectConfig.packageName, salt, fingerprint)
            }
        } else {
            // 老产物：沿用旧的推导方式，保证升级运行端后仍能打开。
            throw IllegalStateException(
                    "产物缺少 scriptSalt / signatureFingerprint：旧加密方案已下线（不再回退 MD5 派生）。" +
                            "请用新版打包器重新打包该工程。")
        }
        try {
            val fieldKey = ScriptEncryption::class.java.getDeclaredField("mKey")
            fieldKey.isAccessible = true
            fieldKey.set(null, key)
            val fieldVector = ScriptEncryption::class.java.getDeclaredField("mInitVector")
            fieldVector.isAccessible = true
            fieldVector.set(null, vector)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    companion object {
        private const val TAG = "C-INRT"

        /** 主脚本里的 ui.layout / ui.layoutFile / ui.inflate 调用（ImGui 模块里的不算）。 */
        private val UI_LAYOUT_PATTERN =
                Regex("""\bui\s*\.\s*(?:layout|inflate)[A-Za-z_]*\s*\(""")
    }

}
