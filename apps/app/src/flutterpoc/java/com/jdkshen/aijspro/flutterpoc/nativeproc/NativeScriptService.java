package com.jdkshen.aijspro.flutterpoc.nativeproc;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.flutterpoc.scriptproc.ConsoleBatch;
import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.core.console.ConsoleImpl;
import com.stardust.autojs.core.graphics.ImGuiDrawingOverlay;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.execution.ScriptExecutionTask;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.JavaScriptSource;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code :native} 隔离进程里的脚本宿主服务（Phase 0）。
 *
 * <p>为什么要有它：带 {@code lib/*.so} / {@code *.dex} 的项目由脚本自己 {@code loadDex} /
 * {@code System.load} 加载原生代码，一旦段错误或 {@code abort()}，整个进程会立刻消失。以前这类脚本
 * 和主界面跑在同一个进程里，原生崩溃会把 Flutter 界面一起带走。现在主进程（{@code PocNativeRunner}）
 * 检测到「原生项目」后，只是把这个服务所在的 {@code :native} 进程拉起来，通过 Messenger 下发
 * 「跑哪个脚本」，脚本崩溃只死隔离进程，主进程只收到 Binder 死亡通知。
 *
 * <p>进程声明在 {@code apps/app/src/flutterpoc/AndroidManifest.xml}：
 * {@code android:process=":native"}、{@code android:exported="false"}。本类<b>不要</b>改成别的包名/类名，
 * 主进程那边按固定字符串绑定。
 *
 * <p>IPC 协议（与 PocNativeRunner 一一对应，改任何一条都要同步改主进程）：
 * <ul>
 *   <li>客户端 -&gt; 服务：{@code what=1 RUN}，{@code msg.replyTo}=客户端 Messenger，
 *       data: {@link #KEY_PATH path}（脚本绝对路径）、{@link #KEY_WORK_DIR workDir}（工作目录，可空）、
 *       {@link #KEY_TOKEN token}（字符串，原样回显）；</li>
 *   <li>客户端 -&gt; 服务：{@code what=2 STOP}，data: {@link #KEY_TOKEN token}；</li>
 *   <li>服务 -&gt; 客户端：{@code what=100 STARTED}，data: token / {@link #KEY_NAME name}；</li>
 *   <li>服务 -&gt; 客户端：{@code what=101 FINISHED}，data: token；</li>
 *   <li>服务 -&gt; 客户端：{@code what=102 ERROR}，data: token / {@link #KEY_MESSAGE message}
 *       （脚本异常、被 STOP、或启动前校验失败）；</li>
 *   <li>服务 -&gt; 客户端：{@code what=103 CONSOLE}，data: token / {@link #KEY_LINE line}
 *       （多行用 {@code \n} 分隔）/ {@link #KEY_LEVEL level}（android.util.Log 等级）：
 *       脚本 console 的实时批量回传，主进程 {@code PocNativeRunner} 收到后交给
 *       {@code HostConsoleSink}（与 :script 同一落点：主进程 GlobalConsole + 环形缓冲）。</li>
 * </ul>
 *
 * <p>几个刻意为之的取舍：
 * <ul>
 *   <li><b>不起 Activity</b>：不启动任何 Activity，脚本直接交给引擎执行；RUN 前仍会打开
 *       引擎的「无界面模式」（{@link ScriptEngineService#setHeadlessUiMode(boolean)}）作兜底 ——
 *       万一 UI 模式脚本落到引擎普通路径（QuickJS/Node24 等），也只是后台执行，不会去启
 *       {@code ScriptExecuteActivity}；</li>
 *   <li><b>不在这里挂 ScriptRunLog</b>：引擎的 {@code RunnableScriptExecution} 在创建引擎后
 *       已经自己 {@code ScriptRunLog.attach(...)}，日志落在同一个 {@code <externalFilesDir>/logs}，
 *       主进程 Flutter 的「日志」页读得到。这里再 attach 一次只会多出一个永不收尾的日志文件；</li>
 *   <li><b>实时 console 转发（what=103，Phase1 日志统一）</b>：每次 RUN 在 onStart 时给本次引擎的
 *       {@code ConsoleImpl} 挂 {@code LogListener}，输出先进 {@code ConsoleBatch} 批量缓冲
 *       （200ms 定时 / 40 行 / 8KB 触发），再以 what=103 回给 {@code PocNativeRunner}；主进程
 *       {@code HostConsoleSink} 写 GlobalConsole（原生日志页/编辑器控制台实时可见）+ 环形缓冲。
 *       脚本结束/服务销毁时解挂并 flush，保证 103 先于 101/102；运行日志文件仍由引擎自动落盘；</li>
 *   <li><b>UI 模式脚本跑在主 Looper</b>：带 {@code "ui";} 的脚本交给引擎服务的主线程宿主
 *       （{@code ScriptEngineService#executeUiOnMainThread}）执行 —— SurfaceView 的 surfaceCreated
 *       主线程回调与脚本里的 Button 等控件调用因此同线程（对齐 Pro 打包运行时，第三方 ImGui 桥
 *       依赖这一点）；其它脚本仍在后台线程跑，协议不变；</li>
 *   <li><b>先复制到私有目录再执行</b>：RUN 时把项目整棵暂存到
 *       {@code <filesDir>/native-staging/<路径hash>}（见 {@link NativeProjectStaging}），入口脚本与
 *       工作目录都换成私有副本 —— 对齐 Pro 打包版从私有目录加载 .so/.dex 的布局；暂存失败直接
 *       回 ERROR(102)，不回退到原目录「将就跑」；</li>
 *   <li><b>不弥补无障碍/自动化能力</b>：隔离进程里 {@code auto}/{@code click}/{@code selector}
 *       本来就用不了（AccessibilityService 只跑在主进程），属 MVP 已知限制；</li>
 *   <li><b>不主动自杀</b>：还有脚本在跑时不 stopSelf；客户端全部解绑且没有运行中脚本时才停，
 *       避免跑一半的进程被回收，也避免白留一个常驻进程。</li>
 * </ul>
 */
public class NativeScriptService extends Service {

    /** 客户端 -&gt; 服务：启动一个脚本。data: path/workDir/token，msg.replyTo 必填。 */
    public static final int MSG_RUN = 1;
    /** 客户端 -&gt; 服务：停止 token 对应的脚本。data: token。 */
    public static final int MSG_STOP = 2;

    /** 服务 -&gt; 客户端：脚本已经开始执行。data: token/name。 */
    public static final int REPLY_STARTED = 100;
    /** 服务 -&gt; 客户端：脚本正常结束。data: token。 */
    public static final int REPLY_FINISHED = 101;
    /** 服务 -&gt; 客户端：脚本异常 / 被停止 / 无法启动。data: token/message。 */
    public static final int REPLY_ERROR = 102;
    /**
     * 服务 -&gt; 客户端：脚本控制台实时输出（Phase1 日志统一，与 :script 的 103 逐字一致）。
     * data: {@link #KEY_TOKEN token} / {@link #KEY_LINE line}（可多行，{@code \n} 分隔）/
     * {@link #KEY_LEVEL level}（android.util.Log 等级，批次取最高等级）。
     */
    public static final int REPLY_CONSOLE = 103;

    public static final String KEY_PATH = "path";
    public static final String KEY_WORK_DIR = "workDir";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_NAME = "name";
    public static final String KEY_MESSAGE = "message";
    /** what=103 CONSOLE 的行内容键（可含多行，{@code \n} 分隔）。 */
    public static final String KEY_LINE = "line";
    /** what=103 CONSOLE 的等级键（android.util.Log 等级；批次取最高等级）。 */
    public static final String KEY_LEVEL = "level";

    /** 用户主动 STOP 时 102 的固定文案：主进程靠它区分「用户停止」和「脚本报错」。 */
    static final String MESSAGE_STOPPED = "脚本已停止";

    /** console 转发的定时 flush 间隔：批量回传，避免每行一条 Binder 消息。 */
    private static final long CONSOLE_FLUSH_INTERVAL_MS = 200L;

    /**
     * 空闲停服务的默认宽限（task-50 C）：脚本刚结束时不要立刻 stopSelf/退出进程 ——
     * 此时引擎的 nativeShutdown/GL 清理可能还没收尾（B 任务串行化后仍需一点时间）。
     * 宽限期内有新 RUN 会取消；到点仍空闲才停。
     */
    private static final long IDLE_STOP_GRACE_MS = 1000L;

    /** 宿主 ImGui 覆盖窗还开着时的额外保活时间（超时兜底，最多等到窗口关掉或这个上限）。 */
    private static final long WINDOW_KEEPALIVE_MS = 3000L;

    private static final String TAG = "NativeScriptService";

    private final Object mLock = new Object();
    /** token -&gt; 这次运行的状态；只在主线程/脚本线程的同步块里读写。 */
    private final Map<String, RunState> mRuns = new LinkedHashMap<>();
    private int mBoundClients = 0;

    /** 浮窗还没关时把空闲停止延到这个时间点之后（超时兜底，task-50 C）。 */
    private volatile long mKeepAliveUntil;

    private final Handler mIpcHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RUN:
                    handleRun(msg);
                    return true;
                case MSG_STOP:
                    handleStop(msg);
                    return true;
                default:
                    return false;
            }
        }
    });

    /** console 转发 flush 用的主线程 Handler（与 IPC 分开，避免互相影响）。 */
    private final Handler mConsoleHandler = new Handler(Looper.getMainLooper());

    /** 延长后的空闲停止动作（task-50 C：给引擎 nativeShutdown/关窗留收尾时间）。 */
    private final Runnable mIdleStop = new Runnable() {
        @Override
        public void run() {
            boolean idle;
            synchronized (mLock) {
                idle = mBoundClients <= 0 && mRuns.isEmpty();
            }
            if (!idle) {
                return;
            }
            Log.i(TAG, "隔离进程空闲，停止服务");
            stopSelf();
        }
    };

    private final Messenger mMessenger = new Messenger(mIpcHandler);

    // ------------------------------------------------------------------
    // Service 生命周期
    // ------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        synchronized (mLock) {
            mBoundClients++;
        }
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        synchronized (mLock) {
            if (mBoundClients > 0) {
                mBoundClients--;
            }
        }
        stopIfIdle();
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 真正的命令都走 Messenger；这里只是让进程在「客户端 startService 之后、bind 之前」
        // 以及脚本运行期间有个存活的锚点。进程被系统回收后不自动重启（START_NOT_STICKY），
        // 下次运行由主进程重新 startService + bindService（见 PocNativeRunner）。
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mIpcHandler.removeCallbacks(mIdleStop);
        // Phase1 日志统一：先 flush 并解挂所有 console 监听，否则最后的批量日志会丢。
        RunState[] runs;
        synchronized (mLock) {
            runs = mRuns.values().toArray(new RunState[0]);
        }
        // task-50 C：进程销毁也按「先停帧/关窗 → 再让引擎收尾」的顺序停，避免 DestroyContext
        // 与正在渲染的帧并发；逐个走 forceStop 而不是直接 engineService.stopAll()。
        for (RunState run : runs) {
            try {
                detachConsoleForwarder(run);
            } catch (Throwable error) {
                Log.w(TAG, "解挂 console 转发失败：" + run.token, error);
            }
            try {
                forceStop(run);
            } catch (Throwable error) {
                Log.w(TAG, "销毁前停止脚本失败：" + run.token, error);
            }
        }
        // 兜底：forceStop 没覆盖到的引擎（例如 execution 还没赋值）也停掉。
        try {
            ScriptEngineService engineService = getEngineService();
            if (engineService != null) {
                engineService.stopAll();
            }
        } catch (Throwable error) {
            Log.w(TAG, "停止隔离进程内脚本失败", error);
        }
        ScriptRuntime.suppressExitEvent = false;
        ScriptEngineService.setHeadlessUiMode(false);
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 客户端 -> 服务
    // ------------------------------------------------------------------

    private void handleRun(Message msg) {
        Bundle data = msg.getData();
        Messenger replyTo = msg.replyTo;
        String token = tokenKey(data);
        String path = data.getString(KEY_PATH);
        String workDir = data.getString(KEY_WORK_DIR);
        if (replyTo == null) {
            // 没有 replyTo 就永远回不了话，只能记 logcat。
            Log.w(TAG, "RUN 缺少 replyTo，忽略：path=" + path);
            return;
        }
        // 正式开跑：取消可能还在等待的空闲停止（上一次运行结束后的保活宽限）。
        mIpcHandler.removeCallbacks(mIdleStop);
        RunState state = new RunState(token, replyTo);
        if (token.isEmpty()) {
            replyError(state, "RUN 缺少 token");
            return;
        }
        boolean duplicated;
        synchronized (mLock) {
            duplicated = mRuns.containsKey(token);
            if (!duplicated) {
                mRuns.put(token, state);
            }
        }
        if (duplicated) {
            // 主进程的 token 是自增的，正常不会撞；撞了说明两端状态不一致，直接报错让上层可见。
            replyError(state, "token 已被占用：" + token);
            return;
        }

        try {
            ScriptEngineService engineService = requireEngineService();
            File file = new File(path == null ? "" : path).getCanonicalFile();
            if (!file.isFile()) {
                throw new IllegalStateException("脚本文件不存在：" + (path == null ? "" : path));
            }
            // 对齐 Pro 打包版：先把项目整棵复制到应用私有目录，再从私有目录执行。
            // 直接在 /sdcard 上加载 .so/.dex 会命中 "Attempt to load writable dex file" 这类差异。
            File projectDir = resolveProjectDir(file, workDir);
            File stagedDir;
            try {
                stagedDir = NativeProjectStaging.stageIfNeeded(getApplicationContext(), projectDir);
            } catch (IOException error) {
                // 必须明确失败：回退到原目录继续跑会让「从私有目录执行」这个前提静默失效。
                String detail = messageOf(error);
                throw new IOException(detail.startsWith("暂存项目失败")
                        ? detail : "项目暂存到应用私有目录失败：" + detail, error);
            }
            File stagedScript = stagedScriptOf(projectDir, stagedDir, file);

            // 10 连跑不堆积：只有当前没有别的运行中任务时才清其它项目的 native-staging 副本，
            // 避免把另一个正在运行的项目的暂存目录删掉。
            boolean soleRun;
            synchronized (mLock) {
                soleRun = mRuns.size() <= 1;
            }
            if (soleRun) {
                cleanupStaleStaging(stagedDir);
            }
            JavaScriptFileSource source = new JavaScriptFileSource(stagedScript);
            state.name = source.getName();
            state.uiMode = isUiMode(source);

            ExecutionConfig config = ExecutionConfig.getDefault();
            config.setWorkingDirectory(stagedDir.getAbsolutePath());

            // 隔离进程里没有 Activity：UI 模式脚本不走 ScriptExecuteActivity。
            ScriptEngineService.setHeadlessUiMode(true);
            // 监听器负责把开始/结束/异常回给主进程。
            ScriptExecutionListener listener = new RunListener(state);
            ScriptExecution execution;
            if (isUiMode(source) && ScriptEngineService.isHeadlessUiMode()) {
                // UI 模式脚本跑在 :native 进程的主 Looper 上（对齐 Pro 打包运行时），让
                // surfaceCreated 等主线程 native 回调与脚本里的控件调用同线程。
                // task-50 C：不再永久 suppress exit —— 那会跳过 nativeShutdown，导致 ImGui/EGL
                // 上下文跨轮次残留（连续运行/停止 10 次的隐患）。停止路径已先停帧/关窗，
                // B 任务在引擎侧再保证 exit 清理与帧序列串行，因此允许 exit 正常执行清理。
                ScriptRuntime.suppressExitEvent = false;
                execution = engineService.executeUiOnMainThread(
                        new ScriptExecutionTask(source, listener, config));
            } else {
                // 普通脚本仍由引擎在后台线程执行。
                execution = engineService.execute(source, listener, config);
            }

            boolean stopNow;
            synchronized (mLock) {
                state.execution = execution;
                stopNow = state.stopRequested && !state.finished;
            }
            if (stopNow) {
                // STOP 在 execute() 返回前就到了：引擎对象刚拿到，补停一次。
                forceStop(state);
            }
        } catch (Throwable error) {
            Log.w(TAG, "启动脚本失败：" + path, error);
            failRun(state, error);
        }
    }

    private void handleStop(Message msg) {
        String token = tokenKey(msg.getData());
        if (token.isEmpty()) {
            Log.w(TAG, "STOP 缺少 token，忽略");
            return;
        }
        RunState state;
        boolean stopNow;
        synchronized (mLock) {
            state = mRuns.get(token);
            if (state == null) {
                // 已经结束/尚未开始：主进程收到 101/102 后会自己移除，这里静默即可。
                Log.w(TAG, "STOP 找不到运行中的脚本：" + token);
                return;
            }
            state.stopRequested = true;
            stopNow = state.execution != null && !state.finished;
        }
        if (stopNow) {
            forceStop(state);
        }
        // execution 还没赋值的情况由 handleRun 在 execute() 返回后补停（见上面的 stopNow）。
    }

    // ------------------------------------------------------------------
    // 执行结果 -> 客户端
    // ------------------------------------------------------------------

    /** 引擎回调：监听一次运行的开始/成功/异常。回调线程不固定（脚本线程/引擎 looper）。 */
    private final class RunListener implements ScriptExecutionListener {

        private final RunState mState;

        RunListener(RunState state) {
            mState = state;
        }

        @Override
        public void onStart(ScriptExecution execution) {
            try {
                reply(mState.replyTo, REPLY_STARTED, mState, mState.name, null);
                // onStart 时引擎已创建、脚本还没开始跑，正好挂 console 监听（实时回主进程）。
                attachConsoleForwarder(mState, execution);
                boolean stopNow;
                synchronized (mLock) {
                    stopNow = mState.stopRequested && !mState.finished;
                }
                if (stopNow) {
                    forceStop(mState);
                }
            } catch (Throwable error) {
                Log.w(TAG, "上报 STARTED 失败：" + mState.token, error);
            }
        }

        @Override
        public void onSuccess(ScriptExecution execution, Object result) {
            try {
                finish(mState, null);
            } catch (Throwable error) {
                Log.w(TAG, "上报 FINISHED 失败：" + mState.token, error);
            }
        }

        @Override
        public void onException(ScriptExecution execution, Throwable e) {
            try {
                finish(mState, e);
            } catch (Throwable error) {
                Log.w(TAG, "上报 ERROR 失败：" + mState.token, error);
            }
        }
    }

    private void finish(RunState state, Throwable error) {
        synchronized (mLock) {
            if (state.finished) {
                return;
            }
            state.finished = true;
            mRuns.remove(state.token);
        }
        // task-50 C：脚本结束后若还有浮窗/覆盖窗，宿主不要立刻停进程；给关闭/清理留时间。
        if (hasHostImGuiWindows() || hasOpenFloatyWindows(state.execution)) {
            mKeepAliveUntil = System.currentTimeMillis() + WINDOW_KEEPALIVE_MS;
            Log.i(TAG, "脚本结束时仍有窗口，延后停止隔离进程（" + WINDOW_KEEPALIVE_MS + "ms）");
        }
        // 先 flush + 解挂 console 监听，保证 103 日志先于 101/102 到达主进程（顺序不乱）。
        detachConsoleForwarder(state);
        if (error == null) {
            reply(state.replyTo, REPLY_FINISHED, state, null, null);
        } else {
            replyError(state, describeError(error));
        }
        stopIfIdle();
    }

    private void failRun(RunState state, Throwable error) {
        synchronized (mLock) {
            if (state.finished) {
                return;
            }
            state.finished = true;
            mRuns.remove(state.token);
        }
        detachConsoleForwarder(state);
        replyError(state, describeError(error));
        stopIfIdle();
    }

    // ------------------------------------------------------------------
    // Phase1 日志统一：:native console -> 主进程（what=103，与 :script 同一套协议/落点）
    // ------------------------------------------------------------------

    /** 给本次运行的引擎挂 console 监听（onStart 时调用；重复调用无副作用）。 */
    private void attachConsoleForwarder(RunState state, ScriptExecution execution) {
        if (state == null || execution == null) {
            return;
        }
        try {
            ScriptEngine engine = execution.getEngine();
            if (!(engine instanceof JavaScriptEngine)) {
                return;
            }
            com.stardust.autojs.runtime.ScriptRuntime runtime =
                    ((JavaScriptEngine) engine).getRuntime();
            if (runtime == null || !(runtime.console instanceof ConsoleImpl)) {
                return;
            }
            ConsoleForwarder forwarder;
            synchronized (mLock) {
                if (state.finished || state.consoleForwarder != null) {
                    return;
                }
                forwarder = new ConsoleForwarder(state, (ConsoleImpl) runtime.console);
                state.consoleForwarder = forwarder;
            }
            forwarder.attach();
            Log.i(TAG, "console 转发已挂接：" + state.token);
        } catch (Throwable error) {
            Log.w(TAG, "挂接 console 转发失败：" + state.token, error);
        }
    }

    /** 解挂 console 监听并 flush 剩余内容（finish/failRun/onDestroy 调用；可重复调用）。 */
    private void detachConsoleForwarder(RunState state) {
        if (state == null) {
            return;
        }
        ConsoleForwarder forwarder;
        synchronized (mLock) {
            forwarder = state.consoleForwarder;
            state.consoleForwarder = null;
        }
        if (forwarder != null) {
            forwarder.close();
        }
    }

    /** 把一批 console 文本以 what=103 回给主进程（与 :script 的 103 消息结构一致）。 */
    private void replyConsole(RunState state, String line, int level) {
        if (state == null || state.replyTo == null || line == null || line.isEmpty()) {
            return;
        }
        try {
            Message reply = Message.obtain(null, REPLY_CONSOLE);
            Bundle data = new Bundle();
            data.putString(KEY_TOKEN, state.token);
            data.putString(KEY_LINE, line);
            data.putInt(KEY_LEVEL, normalizeConsoleLevel(level));
            reply.setData(data);
            state.replyTo.send(reply);
        } catch (RemoteException error) {
            Log.w(TAG, "客户端已断开，console 转发失败（token=" + state.token + "）", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "console 转发组包失败（token=" + state.token + "）", error);
        }
    }

    private static int normalizeConsoleLevel(int level) {
        if (level < Log.VERBOSE || level > Log.ASSERT) {
            return Log.INFO;
        }
        return level;
    }

    /**
     * 一次运行的 console 监听：收到 {@link ConsoleImpl} 的每段输出后先进
     * {@link ConsoleBatch}（:script 同款公共缓冲），200ms 定时 flush，或达到 40 行/8KB 立即
     * flush；通过 what=103 批量回给主进程。
     *
     * <p>回调可能来自脚本线程（后台脚本）或主线程（UI 模式脚本）：缓冲与调度在本类加锁处理，
     * 真正的 Binder 发送在 flush 里（定时走 :native 主线程 Handler；阈值溢出时在当前线程直接发）。
     */
    private final class ConsoleForwarder implements ConsoleImpl.LogListener {

        private final RunState mState;
        private final ConsoleImpl mConsole;
        private final ConsoleBatch mBatch = new ConsoleBatch();
        private final Runnable mFlushRunnable = new Runnable() {
            @Override
            public void run() {
                flush();
            }
        };
        private boolean mFlushScheduled;
        private boolean mClosed;

        ConsoleForwarder(RunState state, ConsoleImpl console) {
            mState = state;
            mConsole = console;
        }

        void attach() {
            mConsole.setLogListener(this);
            synchronized (this) {
                if (mClosed) {
                    // close() 与 attach 竞态：关闭后不要把监听又挂回去。
                    try {
                        mConsole.setLogListener(null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        @Override
        public void onNewLog(ConsoleImpl.LogEntry logEntry) {
            if (logEntry == null || logEntry.content == null) {
                return;
            }
            ConsoleBatch.Batch overflow = null;
            boolean cancelTimer = false;
            synchronized (this) {
                if (mClosed) {
                    return;
                }
                overflow = mBatch.append(normalizeConsoleLevel(logEntry.level), logEntry.content);
                if (overflow != null) {
                    if (mFlushScheduled) {
                        mFlushScheduled = false;
                        cancelTimer = true;
                    }
                } else if (!mFlushScheduled) {
                    mFlushScheduled = true;
                    mConsoleHandler.postDelayed(mFlushRunnable, CONSOLE_FLUSH_INTERVAL_MS);
                }
            }
            if (overflow != null) {
                if (cancelTimer) {
                    mConsoleHandler.removeCallbacks(mFlushRunnable);
                }
                replyConsole(mState, overflow.text, overflow.level);
            }
        }

        @Override
        public void onLogClear() {
            // console.clear() 不转发：主进程 GlobalConsole 里已有日志不跟着清，避免误清主界面输出。
        }

        private void flush() {
            ConsoleBatch.Batch batch;
            synchronized (this) {
                mFlushScheduled = false;
                batch = mBatch.drain();
            }
            if (batch != null) {
                replyConsole(mState, batch.text, batch.level);
            }
        }

        /** 解挂并 flush 剩余内容（只执行一次）。 */
        void close() {
            ConsoleBatch.Batch rest;
            synchronized (this) {
                if (mClosed) {
                    return;
                }
                mClosed = true;
                mFlushScheduled = false;
                mConsoleHandler.removeCallbacks(mFlushRunnable);
                rest = mBatch.drain();
            }
            try {
                mConsole.setLogListener(null);
            } catch (Throwable error) {
                Log.w(TAG, "解挂 console 监听失败：" + mState.token, error);
            }
            if (rest != null) {
                replyConsole(mState, rest.text, rest.level);
            }
        }
    }

    private void forceStop(RunState state) {
        ScriptExecution execution;
        synchronized (mLock) {
            execution = state.execution;
        }
        if (execution == null) {
            return;
        }
        // task-50 C：STOP 顺序 = 先停帧循环/关闭窗口，再让引擎收尾（exit → nativeShutdown →
        // destroy）。ImGui 桥的 nativeShutdown(DestroyContext) 不能与 nativeNewFrame/nativeRender
        // 并发（见 docs/reports/2026-10-17_ImGui桥native上下文定位.md §6 #1/#3）。
        boolean soleRun;
        synchronized (mLock) {
            soleRun = mRuns.size() <= 1;
        }
        stopFramesAndWindows(state, execution, soleRun);
        try {
            // 主线程 Looper 上的 UI 执行需要走它自己的收尾（通知 listener / 销毁引擎 / 关运行日志），
            // 只 forceStop 引擎会让执行状态留在半路。
            ScriptEngineService engineService = getEngineService();
            if (engineService != null && engineService.stopExecution(execution)) {
                return;
            }
            ScriptEngine engine = execution.getEngine();
            if (engine != null) {
                engine.forceStop();
            }
        } catch (Throwable error) {
            Log.w(TAG, "停止脚本失败：" + state.token, error);
        }
    }

    /**
     * STOP 的第一阶段（task-50 C）：停帧循环、关闭悬浮窗/ImGui 覆盖窗，让引擎随后的
     * onExit/nativeShutdown 不再和渲染帧重叠。
     *
     * <p>只对 UI 模式脚本做（它们跑在本进程主 Looper，操作是安全的）：先 recycle 定时器
     * （ImGui 示例的 16ms setTimeout 帧循环就在其中），再关闭 floaty 窗口与宿主 ImGui 覆盖窗。
     * 后台脚本仍由引擎侧 B 任务串行化 exit，宿主这边不跨线程碰它的 runtime。
     */
    private void stopFramesAndWindows(RunState state, ScriptExecution execution, boolean soleRun) {
        if (state == null || !state.uiMode) {
            return;
        }
        ScriptEngine engine = execution.getEngine();
        if (engine instanceof JavaScriptEngine) {
            try {
                ScriptRuntime runtime = ((JavaScriptEngine) engine).getRuntime();
                if (runtime != null) {
                    // 1) 停帧循环：先取消所有定时器（16ms 渲染链不会再有下一帧）。
                    if (runtime.timers != null) {
                        runtime.timers.recycle();
                    }
                    // 2) 关窗：floaty 窗口的 surfaceDestroyed 回调（A 任务已投到脚本线程）
                    //    会在主 Looper 排队，nativeShutdown 由脚本 exit 清理统一收尾。
                    if (runtime.floaty != null) {
                        runtime.floaty.closeAll();
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "停止前停帧/关浮窗失败：" + state.token, error);
            }
        }
        // 3) 宿主侧 ImGui 覆盖窗（GLSurfaceView 自带渲染线程）：停止它再让引擎 destroy。
        //    只在当前是唯一运行任务时做（覆盖窗是进程级，别的脚本可能还在用）。
        if (soleRun) {
            try {
                ImGuiDrawingOverlay.closeAll();
            } catch (Throwable error) {
                Log.w(TAG, "停止前关闭 ImGui 覆盖窗失败：" + state.token, error);
            }
        }
    }

    /** 本次运行的 runtime.floaty 里是否还有没关的窗口（反射读 mWindows；失败按没有处理）。 */
    private static java.lang.reflect.Field sFloatyWindowsField;
    private static Class<?> sFloatyWindowsClass;

    private static boolean hasOpenFloatyWindows(ScriptExecution execution) {
        try {
            ScriptEngine engine = execution == null ? null : execution.getEngine();
            if (!(engine instanceof JavaScriptEngine)) {
                return false;
            }
            ScriptRuntime runtime = ((JavaScriptEngine) engine).getRuntime();
            if (runtime == null || runtime.floaty == null) {
                return false;
            }
            Class<?> type = runtime.floaty.getClass();
            if (sFloatyWindowsField == null || sFloatyWindowsClass != type) {
                sFloatyWindowsClass = type;
                try {
                    sFloatyWindowsField = type.getDeclaredField("mWindows");
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
        } catch (Throwable error) {
            return false;
        }
    }

    /** 宿主进程里是否还有 ImGui 覆盖窗（反射读 ImGuiDrawingOverlay.ALL；失败按没有处理）。 */
    private static java.lang.reflect.Field sOverlaySetField;
    private static boolean sOverlaySetResolved;

    private static boolean hasHostImGuiWindows() {
        try {
            if (!sOverlaySetResolved) {
                sOverlaySetResolved = true;
                try {
                    sOverlaySetField = ImGuiDrawingOverlay.class.getDeclaredField("ALL");
                    sOverlaySetField.setAccessible(true);
                } catch (Throwable ignored) {
                    sOverlaySetField = null;
                }
            }
            if (sOverlaySetField == null) {
                return false;
            }
            Object value = sOverlaySetField.get(null);
            return value instanceof Collection && !((Collection<?>) value).isEmpty();
        } catch (Throwable error) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ScriptEngineService requireEngineService() {
        ScriptEngineService engineService = getEngineService();
        if (engineService == null) {
            throw new IllegalStateException(
                    "脚本引擎尚未初始化（隔离进程 Application 未完成启动？）");
        }
        return engineService;
    }

    /** App.onCreate 在每个进程都会执行 AutoJs.initInstance，所以 :native 里也能直接拿单例。 */
    private ScriptEngineService getEngineService() {
        try {
            AutoJs autojs = AutoJs.getInstance();
            return autojs == null ? null : autojs.getScriptEngineService();
        } catch (Throwable error) {
            Log.w(TAG, "读取引擎服务失败", error);
            return null;
        }
    }

    /** 所有回复都在这里统一组包；单个回复失败不能影响脚本本身，所以吞掉异常只记 logcat。 */
    private void reply(Messenger replyTo, int what, RunState state, String name, String message) {
        if (replyTo == null) {
            return;
        }
        try {
            Message reply = Message.obtain(null, what);
            Bundle data = new Bundle();
            // 主进程固定按 String 解包（见 PocNativeRunner）；输入兼容数值，输出统一字符串。
            data.putString(KEY_TOKEN, state == null ? "" : state.token);
            if (name != null) {
                data.putString(KEY_NAME, name);
            }
            if (message != null) {
                data.putString(KEY_MESSAGE, message);
            }
            reply.setData(data);
            replyTo.send(reply);
        } catch (RemoteException error) {
            Log.w(TAG, "客户端已断开，回复失败（what=" + what + "）", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "组包回复失败（what=" + what + "）", error);
        }
    }

    private void replyError(RunState state, String message) {
        reply(state.replyTo, REPLY_ERROR, state, null, message);
    }

    /**
     * 暂存复制用的「项目根目录」：优先用 RUN 下发的 workDir（入口脚本在它之下时），
     * 否则退回入口脚本所在目录 —— 保证入口脚本一定在被复制的树里。
     */
    private static File resolveProjectDir(File scriptFile, String workDir) throws IOException {
        if (workDir != null && !workDir.trim().isEmpty()) {
            File candidate = new File(workDir.trim()).getCanonicalFile();
            if (candidate.isDirectory() && NativeProjectStaging.isUnder(scriptFile, candidate)) {
                return candidate;
            }
        }
        File parent = scriptFile.getParentFile();
        if (parent == null) {
            throw new IOException("无法确定脚本项目目录：" + scriptFile.getPath());
        }
        return parent.getCanonicalFile();
    }

    /**
     * 原入口脚本在暂存目录里的对应文件：按「原项目目录 -&gt; 暂存目录」的相对路径映射。
     * 项目本来就在私有目录（{@link NativeProjectStaging#stageIfNeeded} 原样返回）时直接用原脚本。
     */
    private static File stagedScriptOf(File projectDir, File stagedDir, File scriptFile) throws IOException {
        if (stagedDir.equals(projectDir)) {
            return scriptFile;
        }
        String root = projectDir.getCanonicalPath();
        String script = scriptFile.getCanonicalPath();
        String prefix = root.endsWith(File.separator) ? root : root + File.separator;
        File staged;
        if (script.startsWith(prefix)) {
            staged = new File(stagedDir, script.substring(prefix.length()));
        } else {
            // 正常不会走到：workDir 与脚本不在一棵树时按文件名放到暂存目录根下，仍从私有目录执行。
            staged = new File(stagedDir, scriptFile.getName());
        }
        if (!staged.isFile()) {
            throw new IOException("复制后的入口脚本不存在：" + staged.getPath());
        }
        return staged;
    }

    /** 异常 -> 给主进程看的一句话（用户主动停止走固定文案，主进程不会弹成错误）。 */
    private static String describeError(Throwable error) {
        if (ScriptInterruptedException.causedByInterrupted(error)) {
            return MESSAGE_STOPPED;
        }
        return messageOf(error);
    }

    /** 脚本是否声明了 {@code "ui";}（UI 模式：走主线程 Looper 宿主）。 */
    private static boolean isUiMode(JavaScriptSource source) {
        return (source.getExecutionMode() & JavaScriptSource.EXECUTION_MODE_UI) != 0;
    }

    /** 取异常消息：staging 抛出的 IOException 已经是中文，直接透传给主进程。 */
    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    /** 清掉其它项目遗留的 native-staging 副本（:native 一次只跑一个项目；10 连跑不堆积）。 */
    private static void cleanupStaleStaging(File currentStagedDir) {
        File root = currentStagedDir == null ? null : currentStagedDir.getParentFile();
        if (root == null) {
            return;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.equals(currentStagedDir)) {
                continue;
            }
            if (!deleteRecursively(child)) {
                Log.w(TAG, "清理旧暂存副本失败：" + child.getPath());
            }
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    /** token 约定是字符串；这里对少数按数值传来的写法也兼容一下，避免两端类型不符就静默丢任务。 */
    private static String tokenKey(Bundle data) {
        if (data == null) {
            return "";
        }
        String token = data.getString(KEY_TOKEN);
        if (token != null) {
            return token.trim();
        }
        int intToken = data.getInt(KEY_TOKEN, Integer.MIN_VALUE);
        return intToken == Integer.MIN_VALUE ? "" : String.valueOf(intToken);
    }

    /**
     * 客户端全部解绑且没有运行中脚本时才停服务，避免隔离进程常驻。
     *
     * <p>task-50 C：不立即 stopSelf。脚本刚结束时引擎的 nativeShutdown/GL 清理（B 任务串行化）
     * 与窗口关闭可能还在收尾，立刻停进程会把它们打断；这里延迟 {@link #IDLE_STOP_GRACE_MS}
     * （宿主 ImGui 覆盖窗还开着时用 {@link #WINDOW_KEEPALIVE_MS}，超时兜底），期间有新 RUN 会取消。
     */
    private void stopIfIdle() {
        boolean idle;
        synchronized (mLock) {
            idle = mBoundClients <= 0 && mRuns.isEmpty();
        }
        if (!idle) {
            return;
        }
        mIpcHandler.removeCallbacks(mIdleStop);
        long delay = hasHostImGuiWindows() ? WINDOW_KEEPALIVE_MS : IDLE_STOP_GRACE_MS;
        long keepAlive = mKeepAliveUntil - System.currentTimeMillis();
        if (keepAlive > delay) {
            // 有浮窗还没关：再等一段（上限 WINDOW_KEEPALIVE_MS*3，避免窗口泄漏把进程拖住）。
            delay = Math.min(keepAlive, WINDOW_KEEPALIVE_MS * 3);
        }
        Log.i(TAG, "隔离进程空闲，" + delay + "ms 后停止服务（等引擎/窗口收尾）");
        mIpcHandler.postDelayed(mIdleStop, delay);
    }

    /** 一次运行的状态。execution 由 handleRun 在 execute() 返回后填；监听器与 STOP 都走同一把锁。 */
    private static final class RunState {
        final String token;
        final Messenger replyTo;
        volatile String name = "";
        ScriptExecution execution;
        boolean stopRequested;
        boolean finished;
        /** 是否 UI 模式脚本（主 Looper 执行；STOP 时先停帧/关窗，见 forceStop）。 */
        boolean uiMode;
        /** Phase1 日志统一：本次运行的 console 监听（结束后解挂并 flush）。 */
        ConsoleForwarder consoleForwarder;

        RunState(String token, Messenger replyTo) {
            this.token = token;
            this.replyTo = replyTo;
        }
    }
}
