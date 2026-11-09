package com.jdkshen.aijspro.mcp;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 * task-50：MCP 运行路径的事件出口（纯 JVM）。
 *
 * 覆盖：开始/结束/异常三类 reason 的转发、path 为 null 时**原样传 null**（由 codec 省略字段）、
 * listener 抛异常不影响脚本执行、clearListener 之后不再投递。
 */
public class McpTaskEventSinkTest {

    private static final class Recording implements McpTaskEventSink.Listener {
        final List<String> events = new ArrayList<>();

        @Override public void onTaskEvent(String reason, String path, String state) {
            events.add(reason + "|" + path + "|" + state);
        }
    }

    @After
    public void tearDown() {
        McpTaskEventSink.clearListener();
    }

    @Test
    public void forwardsScriptLifecycleEventsAndKeepsMissingPathNull() {
        Recording recording = new Recording();
        McpTaskEventSink.setListener(recording);

        McpTaskEventSink.emit("script_started", "main.js", "running");
        McpTaskEventSink.emit("script_finished", "main.js", "finished");
        McpTaskEventSink.emit("script_failed", "main.js", "failed");
        McpTaskEventSink.emit("script_finished", null, "stopped");

        assertEquals(4, recording.events.size());
        assertEquals("script_started|main.js|running", recording.events.get(0));
        assertEquals("script_finished|main.js|finished", recording.events.get(1));
        assertEquals("script_failed|main.js|failed", recording.events.get(2));
        assertEquals("script_finished|null|stopped", recording.events.get(3));
    }

    @Test
    public void listenerFailuresDoNotPropagateAndClearStopsDelivery() {
        McpTaskEventSink.setListener((reason, path, state) -> {
            throw new IllegalStateException("listener boom");
        });
        McpTaskEventSink.emit("script_started", "a.js", "running");

        Recording recording = new Recording();
        McpTaskEventSink.setListener(recording);
        McpTaskEventSink.clearListener();
        McpTaskEventSink.emit("script_started", "a.js", "running");
        assertEquals(0, recording.events.size());
    }

    /** 没有 listener 时必须是零开销 no-op。 */
    @Test
    public void noListenerMeansNoWork() {
        McpTaskEventSink.clearListener();
        McpTaskEventSink.emit("script_started", "a.js", "running");
        McpTaskEventSink.emit("script_failed", null, "failed");
    }
}
