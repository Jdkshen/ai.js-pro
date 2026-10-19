package org.autojs.source.engine.android;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 纯 JVM 回归：{@link Node24ReflectionCache} 的候选元数据必须与旧 findMethod 的遍历/选择语义
 * 完全一致，同时容量有界、close 后释放且不缓存缺失/失败。
 *
 * <p>这里不启动 Android，也不 mock Node；只针对真实 Java 重载（null、Integer/Long/Double、
 * static vs instance、父类覆盖/继承）比较“旧算法现场遍历”与“缓存候选数组 + 同一选择循环”。
 */
public class Node24ReflectionCacheTest {

    public static class Overloads {
        public static String pick(Integer value) {
            return "static-Integer";
        }

        public static String pick(Long value) {
            return "static-Long";
        }

        public static String pick(Object value) {
            return "static-Object";
        }

        public static String nullable(String value) {
            return "static-String";
        }

        public static String nullable(int value) {
            return "static-int";
        }

        public static String mode(Integer value) {
            return "static-mode-Integer";
        }

        public String mode(Long value) {
            return "instance-mode-Long";
        }

        public String choose(Integer value) {
            return "instance-Integer";
        }

        public String choose(Long value) {
            return "instance-Long";
        }

        public String choose(Object value) {
            return "instance-Object";
        }
    }

    public static class Parent {
        public String onlyParent(Object value) {
            return "parent-only";
        }

        public String overridden(Object value) {
            return "parent-overridden";
        }

        public String inherited(Object value) {
            return "parent-Object";
        }
    }

    public static class Child extends Parent {
        public String inherited(Integer value) {
            return "child-Integer";
        }

        @Override
        public String overridden(Object value) {
            return "child-overridden";
        }
    }

    @Test
    public void candidateOrderMatchesOldTraversalAndKeepsDuplicates() {
        Node24ReflectionCache cache = new Node24ReflectionCache();
        Method[] cached = cache.candidateMethods(Overloads.class, "pick", true);
        assertArrayEquals(directCandidates(Overloads.class, "pick", true), cached);
        assertSame(cached, cache.candidateMethods(Overloads.class, "pick", true));

        // Parent.onlyParent 既出现在 Child.getMethods()（继承），又出现在 Parent.getMethods()；
        // 旧遍历会保留两份，缓存不能去重，否则 tie 顺序可能变化。
        Method[] parentOnly = cache.candidateMethods(Child.class, "onlyParent", false);
        assertEquals(2, parentOnly.length);
        assertEquals(parentOnly[0], parentOnly[1]);

        // 子类覆盖方法在父类层级之前，平局时旧算法保留第一个（子类）。
        Method[] overridden = cache.candidateMethods(Child.class, "overridden", false);
        assertEquals(2, overridden.length);
        assertEquals(Child.class, overridden[0].getDeclaringClass());
        assertEquals(Parent.class, overridden[1].getDeclaringClass());
    }

    @Test
    public void oneCandidateArrayStillSelectsOverloadPerCall() throws Exception {
        Node24ReflectionCache cache = new Node24ReflectionCache();

        assertPicked(cache, "pick", new Object[]{Integer.valueOf(7)}, "static-Integer");
        assertPicked(cache, "pick", new Object[]{Long.valueOf(7)}, "static-Long");

        // Double 对 Integer/Long 重载都只是 Number 兼容（score 相同），最终选谁必须由旧算法
        // 的当次遍历决定；这里只要求与旧选择一致，不预设具体重载。
        assertSelectionSameAsOld(cache, Overloads.class, "pick",
                new Object[]{Double.valueOf(7.5d)}, true);
        assertSelectionSameAsOld(cache, Overloads.class, "pick", new Object[]{null}, true);

        // member 路径传 args=null：旧实现返回遍历到的第一个候选。
        assertSelectionSameAsOld(cache, Overloads.class, "pick", null, true);

        // 同一缓存里再跑一遍 Integer，证明没有残留上一次 Long 的选择。
        assertPicked(cache, "pick", new Object[]{Integer.valueOf(8)}, "static-Integer");
    }

    @Test
    public void nullAndStaticInstanceModesMatchOldSelection() {
        Node24ReflectionCache cache = new Node24ReflectionCache();

        Method nullable = assertSelectionSameAsOld(cache, Overloads.class, "nullable",
                new Object[]{null}, true);
        assertNotNull(nullable);
        assertEquals(String.class, nullable.getParameterTypes()[0]);
        assertFalse(nullable.getParameterTypes()[0].isPrimitive());

        Method staticMode = assertSelectionSameAsOld(cache, Overloads.class, "mode",
                new Object[]{Long.valueOf(5)}, true);
        assertNotNull(staticMode);
        assertTrue(Modifier.isStatic(staticMode.getModifiers()));
        assertEquals(Integer.class, staticMode.getParameterTypes()[0]);

        Method instanceMode = assertSelectionSameAsOld(cache, Overloads.class, "mode",
                new Object[]{Long.valueOf(5)}, false);
        assertNotNull(instanceMode);
        assertFalse(Modifier.isStatic(instanceMode.getModifiers()));
        assertEquals(Long.class, instanceMode.getParameterTypes()[0]);

        for (Method method : cache.candidateMethods(Overloads.class, "mode", true)) {
            assertTrue(Modifier.isStatic(method.getModifiers()));
        }
        for (Method method : cache.candidateMethods(Overloads.class, "mode", false)) {
            assertFalse(Modifier.isStatic(method.getModifiers()));
        }
    }

    @Test
    public void inheritanceOrderAndScoringMatchOldSelection() throws Exception {
        Node24ReflectionCache cache = new Node24ReflectionCache();

        Method child = assertSelectionSameAsOld(cache, Child.class, "inherited",
                new Object[]{Integer.valueOf(3)}, false);
        assertEquals(Child.class, child.getDeclaringClass());
        assertEquals("child-Integer", child.invoke(new Child(), Integer.valueOf(3)));

        Method parent = assertSelectionSameAsOld(cache, Child.class, "inherited",
                new Object[]{new Object()}, false);
        assertEquals(Parent.class, parent.getDeclaringClass());
        assertEquals("parent-Object", parent.invoke(new Child(), new Object()));

        Method override = assertSelectionSameAsOld(cache, Child.class, "overridden",
                new Object[]{new Object()}, false);
        assertEquals(Child.class, override.getDeclaringClass());
        assertEquals("child-overridden", override.invoke(new Child(), new Object()));
    }

    @Test
    public void positiveClassesAreCachedButMissingClassesAreNot() throws Exception {
        Node24ReflectionCache cache = new Node24ReflectionCache(8, 8);
        CountingLoader loader = new CountingLoader(Node24ReflectionCacheTest.class.getClassLoader());
        String dotted = Overloads.class.getName().replace('$', '.');

        // dotted 名不是真实二进制名：没有本缓存时每次都会先对 dotted 名调用一次失败的
        // loadClass，再回退到 $ 名；所以第二次 loadCount 不增长只能解释为缓存命中
        // （而不是 JVM 已经加载过 $ 类）。
        assertSame(Overloads.class, cache.findClass(dotted, loader));
        int afterSuccess = loader.loadCount;
        assertTrue(afterSuccess > 0);
        assertEquals(1, cache.classCacheSize());

        // 第二次命中缓存，不再走 Class.forName/ClassLoader。
        assertSame(Overloads.class, cache.findClass(dotted, loader));
        assertEquals(afterSuccess, loader.loadCount);

        String missing = "org.autojs.source.engine.android.NoSuchReflectionCacheFixture";
        try {
            cache.findClass(missing, loader);
            fail("missing class must throw");
        } catch (ClassNotFoundException expected) {
            assertTrue(expected.getMessage().contains("找不到 Java 类"));
        }
        int afterMissing = loader.loadCount;
        assertTrue(afterMissing > afterSuccess);
        assertEquals("missing class must not enter the cache", 1, cache.classCacheSize());

        // 失败不缓存：第二次仍然重新查找。
        try {
            cache.findClass(missing, loader);
            fail("missing class must throw again");
        } catch (ClassNotFoundException expected) {
            // expected
        }
        assertTrue(loader.loadCount > afterMissing);
        assertEquals(1, cache.classCacheSize());
    }

    @Test
    public void emptyMethodCandidatesAreNotCached() {
        Node24ReflectionCache cache = new Node24ReflectionCache(8, 8);
        Method[] missing = cache.candidateMethods(Overloads.class, "noSuchMethod", true);
        assertEquals(0, missing.length);
        assertEquals(0, cache.methodCacheSize());

        // 同名方法存在但 static 模式不匹配，也属于缺失，不缓存。
        Method[] wrongMode = cache.candidateMethods(Overloads.class, "choose", true);
        assertEquals(0, wrongMode.length);
        assertEquals(0, cache.methodCacheSize());
    }

    @Test
    public void cacheIsBoundedAndCloseReleasesEverything() throws Exception {
        assertEquals(512, Node24ReflectionCache.DEFAULT_MAX_CLASS_ENTRIES
                + Node24ReflectionCache.DEFAULT_MAX_METHOD_ENTRIES);

        Node24ReflectionCache cache = new Node24ReflectionCache(2, 2);
        ClassLoader loader = Node24ReflectionCacheTest.class.getClassLoader();
        assertNotNull(cache.findClass(Overloads.class.getName(), loader));
        assertNotNull(cache.findClass(Parent.class.getName(), loader));
        assertNotNull(cache.findClass(Child.class.getName(), loader));
        assertEquals("class cache must stay bounded", 2, cache.classCacheSize());

        assertTrue(cache.candidateMethods(Overloads.class, "pick", true).length > 0);
        assertTrue(cache.candidateMethods(Overloads.class, "choose", false).length > 0);
        assertTrue(cache.candidateMethods(Overloads.class, "mode", true).length > 0);
        assertEquals("method cache must stay bounded", 2, cache.methodCacheSize());

        cache.close();
        assertTrue(cache.isClosed());
        assertEquals(0, cache.classCacheSize());
        assertEquals(0, cache.methodCacheSize());

        // close 后仍能工作（在途调用），但不再缓存，避免任务结束后重新 pin 类。
        assertNotNull(cache.findClass(Overloads.class.getName(), loader));
        assertTrue(cache.candidateMethods(Overloads.class, "pick", true).length > 0);
        assertEquals(0, cache.classCacheSize());
        assertEquals(0, cache.methodCacheSize());
    }

    private static void assertPicked(Node24ReflectionCache cache, String name, Object[] args,
                                     String expected) throws Exception {
        Method selected = assertSelectionSameAsOld(cache, Overloads.class, name, args, true);
        assertNotNull(selected);
        assertTrue(Modifier.isStatic(selected.getModifiers()));
        assertEquals(expected, selected.invoke(null, args));
    }

    private static Method assertSelectionSameAsOld(Node24ReflectionCache cache, Class<?> type,
                                                   String name, Object[] args, boolean staticOnly) {
        Method expected = oldSelect(type, name, args, staticOnly);
        Method actual = cachedSelect(cache, type, name, args, staticOnly);
        assertEquals(expected, actual);
        return actual;
    }

    /** 旧 findMethod 的逐字语义（仅用于测试 oracle）。 */
    private static Method oldSelect(Class<?> type, String name, Object[] args, boolean staticOnly) {
        Method best = null;
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
                if (args == null) {
                    return method;
                }
                if (compatible(method.getParameterTypes(), args)) {
                    if (best == null || better(method.getParameterTypes(), best.getParameterTypes(), args)) {
                        best = method;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return best;
    }

    /** 生产 findMethod 的新形态：候选来自缓存，但选择循环逐字不变。 */
    private static Method cachedSelect(Node24ReflectionCache cache, Class<?> type, String name,
                                       Object[] args, boolean staticOnly) {
        Method best = null;
        for (Method method : cache.candidateMethods(type, name, staticOnly)) {
            if (args == null) {
                return method;
            }
            if (compatible(method.getParameterTypes(), args)) {
                if (best == null || better(method.getParameterTypes(), best.getParameterTypes(), args)) {
                    best = method;
                }
            }
        }
        return best;
    }

    private static Method[] directCandidates(Class<?> type, String name, boolean staticOnly) {
        List<Method> matches = new ArrayList<>();
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
        return matches.toArray(new Method[0]);
    }

    private static boolean compatible(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            if (!compatible(types[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatible(Class<?> type, Object value) {
        if (value == null) {
            return !type.isPrimitive();
        }
        Class<?> boxed = box(type);
        return boxed.isInstance(value) || (Number.class.isAssignableFrom(boxed) && value instanceof Number);
    }

    private static boolean better(Class<?>[] candidate, Class<?>[] current, Object[] args) {
        return score(candidate, args) < score(current, args);
    }

    private static int score(Class<?>[] types, Object[] args) {
        int score = 0;
        for (int i = 0; i < types.length; i++) {
            if (args[i] != null && args[i].getClass() == box(types[i])) {
                score += 0;
            } else {
                score += 1;
            }
        }
        return score;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static final class CountingLoader extends ClassLoader {
        int loadCount;

        CountingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            loadCount++;
            return super.loadClass(name, resolve);
        }
    }
}
