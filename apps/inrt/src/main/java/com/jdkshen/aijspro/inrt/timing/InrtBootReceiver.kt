package com.jdkshen.aijspro.inrt.timing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** task-160：开机/应用更新后从 SharedPreferences 恢复全部定时任务。 */
class InrtBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        Log.i(TAG, "boot completed，恢复定时任务")
        try {
            InrtTimedTaskScheduler.init(context)
        } catch (error: Throwable) {
            Log.e(TAG, "reschedule on boot failed", error)
        }
    }

    companion object {
        private const val TAG = "C-INRT-TASK"
    }
}
