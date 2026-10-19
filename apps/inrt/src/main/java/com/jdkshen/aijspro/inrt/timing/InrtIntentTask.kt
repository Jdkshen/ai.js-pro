package com.jdkshen.aijspro.inrt.timing

/** task-162：inrt 打包运行时的广播 Intent 任务模型。 */
class InrtIntentTask(
    taskId: Long = 0L,
    taskPath: String = "",
    taskAction: String = ""
) {
    private var idValue: Long = taskId
    private var pathValue: String = taskPath
    private var actionValue: String = taskAction

    fun getId(): Long = idValue
    fun setId(value: Long) { idValue = value }
    fun getScriptPath(): String = pathValue
    fun getAction(): String = actionValue

    override fun toString(): String =
        "InrtIntentTask{id=$idValue, action=$actionValue, path=$pathValue}"
}
