package com.jdkshen.aijspro.flutterpoc.scriptproc;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.core.console.ConsoleImpl;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.execution.ScriptExecutionTask;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.remote.RemoteAutomationBridge;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.JavaScriptSource;
import org.autojs.source.engine.android.Node24Client;
import org.autojs.source.engine.android.Node24PoolService1;
import org.autojs.source.engine.android.Node24PoolService2;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code :script} 进程里的通用脚本宿主服务（Phase 1-B）。
 *
 * <p>与 Phase0 的 {@code :native}（{@code NativeScriptService}）的关系：
 * <ul>
 *   <li>协议<b>逐字一致</b>：RUN=1{path,workDir,token} + replyTo、STOP=2{token}、
 *       100 STARTED{token,name}、101 FINISHED{token}、102 ERROR{token,message}；
 *       token 为 String 原样回显；用户主动停止固定回 102 + message「脚本已停止」；</li>
 *   <li>区别只在「宿主策略」：本服务接<b>所有脚本</b>（不再由 NativeProjectDetector 决定），
 *       统一先暂存到私有目录（{@link ScriptHostStaging}），普通脚本后台线程执行、
 *       {@code "ui";} 脚本走进程主 Looper（{@link ScriptEngineService#executeUiOnMainThread}）；</li>
 *   <li>Phase0 的 {@code NativeScriptService} 保留不动，作为回退路径；两个服务各自一个进程
 *       （{@code :native} / {@code :script}），暂存根目录也分开，互不踩踏。</li>
 * </ul>
 *
 * <p>进程声明（由 Phase1-D 在 {@code apps/app/src/flutterpoc/AndroidManifest.xml} 注册）：
 * {@code android:name="com.jdkshen.aijspro.flutterpoc.scriptproc.ScriptHostService"}、
 * {@code android:process=":script"}、{@code android:exported="false"}。
 * 主进程客户端是 Phase1-C 的 {@code PocScriptHost}，按 {@link #SERVICE_CLASS} 固定字符串绑定。
 *
 * <p>刻意保留的 Phase0 取舍：
 * <ul>
 *   <li><b>不起 Activity</b>：进程里没有界面；UI 模式脚本由引擎的主线程 Looper 宿主承载，
 *       不会走 {@code ScriptExecuteActivity}（headless 开关兜底，QuickJS/Node24 退回后台执行）；</li>
 *   <li><b>实时 console 转发（what=103）</b>：每次 RUN 在 onStart 时给本次引擎的
 *       {@code ConsoleImpl} 挂 {@code LogListener}，脚本的每段 console 输出先进
 *       {@link ConsoleBatch} 批量缓冲（200ms 定时 / 40 行 / 8KB 触发），再以
 *       what=103 CONSOLE{token,line,level,consoleTexts,consoleLevels} 回给 {@code PocScriptHost}；
 *       主进程按逐段等级分别写 {@code HostConsoleSink}（GlobalConsole 原生日志页实时可见）+ 环形缓冲，
 *       旧客户端忽略新增数组、仍按 line/level 整批显示。
 *       脚本结束/服务销毁时解挂并 flush。运行日志文件仍由引擎自动 ScriptRunLog 负责落盘，
 *       这里不重复 attach（避免多出永不收尾的日志文件）；</li>
 *   <li><b>统一 staging</b>：所有脚本从私有目录执行，避免可写 dex 限制与运行时原目录被改动；
 *       暂存失败直接 102，不退回原目录「将就跑」；</li>
 *   <li><b>不弥补无障碍/自动化能力</b>：{@code :script} 里 auto/click/selector 仍不可用，
 *       Phase1 后续由 AIDL 自动化代理（task-15 骨架 / spec 对齐后）补；</li>
 *   <li><b>不主动自杀</b>：还有脚本在跑时不 stopSelf；客户端全部解绑且没有运行中脚本时才停。</li>
 * </ul>
 *
 * <p>待统一对齐（不在本任务写作用域，报告里已标给 Lead）：
 * <ul>
 *   <li>{@code App.onCreate} 目前只对 {@code :native} 做重初始化跳过
 *       （{@code App.isNativeIsolationProcess()}），{@code :script} 进程会重复初始化定时任务/
 *       动态广播/示例同步等主进程职责，需要统一改成「隔离脚本进程」判断；</li>
 *   <li>FloatService 等辅助服务是否需要 {@code android:process=":script"}，等 Phase1-A spec。</li>
 * </ul>
 */
public class ScriptHostService extends Service {
    /** task-175：:script 进程内 Node 宿主进程池 spike/编排入口。 */
    private static final String ACTION_NODE24_POOL_SPIKE = "com.jdkshen.aijspro.NODE24_POOL_SPIKE";
    private android.content.BroadcastReceiver mNode24PoolSpikeReceiver;

    /** 主进程客户端绑定用的固定类名（Phase1-C {@code PocScriptHost} 依赖，不要改）。 */
    public static final String SERVICE_CLASS =
            "com.jdkshen.aijspro.flutterpoc.scriptproc.ScriptHostService";

    /** 客户端 -&gt; 服务：启动一个脚本。data: path/workDir/token，msg.replyTo 必填。 */
    public static final int MSG_RUN = 1;
    /** 客户端 -&gt; 服务：停止 token 对应的脚本。data: token。 */
    public static final int MSG_STOP = 2;
    public static final int MSG_STOP_ALL = 3;

    /** 服务 -&gt; 客户端：脚本已经开始执行。data: token/name。 */
    public static final int REPLY_STARTED = 100;
    /** 服务 -&gt; 客户端：脚本正常结束。data: token。 */
    public static final int REPLY_FINISHED = 101;
    /** 服务 -&gt; 客户端：脚本异常 / 被停止 / 无法启动。data: token/message。 */
    public static final int REPLY_ERROR = 102;
    /**
     * 服务 -&gt; 客户端：脚本控制台实时输出（Phase1 日志转发）。
     * data: {@link #KEY_TOKEN token} / {@link #KEY_LINE line}（可含多行，整批拼接文本）/
     * {@link #KEY_LEVEL level}（整批最高等级，旧客户端兼容）/ {@link #KEY_CONSOLE_TEXTS consoleTexts}
     * （String[]，每段 append 的文本，可含多行）/ {@link #KEY_CONSOLE_LEVELS consoleLevels}
     * （int[]，与 consoleTexts 等长同序的原始等级）。新客户端逐段按自己的等级落日志；旧客户端
     * 只读 line/level，整批仍按最高等级显示。
     *
     * <p>批量/节流：每次 RUN 挂 {@code ConsoleImpl.LogListener}，输出先进 {@link ConsoleBatch}，
     * 200ms 定时 flush，或累计 40 行/8KB 立即 flush，避免每行一条 Binder 消息。脚本结束前
     * 会把剩余内容 flush 掉再发 101/102，保证控制台顺序正确。
     */
    public static final int REPLY_CONSOLE = 103;

    public static final String KEY_PATH = "path";
    public static final String KEY_WORK_DIR = "workDir";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_NAME = "name";
    public static final String KEY_MESSAGE = "message";
    /** what=103 CONSOLE 的行内容键（可含多行，{@code \n} 分隔）。 */
    public static final String KEY_LINE = "line";
    /** what=103 CONSOLE 的等级键（android.util.Log 等级；批次取最高等级，旧客户端兼容）。 */
    public static final String KEY_LEVEL = "level";
    /** what=103 CONSOLE 的逐段文本键（String[]，与 {@link #KEY_CONSOLE_LEVELS} 等长同序）。 */
    public static final String KEY_CONSOLE_TEXTS = "consoleTexts";
    /** what=103 CONSOLE 的逐段等级键（int[]，每段原始 android.util.Log 等级）。 */
    public static final String KEY_CONSOLE_LEVELS = "consoleLevels";
    public static final String KEY_CONSOLE_TIMES = "consoleTimes";

    /** 用户主动 STOP 时 102 的固定文案：主进程靠它区分「用户停止」和「脚本报错」。 */
    public static final String MESSAGE_STOPPED = "脚本已停止";

    /** console 转发的定时 flush 间隔：批量回传，避免每行一条 Binder 消息。 */
    private static final long CONSOLE_FLUSH_INTERVAL_MS = 200L;

    private static final String TAG = "ScriptHostService";

    private final Object mLock = new Object();
    /** token -&gt; 这次运行的状态；只在主线程/脚本线程的同步块里读写。 */
    private final Map<String, RunState> mRuns = new LinkedHashMap<>();
    private int mBoundClients = 0;

    /** Phase1 P1：:script -> 主进程自动化代理（引用计数：RUN 增加、脚本结束时减少）。 */
    private final Object mAutomationLock = new Object();
    private int mAutomationUsers = 0;
    private AutomationHostClient mAutomationClient;
    private final java.util.concurrent.ThreadPoolExecutor mPreparation = new java.util.concurrent.ThreadPoolExecutor(
            1,2,30L,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(32),operation -> {
                Thread thread=new Thread(operation,"script-request-prepare"); thread.setDaemon(true); return thread;
            });

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
                case MSG_STOP_ALL:
                    handleStopAll();
                    return true;
                default:
                    return false;
            }
        }
    });

    /** console 转发 flush 用的主线程 Handler（与 IPC 分开，避免互相影响）。 */
    private final Handler mConsoleHandler = new Handler(Looper.getMainLooper());

    private final Messenger mMessenger = new Messenger(mIpcHandler);

    // ------------------------------------------------------------------
    // Service 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        // Phase1 P1：标记当前进程是隔离脚本进程，引擎据此把 auto/click/selector/截图代理到主进程
        // （RemoteAutomationBridge + ScriptRuntime/SimpleActionAutomator/Images 的 remote 分支）。
        RemoteAutomationBridge.setIsolationProcess(false);
        registerNode24PoolSpikeReceiver();
    }

    /** task-175：接收 debug 广播，在 :script 进程内通过 Node 宿主进程池并行跑两个 Node 脚本。 */
    private void registerNode24PoolSpikeReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ACTION_NODE24_POOL_SPIKE.equals(intent.getAction())) {
                    runNode24PoolSpike();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_NODE24_POOL_SPIKE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        mNode24PoolSpikeReceiver = receiver;
    }

    private void runNode24PoolSpike() {
        new Thread(() -> {
            long wall = android.os.SystemClock.elapsedRealtime();
            android.util.Log.i(TAG, "NODE_POOL_SPIKE_START pid=" + android.os.Process.myPid());
            Node24Client[] clients = new Node24Client[2];
            try {
                clients[0] = new Node24Client(getApplicationContext(), Node24PoolService1.class);
                clients[1] = new Node24Client(getApplicationContext(), Node24PoolService2.class);
                final String[] results = new String[2];
                final long[] times = new long[2];
                Thread[] threads = new Thread[2];
                String[] sources = {
                    "\"nodejs\"; const t=Date.now(); setTimeout(()=>{ console.log('POOL_A_DONE '+(Date.now()-t)); },3000);",
                    "\"nodejs\"; const t=Date.now(); let n=0; const id=setInterval(()=>{ n++; if(n>=6){ clearInterval(id); console.log('POOL_B_DONE '+(Date.now()-t)); } },500);"
                };
                for (int i = 0; i < 2; i++) {
                    final int index = i;
                    final Node24Client client = clients[i];
                    threads[i] = new Thread(() -> {
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        try {
                            JSONObject req = new JSONObject()
                                    .put("id", "pool-spike-" + index + "-" + System.nanoTime())
                                    .put("source", sources[index])
                                    .put("filename", "/sdcard/脚本/pool-spike-" + index + ".js")
                                    .put("timeoutMillis", 10000L)
                                    .put("uiScript", false);
                            JSONObject result = client.evaluate(req);
                            results[index] = "status=" + result.optString("status")
                                    + " exit=" + result.optInt("exitCode")
                                    + " cancelled=" + result.optBoolean("cancelled");
                        } catch (Throwable error) {
                            results[index] = "ERROR " + error;
                        }
                        times[index] = android.os.SystemClock.elapsedRealtime() - t0;
                        android.util.Log.i(TAG, "NODE_POOL_SPIKE_" + (index == 0 ? "A" : "B")
                                + "_END ms=" + times[index] + " " + results[index]);
                    }, "pool-spike-" + i);
                }
                threads[0].start();
                threads[1].start();
                threads[0].join(20000);
                threads[1].join(20000);
                android.util.Log.i(TAG, "NODE_POOL_SPIKE_PARALLEL wall="
                        + (android.os.SystemClock.elapsedRealtime() - wall)
                        + " a=" + times[0] + " b=" + times[1]);
                for (Node24Client client : clients) {
                    try { if (client != null) client.close(); } catch (Throwable ignored) { }
                }
                // 停止后立即再跑：验证槽可复用且不再 BUSY。
                Node24Client reuse = new Node24Client(getApplicationContext(), Node24PoolService1.class);
                try {
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    JSONObject req = new JSONObject()
                            .put("id", "pool-spike-reuse-" + System.nanoTime())
                            .put("source", "\"nodejs\"; console.log('POOL_REUSE_OK');")
                            .put("filename", "/sdcard/脚本/pool-spike-reuse.js")
                            .put("timeoutMillis", 5000L)
                            .put("uiScript", false);
                    JSONObject result = reuse.evaluate(req);
                    android.util.Log.i(TAG, "NODE_POOL_SPIKE_REUSE ms="
                            + (android.os.SystemClock.elapsedRealtime() - t0)
                            + " status=" + result.optString("status")
                            + " exit=" + result.optInt("exitCode"));
                } finally {
                    reuse.close();
                }
                // 分别停止：长驻 Node 任务在 PoolService3 上运行，1.2s 后按 id cancel，验证只停一个。
                Node24Client cancelClient = new Node24Client(getApplicationContext(),
                        org.autojs.source.engine.android.Node24PoolService3.class);
                try {
                    final String cancelId = "pool-spike-cancel-" + System.nanoTime();
                    final JSONObject[] cancelResult = new JSONObject[1];
                    Thread longTask = new Thread(() -> {
                        try {
                            JSONObject req = new JSONObject()
                                    .put("id", cancelId)
                                    .put("source", "\"nodejs\"; setInterval(()=>{},1000);")
                                    .put("filename", "/sdcard/脚本/pool-spike-cancel.js")
                                    .put("timeoutMillis", 20000L)
                                    .put("uiScript", false);
                            cancelResult[0] = cancelClient.evaluate(req);
                        } catch (Throwable error) {
                            android.util.Log.w(TAG, "NODE_POOL_SPIKE_CANCEL_EVAL_FAIL", error);
                        }
                    }, "pool-spike-cancel");
                    long t0 = android.os.SystemClock.elapsedRealtime();
                    longTask.start();
                    Thread.sleep(1200L);
                    boolean cancelSent = cancelClient.cancel(cancelId);
                    longTask.join(8000);
                    JSONObject cr = cancelResult[0];
                    android.util.Log.i(TAG, "NODE_POOL_SPIKE_CANCEL sent=" + cancelSent
                            + " elapsed=" + (android.os.SystemClock.elapsedRealtime() - t0)
                            + " status=" + (cr == null ? "null" : cr.optString("status"))
                            + " cancelled=" + (cr != null && cr.optBoolean("cancelled")));
                } finally {
                    cancelClient.close();
                }
            } catch (Throwable error) {
                android.util.Log.e(TAG, "NODE_POOL_SPIKE_FAIL", error);
            } finally {
                for (Node24Client client : clients) {
                    try { if (client != null) client.close(); } catch (Throwable ignored) { }
                }
                android.util.Log.i(TAG, "NODE_POOL_SPIKE_END");
            }
        }, "node-pool-spike").start();
    }

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
        // 下次运行由主进程重新 startService + bindService（见 PocScriptHost）。
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mPreparation.shutdownNow();
        // Phase1 P1：进程退出前解除远程自动化后端（避免 AIDL 绑定泄漏）。
        try {
            synchronized (mAutomationLock) {
                RemoteAutomationBridge.uninstall(mAutomationClient);
                if (mAutomationClient != null) {
                    mAutomationClient.disconnect();
                    mAutomationClient = null;
                }
                mAutomationUsers = 0;
            }
        } catch (Throwable error) {
            Log.w(TAG, "释放远程自动化后端失败", error);
        }
        // Phase1 日志转发：先 flush 并解挂所有 console 监听，否则最后的批量日志会丢。
        RunState[] runs;
        synchronized (mLock) {
            runs = mRuns.values().toArray(new RunState[0]);
        }
        for (RunState run : runs) {
            try {
                detachConsoleForwarder(run);
            } catch (Throwable error) {
                Log.w(TAG, "解挂 console 转发失败：" + run.token, error);
            }
        }
        // 服务/进程被回收时尽力把还在跑的脚本停掉，别留下半截引擎线程。
        try {
            ScriptEngineService engineService = getEngineService();
            if (engineService != null) {
                engineService.stopAll();
            }
        } catch (Throwable error) {
            Log.w(TAG, "停止脚本进程内脚本失败", error);
        }
        ScriptEngineService.setHeadlessUiMode(false);
        try {
            if (mNode24PoolSpikeReceiver != null) {
                unregisterReceiver(mNode24PoolSpikeReceiver);
                mNode24PoolSpikeReceiver = null;
            }
        } catch (Throwable ignored) { }
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

        try { mPreparation.execute(() -> prepareRun(state,data,path,workDir)); }
        catch (RuntimeException rejected) { failRun(state,rejected); }
    }

    private void prepareRun(RunState state,Bundle data,String path,String workDir) {
        try {
            if (state.stopRequested) throw new ScriptInterruptedException();
            ScriptEngineService engineService = requireEngineService();
            com.stardust.autojs.script.JavaScriptSource source;
            ExecutionConfig config;
            String requestFile=data.getString("requestFile");
            if (requestFile!=null) {
                state.requestPacket=ScriptHostRequest.validatedFile(getApplicationContext(),requestFile);
                ScriptHostRequest.Loaded loaded=ScriptHostRequest.read(getApplicationContext(),requestFile);
                source=loaded.source; config=loaded.config; state.actor=true;
            } else {
            File file = new File(path == null ? "" : path).getCanonicalFile();
            if (!file.isFile()) {
                throw new IllegalStateException("脚本文件不存在：" + (path == null ? "" : path));
            }
            // 所有脚本统一从私有目录执行（.so/.dex 可写目录限制 + 运行时原目录稳定性），
            // Phase0 只在 native 项目做的那套 staging 现在对普通脚本也生效。
            File workDirFile = (workDir != null && !workDir.trim().isEmpty())
                    ? new File(workDir.trim()) : null;
            ScriptHostStaging.Staged staged;
            try {
                staged = ScriptHostStaging.stageIfNeeded(getApplicationContext(), file, workDirFile);
            } catch (IOException error) {
                String detail = messageOf(error);
                throw new IOException(detail.startsWith("脚本暂存失败")
                        ? detail : "脚本暂存到应用私有目录失败：" + detail, error);
            }

            source = new JavaScriptFileSource(staged.scriptFile);
            config = ExecutionConfig.getDefault();
            config.setWorkingDirectory(staged.workingDirectory.getAbsolutePath());
            }
            state.name=source.getName();
            if (state.stopRequested) throw new ScriptInterruptedException();

            // Phase1 P1：注入主进程自动化后端（AIDL）；脚本里 auto/click/selector/screenshot 走主进程。
            retainAutomationBackend();
            state.automationRetained = true;

            // The Activity and execution registry now both belong to :script. This is
            // per-task policy, so another UI job cannot change a concurrent job's route.
            // 监听器负责把开始/结束/异常回给主进程。
            ScriptExecutionListener listener = new RunListener(state);
            // Node's own UI route is selected by ScriptEngineService; it never creates
            // an empty compatibility Activity around the Node pool Activity.
            ScriptExecution execution = engineService.execute(source, listener, config);

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

    private void handleStopAll() {
        RunState[] runs;
        synchronized (mLock) {
            runs = mRuns.values().toArray(new RunState[0]);
            for (RunState run : runs) run.stopRequested = true;
        }
        for (RunState run : runs) forceStop(run);
        ScriptEngineService service = getEngineService();
        if (service != null) service.stopAll();
        com.stardust.autojs.overlay.OverlayRegistry.closeAll("script_host_stop_all");
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
                mState.startMillis = System.currentTimeMillis();
                mState.executionId=execution.getId(); mState.engineId=execution.getEngine().getId();
                mState.engine=execution.getSource().getEngineName();
                if (mState.actor && execution.getEngine() instanceof com.stardust.autojs.engine.RhinoJavaScriptEngine) {
                    Object argv=((com.stardust.autojs.engine.JavaScriptEngine)execution.getEngine()).getExecArgv();
                    org.mozilla.javascript.Context context=org.mozilla.javascript.Context.getCurrentContext();
                    if (context!=null && argv instanceof org.mozilla.javascript.Scriptable) {
                        com.jdkshen.aijspro.mcp.McpRhinoArguments.INSTANCE.install(context,
                                (org.mozilla.javascript.Scriptable)argv,execution.getConfig().getArguments());
                    }
                }
                synchronized (mLock) {
                    mState.execution = execution;
                }
                reply(mState.replyTo, REPLY_STARTED, mState, mState.name, null);
                // onStart 时引擎已创建、脚本还没开始跑，正好挂 console 监听（每行实时回主进程）。
                attachConsoleForwarder(mState, execution);
                // task-191 P0：开始行也进主进程统一日志页（与 console.log 同流，TRACE/VERBOSE）。
                forwardGlobalLine(mState, "开始运行[" + execution.getSource().toString() + "]");
                boolean stopNow;
                synchronized (mLock) {
                    stopNow = mState.stopRequested && !mState.finished;
                }
                if (stopNow) {
                    forceStop(mState);
                }
            } catch (Throwable error) {
                Log.w(TAG, "上报 STARTED 失败：" + mState.token, error);
                throw error instanceof RuntimeException ? (RuntimeException)error : new IllegalStateException(error);
            }
        }

        @Override
        public void onSuccess(ScriptExecution execution, Object result) {
            mState.result=result==null ? null : String.valueOf(result);
            if (mState.result!=null && mState.result.length()>4096) mState.result=mState.result.substring(0,4096);
            completeAfterRelease(mState,execution,null);
        }

        @Override
        public void onException(ScriptExecution execution, Throwable e) {
            completeAfterRelease(mState,execution,e);
        }

    }

    private void completeAfterRelease(RunState state,ScriptExecution execution,Throwable error) {
        synchronized (mLock) {
            if (state.finished) return;
            if (error!=null) state.failure=error;
            if (state.completing) return;
            state.completing=true;
        }
        mConsoleHandler.post(new Runnable() {
            @Override public void run() {
                if (state.finished) return;
                if (execution.getEngine()!=null && !execution.getEngine().isDestroyed()) {
                    mConsoleHandler.postDelayed(this,20L); return;
                }
                flushConsoleBeforeFinish(state);
                forwardGlobalLine(state,formatFinishedLine(state,execution));
                finish(state,state.failure);
            }
        });
    }

    private void flushConsoleBeforeFinish(RunState state) {
        ConsoleForwarder forwarder;
        synchronized (mLock) { forwarder = state.consoleForwarder; }
        if (forwarder != null) forwarder.flush();
    }

    private void finish(RunState state, Throwable error) {
        synchronized (mLock) {
            if (state.finished) {
                return;
            }
            state.finished = true;
            mRuns.remove(state.token);
        }
        // 先 flush + 解挂 console 监听，保证 103 日志先于 101/102 到达主进程（顺序不乱）。
        detachConsoleForwarder(state);
        if (error == null) {
            reply(state.replyTo, REPLY_FINISHED, state, null, null);
        } else {
            replyError(state, describeError(error));
        }
        releaseAutomationBackendIfNeeded(state);
        if (state.requestPacket!=null) ScriptHostRequest.discard(state.requestPacket);
        stopIfIdle();
    }

    private void failRun(RunState state, Throwable error) {
        state.failure=error;
        synchronized (mLock) {
            if (state.finished) {
                return;
            }
            state.finished = true;
            mRuns.remove(state.token);
        }
        detachConsoleForwarder(state);
        replyError(state, describeError(error));
        releaseAutomationBackendIfNeeded(state);
        if (state.requestPacket!=null) ScriptHostRequest.discard(state.requestPacket);
        stopIfIdle();
    }

    /**
     * Phase1 P1：为本次 RUN 注入主进程自动化后端（引用计数；第一个 RUN 时 install，最后一个结束时 uninstall）。
     *
     * <p>这里只 install、不做同步 bind：AIDL 客户端在脚本线程第一次调用时懒绑定（最多等 2s），
     * 避免阻塞本服务的主线程 Looper（handleRun 就在主线程）。
     */
    private void retainAutomationBackend() { }

    private void releaseAutomationBackendIfNeeded(RunState state) {
        if (state != null) state.automationRetained = false;
    }

    // ------------------------------------------------------------------
    // Phase1 日志转发：:script console -> 主进程（what=103）
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
            ScriptRuntime runtime = ((JavaScriptEngine) engine).getRuntime();
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

    /** task-191 P0：把运行开始/结束行以 what=103 单独转发到主进程统一日志页（TRACE/VERBOSE）。 */
    private void forwardGlobalLine(RunState state, String text) {
        if (state == null || state.replyTo == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            Message reply = Message.obtain(null, REPLY_CONSOLE);
            Bundle data = new Bundle();
            data.putString(KEY_TOKEN, state.token);
            data.putString(KEY_LINE, text);
            data.putInt(KEY_LEVEL, Log.VERBOSE);
            data.putStringArray(KEY_CONSOLE_TEXTS, new String[]{text});
            data.putIntArray(KEY_CONSOLE_LEVELS, new int[]{Log.VERBOSE});
            data.putLongArray(KEY_CONSOLE_TIMES, new long[]{System.currentTimeMillis()});
            reply.setData(data);
            state.replyTo.send(reply);
        } catch (RemoteException error) {
            Log.w(TAG, "运行开始/结束行转发失败（token=" + state.token + "）", error);
        } catch (RuntimeException error) {
            Log.w(TAG, "运行开始/结束行组包失败（token=" + state.token + "）", error);
        }
    }

    /** task-191：与 Pro 对齐的结束行（\n------------\n[source]运行结束，用时%f秒，6 位小数）。 */
    private static String formatFinishedLine(RunState state, ScriptExecution execution) {
        long start = state == null ? 0L : state.startMillis;
        if (execution != null && execution.getEngine() != null) {
            Object tag = execution.getEngine().getTag("com.jdkshen.aijspro.autojs.Goodbye, World");
            if (tag instanceof Long) {
                start = (Long) tag;
            }
        }
        if (start <= 0L || execution == null) {
            return "";
        }
        String source = execution.getSource().toString();
        if (source.startsWith("no-log##")) {
            return "";
        }
        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        return "\n------------\n[" + source + "]运行结束，用时"
                + String.format(java.util.Locale.US, "%.6f", seconds) + "秒";
    }

    /** 把一批 console 文本以 what=103 回给主进程（旧 line/level + 新逐段数组）。 */
    private void replyConsole(RunState state, ConsoleBatch.Batch batch) {
        if (state == null || state.replyTo == null || batch == null || batch.isEmpty()) {
            return;
        }
        try {
            Message reply = Message.obtain(null, REPLY_CONSOLE);
            Bundle data = new Bundle();
            data.putString(KEY_TOKEN, state.token);
            // 旧客户端兼容：整批拼接文本 + 批次最高等级。
            data.putString(KEY_LINE, batch.text);
            data.putInt(KEY_LEVEL, normalizeConsoleLevel(batch.level));
            // 新客户端：逐段文本/等级（drain 时已做快照，数组不再变化）。
            data.putStringArray(KEY_CONSOLE_TEXTS, batch.segmentTexts);
            data.putIntArray(KEY_CONSOLE_LEVELS, batch.segmentLevels);
            data.putLongArray(KEY_CONSOLE_TIMES, batch.segmentTimes);
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
     * 一次运行的 console 监听：收到 {@link ConsoleImpl} 的每段输出后先进 {@link ConsoleBatch}，
     * 200ms 定时 flush，或达到 40 行/8KB 立即 flush；通过 what=103 批量回给主进程。
     *
     * <p>回调可能来自脚本线程（后台脚本）或主线程（UI 模式脚本）：缓冲与调度在本类加锁处理，
     * 真正的 Binder 发送在 flush 里（定时走 :script 主线程 Handler；阈值溢出时在当前线程直接发，
     * 避免本轮输出再等 200ms）。
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
                overflow = mBatch.append(normalizeConsoleLevel(logEntry.level), logEntry.content, logEntry.time);
                if (overflow != null) {
                    if (mFlushScheduled) {
                        mFlushScheduled = false;
                        cancelTimer = true;
                    }
                } else if (!mFlushScheduled) {
                    mFlushScheduled = true;
                    mConsoleHandler.postDelayed(mFlushRunnable, CONSOLE_FLUSH_INTERVAL_MS);
                }
                if (overflow != null) {
                    if (cancelTimer) mConsoleHandler.removeCallbacks(mFlushRunnable);
                    replyConsole(mState, overflow);
                }
            }
        }

        @Override
        public void onLogClear() {
            // console.clear() 不转发：主进程 GlobalConsole 里已有日志不跟着清，避免误清主界面输出。
        }

        private synchronized void flush() {
            mFlushScheduled = false;
            ConsoleBatch.Batch batch = mBatch.drain();
            if (batch != null) replyConsole(mState, batch);
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
                replyConsole(mState, rest);
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

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ScriptEngineService requireEngineService() {
        ScriptEngineService engineService = getEngineService();
        if (engineService == null) {
            throw new IllegalStateException(
                    "脚本引擎尚未初始化（:script 进程 Application 未完成启动？）");
        }
        return engineService;
    }

    /** App.onCreate 在每个进程都会执行 AutoJs.initInstance，所以 :script 里也能直接拿单例。 */
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
            // 主进程固定按 String 解包（见 PocScriptHost）；输入兼容数值，输出统一字符串。
            data.putString(KEY_TOKEN, state == null ? "" : state.token);
            if (state!=null) {
                data.putInt("executionId",state.executionId); data.putInt("engineId",state.engineId);
                data.putString("engine",state.engine); data.putString("result",state.result);
                data.putString("process",com.stardust.autojs.execution.ScriptProcessIdentity.name()); data.putInt("pid",android.os.Process.myPid());
                Throwable failure=state.failure;
                if (failure!=null) {
                    data.putBoolean("interrupted",ScriptInterruptedException.causedByInterrupted(failure));
                    data.putString("errorType",failure.getClass().getName());
                    String stack=Log.getStackTraceString(failure);
                    data.putString("errorStack",stack.length()>16384 ? stack.substring(0,16384) : stack);
                }
            }
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

    /** 客户端全部解绑且没有运行中脚本时才停服务，避免 :script 进程常驻。 */
    private void stopIfIdle() {
        boolean idle;
        synchronized (mLock) {
            idle = mBoundClients <= 0 && mRuns.isEmpty();
        }
        if (idle) {
            stopSelf();
        }
    }

    /** 一次运行的状态。execution 由 handleRun 在 execute() 返回后填；监听器与 STOP 都走同一把锁。 */
    private static final class RunState {
        final String token;
        final Messenger replyTo;
        volatile String name = "";
        volatile ScriptExecution execution;
        long startMillis;
        volatile boolean stopRequested;
        volatile boolean finished;
        boolean actor,completing;
        volatile int executionId=-1,engineId=-1;
        volatile String engine="",result;
        volatile Throwable failure;
        volatile File requestPacket;
        /** Phase1 P1：本次 RUN 是否已经注入过远程自动化后端（结束时释放一次）。 */
        boolean automationRetained;
        /** Phase1 日志转发：本次运行的 console 监听（结束后解挂并 flush）。 */
        ConsoleForwarder consoleForwarder;

        RunState(String token, Messenger replyTo) {
            this.token = token;
            this.replyTo = replyTo;
        }
    }
}
