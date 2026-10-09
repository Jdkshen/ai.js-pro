package com.jdkshen.aijspro.ui.floating;

import java.util.HashSet;
import java.util.Set;

/** Temporary screenshot suppression is separate from the user's menu switch. */
final class FloatingMenuVisibility {
    interface Target {
        void setTemporarilyHidden(boolean hidden);
    }

    private final Set<Integer> leases = new HashSet<>();
    private int nextId;
    private Target target;

    void attach(Target menu) {
        target = menu;
        menu.setTemporarilyHidden(isSuppressed());
    }

    void detach(Target menu) {
        if (target == menu) target = null;
    }

    boolean isSuppressed() {
        return !leases.isEmpty();
    }

    int acquire() {
        do {
            if (++nextId <= 0) nextId = 1;
        } while (leases.contains(nextId));
        boolean first = leases.isEmpty();
        leases.add(nextId);
        try {
            if (first && target != null) target.setTemporarilyHidden(true);
        } catch (RuntimeException error) {
            leases.remove(nextId);
            throw error;
        }
        return nextId;
    }

    void release(int id) {
        if (!leases.contains(id)) return;
        if (leases.size() == 1 && target != null) target.setTemporarilyHidden(false);
        leases.remove(id);
    }
}
