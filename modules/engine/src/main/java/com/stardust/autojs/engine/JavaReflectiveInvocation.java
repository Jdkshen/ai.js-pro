package com.stardust.autojs.engine;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Shared type-aware conversion for {@code java.lang.reflect.Method.invoke}.
 *
 * <p>Reflection's own signature is {@code invoke(Object, Object...)}, so the normal
 * JS-to-Java bridge sees {@code int}/{@code boolean} only as {@code Object} and
 * boxes a JS number as Double. That makes {@code method.invoke(null, 5)} fail with
 * "argument 1 has type int, got java.lang.Double". This helper converts the
 * arguments using the <b>target method's</b> declared parameter types instead.
 *
 * <p>Rules (same for Rhino/Node/QuickJS):
 * - primitive/wrapper numeric targets are narrowed with range checks (no silent truncation);
 * - boolean accepts Boolean; char accepts Character/single-char String/integral Number;
 * - Object/Number/generic targets keep the original value;
 * - varargs are packed according to the target method.
 */
public final class JavaReflectiveInvocation {
    private JavaReflectiveInvocation() {}

    public static Object invoke(Method method, Object target, Object[] rawArgs)
            throws InvocationTargetException, IllegalAccessException {
        // Method.invoke 的第二个形参是 Object[]：脚本写 m.invoke(target, argArray) 时，按 Java varargs
        // 语义这个 argArray **就是** varargs 数组本身，必须先展开成目标方法的实参列表（见 expandInvokeArguments）。
        // 三引擎（Rhino/QuickJS/Node24）的反射桥都只走这一个入口，所以展开点放在这里，一处修三处正确。
        return method.invoke(target, coerceArguments(method, expandInvokeArguments(rawArgs)));
    }

    /**
     * {@code Method.invoke(Object target, Object... args)} 的 Java varargs 语义：**单个 Object[] 实参要展开**。
     *
     * <p><b>为什么单个数组必须展开</b>：{@code Method.invoke} 的第二个形参是 {@code Object[]}，javac 对
     * {@code m.invoke(target, argArray)} 的处理是「这个数组就是 varargs 数组」（等价于
     * {@code m.invoke(target, (Object[]) argArray)}），它的<b>元素</b>才是目标方法的实参。三引擎的反射桥都
     * 按「脚本实参逐个装箱」构建参数列表（Rhino {@code ReflectiveMethodObject}、QuickJS
     * {@code QuickJsJavaInterop.callReflective}、Node24 {@code Node24HostBridge.invokeMethod}），于是
     * {@code Object[]{3, 7}} 变成「1 个参数」，{@code max(int,int)} 直接报「Java 反射参数个数不匹配：
     * max 需要 2 个，实际 1 个」——脚本里最经典的 {@code getMethod(...).invoke(target, argArray)} 全废。
     *
     * <p>规则（与 javac 对该调用点的实参处理一致）：
     * <ul>
     * <li>参数列表恰好 1 个元素、且该元素是 {@code Object[]}（含 {@code String[]} 等引用类型数组）→ 展开为它的元素；</li>
     * <li>{@code null} 不是数组：{@code m.invoke(target, null)} 是「一个 null 实参」，长度保持 1；</li>
     * <li>原始类型数组（{@code int[]}/{@code long[]}…）不是 {@code Object[]}：保持「1 个参数」
     *     （javac 也不会把它当 varargs 数组）；</li>
     * <li>0 个实参 / 多个实参 / 单个非数组 → 原样返回。</li>
     * </ul>
     *
     * <p>想把「一个 Object[] 参数」传给形参本身就是 {@code Object[]} 的目标方法时，脚本要包一层：
     * {@code m.invoke(target, [theArray])}——外层数组才是 varargs 数组（Java 里同样要写
     * {@code m.invoke(target, (Object) theArray)}）。
     */
    public static Object[] expandInvokeArguments(Object[] reflectionArgs) {
        if (reflectionArgs == null) {
            return new Object[0];
        }
        if (reflectionArgs.length != 1) {
            return reflectionArgs;
        }
        Object single = reflectionArgs[0];
        if (!(single instanceof Object[])) {
            return reflectionArgs;
        }
        return (Object[]) single;
    }

    public static Object[] coerceArguments(Method method, Object[] rawArgs) {
        Object[] args = rawArgs == null ? new Object[0] : rawArgs;
        Class<?>[] types = method.getParameterTypes();
        if (!method.isVarArgs()) {
            if (args.length != types.length) {
                throw new IllegalArgumentException("Java 反射参数个数不匹配：" + method.getName()
                        + " 需要 " + types.length + " 个，实际 " + args.length + " 个");
            }
            Object[] converted = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                converted[i] = coerce(types[i], args[i], "#" + (i + 1));
            }
            return converted;
        }

        int fixed = types.length - 1;
        if (fixed < 0 || args.length < fixed) {
            throw new IllegalArgumentException("Java 反射参数太少：" + method.getName()
                    + " 至少需要 " + Math.max(0, fixed) + " 个");
        }
        Object[] converted = new Object[types.length];
        for (int i = 0; i < fixed; i++) {
            converted[i] = coerce(types[i], args[i], "#" + (i + 1));
        }
        Class<?> component = types[fixed].getComponentType();
        if (args.length == fixed + 1 && args[fixed] instanceof Object[]) {
            Object[] supplied = (Object[]) args[fixed];
            Object array = Array.newInstance(component, supplied.length);
            for (int i = 0; i < supplied.length; i++) {
                Array.set(array, i, coerce(component, supplied[i], "#" + (fixed + 1) + "[" + i + "]"));
            }
            converted[fixed] = array;
        } else {
            int extra = args.length - fixed;
            Object array = Array.newInstance(component, extra);
            for (int i = 0; i < extra; i++) {
                Array.set(array, i, coerce(component, args[fixed + i], "#" + (fixed + 1) + "[" + i + "]"));
            }
            converted[fixed] = array;
        }
        return converted;
    }

    public static Object coerce(Class<?> target, Object value, String label) {
        if (target == Object.class || target == Number.class) {
            // Requirement: Object/Number/generic positions must not be narrowed.
            return value;
        }
        if (value == null) {
            if (target.isPrimitive()) {
                throw new IllegalArgumentException("Java 反射参数 " + label + " 不能为 null：目标为 "
                        + target.getName());
            }
            return null;
        }
        if (target == boolean.class || target == Boolean.class) {
            if (value instanceof Boolean) return value;
            throw typeError(target, value, label);
        }
        if (target == char.class || target == Character.class) {
            if (value instanceof Character) return value;
            if (value instanceof String && ((String) value).length() == 1) {
                return ((String) value).charAt(0);
            }
            if (value instanceof Number) {
                BigInteger integer = integralValue((Number) value, target, label);
                if (integer.signum() < 0 || integer.compareTo(BigInteger.valueOf(0xFFFF)) > 0) {
                    throw rangeError(target, value, label);
                }
                return (char) integer.intValue();
            }
            throw typeError(target, value, label);
        }
        if (isIntegralTarget(target)) {
            if (!(value instanceof Number)) throw typeError(target, value, label);
            BigInteger integer = integralValue((Number) value, target, label);
            BigInteger min;
            BigInteger max;
            if (target == byte.class || target == Byte.class) {
                min = BigInteger.valueOf(Byte.MIN_VALUE); max = BigInteger.valueOf(Byte.MAX_VALUE);
            } else if (target == short.class || target == Short.class) {
                min = BigInteger.valueOf(Short.MIN_VALUE); max = BigInteger.valueOf(Short.MAX_VALUE);
            } else if (target == int.class || target == Integer.class) {
                min = BigInteger.valueOf(Integer.MIN_VALUE); max = BigInteger.valueOf(Integer.MAX_VALUE);
            } else if (target == long.class || target == Long.class) {
                min = BigInteger.valueOf(Long.MIN_VALUE); max = BigInteger.valueOf(Long.MAX_VALUE);
            } else {
                throw new IllegalArgumentException("未知整数参数类型：" + target.getName());
            }
            if (integer.compareTo(min) < 0 || integer.compareTo(max) > 0) {
                throw rangeError(target, value, label);
            }
            if (target == byte.class || target == Byte.class) return (byte) integer.intValue();
            if (target == short.class || target == Short.class) return (short) integer.intValue();
            if (target == int.class || target == Integer.class) return integer.intValue();
            return integer.longValue();
        }
        if (target == float.class || target == Float.class) {
            if (!(value instanceof Number)) throw typeError(target, value, label);
            return ((Number) value).floatValue();
        }
        if (target == double.class || target == Double.class) {
            if (!(value instanceof Number)) throw typeError(target, value, label);
            return ((Number) value).doubleValue();
        }
        if (target.isArray()) {
            if (value instanceof Object[]) {
                Object[] supplied = (Object[]) value;
                Class<?> component = target.getComponentType();
                Object array = Array.newInstance(component, supplied.length);
                for (int i = 0; i < supplied.length; i++) {
                    Array.set(array, i, coerce(component, supplied[i], label + "[" + i + "]"));
                }
                return array;
            }
            if (target.isInstance(value)) return value;
            throw typeError(target, value, label);
        }
        if (target.isInstance(value)) return value;
        // Leave other reference types to reflection so existing semantics are preserved.
        return value;
    }

    /** #14b: java.lang.reflect.Array.set/get 的数组元素类型收窄。 */
    public static Object arraySet(Object array, Object rawIndex, Object value) {
        if (array == null || !array.getClass().isArray()) {
            throw new IllegalArgumentException("Array.set 的第一个参数必须是 Java 数组，实际："
                    + (array == null ? "null" : array.getClass().getName()));
        }
        int index = (Integer) coerce(int.class, rawIndex, "index");
        Class<?> component = array.getClass().getComponentType();
        Object converted;
        try {
            converted = coerce(component, value, "value");
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Array.set 元素类型不匹配：" + error.getMessage(), error);
        }
        Array.set(array, index, converted);
        return null;
    }

    public static Object arrayGet(Object array, Object rawIndex) {
        if (array == null || !array.getClass().isArray()) {
            throw new IllegalArgumentException("Array.get 的第一个参数必须是 Java 数组，实际："
                    + (array == null ? "null" : array.getClass().getName()));
        }
        int index = (Integer) coerce(int.class, rawIndex, "index");
        return Array.get(array, index);
    }

    private static boolean isIntegralTarget(Class<?> target) {
        return target == byte.class || target == Byte.class
                || target == short.class || target == Short.class
                || target == int.class || target == Integer.class
                || target == long.class || target == Long.class;
    }

    private static BigInteger integralValue(Number number, Class<?> target, String label) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(number.toString());
        } catch (RuntimeException error) {
            throw typeError(target, number, label);
        }
        try {
            return decimal.toBigIntegerExact();
        } catch (ArithmeticException notIntegral) {
            throw new IllegalArgumentException("Java 反射参数 " + label + " 不是整数，不能传给 "
                    + target.getName() + "：" + number + "（不静默截断）");
        }
    }

    private static IllegalArgumentException typeError(Class<?> target, Object value, String label) {
        return new IllegalArgumentException("Java 反射参数 " + label + " 类型不匹配：目标 "
                + target.getName() + "，实际 " + value.getClass().getName() + "（" + value + "）");
    }

    private static IllegalArgumentException rangeError(Class<?> target, Object value, String label) {
        return new IllegalArgumentException("Java 反射参数 " + label + " 超出 " + target.getName()
                + " 范围：" + value + "（不静默截断）");
    }
}
