package org.autojs.source.engine.android;

import org.junit.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class Node24NoArgCallCacheTest {
    public static class Counter {
        int count;
        boolean fail;
        public int next() {
            if (fail) throw new IllegalStateException("current state failed");
            return ++count;
        }
        public int next(int value) { return count += value; }
        public static String name() { return "static"; }
    }

    @Test public void everyHitInvokesTheCurrentReceiverAndDoesNotMemoizeResultsOrErrors() throws Exception {
        Node24NoArgCallCache cache = new Node24NoArgCallCache();
        Counter counter = new Counter();
        cache.put("request", Counter.class.getMethod("next"), counter);
        Node24NoArgCallCache.Call call = cache.get("request");
        assertEquals(1, call.method.invoke(call.target));
        assertEquals(2, call.method.invoke(call.target));
        counter.fail = true;
        try {
            call.method.invoke(call.target);
            fail("a cached binding must observe new failures");
        } catch (InvocationTargetException expected) {
            assertEquals("current state failed", expected.getCause().getMessage());
        }
        counter.fail = false;
        assertEquals(3, call.method.invoke(call.target));
    }

    @Test public void exactRequestsKeepReceiversAndStaticModeSeparate() throws Exception {
        Node24NoArgCallCache cache = new Node24NoArgCallCache();
        Counter a = new Counter(), b = new Counter();
        Method next = Counter.class.getMethod("next");
        cache.put("obj:1", next, a);
        cache.put("obj:2", next, b);
        cache.put("static", Counter.class.getMethod("name"), null);
        assertSame(a, cache.get("obj:1").target);
        assertSame(b, cache.get("obj:2").target);
        assertNull(cache.get("static").target);
        assertNull(cache.get("obj:1 "));
        assertEquals("static", cache.get("static").method.invoke(null));
    }

    @Test public void parameterizedOverloadsCannotEnterTheCache() throws Exception {
        Node24NoArgCallCache cache = new Node24NoArgCallCache();
        try {
            cache.put("bad", Counter.class.getMethod("next", int.class), new Counter());
            fail("parameter-dependent overloads must keep normal dispatch");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, cache.size());
        }
    }

    @Test public void lruCapacityAndRequestSizeAreBounded() throws Exception {
        Node24NoArgCallCache cache = new Node24NoArgCallCache();
        Method method = Counter.class.getMethod("name");
        for (int i = 0; i < Node24NoArgCallCache.MAX_ENTRIES; i++) cache.put("key" + i, method, null);
        assertNotNull(cache.get("key0"));
        cache.put("overflow", method, null);
        assertNull(cache.get("key1"));
        assertNotNull(cache.get("key0"));
        assertEquals(Node24NoArgCallCache.MAX_ENTRIES, cache.size());
        String huge = new String(new char[Node24NoArgCallCache.MAX_REQUEST_CHARS + 1]);
        cache.put(huge, method, null);
        assertNull(cache.get(huge));
        assertEquals(Node24NoArgCallCache.MAX_ENTRIES, cache.size());
    }

    @Test public void loaderChangeInvalidatesAndCloseCannotRepopulate() throws Exception {
        Node24NoArgCallCache cache = new Node24NoArgCallCache();
        Method method = Counter.class.getMethod("name");
        cache.put("key", method, null);
        cache.clear();
        assertNull(cache.get("key"));
        cache.put("key", method, null);
        assertNotNull(cache.get("key"));
        cache.close();
        cache.put("key", method, null);
        assertNull(cache.get("key"));
        assertEquals(0, cache.size());
    }
}
