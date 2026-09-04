package com.jdkshen.aijspro.external.foreground;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.imgui.ImGuiWorkspaceActivity;

public class ForegroundService extends Service {


    private static final String TAG = "ForegroundService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANEL_ID = ForegroundService.class.getName() + ".foreground";
    private static final AtomicInteger sExecutionLeases = new AtomicInteger();

    public static void start(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(new Intent(context, ForegroundService.class));
        } else {
            context.startService(new Intent(context, ForegroundService.class));
        }
    }

    public static void stop(Context context){
        if (sExecutionLeases.get() == 0) {
            context.stopService(new Intent(context, ForegroundService.class));
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
            context.getApplicationContext().stopService(
                    new Intent(context, ForegroundService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForeground() {
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, ImGuiWorkspaceActivity.class), 0);
        return new NotificationCompat.Builder(this, CHANEL_ID)
                .setContentTitle(getString(R.string.foreground_notification_title))
                .setContentText(getString(R.string.foreground_notification_text))
                .setSmallIcon(R.drawable.ic_ai_js_pro_notification)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent)
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
