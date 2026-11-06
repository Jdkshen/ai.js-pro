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
        return method.invoke(target, coerceArguments(method, rawArgs));
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
