package com.jdkshen.aijspro.inrt.script;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import com.jdkshen.aijspro.inrt.BuildConfig;
import com.jdkshen.aijspro.inrt.autojs.AutoJs;
import com.jdkshen.aijspro.inrt.debuglog.InrtLogBridge;
import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.core.console.ConsoleImpl;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dalvik.system.DexClassLoader;

/**
 * inrt :script 进程的「主线程 Looper 引擎宿主」（task-113 B）。
 *
 * <p>目标：让脚本引擎、工程 dex/so 解压与 native 初始化、脚本执行全部落在 :script 进程的
 * 主线程 Looper 上，和 AI.js Pro 打包运行时的模型一致（Pro 真机证据：ImGuiBridge 的
 * EGL pid==tid==main，nativeInit/控件调用/脚本都在 :script 进程主线程）。这样第三方
 * libImGuiBridge.so 的 JNI_OnLoad / ImGui context 创建线程与后续 nativeNewFrame/nativeButton
 * 调用线程一致，GImGui 上下文不会跨线程。
 *
 * <p>接口（A=task-112 的 InrtScriptActivity 通过反射调用，类名/方法名勿改）：
 * <ul>
 *   <li>{@link #initialize(Context)}：:script 进程主线程调用一次，成功返回 true；false/异常时
 *       A 回退主进程执行并打 C-INRT: fallback；</li>
 *   <li>{@link #postOnScriptThread(Runnable)}：A 用它把 GlobalProjectLauncher.launch(activity)
 *       整条流程投到脚本线程（:script 进程主 Looper）执行；引擎 execute/native 加载因此同线程；</li>
 *   <li>{@link #executeOnScriptThread(Runnable)}：同 postOnScriptThread 的语义别名；</li>
 *   <li>{@link #assertThread(String)}、{@link #shutdown()}、{@link #isInitialized()} 供诊断/收尾。</li>
 * </ul>
 *
 * <p>日志统一前缀 {@code C-INRT:}：process/thread、host ready、dex prepared、native order、
 * post/execute start-end、异常。
 */
public final class InrtScriptThreadHost {

    private static final String TAG = "C-INRT";
    /** postAndWait 的默认上限：初始化/收尾这类必须同步完成的小步骤用。 */
    private static final long DEFAULT_WAIT_MS = 5000L;

    private static final Object LOCK = new Object();

    private static volatile boolean sInitialized;
    private static volatile Handler sScriptHandler;
    private static volatile Looper sScriptLooper;
    private static volatile Thread sScriptThread;
    /** 工程动态 dex（files/project/libs/*.dex|jar、files/project/.codecache/*.dex）的加载器。 */
    private static volatile ClassLoader sProjectClassLoader;
    private static volatile boolean sBootstrapDone;

    /** Log-only VSCode bridge. Default off; enabled by project.json or the debug SharedPreferences key. */
    private static final String LOG_BRIDGE_ENABLED_KEY = "inrt_log_bridge_enabled";
    private static final String LOG_BRIDGE_DEVICE_ID_KEY = "inrt_log_bridge_device_id";
    private static final Object LOG_BRIDGE_LOCK = new Object();
    private static volatile InrtLogBridge sLogBridge;

    private InrtScriptThreadHost() {
    }

    /**
     * 初始化 :script 进程的脚本线程宿主。
     *
     * <p>必须由 A 的 InrtScriptActivity.onCreate（:script 主线程）调用；返回 true 才允许执行脚本。
     * 这里不新建 HandlerThread，而是用 :script 进程自己的主 Looper 作为脚本 Looper —— 这与 Pro
     * 的打包运行时一致，也让 ScriptExecuteActivity/触摸等 UI 回调天然回到脚本线程（同一线程）。
     */
    public static boolean initialize(Context context) {
        if (context == null) {
            Log.e(TAG, "C-INRT: initialize failed: context == null");
            return false;
        }
        if (sInitialized) {
            return true;
        }
        synchronized (LOCK) {
            if (sInitialized) {
                return true;
            }
            try {
                String process = currentProcessName(context);
                Looper mainLooper = Looper.getMainLooper();
                Thread current = Thread.currentThread();
                Log.i(TAG, "C-INRT: process=" + process + " thread=" + current.getName() + "/" + current.getId()
                        + " initialize begin");
                if (mainLooper == null) {
                    Log.e(TAG, "C-INRT: initialize failed: main looper == null");
                    return false;
                }
                // 1) 进程级上下文：:script 进程的 Application.onCreate 应该已经初始化；缺失则补一次。
                ensureGlobalAppContext(context);
                ensureInrtAutoJs(context);
                // 2) 脚本线程 = :script 进程主线程（Pro 模型）。
                Handler handler = new Handler(mainLooper);
                sScriptLooper = mainLooper;
                sScriptThread = mainLooper.getThread();
                sScriptHandler = handler;
                // 3) dex -> so 顺序的工程侧准备（全部在当前线程 = 脚本线程）。
                runBootstrapOnScriptThread(context);
                sInitialized = true;
                // The bridge is a log-only debug tap; only the :script process owns the
                // global console containing script logs. It must never run in the main process.
                if (process != null && process.endsWith(":script")) {
                    startLogBridgeIfEnabled(context);
                }
                Log.i(TAG, "C-INRT: process=" + process + " thread=" + sScriptThread.getName()
                        + "/" + sScriptThread.getId() + " HOST READY (script looper = process main looper)");
                return true;
            } catch (Throwable error) {
                Log.e(TAG, "C-INRT: initialize failed", error);
                return false;
            }
        }
    }

    /**
     * 把任务投递到脚本线程（:script 进程主 Looper）；当前已在脚本线程则直接执行。
     * A 用这个方法跑 GlobalProjectLauncher.launch(activity)，从而 engine.execute 与 native 加载同线程。
     */
    public static void postOnScriptThread(final Runnable task) {
        if (task == null) {
            return;
        }
        Handler handler = sScriptHandler;
        if (!sInitialized || handler == null) {
            Log.e(TAG, "C-INRT: post rejected: host not initialized");
            return;
        }
        if (isScriptThread()) {
            runTaskOnScriptThread(task, "inline");
            return;
        }
        boolean posted = handler.post(new Runnable() {
            @Override
            public void run() {
                runTaskOnScriptThread(task, "post");
            }
        });
        if (!posted) {
            Log.e(TAG, "C-INRT: post failed (looper quitting)");
        }
    }

    /** {@link #postOnScriptThread(Runnable)} 的语义别名（保留给 B 内部/直调用）。 */
    public static void executeOnScriptThread(Runnable task) {
        postOnScriptThread(task);
    }

    /**
     * 投递并等待任务完成（必须在非脚本线程调用；脚本线程内调用会直接执行）。
     * 用于 A 需要确认「脚本线程上 xx 已完成」的场景（可选，A 通常用 postOnScriptThread）。
     */
    public static boolean postAndWait(final Runnable task, long timeoutMs) {
        long waitMs = timeoutMs <= 0 ? DEFAULT_WAIT_MS : timeoutMs;
        if (task == null) {
            return false;
        }
        if (isScriptThread()) {
            runTaskOnScriptThread(task, "inline-wait");
            return true;
        }
        Handler handler = sScriptHandler;
        if (!sInitialized || handler == null) {
            Log.e(TAG, "C-INRT: postAndWait rejected: host not initialized");
            return false;
        }
        final CountDownLatch done = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    runTaskOnScriptThread(task, "wait");
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            return done.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "C-INRT: postAndWait interrupted");
            return false;
        }
    }

    /** 当前线程是否是脚本线程；不符合时打 W（含 what 便于定位）。 */
    public static boolean assertThread(String what) {
        boolean onScriptThread = isScriptThread();
        if (!onScriptThread) {
            Thread current = Thread.currentThread();
            Log.w(TAG, "C-INRT: NOT on script thread what=" + what + " thread=" + current.getName()
                    + "/" + current.getId() + " expected=" + (sScriptThread == null ? "null" : sScriptThread.getName()));
        }
        return onScriptThread;
    }

    public static boolean isScriptThread() {
        Looper looper = sScriptLooper;
        return looper != null && Looper.myLooper() == looper;
    }

    public static boolean isInitialized() {
        return sInitialized;
    }

    public static Looper getScriptLooper() {
        return sScriptLooper;
    }

    public static Handler getScriptHandler() {
        return sScriptHandler;
    }

    public static ClassLoader getProjectClassLoader() {
        return sProjectClassLoader;
    }

    /** 当前进程名（:script 等），日志/诊断用。 */
    public static String getProcessName(Context context) {
        return currentProcessName(context);
    }

    /**
     * 工程解压完成后再做一次 dex->so 准备（AssetsProjectLauncher.prepare() 之后调用）。
     * 由 A 的 launcher 在 :script 脚本线程上调用；主进程/未初始化时静默跳过。
     */
    public static void prepareProjectOnScriptThread(Context context) {
        if (context == null || !sInitialized) {
            return;
        }
        if (!isScriptThread()) {
            Log.w(TAG, "C-INRT: prepareProjectOnScriptThread skipped: thread="
                    + Thread.currentThread().getName() + " is not script thread");
            return;
        }
        sBootstrapDone = false;
        runBootstrapOnScriptThread(context);
    }

    /** 进程收尾（A 不主动调用；用于 :script 进程结束/测试）。主 Looper 不 quit，只清引用。 */
    public static void shutdown() {
        stopLogBridge();
        synchronized (LOCK) {
            if (!sInitialized) {
                return;
            }
            Handler handler = sScriptHandler;
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
            sInitialized = false;
            sBootstrapDone = false;
            sScriptHandler = null;
            sScriptLooper = null;
            sScriptThread = null;
            Log.i(TAG, "C-INRT: host shutdown");
        }
    }

    // ------------------------------------------------------------------ log-only VSCode bridge

    private static void startLogBridgeIfEnabled(Context context) {
        synchronized (LOG_BRIDGE_LOCK) {
            if (sLogBridge != null) {
                return;
            }
            if (!isLogBridgeEnabled(context)) {
                Log.d(TAG, "C-INRT: log bridge disabled (launchConfig.logBridge=false, pref=false)");
                return;
            }
            AutoJs autoJs;
            try {
                autoJs = AutoJs.Companion.getInstance();
            } catch (Throwable error) {
                Log.w(TAG, "C-INRT: log bridge skipped: inrt AutoJs unavailable", error);
                return;
            }
            Object console = autoJs.getGlobalConsole();
            if (!(console instanceof ConsoleImpl)) {
                Log.w(TAG, "C-INRT: log bridge skipped: globalConsole is not ConsoleImpl");
                return;
            }
            InrtLogBridge bridge = new InrtLogBridge(
                    InrtLogBridge.DEFAULT_PORT,
                    new InrtLogBridge.Hello(
                            "AI.js Pro",
                            (Build.BRAND + " " + Build.MODEL).trim(),
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                            logBridgeDeviceId(context)),
                    message -> Log.w(TAG, "C-INRT-LOG-BRIDGE: " + message));
            try {
                bridge.start();
                bridge.attach(new ConsoleLogSource((ConsoleImpl) console));
                sLogBridge = bridge;
                Log.i(TAG, "C-INRT: log bridge started (log-only) port=" + InrtLogBridge.DEFAULT_PORT);
            } catch (Throwable error) {
                Log.w(TAG, "C-INRT: log bridge start failed (continue)", error);
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void stopLogBridge() {
        synchronized (LOG_BRIDGE_LOCK) {
            InrtLogBridge bridge = sLogBridge;
            sLogBridge = null;
            if (bridge != null) {
                try {
                    bridge.close();
                    Log.i(TAG, "C-INRT: log bridge stopped");
                } catch (Throwable error) {
                    Log.w(TAG, "C-INRT: log bridge stop failed", error);
                }
            }
        }
    }

    private static boolean isLogBridgeEnabled(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        // Debug/runtime escape hatch for manual verification; default false.
        if (preferences.getBoolean(LOG_BRIDGE_ENABLED_KEY, false)) {
            Log.i(TAG, "C-INRT: log bridge enabled by SharedPreferences " + LOG_BRIDGE_ENABLED_KEY);
            return true;
        }
        // Packaged project switch: assets/project/project.json -> launchConfig.logBridge.
        try (InputStream input = context.getAssets().open("project/project.json")) {
            byte[] bytes = readAll(input);
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONObject launchConfig = root.optJSONObject("launchConfig");
            boolean enabled = launchConfig != null && launchConfig.optBoolean("logBridge", false);
            if (enabled) {
                Log.i(TAG, "C-INRT: log bridge enabled by assets/project/project.json launchConfig.logBridge");
            }
            return enabled;
        } catch (Exception error) {
            Log.d(TAG, "C-INRT: log bridge project.json unavailable: " + error.getMessage());
            return false;
        }
    }

    private static String logBridgeDeviceId(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String existing = preferences.getString(LOG_BRIDGE_DEVICE_ID_KEY, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String id = UUID.randomUUID().toString();
        preferences.edit().putString(LOG_BRIDGE_DEVICE_ID_KEY, id).apply();
        return id;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /** Adapter from :script AutoJs.globalConsole (same backing listener as ConsoleView). */
    private static final class ConsoleLogSource implements InrtLogBridge.LogSource {
        private final ConsoleImpl console;
        private final CopyOnWriteArrayList<InrtLogBridge.Listener> listeners = new CopyOnWriteArrayList<>();
        private final ConsoleImpl.LogListener consoleListener = new ConsoleImpl.LogListener() {
            @Override
            public void onNewLog(ConsoleImpl.LogEntry logEntry) {
                String line = logEntry.content == null ? "" : logEntry.content.toString();
                for (InrtLogBridge.Listener listener : listeners) {
                    try {
                        listener.onLog(line);
                    } catch (Throwable ignored) {
                        // A broken network client must not break the console callback.
                    }
                }
            }

            @Override
            public void onLogClear() {
            }
        };

        ConsoleLogSource(ConsoleImpl console) {
            this.console = console;
        }

        @Override
        public void addListener(InrtLogBridge.Listener listener) {
            if (listeners.isEmpty()) {
                console.addLogListener(consoleListener);
            }
            listeners.addIfAbsent(listener);
        }

        @Override
        public void removeListener(InrtLogBridge.Listener listener) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                console.removeLogListener(consoleListener);
            }
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private static void runTaskOnScriptThread(Runnable task, String mode) {
        long start = System.currentTimeMillis();
        Log.d(TAG, "C-INRT: execute start mode=" + mode + " thread=" + Thread.currentThread().getName());
        try {
            task.run();
        } catch (Throwable error) {
            Log.e(TAG, "C-INRT: script task failed mode=" + mode, error);
            // task-156 P0.5：这条失败原来只进 logcat（用户 LogActivity 列表全空）。三通道投递：
            // ① logcat（上面这行）；② 既有「可见启动提示」通道（AI.js Pro console -> LogActivity）；③ 主线程 Toast。
            // 注意：logcat 会把 TAG 渲染成 `C-INRT  :`（冒号前有空格），grep 要搜 `script task failed` 这类正文，别搜 `C-INRT:`。
            final String failureText = "脚本无法启动：密钥初始化失败（可能是签名证书/包名/加密盐与产物不一致）。"
                    + "请用与当前安装包一致的证书重新打包。原因：" + error;
            try {
                com.jdkshen.aijspro.inrt.autojs.AutoJs autoJs = com.jdkshen.aijspro.inrt.autojs.AutoJs.Companion.getInstance();
                if (autoJs != null && autoJs.getGlobalConsole() != null) {
                    autoJs.getGlobalConsole().println(android.util.Log.WARN, failureText);
                }
            } catch (Throwable consoleError) {
                Log.w("CINRT-PROBE", "console delivery failed", consoleError);
            }
            try {
                android.content.Context appContext = com.stardust.app.GlobalAppContext.get();
                if (appContext != null) {
                    final android.content.Context toastContext = appContext.getApplicationContext();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        @Override public void run() {
                            try {
                                android.widget.Toast.makeText(toastContext, failureText, android.widget.Toast.LENGTH_LONG).show();
                            } catch (Throwable toastError) {
                                Log.w("CINRT-PROBE", "toast failed", toastError);
                            }
                        }
                    });
                }
            } catch (Throwable toastPostError) {
                Log.w("CINRT-PROBE", "toast post failed", toastPostError);
            }
        } finally {
            Log.d(TAG, "C-INRT: execute end mode=" + mode + " cost=" + (System.currentTimeMillis() - start) + "ms");
        }
    }

    /** :script 进程 Application.onCreate 正常跑过时什么都不用做；缺失时补 GlobalAppContext。 */
    private static void ensureGlobalAppContext(Context context) {
        try {
            GlobalAppContext.get();
            return;
        } catch (Throwable ignored) {
            // 没初始化，下面补
        }
        Context appContext = context.getApplicationContext();
        if (appContext instanceof Application) {
            GlobalAppContext.set((Application) appContext);
            Log.i(TAG, "C-INRT: GlobalAppContext initialized in process=" + currentProcessName(context));
        }
    }

    /**
     * 确保 inrt 的进程内单例 AI.js Pro 已创建（App.onCreate 正常会做；这里只兜底）。
     * 用反射访问，避免 host 对 inrt AI.js Pro 的编译期依赖（A 的类可能持续改动）。
     */
    private static void ensureInrtAutoJs(Context context) throws Exception {
        try {
            ScriptEngineService service = ScriptEngineService.getInstance();
            if (service != null) {
                return;
            }
        } catch (Throwable ignored) {
            // getInstance 不存在/非静态时忽略，继续反射 inrt AI.js Pro
        }
        Class<?> autoJsClass;
        try {
            autoJsClass = Class.forName("com.jdkshen.aijspro.inrt.autojs.AutoJs");
        } catch (ClassNotFoundException notFound) {
            Log.w(TAG, "C-INRT: inrt AutoJs class not found (non-inrt build?)");
            return;
        }
        Object companion = autoJsClass.getField("Companion").get(null);
        Class<?> companionClass = Class.forName(autoJsClass.getName() + "$Companion");
        try {
            Object instance = companionClass.getMethod("getInstance").invoke(companion);
            if (instance != null) {
                return;
            }
        } catch (Throwable uninitialized) {
            // lateinit 未初始化 → 下面补
        }
        Context appContext = context.getApplicationContext();
        if (appContext instanceof Application) {
            companionClass.getMethod("initInstance", Application.class).invoke(companion, appContext);
            Log.i(TAG, "C-INRT: inrt AutoJs initialized in :script process");
        }
    }

    /**
     * dex -> so 顺序的工程侧准备，全部在脚本线程（:script 主线程）执行。
     *
     * <p>Pro 的顺序：先把工程 libs/.codecache 的动态 dex 准备好，再让 native 库加载；
     * 第三方 .so（如 libImGuiBridge.so）由工程 JS 自己 System.load，这里只做清单与顺序日志，
     * 不重复 load（避免同一 .so 两次 JNI_OnLoad/两套 ImGui 全局导致 GImGui 分裂）。
     */
    private static void runBootstrapOnScriptThread(Context context) {
        if (sBootstrapDone) {
            return;
        }
        sBootstrapDone = true;
        String process = currentProcessName(context);
        Thread thread = Thread.currentThread();
        Log.i(TAG, "C-INRT: process=" + process + " thread=" + thread.getName() + "/" + thread.getId()
                + " bootstrap dex->so begin");
        File projectDir = new File(context.getFilesDir(), "project");
        try {
            List<File> dexFiles = new ArrayList<>();
            collectFiles(new File(projectDir, "libs"), dexFiles, true, 3);
            collectFiles(new File(projectDir, ".codecache"), dexFiles, true, 3);
            if (!dexFiles.isEmpty()) {
                File optDir = new File(context.getCacheDir(), "inrt-project-dex");
                if (!optDir.isDirectory() && !optDir.mkdirs()) {
                    Log.w(TAG, "C-INRT: dex opt dir create failed: " + optDir);
                }
                StringBuilder path = new StringBuilder();
                for (File dex : dexFiles) {
                    if (path.length() > 0) {
                        path.append(File.pathSeparator);
                    }
                    path.append(dex.getAbsolutePath());
                }
                sProjectClassLoader = new DexClassLoader(path.toString(), optDir.getAbsolutePath(),
                        null, context.getClassLoader());
                Log.i(TAG, "C-INRT: dex prepared count=" + dexFiles.size() + " loader=" + sProjectClassLoader
                        + " thread=" + thread.getName());
            } else {
                Log.i(TAG, "C-INRT: dex prepared count=0 (project libs/.codecache 尚未解压或为空) thread=" + thread.getName());
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-INRT: project dex prepare failed (continue, script 可在脚本线程自行加载)", error);
        }
        try {
            List<File> soFiles = new ArrayList<>();
            collectFiles(new File(projectDir, "libs"), soFiles, false, 3);
            collectFiles(new File(projectDir, "lib"), soFiles, false, 3);
            Log.i(TAG, "C-INRT: native order dex->so done; so inventory=" + soFiles.size()
                    + "（由工程 JS 在脚本线程 System.load，保证 JNI_OnLoad/ImGui context 同线程）"
                    + " thread=" + thread.getName());
        } catch (Throwable error) {
            Log.w(TAG, "C-INRT: so inventory failed (continue)", error);
        }
    }

    private static void collectFiles(File dir, List<File> out, boolean dexOnly, int depth) {
        if (dir == null || depth < 0 || !dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, out, dexOnly, depth - 1);
                continue;
            }
            String name = child.getName().toLowerCase();
            if (dexOnly) {
                if (name.endsWith(".dex") || name.endsWith(".jar")) {
                    out.add(child);
                }
            } else if (name.endsWith(".so")) {
                out.add(child);
            }
        }
    }

    private static String currentProcessName(Context context) {
        try {
            if (context != null) {
                String name = Application.getProcessName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // API < 28 没有 Application.getProcessName()
        }
        try {
            byte[] data = new byte[256];
            int size = readProcSelfCmdline(data);
            if (size > 0) {
                int end = 0;
                while (end < size && data[end] != 0) {
                    end++;
                }
                return new String(data, 0, end, "UTF-8");
            }
        } catch (Throwable ignored) {
            // 读不到就退化
        }
        return "unknown";
    }

    private static int readProcSelfCmdline(byte[] data) throws IOException {
        java.io.FileInputStream input = null;
        try {
            input = new java.io.FileInputStream("/proc/self/cmdline");
            return input.read(data);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
