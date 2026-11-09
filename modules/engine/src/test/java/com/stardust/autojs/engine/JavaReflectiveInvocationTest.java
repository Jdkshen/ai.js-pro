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

    // ------------------------------------------------------------------
    // task-51：Method.invoke(target, argArray) 的 Java varargs 语义（device4 真机 bug）
    // ------------------------------------------------------------------

    public static int max(int a, int b) { return Math.max(a, b); }

    public static int argCount(Object... values) { return values.length; }

    public static int sumArray(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    public static int firstLength(Object[] values) { return values.length; }

    private static Method named(String name, Class<?>... parameters) throws Exception {
        return JavaReflectiveInvocationTest.class.getMethod(name, parameters);
    }

    /** A：直传多参（真机 ✅ 的基线）不得被展开逻辑改变。 */
    @Test
    public void twoScalarArgumentsStayTwoArguments() throws Exception {
        assertEquals(7, JavaReflectiveInvocation.invoke(named("max", int.class, int.class), null,
                new Object[]{Double.valueOf(3), Double.valueOf(7)}));
        assertEquals(2, JavaReflectiveInvocation.invoke(named("argCount", Object[].class), null,
                new Object[]{Integer.valueOf(3), Integer.valueOf(7)}));
    }

    /** 展开规则的纯函数覆盖：单个数组展开 + 四个边界（null / 单非数组 / 多参 / 空数组 / 原始类型数组）。 */
    @Test
    public void expandInvokeArgumentsFollowsJavaVarargsRules() {
        Object[] two = new Object[]{Integer.valueOf(3), Integer.valueOf(7)};
        assertSame("单个 Object[] 实参就是 varargs 数组，必须展开", two,
                JavaReflectiveInvocation.expandInvokeArguments(new Object[]{two}));
        assertEquals("空数组 → 0 个参数", 0,
                JavaReflectiveInvocation.expandInvokeArguments(new Object[]{new Object[0]}).length);
        Object[] singleNull = new Object[]{null};
        assertSame("null 不是数组 → 长度保持 1", singleNull,
                JavaReflectiveInvocation.expandInvokeArguments(singleNull));
        Object[] singleScalar = new Object[]{Integer.valueOf(5)};
        assertSame("单个非数组 → 长度保持 1", singleScalar,
                JavaReflectiveInvocation.expandInvokeArguments(singleScalar));
        Object[] multi = new Object[]{Integer.valueOf(3), Integer.valueOf(7)};
        assertSame("多参 → 原样返回", multi, JavaReflectiveInvocation.expandInvokeArguments(multi));
        Object[] primitiveArray = new Object[]{new int[]{1, 2, 3}};
        assertSame("原始类型数组不是 Object[] → 保持 1 个参数（javac 同样如此）", primitiveArray,
                JavaReflectiveInvocation.expandInvokeArguments(primitiveArray));
        assertEquals(0, JavaReflectiveInvocation.expandInvokeArguments(null).length);
    }

    /** B：单个 Object[] 实参（真机 ❌ 的第一种写法，也是 QuickJS/Node24 的参数形态）现在能正确展开。 */
    @Test
    public void singleObjectArrayArgumentIsExpandedForTargetMethod() throws Exception {
        Object[] args = new Object[]{Integer.valueOf(3), Integer.valueOf(7)};
        assertEquals(7, JavaReflectiveInvocation.invoke(named("max", int.class, int.class), null,
                new Object[]{args}));
        assertEquals("空数组 → 目标方法收到 0 个参数", 0,
                JavaReflectiveInvocation.invoke(named("argCount", Object[].class), null,
                        new Object[]{new Object[0]}));
        assertEquals("null 不是数组 → 目标方法收到 1 个 null 参数", 1,
                JavaReflectiveInvocation.invoke(named("argCount", Object[].class), null,
                        new Object[]{null}));
        assertEquals("原始类型数组保持 1 个参数（目标形参是 int[]）", 6,
                JavaReflectiveInvocation.invoke(named("sumArray", int[].class), null,
                        new Object[]{new int[]{1, 2, 3}}));
    }

    /** 变参目标方法：逐个传参与显式数组都仍然打包（既有语义不回归）。 */
    @Test
    public void varargsTargetStillPacksBothWays() throws Exception {
        Method sum = named("sum", int[].class);
        assertEquals(5, JavaReflectiveInvocation.invoke(sum, null,
                new Object[]{Integer.valueOf(2), Integer.valueOf(3)}));
        assertEquals(5, JavaReflectiveInvocation.invoke(sum, null,
                new Object[]{new Object[]{Integer.valueOf(2), Integer.valueOf(3)}}));
        assertEquals(0, JavaReflectiveInvocation.invoke(sum, null, new Object[]{new Object[0]}));
    }

    /** 目标形参本身就是 Object[]：按 Java 语义要包一层（外层数组才是 varargs 数组）。 */
    @Test
    public void singleObjectArrayParameterNeedsWrapping() throws Exception {
        Method method = named("firstLength", Object[].class);
        Object[] inner = new Object[]{Integer.valueOf(1), Integer.valueOf(2)};
        try {
            JavaReflectiveInvocation.invoke(method, null, new Object[]{inner});
            fail("直接传数组时它就是 varargs 数组，目标会收到 2 个参数并报个数不匹配");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("需要 1 个，实际 2 个"));
        }
        assertEquals(2, JavaReflectiveInvocation.invoke(method, null, new Object[]{new Object[]{inner}}));
    }

    public int instanceMax(int a, int b) { return Math.max(a, b); }

    /** 经典实例写法：m.invoke(javaObj, [3, 7])——target 是 Rhino 包装的 Java 对象，参数是脚本数组。 */
    @Test
    public void rhinoWrapperUnwrapsJavaTargetAndExpandsJsArray() throws Exception {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Method method = named("instanceMax", int.class, int.class);
            Function invoke = (Function) new ReflectiveMethodObject(scope, method, Method.class).get("invoke", scope);
            Object target = cx.getWrapFactory().wrap(cx, scope, new JavaReflectiveInvocationTest(),
                    JavaReflectiveInvocationTest.class);
            Object jsArray = cx.newArray(scope, new Object[]{Integer.valueOf(3), Integer.valueOf(7)});
            assertEquals(7, ((Number) invoke.call(cx, scope, scope, new Object[]{target, jsArray})).intValue());
        } finally {
            Context.exit();
        }
    }

    /** C：Rhino 侧 A/B/C 三态（真机复现脚本的三种写法）。 */
    @Test
    public void rhinoWrapperExpandsJsArrayAndJavaArrayArguments() throws Exception {
        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();
            Method max = named("max", int.class, int.class);
            Function invoke = (Function) new ReflectiveMethodObject(scope, max, Method.class).get("invoke", scope);
            // A：直传多参
            assertEquals(7, ((Number) invoke.call(cx, scope, scope,
                    new Object[]{null, Integer.valueOf(3), Integer.valueOf(7)})).intValue());
            // B：Java Object[]（java.lang.reflect.Array.newInstance(java.lang.Object, 2) 的等价物）
            assertEquals(7, ((Number) invoke.call(cx, scope, scope,
                    new Object[]{null, new Object[]{Integer.valueOf(3), Integer.valueOf(7)}})).intValue());
            // B'：Rhino 的 Java 数组包装（NativeJavaObject/NativeJavaArray，Wrapper）
            Object wrapped = cx.getWrapFactory().wrap(cx, scope,
                    new Object[]{Integer.valueOf(3), Integer.valueOf(7)}, Object[].class);
            assertEquals(7, ((Number) invoke.call(cx, scope, scope,
                    new Object[]{null, wrapped})).intValue());
            // C：脚本数组字面量 [3, 7]（Rhino 的 NativeArray）
            Object jsArray = cx.newArray(scope, new Object[]{Integer.valueOf(3), Integer.valueOf(7)});
            assertEquals(7, ((Number) invoke.call(cx, scope, scope,
                    new Object[]{null, jsArray})).intValue());
            // 边界：null 不是数组 → 仍是 1 个参数（argCount 收到 1 个 null）；空脚本数组 → 0 个参数
            Method argCount = named("argCount", Object[].class);
            Function countInvoke = (Function) new ReflectiveMethodObject(scope, argCount, Method.class).get("invoke", scope);
            assertEquals(1, ((Number) countInvoke.call(cx, scope, scope, new Object[]{null, null})).intValue());
            assertEquals("空数组 → 0 个参数", 0, ((Number) countInvoke.call(cx, scope, scope,
                    new Object[]{null, cx.newArray(scope, new Object[0])})).intValue());
        } finally {
            Context.exit();
        }
    }
}
