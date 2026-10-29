package com.stardust.autojs.engine;

/** Per-execution lifecycle shared by the engine facade and isolated runtime jobs. */
public final class ScriptExecutionLifecycle {
    public enum Phase {
        CREATED, RUNNING, STOP_REQUESTED, CANCELLING, RELEASING, RELEASED
    }

    private Phase phase = Phase.CREATED;
    private int stopRequests;

    public synchronized Phase phase() { return phase; }
    public synchronized int stopRequests() { return stopRequests; }
    public synchronized boolean stopRequested() { return stopRequests != 0; }
    public synchronized boolean releasing() { return phase.ordinal() >= Phase.RELEASING.ordinal(); }

    public synchronized void running() { advance(Phase.RUNNING); }

    /** Repeated stop requests are escalation signals; they do not reopen a released job. */
    public synchronized int requestStop() {
        if (phase != Phase.RELEASED) {
            if (stopRequests < Integer.MAX_VALUE) stopRequests++;
            advance(Phase.STOP_REQUESTED);
        }
        return stopRequests;
    }

    public synchronized void cancelling() { advance(Phase.CANCELLING); }
    public synchronized void releasingResources() { advance(Phase.RELEASING); }
    public synchronized void released() { phase = Phase.RELEASED; }

    private void advance(Phase target) {
        if (phase.ordinal() < target.ordinal()) phase = target;
    }
}
