package com.jdkshen.aijspro.inrt.timing

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * task-160：定时任务持久化（filesDir JSON 文件，而非 SharedPreferences）。
 *
 * <p>为什么不用 SharedPreferences：打包产物的 work_manager 在 :script 进程写任务，
 * AlarmManager 广播却在主进程读任务；SharedPreferences 每进程有内存缓存，主进程看不到
 * :script 刚写入的任务（实测 "task not found id=2"）。文件每次读盘，跨进程可见。
 */
class InrtTaskStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    @Synchronized
    fun load(): MutableList<InrtTimedTask> {
        val state = readState()
        val result = mutableListOf<InrtTimedTask>()
        val array = state.optJSONArray("tasks") ?: return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            result.add(InrtTimedTask(
                taskId = item.optLong("id"),
                taskPath = item.optString("path"),
                taskMode = item.optString("mode", "disposable"),
                taskAtMillis = item.optLong("at"),
                taskDays = item.optInt("days"),
                taskScheduled = item.optBoolean("scheduled"),
                taskNextTime = item.optLong("next")
            ))
        }
        return result
    }

    @Synchronized
    fun save(tasks: List<InrtTimedTask>) {
        val state = readState()
        val array = JSONArray()
        for (task in tasks) {
            array.put(JSONObject()
                .put("id", task.getId())
                .put("path", task.getScriptPath())
                .put("mode", task.getMode())
                .put("at", task.getAtMillis())
                .put("days", task.getDays())
                .put("scheduled", task.isScheduled())
                .put("next", task.getNextTime()))
        }
        state.put("tasks", array)
        writeState(state)
    }

    @Synchronized
    fun nextId(): Long {
        val state = readState()
        val current = state.optLong("next_id", 1L)
        state.put("next_id", current + 1)
        writeState(state)
        return current
    }

    @Synchronized
    fun upsert(task: InrtTimedTask) {
        val tasks = load()
        tasks.removeAll { it.getId() == task.getId() }
        tasks.add(task)
        save(tasks)
    }

    @Synchronized
    fun get(id: Long): InrtTimedTask? = load().firstOrNull { it.getId() == id }

    @Synchronized
    fun remove(id: Long): Boolean {
        val tasks = load()
        val removed = tasks.removeAll { it.getId() == id }
        if (removed) save(tasks)
        return removed
    }

    // ---- task-162：广播 Intent 任务 ----

    @Synchronized
    fun loadIntentTasks(): MutableList<InrtIntentTask> {
        val result = mutableListOf<InrtIntentTask>()
        val array = readState().optJSONArray("intent_tasks") ?: return result
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            result.add(InrtIntentTask(
                taskId = item.optLong("id"),
                taskPath = item.optString("path"),
                taskAction = item.optString("action")
            ))
        }
        return result
    }

    @Synchronized
    fun saveIntentTasks(tasks: List<InrtIntentTask>) {
        val state = readState()
        val array = JSONArray()
        for (task in tasks) {
            array.put(JSONObject()
                .put("id", task.getId())
                .put("path", task.getScriptPath())
                .put("action", task.getAction()))
        }
        state.put("intent_tasks", array)
        writeState(state)
    }

    @Synchronized
    fun upsertIntentTask(task: InrtIntentTask) {
        val tasks = loadIntentTasks()
        tasks.removeAll { it.getId() == task.getId() || it.getAction() == task.getAction() }
        tasks.add(task)
        saveIntentTasks(tasks)
    }

    @Synchronized
    fun getIntentTask(id: Long): InrtIntentTask? = loadIntentTasks().firstOrNull { it.getId() == id }

    @Synchronized
    fun removeIntentTask(id: Long): Boolean {
        val tasks = loadIntentTasks()
        val removed = tasks.removeAll { it.getId() == id }
        if (removed) saveIntentTasks(tasks)
        return removed
    }

    private fun readState(): JSONObject {
        return try {
            if (!file.isFile) JSONObject() else JSONObject(file.readText(Charsets.UTF_8))
        } catch (error: Throwable) {
            Log.w(TAG, "read task store failed", error)
            JSONObject()
        }
    }

    private fun writeState(state: JSONObject) {
        try {
            val temp = File(appContext.filesDir, FILE_NAME + ".tmp")
            temp.writeText(state.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.writeText(state.toString(), Charsets.UTF_8)
                temp.delete()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "write task store failed", error)
        }
    }

    companion object {
        private const val TAG = "C-INRT-TASK"
        private const val FILE_NAME = "inrt_timed_tasks.json"
    }
}
