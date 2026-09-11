package com.stardust.autojs.engine;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
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
import com.stardust.autojs.core.database.Database;
import com.stardust.autojs.core.database.DatabaseResultSet;
import com.stardust.autojs.runtime.api.Images;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.OpenCvYoloDetector;
import com.stardust.autojs.runtime.api.ShizukuShell;
import com.stardust.autojs.runtime.api.Yolo;
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
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private final AtomicLong mNextYoloHandle = new AtomicLong(1);
    private final Map<Long, YoloSession> mYoloSessions = new ConcurrentHashMap<>();
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
    private boolean mObservingKeys;
    private boolean mObservingNotifications;
    private boolean mObservingToasts;
    private boolean mObservingGestures;
    private TouchObserver mSystemTouchObserver;
    private long mLastSystemTouchMillis;
    private volatile long mSystemTouchTimeoutMillis = 10;

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

    private static final class YoloSession {
        final String backend;
        final AutoCloseable detector;

        YoloSession(String backend, AutoCloseable detector) {
            this.backend = backend;
            this.detector = detector;
        }
    }

    QuickJsHostBridge(ScriptRuntime runtime) {
        mRuntime = runtime;
    }

    void attachEngine(long engineHandle) {
        mEngineHandle = engineHandle;
    }

    public void console(int level, String message) {
        switch (level) {
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
            mObservingKeys = false;
            mObservingNotifications = false;
            mObservingToasts = false;
            mObservingGestures = false;
            mSystemEventQueue.clear();
        }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        return images().requestScreenCaptureBlocking(normalizeOrientation(orientation));
    }

    public long captureScreenNative(int targetShortEdge, boolean fresh, int timeoutMillis) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw new UnsupportedOperationException("Screen capture requires Android 5.0 or newer");
        }
        long engineHandle = mEngineHandle;
        if (engineHandle == 0) {
            throw new IllegalStateException("QuickJS engine is not attached");
        }
        Image image = images().captureScreenRaw(fresh, timeoutMillis);
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

    public String resolvePath(String path) {
        return mRuntime.files.path(path);
    }

    public byte[] readImageAsset(String path) {
        if (!isAssetPath(path)) {
            throw new IllegalArgumentException("Bundled image path must start with asset://");
        }
        try (InputStream input = mRuntime.uiHandler.getContext().getAssets().open(stripAssetPrefix(path));
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
            detector = isAssetPath(modelPath)
                    ? mRuntime.yolo.createOpenCvFromAssets(stripAssetPrefix(modelPath), inputWidth, inputHeight, threads)
                    : mRuntime.yolo.createOpenCv(mRuntime.files.path(modelPath), inputWidth, inputHeight, threads);
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
                ? mRuntime.uiHandler.getContext().getAssets().open(stripAssetPrefix(path))
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
    public String dialogBuild(String propsJson) {
        try {
            android.app.Activity activity = mRuntime.app.getCurrentActivity();
            JSONObject fallback = new JSONObject();
            fallback.put("action", "cancel");
            fallback.put("inputText", "");
            if (activity == null || activity.isFinishing()) return fallback.toString();

            final JSONObject[] holder = new JSONObject[]{ fallback };
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);

            final JSONObject props = (propsJson != null && !propsJson.isEmpty())
                    ? new JSONObject(propsJson) : new JSONObject();

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    com.afollestad.materialdialogs.MaterialDialog.Builder builder =
                            new com.afollestad.materialdialogs.MaterialDialog.Builder(activity);

                    if (props.has("title"))
                        builder.title(props.getString("title"));
                    if (props.has("content"))
                        builder.content(props.getString("content"));

                    boolean hasInput = props.has("inputHint") || props.has("inputPrefill");
                    String inputHint = props.optString("inputHint", null);
                    String inputPrefill = props.optString("inputPrefill", "");
                    boolean allowEmpty = props.optBoolean("inputAllowEmpty", true);

                    if (hasInput) {
                        builder.input(inputHint, inputPrefill, allowEmpty,
                                (d, input) -> {
                                    try {
                                        JSONObject r = new JSONObject();
                                        r.put("action", "input");
                                        r.put("inputText", input.toString());
                                        holder[0] = r;
                                    } catch (JSONException ignored) {}
                                    latch.countDown();
                                });
                    }

                    String posText = props.has("positiveText")
                            ? props.getString("positiveText") : null;
                    String negText = props.has("negativeText")
                            ? props.getString("negativeText") : null;
                    String neutText = props.has("neutralText")
                            ? props.getString("neutralText") : null;

                    // Add button texts only if provided; omit to hide button
                    if (posText != null) builder.positiveText(posText);
                    if (negText != null) builder.negativeText(negText);
                    if (neutText != null) builder.neutralText(neutText);

                    if (posText != null && !hasInput) {
                        builder.onPositive((d, w) -> {
                            try { holder[0] = new JSONObject().put("action", "positive"); }
                            catch (JSONException ignored) {}
                            latch.countDown();
                        });
                    }
                    if (negText != null) {
                        builder.onNegative((d, w) -> {
                            try { holder[0] = new JSONObject().put("action", "negative"); }
                            catch (JSONException ignored) {}
                            latch.countDown();
                        });
                    }
                    if (neutText != null) {
                        builder.onNeutral((d, w) -> {
                            try { holder[0] = new JSONObject().put("action", "neutral"); }
                            catch (JSONException ignored) {}
                            latch.countDown();
                        });
                    }

                    builder.cancelListener(d -> {
                        try { holder[0] = new JSONObject().put("action", "cancel"); }
                        catch (JSONException ignored) {}
                        latch.countDown();
                    });

                    boolean cancelable = props.optBoolean("cancelable", true);
                    builder.cancelable(cancelable);

                    builder.show();
                } catch (Throwable e) {
                    try { holder[0] = new JSONObject().put("action", "error")
                            .put("error", e.getMessage()); }
                    catch (JSONException ignored) {}
                    latch.countDown();
                }
            });
            latch.await(120, java.util.concurrent.TimeUnit.SECONDS);
            return holder[0].toString();
        } catch (Throwable e) {
            try { return new JSONObject().put("action", "error")
                    .put("error", e.getMessage()).toString(); }
            catch (JSONException je) { return "{\"action\":\"error\",\"error\":\"unknown\"}"; }
        }
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
                argsInit = "var __args = JSON.parse('" + escaped + "');\n";
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
            com.stardust.autojs.execution.ExecutionConfig config = buildExecConfig(configJson);
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
            return info.toString();
        } catch (Throwable e) {
            return "{\"handle\":0,\"id\":-1,\"source\":\"QuickJS current engine\","
                    + "\"engineName\":\"QuickJsJavaScriptEngine\",\"destroyed\":false}";
        }
    }

    public String enginesAll() {
        try {
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject(enginesMyEngineId()));
            for (Map.Entry<Long, com.stardust.autojs.execution.ScriptExecution> entry
                    : mEngineSessions.entrySet()) {
                com.stardust.autojs.execution.ScriptExecution execution = entry.getValue();
                com.stardust.autojs.engine.ScriptEngine engine = execution.getEngine();
                if (engine != null && engine.isDestroyed()) {
                    mEngineSessions.remove(entry.getKey(), execution);
                    continue;
                }
                JSONObject obj = new JSONObject();
                obj.put("handle", entry.getKey());
                obj.put("id", execution.getId());
                obj.put("source", String.valueOf(execution.getSource()));
                obj.put("engineName", engine.getClass().getSimpleName());
                obj.put("destroyed", false);
                arr.put(obj);
            }
            return arr.toString();
        } catch (Throwable e) { return "[]"; }
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
        com.stardust.autojs.execution.ExecutionConfig config = new com.stardust.autojs.execution.ExecutionConfig();
        if (configJson == null || configJson.isEmpty()) return config;
        try {
            JSONObject json = new JSONObject(configJson);
            if (json.has("delay")) config.setDelay(json.optLong("delay", 0));
            if (json.has("interval")) config.setInterval(json.optLong("interval", 0));
            if (json.has("loopTimes")) config.setLoopTimes(json.optInt("loopTimes", 1));
            if (json.has("path")) config.setWorkingDirectory(json.optString("path", ""));
        } catch (JSONException ignored) {}
        return config;
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
    private final java.util.concurrent.atomic.AtomicInteger mNextFloatyId =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private final Map<Integer, QuickJsFloatyWindow> mFloatyWindows = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> mFloatyEvents =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    interface FloatyEventSink {
        void emit(String eventJson);
    }

    public String floatyCreate(String configJson) {
        try {
            JSONObject config = new JSONObject(configJson);
            int id = mNextFloatyId.getAndIncrement();
            Context context = mRuntime.uiHandler.getContext();
            QuickJsFloatyWindow window = new QuickJsFloatyWindow(
                    context, id, config, uiInflater(), mRuntime.ui.getResourceParser(), mFloatyEvents::add);
            mFloatyWindows.put(id, window);
            if (!window.show()) {
                mFloatyWindows.remove(id);
                return "-1";
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
        for (QuickJsFloatyWindow window : new ArrayList<>(mFloatyWindows.values())) {
            window.close();
        }
        mFloatyWindows.clear();
        mFloatyEvents.clear();
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

    private static final class QuickJsFloatyWindow {
        private final Context mContext;
        private final int mId;
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
        private volatile boolean mAdjustable;
        private boolean mTouchable;
        private float mTouchStartX;
        private float mTouchStartY;
        private int mStartX;
        private int mStartY;

        QuickJsFloatyWindow(Context context, int id, JSONObject config,
                            com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater inflater,
                            com.stardust.autojs.core.ui.inflater.ResourceParser resourceParser,
                            FloatyEventSink eventSink) {
            mContext = context;
            mId = id;
            mConfig = config;
            mInflater = inflater;
            mResourceParser = resourceParser;
            mEventSink = eventSink;
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mRoot = new android.widget.FrameLayout(context);
            mTouchable = config.optBoolean("touchable", false);
            mRoot.setOnTouchListener((view, event) -> {
                if (!mTouchable) {
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
                mTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                        (float) c.optDouble("textSize", 14));
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
                mRoot.addView(mContent, new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
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
        }

        boolean show() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return showNow();
            }
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] result = new boolean[1];
            mHandler.post(() -> {
                result[0] = showNow();
                latch.countDown();
            });
            try {
                return latch.await(8, TimeUnit.SECONDS) && result[0];
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private boolean showNow() {
            try {
                if (mShown) {
                    return true;
                }
                applyConfig();
                mWindowManager.addView(mRoot, mParams);
                mShown = true;
                Log.i("QuickJsFloatyWindow", "Floaty window " + mId + " shown");
                return true;
            } catch (Throwable error) {
                Log.e("QuickJsFloatyWindow", "addView failed", error);
                mShown = false;
                return false;
            }
        }

        void update(String configJson) {
            mHandler.post(() -> {
                try {
                    JSONObject c = new JSONObject(configJson);
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
                        mTouchable = c.optBoolean("touchable", false);
                    }
                    boolean updated = false;
                    if (c.has("width") || c.has("height")) {
                        if (c.has("width")) {
                            mParams.width = c.optInt("width", mParams.width);
                        }
                        if (c.has("height")) {
                            mParams.height = c.optInt("height", mParams.height);
                        }
                        updated = true;
                    }
                    if (c.has("x") || c.has("y")) {
                        if (c.has("x")) {
                            mParams.x = c.optInt("x", mParams.x);
                        }
                        if (c.has("y")) {
                            mParams.y = c.optInt("y", mParams.y);
                        }
                        updated = true;
                    }
                    if (updated && mShown) {
                        mWindowManager.updateViewLayout(mRoot, mParams);
                    }
                } catch (Throwable error) {
                    Log.w("QuickJsFloatyWindow", "update failed", error);
                }
            });
        }

        void close() {
            mHandler.post(() -> {
                try {
                    if (mShown) {
                        mWindowManager.removeViewImmediate(mRoot);
                        mShown = false;
                    }
                } catch (Throwable ignored) {
                    mShown = false;
                }
            });
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
            final String[] result = new String[]{""};
            final CountDownLatch latch = new CountDownLatch(1);
            mHandler.post(() -> {
                View view = findViewById(id);
                if (view instanceof android.widget.TextView) {
                    result[0] = ((android.widget.TextView) view).getText().toString();
                }
                latch.countDown();
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return result[0];
        }

        void setViewText(String id, String text) {
            mHandler.post(() -> {
                View view = findViewById(id);
                if (view instanceof android.widget.TextView) {
                    ((android.widget.TextView) view).setText(text == null ? "" : text);
                }
            });
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

        void setAdjustable(boolean enabled) {
            mAdjustable = enabled;
            if (enabled) {
                // Rhino's adjust mode lets the user drag the window; reuse the
                // existing touch-drag path so the switch has a visible effect.
                mTouchable = true;
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
            final int[] result = new int[1];
            final CountDownLatch latch = new CountDownLatch(1);
            mHandler.post(() -> {
                result[0] = mRoot.getWidth();
                latch.countDown();
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return result[0];
        }

        int getWindowHeight() {
            final int[] result = new int[1];
            final CountDownLatch latch = new CountDownLatch(1);
            mHandler.post(() -> {
                result[0] = mRoot.getHeight();
                latch.countDown();
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return result[0];
        }

        private interface ViewCallback<T> {
            T apply(View view);
        }

        /** 在 main 线程找到控件并取值（找不到时回调收到 null）。 */
        private <T> T withView(final String id, final ViewCallback<T> callback) {
            final Object[] result = new Object[1];
            final CountDownLatch latch = new CountDownLatch(1);
            mHandler.post(() -> {
                result[0] = callback.apply(findViewById(id));
                latch.countDown();
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            @SuppressWarnings("unchecked")
            T typed = (T) result[0];
            return typed;
        }
    }

    // ---- ui (minimal: DynamicLayoutInflater + fullscreen overlay) ----
    private com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater mUiInflater;
    private volatile View mUiRoot;
    private volatile boolean mUiShown;
    private volatile int mUiWindowId;

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
            case "width": return String.valueOf(view.getWidth());
            case "height": return String.valueOf(view.getHeight());
            case "id": return String.valueOf(view.getId());
            case "className": return view.getClass().getName();
            default: return null;
        }
    }

    static boolean writeViewAttribute(View view, String name, String value,
                                      com.stardust.autojs.core.ui.inflater.ResourceParser parser) {
        if (view == null || name == null) {
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
            Log.w("QuickJsHostBridge", "Cannot set view attribute " + name, error);
        }
        try {
            switch (name) {
                case "text":
                    if (!(view instanceof android.widget.TextView)) return false;
                    ((android.widget.TextView) view).setText(value == null ? "" : value);
                    return true;
                case "textSize":
                    if (!(view instanceof android.widget.TextView)) return false;
                    ((android.widget.TextView) view).setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_SP, Float.parseFloat(value));
                    return true;
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
        final boolean[] result = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mDialogHandler.post(() -> {
            result[0] = uiFind(id) != null;
            latch.countDown();
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    public boolean uiViewAction(int viewId, String id, String action) {
        final boolean[] result = new boolean[1];
        final CountDownLatch latch = new CountDownLatch(1);
        mDialogHandler.post(() -> {
            result[0] = performViewAction(uiFind(id), action);
            latch.countDown();
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
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
        }
        return mUiInflater;
    }

    public String uiInflate(String xml) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        mDialogHandler.post(() -> {
            try {
                View root = uiInflater().inflate(xml);
                WindowManager windowManager =
                        (WindowManager) mRuntime.uiHandler.getContext()
                                .getSystemService(Context.WINDOW_SERVICE);
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
                windowManager.addView(root, params);
                mUiRoot = root;
                mUiShown = true;
                mUiWindowId++;
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

    public void uiClose() {
        mDialogHandler.post(() -> {
            try {
                if (mUiRoot != null && mUiShown) {
                    WindowManager windowManager =
                            (WindowManager) mRuntime.uiHandler.getContext()
                                    .getSystemService(Context.WINDOW_SERVICE);
                    windowManager.removeViewImmediate(mUiRoot);
                }
            } catch (Throwable ignored) {
            }
            mUiRoot = null;
            mUiShown = false;
            mUiEvents.clear();
        });
    }

    public void uiSetConfig(int viewId, String id, String configJson) {
        mDialogHandler.post(() -> {
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
        });
    }

    public String uiGetText(int viewId, String id) {
        final String[] result = new String[]{""};
        final CountDownLatch latch = new CountDownLatch(1);
        mDialogHandler.post(() -> {
            View view = uiFind(id);
            if (view instanceof android.widget.TextView) {
                result[0] = ((android.widget.TextView) view).getText().toString();
            }
            latch.countDown();
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
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
        final String[] result = new String[]{""};
        final CountDownLatch latch = new CountDownLatch(1);
        mDialogHandler.post(() -> {
            String value = readViewAttribute(uiFind(id), name, mRuntime.ui.getResourceParser());
            result[0] = value == null ? "" : value;
            latch.countDown();
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
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
            "click", "longClick", "accessibilityFocus", "clearAccessibilityFocus", "focus", "clearFocus",
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
            "clickable", "longClickable", "enabled", "password", "scrollable",
    };

    private final AtomicLong mNextAutomatorHandle = new AtomicLong(1);
    private final Map<Long, Object> mAutomatorHandles = new ConcurrentHashMap<>();
    private final Map<String, Method> mAutomatorMethods = new ConcurrentHashMap<>();

    public long selectorCreate() {
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
                return encodeAutomatorResult(mRuntime.accessibilityBridge.getService() != null);
            case "rootActive":
            case "rootCurrent": {
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
                return encodeAutomatorResult(invokeAutomator(selector, method,
                        new Class<?>[]{ int.class, int.class },
                        new Object[]{ args.getInt(0), args.getInt(1) }));
            case "setProgress":
                return encodeAutomatorResult(invokeAutomator(selector, method,
                        new Class<?>[]{ float.class }, new Object[]{ (float) args.getDouble(0) }));
            case "find":
            case "untilFind":
            case "untilFindOne":
            case "exists":
                return encodeAutomatorResult(invokeAutomator(selector, method, NO_PARAMS, NO_ARGS));
            case "waitFor":
                invokeAutomator(selector, method, NO_PARAMS, NO_ARGS);
                return AUTOMATOR_VOID_RESULT;
            case "findOnce":
                return args.length() > 0 && !args.isNull(0)
                        ? encodeAutomatorResult(invokeAutomator(selector, "findOnce",
                        new Class<?>[]{ int.class }, new Object[]{ args.getInt(0) }))
                        : encodeAutomatorResult(invokeAutomator(selector, "findOnce", NO_PARAMS, NO_ARGS));
            case "findOne":
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
        String result = QuickJsNativeBridge.invokeJsCallback(engineHandle, callbackId, argsJson);
        return result == null ? "{}" : result;
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

    private final QuickJsJavaInterop mJavaInterop = new QuickJsJavaInterop();

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

    /** native 侧按句柄取回 Java 对象（Mat → 帧桥使用）。 */
    public Object javaObjectForHandle(long handle) {
        return mJavaInterop.objectForHandle(handle);
    }

    /** `'m'` 有方法 / `'f'` 有字段 / 空串都没有。 */
    public String javaProbe(long handle, String name) {
        return mJavaInterop.probe(handle, name);
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

    private boolean evaluateJsNodePredicate(long callbackId, UiObject node) {
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
            case "bounds":
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
                return encodeAutomatorResult(invokeAutomator(object, method,
                        new Class<?>[]{ int.class, int.class },
                        new Object[]{ args.getInt(0), args.getInt(1) }));
            case "setProgress":
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
                return encodeAutomatorResult(invokeAutomator(collection, method,
                        new Class<?>[]{ int.class, int.class },
                        new Object[]{ args.getInt(0), args.getInt(1) }));
            case "setProgress":
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
        return cached.invoke(target, arguments);
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

    public long sqliteOpen(String name, int version) {
        Database database = version > 0
                ? mRuntime.sqlite.open(name, version)
                : mRuntime.sqlite.open(name);
        long handle = mNextSqliteHandle.getAndIncrement();
        mSqliteHandles.put(handle, database);
        return handle;
    }

    /**
     * 数据库方法分派：`exec`/`select`/`insert`/`update`/`delete`/`close` 与事务开关。
     * 结果统一编码为 JSON（`{"insertId":n,"rowsAffected":m,"rows":[...]}`），无结果返回 "null"。
     */
    public String sqliteCall(long handle, String method, String argsJson) throws JSONException {
        Database database = mSqliteHandles.get(handle);
        if (database == null) {
            throw new IllegalStateException("数据库句柄已失效（连接可能已关闭）");
        }
        JSONArray args = new JSONArray(argsJson == null || argsJson.isEmpty() ? "[]" : argsJson);
        switch (method == null ? "" : method) {
            case "exec": {
                String sql = args.getString(0);
                if (args.isNull(1)) {
                    return encodeSqliteResult(database.exec(sql));
                }
                database.executeSql(sql, toSqliteArgs(args.optJSONArray(1)));
                return "null";
            }
            case "select": {
                String sql = args.getString(0);
                DatabaseResultSet result = args.isNull(1)
                        ? database.select(sql)
                        : database.select(sql, toSqliteArgs(args.optJSONArray(1)));
                return encodeSqliteResult(result);
            }
            case "insert":
                return encodeSqliteResult(database.insert(args.getString(0), toSqliteValues(args.getJSONObject(1))));
            case "update":
                return encodeSqliteResult(database.update(args.getString(0), toSqliteValues(args.getJSONObject(1)),
                        args.isNull(2) ? null : args.getString(2), toSqliteArgs(args.optJSONArray(3))));
            case "delete":
                return encodeSqliteResult(database.delete(args.getString(0),
                        args.isNull(1) ? null : args.getString(1), toSqliteArgs(args.optJSONArray(2))));
            case "begin":
                sqliteWritable(database).beginTransaction();
                return "null";
            case "end":
                android.database.sqlite.SQLiteDatabase writable = sqliteWritable(database);
                if (writable.inTransaction()) {
                    if (args.optBoolean(0)) {
                        writable.setTransactionSuccessful();
                    }
                    writable.endTransaction();
                }
                return "null";
            case "close":
                database.close();
                mSqliteHandles.remove(handle);
                return "null";
            default:
                throw new IllegalArgumentException("sqlite 不支持的方法: " + method);
        }
    }

    /**
     * 事务：`Database` 只提供回调式 transaction，而回调要跨层调回 JS。
     * 这里（宿主内部，非脚本可见）通过私有 `writable()` 拿同一个连接开关事务，
     * 脚本层用 try/catch 包住，语义与 Rhino 的 begin/commit/rollback 一致。
     */
    private android.database.sqlite.SQLiteDatabase sqliteWritable(Database database) {
        try {
            Method method = Database.class.getDeclaredMethod("writable");
            method.setAccessible(true);
            return (android.database.sqlite.SQLiteDatabase) method.invoke(database);
        } catch (Exception e) {
            throw new IllegalStateException("无法开启数据库事务: " + e.getMessage(), e);
        }
    }

    private String encodeSqliteResult(Object result) throws JSONException {
        if (result == null) {
            return "null";
        }
        if (!(result instanceof DatabaseResultSet)) {
            return "null";
        }
        DatabaseResultSet set = (DatabaseResultSet) result;
        JSONArray rows = new JSONArray();
        if (set.rows != null) {
            for (int i = 0; i < set.rows.length; i++) {
                Object row = set.rows.item(i);
                rows.put(row == null ? new JSONObject() : new JSONObject((Map<?, ?>) row));
            }
        }
        JSONObject object = new JSONObject();
        object.put("insertId", set.insertId);
        object.put("rowsAffected", set.rowsAffected);
        object.put("rows", rows);
        return object.toString();
    }

    private Object[] toSqliteArgs(JSONArray array) throws JSONException {
        if (array == null) {
            return null;
        }
        Object[] result = new Object[array.length()];
        for (int i = 0; i < array.length(); i++) {
            result[i] = array.isNull(i) ? null : array.get(i);
        }
        return result;
    }

    private Map<String, Object> toSqliteValues(JSONObject object) throws JSONException {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            values.put(key, object.isNull(key) ? null : object.get(key));
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

    @Override
    public void close() {
        for (String name : new ArrayList<>(mSharedBusSubscriptions)) {
            sharedBusOff(name);
        }
        eventsStopAll();
        drawClose();
        mediaStopMusic();
        sensorsUnregisterAll();
        floatyCloseAll();
        uiClose();
        releaseAutomatorHandles();
        closeSqliteHandles();
        mJavaInterop.clear();
        for (android.app.AlertDialog dialog : new ArrayList<>(pendingDialogRegistry.values())) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        pendingDialogRegistry.clear();
        mDialogs.clear();
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
        mEngineResults.clear();
    }

    // ---- drawing overlay whitelist ----

    private static int sImmersiveCount = 0;
    private volatile QuickJsOverlay mOverlay;

    public boolean drawCreate() {
        Context context = mRuntime.uiHandler.getContext();
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return false;
        }
        try {
            // Multiple engine runs can leave overlay windows behind when a script is
            // force-stopped before the host bridge closes. Make the overlay unique:
            // reap every previous overlay before creating the new one.
            QuickJsOverlay.closeAll();
            mOverlay = new QuickJsOverlay(context);
            if (!mOverlay.show()) {
                mOverlay = null;
                return false;
            }
            // Hide status/navigation bars while drawing so capture coordinates and
            // the overlay stay aligned, and the drawn boxes are not overlapped by
            // system UI.
            applySystemUi(mRuntime.app == null ? null : mRuntime.app.getCurrentActivity(), true);
            return true;
        } catch (Throwable error) {
            Log.w("QuickJsHostBridge", "Cannot create drawing overlay", error);
            mOverlay = null;
            return false;
        }
    }

    public void drawUpdate(String detectionsJson, String statsText) {
        QuickJsOverlay overlay = mOverlay;
        if (overlay == null) return;
        try {
            JSONArray array = new JSONArray(detectionsJson == null ? "[]" : detectionsJson);
            List<Detection> detections = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                detections.add(new Detection(
                        (float) item.optDouble("x1"), (float) item.optDouble("y1"),
                        (float) item.optDouble("x2"), (float) item.optDouble("y2"),
                        item.optString("label", ""),
                        (float) item.optDouble("score")));
            }
            overlay.update(detections, statsText == null ? "" : statsText);
        } catch (JSONException ignored) {
            // malformed payload: keep the previous frame
        }
    }

    public void drawClose() {
        QuickJsOverlay overlay = mOverlay;
        mOverlay = null;
        if (overlay != null) overlay.close();
        applySystemUi(mRuntime.app == null ? null : mRuntime.app.getCurrentActivity(), false);
    }

    private static void applySystemUi(Activity activity, boolean immersive) {
        if (activity == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // Reference-counted: multiple script instances may hide the system
                // bars at the same time. Only restore them when the last instance
                // closes, otherwise the capture size changes and remaining overlay
                // boxes misalign with the screen.
                if (immersive) {
                    sImmersiveCount++;
                } else {
                    sImmersiveCount = Math.max(0, sImmersiveCount - 1);
                }
                View decor = activity.getWindow().getDecorView();
                if (sImmersiveCount > 0) {
                    decor.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                } else {
                    decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                }
            } catch (Throwable error) {
                Log.w("QuickJsHostBridge", "Cannot update system UI visibility", error);
            }
        });
    }

    private static final class Detection {
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final String label;
        final float score;

        Detection(float x1, float y1, float x2, float y2, String label, float score) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.label = label;
            this.score = score;
        }
    }

    private static final class QuickJsOverlay {
        private static final String TAG = "QuickJsOverlay";
        private static final java.util.Set<QuickJsOverlay> sAllOverlays =
                Collections.synchronizedSet(new java.util.HashSet<>());

        private final WindowManager mWindowManager;
        private final OverlayView mView;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private volatile boolean mShown;

        QuickJsOverlay(Context context) {
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mView = new OverlayView(context);
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
                    mWindowManager.addView(mView, params);
                    mShown = true;
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
            if (!statsText.isEmpty()) {
                float textWidth = mStatsPaint.measureText(statsText);
                canvas.drawRoundRect(new RectF(10, 10, 24 + textWidth, 54), 12, 12, mStatsBgPaint);
                canvas.drawText(statsText, 24, 42, mStatsPaint);
            }
            for (Detection detection : detections) {
                canvas.drawRect(new RectF(detection.x1, detection.y1,
                        detection.x2, detection.y2), mBoxPaint);
                String caption = detection.label + " " + Math.round(detection.score * 100) + "%";
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
