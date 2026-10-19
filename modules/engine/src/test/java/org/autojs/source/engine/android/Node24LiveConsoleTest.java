package org.autojs.source.engine.android;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** task-168：Node24 实时控制台的纯 JVM 契约（不依赖 Binder/Android 运行时）。 */
public class Node24LiveConsoleTest {

    @Test public void liveLogsAreOrderedAndDeduplicatedBySequence() {
        final List<String> shown = new ArrayList<>();
        Node24Client.LiveLogState state = new Node24Client.LiveLogState((line, index) ->
                shown.add(index + ":" + line));

        assertTrue(state.offer("one", 0));
        assertTrue(state.offer("two", 1));
        assertFalse("duplicate seq must not be displayed twice", state.offer("two-again", 1));
        assertFalse("late older seq falls back to final logs instead of duplicate", state.offer("zero-late", 0));
        assertEquals(2, state.delivered());
        assertEquals("[0:one, 1:two]", shown.toString());
    }

    @Test public void closeStopsLateLogsAndListenerFailureDoesNotEscape() {
        final List<String> shown = new ArrayList<>();
        Node24Client.LiveLogState state = new Node24Client.LiveLogState((line, index) -> {
            shown.add(line);
            if ("boom".equals(line)) throw new IllegalStateException("console failed");
        });

        assertTrue(state.offer("before", 0));
        assertTrue("listener exception is contained", state.offer("boom", 1));
        state.close();
        assertFalse(state.offer("after", 2));
        assertEquals(2, state.delivered());
        assertEquals(2, shown.size());
    }

    @Test public void missingSequenceUsesNextIndexAndNullLineIsSafe() {
        final List<Integer> indexes = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        Node24Client.LiveLogState state = new Node24Client.LiveLogState((line, index) -> {
            indexes.add(index);
            lines.add(line);
        });

        assertTrue(state.offer("first", -1));
        assertTrue(state.offer(null, -1));
        assertEquals(2, state.delivered());
        assertEquals("[0, 1]", indexes.toString());
        assertEquals("", lines.get(1));
    }

    @Test public void noListenerStillCountsAndNeverThrows() {
        Node24Client.LiveLogState state = new Node24Client.LiveLogState(null);
        assertTrue(state.offer("line", 7));
        assertFalse(state.offer("line-again", 7));
        assertEquals(1, state.delivered());
        state.close();
        assertFalse(state.offer("late", 8));
    }

    @Test public void oldEvaluateOverloadRemainsAndLiveOverloadIsAvailable() throws Exception {
        assertNotNull(Node24Client.class.getMethod("evaluate", JSONObject.class));
        assertNotNull(Node24Client.class.getMethod(
                "evaluate", JSONObject.class, Node24Client.LogListener.class));
    }
}
