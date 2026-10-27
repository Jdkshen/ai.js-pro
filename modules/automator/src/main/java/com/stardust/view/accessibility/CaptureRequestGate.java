package com.stardust.view.accessibility;

/** One physical capture at a time. Completing UI waiting never pretends a Binder call exited. */
public final class CaptureRequestGate {
    public static final class Ticket {
        private final long id;
        private boolean completed;
        private Ticket(long id) { this.id = id; }
        public long getId() { return id; }
    }

    private long nextId;
    private Ticket worker;

    public synchronized Ticket begin() {
        if (worker != null) return null;
        worker = new Ticket(++nextId);
        return worker;
    }

    /** Success, timeout and cancellation race here; exactly one may publish. */
    public synchronized boolean complete(Ticket ticket) {
        if (ticket == null || worker != ticket || ticket.completed) return false;
        ticket.completed = true;
        return true;
    }

    public synchronized boolean isAwaiting(Ticket ticket) {
        return ticket != null && worker == ticket && !ticket.completed;
    }

    public synchronized boolean isWorkerBusy() { return worker != null; }

    /** Only the physical worker's finally block releases this slot. */
    public synchronized void workerExited(Ticket ticket) {
        if (worker == ticket) worker = null;
    }
}
