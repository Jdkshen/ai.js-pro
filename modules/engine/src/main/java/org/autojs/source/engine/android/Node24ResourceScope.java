package org.autojs.source.engine.android;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Per-execution resources whose native lifetime exceeds their Java reference. */
final class Node24ResourceScope implements AutoCloseable {
    private final IdentityHashMap<Object, Runnable> actions = new IdentityHashMap<>();
    private boolean closed;

    void track(Object identity, Runnable cleanup) {
        if (identity == null || cleanup == null) throw new NullPointerException();
        synchronized (this) {
            if (!closed) { actions.put(identity, cleanup); return; }
        }
        run(cleanup); // A constructor/register call may finish after cancellation.
    }

    synchronized void forget(Object identity) { actions.remove(identity); }

    @Override public void close() {
        List<Runnable> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(actions.values());
            actions.clear();
        }
        for (Runnable action : pending) run(action);
    }

    private static void run(Runnable action) {
        try { action.run(); } catch (Throwable ignored) { /* Other resources still need cleanup. */ }
    }
}
