package com.jdkshen.aijspro.inrt.autojs;

import java.util.HashMap;
import java.util.Map;

/**
 * 同一物理按键可能同时从无障碍和 root getevent 两个来源到达。
 * 只抑制另一个来源在同一个按压窗口内的重复事件，不保留会误伤下一次按键的布尔状态。
 */
final class VolumeKeyDeduplicator {

    private static final long WINDOW_MS = 200L;

    private final Map<Integer, Stamp> last = new HashMap<>();

    private static final class Stamp {
        final String source;
        final long time;

        Stamp(String source, long time) {
            this.source = source;
            this.time = time;
        }
    }

    synchronized boolean accept(int key, String source, long now) {
        Stamp previous = last.get(key);
        if (previous != null && !previous.source.equals(source)
                && now >= previous.time && now - previous.time <= WINDOW_MS) {
            return false;
        }
        last.put(key, new Stamp(source, now));
        return true;
    }
}
