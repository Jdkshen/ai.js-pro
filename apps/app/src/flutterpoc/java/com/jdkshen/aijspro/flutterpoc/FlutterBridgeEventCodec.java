package com.jdkshen.aijspro.flutterpoc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flutter 桥「状态推送」事件的**纯载荷契约**（F2 / task-48）。不依赖 Android，可在 JVM 单测里直接跑。
 *
 * 事件一律走既有 autojs.local.bridge 通道，载荷形如 {"event":"<name>", ...}；
 * 事件名与载荷字段需与 W3 的 F1 契约（docs/guides/Flutter本地桥API.md + tools/flutter-bridge-contract.mjs）一致。
 *
 * 五类事件（Pro 的 Flutter 层对应物见括号）：
 * taskChanged（notifyTaskChanged）/ accessibilityState（onAccessibilityServiceStateChanged）/
 * debugConnectionState（onDebugConnectionStateChanged）/ prefsChanged（onSharedPreferenceChanged）/
 * processResumed（onProcessResume）。
 */
final class FlutterBridgeEventCodec {

    static final String EVENT_TASK_CHANGED = "taskChanged";
    static final String EVENT_ACCESSIBILITY_STATE = "accessibilityState";
    static final String EVENT_DEBUG_CONNECTION_STATE = "debugConnectionState";
    static final String EVENT_PREFS_CHANGED = "prefsChanged";
    static final String EVENT_PROCESS_RESUMED = "processResumed";

    /** 本次新增的 5 个事件（F1 契约要收录的集合）。 */
    static final Set<String> EVENT_NAMES;

    /** 通道上全部事件（含既有 pendingAction / nativePackagingReady，便于契约做「未知事件忽略」）。 */
    static final Set<String> ALL_EVENT_NAMES;

    /** 节流窗口：窗口内同 key 只发一次（照 ConsoleLogEvents 的 150ms 合并写法）。 */
    static final long THROTTLE_MS = 150L;

    /** 摘要上限（logcat 单行，避免刷屏）。 */
    static final int SUMMARY_MAX = 160;

    static {
        Set<String> names = new LinkedHashSet<>();
        names.add(EVENT_TASK_CHANGED);
        names.add(EVENT_ACCESSIBILITY_STATE);
        names.add(EVENT_DEBUG_CONNECTION_STATE);
        names.add(EVENT_PREFS_CHANGED);
        names.add(EVENT_PROCESS_RESUMED);
        EVENT_NAMES = Collections.unmodifiableSet(names);
        Set<String> all = new LinkedHashSet<>(names);
        all.add("pendingAction");
        all.add("nativePackagingReady");
        ALL_EVENT_NAMES = Collections.unmodifiableSet(all);
    }

    private FlutterBridgeEventCodec() {}

    // ---------------------------------------------------------------- payloads

    /** 脚本/任务启停、定时任务增删改。reason 是稳定机读码，path 是脚本路径（可空）。 */
    static Map<String, Object> taskChanged(String reason, String path, String state) {
        Map<String, Object> payload = base(EVENT_TASK_CHANGED);
        put(payload, "reason", reason);
        put(payload, "path", path);
        put(payload, "state", state);
        return payload;
    }

    /**
     * 无障碍服务连接/断开。字段全部来自 AccessibilityCheck 的判据（同一个理由码集合），
     * 这里只做投影，不重新判定。
     */
    static Map<String, Object> accessibilityState(boolean connected, String reason, String state,
                                                 boolean automationConnectionReady,
                                                 Boolean settingsContainsComponent, long elapsedMs) {
        Map<String, Object> payload = base(EVENT_ACCESSIBILITY_STATE);
        payload.put("connected", connected);
        put(payload, "reason", reason);
        put(payload, "state", state);
        payload.put("automationConnectionReady", automationConnectionReady);
        if (settingsContainsComponent != null) payload.put("settingsContainsComponent", settingsContainsComponent);
        payload.put("elapsedMs", elapsedMs);
        return payload;
    }

    /** 调试/电脑连接状态变化（state 是稳定机读码，detail 可选）。 */
    static Map<String, Object> debugConnectionState(String state, String detail) {
        Map<String, Object> payload = base(EVENT_DEBUG_CONNECTION_STATE);
        put(payload, "state", state);
        put(payload, "detail", detail);
        return payload;
    }

    /** 偏好变化：**只给 key 名**。值可能含密钥/路径等敏感信息，一律不出通道（有单测钉住）。 */
    static Map<String, Object> prefsChanged(String key) {
        Map<String, Object> payload = base(EVENT_PREFS_CHANGED);
        put(payload, "key", key);
        return payload;
    }

    /**
     * task-50：TimedTask 的 ModelChange action（1=INSERT/2=UPDATE/3=DELETE）→ taskChanged 的 reason。
     * 未知 action 返回空串（调用方跳过，不发明新事件）。
     */
    static String timedTaskReason(int action) {
        switch (action) {
            case 1: return "task_added";
            case 2: return "task_updated";
            case 3: return "task_removed";
            default: return "";
        }
    }

    /** 与 reason 对齐的 state（字段名与既有 taskChanged 一致）。 */
    static String timedTaskState(String reason) {
        if ("task_added".equals(reason)) return "added";
        if ("task_updated".equals(reason)) return "updated";
        if ("task_removed".equals(reason)) return "removed";
        return "changed";
    }

    static Map<String, Object> processResumed(long uptimeMs) {
        Map<String, Object> payload = base(EVENT_PROCESS_RESUMED);
        payload.put("uptimeMs", uptimeMs);
        return payload;
    }

    private static Map<String, Object> base(String event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        return payload;
    }

    private static void put(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isEmpty()) payload.put(key, value);
    }

    /** 事件名（缺 event 视为非法）。 */
    static String eventName(Map<String, Object> payload) {
        Object event = payload == null ? null : payload.get("event");
        return event == null ? "" : String.valueOf(event);
    }

    /** 是否本次新增的五类事件之一。 */
    static boolean isKnownEvent(String event) {
        return EVENT_NAMES.contains(event);
    }

    /**
     * 节流合并键：同一 key 在窗口内只发一次。
     * taskChanged 按 path（无 path 退化到 reason+state）、prefsChanged 按 key、其余按事件名。
     */
    static String mergeKey(Map<String, Object> payload) {
        String event = eventName(payload);
        if (EVENT_TASK_CHANGED.equals(event)) {
            String path = str(payload.get("path"));
            if (!path.isEmpty()) return event + ":" + path;
            return event + ":" + str(payload.get("reason")) + ":" + str(payload.get("state"));
        }
        if (EVENT_PREFS_CHANGED.equals(event)) return event + ":" + str(payload.get("key"));
        return event;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // ------------------------------------------------------------ serialization

    /** 最小 JSON 序列化（确定性顺序 + 转义），避免纯层依赖 org.json / Gson。 */
    static String encode(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String value(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) value;
            return encode(nested);
        }
        if (value instanceof Iterable) {
            List<Object> items = new ArrayList<>();
            for (Object item : (Iterable<?>) value) items.add(item);
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : items) {
                if (!first) sb.append(',');
                first = false;
                sb.append(value(item));
            }
            return sb.append(']').toString();
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append('\\').append('"'); break;
                case '\\': sb.append('\\').append('\\'); break;
                case '\n': sb.append('\\').append('n'); break;
                case '\r': sb.append('\\').append('r'); break;
                case '\t': sb.append('\\').append('t'); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** logcat 单行摘要：<event> key=value ...（截断到 SUMMARY_MAX）。 */
    static String summary(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if ("event".equals(entry.getKey())) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(entry.getKey()).append('=').append(str(entry.getValue()));
        }
        String text = sb.toString();
        return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX) + "...";
    }
}
