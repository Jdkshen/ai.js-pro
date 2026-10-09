package com.stardust.autojs.core.eventloop;

import com.stardust.autojs.runtime.ScriptBridges;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class EventEmitterLifecycleTest {
    private EventEmitter emitter() {
        ScriptBridges bridges = new ScriptBridges();
        bridges.setBridges(new ScriptBridges.Bridges() {
            public Object call(Object f, Object target, Object args) {
                ((Consumer<Object[]>) f).accept((Object[]) args);
                return null;
            }
            public Object toArray(Iterable value) { return value; }
            public Object toString(Object value) { return String.valueOf(value); }
            public Object asArray(Object value) { return value; }
        });
        return new EventEmitter(bridges);
    }
    @Test public void addAndRemoveEmitOriginalListenerIdentity() {
        EventEmitter e = emitter();
        List<Object[]> added = new ArrayList<>(), removed = new ArrayList<>();
        e.on("newListener", (Consumer<Object[]>) added::add);
        e.on("removeListener", (Consumer<Object[]>) removed::add);
        added.clear();
        Consumer<Object[]> listener = args -> {};
        e.on("work", listener);
        assertEquals("work", added.get(0)[0]);
        assertSame(listener, added.get(0)[1]);
        e.removeListener("work", listener);
        e.removeListener("work", listener);
        assertEquals(1, removed.size());
        assertSame(listener, removed.get(0)[1]);
    }
    @Test public void onceIsRemovedBeforeRecursiveEmit() {
        EventEmitter e = emitter();
        int[] calls = {0};
        List<Object[]> removed = new ArrayList<>();
        e.on("removeListener", (Consumer<Object[]>) removed::add);
        e.once("work", (Consumer<Object[]>) args -> { calls[0]++; e.emit("work"); });
        e.emit("work");
        assertEquals(1, calls[0]);
        assertEquals(1, removed.size());
        assertEquals("work", removed.get(0)[0]);
    }
    @Test public void removeAllNotifiesObserverAndReleasesListeners() {
        EventEmitter e = emitter();
        List<Object[]> removed = new ArrayList<>();
        e.on("removeListener", (Consumer<Object[]>) removed::add);
        e.on("a", (Consumer<Object[]>) args -> {});
        e.on("b", (Consumer<Object[]>) args -> {});
        e.removeAllListeners();
        assertEquals(2, removed.size());
        assertEquals(0, e.listenerCount("a"));
        assertEquals(0, e.listenerCount("b"));
        assertEquals(0, e.listenerCount("removeListener"));
    }
}
