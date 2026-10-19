package com.jdkshen.aijspro.inrt.timing

/**
 * task-160：inrt 模板的定时任务模型（JavaBean getter，供 Rhino 脚本访问 id/mode/scriptPath 等）。
 */
class InrtTimedTask(
    taskId: Long = 0L,
    taskPath: String = "",
    /** disposable | daily | weekly */
    taskMode: String = "disposable",
    /** disposable：绝对触发毫秒；daily/weekly：当天时间（毫秒偏移） */
    taskAtMillis: Long = 0L,
    /** weekly 的星期位（bit1=周一 ... bit7=周日） */
    taskDays: Int = 0,
    taskScheduled: Boolean = false,
    /** 下次触发时间（毫秒） */
    taskNextTime: Long = 0L
) {
    private var idValue: Long = taskId
    private var pathValue: String = taskPath
    private var modeValue: String = taskMode
    private var atValue: Long = taskAtMillis
    private var daysValue: Int = taskDays
    private var scheduledValue: Boolean = taskScheduled
    private var nextValue: Long = taskNextTime

    fun getId(): Long = idValue
    fun setId(value: Long) { idValue = value }
    fun getScriptPath(): String = pathValue
    fun setScriptPath(value: String) { pathValue = value }
    fun getMode(): String = modeValue
    fun getAtMillis(): Long = atValue
    fun getMillis(): Long = nextValue
    fun getNextTime(): Long = nextValue
    fun setNextTime(value: Long) { nextValue = value }
    fun getDays(): Int = daysValue
    fun isScheduled(): Boolean = scheduledValue
    fun setScheduled(value: Boolean) { scheduledValue = value }

    override fun toString(): String =
        "InrtTimedTask{id=$idValue, mode=$modeValue, path=$pathValue, next=$nextValue, scheduled=$scheduledValue}"
}
