package org.autojs.source.engine.android;

import org.junit.Test;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class Node24JavaArgumentsTest {
    @Test public void stringAndClassArraysMatchAndInvokeRealReflection() throws Exception {
        Object[] classes = {int.class};
        assertTrue(Node24JavaArguments.compatible(Class[].class, classes));
        Method method = String.class.getMethod("valueOf",
                (Class<?>[]) Node24JavaArguments.coerce(Class[].class, classes));
        assertEquals("12", method.invoke(null, 12));
        Object[] strings = {"a", null, "b"};
        assertTrue(Node24JavaArguments.compatible(String[].class, strings));
        assertArrayEquals(new String[]{"a", null, "b"},
                (String[]) Node24JavaArguments.coerce(String[].class, strings));
        assertFalse(Node24JavaArguments.compatible(String[].class, new Object[]{12}));
    }

    @Test public void primitiveAndNestedArraysRetainTypes() {
        Object[] values = {1, 2.0, 3L};
        assertTrue(Node24JavaArguments.compatible(int[].class, values));
        assertArrayEquals(new int[]{1, 2, 3}, (int[]) Node24JavaArguments.coerce(int[].class, values));
        Object[] nested = {values, new Object[]{4}};
        assertTrue(Node24JavaArguments.compatible(int[][].class, nested));
        int[][] result = (int[][]) Node24JavaArguments.coerce(int[][].class, nested);
        assertArrayEquals(new int[]{1, 2, 3}, result[0]);
        assertArrayEquals(new int[]{4}, result[1]);
        assertFalse(Node24JavaArguments.compatible(int[].class, new Object[]{null}));
        assertFalse(Node24JavaArguments.compatible(int[].class, new Object[]{"bad"}));
        int[] array = new int[2];
        assertSame(array, Node24JavaArguments.coerce(int[].class, array));
    }

    @Test public void charConversionAgreesWithMatching() {
        assertTrue(Node24JavaArguments.compatible(char.class, "中"));
        assertEquals('中', Node24JavaArguments.coerce(char.class, "中"));
        assertTrue(Node24JavaArguments.compatible(char.class, 65));
        assertEquals('A', Node24JavaArguments.coerce(char.class, 65));
        assertFalse(Node24JavaArguments.compatible(char.class, "ab"));
    }

    @Test public void callbackCoercionUsesTheChosenInterface() {
        final Runnable runnable = () -> {};
        Node24JavaArguments.Callback callback = type -> {
            assertSame(Runnable.class, type);
            return runnable;
        };
        assertTrue(Node24JavaArguments.compatible(Runnable.class, callback));
        assertFalse(Node24JavaArguments.compatible(String.class, callback));
        assertSame(runnable, Node24JavaArguments.coerce(Runnable.class, callback));
    }

    @Test public void numericMatchingNeverClaimsAnUnsupportedNumberSubclass() {
        assertFalse(Node24JavaArguments.compatible(java.math.BigInteger.class, 12));
        assertFalse(Node24JavaArguments.compatible(java.math.BigDecimal.class, 1.5));
        assertTrue(Node24JavaArguments.compatible(Integer.class, 12.0));
        assertEquals(12, Node24JavaArguments.coerce(Integer.class, 12.0));
        java.math.BigInteger value = java.math.BigInteger.TEN;
        assertTrue(Node24JavaArguments.compatible(java.math.BigInteger.class, value));
        assertSame(value, Node24JavaArguments.coerce(java.math.BigInteger.class, value));
    }

    public static class Outer { public static class Middle { public static class Inner {} } }
    @Test public void primitiveAndDeeplyNestedClassesResolve() throws Exception {
        Node24ReflectionCache cache = new Node24ReflectionCache();
        ClassLoader loader = getClass().getClassLoader();
        for (Class<?> primitive : new Class<?>[]{boolean.class, byte.class, short.class, int.class,
                long.class, float.class, double.class, char.class, void.class}) {
            assertSame(primitive, cache.findClass(primitive.getName(), loader));
        }
        assertSame(Outer.Middle.Inner.class, cache.findClass(
                Outer.Middle.Inner.class.getName().replace('$', '.'), loader));
        assertSame(int[].class, cache.findClass("[I", loader));
    }
}
