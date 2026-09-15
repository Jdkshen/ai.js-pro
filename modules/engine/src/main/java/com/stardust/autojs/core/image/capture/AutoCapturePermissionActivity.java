package com.stardust.autojs.core.image.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** User-controlled notification access. No settings or permissions are granted by this activity. */
public class AutoCapturePermissionActivity extends Activity {
    public static void show(Context context) {
        context.startActivity(new Intent(context, AutoCapturePermissionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        new AlertDialog.Builder(this)
                .setTitle("自动切换截图")
                .setMessage("需要通知使用权来识别系统录屏的开始和结束。Android 会授予访问通知的权限；此功能只检查录屏通知的应用、通道和状态，不读取、保存或上传通知正文。\n\n录屏运行时使用无障碍截图（整个屏幕，系统限频）；结束后弹出系统授权，确认后恢复高速截图。取消授权不会反复弹窗。\n\n目前优先支持小米 8 的 Lineage 系统录屏；其他录屏无法确认时保持兼容通道。暂不开启也可以继续兼容截图。")
                .setPositiveButton("去开启", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (RuntimeException unavailable) {
                        Toast.makeText(this, "请在系统设置中搜索通知使用权，手动开启 AI.js Pro", Toast.LENGTH_LONG).show();
                    } finally {
                        finish();
                    }
                })
                .setNegativeButton("暂不开启", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }
}
