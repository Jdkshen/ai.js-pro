package com.jdkshen.aijspro.inrt.autojs

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

import com.jdkshen.aijspro.inrt.InrtProcessRole
import com.jdkshen.aijspro.inrt.script.InrtScriptThreadHost
import com.stardust.autojs.ScriptEngineService

/**
 * 音量上键紧急停止的进程内/跨进程中继。
 *
 * <p>进程映射（inrt 正常路由）：
 * <ul>
 *   <li>主进程：App/AutoJs、无障碍服务 SelectToSpeakService（引擎 manifest 未指定 process）、
 *       GlobalKeyObserver、[ScriptEngineService] 空实例；</li>
 *   <li>{@code :script}：App/AutoJs、脚本引擎与执行表、InrtScriptActivity/LogActivity/
 *       ScriptExecuteActivity、悬浮窗和 OverlayRegistry。</li>
 * </ul>
 *
 * <p>主进程收到音量上键后，先由 {@link ScriptEngineService#stopAllByVolumeUp()} 执行本进程
 * 停止，并通过本类安装的 hook 发显式、非导出广播到 {@code :script}；{@code :script} 接收端只做
 * 本进程停止，不再回传，避免循环。不控制其它应用、不需要 root、不会跨包。
 */
object InrtStopRelay {

    private const val TAG = "C-INRT-STOP"

    const val ACTION_STOP_ALL = "com.jdkshen.aijspro.inrt.action.STOP_ALL_SCRIPTS"
    const val EXTRA_REASON = "reason"

    /**
     * 只在主进程安装。shared 的 `stopAllByVolumeUp()` 会先跑 hook 再本地 stopAll；
     * 因此这里只负责把停止请求送到实际跑脚本的 :script，主进程停止由 shared 完成。
     */
    @JvmStatic
    fun installMainVolumeUpHook(context: Context) {
        val appContext = context.applicationContext ?: context
        ScriptEngineService.setVolumeUpStopAllHook {
            relayToScript(appContext, "window_volume_up")
        }
        Log.i(TAG, "volume-up stop hook installed in main process")
    }

    /** 主进程 -> :script 的显式同包广播；:script 未运行时不拉起空进程。 */
    @JvmStatic
    fun relayToScript(context: Context, reason: String) {
        if (!isScriptProcessRunning(context)) {
            Log.i(TAG, "skip stop relay reason=$reason (no :script process)")
            return
        }
        val intent = Intent(context, InrtVolumeUpStopReceiver::class.java)
                .setAction(ACTION_STOP_ALL)
                .putExtra(EXTRA_REASON, reason)
        try {
            context.sendBroadcast(intent)
            Log.i(TAG, "stop relay sent reason=$reason")
        } catch (error: Throwable) {
            Log.w(TAG, "stop relay failed reason=$reason", error)
        }
    }

    /** 只停当前进程：shared `stopAll()` 会同时关闭本进程 OverlayRegistry。 */
    @JvmStatic
    fun stopLocal(context: Context) {
        val service = ScriptEngineService.getInstance()
        if (service == null) {
            Log.w(TAG, "skip local stop: engine service not initialized")
            return
        }
        try {
            service.stopAllAndToast()
            Log.i(TAG, "local stop-all invoked process="
                    + InrtScriptThreadHost.getProcessName(context))
        } catch (error: Throwable) {
            Log.w(TAG, "local stop-all failed", error)
        }
    }

    /**
     * 只查本应用的进程（Android 5.1+ 的 runningAppProcesses 对普通应用只返回自己）。
     * 列表不可用时保守地照常广播：漏停脚本比多起一次同包进程更严重。
     */
    private fun isScriptProcessRunning(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return true
            val processes = manager.runningAppProcesses ?: return true
            if (processes.isEmpty()) {
                return true
            }
            processes.any {
                it.uid == Process.myUid()
                        && InrtProcessRole.isScript(context.packageName, it.processName)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "cannot inspect :script process; relay anyway", error)
            true
        }
    }
}
