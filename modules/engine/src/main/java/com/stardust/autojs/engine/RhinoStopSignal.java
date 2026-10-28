package com.stardust.autojs.engine;

/** Per-engine cancellation must never interrupt Android's shared UI thread. */
public final class RhinoStopSignal {
    private volatile boolean requested;
    public boolean isRequested() { return requested; }
    public void request(Thread scriptThread, Thread uiThread) {
        requested = true;
        if (scriptThread != null && scriptThread != uiThread) scriptThread.interrupt();
    }
}
