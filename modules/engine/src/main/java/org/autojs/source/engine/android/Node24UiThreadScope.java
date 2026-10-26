package org.autojs.source.engine.android;

/**
 * Synchronous Java calls in a Node UI callback belong on Android's main thread.
 * The scope belongs to the JNI host worker, not the whole session: explicitly
 * asynchronous Java work must keep running on its own executor.
 */
final class Node24UiThreadScope {
    private final ThreadLocal<Integer> depth = new ThreadLocal<>();

    boolean isActive() {
        Integer value = depth.get();
        return value != null && value > 0;
    }

    void enter() {
        Integer value = depth.get();
        depth.set(value == null ? 1 : value + 1);
    }

    void exit() {
        Integer value = depth.get();
        if (value == null || value == 0) {
            throw new IllegalStateException("Node UI thread scope is not active");
        }
        if (value == 1) depth.remove();
        else depth.set(value - 1);
    }
}
