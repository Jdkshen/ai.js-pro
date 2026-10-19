package com.jdkshen.aijspro.inrt.timing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.jdkshen.aijspro.inrt.R
import com.jdkshen.aijspro.inrt.launch.GlobalProjectLauncher
import com.jdkshen.aijspro.inrt.script.InrtScriptThreadHost

/**
 * task-160：定时任务触发时在 :script 进程用前台服务跑打包工程主脚本。
 *
 * <p>用 shortService（Android 14+ 要求 FGS type）避免后台 Activity 启动限制；脚本执行走
 * InrtScriptThreadHost + GlobalProjectLauncher.launchHeadless，与正常启动的 headless 路径一致。
 */
class InrtTaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getLongExtra(InrtTimedTaskScheduler.EXTRA_TASK_ID, -1L) ?: -1L
        val scriptPath = intent?.getStringExtra(EXTRA_SCRIPT_PATH)
        Log.i(TAG, "service start taskId=$taskId script=$scriptPath")
        Handler(Looper.getMainLooper()).post {
            try {
                if (!InrtScriptThreadHost.isInitialized()) {
                    InrtScriptThreadHost.initialize(this)
                }
                GlobalProjectLauncher.launchHeadlessScript(scriptPath)
                Log.i(TAG, "headless project launched for taskId=$taskId script=$scriptPath")
            } catch (error: Throwable) {
                Log.e(TAG, "launch timed task failed", error)
            }
            Handler(Looper.getMainLooper()).postDelayed({ stopSelf(startId) }, STOP_DELAY_MILLIS)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("定时任务")
            .setContentText("正在运行打包工程脚本")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val TAG = "C-INRT-TASK"
        private const val CHANNEL_ID = "inrt_timed_task"
        private const val NOTIFICATION_ID = 0x159
        private const val STOP_DELAY_MILLIS = 60_000L
        const val EXTRA_SCRIPT_PATH = "inrt_task_script_path"
    }
}
