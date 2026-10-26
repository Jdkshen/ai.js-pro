package com.jdkshen.aijspro.flutterpoc;

import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.theme.HostUiAppearance;
import com.jdkshen.aijspro.external.HostScripts;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.StringCodec;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flutter 玻璃界面的 POC 宿主（仅 {@code -PflutterPoc=true} 的 debug 变体）。
 *
 * <p>入口：SplashActivity 读 {@code R.bool.flutter_main_entry}（POC 调试源集覆盖为 true）
 * 后跳到本 Activity；Miuix 主界面保留为桌面第二个图标。
 *
 * <p>加载 runtime.json 锁定的引擎（libflutter.so）+ 配套 AOT（libapp.so）+ flutter_assets，
 * 注册 {@code autojs.local.bridge} 这一条 JSON 通道。
 */
public class GlassFlutterActivity extends FlutterActivity {

    private static final String TAG = "GlassFlutterPoc";
    /** task-91：返回键协商通道（Dart 侧 task-90 注册同名 handler）。 */
    private static final String BACK_CHANNEL = "com.jdkshen.aijspro/back";
    /** 事件轮询/异步回调都在主线程。 */
    private Handler mBackHandler;
    private MethodChannel mBackChannel;
    private BasicMessageChannel<String> mLocalChannel;
    private ConsoleLogEvents mLogEvents;
    private static java.lang.ref.WeakReference<GlassFlutterActivity> sVisible =
            new java.lang.ref.WeakReference<>(null);

    public static boolean revealLogsIfVisible() {
        GlassFlutterActivity activity = sVisible.get();
        if (activity == null || activity.isFinishing() || activity.mLocalChannel == null) return false;
        if (activity instanceof UnifiedLogFlutterActivity) return true;
        activity.getSharedPreferences("flutter-glass", MODE_PRIVATE).edit()
                .putString("pending_action", "{\"action\":\"log\"}").apply();
        activity.mLocalChannel.send("{\"event\":\"pendingAction\"}");
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        updateWindowBackground(AppThemeRepository.get(this).isDark());
        sVisible = new java.lang.ref.WeakReference<>(this);
    }

    @Override protected void onPause() {
        if (sVisible.get() == this) sVisible.clear();
        super.onPause();
    }
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicBoolean sOrphanCleanupChecked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        // task-86：首帧前 Android windowBackground 用深色，避免 Flutter 首帧（深色）之前的白闪。
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(
                    HostUiAppearance.surface(AppThemeRepository.get(this).isDark())));
        } catch (Throwable ignored) {
        }
        super.onCreate(savedInstanceState);
        try {
            updateWindowBackground(AppThemeRepository.get(this).isDark());
        } catch (Throwable ignored) {
        }
        // task-86：启动时清理上次进程/脚本留下的孤儿浮窗（十字坐标线等）。
        if (!(this instanceof UnifiedLogFlutterActivity)) {
            new Thread(() -> FloatyOrphanCleanup.cleanup("activity-create"), "floaty-orphan-start").start();
        }
    }

    // Flutter can leave the system-bar area outside its surface. Match that
    // window area to the active theme as well as updating Dart's overlay style.
    void updateWindowBackground(boolean dark) {
        HostUiAppearance.apply(getWindow(), dark);
    }

    /** task-92：pref 开启过 + overlay 权限在 → 恢复悬浮控制条；从未开启则不弹。 */
    private void restoreFloatingMenuIfEnabled() {
        try {
            if (!Pref.isFloatingMenuShown()) {
                Log.i(TAG, "floating menu restore skipped: user disabled");
                return;
            }
            mMain.postDelayed(() -> {
                try {
                    if (FloatyWindowManger.isCircularMenuShowing()) {
                        Log.i(TAG, "floating menu already showing");
                        return;
                    }
                    if (!android.provider.Settings.canDrawOverlays(this)) {
                        Log.i(TAG, "floating menu restore skipped: overlay permission missing");
                        return;
                    }
                    boolean shown = FloatyWindowManger.showCircularMenu();
                    Log.i(TAG, "floating menu restore attempted shown=" + shown);
                } catch (Throwable error) {
                    Log.w(TAG, "floating menu restore failed", error);
                }
            }, 1200L);
        } catch (Throwable error) {
            Log.w(TAG, "floating menu restore setup failed", error);
        }
    }


    /** 旧 fallback：popRoute 让 Flutter 先处理，300ms 后仍未 finishing 就 finish。 */
    private void fallbackBack() {
        try {
            FlutterEngine engine = getFlutterEngine();
            if (engine != null) {
                engine.getNavigationChannel().popRoute();
            }
        } catch (Throwable error) {
            Log.w(TAG, "popRoute failed", error);
        }
        mMain.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                finish();
            }
        }, 300L);
    }


    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        BasicMessageChannel<String> channel = new BasicMessageChannel<>(
                flutterEngine.getDartExecutor().getBinaryMessenger(),
                PocBridge.CHANNEL, StringCodec.INSTANCE);
        mLocalChannel = channel;
        mLogEvents = new ConsoleLogEvents(flutterEngine.getDartExecutor().getBinaryMessenger());
        channel.setMessageHandler((message, reply) ->
                PocBridge.handle(this, message, json -> {
                    if (reply != null) {
                        reply.reply(json);
                    }
                }));
        Log.i(TAG, "glass bridge registered on " + PocBridge.CHANNEL);
        mBackHandler = new Handler(Looper.getMainLooper());
        mBackChannel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), BACK_CHANNEL);
        mBackChannel.setMethodCallHandler((call, result) -> {
            if ("finish".equals(call.method)) {
                mBackHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        finish();
                    }
                });
                result.success(true);
            } else {
                result.notImplemented();
            }
        });
        Log.i(TAG, "back channel registered on " + BACK_CHANNEL);
    }

    @Override
    public void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (mLocalChannel != null) mLocalChannel.send("{\"event\":\"pendingAction\"}");
    }

    @Override
    protected void onDestroy() {
        if (mLogEvents != null) mLogEvents.dispose();
        mLocalChannel = null;
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (PocIntegration.onActivityResult(this, requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
