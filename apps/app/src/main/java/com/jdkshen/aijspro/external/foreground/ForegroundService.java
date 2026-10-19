package com.jdkshen.aijspro.external.foreground;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.external.HostScripts;
import com.jdkshen.aijspro.ui.main.MainActivity;

public class ForegroundService extends Service {


    private static final String TAG = "ForegroundService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANEL_ID = ForegroundService.class.getName() + ".foreground";
    /** 通知栏「停止」按钮的 action：只停脚本，不动普通的前台保活启动流程。 */
    public static final String ACTION_STOP_ALL_SCRIPTS =
            ForegroundService.class.getName() + ".action.STOP_ALL_SCRIPTS";
    private static final AtomicInteger sExecutionLeases = new AtomicInteger();

    public static void start(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(new Intent(context, ForegroundService.class));
        } else {
            context.startService(new Intent(context, ForegroundService.class));
        }
    }

    public static void stop(Context context) {
        if (sExecutionLeases.get() == 0) {
            // Delay so the service can reach startForeground() first. Stopping a
            // service started with startForegroundService() before it calls
            // startForeground() triggers ForegroundServiceDidNotStartInTimeException
            // and kills the whole process 5 seconds later (very fast scripts).
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (sExecutionLeases.get() == 0) {
                    context.stopService(new Intent(context, ForegroundService.class));
                }
            }, 2500);
        }
    }

    /**
     * Keeps script work in Android's foreground scheduling group while it is running. On MIUI,
     * a script-only process otherwise becomes background-restricted as soon as the launcher or a
     * target game covers the workspace, which can more than double OpenCV DNN latency.
     */
    public static boolean acquireExecutionLease(Context context) {
        if (sExecutionLeases.getAndIncrement() == 0) {
            try {
                start(context.getApplicationContext());
            } catch (RuntimeException error) {
                sExecutionLeases.decrementAndGet();
                // Android 12+ may reject a service start triggered by a background-only timed
                // task. The script must still run; QuickJS's raised thread priority remains the
                // safe fallback on such devices.
                Log.w(TAG, "Cannot acquire script foreground lease", error);
                return false;
            }
        }
        return true;
    }

    public static void releaseExecutionLease(Context context) {
        int remaining = sExecutionLeases.updateAndGet(value -> Math.max(0, value - 1));
        if (remaining == 0 && !com.jdkshen.aijspro.Pref.isForegroundServiceEnabled()) {
            stop(context);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForeground();
        } catch (Throwable error) {
            // A failing startForeground() (e.g. notification permission on MIUI)
            // triggers ForegroundServiceDidNotStartInTimeException and kills the
            // process after 5s. Stop immediately instead so script work survives.
            Log.w(TAG, "startForeground failed, stopping service", error);
            stopSelf();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_ALL_SCRIPTS.equals(intent.getAction())) {
            stopAllScriptsFromNotification();
            // 只处理这一次点击：普通启动（acquireExecutionLease / startForegroundService）仍走
            // super 的默认 START_STICKY 行为，保活语义不变。
            return START_NOT_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * 通知栏「停止」：Phase1 脚本默认在 :script 宿主进程里，必须先停宿主进程任务
     * （:script + Phase0 :native），再用主进程旧引擎 stopAll 兜底（auto 模式/回退路径的脚本）。
     */
    private void stopAllScriptsFromNotification() {
        try {
            HostScripts.stopAllHostScripts();
        } catch (Throwable error) {
            Log.w(TAG, "停止宿主进程脚本失败", error);
        }
        try {
            AutoJs autojs = AutoJs.getInstance();
            if (autojs != null && autojs.getScriptEngineService() != null) {
                autojs.getScriptEngineService().stopAll();
            }
        } catch (Throwable error) {
            Log.w(TAG, "停止主进程脚本失败", error);
        }
        if (!Pref.isForegroundServiceEnabled()) {
            // 用户点「停止」= 不想再看到前台通知；设置里「常开前台服务」的原有行为保持不变。
            stop(this);
        }
    }

    private void startForeground() {
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopAllIntent = PendingIntent.getService(this, 1,
                new Intent(this, ForegroundService.class).setAction(ACTION_STOP_ALL_SCRIPTS),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Action stopAllAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_close_white_48dp, getString(R.string.text_stop), stopAllIntent).build();
        // Phase1：脚本默认在 :script 宿主进程里跑，脚本运行通知必须带「停止」入口，
        // 否则用户只能进 App 才能停脚本。通道/图标/文案保持原样，只多一个 action。
        return new NotificationCompat.Builder(this, CHANEL_ID)
                .setContentTitle(getString(R.string.foreground_notification_title))
                .setContentText(getString(R.string.foreground_notification_text))
                .setSmallIcon(R.drawable.ic_ai_js_pro_notification)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent)
                .addAction(stopAllAction)
                .setChannelId(CHANEL_ID)
                .setVibrate(new long[0])
                .build();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        CharSequence name = getString(R.string.foreground_notification_channel_name);
        String description = getString(R.string.foreground_notification_channel_name);
        NotificationChannel channel = new NotificationChannel(CHANEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(description);
        channel.enableLights(false);
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();

    }
}
