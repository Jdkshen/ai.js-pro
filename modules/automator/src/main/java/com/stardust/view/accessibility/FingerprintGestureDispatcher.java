package com.stardust.view.accessibility;

import java.util.LinkedHashSet;
import java.util.Set;

/** Shared across script engines and service reconnects. Backend operations must not block. */
public final class FingerprintGestureDispatcher {
    public interface Listener {
        void onFingerprintGesture(String direction);
        void onFingerprintGestureAvailabilityChanged(boolean available);
    }

    public interface Backend {
        void setListening(boolean listening);
    }

    private final Set<Listener> listeners = new LinkedHashSet<>();
    private Backend backend;
    private boolean available;

    public synchronized void attach(Backend next) {
        if (backend == next) return;
        if (backend != null) backend.setListening(false);
        backend = next;
        setAvailable(false);
        if (next != null && !listeners.isEmpty()) next.setListening(true);
    }

    public synchronized void detach(Backend source) {
        if (backend == source) attach(null);
    }

    public synchronized void addListener(Listener listener) {
        if (!listeners.add(listener)) return;
        listener.onFingerprintGestureAvailabilityChanged(available);
        if (listeners.size() == 1 && backend != null) backend.setListening(true);
    }

    public synchronized void removeListener(Listener listener) {
        if (!listeners.remove(listener)) return;
        if (listeners.isEmpty()) {
            if (backend != null) backend.setListening(false);
            available = false;
        }
    }

    public synchronized boolean isAvailable() {
        return available;
    }

    public synchronized void availabilityChanged(Backend source, boolean value) {
        if (backend == source) setAvailable(value && !listeners.isEmpty());
    }

    private void setAvailable(boolean value) {
        if (available == value) return;
        available = value;
        for (Listener listener : listeners.toArray(new Listener[0])) {
            try {
                listener.onFingerprintGestureAvailabilityChanged(value);
            } catch (RuntimeException ignored) {
                // A failing script must not prevent delivery to other scripts.
            }
        }
    }

    public synchronized void gestureDetected(Backend source, int gestureId) {
        if (backend != source || !available) return;
        String direction = directionOf(gestureId);
        if (direction == null) return;
        for (Listener listener : listeners.toArray(new Listener[0])) {
            try {
                listener.onFingerprintGesture(direction);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** FingerprintGestureController IDs differ from touchscreen accessibility gesture IDs. */
    public static String directionOf(int gestureId) {
        switch (gestureId) {
            case 1: return "right";
            case 2: return "left";
            case 4: return "up";
            case 8: return "down";
            default: return null;
        }
    }
}
