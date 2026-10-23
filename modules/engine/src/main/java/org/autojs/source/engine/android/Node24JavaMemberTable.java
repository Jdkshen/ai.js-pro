// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Method;

/** Stable, per-execution member IDs. Receivers/results are never cached here. */
final class Node24JavaMemberTable {
    static final int MAX_ENTRIES = 4096;
    static final class Member {
        final Class<?> type;
        final String name;
        final boolean isStatic;
        volatile Method zeroArgumentMethod;
        Member(Class<?> type, String name, boolean isStatic) {
            this.type = type; this.name = name; this.isStatic = isStatic;
        }
    }
    private final Map<Class<?>, Map<String, Integer>> classes = new HashMap<>();
    private final Map<Integer, Member> members = new HashMap<>();
    private final Map<Class<?>, Integer> types = new HashMap<>();
    private int nextId = 1;
    private boolean closed;

    synchronized int typeId(Class<?> type) {
        if (closed) return 0;
        Integer id = types.get(type);
        if (id != null) return id;
        if (types.size() >= MAX_ENTRIES) return 0;
        int result = types.size() + 1;
        types.put(type, result);
        return result;
    }

    synchronized int bind(Class<?> type, String name, boolean isStatic) {
        if (closed) return 0;
        String key = (isStatic ? "S:" : "I:") + name;
        Map<String, Integer> names = classes.get(type);
        Integer existing = names == null ? null : names.get(key);
        if (existing != null) return existing;
        if (members.size() >= MAX_ENTRIES) return 0;
        if (names == null) { names = new HashMap<>(); classes.put(type, names); }
        int id = nextId++;
        names.put(key, id);
        members.put(id, new Member(type, name, isStatic));
        return id;
    }

    synchronized Member get(int id) { return closed ? null : members.get(id); }
    synchronized void close() { closed = true; members.clear(); classes.clear(); types.clear(); }
}
