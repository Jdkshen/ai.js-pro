package org.autojs.source.engine.android;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Matching and conversion share the same rules, including nested JS array arguments. */
final class Node24JavaArguments {
    interface Callback { Object asInterface(Class<?> type); }
    interface MethodDispatcher { Object invoke(Method method, Object[] args) throws Exception; }
    private Node24JavaArguments() {}

    static boolean compatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (type.isInterface()) {
            if (value instanceof Callback) return true;
            // A plain JS object literal whose own values are all Java callbacks can
            // implement the interface. Requiring every abstract interface method
            // keeps overload selection from mistaking an unrelated Map for a listener.
            if (isCallbackMapFor(type, value)) return true;
        }
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
        if (type.isInterface()) {
            if (value instanceof Callback) return ((Callback) value).asInterface(type);
            // Map parameters still receive the original map; only an interface
            // parameter whose methods are fully covered is adapted into a proxy.
            if (!type.isInstance(value) && isCallbackMapFor(type, value)) {
                return interfaceFromCallbacks(type, (Map<?, ?>) value);
            }
        }
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

    /**
     * JS object literal → interface conversion (wire {@code $map} whose values are
     * {@code $callback} handles). The strict abstract-method check is deliberate:
     * it lets overload resolution pick a listener only when the object really
     * provides the complete interface. The explicit {@code $jsInterface} marker
     * bypasses this check: it may wrap one whole-object callback (dispatched by
     * method name, so the object must implement those methods) or a method map
     * (missing methods fall back to Java defaults).
     */
    static boolean isCallbackMapFor(Class<?> type, Object value) {
        if (type == null || !type.isInterface() || !(value instanceof Map)) return false;
        Map<?, ?> methods = (Map<?, ?>) value;
        if (methods.isEmpty()) return false;
        try {
            Set<String> required = abstractMethodNames(type);
            if (required == null || required.isEmpty()) return false;
            for (String name : required) {
                if (!(methods.get(name) instanceof Callback)) return false;
            }
            return true;
        } catch (Throwable ignored) {
            // A malformed/unloadable interface must not break overload matching.
            return false;
        }
    }

    /** Build an interface proxy from methodName → {@link Callback}; missing methods use Java defaults. */
    static Object interfaceFromCallbacks(Class<?> type, Map<?, ?> methods) {
        return interfaceProxy(type, (method, args) -> {
            Object implementation = methods.get(method.getName());
            if (!(implementation instanceof Callback)) return defaultValue(method.getReturnType());
            Object target = ((Callback) implementation).asInterface(type);
            if (target == null || !type.isInstance(target)) return defaultValue(method.getReturnType());
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new RuntimeException(cause);
            }
        });
    }

    /** Shared dynamic-proxy construction so interface callbacks behave like Java objects. */
    static Object interfaceProxy(Class<?> type, MethodDispatcher dispatcher) {
        if (type == null || !type.isInterface()) {
            throw new IllegalArgumentException("Java 回调需要接口："
                    + (type == null ? "null" : type.getName()));
        }
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == (args == null ? null : args[0]);
                        if ("toString".equals(method.getName())) return "[NodeInterface " + type.getName() + "]";
                    }
                    return dispatcher.invoke(method, args);
                });
    }

    /** Java default value for a callback return type (primitive-safe). */
    static Object defaultValue(Class<?> type) {
        if (type == null || !type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        return coerce(type, 0);
    }

    /**
     * Resolve a nested class the way Java source does with {@code Owner.Nested}.
     * Nested Android resource classes are lowercase ({@code android.R.color},
     * {@code android.R.attr}), so they are not reachable through the normal
     * uppercase nested-class probe in the Node prelude.
     */
    static Class<?> nestedClass(Class<?> owner, String name) {
        if (owner == null || name == null || name.isEmpty() || name.indexOf('$') >= 0) return null;
        String candidate = owner.getName() + "$" + name;
        ClassLoader loader = owner.getClassLoader();
        try {
            return Class.forName(candidate, false, loader);
        } catch (ClassNotFoundException missing) {
            ClassLoader fallback = Node24JavaArguments.class.getClassLoader();
            if (fallback == null || fallback == loader) return null;
            try {
                return Class.forName(candidate, false, fallback);
            } catch (ClassNotFoundException | LinkageError ignored) {
                return null;
            }
        } catch (LinkageError ignored) {
            return null;
        }
    }

    /** Host-classloader load used by Class.forName bridging (P2-2). */
    static Class<?> loadClass(String name, ClassLoader loader, boolean initialize)
            throws ClassNotFoundException {
        return Class.forName(name, initialize, loader);
    }

    /** Initialize an already resolved class without changing its defining loader. */
    static Class<?> initializeClass(Class<?> type) throws ClassNotFoundException {
        if (type == null || type.isArray()) return type;
        if (type.isPrimitive()) {
            throw new ClassNotFoundException("Class.forName 不支持基本类型名：" + type.getName());
        }
        return loadClass(type.getName(), type.getClassLoader(), true);
    }

    /**
     * Names of abstract instance methods, or {@code null} when the interface has
     * default methods. A proxy for a map cannot run Java default bodies, so such
     * interfaces require the explicit callback marker instead of auto-adaptation.
     */
    private static Set<String> abstractMethodNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        return collectAbstractMethods(type, names, seen) ? names : null;
    }

    private static boolean collectAbstractMethods(Class<?> type, Set<String> names, Set<Class<?>> seen) {
        if (type == null || type == Object.class || !seen.add(type)) return true;
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (!Modifier.isAbstract(method.getModifiers())) return false;
            names.add(method.getName());
        }
        for (Class<?> parent : type.getInterfaces()) {
            if (!collectAbstractMethods(parent, names, seen)) return false;
        }
        return true;
    }
}
