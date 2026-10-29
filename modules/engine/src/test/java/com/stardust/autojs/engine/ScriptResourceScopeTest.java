package com.stardust.autojs.engine;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ScriptResourceScopeTest {
    @Test public void releasesInReverseOrderAndContinuesAfterFailure() {
        ScriptResourceScope scope=new ScriptResourceScope();
        List<Integer> released=new ArrayList<>();
        scope.track(new Object(),()->released.add(1));
        scope.track(new Object(),()-> { released.add(2); throw new IllegalStateException("expected"); });
        scope.track(new Object(),()->released.add(3));
        scope.close(); scope.close();
        assertEquals(Arrays.asList(3,2,1),released);
        assertEquals(1,scope.failureCount());
        assertEquals("expected",scope.firstFailure().getMessage());
    }

    @Test public void borrowedEqualObjectsAreNotMistakenForTheOwnedIdentity() {
        ScriptResourceScope scope=new ScriptResourceScope(); AtomicInteger count=new AtomicInteger();
        String owned=new String("same"),borrowed=new String("same");
        scope.track(owned,count::incrementAndGet); scope.forget(borrowed); scope.close();
        assertEquals(1,count.get());
    }

    @Test public void registrationRacingWithCloseAlwaysReleasesExactlyOnce() throws Exception {
        ScriptResourceScope scope=new ScriptResourceScope(); AtomicInteger count=new AtomicInteger();
        CountDownLatch start=new CountDownLatch(1);
        Thread producer=new Thread(()-> {
            try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            for (int i=0;i<1000;i++) scope.track(new Object(),count::incrementAndGet);
        });
        producer.start(); start.countDown(); scope.close(); producer.join(3000);
        assertFalse(producer.isAlive()); assertEquals(1000,count.get());
    }

    @Test public void explicitReleaseRemovesOwnershipAndLateResourcesAreClosed() {
        ScriptResourceScope scope=new ScriptResourceScope(); AtomicInteger count=new AtomicInteger();
        Object resource=new Object(); scope.track(resource,count::incrementAndGet); scope.forget(resource);
        scope.close(); assertEquals(0,count.get());
        scope.track(resource,count::incrementAndGet); assertEquals(1,count.get());
    }
}
