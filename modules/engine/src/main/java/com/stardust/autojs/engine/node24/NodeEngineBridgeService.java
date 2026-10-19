package com.stardust.autojs.engine.node24;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.engine.JavaScriptEngine;
import com.stardust.autojs.engine.Node24JavaScriptEngine;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.SimpleScriptExecutionListener;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.StringScriptSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实引擎进程里的 app-private 引擎桥，供 :node24 的 Node prelude 调用真实
 * {@link ScriptEngineService}。不使用 AIDL，也不导出；协议为一次 Binder transact 里的 JSON。
 *
 * <p>这份实现同时运行在<b>主进程</b>和 {@code :script} 脚本宿主进程：Node 父引擎可能由任一
 * 进程创建（Flutter 主界面的外部 Run 走 {@code :script}，旧入口仍在主进程）。本服务只暴露
 * 自己进程内真实的 {@link ScriptEngineService} / {@link Node24JavaScriptEngine} 注册表；
 * {@link NodeEngineBridgeScriptService} 是 {@code :script} 的同一实现子类。客户端先查询两个
 * 进程的 {@link NodeEngineBridgeProtocol#OP_STATUS}，选中真正有活动 Node 引擎的进程并全程固定，
 * 不允许把主进程的 id 和 {@code :script} 的 id 混用，也不伪造虚拟 id。
 *
 * <p>职责边界：
 * <ul>
 *   <li>Node→Rhino：在 Node 父引擎所在进程真实创建 Rhino 子执行（并行，不占 :node24 的单任务槽）；</li>
 *   <li>Node→已有引擎：emit/forceStop 落到<b>同一进程</b>的真实 {@link ScriptEngine}；</li>
 *   <li>Rhino→Node：Rhino 侧 {@code execution.engine.emit()} 由
 *       {@link Node24JavaScriptEngine#emit} 直接投递到 Node 轮询队列；本服务负责把
 *       子执行 start/success/exception 事件投递给同进程正在运行的 Node 任务。</li>
 * </ul>
 */
public class NodeEngineBridgeService extends Service {

    private static final String TAG = "NodeEngineBridge";
    private static final Map<String, ExecutionRecord> EXECUTIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger EXECUTION_SEQ = new AtomicInteger(1);

    private final Binder mBinder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code != NodeEngineBridgeProtocol.TRANSACT_JSON) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(NodeEngineBridgeProtocol.SERVICE_ACTION);
            String requestJson = data.readString();
            JSONObject response;
            try {
                response = handle(new JSONObject(requestJson == null ? "{}" : requestJson));
            } catch (Throwable error) {
                Log.w(TAG, "engine bridge request failed", error);
                response = error(error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
            }
            reply.writeNoException();
            reply.writeString(response.toString());
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override public boolean onUnbind(Intent intent) {
        stopOwnedExecutions();
        return false;
    }

    @Override public void onDestroy() {
        stopOwnedExecutions();
        super.onDestroy();
    }

    private void stopOwnedExecutions() {
        // One Node job owns this connection. Its children must not outlive a
        // cancelled parent or leave overlay windows over the next sample.
        for (ExecutionRecord record : EXECUTIONS.values()) {
            ScriptEngine child = record.execution == null ? null : record.execution.getEngine();
            try {
                if (child != null && !child.isDestroyed()) child.forceStop();
            } catch (Throwable error) { Log.w(TAG, "Cannot stop owned child", error); }
        }
        EXECUTIONS.clear();
    }

    private JSONObject handle(JSONObject request) throws Exception {
        String action = request.optString("action", "");
        JSONObject args = request.optJSONObject("args");
        if (args == null) {
            args = new JSONObject();
        }
        ScriptEngineService service = ScriptEngineService.getInstance();
        if (NodeEngineBridgeProtocol.OP_STATUS.equals(action)) {
            // 发现阶段必须能回答「本进程有没有活动 Node 引擎」，即使 ScriptEngineService
            // 尚未初始化也不能抛错，否则客户端无法区分「不是 owner」与「进程坏了」。
            return ok(engineStatus());
        }
        if (service == null) {
            throw new IllegalStateException("ScriptEngineService 尚未初始化");
        }
        switch (action) {
            case NodeEngineBridgeProtocol.OP_MY:
                return ok(myEngine(service));
            case NodeEngineBridgeProtocol.OP_LIST:
                return ok(listEngines(service));
            case NodeEngineBridgeProtocol.OP_EXEC:
                return ok(exec(service, args));
            case NodeEngineBridgeProtocol.OP_EXECUTION:
                return ok(executionState(args.optString("token", "")));
            case NodeEngineBridgeProtocol.OP_EMIT:
                return ok(emit(args));
            case NodeEngineBridgeProtocol.OP_STOP:
                return ok(stop(args));
            case NodeEngineBridgeProtocol.OP_STOP_ALL:
                return ok(service.stopAll());
            default:
                throw new IllegalArgumentException("不支持 engines." + action);
        }
    }

    /**
     * 本进程的 owner 状态：是否有活动 Node 引擎、真实 engine id、启动时刻（elapsedRealtime）。
     * 这是客户端在多个候选进程之间做选择的唯一依据，不包含任何虚拟 id。
     */
    private JSONObject engineStatus() throws Exception {
        Node24JavaScriptEngine engine = Node24JavaScriptEngine.getRunningEngine();
        JSONObject value = new JSONObject();
        value.put("process", processName());
        value.put("pid", android.os.Process.myPid());
        value.put("serviceReady", ScriptEngineService.getInstance() != null);
        value.put("hasNode", engine != null);
        if (engine != null) {
            value.put("engineId", engine.getId());
            value.put("engine", engine.getClass().getSimpleName());
            value.put("startedAt", Node24JavaScriptEngine.getRunningStartedAt());
        }
        return value;
    }

    /** 真实进程名；API 28 以下回退 /proc/self/cmdline（只用于诊断/owner 报告）。 */
    private String processName() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                String name = android.app.Application.getProcessName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            byte[] bytes = new byte[256];
            int count;
            try (java.io.FileInputStream input =
                         new java.io.FileInputStream("/proc/self/cmdline")) {
                count = input.read(bytes);
            }
            int length = 0;
            while (length < count && bytes[length] != 0) {
                length++;
            }
            String name = new String(bytes, 0, length,
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!name.isEmpty()) {
                return name;
            }
        } catch (Throwable ignored) {
        }
        return getPackageName();
    }

    /**
     * 当前 Node 任务的真实主进程引擎 id / execArgv。id 就是 ScriptEngineService 里的
     * Node24JavaScriptEngine id，因此 Rhino 的 engines.all() 能按同一 id 找到父引擎。
     */
    private JSONObject myEngine(ScriptEngineService service) throws Exception {
        Node24JavaScriptEngine engine = Node24JavaScriptEngine.getRunningEngine();
        if (engine == null) {
            throw new IllegalStateException("当前没有正在运行的 Node 引擎");
        }
        return describe(engine);
    }

    private JSONArray listEngines(ScriptEngineService service) throws Exception {
        JSONArray result = new JSONArray();
        List<ScriptEngine> engines;
        synchronized (service.getEngines()) {
            engines = new ArrayList<>(service.getEngines());
        }
        for (ScriptEngine engine : engines) {
            result.put(describe(engine));
        }
        return result;
    }

    private JSONObject describe(ScriptEngine engine) throws Exception {
        JSONObject value = new JSONObject();
        value.put("id", engine.getId());
        value.put("engine", engine.getClass().getSimpleName());
        Object configTag = engine.getTag(ExecutionConfig.getTag());
        JSONObject arguments = new JSONObject();
        if (configTag instanceof ExecutionConfig) {
            for (Map.Entry<String, Object> entry
                    : ((ExecutionConfig) configTag).getArguments().entrySet()) {
                arguments.put(entry.getKey(), NodeEngineBridgeProtocol.jsonValue(entry.getValue()));
            }
        }
        value.put("execArgv", arguments);
        return value;
    }

    private JSONObject exec(ScriptEngineService service, JSONObject args) throws Exception {
        String token = args.optString("token", "");
        if (token.isEmpty()) {
            throw new IllegalArgumentException("execScriptFile 缺少 token");
        }
        String path = args.optString("path", "");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("execScriptFile 路径不能为空");
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("execScriptFile 必须是绝对路径：" + path);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("脚本不存在：" + path);
        }
        ExecutionConfig config = new ExecutionConfig();
        String workingDirectory = args.optString("workingDirectory", "");
        if (workingDirectory.isEmpty()) {
            workingDirectory = file.getParent();
        }
        config.setWorkingDirectory(workingDirectory == null ? "" : workingDirectory);
        config.setPath(new String[]{workingDirectory == null ? "" : workingDirectory});
        JSONObject arguments = args.optJSONObject("arguments");
        if (arguments != null) {
            java.util.Iterator<String> keys = arguments.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                config.setArgument(key, javaValue(arguments.opt(key)));
            }
        }
        JavaScriptFileSource source = new JavaScriptFileSource(file);
        // 先登记 token 再 execute：子执行线程可能在 execute() 返回前就触发 onStart，
        // 否则会丢掉 engineId，Node 侧 execution.engine() 将永远等不到。
        ExecutionRecord record = new ExecutionRecord(null);
        EXECUTIONS.put(token, record);
        ScriptExecution execution;
        try {
            execution = service.execute(source, new SimpleScriptExecutionListener() {
            @Override
            public void onStart(ScriptExecution execution) {
                ScriptEngine engine = execution.getEngine();
                Integer engineId = engine == null ? null : engine.getId();
                if (engine != null) {
                    record.engineName = engine.getClass().getSimpleName();
                }
                record.engineId = engineId;
                postToNode(NodeEngineBridgeProtocol.executionEvent(
                        token, "start", engineId, new JSONArray()));
            }

            @Override
            public void onSuccess(ScriptExecution execution, Object result) {
                record.done = true;
                record.success = true;
                record.error = null;
                postToNode(NodeEngineBridgeProtocol.executionEvent(
                        token, "success", null, new JSONArray()));
            }

            @Override
            public void onException(ScriptExecution execution, Throwable error) {
                record.done = true;
                record.success = false;
                String message = error == null ? "未知异常"
                        : (error.getMessage() == null ? error.toString() : error.getMessage());
                record.error = message;
                JSONArray eventArgs = new JSONArray();
                eventArgs.put(message);
                postToNode(NodeEngineBridgeProtocol.executionEvent(
                        token, "exception", null, eventArgs));
            }
            }, config);
        } catch (Throwable error) {
            EXECUTIONS.remove(token);
            throw error;
        }
        record.execution = execution;
        return new JSONObject().put("token", token).put("executionId", execution.getId());
    }

    private JSONObject executionState(String token) throws Exception {
        ExecutionRecord record = EXECUTIONS.get(token);
        if (record == null) {
            throw new IllegalArgumentException("未知 execution token：" + token);
        }
        JSONObject value = new JSONObject();
        value.put("token", token);
        if (record.execution != null) {
            value.put("executionId", record.execution.getId());
        }
        if (record.engineId != null) {
            value.put("engineId", record.engineId.intValue());
        }
        if (record.engineName != null) {
            value.put("engine", record.engineName);
        }
        value.put("done", record.done);
        // 子执行结束状态如实回报：成功/异常都是真实 listener 回调，不把「已结束」伪装成仍在运行。
        if (record.done) {
            value.put("success", record.success);
        }
        if (record.error != null) {
            value.put("error", record.error);
        }
        return value;
    }

    private boolean emit(JSONObject args) throws Exception {
        int targetId = args.optInt("target", Integer.MIN_VALUE);
        String event = args.optString("event", "");
        if (targetId == Integer.MIN_VALUE || event.isEmpty()) {
            throw new IllegalArgumentException("emit 需要 target 与 event");
        }
        // 兼容两种 payload：数组（新协议）与单个对象（历史调用）。
        JSONArray rawArgs = args.optJSONArray("args");
        if (rawArgs == null) {
            rawArgs = new JSONArray();
            if (args.has("payload")) {
                rawArgs.put(args.opt("payload"));
            }
        }
        ScriptEngine target = findEngine(targetId);
        if (target == null) {
            throw new IllegalArgumentException("目标引擎不存在或已结束：" + targetId);
        }
        if (target instanceof Node24JavaScriptEngine) {
            JSONObject payload = NodeEngineBridgeProtocol.engineEvent(targetId, event, rawArgs);
            if (!((Node24JavaScriptEngine) target).postEvent(
                    NodeEngineBridgeProtocol.EVENT_ENGINE, payload)) {
                throw new IllegalStateException("目标 Node 引擎不在运行：" + targetId);
            }
            return true;
        }
        if (target instanceof JavaScriptEngine) {
            Object[] values = target instanceof com.stardust.autojs.engine.RhinoJavaScriptEngine
                    ? NodeRhinoEventArguments.decode(
                            ((com.stardust.autojs.engine.RhinoJavaScriptEngine) target).getScriptable(), rawArgs)
                    : NodeEngineBridgeProtocol.javaArgs(rawArgs);
            ((JavaScriptEngine) target).emit(event, values);
            return true;
        }
        throw new UnsupportedOperationException("引擎不支持 emit：" + target.getClass().getName());
    }

    private boolean stop(JSONObject args) {
        int targetId = args.optInt("target", Integer.MIN_VALUE);
        if (targetId == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("stop 需要 target");
        }
        ScriptEngine target = findEngine(targetId);
        if (target == null) {
            return false;
        }
        target.forceStop();
        return true;
    }

    private static ScriptEngine findEngine(int id) {
        ScriptEngineService service = ScriptEngineService.getInstance();
        if (service == null) {
            return null;
        }
        synchronized (service.getEngines()) {
            for (ScriptEngine engine : service.getEngines()) {
                if (engine.getId() == id) {
                    return engine;
                }
            }
        }
        return null;
    }

    private static void postToNode(JSONObject payload) {
        try {
            Node24JavaScriptEngine.postEventToRunning(
                    NodeEngineBridgeProtocol.EVENT_ENGINE, payload);
        } catch (Throwable error) {
            Log.w(TAG, "post execution event to Node failed", error);
        }
    }

    private static Object javaValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, javaValue(object.opt(key)));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                list.add(javaValue(array.opt(i)));
            }
            return list;
        }
        return value;
    }

    private static JSONObject ok(Object value) {
        JSONObject response = new JSONObject();
        try {
            response.put("ok", true);
            if (value != null) {
                response.put("value", value);
            }
        } catch (Throwable ignored) {
        }
        return response;
    }

    private static JSONObject error(String message) {
        JSONObject response = new JSONObject();
        try {
            response.put("ok", false).put("error", message == null ? "引擎桥失败" : message);
        } catch (Throwable ignored) {
        }
        return response;
    }

    private static final class ExecutionRecord {
        volatile ScriptExecution execution;
        volatile Integer engineId;
        volatile String engineName;
        volatile boolean done;
        volatile boolean success;
        volatile String error;

        ExecutionRecord(ScriptExecution execution) {
            this.execution = execution;
        }
    }
}
