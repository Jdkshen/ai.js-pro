package com.stardust.autojs.core.graphics;

/** One pending immutable drawing request. Parsing belongs to the render thread. */
final class LatestOverlayFrame {
    static final class Request {
        final String shapes, stats;
        Request(String shapes, String stats) {
            this.shapes = shapes == null ? "[]" : shapes;
            this.stats = stats == null ? "" : stats;
        }
    }
    private Request pending;
    private boolean closed;

    synchronized boolean offer(String shapes, String stats) {
        if (closed) return false;
        pending = new Request(shapes, stats);
        return true;
    }
    synchronized Request take() {
        Request result = pending;
        pending = null;
        return result;
    }
    synchronized void close() { closed = true; pending = null; }
}
