package com.jdkshen.aijspro.mcp;

/**
 * task-50：脚本/任务变更事件的**中立出口**（不依赖 Flutter 源集）。
 *
 * <p>主流程（MCP 脚本 runner）只调用 {@link #emit(String, String, String)}；Flutter 桥在
 * {@code apps/app/src/flutterpoc} 侧注册 {@link Listener}，把事件转成同一份
 * {@code FlutterBridgeEventCodec/FlutterBridgeEvents}（事件名/字段与 F1 契约一致）。
 * 没有 Flutter 界面时 listener 为 null → 零开销。
 *
 * <p>约定：拿不到 path 就传 null（由 codec **省略字段**，不填 null 值）。
 */
public final class McpTaskEventSink {

    /** reason 取值：script_started / script_finished / script_failed（与 Flutter 桥一致）。 */
    public interface Listener {
        void onTaskEvent(String reason, String path, String state);
    }

    private static volatile Listener listener;

    private McpTaskEventSink() {
    }

    public static void setListener(Listener value) {
        listener = value;
    }

    public static void clearListener() {
        listener = null;
    }

    /** 事件出口；listener 抛异常不影响脚本执行（也不能影响）。 */
    public static void emit(String reason, String path, String state) {
        Listener current = listener;
        if (current == null) return;
        try {
            current.onTaskEvent(reason, path, state);
        } catch (Throwable ignored) {
        }
    }
}
