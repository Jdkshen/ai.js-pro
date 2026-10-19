package com.jdkshen.aijspro

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.multidex.MultiDexApplication
import android.view.View
import android.widget.ImageView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.evernote.android.job.JobRequest
import com.flurry.android.FlurryAgent
import com.squareup.leakcanary.LeakCanary
import com.jdkshen.aijspro.external.HostScripts
import com.stardust.app.GlobalAppContext
import com.stardust.autojs.ScriptEngineService
import com.stardust.autojs.core.ui.inflater.ImageLoader
import com.stardust.autojs.core.ui.inflater.util.Drawables
import com.stardust.autojs.core.opencv.OpenCVHelper
import com.stardust.theme.ThemeColor
import com.tencent.bugly.Bugly
import com.tencent.bugly.crashreport.CrashReport
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.autojs.key.GlobalKeyObserver
import com.jdkshen.aijspro.external.receiver.DynamicBroadcastReceivers
import com.jdkshen.aijspro.model.sample.BundledSampleSync
import com.jdkshen.aijspro.theme.ThemeColorManagerCompat
import com.jdkshen.aijspro.timing.TimedTaskManager
import com.jdkshen.aijspro.timing.TimedTaskScheduler
import com.jdkshen.aijspro.tool.CrashHandler
import com.jdkshen.aijspro.ui.error.ErrorReportActivity
import java.lang.ref.WeakReference
import java.util.*

/**
 * Created by Stardust on 2017/1/27.
 */

class App : MultiDexApplication() {
    lateinit var dynamicBroadcastReceivers: DynamicBroadcastReceivers
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Android 9+ forbids sharing a WebView data directory across processes.
        // Configure before providers/onCreate can initialize WebView, including
        // QuickJS :script and Node :node24. Keep the main process's existing data.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val process = getProcessName()
            if (process != base.packageName) {
                android.webkit.WebView.setDataDirectorySuffix(process.replace(':', '_'))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        GlobalAppContext.set(this)
        instance = WeakReference(this)
        setUpStaticsTool()
        setUpDebugEnvironment()
        init()
        // MCP 自动启停：跟随前台服务状态自动起停内置的 MCP HTTP 服务。
        // miuix 源集已随 flavor 退役成为唯一界面线，这里直接引用，不再走反射探测。
        registerActivityLifecycleCallbacks(com.jdkshen.aijspro.mcp.McpAutoLifecycle(this))
        // 「Pro 扩展调试服务器」开关持久化：上次开着的话，回到前台自动拉起（以前每次重启 App 都要手动重点一次）。
        registerActivityLifecycleCallbacks(com.jdkshen.aijspro.pluginclient.DevPluginAutoStart(this))
        // 「外部 Intent 跑脚本」断点续跑：进程被系统杀掉后服务会把进程拉起来，
        // 但脚本不会自己跑（面板窗口消失）——这里补上自动重拉，见 ExternalRunResume。
        com.jdkshen.aijspro.external.ExternalRunResume.resumeIfNeeded(this)
    }

    private fun setUpStaticsTool() {
        if (BuildConfig.DEBUG)
            return
        FlurryAgent.Builder()
                .withLogEnabled(BuildConfig.DEBUG)
                .build(this, "D42MH48ZN4PJC5TKNYZD")
    }

    private fun setUpDebugEnvironment() {
        Bugly.isDev = false
        val crashHandler = CrashHandler(ErrorReportActivity::class.java)

        val strategy = CrashReport.UserStrategy(applicationContext)
        strategy.setCrashHandleCallback(crashHandler)

        CrashReport.initCrashReport(applicationContext, BUGLY_APP_ID, false, strategy)

        crashHandler.setBuglyHandler(Thread.getDefaultUncaughtExceptionHandler())
        Thread.setDefaultUncaughtExceptionHandler(crashHandler)
        if (LeakCanary.isInAnalyzerProcess(this)) {
            // This process is dedicated to LeakCanary for heap analysis.
            // You should not init your app in this process.
            return
        }
        //LeakCanary.install(this);

    }

    private fun init() {
        ThemeColorManagerCompat.init(this, ThemeColor(resources.getColor(R.color.colorPrimary), resources.getColor(R.color.colorPrimaryDark), resources.getColor(R.color.colorAccent)))
        AutoJs.initInstance(this)
        // 音量上键在脚本窗口（ScriptExecuteActivity / QuickJS overlay）聚焦时的停止入口：
        // 宿主层停 :script/:native 的任务；主进程引擎的 stopAll 由 ScriptEngineService 补。
        // 不依赖无障碍/root 的全局按键监听（HyperOS 上无障碍设置可能被系统重置）。
        ScriptEngineService.setVolumeUpStopAllHook { HostScripts.stopAllHostScripts() }
        OpenCVHelper.initIfNeeded(applicationContext) {
            AutoJs.getInstance().globalConsole.info(OpenCVHelper.getVersionInfo())
        }
        // Phase0 隔离脚本进程（:native）：只需要引擎本身。定时任务调度、动态广播、
        // 示例同步、无障碍监控这些都是主进程职责，在隔离进程里再跑一遍会重复触发。
        if (isNativeIsolationProcess()) {
            return
        }
        // task-111：冷启动先清理孤儿 floaty（含 :script 残留），再恢复悬浮菜单；顺序保证清理不误杀 circular menu。
        cleanupOrphanFloatiesBeforeRestore()
        // task-54：记住并恢复「悬浮窗显示意图 / 无障碍曾开启」状态。
        // 悬浮窗：权限还在就自动显示；权限被覆盖安装清掉时，Shizuku 已授权则 appops 自动恢复。
        // 无障碍：Shizuku 已授权时后台自动恢复，否则给非阻塞提示（不伪造绕过系统权限）。
        com.jdkshen.aijspro.tool.StartupPermissionRestore.restoreOnStartup(this)
        if (Pref.isRunningVolumeControlEnabled()) {
            GlobalKeyObserver.init()
        }
        setupDrawableImageLoader()
        TimedTaskScheduler.init(this)
        initDynamicBroadcastReceivers()
        // 升级后把内置示例对齐到新版本：示例目录是懒拷贝，内容被改过的示例
        // 不会自己刷新（旧版本已修好的示例在手机上升级后仍然是旧的）。
        BundledSampleSync.syncIfNeeded(this)
    }

    /** task-111：反射调用 flutterPoc 源集的 FloatyOrphanCleanup（普通构建没有该类，静默跳过）。 */
    private fun cleanupOrphanFloatiesBeforeRestore() {
        try {
            val cls = Class.forName("com.jdkshen.aijspro.flutterpoc.FloatyOrphanCleanup")
            val method = cls.getDeclaredMethod("cleanup", String::class.java)
            method.isAccessible = true
            method.invoke(null, "app-cold-start-before-restore")
        } catch (_: Throwable) {
            // 非 flutterPoc 构建或类被裁剪：忽略
        }
    }

    /**
     * 是否运行在隔离脚本进程：Phase0 的 ":native" 或 Phase1 的 ":script"。
     * 这些进程只需要引擎本身，主进程职责（定时任务调度/动态广播/示例同步/音量键监听）不能重复执行。
     */
    private fun isNativeIsolationProcess(): Boolean {
        val scriptName = "$packageName:script"
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            try {
                java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
            } catch (e: Throwable) {
                ""
            }
        }
        return processName == scriptName || processName == "$packageName:native"
    }

    @SuppressLint("CheckResult")
    private fun initDynamicBroadcastReceivers() {
        dynamicBroadcastReceivers = DynamicBroadcastReceivers(this)
        val localActions = ArrayList<String>()
        val actions = ArrayList<String>()
        TimedTaskManager.getInstance().allIntentTasks
                .filter { task -> task.action != null }
                .doOnComplete {
                    if (localActions.isNotEmpty()) {
                        dynamicBroadcastReceivers.register(localActions, true)
                    }
                    if (actions.isNotEmpty()) {
                        dynamicBroadcastReceivers.register(actions, false)
                    }
                    LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent(
                            DynamicBroadcastReceivers.ACTION_STARTUP
                    ))
                }
                .subscribe({
                    if (it.isLocal) {
                        localActions.add(it.action)
                    } else {
                        actions.add(it.action)
                    }
                }, { it.printStackTrace() })


    }

    private fun setupDrawableImageLoader() {
        Drawables.setDefaultImageLoader(object : ImageLoader {
            override fun loadInto(imageView: ImageView, uri: Uri) {
                Glide.with(imageView)
                        .load(uri)
                        .into(imageView)
            }

            override fun loadIntoBackground(view: View, uri: Uri) {
                Glide.with(view)
                        .load(uri)
                        .into(object : SimpleTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                view.background = resource
                            }
                        })
            }

            override fun load(view: View, uri: Uri): Drawable {
                throw UnsupportedOperationException()
            }

            override fun load(view: View, uri: Uri, drawableCallback: ImageLoader.DrawableCallback) {
                Glide.with(view)
                        .load(uri)
                        .into(object : SimpleTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                drawableCallback.onLoaded(resource)
                            }
                        })
            }

            override fun load(view: View, uri: Uri, bitmapCallback: ImageLoader.BitmapCallback) {
                Glide.with(view)
                        .asBitmap()
                        .load(uri)
                        .into(object : SimpleTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                bitmapCallback.onLoaded(resource)
                            }
                        })
            }
        })
    }

    companion object {

        private val TAG = "App"
        private val BUGLY_APP_ID = "19b3607b53"

        private lateinit var instance: WeakReference<App>

        val app: App
            get() = instance.get()!!
    }


}
