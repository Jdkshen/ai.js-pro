package com.stardust.autojs.runtime;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.R;
import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.annotation.ScriptVariable;
import com.stardust.autojs.core.accessibility.AccessibilityBridge;
import com.stardust.autojs.core.image.ColorMapping;
import com.stardust.autojs.core.image.Colors;
import com.stardust.autojs.core.permission.Permissions;
import com.stardust.autojs.rhino.AndroidClassLoader;
import com.stardust.autojs.rhino.TopLevelScope;
import com.stardust.autojs.rhino.continuation.Continuation;
import com.stardust.autojs.runtime.api.AbstractShell;
import com.stardust.autojs.runtime.api.AppUtils;
import com.stardust.autojs.runtime.api.Console;
import com.stardust.autojs.runtime.api.Crypto;
import com.stardust.autojs.runtime.api.Debug;
import com.stardust.autojs.runtime.api.Device;
import com.stardust.autojs.runtime.api.Engines;
import com.stardust.autojs.runtime.api.Events;
import com.stardust.autojs.runtime.api.Files;
import com.stardust.autojs.runtime.api.Floaty;
import com.stardust.autojs.core.looper.Loopers;
import com.stardust.autojs.runtime.api.Media;
import com.stardust.autojs.runtime.api.MemoryLeakDetector;
import com.stardust.autojs.runtime.api.remote.RemoteAccessibilityBridge;
import com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge;
import com.stardust.autojs.runtime.api.remote.RemoteUiSelector;
import com.stardust.autojs.runtime.api.Plugins;
import com.stardust.autojs.runtime.api.PowerManager;
import com.stardust.autojs.runtime.api.Sensors;
import com.stardust.autojs.runtime.api.Sqlite;
import com.stardust.autojs.runtime.api.Threads;
import com.stardust.autojs.runtime.api.Timers;
import com.stardust.autojs.runtime.api.Yolo;
import com.stardust.autojs.runtime.api.Zips;
import com.stardust.autojs.core.accessibility.UiSelector;
import com.stardust.autojs.runtime.api.Images;
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester;
import com.stardust.autojs.runtime.api.Dialogs;
import com.stardust.autojs.runtime.exception.ScriptEnvironmentException;
import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.core.accessibility.SimpleActionAutomator;
import com.stardust.autojs.runtime.api.UI;
import com.stardust.concurrent.VolatileDispose;
import com.stardust.lang.ThreadCompat;
import com.stardust.pio.UncheckedIOException;
import com.stardust.util.ClipboardUtil;
import com.stardust.autojs.core.util.ProcessShell;
import com.stardust.util.ScreenMetrics;
import com.stardust.util.SdkVersionUtil;
import com.stardust.util.Supplier;
import com.stardust.util.UiHandler;
import com.stardust.autojs.core.activity.ActivityInfoProvider;

import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.ScriptStackElement;
import org.mozilla.javascript.Scriptable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Created by Stardust on 2017/1/27.
 */

public class ScriptRuntime {

    private static final String TAG = "ScriptRuntime";

    /**
     * 把 {@code events.emit("exit")} 投递到脚本 Looper 并等待执行完的最长时间。
     * 超时只记录日志并继续退出清理（exit 监听可能自己很慢），避免脚本故意/无意
     * 写死循环把整个应用退出流程卡死。
     * 注：等待线程是 Android 主线程时会被进一步压到 {@link #MAX_MAIN_THREAD_WAIT_MS}，
     * 避免退出清理把主线程卡到 ANR。
     */
    private static final long EXIT_EMIT_TIMEOUT_MS = 2000L;

    /** 主线程上单次等待脚本 Looper 的上限（防止退出流程把主线程卡到 ANR）。 */
    public static final long MAX_MAIN_THREAD_WAIT_MS = 1000L;


    public static class Builder {
        private UiHandler mUiHandler;
        private Console mConsole;
        private AccessibilityBridge mAccessibilityBridge;
        private Supplier<AbstractShell> mShellSupplier;
        private ScreenCaptureRequester mScreenCaptureRequester;
        private AppUtils mAppUtils;
        private ScriptEngineService mEngineService;

        public Builder() {

        }

        public Builder setUiHandler(UiHandler uiHandler) {
            mUiHandler = uiHandler;
            return this;
        }

        public Builder setConsole(Console console) {
            mConsole = console;
            return this;
        }

        public Builder setAccessibilityBridge(AccessibilityBridge accessibilityBridge) {
            mAccessibilityBridge = accessibilityBridge;
            return this;
        }

        public Builder setShellSupplier(Supplier<AbstractShell> shellSupplier) {
            mShellSupplier = shellSupplier;
            return this;
        }

        public Builder setScreenCaptureRequester(ScreenCaptureRequester requester) {
            mScreenCaptureRequester = requester;
            return this;
        }

        public Builder setAppUtils(AppUtils appUtils) {
            mAppUtils = appUtils;
            return this;
        }

        public Builder setEngineService(ScriptEngineService service) {
            mEngineService = service;
            return this;
        }


        public ScriptRuntime build() {
            return new ScriptRuntime(this);
        }

    }


    @ScriptVariable
    public final AppUtils app;

    @ScriptVariable
    public final Console console;

    @ScriptVariable
    public final SimpleActionAutomator automator;

    @ScriptVariable
    public final ActivityInfoProvider info;

    @ScriptVariable
    public final UI ui;

    /** Auto.js Pro exposes the UI module also as {@code $ui}; mirror that alias. */
    @ScriptVariable
    public final UI $ui;

    @ScriptVariable
    public final Dialogs dialogs;

    @ScriptVariable
    public Events events;

    @ScriptVariable
    public final ScriptBridges bridges = new ScriptBridges();

    @ScriptVariable
    public Loopers loopers;

    @ScriptVariable
    public Timers timers;

    @ScriptVariable
    public Device device;

    @ScriptVariable
    public final AccessibilityBridge accessibilityBridge;

    @ScriptVariable
    public final Engines engines;

    @ScriptVariable
    public Threads threads;

    @ScriptVariable
    public final Floaty floaty;

    @ScriptVariable
    public UiHandler uiHandler;

    @ScriptVariable
    public final Colors colors = new Colors();

    @ScriptVariable
    public final Files files;

    @ScriptVariable
    public Sensors sensors;

    @ScriptVariable
    public final Media media;

    @ScriptVariable
    public final Plugins plugins;

    @ScriptVariable
    public final Yolo yolo;

    @ScriptVariable
    public final Sqlite sqlite;

    @ScriptVariable
    public final Crypto crypto;

    @ScriptVariable
    public final Zips zips;

    @ScriptVariable
    public final Debug debug;

    @ScriptVariable
    public final PowerManager powerManager;

    private Images images;

    private static WeakReference<Context> applicationContext;
    private Map<String, Object> mProperties = new ConcurrentHashMap<>();
    private final java.util.Set<AutoCloseable> mCleanables =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<AutoCloseable, Boolean>());
    private AbstractShell mRootShell;
    private Supplier<AbstractShell> mShellSupplier;
    private ScreenMetrics mScreenMetrics = new ScreenMetrics();
    private Thread mThread;
    /** 当前脚本引擎 id，用于浮窗注册/清理（OverlayRegistry）。 */
    private int mEngineId = -1;
    private TopLevelScope mTopLevelScope;


    public int getEngineId() {
        return mEngineId;
    }

    public void setEngineId(int engineId) {
        mEngineId = engineId;
    }

    protected ScriptRuntime(Builder builder) {
        uiHandler = builder.mUiHandler;
        Context context = uiHandler.getContext();
        app = builder.mAppUtils;
        console = builder.mConsole;
        AccessibilityBridge accessibilityBridge = builder.mAccessibilityBridge;
        // Phase1 P1：:script 进程里 AccessibilityService 只在主进程，把本地桥包装成远程桥，
        // 让 auto()/auto.waitFor() 等可用性检查走主进程（见 RemoteAccessibilityBridge）。
        if (RemoteAutomationBridge.isIsolationProcess() && accessibilityBridge != null
                && GlobalAppContext.get() != null) {
            accessibilityBridge = new RemoteAccessibilityBridge(accessibilityBridge);
        }
        this.accessibilityBridge = accessibilityBridge;
        mShellSupplier = builder.mShellSupplier;
        ui = new UI(context, this);
        $ui = ui;
        this.automator = new SimpleActionAutomator(accessibilityBridge, this);
        automator.setScreenMetrics(mScreenMetrics);
        this.info = accessibilityBridge.getInfoProvider();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            images = new Images(context, this, builder.mScreenCaptureRequester);
        }
        engines = new Engines(builder.mEngineService, this);
        dialogs = new Dialogs(this);
        device = new Device(context);
        floaty = new Floaty(uiHandler, ui, this);
        files = new Files(this);
        media = new Media(context, this);
        plugins = new Plugins(context, this);
        yolo = new Yolo(context);
        sqlite = new Sqlite(context, files, this);
        crypto = new Crypto();
        zips = new Zips(context);
        debug = new Debug(this);
        powerManager = new PowerManager(context);
    }

    public void init() {
        if (loopers != null)
            throw new IllegalStateException("already initialized");
        if (RemoteAutomationBridge.isIsolationProcess()) {
            // :script 进程：自动化 API 走主进程 AIDL 代理（RemoteAutomationBridge + AutomationHostClient）。
            Log.i(TAG, "isolated script process, remote automation active="
                    + RemoteAutomationBridge.isActive());
        }
        threads = new Threads(this);
        timers = new Timers(this);
        loopers = new Loopers(this);
        events = new Events(uiHandler.getContext(), accessibilityBridge, this);
        mThread = Thread.currentThread();
        sensors = new Sensors(uiHandler.getContext(), this);
    }

    public TopLevelScope getTopLevelScope() {
        return mTopLevelScope;
    }

    public void setTopLevelScope(TopLevelScope topLevelScope) {
        if (mTopLevelScope != null) {
            throw new IllegalStateException("top level has already exists");
        }
        mTopLevelScope = topLevelScope;
    }

    public static void setApplicationContext(Context context) {
        applicationContext = new WeakReference<>(context);
    }

    public static Context getApplicationContext() {
        if (applicationContext == null || applicationContext.get() == null) {
            throw new ScriptEnvironmentException("No application context");
        }
        return applicationContext.get();
    }

    public UiHandler getUiHandler() {
        return uiHandler;
    }

    public AccessibilityBridge getAccessibilityBridge() {
        return accessibilityBridge;
    }

    public void toast(final String text) {
        uiHandler.toast(text);
    }

    public void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new ScriptInterruptedException();
        }
    }

    public void setClip(final String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ClipboardUtil.setClip(uiHandler.getContext(), text);
            return;
        }
        VolatileDispose<Object> dispose = new VolatileDispose<>();
        uiHandler.post(() -> {
            ClipboardUtil.setClip(uiHandler.getContext(), text);
            dispose.setAndNotify(text);
        });
        dispose.blockedGet();
    }

    public String getClip() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ClipboardUtil.getClipOrEmpty(uiHandler.getContext()).toString();
        }
        final VolatileDispose<String> clip = new VolatileDispose<>();
        uiHandler.post(() -> clip.setAndNotify(ClipboardUtil.getClipOrEmpty(uiHandler.getContext()).toString()));
        return clip.blockedGetOrThrow(ScriptInterruptedException.class);
    }

    public AbstractShell getRootShell() {
        ensureRootShell();
        return mRootShell;
    }

    private void ensureRootShell() {
        if (mRootShell == null) {
            mRootShell = mShellSupplier.get();
            mRootShell.SetScreenMetrics(mScreenMetrics);
            mShellSupplier = null;
        }
    }

    public AbstractShell.Result shell(String cmd, int root) {
        return ProcessShell.execCommand(cmd, root != 0);
    }

    /**
     * 脚本选择器入口（Rhino {@code __selector__.js} 会把返回对象的方法安装成全局选择器函数）。
     *
     * <p>Phase1 P1：:script 进程里无障碍在主进程，返回 {@link RemoteUiSelector}（节点查询/动作走
     * 主进程 AIDL）；主进程/本地模式仍返回原来的 {@link UiSelector}。返回类型改为 Object 是为了
     * 兼容两种实现，调用方都是动态脚本侧，不涉及 Java 静态类型。
     */
    public Object selector() {
        if (RemoteAutomationBridge.isActive()) {
            return new RemoteUiSelector();
        }
        return new UiSelector(accessibilityBridge);
    }

    public boolean isStopped() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Object engine = engines == null ? null : engines.myEngine();
        return engine instanceof com.stardust.autojs.engine.RhinoJavaScriptEngine
                && ((com.stardust.autojs.engine.RhinoJavaScriptEngine) engine).isStopRequested();
    }

    public static void requiresApi(int i) {
        if (Build.VERSION.SDK_INT < i) {
            throw new ScriptException(GlobalAppContext.getString(R.string.text_requires_sdk_version_to_run_the_script) + SdkVersionUtil.sdkIntToString(i));
        }
    }

    public void requestPermissions(String[] permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        Context context = uiHandler.getContext();
        permissions = Permissions.getPermissionsNeedToRequest(context, permissions);
        if (permissions.length == 0)
            return;
        Permissions.requestPermissions(context, permissions);
    }

    public void loadJar(String path) {
        path = files.path(path);
        try {
            ((AndroidClassLoader) ContextFactory.getGlobal().getApplicationClassLoader()).loadJar(new File(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void loadDex(String path) {
        path = files.path(path);
        try {
            ((AndroidClassLoader) ContextFactory.getGlobal().getApplicationClassLoader()).loadDex(new File(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void exit() {
        mThread.interrupt();
        engines.myEngine().forceStop();
        threads.exit();
        // task-162：headless 脚本在主 Looper 上执行时也必须中止后续代码（原来主线程分支
        // 只 interrupt/forceStop 不抛异常，exit() 之后的代码会继续跑）。
        throw new ScriptInterruptedException();
    }

    public void exit(Throwable e) {
        engines.myEngine().uncaughtException(e);
        exit();
    }

    @Deprecated
    public void stop() {
        exit();
    }


    public void setScreenMetrics(int width, int height) {
        mScreenMetrics.setScreenMetrics(width, height);
    }

    public ScreenMetrics getScreenMetrics() {
        return mScreenMetrics;
    }

    public void ensureAccessibilityServiceEnabled() {
        accessibilityBridge.ensureServiceEnabled();
    }

    /**
     * 隔离脚本进程里的 UI 脚本（第三方 native UI/ImGui）不要自动发 exit 事件：
     * 它们的 exit 监听常做 nativeShutdown，会把正在渲染的 native 上下文销毁。
     * 只由用户停止/服务销毁时的显式清理负责。
     */
    public static volatile boolean suppressExitEvent;

    public void onExit() {
        Log.d(TAG, "on exit suppressExitEvent=" + suppressExitEvent
                + " thread=" + Thread.currentThread().getName());
        //清除interrupt状态
        ThreadCompat.interrupted();
        //悬浮窗需要第一时间关闭以免出现恶意脚本全屏悬浮窗屏蔽屏幕并且在exit中写死循环的问题
        ignoresException(floaty::closeAll);
        // 对话框也必须尽早关：它们可能盖住后续脚本/选择器，且比悬浮窗更常见。
        // 放在 timers/events 回收之前，对话框自身的 dismiss 事件/用户回调仍能正常发出
        // （Dialogs.close() 只 dismiss 本 runtime 创建的窗口，不会误关其它引擎窗口）。
        // Dialogs 也注册了 cleanable，close() 幂等；末尾 closeCleanables() 会再兜底一次。
        ignoresException(dialogs::close);
        ignoresException(ColorMapping::recycleSingleton);
        // 图片泄露检测的开关/日志出口是进程级 static，退出时回置到默认值
        // （日志出口抱着本 runtime，不回置会让下个脚本把日志发到已退出的 runtime 上）
        ignoresException(() -> MemoryLeakDetector.reset(Debug.isPackagedApp()));
        // 注意：timers/threads/events/loopers/sensors 只有 runtime.init() 之后才存在。
        // Node24 等「不调用 runtime.init 的引擎」在 destroy 时这些字段仍是 null：老代码
        // ignoresException(timers::recycle) 会在**捕获方法引用**阶段直接 NPE（onExit:482），
        // 顺着 Node24JavaScriptEngine.destroy -> ScriptExecuteActivity.onDestroy 把主进程
        // 带崩（FATAL: Unable to destroy activity）。这里逐个判空：未初始化的 runtime 只做
        // 构造期字段的收尾，绝不因为缺初始化而抛异常。
        // 先停掉所有定时器/帧回调，再触发 exit 监听：第三方 native UI 桥（如自带
        // EGL/ImGui 的 So）常在 exit 里做 nativeShutdown，如果此时渲染帧还在执行，
        // 会把它正在使用的 ImGui/EGL 上下文销毁掉（异常崩溃：GImGui 变 NULL）。
        ignoresException(() -> {
            if (timers != null) timers.recycle();
        });
        if (suppressExitEvent) {
            Log.d(TAG, "skip events.emit(exit): suppressExitEvent");
            // :script 进程不自动发 exit；但脚本自己手动 emit(exit) 的监听已改为投递到下一
            // Looper tick，若引擎在 tick 之前销毁，pending 会丢（nativeShutdown 漏执行）或在
            // Rhino 销毁后才跑。这里取消 pending 并在脚本 Looper 上同步补发一次：
            // 只补「脚本主动 emit」的语义，不触发自动 exit，保持 suppress 的既有意图。
            final Events manualEvents = events;
            if (manualEvents != null) {
                final boolean[] hadPending = new boolean[1];
                ignoresException(() -> hadPending[0] = manualEvents.cancelPendingEmitAsync());
                if (hadPending[0]) {
                    Log.d(TAG, "flush pending manual events.emit(exit) listeners before destroy");
                    postAndWaitOnScriptLooper(() -> {
                        try {
                            manualEvents.emitSync("exit");
                        } catch (Throwable e) {
                            console.error("exception on manual exit: ", e);
                        }
                    }, EXIT_EMIT_TIMEOUT_MS, "manual events.emit(exit) flush");
                }
            }
        } else if (events != null) {
            final Events exitEvents = events;
            // 脚本自己在帧回调里 emit(exit) 的监听已改为投递到下一 Looper tick（EventEmitter），
            // 可能还排在脚本 Looper 上。这里（suppress=false，会统一 emitSync 一次）先取消待执行
            // 派发：否则它们会在 destroy 之后才执行（Rhino 上下文已销毁），或与屏障里的 emitSync
            // 重复执行 nativeShutdown。
            // suppress=true 的进程（:script）见下面的分支：同样先取消，但如果确实取消了 pending
            // 派发，会在屏障里 emitSync 补发一次（不触发自动 exit，只补脚本手动 emit 的清理）。
            // 说明（语义小差异，已与 B 确认可接受）：脚本在 destroy 前多次 emit(exit) 的 N 个
            // pending 派发会收敛成一次 flush；cleanup/nativeShutdown 通常幂等，不影响正确性。
            ignoresException(() -> exitEvents.cancelPendingEmitAsync());
            Runnable emitExit = () -> {
                // 在脚本 Looper 线程上再清一次定时器：把“投递屏障之前”刚被重新排队的
                // 延迟帧回调也清掉，保证 exit 监听执行后不会再触发 nativeNewFrame/nativeRender。
                recycleTimersQuietly();
                try {
                    // 用 emitSync：exit 监听在退出屏障里同步执行完（emit 会把 exit 投递到
                    // 下一 tick，B 的 destroy 清理来不及等它）。
                    exitEvents.emitSync("exit");
                } catch (Throwable e) {
                    console.error("exception on exit: ", e);
                }
            };
            // 把 exit 监听投递到脚本 Looper 串行执行并等待其完成：第三方 native UI 桥
            // （如自带 EGL/ImGui 的 .so）的 exit 监听常做 nativeShutdown；如果 exit 与
            // nativeNewFrame/nativeRender/nativeButton 并发，会销毁正在使用的上下文
            // （GImGui 变 NULL 的 SIGSEGV，见 docs/reports/2026-10-17_ImGui桥native上下文定位.md §3.4/§6 #1）。
            Log.d(TAG, "emit(exit) serialized on script looper, timeout=" + EXIT_EMIT_TIMEOUT_MS + "ms");
            postAndWaitOnScriptLooper(emitExit, EXIT_EMIT_TIMEOUT_MS, "events.emit(exit)");
        }
        ignoresException(() -> {
            if (threads != null) threads.shutDownAll();
        });
        ignoresException(() -> {
            if (events != null) events.recycle();
        });
        ignoresException(media::recycle);
        // Exit listeners can finish database writes before registered resources are closed.
        ignoresException(this::closeCleanables);
        ignoresException(yolo::close);
        ignoresException(() -> {
            if (loopers != null) loopers.recycle();
        });
        ignoresException(() -> {
            if (mRootShell != null) mRootShell.exit();
            mRootShell = null;
            mShellSupplier = null;
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            ignoresException(() -> {
                if (images != null) images.releaseScreenCapturer();
            });
        }
        ignoresException(() -> {
            if (sensors != null) sensors.unregisterAll();
        });
        ignoresException(ui::recycle);
    }

    /** 在脚本 Looper 上再清一次定时器；runtime 未 init（Node24）时 timers 可能为 null。 */
    private void recycleTimersQuietly() {
        try {
            if (timers != null) {
                timers.recycle();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 等脚本 Looper 队列空闲：向脚本 Looper 投递一个空 Runnable 并等待它执行完。
     * Looper 是 FIFO 的，因此这个屏障执行完 = 当前正在执行/已入队的帧回调都已经结束。
     * 没有脚本 Looper 或已经处于脚本 Looper 上时立即返回 true。
     */
    public boolean awaitScriptLooperIdle(long timeoutMs) {
        return postAndWaitOnScriptLooper(() -> {
        }, timeoutMs, "await script looper idle");
    }

    /**
     * 把 runnable 投递到脚本 Looper 执行并等待完成，用于把 exit 清理与渲染帧串行化。
     *
     * <p>等待策略（避免死锁/语义回归）：
     * <ul>
     *   <li>没有脚本 Looper（Node24 等未 init 的 runtime）或当前已在脚本 Looper 上
     *       → 直接同步执行（本来就是串行的）；</li>
     *   <li>Looper 已经 quit（LoopBasedJavaScriptEngine.destroy 会先 quit 脚本 Looper）
     *       → Handler.post 返回 false，回退为同步执行，保持旧行为；</li>
     *   <li>等待超时且任务尚未开始 → 回退同步执行（不能漏掉 exit 清理）；</li>
     *   <li>等待超时但任务已经开始（exit 监听自己很慢）→ 不重复执行（避免 exit 回调执行两次），
     *       只记日志后继续后续清理。</li>
     * </ul>
     *
     * <p>绝不无限等待：超时后一定返回，防止脚本在 exit 里写死循环把应用退出卡死。
     *
     * @return true 表示 runnable 已经执行完；false 表示已开始但超时仍在执行
     */
    private boolean postAndWaitOnScriptLooper(final Runnable runnable, long timeoutMs, String what) {
        final Looper scriptLooper = getScriptLooper();
        if (scriptLooper == null || Looper.myLooper() == scriptLooper) {
            runnable.run();
            return true;
        }
        long effectiveTimeout = timeoutMs;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            effectiveTimeout = Math.min(timeoutMs, MAX_MAIN_THREAD_WAIT_MS);
        }
        final Handler handler = new Handler(scriptLooper);
        final CountDownLatch done = new CountDownLatch(1);
        final Object gate = new Object();
        final AtomicBoolean started = new AtomicBoolean(false);
        final AtomicBoolean canceled = new AtomicBoolean(false);
        Runnable task = () -> {
            synchronized (gate) {
                if (canceled.get()) {
                    // 调用方已回退为同步执行：本任务作废，绝不能把 exit 等清理跑第二次。
                    done.countDown();
                    return;
                }
                started.set(true);
            }
            try {
                runnable.run();
            } finally {
                done.countDown();
            }
        };
        boolean posted;
        try {
            posted = handler.post(task);
        } catch (Throwable error) {
            Log.w(TAG, what + ": post to script looper failed, run inline", error);
            posted = false;
        }
        if (!posted) {
            Log.d(TAG, what + ": script looper is quitting, run inline");
            runnable.run();
            return true;
        }
        try {
            if (done.await(effectiveTimeout, TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            ThreadCompat.interrupted();
            Log.w(TAG, what + ": interrupted while waiting for script looper");
        }
        boolean runInline = false;
        synchronized (gate) {
            if (!started.get()) {
                canceled.set(true);
                runInline = true;
            }
        }
        if (runInline) {
            // 任务还没开始 → 从 Looper 队列移除并在当前线程执行（保持旧行为，且保证只执行一次）。
            handler.removeCallbacks(task);
            Log.w(TAG, what + ": timeout/interrupted before task started, run inline");
            runnable.run();
            return true;
        }
        Log.w(TAG, what + ": still running after " + effectiveTimeout + "ms, continue without waiting");
        return false;
    }

    /** 当前脚本 Looper；runtime 未 init 或拿不到时返回 null。 */
    private Looper getScriptLooper() {
        Loopers scriptLoopers = loopers;
        if (scriptLoopers == null) {
            return null;
        }
        try {
            return scriptLoopers.getMainLooper();
        } catch (Throwable error) {
            Log.w(TAG, "get script looper failed", error);
            return null;
        }
    }

    private void ignoresException(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * 注册「脚本结束时需要释放」的资源（OCR 会话、RootAutomator2…）。
     *
     * <p>为什么需要：Pro 文档写得很清楚（`$ocr.create()` / `Matrix` 这类对象
     * “默认会在程序退出时自动释放”），脚本作者却常常忘了写 release()，
     * 于是识别器/注入通道会拖到进程结束。
     */
    public void addCleanable(AutoCloseable cleanable) {
        if (cleanable != null) {
            mCleanables.add(cleanable);
        }
    }

    public void removeCleanable(AutoCloseable cleanable) {
        mCleanables.remove(cleanable);
    }

    private void closeCleanables() {
        for (AutoCloseable cleanable : mCleanables.toArray(new AutoCloseable[0])) {
            try {
                cleanable.close();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        mCleanables.clear();
    }

    public Object getImages() {
        return images;
    }

    public Yolo getYolo() {
        return yolo;
    }

    public Object getProperty(String key) {
        return mProperties.get(key);
    }

    public Object putProperty(String key, Object value) {
        return mProperties.put(key, value);
    }

    public Object removeProperty(String key) {
        return mProperties.remove(key);
    }

    public Continuation createContinuation() {
        return Continuation.Companion.create(this, mTopLevelScope);
    }

    public Continuation createContinuation(Scriptable scope) {
        return Continuation.Companion.create(this, scope);
    }


    public static String getStackTrace(Throwable e, boolean printJavaStackTrace) {
        String message = e.getMessage();
        StringBuilder scriptTrace = new StringBuilder(message == null ? "" : message + "\n");
        if (e instanceof RhinoException) {
            RhinoException rhinoException = (RhinoException) e;
            scriptTrace.append(rhinoException.details()).append("\n");
            for (ScriptStackElement element : rhinoException.getScriptStack()) {
                element.renderV8Style(scriptTrace);
                scriptTrace.append("\n");
            }
            if (printJavaStackTrace) {
                scriptTrace.append("- - - - - - - - - - -\n");
            } else {
                return scriptTrace.toString();
            }
        }
        try {
            StringWriter stringWriter = new StringWriter();
            PrintWriter writer = new PrintWriter(stringWriter);
            e.printStackTrace(writer);
            writer.close();
            BufferedReader bufferedReader = new BufferedReader(new StringReader(stringWriter.toString()));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                scriptTrace.append("\n").append(line);
            }
            return scriptTrace.toString();
        } catch (IOException e1) {
            e1.printStackTrace();
            return message;
        }
    }

}
