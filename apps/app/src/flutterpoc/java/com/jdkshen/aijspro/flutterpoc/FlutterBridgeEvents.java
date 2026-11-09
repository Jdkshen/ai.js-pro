package com.jdkshen.aijspro.flutterpoc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flutter 桥事件分发器（F2 / task-48）：**只在 Flutter 存活时发**、**150ms 按 key 合并**、每次发事件打一条 logcat。
 *
 * <p>与 Android 的耦合全部收在 {@link Sender} 这个 seam 里（存活判定 / 发送 / 日志 / 时钟 / 延时投递），
 * 因此本类可在 JVM 单测里用假 Sender 精确验证节流与载荷（不依赖 Handler、不依赖 Flutter 引擎）。
 *
 * <p>存活守卫沿用 {@code GlassFlutterActivity.revealLogsIfVisible()} 的判据：
 * {@code activity != null && !isFinishing() && mLocalChannel != null}——没有 Flutter 界面时不产生任何开销。
 */
final class FlutterBridgeEvents {

    /** Android 侧适配器（生产实现见 {@code GlassFlutterActivity}）。 */
    interface Sender {
        /** Flutter 界面是否存活（false → 直接丢弃，零开销）。 */
        boolean alive();
        /** 发送一行 JSON 到 autojs.local.bridge。 */
        void send(String json);
        /** 打一条 logcat（Lead 真机验收用：FlutterBridgeEvent: &lt;name&gt; &lt;摘要&gt;）。 */
        void log(String line);
        /** 单调时钟（毫秒）。 */
        long now();
        /** 延时投递（生产用主线程 Handler.postDelayed）。 */
        void postDelayed(Runnable runnable, long delayMs);
        /** 撤销未触发的延时投递。 */
        void removeCallbacks(Runnable runnable);
    }

    static final String LOG_TAG_PREFIX = "FlutterBridgeEvent: ";

    private final Sender sender;
    private final Map<String, Map<String, Object>> pending = new LinkedHashMap<>();
    private final Runnable flush = this::flushNow;
    private boolean scheduled;
    private int sentCount;

    FlutterBridgeEvents(Sender sender) {
        this.sender = sender;
    }

    /**
     * 发一个事件；Flutter 不存活或事件名未知时静默丢弃（契约：未知事件忽略）。
     * task-50：调用方可能来自 Rx/后台线程（TimedTask 变更流）——pending 与 scheduled 都在锁内维护，
     * 真正发送仍在 postDelayed 的主线程回调里。
     */
    synchronized void emit(Map<String, Object> payload) {
        if (payload == null) return;
        String event = FlutterBridgeEventCodec.eventName(payload);
        if (!FlutterBridgeEventCodec.isKnownEvent(event)) return;
        if (!sender.alive()) return;
        // 窗口内同 key 合并：后来的覆盖先前的（保留最新状态），只发一次。
        pending.put(FlutterBridgeEventCodec.mergeKey(payload), payload);
        schedule();
    }

    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        sender.postDelayed(flush, FlutterBridgeEventCodec.THROTTLE_MS);
    }

    /** 立刻把待发事件全部发出去（测试可直接调用；生产由 postDelayed 触发）。 */
    synchronized void flushNow() {
        scheduled = false;
        sender.removeCallbacks(flush);
        if (pending.isEmpty()) return;
        List<Map<String, Object>> batch = new ArrayList<>(pending.values());
        pending.clear();
        for (Map<String, Object> payload : batch) {
            if (!sender.alive()) return; // 中途退出界面：剩下的不发
            String event = FlutterBridgeEventCodec.eventName(payload);
            sender.send(FlutterBridgeEventCodec.encode(payload));
            sender.log(LOG_TAG_PREFIX + event + " " + FlutterBridgeEventCodec.summary(payload));
            sentCount++;
        }
    }

    /** 已发送条数（测试/诊断用）。 */
    int sentCount() {
        return sentCount;
    }

    /** 当前待发条数（测试用）。 */
    int pendingCount() {
        return pending.size();
    }

    void dispose() {
        scheduled = false;
        sender.removeCallbacks(flush);
        pending.clear();
    }

    /** 便利入口（Android 侧调用点用；载荷细节都在 codec 里）。 */
    void taskChanged(String reason, String path, String state) {
        emit(FlutterBridgeEventCodec.taskChanged(reason, path, state));
    }

    void accessibilityState(boolean connected, String reason, String state,
                           boolean automationConnectionReady, Boolean settingsContainsComponent, long elapsedMs) {
        emit(FlutterBridgeEventCodec.accessibilityState(connected, reason, state,
                automationConnectionReady, settingsContainsComponent, elapsedMs));
    }

    void debugConnectionState(String state, String detail) {
        emit(FlutterBridgeEventCodec.debugConnectionState(state, detail));
    }

    /** 只给 key（值绝不出通道）。 */
    void prefsChanged(String key) {
        emit(FlutterBridgeEventCodec.prefsChanged(key));
    }

    void processResumed() {
        emit(FlutterBridgeEventCodec.processResumed(sender.now()));
    }

    /** 迭代器语法糖（避免 IDE 误报未使用）。 */
    @SuppressWarnings("unused")
    private Iterator<String> pendingKeys() {
        return pending.keySet().iterator();
    }
}
