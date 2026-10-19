package com.stardust.autojs.engine;

import android.content.Context;
import android.util.Log;

import com.stardust.autojs.runtime.api.Console;
import com.stardust.autojs.runtime.exception.ScriptException;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.JavaScriptSource;

import org.autojs.source.engine.android.Node24Client;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.BitSet;
import java.util.UUID;

/**
 * 第三个引擎：脚本体由 `:node24` 独立进程里的 Node 24.22.0 执行，本进程只保留 Binder 客户端，
 * 并把 `:node24` 捕获的 stdout/stderr 回显到运行控制台。
 *
 * <p>当前范围（P2，task-58/59）：:node24 执行前注入 assets {@code node24/prelude.js}（+ 可选
 * {@code node24/ui.js}），提供 {@code require('rhino').install()}、{@code $autojs.java}、
 * {@code require('app')}、轮询式宿主事件（{@code __autojsPoll}）；Java 反射经
 * {@code Node24HostBridge}（:node24 进程内同步 Host）。UI 模块由 B(task-59) 的
 * {@code node24/ui.js} + {@code NodeUiHost} 提供。
 */
public class Node24JavaScriptEngine extends JavaScriptEngine {

    private static final String TAG = "Node24Engine";
    /** Allow the 55-second system capture consent request to finish within the service budget. */
    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;
    /** task-63：Node UI/keepRunning 脚本的宿主保活上限（:node24 服务侧 holdAlive 用）。 */
    private static final long UI_HOLD_MILLIS = 10 * 60 * 1000L;
    private final Context mContext;
    private volatile Node24Client mClient;
    private volatile org.autojs.source.engine.android.Node24Pool.Slot mPoolSlot;
    private volatile String mRunningId;
    private volatile boolean mStopRequested;
    /** 主进程侧当前正在跑的 Node24 引擎（同时只有一个 Node 任务），供宿主投递 UI 事件。 */
    private static volatile Node24JavaScriptEngine sRunning;
    /** 当前 Node 任务开始时刻（elapsedRealtime）；:node24 的桥用它区分多个候选进程的归属。 */
    private static volatile long sRunningStartedAt;
    /**
     * Rhino→Node 的首个 emit 可能早于 :node24 的 evaluate 登记 active id（样例只 sleep 1000ms，
     * 冷启动时不够稳）。这里短暂保留事件并后台重试，避免把真实消息静默丢掉。
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<JSONObject> mPendingEmits =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean mEmitDrainRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final int MAX_PENDING_EMITS = 64;

    /** 当前 Node24 引擎（没有在跑时返回 null）。 */
    public static Node24JavaScriptEngine getRunningEngine() {
        return sRunning;
    }

    /** 当前 Node 任务开始时刻（elapsedRealtime，毫秒）；没有在跑时返回 0。 */
    public static long getRunningStartedAt() {
        return sRunning == null ? 0L : sRunningStartedAt;
    }

    /**
     * task-58/59：主进程侧把事件投递到当前 Node24 任务的轮询队列。
     * B 的 NodeUiHost / Activity 生命周期回调可用它把 UI 事件送到 Node 的 {@code __autojsOn} 监听。
     */
    public static boolean postEventToRunning(String type, JSONObject payload) {
        Node24JavaScriptEngine engine = sRunning;
        return engine != null && engine.postEvent(type, payload);
    }

    /** 事件名 + payload 会被 JSON 序列化投递；返回 false 表示当前没有在跑的 Node 任务。 */
    public boolean postEvent(String type, JSONObject payload) {
        Node24Client client = mClient;
        String id = mRunningId;
        if (client == null || id == null || type == null || type.isEmpty()) {
            return false;
        }
        return client.postEvent(id, type, payload == null ? null : payload.toString());
    }

    /**
     * Rhino→Node 回传：Rhino 侧 {@code execution.engine.emit('command', ...)} 最终走到这里。
     * 不能继承 {@link JavaScriptEngine#emit}（Node24 没有 runtime.init，timers/events 为 null），
     * 必须把事件投进 :node24 的轮询队列，由 prelude 派发给 {@code engines.myEngine().on(...)}。
     * 没有运行中的 Node 任务时才回退到父类（保持旧/测试语义）。
     */
    @Override
    public void emit(String eventName, Object... args) {
        if (eventName == null || mClient == null || isDestroyed()) {
            return;
        }
        JSONObject payload = com.stardust.autojs.engine.node24.NodeEngineBridgeProtocol.engineEvent(
                getId(), eventName,
                com.stardust.autojs.engine.node24.NodeEngineBridgeProtocol.jsonArgs(args));
        if (postEvent(com.stardust.autojs.engine.node24.NodeEngineBridgeProtocol.EVENT_ENGINE,
                payload)) {
            return;
        }
        if (mPendingEmits.size() < MAX_PENDING_EMITS) {
            mPendingEmits.offer(payload);
            startEmitDrain();
        }
    }

    /** 后台把首轮 emit 重试到 evaluate 的 active id 登记完成；最多 5 秒，不阻塞 Rhino 线程。 */
    private void startEmitDrain() {
        if (!mEmitDrainRunning.compareAndSet(false, true)) {
            return;
        }
        Thread drain = new Thread(() -> {
            try {
                for (int i = 0; i < 100 && !mPendingEmits.isEmpty(); i++) {
                    JSONObject payload = mPendingEmits.peek();
                    if (payload != null && postEvent(
                            com.stardust.autojs.engine.node24.NodeEngineBridgeProtocol.EVENT_ENGINE,
                            payload)) {
                        mPendingEmits.poll();
                        i = 0;
                        continue;
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                mEmitDrainRunning.set(false);
                if (!mPendingEmits.isEmpty()) {
                    Log.w(TAG, "drop " + mPendingEmits.size() + " stale engine events");
                    mPendingEmits.clear();
                }
            }
        }, "node24-emit-drain");
        drain.setDaemon(true);
        drain.start();
    }

    public Node24JavaScriptEngine(Context context) {
        mContext = context.getApplicationContext();
    }

    @Override
    public synchronized void put(String name, Object value) {
        // Node 进程暂未接宿主桥，全局变量无法跨进程传递。
        Log.d(TAG, "Ignoring global for Node24: " + name);
    }

    @Override
    public void init() {
        // task-175：默认使用 :node24_1..3 进程池（每进程单 Node runtime），
        // 只有显式 legacy 开关/池不可用时才回退旧单进程 :node24。
        if (!org.autojs.source.engine.android.Node24Pool.legacyEnabled(mContext)) {
            try {
                mPoolSlot = org.autojs.source.engine.android.Node24Pool.acquire(mContext);
                mClient = mPoolSlot.client();
                Log.i(TAG, "Node24 pool slot=" + mPoolSlot.index());
                return;
            } catch (Throwable error) {
                Log.w(TAG, "Node24 pool unavailable, fallback deprecated :node24", error);
            }
        } else {
            Log.w(TAG, "Node24 legacy flag enabled: using deprecated :node24 process");
        }
        // :node24 旧通道（deprecated）：池不可用/显式回退时仍保证脚本可跑。
        mClient = new Node24Client(mContext);
    }

    @Override
    protected Object doExecution(JavaScriptSource scriptSource) {
        Node24Client client = mClient;
        if (client == null) {
            throw new IllegalStateException("Node24 engine is not initialized");
        }
        String id = UUID.randomUUID().toString();
        mRunningId = id;
        sRunningStartedAt = android.os.SystemClock.elapsedRealtime();
        sRunning = this;
        try {
            if (mStopRequested) {
                throw new ScriptInterruptedException();
            }
            String script = scriptSource.getScript();
            String filename = absoluteFilename(scriptSource);
            // task-132 P0④：解析 type=node / features.nodejs / 位 flags（ui/auto/nodejs/ui-thread/rhino），
            // 同时供脚本内 globalThis.__ajsFeatures 与宿主保活策略使用。
            JSONObject features = parseFeatures(script, filename);
            boolean uiScript = features.optBoolean("ui", false) || shouldHoldAlive(script);
            long holdMillis = uiScript ? UI_HOLD_MILLIS : 0L;
            if (script != null) {
                // task-132-fix：直接注入 JS 对象字面量（之前 JSONObject.quote 会变成字符串）。
                script = "globalThis.__ajsFeatures = " + features.toString() + ";\n" + script;
            }
            Log.i(TAG, "node24 request id=" + id + " uiScript=" + uiScript + " holdMillis=" + holdMillis
                    + " scriptLen=" + (script == null ? 0 : script.length()));
            JSONObject request = new JSONObject()
                    .put("id", id)
                    .put("source", script)
                    .put("filename", absoluteFilename(scriptSource))
                    .put("timeoutMillis", DEFAULT_TIMEOUT_MILLIS)
                    .put("holdMillis", holdMillis)
                    .put("uiScript", uiScript)
                    .put("features", features);
            // task-168：evaluate 等待期间就把 stdout/stderr 行写进运行控制台；返回后只补缺失行。
            LiveLogSink liveLogs = new LiveLogSink();
            JSONObject result;
            try {
                result = client.evaluate(request, liveLogs);
            } catch (Throwable evaluateError) {
                // 进程崩溃/断连：作废当前槽，下一次运行重新拉起宿主进程。
                if (mPoolSlot != null) {
                    org.autojs.source.engine.android.Node24Pool.invalidate(mPoolSlot);
                    mPoolSlot = null;
                    mClient = null;
                }
                throw evaluateError;
            }
            mirrorLogs(result.optJSONArray("logs"), liveLogs);
            String status = result.optString("status");
            if ("SUCCESS".equals(status)) {
                return result;
            }
            String message = result.optString("message");
            if ("CANCELLED".equals(status)) {
                Log.d(TAG, "Node24 execution cancelled: " + message);
                throw new ScriptInterruptedException();
            }
            if ("BUSY".equals(status) && !message.isEmpty()) {
                // task-174：排队超时/排队已满时给用户明确中文提示，不暴露裸 BUSY。
                throw new ScriptException(message);
            }
            throw new ScriptException(message.isEmpty() ? status : status + ": " + message);
        } catch (ScriptException error) {
            throw error;
        } catch (Throwable error) {
            throw new ScriptException(error);
        } finally {
            mRunningId = null;
            if (sRunning == this) {
                sRunning = null;
                sRunningStartedAt = 0L;
            }
        }    }

    @Override
    public void forceStop() {
        mStopRequested = true;
        Node24Client client = mClient;
        String id = mRunningId;
        if (client != null && id != null) {
            client.cancel(id);
        }
    }

    @Override
    public synchronized void destroy() {
        Node24Client client = mClient;
        mClient = null;
        // 池客户端跨脚本复用：正常结束时只释放槽，不关闭 Binder；失败时由 invalidate 关闭。
        if (client != null && mPoolSlot == null) {
            try {
                client.close();
            } catch (Throwable error) {
                // 关闭 Node 进程的 Binder 客户端失败不能阻断下面的引擎销毁（主进程稳定性优先）。
                Log.w(TAG, "close Node24 client failed", error);
            }
        }
        if (mPoolSlot != null) {
            org.autojs.source.engine.android.Node24Pool.release(mPoolSlot, true);
            mPoolSlot = null;
        }
        // super.destroy() -> JavaScriptEngine.destroy() -> ScriptRuntime.onExit()：
        // Node24 不调用 runtime.init()，onExit 里 timers/threads/events/loopers 等为 null，
        // 已在 ScriptRuntime.onExit 做判空，这里不再包 try/catch（否则会跳过引擎注册清理）。
        mPendingEmits.clear();
        super.destroy();
    }

    /**
     * task-63：只有 UI/keepRunning 类脚本才让 :node24 服务阻塞保活（最多 10 分钟），
     * 普通 Node 脚本保持原有立即返回语义（避免脚本结束后进程滞留）。
     */
    /** task-132：从脚本头部指令与 project.json 推断 features/type/位 flags。 */
    private static JSONObject parseFeatures(String script, String filename) {
        JSONObject features = new JSONObject();
        String type = "node";
        try {
            String dir = org.autojs.source.engine.android.Node24Prelude.projectDir(filename);
            File config = new File(dir, "project.json");
            if (config.isFile()) {
                JSONObject project = new JSONObject(new String(
                        java.nio.file.Files.readAllBytes(config.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                String projectType = project.optString("type", "");
                if (!projectType.isEmpty()) type = projectType;
                JSONObject projectFeatures = project.optJSONObject("features");
                if (projectFeatures != null) {
                    java.util.Iterator<String> keys = projectFeatures.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        features.put(key, projectFeatures.optBoolean(key, false));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String head = script == null ? "" : script.substring(0, Math.min(script.length(), 2048));
            boolean nodeDirective = head.contains("nodejs");
            boolean uiDirective = head.contains("ui nodejs") || head.contains("ui-thread") || head.contains("\"ui\"");
            features.put("type", type);
            features.put("nodejs", nodeDirective || "node".equals(type) || features.optBoolean("nodejs", false));
            features.put("ui", uiDirective || features.optBoolean("ui", false));
            features.put("uiThread", head.contains("ui-thread") || features.optBoolean("uiThread", false));
            features.put("auto", head.contains("\"auto\"") || features.optBoolean("auto", false));
            features.put("rhino", head.contains("rhino") || features.optBoolean("rhino", false));
        } catch (org.json.JSONException ignored) {
        }
        return features;
    }

    private static boolean shouldHoldAlive(String script) {
        if (script == null || script.isEmpty()) {
            return false;
        }
        return script.contains("keepRunning")
                || script.contains("require('ui')")
                || script.contains("require(\"ui\")")
                || script.contains("ui nodejs")
                || script.contains("nodejs ui");
    }

    /**
     * 当前 evaluate 的实时日志窗口：index 与最终 logs 数组下标一致。
     * 直接在回调里 console.log（不攒到脚本结束），并记住已显示的下标供兜底回填去重。
     */
    private final class LiveLogSink implements Node24Client.LogListener {
        private final BitSet delivered = new BitSet();
        @Override public void onLog(String line, int index) {
            if (index >= 0) {
                synchronized (delivered) {
                    if (delivered.get(index)) return;
                }
            }
            boolean shown = false;
            try {
                Console console = runtimeConsole();
                if (console != null) {
                    org.autojs.source.engine.android.Node24ConsoleLine decoded =
                            org.autojs.source.engine.android.Node24ConsoleLine.decode(line);
                    console.println(decoded.level, decoded.text);
                    shown = true;
                }
            } catch (Throwable error) {
                Log.w(TAG, "live Node console line failed", error);
            }
            if (shown && index >= 0) {
                synchronized (delivered) { delivered.set(index); }
            }
        }
        boolean isDelivered(int index) {
            synchronized (delivered) { return delivered.get(index); }
        }
    }

    private Console runtimeConsole() {
        try {
            return getRuntime() == null ? null : getRuntime().console;
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * 兜底回填：只显示实时窗口没有成功写出的行。没有 listener/一行都没实时显示时
     * delivered 为空，等价于旧的“结束后统一 mirror”，不会丢日志。
     */
    private void mirrorLogs(JSONArray logs, LiveLogSink liveLogs) {
        if (logs == null || logs.length() == 0) {
            return;
        }
        Console console = runtimeConsole();
        if (console == null) {
            Log.w(TAG, "Node console unavailable; drop " + logs.length() + " result logs");
            return;
        }
        for (int i = 0; i < logs.length(); i++) {
            if (liveLogs != null && liveLogs.isDelivered(i)) continue;
            try {
                org.autojs.source.engine.android.Node24ConsoleLine decoded =
                        org.autojs.source.engine.android.Node24ConsoleLine.decode(logs.optString(i));
                console.println(decoded.level, decoded.text);
            } catch (Throwable error) {
                Log.w(TAG, "mirror Node console line failed", error);
            }
        }
    }

    private String absoluteFilename(JavaScriptSource source) {
        if (source instanceof JavaScriptFileSource) {
            File file = ((JavaScriptFileSource) source).getFile();
            if (file != null && file.isAbsolute()) {
                return file.getAbsolutePath();
            }
        }
        String directory = cwd();
        if (directory == null || !directory.startsWith("/")) {
            directory = new File(mContext.getCacheDir(), "node24-engine").getAbsolutePath();
        }
        String name = source.getName();
        if (name == null || name.isEmpty()) {
            name = "script";
        }
        return new File(directory, name.replaceAll("[^A-Za-z0-9_.\\-]", "_") + ".js").getAbsolutePath();
    }
}
