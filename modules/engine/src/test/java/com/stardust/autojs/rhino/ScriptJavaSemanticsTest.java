package com.stardust.autojs.rhino;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrapFactory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * task-53：Rhino/JS↔Java 桥的**表驱动语义回归组**（+「包装清单」防漏）。
 *
 * <p>为什么要有这个类：连着的两处真 bug 都是「Rhino/JS 原生语义被我们改坏」，而且**先被真实脚本撞上**——
 * ① task-51 `Method.invoke(target, argArray)` 不按 varargs 展开 → 经典反射写法全废；
 * ② task-52 Java 函数包装没有 `Function.prototype` → `.apply/.call/.bind` 缺失、`util.java.array` 不可用，
 * 且报错还是误导性的 `Cannot find default value for object`。
 *
 * <p>机制：一组 (分组, 名称, 可选 Java 注入, 前置脚本, 用例脚本, 期望) 用例，在**同一个 harness**（AijsWrapFactory + ES6）
 * 下逐条跑；一条失败不影响其它，最后把全部差异一次性报出来（一眼看出是哪个语义面回归）。
 *
 * <p>harness 必须 `VERSION_ES6`：与引擎一致（`RhinoJavaScriptEngine.kt:266`），否则 `__java_util__.js` 的
 * `let/const` 会报 `missing ; before statement`。
 *
 * <p>**新增包装类时**：同步本文件的 `baseFunctionWrappersAreAllAudited` 清单、
 * `scriptVisibleWrappersExposeApplyCallAndBind` 表，以及 `docs/reports/2026-11-09_Rhino函数包装与JS桥语义审计.md`。
 */
public class ScriptJavaSemanticsTest {

    // ------------------------------------------------------------------ harness

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
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setWrapFactory(new AijsWrapFactory());
            return cx;
        }
    }

    private static final ContextFactory FACTORY = new AijsContextFactory();

    /** 装载真实的 `__java_util__.js`（公共 API `util.java.array` 的实现），暴露成 `util.java`。 */
    private static final String UTIL_SETUP;

    static {
        String source;
        try {
            source = new String(Files.readAllBytes(Paths.get("src/main/assets/modules/__java_util__.js")),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("读取 __java_util__.js 失败（测试工作目录应为 modules/engine）：" + error, error);
        }
        UTIL_SETUP = "var util = { java: (function(){ var module = { exports: {} }, exports = module.exports;\n"
                + source + "\nreturn module.exports; })() };";
    }

    // ------------------------------------------------------------------ 表驱动用例

    /** 一条用例：Java 侧注入（可空）+ 前置脚本（可空）+ 用例脚本 + 期望（或期望抛出的子串）。 */
    private static final class Case {
        final String group;
        final String name;
        final Consumer<Scriptable> setup;
        final String preface;
        final String script;
        final String expected;
        final String expectThrowContains;

        Case(String group, String name, Consumer<Scriptable> setup, String preface, String script,
                String expected, String expectThrowContains) {
            this.group = group;
            this.name = name;
            this.setup = setup;
            this.preface = preface;
            this.script = script;
            this.expected = expected;
            this.expectThrowContains = expectThrowContains;
        }
    }

    /** 纯脚本用例。 */
    private static Case c(String group, String name, String script, String expected) {
        return new Case(group, name, null, null, script, expected, null);
    }

    /** 需要 Java 侧注入（Java 类/对象）的用例。 */
    private static Case withJava(String group, String name, String script, String expected) {
        return new Case(group, name, ScriptJavaSemanticsTest::javaProbe, null, script, expected, null);
    }

    /** 需要前置脚本（例如装载 util 模块）的用例。 */
    private static Case pre(String group, String name, String script, String expected) {
        return new Case(group, name, null, UTIL_SETUP, script, expected, null);
    }

    /** 期望抛异常且消息里含指定子串。 */
    private static Case throwsWith(String group, String name, String script, String messageContains) {
        return new Case(group, name, null, null, script, null, messageContains);
    }

    private static Case throwsWithJava(String group, String name, String script, String messageContains) {
        return new Case(group, name, ScriptJavaSemanticsTest::javaProbe, null, script, null, messageContains);
    }

    /** 只要求「不抛异常」（用于 `String(x)`/字符串拼接这类过去会抛误导性 TypeError 的路径）。 */
    private static Case noThrow(String group, String name, String script) {
        return new Case(group, name, null, null, script, null, null);
    }

    /** 把 Java 探针注入作用域：T=探针类、M/ML/MS=三个 Method、t=PreferInt 包装、w=ScriptJavaObject 包装。 */
    private static void javaProbe(Scriptable scope) {
        SemanticsOverloadTarget.lastCall = null;
        try {
            ScriptableObject.putProperty(scope, "T", Context.javaToJS(SemanticsOverloadTarget.class, scope));
            ScriptableObject.putProperty(scope, "M", new ReflectiveMethodObject(scope,
                    Math.class.getMethod("max", int.class, int.class), Method.class));
            ScriptableObject.putProperty(scope, "ML", new ReflectiveMethodObject(scope,
                    SemanticsOverloadTarget.class.getMethod("firstLength", Object[].class), Method.class));
            ScriptableObject.putProperty(scope, "MS", new ReflectiveMethodObject(scope,
                    SemanticsOverloadTarget.class.getMethod("sum", int[].class), Method.class));
        } catch (NoSuchMethodException error) {
            throw new IllegalStateException(error);
        }
        ScriptableObject.putProperty(scope, "t", new PreferIntJavaObject(scope,
                new SemanticsOverloadTarget(), SemanticsOverloadTarget.class));
        ScriptableObject.putProperty(scope, "w", new com.stardust.autojs.engine.ScriptJavaObject(scope,
                new SemanticsOverloadTarget(), SemanticsOverloadTarget.class,
                new com.stardust.autojs.engine.ScriptJavaResources()));
        // 普通 Java 对象（Rhino 自带的 NativeJavaMethod 路径）：实例方法也必须能 apply/call/bind
        // —— task-53 曾把这条误判为缺口（G1），task-58 双机实证后更正为「本来就正常」。
        java.util.ArrayList<Object> list = new java.util.ArrayList<>();
        list.add("a");
        ScriptableObject.putProperty(scope, "list", Context.javaToJS(list, scope));
    }
    // ------------------------------------------------------------------ 用例表

    private static List<Case> buildCases() {
        List<Case> list = new ArrayList<>();

        // ---- 函数性：包装函数必须拿到 Function.prototype（task-52 真机 bug） ----
        list.add(c("函数性", "静态方法包装是函数", "typeof java.lang.Math.max", "function"));
        list.add(c("函数性", "apply 存在", "typeof java.lang.Math.max.apply", "function"));
        list.add(c("函数性", "call 存在", "typeof java.lang.Math.max.call", "function"));
        list.add(c("函数性", "bind 存在", "typeof java.lang.Math.max.bind", "function"));
        list.add(c("函数性", "apply 就是 Rhino 原生实现", "String(java.lang.Math.max.apply === Function.prototype.apply)", "true"));
        list.add(c("函数性", "bind 绑定后可直接调用", "String(java.lang.Math.max.bind(null, 3)(7))", "7"));
        list.add(c("函数性", "对照：普通 JS 函数", "String((function(a, b){ return a + b; }).apply(null, [1, 2]))", "3"));
        list.add(c("函数性", "普通 Java 对象的实例方法仍是函数", "typeof java.lang.System.out.println", "function"));
        // 实例方法（Rhino 自带 NativeJavaMethod 路径）：task-53 曾误报为缺口 G1，task-58 双机实证后更正
        list.add(c("函数性", "实例方法有 apply（Rhino JavaMembers 会 setPrototype）", "typeof java.lang.System.out.println.apply", "function"));
        list.add(c("函数性", "实例方法的 apply 就是 Rhino 原生实现", "String(java.lang.System.out.println.apply === Function.prototype.apply)", "true"));
        list.add(c("函数性", "实例方法包装的原型链指向 Function.prototype", "String(java.lang.System.out.println.__proto__ === Function.prototype)", "true"));

        // ---- apply/call 的实参数组形态 ----
        list.add(c("apply 实参", "JS 数组", "String(java.lang.Math.max.apply(null, [3, 7]))", "7"));
        list.add(c("apply 实参", "arguments 对象", "function f(){ return java.lang.Math.max.apply(null, arguments); } String(f(3, 7))", "7"));
        list.add(c("apply 实参", "Java 数组(NativeJavaArray)", "var a = java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 2); java.lang.reflect.Array.set(a, 0, 3); java.lang.reflect.Array.set(a, 1, 7); String(java.lang.Math.max.apply(null, a))", "7"));
        list.add(c("apply 实参", "null → 0 个参数", "typeof java.lang.System.currentTimeMillis.apply(null, null)", "number"));
        list.add(c("apply 实参", "空数组 → 0 个参数", "typeof java.lang.System.currentTimeMillis.apply(null, [])", "number"));
        list.add(c("apply 实参", "thisArg 为 undefined 不报错", "String(java.lang.Math.max.apply(undefined, [3, 7]))", "7"));
        list.add(c("apply 实参", "call 直传", "String(java.lang.Math.max.call(null, 3, 7))", "7"));
        list.add(c("apply 实参", "call 的 thisArg 为 undefined", "String(java.lang.Math.max.call(undefined, 3, 7))", "7"));
        list.add(c("apply 实参", "Array.newInstance 走 apply", "String(java.lang.reflect.Array.newInstance.apply(null, [java.lang.Integer.TYPE, 3]).length)", "3"));
        // 实例方法的 apply/call/bind：thisArg 是接收对象，必须真的作用在它身上
        list.add(withJava("apply 实参", "实例方法 apply（list.size）", "String(list.size.apply(list, []))", "1"));
        list.add(withJava("apply 实参", "实例方法 call（list.size）", "String(list.size.call(list))", "1"));
        list.add(withJava("apply 实参", "实例方法 bind（list.size）", "String(list.size.bind(list)())", "1"));
        list.add(withJava("apply 实参", "实例方法 apply 真的改到了对象", "list.add.apply(list, ['x']); String(list.size())", "2"));

        // ---- varargs：Method.invoke(target, argArray) 的展开（task-51 真机 bug） ----
        list.add(c("varargs", "直接调用多参（基线）", "String(java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 3).length)", "3"));
        list.add(withJava("varargs", "invoke 直传多参", "String(M.invoke(null, 3, 7))", "7"));
        list.add(withJava("varargs", "invoke + JS 数组 → 展开", "String(M.invoke(null, [3, 7]))", "7"));
        list.add(withJava("varargs", "invoke.apply + JS 数组 → 展开", "String(M.invoke.apply(null, [null, [3, 7]]))", "7"));
        list.add(withJava("varargs", "invoke + Java Object[] → 展开", "var a = java.lang.reflect.Array.newInstance(java.lang.Object, 2); java.lang.reflect.Array.set(a, 0, 3); java.lang.reflect.Array.set(a, 1, 7); String(M.invoke(null, a))", "7"));
        list.add(throwsWithJava("varargs", "边界：null 不展开（仍 1 个参数）", "M.invoke(null, null)", "需要 2 个"));
        list.add(throwsWithJava("varargs", "边界：原始类型数组不展开（javac 同样不展开）", "var b = java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 2); M.invoke(null, b)", "需要 2 个"));
        list.add(throwsWithJava("varargs", "边界：空 JS 数组 → 0 个参数", "M.invoke(null, [])", "需要 2 个"));
        list.add(throwsWithJava("varargs", "边界：目标形参是 Object[] 时直接传 → 个数不匹配", "var i = java.lang.reflect.Array.newInstance(java.lang.Object, 2); ML.invoke(null, i)", "需要 1 个"));
        list.add(withJava("varargs", "目标形参是 Object[] 时要包一层", "var i = java.lang.reflect.Array.newInstance(java.lang.Object, 2); String(ML.invoke(null, [i]))", "2"));
        list.add(withJava("varargs", "变参目标：直传多参", "String(MS.invoke(null, 1, 2, 3))", "6"));
        list.add(withJava("varargs", "变参目标：显式数组", "String(MS.invoke(null, [1, 2, 3]))", "6"));

        // ---- Class / typeof / in（含 dev-1383 的 Class 实例 API 回归） ----
        list.add(c("Class", "Class.forName + getName", "String(java.lang.Class.forName('java.lang.String').getName())", "java.lang.String"));
        list.add(c("Class", "getClass().getName()", "String(new java.util.ArrayList().getClass().getName())", "java.util.ArrayList"));
        list.add(c("Class", "getClass().getSimpleName()", "String(new java.util.ArrayList().getClass().getSimpleName())", "ArrayList"));
        list.add(c("Class", "instanceof Java 类", "String(new java.util.ArrayList() instanceof java.lang.Object)", "true"));
        list.add(c("Class", "'name' in 类（静态成员）", "String('valueOf' in java.lang.String)", "true"));
        list.add(c("Class", "'name' in 类（Class 实例 API）", "String('getName' in java.lang.String)", "true"));
        list.add(c("Class", "静态字段", "String(java.lang.Integer.MAX_VALUE)", "2147483647"));
        list.add(c("Class", ".class 返回真 Class", "String(java.lang.String.class.getName())", "java.lang.String"));
        list.add(c("Class", "Class 实例 API 走 apply（getName）", "String(java.lang.String.getName.apply(java.lang.String))", "java.lang.String"));
        list.add(c("Class", "new + 链式调用", "String(new java.lang.StringBuilder('ab').append('c').toString())", "abc"));
        list.add(c("Class", "普通 JS 对象 typeof", "typeof ({})", "object"));

        // ---- 数组 ----
        list.add(c("数组", "newInstance + set/get", "var a = java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 1); java.lang.reflect.Array.set(a, 0, 5); String(java.lang.reflect.Array.get(a, 0))", "5"));
        list.add(c("数组", "NativeJavaArray.length", "String(java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 4).length)", "4"));
        list.add(throwsWith("数组", "越界报错", "var a = java.lang.reflect.Array.newInstance(java.lang.Integer.TYPE, 2); java.lang.reflect.Array.get(a, 5)", "ArrayIndexOutOfBounds"));
        list.add(pre("数组", "util.java.array('int', 3)", "String(util.java.array('int', 3).length)", "3"));
        list.add(pre("数组", "util.java.array(Boolean.TYPE, 2)", "String(util.java.array(java.lang.Boolean.TYPE, 2).length)", "2"));
        list.add(pre("数组", "util.java.array('int') 组件类型正确", "var b = util.java.array('int', 2); java.lang.reflect.Array.set(b, 0, 3); String(java.lang.reflect.Array.get(b, 0))", "3"));
        list.add(pre("数组", "util.java.array('string', 2)", "String(util.java.array('string', 2).length)", "2"));
        list.add(pre("数组", "util.java.array('double', 2)", "String(util.java.array('double', 2).length)", "2"));

        // ---- 重载与收窄 ----
        list.add(withJava("重载", "静态重载：整数优先 int", "String(T.overloaded(5))", "int:5"));
        list.add(withJava("重载", "静态重载：小数走 double", "String(T.overloaded(1.5))", "double:1.5"));
        list.add(withJava("重载", "静态重载：字符串走 String", "String(T.overloaded('x'))", "string:x"));
        list.add(withJava("重载", "PreferInt：直接调用命中 int", "t.setValue(5); String(T.lastCall)", "instance-int:5"));
        list.add(withJava("重载", "PreferInt：既有截断语义（1.9 → int 1）", "t.setValue(1.9); String(T.lastCall)", "instance-int:1"));
        list.add(withJava("重载", "PreferInt：apply 命中 int", "t.setValue.apply(t, [5]); String(T.lastCall)", "instance-int:5"));
        list.add(withJava("重载", "PreferInt：call 命中 int", "t.setValue.call(t, 5); String(T.lastCall)", "instance-int:5"));
        list.add(throwsWithJava("重载", "反射收窄：越界不静默截断", "M.invoke(null, 2147483648, 7)", "超出 int 范围"));

        // ---- toString / 默认值：过去会抛误导性 TypeError 的路径（task-52 的现象） ----
        list.add(noThrow("toString", "String(静态方法包装)", "String(java.lang.Math.max)"));
        list.add(noThrow("toString", "字符串拼接", "'' + java.lang.Math.max"));
        list.add(c("toString", "拼接结果是字符串", "typeof ('' + java.lang.Math.max)", "string"));
        list.add(c("toString", "拼接结果非空", "String(('' + java.lang.Math.max).length > 0)", "true"));
        list.add(noThrow("toString", "String(Array.newInstance)", "String(java.lang.reflect.Array.newInstance)"));
        list.add(noThrow("toString", "String(apply 本身)", "String(java.lang.Math.max.apply)"));
        list.add(c("toString", "String(Java 对象实例的 toString())", "String(new java.util.ArrayList().toString())", "[]"));

        // ---- Packages / java.* 访问 ----
        list.add(c("Packages", "Packages.java.lang.String 静态方法", "String(Packages.java.lang.String.valueOf(true))", "true"));
        list.add(c("Packages", "java.lang.String 静态方法 + 整数优先", "String(java.lang.String.valueOf(123))", "123"));
        list.add(c("Packages", "Packages.java.lang.String.class", "String(Packages.java.lang.String.class.getName())", "java.lang.String"));
        list.add(c("Packages", "java.lang.System 静态方法", "String(java.lang.System.currentTimeMillis() > 0)", "true"));
        list.add(c("Packages", "new java.util.ArrayList()", "String(new java.util.ArrayList().size())", "0"));

        return list;
    }

    private static final List<Case> CASES = buildCases();

    // ------------------------------------------------------------------ 表驱动执行

    @Test
    public void tableDrivenSemanticsRegressionGroup() {
        List<String> failures = new ArrayList<>();
        Map<String, Integer> perGroup = new LinkedHashMap<>();
        for (Case testCase : CASES) {
            perGroup.merge(testCase.group, 1, Integer::sum);
            try {
                String actual = evaluate(testCase);
                if (testCase.expectThrowContains != null) {
                    failures.add(testCase.group + " / " + testCase.name + "：期望抛异常（含「"
                            + testCase.expectThrowContains + "」），实际返回「" + actual + "」");
                } else if (testCase.expected != null && !testCase.expected.equals(actual)) {
                    failures.add(testCase.group + " / " + testCase.name + "：期望「" + testCase.expected
                            + "」实际「" + actual + "」\n      脚本：" + testCase.script);
                }
            } catch (Throwable error) {
                String detail = describe(error);
                if (testCase.expectThrowContains == null) {
                    failures.add(testCase.group + " / " + testCase.name + "：意外异常 " + detail
                            + "\n      脚本：" + testCase.script);
                } else if (!detail.contains(testCase.expectThrowContains)) {
                    failures.add(testCase.group + " / " + testCase.name + "：异常消息不含「"
                            + testCase.expectThrowContains + "」：" + detail);
                }
            }
        }
        if (!failures.isEmpty()) {
            fail("Rhino/JS 语义回归组失败 " + failures.size() + " / " + CASES.size() + " 条（分组 " + perGroup + "）：\n  - "
                    + String.join("\n  - ", failures));
        }
    }

    private static String evaluate(Case testCase) {
        return evaluate(testCase.preface == null ? testCase.script : testCase.preface + "\n" + testCase.script,
                testCase.setup);
    }

    private static String evaluate(String source, Consumer<Scriptable> setup) {
        Context cx = FACTORY.enterContext();
        try {
            Scriptable scope = cx.initStandardObjects();
            if (setup != null) setup.accept(scope);
            Object result = cx.evaluateString(scope, source, "semantics", 1, null);
            return result == null ? "null" : Context.toString(result);
        } finally {
            Context.exit();
        }
    }

    /** 异常链的类名 + 消息（Rhino 会把 Java 异常包进 WrappedException，所以要连 cause 一起看）。 */
    private static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (text.length() > 0) text.append(" <- ");
            text.append(current.getClass().getName()).append(": ").append(current.getMessage());
            if (current.getCause() == current) break;
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ 包装清单（防漏）

    /**
     * 每个「脚本可见的包装函数」都必须能 apply/call/bind，而且用的是 Rhino 原生 Function.prototype
     * （apply 与 Function.prototype.apply 是同一个对象）—— task-52 的真机 bug 就是这条被破坏。
     * 新增包装类时，这里和 baseFunctionWrappersAreAllAudited 都要同步登记。
     */
    @Test
    public void scriptVisibleWrappersExposeApplyCallAndBind() {
        assertWrapperIsFunctionLike("静态方法包装（AijsJavaStaticMethod）", "java.lang.Math.max", null);
        assertWrapperIsFunctionLike("静态方法包装（真机入口）", "java.lang.reflect.Array.newInstance", null);
        assertWrapperIsFunctionLike("Packages 路径的静态方法", "Packages.java.lang.String.valueOf", null);
        assertWrapperIsFunctionLike("对照：普通 JS 函数", "(function(a, b){ return a + b; })", null);
        assertWrapperIsFunctionLike("PreferIntJavaObject 包装（Paint int/long）", "t.setValue", ScriptJavaSemanticsTest::javaProbe);
        assertWrapperIsFunctionLike("ReflectiveMethodObject 的 invoke", "M.invoke", ScriptJavaSemanticsTest::javaProbe);
        assertWrapperIsFunctionLike("ScriptJavaObject 的成员包装", "w.release", ScriptJavaSemanticsTest::javaProbe);
        // Rhino 自带的实例方法包装（JavaMembers 会 setPrototype）：task-53 曾误报为缺口，task-58 更正为覆盖
        assertWrapperIsFunctionLike("Rhino 原生实例方法包装", "java.lang.System.out.println", null);
        assertWrapperIsFunctionLike("Rhino 原生实例方法包装（注入对象）", "list.size", ScriptJavaSemanticsTest::javaProbe);
    }

    private static void assertWrapperIsFunctionLike(String label, String obtainer, Consumer<Scriptable> setup) {
        assertEquals(label + "：「" + obtainer + "」应当是函数", "function", evaluate("typeof (" + obtainer + ")", setup));
        assertEquals(label + "：应有 .apply", "function", evaluate("typeof (" + obtainer + ").apply", setup));
        assertEquals(label + "：应有 .call", "function", evaluate("typeof (" + obtainer + ").call", setup));
        assertEquals(label + "：应有 .bind", "function", evaluate("typeof (" + obtainer + ").bind", setup));
        assertEquals(label + "：.apply 应当是 Rhino 原生实现", "true",
                evaluate("String((" + obtainer + ").apply === Function.prototype.apply)", setup));
    }

    /**
     * 防漏机制：扫描 engine 主源里所有 BaseFunction 的构造/继承点，必须与审计表逐字一致。
     * 新增/改名包装类而不登记 → 本用例失败（提示同步三处：本清单、包装表、审计 md）。
     */
    @Test
    public void baseFunctionWrappersAreAllAudited() throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            paths.filter(Files::isRegularFile).forEach(files::add);
        }
        Set<String> found = new TreeSet<>();
        for (Path path : files) {
            String fileName = path.getFileName().toString();
            if (!fileName.endsWith(".java") && !fileName.endsWith(".kt")) continue;
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (text.contains("extends BaseFunction") || text.contains(": BaseFunction")
                    || text.contains("new BaseFunction(")) {
                found.add(fileName);
            }
        }
        Set<String> expected = new TreeSet<>(Arrays.asList(
                "AijsNativeJavaClass.kt",
                "PreferIntJavaObject.kt",
                "ReflectiveMethodObject.java",
                "NativeView.java",
                "ScriptJavaObject.java"));
        assertEquals("新增/改名 BaseFunction 包装类时必须同步三处：① baseFunctionWrappersAreAllAudited 清单"
                        + " ② scriptVisibleWrappersExposeApplyCallAndBind 包装表"
                        + " ③ docs/reports/2026-11-09_Rhino函数包装与JS桥语义审计.md 审计表",
                expected, found);
    }

}

