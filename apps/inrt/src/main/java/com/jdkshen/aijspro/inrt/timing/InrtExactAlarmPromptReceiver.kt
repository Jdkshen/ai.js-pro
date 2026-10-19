package com.jdkshen.aijspro.inrt.timing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.jdkshen.aijspro.inrt.R

/** task-162：精确闹钟权限未授权时的用户引导（通知 + 尝试打开系统授权页）。 */
class InrtExactAlarmPromptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.w(TAG, "精确闹钟权限未授权，显示授权引导")
        try {
            Toast.makeText(context, "请在系统设置中允许「闹钟和提醒」以保证定时任务准点运行", Toast.LENGTH_LONG).show()
        } catch (ignored: Throwable) { }
        try { startSettings(context) } catch (error: Throwable) { Log.w(TAG, "打开精确闹钟设置失败", error) }
        try { showNotification(context) } catch (error: Throwable) { Log.w(TAG, "精确闹钟通知失败", error) }
    }

    private fun settingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun startSettings(context: Context) {
        val intent = settingsIntent(context) ?: return
        context.startActivity(intent)
    }

    private fun showNotification(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "inrt_exact_alarm"
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(NotificationChannel(channelId, "定时任务权限", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val settings = settingsIntent(context)
        val pending = settings?.let {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getActivity(context, 0x162, it, flags)
        }
        val builder = Notification.Builder(context, channelId)
            .setContentTitle("定时任务需要精确闹钟权限")
            .setContentText("点击允许「闹钟和提醒」，否则任务可能延迟")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
        if (pending != null) builder.setContentIntent(pending)
        manager.notify(0x162, builder.build())
    }

    companion object {
        private const val TAG = "C-INRT-TASK"
    }
}
