package com.jdkshen.aijspro.autojs.key;

/** Suppress the other input source for one press, never retain a stale boolean indefinitely. */
final class VolumeKeyDeduplicator {
    private final java.util.Map<Integer, Stamp> last = new java.util.HashMap<>();
    private static final class Stamp {
        final String source; final long time;
        Stamp(String source, long time) { this.source = source; this.time = time; }
    }
    synchronized boolean accept(int key, String source, long now) {
        Stamp previous = last.get(key);
        if (previous != null && !previous.source.equals(source)
                && now >= previous.time && now - previous.time <= 200) return false;
        last.put(key, new Stamp(source, now));
        return true;
    }
}
