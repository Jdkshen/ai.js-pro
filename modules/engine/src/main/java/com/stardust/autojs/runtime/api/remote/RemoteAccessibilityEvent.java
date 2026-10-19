package com.stardust.autojs.runtime.api.remote;

import org.json.JSONObject;

/**
 * task-163：:script 进程收到的远程无障碍事件（主进程 AccessibilityEvent 的 JSON 快照）。
 *
 * <p>只保留可跨进程的字段；Rhino 脚本看到的是 Java Bean（getKind()/getPackageName()/getText()…），
 * 需要原始 JSON 时调 {@link #toJson()}。它不冒充 android AccessibilityEvent（那需要系统 binder 对象），
 * 对齐项与限制见 docs/reports/2026-10-19_Rhino_script无障碍事件回调.md。
 */
public final class RemoteAccessibilityEvent {

    private final String kind;
    private final int eventType;
    private final String packageName;
    private final String className;
    private final String text;
    private final int windowId;
    private final long eventTime;
    private final int contentChangeTypes;
    private final int windowChangeTypes;
    private final String json;

    private RemoteAccessibilityEvent(JSONObject raw) {
        kind = raw.optString("kind", "accessibility_event");
        eventType = raw.optInt("eventType", 0);
        packageName = raw.optString("packageName", "");
        className = raw.optString("className", "");
        text = raw.optString("text", "");
        windowId = raw.optInt("windowId", -1);
        eventTime = raw.optLong("eventTime", 0L);
        contentChangeTypes = raw.optInt("contentChangeTypes", 0);
        windowChangeTypes = raw.optInt("windowChangeTypes", 0);
        json = raw.toString();
    }

    /** 解析主进程回调 JSON；非法/空串返回 null。 */
    public static RemoteAccessibilityEvent fromJson(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return new RemoteAccessibilityEvent(new JSONObject(value));
        } catch (Throwable error) {
            return null;
        }
    }

    public String getKind() { return kind; }
    public int getEventType() { return eventType; }
    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }
    public String getText() { return text; }
    public int getWindowId() { return windowId; }
    public long getEventTime() { return eventTime; }
    public int getContentChangeTypes() { return contentChangeTypes; }
    public int getWindowChangeTypes() { return windowChangeTypes; }

    /** 原始事件 JSON（脚本可自己 JSON.parse 做二次处理）。 */
    public String toJson() { return json; }

    @Override
    public String toString() {
        return "[RemoteAccessibilityEvent " + kind + " pkg=" + packageName
                + " class=" + className + " text=" + text + "]";
    }
}
