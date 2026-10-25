package com.jdkshen.aijspro.inrt.autojs

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

import com.stardust.app.GlobalAppContext
import com.jdkshen.aijspro.inrt.InrtProcessRole
import com.jdkshen.aijspro.inrt.Pref
import com.jdkshen.aijspro.inrt.script.InrtScriptThreadHost
import com.stardust.autojs.ScriptEngineService
import com.stardust.autojs.core.inputevent.InputEventObserver
import com.stardust.autojs.core.inputevent.ShellKeyObserver
import com.stardust.view.accessibility.AccessibilityService
import com.stardust.view.accessibility.OnKeyListener

/**
 * Created by Stardust on 2017/8/14.
 *
 * 进程归属：无障碍服务在引擎 manifest 中没有 android:process，固定属于主进程；脚本/浮窗
 * 通常在 :script。因此本监听器只在主进程注册。音量上键的停止由
 * [ScriptEngineService.stopAllByVolumeUp] 完成：主进程本地 stopAll + 已安装的 hook 广播到
 * :script（见 [InrtStopRelay]）。
 *
 * 音量上键是紧急停止，不再受「音量上键停止所有脚本」开关限制；该开关与 root 开关一起只控制
 * 可选的 root getevent 回退，默认不会因为启动 App 就拉起 root shell。
 */
class GlobalKeyObserver internal constructor() : OnKeyListener, ShellKeyObserver.KeyListener {

    private val mKeys = VolumeKeyDeduplicator()
    private var mShellInstalled = false

    init {
        AccessibilityService.stickOnKeyObserver.addListener(this)
    }

    fun onVolumeUp() {
        Log.d(LOG_TAG, "onVolumeUp at " + System.currentTimeMillis())
        try {
            ScriptEngineService.stopAllByVolumeUp()
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "emergency stop-all failed", error)
        }
    }

    override fun onKeyEvent(keyCode: Int, event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_UP || event.isCanceled) {
            return
        }
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return
        }
        if (!mKeys.accept(keyCode, "accessibility", SystemClock.uptimeMillis())) {
            return
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            onVolumeDown()
        } else {
            onVolumeUp()
        }
    }

    /** 保持 inrt 原有语义：打包运行时没有主程序的音量下键运行/录制动作。 */
    fun onVolumeDown() {
    }

    override fun onKeyDown(keyName: String) {
    }

    override fun onKeyUp(keyName: String) {
        if ("KEY_VOLUMEUP" == keyName) {
            if (mKeys.accept(KeyEvent.KEYCODE_VOLUME_UP, "shell", SystemClock.uptimeMillis())) {
                onVolumeUp()
            }
        } else if ("KEY_VOLUMEDOWN" == keyName) {
            if (mKeys.accept(KeyEvent.KEYCODE_VOLUME_DOWN, "shell", SystemClock.uptimeMillis())) {
                onVolumeDown()
            }
        }
    }

    /**
     * root getevent 是可选回退：只有用户同时开启「音量上键停止所有脚本」和
     * 「使用Root权限自动启用服务」才启动，避免默认安装首次启动就弹 root。
     */
    @Synchronized
    private fun ensureShellObserver() {
        if (mShellInstalled) {
            return
        }
        try {
            val observer = ShellKeyObserver()
            observer.setKeyListener(this)
            InputEventObserver.getGlobal(GlobalAppContext.get()).addListener(observer)
            mShellInstalled = true
            Log.i(LOG_TAG, "root getevent key observer installed")
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "install root getevent observer failed", error)
        }
    }

    companion object {

        private const val LOG_TAG = "GlobalKeyObserver"

        @Volatile
        private var sSingleton: GlobalKeyObserver? = null

        @Synchronized
        private fun getSingleton(): GlobalKeyObserver {
            return sSingleton ?: GlobalKeyObserver().also { sSingleton = it }
        }

        /**
         * 只在主进程注册。:script 里的 App.onCreate 也会调用到这里，直接跳过：那里没有
         * 无障碍服务实例，也不应该再起一份 root getevent。
         */
        @JvmStatic
        fun init(context: Context) {
            val processName = InrtScriptThreadHost.getProcessName(context)
            if (!InrtProcessRole.isMain(context.packageName, processName)) {
                Log.i(LOG_TAG, "skip global key observer in process=$processName")
                return
            }
            val observer = getSingleton()
            if (Pref.shouldStopAllScriptsWhenVolumeUp()
                    && Pref.shouldEnableAccessibilityServiceByRoot()) {
                observer.ensureShellObserver()
            }
        }
    }
}
