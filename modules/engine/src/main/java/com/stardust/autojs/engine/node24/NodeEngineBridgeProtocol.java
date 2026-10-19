package com.stardust.autojs.engine.node24;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 主进程 ScriptEngineService 与 :node24 宿主桥之间的 JSON-over-Binder 协议。
 *
 * <p>刻意不使用 AIDL：服务只在同一 UID、同一 App 的两个私有进程之间通信，
 * 一次同步 transact 携带一个 JSON 字符串（请求/响应同构），便于在单元测试里直接验证。
 * 这里只放纯 Java 的协议常量与 JSON 转换，不依赖 Android，便于 JVM 测试。
 */
public final class NodeEngineBridgeProtocol {

    /** Binder transact code：入参一个 JSON String，返回一个 JSON String。 */
    public static final int TRANSACT_JSON = 0x4e45; // 'NE'

    /** 服务 action（app-private，不导出）。 */
    public static final String SERVICE_ACTION =
            "com.stardust.autojs.engine.node24.NODE_ENGINE_BRIDGE";

    /** HostBridge 轮询队列里跨引擎事件的 type。 */
    public static final String EVENT_ENGINE = "__ajs_engine_event";

    /** 能力域 action。 */
    public static final String OP_STATUS = "status";
    public static final String OP_MY = "my";
    public static final String OP_LIST = "list";
    public static final String OP_EXEC = "exec";
    public static final String OP_EXECUTION = "execution";
    public static final String OP_EMIT = "emit";
    public static final String OP_STOP = "stop";
    public static final String OP_STOP_ALL = "stopAll";

    private NodeEngineBridgeProtocol() {
    }

    /** 把 Java/Rhino 值转成可放进 JSON 的结构；未知对象退化为字符串，绝不伪造回调。 */
    public static Object jsonValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return JSONObject.NULL;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value;
        }
        if (value instanceof Map) {
            JSONObject object = new JSONObject();
            for (Object entryObject : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
                String key = String.valueOf(entry.getKey());
                try {
                    object.put(key, jsonValue(entry.getValue()));
                } catch (Throwable ignored) {
                    // 单个不可序列化的键不能拖垮整次 emit；跳过它并保留其它字段。
                }
            }
            return object;
        }
        if (value instanceof Iterable) {
            JSONArray array = new JSONArray();
            for (Object item : (Iterable<?>) value) {
                array.put(jsonValue(item));
            }
            return array;
        }
        if (value.getClass().isArray()) {
            JSONArray array = new JSONArray();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                array.put(jsonValue(Array.get(value, i)));
            }
            return array;
        }
        return String.valueOf(value);
    }

    public static JSONArray jsonArgs(Object... args) {
        JSONArray array = new JSONArray();
        if (args == null) {
            return array;
        }
        for (Object arg : args) {
            array.put(jsonValue(arg));
        }
        return array;
    }

    /** 主进程 → :node24 的引擎事件信封。 */
    public static JSONObject engineEvent(int engineId, String event, JSONArray args) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("engineId", engineId);
            payload.put("event", event == null ? "" : event);
            payload.put("args", args == null ? new JSONArray() : args);
        } catch (Throwable ignored) {
            // JSONObject.put 不会因为上述基本类型失败；保留空对象让调用方明确失败。
        }
        return payload;
    }

    /** 主进程执行回调 → Node execution 路由器的事件信封。 */
    public static JSONObject executionEvent(String token, String event, Integer engineId,
                                            JSONArray args) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("token", token == null ? "" : token);
            payload.put("event", event == null ? "" : event);
            if (engineId != null) {
                payload.put("engineId", engineId.intValue());
            }
            payload.put("args", args == null ? new JSONArray() : args);
        } catch (Throwable ignored) {
        }
        return payload;
    }

    /**
     * 从各候选进程的 {@link #OP_STATUS} 结果中选出本次 Node 任务真正的父引擎所在进程。
     *
     * <p>输入数组与客户端候选进程一一对应；元素为 {@code null}（绑定/查询失败）或
     * {@code {hasNode:boolean, startedAt:long, process:string, pid:int}}。只选
     * {@code hasNode=true} 的候选；同时有多个（运行切换的短暂交叠）时选 startedAt 最新的，
     * 完全相同则取候选顺序靠前者，保证结果确定。没有活动 Node 引擎返回 -1，由调用方明确报错，
     * 绝不退化成「默认主进程」。
     */
    public static int selectOwner(JSONArray candidateStatuses) {
        if (candidateStatuses == null) {
            return -1;
        }
        int selected = -1;
        long selectedStartedAt = Long.MIN_VALUE;
        for (int i = 0; i < candidateStatuses.length(); i++) {
            JSONObject status = candidateStatuses.optJSONObject(i);
            if (status == null || !status.optBoolean("hasNode", false)) {
                continue;
            }
            long startedAt = status.optLong("startedAt", 0L);
            if (selected < 0 || startedAt > selectedStartedAt) {
                selected = i;
                selectedStartedAt = startedAt;
            }
        }
        return selected;
    }

    /** 把 JSON 数组还原成 Java Map/List，Rhino 侧看到的是普通对象/数组，而非 JSONObject 代理。 */
    public static Object[] javaArgs(JSONArray array) {
        if (array == null) {
            return new Object[0];
        }
        Object[] args = new Object[array.length()];
        for (int i = 0; i < array.length(); i++) {
            args[i] = javaValue(array.opt(i));
        }
        return args;
    }

    private static Object javaValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, javaValue(object.opt(key)));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new java.util.ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                list.add(javaValue(array.opt(i)));
            }
            return list;
        }
        return value;
    }
}
