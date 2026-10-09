package com.stardust.view.accessibility;

import java.util.List;

/** Chooses the foreground application instead of an inspector overlay or input method. */
public final class LayoutCaptureSelector {
    private LayoutCaptureSelector() {}

    public interface Readable<T> {
        boolean isReadable(T root);
    }

    public static final class Window<T> {
        public final T root;
        public final int id;
        public final boolean application, active, focused;
        public final int layer;

        public Window(T root, int id, boolean application, boolean active, boolean focused, int layer) {
            this.root = root;
            this.id = id;
            this.application = application;
            this.active = active;
            this.focused = focused;
            this.layer = layer;
        }
    }

    public static <T> T select(List<Window<T>> windows, T activeRoot, int activeId) {
        return select(windows, activeRoot, activeId, root -> root != null);
    }

    public static <T> T select(List<Window<T>> windows, T activeRoot, int activeId, Readable<T> readable) {
        Window<T> best = null;
        boolean activeWindowKnown = false;
        for (Window<T> window : windows) {
            if (window.id == activeId) activeWindowKnown = true;
            if (!window.application) continue;
            if (window.id == activeId && activeRoot != null && readable.isReadable(activeRoot)) return activeRoot;
            if (window.root == null || !readable.isReadable(window.root)) continue;
            if (best == null || compare(window, best) > 0) best = window;
        }
        if (best != null) return best.root;
        // Some ROMs do not expose getWindows(); an unknown active root is still usable.
        return !activeWindowKnown && activeRoot != null && readable.isReadable(activeRoot) ? activeRoot : null;
    }

    private static int compare(Window<?> a, Window<?> b) {
        if (a.active != b.active) return a.active ? 1 : -1;
        if (a.focused != b.focused) return a.focused ? 1 : -1;
        return Integer.compare(a.layer, b.layer);
    }
}
