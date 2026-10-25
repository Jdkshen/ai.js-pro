package com.jdkshen.aijspro.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * task-245：手机终端 aijs-ui 的显式广播入口。
 *
 * Manifest 由 Lead 添加（本任务不修改 AndroidManifest）：
 *   <receiver android:name=".mcp.AijsUiReceiver" android:exported="false">
 *       <intent-filter><action android:name="com.jdkshen.aijspro.AIJS_UI"/></intent-filter>
 *   </receiver>
 *
 * 只接受同应用 UID 的显式广播；真正执行在 AijsUiBridge 的后台线程，避免
 * dispatchGesture 回调/等待阻塞 onReceive 主线程。结果写入 app-private
 * files/aijs-ui/last-result.json，终端命令可在广播后读取该文件。
 */
class AijsUiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        // goAsync 让后台执行器可以在 onReceive 返回后继续；最多约 10 秒由系统限制。
        val pending = goAsync()
        AijsUiBridge.handleAsync(context, intent, pending)
    }
}
