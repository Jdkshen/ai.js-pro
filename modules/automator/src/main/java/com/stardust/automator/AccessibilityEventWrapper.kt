package com.stardust.automator

/**
 * task-60（P0-2 取证结论）：脚本**已经能拿到**无障碍事件对象（:script 进程的
 * com.stardust.autojs.runtime.api.remote.RemoteAccessibilityEvent，字段 kind/eventType/
 * packageName/className/text/windowId/eventTime/contentChangeTypes/windowChangeTypes + toJson + 可读 toString），
 * 所以**不需要**再造一个脚本 API。真正的缺口是：这份「事件 JSON 快照」的映射逻辑内联在
 * AccessibilityEventForwarder.forward() 里（Android-only、无单测），脚本可见的形状没有唯一、可测的定义。
 *
 * 本类就是那份唯一定义：纯 JVM（不依赖 android.*、不依赖 org.json），
 * 由 AccessibilityEventForwarder 从真实 AccessibilityEvent 填字段（既有事件源的唯一出口，不新增第二条事件源），
 * 产出与改造前**逐键相同**的 JSON（kind/eventType/packageName/className/windowId/eventTime/text
 * + 条件键 contentChangeTypes/windowContentTypes 规则见下）。
 */
class AccessibilityEventWrapper @JvmOverloads constructor(
    /** 事件种类（view_clicked/window_state_changed/toast/... ；见 kindOf）。 */
    val kind: String,
    /** android.view.accessibility.AccessibilityEvent.getEventType() 原值。 */
    val eventType: Int,
    val packageName: String,
    val className: String,
    /** 多个 text 片段按空格连接（与改造前一致）。 */
    val text: String,
    val windowId: Int,
    val eventTime: Long,
    val contentChangeTypes: Int = 0,
    val windowChangeTypes: Int = 0,
) {
    /** 可读形式；脚本侧 / logcat 用。 */
    override fun toString(): String =
        "[AccessibilityEventWrapper " + kind + " type=" + eventType + " pkg=" + packageName +
            " class=" + className + " text=" + text + " windowId=" + windowId + " at=" + eventTime + "]"

    /**
     * 与改造前 forward() 逐键相同的 JSON 字符串（顺序也一致，便于回归比对）。
     * 条件键：只有 window_content_changed 带 contentChangeTypes；只有 window_state_changed 带 windowChangeTypes。
     */
    fun toJson(): String {
        val builder = StringBuilder(160)
        builder.append('{')
        appendKey(builder, "kind", kind, false)
        builder.append(",\"eventType\":").append(eventType)
        appendKey(builder, "packageName", packageName, true)
        appendKey(builder, "className", className, true)
        builder.append(",\"windowId\":").append(windowId)
        builder.append(",\"eventTime\":").append(eventTime)
        appendKey(builder, "text", text, true)
        if (kind == KIND_WINDOW_CONTENT_CHANGED) {
            builder.append(",\"contentChangeTypes\":").append(contentChangeTypes)
        }
        if (kind == KIND_WINDOW_STATE_CHANGED) {
            builder.append(",\"windowChangeTypes\":").append(windowChangeTypes)
        }
        return builder.append('}').toString()
    }

    private fun appendKey(builder: StringBuilder, key: String, value: String, comma: Boolean) {
        if (comma) builder.append(',')
        builder.append('"').append(key).append("\":\"").append(escape(value)).append('"')
    }

    companion object {
        const val KIND_ACCESSIBILITY_EVENT = "accessibility_event"
        const val KIND_VIEW_CLICKED = "view_clicked"
        const val KIND_VIEW_LONG_CLICKED = "view_long_clicked"
        const val KIND_WINDOWS_CHANGED = "windows_changed"
        const val KIND_WINDOW_STATE_CHANGED = "window_state_changed"
        const val KIND_WINDOW_CONTENT_CHANGED = "window_content_changed"
        const val KIND_NOTIFICATION = "notification"
        const val KIND_TOAST = "toast"

        /** JSON 字符串转义（org.json 在 JVM 单测里是 stub，所以这里自己实现一份可测的）。 */
        @JvmStatic
        fun escape(value: String): String {
            val builder = StringBuilder(value.length + 8)
            for (character in value) {
                when (character) {
                    '"' -> builder.append("\\\"")
                    '\\' -> builder.append("\\\\")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    else -> if (character < ' ') builder.append(String.format("\\u%04x", character.code)) else builder.append(character)
                }
            }
            return builder.toString()
        }

        /** 与改造前的 kindOf(event) 完全同一套判定（含 Toast 特判）。 */
        @JvmStatic
        fun kindOf(eventType: Int, className: String): String = when (eventType) {
            1 -> KIND_VIEW_CLICKED                 // TYPE_VIEW_CLICKED
            2 -> KIND_VIEW_LONG_CLICKED            // TYPE_VIEW_LONG_CLICKED
            0x00400000 -> KIND_WINDOWS_CHANGED     // TYPE_WINDOWS_CHANGED
            0x00000020 -> KIND_WINDOW_STATE_CHANGED // TYPE_WINDOW_STATE_CHANGED
            0x00000800 -> KIND_WINDOW_CONTENT_CHANGED // TYPE_WINDOW_CONTENT_CHANGED
            0x00000040 ->                          // TYPE_NOTIFICATION_STATE_CHANGED（AOSP=64；曾误写成 0x00100000，被单测当场抓住）
                if (className.contains("Toast", ignoreCase = true)) KIND_TOAST else KIND_NOTIFICATION
            else -> KIND_ACCESSIBILITY_EVENT
        }
    }
}
