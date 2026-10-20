package org.autojs.source.engine.android;

import java.util.concurrent.ConcurrentHashMap;

/** Runtime classes belong to a script owner, including when two scripts share a directory. */
public final class Node24GeneratedClasses {
    private static final ConcurrentHashMap<String, ClassLoader> LOADERS = new ConcurrentHashMap<>();
    private Node24GeneratedClasses() {}
    static void register(String owner, ClassLoader loader) { LOADERS.put(owner, loader); }
    public static ClassLoader loader(String owner, ClassLoader fallback) {
        ClassLoader loader = owner == null ? null : LOADERS.get(owner);
        return loader == null ? fallback : loader;
    }
    static void remove(String owner) { LOADERS.remove(owner); }
}
