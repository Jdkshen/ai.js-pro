package com.jdkshen.aijspro.flutterpoc.nativeproc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import com.jdkshen.aijspro.flutterpoc.scriptproc.ConsoleBatch;
import com.jdkshen.aijspro.flutterpoc.scriptproc.HostConsoleSink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主进程侧的「:native 隔离进程」客户端：把自带 .so/.dex 的脚本项目交给独立进程执行。
 *
 * <p>Phase0 的安全目标：需要加载原生库的脚本放进 {@code :native} 进程跑，脚本把原生库跑崩时
 * 只死脚本进程，主界面（以及 AutoJs 引擎本身）不受影响；主进程通过 binder death 感知到崩溃，
 * 清掉任务列表、提示一次，然后重新绑定，下一次运行照常可用。
 *
 * <p>协议（与 {@code NativeScriptService} 逐字一致，服务在 AndroidManifest 里声明为
 * {@code android:process=":native"}）：
 * <ul>
 *   <li>客户端 -> 服务：{@code what=1 RUN}，data {@code path}/{@code workDir}/{@code token}
 *       （均为 String），{@link Message#replyTo} 指向本类的客户端 Messenger；
 *       {@code what=2 STOP}，data 只有 {@code token}。</li>
 *   <li>服务 -> 客户端：{@code what=100 STARTED}（data {@code token}/{@code name}）、
 *       {@code what=101 FINISHED}（data {@code token}）、
 *       {@code what=102 ERROR}（data {@code token}/{@code message}）；
 *       {@code what=103 CONSOLE}（data {@code token}/{@code line}（整批拼接文本，多行用
 *       {@code \n} 分隔）/{@code level}（整批最高等级），新版另带可选
 *       {@code consoleTexts}/{@code consoleLevels}（逐段文本/等级，等长同序））：
 *       :native 脚本 console 的实时批量回传，收到后交给 {@link HostConsoleSink}（与 :script 的
 *       103 同一落点：主进程 GlobalConsole + 环形缓冲，Flutter 实时日志页/原生日志页统一一条流）；
 *       两数组齐全合法时按各段原等级逐段转发，缺失/畸形时回退 {@code line}/{@code level}。</li>
 * </ul>
 *
 * <p>对外任务列表 token 格式为 {@code "native:<协议 token>"}，与 {@code PocRuntime} 普通脚本的
 * 纯数字 token 区分开；{@code PocRuntime.stopTasks} 据此把停止请求路由回这里。
 *
 * <p>隔离进程里没有 Accessibility/Automator 能力，本类不做任何兜底；也不启动任何 Activity。
 */
public final class PocNativeRunner {

    private static final String TAG = "PocNativeRunner";

    /**
     * 隔离进程里脚本宿主服务的类名。
     *
     * <p>用固定字符串而不是 {@code NativeScriptService.class}：主进程侧不需要在编译期依赖服务类，
     * 两边可以各自独立实现/编译，运行时按 manifest 声明绑定。服务名由 task-8 在
     * {@code apps/app/src/flutterpoc/AndroidManifest.xml} 注册（{@code android:process=":native"}）。
     */
    public static final String SERVICE_CLASS =
            "com.jdkshen.aijspro.flutterpoc.nativeproc.NativeScriptService";

    // ---- Messenger 协议 what 值（必须与服务端一致，改动前先对齐）----------------
    /** 客户端 -> 服务：启动一个脚本。 */
    private static final int WHAT_RUN = 1;
    /** 客户端 -> 服务：停止指定 token 的脚本。 */
    private static final int WHAT_STOP = 2;
    private static final int WHAT_STOP_ALL = 3;
    /** 服务 -> 客户端：脚本已开始执行。 */
    private static final int WHAT_STARTED = 100;
    /** 服务 -> 客户端：脚本正常结束。 */
    private static final int WHAT_FINISHED = 101;
    /** 服务 -> 客户端：脚本异常/被停止。 */
    private static final int WHAT_ERROR = 102;
    /** 服务 -> 客户端：脚本 console 实时输出（Phase1 日志统一，批量/节流后发送）。 */
    private static final int WHAT_CONSOLE = 103;

    // ---- console 转发消息的 data 键（与 NativeScriptService 的 KEY_LINE/KEY_LEVEL 一致）----
    private static final String KEY_CONSOLE_LINE = "line";
    private static final String KEY_CONSOLE_LEVEL = "level";
    /** 新服务端附带的逐段文本（String[]）；旧服务端没有，按 line/level 回退。 */
    private static final String KEY_CONSOLE_TEXTS = "consoleTexts";
    /** 新服务端附带的逐段等级（int[]）；与 consoleTexts 等长同序。 */
    private static final String KEY_CONSOLE_LEVELS = "consoleLevels";
    private static final String KEY_CONSOLE_TIMES = "consoleTimes";

    /** 任务列表里 native 任务的 token 前缀；去掉后就是协议里的 token。 */
    public static final String TOKEN_PREFIX = "native:";

    /** 服务端对「用户主动停止」回的 ERROR message，不当成脚本错误提示。 */
    private static final String STOP_MESSAGE = "脚本已停止";

    /** 连接超时：bindService 返回 true 后迟迟没有 onServiceConnected 时的兜底。 */
    private static final long CONNECT_TIMEOUT_MS = 8000L;
    /** 隔离进程连续退出后的自动重连上限，避免服务启动即崩时无限重连。 */
    private static final int MAX_AUTO_RECONNECT = 3;
    /** 自动重连基础延迟（毫秒），按连续退出次数递增。 */
    private static final long RECONNECT_DELAY_MS = 400L;
    /**
     * 空闲释放隔离进程的延迟（task-50 C）：任务全部结束后等待一会再 unbind/stopService，
     * 让服务端把 nativeShutdown/窗口收尾做完；下次运行重新 bind 会拉起全新 :native 进程，
     * 避免 .so/ImGui/EGL 上下文跨轮次残留（连续运行/停止 10 次的稳定性）。
     */
    private static final long IDLE_RELEASE_MS = 1500L;

    private static final Object LOCK = new Object();
    /** 运行中（含已提交给服务、等待 STARTED）的任务，key=协议 token。LinkedHashMap 保证列表顺序稳定。 */
    private static final Map<String, Task> TASKS = new LinkedHashMap<>();
    /** 服务尚未连上时先攒着的 RUN/STOP 消息，连上后按顺序补发。 */
    private static final List<Message> PENDING = new ArrayList<>();

    private static Context appContext;
    private static Messenger service;
    /** 当前连接对应的远程 binder，掉线回收时用来解除 death 监听。 */
    private static IBinder remoteBinder;
    /** bindService 已返回 true 且尚未 unbind。 */
    private static boolean bound;
    /** 正在 bindService、等待 onServiceConnected。 */
    private static boolean binding;
    /** 连接代数：避免连接超时回调误伤后一次连接尝试。 */
    private static int connectAttempt;
    private static int nextToken;
    private static int consecutiveDeaths;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 任务全部结束后的空闲释放动作（可通过 run() 取消）。 */
    private static final Runnable IDLE_RELEASE = new Runnable() {
        @Override
        public void run() {
            releaseIdleConnection();
        }
    };

    private PocNativeRunner() {
    }

    /** 一条 native 运行任务（任务页只需要这三个字段 + 固定展示字段）。 */
    private static final class Task {
        final String token;
        final String path;
        String name;

        Task(String token, String name, String path) {
            this.token = token;
            this.name = name;
            this.path = path;
        }
    }

    // ---- 对外接口 --------------------------------------------------------

    /**
     * 在 :native 隔离进程里运行一个脚本文件。
     *
     * @param context    任意 Context（内部转成 Application context，避免持有 Activity）
     * @param scriptFile 入口脚本文件（绝对路径）；为 null 时忽略
     * @param workDir    工作目录；为 null 时退回脚本所在目录
     */
    public static void run(Context context, File scriptFile, File workDir) {
        if (scriptFile == null) {
            return;
        }
        // 有新运行：取消上一次任务结束后的空闲释放。
        MAIN.removeCallbacks(IDLE_RELEASE);
        attach(context);
        final String path = scriptFile.getAbsolutePath();
        final File directory = workDir != null ? workDir : scriptFile.getParentFile();
        final String workDirPath = directory == null ? "" : directory.getAbsolutePath();
        final String token;
        synchronized (LOCK) {
            token = String.valueOf(++nextToken);
            // 先入列表再发消息：桥方法立即返回，任务页随后就能看到「隔离进程」这一条。
            TASKS.put(token, new Task(token, displayName(scriptFile), path));
        }
        Message message = Message.obtain(null, WHAT_RUN);
        Bundle data = new Bundle();
        data.putString("path", path);
        data.putString("workDir", workDirPath);
        data.putString("token", token);
        message.setData(data);
        message.replyTo = CLIENT;
        dispatch(message);
    }

    /**
     * 停止 :native 进程里指定 token 的脚本。
     *
     * @param id 协议 token（任务列表 token 去掉 {@link #TOKEN_PREFIX} 前缀后的部分）
     */
    public static void stop(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        // 先乐观移出列表：任务页/「停止全部」不必等服务的 FINISHED 回执。
        boolean idle;
        synchronized (LOCK) {
            TASKS.remove(id);
            idle = TASKS.isEmpty();
        }
        if (idle && !binding && bound) {
            // 服务端对 STOP 可能只打日志不回执（execution 还没赋值等）：同样安排空闲释放。
            scheduleIdleRelease();
        }
        Message message = Message.obtain(null, WHAT_STOP);
        Bundle data = new Bundle();
        data.putString("token", id);
        message.setData(data);
        Messenger target;
        synchronized (LOCK) {
            target = service;
            if (target == null && (binding || bound)) {
                // 正在连接：排队，连上后立刻补发，避免 STOP 丢失。
                PENDING.add(message);
                return;
            }
        }
        if (target != null) {
            send(target, message);
        }
        // 完全没有连接：隔离进程本就不存在，没有可停的脚本，直接忽略。
    }

    public static void stopAll() {
        Messenger target;
        Message stop = Message.obtain(null, WHAT_STOP_ALL);
        synchronized (LOCK) {
            TASKS.clear();
            java.util.Iterator<Message> iterator = PENDING.iterator();
            while (iterator.hasNext()) if (iterator.next().what == WHAT_RUN) iterator.remove();
            target = service;
            if (target == null && (binding || bound)) PENDING.add(stop);
        }
        if (target != null) send(target, stop);
        if (!binding && bound) scheduleIdleRelease();
    }

    /**
     * 当前 :native 进程里运行中的任务，供 {@code PocRuntime.tasks()} 合并进任务列表。
     *
     * <p>每条字段与主进程引擎任务保持一致，{@code engine} 固定 Rhino（隔离进程 Phase0 只跑
     * Rhino 兼容引擎），{@code detail} 用「隔离进程」和普通「运行中」区分。
     */
    public static JSONArray runningTasks() {
        JSONArray result = new JSONArray();
        synchronized (LOCK) {
            for (Task task : TASKS.values()) {
                try {
                    result.put(new JSONObject()
                            .put("token", TOKEN_PREFIX + task.token)
                            .put("kind", "script")
                            .put("name", task.name)
                            .put("path", task.path)
                            .put("engine", "Rhino")
                            .put("detail", "隔离进程")
                            .put("nextTime", 0));
                } catch (Throwable error) {
                    // 单条序列化失败不能让整个任务页读不出来。
                    Log.w(TAG, "序列化隔离进程任务失败", error);
                }
            }
        }
        return result;
    }

    // ---- 客户端 Messenger：接收服务的 STARTED/FINISHED/ERROR -----------------

    private static final Handler REPLY_HANDLER = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            Bundle data = msg.getData();
            String token = data == null ? null : data.getString("token");
            switch (msg.what) {
                case WHAT_STARTED:
                    onStarted(token, data == null ? null : data.getString("name"));
                    break;
                case WHAT_FINISHED:
                    onFinished(token);
                    break;
                case WHAT_ERROR:
                    onError(token, data == null ? null : data.getString("message"));
                    break;
                case WHAT_CONSOLE:
                    onConsole(token,
                            data == null ? null : data.getString(KEY_CONSOLE_LINE),
                            data == null ? Log.INFO : data.getInt(KEY_CONSOLE_LEVEL, Log.INFO),
                            data == null ? null : data.getStringArray(KEY_CONSOLE_TEXTS),
                            data == null ? null : data.getIntArray(KEY_CONSOLE_LEVELS),
                            data == null ? null : data.getLongArray(KEY_CONSOLE_TIMES));
                    break;
                default:
                    Log.w(TAG, "未知的隔离进程回执：what=" + msg.what);
                    break;
            }
        }
    };

    private static final Messenger CLIENT = new Messenger(REPLY_HANDLER);

    private static void onStarted(String token, String name) {
        if (token == null) {
            return;
        }
        synchronized (LOCK) {
            Task task = TASKS.get(token);
            if (task == null) {
                // 列表被清过（例如刚发生一次进程退出重连），补一条，保证任务页能看到。
                TASKS.put(token, new Task(token, name == null ? "" : name, ""));
            } else if (name != null && !name.isEmpty()) {
                // 服务给的 name 是去扩展名的脚本名，和主进程引擎任务列表的展示保持一致。
                task.name = name;
            }
            // 服务能正常回消息，说明这一轮连接是好的：恢复自动重连预算。
            consecutiveDeaths = 0;
        }
    }

    private static void onFinished(String token) {
        if (token == null) {
            return;
        }
        boolean idle;
        synchronized (LOCK) {
            TASKS.remove(token);
            consecutiveDeaths = 0;
            idle = TASKS.isEmpty();
        }
        if (idle) {
            scheduleIdleRelease();
        }
    }

    private static void onError(String token, String message) {
        boolean idle = false;
        if (token != null) {
            synchronized (LOCK) {
                TASKS.remove(token);
                consecutiveDeaths = 0;
                idle = TASKS.isEmpty();
            }
        }
        if (idle) {
            scheduleIdleRelease();
        }
        if (message == null || message.isEmpty()) {
            return;
        }
        if (STOP_MESSAGE.equals(message)) {
            // 用户在任务页点「停止」：不是错误，不弹错误提示（stop() 已经移出列表）。
            Log.i(TAG, "隔离进程任务已停止：" + token);
            return;
        }
        Log.w(TAG, "隔离进程任务失败：" + message);
        showToast("脚本运行失败：" + message);
    }

    private static void scheduleIdleRelease() {
        MAIN.removeCallbacks(IDLE_RELEASE);
        MAIN.postDelayed(IDLE_RELEASE, IDLE_RELEASE_MS);
    }

    /**
     * 空闲释放（task-50 C）：任务全部结束且不再需要隔离进程时，主动 unbind + stopService，
     * 让 :native 进程整体退出；下次运行 {@link #ensureConnected()} 重新拉起全新进程。
     *
     * <p>这样连续「运行/停止」不会在同一个进程里反复复用已加载的 .so / ImGui / EGL 上下文
     * （报告 §6 #3 的进程级清理），也不会长期残留一个空闲隔离进程。释放前先把本地连接状态
     * 清掉，{@code unbindService} 触发的 onServiceDisconnected/binder death 会被
     * {@link #onRemoteLost()} 的早退分支忽略，不会误弹「脚本进程已退出」。
     */
    private static void releaseIdleConnection() {
        Context context;
        IBinder binder;
        boolean needUnbind;
        synchronized (LOCK) {
            if (!TASKS.isEmpty()) {
                return;
            }
            if (service == null && !bound && !binding) {
                return;
            }
            context = appContext;
            binder = remoteBinder;
            remoteBinder = null;
            service = null;
            binding = false;
            needUnbind = bound;
            bound = false;
            connectAttempt++;
        }
        if (binder != null) {
            try {
                binder.unlinkToDeath(DEATH, 0);
            } catch (Throwable ignored) {
                // binder 已经不可用：忽略。
            }
        }
        if (!needUnbind || context == null) {
            return;
        }
        try {
            context.unbindService(CONNECTION);
        } catch (Throwable error) {
            Log.w(TAG, "释放隔离进程绑定时失败", error);
        }
        try {
            // bindService 之前还 startService 过（保证服务进程创建），这里一并停掉；
            // 服务端 onUnbind/空闲宽限后也会自己 stopSelf，双保险。
            context.stopService(new Intent().setClassName(context, SERVICE_CLASS));
        } catch (Throwable error) {
            Log.w(TAG, "停止空闲隔离进程服务失败", error);
        }
        Log.i(TAG, "隔离进程空闲，已释放连接（下次运行重新拉起）");
    }

    /**
     * 处理服务端 103 CONSOLE：把 :native 脚本的实时 console 输出交给 {@link HostConsoleSink}
     * （写主进程 GlobalConsole + 环形缓冲，与 :script 的日志同一落点、同一条统一流）。
     *
     * <p>新版 103 带 {@code consoleTexts}/{@code consoleLevels} 时逐段按原等级转发（避免整批被最高等级
     * 染色）；缺失/畸形回退 {@code line}/{@code level}，兼容旧服务端。
     *
     * <p>在 REPLY_HANDLER（主线程）里执行，不会阻塞脚本进程；token 可能对应已结束的任务
     * （FINISHED/ERROR 之后仍有在途批次），这里不丢，照常转发，name 取不到就用空。
     *
     * <p>统一日志流的 token 加进程命名空间（{@code native:<token>}，与任务页对外 token 一致）：
     * :script / :native 的协议 token 各自从 1 计数，裸 token 会撞号，导致「按任务查看日志」混行。
     */
    private static void onConsole(String token, String line, int level,
                                  String[] texts, int[] levels, long[] times) {
        String name = "";
        if (token != null) {
            synchronized (LOCK) {
                Task task = TASKS.get(token);
                if (task != null) {
                    name = task.name == null ? "" : task.name;
                }
            }
        }
        String logToken = token == null ? "" : TOKEN_PREFIX + token;
        if (isValidConsoleBatch(texts, levels)) {
            // 新服务端：每段带自己的原等级，按 append 顺序逐段走原 HostConsoleSink 落点。
            for (int i = 0; i < texts.length; i++) {
                long time = times != null && times.length == texts.length && times[i] > 0
                        ? times[i] : System.currentTimeMillis();
                appendConsole(logToken, name, texts[i], levels[i], time);
            }
            return;
        }
        // 旧服务端 / 畸形 metadata：回退整批 text + 最高 level，保持修复前行为。
        appendConsole(logToken, name, line, level);
    }

    /** 两数组齐全、等长、数量合理（不超过单批段数上限）且每段文本/等级合法才逐段转发。 */
    private static boolean isValidConsoleBatch(String[] texts, int[] levels) {
        if (texts == null || levels == null || texts.length != levels.length
                || texts.length <= 0 || texts.length > ConsoleBatch.MAX_SEGMENTS) {
            return false;
        }
        for (int i = 0; i < texts.length; i++) {
            if (texts[i] == null
                    || levels[i] < Log.VERBOSE || levels[i] > Log.ASSERT) {
                return false;
            }
        }
        return true;
    }

    private static void appendConsole(String logToken, String name, String line, int level) {
        appendConsole(logToken, name, line, level, System.currentTimeMillis());
    }

    private static void appendConsole(String logToken, String name, String line, int level, long time) {
        if (line == null) {
            return;
        }
        try {
            HostConsoleSink.append(logToken, name, level, line, time);
        } catch (Throwable error) {
            Log.w(TAG, "转发隔离进程 console 失败（token=" + logToken + "）", error);
        }
    }

    // ---- 服务绑定 / 连接生命周期 -----------------------------------------

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Messenger connected = null;
            List<Message> flush = new ArrayList<>();
            synchronized (LOCK) {
                binding = false;
                bound = true;
                remoteBinder = binder;
                if (binder != null) {
                    connected = new Messenger(binder);
                    service = connected;
                    try {
                        binder.linkToDeath(DEATH, 0);
                    } catch (Throwable error) {
                        // 绑定的一瞬间对端就没了：按掉线兜底，onServiceDisconnected 也会来一次。
                        Log.w(TAG, "监听隔离进程退出失败", error);
                    }
                    flush.addAll(PENDING);
                    PENDING.clear();
                }
            }
            if (connected == null) {
                Log.w(TAG, "隔离进程服务连接成功但 binder 为空");
                MAIN.post(PocNativeRunner::onRemoteLost);
                return;
            }
            Log.i(TAG, "已连接隔离脚本进程，补发 " + flush.size() + " 条待处理消息");
            for (Message message : flush) {
                send(connected, message);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // 服务进程崩溃/被系统杀掉。这里只做状态回收，统一在主线程处理。
            Log.w(TAG, "隔离脚本进程连接断开");
            MAIN.post(PocNativeRunner::onRemoteLost);
        }
    };

    private static final IBinder.DeathRecipient DEATH = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            // binder 死亡回调在 binder 线程：投到主线程统一处理（Toast、unbind 都更安全）。
            Log.w(TAG, "隔离脚本进程 binder 已死亡");
            MAIN.post(PocNativeRunner::onRemoteLost);
        }
    };

    private static void attach(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            if (appContext != null) {
                return;
            }
            Context application = context.getApplicationContext();
            appContext = application == null ? context : application;
        }
    }

    /** 懒连接：第一次 run 时 startService + bindService，之后复用同一条连接。 */
    private static void ensureConnected() {
        Context context;
        int attempt;
        synchronized (LOCK) {
            if (appContext == null || service != null || binding || bound) {
                return;
            }
            binding = true;
            attempt = ++connectAttempt;
            context = appContext;
        }
        Intent intent = new Intent();
        intent.setClassName(context, SERVICE_CLASS);
        try {
            context.startService(intent);
        } catch (Throwable error) {
            // 服务被系统限制或 manifest 未注册：bindService 还能兜底；两者都失败再提示。
            Log.w(TAG, "启动隔离脚本进程失败", error);
        }
        boolean isBound;
        try {
            isBound = context.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            Log.w(TAG, "绑定隔离脚本进程失败", error);
            isBound = false;
        }
        boolean dropped = false;
        synchronized (LOCK) {
            if (isBound) {
                bound = true;
            } else {
                binding = false;
                if (connectAttempt == attempt) {
                    // 连不上：把刚插入的「隔离进程」任务清掉，别让任务页永远显示在运行。
                    boolean hadPending = !TASKS.isEmpty();
                    TASKS.clear();
                    PENDING.clear();
                    dropped = hadPending;
                }
            }
        }
        if (!isBound) {
            Log.w(TAG, "无法连接隔离脚本进程（服务未注册或不允许绑定）：" + SERVICE_CLASS);
            showToast("隔离脚本进程不可用");
            if (dropped) {
                // 提示已经给过一次，这里不重复弹窗。
                Log.w(TAG, "隔离脚本进程不可用，已清理未启动的任务");
            }
            return;
        }
        scheduleConnectTimeout(attempt);
    }

    /** bindService 返回 true 但迟迟没有 onServiceConnected：按连接失败回收，等下次 run 重试。 */
    private static void scheduleConnectTimeout(final int attempt) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                Context context = null;
                boolean needUnbind = false;
                boolean timedOut;
                synchronized (LOCK) {
                    timedOut = binding && service == null && connectAttempt == attempt;
                    if (timedOut) {
                        binding = false;
                        boolean hadPending = !TASKS.isEmpty();
                        TASKS.clear();
                        PENDING.clear();
                        needUnbind = bound;
                        bound = false;
                        context = appContext;
                        if (!hadPending) {
                            Log.w(TAG, "连接隔离脚本进程超时（没有待启动任务）");
                        }
                    }
                }
                if (!timedOut) {
                    return;
                }
                if (needUnbind && context != null) {
                    try {
                        context.unbindService(CONNECTION);
                    } catch (Throwable error) {
                        Log.w(TAG, "解除隔离脚本进程绑定失败", error);
                    }
                }
                Log.w(TAG, "连接隔离脚本进程超时");
                showToast("隔离脚本进程连接超时");
            }
        }, CONNECT_TIMEOUT_MS);
    }

    /**
     * 隔离进程退出（binder death / 服务断开）的统一处理，**必须在主线程**：
     * 清空运行列表、提示用户、解除旧绑定，并在有限次数内延迟重连。
     *
     * <p>关键点：只做状态回收，绝不 {@code System.exit}/{@code killProcess}/{@code finish()}，
     * 主进程继续活着，下一次运行重新绑定即可。
     */
    private static void onRemoteLost() {
        IBinder binder;
        Context context;
        boolean needUnbind;
        boolean notifyUser;
        int deaths;
        synchronized (LOCK) {
            if (service == null && !binding && !bound) {
                // binderDied 与 onServiceDisconnected 可能都回调：先到的处理，后到的直接返回。
                return;
            }
            binder = remoteBinder;
            remoteBinder = null;
            context = appContext;
            // 只有「真的有过连接」或「有正在跑的任务」才值得弹「进程已退出」：
            // 连接建立失败（binder 为空）这种还没跑过脚本的情况不打扰用户。
            notifyUser = service != null || !TASKS.isEmpty();
            TASKS.clear();
            PENDING.clear();
            service = null;
            binding = false;
            needUnbind = bound;
            bound = false;
            connectAttempt++;
            consecutiveDeaths++;
            deaths = consecutiveDeaths;
        }
        if (binder != null) {
            try {
                binder.unlinkToDeath(DEATH, 0);
            } catch (Throwable ignored) {
                // binder 已经死了，解绑监听必然失败：忽略。
            }
        }
        if (needUnbind && context != null) {
            try {
                context.unbindService(CONNECTION);
            } catch (Throwable error) {
                Log.w(TAG, "解除隔离脚本进程绑定失败", error);
            }
        }
        Log.w(TAG, "隔离脚本进程已退出（连续第 " + deaths + " 次）");
        if (notifyUser) {
            // 按验收要求：进程崩只提示一次，主界面不受影响。
            showToast("脚本进程已退出，主界面不受影响");
        }
        if (deaths <= MAX_AUTO_RECONNECT) {
            MAIN.postDelayed(PocNativeRunner::ensureConnected, RECONNECT_DELAY_MS * deaths);
        } else {
            Log.w(TAG, "隔离脚本进程连续退出 " + deaths + " 次，暂停自动重连，等待下次运行");
        }
    }

    // ---- 消息收发辅助 ----------------------------------------------------

    /** 发送一条消息；连不上时按绑定状态排队或直接懒连接。 */
    private static void dispatch(Message message) {
        Messenger target;
        boolean needConnect = false;
        synchronized (LOCK) {
            target = service;
            if (target == null) {
                PENDING.add(message);
                // 重连计时中（binding/bound）就等回调补发，别重复 bind。
                needConnect = !binding && !bound;
            }
        }
        if (target != null) {
            send(target, message);
        } else if (needConnect) {
            ensureConnected();
        }
    }

    private static void send(Messenger target, Message message) {
        try {
            target.send(message);
        } catch (RemoteException error) {
            Log.w(TAG, "发送消息到隔离进程失败", error);
            MAIN.post(PocNativeRunner::onRemoteLost);
        }
    }

    /** 主线程 Toast；不持有 Activity，避免泄漏。 */
    private static void showToast(String text) {
        final Context context = appContext;
        if (context == null) {
            Log.w(TAG, text);
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
                } catch (Throwable error) {
                    Log.w(TAG, "Toast 显示失败", error);
                }
            }
        });
    }

    /** 与 {@code JavaScriptFileSource.getName()} 的展示规则一致：去掉文件扩展名。 */
    private static String displayName(File file) {
        String name = file == null ? "" : file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
