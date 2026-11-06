package com.stardust.autojs.rhino;

import com.stardust.autojs.engine.JavaReflectiveInvocation;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Wraps a {@link Method} so {@code method.invoke(target, value)} uses the target
 * method's declared parameter types. Rhino otherwise converts a JS number to
 * Double for the bridge signature {@code invoke(Object, Object...)}, and a target
 * {@code int} parameter then fails with "got java.lang.Double".
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
            return new BaseFunction() {
                @Override
                public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                    if (args == null || args.length == 0) {
                        throw Context.reportRuntimeError("Method.invoke 需要目标对象参数");
                    }
                    Object target = toJava(args[0]);
                    Object[] invokeArgs = new Object[args.length - 1];
                    for (int i = 0; i < invokeArgs.length; i++) {
                        invokeArgs[i] = toJava(args[i + 1]);
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
