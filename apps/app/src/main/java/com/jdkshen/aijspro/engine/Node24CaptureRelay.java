package com.jdkshen.aijspro.engine;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * task-180：Node24 截图授权中继。
 *
 * <p>背景：Node24CaptureConsentActivity 声明在 :node24 进程（结果回调必须同进程闭环），
 * 由 :node24 后台服务直接 startActivity 会被 Android 10+ 的后台 Activity 启动限制拦下，
 * 表现为授权页 paused/无窗口、系统 MediaProjection 授权框不出现。</p>
 *
 * <p>方案：:node24 发同包广播到本接收器（运行在主进程）；主进程通常处于前台/可见状态，
 * 由它通过 PendingIntent 启动 :node24 的授权 Activity，获得 BAL 豁免。授权结果仍在
 * :node24 进程的 Session 内闭环，不需要跨进程回传 Intent。</p>
 */
public final class Node24CaptureRelay extends BroadcastReceiver {

    private static final String TAG = "Node24CaptureRelay";
    public static final String ACTION = "com.jdkshen.aijspro.NODE24_CAPTURE_CONSENT";
    public static final String EXTRA_TOKEN = "org.autojs.source.engine.android.NODE24_CAPTURE_TOKEN";
    private static final String CONSENT_ACTIVITY =
            "org.autojs.source.engine.android.Node24CaptureConsentActivity";
    /** :node24 池进程的授权页子类（:node24_1/_2/_3 各一个，保证与请求进程同进程）。 */
    private static final String EXTRA_ACTIVITY =
            "org.autojs.source.engine.android.NODE24_CAPTURE_ACTIVITY";
    private static final String[] ALLOWED_ACTIVITIES = {
            CONSENT_ACTIVITY,
            CONSENT_ACTIVITY + "1",
            CONSENT_ACTIVITY + "2",
            CONSENT_ACTIVITY + "3"
    };

    private static boolean isAllowedActivity(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String allowed : ALLOWED_ACTIVITIES) {
            if (allowed.equals(name)) return true;
        }
        return false;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (token == null || token.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        String activity = intent.getStringExtra(EXTRA_ACTIVITY);
        if (!isAllowedActivity(activity)) {
            activity = CONSENT_ACTIVITY;
        }
        Intent target = new Intent()
                .setClassName(app.getPackageName(), activity)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            PendingIntent pendingIntent = PendingIntent.getActivity(app,
                    token.hashCode() & 0x7fffffff, target,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            // PendingIntent.send 以主进程（通常有可见 Activity）身份启动，规避 :node24 的后台启动限制。
            pendingIntent.send();
            Log.i(TAG, "capture consent activity launched via PendingIntent token=" + token);
        } catch (Throwable error) {
            Log.w(TAG, "launch capture consent failed, fallback startActivity", error);
            try {
                app.startActivity(target);
            } catch (Throwable fallbackError) {
                Log.w(TAG, "fallback startActivity failed", fallbackError);
            }
        }
    }
}
