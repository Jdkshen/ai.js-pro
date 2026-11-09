package com.stardust.view.accessibility

import android.view.accessibility.AccessibilityEvent
import com.stardust.automator.AccessibilityEventWrapper

/**
 * task-64：把框架 [AccessibilityEvent] 抽成 [AccessibilityEventWrapper] 的**唯一 Android 依赖点**。
 *
 * <p>两条事件通路共用它（键与顺序由 [AccessibilityEventWrapper.toJson] 保证同源）：
 * ① `:node24` 进程：[AccessibilityEventForwarder] → 广播 → Node 的 accessibility.on；
 * ② `:script` 进程：`com.stardust.autojs.runtime.api.Events` 的**本地 delegate**
 *    （无障碍服务声明在 :script，服务实例在本进程时直订，不再走主进程 AIDL 反向通道）。
 *
 * <p>抽出来的原因：以前这份字段抽取内联在 [AccessibilityEventForwarder] 的私有方法里，
 * 第二条通路只能自己再抄一份 —— 两条通路一旦对不上，脚本看到的字段就会随进程而变。
 */
object AccessibilityEventSnapshot {

    /** 字段抽取规则与改造前 [AccessibilityEventForwarder] 的私有实现逐字相同（text 用空格连接、windowChangeTypes 取 windowChanges）。 */
    @JvmStatic
    fun wrapperOf(event: AccessibilityEvent): AccessibilityEventWrapper {
        val className = event.className?.toString() ?: ""
        return AccessibilityEventWrapper(
                kind = AccessibilityEventWrapper.kindOf(event.eventType, className),
                eventType = event.eventType,
                packageName = event.packageName?.toString() ?: "",
                className = className,
                text = event.text?.joinToString(" ") ?: "",
                windowId = event.windowId,
                eventTime = event.eventTime,
                contentChangeTypes = event.contentChangeTypes,
                windowChangeTypes = event.windowChanges)
    }
}