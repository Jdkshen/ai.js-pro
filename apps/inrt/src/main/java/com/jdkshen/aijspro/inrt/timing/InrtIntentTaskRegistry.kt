package com.jdkshen.aijspro.inrt.timing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.jdkshen.aijspro.inrt.BuildConfig

/**
 * task-162：广播 Intent 任务注册表。
 *
 * <p>动态 BroadcastReceiver 只在主进程有效；:script 里 addBroadcastIntentTask 写盘后通过显式
 * 广播通知主进程的 InrtIntentTaskCommandReceiver 重新注册。开机/进程重建时 App.onCreate 恢复。
 */
object InrtIntentTaskRegistry {

    private const val TAG = "C-INRT-TASK"
    const val EXTRA_ACTION = "intent_action"
    const val EXTRA_SCRIPT_PATH = "intent_script_path"

    @Volatile private var appContext: Context? = null
    private val receivers = HashMap<String, BroadcastReceiver>()

    fun init(context: Context) {
        appContext = context.applicationContext
        registerAll()
    }

    fun registerAll() {
        val context = appContext ?: return
        for (task in InrtTaskStore(context).loadIntentTasks()) {
            register(task)
        }
    }

    fun add(task: InrtIntentTask) {
        val context = appContext ?: return
        InrtTaskStore(context).upsertIntentTask(task)
        register(task)
    }

    fun remove(id: Long): Boolean {
        val context = appContext ?: return false
        val store = InrtTaskStore(context)
        val task = store.getIntentTask(id) ?: return false
        unregister(task.getAction())
        return store.removeIntentTask(id)
    }

    @Synchronized
    fun register(task: InrtIntentTask) {
        val context = appContext ?: return
        val action = task.getAction()
        if (action.isBlank()) return
        unregister(action)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                Log.i(TAG, "intent task fired action=$action path=${task.getScriptPath()}")
                launchScript(ctx, task.getScriptPath())
            }
        }
        try {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // debug 包允许 adb broadcast 验证；发布包保持 NOT_EXPORTED。
                val flags = if (BuildConfig.DEBUG) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
                context.registerReceiver(receiver, filter, flags)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receivers[action] = receiver
            Log.i(TAG, "intent task registered action=$action")
        } catch (error: Throwable) {
            Log.e(TAG, "register intent receiver failed: $action", error)
        }
    }

    @Synchronized
    fun unregister(action: String) {
        val context = appContext ?: return
        val receiver = receivers.remove(action) ?: return
        try { context.unregisterReceiver(receiver) } catch (ignored: Throwable) { }
    }

    private fun launchScript(context: Context, scriptPath: String) {
        val service = Intent(context, InrtTaskService::class.java)
            .putExtra(InrtTaskService.EXTRA_SCRIPT_PATH, scriptPath)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service)
            else context.startService(service)
        } catch (error: Throwable) {
            Log.e(TAG, "start service for intent task failed", error)
        }
    }
}
