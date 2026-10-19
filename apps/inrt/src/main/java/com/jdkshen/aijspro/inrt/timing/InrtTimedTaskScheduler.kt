package com.jdkshen.aijspro.inrt.timing

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jdkshen.aijspro.inrt.BuildConfig
import java.util.Calendar

/**
 * task-160：inrt 打包运行时的定时任务调度（AlarmManager + 显式广播）。
 *
 * <p>主 App 的 TimedTaskManager 依赖 app 模块的 DB 与 JobManager；inrt 是独立 Application，
 * 不能依赖 app 模块，因此用 SharedPreferences 持久化 + AlarmManager 直连 InrtTaskReceiver；
 * App 启动/开机时 rescheduleAll 恢复。
 */
object InrtTimedTaskScheduler {

    private const val TAG = "C-INRT-TASK"
    private const val ACTION_FIRE = "com.jdkshen.aijspro.inrt.action.TIMED_TASK"
    const val EXTRA_TASK_ID = "inrt_task_id"

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // 每个进程都需要 scheduler 上下文（:script 里的 work_manager 直接排任务）；
        // 但只有主进程负责启动/开机时恢复，避免两个进程同时读写 SharedPreferences。
        if (!isScriptProcess(context)) {
            try {
                rescheduleAll()
            } catch (error: Throwable) {
                Log.w(TAG, "rescheduleAll failed", error)
            }
        }
    }

    private fun isScriptProcess(context: Context): Boolean {
        return try {
            com.jdkshen.aijspro.inrt.script.InrtScriptThreadHost.getProcessName(context).endsWith(":script")
        } catch (error: Throwable) {
            false
        }
    }

    fun schedule(task: InrtTimedTask): InrtTimedTask {
        requestExactAlarmPromptIfNeeded(requireContext())
        if (task.getId() <= 0L) {
            val store = InrtTaskStore(requireContext())
            task.setId(store.nextId())
        }
        task.setNextTime(computeNext(task))
        task.setScheduled(true)
        InrtTaskStore(requireContext()).upsert(task)
        scheduleAlarm(task)
        Log.i(TAG, "scheduled $task next=${task.getNextTime()}")
        return task
    }

    fun cancel(id: Long): Boolean {
        val context = requireContext()
        val store = InrtTaskStore(context)
        val task = store.get(id) ?: return false
        cancelAlarm(task)
        store.remove(id)
        return true
    }

    fun rescheduleAll() {
        val context = requireContext()
        val store = InrtTaskStore(context)
        val now = System.currentTimeMillis()
        val tasks = store.load()
        var scheduleCount = 0
        for (task in tasks) {
            if (task.getMode() == "disposable" && task.getNextTime() <= now) {
                store.remove(task.getId())
                continue
            }
            if (task.getNextTime() <= now) {
                task.setNextTime(computeNext(task))
            }
            task.setScheduled(true)
            scheduleAlarm(task)
            scheduleCount++
        }
        store.save(tasks)
        Log.i(TAG, "rescheduleAll count=$scheduleCount")
    }

    /** 触发后的记账：一次性任务删除；daily/weekly 计算下一次并重新排。 */
    fun onFired(context: Context, task: InrtTimedTask) {
        val store = InrtTaskStore(context)
        if (task.getMode() == "disposable") {
            store.remove(task.getId())
            Log.i(TAG, "fired disposable id=${task.getId()} path=${task.getScriptPath()}")
            return
        }
        task.setNextTime(computeNext(task))
        task.setScheduled(true)
        store.upsert(task)
        scheduleAlarm(task)
        Log.i(TAG, "fired recurring id=${task.getId()} next=${task.getNextTime()}")
    }

    fun computeNext(task: InrtTimedTask): Long {
        val now = System.currentTimeMillis()
        return when (task.getMode()) {
            "disposable" -> task.getAtMillis()
            "daily" -> nextDaily(task.getAtMillis(), now)
            "weekly" -> nextWeekly(task.getAtMillis(), task.getDays(), now)
            else -> now + 60_000L
        }
    }

    private fun nextDaily(millisOfDay: Long, now: Long): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, ((millisOfDay / 3_600_000L) % 24).toInt())
            set(Calendar.MINUTE, ((millisOfDay / 60_000L) % 60).toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    private fun nextWeekly(millisOfDay: Long, days: Int, now: Long): Long {
        var best = Long.MAX_VALUE
        for (day in 1..7) {
            val bit = 1 shl day
            if ((days and bit) == 0) continue
            val calendarDay = if (day == 7) Calendar.SUNDAY else day + 1
            val candidate = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.DAY_OF_WEEK, calendarDay)
                set(Calendar.HOUR_OF_DAY, ((millisOfDay / 3_600_000L) % 24).toInt())
                set(Calendar.MINUTE, ((millisOfDay / 60_000L) % 60).toInt())
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis <= now) candidate.add(Calendar.WEEK_OF_YEAR, 1)
            if (candidate.timeInMillis < best) best = candidate.timeInMillis
        }
        return if (best == Long.MAX_VALUE) now + 60_000L else best
    }

    /** task-162：精确闹钟未授权时引导用户（主进程弹 toast/通知/尝试打开系统页）。 */
    private fun requestExactAlarmPromptIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // debug 测试开关：部分 ROM 默认授予精确闹钟，无法用 adb 复现“未授权”状态；
        // 仅 BuildConfig.DEBUG 下可通过 SharedPreferences 强制走引导分支。
        val forcedDenied = BuildConfig.DEBUG && context.getSharedPreferences("inrt_debug", Context.MODE_PRIVATE)
            .getBoolean("force_exact_alarm_prompt", false)
        val allowed = if (forcedDenied) false else try { manager.canScheduleExactAlarms() } catch (error: Throwable) { true }
        Log.i(TAG, "exactAlarm allowed=" + allowed)
        if (allowed) return
        try { android.widget.Toast.makeText(context, "请在系统设置中允许「闹钟和提醒」以保证定时任务准点", android.widget.Toast.LENGTH_LONG).show() } catch (ignored: Throwable) { }
        try {
            context.sendBroadcast(Intent(context, InrtExactAlarmPromptReceiver::class.java)
                .setPackage(context.packageName))
        } catch (error: Throwable) {
            Log.w(TAG, "send exact alarm prompt failed", error)
        }
    }

    private fun scheduleAlarm(task: InrtTimedTask) {
        val context = requireContext()
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context, task)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.getNextTime(), pending)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.getNextTime(), pending)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "setExact failed, fallback inexact", error)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.getNextTime(), pending)
        }
    }

    private fun cancelAlarm(task: InrtTimedTask) {
        val context = requireContext()
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pendingIntent(context, task))
    }

    private fun pendingIntent(context: Context, task: InrtTimedTask): PendingIntent {
        val intent = Intent(context, InrtTaskReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_TASK_ID, task.getId())
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, task.getId().toInt(), intent, flags)
    }

    private fun requireContext(): Context = appContext
        ?: throw IllegalStateException("InrtTimedTaskScheduler 尚未初始化")
}
