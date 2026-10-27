package org.autojs.source.engine.android;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;

public class Node24ResourceScopeTest {
    @Test public void cleanupIsOwnedByIdentityAndIdempotent() {
        Node24ResourceScope first = new Node24ResourceScope(), second = new Node24ResourceScope();
        AtomicInteger count = new AtomicInteger();
        Object a = new String("same"), b = new String("same");
        first.track(a, count::incrementAndGet); second.track(b, count::incrementAndGet);
        first.close(); first.close(); assertEquals(1, count.get());
        second.close(); assertEquals(2, count.get());
    }
    @Test public void explicitReleaseRemovesOnlyItsCleanup() {
        Node24ResourceScope scope = new Node24ResourceScope(); AtomicInteger count = new AtomicInteger();
        Object a = new Object(), b = new Object();
        scope.track(a, count::incrementAndGet); scope.track(b, count::incrementAndGet);
        scope.forget(a); scope.close(); assertEquals(1, count.get());
    }
    @Test public void failureDoesNotSkipOtherResources() {
        Node24ResourceScope scope = new Node24ResourceScope(); AtomicInteger count = new AtomicInteger();
        scope.track(new Object(), () -> { throw new IllegalStateException(); });
        scope.track(new Object(), count::incrementAndGet); scope.close(); assertEquals(1, count.get());
    }
    @Test public void lateRegistrationIsImmediatelyDisposed() {
        Node24ResourceScope scope = new Node24ResourceScope(); AtomicInteger count = new AtomicInteger();
        scope.close(); scope.track(new Object(), count::incrementAndGet); assertEquals(1, count.get());
    }
    @Test public void closeRacingWithRegistrationStillCleansExactlyOnce() throws Exception {
        for (int i = 0; i < 100; i++) {
            Node24ResourceScope scope = new Node24ResourceScope(); AtomicInteger count = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Thread worker = new Thread(() -> { try { start.await(); scope.track(new Object(), count::incrementAndGet); } catch (InterruptedException e) { throw new AssertionError(e); } });
            worker.start(); start.countDown(); scope.close(); worker.join(); scope.close(); assertEquals(1, count.get());
        }
    }
}
