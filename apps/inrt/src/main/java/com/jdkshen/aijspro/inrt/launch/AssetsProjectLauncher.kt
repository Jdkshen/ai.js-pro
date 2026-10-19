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
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecutionTask
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
import com.stardust.util.MD5

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
    private val mProjectDir: String = File(mActivity.filesDir, "project/").path
    private val mProjectConfig: ProjectConfig = ProjectConfig.fromAssets(mActivity, ProjectConfig.configFileOfDir(mAssetsProjectDir))
    private val mMainScriptFile: File = File(mProjectDir, mProjectConfig.mainScriptFile)
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var mScriptExecution: ScriptExecution? = null

    init {
        prepare()
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
            val config = ExecutionConfig(
                workingDirectory = mProjectDir,
                scriptConfig = mProjectConfig.getScriptConfig(normalized))
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
            // task-115：ui.layout 项目改由 :script 内的 ScriptExecuteActivity 承载，透明 trampoline
            // 退场（否则脚本结束/失败后露出空白底）。headless 的 ImGui/浮窗项目保持 trampoline 存活。
            if (InrtScriptThreadHost.isScriptThread() && wantsScriptExecuteActivity(source)) {
                activity?.finish()
            }
        } catch (e: Exception) {
            AutoJs.instance.globalConsole.error(e)
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
                + " uiLayout=" + hasUiLayoutCall(source) + " resLayout=" + hasLayoutResources())
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
            return UI_LAYOUT_PATTERN.containsMatchIn(text)
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
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
        extractScriptPackIfPresent()
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
            // 告诉解密入口「这个产物是新方案的」：优先交给原生库解密（密钥不落到 Java 侧）。
            EncryptedScripts.setHardenedParams(projectConfig.packageName, salt, fingerprint)
        } else {
            // 老产物：沿用旧的推导方式，保证升级运行端后仍能打开。
            key = ScriptKeyDerivation.legacyKey(projectConfig.packageName,
                    projectConfig.versionName, projectConfig.mainScriptFile)
            vector = ScriptKeyDerivation.legacyVector(
                    projectConfig.buildInfo.buildId, projectConfig.name)
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
