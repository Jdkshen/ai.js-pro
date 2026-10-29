package com.stardust.autojs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import androidx.annotation.Nullable;

import com.stardust.autojs.core.console.ScriptRunLog;
import com.stardust.autojs.engine.EngineFallbacks;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.engine.LoopBasedJavaScriptEngine;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.engine.ScriptEngineManager;
import com.stardust.autojs.engine.TemplateLiteralPreprocessor;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.LoopedBasedJavaScriptExecution;
import com.stardust.autojs.execution.RunnableScriptExecution;
import com.stardust.autojs.execution.ScriptExecuteActivity;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.execution.ScriptExecutionObserver;
import com.stardust.autojs.execution.ScriptExecutionTask;
import com.stardust.autojs.execution.SimpleScriptExecutionListener;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.Console;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.autojs.script.ScriptSource;
import com.stardust.lang.ThreadCompat;
import com.stardust.util.UiHandler;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import static com.stardust.autojs.runtime.exception.ScriptInterruptedException.causedByInterrupted;

/**
 * Created by Stardust on 2017/1/23.
 */

public class ScriptEngineService {
    public interface ExecutionTransport { ScriptExecution execute(ScriptExecutionTask task); }
    private volatile ExecutionTransport mExecutionTransport;
    public void setExecutionTransport(ExecutionTransport transport) { mExecutionTransport=transport; }

    private static final String LOG_TAG = "ScriptEngineService";

    /**
     * 无界面执行模式：为 {@code true} 时，带 {@code "ui";} 声明的脚本不再交给
     * {@link ScriptExecuteActivity}，而是落到普通执行分支。
     *
     * <p>只给 {@code :native} 隔离进程用（见 NativeScriptService）：那里没有 Activity
     * 可以承载脚本界面，起 Activity 会直接失败/闪烁。该进程的 RUN 会在设置本开关后优先走
     * {@link #executeUiOnMainThread(ScriptExecutionTask)}（主线程 Looper 宿主，模拟 Pro 打包
     * 运行时）；QuickJS 等 UI 模式脚本仍落到 {@link #execute(ScriptExecutionTask)} 的普通路径时，
     * 这里也保证它走后台执行分支而不是启 Activity。Node24 的 UI 由 NodeUiHost 承载，与此开关无关。
     */
    private static volatile boolean sHeadlessUiMode;

    /** 见 {@link #sHeadlessUiMode}。 */
    public static void setHeadlessUiMode(boolean headlessUiMode) {
        sHeadlessUiMode = headlessUiMode;
    }

    /** 见 {@link #sHeadlessUiMode}。 */
    public static boolean isHeadlessUiMode() {
        return sHeadlessUiMode;
    }

    /** task-191：与 Pro m2414 对齐的运行开始时间 tag。 */
    private static final String ENGINE_TAG_START_TIME = "com.jdkshen.aijspro.autojs.Goodbye, World";

    /** task-191 P0：写进程 GlobalConsole（主进程直接可见；:script 由宿主转发主日志页）。 */
    private static void verboseGlobal(String line) {
        ScriptEngineService service = sInstance;
        if (service == null) {
            Log.i(LOG_TAG, line);
            return;
        }
        try {
            service.mGlobalConsole.verbose(line);
        } catch (Throwable error) {
            Log.w(LOG_TAG, "global console verbose failed", error);
        }
    }

    private static final EventBus EVENT_BUS = new EventBus();
    private static final ScriptExecutionListener GLOBAL_LISTENER = new SimpleScriptExecutionListener() {
        @Override
        public void onStart(ScriptExecution execution) {
            if (execution.getEngine() instanceof JavaScriptEngine) {
                ((JavaScriptEngine) execution.getEngine()).getRuntime()
                        .console.setTitle(execution.getSource().getName());
            }
            if (execution.getEngine() != null) {
                execution.getEngine().setTag(ENGINE_TAG_START_TIME, System.currentTimeMillis());
            }
            // task-191 P0：开始行与 Pro 一致（开始运行[source]），写入进程 GlobalConsole；
            // :script 宿主另行转发到主进程统一日志页（见 ScriptHostService RunListener）。
            verboseGlobal("开始运行[" + execution.getSource().toString() + "]");
            EVENT_BUS.post(new ScriptExecutionEvent(ScriptExecutionEvent.ON_START, execution.getSource().toString()));
        }

        @Override
        public void onSuccess(ScriptExecution execution, Object result) {
            onFinish(execution);
        }

        /** task-191 P1：结束行先于错误堆栈（对齐 Pro m2414）。 */
        private void onFinish(ScriptExecution execution) {
            ScriptEngine engine = execution.getEngine();
            if (engine == null) {
                return;
            }
            Long millis = (Long) engine.getTag(ENGINE_TAG_START_TIME);
            if (millis == null) {
                return;
            }
            String source = execution.getSource().toString();
            if (source.startsWith("no-log##")) {
                return;
            }
            double seconds = (System.currentTimeMillis() - millis) / 1000.0;
            verboseGlobal("\n------------\n[" + source + "]运行结束，用时"
                    + String.format(java.util.Locale.US, "%.6f", seconds) + "秒");
        }

        @Override
        public void onException(ScriptExecution execution, Throwable e) {
            // task-191 P1：先打结束行，再打错误堆栈（Pro 顺序），用户停止也走这里但不会打 error。
            onFinish(execution);
            e.printStackTrace();
            String message = null;
            if (!causedByInterrupted(e)) {
                message = e.getMessage();
                if (execution.getEngine() instanceof JavaScriptEngine) {
                    ((JavaScriptEngine) execution.getEngine()).getRuntime()
                            .console.error(e);
                }
            }
            if (execution.getEngine() instanceof JavaScriptEngine) {
                JavaScriptEngine engine = (JavaScriptEngine) execution.getEngine();
                Throwable uncaughtException = engine.getUncaughtException();
                if (uncaughtException != null) {
                    engine.getRuntime().console.error(uncaughtException);
                    message = uncaughtException.getMessage();
                }
            }
            if (message != null) {
                EVENT_BUS.post(new ScriptExecutionEvent(ScriptExecutionEvent.ON_EXCEPTION, message));
            }
        }

    };


    private static ScriptEngineService sInstance;
    private final Context mContext;
    private UiHandler mUiHandler;
    private final Console mGlobalConsole;
    private final ScriptEngineManager mScriptEngineManager;
    private final EngineLifecycleObserver mEngineLifecycleObserver = new EngineLifecycleObserver() {

        @Override
        public void onEngineRemove(ScriptEngine engine) {
            mScriptExecutions.unregister(engine.getId());
            super.onEngineRemove(engine);
        }
    };
    private ScriptExecutionObserver mScriptExecutionObserver = new ScriptExecutionObserver();
    private final ScriptExecutionRegistry mScriptExecutions = new ScriptExecutionRegistry();
    /** 只为启动时记录一次 Node24 UI 路由；不放在执行热路径上反复打日志。 */
    private boolean mLoggedNode24UiRoute;

    ScriptEngineService(ScriptEngineServiceBuilder builder) {
        mUiHandler = builder.mUiHandler;
        mContext = mUiHandler.getContext();
        mScriptEngineManager = builder.mScriptEngineManager;
        mGlobalConsole = builder.mGlobalConsole;
        // 让脚本界面里的 <globalconsole/> 能找到这份全局控制台（见 GlobalConsoleView）
        com.stardust.autojs.core.console.GlobalConsoleView.setGlobalConsole(mGlobalConsole);
        mScriptEngineManager.setEngineLifecycleCallback(mEngineLifecycleObserver);
        mScriptExecutionObserver.registerScriptExecutionListener(GLOBAL_LISTENER);
        EVENT_BUS.register(this);
        mScriptEngineManager.putGlobal("context", mUiHandler.getContext());
        ScriptRuntime.setApplicationContext(builder.mUiHandler.getContext().getApplicationContext());
    }

    public Console getGlobalConsole() {
        return mGlobalConsole;
    }

    public void registerEngineLifecycleCallback(ScriptEngineManager.EngineLifecycleCallback engineLifecycleCallback) {
        mEngineLifecycleObserver.registerCallback(engineLifecycleCallback);
    }

    public void unregisterEngineLifecycleCallback(ScriptEngineManager.EngineLifecycleCallback engineLifecycleCallback) {
        mEngineLifecycleObserver.unregisterCallback(engineLifecycleCallback);
    }

    public boolean registerGlobalScriptExecutionListener(ScriptExecutionListener listener) {
        return mScriptExecutionObserver.registerScriptExecutionListener(listener);
    }

    public boolean unregisterGlobalScriptExecutionListener(ScriptExecutionListener listener) {
        return mScriptExecutionObserver.removeScriptExecutionListener(listener);
    }

    public ScriptExecution execute(ScriptExecutionTask task) {
        return executeInternal(task);
    }

    private ScriptExecution executeInternal(ScriptExecutionTask task) {
        return executeInternal(task, sHeadlessUiMode);
    }

    private ScriptExecution executeInternal(ScriptExecutionTask task, boolean headlessUi) {
        ExecutionTransport transport=mExecutionTransport;
        if (transport!=null && task.getSource() instanceof JavaScriptSource) return transport.execute(task);
        prepareTaskListener(task);
        ScriptSource source = task.getSource();
        switchEngineWhenTheChosenOneCannotParse(source);
        String engineName = source instanceof JavaScriptSource ? source.getEngineName() : null;
        // QuickJS 与 Node24 的事件循环都不在本执行线程上（Node24 在 :node24 进程），
        // 两者都走 RunnableScriptExecution；Rhino 仍走 LoopBased。Node24 的 UI 也走这里：
        // 界面由 NodeUiHost（:node24 进程 -> 主进程 NodeUiActivity）承载，不能再套通用 Activity。
        boolean isAsyncJs = JavaScriptSource.ENGINE_QUICKJS.equals(engineName)
                || JavaScriptSource.ENGINE_NODE24.equals(engineName);
        boolean isNode24 = JavaScriptSource.ENGINE_NODE24.equals(engineName);
        // 带 `"ui";` 声明的 Rhino / QuickJS 脚本仍跑在专用 ScriptExecuteActivity 里：
        // 界面是 Activity 的内容视图，按 Home 会退到后台、返回键才结束。以前 QuickJS 分支
        // 被排除在这条路之外，于是 ui.layout 只能挂系统悬浮窗——退到桌面后还盖在上面。
        // Node24 例外：它自己的 NodeUiActivity 才是 UI 宿主；这里若再起一个空
        // ScriptExecuteActivity，Node UI 关闭后就会先露出白页，用户需要再按一次返回。
        if (source instanceof JavaScriptSource) {
            int mode = ((JavaScriptSource) source).getExecutionMode();
            boolean uiMode = (mode & JavaScriptSource.EXECUTION_MODE_UI) != 0;
            if (uiMode && !headlessUi && isNode24 && !mLoggedNode24UiRoute) {
                mLoggedNode24UiRoute = true;
                Log.i(LOG_TAG, "Node24 UI 由 NodeUiHost 承载，跳过 ScriptExecuteActivity");
            }
            // :native 隔离进程里没有 Activity（见 sHeadlessUiMode），此时 UI 模式脚本
            // 不走 ScriptExecuteActivity，直接落到下面的普通执行分支。
            if (uiMode && !headlessUi && !isNode24) {
                // ScriptExecuteActivity.execute() 先 new ActivityScriptExecution（分配本表 id），
                // 再 startActivity()。Activity.onCreate 会立刻用该 id 回查本表；如果 startActivity
                // 返回后才登记，后台线程调 execute() 时 Activity 可能先读到空表而直接 finish。
                // 这里持同一把锁完成「启动 + 登记」：onCreate 的 getScriptExecution() 会等待锁，
                // 等价于先登记再让 UI 读取，且不需要改 ScriptExecuteActivity。
                synchronized (mScriptExecutions) {
                    ScriptExecution execution = ScriptExecuteActivity.execute(mContext, mScriptEngineManager, task);
                    mScriptExecutions.register(execution);
                    return execution;
                }
            }
        }
        RunnableScriptExecution r;
        if (source instanceof JavaScriptSource && !isAsyncJs) {
            r = new LoopedBasedJavaScriptExecution(mScriptEngineManager, task);
        } else {
            r = new RunnableScriptExecution(mScriptEngineManager, task);
        }
        // 先登记再启动：脚本可能瞬间结束，engine.destroy() 的 onEngineRemove 会在本方法
        // 返回前就触发。若先启动再 put，onRemove 先删一个尚不存在的 id，随后 put 会把
        // 已经结束的执行留成幽灵项，任务列表/超级岛就会多算一个。
        mScriptExecutions.register(r);
        try {
            new ThreadCompat(r).start();
        } catch (Throwable error) {
            // 线程都没起来时不会有 engine，也不会再触发 onEngineRemove，按 executionId 清表。
            mScriptExecutions.unregister(r.getId());
            throw error;
        }
        return r;
    }

    /**
     * 统一装配执行监听器：用户监听器（若有）→ 全局监听器 → 无引擎收尾兜底。
     *
     * <p>兜底必须放在最外层：用户监听器抛异常时前面的 observer 可能没机会执行，但清表
     * 仍然要做。
     */
    private void prepareTaskListener(ScriptExecutionTask task) {
        if (task.getListener() != null) {
            task.setExecutionListener(new ScriptExecutionObserver.Wrapper(mScriptExecutionObserver, task.getListener()));
        } else {
            task.setExecutionListener(mScriptExecutionObserver);
        }
        task.setExecutionListener(new NoEngineExecutionCleanupListener(task.getListener()));
    }

    /**
     * 引擎创建失败 / UI 启动被拒绝时，结束回调里的 execution.getEngine() 仍是 null。
     * 这种执行不会再有 engine 可销毁，也就不会触发 onEngineRemove，必须按 executionId 主动
     * 清表；否则任务列表和超级岛会一直显示一个实际不存在的任务。
     *
     * <p>只要 execution 已经有 engine（包括 UI/Looper 执行），就不在这里删除：成功回调
     * 不是执行真正结束的信号，必须等 engine.destroy() 触发 onEngineRemove，避免把仍在
     * 收尾的 UI/Looper 任务提前从运行列表移除。
     */
    private final class NoEngineExecutionCleanupListener implements ScriptExecutionListener {

        private final ScriptExecutionListener mDelegate;

        NoEngineExecutionCleanupListener(ScriptExecutionListener delegate) {
            mDelegate = delegate;
        }

        @Override
        public void onStart(ScriptExecution execution) {
            mDelegate.onStart(execution);
        }

        @Override
        public void onSuccess(ScriptExecution execution, Object result) {
            mDelegate.onSuccess(execution, result);
        }

        @Override
        public void onException(ScriptExecution execution, Throwable e) {
            try {
                mDelegate.onException(execution, e);
            } finally {
                if (execution.getEngine() == null) {
                    mScriptExecutions.unregister(execution.getId());
                }
            }
        }
    }

    public ScriptExecution execute(ScriptSource source, ScriptExecutionListener listener, ExecutionConfig config) {
        return execute(new ScriptExecutionTask(source, listener, config));
    }

    public ScriptExecution execute(ScriptSource source, ExecutionConfig config) {
        return execute(new ScriptExecutionTask(source, null, config));
    }

    /**
     * 在调用线程（必须是主线程）上执行 UI 模式脚本，不启动任何 Activity。
     *
     * <p>为什么需要它：Pro 打包运行时的脚本进程里，UI 模式脚本跑在脚本进程主线程的 Looper 上，
     * 于是 {@code SurfaceView} 的 surfaceCreated（主线程回调）里调用的 {@code nativeInit} 和脚本
     * 渲染循环里的 Button 等控件调用是同一个线程；而普通后台线程执行时两者不同线程（第三方
     * ImGui 桥就因此出现 GImGui 为空崩溃）。{@code :native} 隔离进程没有 Activity，这里用同样的
     * 主线程模型把引擎跑在进程主 Looper 上，但只用 Application Context，不启动 Activity。
     *
     * <p>机制照搬 {@link ScriptExecuteActivity}（Rhino UI 模式宿主）：
     * {@link LoopBasedJavaScriptEngine#execute(ScriptSource, LoopBasedJavaScriptEngine.ExecuteCallback)}
     * 把脚本体 post 到主 Looper；{@link ScriptRunLog#attach} 挂运行日志；listener 的
     * onStart/onSuccess/onException 照常回传。顶层脚本返回后不立刻结束（UI 脚本通常只是搭界面，
     * 定时器/事件/悬浮窗随后才工作），而是在主 Looper 空闲时检查还有没有未完成的工作
     * （timer / 脚本线程 / 悬浮窗），没有才结束。
     *
     * <p>只支持 Rhino 循环引擎：QuickJS / Node24 没有主线程 Looper 语义，遇到时退回普通后台执行。
     * 停止请用 {@link #stopExecution(ScriptExecution)} 或 {@link #stopAll()}，直接 forceStop 引擎
     * 无法完成主线程执行的收尾。
     *
     * @return 本次执行，可用 {@link #stopExecution(ScriptExecution)} 停止
     */
    public ScriptExecution executeUiOnMainThread(ScriptExecutionTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task == null");
        }
        ExecutionTransport transport=mExecutionTransport;
        if (transport!=null) return transport.execute(task);
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("executeUiOnMainThread() 必须在主线程调用");
        }
        ScriptSource source = task.getSource();
        switchEngineWhenTheChosenOneCannotParse(source);
        if (!(source instanceof JavaScriptSource) || isAsyncEngine((JavaScriptSource) source)) {
            // QuickJS / Node24 的事件循环不依赖调用线程，退回原来的后台执行路径。
            return executeInternal(task, true);
        }
        prepareTaskListener(task);
        MainThreadUiExecution execution = new MainThreadUiExecution(task);
        // 先登记再 post 启动：否则主 Looper 上的执行可能在登记前就结束并触发 onEngineRemove。
        mScriptExecutions.register(execution);
        try {
            execution.start();
        } catch (Throwable error) {
            // post 失败（主 Looper 正在退出）时不会创建 engine，也不会再有 onEngineRemove。
            mScriptExecutions.unregister(execution.getId());
            throw error;
        }
        return execution;
    }

    /**
     * 停止一个执行。主线程 UI 执行需要走它自己的收尾（通知 listener、销毁引擎、关闭运行日志），
     * 只 forceStop 引擎会让主 Looper 上的执行状态留在半路；普通后台执行返回 false，
     * 由调用方按原逻辑 forceStop 引擎。
     *
     * @return true 表示已处理（主线程 UI 执行），false 表示不是主线程执行
     */
    public boolean stopExecution(ScriptExecution execution) {
        if (execution instanceof MainThreadUiExecution) {
            ((MainThreadUiExecution) execution).forceStop();
            return true;
        }
        return false;
    }

    private static boolean isAsyncEngine(JavaScriptSource source) {
        String engineName = source.getEngineName();
        return JavaScriptSource.ENGINE_QUICKJS.equals(engineName)
                || JavaScriptSource.ENGINE_NODE24.equals(engineName);
    }

    /**
     * 两个引擎各有一处「对方能跑、它连解析都过不去」的死角（详见 {@link EngineFallbacks}）。
     * 与其只丢一句 {@code illegal character} / {@code unexpected token '<'}，不如换到能跑的
     * 那个引擎，并在控制台把原因写清楚。
     *
     * <p>两边都换不成（目标引擎也编译不过）时保持原样，不做来回倒腾。
     */
    private void switchEngineWhenTheChosenOneCannotParse(ScriptSource source) {
        if (!(source instanceof JavaScriptSource)) {
            return;
        }
        JavaScriptSource scriptSource = (JavaScriptSource) source;
        if (JavaScriptSource.ENGINE_NODE24.equals(scriptSource.getEngineName())) {
            // Node24 是三引擎之一，不参与 Rhino/QuickJS 的解析兜底互换。
            return;
        }
        String script = scriptSource.getScript();
        String name = scriptSource.toString();
        if (JavaScriptSource.ENGINE_QUICKJS.equals(scriptSource.getEngineName())) {
            // QuickJS 没有 E4X：ui.layout(<xml>) 这种写法它解析不了。
            if (EngineFallbacks.shouldSwitchToRhinoForXmlLayout(script, name)) {
                scriptSource.setForcedEngine(JavaScriptSource.ENGINE_RHINO);
                mGlobalConsole.warn(mContext.getString(
                        R.string.text_switched_to_rhino_for_xml_layout));
            }
            return;
        }
        // 兜底：含反引号的脚本若 Rhino 编译不过（新版 Rhino 1.7.14.1 已原生支持模板字符串，
        // 多半是命中了它尚不支持的其它新语法），交给 QuickJS 试跑。
        if (EngineFallbacks.shouldSwitchToQuickJs(script, name)) {
            // 但先看能不能把模板字符串降级成 ES5 字符串拼接（TemplateLiteralPreprocessor）。
            // 可转换的默认脚本继续由 Rhino 执行，保持旧脚本的引擎选择。
            // QuickJS Canvas 由其 JS 事件循环调度，不再要求绘制线程直接回调 JS。
            if (TemplateLiteralPreprocessor.canApply(script)) {
                mGlobalConsole.warn(mContext.getString(
                        R.string.text_downgraded_template_literals_for_rhino));
                return;
            }
            scriptSource.setForcedEngine(JavaScriptSource.ENGINE_QUICKJS);
            mGlobalConsole.warn(mContext.getString(
                    R.string.text_switched_to_quickjs_for_template_string));
        }
    }

    @Subscribe
    public void onScriptExecution(ScriptExecutionEvent event) {
        if (event.getCode() == ScriptExecutionEvent.ON_START) {
            // task-191：开始行已由 GLOBAL_LISTENER.onStart 统一打印（避免重复）。
        } else if (event.getCode() == ScriptExecutionEvent.ON_EXCEPTION) {
            mUiHandler.toast(mContext.getString(R.string.text_error) + ": " + event.getMessage());
        }
    }

    public int stopAll() {
        int count = 0;
        // 主线程 UI 执行跑在进程主 Looper 上：只 forceStop 引擎既停不干净也没人收尾，
        // 必须走它们自己的 forceStop（通知 listener / 销毁引擎 / 关运行日志）。
        for (ScriptExecution execution : mScriptExecutions.snapshot()) {
            if (execution instanceof MainThreadUiExecution) {
                ((MainThreadUiExecution) execution).forceStop();
                count++;
            }
        }
        try {
            count += mScriptEngineManager.stopAll();
        } finally {
            com.stardust.autojs.overlay.OverlayRegistry.closeAll("engine_stop_all");
        }
        return count;
    }


    public void stopAllAndToast() {
        int n = stopAll();
        if (n > 0)
            mUiHandler.toast(String.format(mContext.getString(R.string.text_already_stop_n_scripts), n));
    }

    // ------------------------------------------------------------------
    // 音量上键停止全部脚本（窗口聚焦路径，不依赖无障碍/root 全局按键监听）
    // ------------------------------------------------------------------

    /**
     * 宿主层「停止全部脚本」hook（App 层注入，通常是 {@code HostScripts::stopAllHostScripts}）。
     *
     * <p>为什么放在引擎层：全局音量键监听依赖无障碍/root 键事件，HyperOS 上无障碍设置
     * （{@code enabled_accessibility_services}）会被系统重置，物理音量上键就收不到。但脚本自己
     * 的窗口（{@link ScriptExecuteActivity} / QuickJS overlay）聚焦时能直接收到
     * {@code KEYCODE_VOLUME_UP}，那里通过 {@link #stopAllByVolumeUp()} 先停宿主进程
     * （{@code :script} / {@code :native}）再停主进程引擎任务。
     */
    public static volatile Runnable volumeUpStopAllHook;

    public static void setVolumeUpStopAllHook(Runnable hook) {
        volumeUpStopAllHook = hook;
    }

    /**
     * 音量上键统一停止入口：先执行宿主 hook（停 :script/:native），再停主进程引擎任务。
     * 任一环节失败只记日志，不向调用方（键事件/窗口）抛异常。
     *
     * @return 主进程引擎停止数量（宿主进程任务数量不在这里统计）
     */
    public static int stopAllByVolumeUp() {
        return stopAllAcrossProcesses();
    }

    /** Public API/emergency stop; service teardown still uses the process-local stopAll(). */
    public static int stopAllAcrossProcesses() {
        ScriptEngineService service = sInstance;
        // The app hook also stops this process. Capture its requested count before
        // invoking the coordinator so public engines.stopAll() does not always return 0.
        int localCount = service == null ? 0 : service.getEngines().size();
        Runnable hook = volumeUpStopAllHook;
        if (hook != null) {
            try {
                hook.run();
                Log.i(LOG_TAG, "volume-up stop-all hook executed");
            } catch (Throwable error) {
                Log.w(LOG_TAG, "volume-up stop-all hook failed", error);
            }
        } else {
            Log.w(LOG_TAG, "volume-up stop-all hook is not installed");
        }
        if (service == null) {
            Log.w(LOG_TAG, "volume-up stopAll skipped: service instance is null");
            return 0;
        }
        try {
            int stopped = service.stopAll();
            Log.i(LOG_TAG, "volume-up stopAll main-process engines: " + stopped);
            return Math.max(localCount, stopped);
        } catch (Throwable error) {
            Log.w(LOG_TAG, "volume-up stopAll main-process engines failed", error);
            return 0;
        }
    }

    public static void stopAllAcrossProcessesAndToast() {
        int count = stopAllAcrossProcesses();
        ScriptEngineService service = sInstance;
        if (count > 0 && service != null) {
            service.mUiHandler.toast(String.format(
                    service.mContext.getString(R.string.text_already_stop_n_scripts), count));
        }
    }

    public Set<ScriptEngine> getEngines() {
        return mScriptEngineManager.getEngines();
    }

    /**
     * 当前运行中执行的稳定快照。调用方可以在任意线程安全遍历；之后的登记/移除不会
     * 改变已返回的集合，也不会抛 ConcurrentModificationException。
     */
    public Collection<ScriptExecution> getScriptExecutions() {
        return mScriptExecutions.snapshot();
    }

    @Nullable
    public ScriptExecution getScriptExecution(int id) {
        if (id == ScriptExecution.NO_ID) {
            return null;
        }
        return mScriptExecutions.get(id);
    }

    public static void setInstance(ScriptEngineService service) {
        if (sInstance != null) {
            throw new IllegalStateException();
        }
        sInstance = service;
    }

    public static ScriptEngineService getInstance() {
        return sInstance;
    }


    /**
     * 主线程 UI 模式执行 —— 不启动 Activity 的 {@link ScriptExecuteActivity} 等价物。
     *
     * <p>生命周期：
     * <ol>
     *   <li>{@link #start()} 把「创建引擎 + init + 挂运行日志」post 到主 Looper，不阻塞调用方；</li>
     *   <li>onStart 通知之后调用 {@code LoopBasedJavaScriptEngine.execute(source, callback)}，
     *       脚本体在主 Looper 上执行（与 Pro 打包运行时的脚本进程主线程模型一致）；</li>
     *   <li>顶层脚本返回后不结束：主 Looper 空闲时检查 timer / 脚本线程 / 悬浮窗，还有活就继续
     *       养着，没活才 onSuccess；异常或 forceStop 走 onException；</li>
     *   <li>收尾顺序：先通知 listener，再销毁引擎（触发 {@code runtime.onExit()}：关悬浮窗、
     *       清定时器、清 interrupt 状态），最后收尾 {@link ScriptRunLog}。</li>
     * </ol>
     */
    private final class MainThreadUiExecution extends ScriptExecution.AbstractScriptExecution implements MessageQueue.IdleHandler {

        private final Object mLock = new Object();
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final Runnable mStartRunnable = new Runnable() {
            @Override
            public void run() {
                startOnMainThread();
            }
        };
        private volatile ScriptEngine mEngine;
        private ScriptRunLog mRunLog;
        private boolean mStarted;
        private boolean mFinished;
        private boolean mStopRequested;
        private boolean mWatchingIdle;

        MainThreadUiExecution(ScriptExecutionTask task) {
            super(task);
        }

        void start() {
            if (!mMainHandler.post(mStartRunnable)) {
                throw new IllegalStateException("主线程 Looper 正在退出，无法启动 UI 脚本");
            }
        }

        @Override
        public ScriptEngine getEngine() {
            return mEngine;
        }

        /** 主线程：创建引擎、初始化 runtime、挂运行日志、通知 onStart，并把脚本体放进主 Looper。 */
        private void startOnMainThread() {
            synchronized (mLock) {
                if (mFinished) {
                    return;
                }
                if (mStopRequested) {
                    finishOnMainThread(new ScriptInterruptedException());
                    return;
                }
                mStarted = true;
            }
            try {
                ScriptEngine engine = mScriptEngineManager.createEngineOfSourceOrThrow(getSource(), getId());
                mEngine = engine;
                engine.setTag(ExecutionConfig.getTag(), getConfig());
                // 与 RunnableScriptExecution 的顺序一致：先挂运行日志，再 init / 执行。
                mRunLog = ScriptRunLog.attach(engine, getSource(), mContext);
                engine.setTag(ScriptEngine.TAG_WORKING_DIRECTORY, getConfig().getWorkingDirectory());
                engine.setTag(ScriptEngine.TAG_ENV_PATH, getConfig().getPath());
                engine.init();
                engine.setTag(ScriptEngine.TAG_SOURCE, getSource());
                getListener().onStart(this);
                ((LoopBasedJavaScriptEngine) engine).execute(getSource(),
                        new LoopBasedJavaScriptEngine.ExecuteCallback() {
                            @Override
                            public void onResult(Object result) {
                                onTopLevelFinished(null);
                            }

                            @Override
                            public void onException(Exception error) {
                                onTopLevelFinished(error);
                            }
                        });
            } catch (Throwable error) {
                Log.w(LOG_TAG, "主线程 UI 脚本启动失败", error);
                finishOnMainThread(error);
            }
        }

        /** 主线程：脚本体跑完了（error 为 null 表示正常返回）。 */
        private void onTopLevelFinished(Throwable error) {
            if (error != null) {
                finishOnMainThread(error);
                return;
            }
            synchronized (mLock) {
                if (mFinished || mWatchingIdle) {
                    return;
                }
                if (mStopRequested) {
                    finishOnMainThread(new ScriptInterruptedException());
                    return;
                }
                mWatchingIdle = true;
            }
            // UI 脚本顶层通常只是搭界面/起渲染循环，真正的活（定时器、事件、悬浮窗）在顶层返回后
            // 才开始；所以先不结束，等主 Looper 空闲再看还有没有要做的事。
            Looper.myQueue().addIdleHandler(this);
        }

        @Override
        public boolean queueIdle() {
            synchronized (mLock) {
                if (mFinished) {
                    return false;
                }
            }
            ScriptEngine engine = mEngine;
            if (engine == null) {
                finishOnMainThread(null);
                return false;
            }
            Throwable uncaught = engine.getUncaughtException();
            if (uncaught != null) {
                // 定时器/事件回调里的异常：JS runtime 会记到引擎 uncaughtException 并要求停止。
                finishOnMainThread(uncaught);
                return false;
            }
            boolean stopRequested;
            synchronized (mLock) {
                stopRequested = mStopRequested;
            }
            if (stopRequested) {
                finishOnMainThread(new ScriptInterruptedException());
                return false;
            }
            if (Thread.currentThread().isInterrupted()) {
                // 脚本自己 exit()：主线程模式下 runtime 不抛异常，只置 interrupt；按正常退出处理。
                finishOnMainThread(null);
                return false;
            }
            if (hasPendingWork(engine)) {
                return true;
            }
            // UI 脚本的顶层返回不等于脚本结束：悬浮窗/第三方 native UI（如 ImGui 渲染循环）
            // 可能靠 Looper 上的延迟消息继续活着。这里保持引擎存活，只有用户停止/服务销毁
            // 才走 finishOnMainThread → engine.destroy()（触发 runtime.onExit 的 nativeShutdown），
            // 避免把第三方 native UI 正在使用的 ImGui/EGL 上下文提前销毁。
            return true;
        }

        private boolean hasPendingWork(ScriptEngine engine) {
            if (!(engine instanceof JavaScriptEngine)) {
                return false;
            }
            ScriptRuntime runtime = ((JavaScriptEngine) engine).getRuntime();
            if (runtime == null) {
                return false;
            }
            if (runtime.timers != null && runtime.timers.hasPendingCallbacks()) {
                return true;
            }
            if (runtime.threads != null && runtime.threads.hasRunningThreads()) {
                return true;
            }
            return hasOpenFloatyWindows(runtime);
        }

        /** 停止：可从任意线程调用。主线程上直接收尾；后台线程调用则 post 到主线程收尾。 */
        void forceStop() {
            boolean started;
            synchronized (mLock) {
                if (mFinished) {
                    return;
                }
                mStopRequested = true;
                started = mStarted;
            }
            mMainHandler.removeCallbacks(mStartRunnable);
            boolean onMainThread = Looper.myLooper() == Looper.getMainLooper();
            ScriptEngine engine = mEngine;
            if (started && engine != null && !onMainThread) {
                // 只有从后台线程调用时才需要打断脚本（脚本可能正阻塞在主 Looper 上）；
                // 主线程调用时脚本必然没在跑，直接销毁引擎收尾即可 —— 别去 interrupt 主线程
                // （Pro 的 LoopBasedJavaScriptEngine 在主线程模式同样跳过这种打断）。
                try {
                    engine.forceStop();
                } catch (Throwable error) {
                    Log.w(LOG_TAG, "停止主线程 UI 脚本失败", error);
                }
            }
            if (onMainThread) {
                finishOnMainThread(new ScriptInterruptedException());
            } else {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishOnMainThread(new ScriptInterruptedException());
                    }
                });
            }
        }

        /** 主线程收尾：通知 listener -> 销毁引擎（runtime.onExit）-> 关闭运行日志。 */
        private void finishOnMainThread(Throwable error) {
            ScriptEngine engine;
            ScriptRunLog runLog;
            synchronized (mLock) {
                if (mFinished) {
                    return;
                }
                mFinished = true;
                engine = mEngine;
                runLog = mRunLog;
                mRunLog = null;
            }
            mMainHandler.removeCallbacks(mStartRunnable);
            Looper.myQueue().removeIdleHandler(this);
            try {
                try {
                    ScriptExecutionListener listener = getListener();
                    if (error == null) {
                        listener.onSuccess(this, null);
                    } else {
                        listener.onException(this, error);
                    }
                } catch (Throwable notifyError) {
                    Log.w(LOG_TAG, "通知主线程 UI 脚本结束失败", notifyError);
                }
                if (engine != null) {
                    try {
                        engine.destroy();
                    } catch (Throwable destroyError) {
                        Log.w(LOG_TAG, "销毁主线程 UI 脚本引擎失败", destroyError);
                    }
                }
                if (runLog != null) {
                    runLog.finish(error);
                }
            } finally {
                // 正常路径只由 engine.destroy() 的 onEngineRemove 清表；这里只兜底
                // 「从未创建出 engine」和「destroy 没走到 onDestroy」两种不会再有回调的情况。
                // 成功回调发生时 engine 还活着，不能提前删，否则 UI/Looper 任务会从运行列表消失。
                if (engine == null || !engine.isDestroyed()) {
                    mScriptExecutions.unregister(getId());
                }
            }
        }
    }

    /**
     * Floaty 没有公开的「还有几个窗口」API，而「脚本顶层返回但悬浮窗还开着」是 UI 模式的常见形态
     * （脚本靠窗口 + 事件活着），所以这里读一次 Floaty 内部的窗口集合来判断要不要继续养着引擎。
     * 读取失败按「没有窗口」处理，绝不能因为观测失败反过来把脚本干掉。
     */
    private static java.lang.reflect.Field sFloatyWindowsField;
    private static boolean sFloatyWindowsFieldResolved;

    private static boolean hasOpenFloatyWindows(ScriptRuntime runtime) {
        try {
            if (!sFloatyWindowsFieldResolved) {
                sFloatyWindowsFieldResolved = true;
                try {
                    sFloatyWindowsField = runtime.floaty.getClass().getDeclaredField("mWindows");
                    sFloatyWindowsField.setAccessible(true);
                } catch (Throwable ignored) {
                    sFloatyWindowsField = null;
                }
            }
            if (sFloatyWindowsField == null) {
                return false;
            }
            Object windows = sFloatyWindowsField.get(runtime.floaty);
            return windows instanceof Collection && !((Collection<?>) windows).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }


    private static class EngineLifecycleObserver implements ScriptEngineManager.EngineLifecycleCallback {

        private final Set<ScriptEngineManager.EngineLifecycleCallback> mEngineLifecycleCallbacks = new LinkedHashSet<>();

        @Override
        public void onEngineCreate(ScriptEngine engine) {
            synchronized (mEngineLifecycleCallbacks) {
                for (ScriptEngineManager.EngineLifecycleCallback callback : mEngineLifecycleCallbacks) {
                    callback.onEngineCreate(engine);
                }
            }
        }

        @Override
        public void onEngineRemove(ScriptEngine engine) {
            synchronized (mEngineLifecycleCallbacks) {
                for (ScriptEngineManager.EngineLifecycleCallback callback : mEngineLifecycleCallbacks) {
                    callback.onEngineRemove(engine);
                }
            }
        }

        void registerCallback(ScriptEngineManager.EngineLifecycleCallback callback) {
            synchronized (mEngineLifecycleCallbacks) {
                mEngineLifecycleCallbacks.add(callback);
            }

        }

        void unregisterCallback(ScriptEngineManager.EngineLifecycleCallback callback) {
            synchronized (mEngineLifecycleCallbacks) {
                mEngineLifecycleCallbacks.remove(callback);
            }
        }
    }


    private static class ScriptExecutionEvent {

        static final int ON_START = 1001;
        static final int ON_SUCCESS = 1002;
        static final int ON_EXCEPTION = 1003;

        private final int mCode;
        private final String mMessage;

        ScriptExecutionEvent(int code, String message) {
            mCode = code;
            mMessage = message;
        }

        public int getCode() {
            return mCode;
        }

        public String getMessage() {
            return mMessage;
        }
    }

}
