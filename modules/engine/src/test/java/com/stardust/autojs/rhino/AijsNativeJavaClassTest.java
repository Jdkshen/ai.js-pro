package com.stardust.autojs.rhino;

import com.stardust.autojs.engine.ScriptJavaObject;
import com.stardust.autojs.engine.ScriptJavaResources;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrapFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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
        // 与引擎一致：RhinoJavaScriptEngine 用 ES6（__java_util__.js 里有 let/const，默认版本会解析失败）
        cx.setLanguageVersion(Context.VERSION_ES6);
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

    // ------------------------------------------------------------------
    // task-52：Java 静态方法包装必须拿到 Function.prototype（.apply/.call/.bind）
    // ------------------------------------------------------------------

    /** 能在 JVM 里构造的目标对象：覆盖 PreferIntJavaObject 与 ScriptJavaObject 两条包装路径（Paint 造不出来）。 */
    public static class IntLongTarget {
        boolean released;
        String lastCall;

        public void setValue(int value) { lastCall = "int:" + value; }

        public void setValue(long value) { lastCall = "long:" + value; }

        public void release() { released = true; }
    }

    private static String evalIn(Context cx, Scriptable scope, String source) {
        return Context.toString(cx.evaluateString(scope, source, "aijs-test", 1, null));
    }

    /** ① 真机现象：静态方法包装没有 Function.prototype → typeof f.apply === 'undefined'。 */
    @Test
    public void javaStaticMethodWrapperExposesFunctionPrototype() {
        assertEquals("function", evaluate("typeof java.lang.reflect.Array.newInstance"));
        assertEquals("function", evaluate("typeof java.lang.reflect.Array.newInstance.apply"));
        assertEquals("function", evaluate("typeof java.lang.reflect.Array.newInstance.call"));
        assertEquals("function", evaluate("typeof java.lang.reflect.Array.newInstance.bind"));
        // 与 Rhino 原生 Function.prototype.apply 是同一个对象（不是我们另写一份实现）
        assertEquals("true", evaluate("String(java.lang.Math.max.apply === Function.prototype.apply)"));
    }

    /** ②③⑤ apply/call 与直接调用一致；thisArg（null/undefined）对静态方法无意义但不能报错。 */
    @Test
    public void staticMethodApplyAndCallMatchDirectCall() {
        assertEquals("3", evaluate("String(java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 3).length)"));
        assertEquals("3", evaluate("String(java.lang.reflect.Array.newInstance.apply(null, [java.lang.Integer.TYPE, 3]).length)"));
        assertEquals("3", evaluate("String(java.lang.reflect.Array.newInstance.apply(undefined, [java.lang.Integer.TYPE, 3]).length)"));
        assertEquals("3", evaluate("String(java.lang.reflect.Array.newInstance.call(null, java.lang.Integer.TYPE, 3).length)"));
        assertEquals("7", evaluate("String(java.lang.Math.max.apply(null, [3, 7]))"));
        assertEquals("7", evaluate("String(java.lang.Math.max.call(null, 3, 7))"));
        assertEquals("3", evaluate("String(java.lang.Math.min.apply(null, [7, 3]))"));
    }

    /** ③ apply 的实参数组：JS 数组 / arguments 对象 / Java 数组（NativeJavaArray）/ null（→ 0 参）。 */
    @Test
    public void applyAcceptsJsArraysArgumentsAndJavaArrays() {
        // Java 数组（NativeJavaArray）作为实参数组：Rhino 靠它暴露的 length 属性 + 下标访问取值
        assertEquals("7", evaluate(
                "var a = java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 2);" +
                        "java.lang.reflect.Array.set(a, 0, 3);" +
                        "java.lang.reflect.Array.set(a, 1, 7);" +
                        "String(java.lang.Math.max.apply(null, a))"));
        // null / 空数组 → 0 个参数（currentTimeMillis 无参，能证明既没多传也没少传）
        assertEquals("number", evaluate("typeof java.lang.System.currentTimeMillis.apply(null, null)"));
        assertEquals("number", evaluate("typeof java.lang.System.currentTimeMillis.apply(null, [])"));
        // arguments 对象（util.java.array 内部就是 arguments + apply）
        assertEquals("2", evaluate(
                "function f(){ return java.lang.reflect.Array.newInstance.apply(null, arguments); }" +
                        "String(f(java.lang.Integer.TYPE, 2).length)"));
    }

    /** ④ 公共 API 直接回归：__java_util__.js 的 J.array（= util.java.array 的实现）走 arguments + apply。 */
    @Test
    public void utilJavaArrayStillWorksAfterThePrototypeFix() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/assets/modules/__java_util__.js")), StandardCharsets.UTF_8);
        ContextFactory factory = new AijsContextFactory();
        Context cx = factory.enterContext();
        // 与引擎一致（RhinoJavaScriptEngine.kt:266）：__java_util__.js 用 let/const，ES5 解析会 missing ; before statement
        cx.setLanguageVersion(Context.VERSION_ES6);
        try {
            Scriptable scope = cx.initStandardObjects();
            // 复刻引擎的 CommonJS 装载：module.exports = J（J.array 内部就是 arguments + apply）
            cx.evaluateString(scope,
                    "var module = { exports: {} }, exports = module.exports;\n" + source + "\n",
                    "__java_util__", 1, null);
            assertEquals("2", evalIn(cx, scope, "String(module.exports.array(java.lang.Boolean.TYPE, 2).length)"));
            // 组件类型也真的传对了：int[] 才能用 Array.set/get 存取整数
            assertEquals("3", evalIn(cx, scope,
                    "var b = module.exports.array('int', 2);" +
                            "java.lang.reflect.Array.set(b, 0, 3);" +
                            "String(java.lang.reflect.Array.get(b, 0))"));
            assertEquals("true", evalIn(cx, scope,
                    "String(module.exports.array(java.lang.Boolean.TYPE, 2) instanceof java.lang.Object)"));
        } finally {
            Context.exit();
        }
    }

    /** ⑥ PreferIntJavaObject（Paint 的 int/long 重载包装）不回归，且新增 apply/call。 */
    @Test
    public void preferIntWrapperKeepsIntPriorityAndGainsApplyAndCall() {
        IntLongTarget target = new IntLongTarget();
        ContextFactory factory = new AijsContextFactory();
        Context cx = factory.enterContext();
        try {
            Scriptable scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "t",
                    new PreferIntJavaObject(scope, target, IntLongTarget.class));
            assertEquals("function", evalIn(cx, scope, "typeof t.setValue.apply"));
            // 直接调用：整数优先（Rhino 原生会挑 long，这里必须是 int）
            target.lastCall = null;
            evalIn(cx, scope, "t.setValue(5)");
            assertEquals("int:5", target.lastCall);
            // apply / call：同样走包装器（包装器对 void 方法返回 undefined，只能看副作用）
            target.lastCall = null;
            evalIn(cx, scope, "t.setValue.apply(t, [5])");
            assertEquals("int:5", target.lastCall);
            target.lastCall = null;
            evalIn(cx, scope, "t.setValue.call(t, 5)");
            assertEquals("int:5", target.lastCall);
        } finally {
            Context.exit();
        }
    }

    /** 审计补漏：ReflectiveMethodObject 的 m.invoke 也是包装函数（m.invoke.apply 是常见写法）。 */
    @Test
    public void reflectiveInvokeWrapperGainsApply() throws Exception {
        ContextFactory factory = new AijsContextFactory();
        Context cx = factory.enterContext();
        try {
            Scriptable scope = cx.initStandardObjects();
            Method max = Math.class.getMethod("max", int.class, int.class);
            ScriptableObject.putProperty(scope, "M", new ReflectiveMethodObject(scope, max, Method.class));
            assertEquals("function", evalIn(cx, scope, "typeof M.invoke.apply"));
            assertEquals("7", evalIn(cx, scope, "String(M.invoke(null, 3, 7))"));
            assertEquals("7", evalIn(cx, scope, "String(M.invoke.apply(null, [null, [3, 7]]))"));
        } finally {
            Context.exit();
        }
    }

    /** 审计补漏：ScriptJavaObject 的成员包装（view.release/addView 等）同样要有 apply。 */
    @Test
    public void scriptJavaObjectWrapperGainsApply() {
        IntLongTarget target = new IntLongTarget();
        ContextFactory factory = new AijsContextFactory();
        Context cx = factory.enterContext();
        try {
            Scriptable scope = cx.initStandardObjects();
            ScriptableObject.putProperty(scope, "w", new ScriptJavaObject(scope, target, IntLongTarget.class,
                    new ScriptJavaResources()));
            assertEquals("function", evalIn(cx, scope, "typeof w.release.apply"));
            evalIn(cx, scope, "w.release.apply(w, [])");
            assertTrue("包装函数仍然调用了目标方法", target.released);
        } finally {
            Context.exit();
        }
    }
}
