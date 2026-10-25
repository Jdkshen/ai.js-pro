package com.jdkshen.aijspro.inrt

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jdkshen.aijspro.inrt.autojs.AutoJs
import com.jdkshen.aijspro.inrt.launch.GlobalProjectLauncher

/**
 * task-112：:script 进程里执行入口脚本的宿主 Activity。
 *
 * <p>Pro 模型：主进程只做权限/交接；engine 初始化、dex/so、脚本执行全在 :script 进程
 * 主线程（由 B 的 InrtScriptThreadHost 提供）。本 Activity 保持存活（透明窗口）以维持
 * :script 进程优先级，供 ImGui/浮窗类长驻脚本使用；不 finish。
 */
class InrtScriptActivity : AppCompatActivity() {

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) {
                com.stardust.autojs.ScriptEngineService.stopAllByVolumeUp()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i(TAG, "C-INRT: script activity start pid=" + android.os.Process.myPid())
        if (InrtScriptHostBridge.initialize(this)) {
            val posted = InrtScriptHostBridge.postOnScriptThread(Runnable {
                try {
                    GlobalProjectLauncher.launch(this)
                } catch (e: Throwable) {
                    val cause = e.cause ?: e
                    android.util.Log.e(TAG, "C-INRT: launch failed on script thread", cause)
                    AutoJs.instance?.globalConsole?.printAllStackTrace(cause)
                }
            })
            if (posted) {
                android.util.Log.i(TAG, "C-INRT: host ready, launch posted; keep activity alive")
                return
            }
        }
        // B host 不可用：回退到本进程旧路径（仍可能在 :script），失败再回主进程。
        android.util.Log.w(TAG, "C-INRT: fallback: host not initialized, run launcher directly")
        try {
            GlobalProjectLauncher.launch(this)
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            android.util.Log.e(TAG, "C-INRT: fallback launch failed", cause)
            AutoJs.instance?.globalConsole?.printAllStackTrace(cause)
        }
        finish()
    }

    companion object {
        private const val TAG = "InrtScript"
    }
}
