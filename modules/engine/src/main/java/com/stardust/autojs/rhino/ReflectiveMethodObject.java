package com.stardust.autojs.rhino;

import com.stardust.autojs.engine.JavaReflectiveInvocation;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Wraps a {@link Method} so {@code method.invoke(target, value)} uses the target
 * method's declared parameter types. Rhino otherwise converts a JS number to
 * Double for the bridge signature {@code invoke(Object, Object...)}, and a target
 * {@code int} parameter then fails with "got java.lang.Double".
 *
 * <p>task-51：{@code method.invoke(target, argArray)} 还要遵守 Java varargs 语义——那个数组
 * <b>就是</b> {@code invoke(Object, Object...)} 的 varargs 数组，其元素才是目标方法的实参
 * （展开逻辑在共用的 {@link JavaReflectiveInvocation#expandInvokeArguments}）。Rhino 里脚本数组是
 * {@link NativeArray}（不是 Java 数组），Java 数组又常被包成 {@link Wrapper}，所以这里先把实参
 * 统一成 Java 值/Java 数组，再交给共用展开逻辑。
 */
public class ReflectiveMethodObject extends NativeJavaObject {

    private final Method mMethod;

    public ReflectiveMethodObject(Scriptable scope, Method method, Class<?> staticType) {
        super(scope, method, staticType);
        mMethod = method;
    }

    @Override
    public Object get(String name, Scriptable start) {
        if ("invoke".equals(name)) {
            // task-52：带 scope 与 Function.prototype —— m.invoke.apply(null, [target, args]) 是脚本常见写法。
            return new BaseFunction(start, ScriptFunctions.functionPrototype(start)) {
                @Override
                public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                    if (args == null || args.length == 0) {
                        throw Context.reportRuntimeError("Method.invoke 需要目标对象参数");
                    }
                    // target 也走同一套转换：脚本里 m.invoke(javaObj, ...) 传进来的常常是 Rhino 的
                    // Java 对象包装（Wrapper），Method.invoke 需要的是真对象，否则报「不是声明类的实例」。
                    Object target = toInvokeArg(args[0]);
                    Object[] invokeArgs = new Object[args.length - 1];
                    for (int i = 0; i < invokeArgs.length; i++) {
                        invokeArgs[i] = toInvokeArg(args[i + 1]);
                    }
                    try {
                        Object result = JavaReflectiveInvocation.invoke(mMethod, target, invokeArgs);
                        if (result == null) {
                            return Undefined.instance;
                        }
                        return Context.javaToJS(result, scope);
                    } catch (InvocationTargetException error) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        throw Context.throwAsScriptRuntimeEx(cause);
                    } catch (Throwable error) {
                        throw Context.throwAsScriptRuntimeEx(error);
                    }
                }
            };
        }
        return super.get(name, start);
    }

    /**
     * 反射桥的实参转换：在 {@link #toJava} 之外，把 Rhino 的 Java 数组包装（{@link Wrapper}）与
     * 脚本数组（{@link NativeArray}）统一成 Java {@code Object[]}。
     *
     * <p>为什么需要：{@code m.invoke(target, [3, 7])} 里的 {@code [3, 7]} 在 Rhino 里是 NativeArray，
     * 直接传给共用展开逻辑时既不是 {@code Object[]} 也不是数组（会被当成「1 个参数」），于是
     * {@code max(int,int)} 报「需要 2 个，实际 1 个」；转成 {@code Object[]} 后共用逻辑就能按
     * varargs 语义展开。只转一层：元素本身是脚本数组时不再递归（目标形参决定它要不要是数组；
     * 真需要嵌套数组时请用 Java 数组，例如 {@code java.lang.reflect.Array.newInstance}）。
     */
    private static Object toInvokeArg(Object value) {
        Object java = toJava(value);
        if (java instanceof Wrapper) {
            java = ((Wrapper) java).unwrap();
        }
        if (java instanceof NativeArray) {
            NativeArray array = (NativeArray) java;
            int length = (int) array.getLength();
            Object[] elements = new Object[length];
            for (int i = 0; i < length; i++) {
                Object element = array.get(i, array);
                elements[i] = element == Scriptable.NOT_FOUND ? null : toJava(element);
            }
            return elements;
        }
        return java;
    }

    private static Object toJava(Object value) {
        if (value == null || value == Undefined.instance) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String
                || value instanceof Character) {
            return value;
        }
        return Context.jsToJava(value, Object.class);
    }
}
