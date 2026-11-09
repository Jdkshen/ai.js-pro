package com.jdkshen.aijspro.flutterpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * F2 / task-48：Flutter 桥状态推送事件（纯 JVM，不依赖 Android / Flutter 引擎）。
 *
 * 覆盖：事件名集合（F1 契约）/ 载荷字段 / 150ms 按 key 合并 / 只在 Flutter 存活时发 / logcat 行格式 /
 * prefsChanged 只给 key 不给值 / 未知事件忽略。
 */
public class FlutterBridgeEventsTest {

    /** 假 Sender：手动时钟 + 记录投递，便于精确验证节流。 */
    private static final class FakeSender implements FlutterBridgeEvents.Sender {
        boolean alive = true;
        long clock = 1_000L;
        final List<String> sent = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final List<Runnable> scheduled = new ArrayList<>();

        @Override public boolean alive() { return alive; }
        @Override public void send(String json) { sent.add(json); }
        @Override public void log(String line) { logs.add(line); }
        @Override public long now() { return clock; }
        @Override public void postDelayed(Runnable runnable, long delayMs) { scheduled.add(runnable); }
        @Override public void removeCallbacks(Runnable runnable) { scheduled.remove(runnable); }
    }

    @Test
    public void eventNamesMatchTheContract() {
        assertEquals(new LinkedHashSet<>(Arrays.asList(
                "taskChanged", "accessibilityState", "debugConnectionState", "prefsChanged", "processResumed")),
                FlutterBridgeEventCodec.EVENT_NAMES);
        assertEquals(5, FlutterBridgeEventCodec.EVENT_NAMES.size());
        assertTrue(FlutterBridgeEventCodec.ALL_EVENT_NAMES.containsAll(FlutterBridgeEventCodec.EVENT_NAMES));
        assertTrue(FlutterBridgeEventCodec.ALL_EVENT_NAMES.contains("pendingAction"));
        assertTrue(FlutterBridgeEventCodec.ALL_EVENT_NAMES.contains("nativePackagingReady"));
        assertEquals(150L, FlutterBridgeEventCodec.THROTTLE_MS);
    }

    @Test
    public void payloadsCarryTheContractFields() {
        Map<String, Object> task = FlutterBridgeEventCodec.taskChanged("script_started", "main.js", "running");
        assertEquals("taskChanged", FlutterBridgeEventCodec.eventName(task));
        assertEquals("script_started", task.get("reason"));
        assertEquals("main.js", task.get("path"));
        assertEquals("running", task.get("state"));

        Map<String, Object> bare = FlutterBridgeEventCodec.taskChanged("script_finished", null, "finished");
        assertFalse(bare.containsKey("path"));
        assertFalse(FlutterBridgeEventCodec.encode(bare).contains("path"));

        Map<String, Object> accessibility = FlutterBridgeEventCodec.accessibilityState(
                false, "not_enabled", "disabled", false, Boolean.FALSE, 12L);
        assertEquals("accessibilityState", FlutterBridgeEventCodec.eventName(accessibility));
        assertEquals(Boolean.FALSE, accessibility.get("connected"));
        assertEquals("not_enabled", accessibility.get("reason"));
        assertEquals(Boolean.FALSE, accessibility.get("settingsContainsComponent"));
        assertEquals(12L, accessibility.get("elapsedMs"));
        Map<String, Object> unreadable = FlutterBridgeEventCodec.accessibilityState(
                false, "settings_unreadable", "unknown", false, null, 7L);
        assertFalse(unreadable.containsKey("settingsContainsComponent"));

        Map<String, Object> debug = FlutterBridgeEventCodec.debugConnectionState("connected", "adb");
        assertEquals("debugConnectionState", FlutterBridgeEventCodec.eventName(debug));
        assertEquals("connected", debug.get("state"));
        assertEquals("adb", debug.get("detail"));

        Map<String, Object> resumed = FlutterBridgeEventCodec.processResumed(42L);
        assertEquals("processResumed", FlutterBridgeEventCodec.eventName(resumed));
        assertEquals(42L, resumed.get("uptimeMs"));
    }

    @Test
    public void throttleMergesSameKeyWithinWindowAndKeepsDistinctKeys() {
        FakeSender sender = new FakeSender();
        FlutterBridgeEvents events = new FlutterBridgeEvents(sender);

        events.taskChanged("script_started", "a.js", "running");
        events.taskChanged("script_progress", "a.js", "running");
        events.taskChanged("script_started", "b.js", "running");
        events.prefsChanged("k1");
        events.prefsChanged("k2");
        events.prefsChanged("k1");

        assertEquals("同 key 合并：a.js 只留一份", 4, events.pendingCount());
        assertEquals("窗口内只安排一次 flush", 1, sender.scheduled.size());
        events.flushNow();
        assertEquals(4, sender.sent.size());
        assertEquals(4, events.sentCount());
        long aCount = sender.sent.stream().filter(json -> json.contains("\"path\":\"a.js\"")).count();
        assertEquals("同一 path 只发一条", 1, aCount);
        assertTrue("同 key 保留最新载荷",
                sender.sent.stream().anyMatch(json -> json.contains("a.js") && json.contains("script_progress")));
        assertTrue("不同 path 各自保留",
                sender.sent.stream().anyMatch(json -> json.contains("b.js") && json.contains("script_started")));

        events.prefsChanged("k1");
        assertEquals(1, sender.scheduled.size());
        events.flushNow();
        assertEquals(5, sender.sent.size());
    }

    @Test
    public void notAliveMeansZeroWork() {
        FakeSender sender = new FakeSender();
        sender.alive = false;
        FlutterBridgeEvents events = new FlutterBridgeEvents(sender);
        events.taskChanged("script_started", "a.js", "running");
        events.accessibilityState(true, "connected", "enabled", true, Boolean.TRUE, 1L);
        events.debugConnectionState("disconnected", null);
        events.prefsChanged("k1");
        events.processResumed();
        assertEquals(0, events.pendingCount());
        assertEquals(0, sender.scheduled.size());
        assertEquals(0, sender.sent.size());
        assertEquals(0, sender.logs.size());
    }

    @Test
    public void logcatLineFormatIsStable() {
        FakeSender sender = new FakeSender();
        FlutterBridgeEvents events = new FlutterBridgeEvents(sender);
        events.taskChanged("script_started", "main.js", "running");
        events.flushNow();
        assertEquals(1, sender.logs.size());
        String line = sender.logs.get(0);
        assertTrue("必须固定前缀 + 事件名：" + line, line.startsWith("FlutterBridgeEvent: taskChanged "));
        assertTrue("摘要要点出关键字段：" + line, line.contains("path=main.js"));
    }

    @Test
    public void prefsChangedNeverLeaksValues() {
        Map<String, Object> payload = FlutterBridgeEventCodec.prefsChanged("key_enable_accessibility_service_by_root");
        assertEquals(new LinkedHashSet<>(Arrays.asList("event", "key")), new LinkedHashSet<>(payload.keySet()));
        String json = FlutterBridgeEventCodec.encode(payload);
        assertEquals("{\"event\":\"prefsChanged\",\"key\":\"key_enable_accessibility_service_by_root\"}", json);
        assertFalse("不能出现 value 字段", json.contains("value"));
        assertFalse("不能出现值本身", json.contains("true") || json.contains("false"));
        assertEquals("{\"event\":\"prefsChanged\"}", FlutterBridgeEventCodec.encode(FlutterBridgeEventCodec.prefsChanged(null)));
    }

    @Test
    public void unknownEventIsIgnoredAndSummaryIsBounded() {
        FakeSender sender = new FakeSender();
        FlutterBridgeEvents events = new FlutterBridgeEvents(sender);
        events.emit(java.util.Collections.singletonMap("event", "bogus"));
        assertEquals(0, events.pendingCount());

        StringBuilder longPath = new StringBuilder();
        for (int i = 0; i < 60; i++) longPath.append("dir/");
        events.taskChanged("script_started", longPath + "main.js", "running");
        events.flushNow();
        String line = sender.logs.get(0);
        assertTrue("摘要要截断", line.length() < 260);
        assertTrue(line.endsWith("..."));
        assertNotNull(FlutterBridgeEventCodec.summary(FlutterBridgeEventCodec.processResumed(9L)));
    }

    @Test
    public void processResumedCarriesTheSenderClock() {
        FakeSender sender = new FakeSender();
        sender.clock = 12_345L;
        FlutterBridgeEvents events = new FlutterBridgeEvents(sender);
        events.processResumed();
        events.flushNow();
        assertEquals("{\"event\":\"processResumed\",\"uptimeMs\":12345}", sender.sent.get(0));
    }

    @Test
    public void jsonEncodingEscapesSafely() {
        Map<String, Object> payload = FlutterBridgeEventCodec.taskChanged("reason", "a\"b\\c\n.js", "running");
        String json = FlutterBridgeEventCodec.encode(payload);
        assertTrue(json.contains("a\\\"b\\\\c\\n.js"));
        assertTrue(FlutterBridgeEventCodec.encode(FlutterBridgeEventCodec.processResumed(1L)).startsWith("{\"event\":"));
    }
}
