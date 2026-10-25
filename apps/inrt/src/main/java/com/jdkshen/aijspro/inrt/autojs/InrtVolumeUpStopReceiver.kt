package com.jdkshen.aijspro.inrt.autojs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * `:script` 进程内的停止端点（manifest 中声明为 `android:process=":script"`、exported=false）。
 *
 * <p>只接受本应用的显式广播；收到后只停当前进程，不再向主进程中继，避免 main -> :script ->
 * main 的循环。主进程自己的停止由 `ScriptEngineService.stopAllByVolumeUp()` 完成。
 */
class InrtVolumeUpStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != InrtStopRelay.ACTION_STOP_ALL) {
            return
        }
        val reason = intent.getStringExtra(InrtStopRelay.EXTRA_REASON) ?: "volume_up"
        Log.i(TAG, "received stop-all reason=$reason")
        InrtStopRelay.stopLocal(context)
    }

    companion object {
        private const val TAG = "C-INRT-STOP"
    }
}
