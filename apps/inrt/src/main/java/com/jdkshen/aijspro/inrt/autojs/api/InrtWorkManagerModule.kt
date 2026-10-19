package com.jdkshen.aijspro.inrt.autojs.api

import android.content.Intent
import com.jdkshen.aijspro.inrt.timing.InrtIntentTask
import com.jdkshen.aijspro.inrt.timing.InrtIntentTaskRegistry
import com.jdkshen.aijspro.inrt.timing.InrtTimedTask
import com.jdkshen.aijspro.inrt.timing.InrtTimedTaskScheduler
import com.jdkshen.aijspro.inrt.timing.InrtTaskStore
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * task-160：inrt 打包运行时的 work_manager（Pro 子集）。
 *
 * <p>打包产物没有主 App 的 app 模块/DB，这里用 inrt 自己的 SharedPreferences + AlarmManager；
 * 定时到点统一拉起打包工程主脚本（打包产物只有一个工程入口）。
 */
class InrtWorkManagerModule {

    fun addDisposableTask(options: Any?): InrtTimedTask {
        val path = requirePath(options)
        val dateValue = prop(options, "date") ?: prop(options, "time")
            ?: throw IllegalArgumentException("date/time 不能为空，示例：Date.now() + 60000 或 2019-10-1T20:00:00")
        val millis = parseDateMillis(dateValue)
        return create(InrtTimedTask(taskPath = path, taskMode = "disposable", taskAtMillis = millis))
    }

    fun addOneTimeTask(options: Any?): InrtTimedTask = addDisposableTask(options)

    fun addDailyTask(options: Any?): InrtTimedTask {
        val path = requirePath(options)
        val time = requireString(options, "time", "time 不能为空，格式示例：13:00")
        val millisOfDay = parseTimeOfDayMillis(time)
        return create(InrtTimedTask(taskPath = path, taskMode = "daily", taskAtMillis = millisOfDay))
    }

    fun addWeeklyTask(options: Any?): InrtTimedTask {
        val path = requirePath(options)
        val time = requireString(options, "time", "time 不能为空，格式示例：13:00")
        val days = toList(prop(options, "daysOfWeek"))
            ?: throw IllegalArgumentException("daysOfWeek 不能为空，示例：['一', '三', '五']")
        var flags = 0
        for (day in days) flags = flags or dayFlag(day)
        if (flags == 0) throw IllegalArgumentException("daysOfWeek 没有有效值")
        return create(InrtTimedTask(
            taskPath = path, taskMode = "weekly", taskAtMillis = parseTimeOfDayMillis(time), taskDays = flags))
    }

    // ---- task-162：广播 Intent 任务 ----

    fun addBroadcastIntentTask(options: Any?): InrtIntentTask {
        val path = requirePath(options)
        val action = requireString(options, "action", "action 不能为空，示例：android.intent.action.BATTERY_CHANGED")
        val store = InrtTaskStore(context())
        val task = InrtIntentTask(taskId = store.nextId(), taskPath = path, taskAction = action)
        store.upsertIntentTask(task)
        notifyIntentRegistry(task.getAction(), task.getScriptPath())
        return task
    }

    fun addIntentTask(options: Any?): InrtIntentTask = addBroadcastIntentTask(options)

    fun addActivityIntentTask(options: Any?): InrtIntentTask {
        throw IllegalArgumentException("addActivityIntentTask 需要无障碍活动监听，inrt 打包运行时暂不支持；请用 addBroadcastIntentTask")
    }

    fun getIntentTask(id: Number): InrtIntentTask? = InrtTaskStore(context()).getIntentTask(id.toLong())

    fun removeIntentTask(id: Number): Boolean {
        val store = InrtTaskStore(context())
        val task = store.getIntentTask(id.toLong()) ?: return false
        notifyIntentRegistry(task.getAction(), null)
        return store.removeIntentTask(id.toLong())
    }

    fun queryIntentTasks(filter: Any?): List<InrtIntentTask> {
        val action = optString(filter, "action")
        val path = optString(filter, "path")
        return getAllIntentTasks().filter {
            (action.isNullOrEmpty() || it.getAction() == action) &&
                (path.isNullOrEmpty() || it.getScriptPath() == path)
        }
    }

    fun getAllIntentTasks(): List<InrtIntentTask> =
        InrtTaskStore(context()).loadIntentTasks()

    private fun notifyIntentRegistry(action: String, path: String?) {
        try {
            val intent = Intent(context(), com.jdkshen.aijspro.inrt.timing.InrtIntentTaskCommandReceiver::class.java)
                .setPackage(context().packageName)
                .putExtra(InrtIntentTaskRegistry.EXTRA_ACTION, action)
            if (!path.isNullOrEmpty()) {
                intent.putExtra(InrtIntentTaskRegistry.EXTRA_SCRIPT_PATH, path)
            }
            context().sendBroadcast(intent)
        } catch (ignored: Throwable) { }
    }

    fun getTimedTask(id: Number): InrtTimedTask? =
        InrtTaskStore(context()).get(id.toLong())

    fun removeTimedTask(id: Number): Boolean =
        InrtTimedTaskScheduler.cancel(id.toLong())

    fun queryTimedTasks(filter: Any?): List<InrtTimedTask> {
        val path = optString(filter, "path")
        return getAllTasks().filter { path.isNullOrEmpty() || it.getScriptPath() == path }
    }

    fun getAllTasks(): List<InrtTimedTask> = InrtTaskStore(context()).load()

    fun getTaskCount(): Long = getAllTasks().size.toLong()

    private fun create(task: InrtTimedTask): InrtTimedTask =
        InrtTimedTaskScheduler.schedule(task)

    private fun context() = com.stardust.app.GlobalAppContext.get()

    private fun requirePath(options: Any?): String {
        val path = optString(options, "path") ?: optString(options, 0)
        return path ?: throw IllegalArgumentException("path 不能为空（脚本的绝对路径）")
    }

    private fun prop(options: Any?, name: String): Any? = when (options) {
        null -> null
        is Map<*, *> -> options[name]
        is org.json.JSONObject -> options.opt(name).takeUnless { it == org.json.JSONObject.NULL }
        is Scriptable -> {
            val value = ScriptableObject.getProperty(options, name)
            if (value == Scriptable.NOT_FOUND) null else value
        }
        else -> null
    }

    private fun optString(options: Any?, name: String): String? =
        optString(options, name as Any)

    private fun optString(options: Any?, key: Any): String? {
        val value = when (options) {
            null -> null
            is Map<*, *> -> options[key]
            is org.json.JSONObject -> options.opt(key.toString()).takeUnless { it == org.json.JSONObject.NULL }
            is Scriptable -> {
                val found = if (key is String) ScriptableObject.getProperty(options, key) else Scriptable.NOT_FOUND
                if (found == Scriptable.NOT_FOUND) null else found
            }
            else -> null
        }
        return value?.toString()
    }

    private fun requireString(options: Any?, name: String, message: String): String =
        optString(options, name) ?: throw IllegalArgumentException(message)

    private fun toList(value: Any?): List<Any>? {
        if (value == null) return null
        if (value is org.json.JSONArray) {
            return (0 until value.length()).mapNotNull { value.opt(it).takeUnless { v -> v == org.json.JSONObject.NULL } }
        }
        if (value is List<*>) return value.filterNotNull()
        if (value is Scriptable) {
            val length = ScriptableObject.getProperty(value, "length")
            if (length is Number) {
                val result = ArrayList<Any>(length.toInt())
                for (i in 0 until length.toInt()) {
                    val item = ScriptableObject.getProperty(value, i)
                    if (item != Scriptable.NOT_FOUND && item != null) result.add(item)
                }
                return result
            }
        }
        return listOf(value)
    }

    private fun parseDateMillis(value: Any): Long {
        if (value is Number) return value.toLong()
        val text = value.toString().trim().replace(' ', 'T')
        if (text.matches(Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$"))) {
            val millis = parseTimeOfDayMillis(text)
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, ((millis / 3_600_000L) % 24).toInt())
                set(Calendar.MINUTE, ((millis / 60_000L) % 60).toInt())
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendar.timeInMillis <= System.currentTimeMillis()) calendar.add(Calendar.DAY_OF_YEAR, 1)
            return calendar.timeInMillis
        }
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
            "yyyy/M/d'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS")
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(text)?.time
                    ?: continue
            } catch (ignored: Exception) { }
        }
        throw IllegalArgumentException("无法解析日期：$value（格式示例：2019-10-1T20:00:00）")
    }

    private fun parseTimeOfDayMillis(time: String): Long {
        val parts = time.trim().split(":")
        if (parts.size < 2) throw IllegalArgumentException("无法解析时间：$time（格式示例：13:00）")
        val hour = parts[0].toIntOrNull() ?: throw IllegalArgumentException("无法解析小时：$time")
        val minute = parts[1].toIntOrNull() ?: throw IllegalArgumentException("无法解析分钟：$time")
        if (hour !in 0..23 || minute !in 0..59) throw IllegalArgumentException("时间越界：$time")
        return hour * 3_600_000L + minute * 60_000L
    }

    private fun dayFlag(value: Any): Int = when (val text = value.toString().trim()) {
        "一", "周一", "星期一", "1", "Mon", "Monday" -> 1 shl 1
        "二", "周二", "星期二", "2", "Tue", "Tuesday" -> 1 shl 2
        "三", "周三", "星期三", "3", "Wed", "Wednesday" -> 1 shl 3
        "四", "周四", "星期四", "4", "Thu", "Thursday" -> 1 shl 4
        "五", "周五", "星期五", "5", "Fri", "Friday" -> 1 shl 5
        "六", "周六", "星期六", "6", "Sat", "Saturday" -> 1 shl 6
        "日", "天", "周日", "周天", "0", "7", "Sun", "Sunday" -> 1 shl 7
        else -> throw IllegalArgumentException("无法识别的星期：$text")
    }
}
