package com.jdkshen.aijspro.pluginclient;

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

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.main.MainActivity;

import java.io.IOException;

/**
 * Keeps {@link DevPluginServer} alive while the user wants to debug from VS Code.
 *
 * <p>This is what the extension expects the device to expose when "使用adb连接设备(USB)"
 * is picked: {@link DebugServerProvider} reports {@code state=2} only while this
 * service runs, so the extension can distinguish "switch off" from "port closed".
 */
public class DevPluginServerService extends Service {

    private static final String TAG = "DevPluginServerService";
    private static final int NOTIFICATION_ID = 0x9317;
    private static final String CHANNEL_ID = "dev_plugin_server";

    /** 「手机回连电脑」（Pro ws-rpc 客户端模式）的启动参数：电脑地址与端口（默认 21029）。 */
    public static final String EXTRA_PRO_COMPUTER_HOST = "pro_computer_host";
    public static final String EXTRA_PRO_COMPUTER_PORT = "pro_computer_port";

    public static void start(Context context) {
        Intent intent = new Intent(context, DevPluginServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, DevPluginServerService.class));
    }

    /**
     * 拉起服务并让手机主动回连电脑的 ws-rpc 服务（{@code ws://host:21029}）。
     *
     * <p>给「已知电脑地址」的入口用（adb / 以后的 UI 开关）；不传端口时用
     * {@link ProRpcServer#PRO_COMPUTER_DEFAULT_PORT}。
     */
    public static void startWithProComputer(Context context, String host, int port) {
        Intent intent = new Intent(context, DevPluginServerService.class)
                .putExtra(EXTRA_PRO_COMPUTER_HOST, host)
                .putExtra(EXTRA_PRO_COMPUTER_PORT, port);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable error) {
            // A failing startForeground() kills the process after 5s on some ROMs; bail out.
            Log.w(TAG, "startForeground failed, stopping service", error);
            stopSelf();
            return;
        }
        try {
            DevPluginServer.getInstance().start();
        } catch (IOException error) {
            // 具体原因（端口占用等）已写进 DevPluginServer.getServerError()，供 Dart 桥展示。
            Log.w(TAG, "调试服务器启动失败：" + DevPluginServer.getInstance().getServerError(), error);
            stopSelf();
            return;
        }
        // 同一个开关同时拉起 Pro 电脑端用的 ws-rpc 通道（动态端口 + mDNS）。
        // 它失败不应该影响 legacy 9317：只在日志里留痕，让用户仍能用旧扩展。
        try {
            ProRpcServer.getInstance().start();
        } catch (IOException error) {
            Log.w(TAG, "ws-rpc 调试通道启动失败", error);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String host = intent == null ? null : intent.getStringExtra(EXTRA_PRO_COMPUTER_HOST);
        if (host != null && !host.trim().isEmpty()) {
            int port = intent.getIntExtra(EXTRA_PRO_COMPUTER_PORT,
                    ProRpcServer.PRO_COMPUTER_DEFAULT_PORT);
            ProRpcServer.getInstance().connectToComputer(host, port);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        ProRpcServer.getInstance().stop();
        DevPluginServer.getInstance().stop();
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        }
        // The LAN address is what the extension asks for in "输入设备IP地址",
        // so keep it visible without opening the drawer.
        String address = DevPluginServer.localIpv4Address();
        CharSequence text = address == null
                ? getString(R.string.dev_plugin_server_notification_text)
                : getString(R.string.format_dev_plugin_server_listening, address + ":9317");
        String computer = DevPluginServer.getInstance().getReconnectHost();
        if (!computer.isEmpty()) text = getString(R.string.format_dev_plugin_server_auto, computer);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.dev_plugin_server_notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_ai_js_pro_notification)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent)
                .setChannelId(CHANNEL_ID)
                .setVibrate(new long[0])
                .build();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        CharSequence name = getString(R.string.dev_plugin_server_notification_channel_name);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name,
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }
}
