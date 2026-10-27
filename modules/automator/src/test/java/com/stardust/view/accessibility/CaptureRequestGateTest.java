package com.stardust.view.accessibility;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CaptureRequestGateTest {
    @Test public void timeoutDoesNotAdmitMoreWorkersWhileBinderIsStuck() {
        CaptureRequestGate gate = new CaptureRequestGate();
        CaptureRequestGate.Ticket first = gate.begin();
        assertTrue(gate.complete(first)); // Timeout delivered to UI.
        assertTrue(gate.isWorkerBusy());
        assertFalse(gate.isAwaiting(first));
        for (int i = 0; i < 1000; i++) assertNull(gate.begin());
        assertFalse(gate.complete(first)); // Late native result cannot publish.
        gate.workerExited(first);
        CaptureRequestGate.Ticket retry = gate.begin();
        assertNotNull(retry);
        assertTrue(retry.getId() > first.getId());
    }

    @Test public void oldWorkerCannotReleaseOrCompleteANewRequest() {
        CaptureRequestGate gate = new CaptureRequestGate();
        CaptureRequestGate.Ticket old = gate.begin();
        assertTrue(gate.complete(old));
        gate.workerExited(old);
        CaptureRequestGate.Ticket current = gate.begin();
        gate.workerExited(old);
        assertFalse(gate.complete(old));
        assertTrue(gate.isAwaiting(current));
        assertNull(gate.begin());
        assertTrue(gate.complete(current));
    }

    @Test public void cancellationSuccessAndDeadlineHaveOnlyOneWinner() throws Exception {
        CaptureRequestGate gate = new CaptureRequestGate();
        CaptureRequestGate.Ticket ticket = gate.begin();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        Thread[] contenders = new Thread[3];
        for (int i = 0; i < contenders.length; i++) {
            contenders[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (gate.complete(ticket)) publications.incrementAndGet();
            });
            contenders[i].start();
        }
        start.countDown();
        for (Thread contender : contenders) contender.join(2000);
        for (Thread contender : contenders) assertFalse(contender.isAlive());
        assertEquals(1, publications.get());
        assertTrue(gate.isWorkerBusy());
    }

    @Test public void anInactiveOrNullTicketNeverPublishes() {
        CaptureRequestGate gate = new CaptureRequestGate();
        assertFalse(gate.complete(null));
        CaptureRequestGate.Ticket ticket = gate.begin();
        gate.workerExited(ticket);
        assertFalse(gate.complete(ticket));
    }
}
