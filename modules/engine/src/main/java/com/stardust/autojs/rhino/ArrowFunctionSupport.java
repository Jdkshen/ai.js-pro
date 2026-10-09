package com.stardust.autojs.rhino;

import org.mozilla.javascript.ArrowFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * 箭头函数 → Java interface 的适配（Rhino fork 限制的补丁）。
 *
 * <p>Rhino fork（1.7.7.2）的 {@code NativeJavaObject.coerceTypeImpl} 在目标是 interface 时
 * 只认 {@code instanceof NativeFunction}：{@code function(){}} 的编译产物是 InterpretedFunction
 * （符合），而箭头函数的编译产物是 {@code ArrowFunction}（不符合）→ 直接把箭头函数传给
 * {@code setOnClickListener} 等会抛 {@code Cannot convert ... to ...}。
 *
 * <p>这里把箭头函数包成一个由 {@code compileFunction} 生成的转发函数（InterpretedFunction），
 * 使其与普通函数走完全相同的转换路径。
 */
public final class ArrowFunctionSupport {

    private static final Object ADAPTED_FUNCTION_KEY = new Object();

    private ArrowFunctionSupport() {
    }

    /** 若值是箭头函数，返回一个"同为 NativeFunction 的转发函数"，否则原样返回。 */
    public static Object adaptArgument(Context cx, Scriptable scope, Object value) {
        if (!(value instanceof ArrowFunction)) {
            return value;
        }
        if (cx == null) {
            cx = Context.getCurrentContext();
        }
        if (cx == null) {
            return value;
        }
        ScriptableObject arrow = (ScriptableObject) value;
        Object cached = arrow.getAssociatedValue(ADAPTED_FUNCTION_KEY);
        if (cached != null) {
            return cached;
        }
        NativeObject bag = new NativeObject();
        bag.setParentScope(arrow.getParentScope() != null ? arrow.getParentScope() : scope);
        ScriptableObject.putProperty(bag, "__fn", value);
        Function adapted = cx.compileFunction(bag,
                "function(){ return __fn.apply(this, arguments); }",
                "arrow-adapter", 1, null);
        // 与原箭头函数一起回收，并让 add/remove 使用同一个 Java interface proxy。
        return arrow.associateValue(ADAPTED_FUNCTION_KEY, adapted);
    }

    /** 把参数数组中所有箭头函数替换为适配后的 NativeFunction（无箭头时原样返回）。 */
    public static Object[] adaptArguments(Context cx, Scriptable scope, Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        boolean found = false;
        for (Object arg : args) {
            if (arg instanceof ArrowFunction) {
                found = true;
                break;
            }
        }
        if (!found) {
            return args;
        }
        Object[] adapted = args.clone();
        for (int i = 0; i < adapted.length; i++) {
            adapted[i] = adaptArgument(cx, scope, adapted[i]);
        }
        return adapted;
    }
}
