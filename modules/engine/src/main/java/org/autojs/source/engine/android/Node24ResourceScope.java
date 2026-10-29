package org.autojs.source.engine.android;

import com.stardust.autojs.engine.ScriptResourceScope;

/** Per-execution resources whose native lifetime exceeds their Java reference. */
final class Node24ResourceScope implements AutoCloseable {
    private final ScriptResourceScope resources = new ScriptResourceScope();

    void track(Object identity, Runnable cleanup) {
        resources.track(identity, cleanup);
    }

    void forget(Object identity) { resources.forget(identity); }

    int failureCount() { return resources.failureCount(); }
    Throwable firstFailure() { return resources.firstFailure(); }

    @Override public void close() {
        resources.close();
    }
}
