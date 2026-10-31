package com.stardust.autojs.engine;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.Image;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.stardust.autojs.core.accessibility.UiSelector;
import com.stardust.autojs.core.http.MutableOkHttp;
import com.stardust.autojs.core.inputevent.InputEventObserver;
import com.stardust.autojs.core.inputevent.TouchObserver;
import com.stardust.autojs.core.io.ScriptFileObserver;
import com.stardust.autojs.core.database.Database;
import com.stardust.autojs.core.database.DatabaseResultSet;
import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.overlay.OverlayRegistry;
import com.stardust.autojs.runtime.api.Images;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge;
import com.stardust.autojs.runtime.api.remote.RemoteUiObject;
import com.stardust.autojs.runtime.api.remote.RemoteUiObjectCollection;
import com.stardust.autojs.runtime.api.remote.RemoteUiSelector;
import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.api.OcrSession;
import com.stardust.autojs.runtime.api.OCRResult;
import com.stardust.autojs.runtime.api.PPOcr;
import com.stardust.autojs.runtime.api.Ocr;
import com.stardust.autojs.runtime.api.OpenCvYoloDetector;
import com.stardust.autojs.runtime.api.Files;
import com.stardust.autojs.runtime.api.ShizukuShell;
import com.stardust.autojs.runtime.api.Yolo;
import com.stardust.automator.NodeActionArguments;
import com.stardust.automator.UiGlobalSelector;
import com.stardust.automator.UiObject;
import com.stardust.automator.UiObjectCollection;
import com.stardust.automator.filter.BooleanFilter;
import com.stardust.automator.filter.Filter;
import com.stardust.notification.Notification;
import com.stardust.notification.NotificationListenerService;
import com.stardust.pio.UncheckedIOException;
import com.stardust.view.accessibility.AccessibilityNotificationObserver;
import com.stardust.view.accessibility.OnKeyListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Deliberately small Java/Native API boundary for QuickJS. New native APIs
 * should be added here instead of exposing arbitrary Java reflection to JS.
 */
final class QuickJsHostBridge implements AutoCloseable,
        com.stardust.autojs.core.web.QuickJsWebScriptHost {

    private final ScriptRuntime mRuntime;
    private final java.util.concurrent.atomic.AtomicBoolean mCloseStarted = new java.util.concurrent.atomic.AtomicBoolean();
    /** 本引擎在进程内的 id（ScriptEngine.getId()），浮窗注册表按 owner 清理用。 */
    private volatile int mOwnerEngineId;
    /**
     * 进程内活动 bridge 注册表：QuickJS 的 asset 层 API（{@code ui-accessibility.js} 里的
     * {@code ui.inflate} 等）通过 {@code Java.type(...QuickJsHostBridge).forEngine(id)} 取回
     * 当前引擎的宿主桥。沿用 {@code QuickJsApiExtensions.forEngine} 的模式，
     * 只加 Java 方法、不需要重新编译 native。
     */
    private static final Map<Integer, QuickJsHostBridge> sActiveBridges = new ConcurrentHashMap<>();
    private final AtomicLong mNextYoloHandle = new AtomicLong(1);
    private final Map<Long, YoloSession> mYoloSessions = new ConcurrentHashMap<>();
    /** QuickJS 的 `$ocr.create()` 会话（Rhino 的 __ocr__.js 也是「每次调用一个新对象」）。 */
    private final AtomicLong mNextOcrHandle = new AtomicLong(1);
    private final Map<Long, OcrSession> mOcrSessions = new ConcurrentHashMap<>();
    /**
     * OCR 的复用位图（每帧 1080×2400 就是 10MB，每帧新建 + 回收会让 GC 一直响）。
     * 尺寸没变就复用；尺寸变了才重新分配（并把旧的回收）。
     */
    private final OcrImageBuffer mOcrImageBuffer = new OcrImageBuffer();
    private final MutableOkHttp mHttpClient = new MutableOkHttp();
    private final ArrayBlockingQueue<String> mSystemEventQueue = new ArrayBlockingQueue<>(256);
    // Shared event bus across all QuickJS engines (parent + workers). Each engine
    // subscribes its own queue; emit() fans out one pre-serialized JSON item to
    // every subscriber. Subscriptions are cancelled in close().
    private static final Map<String, List<ArrayBlockingQueue<String>>> sSharedBus =
            new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<String> mSharedBusQueue = new ArrayBlockingQueue<>(256);
    private final Set<String> mSharedBusSubscriptions = ConcurrentHashMap.newKeySet();
    private final Object mSystemEventLock = new Object();
    private volatile long mEngineHandle;
    /** 当前阻塞在 Pikafish 搜索中的脚本线程 TID；由 forceStop 跨线程读取。 */
    private volatile int mPikafishAnalyzeTid;
    private volatile boolean mScriptStopRequested;
    private ByteBuffer mAccessibilityPixels;
    private boolean mLoggedAccessibilityPath;
    private boolean mObservingKeys;
    private boolean mObservingNotifications;
    private boolean mObservingToasts;
    private boolean mObservingGestures;
    private volatile boolean mObservingFingerprintGestures;
    private boolean mObservingClip;
    private TouchObserver mSystemTouchObserver;
    private long mLastSystemTouchMillis;
    private volatile long mSystemTouchTimeoutMillis = 10;
    /** `files.observe` 句柄（QuickJS 没有 Rhino 的 waitWhenIdle，靠系统事件定时器保活）。 */
    private final AtomicInteger mNextFileWatcherId = new AtomicInteger(1);
    private final Map<Integer, FileWatcher> mFileWatchers = new ConcurrentHashMap<>();

    /** 与 Rhino/Pro 一致：脚本订阅 `clip_changed` 后才会挂上这个监听。 */
    private final ClipboardManager.OnPrimaryClipChangedListener mQuickJsClipListener = () -> {
        ClipboardManager manager = clipboardManager();
        if (manager == null) return;
        ClipData clip = manager.getPrimaryClip();
        if (clip == null) return;
        CharSequence text = (clip.getItemCount() == 0 || clip.getItemAt(0) == null)
                ? "" : clip.getItemAt(0).getText();
        JSONObject payload = new JSONObject();
        try {
            payload.put("text", text == null ? "" : text.toString());
            enqueueSystemEvent("clip", payload);
        } catch (JSONException error) {
            Log.w("QuickJsHostBridge", "Cannot serialize clip event", error);
        }
    };

    private final OnKeyListener mQuickJsKeyListener = new OnKeyListener() {
        @Override
        public void onKeyEvent(int keyCode, KeyEvent event) {
            String keyName = KeyEvent.keyCodeToString(keyCode);
            if (keyName.startsWith("KEYCODE_")) keyName = keyName.substring(8);
            JSONObject payload = new JSONObject();
            try {
                payload.put("keyCode", keyCode);
                payload.put("keyName", keyName.toLowerCase(Locale.ROOT));
                payload.put("action", event.getAction());
                payload.put("repeatCount", event.getRepeatCount());
                payload.put("eventTime", event.getEventTime());
                enqueueSystemEvent(event.getAction() == KeyEvent.ACTION_UP ? "key_up" : "key_down", payload);
            } catch (JSONException error) {
                Log.w("QuickJsHostBridge", "Cannot serialize key event", error);
            }
        }
    };

    private final com.stardust.view.accessibility.NotificationListener mQuickJsNotificationListener =
            new com.stardust.view.accessibility.NotificationListener() {
                @Override
                public void onNotification(Notification notification) {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("packageName", notification.getPackageName());
                        payload.put("title", notification.getTitle());
                        payload.put("text", notification.getText());
                        payload.put("when", notification.when);
                        payload.put("number", notification.number);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            payload.put("category", notification.category);
                        }
                        enqueueSystemEvent("notification", payload);
                    } catch (JSONException error) {
                        Log.w("QuickJsHostBridge", "Cannot serialize notification", error);
                    }
                }
            };

    private final AccessibilityNotificationObserver.ToastListener mQuickJsToastListener =
            new AccessibilityNotificationObserver.ToastListener() {
                @Override
                public void onToast(AccessibilityNotificationObserver.Toast toast) {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("packageName", toast.getPackageName());
                        payload.put("text", toast.getText());
                        payload.put("texts", new JSONArray(toast.getTexts()));
                        enqueueSystemEvent("toast", payload);
                    } catch (JSONException error) {
                        Log.w("QuickJsHostBridge", "Cannot serialize toast", error);
                    }
                }
            };

    private final com.stardust.view.accessibility.AccessibilityService.GestureListener mQuickJsGestureListener =
            gestureId -> {
                JSONObject payload = new JSONObject();
                try {
                    payload.put("gestureId", gestureId);
                    payload.put("gesture", gestureName(gestureId));
                    enqueueSystemEvent("gesture", payload);
                } catch (JSONException error) {
                    Log.w("QuickJsHostBridge", "Cannot serialize gesture", error);
                }
            };

    private final com.stardust.view.accessibility.FingerprintGestureDispatcher.Listener mQuickJsFingerprintListener =
            new com.stardust.view.accessibility.FingerprintGestureDispatcher.Listener() {
                @Override
                public void onFingerprintGesture(String direction) {
                    enqueueFingerprintEvent("fingerprint_gesture", "direction", direction);
                }

                @Override
                public void onFingerprintGestureAvailabilityChanged(boolean available) {
                    enqueueFingerprintEvent("fingerprint_gesture_available", "available", available);
                }
            };

    private void enqueueFingerprintEvent(String type, String key, Object value) {
        if (!mObservingFingerprintGestures) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put(key, value);
            enqueueSystemEvent(type, payload);
        } catch (JSONException error) {
            Log.w("QuickJsHostBridge", "Cannot serialize fingerprint event", error);
        }
    }

    private static final class YoloSession {
        final String backend;
        final AutoCloseable detector;

        YoloSession(String backend, AutoCloseable detector) {
            this.backend = backend;
            this.detector = detector;
        }
    }

    /** `files.observe` 的一个监听句柄；observer 为 null 表示已 stopWatching（可再 start）。 */
    private static final class FileWatcher {
        final String path;
        volatile FileObserver observer;

        FileWatcher(String path) {
            this.path = path;
        }
    }

    QuickJsHostBridge(ScriptRuntime runtime) {
        mRuntime = runtime;
        mJavaInterop = new QuickJsJavaInterop(runtime.javaResources);
        QuickJsApiExtensions.install(runtime, this);
    }

    /** engine.init() 里注入：bridge 的生命周期早于/独立于 ScriptEngine.setId。 */
    void setOwnerEngineId(int engineId) {
        int previous = mOwnerEngineId;
        mOwnerEngineId = engineId;
        if (engineId > 0) {
            sActiveBridges.put(engineId, this);
            if (previous > 0 && previous != engineId) {
                sActiveBridges.remove(previous, this);
            }
        }
    }

    /** 按引擎 id 取回活动 bridge（asset 层 API 入口；引擎已结束时不可用）。 */
    public static QuickJsHostBridge forEngine(int engineId) {
        QuickJsHostBridge bridge = sActiveBridges.get(engineId);
        if (bridge == null) {
            throw new IllegalStateException("脚本引擎已经结束");
        }
        return bridge;
    }

    /** 浮窗注册表 ownerTag：qjs-<native handle>（同一脚本的宿主/worker 可区分）。 */
    private String overlayOwnerTag() {
        return "qjs-" + mEngineHandle;
    }

    void attachEngine(long engineHandle) {
        mEngineHandle = engineHandle;
    }

    public String modernModulesScript() {
        StringBuilder scripts = new StringBuilder();
        String[] assets = { "modules/qjs-console-compat.js", "modules/qjs-dialogs-compat.js", "modules/pro-modern-compat.js", "modules/pro-modern-platform.js", "modules/qjs-touch-capture.js", "modules/qjs-canvas.js", "modules/qjs-plugins.js", "modules/sift-api.js", "modules/ui-accessibility.js", "modules/common-api.js", "modules/common-device.js", "modules/common-color.js", "modules/common-lang.js" };
        for (String path : assets) {
            try (InputStream input = mRuntime.uiHandler.getContext().getAssets().open(path);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                scripts.append(bytes.toString("UTF-8")).append('\n');
            } catch (IOException error) {
                throw new IllegalStateException("Cannot load compatibility asset: " + path, error);
            }
        }
        return scripts.toString();
    }

    public void console(int level, String message) {
        switch (level) {
            case Log.ASSERT:
                mRuntime.console.println(Log.ASSERT, message);
                break;
            case Log.ERROR:
                mRuntime.console.error(message);
                break;
            case Log.WARN:
                mRuntime.console.warn(message);
                break;
            case Log.INFO:
                mRuntime.console.info(message);
                break;
            case Log.VERBOSE:
                mRuntime.console.verbose(message);
                break;
            default:
                mRuntime.console.log(message);
                break;
        }
    }

    public boolean eventsObserve(String kind) {
        if (kind == null) throw new IllegalArgumentException("event observation kind is required");
        synchronized (mSystemEventLock) {
            switch (kind.toLowerCase(Locale.ROOT)) {
                case "fingerprint_available":
                    return com.stardust.view.accessibility.AccessibilityService.fingerprintGestureDispatcher.isAvailable();
                case "fingerprint_gesture": {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
                    if (mObservingFingerprintGestures) return true;
                    requireAccessibilityService();
                    mObservingFingerprintGestures = true;
                    com.stardust.view.accessibility.AccessibilityService.fingerprintGestureDispatcher
                            .addListener(mQuickJsFingerprintListener);
                    return true;
                }
                case "key": {
                    if (mObservingKeys) return true;
                    com.stardust.view.accessibility.AccessibilityService service = requireAccessibilityService();
                    AccessibilityServiceInfo info = service.getServiceInfo();
                    if (info == null || (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) == 0) {
                        throw new IllegalStateException("Accessibility service key observation is not enabled");
                    }
                    service.getOnKeyObserver().addListener(mQuickJsKeyListener);
                    mObservingKeys = true;
                    return true;
                }
                case "notification": {
                    if (mObservingNotifications) return true;
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return false;
                    NotificationListenerService service = NotificationListenerService.Companion.getInstance();
                    if (service == null) {
                        // 与 Rhino 行为一致：跳系统“通知使用权”设置页引导开启；application context 必须带 NEW_TASK
                        try {
                            mRuntime.uiHandler.getContext().getApplicationContext().startActivity(
                                    new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ignored) {
                        }
                        throw new IllegalStateException("Notification listener service is not enabled");
                    }
                    service.addListener(mQuickJsNotificationListener);
                    mObservingNotifications = true;
                    return true;
                }
                case "toast": {
                    if (mObservingToasts) return true;
                    requireAccessibilityService();
                    mRuntime.accessibilityBridge.getNotificationObserver().addToastListener(mQuickJsToastListener);
                    mObservingToasts = true;
                    return true;
                }
                case "gesture": {
                    if (mObservingGestures) return true;
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
                    com.stardust.view.accessibility.AccessibilityService service = requireAccessibilityService();
                    AccessibilityServiceInfo info = service.getServiceInfo();
                    if (info == null || (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) == 0) {
                        throw new IllegalStateException("Accessibility service gesture observation is not enabled");
                    }
                    service.getGestureEventDispatcher().addListener(mQuickJsGestureListener);
                    mObservingGestures = true;
                    return true;
                }
                case "clip": {
                    if (mObservingClip) return true;
                    ClipboardManager manager = clipboardManager();
                    if (manager == null) return false;
                    manager.addPrimaryClipChangedListener(mQuickJsClipListener);
                    mObservingClip = true;
                    return true;
                }
                case "touch": {
                    if (mSystemTouchObserver != null) return true;
                    TouchObserver observer = new TouchObserver(
                            InputEventObserver.getGlobal(ScriptRuntime.getApplicationContext()));
                    observer.setOnTouchEventListener((x, y) -> {
                        long now = System.currentTimeMillis();
                        if (now - mLastSystemTouchMillis < mSystemTouchTimeoutMillis) return;
                        mLastSystemTouchMillis = now;
                        JSONObject payload = new JSONObject();
                        try {
                            payload.put("x", x);
                            payload.put("y", y);
                            enqueueSystemEvent("touch", payload);
                        } catch (JSONException error) {
                            Log.w("QuickJsHostBridge", "Cannot serialize touch event", error);
                        }
                    });
                    observer.observe();
                    mSystemTouchObserver = observer;
                    return true;
                }
                default:
                    throw new IllegalArgumentException("Unknown event observation kind: " + kind);
            }
        }
    }

    public String eventsPoll() {
        String event = mSystemEventQueue.poll();
        return event == null ? "" : event;
    }

    public void eventsSetTouchTimeout(int timeoutMillis) {
        mSystemTouchTimeoutMillis = Math.max(0, timeoutMillis);
    }

    public void eventsStopAll() {
        synchronized (mSystemEventLock) {
            stopFingerprintObservation();
            com.stardust.view.accessibility.AccessibilityService accessibilityService =
                    mRuntime.accessibilityBridge.getService();
            if (accessibilityService != null) {
                if (mObservingKeys) {
                    accessibilityService.getOnKeyObserver().removeListener(mQuickJsKeyListener);
                }
                if (mObservingGestures) {
                    accessibilityService.getGestureEventDispatcher().removeListener(mQuickJsGestureListener);
                }
            }
            NotificationListenerService notificationService = NotificationListenerService.Companion.getInstance();
            if (notificationService != null && mObservingNotifications) {
                notificationService.removeListener(mQuickJsNotificationListener);
            }
            if (mObservingToasts) {
                mRuntime.accessibilityBridge.getNotificationObserver().removeToastListener(mQuickJsToastListener);
            }
            if (mSystemTouchObserver != null) {
                mSystemTouchObserver.stop();
                mSystemTouchObserver = null;
            }
            if (mObservingClip) {
                ClipboardManager manager = clipboardManager();
                if (manager != null) {
                    manager.removePrimaryClipChangedListener(mQuickJsClipListener);
                }
                mObservingClip = false;
            }
            mObservingKeys = false;
            mObservingNotifications = false;
            mObservingToasts = false;
            mObservingGestures = false;
            mSystemEventQueue.clear();
        }
    }

    /**
     * `events.removeListener`/`removeAllListeners` 把 clip_changed 的监听删空时，
     * 停止底层剪贴板监听（与 Pro 的 listenerCount==0 → removePrimaryClipChangedListener 一致）。
     */
    public void eventsStopObserving(String kind) {
        if (kind == null) return;
        synchronized (mSystemEventLock) {
            if ("fingerprint_gesture".equalsIgnoreCase(kind)) {
                stopFingerprintObservation();
                return;
            }
            if (!"clip".equalsIgnoreCase(kind)) return;
            if (!mObservingClip) return;
            ClipboardManager manager = clipboardManager();
            if (manager != null) {
                manager.removePrimaryClipChangedListener(mQuickJsClipListener);
            }
            mObservingClip = false;
        }
    }

    private void stopFingerprintObservation() {
        mObservingFingerprintGestures = false;
        com.stardust.view.accessibility.AccessibilityService.fingerprintGestureDispatcher
                .removeListener(mQuickJsFingerprintListener);
        mSystemEventQueue.removeIf(raw -> raw.contains("\"type\":\"fingerprint_"));
    }

    private ClipboardManager clipboardManager() {
        Context context = ScriptRuntime.getApplicationContext();
        if (context == null) return null;
        return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /**
     * `files.observe(path)`（对齐 Rhino 的 ScriptFileObserver）：返回监听句柄 id（失败 -1）。
     * 事件经系统事件队列以 `file_observer` 派发，事件名与 Pro 的 fileEventToString 一致。
     */
    public int fileObserverNew(String path) {
        if (path == null || path.isEmpty()) return -1;
        try {
            FileWatcher watcher = new FileWatcher(path);
            watcher.observer = createFileObserver(mNextFileWatcherId.get(), path);
            int id = mNextFileWatcherId.getAndIncrement();
            mFileWatchers.put(id, watcher);
            return id;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot observe path: " + path, error);
            return -1;
        }
    }

    public void fileObserverStart(int id) {
        FileWatcher watcher = mFileWatchers.get(id);
        if (watcher == null || watcher.observer != null) return;
        try {
            watcher.observer = createFileObserver(id, watcher.path);
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot restart file observation: " + watcher.path, error);
        }
    }

    public void fileObserverStop(int id) {
        FileWatcher watcher = mFileWatchers.get(id);
        if (watcher == null) return;
        FileObserver observer = watcher.observer;
        if (observer != null) {
            observer.stopWatching();
            watcher.observer = null;
        }
    }

    private FileObserver createFileObserver(final int id, String path) {
        FileObserver observer = new FileObserver(path, FileObserver.ALL_EVENTS) {
            @Override
            public void onEvent(int event, @androidx.annotation.Nullable String relativePath) {
                for (String name : ScriptFileObserver.eventNames(event)) {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("id", id);
                        payload.put("event", name);
                        payload.put("path", relativePath == null ? JSONObject.NULL : relativePath);
                        enqueueSystemEvent("file_observer", payload);
                    } catch (JSONException error) {
                        Log.w("QuickJsHostBridge", "Cannot serialize file observation event", error);
                    }
                }
            }
        };
        observer.startWatching();
        return observer;
    }

    private void closeFileObservers() {
        for (FileWatcher watcher : new ArrayList<>(mFileWatchers.values())) {
            FileObserver observer = watcher.observer;
            if (observer != null) {
                try {
                    observer.stopWatching();
                } catch (Throwable ignored) {
                }
                watcher.observer = null;
            }
        }
        mFileWatchers.clear();
    }

    private com.stardust.view.accessibility.AccessibilityService requireAccessibilityService() {
        mRuntime.ensureAccessibilityServiceEnabled();
        com.stardust.view.accessibility.AccessibilityService service =
                mRuntime.accessibilityBridge.getService();
        if (service == null) throw new IllegalStateException("Accessibility service is not running");
        return service;
    }

    private void enqueueSystemEvent(String type, JSONObject payload) throws JSONException {
        payload.put("type", type);
        String serialized = payload.toString();
        if (!mSystemEventQueue.offer(serialized)) {
            mSystemEventQueue.poll();
            mSystemEventQueue.offer(serialized);
        }
    }

    private static String gestureName(int gestureId) {
        switch (gestureId) {
            case com.stardust.view.accessibility.AccessibilityService.GESTURE_SWIPE_UP: return "up";
            case com.stardust.view.accessibility.AccessibilityService.GESTURE_SWIPE_DOWN: return "down";
            case com.stardust.view.accessibility.AccessibilityService.GESTURE_SWIPE_LEFT: return "left";
            case com.stardust.view.accessibility.AccessibilityService.GESTURE_SWIPE_RIGHT: return "right";
            case com.stardust.view.accessibility.AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT: return "left_right";
            case com.stardust.view.accessibility.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT: return "right_left";
            case com.stardust.view.accessibility.AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN: return "up_down";
            case com.stardust.view.accessibility.AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP: return "down_up";
            default: return "unknown";
        }
    }

    public void toast(String message) {
        mRuntime.toast(message);
    }

    /**
     * 多分辨率适配：设置脚本坐标系后，点击、滑动、图色与找图的坐标都会按实际屏幕缩放。
     * 与 Rhino 共用同一个 ScreenMetrics 实例（Automator/Images/Shell 已接好）。
     */
    public void setScreenMetrics(int width, int height) {
        mRuntime.setScreenMetrics(width, height);
    }

    // ---- 多指手势 / 文本输入 ----

    /**
     * 单指手势路径（Auto.js 的 `gesture(duration, [x, y], ...)`）：
     * 点坐标交给 Automator，它会按 ScreenMetrics 缩放（与 Rhino 一致）。
     */
    public boolean gesture(int start, int duration, String pointsJson, boolean async) throws JSONException {
        int[][] points = parseGesturePoints(pointsJson);
        if (async) {
            mRuntime.automator.gestureAsync((long) start, (long) duration, points);
            return true;
        }
        return mRuntime.automator.gesture((long) start, (long) duration, points);
    }

    /**
     * 多指手势（Auto.js 的 `gestures([start, duration, [x, y], ...], ...)`）：
     * Rhino 在 JS 层拼 StrokeDescription；白名单桥不给脚本 Java 类，改由宿主拼。
     */
    public boolean gestures(String strokesJson, boolean async) throws JSONException {
        GestureDescription.StrokeDescription[] strokes = parseStrokes(strokesJson);
        if (async) {
            mRuntime.automator.gesturesAsync(strokes);
            return true;
        }
        return mRuntime.automator.gestures(strokes);
    }

    /** Auto.js 的 input(text)：向当前聚焦的输入框追加文本（无障碍 ACTION_APPEND_TEXT）。 */
    public boolean inputText(String text) {
        return mRuntime.automator.appendText(mRuntime.automator.editable(-1), text == null ? "" : text);
    }

    private int[][] parseGesturePoints(String json) throws JSONException {
        JSONArray points = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
        int[][] result = new int[points.length()][2];
        for (int i = 0; i < points.length(); i++) {
            JSONArray point = points.getJSONArray(i);
            result[i][0] = point.getInt(0);
            result[i][1] = point.getInt(1);
        }
        return result;
    }

    private GestureDescription.StrokeDescription[] parseStrokes(String json) throws JSONException {
        JSONArray strokes = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
        GestureDescription.StrokeDescription[] result =
                new GestureDescription.StrokeDescription[strokes.length()];
        for (int i = 0; i < strokes.length(); i++) {
            JSONArray stroke = strokes.getJSONArray(i);
            long start = 0;
            long duration;
            int pointIndex;
            // [delay, duration, [x, y], ...] 或 [delay, [x, y], ...]（与 Rhino 的 toStrokes 一致）。
            if (stroke.length() > 1 && stroke.get(1) instanceof Number) {
                start = stroke.getLong(0);
                duration = stroke.getLong(1);
                pointIndex = 2;
            } else {
                duration = stroke.getLong(0);
                pointIndex = 1;
            }
            Path path = new Path();
            JSONArray first = stroke.getJSONArray(pointIndex);
            path.moveTo(scaleX(first.getInt(0)), scaleY(first.getInt(1)));
            for (int j = pointIndex + 1; j < stroke.length(); j++) {
                JSONArray point = stroke.getJSONArray(j);
                path.lineTo(scaleX(point.getInt(0)), scaleY(point.getInt(1)));
            }
            result[i] = new GestureDescription.StrokeDescription(path, start, duration);
        }
        return result;
    }

    private float scaleX(int value) {
        return (float) mRuntime.getScreenMetrics().scaleX(value);
    }

    private float scaleY(int value) {
        return (float) mRuntime.getScreenMetrics().scaleY(value);
    }

    // ------------------------------------------------------------------
    // 内置模块：crypto / zips / 运行环境信息
    // ------------------------------------------------------------------

    /** `crypto` 模块（对齐 Rhino 的 `runtime.crypto`）。 */
    public String cryptoCall(String method, String arg1, String arg2) {
        switch (method == null ? "" : method) {
            case "md5":
                return mRuntime.crypto.md5(arg1);
            case "sha1":
                return mRuntime.crypto.sha1(arg1);
            case "sha256":
                return mRuntime.crypto.sha256(arg1);
            case "digest":
                return mRuntime.crypto.digest(arg1, arg2);
            case "hmacSha256":
                return mRuntime.crypto.hmacSha256(arg1, arg2);
            case "base64Encode":
                return mRuntime.crypto.base64Encode(arg1);
            case "base64Decode":
                return mRuntime.crypto.base64Decode(arg1);
            case "md5Base64": {
                // WebSocket 二进制（web.ByteString.md5()）：arg1 为 base64，输出为字节的 MD5 十六进制
                try {
                    okio.ByteString bytes = okio.ByteString.decodeBase64(arg1 == null ? "" : arg1);
                    byte[] digest = java.security.MessageDigest.getInstance("MD5")
                            .digest(bytes == null ? new byte[0] : bytes.toByteArray());
                    StringBuilder builder = new StringBuilder(digest.length * 2);
                    for (byte b : digest) {
                        builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                        builder.append(Character.forDigit(b & 0xF, 16));
                    }
                    return builder.toString();
                } catch (Exception error) {
                    throw new IllegalStateException("md5Base64 failed", error);
                }
            }
            default:
                throw new IllegalArgumentException("crypto 不支持的方法: " + method);
        }
    }

    /**
     * `zips` 模块（对齐 Rhino 的 `runtime.zips`）。
     * `zip`/`unzip` 返回 "true"/"false"，`list` 返回 JSON 数组。
     */
    public String zipsCall(String method, String arg1, String arg2) throws JSONException {
        switch (method == null ? "" : method) {
            case "zip":
                return String.valueOf(mRuntime.zips.zip(arg1, arg2));
            case "unzip":
                return String.valueOf(mRuntime.zips.unzip(arg1, arg2));
            case "list":
                return new JSONArray(Arrays.asList(mRuntime.zips.list(arg1))).toString();
            default:
                throw new IllegalArgumentException("zips 不支持的方法: " + method);
        }
    }

    /** `isStopped()`：引擎线程被中断即视为脚本已请求停止（与 `ScriptRuntime.isStopped` 一致）。 */
    public boolean scriptStopped() {
        return mRuntime.isStopped() || Thread.currentThread().isInterrupted();
    }

    /**
     * 控制台浮窗（Rhino 的 `openConsole`/`clearConsole` 与 `console.show/hide/clear/setTitle/setSize/setPosition`）。
     * `setSize`/`setPosition` 只存在于浮窗实现类上，接口里没有，这里用宿主内部反射调用。
     */
    public void consoleCall(String method, String arg) throws JSONException {
        switch (method == null ? "" : method) {
            case "show":
                mRuntime.console.show();
                return;
            case "hide":
                mRuntime.console.hide();
                return;
            case "clear":
                mRuntime.console.clear();
                return;
            case "setTitle":
                mRuntime.console.setTitle(arg);
                return;
            case "setSize":
            case "setPosition": {
                JSONArray pair = new JSONArray(arg == null || arg.isEmpty() ? "[0,0]" : arg);
                invokeConsoleIntMethod(method, pair.getInt(0), pair.getInt(1));
                return;
            }
            default:
                throw new IllegalArgumentException("控制台不支持的方法: " + method);
        }
    }

    private void invokeConsoleIntMethod(String name, int first, int second) {
        try {
            Method method = mRuntime.console.getClass().getMethod(name, int.class, int.class);
            method.invoke(mRuntime.console, first, second);
        } catch (Exception e) {
            throw new IllegalStateException("控制台调用失败 " + name + ": " + e.getMessage(), e);
        }
    }

    public void requiresApi(int api) {
        ScriptRuntime.requiresApi(api);
    }

    /** 应用自身版本，格式 "versionCode|versionName"，供 `requiresAutojsVersion` 比较。 */
    public String appVersionInfo() {
        try {
            Context context = mRuntime.uiHandler.getContext();
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode + "|" + (info.versionName == null ? "" : info.versionName);
        } catch (Exception e) {
            return "0|";
        }
    }

    /**
     * `context` 全局：Rhino 里直接给 Android Context 对象，白名单桥下只暴露常用目录与包名，
     * 目录类返回的路径字符串会由 JS 包装成带 `getAbsolutePath()` 的 file-like 对象。
     */
    public String contextInfo(String key) {
        Context context = mRuntime.uiHandler.getContext();
        File file;
        switch (key == null ? "" : key) {
            case "packageName":
                return context.getPackageName();
            case "filesDir":
                file = context.getFilesDir();
                break;
            case "cacheDir":
                file = context.getCacheDir();
                break;
            case "noBackupFilesDir":
                file = context.getNoBackupFilesDir();
                break;
            case "externalFilesDir":
                file = context.getExternalFilesDir(null);
                break;
            case "externalCacheDir":
                file = context.getExternalCacheDir();
                break;
            default:
                return null;
        }
        return file == null ? null : file.getAbsolutePath();
    }

    public boolean click(int x, int y) {
        return mRuntime.automator.click(x, y);
    }

    public boolean press(int x, int y, int duration) {
        return mRuntime.automator.press(x, y, duration);
    }

    public boolean longClick(int x, int y) {
        return mRuntime.automator.longClick(x, y);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        return mRuntime.automator.swipe(x1, y1, x2, y2, duration);
    }

    public boolean globalAction(String action) {
        switch (action) {
            case "back":
                return mRuntime.automator.back();
            case "home":
                return mRuntime.automator.home();
            case "recents":
                return mRuntime.automator.recents();
            case "notifications":
                return mRuntime.automator.notifications();
            case "quickSettings":
                return mRuntime.automator.quickSettings();
            case "powerDialog":
                return mRuntime.automator.powerDialog();
            case "splitScreen":
                return mRuntime.automator.splitScreen();
            default:
                throw new IllegalArgumentException("Unknown global action: " + action);
        }
    }

    public void setClip(String text) {
        mRuntime.setClip(text);
    }

    public String getClip() {
        return mRuntime.getClip();
    }

    public String getForegroundInfo(String kind) {
        if ("activity".equals(kind)) {
            return mRuntime.info.getLatestActivity();
        }
        return mRuntime.info.getLatestPackage();
    }

    public boolean requestScreenCapture(int orientation) {
        return requestScreenCapture(orientation, "projection");
    }

    public boolean requestScreenCapture(int orientation, String mode) {
        if ("accessibility".equals(mode)) return images().requestAccessibilityScreenCapture();
        if ("auto".equals(mode)) return images().requestAutoScreenCapture(normalizeOrientation(orientation));
        if (!"projection".equals(mode)) throw new IllegalArgumentException("未知截图通道：" + mode);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        return images().requestScreenCaptureBlocking(normalizeOrientation(orientation));
    }

    // ---- async 截图流（对齐 Pro 的 requestScreenCapture({async:true}) + screen_capture 事件）----
    /** 最新的异步帧（只保留一帧，旧的直接回收）。 */
    private volatile ImageWrapper mPendingAsyncFrame;
    private final Images.RawImageCaptureListener mRawCaptureListener = wrapper -> {
        ImageWrapper previous = mPendingAsyncFrame;
        mPendingAsyncFrame = wrapper;
        if (previous != null) previous.recycle();
    };

    /** 带 width/height/async 的申请（Pro 的 4 参版本）；async 时帧进入 takeAsyncScreenCaptureFrame()。 */
    public String getScreenCaptureOptions() throws JSONException {
        com.stardust.autojs.core.image.capture.ScreenCapturer.Options options = (com.stardust.autojs.core.image.capture.ScreenCapturer.Options) images().getScreenCaptureOptions();
        if (options == null) return "null";
        return new JSONObject().put("width", options.width).put("height", options.height)
                .put("orientation", options.orientation).put("density", options.density).put("async", options.async).toString();
    }

    /** A real asynchronous permission request: its result returns through the engine task queue. */
    public String requestScreenCaptureAsync(String optionsJson) throws JSONException {
        JSONObject options = new JSONObject(optionsJson);
        long callbackId = options.getLong("callbackId");
        boolean async = options.optBoolean("async");
        if (async) images().setRawImageCaptureListener(mRawCaptureListener);
        try {
            images().requestScreenCapture(normalizeOrientation(options.optInt("orientation", 0)),
                    options.optInt("width", -1), options.optInt("height", -1), async)
                    .onResolve(value -> {
                        if (!Boolean.TRUE.equals(value) && async) images().setRawImageCaptureListener(null);
                        postJsCallback(callbackId, new JSONArray().put(Boolean.TRUE.equals(value)).toString());
                    })
                    .onReject(error -> {
                        if (async) images().setRawImageCaptureListener(null);
                        try { postJsCallback(callbackId, new JSONArray().put(new JSONObject().put("error", String.valueOf(error))).toString()); }
                        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
                    });
            return "null";
        } catch (RuntimeException error) {
            if (async) images().setRawImageCaptureListener(null);
            throw error;
        }
    }

    public boolean requestScreenCaptureEx(int orientation, int width, int height, boolean async) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        if (async) images().setRawImageCaptureListener(mRawCaptureListener);
        try {
            return images().requestScreenCaptureBlocking(normalizeOrientation(orientation), width, height, async);
        } catch (RuntimeException error) {
            if (async) images().setRawImageCaptureListener(null);
            throw error;
        }
    }

    /**
     * async 截图流取帧：0=暂无帧，-1=会话已结束，&gt;0=NativeFrame 句柄。
     * 帧在这里转成 NativeFrame（与 captureScreenNative 同一套 QuickJsNativeBridge 通道）。
     */
    public long takeAsyncScreenCaptureFrame() {
        if (!images().isAsyncScreenCaptureActive()) {
            recyclePendingAsyncFrame();
            return -1;
        }
        ImageWrapper frame = mPendingAsyncFrame;
        if (frame == null) return 0;
        mPendingAsyncFrame = null;
        long engineHandle = mEngineHandle;
        if (engineHandle == 0) {
            frame.recycle();
            throw new IllegalStateException("QuickJS engine is not attached");
        }
        try {
            android.graphics.Bitmap bitmap = frame.getBitmap();
            if (mAccessibilityPixels == null || mAccessibilityPixels.capacity() < bitmap.getByteCount()) {
                mAccessibilityPixels = ByteBuffer.allocateDirect(bitmap.getByteCount());
            }
            mAccessibilityPixels.clear();
            bitmap.copyPixelsToBuffer(mAccessibilityPixels);
            mAccessibilityPixels.rewind();
            return QuickJsNativeBridge.createNativeFrame(engineHandle, mAccessibilityPixels,
                    bitmap.getWidth(), bitmap.getHeight(), bitmap.getRowBytes(), 4, 0);
        } finally {
            frame.recycle();
        }
    }

    public void imagesStopScreenCapture() {
        recyclePendingAsyncFrame();
        images().setRawImageCaptureListener(null);
        images().stopScreenCapture();
    }

    private void recyclePendingAsyncFrame() {
        ImageWrapper frame = mPendingAsyncFrame;
        mPendingAsyncFrame = null;
        if (frame != null) frame.recycle();
    }

    public long captureScreenNative(int targetShortEdge, boolean fresh, int timeoutMillis) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw new UnsupportedOperationException("Screen capture requires Android 5.0 or newer");
        }
        long engineHandle = mEngineHandle;
        if (engineHandle == 0) {
            throw new IllegalStateException("QuickJS engine is not attached");
        }
        images().prepareAutoCapture();
        if (images().isSharedScreenCapture()) {
            ImageWrapper frame = images().captureScreen(fresh, timeoutMillis);
            try {
                Bitmap bitmap = frame.getBitmap();
                if (mAccessibilityPixels == null || mAccessibilityPixels.capacity() < bitmap.getByteCount()) {
                    mAccessibilityPixels = ByteBuffer.allocateDirect(bitmap.getByteCount());
                }
                mAccessibilityPixels.clear();
                bitmap.copyPixelsToBuffer(mAccessibilityPixels);
                mAccessibilityPixels.rewind();
                return QuickJsNativeBridge.createNativeFrame(engineHandle, mAccessibilityPixels,
                        bitmap.getWidth(), bitmap.getHeight(), bitmap.getRowBytes(), 4, targetShortEdge);
            } finally {
                frame.recycle();
            }
        }
        if (images().isAccessibilityScreenCapture()) {
            try (com.stardust.autojs.core.image.capture.AccessibilityScreenCapturer.HardwareFrame frame
                         = images().captureAccessibilityHardware(timeoutMillis)) {
                long nativeFrame = QuickJsNativeBridge.createNativeFrameFromHardwareBuffer(
                        engineHandle, frame.getBuffer(), targetShortEdge);
                if (nativeFrame != 0) {
                    if (!mLoggedAccessibilityPath) {
                        Log.i("QuickJsHostBridge", "Accessibility capture: hardware-direct, no full Bitmap/ByteBuffer copy");
                        mLoggedAccessibilityPath = true;
                    }
                    return nativeFrame;
                }
                if (!mLoggedAccessibilityPath) {
                    Log.i("QuickJsHostBridge", "Accessibility capture: portable fallback, reusable ByteBuffer");
                    mLoggedAccessibilityPath = true;
                }
                android.graphics.Bitmap bitmap = frame.toBitmap();
                try {
                    if (mAccessibilityPixels == null || mAccessibilityPixels.capacity() < bitmap.getByteCount()) {
                        mAccessibilityPixels = ByteBuffer.allocateDirect(bitmap.getByteCount());
                    }
                    mAccessibilityPixels.clear();
                    bitmap.copyPixelsToBuffer(mAccessibilityPixels);
                    mAccessibilityPixels.rewind();
                    return QuickJsNativeBridge.createNativeFrame(engineHandle, mAccessibilityPixels,
                            bitmap.getWidth(), bitmap.getHeight(), bitmap.getRowBytes(), 4, targetShortEdge);
                } finally {
                    bitmap.recycle();
                }
            }
        }
        Image image;
        try {
            image = images().captureScreenRaw(fresh, timeoutMillis);
        } catch (RuntimeException error) {
            if (!images().hasStoppedAutoProjection()) throw error;
            return captureScreenNative(targetShortEdge, fresh, timeoutMillis);
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            throw new IllegalStateException("Screen capture returned no image planes");
        }
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        buffer.rewind();
        return QuickJsNativeBridge.createNativeFrame(
                engineHandle,
                buffer,
                image.getWidth(),
                image.getHeight(),
                plane.getRowStride(),
                plane.getPixelStride(),
                targetShortEdge
        );
    }

    public boolean isWindowCaptureAvailable() { return images().isWindowCaptureAvailable(); }

    public String listCaptureWindows() { return images().listCaptureWindows(); }

    public String captureWindowNative(String optionsJson) throws JSONException {
        JSONObject options = new JSONObject(optionsJson);
        if (mEngineHandle == 0) throw new IllegalStateException("QuickJS engine is not attached");
        com.stardust.autojs.core.image.capture.WindowCapture.Frame captured = images().captureWindow(
                options.optInt("windowId", -1), options.optString("packageName", ""),
                options.optLong("timeout", 2000), options.optBoolean("screenCoordinates", false));
        try {
            JSONObject metadata = new JSONObject(captured.metadata);
            Bitmap bitmap = captured.image.getBitmap();
            if (mAccessibilityPixels == null || mAccessibilityPixels.capacity() < bitmap.getByteCount()) {
                mAccessibilityPixels = ByteBuffer.allocateDirect(bitmap.getByteCount());
            }
            mAccessibilityPixels.clear();
            bitmap.copyPixelsToBuffer(mAccessibilityPixels);
            mAccessibilityPixels.rewind();
            long handle = QuickJsNativeBridge.createNativeFrame(mEngineHandle, mAccessibilityPixels,
                    bitmap.getWidth(), bitmap.getHeight(), bitmap.getRowBytes(), 4, options.optInt("size", 0));
            if (handle == 0) throw new IllegalStateException("窗口截图原生帧创建失败");
            return metadata.put("handle", handle).toString();
        } finally {
            captured.image.recycle();
        }
    }

    public String resolvePath(String path) {
        return mRuntime.files.path(path);
    }

    public byte[] readImageAsset(String path) {
        if (!isAssetPath(path)) {
            throw new IllegalArgumentException("Bundled image path must start with asset://");
        }
        try (InputStream input = getAssets().open(resolveBundledAssetName(stripAssetPrefix(path)));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to read bundled image: " + path, error);
        }
    }

    public boolean isYoloAvailable(String backend) {
        return mRuntime.yolo.isAvailable(backend);
    }

    public String getYoloVersion(String backend) {
        return mRuntime.yolo.getVersion(backend);
    }

    public String getYoloUnavailableReason(String backend) {
        return mRuntime.yolo.getUnavailableReason(backend);
    }

    public long loadYolo(String backend, String modelPath, String paramPath, String binPath,
                         int inputWidth, int inputHeight, int threads) {
        String normalized = normalizeQuickJsBackend(backend);
        AutoCloseable detector;
        if ("opencv".equals(normalized)) {
            if (isAssetPath(modelPath)) {
                try {
                    // task-118：老脚本可能还引用已改名的示例根目录（QuickJS 新引擎 → QuickJS 引擎）。
                    String assetName = resolveBundledAssetName(stripAssetPrefix(modelPath));
                    detector = mRuntime.yolo.createOpenCvFromAssets(assetName, inputWidth, inputHeight, threads);
                } catch (IOException error) {
                    throw new IllegalArgumentException("无法读取 YOLO 模型: " + modelPath, error);
                }
            } else {
                detector = mRuntime.yolo.createOpenCv(mRuntime.files.path(modelPath), inputWidth, inputHeight, threads);
            }
        } else {
            throw new IllegalArgumentException("QuickJS YOLO 不支持的 backend: " + normalized);
        }
        long handle = mNextYoloHandle.getAndIncrement();
        mYoloSessions.put(handle, new YoloSession(normalized, detector));
        return handle;
    }

    public float[] detectYolo(long sessionHandle, ByteBuffer rgba,
                              int width, int height, int rowStride,
                              int regionX, int regionY, int regionWidth, int regionHeight,
                              float confidence, float nmsThreshold) {
        YoloSession session = mYoloSessions.get(sessionHandle);
        if (session == null) {
            throw new IllegalStateException("QuickJS YOLO detector 已关闭");
        }
        AutoCloseable detector = session.detector;
        if ("opencv".equals(session.backend)) {
            return ((OpenCvYoloDetector) detector).detectRgba(rgba, width, height, rowStride,
                    regionX, regionY, regionWidth, regionHeight, confidence, nmsThreshold);
        }
        throw new IllegalStateException("QuickJS YOLO 不支持的 backend: " + session.backend);
    }

    public boolean closeYolo(long sessionHandle) {
        YoloSession session = mYoloSessions.remove(sessionHandle);
        if (session == null) return false;
        try {
            session.detector.close();
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot close YOLO detector", error);
        }
        return true;
    }

    // ---- OCR（$ocr）----
    //
    // 接口面与 Rhino 的 modules/engine/src/main/assets/modules/__ocr__.js 保持一致：
    //   $ocr.create([options]) -> { detect(image[, options]), release(), isReleased() }
    //   $ocr.liveCount()
    // 后端有两只：默认 ML Kit（模型内置、离线），backend:'ppocr' 则是 ncnn 版 PP-OCRv6。
    // Pro 的 models / cpuPowerMode / parallelThreads 这些 PaddleOCR 选项现在对 ppocr 后端**真的生效**。

    /** `$ocr.create({backend:'ppocr'})` 的后端编号（与 bootstrap 的约定一致）。 */
    private static final int OCR_BACKEND_PPOCR = 1;

    /**
     * `$ocr.create([options])`：建一个独立会话（脚本忘记 release 时靠引擎销毁兜底）。
     *
     * @param backend    0=ML Kit（默认） 1=PP-OCR（ncnn）
     * @param targetSize 检测长边（0=默认 768；只对 ppocr 有意义）
     * @param cpuPower   0=不绑核（推荐） 1=绑小核 2=绑大核簇
     * @param threads    识别工作线程数（0=默认 4）
     * @param useFp16    非 0 则开 ncnn fp16
     * @param recMaxWidth rec 裁剪宽度上限（0=默认 1024）
     */
    public long ocrCreate(int backend, int targetSize, int cpuPower, int threads,
                          int useFp16, int recMaxWidth) {
        OcrSession session = backend == OCR_BACKEND_PPOCR
                ? new PPOcr(mRuntime, null, cpuPower, threads, 0f, targetSize, useFp16 != 0, recMaxWidth)
                : new Ocr(mRuntime);
        long handle = mNextOcrHandle.getAndIncrement();
        mOcrSessions.put(handle, session);
        return handle;
    }

    /**
     * `session.detect(image[, options])`：RGBA 字节 → Bitmap → ML Kit，结果转 JSON（bootstrap 侧解析）。
     *
     * <p>走 RGBA 直接缓冲区的原因与 YOLO 一致：帧在 native 侧就是 CV_8UC4 的 Mat，
     * 把它整块交给 Java 比逐个像素跨 JNI 调用快得多。
     */
    public String ocrDetect(long handle, ByteBuffer rgba, int width, int height, int rowStride,
                            int max, boolean detectRotation,
                            int regionX, int regionY, int regionWidth, int regionHeight)
            throws JSONException {
        OcrSession session = mOcrSessions.get(handle);
        if (session == null || session.isReleased()) {
            throw new IllegalStateException("QuickJS OCR 会话不存在或已释放");
        }
        if (rgba == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("OCR 需要一张有效图像");
        }
        ImageWrapper image = mOcrImageBuffer.acquire(width, height);
        Bitmap bitmap = image.getBitmap();
        // 位图以 ARGB_8888 存成 RGBA 字节序，和 native 侧的 CV_8UC4 一致。
        // 行连续（step == width*4，屏幕帧与 clip/read 出来的图都是）时直接从直接缓冲区拷贝，
        // 不走中间的 byte[] —— 1080×2400 下这就是每帧省一次 10MB 分配 + 一次 10MB 拷贝。
        if (rowStride == width * 4) {
            ByteBuffer source = rgba.duplicate();
            source.position(0);
            source.limit(width * height * 4);
            bitmap.copyPixelsFromBuffer(source);
        } else {
            byte[] pixels = new byte[width * height * 4];
            ByteBuffer source = rgba.duplicate();
            int rowBytes = width * 4;
            for (int row = 0; row < height; row++) {
                source.position(row * rowStride);
                source.get(pixels, row * rowBytes, rowBytes);
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
        }
        int[] region = (regionWidth > 0 && regionHeight > 0)
                ? new int[]{regionX, regionY, regionWidth, regionHeight}
                : null;
        OCRResult[] results = session.detect(image, max, detectRotation, region);
        JSONArray array = new JSONArray();
        for (OCRResult result : results) {
            JSONObject item = new JSONObject();
            item.put("text", result.getText() == null ? "" : result.getText());
            item.put("confidence", result.getConfidence());
            item.put("rotation", result.getRotation());
            item.put("rotationConfidence", result.getRotationConfidence());
            Rect bounds = result.getBounds();
            JSONObject box = new JSONObject();
            if (bounds != null) {
                box.put("left", bounds.left);
                box.put("top", bounds.top);
                box.put("right", bounds.right);
                box.put("bottom", bounds.bottom);
                box.put("width", bounds.width());
                box.put("height", bounds.height());
                box.put("centerX", bounds.centerX());
                box.put("centerY", bounds.centerY());
            }
            item.put("bounds", box);
            array.put(item);
        }
        return array.toString();
    }

    /** `session.release()`：幂等（重复释放返回 false，与 Rhino 侧一致）。 */
    public boolean ocrRelease(long handle) {
        OcrSession session = mOcrSessions.remove(handle);
        if (session == null) {
            return false;
        }
        try {
            session.release();
        } finally {
            if (mOcrSessions.isEmpty()) mOcrImageBuffer.close();
        }
        return true;
    }

    /** `$ocr.liveCount()`：进程内未释放的会话数（探针用；两种后端都算）。 */
    public int ocrLiveCount() {
        return Ocr.liveCount() + PPOcr.liveCount();
    }

    // ---- Pikafish（$pikafish：中国象棋 NNUE 原生引擎）----
    //
    // 引擎本体是 third-party/Pikafish（arm64 上编译成 libpikafish.so，见 build-pikafish.ps1），
    // UCI 会话跑在 native 线程里。这里只做三件事：找 NNUE 权重、启停会话、把 UCI 输出
    // 解析成 JSON 交给 bootstrap 的 $pikafish。分数/着法语义（红方视角等）由 JS 侧适配。

    private static final String PIKAFISH_NNUE_NAME = "pikafish.nnue";
    private static final Pattern PIKAFISH_POSITION = Pattern.compile(
            "^position fen (?:[rnbakcpRNBAKCP1-9]+/){9}[rnbakcpRNBAKCP1-9]+ [wb] - - [0-9]+ [1-9][0-9]*"
                    + "(?: moves(?: [a-i][0-9][a-i][0-9]){1,512})?$");
    /**
     * `$pikafish.analyze` 的 FEN 校验：必须是完整六段（棋盘段 + 走棋方 + "- -" + 两个计数）。
     *
     * <p>⚠️ 只给棋盘段（缺 ` w - - 0 1`）会让 native 引擎解析非法局面、**把整个 App 进程静默带走**
     * （2026-09-25 真机实测：无墓碑、无 ANR、无 FATAL、dropbox 无记录），所以这里在进 native 前就拦掉。
     */
    private static final Pattern PIKAFISH_FEN = Pattern.compile(
            "^(?:[rnbakcpRNBAKCP1-9]+/){9}[rnbakcpRNBAKCP1-9]+ [wb] - - [0-9]+ [1-9][0-9]*$");

    /**
     * `$pikafish.start([options])`：启动（或复用）引擎会话。
     *
     * @param evalFile NNUE 权重路径；null/空 = 依次找 filesDir / externalFilesDir 下的 pikafish.nnue
     * @param threads  搜索线程数（K40 上 2~4 比较合适）
     * @param hashMb   置换表大小 MB（64~256；权重本身常驻约 50MB，别开太大）
     * @param contempt 避和倾向（自编译补丁选项；-200..200，0 = 关）
     * @return JSON：{ok, running, evalFile, threads, hashMb, contempt, options}
     *         或 {ok:false, error}；options 是启动时抓到的 "option name ..." 行（真机自检用）
     */
    public String pikafishStart(String evalFile, int threads, int hashMb, int contempt) throws JSONException {
        JSONObject result = new JSONObject();
        if (!PikafishNativeBridge.isAvailable()) {
            result.put("ok", false);
            result.put("error", "Pikafish 原生库不可用（" + PikafishNativeBridge.loadError()
                    + "）；目前只随包 arm64-v8a");
            return result.toString();
        }
        String nnue = resolvePikafishNnuePath(evalFile);
        if (nnue == null) {
            result.put("ok", false);
            result.put("error", "找不到 " + PIKAFISH_NNUE_NAME + "（" + defaultPikafishNnueHint()
                    + "）；可以装『象棋引擎插件』（自带权重），或把 " + PIKAFISH_NNUE_NAME
                    + " 放到上面任一目录，或用 $pikafish.start({evalFile:'/绝对路径'}) 指定");
            return result.toString();
        }
        int clampedContempt = Math.max(-200, Math.min(200, contempt));
        boolean ok = PikafishNativeBridge.nativeStart(nnue, threads, hashMb, clampedContempt);
        result.put("ok", ok);
        result.put("running", PikafishNativeBridge.nativeIsRunning());
        result.put("evalFile", nnue);
        result.put("threads", PikafishNativeBridge.nativeConfiguredThreads());
        result.put("hashMb", hashMb);
        result.put("contempt", clampedContempt);
        result.put("options", readPikafishOptions());
        if (!ok) {
            result.put("error", "原生引擎启动失败（uciok/readyok 阶段超时；权重文件可读吗：" + nnue + "）");
        }
        return result.toString();
    }

    /**
     * 启动时抓到的 UCI 选项行（自检用：确认 Contempt 等选项真的注册在引擎里）。
     * 旧 .so 没有这个导出、或会话未启动时返回空数组，绝不让它打断 start 的结果。
     */
    private JSONArray readPikafishOptions() {
        JSONArray options = new JSONArray();
        try {
            String raw = PikafishNativeBridge.nativeOptions();
            if (raw != null) {
                for (String line : raw.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        options.put(trimmed);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 旧版 .so（没有 _nativeOptions 符号）或加载异常：给空清单即可
        }
        return options;
    }

    /**
     * `$pikafish.analyze(fen, movetimeMs[, timeoutMs[, multiPv]])`：算一步棋（阻塞）。
     *
     * <p>multiPv &gt; 1 时引擎会同时报前 N 路着法（每路给分数/深度/主变），脚本侧可以
     * 避开「游戏拒绝过的着法/循环排除表」再挑 —— 以前带排除表就只能退回 JS 引擎（深度 3~4），
     * 这是 v1.1 补上的口子。
     *
     * @return JSON：成功 {ok:true, move, scoreCp|mate, depth, nodes, timeMs, pv,
     * candidates:[{multipv,move,scoreCp,mix,depth,pv}...], raw}；失败 {ok:false, error}
     */
    public String pikafishAnalyze(String fen, long movetimeMs, long timeoutMs, int multiPv, int depth)
            throws JSONException {
        return pikafishAnalyzeInternal(fen, movetimeMs, timeoutMs, multiPv, depth, false);
    }

    /** Search from a confirmed `position fen ... moves ...` history. */
    public String pikafishAnalyzePosition(String positionCommand, long movetimeMs,
                                          long timeoutMs, int multiPv, int depth) throws JSONException {
        return pikafishAnalyzeInternal(positionCommand, movetimeMs, timeoutMs, multiPv, depth, true);
    }

    private String pikafishAnalyzeInternal(String input, long movetimeMs, long timeoutMs,
                                           int multiPv, int depth, boolean withHistory) throws JSONException {
        if (mScriptStopRequested) {
            throw new com.stardust.autojs.runtime.exception.ScriptInterruptedException();
        }
        JSONObject result = new JSONObject();
        if (!PikafishNativeBridge.isAvailable()) {
            result.put("ok", false);
            result.put("error", "Pikafish 原生库不可用（" + PikafishNativeBridge.loadError() + "）");
            return result.toString();
        }
        if (input != null) {
            input = input.trim();
        }
        if (input == null || input.length() > 4096 ||
                (withHistory && !PIKAFISH_POSITION.matcher(input).matches()) ||
                (!withHistory && !PIKAFISH_FEN.matcher(input).matches())) {
            result.put("ok", false);
            result.put("error", withHistory ? "无效的 Pikafish 局面历史命令"
                    : "无效 FEN：缺 走棋方/回合 字段（应带「 w - - 0 1」）");
            return result.toString();
        }
        String raw;
        mPikafishAnalyzeTid = android.os.Process.myTid();
        try {
            raw = withHistory
                    ? PikafishNativeBridge.nativeAnalyzePosition(input, movetimeMs, timeoutMs, multiPv, depth)
                    : PikafishNativeBridge.nativeAnalyze(input, movetimeMs, timeoutMs, multiPv, depth);
        } catch (UnsatisfiedLinkError error) {
            result.put("ok", false);
            result.put("error", "Pikafish 原生库未包含棋谱历史接口：" + error.getMessage());
            return result.toString();
        } finally {
            mPikafishAnalyzeTid = 0;
        }
        // nativeStop 会正常返回 bestmove；脚本已停止时不能再把它当成可执行的走法。
        if (mScriptStopRequested) {
            throw new com.stardust.autojs.runtime.exception.ScriptInterruptedException();
        }
        if (raw == null) {
            raw = "";
        }
        String bestmoveLine = null;
        // 同一路 multipv 取**最后**一条 info（搜索是逐层加深的，后面的深度更大）
        Map<Integer, PikafishInfo> byPv = new TreeMap<>();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.startsWith("bestmove")) {
                bestmoveLine = line;
                continue;
            }
            if (!line.startsWith("info ")) {
                continue;
            }
            PikafishInfo info = parsePikafishInfoLine(line);
            if (info.move == null) {
                continue;
            }
            byPv.put(info.multipv, info);
        }
        String bestmove = null;
        if (bestmoveLine != null) {
            String[] bestParts = bestmoveLine.split("\\s+");
            bestmove = bestParts.length >= 2 ? bestParts[1] : null;
        }
        if (byPv.isEmpty() && (bestmove == null || bestmove.isEmpty() || "(none)".equals(bestmove))) {
            result.put("ok", false);
            result.put("error", raw.isEmpty() ? "引擎没有输出" : raw);
            return result.toString();
        }
        if (byPv.isEmpty()) {
            // 极快收敛（如单步杀）时可能只有 bestmove、没有 info：当成一个无分数的候选
            PikafishInfo info = new PikafishInfo();
            info.move = bestmove;
            byPv.put(1, info);
        }
        JSONArray candidates = new JSONArray();
        for (PikafishInfo info : byPv.values()) {
            candidates.put(info.toJson());
        }
        PikafishInfo top = byPv.values().iterator().next();
        result.put("ok", true);
        // 顶层 move 优先用引擎的 bestmove（与 MultiPV 次序一致的 1 号路），没有就用候选头名
        result.put("move", bestmove != null && !bestmove.isEmpty() && !"(none)".equals(bestmove)
                ? bestmove : top.move);
        if (top.scoreCp != null) {
            result.put("scoreCp", top.scoreCp.intValue());
        }
        if (top.mate != null) {
            result.put("mate", top.mate.intValue());
        }
        if (top.depth != null) {
            result.put("depth", top.depth.intValue());
        }
        if (top.seldepth != null) {
            result.put("seldepth", top.seldepth.intValue());
        }
        if (top.nodes != null) {
            result.put("nodes", top.nodes.intValue());
        }
        if (top.timeMs != null) {
            result.put("timeMs", top.timeMs.intValue());
        }
        result.put("pv", top.pv);
        result.put("candidates", candidates);
        result.put("raw", raw);
        return result.toString();
    }

    /** `$pikafish.stop()`：打断正在跑的搜索（analyze 会带着 bestmove 返回）。 */
    public void pikafishStop() {
        if (PikafishNativeBridge.isAvailable()) {
            PikafishNativeBridge.nativeStop();
        }
    }

    /** 停止本脚本正在等待的原生搜索，不影响其他 QuickJS 脚本。 */
    void stopOwnPikafishSearch() {
        mScriptStopRequested = true;
        int tid = mPikafishAnalyzeTid;
        if (tid != 0 && PikafishNativeBridge.isAvailable()) {
            PikafishNativeBridge.nativeStopForTid(tid);
        }
    }

    /** `$pikafish.quit()`：退出引擎线程（幂等；下次 start 会重新拉起）。 */
    public void pikafishQuit() {
        if (PikafishNativeBridge.isAvailable()) {
            PikafishNativeBridge.nativeQuit();
        }
    }

    public boolean pikafishIsRunning() {
        return PikafishNativeBridge.isAvailable() && PikafishNativeBridge.nativeIsRunning();
    }

    public boolean pikafishAvailable() {
        return PikafishNativeBridge.isAvailable();
    }

    /** 找权重：显式路径优先；否则已有私有文件 → 外部目录 → 公共目录 → APK 内置资产（可选打包）。 */
    private String resolvePikafishNnuePath(String evalFile) {
        if (evalFile != null && evalFile.length() > 0) {
            File file = new File(evalFile);
            return file.isFile() ? file.getAbsolutePath() : null;
        }
        for (File candidate : defaultPikafishNnueCandidates()) {
            if (candidate.isFile() && candidate.length() > 20_000_000L) {
                return candidate.getAbsolutePath();
            }
        }
        return extractBundledPikafishNnue();
    }

    /**
     * 内置权重（只有用 {@code -PwithPikafishNnue=true} 构建的包才有）。
     *
     * <p>正式包不再随包 50MB 权重：用象棋请装「象棋引擎插件」（自带权重），
     * 或者手工把 pikafish.nnue 放到候选目录里。资产缺失不是错误，静默返回 null，
     * 由调用方给出「权重从哪来」的指引。
     */
    private String extractBundledPikafishNnue() {
        try {
            Context context = mRuntime.uiHandler.getContext().getApplicationContext();
            File target = new File(context.getFilesDir(), PIKAFISH_NNUE_NAME);
            synchronized (PikafishNativeBridge.class) {
                if (target.isFile() && target.length() > 20_000_000L) {
                    return target.getAbsolutePath();
                }
                File temporary = new File(context.getFilesDir(), PIKAFISH_NNUE_NAME + ".tmp");
                long copied = 0;
                try (InputStream input = context.getAssets().open(PIKAFISH_NNUE_NAME);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        copied += count;
                    }
                    output.getFD().sync();
                }
                if (copied <= 20_000_000L) {
                    temporary.delete();
                    return null;
                }
                if (!temporary.renameTo(target)) {
                    temporary.delete();
                    return null;
                }
                return target.getAbsolutePath();
            }
        } catch (IOException | RuntimeException error) {
            Log.w("QuickJsHostBridge", "Bundled Pikafish NNUE extraction failed", error);
            return null;
        }
    }

    private List<File> defaultPikafishNnueCandidates() {
        List<File> candidates = new ArrayList<>();
        try {
            Context context = mRuntime.uiHandler.getContext().getApplicationContext();
            candidates.add(new File(context.getFilesDir(), PIKAFISH_NNUE_NAME));
            File external = context.getExternalFilesDir(null);
            if (external != null) {
                candidates.add(new File(external, PIKAFISH_NNUE_NAME));
            }
            // 象棋工程自己认的公共目录（用户手工放权重最常见的位置）。
            candidates.add(new File("/sdcard/xiangqi-p4/" + PIKAFISH_NNUE_NAME));
        } catch (Throwable ignored) {
            // 拿不到 Context 时只能靠显式路径
        }
        return candidates;
    }

    private String defaultPikafishNnueHint() {
        StringBuilder hint = new StringBuilder();
        for (File candidate : defaultPikafishNnueCandidates()) {
            if (hint.length() > 0) {
                hint.append(" 或 ");
            }
            hint.append(candidate.getAbsolutePath());
        }
        return hint.length() == 0 ? "(无法解析默认目录)" : hint.toString();
    }

    /** 一条 UCI info 行解析出来的候选（multipv 路号 + 着法 + 分数/深度/主变）。 */
    private static final class PikafishInfo {
        int multipv = 1;
        String move;
        Integer scoreCp;
        Integer mate;
        Integer depth;
        Integer seldepth;
        Integer nodes;
        Integer timeMs;
        String pv = "";

        JSONObject toJson() throws JSONException {
            JSONObject item = new JSONObject();
            item.put("multipv", multipv);
            item.put("move", move == null ? "" : move);
            if (scoreCp != null) {
                item.put("scoreCp", scoreCp.intValue());
            }
            if (mate != null) {
                item.put("mate", mate.intValue());
            }
            if (depth != null) {
                item.put("depth", depth.intValue());
            }
            if (seldepth != null) {
                item.put("seldepth", seldepth.intValue());
            }
            if (nodes != null) {
                item.put("nodes", nodes.intValue());
            }
            if (timeMs != null) {
                item.put("timeMs", timeMs.intValue());
            }
            item.put("pv", pv);
            return item;
        }
    }

    /**
     * 解析 "info depth 14 seldepth 22 multipv 2 score cp 12 nodes 123 time 789 pv h2e2 ..."。
     * move = pv 的第一个 token（引擎着法规则就是主变头）。
     */
    private static PikafishInfo parsePikafishInfoLine(String infoLine) {
        PikafishInfo info = new PikafishInfo();
        String[] tokens = infoLine.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "multipv":
                    if (i + 1 < tokens.length) {
                        Integer value = tryParseInt(tokens[i + 1]);
                        if (value != null && value.intValue() > 0) {
                            info.multipv = value.intValue();
                        }
                    }
                    break;
                case "depth":
                    info.depth = intAt(tokens, i + 1);
                    break;
                case "seldepth":
                    info.seldepth = intAt(tokens, i + 1);
                    break;
                case "nodes":
                    info.nodes = intAt(tokens, i + 1);
                    break;
                case "time":
                    info.timeMs = intAt(tokens, i + 1);
                    break;
                case "score":
                    if (i + 2 < tokens.length) {
                        Integer value = tryParseInt(tokens[i + 2]);
                        if ("cp".equals(tokens[i + 1])) {
                            info.scoreCp = value;
                        } else if ("mate".equals(tokens[i + 1])) {
                            info.mate = value;
                        }
                    }
                    break;
                case "pv":
                    StringBuilder pv = new StringBuilder();
                    for (int j = i + 1; j < tokens.length; j++) {
                        if (pv.length() > 0) {
                            pv.append(' ');
                        }
                        pv.append(tokens[j]);
                    }
                    info.pv = pv.toString();
                    i = tokens.length;  // pv 之后没有别的字段
                    break;
                default:
                    break;
            }
        }
        if (!info.pv.isEmpty()) {
            int space = info.pv.indexOf(' ');
            info.move = space > 0 ? info.pv.substring(0, space) : info.pv;
        }
        return info;
    }

    private static Integer intAt(String[] tokens, int index) {
        return index < tokens.length ? tryParseInt(tokens[index]) : null;
    }

    private static Integer tryParseInt(String text) {
        try {
            return Integer.valueOf(Integer.parseInt(text));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String normalizeQuickJsBackend(String backend) {
        if (backend == null) return "opencv";
        String normalized = backend.trim().toLowerCase();
        if (normalized.isEmpty() || "cpu".equals(normalized)) return "opencv";
        if ("opencv-dnn".equals(normalized) || "opencv5".equals(normalized)
                || "dnn".equals(normalized)) return "opencv";
        return normalized;
    }

    public String readYoloLabels(String path) {
        try (InputStream input = isAssetPath(path)
                ? getAssets().open(resolveBundledAssetName(stripAssetPrefix(path)))
                : new FileInputStream(mRuntime.files.path(path));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalArgumentException("无法读取 YOLO labels: " + path, error);
        }
    }

    // ---- files whitelist ----

    public boolean filesExists(String path) {
        return mRuntime.files.exists(path);
    }

    public boolean filesIsFile(String path) {
        return mRuntime.files.isFile(path);
    }

    public boolean filesIsDir(String path) {
        return mRuntime.files.isDir(path);
    }

    public String filesRead(String path) {
        return mRuntime.files.read(path);
    }

    public void filesWrite(String path, String text) {
        mRuntime.files.write(path, text);
    }

    public void filesAppend(String path, String text) {
        mRuntime.files.append(path, text);
    }

    public boolean filesCreate(String path) {
        return mRuntime.files.createWithDirs(path);
    }

    public boolean filesEnsureDir(String path) {
        return mRuntime.files.ensureDir(path);
    }

    public String filesListDir(String path) {
        String[] children = mRuntime.files.listDir(path);
        JSONArray array = new JSONArray();
        if (children != null) {
            for (String child : children) {
                array.put(child);
            }
        }
        return array.toString();
    }

    public boolean filesRemove(String path) {
        return mRuntime.files.remove(path);
    }

    public boolean filesRename(String path, String newName) {
        return mRuntime.files.rename(path, newName);
    }

    public boolean filesCopy(String source, String target) {
        return mRuntime.files.copy(source, target);
    }

    public boolean filesMove(String source, String target) {
        return mRuntime.files.move(source, target);
    }

    public String filesCwd() {
        return mRuntime.files.cwd();
    }

    public String filesGetSdcardPath() {
        return mRuntime.files.getSdcardPath();
    }

    public String filesGetName(String path) {
        return mRuntime.files.getName(path);
    }

    public String filesGetNameWithoutExtension(String path) {
        return mRuntime.files.getNameWithoutExtension(path);
    }

    public String filesGetExtension(String path) {
        return mRuntime.files.getExtension(path);
    }

    public boolean filesIsEmptyDir(String path) {
        return mRuntime.files.isEmptyDir(path);
    }

    public boolean filesCreateIfNotExists(String path) {
        return mRuntime.files.createIfNotExists(path);
    }

    public boolean filesRemoveDir(String path) {
        return mRuntime.files.removeDir(path);
    }

    public byte[] filesReadBytes(String path) {
        return mRuntime.files.readBytes(path);
    }

    public String filesDirname(String path) {
        return mRuntime.files.dirname(path);
    }

    public String filesStat(String path) {
        JSONObject object = new JSONObject();
        try {
            Files.FileStat stat = mRuntime.files.stat(path);
            object.put("path", stat.getPath());
            object.put("lastModified", stat.getLastModified());
            object.put("size", stat.getSize());
            object.put("readable", stat.isReadable());
            object.put("writable", stat.isWritable());
            object.put("executable", stat.isExecutable());
            object.put("hidden", stat.isHidden());
        } catch (Throwable ignored) {
        }
        return object.toString();
    }

    // ---- http whitelist ----

    public String httpRequest(String method, String url, String headersJson,
                              String body, String contentType) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("http url is required");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        String methodUpper = method == null || method.isEmpty()
                ? "GET" : method.toUpperCase(Locale.US);
        Request.Builder builder = new Request.Builder().url(url);
        try {
            JSONObject headers = new JSONObject(headersJson == null ? "{}" : headersJson);
            JSONArray names = headers.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    Object value = headers.get(name);
                    if (value instanceof JSONArray) {
                        JSONArray values = (JSONArray) value;
                        for (int j = 0; j < values.length(); j++) {
                            builder.header(name, String.valueOf(values.get(j)));
                        }
                    } else {
                        builder.header(name, String.valueOf(value));
                    }
                }
            }
        } catch (JSONException ignored) {
            // invalid header payload: proceed without headers
        }
        RequestBody requestBody = null;
        if (body != null && !body.isEmpty() && !"GET".equals(methodUpper) && !"HEAD".equals(methodUpper)) {
            MediaType mediaType = contentType == null || contentType.isEmpty()
                    ? null : MediaType.parse(contentType);
            requestBody = RequestBody.create(mediaType, body);
        }
        builder.method(methodUpper, requestBody);
        try (Response response = mHttpClient.client().newCall(builder.build()).execute()) {
            JSONObject result = new JSONObject();
            result.put("statusCode", response.code());
            result.put("statusMessage", response.message() == null ? "" : response.message());
            result.put("method", methodUpper);
            result.put("url", response.request().url().toString());
            JSONObject responseHeaders = new JSONObject();
            Headers headers = response.headers();
            for (int i = 0; i < headers.size(); i++) {
                String name = headers.name(i);
                String value = headers.value(i);
                if (responseHeaders.has(name)) {
                    Object existing = responseHeaders.get(name);
                    JSONArray values = existing instanceof JSONArray
                            ? (JSONArray) existing : new JSONArray().put(existing);
                    responseHeaders.put(name, values.put(value));
                } else {
                    responseHeaders.put(name, value);
                }
            }
            result.put("headers", responseHeaders);
            ResponseBody responseBody = response.body();
            result.put("body", responseBody == null ? "" : responseBody.string());
            result.put("contentType", responseBody == null || responseBody.contentType() == null
                    ? "" : responseBody.contentType().toString());
            return result.toString();
        } catch (IOException error) {
            throw new UncheckedIOException(new IOException(
                    "QuickJS http request failed: " + error.getMessage(), error));
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to serialize http response", error);
        }
    }

    // ---- app module: additional APIs ----

    public String appGetPackageName(String appName) {
        try {
            String pkg = mRuntime.app.getPackageName(appName);
            return pkg != null ? pkg : "";
        } catch (Throwable e) { return ""; }
    }

    public String appGetAppName(String packageName) {
        try {
            String name = mRuntime.app.getAppName(packageName);
            return name != null ? name : "";
        } catch (Throwable e) { return ""; }
    }

    public boolean appOpenAppSetting(String packageName) {
        try {
            mRuntime.app.openAppSetting(packageName);
            return true;
        } catch (Throwable e) { return false; }
    }

    public boolean appViewFile(String path) {
        try {
            mRuntime.app.viewFile(path);
            return true;
        } catch (Throwable e) { return false; }
    }

    public boolean appEditFile(String path) {
        try {
            mRuntime.app.editFile(path);
            return true;
        } catch (Throwable e) { return false; }
    }

    public boolean appUninstall(String packageName) {
        try {
            mRuntime.app.uninstall(packageName);
            return true;
        } catch (Throwable e) { return false; }
    }

    /**
     * app.getTopActivity()（QuickJS 版）：返回 "className|packageName"，无前台 Activity 返回 ""。
     */
    public String appGetTopActivityInfo() {
        try {
            android.app.Activity activity = mRuntime.app.getTopActivity();
            if (activity == null) {
                return "";
            }
            return activity.getClass().getName() + "|" + activity.getPackageName();
        } catch (Throwable e) {
            return "";
        }
    }

    /** app.getPathFromUri(uri)：解析失败返回 ""。 */
    public String appGetPathFromUri(String uri) {
        try {
            String path = mRuntime.app.getPathFromUri(android.net.Uri.parse(uri));
            return path == null ? "" : path;
        } catch (Throwable e) {
            return "";
        }
    }

    public boolean appStartActivity(String action, String packageName, String className,
                                     String data, String type, String extrasJson, int flags) {
        try {
            android.content.Intent intent = new android.content.Intent();
            if (action != null && !action.isEmpty()) intent.setAction(action);
            if (packageName != null && className != null && !className.isEmpty()) {
                intent.setClassName(packageName, className);
            } else if (packageName != null && !packageName.isEmpty()) {
                intent.setPackage(packageName);
            }
            if (data != null && !data.isEmpty()) {
                android.net.Uri uri = android.net.Uri.parse(data);
                if (type != null && !type.isEmpty()) {
                    intent.setDataAndType(uri, type);
                } else {
                    intent.setData(uri);
                }
            } else if (type != null && !type.isEmpty()) {
                intent.setType(type);
            }
            if (flags != 0) intent.setFlags(flags);
            if (extrasJson != null && !extrasJson.isEmpty()) {
                JSONObject extras = new JSONObject(extrasJson);
                java.util.Iterator<String> keys = extras.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = extras.get(key);
                    if (val instanceof String) intent.putExtra(key, (String) val);
                    else if (val instanceof Integer) intent.putExtra(key, (Integer) val);
                    else if (val instanceof Boolean) intent.putExtra(key, (Boolean) val);
                    else if (val instanceof Long) intent.putExtra(key, (Long) val);
                    else if (val instanceof Double) intent.putExtra(key, (Double) val);
                }
            }
            Context context = mRuntime.app.getCurrentActivity();
            if (context == null) {
                context = mRuntime.uiHandler.getContext().getApplicationContext();
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable e) {
            Log.w("QuickJsHostBridge", "appStartActivity failed: " + e.getMessage());
            return false;
        }
    }

    // ---- device module: additional APIs ----

    public boolean deviceIsCharging() {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent battery = mRuntime.uiHandler.getContext()
                    .registerReceiver(null, filter);
            if (battery == null) return false;
            int status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable e) { return false; }
    }

    public int deviceGetBrightness() {
        try {
            return Settings.System.getInt(mRuntime.uiHandler.getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
        } catch (Throwable e) { return -1; }
    }

    public int deviceGetBrightnessMode() {
        try {
            return Settings.System.getInt(mRuntime.uiHandler.getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE);
        } catch (Throwable e) { return -1; }
    }

    public void deviceCancelVibration() {
        try {
            android.os.Vibrator v = (android.os.Vibrator)
                    mRuntime.uiHandler.getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Throwable ignored) {}
    }

    public long deviceGetAvailMem() {
        try {
            android.app.ActivityManager manager = (android.app.ActivityManager)
                    mRuntime.uiHandler.getContext().getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return info.availMem;
        } catch (Throwable error) {
            return 0;
        }
    }

    public long deviceGetTotalMem() {
        try {
            android.app.ActivityManager manager = (android.app.ActivityManager)
                    mRuntime.uiHandler.getContext().getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return info.totalMem;
        } catch (Throwable error) {
            return 0;
        }
    }

    public String deviceGetIMEI() {
        try {
            return mRuntime.device.getIMEI();
        } catch (Throwable error) {
            return null;
        }
    }

    public int deviceGetMusicVolume() {
        return mRuntime.device.getMusicVolume();
    }

    public int deviceGetNotificationVolume() {
        return mRuntime.device.getNotificationVolume();
    }

    public void deviceKeepScreenOn() {
        mRuntime.device.keepScreenOn();
    }

    public void deviceKeepScreenOnMs(int timeout) {
        mRuntime.device.keepScreenOn(timeout);
    }

    public void deviceWakeUp() {
        mRuntime.device.wakeUp();
    }

    public String deviceGetScreenOrientation() {
        return mRuntime.device.getScreenOrientation();
    }

    public int deviceGetScreenRotation() {
        return mRuntime.device.getScreenRotation();
    }

    // ---- shell module ----

    public String shellExecute(String command, boolean root, boolean shizuku,
                               int timeoutMs, int maxOutput) {
        timeoutMs = Math.max(1, timeoutMs);
        maxOutput = Math.max(0, Math.min(maxOutput, 16 * 1024 * 1024));
        if (root && shizuku) {
            return shellResult(-1, "", "root 和 shizuku 不能同时启用");
        }
        if (shizuku) {
            ShizukuShell.Result privilegedResult = ShizukuShell.execute(command, timeoutMs, maxOutput);
            return shellResult(privilegedResult.code, privilegedResult.output, privilegedResult.error);
        }
        File pidFile = null;
        try {
            pidFile = File.createTempFile("quickjs-shell-", ".pid",
                    mRuntime.uiHandler.getContext().getCacheDir());
            String trackedCommand = "echo $$ > " + shellQuote(pidFile.getAbsolutePath())
                    + "\n" + command;
            ProcessBuilder pb = new ProcessBuilder();
            if (root) {
                pb.command("su", "-c", "setsid sh -c " + shellQuote(trackedCommand));
            } else {
                pb.command("setsid", "sh", "-c", trackedCommand);
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            mShellProcesses.add(process);
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                final int outputLimit = maxOutput;
                Thread outputReader = new Thread(() -> {
                    try (java.io.InputStream input = process.getInputStream()) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            int remaining = outputLimit - baos.size();
                            if (remaining > 0) {
                                baos.write(buffer, 0, Math.min(read, remaining));
                            }
                            // Keep draining after the limit so the child process cannot block on a full pipe.
                        }
                    } catch (IOException ignored) {
                        // Closing/destroying a timed-out process normally closes this stream.
                    }
                }, "QuickJS-shell-output");
                outputReader.setDaemon(true);
                outputReader.start();

                boolean finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                int code = -1;
                if (finished) {
                    code = process.exitValue();
                } else {
                    int shellPid = readPid(pidFile);
                    terminateProcessGroup(process, shellPid, root);
                }
                outputReader.join(finished ? 1000 : 200);
                String output = baos.toString("UTF-8");
                JSONObject result = new JSONObject();
                result.put("code", code);
                result.put("result", output);
                result.put("error", finished ? "" : "timeout");
                return result.toString();
            } finally {
                mShellProcesses.remove(process);
            }
        } catch (Throwable e) {
            try {
                JSONObject err = new JSONObject();
                err.put("code", -1);
                err.put("result", "");
                err.put("error", e.getMessage());
                return err.toString();
            } catch (JSONException je) { return "{\"code\":-1,\"result\":\"\",\"error\":\"unknown\"}"; }
        } finally {
            if (pidFile != null && pidFile.exists() && !pidFile.delete()) {
                pidFile.deleteOnExit();
            }
        }
    }

    /**
     * Stops the complete command tree, not only the outer {@code sh -c} process. Android's
     * {@link Process#destroyForcibly()} targets one PID; a child such as {@code sleep} can survive
     * it and keep the stdout pipe open until its natural exit. The shell writes its own PID before
     * running the user's command, avoiding hidden Process APIs on newer Android releases. It runs
     * the command in a dedicated session/process group, then terminates that group on timeout.
     */
    private static void terminateProcessGroup(Process process, int rootPid, boolean root) {
        if (rootPid > 0) {
            try {
                if (root) {
                    Process killer = new ProcessBuilder("su", "-c",
                            "kill -9 -- -" + rootPid).start();
                    killer.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    killer.destroy();
                } else {
                    Process killer = new ProcessBuilder("kill", "-9", "--",
                            "-" + rootPid).start();
                    killer.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    killer.destroy();
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            process.destroyForcibly();
        } catch (Throwable ignored) {
        }
    }

    private static int readPid(File pidFile) {
        try (BufferedReader reader = new BufferedReader(new java.io.FileReader(pidFile))) {
            String value = reader.readLine();
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String shellResult(int code, String output, String error) {
        try {
            JSONObject result = new JSONObject();
            result.put("code", code);
            result.put("result", output == null ? "" : output);
            result.put("error", error == null ? "" : error);
            return result.toString();
        } catch (JSONException ignored) {
            return "{\"code\":-1,\"result\":\"\",\"error\":\"json error\"}";
        }
    }

    public boolean shellIsRootAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id").start();
            mShellProcesses.add(process);
            boolean finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                return false;
            }
            return process.exitValue() == 0;
        } catch (Throwable e) {
            if (process != null) {
                try {
                    process.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
            return false;
        } finally {
            if (process != null) {
                mShellProcesses.remove(process);
            }
        }
    }

    public boolean shellIsShizukuAvailable() {
        return ShizukuShell.isAvailable();
    }

    public boolean shellHasShizukuPermission() {
        return ShizukuShell.hasPermission();
    }

    public boolean shellRequestShizukuPermission(int timeoutMs) {
        return ShizukuShell.requestPermission(Math.max(1000, Math.min(timeoutMs, 120000)));
    }

    private final java.util.Set<Process> mShellProcesses =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ---- dialogs module ----

    public String dialogAlert(String title, String content) {
        try {
            android.app.Activity activity = mRuntime.app.getCurrentActivity();
            if (activity == null) return "error:no_activity";
            final Object[] result = new Object[1];
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    new com.afollestad.materialdialogs.MaterialDialog.Builder(activity)
                            .title(title)
                            .content(content)
                            .positiveText(android.R.string.ok)
                            .onPositive((d, w) -> { result[0] = "ok"; latch.countDown(); })
                            .cancelListener(d -> { result[0] = "cancel"; latch.countDown(); })
                            .show();
                } catch (Throwable e) { result[0] = "error:" + e.getMessage(); latch.countDown(); }
            });
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            return String.valueOf(result[0]);
        } catch (Throwable e) { return "error:" + e.getMessage(); }
    }

    public String dialogConfirm(String title, String content) {
        try {
            android.app.Activity activity = mRuntime.app.getCurrentActivity();
            if (activity == null) return "error:no_activity";
            final Object[] result = new Object[1];
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    new com.afollestad.materialdialogs.MaterialDialog.Builder(activity)
                            .title(title)
                            .content(content)
                            .positiveText(android.R.string.ok)
                            .negativeText(android.R.string.cancel)
                            .onPositive((d, w) -> { result[0] = true; latch.countDown(); })
                            .onNegative((d, w) -> { result[0] = false; latch.countDown(); })
                            .cancelListener(d -> { result[0] = false; latch.countDown(); })
                            .show();
                } catch (Throwable e) { result[0] = false; latch.countDown(); }
            });
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            return String.valueOf(result[0]);
        } catch (Throwable e) { return "false"; }
    }

    public String dialogPrompt(String title, String prefill) {
        try {
            android.app.Activity activity = mRuntime.app.getCurrentActivity();
            if (activity == null) return "error:no_activity";
            final String[] result = new String[1];
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    new com.afollestad.materialdialogs.MaterialDialog.Builder(activity)
                            .title(title)
                            .positiveText(android.R.string.ok)
                            .negativeText(android.R.string.cancel)
                            .input(prefill != null ? prefill : "", "", (d, input) -> {
                                result[0] = input.toString();
                                latch.countDown();
                            })
                            .onNegative((d, w) -> { result[0] = null; latch.countDown(); })
                            .cancelListener(d -> { result[0] = null; latch.countDown(); })
                            .show();
                } catch (Throwable e) { result[0] = null; latch.countDown(); }
            });
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            return result[0] != null ? result[0] : "";
        } catch (Throwable e) { return ""; }
    }

    public String dialogSelect(String title, String itemsJson) {
        return dialogSingleChoice(title, itemsJson, -1);
    }

    public String dialogSingleChoice(String title, String itemsJson, int index) {
        try {
            android.app.Activity activity = mRuntime.app.getCurrentActivity();
            if (activity == null) return "-1";
            final int[] result = new int[]{-1};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            JSONArray items = new JSONArray(itemsJson);
            String[] itemsArr = new String[items.length()];
            for (int i = 0; i < items.length(); i++) itemsArr[i] = items.getString(i);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    new com.afollestad.materialdialogs.MaterialDialog.Builder(activity)
                            .title(title)
                            .items(itemsArr)
                            .itemsCallbackSingleChoice(index, (dialog, itemView, which, text) -> {
                                result[0] = which;
                                latch.countDown();
                                return true;
                            })
                            .negativeText(android.R.string.cancel)
                            .onNegative((d, w) -> latch.countDown())
                            .cancelListener(d -> latch.countDown())
                            .show();
                } catch (Throwable e) { latch.countDown(); }
            });
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            return String.valueOf(result[0]);
        } catch (Throwable e) { return "-1"; }
    }

    public String dialogMultiChoice(String title, String itemsJson, String indicesJson) {
        try {
            android.app.Activity activity = mRuntime.app.getCurrentActivity();
            if (activity == null) return "[]";
            final java.util.List<Integer> result = new ArrayList<>();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            JSONArray items = new JSONArray(itemsJson);
            String[] itemsArr = new String[items.length()];
            for (int i = 0; i < items.length(); i++) itemsArr[i] = items.getString(i);
            java.util.List<Integer> selectedIndices = new ArrayList<>();
            if (indicesJson != null && !indicesJson.isEmpty()) {
                JSONArray sel = new JSONArray(indicesJson);
                for (int i = 0; i < sel.length(); i++) {
                    int selectedIndex = sel.getInt(i);
                    if (selectedIndex >= 0 && selectedIndex < itemsArr.length
                            && !selectedIndices.contains(selectedIndex)) {
                        selectedIndices.add(selectedIndex);
                    }
                }
            }
            Integer[] selected = selectedIndices.toArray(new Integer[0]);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    new com.afollestad.materialdialogs.MaterialDialog.Builder(activity)
                            .title(title)
                            .items(itemsArr)
                            .positiveText(android.R.string.ok)
                            .negativeText(android.R.string.cancel)
                            .itemsCallbackMultiChoice(selected, (dialog, which, text) -> {
                                result.clear();
                                for (int i = 0; i < which.length; i++) result.add(which[i]);
                                latch.countDown();
                                return true;
                            })
                            .onNegative((d, w) -> latch.countDown())
                            .cancelListener(d -> latch.countDown())
                            .show();
                } catch (Throwable e) { latch.countDown(); }
            });
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
            JSONArray arr = new JSONArray();
            for (int i : result) arr.put(i);
            return arr.toString();
        } catch (Throwable e) { return "[]"; }
    }

    /**
     * Unified dialogs.build() bridge. Receives a JSON property bag, builds and
     * shows a MaterialDialog on the main thread, and blocks the calling (script)
     * thread until the user acts or the dialog is dismissed.
     *
     * Supported props: title, content, positiveText, negativeText, neutralText,
     *                   inputHint, inputPrefill, inputAllowEmpty, cancelable
     *
     * Returns JSON: { "action": "positive"|"negative"|"neutral"|"cancel",
     *                 "inputText": "..." }
     */
    private static final class ScriptDialogState {
        com.afollestad.materialdialogs.MaterialDialog dialog;
        final java.util.concurrent.ConcurrentLinkedQueue<String> events = new java.util.concurrent.ConcurrentLinkedQueue<>();
        void emit(String name, Object... args) {
            try {
                JSONArray values = new JSONArray();
                for (Object arg : args) values.put(arg == null ? JSONObject.NULL : arg);
                events.add(new JSONObject().put("event", name).put("args", values).toString());
            } catch (JSONException error) { throw new IllegalStateException(error); }
        }
    }
    private final Map<Long, ScriptDialogState> mScriptDialogs = new ConcurrentHashMap<>();

    /** Build a dialog handle without showing it; JavaScript owns its listeners and lifetime. */
    public String dialogBuild(String propsJson) {
        try {
            final JSONObject props = new JSONObject(propsJson == null ? "{}" : propsJson);
            // Resolve the same app/overlay context and permission policy as the Rhino API.
            final com.afollestad.materialdialogs.MaterialDialog.Builder builder =
                    mRuntime.dialogs.newBuilder(props.optString("type", null));
            final long id = mNextDialogId.getAndIncrement();
            final ScriptDialogState state = new ScriptDialogState();
            final JSONObject self = new JSONObject().put("__dialog", true);
            return runOnMainSync(mDialogHandler, () -> {
                try {
                    if (props.has("title")) builder.title(props.optString("title"));
                    if (props.has("content")) builder.content(props.optString("content"));
                    String[] actions = { "positive", "negative", "neutral" };
                    for (String action : actions) {
                        String key = props.has(action) ? action : action + "Text";
                        if (!props.has(key)) continue;
                        String label = props.optString(key);
                        if ("positive".equals(action)) builder.positiveText(label);
                        else if ("negative".equals(action)) builder.negativeText(label);
                        else builder.neutralText(label);
                    }
                    if (props.has("titleColor")) builder.titleColor(dialogColor(props.get("titleColor")));
                    if (props.has("contentColor")) builder.contentColor(dialogColor(props.get("contentColor")));
                    if (props.has("itemsColor")) builder.itemsColor(dialogColor(props.get("itemsColor")));
                    if (props.has("positiveColor")) builder.positiveColor(dialogColor(props.get("positiveColor")));
                    if (props.has("negativeColor")) builder.negativeColor(dialogColor(props.get("negativeColor")));
                    if (props.has("neutralColor")) builder.neutralColor(dialogColor(props.get("neutralColor")));
                    if (props.has("buttonRippleColor")) builder.buttonRippleColor(dialogColor(props.get("buttonRippleColor")));
                    if (props.has("contentLineSpacing")) builder.contentLineSpacing((float) props.getDouble("contentLineSpacing"));
                    builder.autoDismiss(props.optBoolean("autoDismiss", true));
                    builder.cancelable(props.optBoolean("cancelable", true));
                    builder.canceledOnTouchOutside(props.optBoolean("canceledOnTouchOutside", true));
                    if (props.has("inputHint") || props.has("inputPrefill")) {
                        builder.input(props.optString("inputHint", ""), props.optString("inputPrefill", ""),
                                props.optBoolean("inputAllowEmpty", true),
                                (dialog, input) -> state.emit("input_change", input.toString(), self));
                        builder.alwaysCallInputCallback();
                    }
                    JSONArray items = props.optJSONArray("items");
                    if (items != null) {
                        String[] names = new String[items.length()];
                        for (int i = 0; i < names.length; i++) names[i] = items.optString(i);
                        builder.items(names);
                        String mode = props.optString("itemsSelectMode", "select");
                        if ("single".equals(mode) || "singleChoice".equals(mode)) {
                            builder.itemsCallbackSingleChoice(props.optInt("itemsSelectedIndex", -1),
                                    (dialog, view, index, label) -> { state.emit("single_choice", index, String.valueOf(label), self); return true; });
                        } else if ("multi".equals(mode) || "multiChoice".equals(mode)) {
                            JSONArray selected = props.optJSONArray("itemsSelectedIndex");
                            if (props.has("itemsSelectedIndex") && !props.isNull("itemsSelectedIndex") && selected == null)
                                throw new IllegalArgumentException("itemsSelectedIndex must be an array in multi-choice mode");
                            Integer[] indices = new Integer[selected == null ? 0 : selected.length()];
                            for (int i = 0; i < indices.length; i++) indices[i] = selected.getInt(i);
                            builder.itemsCallbackMultiChoice(indices, (dialog, indicesResult, labels) -> {
                                JSONArray texts = new JSONArray();
                                for (CharSequence label : labels) texts.put(String.valueOf(label));
                                state.emit("multi_choice", new JSONArray(Arrays.asList(indicesResult)), texts, self);
                                return true;
                            });
                        } else if ("select".equals(mode)) {
                            builder.itemsCallback((dialog, view, index, label) -> state.emit("item_select", index, String.valueOf(label), self));
                        } else throw new IllegalArgumentException("Unknown itemsSelectMode: " + mode);
                    }
                    JSONObject progress = props.optJSONObject("progress");
                    if (progress != null) {
                        int max = progress.optInt("max", -1);
                        builder.progress(max == -1, max, progress.optBoolean("showMinMax", true));
                        builder.progressIndeterminateStyle(progress.optBoolean("horizontal"));
                    }
                    if (props.has("checkBoxPrompt") || props.has("checkBoxChecked")) {
                        builder.checkBoxPrompt(props.optString("checkBoxPrompt", ""), props.optBoolean("checkBoxChecked"),
                                (button, checked) -> state.emit("check", checked, self));
                    }
                    builder.onAny((dialog, which) -> {
                        String action = which.name().toLowerCase(Locale.ROOT);
                        if (which == com.afollestad.materialdialogs.DialogAction.POSITIVE && dialog.getInputEditText() != null)
                            state.emit("input", dialog.getInputEditText().getText().toString(), self);
                        state.emit(action, self);
                        state.emit("any", action, self);
                    });
                    builder.showListener(dialog -> state.emit("show", self));
                    builder.cancelListener(dialog -> state.emit("cancel", self));
                    builder.dismissListener(dialog -> state.emit("dismiss", self));
                    state.dialog = builder.build();
                    mScriptDialogs.put(id, state);
                    return new JSONObject().put("id", id).toString();
                } catch (Throwable error) { return jsonError(describeError(error)); }
            }, jsonError("Timed out building dialog"));
        } catch (Throwable error) { return jsonError(describeError(error)); }
    }

    private static int dialogColor(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Color.parseColor(String.valueOf(value));
    }

    /** Commands and event polling on a previously built dialog. */
    public String dialogCall(String requestJson) {
        try {
            JSONObject request = new JSONObject(requestJson);
            String method = request.getString("method");
            if ("setDefaultDialogType".equals(method)) {
                mRuntime.dialogs.setDefaultDialogType(request.getJSONArray("args").getString(0)); return "null";
            }
            if ("getDefaultDialogType".equals(method)) return JSONObject.quote(mRuntime.dialogs.getDefaultDialogType());
            ScriptDialogState state = mScriptDialogs.get(request.getLong("id"));
            if (state == null) throw new IllegalStateException("Dialog handle is closed");
            if ("poll".equals(method)) {
                JSONArray events = new JSONArray(); String event;
                while ((event = state.events.poll()) != null) events.put(new JSONObject(event));
                return events.toString();
            }
            JSONArray args = request.optJSONArray("args");
            return runOnMainSync(mDialogHandler, () -> {
                try {
                    com.afollestad.materialdialogs.MaterialDialog dialog = state.dialog;
                    switch (method) {
                        case "show":
                            if (!com.stardust.app.DialogUtils.isActivityContext(dialog.getContext()) && dialog.getWindow() != null)
                                dialog.getWindow().setType(Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE);
                            dialog.show(); return "true";
                        case "dismiss": dialog.dismiss(); return "null";
                        case "cancel": dialog.cancel(); return "null";
                        case "isShowing": return String.valueOf(dialog.isShowing());
                        case "setTitle": dialog.setTitle(args.getString(0)); return "null";
                        case "setContent": dialog.setContent(args.getString(0)); return "null";
                        case "setActionButton":
                            dialog.setActionButton(com.afollestad.materialdialogs.DialogAction.valueOf(args.getString(0).toUpperCase(Locale.ROOT)), args.getString(1)); return "null";
                        case "setProgress": dialog.setProgress(args.getInt(0)); return "null";
                        case "incrementProgress": dialog.incrementProgress(args.getInt(0)); return "null";
                        case "getProgress": return String.valueOf(dialog.getCurrentProgress());
                        case "setMaxProgress": dialog.setMaxProgress(args.getInt(0)); return "null";
                        case "getMaxProgress": return String.valueOf(dialog.getMaxProgress());
                        case "getInput": return dialog.getInputEditText() == null ? "null" : JSONObject.quote(dialog.getInputEditText().getText().toString());
                        case "close": dialog.dismiss(); mScriptDialogs.remove(request.getLong("id")); return "null";
                        default: throw new IllegalArgumentException("Unsupported dialog method: " + method);
                    }
                } catch (Throwable error) { return jsonError(describeError(error)); }
            }, jsonError("Timed out updating dialog"));
        } catch (Throwable error) { return jsonError(describeError(error)); }
    }

    // ---- threads module ----

    /**
     * Execute a script in a new QuickJS engine thread with optional JSON args.
     * The argsJson is passed to the child engine as `__args` global.
     */
    public String threadsExec(String name, String source, String argsJson) {
        long handle = mNextEngineHandle.getAndIncrement();
        EngineResultState resultState = new EngineResultState();
        mEngineResults.put(handle, resultState);
        try {
            // Inject args into source: __args = JSON.parse(argsJson)
            String argsInit = "";
            if (argsJson != null && !argsJson.isEmpty() && !"null".equals(argsJson)) {
                // Escape single quotes in JSON for embedding
                String escaped = argsJson.replace("\\", "\\\\").replace("'", "\\'");
                argsInit = "var __args = JSON.parse('" + escaped + "');\n"
                        // 窗口代理在父脚本里被序列化成 {__floatyWindowId:n}，这里还原成可操作的窗口对象
                        + "if (typeof __aiReviveFloatyArgs === 'function') { __args = __aiReviveFloatyArgs(__args); }\n";
            } else {
                argsInit = "var __args = null;\n";
            }
            // The engine directive must remain the first non-empty line. Putting
            // __args before it silently routed workers back to Rhino.
            String fullSource = com.stardust.autojs.script.JavaScriptSource.QUICKJS_ENGINE_DIRECTIVE
                    + "\n" + argsInit + source;
            com.stardust.autojs.execution.ExecutionConfig config =
                    new com.stardust.autojs.execution.ExecutionConfig();
            com.stardust.autojs.execution.ScriptExecution execution =
                    mRuntime.engines.execScript(name, fullSource, config,
                            createEngineResultListener(resultState));
            mEngineSessions.put(handle, execution);
            return String.valueOf(handle);
        } catch (Throwable e) {
            resultState.fail(e);
            return "-1";
        }
    }

    public int threadsStop(long handle) {
        try {
            com.stardust.autojs.execution.ScriptExecution execution =
                    mEngineSessions.get(handle);
            if (execution == null || execution.getEngine() == null) return -1;
            execution.getEngine().forceStop();
            return 0;
        } catch (Throwable e) { return -1; }
    }

    // ---- shared event bus (cross-engine, incl. workers) ----

    public boolean sharedBusOn(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("event name is required");
        }
        if (!mSharedBusSubscriptions.add(name)) return true;
        sSharedBus.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>())
                .add(mSharedBusQueue);
        return true;
    }

    public boolean sharedBusOff(String name) {
        if (!mSharedBusSubscriptions.remove(name)) return false;
        List<ArrayBlockingQueue<String>> subscribers = sSharedBus.get(name);
        if (subscribers != null) subscribers.remove(mSharedBusQueue);
        return true;
    }

    public boolean sharedBusEmit(String name, String itemJson) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("event name is required");
        }
        List<ArrayBlockingQueue<String>> subscribers = sSharedBus.get(name);
        if (subscribers == null || subscribers.isEmpty()) return false;
        for (ArrayBlockingQueue<String> queue : subscribers) {
            if (!queue.offer(itemJson)) {
                queue.poll();
                queue.offer(itemJson);
            }
        }
        return true;
    }

    public String sharedBusPoll() {
        String item = mSharedBusQueue.poll();
        return item == null ? "" : item;
    }

    // ---- engines module ----

    public String enginesExecScript(String name, String source, String configJson) {
        long handle = mNextEngineHandle.getAndIncrement();
        EngineResultState resultState = new EngineResultState();
        mEngineResults.put(handle, resultState);
        try {
            com.stardust.autojs.execution.ExecutionConfig config = buildExecConfig(configJson);
            if (!requestsRhino(configJson)
                    && !com.stardust.autojs.script.JavaScriptSource.requestsQuickJs(source)) {
                source = com.stardust.autojs.script.JavaScriptSource.QUICKJS_ENGINE_DIRECTIVE
                        + "\n" + source;
            }
            com.stardust.autojs.execution.ScriptExecution execution =
                    mRuntime.engines.execScript(name, source, config,
                            createEngineResultListener(resultState));
            mEngineSessions.put(handle, execution);
            return String.valueOf(handle);
        } catch (Throwable e) {
            resultState.fail(e);
            return "-1";
        }
    }

    public String enginesExecScriptFile(String path, String configJson) {
        long handle = mNextEngineHandle.getAndIncrement();
        EngineResultState resultState = new EngineResultState();
        mEngineResults.put(handle, resultState);
        try {
            com.stardust.autojs.execution.ExecutionConfig config =
                    buildExecConfig(configJson, scriptParentDirectory(path));
            com.stardust.autojs.execution.ScriptExecution execution =
                    mRuntime.engines.execScriptFile(path, config,
                            createEngineResultListener(resultState));
            mEngineSessions.put(handle, execution);
            return String.valueOf(handle);
        } catch (Throwable e) {
            resultState.fail(e);
            return "-1";
        }
    }

    public String engineResult(long handle, int timeoutMillis) {
        EngineResultState state = mEngineResults.get(handle);
        if (state == null) return "{\"status\":\"missing\"}";
        if (timeoutMillis > 0 && "running".equals(state.status)) {
            try {
                state.finished.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        return state.toJson();
    }

    private com.stardust.autojs.execution.ScriptExecutionListener createEngineResultListener(
            EngineResultState state) {
        return new com.stardust.autojs.execution.SimpleScriptExecutionListener() {
            @Override
            public void onSuccess(com.stardust.autojs.execution.ScriptExecution execution,
                                  Object result) {
                state.succeed(result);
            }

            @Override
            public void onException(com.stardust.autojs.execution.ScriptExecution execution,
                                    Throwable error) {
                state.fail(error);
            }
        };
    }

    public String enginesMyEngineId() {
        try {
            com.stardust.autojs.engine.ScriptEngine engine = mRuntime.engines.myEngine();
            JSONObject info = new JSONObject();
            info.put("handle", 0);
            info.put("id", engine == null ? -1 : engine.getId());
            info.put("source", "QuickJS current engine");
            info.put("engineName", engine == null ? "QuickJsJavaScriptEngine"
                    : engine.getClass().getSimpleName());
            info.put("destroyed", engine == null || engine.isDestroyed());
            com.stardust.autojs.execution.ExecutionConfig config = engine == null ? null
                    : (com.stardust.autojs.execution.ExecutionConfig) engine.getTag(
                            com.stardust.autojs.execution.ExecutionConfig.getTag());
            info.put("execArgv", config == null ? new JSONObject() : new JSONObject(config.getArguments()));
            return info.toString();
        } catch (Throwable e) {
            return "{\"handle\":0,\"id\":-1,\"source\":\"QuickJS current engine\","
                    + "\"engineName\":\"QuickJsJavaScriptEngine\",\"destroyed\":false}";
        }
    }

    public String enginesAll() {
        JSONArray arr = new JSONArray();
        try {
            arr.put(new JSONObject(enginesMyEngineId()));
        } catch (Throwable error) {
            // 当前引擎信息序列化失败也不应抹掉下面仍在运行的子引擎。
            Log.w("QuickJsHostBridge", "Cannot describe current engine", error);
        }
        for (Map.Entry<Long, com.stardust.autojs.execution.ScriptExecution> entry
                : new ArrayList<>(mEngineSessions.entrySet())) {
            com.stardust.autojs.execution.ScriptExecution execution = entry.getValue();
            if (execution == null) continue;
            try {
                com.stardust.autojs.engine.ScriptEngine engine = execution.getEngine();
                if (engine == null) {
                    // 尚未挂上 / 已拆除的空槽位只跳过自己，不能把整个 engines.all() 变成 []。
                    continue;
                }
                if (engine.isDestroyed()) {
                    mEngineSessions.remove(entry.getKey(), execution);
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put("handle", entry.getKey());
                obj.put("id", execution.getId());
                obj.put("source", String.valueOf(execution.getSource()));
                obj.put("engineName", engine.getClass().getSimpleName());
                obj.put("destroyed", false);
                JSONObject execArgv = new JSONObject();
                Object configTag = engine.getTag(
                        com.stardust.autojs.execution.ExecutionConfig.getTag());
                if (configTag instanceof com.stardust.autojs.execution.ExecutionConfig) {
                    try {
                        execArgv = new JSONObject(
                                ((com.stardust.autojs.execution.ExecutionConfig) configTag)
                                        .getArguments());
                    } catch (RuntimeException ignored) {
                    }
                }
                obj.put("execArgv", execArgv);
                arr.put(obj);
            } catch (Throwable error) {
                // 单条目异常（例如引擎并发销毁）只跳过该条目。
                Log.w("QuickJsHostBridge", "Cannot describe engine " + entry.getKey(), error);
            }
        }
        return arr.toString();
    }

    public int enginesStopAll() {
        try {
            return mRuntime.engines.stopAll();
        } catch (Throwable e) { return 0; }
    }

    public void enginesStopAllAndToast() {
        try {
            mRuntime.engines.stopAllAndToast();
        } catch (Throwable ignored) {}
    }

    public boolean engineForceStop(long handle) {
        try {
            if (handle == 0) {
                com.stardust.autojs.engine.ScriptEngine current = mRuntime.engines.myEngine();
                if (current == null) return false;
                current.forceStop();
                return true;
            }
            com.stardust.autojs.execution.ScriptExecution execution = mEngineSessions.get(handle);
            if (execution == null || execution.getEngine() == null) return false;
            execution.getEngine().forceStop();
            return true;
        } catch (Throwable e) { return false; }
    }

    public boolean engineIsDestroyed(long handle) {
        try {
            if (handle == 0) {
                com.stardust.autojs.engine.ScriptEngine current = mRuntime.engines.myEngine();
                return current == null || current.isDestroyed();
            }
            com.stardust.autojs.execution.ScriptExecution execution = mEngineSessions.get(handle);
            if (execution == null) return true;
            com.stardust.autojs.engine.ScriptEngine engine = execution.getEngine();
            boolean destroyed = engine != null && engine.isDestroyed();
            if (destroyed) mEngineSessions.remove(handle, execution);
            return destroyed;
        } catch (Throwable e) { return true; }
    }

    private boolean requestsRhino(String configJson) {
        if (configJson == null || configJson.isEmpty()) return false;
        try {
            return "rhino".equalsIgnoreCase(new JSONObject(configJson).optString("engine"));
        } catch (JSONException ignored) {
            return false;
        }
    }

    private com.stardust.autojs.execution.ExecutionConfig buildExecConfig(String configJson) {
        return buildExecConfig(configJson, currentWorkingDirectory());
    }

    private String currentWorkingDirectory() {
        try {
            String cwd = mRuntime.files.cwd();
            return cwd == null ? "" : cwd;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** execScriptFile 未显式给 path 时，以脚本自身目录作为模块搜索路径。 */
    private String scriptParentDirectory(String path) {
        try {
            String resolved = mRuntime.files.path(path);
            String parent = resolved == null ? null : new File(resolved).getParent();
            if (parent != null && !parent.isEmpty()) return parent;
        } catch (Throwable ignored) {
        }
        return currentWorkingDirectory();
    }

    /**
     * Rhino 的 __engines__.js 会把 config.path（string 或 Array）写进
     * ExecutionConfig.path，并把第一个元素设为 workingDirectory；config.arguments
     * 则逐项写进 ExecutionConfig.arguments。QuickJS 以前只认 string path 且整段
     * 丢弃 arguments，这里按同一契约补齐（嵌套对象/数组保持 JSON 结构）。
     */
    private com.stardust.autojs.execution.ExecutionConfig buildExecConfig(
            String configJson, String fallbackWorkingDirectory) {
        com.stardust.autojs.execution.ExecutionConfig config =
                new com.stardust.autojs.execution.ExecutionConfig();
        String fallback = fallbackWorkingDirectory == null ? "" : fallbackWorkingDirectory;
        if (configJson == null || configJson.isEmpty()) {
            applyFallbackPath(config, fallback);
            return config;
        }
        try {
            JSONObject json = new JSONObject(configJson);
            if (json.has("delay")) config.setDelay(json.optLong("delay", 0));
            if (json.has("interval")) config.setInterval(json.optLong("interval", 0));
            if (json.has("loopTimes")) config.setLoopTimes(json.optInt("loopTimes", 1));
            applyArguments(config, json);
            if (!applyPath(config, json)) applyFallbackPath(config, fallback);
        } catch (JSONException ignored) {
            applyFallbackPath(config, fallback);
        }
        return config;
    }

    private void applyFallbackPath(
            com.stardust.autojs.execution.ExecutionConfig config, String fallback) {
        if (fallback == null || fallback.isEmpty()) return;
        config.setWorkingDirectory(fallback);
        config.setPath(new String[]{fallback});
    }

    /**
     * 接受 string 或 Array 两种 path。Rhino 对空/缺省的 path 回退到 files.cwd()；
     * execScriptFile 的 fallback 是脚本父目录（与 Node 引擎一致）。
     */
    private boolean applyPath(
            com.stardust.autojs.execution.ExecutionConfig config, JSONObject json) {
        Object raw = json.opt("path");
        List<String> paths = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item == null || item == JSONObject.NULL) continue;
                String value = String.valueOf(item);
                if (!value.isEmpty()) paths.add(value);
            }
        } else if (raw != null && raw != JSONObject.NULL && !(raw instanceof JSONObject)) {
            String value = String.valueOf(raw);
            if (!value.isEmpty()) paths.add(value);
        }
        if (paths.isEmpty()) return false;
        config.setPath(paths.toArray(new String[0]));
        config.setWorkingDirectory(paths.get(0));
        return true;
    }

    private void applyArguments(
            com.stardust.autojs.execution.ExecutionConfig config, JSONObject json) {
        Object raw = json.opt("arguments");
        JSONObject arguments = null;
        if (raw instanceof JSONObject) {
            arguments = (JSONObject) raw;
        } else if (raw instanceof String && !((String) raw).isEmpty()) {
            try {
                arguments = new JSONObject((String) raw);
            } catch (JSONException ignored) {
            }
        }
        if (arguments == null) return;
        java.util.Iterator<String> keys = arguments.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = arguments.opt(key);
            // Kotlin 的 setArgument(key, Any) 不接受 null；JSON null 用 JSONObject.NULL
            // 保留键，后续 new JSONObject(arguments) 仍会序列化为 null。
            config.setArgument(key, value == null ? JSONObject.NULL : value);
        }
    }

    // ---- media (audio volume / music player) ----
    private AudioManager mAudioManager;
    private MediaPlayer mMediaPlayer;

    private AudioManager audioManager() {
        if (mAudioManager == null) {
            Context context = mRuntime.uiHandler.getContext();
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    public int mediaGetVolume() {
        return audioManager().getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    public int mediaSetVolume(int volume) {
        AudioManager audio = audioManager();
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.max(0, Math.min(volume, max));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    public int mediaGetMaxVolume() {
        return audioManager().getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    public boolean mediaPlayMusic(String path, float volume, boolean looping) {
        try {
            stopMediaPlayer();
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(path);
            player.setVolume(volume, volume);
            player.setLooping(looping);
            player.prepare();
            player.start();
            mMediaPlayer = player;
            return true;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot play music: " + path, error);
            return false;
        }
    }

    private void stopMediaPlayer() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.stop();
            } catch (Throwable ignored) {
            }
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    public void mediaStopMusic() {
        stopMediaPlayer();
    }

    public void mediaPauseMusic() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    public void mediaResumeMusic() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.start();
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean mediaIsMusicPlaying() {
        return mMediaPlayer != null && mMediaPlayer.isPlaying();
    }

    public void mediaMusicSeekTo(int positionMs) {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.seekTo(Math.max(0, positionMs));
            } catch (Throwable ignored) {
            }
        }
    }

    public int mediaGetMusicDuration() {
        return mMediaPlayer != null ? mMediaPlayer.getDuration() : 0;
    }

    public int mediaGetMusicCurrentPosition() {
        return mMediaPlayer != null ? mMediaPlayer.getCurrentPosition() : 0;
    }

    public void mediaScanFile(String path) {
        try {
            Context context = mRuntime.uiHandler.getContext();
            MediaScannerConnection.scanFile(context, new String[]{path}, null, null);
        } catch (Throwable ignored) {
        }
    }

    // ---- sensors (single-sample capture, polled from JS) ----
    private SensorManager mSensorManager;
    private final java.util.concurrent.atomic.AtomicInteger mNextSensorHandle =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private final Map<Integer, SensorEventListener> mSensorListeners = new HashMap<>();
    private final Map<Integer, SensorSample> mSensorSamples = new ConcurrentHashMap<>();

    private static class SensorSample {
        volatile float[] values = new float[0];
        volatile int accuracy = 0;
        volatile long timestamp = 0;
    }

    private SensorManager sensorManager() {
        if (mSensorManager == null) {
            Context context = mRuntime.uiHandler.getContext();
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        }
        return mSensorManager;
    }

    private Sensor findSensor(String name) {
        SensorManager manager = sensorManager();
        List<Sensor> sensors = manager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor sensor : sensors) {
            if (sensor.getName().equalsIgnoreCase(name)
                    || sensor.getStringType().equalsIgnoreCase(name)
                    || String.valueOf(sensor.getType()).equals(name)) {
                return sensor;
            }
        }
        if ("accelerometer".equalsIgnoreCase(name) || "acceleration".equalsIgnoreCase(name)) {
            return manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        if ("gyroscope".equalsIgnoreCase(name) || "gyro".equalsIgnoreCase(name)) {
            return manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        if ("light".equalsIgnoreCase(name)) {
            return manager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
        if ("proximity".equalsIgnoreCase(name)) {
            return manager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }
        if ("magnetic".equalsIgnoreCase(name) || "magnetic_field".equalsIgnoreCase(name)) {
            return manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        if ("orientation".equalsIgnoreCase(name)) {
            return manager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        }
        if ("pressure".equalsIgnoreCase(name)) {
            return manager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        }
        return null;
    }

    public int sensorsRegister(String name, int delayMicros) {
        Sensor sensor = findSensor(name);
        if (sensor == null) {
            return -1;
        }
        SensorManager manager = sensorManager();
        int handle = mNextSensorHandle.getAndIncrement();
        SensorSample sample = new SensorSample();
        mSensorSamples.put(handle, sample);
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                float[] values = new float[event.values.length];
                System.arraycopy(event.values, 0, values, 0, values.length);
                sample.values = values;
                sample.accuracy = event.accuracy;
                sample.timestamp = event.timestamp;
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                sample.accuracy = accuracy;
            }
        };
        manager.registerListener(listener, sensor, Math.max(0, delayMicros),
                new Handler(Looper.getMainLooper()));
        mSensorListeners.put(handle, listener);
        return handle;
    }

    public String sensorsRead(int handle) {
        SensorSample sample = mSensorSamples.get(handle);
        if (sample == null) {
            return "";
        }
        try {
            JSONObject object = new JSONObject();
            JSONArray values = new JSONArray();
            for (float value : sample.values) {
                values.put(value);
            }
            object.put("values", values);
            object.put("accuracy", sample.accuracy);
            object.put("timestamp", sample.timestamp);
            return object.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    public boolean sensorsUnregister(int handle) {
        SensorEventListener listener = mSensorListeners.remove(handle);
        if (listener == null) {
            return false;
        }
        sensorManager().unregisterListener(listener);
        mSensorSamples.remove(handle);
        return true;
    }

    public void sensorsUnregisterAll() {
        SensorManager manager = sensorManager();
        for (SensorEventListener listener : new ArrayList<>(mSensorListeners.values())) {
            manager.unregisterListener(listener);
        }
        mSensorListeners.clear();
        mSensorSamples.clear();
    }

    public String sensorsList() {
        try {
            JSONArray array = new JSONArray();
            for (Sensor sensor : sensorManager().getSensorList(Sensor.TYPE_ALL)) {
                array.put(sensor.getName());
            }
            return array.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    // ---- dialogs (blocking dialogs driven by a JS poll loop) ----
    private static class PendingDialog {
        volatile String result = null;
    }

    private final Handler mDialogHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong mNextDialogId = new AtomicLong(1);
    private final Map<Long, PendingDialog> mDialogs = new ConcurrentHashMap<>();
    private final Map<Long, android.app.AlertDialog> pendingDialogRegistry = new ConcurrentHashMap<>();

    public long dialogsShow(int type, String title, String content,
                            String itemsJson, String extrasJson) {
        PendingDialog pending = new PendingDialog();
        final long id = mNextDialogId.getAndIncrement();
        mDialogs.put(id, pending);
        mDialogHandler.post(() -> {
            try {
                Context context = mRuntime.app.getCurrentActivity();
                if (context == null) {
                    context = mRuntime.uiHandler.getContext();
                }
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
                if (title != null && !title.isEmpty()) {
                    builder.setTitle(title);
                }
                JSONArray items = null;
                if (itemsJson != null && !itemsJson.isEmpty()) {
                    items = new JSONArray(itemsJson);
                }
                switch (type) {
                    case 0: // alert
                        builder.setMessage(content == null ? "" : content);
                        builder.setPositiveButton(android.R.string.ok,
                                (d, w) -> pending.result = "{}");
                        break;
                    case 1: // confirm
                        builder.setMessage(content == null ? "" : content);
                        builder.setPositiveButton(android.R.string.ok,
                                (d, w) -> pending.result = "true");
                        builder.setNegativeButton(android.R.string.cancel,
                                (d, w) -> pending.result = "false");
                        break;
                    case 2: { // rawInput (content = prefill)
                        android.widget.EditText input = new android.widget.EditText(context);
                        if (content != null) {
                            input.setText(content);
                        }
                        builder.setView(input);
                        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
                            String text = input.getText() == null ? "" : input.getText().toString();
                            try {
                                pending.result = new JSONObject().put("value", text).toString();
                            } catch (JSONException ignored) {
                                pending.result = "{}";
                            }
                        });
                        builder.setNegativeButton(android.R.string.cancel,
                                (d, w) -> pending.result = "null");
                        break;
                    }
                    case 3: { // select (single-choice list), extras = default index
                        if (items == null) {
                            pending.result = "-1";
                            break;
                        }
                        final String[] names = new String[items.length()];
                        for (int i = 0; i < items.length(); i++) {
                            names[i] = items.optString(i);
                        }
                        builder.setItems(names, (d, which) -> {
                            try {
                                pending.result = new JSONObject().put("index", which).toString();
                            } catch (JSONException ignored) {
                                pending.result = "{}";
                            }
                        });
                        builder.setNegativeButton(android.R.string.cancel,
                                (d, w) -> pending.result = "-1");
                        break;
                    }
                    case 4: { // singleChoice radio, extras = default index
                        if (items == null) {
                            pending.result = "-1";
                            break;
                        }
                        final String[] names = new String[items.length()];
                        for (int i = 0; i < items.length(); i++) {
                            names[i] = items.optString(i);
                        }
                        final int[] selected = {0};
                        try {
                            selected[0] = extrasJson == null ? 0 : Integer.parseInt(extrasJson);
                        } catch (NumberFormatException ignored) {
                        }
                        builder.setSingleChoiceItems(names, selected[0], (d, which) -> selected[0] = which);
                        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
                            try {
                                pending.result = new JSONObject().put("index", selected[0]).toString();
                            } catch (JSONException ignored) {
                                pending.result = "{}";
                            }
                        });
                        builder.setNegativeButton(android.R.string.cancel,
                                (d, w) -> pending.result = "-1");
                        break;
                    }
                    case 5: { // multiChoice, extras = default indices JSON
                        if (items == null) {
                            pending.result = "[]";
                            break;
                        }
                        final String[] names = new String[items.length()];
                        for (int i = 0; i < items.length(); i++) {
                            names[i] = items.optString(i);
                        }
                        final boolean[] checked = new boolean[names.length];
                        if (extrasJson != null && !extrasJson.isEmpty()) {
                            try {
                                JSONArray def = new JSONArray(extrasJson);
                                for (int i = 0; i < def.length(); i++) {
                                    int idx = def.optInt(i);
                                    if (idx >= 0 && idx < checked.length) {
                                        checked[idx] = true;
                                    }
                                }
                            } catch (JSONException ignored) {
                            }
                        }
                        builder.setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked);
                        builder.setPositiveButton(android.R.string.ok, (d, w) -> {
                            try {
                                JSONArray arr = new JSONArray();
                                for (int i = 0; i < checked.length; i++) {
                                    if (checked[i]) {
                                        arr.put(i);
                                    }
                                }
                                pending.result = new JSONObject().put("indices", arr).toString();
                            } catch (JSONException ignored) {
                                pending.result = "[]";
                            }
                        });
                        builder.setNegativeButton(android.R.string.cancel,
                                (d, w) -> pending.result = "[]");
                        break;
                    }
                    default:
                        pending.result = "{}";
                        break;
                }
                android.app.AlertDialog dialog = builder.create();
                dialog.setCanceledOnTouchOutside(false);
                dialog.setOnCancelListener(d -> {
                    if (pending.result == null) {
                        pending.result = "null";
                    }
                });
                dialog.show();
                pendingDialogRegistry.put(id, dialog);
            } catch (Throwable error) {
                pending.result = "-1";
                Log.w("QuickJsHostBridge", "Cannot show dialog", error);
            }
        });
        return id;
    }

    public String dialogsPoll(long id) {
        PendingDialog pending = mDialogs.get(id);
        if (pending == null) {
            return "";
        }
        String result = pending.result;
        if (result == null) {
            return "";
        }
        mDialogs.remove(id);
        android.app.AlertDialog dialog = pendingDialogRegistry.remove(id);
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    // ---- floaty (minimal overlay windows: text + drag + geometry) ----
    // 窗口注册表是**进程级**的：worker 线程（独立 QuickJS 引擎）可以用 floaty.windowById(id)
    // 操作主脚本创建的窗口，不再限于创建它的那个引擎。
    private static final java.util.concurrent.atomic.AtomicInteger mNextFloatyId =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private static final Map<Integer, QuickJsFloatyWindow> mFloatyWindows = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> mFloatyEvents =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    interface FloatyEventSink {
        void emit(String eventJson);
    }

    /** 窗口是否存在（跨引擎按 id 查询）。 */
    public boolean floatyWindowExists(int windowId) {
        return mFloatyWindows.containsKey(windowId);
    }

    /** floaty.closeAll()：用户显式关闭所有窗口（跨引擎）。 */
    public void floatyCloseAllGlobal() {
        for (QuickJsFloatyWindow window : new ArrayList<>(mFloatyWindows.values())) {
            window.close();
            window.unregisterOverlay();
        }
        mFloatyWindows.clear();
    }

    /** 引擎销毁时只关属于本引擎的窗口（不能误伤其它引擎创建的窗口）。 */
    private void closeOwnedFloatyWindows() {
        for (QuickJsFloatyWindow window : new ArrayList<>(mFloatyWindows.values())) {
            if (window.isOwnedBy(mEngineHandle)) {
                window.close();
                window.unregisterOverlay();
                mFloatyWindows.remove(window.id());
            }
        }
        mFloatyEvents.clear();
    }

    /**
     * PDF §2.14：{@code floaty.checkPermission()} —— 当前应用是否有悬浮窗权限（不触发请求）。
     *
     * <p>直接复用 Rhino 那份实现（{@code ScriptRuntime.floaty} → {@code FloatingPermission}），
     * 两个引擎语义一致。以前 QuickJS 的 floaty 漏了这两个方法，悬浮窗脚本开头的
     * {@code if (!floaty.checkPermission()) …} 会报 "TypeError: not a function"。
     */
    public boolean floatyCheckPermission() {
        return mRuntime.floaty.checkPermission();
    }

    /** PDF §2.14：{@code floaty.requestPermission()} —— 跳到系统悬浮窗权限界面（不阻塞、不等待）。 */
    public void floatyRequestPermission() {
        mRuntime.floaty.requestPermission();
    }

    public String floatyCreate(String configJson) {
        try {
            JSONObject config = new JSONObject(configJson);
            int id = mNextFloatyId.getAndIncrement();
            Context context = mRuntime.uiHandler.getContext();
            QuickJsFloatyWindow window = new QuickJsFloatyWindow(
                    context, id, config, uiInflater(), mRuntime.ui.getResourceParser(),
                    mFloatyEvents::add, mEngineHandle, mOwnerEngineId, overlayOwnerTag());
            mFloatyWindows.put(id, window);
            if (config.optBoolean("visible", true)) {
                if (!window.show()) {
                    mFloatyWindows.remove(id);
                    return "-1";
                }
            } else {
                // visible:false：先把布局和窗口参数准备好但不挂载，
                // 既消掉“出生在 (0,0)”的闪现，又让 window.<id> 在显示前就可访问。
                window.prepare();
            }
            return String.valueOf(id);
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot create floaty window", error);
            return "-1";
        }
    }

    public void floatyUpdate(int id, String configJson) {
        QuickJsFloatyWindow window = mFloatyWindows.get(id);
        if (window != null) {
            window.update(configJson);
        }
    }

    public void floatyClose(int id) {
        QuickJsFloatyWindow window = mFloatyWindows.remove(id);
        if (window != null) {
            window.close();
        }
    }

    public void floatyCloseAll() {
        floatyCloseAllGlobal();
    }

    public String floatyViewGetText(int windowId, String id) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? "" : window.getViewText(id);
    }

    public void floatyViewSetText(int windowId, String id, String text) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.setViewText(id, text);
    }

    public void floatyViewSetVisibility(int windowId, String id, String visibility) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.setViewVisibility(id, visibility);
    }

    public void floatyViewClick(int windowId, String id, String mode) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.registerViewClick(id, mode);
    }

    public String floatyViewPoll() {
        String event = mFloatyEvents.poll();
        return event == null ? "" : event;
    }

    public void floatySetAdjustable(int windowId, boolean enabled) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.setAdjustable(enabled);
    }

    /** 只让指定控件承担原生拖动，避免逐帧触摸事件经过脚本线程。 */
    public void floatySetDragHandle(int windowId, String viewId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.setDragHandle(viewId);
    }

    public boolean floatyIsAdjustable(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window != null && window.isAdjustable();
    }

    public int floatyGetX(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 0 : window.getWindowX();
    }

    public int floatyGetY(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 0 : window.getWindowY();
    }

    /** 窗口（整个悬浮窗）显隐：0 = 显示，其它（4/8）= 隐藏。原子生效，返回操作后是否可见。 */
    public boolean floatySetVisibility(int windowId, int visibility) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window == null) {
            return false;
        }
        boolean show = visibility == android.view.View.VISIBLE;
        return show ? window.show() : window.hide();
    }

    public boolean floatyIsVisible(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window != null && window.isShownNow();
    }

    /** 只切根 View 可见性（最快路径，不涉及 WindowManager）；返回后调用方无需等待。 */
    public void floatySetContentVisibility(int windowId, int visibility) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) {
            window.setContentVisibility(visibility);
        }
    }

    public int floatyGetContentVisibility(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 0 : window.getContentVisibility();
    }

    /** 真实 `android.view.View` 句柄：脚本可把它直接交给系统动画 API（ObjectAnimator 等）。 */
    public long floatyViewHandle(int windowId, String id) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        android.view.View view = window == null ? null : window.getView(id);
        return view == null ? 0 : mJavaInterop.putForScript(view);
    }

    com.stardust.autojs.core.graphics.ScriptCanvasView findCanvasView(int windowId, String id) {
        View view = runOnMainSync(mDialogHandler, () -> {
            QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
            return windowId == 0 ? uiFind(id) : window == null ? null : window.getView(id);
        }, null);
        if (!(view instanceof com.stardust.autojs.core.graphics.ScriptCanvasView)) {
            throw new IllegalArgumentException("draw 事件需要有效的 canvas 控件：" + id);
        }
        return (com.stardust.autojs.core.graphics.ScriptCanvasView) view;
    }

    private static void closeCanvasViews(View view) {
        if (view instanceof com.stardust.autojs.core.graphics.ScriptCanvasView) {
            ((com.stardust.autojs.core.graphics.ScriptCanvasView) view).closeQuickJsCanvas();
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) closeCanvasViews(group.getChildAt(i));
        }
    }

    /** 窗口根 View 的句柄（整个悬浮窗的动画目标）。 */
    public long floatyRootViewHandle(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 0 : mJavaInterop.putForScript(window.getRootView());
    }

    public void floatySetAlpha(int windowId, float alpha) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) {
            window.setWindowAlpha(alpha);
        }
    }

    public void floatySetScale(int windowId, float scaleX, float scaleY) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) {
            window.setWindowScale(scaleX, scaleY);
        }
    }

    public float floatyGetAlpha(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 1f : window.getWindowAlpha();
    }

    /** 主线程 flush 后读真实坐标（setPosition 后立即读时仍可能还是旧值，故单独提供）。 */
    public int floatyGetRealX(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 0 : window.getRealWindowX();
    }

    public int floatyGetRealY(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 0 : window.getRealWindowY();
    }

    public void floatyViewTouch(int windowId, String id) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.registerViewTouch(id);
    }

    public void floatyViewKey(int windowId, String id) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.registerViewKey(id);
    }

    public void floatySetWindowFocusable(int windowId, boolean focusable) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.setWindowFocusable(focusable);
    }

    public void floatyViewRequestFocus(int windowId, String id) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        if (window != null) window.requestViewFocus(id);
    }

    /** 控件级轮廓裁剪（setClipToOutline / setOutlineShape / setCornerRadius 用）。 */
    public boolean floatyViewShape(int windowId, String id, String configJson) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window != null && window.setViewShape(id, configJson);
    }

    /** 窗口圆形触摸区域能力探测（隐藏 API WindowManager.LayoutParams#setTouchableRegion）。 */
    public String floatyTouchRegionInfo() {
        return QuickJsFloatyWindow.touchRegionInfo();
    }

    /** 当前窗口的触摸区域快照。 */
    public String floatyGetTouchRegion(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? "{\"supported\":false,\"shape\":\"none\"}" : window.getTouchRegionInfo();
    }

    private static final class QuickJsFloatyWindow {
        private final Context mContext;
        private final int mId;
        private final long mOwnerEngine;
        private final int mOwnerEngineId;
        private final String mOwnerTag;
        private final WindowManager mWindowManager;
        private final android.widget.FrameLayout mRoot;
        private final com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater mInflater;
        private final com.stardust.autojs.core.ui.inflater.ResourceParser mResourceParser;
        private final org.json.JSONObject mConfig;
        private android.widget.TextView mTextView;
        private android.view.View mContent;
        private android.graphics.drawable.Drawable mBackground;
        private WindowManager.LayoutParams mParams;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final FloatyEventSink mEventSink;
        private volatile boolean mShown;
        private boolean mPrepared;
        private float mAlpha = 1f;
        private float mScaleX = 1f;
        private float mScaleY = 1f;
        /** setSize/创建配置显式指定的尺寸（超过 0 时 getWidth/getHeight 直接返回它，不等重排）。 */
        private volatile int mRequestedWidth;
        private volatile int mRequestedHeight;
        private volatile int mContentVisibility = android.view.View.VISIBLE;
        /** 是否能接收触摸（false = FLAG_NOT_TOUCHABLE，触摸穿透到下层，与 Auto.js 语义一致）。 */
        private volatile boolean mTouchable;
        /** 是否可按住拖动窗口（setAdjustEnabled(true) 会打开）。 */
        private volatile boolean mDraggable;
        private volatile boolean mAdjustable;
        private float mTouchStartX;
        private float mTouchStartY;
        private int mStartX;
        private int mStartY;
        // ---- 圆形触摸穿透（·轮廓裁剪 + 窗口触摸区域）----
        /** 窗口轮廓：null（未设置）| oval | roundRect | rect。 */
        private volatile String mOutlineShape;
        private volatile float mOutlineRadius = -1f;
        private volatile boolean mClipToOutline;
        /** null=自动跟随轮廓，false=整窗矩形，true=按形状收缩触摸区域。 */
        private volatile Boolean mTouchableOnlyInShape;
        /** circle（圆外穿透）| rect。 */
        private volatile String mTouchShape;
        private volatile int mTouchCenterX = Integer.MIN_VALUE;
        private volatile int mTouchCenterY = Integer.MIN_VALUE;
        private volatile int mTouchRadius = -1;
        /** 最近一次生效的触摸区域 [cx, cy, r]，未生效时为 null。 */
        private volatile int[] mTouchRegion;

        QuickJsFloatyWindow(Context context, int id, JSONObject config,
                            com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater inflater,
                            com.stardust.autojs.core.ui.inflater.ResourceParser resourceParser,
                            FloatyEventSink eventSink, long ownerEngine,
                            int ownerEngineId, String ownerTag) {
            mContext = context;
            mId = id;
            mOwnerEngine = ownerEngine;
            mOwnerEngineId = ownerEngineId;
            mOwnerTag = ownerTag;
            mConfig = config;
            mInflater = inflater;
            mResourceParser = resourceParser;
            mEventSink = eventSink;
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mRoot = new android.widget.FrameLayout(context);
            // 尺寸变化（wrap_content 量完 / setSize 后）需要重算圆形轮廓与触摸区域。
            mRoot.addOnLayoutChangeListener((view, left, top, right, bottom,
                                             oldLeft, oldTop, oldRight, oldBottom) -> {
                if (mOutlineShape != null || mTouchableOnlyInShape != null
                        || mTouchShape != null || mRoot.getClipToOutline()) {
                    mHandler.post(() -> {
                        applyOutlineNow();
                        applyTouchRegionNow();
                    });
                }
            });
            // 默认可接收触摸；穿透用 setTouchable(false)，拖动单独用 draggable/setAdjustEnabled。
            mTouchable = config.optBoolean("touchable", true);
            mDraggable = config.optBoolean("draggable", false);
            mRoot.setOnTouchListener((view, event) -> {
                if (!mDraggable) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        mTouchStartX = event.getRawX();
                        mTouchStartY = event.getRawY();
                        mStartX = mParams.x;
                        mStartY = mParams.y;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        mParams.x = mStartX + (int) (event.getRawX() - mTouchStartX);
                        mParams.y = mStartY + (int) (event.getRawY() - mTouchStartY);
                        try {
                            mWindowManager.updateViewLayout(mRoot, mParams);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    default:
                        return true;
                }
            });
        }

        /** 在 main 线程执行：构建内容与窗口参数（XML 布局或文本）。 */
        private void applyConfig() {
            JSONObject c = mConfig;
            if (c.has("xml")) {
                // 带 parent(不挂载) 以生成 LayoutParams，支持 root 的 w/h/padding 等属性
                mContent = mInflater.inflate(c.optString("xml"), mRoot, false);
            }
            mTextView = new android.widget.TextView(mContext);
            mTextView.setText(c.optString("text", ""));
            if (c.has("textSize")) {
                // 兼容 textSize: 14（数字=SP）与 textSize: "22sp"/"12dp"/"100px"（字符串走尺寸解析）；
                // 非法值忽略并打中文提示，不让浮窗创建因 NumberFormatException 失败。
                Object rawTextSize = c.opt("textSize");
                float textSizePx = Float.NaN;
                if (rawTextSize instanceof Number) {
                    textSizePx = android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_SP,
                            ((Number) rawTextSize).floatValue(), displayMetrics(mContext));
                } else if (rawTextSize != null) {
                    textSizePx = parseUiDimensionPx(String.valueOf(rawTextSize),
                            android.util.TypedValue.COMPLEX_UNIT_SP, mContext, "textSize");
                }
                if (!Float.isNaN(textSizePx)) {
                    mTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx);
                }
            }
            if (c.has("textColor")) {
                try {
                    mTextView.setTextColor(android.graphics.Color.parseColor(c.optString("textColor")));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (c.has("backgroundColor")) {
                try {
                    mBackground = new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.parseColor(c.optString("backgroundColor")));
                } catch (IllegalArgumentException ignored) {
                    mBackground = null;
                }
            }
            mRoot.removeAllViews();
            mRoot.setBackground(mBackground);
            if (mContent != null) {
                // XML 根控件自带 w/h（且窗口是 wrap_content）时按内容尺寸排布：
                // 否则 MATCH_PARENT 会把它量成 0×0（圆形触摸穿透需求的 card 根就有这个问题）。
                android.view.ViewGroup.LayoutParams contentParams = mContent.getLayoutParams();
                android.widget.FrameLayout.LayoutParams frameParams =
                        new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
                if (contentParams != null) {
                    // 窗口是 wrap_content 的维度才按内容自带尺寸，否则内容填满窗口。
                    frameParams.width = c.optInt("width", Integer.MIN_VALUE) <= 0
                            && contentParams.width > 0
                            ? contentParams.width
                            : android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
                    frameParams.height = c.optInt("height", Integer.MIN_VALUE) <= 0
                            && contentParams.height > 0
                            ? contentParams.height
                            : android.widget.FrameLayout.LayoutParams.MATCH_PARENT;
                    if (contentParams instanceof android.widget.FrameLayout.LayoutParams) {
                        frameParams.gravity =
                                ((android.widget.FrameLayout.LayoutParams) contentParams).gravity;
                    }
                    if (contentParams instanceof android.view.ViewGroup.MarginLayoutParams) {
                        android.view.ViewGroup.MarginLayoutParams margins =
                                (android.view.ViewGroup.MarginLayoutParams) contentParams;
                        frameParams.setMargins(margins.leftMargin, margins.topMargin,
                                margins.rightMargin, margins.bottomMargin);
                    }
                }
                mRoot.addView(mContent, frameParams);
            } else {
                mRoot.addView(mTextView, new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.CENTER));
            }
            int width = c.optInt("width", android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            int height = c.optInt("height", android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            mParams = new WindowManager.LayoutParams(
                    width < 0 ? WindowManager.LayoutParams.WRAP_CONTENT : width,
                    height < 0 ? WindowManager.LayoutParams.WRAP_CONTENT : height,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT);
            mParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            mParams.x = c.optInt("x", 0);
            mParams.y = c.optInt("y", 0);
            if (c.optInt("width", 0) > 0) {
                mRequestedWidth = c.optInt("width", 0);
            }
            if (c.optInt("height", 0) > 0) {
                mRequestedHeight = c.optInt("height", 0);
            }
            applyTouchableFlag();
            if (readShapeConfig(c)) {
                applyOutlineNow();
                applyTouchRegionNow();
            }
        }

        /** touchable=false 时窗口不接收触摸，事件穿透到下层（·P3-1 需求）。 */
        private void applyTouchableFlag() {
            if (mTouchable) {
                mParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                mParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
        }

        // ---- 圆形触摸穿透：轮廓裁剪 + 窗口触摸区域收缩（·需求文档 P0/P1）----

        /** 读取形状相关配置到字段（不碰视图，可在任意线程调用）；返回是否含形状/触摸区域配置。 */
        private boolean readShapeConfig(JSONObject c) {
            boolean regionChanged = false;
            if (c.has("clipToOutline")) {
                mClipToOutline = c.optBoolean("clipToOutline", mClipToOutline);
            }
            if (c.has("outlineShape")) {
                mOutlineShape = c.optString("outlineShape", null);
                mClipToOutline = !"rect".equals(mOutlineShape);
            }
            if (c.has("outlineRadius")) {
                mOutlineRadius = (float) c.optDouble("outlineRadius", mOutlineRadius);
            }
            if (c.has("touchShape")) {
                mTouchShape = c.optString("touchShape", null);
                regionChanged = true;
            }
            if (c.has("touchCenterX")) {
                mTouchCenterX = c.optInt("touchCenterX", Integer.MIN_VALUE);
                regionChanged = true;
            }
            if (c.has("touchCenterY")) {
                mTouchCenterY = c.optInt("touchCenterY", Integer.MIN_VALUE);
                regionChanged = true;
            }
            if (c.has("touchRadius")) {
                mTouchRadius = c.optInt("touchRadius", -1);
                regionChanged = true;
            }
            if (c.has("touchableOnlyInShape")) {
                mTouchableOnlyInShape = c.optBoolean("touchableOnlyInShape", true);
                regionChanged = true;
            }
            return regionChanged;
        }

        /** 生成轮廓提供者：oval 用椭圆（正方形即圆），roundRect 用圆角矩形。 */
        private static android.view.ViewOutlineProvider outlineProviderFor(final String shape,
                                                                          final float radius) {
            return new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w <= 0 || h <= 0) {
                        return;
                    }
                    if ("oval".equals(shape)) {
                        outline.setOval(0, 0, w, h);
                        return;
                    }
                    float r = radius > 0 ? radius : Math.min(w, h) / 2f;
                    outline.setRoundRect(0, 0, w, h, r);
                }
            };
        }

        /** 应用窗口级轮廓裁剪（主线程）。 */
        private void applyOutlineNow() {
            try {
                if (mOutlineShape == null) {
                    if (mClipToOutline) {
                        mRoot.setClipToOutline(true);
                    }
                    return;
                }
                if ("rect".equals(mOutlineShape)) {
                    mRoot.setClipToOutline(false);
                    mRoot.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                } else {
                    mRoot.setOutlineProvider(outlineProviderFor(mOutlineShape, mOutlineRadius));
                    mRoot.setClipToOutline(mClipToOutline);
                }
                mRoot.invalidateOutline();
                mRoot.invalidate();
            } catch (Throwable error) {
                Log.w("QuickJsFloatyWindow", "applyOutline failed", error);
            }
        }

        /** 控件级轮廓裁剪（主线程）：CardView 走 cardCornerRadius，其余用自定义轮廓。 */
        boolean setViewShape(String id, String configJson) {
            JSONObject c;
            try {
                c = new JSONObject(configJson == null ? "{}" : configJson);
            } catch (Throwable error) {
                return false;
            }
            return onMain(() -> {
                View view = findViewById(id);
                if (view == null) {
                    return Boolean.FALSE;
                }
                try {
                    String shape = c.optString("outlineShape", "");
                    float radius = (float) c.optDouble("outlineRadius", -1);
                    boolean clip = c.optBoolean("clipToOutline", true);
                    if ("rect".equals(shape)) {
                        view.setClipToOutline(false);
                    } else if (view instanceof androidx.cardview.widget.CardView) {
                        // CardView 自带圆角背景轮廓，只补裁剪（圆角半径由 cardCornerRadius 控制）。
                        if (radius > 0) {
                            ((androidx.cardview.widget.CardView) view).setRadius(radius);
                        } else if ("oval".equals(shape)) {
                            int side = Math.min(view.getWidth(), view.getHeight());
                            if (side > 0) {
                                ((androidx.cardview.widget.CardView) view).setRadius(side / 2f);
                            }
                        }
                        view.setClipToOutline(clip);
                    } else {
                        if (!shape.isEmpty()) {
                            view.setOutlineProvider(outlineProviderFor(shape, radius));
                        }
                        view.setClipToOutline(clip);
                    }
                    view.invalidateOutline();
                    view.invalidate();
                    return Boolean.TRUE;
                } catch (Throwable error) {
                    Log.w("QuickJsFloatyWindow", "setViewShape failed", error);
                    return Boolean.FALSE;
                }
            }, Boolean.FALSE);
        }

        /**
         * 触摸区域反射策略：不同 Android 版本上隐藏 API 的形态不一样，逐个探测。
         * 1. {@code WindowManager.LayoutParams#setTouchableRegion(Region)}（Android 12+ 隐藏方法）
         * 2. 直接写隐藏字段 {@code mTouchableRegion}（+ mSetTouchableRegion 标记位）
         */
        private static final class TouchRegionApi {
            final String name;
            private final Method mMethod;
            private final java.lang.reflect.Field mRegionField;
            private final java.lang.reflect.Field mFlagField;

            TouchRegionApi(String name, Method method, java.lang.reflect.Field regionField,
                           java.lang.reflect.Field flagField) {
                this.name = name;
                mMethod = method;
                mRegionField = regionField;
                mFlagField = flagField;
            }

            void apply(WindowManager.LayoutParams params, android.graphics.Region region)
                    throws Throwable {
                if (mMethod != null) {
                    mMethod.invoke(params, region);
                }
                if (mRegionField != null) {
                    mRegionField.set(params, region);
                }
                if (mFlagField != null) {
                    mFlagField.setBoolean(params, region != null);
                }
            }
        }

        private static Boolean sTouchApiProbed;
        private static TouchRegionApi sTouchApi;

        private static java.lang.reflect.Field findLayoutParamsField(String name) {
            try {
                java.lang.reflect.Field field =
                        WindowManager.LayoutParams.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static synchronized TouchRegionApi touchRegionApi() {
            if (sTouchApiProbed == null) {
                sTouchApiProbed = Boolean.TRUE;
                sTouchApi = null;
                try {
                    Method method = WindowManager.LayoutParams.class.getDeclaredMethod(
                            "setTouchableRegion", android.graphics.Region.class);
                    method.setAccessible(true);
                    sTouchApi = new TouchRegionApi("LayoutParams#setTouchableRegion", method,
                            findLayoutParamsField("mTouchableRegion"),
                            findLayoutParamsField("mSetTouchableRegion"));
                } catch (Throwable error) {
                    Log.d("QuickJsFloatyWindow", "no setTouchableRegion method: " + error);
                }
                if (sTouchApi == null) {
                    java.lang.reflect.Field field = findLayoutParamsField("mTouchableRegion");
                    if (field != null) {
                        sTouchApi = new TouchRegionApi("LayoutParams.mTouchableRegion", null, field,
                                findLayoutParamsField("mSetTouchableRegion"));
                    }
                }
                Log.i("QuickJsFloatyWindow", "touch region api = "
                        + (sTouchApi == null ? "none" : sTouchApi.name));
            }
            return sTouchApi;
        }

        static String touchRegionInfo() {
            TouchRegionApi api = touchRegionApi();
            JSONObject json = new JSONObject();
            try {
                json.put("supported", api != null);
                json.put("api", api == null ? "" : api.name);
                json.put("android", Build.VERSION.SDK_INT);
                json.put("hint", "隐藏 API 尽力而为：supported=true 时圆外穿透由系统输入分发决定");
            } catch (Throwable ignored) {
            }
            return json.toString();
        }
        String getTouchRegionInfo() {
            JSONObject json = new JSONObject();
            int[] region = mTouchRegion;
            try {
                TouchRegionApi api = touchRegionApi();
                json.put("supported", api != null);
                json.put("api", api == null ? "" : api.name);
                json.put("shape", region == null ? "rect" : "circle");
                json.put("cx", region == null ? -1 : region[0]);
                json.put("cy", region == null ? -1 : region[1]);
                json.put("r", region == null ? -1 : region[2]);
                json.put("touchable", mTouchable);
            } catch (Throwable ignored) {
            }
            return json.toString();
        }

        private int windowWidthNow() {
            if (mParams != null && mParams.width > 0) {
                return mParams.width;
            }
            int measured = mRoot.getWidth();
            return measured > 0 ? measured : mRequestedWidth;
        }

        private int windowHeightNow() {
            if (mParams != null && mParams.height > 0) {
                return mParams.height;
            }
            int measured = mRoot.getHeight();
            return measured > 0 ? measured : mRequestedHeight;
        }

        /**
         * 视图的“接近圆形”轮廓 → 窗口内坐标圆心/半径；不是圆（如普通圆角矩形）返回 null，
         * 避免把整块面板的触摸区域误缩成圆。
         */
        private static int[] circleRegionOf(View view) {
            if (view == null || !view.getClipToOutline()) {
                return null;
            }
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            try {
                android.graphics.Outline outline = new android.graphics.Outline();
                android.view.ViewOutlineProvider provider = view.getOutlineProvider();
                if (provider == null) {
                    return null;
                }
                provider.getOutline(view, outline);
                if (outline.isEmpty()) {
                    return null;
                }
                android.graphics.Rect rect = new android.graphics.Rect();
                if (!outline.getRect(rect)) {
                    return null;
                }
                int rw = Math.max(1, rect.width());
                int rh = Math.max(1, rect.height());
                float radius = outline.getRadius();
                if (radius < Math.min(rw, rh) / 2f - 1.5f) {
                    return null;
                }
                int cx = view.getLeft() + rect.left + rw / 2;
                int cy = view.getTop() + rect.top + rh / 2;
                return new int[]{cx, cy, Math.min(rw, rh) / 2};
            } catch (Throwable error) {
                return null;
            }
        }

        /** 当前应生效的圆形触摸区域；null = 整窗矩形。 */
        private int[] effectiveTouchRegionNow() {
            if (mTouchableOnlyInShape != null && !mTouchableOnlyInShape) {
                return null;
            }
            if ("rect".equals(mTouchShape)) {
                return null;
            }
            if (mTouchShape != null || mTouchableOnlyInShape != null) {
                int w = windowWidthNow();
                int h = windowHeightNow();
                if (w <= 0 || h <= 0) {
                    return null;
                }
                int cx = mTouchCenterX != Integer.MIN_VALUE ? mTouchCenterX : w / 2;
                int cy = mTouchCenterY != Integer.MIN_VALUE ? mTouchCenterY : h / 2;
                int r = mTouchRadius > 0
                        ? mTouchRadius
                        : Math.min(Math.min(cx, w - cx), Math.min(cy, h - cy));
                return r > 0 ? new int[]{cx, cy, r} : null;
            }
            // 自动跟随：窗口根或 XML 根控件的圆形轮廓（clipToOutline="true" + 圆角=半边长）。
            int[] fromRoot = circleRegionOf(mRoot);
            if (fromRoot != null) {
                return fromRoot;
            }
            return circleRegionOf(mContent);
        }

        /** 把触摸区域同步到窗口（主线程）；返回是否成功。 */
        private boolean applyTouchRegionNow() {
            if (mParams == null) {
                return false;
            }
            TouchRegionApi api = touchRegionApi();
            if (api == null) {
                return false;
            }
            int[] region = effectiveTouchRegionNow();
            try {
                if (region == null) {
                    api.apply(mParams, null);
                    mTouchRegion = null;
                } else {
                    int w = Math.max(1, windowWidthNow());
                    int h = Math.max(1, windowHeightNow());
                    android.graphics.Region pathRegion = new android.graphics.Region();
                    Path path = new Path();
                    path.addCircle(region[0], region[1], region[2], Path.Direction.CW);
                    pathRegion.setPath(path, new android.graphics.Region(0, 0, w, h));
                    api.apply(mParams, pathRegion);
                    mTouchRegion = region;
                }
                if (mShown) {
                    mWindowManager.updateViewLayout(mRoot, mParams);
                }
                return true;
            } catch (Throwable error) {
                Log.w("QuickJsFloatyWindow", "applyTouchRegion failed", error);
                return false;
            }
        }

        boolean show() {
            return onMain(this::showNow, Boolean.FALSE);
        }

        /** visible:false 创建时调用：先在主线程构建内容与参数，但不上屏。 */
        void prepare() {
            onMain(() -> {
                prepareNow();
                return Boolean.TRUE;
            }, Boolean.FALSE);
        }

        /** 隐藏整个窗口（与 show 一样是同步的，返回后即已生效）。 */
        boolean hide() {
            return onMain(this::hideNow, Boolean.FALSE);
        }

        boolean isShownNow() {
            return mShown;
        }

        private void prepareNow() {
            if (mPrepared) {
                return;
            }
            applyConfig();
            mPrepared = true;
        }

        private boolean showNow() {
            try {
                if (mShown) {
                    return true;
                }
                prepareNow();
                mWindowManager.addView(mRoot, mParams);
                mShown = true;
                OverlayRegistry.register(mOwnerEngineId, mOwnerTag, "quickjs",
                        OverlayRegistry.Kind.QUICKJS_FLOATY, mRoot, mWindowManager, mTouchable);
                mRoot.setVisibility(mContentVisibility);
                applyWindowTransform();
                emitLifecycle("attached");
                Log.i("QuickJsFloatyWindow", "Floaty window " + mId + " shown");
                return true;
            } catch (Throwable error) {
                Log.e("QuickJsFloatyWindow", "addView failed", error);
                mShown = false;
                return false;
            }
        }

        private boolean hideNow() {
            if (!mShown) {
                return true;
            }
            try {
                mWindowManager.removeViewImmediate(mRoot);
            } catch (Throwable error) {
                Log.w("QuickJsFloatyWindow", "removeView failed", error);
            }
            mShown = false;
            OverlayRegistry.unregister(mRoot);
            emitLifecycle("detached");
            return true;
        }

        /** 窗口挂载/卸载事件（脚本侧 win.on('attached'/'detached')）。 */
        private void emitLifecycle(String event) {
            if (mEventSink == null) {
                return;
            }
            try {
                mEventSink.emit(new JSONObject()
                        .put("window", mId).put("id", "").put("event", event).toString());
            } catch (JSONException ignored) {
            }
        }

        /** 窗口级 alpha / scale（直接作用于根 View，不重排布局）。 */
        private void applyWindowTransform() {
            mRoot.setAlpha(mAlpha);
            mRoot.setScaleX(mScaleX);
            mRoot.setScaleY(mScaleY);
            if (mRoot.getWidth() > 0) {
                mRoot.setPivotX(mRoot.getWidth() / 2f);
                mRoot.setPivotY(mRoot.getHeight() / 2f);
            }
        }

        void setWindowAlpha(float alpha) {
            mAlpha = Math.max(0f, Math.min(1f, alpha));
            onMain(() -> {
                mRoot.setAlpha(mAlpha);
                return Boolean.TRUE;
            }, Boolean.FALSE);
        }

        float getWindowAlpha() {
            return mAlpha;
        }

        void setWindowScale(float scaleX, float scaleY) {
            mScaleX = scaleX;
            mScaleY = scaleY;
            onMain(() -> {
                if (mRoot.getWidth() > 0) {
                    mRoot.setPivotX(mRoot.getWidth() / 2f);
                    mRoot.setPivotY(mRoot.getHeight() / 2f);
                }
                mRoot.setScaleX(mScaleX);
                mRoot.setScaleY(mScaleY);
                return Boolean.TRUE;
            }, Boolean.FALSE);
        }

        /** 主线程 flush 一次后再读，得到真正生效的窗口坐标。 */
        int getRealWindowX() {
            return onMain(() -> mParams == null ? 0 : mParams.x, 0);
        }
        int getRealWindowY() {
            return onMain(() -> mParams == null ? 0 : mParams.y, 0);
        }

        /** 只改根 View 可见性：最廉价的开合方式（不掉窗口，不等 WindowManager）。 */
        void setContentVisibility(int visibility) {
            mContentVisibility = visibility;
            mHandler.post(() -> mRoot.setVisibility(visibility));
        }

        int getContentVisibility() {
            return mContentVisibility;
        }

        int id() {
            return mId;
        }

        boolean isOwnedBy(long engineHandle) {
            return mOwnerEngine != 0 && mOwnerEngine == engineHandle;
        }

        private interface MainTask<T> {
            T run();
        }

        /** 在主线程执行并等待完成（已经主线程时直接执行），返回后调用方看到的状态已生效。 */
        private <T> T onMain(MainTask<T> task, T fallback) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                try {
                    return task.run();
                } catch (Throwable error) {
                    Log.w("QuickJsFloatyWindow", "main task failed", error);
                    return fallback;
                }
            }
            final Object[] result = new Object[1];
            CountDownLatch latch = new CountDownLatch(1);
            mHandler.post(() -> {
                try {
                    result[0] = task.run();
                } catch (Throwable error) {
                    Log.w("QuickJsFloatyWindow", "main task failed", error);
                } finally {
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(8, TimeUnit.SECONDS)) {
                    return fallback;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return fallback;
            }
            //noinspection unchecked
            return result[0] == null ? fallback : (T) result[0];
        }

        void update(String configJson) {
            JSONObject c;
            try {
                c = new JSONObject(configJson);
            } catch (Throwable error) {
                Log.w("QuickJsFloatyWindow", "update failed", error);
                return;
            }
            // 位置/尺寸/变换先同步写进 mParams 与字段：调用方紧接着 getX()/getWidth() 就能拿到新值，
            // 真正上屏仍由主线程完成（setPosition 的“意图值”与“生效值”因此可区分）。
            if (c.has("x")) {
                mParams.x = c.optInt("x", mParams.x);
            }
            if (c.has("y")) {
                mParams.y = c.optInt("y", mParams.y);
            }
            if (c.has("width")) {
                mParams.width = c.optInt("width", mParams.width);
                mRequestedWidth = mParams.width;
            }
            if (c.has("height")) {
                mParams.height = c.optInt("height", mParams.height);
                mRequestedHeight = mParams.height;
            }
            if (c.has("alpha")) {
                mAlpha = Math.max(0f, Math.min(1f, (float) c.optDouble("alpha", mAlpha)));
            }
            if (c.has("scale")) {
                mScaleX = mScaleY = (float) c.optDouble("scale", mScaleX);
            }
            if (c.has("scaleX")) {
                mScaleX = (float) c.optDouble("scaleX", mScaleX);
            }
            if (c.has("scaleY")) {
                mScaleY = (float) c.optDouble("scaleY", mScaleY);
            }
            // 触摸开关/形状同步写字段：调用方紧接着 getTouchRegion() 就能读到新值（上屏仍由主线程完成）。
            if (c.has("touchable")) {
                mTouchable = c.optBoolean("touchable", true);
            }
            boolean regionConfig = readShapeConfig(c);
            mHandler.post(() -> {
                try {
                    if (mTextView != null && c.has("text")) {
                        mTextView.setText(c.optString("text"));
                    }
                    if (mTextView != null && c.has("textColor")) {
                        try {
                            mTextView.setTextColor(android.graphics.Color.parseColor(c.optString("textColor")));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    if (c.has("backgroundColor")) {
                        try {
                            mRoot.setBackground(new android.graphics.drawable.ColorDrawable(
                                    android.graphics.Color.parseColor(c.optString("backgroundColor"))));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    if (c.has("touchable")) {
                        mTouchable = c.optBoolean("touchable", true);
                        applyTouchableFlag();
                        if (mShown) {
                            OverlayRegistry.register(mOwnerEngineId, mOwnerTag, "quickjs",
                                    OverlayRegistry.Kind.QUICKJS_FLOATY, mRoot, mWindowManager, mTouchable);
                            try {
                                mWindowManager.updateViewLayout(mRoot, mParams);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (c.has("draggable")) {
                        mDraggable = c.optBoolean("draggable", false);
                    }
                    if (regionConfig) {
                        applyOutlineNow();
                        applyTouchRegionNow();
                    }
                    if (c.has("alpha") || c.has("scale") || c.has("scaleX") || c.has("scaleY")) {
                        applyWindowTransform();
                    }
                    if (mShown && (c.has("width") || c.has("height") || c.has("x") || c.has("y"))) {
                        mWindowManager.updateViewLayout(mRoot, mParams);
                    }
                } catch (Throwable error) {
                    Log.w("QuickJsFloatyWindow", "update failed", error);
                }
            });
        }

        void close() {
            onMain(() -> {
                hideNow();
                closeCanvasViews(mRoot);
                return Boolean.TRUE;
            }, Boolean.FALSE);
        }

        /** closeOwnedFloatyWindows 兜底：窗口从未 show / hideNow 异常时也要反注册。 */
        void unregisterOverlay() {
            OverlayRegistry.unregister(mRoot);
        }

        // ---- view access (Auto.js-style window.<id> proxies) ----

        private View findViewById(String id) {
            try {
                int resourceId = com.stardust.autojs.core.ui.inflater.util.Ids.parse(id);
                return mRoot.findViewById(resourceId);
            } catch (Throwable error) {
                return null;
            }
        }

        String getViewText(String id) {
            // 已在主线程时直接执行：在主线程 post + 等 latch 会自锁（runOnMainThread 里就是这种情况）。
            return onMain(() -> {
                View view = findViewById(id);
                return view instanceof android.widget.TextView
                        ? ((android.widget.TextView) view).getText().toString() : "";
            }, "");
        }

        void setViewText(String id, String text) {
            mHandler.post(() -> {
                View view = findViewById(id);
                if (view instanceof android.widget.TextView) {
                    ((android.widget.TextView) view).setText(text == null ? "" : text);
                }
            });
        }

        /** 取真实 View 引用（主线程查找后返回）；找不到返回 null。 */
        View getView(String id) {
            return onMain(() -> findViewById(id), null);
        }

        View getRootView() {
            return mRoot;
        }

        void setViewVisibility(String id, String visibility) {
            mHandler.post(() -> {
                View view = findViewById(id);
                if (view != null) {
                    try {
                        view.setVisibility(Integer.parseInt(visibility.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
        }

        void registerViewClick(String id, String mode) {
            mHandler.post(() -> {
                try {
                    View view = findViewById(id);
                    if (view == null) {
                        return;
                    }
                    if ("long_click".equals(mode)) {
                        view.setOnLongClickListener(v -> {
                            emitEvent(id, "long_click");
                            return true;
                        });
                    } else if ("touch".equals(mode)) {
                        view.setOnTouchListener((v, event) -> {
                            emitEvent(id, "touch");
                            return false;
                        });
                    } else {
                        view.setClickable(true);
                        view.setOnClickListener(v -> emitEvent(id, "click"));
                    }
                } catch (Throwable error) {
                    Log.w("QuickJsFloatyWindow", "registerViewClick failed", error);
                }
            });
        }

        private void emitEvent(String viewId, String event) {
            if (mEventSink == null) {
                return;
            }
            try {
                mEventSink.emit(new JSONObject()
                        .put("window", mId).put("id", viewId).put("event", event).toString());
            } catch (JSONException ignored) {
            }
        }

        void setDragHandle(String id) {
            mHandler.post(() -> {
                final View handle = findViewById(id);
                if (handle == null) {
                    Log.w("QuickJsFloatyWindow", "drag handle not found: " + id);
                    return;
                }
                handle.setOnTouchListener(new View.OnTouchListener() {
                    private float downX;
                    private float downY;
                    private int startX;
                    private int startY;
                    private boolean moved;

                    @Override
                    public boolean onTouch(View view, android.view.MotionEvent event) {
                        if (mParams == null || !mShown) return false;
                        switch (event.getActionMasked()) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                downX = event.getRawX();
                                downY = event.getRawY();
                                startX = mParams.x;
                                startY = mParams.y;
                                moved = false;
                                return true;
                            case android.view.MotionEvent.ACTION_MOVE:
                                final int dx = Math.round(event.getRawX() - downX);
                                final int dy = Math.round(event.getRawY() - downY);
                                if (Math.abs(dx) + Math.abs(dy) < 3) return true;
                                moved = true;
                                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                                mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
                                int maxX = Math.max(0, metrics.widthPixels - mRoot.getWidth());
                                int maxY = Math.max(0, metrics.heightPixels - mRoot.getHeight());
                                mParams.x = Math.max(0, Math.min(maxX, startX + dx));
                                mParams.y = Math.max(0, Math.min(maxY, startY + dy));
                                try {
                                    mWindowManager.updateViewLayout(mRoot, mParams);
                                } catch (Throwable error) {
                                    Log.w("QuickJsFloatyWindow", "drag update failed", error);
                                }
                                return true;
                            case android.view.MotionEvent.ACTION_UP:
                            case android.view.MotionEvent.ACTION_CANCEL:
                                if (moved) emitEvent(id, "drag_end");
                                return true;
                            default:
                                return true;
                        }
                    }
                });
            });
        }

        void setAdjustable(boolean enabled) {
            mAdjustable = enabled;
            if (enabled) {
                // Rhino's adjust mode lets the user drag the window; reuse the
                // existing touch-drag path so the switch has a visible effect.
                mDraggable = true;
            }
        }

        boolean isAdjustable() {
            return mAdjustable;
        }

        int getWindowX() {
            return mParams == null ? 0 : mParams.x;
        }

        int getWindowY() {
            return mParams == null ? 0 : mParams.y;
        }

        void registerViewTouch(String id) {
            mHandler.post(() -> {
                try {
                    View view = findViewById(id);
                    if (view == null) {
                        return;
                    }
                    view.setOnTouchListener((v, event) -> {
                        if (mEventSink == null) {
                            return false;
                        }
                        try {
                            mEventSink.emit(new JSONObject()
                                    .put("window", mId).put("id", id).put("event", "touch")
                                    .put("action", event.getAction())
                                    .put("x", (int) event.getX())
                                    .put("y", (int) event.getY())
                                    .put("rawX", (int) event.getRawX())
                                    .put("rawY", (int) event.getRawY()).toString());
                        } catch (JSONException ignored) {
                        }
                        return true;
                    });
                } catch (Throwable error) {
                    Log.w("QuickJsFloatyWindow", "registerViewTouch failed", error);
                }
            });
        }

        void registerViewKey(String id) {
            mHandler.post(() -> {
                try {
                    View view = findViewById(id);
                    if (view == null) {
                        return;
                    }
                    view.setFocusableInTouchMode(true);
                    view.requestFocus();
                    view.setOnKeyListener((v, keyCode, event) -> {
                        if (mEventSink == null) {
                            return false;
                        }
                        try {
                            mEventSink.emit(new JSONObject()
                                    .put("window", mId).put("id", id).put("event", "key")
                                    .put("action", event.getAction())
                                    .put("keyCode", keyCode)
                                    .put("keyName", keyName(keyCode)).toString());
                        } catch (JSONException ignored) {
                        }
                        return true;
                    });
                } catch (Throwable error) {
                    Log.w("QuickJsFloatyWindow", "registerViewKey failed", error);
                }
            });
        }

        private static String keyName(int keyCode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK: return "back";
                case KeyEvent.KEYCODE_HOME: return "home";
                case KeyEvent.KEYCODE_MENU: return "menu";
                case KeyEvent.KEYCODE_VOLUME_UP: return "volume_up";
                case KeyEvent.KEYCODE_VOLUME_DOWN: return "volume_down";
                case KeyEvent.KEYCODE_POWER: return "power";
                case KeyEvent.KEYCODE_ENTER: return "enter";
                default: return "key_" + keyCode;
            }
        }

        void setWindowFocusable(boolean focusable) {
            mHandler.post(() -> {
                if (mParams == null) {
                    return;
                }
                if (focusable) {
                    mParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                } else {
                    mParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                }
                if (mShown) {
                    try {
                        mWindowManager.updateViewLayout(mRoot, mParams);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        void requestViewFocus(String id) {
            mHandler.post(() -> {
                View view = findViewById(id);
                if (view != null) {
                    view.requestFocus();
                }
            });
        }

        // ---- 通用属性 / 动作 / 尺寸（对齐 Rhino 的 NativeView.attr 与窗口 getWidth/getHeight）----

        boolean hasView(String id) {
            Boolean result = withView(id, view -> view != null);
            return result != null && result;
        }

        String getViewAttr(String id, String name) {
            return withView(id, view -> readViewAttribute(view, name, mResourceParser));
        }

        boolean setViewAttr(final String id, final String name, final String value) {
            final boolean[] result = new boolean[1];
            final CountDownLatch latch = new CountDownLatch(1);
            mHandler.post(() -> {
                result[0] = writeViewAttribute(findViewById(id), name, value, mResourceParser);
                latch.countDown();
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return result[0];
        }

        boolean performViewAction(String id, String action) {
            // 限定外类方法：本类里也有同名的 performViewAction(String, String)。
            Boolean result = withView(id, view -> QuickJsHostBridge.performViewAction(view, action));
            return result != null && result;
        }

        int getWindowWidth() {
            if (mRequestedWidth > 0) {
                return mRequestedWidth;
            }
            int measured = onMain(() -> mRoot.getWidth(), 0);
            if (measured > 0) {
                return measured;
            }
            // 还没完成布局时退回 XML/窗口参数里的期望宽度（避免读出 0）。
            return mParams != null && mParams.width > 0 ? mParams.width : measured;
        }

        int getWindowHeight() {
            if (mRequestedHeight > 0) {
                return mRequestedHeight;
            }
            int measured = onMain(() -> mRoot.getHeight(), 0);
            if (measured > 0) {
                return measured;
            }
            return mParams != null && mParams.height > 0 ? mParams.height : measured;
        }

        private interface ViewCallback<T> {
            T apply(View view);
        }

        /** 在 main 线程找到控件并取值（找不到时回调收到 null）；已在主线程时直接执行。 */
        private <T> T withView(final String id, final ViewCallback<T> callback) {
            return onMain(() -> callback.apply(findViewById(id)), null);
        }
    }

    // ---- ui (Activity 承载 / fullscreen overlay 两种形态) ----
    private com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater mUiInflater;
    private volatile View mUiRoot;
    /** overlay 窗口的根容器（UiOverlayRoot，包着内容视图和右上角关闭按钮）；Activity 模式为 null。 */
    private volatile View mUiWindowRoot;
    /** 用户主动关闭 overlay 的防抖标记（返回键 + ✕ 可能连续触发，只处理一次）。 */
    private volatile boolean mUiCloseRequested;
    /**
     * 进程内最近一次 QuickJS overlay UI 的根视图：新的 {@code ui.layout} 会先把它摘掉。
     * 真机出现过「界面叠在一起」：上一次脚本/另一个 UI 示例的覆盖窗还开着时，新脚本又
     * addView 了一个 MATCH_PARENT 窗口，看起来就是标题/按钮互相重叠。
     */
    private static volatile View sUiOverlayRoot;
    private volatile boolean mUiShown;
    private volatile int mUiWindowId;
    /** `"ui"` 模式（跑在 ScriptExecuteActivity 里）注入的宿主 Activity；其余场景为 null。 */
    private volatile android.app.Activity mUiHostActivity;

    /**
     * 由 {@code QuickJsJavaScriptEngine.setHostActivity} 注入：有宿主 Activity 时
     * {@code ui.layout} 挂到它的内容视图（按 Home 退到后台、返回键才结束），
     * 与 Rhino 的 {@code ui.setContentView(activity)} 一致；没有时仍用系统悬浮窗。
     */
    public void setUiHostActivity(android.app.Activity activity) {
        mUiHostActivity = activity;
    }

    // ------------------------------------------------------------------
    // 控件通用属性 / 动作
    //
    // Rhino 里窗口控件是 NativeView：任意 XML 属性可以 attr(name[, value]) 读写（ViewAttributes），
    // 常用控件方法也能直接调。QuickJS 侧用同一套 ViewAttributes 表 + 常用方法兜底。
    // ------------------------------------------------------------------

    static String readViewAttribute(View view, String name,
                                    com.stardust.autojs.core.ui.inflater.ResourceParser parser) {
        if (view == null || name == null) {
            return null;
        }
        try {
            com.stardust.autojs.core.ui.attribute.ViewAttributes attributes =
                    com.stardust.autojs.core.ui.ViewExtras.getViewAttributes(view, parser);
            if (attributes.contains(name)) {
                String value = attributes.getAttrValue(name);
                if (value != null) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        switch (name) {
            case "text":
                return view instanceof android.widget.TextView
                        ? ((android.widget.TextView) view).getText().toString() : null;
            case "textSize":
                return view instanceof android.widget.TextView
                        ? String.valueOf(((android.widget.TextView) view).getTextSize()) : null;
            case "textColor":
                return view instanceof android.widget.TextView
                        ? String.format("#%08X", ((android.widget.TextView) view).getCurrentTextColor())
                        : null;
            case "hint":
                return view instanceof android.widget.TextView
                        ? String.valueOf(((android.widget.TextView) view).getHint()) : null;
            case "visibility": return String.valueOf(view.getVisibility());
            case "enabled": return String.valueOf(view.isEnabled());
            case "alpha": return String.valueOf(view.getAlpha());
            case "clickable": return String.valueOf(view.isClickable());
            case "longClickable": return String.valueOf(view.isLongClickable());
            case "selected": return String.valueOf(view.isSelected());
            case "focusable": return String.valueOf(view.isFocusable());
            case "checked":
                return view instanceof android.widget.CompoundButton
                        ? String.valueOf(((android.widget.CompoundButton) view).isChecked()) : null;
            case "width": return String.valueOf(viewSize(view, true));
            case "height": return String.valueOf(viewSize(view, false));
            case "canScrollDown": return String.valueOf(view.canScrollVertically(1));
            case "scrollY": return String.valueOf(view.getScrollY());
            case "id": return String.valueOf(view.getId());
            case "className": return view.getClass().getName();
            default: return null;
        }
    }

    /** 控件尺寸：布局已完成时用实测值，未完成时退回 layoutParams 里的期望值。 */
    static int viewSize(View view, boolean width) {
        if (view == null) {
            return 0;
        }
        int measured = width ? view.getWidth() : view.getHeight();
        if (measured > 0) {
            return measured;
        }
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            int requested = width ? params.width : params.height;
            if (requested > 0) {
                return requested;
            }
        }
        return measured;
    }

    /**
     * QuickJS UI 的尺寸字符串解析（{@code attr("textSize", "22sp")}、浮窗配置等入口共用）。
     *
     * <p>支持 px / dp / dip / sp / pt / in / mm 单位，以及纯数字、正负号、小数；无单位时用
     * {@code defaultUnit}（textSize 传 SP，与 XML {@code textSize="16sp"} 以及浮窗
     * {@code textSize: 14}（数字按 SP）的语义一致）。换算用
     * {@link android.util.TypedValue#applyDimension}：SP 自动走 scaledDensity（字体缩放），
     * DP 走 density。
     *
     * <p>非法值/未知单位<b>不抛异常</b>：记录中文提示并返回 {@link Float#NaN}，调用方按
     * 「忽略该属性」处理。以前 textSize 直接 {@code Float.parseFloat("22sp")} 会抛
     * NumberFormatException，把整段 UI 脚本带崩（QuickJS UI 示例回归问题）。
     */
    private static final Pattern UI_DIMENSION_PATTERN = Pattern.compile(
            "([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))\\s*([a-zA-Z]*)");

    static float parseUiDimensionPx(String raw, int defaultUnit, Context context, String attributeName) {
        if (raw == null) {
            return Float.NaN;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            Log.w("QuickJsHostBridge", "控件属性 " + attributeName + " 的尺寸为空，已忽略");
            return Float.NaN;
        }
        java.util.regex.Matcher matcher = UI_DIMENSION_PATTERN.matcher(text);
        if (!matcher.matches()) {
            Log.w("QuickJsHostBridge", "控件属性 " + attributeName + " 的尺寸「" + raw
                    + "」无法解析，已忽略（支持 px/dp/dip/sp/pt/in/mm 或纯数字）");
            return Float.NaN;
        }
        int unit = defaultUnit;
        String unitText = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.US);
        if (!unitText.isEmpty()) {
            switch (unitText) {
                case "px":
                    unit = android.util.TypedValue.COMPLEX_UNIT_PX;
                    break;
                case "dp":
                case "dip":
                    unit = android.util.TypedValue.COMPLEX_UNIT_DIP;
                    break;
                case "sp":
                    unit = android.util.TypedValue.COMPLEX_UNIT_SP;
                    break;
                case "pt":
                    unit = android.util.TypedValue.COMPLEX_UNIT_PT;
                    break;
                case "in":
                    unit = android.util.TypedValue.COMPLEX_UNIT_IN;
                    break;
                case "mm":
                    unit = android.util.TypedValue.COMPLEX_UNIT_MM;
                    break;
                default:
                    Log.w("QuickJsHostBridge", "控件属性 " + attributeName + " 的尺寸单位「" + unitText
                            + "」不支持，已忽略（支持 px/dp/dip/sp/pt/in/mm）");
                    return Float.NaN;
            }
        }
        final float number;
        try {
            number = Float.parseFloat(matcher.group(1));
        } catch (NumberFormatException error) {
            Log.w("QuickJsHostBridge", "控件属性 " + attributeName + " 的尺寸数值「" + raw + "」非法，已忽略");
            return Float.NaN;
        }
        return android.util.TypedValue.applyDimension(unit, number, displayMetrics(context));
    }

    /** 当前屏幕 DisplayMetrics（优先控件 Context；拿不到时退回系统资源）。 */
    private static android.util.DisplayMetrics displayMetrics(Context context) {
        try {
            if (context != null && context.getResources() != null) {
                return context.getResources().getDisplayMetrics();
            }
        } catch (Throwable ignored) {
        }
        return android.content.res.Resources.getSystem().getDisplayMetrics();
    }

    // ------------------------------------------------------------------
    // 常用布局属性（单位/枚举解析，失败只警告）
    // ------------------------------------------------------------------

    /**
     * 常用布局属性写入。命中返回 true/false（false = 已识别但当前控件/父布局不支持，已警告），
     * 未命中返回 null 交给 ViewAttributes / 控件方法兜底。
     */
    private static Boolean writeQuickJsCommonAttribute(View view, String name, String value) {
        if (value != null) {
            String trimmed = value.trim();
            // 资源引用（@dimen/x、?attr/x）与百分比（50%）交给 ViewAttributes/Dimensions 处理，
            // 本层只做单位换算与布局参数兜底，避免把原本能用的写法拦掉。
            if (trimmed.startsWith("@") || trimmed.startsWith("?") || trimmed.contains("%")) {
                return null;
            }
        }
        switch (name) {
            case "visibility": {
                Integer visibility = parseQuickJsVisibility(value);
                if (visibility == null) {
                    Log.w("QuickJsHostBridge", "控件属性 visibility=" + value
                            + " 无效，已忽略（可用 0/4/8 或 visible/invisible/gone）");
                    return false;
                }
                view.setVisibility(visibility);
                return true;
            }
            case "width":
            case "layout_width":
            case "w":
                return applyQuickJsViewSize(view, true, value, name);
            case "height":
            case "layout_height":
            case "h":
                return applyQuickJsViewSize(view, false, value, name);
            case "minWidth":
            case "minHeight": {
                Integer pixels = parseQuickJsDimension(value, view, name);
                if (pixels == null) {
                    return false;
                }
                if ("minWidth".equals(name)) {
                    view.setMinimumWidth(pixels);
                } else {
                    view.setMinimumHeight(pixels);
                }
                view.requestLayout();
                return true;
            }
            case "maxWidth":
            case "maxHeight": {
                if (!(view instanceof android.widget.TextView)) {
                    Log.w("QuickJsHostBridge", "控件属性 " + name + " 只支持文本控件，已忽略");
                    return false;
                }
                Integer pixels = parseQuickJsDimension(value, view, name);
                if (pixels == null) {
                    return false;
                }
                if ("maxWidth".equals(name)) {
                    ((android.widget.TextView) view).setMaxWidth(pixels);
                } else {
                    ((android.widget.TextView) view).setMaxHeight(pixels);
                }
                view.requestLayout();
                return true;
            }
            case "padding":
                return applyQuickJsPadding(view, "padding", value);
            case "paddingLeft":
            case "paddingTop":
            case "paddingRight":
            case "paddingBottom":
                return applyQuickJsPadding(view, name, value);
            case "margin":
            case "layout_margin":
                return applyQuickJsMargin(view, "margin", value);
            case "marginLeft":
            case "marginTop":
            case "marginRight":
            case "marginBottom":
            case "layout_marginLeft":
            case "layout_marginTop":
            case "layout_marginRight":
            case "layout_marginBottom":
            case "layout_marginStart":
            case "layout_marginEnd":
                return applyQuickJsMargin(view, name, value);
            case "layout_weight":
                return applyQuickJsLayoutWeight(view, value);
            case "gravity":
                return applyQuickJsGravity(view, false, value);
            case "layout_gravity":
                return applyQuickJsGravity(view, true, value);
            default:
                return null;
        }
    }

    /** 数字或名称的可见性：0/4/8 与 visible/invisible/gone（大小写不敏感）。 */
    private static Integer parseQuickJsVisibility(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        switch (text.toLowerCase(Locale.US)) {
            case "0":
            case "visible":
            case "show":
                return android.view.View.VISIBLE;
            case "4":
            case "invisible":
                return android.view.View.INVISIBLE;
            case "8":
            case "gone":
            case "hide":
            case "hidden":
                return android.view.View.GONE;
            default:
                return null;
        }
    }

    /** 尺寸字符串 -> 像素（默认单位 DIP，与 XML 的 w/h 一致）；非法返回 null 并警告。 */
    private static Integer parseQuickJsDimension(String raw, View view, String attrName) {
        float pixels = parseUiDimensionPx(raw,
                android.util.TypedValue.COMPLEX_UNIT_DIP, view.getContext(), attrName);
        if (Float.isNaN(pixels)) {
            return null;
        }
        return Math.round(pixels);
    }

    /** width/height：支持 match_parent/fill_parent/wrap_content 与带单位尺寸（默认 dp）。 */
    private static boolean applyQuickJsViewSize(View view, boolean width, String raw, String attrName) {
        String text = raw == null ? "" : raw.trim();
        int size;
        switch (text.toLowerCase(Locale.US)) {
            case "match_parent":
            case "fill_parent":
                size = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                break;
            case "wrap_content":
                size = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                break;
            default: {
                Integer pixels = parseQuickJsDimension(text, view, attrName);
                if (pixels == null) {
                    return false;
                }
                size = pixels;
                break;
            }
        }
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) {
            params = new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (width) {
            params.width = size;
        } else {
            params.height = size;
        }
        view.setLayoutParams(params);
        view.requestLayout();
        return true;
    }

    /** padding[/Left/Top/Right/Bottom]：支持 1~4 个带单位值（默认 dp）。 */
    private static boolean applyQuickJsPadding(View view, String attrName, String raw) {
        if ("padding".equals(attrName)) {
            Integer[] pixels = parseQuickJsDimensionArray(raw, view, attrName);
            if (pixels == null) {
                return false;
            }
            view.setPadding(pixels[0], pixels[1], pixels[2], pixels[3]);
            view.requestLayout();
            return true;
        }
        Integer pixels = parseQuickJsDimension(raw, view, attrName);
        if (pixels == null) {
            return false;
        }
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getPaddingRight();
        int bottom = view.getPaddingBottom();
        switch (attrName) {
            case "paddingLeft":
                left = pixels;
                break;
            case "paddingTop":
                top = pixels;
                break;
            case "paddingRight":
                right = pixels;
                break;
            case "paddingBottom":
                bottom = pixels;
                break;
            default:
                return false;
        }
        view.setPadding(left, top, right, bottom);
        view.requestLayout();
        return true;
    }

    /** margin[/layout_margin] 与各方向 margin：支持 1~4 个带单位值（默认 dp）。 */
    private static boolean applyQuickJsMargin(View view, String attrName, String raw) {
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof android.view.ViewGroup.MarginLayoutParams)) {
            Log.w("QuickJsHostBridge", "控件属性 " + attrName
                    + " 需要控件已加入支持 margin 的父布局，已忽略");
            return false;
        }
        android.view.ViewGroup.MarginLayoutParams marginParams =
                (android.view.ViewGroup.MarginLayoutParams) params;
        if ("margin".equals(attrName) || "layout_margin".equals(attrName)) {
            Integer[] pixels = parseQuickJsDimensionArray(raw, view, attrName);
            if (pixels == null) {
                return false;
            }
            marginParams.setMargins(pixels[0], pixels[1], pixels[2], pixels[3]);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                marginParams.setMarginStart(pixels[0]);
                marginParams.setMarginEnd(pixels[2]);
            }
        } else {
            Integer pixels = parseQuickJsDimension(raw, view, attrName);
            if (pixels == null) {
                return false;
            }
            switch (attrName) {
                case "marginLeft":
                case "layout_marginLeft":
                    marginParams.leftMargin = pixels;
                    break;
                case "marginTop":
                case "layout_marginTop":
                    marginParams.topMargin = pixels;
                    break;
                case "marginRight":
                case "layout_marginRight":
                    marginParams.rightMargin = pixels;
                    break;
                case "marginBottom":
                case "layout_marginBottom":
                    marginParams.bottomMargin = pixels;
                    break;
                case "layout_marginStart":
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        marginParams.setMarginStart(pixels);
                    } else {
                        marginParams.leftMargin = pixels;
                    }
                    break;
                case "layout_marginEnd":
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        marginParams.setMarginEnd(pixels);
                    } else {
                        marginParams.rightMargin = pixels;
                    }
                    break;
                default:
                    return false;
            }
        }
        view.setLayoutParams(marginParams);
        view.requestLayout();
        return true;
    }

    /** layout_weight：只对线性布局子控件生效（多列行靠它平分宽度，否则控件会挤在一起/重叠）。 */
    private static boolean applyQuickJsLayoutWeight(View view, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            Log.w("QuickJsHostBridge", "控件属性 layout_weight 为空，已忽略");
            return false;
        }
        final float weight;
        try {
            weight = Float.parseFloat(raw.trim());
        } catch (NumberFormatException error) {
            Log.w("QuickJsHostBridge", "控件属性 layout_weight=" + raw + " 不是数字，已忽略");
            return false;
        }
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof android.widget.LinearLayout.LayoutParams)) {
            Log.w("QuickJsHostBridge", "控件属性 layout_weight 需要父布局为线性布局，已忽略");
            return false;
        }
        ((android.widget.LinearLayout.LayoutParams) params).weight = weight;
        view.setLayoutParams(params);
        view.requestLayout();
        return true;
    }

    /** gravity / layout_gravity：名称（center|center_vertical|left|right|top|bottom…）或数字。 */
    private static boolean applyQuickJsGravity(View view, boolean layoutGravity, String raw) {
        Integer gravity = parseQuickJsGravity(raw);
        if (gravity == null) {
            Log.w("QuickJsHostBridge", "控件属性 " + (layoutGravity ? "layout_gravity" : "gravity")
                    + "=" + raw + " 无效，已忽略");
            return false;
        }
        if (!layoutGravity) {
            try {
                java.lang.reflect.Method setGravity =
                        view.getClass().getMethod("setGravity", int.class);
                setGravity.invoke(view, gravity);
                view.requestLayout();
                return true;
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "控件 " + view.getClass().getName()
                        + " 不支持 gravity，已忽略");
                return false;
            }
        }
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) params).gravity = gravity;
        } else if (params instanceof android.widget.FrameLayout.LayoutParams) {
            ((android.widget.FrameLayout.LayoutParams) params).gravity = gravity;
        } else if (params != null) {
            try {
                java.lang.reflect.Field field = params.getClass().getField("gravity");
                field.setInt(params, gravity);
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "控件属性 layout_gravity 当前父布局不支持，已忽略");
                return false;
            }
        } else {
            Log.w("QuickJsHostBridge", "控件属性 layout_gravity 需要控件已加入父布局，已忽略");
            return false;
        }
        view.setLayoutParams(params);
        view.requestLayout();
        return true;
    }

    /** 解析 gravity 名称（'|'、空格或下划线组合）或数字；无法识别返回 null。 */
    private static Integer parseQuickJsGravity(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        int gravity = 0;
        boolean recognized = false;
        for (String part : text.toLowerCase(Locale.US).split("[|\\s]+")) {
            switch (part) {
                case "":
                    break;
                case "center":
                    gravity |= android.view.Gravity.CENTER;
                    recognized = true;
                    break;
                case "center_horizontal":
                case "centerhorizontal":
                    gravity |= android.view.Gravity.CENTER_HORIZONTAL;
                    recognized = true;
                    break;
                case "center_vertical":
                case "centervertical":
                    gravity |= android.view.Gravity.CENTER_VERTICAL;
                    recognized = true;
                    break;
                case "left":
                case "start":
                    gravity |= android.view.Gravity.LEFT;
                    recognized = true;
                    break;
                case "right":
                case "end":
                    gravity |= android.view.Gravity.RIGHT;
                    recognized = true;
                    break;
                case "top":
                    gravity |= android.view.Gravity.TOP;
                    recognized = true;
                    break;
                case "bottom":
                    gravity |= android.view.Gravity.BOTTOM;
                    recognized = true;
                    break;
                case "fill":
                    gravity |= android.view.Gravity.FILL;
                    recognized = true;
                    break;
                case "fill_horizontal":
                case "fillhorizontal":
                    gravity |= android.view.Gravity.FILL_HORIZONTAL;
                    recognized = true;
                    break;
                case "fill_vertical":
                case "fillvertical":
                    gravity |= android.view.Gravity.FILL_VERTICAL;
                    recognized = true;
                    break;
                default:
                    return null;
            }
        }
        return recognized ? gravity : null;
    }

    /** 1~4 个尺寸值 -> 左上右下像素（同 XML padding/margin 语法）。非法返回 null。 */
    private static Integer[] parseQuickJsDimensionArray(String raw, View view, String attrName) {
        if (raw == null || raw.trim().isEmpty()) {
            Log.w("QuickJsHostBridge", "控件属性 " + attrName + " 的尺寸为空，已忽略");
            return null;
        }
        String[] parts = raw.trim().split("\\s+");
        if (parts.length != 1 && parts.length != 2 && parts.length != 4) {
            Log.w("QuickJsHostBridge", "控件属性 " + attrName + " 需要 1/2/4 个尺寸值，已忽略："
                    + raw);
            return null;
        }
        Integer[] pixels = new Integer[]{0, 0, 0, 0};
        for (int i = 0; i < parts.length; i++) {
            Integer pixel = parseQuickJsDimension(parts[i], view, attrName);
            if (pixel == null) {
                return null;
            }
            pixels[i] = pixel;
        }
        if (parts.length == 1) {
            pixels[1] = pixels[2] = pixels[3] = pixels[0];
        } else if (parts.length == 2) {
            pixels[2] = pixels[0];
            pixels[3] = pixels[1];
        }
        return pixels;
    }

    /**
     * 写控件属性（{@code ui.<id>.attr(name, value)}、{@code uiSetConfig}、浮窗控件共用）。
     *
     * <p>处理顺序：
     * <ol>
     *   <li>常用布局属性（{@link #writeQuickJsCommonAttribute}）：visibility 数字/名称、
     *       width/height/w/h（含 match_parent/wrap_content 与 sp/dp/px 等单位）、
     *       min/maxWidth/Height、padding/margin 系列、layout_weight、gravity/layout_gravity。
     *       这一层用 {@link #parseUiDimensionPx} 解析单位，早于 ViewAttributes 执行，
     *       所以 {@code visibility=8} 这种数字值不会再触发 ViewAttributes 的 InflateException 警告；</li>
     *   <li>其余属性交给 ViewAttributes（与 Rhino NativeView 同一套）；</li>
     *   <li>最后是常用控件方法兜底（text/hint/textColor/backgroundColor…）。</li>
     * </ol>
     *
     * <p><b>任何单个属性失败都只记警告并返回 false，绝不向脚本抛异常</b>：属性设置是
     * 「尽力而为」，一个控件写失败不能影响其它控件，更不能让 UI 脚本中断。
     */
    static boolean writeViewAttribute(View view, String name, String value,
                                      com.stardust.autojs.core.ui.inflater.ResourceParser parser) {
        if (view == null || name == null) {
            return false;
        }
        try {
            Boolean handled = writeQuickJsCommonAttribute(view, name, value);
            if (handled != null) {
                return handled;
            }
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "控件属性 " + name + "=" + value
                    + " 设置失败（已忽略）：" + error.getMessage(), error);
            return false;
        }
        try {
            com.stardust.autojs.core.ui.attribute.ViewAttributes attributes =
                    com.stardust.autojs.core.ui.ViewExtras.getViewAttributes(view, parser);
            if (attributes.contains(name)) {
                attributes.setAttrValue(name, value);
                return true;
            }
        } catch (Throwable error) {
            // 例如 attr("width","abc") 这类非法尺寸：ViewAttributes 会抛 InflateException；
            // 这里给中文提示并继续走下方常用属性兜底，不让它变成脚本崩溃。
            Log.w("QuickJsHostBridge", "控件属性 " + name + "=" + value
                    + " 设置失败，已尝试兜底：" + error.getMessage(), error);
        }
        try {
            switch (name) {
                case "text":
                    if (!(view instanceof android.widget.TextView)) return false;
                    ((android.widget.TextView) view).setText(value == null ? "" : value);
                    return true;
                case "textSize": {
                    if (!(view instanceof android.widget.TextView)) return false;
                    // 支持 "22sp"/"12dp"/"100px"/纯数字（纯数字按 SP），非法值忽略并打中文提示。
                    float textSizePx = parseUiDimensionPx(value,
                            android.util.TypedValue.COMPLEX_UNIT_SP, view.getContext(), "textSize");
                    if (Float.isNaN(textSizePx)) {
                        return false;
                    }
                    ((android.widget.TextView) view).setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx);
                    return true;
                }
                case "textColor":
                    if (!(view instanceof android.widget.TextView)) return false;
                    ((android.widget.TextView) view).setTextColor(android.graphics.Color.parseColor(value));
                    return true;
                case "hint":
                    if (!(view instanceof android.widget.TextView)) return false;
                    ((android.widget.TextView) view).setHint(value);
                    return true;
                case "visibility":
                    view.setVisibility(Integer.parseInt(value));
                    return true;
                case "enabled":
                    view.setEnabled(Boolean.parseBoolean(value));
                    return true;
                case "alpha":
                    view.setAlpha(Float.parseFloat(value));
                    return true;
                case "clickable":
                    view.setClickable(Boolean.parseBoolean(value));
                    return true;
                case "longClickable":
                    view.setLongClickable(Boolean.parseBoolean(value));
                    return true;
                case "selected":
                    view.setSelected(Boolean.parseBoolean(value));
                    return true;
                case "focusable":
                    view.setFocusable(Boolean.parseBoolean(value));
                    return true;
                case "checked":
                    if (!(view instanceof android.widget.CompoundButton)) return false;
                    ((android.widget.CompoundButton) view).setChecked(Boolean.parseBoolean(value));
                    return true;
                case "backgroundColor":
                    view.setBackgroundColor(android.graphics.Color.parseColor(value));
                    return true;
                case "scrollToEnd":
                    if (!(view instanceof android.widget.ScrollView)) return false;
                    if (Boolean.parseBoolean(value)) {
                        view.post(() -> {
                            android.widget.ScrollView scroll = (android.widget.ScrollView) view;
                            if (scroll.getChildCount() > 0) {
                                scroll.scrollTo(0, scroll.getChildAt(0).getBottom());
                            }
                        });
                    }
                    return true;
                default:
                    return false;
            }
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot set view attribute " + name + "=" + value, error);
            return false;
        }
    }

    private static boolean performViewAction(View view, String action) {
        if (view == null) {
            return false;
        }
        switch (action == null ? "" : action) {
            case "click":
                return view.performClick();
            case "longClick":
                return view.performLongClick();
            default:
                throw new IllegalArgumentException("不支持的控件动作：" + action);
        }
    }

    /** 脚本侧 `window.<id>` 是否为真实存在的控件（Rhino 找不到时返回 undefined）。 */
    public boolean floatyViewExists(int windowId, String id) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window != null && window.hasView(id);
    }

    public String floatyViewGetAttr(int windowId, String id, String name) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        String value = window == null ? null : window.getViewAttr(id, name);
        return value == null ? "" : value;
    }

    public boolean floatyViewSetAttr(int windowId, String id, String name, String value) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window != null && window.setViewAttr(id, name, value);
    }

    public boolean floatyViewAction(int windowId, String id, String action) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window != null && window.performViewAction(id, action);
    }

    public int floatyGetWidth(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 0 : window.getWindowWidth();
    }

    public int floatyGetHeight(int windowId) {
        QuickJsFloatyWindow window = mFloatyWindows.get(windowId);
        return window == null ? 0 : window.getWindowHeight();
    }

    public boolean uiViewExists(int viewId, String id) {
        return Boolean.TRUE.equals(runOnMainSync(mDialogHandler, () -> uiFind(id) != null, Boolean.FALSE));
    }

    public boolean uiViewAction(int viewId, String id, String action) {
        return Boolean.TRUE.equals(runOnMainSync(mDialogHandler,
                () -> performViewAction(uiFind(id), action), Boolean.FALSE));
    }

    /** 控件句柄缓存：ui.xxx.yyy() 这类 Java 成员调用要把控件交给 Java 互操作反射。 */
    private final java.util.Map<String, Long> mUiViewHandles =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 控件成员探测（m/f/p:getter/空），JS 侧据此决定属性是「函数」还是「值」。 */
    public String uiViewProbe(int viewId, String id, String name) {
        return runOnMainSync(mDialogHandler, () -> {
            long handle = uiViewHandle(id);
            return handle == 0 ? "" : mJavaInterop.probe(handle, name);
        }, "");
    }

    /**
     * 控件 Java 成员调用（ui.wv.loadUrl(...)、getSettings() 这类）；字段名返回字段值。
     * 结果按 Java 值编码：对象 → 句柄，与 java.* 通道共用解码逻辑。
     */
    public String uiViewInvoke(int viewId, String id, String name, String argsJson) {
        return runOnMainSync(mDialogHandler, () -> {
            try {
                long handle = uiViewHandle(id);
                if (handle == 0) {
                    return "{\"__error\":\"ui view not found: " + id + "\"}";
                }
                if ("f".equals(mJavaInterop.probe(handle, name))) {
                    return mJavaInterop.getField(handle, name);
                }
                return mJavaInterop.call(handle, name, argsJson);
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "uiViewInvoke failed: " + name, error);
                String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                return "{\"__error\":" + org.json.JSONObject.quote(message) + "}";
            }
        }, "{\"__error\":\"uiViewInvoke timed out\"}");
    }

    private long uiViewHandle(String id) {
        Long cached = mUiViewHandles.get(id);
        if (cached != null) {
            return cached;
        }
        View view = uiFind(id);
        if (view == null) {
            return 0;
        }
        long handle = mJavaInterop.putForScript(view);
        mUiViewHandles.put(id, handle);
        return handle;
    }

    /** 页面桥引用（防 GC：WebView 内部对注入对象持弱引用）。 */
    private final java.util.Map<String, WebViewJsBridge> mWebViewBridges =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 把 JS 对象注册为 WebView 页面桥（脚本里的 `wv.addJavascriptInterface(obj, name)`）。
     * WebView 只能调用带 {@code @JavascriptInterface} 注解的方法（API 17+ 强制），
     * 所以用这个注解桥接；页面侧会由注入的 shim 把 window[name].xxx(...) 转发到 __ajs。
     */
    public boolean uiAddJsInterface(int viewId, String id, long callbackId, String name) {
        return Boolean.TRUE.equals(runOnMainSync(mDialogHandler, () -> {
            View view = uiFind(id);
            if (!(view instanceof android.webkit.WebView)) {
                return false;
            }
            WebViewJsBridge bridge = new WebViewJsBridge(callbackId);
            mWebViewBridges.put(name, bridge);
            ((android.webkit.WebView) view).addJavascriptInterface(bridge, name);
            return true;
        }, Boolean.FALSE));
    }

    /** WebView 页面 → 脚本对象的桥接体（方法需 @JavascriptInterface 注解）。 */
    private final class WebViewJsBridge {

        private final long mCallbackId;

        WebViewJsBridge(long callbackId) {
            mCallbackId = callbackId;
        }

        @android.webkit.JavascriptInterface
        public String __ajs(String method, String argsJson) {
            // 这个方法运行在 WebView 的 JavaScript 线程上，不能直接进 QuickJS 上下文
            // （引擎线程可能正在执行脚本），与页面注入桥一样只排队。
            try {
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("method", method);
                payload.put("args", new org.json.JSONArray(argsJson == null ? "[]" : argsJson));
                postJsCallback(mCallbackId, new org.json.JSONArray().put(payload).toString());
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "web bridge call failed: " + method, error);
            }
            return "";
        }
    }

    /**
     * `new JavaAdapter(Base, overrides)` 的宿主侧实现。
     *
     * <p>Rhino 会为任意基类动态生成子类字节码；QuickJS 里改为预置常用基类的转发子类
     * （WebView 脚本几乎只用这两个），不支持的基类返回空串让脚本侧抛明确错误。
     *
     * @return `{"__ref":handle,"__class":name}`；不支持的基类返回空串。
     */
    public String javaAdapterCreate(long baseHandle, long callbackId) {
        Object base = mJavaInterop.objectForHandle(baseHandle);
        if (!(base instanceof Class)) {
            return "";
        }
        Class<?> baseClass = (Class<?>) base;
        Object adapter;
        String className;
        if (android.webkit.WebViewClient.class.isAssignableFrom(baseClass)) {
            adapter = new AdapterWebViewClient(this, callbackId);
            className = "android.webkit.WebViewClient";
        } else if (android.webkit.WebChromeClient.class.isAssignableFrom(baseClass)) {
            adapter = new AdapterWebChromeClient(this, callbackId);
            className = "android.webkit.WebChromeClient";
        } else {
            return "";
        }
        long handle = mJavaInterop.putForScript(adapter);
        return "{\"__ref\":" + handle + ",\"__class\":\"" + className + "\"}";
    }

    /**
     * 适配器回调 → 脚本。WebView 回调在主线程，而 QuickJS 上下文只能在引擎线程进入，
     * 因此这里只排队（{@link #postJsCallback}），由 native 事件循环在引擎线程取出执行。
     */
    void postAdapterCallback(long callbackId, String method, Object... args) {
        try {
            org.json.JSONArray encodedArgs = new org.json.JSONArray();
            for (Object arg : args) {
                if (arg == null) {
                    encodedArgs.put(org.json.JSONObject.NULL);
                } else if (arg instanceof String || arg instanceof Number || arg instanceof Boolean) {
                    encodedArgs.put(arg);
                } else {
                    long handle = mJavaInterop.putForScript(arg);
                    encodedArgs.put(new org.json.JSONObject()
                            .put("__ref", handle)
                            .put("__class", arg.getClass().getName()));
                }
            }
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("method", method);
            payload.put("args", encodedArgs);
            postJsCallback(callbackId, new org.json.JSONArray().put(payload).toString());
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "adapter callback failed: " + method, error);
        }
    }

    /**
     * `android.webkit.JsResult` 的脚本侧代理。
     *
     * <p>为什么需要代理：JavaAdapter 回调是异步桥（见 {@link #postAdapterCallback}），
     * `onJsAlert` 只能同步返回 `true`（声称已接管），而页面的 JS 线程要等 JsResult 被
     * confirm/cancel 才会解冻。脚本一旦抛异常没调用到，整页就会永久失去响应（点击、
     * 切页全部失效），因此这里包一层记录“是否已处理”，配合
     * {@link #scheduleJsResultFallback} 兜底。
     */
    public static final class JsResultProxy {

        private final android.webkit.JsResult mResult;
        private boolean mHandled;

        JsResultProxy(android.webkit.JsResult result) {
            mResult = result;
        }

        /** 脚本侧 `result.confirm()`。 */
        public void confirm() {
            finish(true);
        }

        /** 脚本侧 `result.cancel()`。 */
        public void cancel() {
            finish(false);
        }

        /** 是否已被脚本处理（重复完成时以第一次为准）。 */
        public boolean isHandled() {
            synchronized (this) {
                return mHandled;
            }
        }

        private void finish(final boolean confirmed) {
            synchronized (this) {
                if (mHandled) {
                    return;
                }
                mHandled = true;
            }
            // 脚本侧可能在引擎线程调用，完成 JsResult 统一回主线程。
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    if (confirmed) {
                        mResult.confirm();
                    } else {
                        mResult.cancel();
                    }
                } catch (Throwable error) {
                    Log.w("QuickJsHostBridge", "完成 JsResult 失败", error);
                }
            });
        }
    }

    /**
     * JS 弹窗兜底：脚本侧若因异常没有调用 `confirm()/cancel()`，renderer 的 JS 线程会
     * 一直等待（现象就是 alert 之后整页失去响应），这里延迟替它确认一次。
     */
    void scheduleJsResultFallback(final JsResultProxy proxy) {
        mDialogHandler.postDelayed(() -> {
            if (!proxy.isHandled()) {
                Log.w("QuickJsHostBridge", "JS 弹窗未被脚本处理，已兜底 confirm()");
                proxy.confirm();
            }
        }, 1500);
    }

    /** `JavaAdapter(android.webkit.WebViewClient, {...})` 的宿主子类。 */
    private static final class AdapterWebViewClient extends android.webkit.WebViewClient {

        private final QuickJsHostBridge mBridge;
        private final long mCallbackId;

        AdapterWebViewClient(QuickJsHostBridge bridge, long callbackId) {
            mBridge = bridge;
            mCallbackId = callbackId;
        }

        @Override
        public void onPageStarted(android.webkit.WebView view, String url,
                                  android.graphics.Bitmap favicon) {
            mBridge.postAdapterCallback(mCallbackId, "onPageStarted", view, url, favicon);
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(android.webkit.WebView view, String url) {
            mBridge.postAdapterCallback(mCallbackId, "onPageFinished", view, url);
            super.onPageFinished(view, url);
        }
    }

    /** `JavaAdapter(android.webkit.WebChromeClient, {...})` 的宿主子类。 */
    private static final class AdapterWebChromeClient extends android.webkit.WebChromeClient {

        private final QuickJsHostBridge mBridge;
        private final long mCallbackId;

        AdapterWebChromeClient(QuickJsHostBridge bridge, long callbackId) {
            mBridge = bridge;
            mCallbackId = callbackId;
        }

        @Override
        public boolean onJsAlert(android.webkit.WebView view, String url, String message,
                                 android.webkit.JsResult result) {
            // 页面 alert 一律交给脚本处理（脚本常用它做 ajs:// 消息通道），异步桥表达不了
            // 同步返回值，因此统一按“已被脚本处理”返回；由 JsResultProxy + 兜底保证
            // 一定会被完成，避免页面 JS 线程永久等待。
            JsResultProxy proxy = new JsResultProxy(result);
            mBridge.postAdapterCallback(mCallbackId, "onJsAlert", view, url, message, proxy);
            mBridge.scheduleJsResultFallback(proxy);
            return true;
        }

        @Override
        public boolean onJsConfirm(android.webkit.WebView view, String url, String message,
                                   android.webkit.JsResult result) {
            JsResultProxy proxy = new JsResultProxy(result);
            mBridge.postAdapterCallback(mCallbackId, "onJsConfirm", view, url, message, proxy);
            mBridge.scheduleJsResultFallback(proxy);
            return true;
        }
    }

    /**
     * 在主线程执行并等待结果；**已在主线程时直接执行**——
     * 否则 `runOnMainThread` 回调里再调用这些接口会 post + 等 latch 自锁。
     */
    private static <T> T runOnMainSync(Handler handler, java.util.function.Supplier<T> task, T fallback) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return task.get();
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "main task failed", error);
                return fallback;
            }
        }
        final Object[] result = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> {
            try {
                result[0] = task.get();
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "main task failed", error);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                return fallback;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return fallback;
        }
        //noinspection unchecked
        return result[0] == null ? fallback : (T) result[0];
    }

    /** `ui.isUiThread()`。 */
    public boolean uiIsMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** `ui.statusBarColor(color)`：设置当前 Activity 的状态栏颜色。 */
    public void statusBarColor(String color) {
        if (color == null || color.isEmpty()) {
            return;
        }
        final int resolved;
        try {
            resolved = android.graphics.Color.parseColor(color);
        } catch (IllegalArgumentException error) {
            return;
        }
        mDialogHandler.post(() -> {
            try {
                android.app.Activity activity = mRuntime.app.getCurrentActivity();
                if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    activity.getWindow().setStatusBarColor(resolved);
                }
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot set status bar color", error);
            }
        });
    }

    private com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater uiInflater() {
        if (mUiInflater == null) {
            com.stardust.autojs.core.ui.inflater.ResourceParser parser =
                    new com.stardust.autojs.core.ui.inflater.ResourceParser(
                            new com.stardust.autojs.core.ui.inflater.util.Drawables());
            mUiInflater = new com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater(parser);
            mUiInflater.setContext(mRuntime.uiHandler.getContext());
            // 列表支持（与 Rhino UI 相同注册方式）
            mUiInflater.registerViewAttrSetter(com.stardust.autojs.core.ui.widget.JsListView.class.getName(),
                    new com.stardust.autojs.core.ui.inflater.inflaters.JsListViewInflater(parser, mRuntime));
            mUiInflater.registerViewAttrSetter(com.stardust.autojs.core.ui.widget.JsGridView.class.getName(),
                    new com.stardust.autojs.core.ui.inflater.inflaters.JsGridViewInflater(parser, mRuntime));
            // canvas 控件（XML `<canvas>`）：Rhino 的 UI.java 里注册了 CanvasViewInflater，
            // QuickJS 侧之前漏了。漏注册时会走「标签名反射 + getConstructor(Context)」的兜底路径，
            // 而 ScriptCanvasView 只有 (Context, ScriptRuntime) 构造 → NoSuchMethodException，
            // 脚本只看到 `Unable to create floaty window`（用户脚本「十字坐标线.js」就是这么挂的）。
            mUiInflater.registerViewAttrSetter(com.stardust.autojs.core.graphics.ScriptCanvasView.class.getName(),
                    new com.stardust.autojs.core.ui.inflater.inflaters.CanvasViewInflater(parser, mRuntime));
        }
        return mUiInflater;
    }

    /**
     * 与 Rhino 的 {@code runtime.ui.layoutInflater.setContext(...)} 对应：
     * 固定缓存一个 inflater，但每次按当前场景换上下文（Activity / 应用上下文）。
     */
    private com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater uiInflaterFor(
            android.content.Context context) {
        com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater inflater = uiInflater();
        inflater.setContext(context);
        return inflater;
    }

    /**
     * overlay 根视图没有背景时补一个「脚本窗口主题」的背景（windowBackground）。
     *
     * <p>为什么必须补：直接 addView 到 WindowManager 的根 View 如果**没有背景**，窗口 surface
     * 在没有任何 View 绘制的区域不会每帧清屏，于是上一帧（初始布局/上一版文本）的像素会残留在
     * 屏幕上，看起来就是「两层界面叠在一起」——QuickJS 真机回归里「控件代理示例」标题两层、
     * YOLO 列表行和按钮列错位都是这个鬼影。
     *
     * <p>Rhino 的 ui.layout 走 Activity，DecorView 自带 windowBackground，所以没有这个问题；
     * 这里按同样的 ScriptTheme windowBackground 补上，视觉也与 Rhino 的脚本界面一致。
     * 脚本自己在 XML 根节点写了 {@code bg} 时保留脚本的背景，不覆盖。
     */
    private static void ensureOverlayRootBackground(View root, android.content.Context themedContext) {
        if (root == null || root.getBackground() != null) {
            return;
        }
        try {
            android.util.TypedValue value = new android.util.TypedValue();
            if (themedContext != null && themedContext.getTheme().resolveAttribute(
                    android.R.attr.windowBackground, value, true)) {
                if (value.resourceId != 0) {
                    root.setBackgroundResource(value.resourceId);
                    return;
                }
                if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    root.setBackgroundColor(value.data);
                    return;
                }
            }
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot resolve window background for UI overlay", error);
        }
        // ScriptTheme 是 AppCompat.Light：兜底白色，至少保证整窗每帧被绘制、不再残留鬼影。
        root.setBackgroundColor(android.graphics.Color.WHITE);
    }

    /**
     * overlay 窗口根容器：接返回键 + 承载右上角关闭按钮。
     *
     * <p>为什么需要：QuickJS 的 ui.layout 是系统悬浮窗（不是 Activity），BACK 不会被任何
     * Activity 的默认行为处理；全屏 UI 又没有关闭入口时，用户只能 stopAll/杀进程退出
     * （dev-972 真机回归）。这里让根容器可获焦并在 {@link #dispatchKeyEvent} 里吃掉 BACK，
     * 关闭窗口并结束脚本。
     */
    private final class UiOverlayRoot extends android.widget.FrameLayout {

        UiOverlayRoot(android.content.Context context) {
            super(context);
            // 可获焦：WindowManager 只把按键派发给有焦点的窗口；根 View 不可获焦时 BACK 收不到。
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(android.view.KeyEvent event) {
            int keyCode = event.getKeyCode();
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) {
                    // 音量上键 = 停止全部脚本：窗口聚焦路径不依赖无障碍/root（HyperOS 上无障碍
                    // 设置会被系统重置，全局监听收不到键）。先跑宿主停止 hook + 主进程 stopAll，
                    // 再关闭 overlay（requestUiOverlayClose 里的 forceStop 幂等）。
                    Log.i("QuickJsHostBridge", "UI overlay volume-up: stop all scripts");
                    try {
                        com.stardust.autojs.ScriptEngineService.stopAllByVolumeUp();
                    } catch (Throwable error) {
                        Log.w("QuickJsHostBridge", "UI overlay volume-up stop-all failed", error);
                    }
                    requestUiOverlayClose("volume");
                }
                // 按下/抬起都吃掉，避免同时改变系统音量。
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) {
                    requestUiOverlayClose(keyCode == android.view.KeyEvent.KEYCODE_BACK
                            ? "back" : "escape");
                }
                // 按下/抬起都吃掉：避免 BACK 继续传给下层窗口/系统（以前按返回键毫无反应）。
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }

    /** 状态栏高度（拿不到时按 24dp 估）：overlay 是 FLAG_LAYOUT_IN_SCREEN，关闭按钮要避开状态栏。 */
    private static int statusBarHeight(android.content.Context context) {
        try {
            if (context != null) {
                int id = context.getResources().getIdentifier(
                        "status_bar_height", "dimen", "android");
                if (id > 0) {
                    return context.getResources().getDimensionPixelSize(id);
                }
            }
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot resolve status bar height", error);
        }
        return Math.round(displayMetrics(context).density * 24);
    }

    /** 右上角默认关闭按钮（脚本 XML 自带的关闭按钮照旧保留；这只是保底退出通道）。 */
    private android.widget.TextView createOverlayCloseButton(android.content.Context context) {
        android.widget.TextView button = new android.widget.TextView(context);
        button.setText("✕");
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        button.setTextColor(0xFF444444);
        button.setGravity(android.view.Gravity.CENTER);
        button.setContentDescription("关闭脚本界面");
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        background.setColor(0xE6FFFFFF);
        background.setStroke(Math.max(1, Math.round(displayMetrics(context).density)),
                0x66000000);
        button.setBackground(background);
        button.setOnClickListener(v -> requestUiOverlayClose("button"));
        return button;
    }

    /**
     * 用户主动关闭 overlay（返回键 / 右上角 ✕）：<b>先关窗口，再结束脚本</b>。
     *
     * <p>等价于 Rhino 的 {@code ui.finish()}（关界面 = 结束脚本）：QuickJS overlay 没有
     * Activity，所以实现成 uiClose() + 引擎 forceStop()——和 stopAll/通知栏「停止」同一条
     * 链路（forceStop -> doExecution 中断 -> engine.destroy -> bridge.close -> uiClose，
     * uiClose 幂等），保证既不会残留窗口、也不会留下后台脚本继续跑。
     */
    private void requestUiOverlayClose(String reason) {
        Log.i("QuickJsHostBridge", "UI overlay close requested: " + reason);
        if (mUiCloseRequested) {
            return;
        }
        mUiCloseRequested = true;
        uiClose();
        try {
            mRuntime.uiHandler.toast("已关闭脚本界面");
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot toast UI close", error);
        }
        mDialogHandler.post(() -> {
            try {
                JavaScriptEngine engine = mRuntime.engines.myEngine();
                if (engine != null) {
                    engine.forceStop();
                } else {
                    // 极端情况（引擎尚未注册完成）：直接走 runtime 的退出逻辑兜底。
                    mRuntime.exit();
                }
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot stop script after UI close", error);
            }
        });
    }

    public String uiInflate(String xml) {
        // `"ui"` 模式的脚本跑在 ScriptExecuteActivity 里：界面就是 Activity 的内容视图
        // （按 Home 退到后台、返回键才结束），也不需要「显示在其他应用上层」权限。
        final android.app.Activity host = mUiHostActivity;
        if (host != null) {
            return uiInflateIntoActivity(host, xml);
        }
        // 其余场景（普通脚本用 ui.layout 做覆盖层）保持系统悬浮窗方案。
        // 没有「显示在其他应用上层」权限时 addView 会以 BadTokenException 失败，
        // 脚本只会看到「Unable to inflate UI layout」这种误导性错误。这里提前检查并引导授权。
        final android.content.Context appContext = mRuntime.uiHandler.getContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(appContext)) {
            Log.w("QuickJsHostBridge",
                    "Cannot inflate UI layout: overlay permission not granted");
            mDialogHandler.post(() -> {
                try {
                    android.content.Intent intent = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + appContext.getPackageName()));
                    android.app.Activity activity = mRuntime.app.getCurrentActivity();
                    if (activity != null) {
                        activity.startActivity(intent);
                    } else {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        appContext.startActivity(intent);
                    }
                    android.widget.Toast.makeText(appContext,
                            "脚本界面需要「显示在其他应用上层」权限，请在打开的页面中开启后重新运行",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable error) {
                    Log.w("QuickJsHostBridge", "Cannot open overlay permission page", error);
                }
            });
            // 不能只返回 -1：脚本侧会把它翻成「Unable to inflate UI layout」，用户既看不出
            // 界面为什么不出现，也不知道还有第二条路（写成 "ui"; 就根本不需要这个权限）。
            //
            // ⚠️ 文案必须短：QuickJS 的 JS_ThrowError2 是 vsnprintf 进 char buf[256]，
            // 超长会被硬截断（还会把多字节汉字切成半个）——曾经把「两种改法」整段切掉，
            // 用户只看到诊断、看不到怎么改。详细说明走日志，脚本里只留一句能照做的。
            Log.w("QuickJsHostBridge", "UI layout could not be shown: the script uses ui.layout "
                    + "without a leading \"ui\";, so the layout needs the draw-over-other-apps "
                    + "permission, which this app does not have. Fix: add \"ui\"; as the first "
                    + "line (recommended), or grant the overlay permission.");
            throw new QuickJsException(
                    "脚本界面没出来：首行加 \"ui\"; 即可（否则 ui.layout 需要「显示在其他应用上层」权限）");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        // 与 Rhino 的 __ui__.js 保持一致：没有 Activity 时用 ScriptTheme 作为 inflate 上下文
        // （控件默认样式/文字颜色/窗口背景都按脚本 UI 主题走，而不是 App 的 Flutter 主题）。
        final android.content.Context overlayContext = new android.view.ContextThemeWrapper(
                appContext, com.stardust.autojs.R.style.ScriptTheme);
        mDialogHandler.post(() -> {
            try {
                View content = uiInflaterFor(overlayContext).inflate(xml);
                WindowManager windowManager =
                        (WindowManager) mRuntime.uiHandler.getContext()
                                .getSystemService(Context.WINDOW_SERVICE);
                // 旧的覆盖层先摘掉：以前这里直接 addView 新根视图，重复 ui.layout（或上一次脚本
                // 的窗口还没关）时两个窗口叠在一起，看起来就是「标题/按钮互相重叠」。
                View previous = mUiWindowRoot;
                if (previous != null && mUiShown) {
                    try {
                        windowManager.removeViewImmediate(previous);
                    } catch (Throwable error) {
                        Log.w("QuickJsHostBridge", "Cannot remove previous UI layout", error);
                    }
                }
                // 跨脚本/跨 run 的遗留覆盖窗一起摘掉（同一时刻只保留最新一次 QuickJS UI overlay）。
                View staleOverlay = sUiOverlayRoot;
                if (staleOverlay != null && staleOverlay != previous) {
                    try {
                        windowManager.removeViewImmediate(staleOverlay);
                    } catch (Throwable error) {
                        Log.w("QuickJsHostBridge", "Cannot remove stale UI layout", error);
                    }
                }
                closeCanvasViews(previous);
                closeCanvasViews(staleOverlay);
                // overlay 窗口根：整窗主题背景（防首帧残影）+ 返回键处理 + 右上角关闭按钮。
                // 内容视图单独放里面，uiFind/ui.<id> 仍以内容视图为根，控件代理不受影响。
                UiOverlayRoot container = new UiOverlayRoot(overlayContext);
                ensureOverlayRootBackground(container, overlayContext);
                container.addView(content, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                android.widget.TextView closeButton = createOverlayCloseButton(overlayContext);
                float density = displayMetrics(overlayContext).density;
                int closeSize = Math.round(density * 44);
                int closeMargin = Math.round(density * 8);
                android.widget.FrameLayout.LayoutParams closeParams =
                        new android.widget.FrameLayout.LayoutParams(closeSize, closeSize,
                                android.view.Gravity.TOP | android.view.Gravity.END);
                // overlay 窗口是 FLAG_LAYOUT_IN_SCREEN 的，y=0 会被状态栏盖住；标到状态栏下方。
                closeParams.topMargin = closeMargin + statusBarHeight(overlayContext);
                closeParams.rightMargin = closeMargin;
                container.addView(closeButton, closeParams);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.OPAQUE);
                params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                params.x = 0;
                params.y = 0;
                windowManager.addView(container, params);
                mUiWindowRoot = container;
                mUiRoot = content;
                sUiOverlayRoot = container;
                mUiShown = true;
                mUiCloseRequested = false;
                mUiWindowId++;
                Log.i("QuickJsHostBridge", "UI overlay added: root="
                        + container.getClass().getName() + ", background="
                        + (container.getBackground() != null)
                        + ", children=" + container.getChildCount()
                        + ", closeButton=true");
                // overlay 窗口默认焦点在关闭按钮/容器上，返回键事件才能进 dispatchKeyEvent。
                container.requestFocus();
                result[0] = String.valueOf(mUiWindowId);
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot inflate UI layout", error);
                result[0] = "-1";
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                return "-1";
            }
        } catch (InterruptedException ignored) {
            return "-1";
        }
        return result[0];
    }

    private View uiFind(String id) {
        View root = mUiRoot;
        if (root == null) {
            return null;
        }
        int rid = com.stardust.autojs.core.ui.inflater.util.Ids.parse(id);
        return root.findViewById(rid);
    }

    /** 把界面挂到宿主 Activity 的内容视图（`"ui"` 模式）。已在主线程时直接执行，避免自锁。 */
    private String uiInflateIntoActivity(final android.app.Activity host, String xml) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return inflateIntoActivityNow(host, xml);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        host.runOnUiThread(() -> {
            result[0] = inflateIntoActivityNow(host, xml);
            latch.countDown();
        });
        try {
            if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                return "-1";
            }
        } catch (InterruptedException ignored) {
            return "-1";
        }
        return result[0];
    }

    private String inflateIntoActivityNow(android.app.Activity host, String xml) {
        try {
            View root = uiInflaterFor(host).inflate(xml);
            closeCanvasViews(mUiRoot);
            // 脚本从 overlay 模式切到 Activity UI（或上一次 run 的覆盖窗还在）：先摘掉旧窗口，
            // 否则覆盖窗会盖在 Activity 内容上，看起来就是两层界面重叠。
            View staleOverlay = sUiOverlayRoot;
            if (staleOverlay != null && staleOverlay != mUiWindowRoot) {
                try {
                    WindowManager windowManager = (WindowManager) host
                            .getSystemService(Context.WINDOW_SERVICE);
                    windowManager.removeViewImmediate(staleOverlay);
                } catch (Throwable error) {
                    Log.w("QuickJsHostBridge", "Cannot remove stale overlay layout", error);
                }
                closeCanvasViews(staleOverlay);
            }
            sUiOverlayRoot = null;
            mUiWindowRoot = null;
            mUiCloseRequested = false;
            mUiViewHandles.clear();
            host.setContentView(root);
            mUiRoot = root;
            mUiShown = true;
            mUiWindowId++;
            return String.valueOf(mUiWindowId);
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot inflate UI layout into activity", error);
            return "-1";
        }
    }

    /**
     * QuickJS 公开 {@code ui.inflate(xml, parent, attachToParent)} 的宿主实现：
     * 与 Rhino 的 {@code __ui__.js} / Node 的 {@code ui.inflate} 对齐，真正 inflate 出 View，
     * 但不像 {@link #uiInflate(String)} 那样把界面 show/attach 到内容视图。
     *
     * <p>parent 可以是控件 id 字符串（asset 层传 {@code ui.xxx.__id}）或 Java View/ViewGroup
     * 引用（上一次 {@code ui.inflate} 返回的 View）；{@code attachToParent} 为真且确实有父容器时
     * 才 addView，否则只按父容器生成 LayoutParams（Android LayoutInflater 的 attachToRoot 语义）。
     */
    public View uiInflateView(final String xml, final Object parent, final boolean attachToParent) {
        if (xml == null || xml.trim().isEmpty()) {
            throw new QuickJsException("ui.inflate 需要非空的 XML 布局字符串");
        }
        // 与 Rhino 的 __ui__.js 一致：有宿主 Activity 时用它，否则用 ScriptTheme 的 ContextThemeWrapper。
        final Context context = mUiHostActivity != null
                ? mUiHostActivity
                : new android.view.ContextThemeWrapper(mRuntime.uiHandler.getContext(),
                        com.stardust.autojs.R.style.ScriptTheme);
        final View[] result = new View[1];
        final Throwable[] failure = new Throwable[1];
        runOnMainOrThrow(() -> {
            try {
                android.view.ViewGroup parentGroup = resolveInflateParent(parent);
                result[0] = uiInflaterFor(context).inflate(xml, parentGroup, attachToParent);
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) {
            Log.w("QuickJsHostBridge", "ui.inflate failed", failure[0]);
            String message = failure[0].getMessage();
            if (message == null || message.isEmpty()) {
                message = failure[0].getClass().getSimpleName();
            }
            throw new QuickJsException("ui.inflate 失败：" + message);
        }
        return result[0];
    }

    /**
     * 解析 {@code ui.inflate} 的 parent：控件 id 字符串 → 当前界面根下的原生 View；Java 引用直接用。
     * 不是 ViewGroup / 找不到时给出可读错误，而不是让 inflater 抛空指针。
     */
    private android.view.ViewGroup resolveInflateParent(Object parent) {
        if (parent == null) {
            return null;
        }
        View view;
        if (parent instanceof View) {
            view = (View) parent;
        } else if (parent instanceof CharSequence) {
            String id = parent.toString().trim();
            if (id.isEmpty()) {
                return null;
            }
            view = uiFind(id);
            if (view == null) {
                throw new IllegalStateException("找不到 parent 控件：" + id);
            }
        } else {
            throw new IllegalArgumentException("parent 必须是控件 id、View/ViewGroup 或 null");
        }
        if (!(view instanceof android.view.ViewGroup)) {
            throw new IllegalArgumentException("parent 必须是 ViewGroup：" + view.getClass().getName());
        }
        return (android.view.ViewGroup) view;
    }

    /** 主线程执行并等待；异常由调用方从 failure 里取（ui.inflate 的错误要让脚本看到，不能吞）。 */
    private void runOnMainOrThrow(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        mDialogHandler.post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new QuickJsException("ui.inflate 超时（主线程 10 秒未完成）");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new QuickJsException("ui.inflate 被中断");
        }
    }

    /** 宿主 Activity 的脚本侧句柄（脚本里的 `activity` 全局）；没有宿主时返回空串。 */
    public String hostActivityRef() {
        android.app.Activity host = mUiHostActivity;
        if (host == null) {
            return "";
        }
        long handle = mJavaInterop.putForScript(host);
        return "{\"__ref\":" + handle + ",\"__class\":\"" + host.getClass().getName() + "\"}";
    }

    public void uiClose() {
        closeCanvasViews(mUiRoot);
        final android.app.Activity host = mUiHostActivity;
        if (host != null) {
            // Activity 承载：关界面 = 结束这个脚本界面（Rhino 的 ui.finish 语义），
            // Activity 结束会连带停掉引擎，因此直接 finish 并由生命周期收尾。
            mUiRoot = null;
            mUiShown = false;
            mUiEvents.clear();
            mUiViewHandles.clear();
            mWebViewBridges.clear();
            mDialogHandler.post(() -> {
                try {
                    if (!host.isFinishing()) {
                        host.finish();
                    }
                } catch (Throwable error) {
                    Log.w("QuickJsHostBridge", "Cannot finish host activity", error);
                }
            });
            return;
        }
        mDialogHandler.post(() -> {
            // overlay 模式下窗口根是 UiOverlayRoot（含关闭按钮），内容视图只是它的子 View；
            // removeViewImmediate 必须摘窗口根，否则会不生效/抛出「View not attached」。
            View closingWindowRoot = mUiWindowRoot;
            View closingContentRoot = mUiRoot;
            try {
                if (closingWindowRoot != null && mUiShown) {
                    WindowManager windowManager =
                            (WindowManager) mRuntime.uiHandler.getContext()
                                    .getSystemService(Context.WINDOW_SERVICE);
                    windowManager.removeViewImmediate(closingWindowRoot);
                } else if (closingContentRoot != null && mUiShown) {
                    // 兼容：窗口根还没赋值的极端情况（例如 addView 前出错）。
                    WindowManager windowManager =
                            (WindowManager) mRuntime.uiHandler.getContext()
                                    .getSystemService(Context.WINDOW_SERVICE);
                    windowManager.removeViewImmediate(closingContentRoot);
                }
            } catch (Throwable ignored) {
            }
            if (sUiOverlayRoot == closingWindowRoot || sUiOverlayRoot == closingContentRoot) {
                sUiOverlayRoot = null;
            }
            closeCanvasViews(closingContentRoot);
            closeCanvasViews(closingWindowRoot);
            mUiWindowRoot = null;
            mUiRoot = null;
            mUiShown = false;
            mUiEvents.clear();
            mUiViewHandles.clear();
            mWebViewBridges.clear();
        });
    }

    public void uiSetConfig(int viewId, String id, String configJson) {
        Runnable apply = () -> {
            View view = uiFind(id);
            if (view == null) {
                return;
            }
            try {
                org.json.JSONObject config = new org.json.JSONObject(configJson);
                java.util.Iterator<String> keys = config.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object raw = config.opt(key);
                    String value = raw == null ? "" : String.valueOf(raw);
                    // 与 Rhino 的 NativeView 一样走 ViewAttributes，外加常用控件方法兜底。
                    if (!writeViewAttribute(view, key, value, mRuntime.ui.getResourceParser())) {
                        Log.w("QuickJsHostBridge", "UI 不支持的属性：" + key);
                    }
                }
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot update UI view " + id, error);
            }
        };
        // ui.run really executes on main. Apply there before an immediate getter;
        // posting again would leave the old value visible inside the same callback.
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run();
        else mDialogHandler.post(apply);
    }

    public String uiGetText(int viewId, String id) {
        String text = runOnMainSync(mDialogHandler, () -> {
            View view = uiFind(id);
            return view instanceof android.widget.TextView
                    ? ((android.widget.TextView) view).getText().toString() : "";
        }, "");
        return text == null ? "" : text;
    }

    public void uiSetClickListener(int viewId, String id) {
        mDialogHandler.post(() -> {
            View view = uiFind(id);
            if (view == null) {
                return;
            }
            if (view instanceof com.stardust.autojs.core.ui.widget.JsListView) {
                ((com.stardust.autojs.core.ui.widget.JsListView) view).setOnItemTouchListener(
                        new com.stardust.autojs.core.ui.widget.JsListView.OnItemTouchListener() {
                            @Override
                            public void onItemClick(com.stardust.autojs.core.ui.widget.JsListView listView,
                                                   View itemView, Object item, int pos) {
                                emitUiEvent(id, "item_click", pos);
                            }

                            @Override
                            public boolean onItemLongClick(com.stardust.autojs.core.ui.widget.JsListView listView,
                                                           View itemView, Object item, int pos) {
                                emitUiEvent(id, "item_long_click", pos);
                                return true;
                            }
                        });
                return;
            }
            view.setClickable(true);
            view.setOnClickListener(v -> {
                try {
                    mUiEvents.add(new JSONObject()
                            .put("id", id).put("event", "click").toString());
                } catch (JSONException ignored) {
                }
            });
        });
    }

    public void uiSetDataSource(int viewId, String id, String dataJson) {
        mDialogHandler.post(() -> {
            View view = uiFind(id);
            if (!(view instanceof com.stardust.autojs.core.ui.widget.JsListView)) {
                return;
            }
            try {
                JSONArray arr = new JSONArray(dataJson);
                java.util.List<Object> data = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    data.add(arr.get(i));
                }
                com.stardust.autojs.core.ui.widget.JsListView listView =
                        (com.stardust.autojs.core.ui.widget.JsListView) view;
                // 项目未配置默认 DataSourceAdapter，这里为 QuickJS 列表直接接入
                listView.setDataSourceAdapter(new QuickJsListAdapter());
                listView.setDataSource(data);
            } catch (Throwable ignored) {
            }
        });
    }

    private static final class QuickJsListAdapter
            implements com.stardust.autojs.core.ui.widget.JsListView.DataSourceAdapter {
        @Override
        public int getItemCount(Object dataSource) {
            return dataSource instanceof java.util.List ? ((java.util.List<?>) dataSource).size() : 0;
        }

        @Override
        public Object getItem(Object dataSource, int i) {
            if (!(dataSource instanceof java.util.List)) {
                return null;
            }
            java.util.List<?> list = (java.util.List<?>) dataSource;
            return i >= 0 && i < list.size() ? list.get(i) : null;
        }

        @Override
        public void setDataSource(Object dataSource) {
        }
    }

    private void emitUiEvent(String id, String event, int index) {
        try {
            mUiEvents.add(new JSONObject()
                    .put("id", id).put("event", event).put("index", index).toString());
        } catch (JSONException ignored) {
        }
    }

    public String uiPollEvent() {
        String event = mUiEvents.poll();
        return event == null ? "" : event;
    }

    public String uiGetAttr(int viewId, String id, String name) {
        return runOnMainSync(mDialogHandler, () -> {
            String value = readViewAttribute(uiFind(id), name, mRuntime.ui.getResourceParser());
            return value == null ? "" : value;
        }, "");
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<String> mUiEvents =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final AtomicLong mNextEngineHandle = new AtomicLong(1);
    private final Map<Long, com.stardust.autojs.execution.ScriptExecution> mEngineSessions =
            new ConcurrentHashMap<>();
    private final Map<Long, EngineResultState> mEngineResults = new ConcurrentHashMap<>();

    private static final class EngineResultState {
        final CountDownLatch finished = new CountDownLatch(1);
        volatile String status = "running";
        volatile Object value;
        volatile String error = "";

        void succeed(Object result) {
            value = result;
            status = "success";
            finished.countDown();
        }

        void fail(Throwable failure) {
            error = failure == null ? "Unknown script error" : Log.getStackTraceString(failure);
            status = "error";
            finished.countDown();
        }

        String toJson() {
            try {
                JSONObject json = new JSONObject().put("status", status);
                if ("success".equals(status)) {
                    Object encoded = value;
                    if (encoded == null) encoded = JSONObject.NULL;
                    else if (!(encoded instanceof String) && !(encoded instanceof Number)
                            && !(encoded instanceof Boolean) && !(encoded instanceof JSONObject)
                            && !(encoded instanceof JSONArray)) {
                        encoded = String.valueOf(encoded);
                    }
                    json.put("value", encoded);
                } else if ("error".equals(status)) {
                    json.put("error", error);
                }
                return json.toString();
            } catch (JSONException impossible) {
                return "{\"status\":\"error\",\"error\":\"Cannot encode result\"}";
            }
        }
    }

    // ------------------------------------------------------------------
    // 控件选择器 / UiObject（Auto.js 4.x 兼容）
    //
    // 复用 modules/automator 的同一套实现，find / waitFor / 控件动作的语义与 Rhino 完全一致。
    // 选择器、UiObject、UiObjectCollection 在 Java 侧各对应一个 long 句柄，JS 侧拿到的也只是
    // 句柄；脚本能调到的方法名限定在下面几张白名单表里，不向脚本暴露任意反射。
    // ------------------------------------------------------------------

    private static final String AUTOMATOR_VOID_RESULT = "{\"t\":\"v\"}";
    private static final Class<?>[] NO_PARAMS = new Class<?>[0];
    private static final Object[] NO_ARGS = new Object[0];
    /** 句柄上限：长时间 find 会不断产出新句柄，到顶就整体丢弃（后续调用会明确报句柄失效）。 */
    private static final int MAX_AUTOMATOR_HANDLES = 8192;

    /** 字符串参数的过滤器，返回自身可链式。 */
    private static final String[] SELECTOR_STRING_FILTERS = {
            "text", "textContains", "textStartsWith", "textEndsWith", "textMatches",
            "id", "idContains", "idStartsWith", "idEndsWith", "idMatches",
            "desc", "descContains", "descStartsWith", "descEndsWith", "descMatches",
            "className", "classNameContains", "classNameStartsWith", "classNameEndsWith", "classNameMatches",
            "packageName", "packageNameContains", "packageNameStartsWith", "packageNameEndsWith",
            "packageNameMatches", "algorithm",
    };

    /** int 参数的过滤器。 */
    private static final String[] SELECTOR_INT_FILTERS = {
            "drawingOrder", "depth", "row", "rowCount", "rowSpan", "column", "columnCount", "columnSpan",
            "indexInParent",
    };

    /** 布尔过滤器：带参数时调布尔重载，不带参数时调无参重载。 */
    private static final String[] SELECTOR_BOOL_FILTERS = {
            "checkable", "checked", "focusable", "focused", "visibleToUser", "accessibilityFocused", "selected",
            "clickable", "longClickable", "enabled", "password", "scrollable", "editable", "contentInvalid",
            "contextClickable", "multiLine", "dismissable",
    };

    private static final String[] SELECTOR_BOUNDS_FILTERS = { "bounds", "boundsInside", "boundsContains" };

    /** UiObject / UiObjectCollection 共有的动作（无参、返回布尔）。 */
    private static final String[] NODE_ACTIONS = {
            "click", "clickCenter", "longClick", "accessibilityFocus", "clearAccessibilityFocus", "focus", "clearFocus",
            "copy", "paste", "select", "cut", "collapse", "expand", "dismiss", "show",
            "scrollForward", "scrollBackward", "scrollUp", "scrollDown", "scrollLeft", "scrollRight",
            "contextClick",
    };

    private static final String[] OBJECT_STRING_PROPERTIES = { "text", "desc", "id", "className", "packageName" };

    private static final String[] OBJECT_INT_PROPERTIES = {
            "depth", "drawingOrder", "indexInParent", "childCount", "row", "column", "rowSpan", "columnSpan",
            "rowCount", "columnCount",
    };

    private static final String[] OBJECT_BOOL_PROPERTIES = {
            "checkable", "checked", "focusable", "focused", "visibleToUser", "accessibilityFocused", "selected",
            "clickable", "longClickable", "enabled", "password", "scrollable", "editable", "contextClickable", "dismissable", "contentInvalid", "multiLine", "isHierarchically",
    };

    private final AtomicLong mNextAutomatorHandle = new AtomicLong(1);
    private final Map<Long, Object> mAutomatorHandles = new ConcurrentHashMap<>();
    private final Map<String, Method> mAutomatorMethods = new ConcurrentHashMap<>();

    public long selectorCreate() {
        if (RemoteAutomationBridge.isActive()) {
            // task-153：:script 进程的 QuickJS 选择器链走主进程 RemoteAutomationBridge，
            // 与 Rhino 的 ScriptRuntime.selector() -> RemoteUiSelector 同一条链路。
            return putAutomatorHandle(new RemoteUiSelector());
        }
        mRuntime.accessibilityBridge.ensureServiceEnabled();
        return putAutomatorHandle(new UiSelector(mRuntime.accessibilityBridge));
    }

    /**
     * `auto` 对象（Rhino `__automator__.js` 的子集）：服务确保/等待、模式与标志位、
     * 以及当前窗口根节点（返回 UiObject 句柄，交给 JS 包装层）。
     */
    public String autoCall(String method, int value) throws JSONException {
        switch (method == null ? "" : method) {
            case "ensure":
                mRuntime.accessibilityBridge.ensureServiceEnabled();
                return AUTOMATOR_VOID_RESULT;
            case "waitFor":
                mRuntime.accessibilityBridge.waitForServiceEnabled();
                return AUTOMATOR_VOID_RESULT;
            case "setMode":
                mRuntime.accessibilityBridge.setMode(value);
                return AUTOMATOR_VOID_RESULT;
            case "setFlags":
                mRuntime.accessibilityBridge.setFlags(value);
                return AUTOMATOR_VOID_RESULT;
            case "serviceReady":
                return encodeAutomatorResult(RemoteAutomationBridge.isActive()
                        ? RemoteAutomationBridge.requireBackend().isReady()
                        : mRuntime.accessibilityBridge.getService() != null);
            case "rootActive":
            case "rootCurrent": {
                if (RemoteAutomationBridge.isActive()) return encodeAutomatorResult(new RemoteUiSelector().root());
                AccessibilityNodeInfo root = "rootActive".equals(method)
                        ? mRuntime.accessibilityBridge.getRootInActiveWindow()
                        : mRuntime.accessibilityBridge.getRootInCurrentWindow();
                return root == null
                        ? encodeAutomatorResult(null)
                        : encodeAutomatorResult(UiObject.Companion.createRoot(root));
            }
            default:
                throw new IllegalArgumentException("不支持的 auto 方法：" + method);
        }
    }

    /**
     * 选择器 / UiObject / UiObjectCollection 的统一调用入口。
     * 返回 JSON：{t:"v"} 无返回值、{t:"n"} null、{t:"b|i|s"} 基本类型、
     * {t:"r",l,t,r,b} 矩形、{t:"h",k:"o|c",v:handle} 控件或集合句柄。
     */
    public String automatorCall(long handle, String method, String argsJson) throws Exception {
        Object target = mAutomatorHandles.get(handle);
        if (target == null) {
            throw new IllegalStateException("自动控制句柄已失效：" + handle);
        }
        JSONArray args = new JSONArray(argsJson == null || argsJson.isEmpty() ? "[]" : argsJson);
        // task-153：远程自动化（:script）节点/集合与本地类型不同，走 remote 适配分支。
        if (target instanceof RemoteUiSelector) {
            return callRemoteSelector((RemoteUiSelector) target, method, args);
        }
        if (target instanceof RemoteUiObject) {
            return callRemoteObject((RemoteUiObject) target, method, args);
        }
        if (target instanceof RemoteUiObjectCollection) {
            return callRemoteCollection((RemoteUiObjectCollection) target, method, args);
        }
        if (target instanceof UiObjectCollection) {
            return callCollection((UiObjectCollection) target, method, args);
        }
        if (target instanceof UiObject) {
            return callObject((UiObject) target, method, args);
        }
        if (target instanceof UiGlobalSelector) {
            return callSelector((UiGlobalSelector) target, method, args);
        }
        throw new IllegalStateException("未知的自动控制句柄类型");
    }

    // ---- task-153：RemoteUiSelector / RemoteUiObject / 集合适配（:script 进程） ----
    private Object remoteHandleArg(JSONArray args, int index) throws Exception {
        if (args.length() <= index || args.isNull(index)) return null;
        Object value = args.get(index);
        if (value instanceof Number) return mAutomatorHandles.get(((Number) value).longValue());
        return value;
    }

    private String callRemoteSelector(RemoteUiSelector selector, String method, JSONArray args) throws Exception {
        if (namesContain(SELECTOR_STRING_FILTERS, method)) {
            invokeAutomator(selector, method, new Class<?>[]{ String.class }, new Object[]{ args.getString(0) });
            return AUTOMATOR_VOID_RESULT;
        }
        if (namesContain(SELECTOR_INT_FILTERS, method)) {
            invokeAutomator(selector, method, new Class<?>[]{ int.class }, new Object[]{ args.getInt(0) });
            return AUTOMATOR_VOID_RESULT;
        }
        if (namesContain(SELECTOR_BOOL_FILTERS, method)) {
            if (args.length() > 0 && !args.isNull(0)) {
                invokeAutomator(selector, method, new Class<?>[]{ boolean.class }, new Object[]{ args.getBoolean(0) });
            } else {
                invokeAutomator(selector, method, NO_PARAMS, NO_ARGS);
            }
            return AUTOMATOR_VOID_RESULT;
        }
        if (namesContain(SELECTOR_BOUNDS_FILTERS, method)) {
            invokeAutomator(selector, method, new Class<?>[]{ int.class, int.class, int.class, int.class },
                    new Object[]{ args.getInt(0), args.getInt(1), args.getInt(2), args.getInt(3) });
            return AUTOMATOR_VOID_RESULT;
        }
        if (namesContain(NODE_ACTIONS, method)) return encodeAutomatorResult(invokeAutomator(selector, method, NO_PARAMS, NO_ARGS));
        if ("setSelection".equals(method) || "scrollTo".equals(method)) {
            NodeActionArguments.validate(method, args);
            return encodeAutomatorResult(invokeAutomator(selector, method,
                    new Class<?>[]{int.class, int.class},
                    new Object[]{args.getInt(0), args.getInt(1)}));
        }
        if ("setProgress".equals(method)) {
            NodeActionArguments.validate(method, args);
            return encodeAutomatorResult(selector.setProgress((float) args.getDouble(0)));
        }
        switch (method) {
            case "filter":
            case "addFilter": {
                final long callbackId=args.getLong(0);
                selector.filter(node -> evaluateJsNodePredicate(callbackId,node));
                return AUTOMATOR_VOID_RESULT;
            }

            case "find":
                return encodeAutomatorResult(args.length() > 0 && !args.isNull(0)
                        ? selector.find(args.getInt(0)) : selector.find());
            case "findOnce":
                return encodeAutomatorResult(args.length() > 0 && !args.isNull(0)
                        ? selector.findOnce(args.getInt(0)) : selector.findOnce());
            case "findOne":
                if (args.length() == 0 || args.isNull(0)) return encodeAutomatorResult(selector.findOne());
                if (args.length() == 1) return encodeAutomatorResult(selector.findOne(args.getLong(0)));
                return encodeAutomatorResult(selector.findOne(args.getLong(0), args.getLong(1)));
            case "untilFindOne":
                return encodeAutomatorResult(selector.untilFindOne());
            case "untilFind":
                if (args.length() == 0 || args.isNull(0)) return encodeAutomatorResult(selector.untilFind());
                if (args.length() == 1) return encodeAutomatorResult(selector.untilFind(args.getLong(0)));
                return encodeAutomatorResult(selector.untilFind(args.getLong(0), args.getLong(1)));
            case "exists":
                return encodeAutomatorResult(selector.exists());
            case "waitFor":
                selector.waitFor();
                return AUTOMATOR_VOID_RESULT;
            case "click":
                return encodeAutomatorResult(selector.click());
            case "longClick":
                return encodeAutomatorResult(selector.longClick());
            case "setText":
                return encodeAutomatorResult(selector.setText(args.getString(0)));
            case "scrollForward":
                return encodeAutomatorResult(selector.scrollForward());
            case "scrollBackward":
                return encodeAutomatorResult(selector.scrollBackward());
            case "toString":
                return encodeAutomatorResult(selector.toString());
            default:
                throw new IllegalArgumentException("远程选择器不支持的方法：" + method);
        }
    }

    private String callRemoteObject(RemoteUiObject object, String method, JSONArray args) throws Exception {
        if (namesContain(NODE_ACTIONS, method) || namesContain(OBJECT_STRING_PROPERTIES, method)
                || namesContain(OBJECT_INT_PROPERTIES, method) || namesContain(OBJECT_BOOL_PROPERTIES, method))
            return encodeAutomatorResult(invokeAutomator(object, method, NO_PARAMS, NO_ARGS));
        switch (method) {
            case "text": return encodeAutomatorResult(object.text());
            case "desc": return encodeAutomatorResult(object.desc());
            case "id": return encodeAutomatorResult(object.id());
            case "className": return encodeAutomatorResult(object.className());
            case "packageName": return encodeAutomatorResult(object.packageName());
            case "clickable": return encodeAutomatorResult(object.clickable());
            case "enabled": return encodeAutomatorResult(object.enabled());
            case "scrollable": return encodeAutomatorResult(object.scrollable());
            case "depth": return encodeAutomatorResult(object.depth());
            case "indexInParent": return encodeAutomatorResult(object.indexInParent());
            case "childCount": return encodeAutomatorResult(object.childCount());
            case "bounds": return encodeAutomatorResult(object.bounds());
            case "exists": return encodeAutomatorResult(object.exists());
            case "click": return encodeAutomatorResult(object.click());
            case "clickCenter": return encodeAutomatorResult(object.clickCenter());
            case "longClick": return encodeAutomatorResult(object.longClick());
            case "setText": return encodeAutomatorResult(object.setText(args.getString(0)));
            case "scrollForward": return encodeAutomatorResult(object.scrollForward());
            case "scrollBackward": return encodeAutomatorResult(object.scrollBackward());
            case "children": return encodeAutomatorResult(object.children());
            case "getRangeInfo": return encodeAutomatorResult(object.getRangeInfo());
            case "boundsInParent": return encodeAutomatorResult(object.boundsInParent());
            case "setSelection":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(object.setSelection(args.getInt(0), args.getInt(1)));
            case "setProgress":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(object.setProgress((float) args.getDouble(0)));
            case "scrollTo":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(object.scrollTo(args.getInt(0), args.getInt(1)));
            case "findByText": return encodeAutomatorResult(new RemoteUiObjectCollection(object.findByText(args.getString(0))));
            case "findByViewId": return encodeAutomatorResult(new RemoteUiObjectCollection(object.findByViewId(args.getString(0))));
            case "parent": return encodeAutomatorResult(object.parent());
            case "child": return encodeAutomatorResult(object.child(args.getInt(0)));
            case "find": return encodeAutomatorResult(object.find((RemoteUiSelector) remoteHandleArg(args, 0)));
            case "findOne": return encodeAutomatorResult(object.findOne((RemoteUiSelector) remoteHandleArg(args, 0)));
            case "toString": return encodeAutomatorResult(object.toString());
            default: throw new IllegalArgumentException("远程控件不支持的方法：" + method);
        }
    }

    private String callRemoteCollection(RemoteUiObjectCollection collection, String method, JSONArray args) throws Exception {
        if (namesContain(NODE_ACTIONS, method)) return encodeAutomatorResult(invokeAutomator(collection, method, NO_PARAMS, NO_ARGS));
        if ("setSelection".equals(method) || "scrollTo".equals(method)) {
            NodeActionArguments.validate(method, args);
            return encodeAutomatorResult(invokeAutomator(collection, method,
                    new Class<?>[]{int.class, int.class},
                    new Object[]{args.getInt(0), args.getInt(1)}));
        }
        if ("setProgress".equals(method)) {
            NodeActionArguments.validate(method, args);
            return encodeAutomatorResult(collection.setProgress((float) args.getDouble(0)));
        }
        switch (method) {
            case "size": return encodeAutomatorResult(collection.size());
            case "empty": return encodeAutomatorResult(collection.empty());
            case "nonEmpty": return encodeAutomatorResult(collection.nonEmpty());
            case "get": return encodeAutomatorResult(collection.get(args.getInt(0)));
            case "toString": return encodeAutomatorResult(collection.toString());
            case "click": { boolean any = false; for (RemoteUiObject item : collection) any |= item.click(); return encodeAutomatorResult(any); }
            case "longClick": { boolean any = false; for (RemoteUiObject item : collection) any |= item.longClick(); return encodeAutomatorResult(any); }
            case "setText": return encodeAutomatorResult(collection.setText(args.getString(0)));
            default: throw new IllegalArgumentException("远程控件集合不支持的方法：" + method);
        }
    }

    private String callSelector(UiGlobalSelector selector, String method, JSONArray args) throws Exception {
        if (namesContain(SELECTOR_STRING_FILTERS, method)) {
            invokeAutomator(selector, method, new Class<?>[]{ String.class }, new Object[]{ args.getString(0) });
            return AUTOMATOR_VOID_RESULT;
        }
        if (namesContain(SELECTOR_INT_FILTERS, method)) {
            invokeAutomator(selector, method, new Class<?>[]{ int.class }, new Object[]{ args.getInt(0) });
            return AUTOMATOR_VOID_RESULT;
        }
        if (namesContain(SELECTOR_BOOL_FILTERS, method)) {
            if (args.length() > 0 && !args.isNull(0)) {
                invokeAutomator(selector, method, new Class<?>[]{ boolean.class },
                        new Object[]{ args.getBoolean(0) });
            } else {
                invokeAutomator(selector, method, NO_PARAMS, NO_ARGS);
            }
            return AUTOMATOR_VOID_RESULT;
        }
        if (namesContain(SELECTOR_BOUNDS_FILTERS, method)) {
            invokeAutomator(selector, method, new Class<?>[]{ int.class, int.class, int.class, int.class },
                    new Object[]{ args.getInt(0), args.getInt(1), args.getInt(2), args.getInt(3) });
            return AUTOMATOR_VOID_RESULT;
        }
        // Rhino 的 UiSelector 本身也能直接执行动作（内部先 untilFind），保持一致。
        if (namesContain(NODE_ACTIONS, method)) {
            return encodeAutomatorResult(invokeAutomator(selector, method, NO_PARAMS, NO_ARGS));
        }
        switch (method) {
            case "setText":
                return encodeAutomatorResult(invokeAutomator(selector, method,
                        new Class<?>[]{ String.class }, new Object[]{ args.getString(0) }));
            case "setSelection":
            case "scrollTo":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(invokeAutomator(selector, method,
                        new Class<?>[]{ int.class, int.class },
                        new Object[]{ args.getInt(0), args.getInt(1) }));
            case "setProgress":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(invokeAutomator(selector, method,
                        new Class<?>[]{ float.class }, new Object[]{ (float) args.getDouble(0) }));
            case "find":
            case "untilFindOne":
            case "exists":
                return encodeAutomatorResult(invokeAutomator(selector, method, NO_PARAMS, NO_ARGS));
            case "untilFind":
                if (args.length() > 1 && !args.isNull(1)) {
                    return encodeAutomatorResult(invokeAutomator(selector, "untilFind",
                            new Class<?>[]{ long.class, long.class },
                            new Object[]{ args.getLong(0), args.getLong(1) }));
                }
                if (args.length() > 0 && !args.isNull(0)) {
                    return encodeAutomatorResult(invokeAutomator(selector, "untilFind",
                            new Class<?>[]{ long.class }, new Object[]{ args.getLong(0) }));
                }
                return encodeAutomatorResult(invokeAutomator(selector, "untilFind", NO_PARAMS, NO_ARGS));
            case "waitFor":
                invokeAutomator(selector, method, NO_PARAMS, NO_ARGS);
                return AUTOMATOR_VOID_RESULT;
            case "findOnce":
                return args.length() > 0 && !args.isNull(0)
                        ? encodeAutomatorResult(invokeAutomator(selector, "findOnce",
                        new Class<?>[]{ int.class }, new Object[]{ args.getInt(0) }))
                        : encodeAutomatorResult(invokeAutomator(selector, "findOnce", NO_PARAMS, NO_ARGS));
            case "findOne":
                if (args.length() > 1 && !args.isNull(1)) {
                    return encodeAutomatorResult(invokeAutomator(selector, "findOne",
                            new Class<?>[]{ long.class, long.class },
                            new Object[]{ args.getLong(0), args.getLong(1) }));
                }
                return args.length() > 0 && !args.isNull(0)
                        ? encodeAutomatorResult(invokeAutomator(selector, "findOne",
                        new Class<?>[]{ long.class }, new Object[]{ args.getLong(0) }))
                        : encodeAutomatorResult(invokeAutomator(selector, "findOne", NO_PARAMS, NO_ARGS));
            case "findOf":
                return args.length() > 1 && !args.isNull(1)
                        ? encodeAutomatorResult(invokeAutomator(selector, "findOf",
                        new Class<?>[]{ UiObject.class, int.class },
                        new Object[]{ requireAutomatorObject(args.getLong(0)), args.getInt(1) }))
                        : encodeAutomatorResult(invokeAutomator(selector, "findOf",
                        new Class<?>[]{ UiObject.class },
                        new Object[]{ requireAutomatorObject(args.getLong(0)) }));
            case "findOneOf":
                return encodeAutomatorResult(invokeAutomator(selector, "findOneOf",
                        new Class<?>[]{ UiObject.class },
                        new Object[]{ requireAutomatorObject(args.getLong(0)) }));
            case "findAndReturnList": {
                UiObject root = requireAutomatorObject(args.getLong(0));
                int max = args.length() > 1 && !args.isNull(1) && args.getInt(1) >= 0
                        ? args.getInt(1) : Integer.MAX_VALUE;
                return encodeAutomatorResult(invokeAutomator(selector, "findAndReturnList",
                        new Class<?>[]{ UiObject.class, int.class }, new Object[]{ root, max }));
            }
            // Rhino 的 __selector__.js 还把 filter/addFilter 挂到全局：谓词是脚本函数，
            // 遍历控件时通过 invokeJsCallback 同步回调脚本（同一引擎线程）。
            // Rhino 的 addFilter 需要 Java Filter 实例，脚本通常只会传函数，这里同样按谓词处理。
            case "filter":
                invokeAutomator(selector, "filter", new Class<?>[]{ BooleanFilter.BooleanSupplier.class },
                        new Object[]{ new JsNodePredicate(args.getLong(0)) });
                return AUTOMATOR_VOID_RESULT;
            case "addFilter":
                invokeAutomator(selector, "addFilter", new Class<?>[]{ Filter.class },
                        new Object[]{ new JsNodeFilter(args.getLong(0)) });
                return AUTOMATOR_VOID_RESULT;
            case "toString":
                return encodeAutomatorResult(selector.toString());
            default:
                throw new IllegalArgumentException("不支持的选择器方法：" + method);
        }
    }

    /**
     * Java → JS 同步回调：脚本函数登记在 JS 侧的表里，这里只持有 id。
     * 返回脚本编码后的 JSON（`{"value":...}` 或 `{"error":"..."}`）。
     */
    String invokeJsCallback(long callbackId, String argsJson) {
        long engineHandle = mEngineHandle;
        if (engineHandle == 0) {
            throw new IllegalStateException("QuickJS 引擎尚未就绪，无法回调脚本函数");
        }
        String result = QuickJsNativeBridge.invokeJsCallback(engineHandle, callbackId, argsJson,
                Boolean.TRUE.equals(mMainHandoff.get()));
        return result == null ? "{}" : result;
    }

    /**
     * 在主线程上同步执行一段脚本回调（等价于 Rhino 的 `ui.run(fn)`）。
     *
     * <p>此时引擎线程正阻塞在 native 调用里，因此主线程进入 QuickJS 上下文是安全的；
     * 控件 / View 的 API 只能在主线程调用（否则抛 CalledFromWrongThreadException），
     * `runOnMainThread(fn)` 就是给脚本提供的入口。
     */
    private final ThreadLocal<Boolean> mMainHandoff = new ThreadLocal<>();

    public String runJsCallbackOnMain(long callbackId) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return invokeJsCallback(callbackId, "[]");
        }
        final String[] result = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        final Object gate = new Object();
        final boolean[] state = new boolean[2]; // started, abandoned
        Runnable operation = () -> {
            synchronized (gate) {
                if (state[1]) { latch.countDown(); return; }
                state[0] = true;
            }
            mMainHandoff.set(true);
            try {
                result[0] = invokeJsCallback(callbackId, "[]");
            } catch (Throwable error) {
                result[0] = jsonError(describeError(error));
            } finally {
                mMainHandoff.remove();
                latch.countDown();
            }
        };
        if (!mDialogHandler.post(operation)) return jsonError("UI looper unavailable");
        boolean interrupted = false;
        boolean expired = false;
        try {
            expired = !latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            interrupted = true;
        }
        if (expired || interrupted) {
            boolean started;
            synchronized (gate) { started=state[0]; if (!started) state[1]=true; }
            if (!started) mDialogHandler.removeCallbacks(operation);
            else {
                // The script thread must never resume while its UI callback still owns JS.
                long handle=mEngineHandle;
                if (handle!=0) QuickJsNativeBridge.requestInterrupt(handle);
                while (latch.getCount()!=0) {
                    try { latch.await(); }
                    catch (InterruptedException again) { interrupted=true; }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return jsonError(interrupted ? "主线程执行被中断" : "主线程执行超时");
        }
        return result[0] == null ? "{}" : result[0];
    }

    private static String jsonError(String message) {
        try {
            return new JSONObject().put("error", message).toString();
        } catch (JSONException impossible) {
            return "{\"error\":\"unknown\"}";
        }
    }

    private static String describeError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    // ------------------------------------------------------------------
    // 跨线程 JS 任务（WebView 页面回调、注入结果回调等）
    //
    // 非引擎线程只把任务放进队列，真正的 JS 调用由 native 事件循环在引擎线程上取出执行
    // （`__aiRunJsTask`），避免多线程同时进入 QuickJS 上下文。
    // ------------------------------------------------------------------

    private final java.util.concurrent.ConcurrentLinkedQueue<String> mJsTasks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** native 事件循环用：当前排队的任务数。 */
    public int pendingJsTaskCount() {
        return mJsTasks.size();
    }

    /** native 事件循环用：取出一个任务（JSON 描述）。 */
    public String takePendingJsTask() {
        return mJsTasks.poll();
    }

    /** native 事件循环用：任务执行失败时把原因写进脚本控制台。 */
    public void reportJsTaskResult(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        try {
            mRuntime.console.error(message);
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", message);
        }
    }

    /** 在其它线程里安排一次脚本回调（异步）。 */
    public void postJsCallback(long callbackId, String argsJson) {
        enqueueJsTask("callback", json -> {
            json.put("id", callbackId);
            json.put("args", argsJson == null || argsJson.isEmpty() ? "[]" : argsJson);
        });
    }

    /** 在其它线程里安排一次脚本全局函数调用（页面里的 `rhino.call`）。 */
    public void postJsFunctionCall(String functionName, String argsJson) {
        enqueueJsTask("call", json -> {
            json.put("name", functionName == null ? "" : functionName);
            json.put("args", argsJson == null || argsJson.isEmpty() ? "[]" : argsJson);
        });
    }

    /** 在其它线程里安排一次脚本求值（页面里的 `rhino.eval`）。 */
    public void postJsEval(String script) {
        enqueueJsTask("eval", json -> json.put("code", script == null ? "" : script));
    }

    private interface JsTaskEncoder {
        void encode(JSONObject json) throws JSONException;
    }

    /** 从一个非引擎线程延迟安排回调（页面桥与回归测试都走这条路径）。 */
    public void postJsCallbackDelayed(final long callbackId, final long delayMillis) {
        mDialogHandler.postDelayed(() -> postJsCallback(callbackId, "[]"), Math.max(0, delayMillis));
    }

    // ---- QuickJsWebScriptHost：页面里的 rhino 桥 ----
    @Override
    public void postScriptCall(String name, String argsJson) {
        postJsFunctionCall(name, argsJson);
    }

    @Override
    public void postScriptEval(String script) {
        postJsEval(script);
    }

    @Override
    public void postScriptCallback(long callbackId, String value) {
        String argsJson = new JSONArray().put(value == null ? "" : value).toString();
        postJsCallback(callbackId, argsJson);
    }

    private void enqueueJsTask(String kind, JsTaskEncoder encoder) {
        try {
            JSONObject json = new JSONObject().put("kind", kind);
            encoder.encode(json);
            mJsTasks.add(json.toString());
        } catch (JSONException impossible) {
            Log.w("QuickJsHostBridge", "Cannot enqueue JS task: " + kind, impossible);
        }
    }

    // ---- web 模块（QuickJS 版 InjectableWebView / InjectableWebClient）----
    private final AtomicLong mNextWebHandle = new AtomicLong(1);
    private final Map<Long, Object> mWebHandles = new ConcurrentHashMap<>();

    /** UI 线程创建 WebView / WebViewClient，返回句柄；`withView=false` 只建客户端。 */
    public long webCreate(boolean withView) {
        final CountDownLatch latch = new CountDownLatch(1);
        final long[] handle = new long[]{ 0 };
        mDialogHandler.post(() -> {
            try {
                Object injector;
                if (withView) {
                    android.app.Activity activity = mRuntime.app.getCurrentActivity();
                    Context context = activity != null ? activity : mRuntime.uiHandler.getContext();
                    injector = new com.stardust.autojs.core.web.QuickJsInjectableWebView(context, this);
                } else {
                    injector = new com.stardust.autojs.core.web.QuickJsInjectableWebClient(this);
                }
                handle[0] = putWebHandle(injector);
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot create injectable web view", error);
                handle[0] = 0;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return handle[0];
    }

    /** web 对象方法分派（全部回到 UI 线程执行）。 */
    public String webCall(long handle, String method, String argsJson) throws Exception {
        Object target = mWebHandles.get(handle);
        if (target == null) {
            throw new IllegalStateException("WebView 句柄已失效：" + handle);
        }
        JSONArray args = new JSONArray(argsJson == null || argsJson.isEmpty() ? "[]" : argsJson);
        return runOnUiThread(() -> {
            try {
                return invokeWebMethod(target, method, args);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    private String invokeWebMethod(Object target, String method, JSONArray args) throws Exception {
        switch (method == null ? "" : method) {
            case "inject":
                injectScript(target, args.getString(0), args.optLong(1, 0));
                return "{\"t\":\"v\"}";
            case "loadUrl":
                requireWebView(target).loadUrl(args.getString(0));
                return "{\"t\":\"v\"}";
            case "loadData":
                requireWebView(target).loadData(args.getString(0), args.getString(1),
                        args.length() > 2 ? args.getString(2) : "utf-8");
                return "{\"t\":\"v\"}";
            case "reload":
                requireWebView(target).reload();
                return "{\"t\":\"v\"}";
            case "stopLoading":
                requireWebView(target).stopLoading();
                return "{\"t\":\"v\"}";
            case "getUrl":
                return new JSONObject().put("t", "s").put("v", requireWebView(target).getUrl()).toString();
            default:
                throw new IllegalArgumentException("不支持的 web 方法：" + method);
        }
    }

    private void injectScript(Object target, String script, long callbackId) {
        if (target instanceof com.stardust.autojs.core.web.QuickJsInjectableWebView) {
            ((com.stardust.autojs.core.web.QuickJsInjectableWebView) target).inject(script, callbackId);
            return;
        }
        ((com.stardust.autojs.core.web.QuickJsInjectableWebClient) target).inject(script, callbackId);
    }

    private android.webkit.WebView requireWebView(Object target) {
        if (!(target instanceof android.webkit.WebView)) {
            throw new IllegalStateException("该对象不是 WebView（newInjectableWebClient 只有 inject）");
        }
        return (android.webkit.WebView) target;
    }

    private long putWebHandle(Object value) {
        long handle = mNextWebHandle.getAndIncrement();
        mWebHandles.put(handle, value);
        return handle;
    }

    /** 在 UI 线程执行并等待结果（桥里的 UI 操作统一走这里）。 */
    private String runOnUiThread(java.util.concurrent.Callable<String> action) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        final Exception[] failure = new Exception[1];
        mDialogHandler.post(() -> {
            try {
                result[0] = action.call();
            } catch (Exception e) {
                failure[0] = e;
            } catch (Throwable error) {
                failure[0] = new IllegalStateException(error);
            } finally {
                latch.countDown();
            }
        });
        latch.await(10, TimeUnit.SECONDS);
        if (failure[0] != null) {
            throw failure[0];
        }
        return result[0];
    }

    // ------------------------------------------------------------------
    // Java 互操作（Rhino 兼容：完整 public 反射）
    //
    // 用户拍板采用与 Rhino 相同的模型：Packages / importClass / importPackage / Java.type
    // 都能解析任意类。类与实例统一用 long 句柄跨边界，JS 侧是 Proxy 包装。
    // ------------------------------------------------------------------

    private final QuickJsJavaInterop mJavaInterop;

    /** 解析类名，返回类句柄（0 表示找不到）。 */
    public long javaResolveClass(String name) {
        return mJavaInterop.resolveClass(name);
    }

    /**
     * 把 Android Context 交给脚本（Rhino 里 `context` 就是 Context 对象）。
     * 拿不到时返回 0，JS 侧回退到白名单版的 context。
     */
    public long javaContextHandle() {
        try {
            return mJavaInterop.putForScript(mRuntime.uiHandler.getContext());
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot expose Android context", error);
            return 0;
        }
    }

    /** native 侧创建好 Java 对象（如 OpenCV Mat）后登记句柄，供 JS 侧 Proxy 包装。 */
    public long javaRegisterObject(Object value) {
        return value == null ? 0 : mJavaInterop.putForScript(value);
    }

    public void javaReleaseSiftInput(long handle) {
        if (mJavaInterop.objectForHandle(handle) instanceof com.stardust.autojs.core.image.SiftMatcher.NativeFrameInput)
            mJavaInterop.releaseForScript(handle);
    }

    /** native 侧按句柄取回 Java 对象（Mat → 帧桥使用）。 */
    public Object javaObjectForHandle(long handle) {
        return mJavaInterop.objectForHandle(handle);
    }

    /** `'m'` 有方法 / `'f'` 有字段 / `'p:getX'` JavaBean 属性 / `'c'` 嵌套类 / 空串都没有。 */
    public String javaProbe(long handle, String name) {
        return mJavaInterop.probe(handle, name);
    }

    /** 嵌套类全限定名（`android.os.Build` + `VERSION` → `android.os.Build$VERSION`），找不到返回空串。 */
    public String javaNestedClassName(long handle, String name) {
        return mJavaInterop.nestedClassName(handle, name);
    }

    public String javaCall(long handle, String name, String argsJson) throws JSONException {
        return mJavaInterop.call(handle, name, argsJson);
    }

    public String javaGetField(long handle, String name) throws JSONException {
        return mJavaInterop.getField(handle, name);
    }

    public boolean javaSetField(long handle, String name, String valueJson) throws JSONException {
        return mJavaInterop.setField(handle, name, valueJson);
    }

    public String javaNew(long classHandle, String argsJson) throws JSONException {
        return mJavaInterop.instantiate(classHandle, argsJson);
    }

    /** 脚本谓词：UiGlobalSelector.filter(BooleanSupplier) 用。 */
    private final class JsNodePredicate implements BooleanFilter.BooleanSupplier {        private final long mCallbackId;

        JsNodePredicate(long callbackId) {
            mCallbackId = callbackId;
        }

        @Override
        public boolean get(UiObject node) {
            return evaluateJsNodePredicate(mCallbackId, node);
        }
    }

    /** 脚本谓词：UiGlobalSelector.addFilter(Filter) 用，语义与上面一致。 */
    private final class JsNodeFilter implements Filter {
        private final long mCallbackId;

        JsNodeFilter(long callbackId) {
            mCallbackId = callbackId;
        }

        @Override
        public boolean filter(UiObject node) {
            return evaluateJsNodePredicate(mCallbackId, node);
        }
    }

    private boolean evaluateJsNodePredicate(long callbackId, Object node) {
        try {
            JSONObject argument = new JSONObject();
            argument.put("__node", putAutomatorHandle(node));
            JSONObject result = new JSONObject(invokeJsCallback(
                    callbackId, new JSONArray().put(argument).toString()));
            if (result.has("error")) {
                throw new IllegalStateException("脚本谓词执行失败：" + result.optString("error"));
            }
            return result.optBoolean("value", false);
        } catch (JSONException e) {
            throw new IllegalStateException("脚本谓词返回了非法结果", e);
        }
    }

    private String callObject(UiObject object, String method, JSONArray args) throws Exception {
        if (namesContain(OBJECT_STRING_PROPERTIES, method) || namesContain(OBJECT_INT_PROPERTIES, method)
                || namesContain(OBJECT_BOOL_PROPERTIES, method) || namesContain(NODE_ACTIONS, method)) {
            return encodeAutomatorResult(invokeAutomator(object, method, NO_PARAMS, NO_ARGS));
        }
        switch (method) {
            case "findByText": return encodeAutomatorResult(UiObjectCollection.Companion.of(object.findByText(args.getString(0))));
            case "findByViewId": return encodeAutomatorResult(UiObjectCollection.Companion.of(object.findByViewId(args.getString(0))));
            case "bounds":
            case "getRangeInfo":
            case "boundsInParent":
            case "parent":
            case "children":
                return encodeAutomatorResult(invokeAutomator(object, method, NO_PARAMS, NO_ARGS));
            case "child":
                return encodeAutomatorResult(invokeAutomator(object, method,
                        new Class<?>[]{ int.class }, new Object[]{ args.getInt(0) }));
            case "setText":
                return encodeAutomatorResult(invokeAutomator(object, method,
                        new Class<?>[]{ String.class }, new Object[]{ args.getString(0) }));
            case "setSelection":
            case "scrollTo":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(invokeAutomator(object, method,
                        new Class<?>[]{ int.class, int.class },
                        new Object[]{ args.getInt(0), args.getInt(1) }));
            case "setProgress":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(invokeAutomator(object, method,
                        new Class<?>[]{ float.class }, new Object[]{ (float) args.getDouble(0) }));
            case "find":
            case "findOne":
                return encodeAutomatorResult(invokeAutomator(object, method,
                        new Class<?>[]{ UiGlobalSelector.class },
                        new Object[]{ requireAutomatorSelector(args.getLong(0)) }));
            case "toString":
                return encodeAutomatorResult(object.toString());
            default:
                throw new IllegalArgumentException("不支持的控件方法：" + method);
        }
    }

    private String callCollection(UiObjectCollection collection, String method, JSONArray args) throws Exception {
        switch (method) {
            case "size":
                return encodeAutomatorResult(collection.size());
            case "get": {
                int index = args.getInt(0);
                return encodeAutomatorResult(index >= 0 && index < collection.size()
                        ? collection.get(index) : null);
            }
            case "empty":
                return encodeAutomatorResult(collection.empty());
            case "nonEmpty":
                return encodeAutomatorResult(collection.nonEmpty());
            case "find":
                return encodeAutomatorResult(collection.find(requireAutomatorSelector(args.getLong(0))));
            case "findOne":
                return encodeAutomatorResult(collection.findOne(requireAutomatorSelector(args.getLong(0))));
            case "setText":
                return encodeAutomatorResult(invokeAutomator(collection, method,
                        new Class<?>[]{ CharSequence.class }, new Object[]{ args.getString(0) }));
            case "setSelection":
            case "scrollTo":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(invokeAutomator(collection, method,
                        new Class<?>[]{ int.class, int.class },
                        new Object[]{ args.getInt(0), args.getInt(1) }));
            case "setProgress":
                NodeActionArguments.validate(method, args);
                return encodeAutomatorResult(invokeAutomator(collection, method,
                        new Class<?>[]{ float.class }, new Object[]{ (float) args.getDouble(0) }));
            case "toString":
                return encodeAutomatorResult(collection.toString());
            default:
                if (namesContain(NODE_ACTIONS, method)) {
                    return encodeAutomatorResult(invokeAutomator(collection, method, NO_PARAMS, NO_ARGS));
                }
                throw new IllegalArgumentException("不支持的控件集合方法：" + method);
        }
    }

    private String encodeAutomatorResult(Object value) throws JSONException {
        JSONObject json = new JSONObject();
        if (value == null) {
            json.put("t", "n");
        } else if (value instanceof Boolean) {
            json.put("t", "b").put("v", ((Boolean) value).booleanValue());
        } else if (value instanceof Integer) {
            json.put("t", "i").put("v", ((Integer) value).intValue());
        } else if (value instanceof String) {
            json.put("t", "s").put("v", value);
        } else if (value instanceof Rect) {
            Rect rect = (Rect) value;
            JSONObject bounds = new JSONObject();
            bounds.put("left", rect.left).put("top", rect.top)
                    .put("right", rect.right).put("bottom", rect.bottom);
            json.put("t", "r").put("v", bounds);
        } else if (value instanceof androidx.core.view.accessibility.AccessibilityNodeInfoCompat.RangeInfoCompat) {
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.RangeInfoCompat r =
                    (androidx.core.view.accessibility.AccessibilityNodeInfoCompat.RangeInfoCompat)value;
            return encodeAutomatorResult(new RemoteUiObject.RangeInfo(r.getType(),r.getMin(),r.getMax(),r.getCurrent()));
        } else if (value instanceof RemoteUiObject.RangeInfo) {
            RemoteUiObject.RangeInfo r=(RemoteUiObject.RangeInfo)value;
            json.put("t","g").put("v",new JSONObject().put("type",r.getType()).put("min",r.getMin())
                    .put("max",r.getMax()).put("current",r.getCurrent()));
        } else if (value instanceof RemoteUiSelector) {
            json.put("t", "v");
        } else if (value instanceof RemoteUiObjectCollection) {
            json.put("t", "h").put("k", "c").put("v", putAutomatorHandle(value));
        } else if (value instanceof RemoteUiObject) {
            json.put("t", "h").put("k", "o").put("v", putAutomatorHandle(value));
        } else if (value instanceof UiObjectCollection) {
            json.put("t", "h").put("k", "c").put("v", putAutomatorHandle(value));
        } else if (value instanceof java.util.List) {
            JSONArray items = new JSONArray();
            for (Object item : (java.util.List<?>) value) {
                if (item instanceof UiObject) {
                    items.put(new JSONObject().put("k", "o").put("v", putAutomatorHandle(item)));
                }
            }
            json.put("t", "a").put("v", items);
        } else if (value instanceof UiObject) {
            json.put("t", "h").put("k", "o").put("v", putAutomatorHandle(value));
        } else {
            throw new IllegalStateException("不支持的自动控制返回值：" + value.getClass().getName());
        }
        return json.toString();
    }

    private Object invokeAutomator(Object target, String method, Class<?>[] parameters, Object[] arguments)
            throws Exception {
        String key = automatorMethodKey(target.getClass(), method, parameters);
        Method cached = mAutomatorMethods.get(key);
        if (cached == null) {
            cached = target.getClass().getMethod(method, parameters);
            mAutomatorMethods.put(key, cached);
        }
        return AutomatorInvocation.invoke(cached,target,arguments);
    }

    private static String automatorMethodKey(Class<?> type, String method, Class<?>[] parameters) {
        StringBuilder builder = new StringBuilder(type.getName()).append('#').append(method).append('(');
        for (Class<?> parameter : parameters) {
            builder.append(parameter.getName()).append(',');
        }
        return builder.append(')').toString();
    }

    private static boolean namesContain(String[] names, String method) {
        for (String name : names) {
            if (name.equals(method)) return true;
        }
        return false;
    }

    private long putAutomatorHandle(Object value) {
        if (mAutomatorHandles.size() >= MAX_AUTOMATOR_HANDLES) {
            Log.w("QuickJsHostBridge", "Selector handle limit reached, dropping stale handles");
            mAutomatorHandles.clear();
        }
        long handle = mNextAutomatorHandle.getAndIncrement();
        mAutomatorHandles.put(handle, value);
        return handle;
    }

    private UiObject requireAutomatorObject(long handle) {
        Object target = mAutomatorHandles.get(handle);
        if (!(target instanceof UiObject)) {
            throw new IllegalArgumentException("句柄不是控件：" + handle);
        }
        return (UiObject) target;
    }

    private UiGlobalSelector requireAutomatorSelector(long handle) {
        Object target = mAutomatorHandles.get(handle);
        if (!(target instanceof UiGlobalSelector)) {
            throw new IllegalArgumentException("句柄不是选择器：" + handle);
        }
        return (UiGlobalSelector) target;
    }

    // ------------------------------------------------------------------
    // sqlite 模块（复用 core/database 的 Database）
    // ------------------------------------------------------------------

    private final AtomicLong mNextSqliteHandle = new AtomicLong(1);
    private final Map<Long, Database> mSqliteHandles = new ConcurrentHashMap<>();

    public long sqliteOpen(String name, String optionsJson) throws JSONException {
        JSONObject options = new JSONObject(optionsJson == null ? "{}" : optionsJson);
        int version = options.optInt("version", 1);
        if (version < 1) throw new IllegalArgumentException("SQLite version must be >= 1");
        JSONObject callbacks = options.optJSONObject("callbacks");
        final long handle = mNextSqliteHandle.getAndIncrement();
        Database.Callbacks lifecycle = new Database.Callbacks() {
            private void fire(String name, Object... extra) {
                long callbackId = callbacks == null ? 0 : callbacks.optLong(name);
                if (callbackId == 0) return;
                JSONArray args = new JSONArray().put(handle);
                for (Object value : extra) args.put(value);
                try {
                    JSONObject result = new JSONObject(invokeJsCallback(callbackId, args.toString()));
                    if (result.has("error")) throw new IllegalStateException(result.getString("error"));
                } catch (JSONException error) {
                    throw new IllegalStateException("Cannot decode SQLite callback", error);
                }
            }
            @Override public void onCreate(Database db) { fire("onCreate"); }
            @Override public void onUpgrade(Database db, int oldVersion, int newVersion) {
                fire("onUpgrade", oldVersion, newVersion);
            }
            @Override public void onConfigure(Database db) { fire("onConfigure"); }
            @Override public void onOpen(Database db) { fire("onOpen"); }
            @Override public void onCorruption(android.database.sqlite.SQLiteDatabase db) {
                fire("onCorruption");
            }
        };
        String path = name == null ? null : mRuntime.files.path(name);
        Database database = new Database(ScriptRuntime.getApplicationContext(), path, version,
                options.optBoolean("readOnly"), lifecycle, mRuntime);
        mSqliteHandles.put(handle, database);
        try {
            database.open();
            return handle;
        } catch (RuntimeException error) {
            mSqliteHandles.remove(handle);
            database.close();
            throw error;
        }
    }

    /** JSON is only the transport; primitive CRUD results stay numbers in JavaScript. */
    public String sqliteCall(long handle, String method, String argsJson) throws JSONException {
        Database database = mSqliteHandles.get(handle);
        if (database == null) throw new IllegalStateException("Database connection is closed");
        JSONArray args = new JSONArray(argsJson == null || argsJson.isEmpty() ? "[]" : argsJson);
        switch (method == null ? "" : method) {
            case "exec":
                return encodeSqliteResult(args.isNull(1) ? database.exec(args.getString(0))
                        : database.exec(args.getString(0), toSqliteArgs(args.optJSONArray(1))));
            case "execSQL":
                if (args.isNull(1)) database.execSQL(args.getString(0));
                else database.execSQL(args.getString(0), toSqliteArgs(args.optJSONArray(1)));
                return "null";
            case "select":
                return encodeSqliteResult(database.select(args.getString(0),
                        toSqliteArgs(args.optJSONArray(1))));
            case "rawQuery": {
                Object[] values = toSqliteArgs(args.optJSONArray(1));
                String[] strings = values == null ? null : new String[values.length];
                if (values != null) for (int i = 0; i < values.length; i++) {
                    strings[i] = values[i] == null ? null : String.valueOf(values[i]);
                }
                try (android.database.Cursor cursor = database.getSQLiteDatabase()
                        .rawQuery(args.getString(0), strings)) {
                    JSONArray rows = new JSONArray(), data = new JSONArray(), types = new JSONArray();
                    String[] columns = cursor.getColumnNames();
                    while (cursor.moveToNext()) {
                        JSONObject row = new JSONObject();
                        JSONArray cells = new JSONArray(), cellTypes = new JSONArray();
                        for (int i = 0; i < columns.length; i++) {
                            Object value;
                            switch (cursor.getType(i)) {
                                case android.database.Cursor.FIELD_TYPE_INTEGER: value = cursor.getLong(i); break;
                                case android.database.Cursor.FIELD_TYPE_FLOAT: value = cursor.getDouble(i); break;
                                case android.database.Cursor.FIELD_TYPE_BLOB: value = sqliteBlob(cursor.getBlob(i)); break;
                                case android.database.Cursor.FIELD_TYPE_STRING: value = cursor.getString(i); break;
                                default: value = JSONObject.NULL;
                            }
                            row.put(columns[i], value);
                            cells.put(value); cellTypes.put(cursor.getType(i));
                        }
                        rows.put(row); data.put(cells); types.put(cellTypes);
                    }
                    return new JSONObject().put("rows", rows).put("data", data).put("types", types)
                            .put("columns", new JSONArray(Arrays.asList(columns))).toString();
                }
            }
            case "insert":
                return encodeSqliteResult(database.insert(args.getString(0), toSqliteValues(args.getJSONObject(1))));
            case "update":
                return encodeSqliteResult(database.update(args.getString(0), toSqliteValues(args.getJSONObject(1)),
                        args.isNull(2) ? null : args.getString(2), toSqliteArgs(args.optJSONArray(3))));
            case "delete":
                return encodeSqliteResult(database.delete(args.getString(0),
                        args.isNull(1) ? null : args.getString(1), toSqliteArgs(args.optJSONArray(2))));
            case "insertResult":
                return encodeSqliteResult(database.insertResult(args.getString(0), toSqliteValues(args.getJSONObject(1))));
            case "updateResult":
                return encodeSqliteResult(database.updateResult(args.getString(0), toSqliteValues(args.getJSONObject(1)),
                        args.isNull(2) ? null : args.getString(2), toSqliteArgs(args.optJSONArray(3))));
            case "deleteResult":
                return encodeSqliteResult(database.deleteResult(args.getString(0),
                        args.isNull(1) ? null : args.getString(1), toSqliteArgs(args.optJSONArray(2))));
            case "begin":
                if (args.optBoolean(0)) database.getSQLiteDatabase().beginTransactionNonExclusive();
                else database.getSQLiteDatabase().beginTransaction();
                return "null";
            case "end": {
                android.database.sqlite.SQLiteDatabase writable = database.getSQLiteDatabase();
                if (writable.inTransaction()) {
                    if (args.optBoolean(0)) writable.setTransactionSuccessful();
                    writable.endTransaction();
                }
                return "null";
            }
            case "succeed": database.getSQLiteDatabase().setTransactionSuccessful(); return "null";
            case "version": return String.valueOf(database.getSQLiteDatabase().getVersion());
            case "setVersion": database.getSQLiteDatabase().setVersion(args.getInt(0)); return "null";
            case "pageSize": return String.valueOf(database.getSQLiteDatabase().getPageSize());
            case "setPageSize": database.getSQLiteDatabase().setPageSize(args.getLong(0)); return "null";
            case "maximumSize": return String.valueOf(database.getSQLiteDatabase().getMaximumSize());
            case "setMaximumSize": return String.valueOf(database.getSQLiteDatabase().setMaximumSize(args.getLong(0)));
            case "path": return JSONObject.quote(database.getSQLiteDatabase().getPath());
            case "isOpen": return String.valueOf(database.getSQLiteDatabase().isOpen());
            case "isReadOnly": return String.valueOf(database.getSQLiteDatabase().isReadOnly());
            case "inTransaction": return String.valueOf(database.getSQLiteDatabase().inTransaction());
            case "close": database.close(); mSqliteHandles.remove(handle); return "null";
            default: throw new IllegalArgumentException("Unsupported sqlite method: " + method);
        }
    }

    private static JSONObject sqliteBlob(byte[] bytes) throws JSONException {
        JSONArray array = new JSONArray();
        for (byte value : bytes) array.put(value & 255);
        return new JSONObject().put("__sqliteBlob", array);
    }

    private Object decodeSqliteValue(Object value) throws JSONException {
        if (value instanceof JSONObject && ((JSONObject) value).has("__sqliteBlob")) {
            JSONArray bytes = ((JSONObject) value).getJSONArray("__sqliteBlob");
            byte[] result = new byte[bytes.length()];
            for (int i = 0; i < result.length; i++) result[i] = (byte) bytes.getInt(i);
            return result;
        }
        return value == JSONObject.NULL ? null : value;
    }

    private String encodeSqliteResult(Object result) throws JSONException {
        if (result == null) return "null";
        if (result instanceof Number || result instanceof Boolean) return result.toString();
        if (!(result instanceof DatabaseResultSet)) return "null";
        DatabaseResultSet set = (DatabaseResultSet) result;
        JSONArray rows = new JSONArray();
        if (set.rows != null) for (int i = 0; i < set.rows.length; i++) {
            Map<?, ?> source = (Map<?, ?>) set.rows.item(i);
            JSONObject row = new JSONObject();
            if (source != null) for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object value = entry.getValue();
                row.put(String.valueOf(entry.getKey()), value instanceof byte[] ? sqliteBlob((byte[]) value)
                        : value == null ? JSONObject.NULL : value);
            }
            rows.put(row);
        }
        return new JSONObject().put("insertId", set.insertId).put("rowsAffected", set.rowsAffected)
                .put("rows", rows).toString();
    }

    private Object[] toSqliteArgs(JSONArray array) throws JSONException {
        if (array == null) {
            return null;
        }
        Object[] result = new Object[array.length()];
        for (int i = 0; i < array.length(); i++) {
            result[i] = array.isNull(i) ? null : decodeSqliteValue(array.get(i));
        }
        return result;
    }

    private Map<String, Object> toSqliteValues(JSONObject object) throws JSONException {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            values.put(key, object.isNull(key) ? null : decodeSqliteValue(object.get(key)));
        }
        return values;
    }

    private void closeSqliteHandles() {
        for (Database database : new ArrayList<>(mSqliteHandles.values())) {
            try {
                database.close();
            } catch (Throwable ignored) {
            }
        }
        mSqliteHandles.clear();
    }

    private void releaseAutomatorHandles() {
        mAutomatorHandles.clear();
        mAutomatorMethods.clear();
    }

    // ------------------------------------------------------------------
    // web.newWebSocket（事件经系统事件队列投递，40ms 轮询派发到脚本）
    // ------------------------------------------------------------------

    private final java.util.concurrent.ConcurrentHashMap<Long, okhttp3.WebSocket> mWebSockets =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong mNextWebSocketId =
            new java.util.concurrent.atomic.AtomicLong(1);
    private volatile okhttp3.OkHttpClient mWebSocketClient;

    /** 创建 WebSocket 连接，返回句柄（>0）；失败返回 -1。 */
    public long webSocketNew(String url) {
        try {
            okhttp3.OkHttpClient client = mWebSocketClient;
            if (client == null) {
                synchronized (this) {
                    if (mWebSocketClient == null) {
                        mWebSocketClient = new okhttp3.OkHttpClient();
                    }
                    client = mWebSocketClient;
                }
            }
            final long id = mNextWebSocketId.getAndIncrement();
            okhttp3.WebSocket socket = client.newWebSocket(
                    new okhttp3.Request.Builder().url(url).build(),
                    new okhttp3.WebSocketListener() {
                        @Override
                        public void onOpen(okhttp3.WebSocket webSocket, okhttp3.Response response) {
                            enqueueWebSocketEvent(id, "open", response == null ? null : response.message());
                        }

                        @Override
                        public void onMessage(okhttp3.WebSocket webSocket, String text) {
                            enqueueWebSocketEvent(id, "text", text);
                        }

                        @Override
                        public void onMessage(okhttp3.WebSocket webSocket, okio.ByteString bytes) {
                            enqueueWebSocketEvent(id, "binary", bytes == null ? "" : bytes.base64());
                        }

                        @Override
                        public void onClosing(okhttp3.WebSocket webSocket, int code, String reason) {
                            enqueueWebSocketEvent(id, "closing", code, reason);
                        }

                        @Override
                        public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) {
                            mWebSockets.remove(id);
                            enqueueWebSocketEvent(id, "closed", code, reason);
                        }

                        @Override
                        public void onFailure(okhttp3.WebSocket webSocket, Throwable t, okhttp3.Response response) {
                            mWebSockets.remove(id);
                            enqueueWebSocketEvent(id, "failure",
                                    t == null ? "WebSocket failure" : String.valueOf(t.getMessage()));
                        }
                    });
            mWebSockets.put(id, socket);
            return id;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "webSocketNew failed: " + url, error);
            return -1;
        }
    }

    private void enqueueWebSocketEvent(long id, String event, Object... args) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("id", id);
            payload.put("event", event);
            JSONArray array = new JSONArray();
            for (Object arg : args) {
                array.put(arg == null ? JSONObject.NULL : arg);
            }
            payload.put("args", array);
            enqueueSystemEvent("websocket", payload);
        } catch (JSONException error) {
            Log.w("QuickJsHostBridge", "Cannot serialize websocket event", error);
        }
    }

    public boolean webSocketSend(long id, String type, String data) {
        okhttp3.WebSocket socket = mWebSockets.get(id);
        if (socket == null) {
            return false;
        }
        if ("binary".equals(type)) {
            okio.ByteString bytes = okio.ByteString.decodeBase64(data == null ? "" : data);
            return bytes != null && socket.send(bytes);
        }
        return socket.send(data == null ? "" : data);
    }

    public boolean webSocketClose(long id, int code, String reason) {
        okhttp3.WebSocket socket = mWebSockets.get(id);
        if (socket == null) {
            return false;
        }
        return socket.close(code, reason == null ? "" : reason);
    }

    public void webSocketCancel(long id) {
        okhttp3.WebSocket socket = mWebSockets.get(id);
        if (socket != null) {
            socket.cancel();
        }
    }

    public long webSocketQueueSize(long id) {
        okhttp3.WebSocket socket = mWebSockets.get(id);
        return socket == null ? 0 : socket.queueSize();
    }

    private void closeWebSockets() {
        for (okhttp3.WebSocket socket : new ArrayList<>(mWebSockets.values())) {
            try {
                socket.cancel();
            } catch (Throwable ignored) {
            }
        }
        mWebSockets.clear();
    }

    @Override
    public void close() {
        if (!mCloseStarted.compareAndSet(false, true)) return;
        if (mOwnerEngineId > 0) {
            sActiveBridges.remove(mOwnerEngineId, this);
        }
        // 最先关 overlay 窗口：异常/结束路径（engine.destroy -> bridge.close）里这一步必须在
        // 其它清理之前，保证即使后面的清理抛异常，全屏窗口也不会残留在屏幕上（残留=用户卡死）。
        uiClose();
        mAccessibilityPixels = null;
        for (String name : new ArrayList<>(mSharedBusSubscriptions)) {
            sharedBusOff(name);
        }
        eventsStopAll();
        closeFileObservers();
        images().setRawImageCaptureListener(null);
        recyclePendingAsyncFrame();
        drawClose();
        imguiPanel("close", "{}");
        mediaStopMusic();
        sensorsUnregisterAll();
        closeOwnedFloatyWindows();   // 只关本引擎创建的窗口（注册表是进程级的）
        if (mOwnerEngineId > 0) {
            // 兜底：ui/draw/floaty 中间路径漏掉的窗口按 owner 统一关闭（幂等）。
            OverlayRegistry.closeByOwner(mOwnerEngineId, "quickjs_close");
        }
        releaseAutomatorHandles();
        closeSqliteHandles();
        closeWebSockets();
        mJavaInterop.clear();
        for (android.app.AlertDialog dialog : new ArrayList<>(pendingDialogRegistry.values())) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        pendingDialogRegistry.clear();
        mDialogs.clear();
        for (ScriptDialogState state : mScriptDialogs.values()) {
            mDialogHandler.post(() -> state.dialog.dismiss());
        }
        mScriptDialogs.clear();
        // Kill any shell child processes still running so a stopped script cannot
        // leak background commands.
        for (Process process : new ArrayList<>(mShellProcesses)) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
        mShellProcesses.clear();
        for (YoloSession session : new ArrayList<>(mYoloSessions.values())) {
            try {
                session.detector.close();
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot close YOLO detector", error);
            }
        }
        mYoloSessions.clear();
        for (OcrSession session : new ArrayList<>(mOcrSessions.values())) {
            try {
                session.release();
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot release OCR session", error);
            }
        }
        mOcrSessions.clear();
        mOcrImageBuffer.close();
        mEngineResults.clear();
    }

    // ---- drawing overlay whitelist ----

    private volatile QuickJsOverlay mOverlay;
    private volatile com.stardust.autojs.core.graphics.ImGuiDrawingOverlay mImGuiOverlay;
    private volatile com.stardust.autojs.core.graphics.ImGuiControlPanel mImGuiPanel;

    public String imguiPanel(String operation, String json) {
        try {
            if ("close".equals(operation)) {
                com.stardust.autojs.core.graphics.ImGuiControlPanel closing = mImGuiPanel;
                mImGuiPanel = null;
                if (closing != null) closing.close();
                return "{\"ok\":true,\"events\":[]}";
            }
            if ("show".equals(operation)) {
                imguiPanel("close", "{}");
                Context context = mRuntime.uiHandler.getContext();
                if (context == null) return "{\"ok\":false,\"events\":[]}";
                com.stardust.autojs.core.graphics.ImGuiControlPanel panel = new com.stardust.autojs.core.graphics.ImGuiControlPanel(context);
                if (!panel.show(new JSONObject(json))) { panel.close(); return "{\"ok\":false,\"events\":[]}"; }
                mImGuiPanel = panel;
                return panel.poll();
            }
            com.stardust.autojs.core.graphics.ImGuiControlPanel panel = mImGuiPanel;
            if (panel == null) return "{\"ok\":false,\"events\":[]}";
            if ("update".equals(operation)) { panel.update(new JSONObject(json)); return "{\"ok\":true}"; }
            if ("poll".equals(operation)) return panel.poll();
            throw new IllegalArgumentException("Unknown ImGui panel operation");
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("Invalid ImGui panel: " + error.getMessage(), error); }
    }

    public boolean drawCreate() {
        return drawCreateWithRenderer("canvas");
    }

    public boolean drawCreateWithRenderer(String renderer) {
        drawClose();
        Context context = mRuntime.uiHandler.getContext();
        if (context == null) return false;
        com.stardust.autojs.core.graphics.ImGuiDrawingOverlay.closeAll();
        if ("imgui".equals(renderer)) {
            QuickJsOverlay.closeAll();
            com.stardust.autojs.core.graphics.ImGuiDrawingOverlay overlay =
                    new com.stardust.autojs.core.graphics.ImGuiDrawingOverlay(context);
            if (!overlay.show()) { overlay.close(); return false; }
            mImGuiOverlay = overlay;
            registerImGuiDrawingOverlay(overlay);
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return false;
        }
        try {
            // Multiple engine runs can leave overlay windows behind when a script is
            // force-stopped before the host bridge closes. Make the overlay unique:
            // reap every previous overlay before creating the new one.
            QuickJsOverlay.closeAll();
            mOverlay = new QuickJsOverlay(context, mOwnerEngineId, overlayOwnerTag());
            if (!mOverlay.show()) {
                mOverlay = null;
                return false;
            }
            // 这里**不要**再去隐藏系统栏（历史实现给当前 Activity 设过
            // FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY，已删除）。原因：
            //   1) 那只作用于「我们自己的窗口」——画板盖在其他应用上时，系统状态栏
            //      照样压在画板上，达不到「不被系统 UI 遮挡」的目的；
            //   2) 副作用却是实打实的：脚本一跑，回到本应用看到的状态栏就是被隐藏后
            //      的一条黑条（真机 K40 实测：顶部 0~75px 纯黑、无时间/电量图标）；
            //   3) 恢复只发生在 drawClose()，脚本被强停/异常结束就永远不会恢复；
            //      sImmersiveCount 是 static，跨 Activity 计数还会漂移。
            // 截图坐标也无需隐藏系统栏——MediaProjection 截的始终是整块屏幕。
            return true;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot create drawing overlay", error);
            mOverlay = null;
            return false;
        }
    }

    public void drawUpdate(String detectionsJson, String statsText) {
        com.stardust.autojs.core.graphics.ImGuiDrawingOverlay nativeOverlay = mImGuiOverlay;
        if (nativeOverlay != null) {
            try { nativeOverlay.update(detectionsJson, statsText); }
            catch (JSONException error) { Log.w("QuickJsHostBridge", "Invalid ImGui drawing snapshot", error); }
            return;
        }
        QuickJsOverlay overlay = mOverlay;
        if (overlay == null) return;
        try {
            JSONArray array = new JSONArray(detectionsJson == null ? "[]" : detectionsJson);
            List<Detection> detections = new ArrayList<>(Math.min(2048, array.length()));
            for (int i = 0; i < Math.min(2048, array.length()); i++) detections.add(new Detection(array.getJSONObject(i)));
            overlay.update(detections, statsText == null ? "" : statsText);
        } catch (JSONException ignored) {
            // malformed payload: keep the previous frame
        }
    }

    public void drawClose() {
        com.stardust.autojs.core.graphics.ImGuiDrawingOverlay nativeOverlay = mImGuiOverlay;
        mImGuiOverlay = null;
        if (nativeOverlay != null) {
            unregisterImGuiDrawingOverlay(nativeOverlay);
            nativeOverlay.close();
        }
        QuickJsOverlay overlay = mOverlay;
        mOverlay = null;
        if (overlay != null) overlay.close();
    }

    /**
     * ImGuiDrawingOverlay 没有公开根 View / WindowManager 访问器（且不在本次改造的写入范围），
     * 这里通过反射取得它的 Surface/WindowManager 登记为 QUICKJS_DRAW；失败只记日志、不影响绘制。
     */
    private void registerImGuiDrawingOverlay(com.stardust.autojs.core.graphics.ImGuiDrawingOverlay overlay) {
        View view = imGuiOverlayView(overlay);
        if (view == null) {
            return;
        }
        WindowManager windowManager = imGuiOverlayWindowManager(overlay);
        view.setClickable(false);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        forceOverlayNotTouchable(view, windowManager);
        OverlayRegistry.register(mOwnerEngineId, overlayOwnerTag(), "quickjs",
                OverlayRegistry.Kind.QUICKJS_DRAW, view, windowManager, false);
    }

    private void unregisterImGuiDrawingOverlay(com.stardust.autojs.core.graphics.ImGuiDrawingOverlay overlay) {
        View view = imGuiOverlayView(overlay);
        if (view != null) {
            OverlayRegistry.unregister(view);
        }
    }

    private static View imGuiOverlayView(com.stardust.autojs.core.graphics.ImGuiDrawingOverlay overlay) {
        try {
            Field field = com.stardust.autojs.core.graphics.ImGuiDrawingOverlay.class
                    .getDeclaredField("view");
            field.setAccessible(true);
            Object value = field.get(overlay);
            return value instanceof View ? (View) value : null;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot resolve ImGui drawing overlay view", error);
            return null;
        }
    }

    private static WindowManager imGuiOverlayWindowManager(
            com.stardust.autojs.core.graphics.ImGuiDrawingOverlay overlay) {
        try {
            Field field = com.stardust.autojs.core.graphics.ImGuiDrawingOverlay.class
                    .getDeclaredField("manager");
            field.setAccessible(true);
            Object value = field.get(overlay);
            return value instanceof WindowManager ? (WindowManager) value : null;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot resolve ImGui drawing overlay WindowManager", error);
            return null;
        }
    }

    /** 绘制层必须不可触摸：兜底把被外部改掉的窗口 flags 改回 NOT_TOUCHABLE（主线程执行）。 */
    private static void forceOverlayNotTouchable(View view, WindowManager windowManager) {
        try {
            if (!(view.getLayoutParams() instanceof WindowManager.LayoutParams)) {
                return;
            }
            final WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
            if ((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) {
                return;
            }
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            Runnable update = () -> {
                try {
                    if (windowManager != null) {
                        windowManager.updateViewLayout(view, params);
                    }
                } catch (Throwable error) {
                    Log.w("QuickJsHostBridge", "Cannot enforce not-touchable draw overlay", error);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                update.run();
            } else {
                new Handler(Looper.getMainLooper()).post(update);
            }
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot enforce not-touchable draw overlay", error);
        }
    }

    private static final class Detection {
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final String label;
        final float score;
        final String kind;
        final int color;
        final boolean customColor;
        final float strokeWidth, radius, size, headSize;

        Detection(JSONObject shape) throws JSONException {
            x1 = (float) shape.optDouble("x1"); y1 = (float) shape.optDouble("y1");
            x2 = (float) shape.optDouble("x2"); y2 = (float) shape.optDouble("y2");
            kind = shape.optString("kind", "rect"); label = shape.optString("label", "");
            score = (float) shape.optDouble("score", 0);
            strokeWidth = Math.max(1f, (float) shape.optDouble("strokeWidth", 4));
            radius = Math.max(1f, (float) shape.optDouble("radius", 8));
            size = Math.max(8f, (float) shape.optDouble("size", 30));
            headSize = Math.max(4f, (float) shape.optDouble("headSize", 18));
            for (float value : new float[]{x1, y1, x2, y2, strokeWidth, radius, size, headSize})
                if (Float.isNaN(value) || Float.isInfinite(value)) throw new JSONException("Non-finite drawing coordinate");
            Object rawColor = shape.opt("color");
            int parsed = Color.parseColor("#33FF66");
            boolean custom = false;
            if (rawColor instanceof Number) { parsed = ((Number) rawColor).intValue(); custom = true; }
            else if (rawColor instanceof String) try { parsed = Color.parseColor((String) rawColor); custom = true; }
            catch (IllegalArgumentException ignored) {}
            color = parsed; customColor = custom;
        }
    }

    private static final class QuickJsOverlay {
        private static final String TAG = "QuickJsOverlay";
        private static final java.util.Set<QuickJsOverlay> sAllOverlays =
                Collections.synchronizedSet(new java.util.HashSet<>());

        private final WindowManager mWindowManager;
        private final OverlayView mView;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final int mOwnerEngineId;
        private final String mOwnerTag;
        private volatile boolean mShown;

        QuickJsOverlay(Context context, int ownerEngineId, String ownerTag) {
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mView = new OverlayView(context);
            // 只绘制、不交互：即使窗口参数被外部改动也不接收触摸/焦点。
            mView.setClickable(false);
            mView.setFocusable(false);
            mView.setFocusableInTouchMode(false);
            mOwnerEngineId = ownerEngineId;
            mOwnerTag = ownerTag;
            sAllOverlays.add(this);
        }

        static void closeAll() {
            java.util.List<QuickJsOverlay> overlays;
            synchronized (sAllOverlays) {
                overlays = new ArrayList<>(sAllOverlays);
            }
            for (QuickJsOverlay overlay : overlays) {
                overlay.close();
            }
        }

        boolean show() {
            mMainHandler.post(() -> {
                try {
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    : WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            android.graphics.PixelFormat.TRANSLUCENT);
                    // Draw across the whole physical screen (status bar / cutout /
                    // gesture bar) so overlay coordinates match MediaProjection
                    // capture coordinates exactly.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        params.layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    }
                    // Anchor explicitly to the top-left so the overlay always starts
                    // at (0,0) and spans the whole screen even with multiple overlays.
                    params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                    // 绘制层强制不可触摸：不因 UI 兼容逻辑变成可触摸。
                    params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                    mWindowManager.addView(mView, params);
                    mShown = true;
                    OverlayRegistry.register(mOwnerEngineId, mOwnerTag, "quickjs",
                            OverlayRegistry.Kind.QUICKJS_DRAW, mView, mWindowManager, false);
                    Log.i(TAG, "Overlay window added");
                } catch (Throwable error) {
                    Log.e(TAG, "addView failed", error);
                    mShown = false;
                }
            });
            return true;
        }

        void update(List<Detection> detections, String statsText) {
            mView.setData(detections, statsText);
        }

        void close() {
            sAllOverlays.remove(this);
            OverlayRegistry.unregister(mView);
            mMainHandler.post(() -> {
                try {
                    if (mShown) {
                        mWindowManager.removeViewImmediate(mView);
                        mShown = false;
                        Log.i(TAG, "Overlay window removed");
                    } else {
                        Log.w(TAG, "close called but overlay was not shown");
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "removeViewImmediate failed", error);
                    mShown = false;
                }
            });
        }
    }

    private static final class OverlayView extends View {
        private final Paint mBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelBgPaint = new Paint();
        private final Paint mStatsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStatsBgPaint = new Paint();
        private final Object mLock = new Object();
        private List<Detection> mDetections = Collections.emptyList();
        private String mStatsText = "";
        /** HUD 顶部 y：让开系统状态栏（含投屏/录屏胶囊把状态栏撑高的情况）。 */
        private final int mHudTop;

        OverlayView(Context context) {
            super(context);
            mBoxPaint.setStyle(Paint.Style.STROKE);
            mBoxPaint.setStrokeWidth(4f);
            mBoxPaint.setColor(Color.parseColor("#33FF66"));
            mLabelPaint.setTextSize(30f);
            mLabelPaint.setColor(Color.parseColor("#FFD54F"));
            mLabelBgPaint.setColor(0xCC000000);
            mStatsPaint.setTextSize(28f);
            mStatsPaint.setColor(Color.WHITE);
            mStatsBgPaint.setColor(0x99000000);
            mHudTop = resolveHudTop(context);
        }

        /**
         * 状态栏底边 + 8dp 作为 HUD 顶部。
         *
         * <p>历史原因：HUD 画死在 y=10..54，而状态栏从 y=0 起就有 80dp 上下（K40 状态栏
         * 高 80px、小米 8 更高，出现投屏/录屏胶囊时还会再涨），于是状态文字一直压在状态栏
         * 底下看不见（真机反馈「悬浮窗显示的测试时间被挡住」）。
         */
        private static int resolveHudTop(Context context) {
            float density = context.getResources().getDisplayMetrics().density;
            int statusBar = 0;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager wm =
                            (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) {
                        statusBar = wm.getCurrentWindowMetrics().getWindowInsets()
                                .getInsets(android.view.WindowInsets.Type.statusBars()).top;
                    }
                }
            } catch (Throwable ignored) {
                // 取不到就退回资源/默认值，绝不因为 HUD 位置把绘制整个搞崩。
            }
            if (statusBar <= 0) {
                int id = context.getResources()
                        .getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) {
                    statusBar = context.getResources().getDimensionPixelSize(id);
                }
            }
            if (statusBar <= 0) {
                statusBar = (int) (24 * density);
            }
            return statusBar + (int) (8 * density);
        }

        void setData(List<Detection> detections, String statsText) {
            synchronized (mLock) {
                mDetections = detections;
                mStatsText = statsText;
            }
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            List<Detection> detections;
            String statsText;
            synchronized (mLock) {
                detections = mDetections;
                statsText = mStatsText;
            }
            float hudLeft = 10, hudTop = mHudTop, hudRight = getWidth() - 10, hudBottom = getHeight();
            for (Detection detection : detections) if ("hud".equals(detection.kind)) {
                hudLeft = Math.max(0, detection.x1); hudTop = Math.max(0, detection.y1);
                hudRight = Math.min(getWidth(), detection.x2); hudBottom = Math.min(getHeight(), detection.y2);
            }
            if (!statsText.isEmpty() && hudRight > hudLeft && hudBottom > hudTop) {
                android.text.StaticLayout hud = new android.text.StaticLayout(statsText,
                        new android.text.TextPaint(mStatsPaint), Math.max(1, (int) (hudRight - hudLeft - 28)),
                        android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
                canvas.drawRoundRect(new RectF(hudLeft, hudTop, hudRight, Math.min(hudBottom, hudTop + hud.getHeight() + 16)),
                        12, 12, mStatsBgPaint);
                canvas.save(); canvas.clipRect(hudLeft, hudTop, hudRight, hudBottom);
                canvas.translate(hudLeft + 14, hudTop + 8); hud.draw(canvas); canvas.restore();
            }
            for (Detection detection : detections) {
                mBoxPaint.setColor(detection.color); mBoxPaint.setStrokeWidth(detection.strokeWidth);
                switch (detection.kind) {
                    case "line": canvas.drawLine(detection.x1, detection.y1, detection.x2, detection.y2, mBoxPaint); break;
                    case "circle": canvas.drawCircle(detection.x1, detection.y1, detection.radius, mBoxPaint); break;
                    case "arrow": {
                        float dx = detection.x2 - detection.x1, dy = detection.y2 - detection.y1;
                        float length = (float) Math.hypot(dx, dy);
                        if (length < 1) break;
                        float head = Math.min(detection.headSize, length * 0.4f), ux = dx / length, uy = dy / length;
                        float bx = detection.x2 - ux * head, by = detection.y2 - uy * head;
                        canvas.drawLine(detection.x1, detection.y1, bx, by, mBoxPaint);
                        android.graphics.Path triangle = new android.graphics.Path();
                        triangle.moveTo(detection.x2, detection.y2);
                        triangle.lineTo(bx - uy * head * 0.5f, by + ux * head * 0.5f);
                        triangle.lineTo(bx + uy * head * 0.5f, by - ux * head * 0.5f); triangle.close();
                        mBoxPaint.setStyle(Paint.Style.FILL); canvas.drawPath(triangle, mBoxPaint);
                        mBoxPaint.setStyle(Paint.Style.STROKE); break;
                    }
                    case "text": case "hud": break;
                    default: canvas.drawRect(new RectF(Math.min(detection.x1, detection.x2), Math.min(detection.y1, detection.y2),
                            Math.max(detection.x1, detection.x2), Math.max(detection.y1, detection.y2)), mBoxPaint);
                }
                if (detection.label.isEmpty()) continue;
                mLabelPaint.setColor(detection.customColor ? detection.color : Color.parseColor("#FFD54F"));
                mLabelPaint.setTextSize(detection.size);
                if ("text".equals(detection.kind)) {
                    canvas.drawText(detection.label, detection.x1, detection.y1 - mLabelPaint.ascent(), mLabelPaint);
                    continue;
                }
                String caption = detection.label + ("rect".equals(detection.kind) ? " " + Math.round(detection.score * 100) + "%" : "");
                float captionWidth = mLabelPaint.measureText(caption);
                float top = Math.max(0f, detection.y1 - 40f);
                canvas.drawRoundRect(new RectF(detection.x1, top,
                        detection.x1 + captionWidth + 14, top + 36), 8, 8, mLabelBgPaint);
                canvas.drawText(caption, detection.x1 + 7, top + 26, mLabelPaint);
            }
        }
    }

    private static boolean isAssetPath(String path) {
        return path != null && path.startsWith("asset://");
    }

    // ---- task-118：老示例根目录 asset 路径兼容 --------------------------------

    /**
     * v1044 以前示例根目录叫 {@code sample/QuickJS 新引擎/}，后来改成 {@code sample/QuickJS 引擎/}。
     * 用户脚本目录里保留的旧示例仍写 {@code asset://sample/QuickJS 新引擎/...}，严格按新资产路径
     * 打开会 FileNotFoundException；这里做一次只读别名回退（模型/标签/图片都适用），不改用户文件。
     */
    private static final String LEGACY_QUICKJS_SAMPLE_ROOT = "sample/QuickJS 新引擎/";
    private static final String CURRENT_QUICKJS_SAMPLE_ROOT = "sample/QuickJS 引擎/";

    private android.content.res.AssetManager getAssets() {
        return mRuntime.uiHandler.getContext().getAssets();
    }

    private String resolveBundledAssetName(String name) throws IOException {
        if (bundledAssetExists(name)) {
            return name;
        }
        if (name != null && name.startsWith(LEGACY_QUICKJS_SAMPLE_ROOT)) {
            String candidate = CURRENT_QUICKJS_SAMPLE_ROOT
                    + name.substring(LEGACY_QUICKJS_SAMPLE_ROOT.length());
            if (bundledAssetExists(candidate)) {
                Log.i("QuickJsHostBridge", "asset legacy alias: " + name + " -> " + candidate);
                return candidate;
            }
        }
        throw new java.io.FileNotFoundException("asset 不存在：" + name);
    }

    private boolean bundledAssetExists(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        try (InputStream ignored = getAssets().open(name)) {
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static String stripAssetPrefix(String path) {
        return path.substring("asset://".length());
    }

    private Images images() {
        Object images = mRuntime.getImages();
        if (!(images instanceof Images)) {
            throw new IllegalStateException("Image APIs are unavailable on this Android version");
        }
        return (Images) images;
    }

    private static int normalizeOrientation(int orientation) {
        switch (orientation) {
            case Configuration.ORIENTATION_PORTRAIT:
            case Configuration.ORIENTATION_LANDSCAPE:
                return orientation;
            default:
                return Configuration.ORIENTATION_UNDEFINED;
        }
    }

    // ---- app module whitelist ----

    public boolean appLaunch(String packageName) {
        try {
            mRuntime.app.launchPackage(packageName);
            return true;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "appLaunch failed: " + error.getMessage());
            return false;
        }
    }

    public boolean appOpenUrl(String url) {
        try {
            mRuntime.app.openUrl(url);
            return true;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "appOpenUrl failed: " + error.getMessage());
            return false;
        }
    }

    public String appGetInstalledApps() {
        try {
            Context context = mRuntime.uiHandler.getContext().getApplicationContext();
            List<android.content.pm.ApplicationInfo> apps = context.getPackageManager()
                    .getInstalledApplications(0);
            JSONArray array = new JSONArray();
            for (android.content.pm.ApplicationInfo info : apps) {
                JSONObject obj = new JSONObject();
                obj.put("packageName", info.packageName);
                obj.put("label", mRuntime.app.getAppName(info.packageName));
                array.put(obj);
            }
            return array.toString();
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "appGetInstalledApps failed: " + error.getMessage());
            return "[]";
        }
    }

    public String appGetAppInfo(String packageName) {
        try {
            android.content.pm.PackageInfo info = mRuntime.uiHandler.getContext()
                    .getPackageManager().getPackageInfo(packageName, 0);
            JSONObject obj = new JSONObject();
            obj.put("packageName", packageName);
            obj.put("label", mRuntime.app.getAppName(packageName));
            obj.put("versionName", info.versionName == null ? "" : info.versionName);
            obj.put("versionCode", info.versionCode);
            return obj.toString();
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "appGetAppInfo failed: " + error.getMessage());
            return "{}";
        }
    }

    // ---- storages module whitelist ----

    private final Map<Long, com.stardust.autojs.core.storage.LocalStorage> mStorages =
            new ConcurrentHashMap<>();
    private final AtomicLong mNextStorageHandle = new AtomicLong(1);

    public long storageCreate(String name) {
        long handle = mNextStorageHandle.getAndIncrement();
        mStorages.put(handle, new com.stardust.autojs.core.storage.LocalStorage(
                mRuntime.app.getCurrentActivity() != null
                        ? mRuntime.app.getCurrentActivity()
                        : mRuntime.uiHandler.getContext().getApplicationContext(),
                name));
        return handle;
    }

    public boolean storagePut(long handle, String key, String value) {
        com.stardust.autojs.core.storage.LocalStorage storage = mStorages.get(handle);
        if (storage == null) return false;
        storage.put(key, value);
        return true;
    }

    public String storageGet(long handle, String key, String defaultValue) {
        com.stardust.autojs.core.storage.LocalStorage storage = mStorages.get(handle);
        if (storage == null) return defaultValue;
        String value = storage.getString(key, null);
        return value != null ? value : defaultValue;
    }

    public boolean storageRemove(long handle, String key) {
        com.stardust.autojs.core.storage.LocalStorage storage = mStorages.get(handle);
        if (storage == null) return false;
        storage.remove(key);
        return true;
    }

    public boolean storageContains(long handle, String key) {
        com.stardust.autojs.core.storage.LocalStorage storage = mStorages.get(handle);
        return storage != null && storage.contains(key);
    }

    public boolean storageClear(long handle) {
        com.stardust.autojs.core.storage.LocalStorage storage = mStorages.get(handle);
        if (storage == null) return false;
        storage.clear();
        return true;
    }

    // ---- device module whitelist ----

    public String deviceGetInfo(String kind) {
        try {
            switch (kind) {
                case "width":
                    return String.valueOf(mRuntime.device != null ? mRuntime.device.width : 0);
                case "height":
                    return String.valueOf(mRuntime.device != null ? mRuntime.device.height : 0);
                case "model":
                    return Build.MODEL;
                case "brand":
                    return Build.BRAND;
                case "board":
                    return Build.BOARD;
                case "hardware":
                    return Build.HARDWARE;
                case "sdkInt":
                    return String.valueOf(Build.VERSION.SDK_INT);
                case "release":
                    return Build.VERSION.RELEASE;
                case "buildId":
                    return Build.ID;
                case "display":
                    return Build.DISPLAY;
                case "product":
                    return Build.PRODUCT;
                case "manufacturer":
                    return Build.MANUFACTURER;
                default:
                    return "";
            }
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "deviceGetInfo failed: " + error.getMessage());
            return "";
        }
    }

    public boolean deviceIsScreenOn() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    mRuntime.uiHandler.getContext().getApplicationContext()
                            .getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isScreenOn();
        } catch (Throwable error) {
            return false;
        }
    }

    public void deviceVibrate(int millis) {
        try {
            android.os.Vibrator v = (android.os.Vibrator)
                    mRuntime.uiHandler.getContext().getApplicationContext()
                            .getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(millis);
        } catch (Throwable ignored) {
        }
    }

    public float deviceGetBattery() {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent battery = mRuntime.uiHandler.getContext().getApplicationContext()
                    .registerReceiver(null, filter);
            if (battery == null) return -1f;
            int level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            return scale > 0 ? (float) level / scale * 100f : -1f;
        } catch (Throwable error) {
            return -1f;
        }
    }
}
