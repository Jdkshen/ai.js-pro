package com.jdkshen.aijspro.external.foreground;

/** Commits notification fingerprints only after delivery; superseded UI work is discarded. */
final class NotificationDeliveryState {
    private String published;
    private String pending;
    private long revision;
    private long pendingRevision;

    synchronized long begin(String fingerprint) {
        if (fingerprint.equals(published)) {
            pending = null;
            pendingRevision = 0;
            return 0;
        }
        if (fingerprint.equals(pending)) return 0;
        pending = fingerprint;
        return pendingRevision = ++revision;
    }

    synchronized boolean isCurrent(long attempt) {
        return attempt != 0 && pendingRevision == attempt;
    }

    synchronized void succeeded(long attempt) {
        if (!isCurrent(attempt)) return;
        published = pending;
        pending = null;
        pendingRevision = 0;
    }

    synchronized void failed(long attempt) {
        if (!isCurrent(attempt)) return;
        pending = null;
        pendingRevision = 0;
    }

    synchronized void invalidate() {
        published = null;
        pending = null;
        pendingRevision = 0;
        ++revision;
    }
}
