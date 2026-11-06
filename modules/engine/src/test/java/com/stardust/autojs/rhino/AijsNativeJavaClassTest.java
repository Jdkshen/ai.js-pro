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

    @Test
    public void reflectArraySetUsesComponentTypeInsteadOfDouble() {
        Object result = evaluate(
                "var a = java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 1);" +
                        "java.lang.reflect.Array.set(a, 0, 5);" +
                        "String(java.lang.reflect.Array.get(a, 0))");
        assertEquals("5", result);
    }
}
