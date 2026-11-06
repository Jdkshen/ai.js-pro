package com.stardust.autojs.engine;

import com.stardust.autojs.rhino.ReflectiveMethodObject;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Regression coverage for #14: reflection must narrow JS numbers by the target method's formals. */
public class JavaReflectiveInvocationTest {

    public static int intTarget(int value) { return value; }
    public static long longTarget(long value) { return value; }
    public static short shortTarget(short value) { return value; }
    public static byte byteTarget(byte value) { return value; }
    public static char charTarget(char value) { return value; }
    public static boolean booleanTarget(boolean value) { return value; }
    public static float floatTarget(float value) { return value; }
    public static double doubleTarget(double value) { return value; }
    public static Integer integerWrapperTarget(Integer value) { return value; }
    public static Boolean booleanWrapperTarget(Boolean value) { return value; }
    public static Object objectTarget(Object value) { return value; }
    public static Number numberTarget(Number value) { return value; }

    private static Method method(String name, Class<?> parameter) throws Exception {
        return JavaReflectiveInvocationTest.class.getMethod(name, parameter);
    }

    private static Object invoke(String name, Class<?> parameter, Object value) throws Exception {
        return JavaReflectiveInvocation.invoke(method(name, parameter), null, new Object[]{value});
    }

    @Test
    public void narrowsEightPrimitiveFormals() throws Exception {
        assertEquals(5, invoke("intTarget", int.class, Double.valueOf(5)));
        assertEquals(7L, invoke("longTarget", long.class, Double.valueOf(7)));
        assertEquals((short) 3, invoke("shortTarget", short.class, Double.valueOf(3)));
        assertEquals((byte) 2, invoke("byteTarget", byte.class, Double.valueOf(2)));
        assertEquals('A', invoke("charTarget", char.class, Double.valueOf(65)));
        assertEquals(Boolean.TRUE, invoke("booleanTarget", boolean.class, Boolean.TRUE));
        assertEquals(1.5f, ((Float) invoke("floatTarget", float.class, Double.valueOf(1.5))), 0.0001f);
        assertEquals(2.5d, (Double) invoke("doubleTarget", double.class, Double.valueOf(2.5)), 0.0000001d);
        assertEquals(Integer.valueOf(9), invoke("integerWrapperTarget", Integer.class, Double.valueOf(9)));
        assertEquals(Boolean.FALSE, invoke("booleanWrapperTarget", Boolean.class, Boolean.FALSE));
    }

    @Test
    public void keepsObjectAndNumberPositionsUnchanged() throws Exception {
        Double number = Double.valueOf(5);
        Object objectResult = invoke("objectTarget", Object.class, number);
        assertSame(number, objectResult);
        assertEquals(Double.class, objectResult.getClass());

        Integer integer = Integer.valueOf(5);
        Object numberResult = invoke("numberTarget", Number.class, integer);
        assertSame(integer, numberResult);
    }

    @Test
    public void rejectsOverflowAndFractionInsteadOfTruncating() throws Exception {
        assertFailsWith("intTarget", int.class, 2147483648L, "超出 int 范围");
        assertFailsWith("byteTarget", byte.class, 128, "超出 byte 范围");
        assertFailsWith("shortTarget", short.class, 40000, "超出 short 范围");
        assertFailsWith("charTarget", char.class, 65536, "超出 char 范围");
        assertFailsWith("intTarget", int.class, 5.5d, "不是整数");
        assertFailsWith("booleanTarget", boolean.class, 1, "类型不匹配");
    }

    private static void assertFailsWith(String name, Class<?> parameter, Object value, String message) throws Exception {
        try {
            invoke(name, parameter, value);
            fail("expected IllegalArgumentException for " + name + " value=" + value);
        } catch (IllegalArgumentException expected) {
            assertTrue("message=" + expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    @Test
    public void rhinoMethodWrapperUsesTargetFormals() throws Exception {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Method intMethod = method("intTarget", int.class);
            ReflectiveMethodObject wrapper = new ReflectiveMethodObject(scope, intMethod, Method.class);
            Function invoke = (Function) wrapper.get("invoke", scope);
            Object result = invoke.call(cx, scope, scope, new Object[]{null, Double.valueOf(7)});
            assertEquals(7, ((Number) result).intValue());
        } finally {
            Context.exit();
        }
    }

    @Test
    public void supportsVarargsTarget() throws Exception {
        Method method = JavaReflectiveInvocationTest.class.getMethod("sum", int[].class);
        Object result = JavaReflectiveInvocation.invoke(method, null, new Object[]{1, 2, 3});
        assertEquals(6, result);
    }

    public static int sum(int... values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    @Test
    public void supportsExplicitVarargsArray() throws Exception {
        Method method = JavaReflectiveInvocationTest.class.getMethod("sum", int[].class);
        Object result = JavaReflectiveInvocation.invoke(method, null,
                new Object[]{new Object[]{Integer.valueOf(2), Integer.valueOf(3)}});
        assertEquals(5, result);
    }
}
