package org.autojs.source.engine.android;

import java.lang.reflect.Array;

/** Matching and conversion share the same rules, including nested JS array arguments. */
final class Node24JavaArguments {
    interface Callback { Object asInterface(Class<?> type); }
    private Node24JavaArguments() {}

    static boolean compatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (type.isInterface() && value instanceof Callback) return true;
        if (type.isInstance(value)) return true;
        if (type.isArray() && value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                if (!compatible(type.getComponentType(), item)) return false;
            }
            return true;
        }
        if (type == char.class || type == Character.class) {
            return value instanceof Character || value instanceof Number
                    || (value instanceof String && ((String) value).length() == 1);
        }
        if (type == boolean.class) return value instanceof Boolean;
        return value instanceof Number && (type == byte.class || type == short.class
                || type == int.class || type == long.class || type == float.class
                || type == double.class || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class || type == Float.class || type == Double.class);
    }

    static Object coerce(Class<?> type, Object value) {
        if (value instanceof Callback && type.isInterface()) return ((Callback) value).asInterface(type);
        if (value == null || type.isInstance(value)) return value;
        if (type.isArray() && value instanceof Object[]) {
            Object[] source = (Object[]) value;
            Class<?> component = type.getComponentType();
            Object array = Array.newInstance(component, source.length);
            for (int i = 0; i < source.length; i++) Array.set(array, i, coerce(component, source[i]));
            return array;
        }
        if (value instanceof Number) {
            Number number = (Number) value;
            if (type == int.class || type == Integer.class) return number.intValue();
            if (type == long.class || type == Long.class) return number.longValue();
            if (type == double.class || type == Double.class) return number.doubleValue();
            if (type == float.class || type == Float.class) return number.floatValue();
            if (type == short.class || type == Short.class) return number.shortValue();
            if (type == byte.class || type == Byte.class) return number.byteValue();
            if (type == char.class || type == Character.class) return (char) number.intValue();
        }
        if ((type == char.class || type == Character.class) && value instanceof String
                && ((String) value).length() == 1) return ((String) value).charAt(0);
        return value;
    }
}
