package com.jdkshen.aijspro.inrt.timing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * task-162：:script 里新增/删除 Intent 任务后，通知主进程重新注册/反注册动态广播。
 * 显式广播、不导出（同 UID 才能发）。
 */
class InrtIntentTaskCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.getStringExtra(InrtIntentTaskRegistry.EXTRA_ACTION) ?: return
        val path = intent.getStringExtra(InrtIntentTaskRegistry.EXTRA_SCRIPT_PATH)
        try {
            InrtIntentTaskRegistry.init(context)
            if (path.isNullOrEmpty()) {
                InrtIntentTaskRegistry.unregister(action)
            } else {
                val task = InrtTaskStore(context).loadIntentTasks().firstOrNull { it.getAction() == action }
                if (task != null) InrtIntentTaskRegistry.register(task)
            }
        } catch (error: Throwable) {
            Log.e("C-INRT-TASK", "intent command failed: $action", error)
        }
    }
}
