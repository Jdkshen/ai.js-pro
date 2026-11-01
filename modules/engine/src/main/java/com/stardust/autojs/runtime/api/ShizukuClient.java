package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.stardust.autojs.execution.ScriptProcessIdentity;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * Per-process Shizuku bootstrap and diagnostic state.
 *
 * <p>The Shizuku server delivers its binder to the process that hosts
 * {@code ShizukuProvider} (the app's main process here). Script, Node and renderer
 * processes need the SDK's multi-process bridge to obtain the same binder. This
 * class is deliberately process-local: every process stores its own cached state,
 * registers its own listeners and (at most once) asks the provider for the binder.</p>
 *
 * <p>Threading contract: {@link #attach(Context)} is safe on the main thread and must
 * be called from {@code Application.attachBaseContext}. {@link #ensureConnected(long)}
 * is worker-only because it waits for a binder. Permission and
 * {@code ContentResolver} work is never started from the main thread; UI callers can
 * use {@link #warmUp()} / {@link #state()} and read the cached, including unknown,
 * values.</p>
 */
public final class ShizukuClient {

    private static final String TAG = "ShizukuClient";
    private static final String PROVIDER_CLASS = "rikka.shizuku.ShizukuProvider";
    private static final int MIN_SDK = Build.VERSION_CODES.M;
    private static final long MAX_CONNECT_WAIT_MILLIS = 60_000L;
    private static final long WAIT_SLICE_MILLIS = 200L;

    private static final Object LOCK = new Object();
    private static final Object STATE_LOCK = new Object();
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean(false);
    private static final AtomicBoolean RECEIVED_LISTENER_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean DEAD_LISTENER_REGISTERED = new AtomicBoolean(false);

    private static final ExecutorService CONNECT_EXECUTOR = Executors.newSingleThreadExecutor(
            daemonThreadFactory("Shizuku-connect"));
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadExecutor(
            daemonThreadFactory("Shizuku-state"));

    private static volatile Context applicationContext;
    private static volatile boolean attached;
    private static volatile boolean sdkSupported;
    private static volatile boolean providerProcess;
    private static volatile String processName = "";
    private static volatile boolean binderRequestStarted;
    private static volatile boolean binderRequestSettled;
    private static volatile boolean binderAlive;
    private static volatile Boolean permissionGranted;
    private static volatile Integer backendUid;
    private static volatile Integer serverVersion;
    private static volatile String lastError;
    private static volatile long binderGeneration;

    private ShizukuClient() {
    }

    /**
     * Prepares this process before providers and the rest of {@code Application.onCreate}
     * run. Safe to call more than once; only the first call determines the process role.
     *
     * <p>The main process hosts the provider and therefore takes the provider role.
     * Child processes take the non-provider role so they can pull the binder from the
     * provider. No binder request or permission query happens here.</p>
     */
    public static void attach(Context context) {
        try {
            attachInternal(context);
        } catch (Throwable error) {
            recordError("Shizuku 初始化失败: " + message(error));
            Log.w(TAG, "attach failed", error);
        }
    }

    private static void attachInternal(Context context) {
        if (context == null) return;

        Context appContext;
        try {
            appContext = context.getApplicationContext();
        } catch (Throwable ignored) {
            appContext = null;
        }
        if (appContext == null) appContext = context;

        final boolean firstAttach;
        synchronized (LOCK) {
            if (!attached) {
                applicationContext = appContext;
                processName = safeProcessName();
                sdkSupported = Build.VERSION.SDK_INT >= MIN_SDK;
                providerProcess = isProviderProcess(appContext.getPackageName(), processName);
                attached = true;
                firstAttach = true;
            } else {
                if (applicationContext == null) applicationContext = appContext;
                firstAttach = false;
            }
        }

        if (!sdkSupported) {
            recordError("Android 6.0 以下不支持 Shizuku");
            return;
        }

        if (firstAttach) {
            try {
                ProviderBridge.enableMultiProcessSupport(providerProcess);
            } catch (Throwable error) {
                recordError("启用 Shizuku 多进程支持失败: " + message(error));
                Log.w(TAG, "ShizukuProvider multi-process init failed", error);
            }
        }

        ensureListeners();
    }

    /**
     * Starts the one allowed binder lookup in this process. Never blocks: the actual
     * provider lookup runs on a daemon worker. Subsequent calls are no-ops, so the SDK's
     * broadcast receiver is registered at most once per process.
     */
    public static void warmUp() {
        if (!attached || !sdkSupported) return;
        ensureListeners();
        if (probeBinderAlive()) {
            scheduleRefreshIfNeeded();
            return;
        }
        startBinderRequest();
    }

    /**
     * Waits on a worker for the Shizuku binder, bounded by {@code timeoutMillis}.
     *
     * @return true when a binder is available; it does not imply that permission was
     * granted. Returns false immediately on the main thread, before attach, on API &lt; 23
     * or when the bounded wait expires.
     */
    public static boolean ensureConnected(long timeoutMillis) {
        if (!attached) {
            recordError("ShizukuClient 尚未初始化");
            return false;
        }
        if (!sdkSupported) {
            recordError("Android 6.0 以下不支持 Shizuku");
            return false;
        }
        if (isMainThread()) {
            recordError("ensureConnected 只允许在工作线程等待 Shizuku Binder");
            return false;
        }

        ensureListeners();
        long boundedTimeout = Math.max(0L,
                Math.min(timeoutMillis, MAX_CONNECT_WAIT_MILLIS));
        long deadline = SystemClock.elapsedRealtime() + boundedTimeout;

        if (probeBinderAlive()) {
            refreshRemoteState();
            return true;
        }

        startBinderRequest();
        while (true) {
            if (probeBinderAlive()) {
                refreshRemoteState();
                return true;
            }

            boolean requestFinishedWithoutBinder;
            synchronized (LOCK) {
                requestFinishedWithoutBinder = !providerProcess && binderRequestSettled;
            }
            if (requestFinishedWithoutBinder) {
                return false;
            }

            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) return false;

            synchronized (STATE_LOCK) {
                try {
                    STATE_LOCK.wait(Math.min(remaining, WAIT_SLICE_MILLIS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    recordError("等待 Shizuku Binder 被中断");
                    return false;
                }
            }
        }
    }

    /** The application context supplied by the first {@link #attach(Context)} call. */
    public static Context context() {
        return applicationContext;
    }

    /** True when a live Shizuku binder is visible from this process. */
    public static boolean isBinderAlive() {
        if (!attached || !sdkSupported) return false;
        return probeBinderAlive();
    }

    /**
     * Reads the cached authorization state. On a worker this refreshes from the binder;
     * on the main thread it only returns the cache and schedules a background refresh.
     */
    public static boolean hasPermission() {
        if (!attached || !sdkSupported) return false;
        if (!probeBinderAlive()) return false;
        if (isMainThread()) {
            scheduleRefreshIfNeeded();
            return Boolean.TRUE.equals(permissionGranted);
        }
        refreshRemoteState();
        return Boolean.TRUE.equals(permissionGranted);
    }

    /**
     * A snapshot for diagnostics / JS. Unknown values are JSON {@code null}; in
     * particular {@code backendUid} and {@code serverVersion} are never fabricated.
     * This method is safe to call from the main thread: it does not request permission
     * and does not use {@code ContentResolver}.
     */
    public static JSONObject state() {
        if (attached && sdkSupported) {
            if (probeBinderAlive()) {
                if (isMainThread()) {
                    scheduleRefreshIfNeeded();
                } else {
                    refreshRemoteState();
                }
            } else {
                // A diagnostic call is also a valid on-demand trigger; the lookup remains
                // on a worker and this method still returns immediately.
                warmUp();
            }
        }

        final boolean supported;
        final String name;
        final boolean alive;
        final Boolean permission;
        final Integer uid;
        final Integer version;
        final String error;
        if (attached) {
            synchronized (LOCK) {
                supported = sdkSupported;
                name = processName == null || processName.isEmpty() ? null : processName;
                alive = binderAlive;
                permission = permissionGranted;
                uid = backendUid;
                version = serverVersion;
                error = lastError;
            }
        } else {
            supported = Build.VERSION.SDK_INT >= MIN_SDK;
            String currentName = safeProcessName();
            name = currentName.isEmpty() ? null : currentName;
            alive = false;
            permission = null;
            uid = null;
            version = null;
            error = null;
        }
        return stateJson(supported, alive, permission, uid, version, name, error);
    }

    /** Records the result of an explicit permission request for other callers. */
    static void onPermissionResult(boolean granted) {
        synchronized (LOCK) {
            permissionGranted = granted;
            if (granted) lastError = null;
        }
        signalStateChanged();
        if (granted) scheduleRefresh();
    }

    /** Adds a diagnostic error without touching binder state. */
    static void reportError(String error) {
        if (error == null || error.isEmpty()) return;
        recordError(error);
    }

    /**
     * Pure JSON assembly used by {@link #state()} and by the targeted JVM tests.
     * Package-private so the test does not need an Android runtime.
     */
    static JSONObject stateJson(boolean sdkSupported, boolean binderAlive,
                                Boolean permissionGranted, Integer backendUid,
                                Integer serverVersion, String processName,
                                String lastError) {
        JSONObject result = new JSONObject();
        try {
            result.put("sdkSupported", sdkSupported);
            result.put("binderAlive", binderAlive);
            result.put("permissionGranted",
                    permissionGranted == null ? JSONObject.NULL : permissionGranted);
            result.put("backendUid", backendUid == null ? JSONObject.NULL : backendUid);
            result.put("serverVersion",
                    serverVersion == null ? JSONObject.NULL : serverVersion);
            result.put("processName", processName == null ? JSONObject.NULL : processName);
            result.put("lastError", lastError == null ? JSONObject.NULL : lastError);
        } catch (JSONException ignored) {
            // All values above are valid JSON primitives; keep the partial object.
        }
        return result;
    }

    /** Main-process detection reused by diagnostics; provider lives in the main process. */
    static boolean isProviderProcess(String packageName, String candidateProcessName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return candidateProcessName == null
                || candidateProcessName.isEmpty()
                || packageName.equals(candidateProcessName);
    }

    private static void ensureListeners() {
        if (RECEIVED_LISTENER_REGISTERED.compareAndSet(false, true)) {
            try {
                Shizuku.addBinderReceivedListenerSticky(ShizukuClient::handleBinderReceived);
            } catch (Throwable error) {
                RECEIVED_LISTENER_REGISTERED.set(false);
                recordError("注册 Shizuku Binder 监听失败: " + message(error));
                Log.w(TAG, "add binder received listener failed", error);
            }
        }
        if (DEAD_LISTENER_REGISTERED.compareAndSet(false, true)) {
            try {
                Shizuku.addBinderDeadListener(ShizukuClient::handleBinderDead);
            } catch (Throwable error) {
                DEAD_LISTENER_REGISTERED.set(false);
                recordError("注册 Shizuku Binder 死亡监听失败: " + message(error));
                Log.w(TAG, "add binder dead listener failed", error);
            }
        }
    }

    private static void startBinderRequest() {
        synchronized (LOCK) {
            if (binderRequestStarted) return;
            binderRequestStarted = true;
            binderRequestSettled = false;
        }

        CONNECT_EXECUTOR.execute(() -> {
            try {
                if (!providerProcess) {
                    Context context = applicationContext;
                    if (context == null) {
                        recordError("Shizuku 多进程 Binder 请求缺少 Context");
                    } else {
                        // This is the only call that registers the SDK broadcast receiver.
                        ProviderBridge.requestBinderForNonProviderProcess(context);
                    }
                }
                if (probeBinderAlive()) {
                    refreshRemoteState();
                }
            } catch (Throwable error) {
                recordError("请求 Shizuku Binder 失败: " + message(error));
                Log.w(TAG, "request binder failed", error);
            } finally {
                synchronized (LOCK) {
                    binderRequestSettled = true;
                }
                signalStateChanged();
            }
        });
    }

    private static boolean probeBinderAlive() {
        if (!attached || !sdkSupported) return false;
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable error) {
            alive = false;
        }
        if (alive) {
            if (!binderAlive) handleBinderReceived();
        } else if (binderAlive) {
            handleBinderDead();
        }
        return alive;
    }

    private static void handleBinderReceived() {
        try {
            synchronized (LOCK) {
                binderAlive = true;
                binderGeneration++;
                permissionGranted = null;
                backendUid = null;
                serverVersion = null;
                lastError = null;
                binderRequestSettled = true;
            }
            signalStateChanged();
            scheduleRefresh();
        } catch (Throwable error) {
            Log.w(TAG, "binder received handler failed", error);
        }
    }

    private static void handleBinderDead() {
        try {
            synchronized (LOCK) {
                if (!binderAlive) return;
                binderAlive = false;
                binderGeneration++;
                permissionGranted = null;
                backendUid = null;
                serverVersion = null;
                lastError = "Shizuku 服务已断开，等待重新连接";
                // A later broadcast may still deliver a new binder; allow callers that
                // are already waiting to stay within their own bounded timeout.
                binderRequestSettled = false;
            }
            signalStateChanged();
        } catch (Throwable error) {
            Log.w(TAG, "binder dead handler failed", error);
        }
    }

    private static void scheduleRefreshIfNeeded() {
        if (!binderAlive) return;
        if (permissionGranted == null || backendUid == null || serverVersion == null) {
            scheduleRefresh();
        }
    }

    private static void scheduleRefresh() {
        if (!attached || !sdkSupported) return;
        if (!REFRESH_PENDING.compareAndSet(false, true)) return;
        REFRESH_EXECUTOR.execute(() -> {
            try {
                refreshRemoteState();
            } finally {
                REFRESH_PENDING.set(false);
            }
        });
    }

    private static void refreshRemoteState() {
        if (!attached || !sdkSupported) return;
        if (isMainThread()) {
            scheduleRefresh();
            return;
        }
        if (!probeBinderAlive()) return;

        final long generation;
        synchronized (LOCK) {
            generation = binderGeneration;
        }

        Boolean granted = null;
        Integer uid = null;
        Integer version = null;
        String error = null;
        try {
            granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable failure) {
            error = "读取 Shizuku 授权状态失败: " + message(failure);
        }

        if (!probeBinderAlive()) return;

        try {
            int value = Shizuku.getUid();
            if (value >= 0) uid = value;
        } catch (Throwable failure) {
            if (error == null) error = "读取 Shizuku 后端 UID 失败: " + message(failure);
        }

        try {
            int value = Shizuku.getVersion();
            if (value > 0) version = value;
        } catch (Throwable failure) {
            if (error == null) error = "读取 Shizuku 服务版本失败: " + message(failure);
        }

        synchronized (LOCK) {
            if (generation != binderGeneration) return;
            if (granted != null) permissionGranted = granted;
            if (uid != null) backendUid = uid;
            if (version != null) serverVersion = version;
            lastError = error;
        }
        signalStateChanged();
    }

    private static void recordError(String error) {
        if (error == null || error.isEmpty()) return;
        synchronized (LOCK) {
            lastError = error;
        }
        signalStateChanged();
    }

    private static void signalStateChanged() {
        synchronized (STATE_LOCK) {
            STATE_LOCK.notifyAll();
        }
    }

    private static boolean isMainThread() {
        try {
            Looper mainLooper = Looper.getMainLooper();
            return mainLooper != null && Looper.myLooper() == mainLooper;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String safeProcessName() {
        try {
            String name = ScriptProcessIdentity.name();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof InvocationTargetException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    private static ThreadFactory daemonThreadFactory(final String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * The engine module compiles against the Shizuku API AAR only; the provider AAR is
     * packaged by the application modules. Reflection avoids a build dependency change
     * and still calls the official public API on the real application class.
     *
     * <p>Release shrinking must keep these two static provider methods (or the app must
     * add the provider AAR as a compile-only engine dependency and call them directly);
     * otherwise R8 can remove methods that are only reached by name.</p>
     */
    private static final class ProviderBridge {
        private static boolean resolved;
        private static Method enableMethod;
        private static Method requestMethod;

        private ProviderBridge() {
        }

        static synchronized void enableMultiProcessSupport(boolean isProviderProcess)
                throws Exception {
            resolve();
            try {
                enableMethod.invoke(null, isProviderProcess);
            } catch (InvocationTargetException error) {
                throw rethrow(error);
            }
        }

        static synchronized void requestBinderForNonProviderProcess(Context context)
                throws Exception {
            resolve();
            try {
                requestMethod.invoke(null, context);
            } catch (InvocationTargetException error) {
                throw rethrow(error);
            }
        }

        private static void resolve() throws Exception {
            if (resolved) return;
            Class<?> provider = Class.forName(PROVIDER_CLASS);
            enableMethod = provider.getMethod("enableMultiProcessSupport", boolean.class);
            requestMethod = provider.getMethod(
                    "requestBinderForNonProviderProcess", Context.class);
            resolved = true;
        }

        private static Exception rethrow(InvocationTargetException error) {
            Throwable cause = error.getTargetException();
            return cause instanceof Exception ? (Exception) cause : new Exception(cause);
        }
    }
}
