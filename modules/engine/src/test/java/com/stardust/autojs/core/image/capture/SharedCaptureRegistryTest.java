package com.stardust.autojs.core.image.capture;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SharedCaptureRegistryTest {
    private static final class Resource implements AutoCloseable {
        int closes;
        @Override public void close() { closes++; }
    }
    @Test public void ownerExitKeepsChildrenAliveAndLastChildReleasesOnce() {
        SharedCaptureRegistry<Resource> registry = new SharedCaptureRegistry<>();
        Resource resource = new Resource();
        SharedCaptureRegistry<Resource>.Lease owner = registry.publish(resource);
        String token = owner.token();
        SharedCaptureRegistry<Resource>.Lease first = registry.acquire(token), second = registry.acquire(token);
        owner.close(); owner.close(); first.close();
        assertEquals(0, resource.closes);
        assertSame(resource, second.use(value -> value));
        second.close(); second.close();
        assertEquals(1, resource.closes);
        assertEquals(0, registry.size());
        try { registry.acquire(token); fail("expired token accepted"); }
        catch (IllegalStateException expected) { }
    }
    @Test public void closedLeaseCannotCaptureEvenWithLiveSibling() {
        SharedCaptureRegistry<Resource> registry = new SharedCaptureRegistry<>();
        SharedCaptureRegistry<Resource>.Lease owner = registry.publish(new Resource());
        SharedCaptureRegistry<Resource>.Lease child = registry.acquire(owner.token());
        owner.close();
        try { owner.use(value -> value); fail("closed lease usable"); }
        catch (IllegalStateException expected) { }
        child.close();
    }
    @Test public void consumersSerializeFrameCopyAndCloseWaitsForCopy() throws Exception {
        SharedCaptureRegistry<Resource> registry = new SharedCaptureRegistry<>();
        Resource resource = new Resource();
        SharedCaptureRegistry<Resource>.Lease owner = registry.publish(resource), child = registry.acquire(owner.token());
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger childCalls = new AtomicInteger();
        Thread first = new Thread(() -> owner.use(value -> {
            entered.countDown();
            try { finish.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            assertEquals(0, value.closes);
            return null;
        }));
        first.start(); entered.await();
        Thread second = new Thread(() -> { child.use(value -> childCalls.incrementAndGet()); child.close(); });
        second.start();
        assertEquals(0, childCalls.get());
        finish.countDown(); first.join(2000); second.join(2000);
        assertFalse(first.isAlive()); assertFalse(second.isAlive());
        assertEquals(1, childCalls.get()); owner.close(); assertEquals(1, resource.closes);
    }
    @Test public void failedConsumerDoesNotRevokeOtherLeases() {
        SharedCaptureRegistry<Resource> registry = new SharedCaptureRegistry<>();
        SharedCaptureRegistry<Resource>.Lease owner = registry.publish(new Resource()), child = registry.acquire(owner.token());
        try { owner.use(value -> { throw new IllegalArgumentException("capture failed"); }); fail(); }
        catch (IllegalArgumentException expected) { }
        assertNotNull(child.use(value -> value));
        owner.close(); child.close();
    }
}
