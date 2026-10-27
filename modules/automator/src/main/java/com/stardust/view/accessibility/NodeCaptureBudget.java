package com.stardust.view.accessibility;

import java.util.concurrent.TimeUnit;

/**
 * Cooperative timeout/cancellation and size budget for a live accessibility capture.
 *
 * <p>The budget is intentionally thread-light: one capture thread calls {@link #enter(int)} and
 * {@link #check()}, while {@link #cancel()} may be called from another thread. A budget can be
 * shared by several window captures so that the total work is bounded, not each window separately.
 *
 * <p>Checks happen between node operations. A single blocked Binder call cannot be interrupted by
 * this class, so the timeout bounds the traversal work but does not guarantee that one remote call
 * returns within the timeout.
 */
public final class NodeCaptureBudget {

    public static final long DEFAULT_TIMEOUT_MILLIS = 8000L;
    public static final int DEFAULT_MAX_NODES = 15000;
    public static final int DEFAULT_MAX_DEPTH = 256;

    private final long timeoutMillis;
    private final long timeoutNanos;
    private final int maxNodes;
    private final int maxDepth;
    private final NanoClock clock;
    private final long startNanos;

    private volatile boolean cancelled;
    private volatile int visitedCount;

    public NodeCaptureBudget(long timeoutMillis, int maxNodes, int maxDepth) {
        this(timeoutMillis, maxNodes, maxDepth, NanoClock.SYSTEM);
    }

    /** Test seam: inject a deterministic monotonic clock. */
    NodeCaptureBudget(long timeoutMillis, int maxNodes, int maxDepth, NanoClock clock) {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be > 0");
        }
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be > 0");
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.timeoutMillis = timeoutMillis;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.maxNodes = maxNodes;
        this.maxDepth = maxDepth;
        this.clock = clock;
        this.startNanos = clock.nanoTime();
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * Throws when the capture was cancelled or the deadline has been reached.
     *
     * <p>The deadline is inclusive: a check at exactly {@code timeoutMillis} reports TIMEOUT.
     */
    public void check() {
        if (cancelled) {
            throw new CaptureLimitException("CANCELLED",
                    "CANCELLED: node capture was cancelled");
        }
        long elapsedNanos = clock.nanoTime() - startNanos;
        if (elapsedNanos >= timeoutNanos) {
            throw new CaptureLimitException("TIMEOUT",
                    "TIMEOUT: node capture exceeded " + timeoutMillis + " ms");
        }
    }

    /**
     * Accounts for one unique node at {@code depth} (the root is depth 0) and enforces the limits.
     * The count is incremented only after all checks pass.
     */
    public void enter(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0");
        }
        check();
        if (depth > maxDepth) {
            throw new CaptureLimitException("DEPTH_LIMIT",
                    "DEPTH_LIMIT: node capture exceeded maximum depth " + maxDepth
                            + " (depth=" + depth + ")");
        }
        if (visitedCount >= maxNodes) {
            throw new CaptureLimitException("NODE_LIMIT",
                    "NODE_LIMIT: node capture exceeded " + maxNodes + " nodes");
        }
        visitedCount++;
    }

    public int getVisitedCount() {
        return visitedCount;
    }

    /**
     * Preflights a queue expansion before an untrusted {@code childCount} is iterated. It keeps
     * visited nodes, already queued nodes and all declared children inside the global node budget,
     * so a corrupt or huge {@code childCount} cannot grow the queue without bound.
     */
    public void checkChildCapacity(int pendingNodes, int childCount) {
        if (pendingNodes < 0) {
            throw new IllegalArgumentException("pendingNodes must be >= 0");
        }
        if (childCount < 0) {
            throw new IllegalArgumentException("childCount must be >= 0");
        }
        check();
        long planned = (long) visitedCount + pendingNodes + childCount;
        if (planned > maxNodes) {
            throw new CaptureLimitException("NODE_LIMIT",
                    "NODE_LIMIT: node capture exceeded " + maxNodes + " nodes (visited="
                            + visitedCount + ", pending=" + pendingNodes
                            + ", childCount=" + childCount + ")");
        }
    }

    /** Monotonic nanosecond source, injectable for tests. */
    interface NanoClock {
        long nanoTime();

        NanoClock SYSTEM = System::nanoTime;
    }

    /** A hard budget stop; callers must not treat this as a disappearing-node failure. */
    public static final class CaptureLimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String limitCode;

        CaptureLimitException(String limitCode, String message) {
            super(message);
            this.limitCode = limitCode;
        }

        /** One of TIMEOUT, CANCELLED, NODE_LIMIT or DEPTH_LIMIT. */
        public String getLimitCode() {
            return limitCode;
        }
    }
}
