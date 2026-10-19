package com.jdkshen.aijspro.inrt.timing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** task-160：AlarmManager 到点广播 -> 更新持久化 -> 前台服务里拉起打包工程脚本。 */
class InrtTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getLongExtra(InrtTimedTaskScheduler.EXTRA_TASK_ID, -1L) ?: -1L
        if (id <= 0L) return
        Log.i(TAG, "alarm fired id=$id")
        val store = InrtTaskStore(context)
        val task = store.get(id)
        if (task == null) {
            Log.w(TAG, "task not found id=$id")
            return
        }
        InrtTimedTaskScheduler.onFired(context, task)
        val service = Intent(context, InrtTaskService::class.java)
            .putExtra(InrtTimedTaskScheduler.EXTRA_TASK_ID, id)
            .putExtra(InrtTaskService.EXTRA_SCRIPT_PATH, task.getScriptPath())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "start InrtTaskService failed", error)
        }
    }

    companion object {
        private const val TAG = "C-INRT-TASK"
    }
}
