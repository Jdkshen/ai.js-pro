package com.jdkshen.aijspro.flutterpoc;

import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.tool.AccessibilityCheck;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
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
    /** F2（task-48）：原生→Dart 的状态推送（5 个事件，150ms 合并，只在 Flutter 存活时发）。 */
    private FlutterBridgeEvents mBridgeEvents;
    private SharedPreferences mPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener;
    private ScriptExecutionListener mScriptListener;
    private String mLastAccessibilityKey;
    private static java.lang.ref.WeakReference<GlassFlutterActivity> sVisible =
            new java.lang.ref.WeakReference<>(null);

    /** F2：给 PocBridge 等调用点用（Flutter 不存活时是 no-op，不产生开销）。 */
    static void emitDebugConnectionState(String state, String detail) {
        GlassFlutterActivity activity = sVisible.get();
        if (activity == null || activity.mBridgeEvents == null) return;
        activity.mBridgeEvents.debugConnectionState(state, detail);
    }

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
        // F2：进程回到前台 + 无障碍状态（用户可能刚从系统设置回来）——都只在变化时发。
        if (mBridgeEvents != null) mBridgeEvents.processResumed();
        emitAccessibilityIfChanged();
    }

    /** F2：无障碍状态变化（判据**复用** AccessibilityCheck，不另写一套）。 */
    private void emitAccessibilityIfChanged() {
        if (mBridgeEvents == null) return;
        try {
            AccessibilityCheck.AccessibilityCheckResult result = AccessibilityCheck.INSTANCE.checkNow();
            boolean connected = result instanceof AccessibilityCheck.AccessibilityCheckResult.Success;
            String reason;
            boolean automationReady = false;
            Boolean settingsContains = null;
            long elapsedMs = 0L;
            if (connected) {
                reason = AccessibilityCheck.REASON_CONNECTED;
            } else {
                AccessibilityCheck.AccessibilityCheckResult.Failure failure =
                        (AccessibilityCheck.AccessibilityCheckResult.Failure) result;
                reason = failure.getReason();
                automationReady = failure.getAutomationConnectionReady();
                settingsContains = failure.getSettingsContainsComponent();
                elapsedMs = failure.getElapsedMs();
            }
            String key = connected + "|" + reason;
            if (key.equals(mLastAccessibilityKey)) return;
            mLastAccessibilityKey = key;
            mBridgeEvents.accessibilityState(connected, reason, connected ? "enabled" : "disabled",
                    automationReady, settingsContains, elapsedMs);
        } catch (Throwable error) {
            Log.w(TAG, "accessibility state emit failed", error);
        }
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
        // F2（task-48）：状态推送分发器。存活守卫 = 通道存在 + 未 finishing/destroyed（没有 Flutter 界面就零开销）。
        mBridgeEvents = new FlutterBridgeEvents(new FlutterBridgeEvents.Sender() {
            @Override public boolean alive() {
                return mLocalChannel != null && !isFinishing() && !isDestroyed();
            }
            @Override public void send(String json) {
                try {
                    if (mLocalChannel != null) mLocalChannel.send(json);
                } catch (Throwable error) {
                    Log.w(TAG, "bridge event send failed", error);
                }
            }
            @Override public void log(String line) { Log.i(TAG, line); }
            @Override public long now() { return SystemClock.uptimeMillis(); }
            @Override public void postDelayed(Runnable runnable, long delayMs) { mMain.postDelayed(runnable, delayMs); }
            @Override public void removeCallbacks(Runnable runnable) { mMain.removeCallbacks(runnable); }
        });
        registerPrefsEvents();
        registerScriptEvents();
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

    /** F2：偏好变化 → prefsChanged（**只给 key**，值可能含密钥/路径，绝不出通道）。 */
    private void registerPrefsEvents() {
        try {
            mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
            mPrefsListener = (prefs, key) -> {
                if (mBridgeEvents != null && key != null) mBridgeEvents.prefsChanged(key);
            };
            mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
        } catch (Throwable error) {
            Log.w(TAG, "prefs event registration failed", error);
        }
    }

    /** F2：脚本/任务启停 → taskChanged（全局执行监听；引擎没起来时静默降级）。 */
    private void registerScriptEvents() {
        try {
            mScriptListener = new ScriptExecutionListener() {
                @Override public void onStart(ScriptExecution execution) {
                    taskChanged("script_started", execution, "running");
                }
                @Override public void onSuccess(ScriptExecution execution, Object result) {
                    taskChanged("script_finished", execution, "finished");
                }
                @Override public void onException(ScriptExecution execution, Throwable error) {
                    taskChanged("script_failed", execution, "failed");
                }
            };
            AutoJs.getInstance().getScriptEngineService()
                    .registerGlobalScriptExecutionListener(mScriptListener);
        } catch (Throwable error) {
            Log.w(TAG, "script execution listener registration failed", error);
        }
    }

    private void taskChanged(String reason, ScriptExecution execution, String state) {
        if (mBridgeEvents == null) return;
        String path = null;
        try {
            if (execution != null && execution.getSource() != null) {
                path = String.valueOf(execution.getSource());
            }
        } catch (Throwable ignored) {
        }
        mBridgeEvents.taskChanged(reason, path, state);
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
        if (mBridgeEvents != null) mBridgeEvents.dispose();
        if (mPrefs != null && mPrefsListener != null) {
            try {
                mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
            } catch (Throwable ignored) {
            }
        }
        if (mScriptListener != null) {
            try {
                AutoJs.getInstance().getScriptEngineService()
                        .unregisterGlobalScriptExecutionListener(mScriptListener);
            } catch (Throwable ignored) {
            }
        }
        mBridgeEvents = null;
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
