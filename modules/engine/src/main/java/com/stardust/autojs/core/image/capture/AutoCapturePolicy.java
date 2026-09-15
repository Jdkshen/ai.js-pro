package com.stardust.autojs.core.image.capture;

/** No frame-count heuristics: only a connected recorder observer may permit a consent prompt. */
public final class AutoCapturePolicy {
    public enum Recording { UNKNOWN, ACTIVE, IDLE }
    private Recording previous = Recording.UNKNOWN;
    private long idleSince = -1;
    private boolean pending;
    private boolean suppressed;

    public boolean update(Recording recording, long now, boolean compatibility) {
        if (recording == Recording.ACTIVE && previous != Recording.ACTIVE) suppressed = false;
        previous = recording;
        if (recording != Recording.IDLE) {
            idleSince = -1;
            return false;
        }
        if (idleSince < 0) idleSince = now;
        if (!compatibility || pending || suppressed || now - idleSince < 1500) return false;
        pending = true;
        return true;
    }

    public void complete(boolean restored) {
        pending = false;
        suppressed = !restored;
    }
}
