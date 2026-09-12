package com.jdkshen.aijspro.autojs.api.timing

import com.jdkshen.aijspro.timing.IntentTask
import com.jdkshen.aijspro.timing.TimedTask
import com.jdkshen.aijspro.timing.TimedTaskManager
import com.stardust.autojs.execution.ExecutionConfig
import org.joda.time.LocalDateTime
import org.joda.time.LocalTime
import org.joda.time.format.DateTimeFormat
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Auto.js Pro 的 `$work_manager`（定时任务）在 AI.js Pro 上的实现。
 *
 * 由 App 侧通过 `runtime.putProperty("work_manager", ...)` 注册，引擎的 `init.js` 再把它
 * 暴露成全局 `work_manager` 与 `$work_manager`，因此 Pro 的定时任务示例可以原样运行。
 *
 * 支持的接口（与 Pro 对齐）：
 * - `addDisposableTask({path, date})` 一次性任务，`date` 形如 `2019-10-1T20:00:00`
 * - `addDailyTask({path, time})` 每日任务，`time` 形如 `13:00`
 * - `addWeeklyTask({path, time, daysOfWeek: ['一','三','五']})` 每周任务
 * - `addBroadcastIntentTask({path, action})` 广播（意图）任务
 * - `getTimedTask(id)` / `removeTimedTask(id)` / `queryTimedTasks(filter[, callback])` / `queryIntentTasks(filter)`
 * - `getAllTasks()` / `getAllIntentTasks()` / `getTaskCount()`
 */
class WorkManagerModule {

    // ---------------- 添加任务 ----------------

    fun addDisposableTask(options: Any?): TimedTask {
        val path = requirePath(options)
        val date = prop(options, "date")
            ?: throw IllegalArgumentException("date 不能为空，支持毫秒时间戳或 2019-10-1T20:00:00 这样的字符串")
        val task = TimedTask.disposableTask(toLocalDateTime(date), path, ExecutionConfig())
        TimedTaskManager.getInstance().addTask(task)
        return task
    }

    fun addDailyTask(options: Any?): TimedTask {
        val path = requirePath(options)
        val time = requireString(options, "time", "time 不能为空，格式示例：13:00")
        val task = TimedTask.dailyTask(parseLocalTime(time), path, ExecutionConfig())
        TimedTaskManager.getInstance().addTask(task)
        return task
    }

    fun addWeeklyTask(options: Any?): TimedTask {
        val path = requirePath(options)
        val time = requireString(options, "time", "time 不能为空，格式示例：13:00")
        val days = toList(prop(options, "daysOfWeek"))
            ?: throw IllegalArgumentException("daysOfWeek 不能为空，示例：['一', '三', '五']")
        var timeFlag = 0L
        days.forEach { day ->
            timeFlag = timeFlag or dayFlag(dayToString(day))
        }
        if (timeFlag == 0L) {
            throw IllegalArgumentException("daysOfWeek 没有有效值，示例：['一', '三', '五']")
        }
        val task = TimedTask.weeklyTask(parseLocalTime(time), timeFlag, path, ExecutionConfig())
        TimedTaskManager.getInstance().addTask(task)
        return task
    }

    fun addBroadcastIntentTask(options: Any?): IntentTask {
        val path = requirePath(options)
        val action = requireString(options, "action", "action 不能为空，示例：android.intent.action.BATTERY_CHANGED")
        val task = IntentTask()
        task.scriptPath = path
        task.action = action
        val category = optString(options, "category")
        if (category != null) {
            task.category = category
        }
        val dataType = optString(options, "dataType")
        if (dataType != null) {
            task.dataType = dataType
        }
        TimedTaskManager.getInstance().addTask(task)
        return task
    }

    // ---------------- 查询与取消 ----------------

    fun getAllTasks(): List<TimedTask> = TimedTaskManager.getInstance().allTasksAsList

    fun getAllIntentTasks(): List<IntentTask> = TimedTaskManager.getInstance().allIntentTasksAsList

    fun getTaskCount(): Long = TimedTaskManager.getInstance().countTasks()

    fun getTimedTask(id: Number): TimedTask? = TimedTaskManager.getInstance().getTimedTask(id.toLong())

    fun removeTimedTask(id: Number): Boolean {
        val task = TimedTaskManager.getInstance().getTimedTask(id.toLong()) ?: return false
        TimedTaskManager.getInstance().removeTask(task)
        return true
    }

    fun getIntentTask(id: Number): IntentTask? = TimedTaskManager.getInstance().getIntentTask(id.toLong())

    fun removeIntentTask(id: Number): Boolean {
        val task = TimedTaskManager.getInstance().getIntentTask(id.toLong()) ?: return false
        TimedTaskManager.getInstance().removeTask(task)
        return true
    }

    /** 按 `{path}` 过滤定时任务。 */
    fun queryTimedTasks(filter: Any?): List<TimedTask> = queryTimedTasks(filter, null)

    /** 按 `{path}` 过滤定时任务；传了 callback（脚本函数）时会再回调一次并返回任务列表。 */
    fun queryTimedTasks(filter: Any?, callback: Any?): List<TimedTask> {
        val path = optString(filter, "path")
        val result = getAllTasks().filter { path == null || it.scriptPath == path }
        invokeCallback(callback, result)
        return result
    }

    /** 按 `{action}` / `{path}` 过滤意图任务。 */
    fun queryIntentTasks(filter: Any?): List<IntentTask> = queryIntentTasks(filter, null)

    /** 按 `{action}` / `{path}` 过滤意图任务。 */
    fun queryIntentTasks(filter: Any?, callback: Any?): List<IntentTask> {
        val action = optString(filter, "action")
        val path = optString(filter, "path")
        val result = getAllIntentTasks().filter {
            (action == null || it.action == action) && (path == null || it.scriptPath == path)
        }
        invokeCallback(callback, result)
        return result
    }

    // ---------------- 参数解析 ----------------

    private fun prop(options: Any?, name: String): Any? {
        return when (options) {
            null -> null
            is Map<*, *> -> options[name]
            is Scriptable -> {
                val value = ScriptableObject.getProperty(options, name)
                if (value == Scriptable.NOT_FOUND) null else value
            }
            else -> null
        }
    }

    private fun requirePath(options: Any?): String {
        val path = optString(options, "path") ?: optString(options, 0)
        return path ?: throw IllegalArgumentException("path 不能为空（脚本的绝对路径）")
    }

    private fun optString(options: Any?, name: String): String? {
        return optString(options, name as Any)
    }

    private fun optString(options: Any?, key: Any): String? {
        val value = when (options) {
            null -> null
            is Map<*, *> -> options[key]
            is Scriptable -> {
                val v = if (key is String) ScriptableObject.getProperty(options, key) else Scriptable.NOT_FOUND
                if (v == Scriptable.NOT_FOUND) null else v
            }
            else -> null
        }
        return when (value) {
            null -> null
            is String -> value
            is Number -> value.toString()
            else -> value.toString()
        }
    }

    private fun requireString(options: Any?, name: String, message: String): String =
        optString(options, name) ?: throw IllegalArgumentException(message)

    private fun toList(value: Any?): List<Any>? {
        if (value == null) {
            return null
        }
        if (value is List<*>) {
            return value.filterNotNull()
        }
        if (value is Scriptable) {
            val length = ScriptableObject.getProperty(value, "length")
            if (length is Number) {
                val result = ArrayList<Any>(length.toInt())
                for (i in 0 until length.toInt()) {
                    val item = ScriptableObject.getProperty(value, i)
                    if (item != Scriptable.NOT_FOUND && item != null) {
                        result.add(item)
                    }
                }
                return result
            }
        }
        return listOf(value)
    }

    private fun dayToString(day: Any): String = when (day) {
        is Number -> when (day.toInt()) {
            1 -> "一"
            2 -> "二"
            3 -> "三"
            4 -> "四"
            5 -> "五"
            6 -> "六"
            7, 0 -> "日"
            else -> day.toString()
        }
        else -> day.toString()
    }

    private fun dayFlag(day: String): Long = when (day.trim()) {
        "日", "天", "周日", "周天", "0", "7", "Sun", "Sunday" -> TimedTask.FLAG_SUNDAY.toLong()
        "一", "周一", "星期一", "1", "Mon", "Monday" -> TimedTask.FLAG_MONDAY.toLong()
        "二", "周二", "星期二", "2", "Tue", "Tuesday" -> TimedTask.FLAG_TUESDAY.toLong()
        "三", "周三", "星期三", "3", "Wed", "Wednesday" -> TimedTask.FLAG_WEDNESDAY.toLong()
        "四", "周四", "星期四", "4", "Thu", "Thursday" -> TimedTask.FLAG_THURSDAY.toLong()
        "五", "周五", "星期五", "5", "Fri", "Friday" -> TimedTask.FLAG_FRIDAY.toLong()
        "六", "周六", "星期六", "6", "Sat", "Saturday" -> TimedTask.FLAG_SATURDAY.toLong()
        else -> throw IllegalArgumentException("无法识别的星期：$day（可用：日/一/二/三/四/五/六）")
    }

    private fun parseLocalTime(time: String): LocalTime {
        val text = time.trim()
        return try {
            LocalTime.parse(text)
        } catch (e: Exception) {
            try {
                LocalTime.parse(text, DateTimeFormat.forPattern("HH:mm:ss"))
            } catch (e2: Exception) {
                throw IllegalArgumentException("无法解析时间：$text（格式示例：13:00）")
            }
        }
    }

    private fun parseDateTime(date: String): LocalDateTime {
        val text = date.trim().replace(' ', 'T')
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy/M/d'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS")
        for (pattern in patterns) {
            try {
                return LocalDateTime.parse(text, DateTimeFormat.forPattern(pattern))
            } catch (ignored: Exception) {
            }
        }
        throw IllegalArgumentException("无法解析日期：$date（格式示例：2019-10-1T20:00:00）")
    }

    /** `date` 既可以是毫秒时间戳（Pro 的常见写法），也可以是 "2019-10-1T20:00:00" 字符串。 */
    private fun toLocalDateTime(value: Any): LocalDateTime = when (value) {
        is Number -> LocalDateTime(value.toLong())
        else -> parseDateTime(value.toString())
    }

    private fun invokeCallback(callback: Any?, result: Any) {
        if (callback == null) {
            return
        }
        val call = callback as? org.mozilla.javascript.Callable ?: return
        try {
            call.call(
                org.mozilla.javascript.Context.getCurrentContext(),
                ScriptableObject.getTopLevelScope(call as Scriptable),
                callback as Scriptable,
                arrayOf(result)
            )
        } catch (ignored: Exception) {
        }
    }
}
