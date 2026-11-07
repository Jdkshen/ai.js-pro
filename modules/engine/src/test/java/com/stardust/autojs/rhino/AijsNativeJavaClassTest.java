package com.stardust.autojs.rhino;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.WrapFactory;

import static org.junit.Assert.*;

public class AijsNativeJavaClassTest {

    private static final class AijsWrapFactory extends WrapFactory {
        @Override
        public Scriptable wrapJavaClass(Context cx, Scriptable scope, Class<?> clazz) {
            return new AijsNativeJavaClass(scope, clazz);
        }

        @Override
        public Object wrap(Context cx, Scriptable scope, Object obj, Class<?> staticType) {
            if (obj instanceof Class) return new AijsNativeJavaClass(scope, (Class<?>) obj);
            return super.wrap(cx, scope, obj, staticType);
        }

        @Override
        public Scriptable wrapAsJavaObject(Context cx, Scriptable scope, Object obj, Class<?> staticType) {
            if (obj instanceof Class) return new AijsNativeJavaClass(scope, (Class<?>) obj);
            return super.wrapAsJavaObject(cx, scope, obj, staticType);
        }
    }

    private static final class AijsContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setWrapFactory(new AijsWrapFactory());
            return cx;
        }
    }

    private Object evaluate(String source) {
        ContextFactory factory = new AijsContextFactory();
        Context cx = factory.enterContext();
        try {
            Scriptable scope = cx.initStandardObjects();
            return cx.evaluateString(scope, source, "aijs-test", 1, null);
        } finally {
            Context.exit();
        }
    }

    @Test
    public void keepsRhinoPackagesAndArithmeticWorking() {
        assertEquals(2, ((Number) evaluate("1 + 1")).intValue());
        assertEquals("true", evaluate("String(Packages.java.lang.String.valueOf(true))"));
        assertNotNull(evaluate("Packages.java.lang.String"));
    }

    @Test
    public void classPropertyReturnsRealJavaClass() {
        assertEquals("java.lang.String", evaluate("String(Packages.java.lang.String.class.getName())"));
    }

    @Test
    public void integralNumbersPreferIntOverloads() {
        assertEquals("123", evaluate("String(Packages.java.lang.String.valueOf(123))"));
        assertEquals("-7", evaluate("String(Packages.java.lang.String.valueOf(-7))"));
        assertEquals("1.5", evaluate("String(Packages.java.lang.String.valueOf(1.5))"));
    }

    /**
     * 线上回归（dev-1380~1382）：WrapFactory 把所有 Class 包成 AijsNativeJavaClass 后，
     * 只剩“静态成员”语义，`obj.getClass().getName()` 这类 **Class 实例 API** 全挂 ——
     * 用户打包版就是死在 __ui__.js:408 的 `view.getClass().getName()`。
     */
    @Test
    public void classInstanceApiStaysReachableOnWrappedClasses() {
        // 用户场景的等价写法：普通对象实例 -> getClass() -> Class 实例 API
        assertEquals("java.util.ArrayList", evaluate("String(new java.util.ArrayList().getClass().getName())"));
        assertEquals("ArrayList", evaluate("String(new java.util.ArrayList().getClass().getSimpleName())"));
        assertEquals("java.lang.String", evaluate("String(Packages.java.lang.String.getName())"));
        // 类引用上的 getClass() 在原生 Rhino 下也返回 java.lang.Class（保持不回归、不报错）
        assertEquals("java.lang.Class", evaluate("String(Packages.java.lang.String.getClass().getName())"));
        assertEquals("false", evaluate("String(Packages.java.lang.String.class.isInterface())"));
        assertEquals("true", evaluate("String(Packages.java.lang.String.class.isAssignableFrom(Packages.java.lang.String.class))"));
    }

    @Test
    public void classApiNamesAreVisibleToTheInOperator() {
        assertEquals("true", evaluate("String('getName' in Packages.java.lang.String)"));
        assertEquals("true", evaluate("String('getSimpleName' in java.lang.String)"));
        // 静态成员仍然可见（不能被 Class 实例 API 遮蔽）。
        assertEquals("true", evaluate("String('valueOf' in Packages.java.lang.String)"));
    }

    @Test
    public void staticFieldsAndMethodsStillResolveAfterFallback() {
        assertEquals("2147483647", evaluate("String(Packages.java.lang.Integer.MAX_VALUE)"));
        assertEquals("123", evaluate("String(Packages.java.lang.String.valueOf(123))"));
    }

    @Test
    public void reflectArraySetUsesComponentTypeInsteadOfDouble() {
        Object result = evaluate(
                "var a = java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 1);" +
                        "java.lang.reflect.Array.set(a, 0, 5);" +
                        "String(java.lang.reflect.Array.get(a, 0))");
        assertEquals("5", result);
    }
}
