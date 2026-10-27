package com.jdkshen.aijspro.flutterpoc.scriptproc;

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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase1 主进程侧的「:script 脚本宿主进程」客户端：所有脚本默认交给独立进程执行。
 *
 * <p>与 Phase0 的 {@code PocNativeRunner}（:native）关系：绑定方式、Messenger 协议、
 * binder 死亡恢复、任务列表全部照搬，只换服务类名（{@code scriptproc.ScriptHostService}）和
 * token 前缀（{@code script:}），并且<b>不再用 NativeProjectDetector 过滤</b>——普通脚本、
 * native 项目、UI 模式脚本都走 :script。Phase0 的 :native 路径保留不删，作为可回退的兜底。
 *
 * <p>为什么隔离：脚本把宿主进程跑崩（native 段错误、OOM 等）只死 :script 进程，主界面
 * （Flutter/原生页）以及 AutoJs 引擎本身不受影响；主进程通过 binder death 感知崩溃、清任务、
 * 提示一次，再重新绑定即可继续跑。
 *
 * <p>协议（与 {@code ScriptHostService} 逐字一致，服务在 AndroidManifest 里声明为
 * {@code android:process=":script"}）：
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
 *       :script 脚本 console 的实时批量回传，收到后交给 {@link HostConsoleSink} 写主进程
 *       GlobalConsole（原生日志页实时可见）+ 环形缓冲；两数组齐全合法时按各段原等级逐段转发，
 *       缺失/畸形时回退 {@code line}/{@code level}，详见 Phase1 日志转发。</li>
 * </ul>
 *
 * <p>对外任务列表 token 格式为 {@code "script:<协议 token>"}，与 Phase0 的 {@code "native:<id>"}
 * 以及主进程引擎的纯数字 token 区分开；{@code PocRuntime.stopTasks} 据此把停止请求路由回来。
 *
 * <p>进程内执行细节（staging 到私有目录、UI 模式主线程 Looper 宿主）由服务端负责，客户端只传
 * 原始脚本绝对路径 + 工作目录；:script 进程同样没有 Accessibility，自动化能力的跨进程代理是
 * Phase1 后续任务（AIDL）的事（{@code "auto";} 脚本 Phase1 第一批由 {@code PocRuntime} 留在主进程）。
 *
 * <p>Phase1 第一批兜底：服务端回 102 且 message 是「暂存失败」类（大目录超过 staging 上限、
 * 私有目录不可用等）时，客户端自动用注册进来的 {@link LegacyRunner} 在主进程旧路径再跑一次，
 * 并提示「已回退主进程运行」——主进程执行不需要 staging，避免这类脚本直接失败。
 *
 * <p>本类不启动任何 Activity，也不持有 Activity 引用。
 */
public final class PocScriptHost {

    private static final String TAG = "PocScriptHost";

    /**
     * :script 进程里脚本宿主服务的类名。
     *
     * <p>用固定字符串而不是 {@code ScriptHostService.class}：主进程侧不需要在编译期依赖服务类，
     * 两边可以各自独立实现/编译，运行时按 manifest 声明绑定。服务名由 Phase1 infrastructure 任务
     * 在 {@code apps/app/src/flutterpoc/AndroidManifest.xml} 注册（{@code android:process=":script"}）。
     */
    public static final String SERVICE_CLASS =
            "com.jdkshen.aijspro.flutterpoc.scriptproc.ScriptHostService";

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
    /** 服务 -> 客户端：脚本 console 实时输出（Phase1 日志转发，批量/节流后发送）。 */
    private static final int WHAT_CONSOLE = 103;

    // ---- console 转发消息的 data 键（与服务端 ScriptHostService 的 KEY_* 一致）--------
    private static final String KEY_CONSOLE_LINE = "line";
    private static final String KEY_CONSOLE_LEVEL = "level";
    /** 新服务端附带的逐段文本（String[]）；旧服务端没有，按 line/level 回退。 */
    private static final String KEY_CONSOLE_TEXTS = "consoleTexts";
    /** 新服务端附带的逐段等级（int[]）；与 consoleTexts 等长同序。 */
    private static final String KEY_CONSOLE_LEVELS = "consoleLevels";
    private static final String KEY_CONSOLE_TIMES = "consoleTimes";

    /** 任务列表里 :script 任务的 token 前缀；去掉后就是协议里的 token。 */
    public static final String TOKEN_PREFIX = "script:";

    /** 服务端对「用户主动停止」回的 ERROR message，不当成脚本错误提示。 */
    private static final String STOP_MESSAGE = "脚本已停止";

    /**
     * 服务端 102「暂存失败」类消息前缀：Phase1 第一批遇到时回退主进程旧路径再跑一次。
     *
     * <p>两种前缀都算：服务端 {@code ScriptHostStaging} 自己抛的是「脚本暂存失败：…」，
     * 底层 {@code NativeProjectStaging} 直接抛的是「暂存项目失败：…」（当前服务端会再包一层，
     * 实际 message 以「脚本暂存」开头）。
     */
    private static final String STAGING_FAILURE_PROJECT = "暂存项目失败";
    private static final String STAGING_FAILURE_SCRIPT = "脚本暂存";

    /**
     * 主进程旧路径执行器：:script 报「暂存失败」时回退到主进程再跑一次。
     *
     * <p>由 {@code PocRuntime} 在静态初始化时注册（实现在主进程引擎即可），PocScriptHost 不反向
     * 依赖 flutterpoc 包里的运行桥，避免两个包循环引用。
     */
    public interface LegacyRunner {
        /**
         * 在主进程按旧路径执行脚本（不经过 staging）。
         *
         * @throws java.io.IOException 执行失败（调用方会退回普通错误提示）
         */
        void runLegacy(File scriptFile, File workDir) throws IOException;
    }

    /** 连接超时：bindService 返回 true 后迟迟没有 onServiceConnected 时的兜底。 */
    private static final long CONNECT_TIMEOUT_MS = 8000L;
    /** 脚本进程连续退出后的自动重连上限，避免服务启动即崩时无限重连。 */
    private static final int MAX_AUTO_RECONNECT = 3;
    /** 自动重连基础延迟（毫秒），按连续退出次数递增。 */
    private static final long RECONNECT_DELAY_MS = 400L;

    private static final Object LOCK = new Object();
    /** 运行中（含已提交给服务、等待 STARTED）的任务，key=协议 token。LinkedHashMap 保证列表顺序稳定。 */
    private static final Map<String, Task> TASKS = new LinkedHashMap<>();
    /** 服务尚未连上时先攒着的 RUN/STOP 消息，连上后按顺序补发。 */
    private static final List<Message> PENDING = new ArrayList<>();

    /**
     * 总开关：默认 true（Phase1 起脚本默认在 :script 里跑）。
     *
     * <p>关掉后 {@link #run} 会直接失败，由 {@code PocRuntime} 回退旧的就地执行逻辑
     * （native 项目 → Phase0 :native，普通脚本 → 主进程引擎）；用于临时验证/回退，不用改代码。
     */
    private static volatile boolean enabled = true;

    /** 主进程旧路径回退执行器（:script 暂存失败时用），由 PocRuntime 注册。 */
    private static volatile LegacyRunner legacyRunner;

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

    private PocScriptHost() {
    }

    /** 一条 :script 运行任务（任务页只需要这几个字段；workDir 供「暂存失败」回退主进程时用）。 */
    private static final class Task {
        final String token;
        final String path;
        /** 提交给服务端的原始工作目录，回退主进程执行时还原执行环境。 */
        final String workDir;
        final long startedAt=System.currentTimeMillis();
        final String engine;
        String name;

        Task(String token, String name, String path, String workDir) {
            this.token = token;
            this.name = name;
            this.path = path;
            this.workDir = workDir;
            this.engine = engineOf(path);
        }
    }

    // ---- 总开关 ----------------------------------------------------------

    /** Phase1 路由开关：true 时所有脚本走 :script（默认），false 时回退旧路径。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 切换路由开关（调试/回退用；只影响后续 run，不影响已在跑的任务）。 */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * 注册主进程旧路径回退执行器（{@code PocRuntime} 静态初始化时调用一次）。
     *
     * <p>只用于「暂存失败」这类 :script 侧跑不起来、但主进程能跑的错误；普通脚本错误不回退。
     */
    public static void setLegacyRunner(LegacyRunner runner) {
        legacyRunner = runner;
    }

    // ---- 对外接口 --------------------------------------------------------

    /**
     * 在 :script 宿主进程里运行一个脚本文件。
     *
     * @param context    任意 Context（内部转成 Application context，避免持有 Activity）
     * @param scriptFile 入口脚本文件（绝对路径）；为 null 时抛 IOException
     * @param workDir    工作目录（项目目录或脚本父目录）；为 null 时退回脚本所在目录
     * @throws IOException 开关已关闭，或 :script 服务同步绑定失败（未注册/不允许绑定），
     *                     调用方（{@code PocRuntime}）据此回退旧执行路径
     */
    public static void run(Context context, File scriptFile, File workDir) throws IOException {
        if (scriptFile == null) {
            throw new IOException("脚本文件不存在");
        }
        if (!enabled) {
            throw new IOException("脚本宿主进程已关闭");
        }
        attach(context);
        final String path = scriptFile.getAbsolutePath();
        final File directory = workDir != null ? workDir : scriptFile.getParentFile();
        final String workDirPath = directory == null ? "" : directory.getAbsolutePath();
        final String token;
        synchronized (LOCK) {
            token = String.valueOf(++nextToken);
            // 先入列表再发消息：桥方法立即返回，任务页随后就能看到「脚本进程」这一条。
            TASKS.put(token, new Task(token, displayName(scriptFile), path, workDirPath));
        }
        Message message = Message.obtain(null, WHAT_RUN);
        Bundle data = new Bundle();
        data.putString("path", path);
        data.putString("workDir", workDirPath);
        data.putString("token", token);
        message.setData(data);
        message.replyTo = CLIENT;
        if (dispatch(message)) {
            return;
        }
        // 连不上服务：把刚插入的任务和排队的 RUN 撤回，让调用方能立刻回退旧路径。
        synchronized (LOCK) {
            TASKS.remove(token);
            PENDING.remove(message);
        }
        throw new IOException("脚本宿主进程不可用（ScriptHostService 未注册或不允许绑定）");
    }

    /**
     * 停止 :script 进程里指定 token 的脚本。
     *
     * @param id 协议 token（任务列表 token 去掉 {@link #TOKEN_PREFIX} 前缀后的部分）
     */
    public static void stop(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        // 先乐观移出列表：任务页/「停止全部」不必等服务的 FINISHED 回执。
        synchronized (LOCK) {
            TASKS.remove(id);
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
        // 完全没有连接：脚本进程本就不存在，没有可停的脚本，直接忽略。
    }

    /** 停止 :script 进程里所有脚本（「停止全部」/悬浮控制条 ⏹ 用）。 */
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
    }

    /**
     * 某个脚本路径是否正在 :script 里运行（悬浮控制条 ▶ 防重入、● 运行中状态显示用）。
     *
     * @param path 脚本路径（绝对/相对均可，内部按 canonical 路径比较）
     */
    public static boolean hasRunning(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        synchronized (LOCK) {
            for (Task task : TASKS.values()) {
                if (sameFile(task.path, path)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 停止某个脚本路径在 :script 里的任务（悬浮控制条 ■ 停止当前用）。
     *
     * @return 是否真的找到了任务并提交了 STOP
     */
    public static boolean stopByPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        List<String> ids = new ArrayList<>();
        synchronized (LOCK) {
            for (Task task : TASKS.values()) {
                if (sameFile(task.path, path)) {
                    ids.add(task.token);
                }
            }
        }
        for (String id : ids) {
            stop(id);
        }
        return !ids.isEmpty();
    }

    /**
     * 当前 :script 进程里运行中的任务，供 {@code PocRuntime.tasks()} 合并进任务列表。
     *
     * <p>字段与主进程引擎任务保持一致：{@code kind=script}、{@code detail="脚本进程"}；
     * {@code engine} 按脚本首行引擎指令推断（Rhino/QuickJS/Node），与引擎执行列表的展示对齐。
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
                            .put("engine", task.engine)
                            .put("startedAt", task.startedAt)
                            .put("detail", "脚本进程")
                            .put("nextTime", 0));
                } catch (Throwable error) {
                    // 单条序列化失败不能让整个任务页读不出来。
                    Log.w(TAG, "序列化脚本进程任务失败", error);
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
                    // 将来可能继续加回执：不认识的一律忽略，别打断脚本。
                    Log.w(TAG, "未知的脚本进程回执：what=" + msg.what);
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
                TASKS.put(token, new Task(token, name == null ? "" : name, "", ""));
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
        synchronized (LOCK) {
            TASKS.remove(token);
            consecutiveDeaths = 0;
        }
    }

    private static void onError(String token, String message) {
        Task failed = null;
        if (token != null) {
            synchronized (LOCK) {
                failed = TASKS.remove(token);
                consecutiveDeaths = 0;
            }
        }
        if (message == null || message.isEmpty()) {
            return;
        }
        if (STOP_MESSAGE.equals(message)) {
            // 用户在任务页点「停止」：不是错误，不弹错误提示（stop() 已经移出列表）。
            Log.i(TAG, "脚本进程任务已停止：" + token);
            return;
        }
        if (failed != null && isStagingFailure(message) && retryLegacy(failed)) {
            // 暂存失败不是脚本本身的错：主进程旧路径已经接手执行，只提示「回退」。
            Log.w(TAG, "脚本暂存失败，已回退主进程执行：" + message);
            showToast("已回退主进程运行");
            return;
        }
        // task-191 P1：引擎侧已有「错误: <message>」Toast（Pro 同款），host 不再重复弹。
        Log.w(TAG, "脚本进程任务失败：" + message);
    }

    /**
     * 处理服务端 103 CONSOLE：把 :script 脚本的实时 console 输出交给 {@link HostConsoleSink}
     * （写主进程 GlobalConsole + 环形缓冲，供原生日志页/后续 Flutter 实时日志页消费）。
     *
     * <p>在 REPLY_HANDLER（主线程）里执行，不会阻塞脚本进程；token 可能对应已结束的任务
     * （FINISHED/ERROR 之后仍有在途批次），这里不丢，照常转发，name 取不到就用空。
     *
     * <p>新版 103 带 {@code consoleTexts}/{@code consoleLevels} 时逐段按原等级转发（避免整批被最高等级
     * 染色）；缺失/畸形回退 {@code line}/{@code level}，兼容旧服务端。
     *
     * <p>统一日志流的 token 加进程命名空间（{@code script:<token>}，与任务页对外 token 一致）：
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
            Log.w(TAG, "转发脚本 console 失败（token=" + logToken + "）", error);
        }
    }

    /** 102 的 message 是否是「暂存失败」类（可回退主进程执行）错误。 */
    private static boolean isStagingFailure(String message) {
        return message.startsWith(STAGING_FAILURE_PROJECT)
                || message.startsWith(STAGING_FAILURE_SCRIPT);
    }

    /**
     * 暂存类错误回退主进程旧路径再跑一次（Phase1 第一批兜底）。
     *
     * <p>:script 每个 RUN 都要先把执行环境 staging 到应用私有目录；用户把脚本平铺在超大目录
     * （超过单文件 64MB / 整树 256MB 上限）时服务端会回 102。主进程执行不需要 staging，能跑就跑，
     * 别让用户直接失败；回退成功后由 {@link #onError} 提示「已回退主进程运行」。
     */
    private static boolean retryLegacy(Task task) {
        LegacyRunner runner = legacyRunner;
        if (runner == null || task == null || task.path.isEmpty()) {
            Log.w(TAG, "暂存失败，但没有可用的主进程回退执行器");
            return false;
        }
        try {
            File scriptFile = new File(task.path);
            File workDir = task.workDir == null || task.workDir.isEmpty() ? null : new File(task.workDir);
            runner.runLegacy(scriptFile, workDir);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "回退主进程执行失败：" + task.path, error);
            return false;
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
                        Log.w(TAG, "监听脚本进程退出失败", error);
                    }
                    flush.addAll(PENDING);
                    PENDING.clear();
                }
            }
            if (connected == null) {
                Log.w(TAG, "脚本进程服务连接成功但 binder 为空");
                MAIN.post(PocScriptHost::onRemoteLost);
                return;
            }
            Log.i(TAG, "已连接脚本宿主进程，补发 " + flush.size() + " 条待处理消息");
            for (Message message : flush) {
                send(connected, message);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // 服务进程崩溃/被系统杀掉。这里只做状态回收，统一在主线程处理。
            Log.w(TAG, "脚本宿主进程连接断开");
            MAIN.post(PocScriptHost::onRemoteLost);
        }
    };

    private static final IBinder.DeathRecipient DEATH = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            // binder 死亡回调在 binder 线程：投到主线程统一处理（Toast、unbind 都更安全）。
            Log.w(TAG, "脚本宿主进程 binder 已死亡");
            MAIN.post(PocScriptHost::onRemoteLost);
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

    /**
     * 懒连接：第一次 run 时 startService + bindService，之后复用同一条连接。
     *
     * @return false 表示同步就能确定连不上（appContext 缺失 / bindService 返回 false），
     *         调用方应回退；true 表示已连接或正在连接（消息已排队，连上后补发）
     */
    private static boolean ensureConnected() {
        Context context;
        int attempt;
        synchronized (LOCK) {
            if (service != null || binding || bound) {
                // 已连接/正在连接：消息已经在 PENDING 里，等回调补发。
                return true;
            }
            if (appContext == null) {
                return false;
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
            // 服务被系统限制或 manifest 未注册：bindService 还能兜底；两者都失败才回退。
            Log.w(TAG, "启动脚本宿主进程失败", error);
        }
        boolean isBound;
        try {
            isBound = context.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
        } catch (Throwable error) {
            Log.w(TAG, "绑定脚本宿主进程失败", error);
            isBound = false;
        }
        synchronized (LOCK) {
            if (isBound) {
                bound = true;
            } else {
                binding = false;
                if (connectAttempt == attempt) {
                    // 连不上：把排队/已登记的任务清掉，别让任务页永远显示在运行。
                    TASKS.clear();
                    PENDING.clear();
                }
            }
        }
        if (!isBound) {
            Log.w(TAG, "无法连接脚本宿主进程（服务未注册或不允许绑定）：" + SERVICE_CLASS);
            return false;
        }
        scheduleConnectTimeout(attempt);
        return true;
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
                        TASKS.clear();
                        PENDING.clear();
                        needUnbind = bound;
                        bound = false;
                        context = appContext;
                    }
                }
                if (!timedOut) {
                    return;
                }
                if (needUnbind && context != null) {
                    try {
                        context.unbindService(CONNECTION);
                    } catch (Throwable error) {
                        Log.w(TAG, "解除脚本宿主进程绑定失败", error);
                    }
                }
                Log.w(TAG, "连接脚本宿主进程超时");
                showToast("脚本进程连接超时");
            }
        }, CONNECT_TIMEOUT_MS);
    }

    /**
     * 脚本进程退出（binder death / 服务断开）的统一处理，**必须在主线程**：
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
                Log.w(TAG, "解除脚本宿主进程绑定失败", error);
            }
        }
        Log.w(TAG, "脚本宿主进程已退出（连续第 " + deaths + " 次）");
        if (notifyUser) {
            // 进程崩只提示一次，主界面不受影响（Flutter/原生页继续活着）。
            showToast("脚本进程已退出，主界面不受影响");
        }
        if (deaths <= MAX_AUTO_RECONNECT) {
            MAIN.postDelayed(PocScriptHost::ensureConnected, RECONNECT_DELAY_MS * deaths);
        } else {
            Log.w(TAG, "脚本宿主进程连续退出 " + deaths + " 次，暂停自动重连，等待下次运行");
        }
    }

    // ---- 消息收发辅助 ----------------------------------------------------

    /**
     * 发送一条 RUN/STOP 消息；连不上时按绑定状态排队或直接懒连接。
     *
     * @return false 表示同步确定连不上服务（需要回退旧执行路径）
     */
    private static boolean dispatch(Message message) {
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
            return true;
        }
        return !needConnect || ensureConnected();
    }

    private static void send(Messenger target, Message message) {
        try {
            target.send(message);
        } catch (RemoteException error) {
            Log.w(TAG, "发送消息到脚本进程失败", error);
            MAIN.post(PocScriptHost::onRemoteLost);
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

    /** 两个路径是否指向同一个脚本文件（canonical 优先，失败退回绝对路径比较）。 */
    private static boolean sameFile(String first, String second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return false;
        }
        try {
            return new File(first).getCanonicalPath().equals(new File(second).getCanonicalPath());
        } catch (IOException ignored) {
            return new File(first).getAbsolutePath().equals(new File(second).getAbsolutePath());
        }
    }

    /**
     * 按脚本首行引擎标记推断任务页要显示的引擎名，与 {@code PocRuntime.engineOf} 的规则一致：
     * 读文件头 4KB，命中 quickjs 指令 → QuickJS，命中 node 指令 → Node，其余按 Rhino。
     *
     * <p>只影响任务页头像/搜索，不影响真实执行（引擎选择在服务进程里由引擎自己决定）。
     */
    private static String engineOf(String path) {
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".js")) {
            return "";
        }
        try (InputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[4096];
            int read = in.read(buffer);
            String head = read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            if (head.contains("quickjs")) {
                return "QuickJS";
            }
            if (head.contains("nodejs") || head.contains("// @engine node")) {
                return "Node";
            }
        } catch (Throwable ignored) {
            // 读不出（文件不存在/权限）就按 Rhino 显示，不影响任务列表。
        }
        return "Rhino";
    }
}
