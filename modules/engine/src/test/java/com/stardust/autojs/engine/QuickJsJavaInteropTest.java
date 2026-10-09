package com.stardust.autojs.engine;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Real reflection regressions for hidden JDK/Android collection implementation classes. */
public class QuickJsJavaInteropTest {
    private final QuickJsJavaInterop interop = new QuickJsJavaInterop();

    private Object call(Object receiver, String name, Class<?>[] parameters, Object... args) throws Exception {
        Method target = receiver.getClass().getMethod(name, parameters);
        Method invoke = QuickJsJavaInterop.class.getDeclaredMethod("invoke", Method.class, Object.class, Object[].class);
        invoke.setAccessible(true);
        try {
            return invoke.invoke(interop, target, receiver, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private Object call(Object receiver, String name) throws Exception {
        return call(receiver, name, new Class<?>[0]);
    }

    @Test public void hashSetIteratorSupportsReadAndRemove() throws Exception {
        Set<String> values = new HashSet<>(Arrays.asList("x"));
        Iterator<?> iterator = (Iterator<?>) call(values, "iterator");
        assertFalse(Modifier.isPublic(iterator.getClass().getModifiers()));
        assertEquals(true, call(iterator, "hasNext"));
        assertEquals("x", call(iterator, "next"));
        call(iterator, "remove");
        assertTrue(values.isEmpty());
        assertEquals(false, call(iterator, "hasNext"));
    }

    @Test public void hashMapKeySetSupportsCollectionAndIteratorMethods() throws Exception {
        Map<String, Integer> values = new HashMap<>();
        values.put("key", 7);
        Object keys = call(values, "keySet");
        assertEquals(1, call(keys, "size"));
        assertEquals(false, call(keys, "isEmpty"));
        assertEquals(true, call(keys, "contains", new Class<?>[]{Object.class}, "key"));
        Object iterator = call(keys, "iterator");
        assertEquals(true, call(iterator, "hasNext"));
        assertEquals("key", call(iterator, "next"));
    }

    @Test public void hashMapEntrySetAndEntriesRetainMutation() throws Exception {
        Map<String, Integer> values = new HashMap<>();
        values.put("key", 7);
        Object entries = call(values, "entrySet");
        assertEquals(1, call(entries, "size"));
        Object iterator = call(entries, "iterator");
        Object entry = call(iterator, "next");
        assertFalse(Modifier.isPublic(entry.getClass().getModifiers()));
        assertEquals("key", call(entry, "getKey"));
        assertEquals(7, call(entry, "getValue"));
        assertEquals(7, call(entry, "setValue", new Class<?>[]{Object.class}, 9));
        assertEquals(Integer.valueOf(9), values.get("key"));
    }

    @Test public void publicCollectionsKeepNormalInvocation() throws Exception {
        ArrayList<String> values = new ArrayList<>();
        assertEquals(true, call(values, "add", new Class<?>[]{Object.class}, "item"));
        assertEquals("item", call(values, "get", new Class<?>[]{int.class}, 0));
        assertEquals(1, call(values, "size"));
    }

    public interface Labelled { String label(); }
    private static final class HiddenLabel implements Labelled {
        @Override public String label() { return "interface implementation"; }
    }
    public static class PublicBase {
        public String label(int value) { return "base " + value; }
    }
    private static final class HiddenSubclass extends PublicBase {
        @Override public String label(int value) { return "override " + value; }
    }

    @Test public void hiddenClassesDispatchThroughPublicInterfacesAndSuperclasses() throws Exception {
        assertEquals("interface implementation", call(new HiddenLabel(), "label"));
        assertEquals("override 3", call(new HiddenSubclass(), "label", new Class<?>[]{int.class}, 3));
    }

    @Test public void targetExceptionsStillReachTheCaller() throws Exception {
        try {
            call(new ArrayList<>(), "get", new Class<?>[]{int.class}, 0);
            fail("The collection exception must propagate");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("IndexOutOfBoundsException"));
        }
    }
}
