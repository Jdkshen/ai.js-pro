// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每次 Node job 独占的反射元数据缓存，供 {@link Node24HostBridge} 的 static/instance 调用使用。
 *
 * <p>只缓存“正向”元数据：成功解析的类，以及按“类 + 方法名 + static/instance 模式”筛出的
 * public 方法候选数组。缺失类、解析失败、空候选都不缓存，插件类暂时不可用时下次仍会重试。
 *
 * <p>候选数组完整保留旧实现的遍历顺序：先请求类、再逐级父类，每个类内保持
 * {@link Class#getMethods()} 顺序，且不去重（父类方法会在不同层级重复出现）。最终重载必须
 * 在每次调用时按当次参数的 null/Integer/Long... 重新 compatible/better 选取，所以这里绝不缓存
 * 已经选定的 {@link Method}。
 *
 * <p>每个 {@link Node24HostBridge} 持有独立实例，没有 static/global 缓存。容量有界：
 * 类名条目 {@value #DEFAULT_MAX_CLASS_ENTRIES} 个 + 方法候选条目
 * {@value #DEFAULT_MAX_METHOD_ENTRIES} 个 = 每 job 最多 512 条。{@link #close()} 清空两张表，
 * 避免任务结束后继续强引用插件 Class/ClassLoader。
 */
final class Node24ReflectionCache {

    static final int DEFAULT_MAX_CLASS_ENTRIES = 256;
    static final int DEFAULT_MAX_METHOD_ENTRIES = 256;

    private static final Method[] EMPTY_METHODS = new Method[0];

    private final LinkedHashMap<String, Class<?>> mClasses;
    private final LinkedHashMap<MethodKey, Method[]> mMethods;
    private boolean mClosed;

    Node24ReflectionCache() {
        this(DEFAULT_MAX_CLASS_ENTRIES, DEFAULT_MAX_METHOD_ENTRIES);
    }

    /** 测试/诊断可指定更小容量；生产使用无参构造的 256 + 256。 */
    Node24ReflectionCache(int maxClassEntries, int maxMethodEntries) {
        if (maxClassEntries <= 0) {
            throw new IllegalArgumentException("maxClassEntries must be > 0: " + maxClassEntries);
        }
        if (maxMethodEntries <= 0) {
            throw new IllegalArgumentException("maxMethodEntries must be > 0: " + maxMethodEntries);
        }
        final int classLimit = maxClassEntries;
        mClasses = new LinkedHashMap<String, Class<?>>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Class<?>> eldest) {
                return size() > classLimit;
            }
        };
        final int methodLimit = maxMethodEntries;
        mMethods = new LinkedHashMap<MethodKey, Method[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<MethodKey, Method[]> eldest) {
                return size() > methodLimit;
            }
        };
    }

    /**
     * 解析类名：成功结果按 trim 后的类名缓存；空名/缺失/异常都不写入缓存。
     *
     * <p>内部类点号写法（com.x.Outer.Inner）的回退逻辑与旧 findClass 完全一致。
     */
    synchronized Class<?> findClass(String name, ClassLoader loader) throws ClassNotFoundException {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new ClassNotFoundException("类名为空");
        }
        Class<?> cached = mClasses.get(clean);
        if (cached != null) {
            return cached;
        }
        Class<?> resolved = resolveClass(clean, name, loader);
        if (!mClosed) {
            mClasses.put(clean, resolved);
        }
        return resolved;
    }

    /**
     * 返回旧 findMethod 会遍历到的候选方法，顺序/重复项均保持原样。
     *
     * <p>调用方仍需按本次 args 逐个 compatible/better 选取；不要从这里缓存最终重载。
     */
    synchronized Method[] candidateMethods(Class<?> type, String name, boolean staticOnly) {
        if (type == null) {
            return EMPTY_METHODS;
        }
        MethodKey key = new MethodKey(type, name, staticOnly);
        Method[] cached = mMethods.get(key);
        if (cached != null) {
            return cached;
        }
        Method[] collected = collectCandidateMethods(type, name, staticOnly);
        if (collected.length == 0) {
            // 与方法同名但 static 模式不匹配也属于“缺失”，不缓存。
            return EMPTY_METHODS;
        }
        if (!mClosed) {
            mMethods.put(key, collected);
        }
        return collected;
    }

    /** close 后清空并禁止再写入；正在进行的调用仍会正常解析/返回候选，只是不再缓存。 */
    synchronized void close() {
        mClosed = true;
        mClasses.clear();
        mMethods.clear();
    }

    synchronized boolean isClosed() {
        return mClosed;
    }

    synchronized int classCacheSize() {
        return mClasses.size();
    }

    synchronized int methodCacheSize() {
        return mMethods.size();
    }

    private static Class<?> resolveClass(String clean, String requestedName, ClassLoader loader)
            throws ClassNotFoundException {
        try {
            return Class.forName(clean, false, loader);
        } catch (ClassNotFoundException ignored) {
            // Android/Java 内部类支持 com.x.Outer.Inner，也支持 com.x.Outer$Inner。
            int dot = clean.length();
            while ((dot = clean.lastIndexOf('.', dot - 1)) > 0) {
                String candidate = clean.substring(0, dot) + "$" + clean.substring(dot + 1);
                try {
                    return Class.forName(candidate, false, loader);
                } catch (ClassNotFoundException ignoredAgain) {
                    // 继续尝试更靠前的点
                }
            }
            throw new ClassNotFoundException("找不到 Java 类：" + requestedName
                    + "（请检查完整包名；Android 类不能省略包名）");
        }
    }

    private static Method[] collectCandidateMethods(Class<?> type, String name, boolean staticOnly) {
        ArrayList<Method> matches = new ArrayList<>();
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                if (staticOnly && !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (!staticOnly && Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                matches.add(method);
            }
            current = current.getSuperclass();
        }
        return matches.isEmpty() ? EMPTY_METHODS : matches.toArray(new Method[0]);
    }

    private static final class MethodKey {
        private final Class<?> mType;
        private final String mName;
        private final boolean mStaticOnly;

        MethodKey(Class<?> type, String name, boolean staticOnly) {
            mType = type;
            mName = name;
            mStaticOnly = staticOnly;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MethodKey)) {
                return false;
            }
            MethodKey key = (MethodKey) other;
            return mType == key.mType
                    && mStaticOnly == key.mStaticOnly
                    && (mName == null ? key.mName == null : mName.equals(key.mName));
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(mType);
            result = 31 * result + (mName == null ? 0 : mName.hashCode());
            result = 31 * result + (mStaticOnly ? 1 : 0);
            return result;
        }
    }
}
