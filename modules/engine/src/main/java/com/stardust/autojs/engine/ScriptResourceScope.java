package com.stardust.autojs.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Resources owned by one execution, released once in reverse acquisition order. */
public final class ScriptResourceScope implements AutoCloseable {
    private final IdentityHashMap<Object, Entry> identities = new IdentityHashMap<>();
    private final List<Entry> order = new ArrayList<>();
    private boolean closed;
    private Throwable failure;
    private int failureCount;

    public void track(Object identity, Runnable cleanup) {
        if (identity == null || cleanup == null) throw new NullPointerException();
        synchronized (this) {
            if (!closed) {
                Entry previous = identities.remove(identity);
                if (previous != null) order.remove(previous);
                Entry entry = new Entry(cleanup);
                identities.put(identity, entry);
                order.add(entry);
                return;
            }
        }
        // A native constructor or registration can finish while cancellation is in progress.
        release(cleanup);
    }

    public synchronized void forget(Object identity) {
        Entry previous = identities.remove(identity);
        if (previous != null) order.remove(previous);
    }

    public synchronized boolean isClosed() { return closed; }
    public synchronized int failureCount() { return failureCount; }
    public synchronized Throwable firstFailure() { return failure; }

    @Override public void close() {
        List<Entry> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(order);
            order.clear();
            identities.clear();
        }
        for (int i = pending.size() - 1; i >= 0; i--) release(pending.get(i).cleanup);
    }

    private void release(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable error) {
            synchronized (this) {
                failureCount++;
                if (failure == null) failure = error;
                else if (failure != error && failureCount <= 16) failure.addSuppressed(error);
            }
        }
    }

    private static final class Entry {
        final Runnable cleanup;
        Entry(Runnable cleanup) { this.cleanup = cleanup; }
    }
}
