package org.autojs.source.engine.android;

import org.junit.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class Node24UiThreadScopeTest {
    @Test public void nestedScopeRestoresCallerAfterException() {
        Node24UiThreadScope scope = new Node24UiThreadScope();
        scope.enter();
        try {
            scope.enter();
            try { throw new IllegalArgumentException("user callback"); }
            catch (IllegalArgumentException expected) { assertTrue(scope.isActive()); }
            finally { scope.exit(); }
            assertTrue(scope.isActive());
        } finally { scope.exit(); }
        assertFalse(scope.isActive());
    }

    @Test public void explicitBackgroundWorkerDoesNotInheritUiScope() throws Exception {
        Node24UiThreadScope scope = new Node24UiThreadScope();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        scope.enter();
        try {
            assertFalse(worker.submit(() -> {
                boolean inherited = scope.isActive();
                scope.enter();
                assertTrue(scope.isActive());
                scope.exit();
                assertFalse(scope.isActive());
                return inherited;
            }).get(2, TimeUnit.SECONDS));
            assertTrue(scope.isActive());
        } finally { scope.exit(); worker.shutdownNow(); }
    }

    @Test public void unbalancedExitFailsWithoutPoisoningNextScope() {
        Node24UiThreadScope scope = new Node24UiThreadScope();
        try { scope.exit(); fail("unbalanced exit"); }
        catch (IllegalStateException expected) { assertFalse(scope.isActive()); }
        scope.enter();
        assertTrue(scope.isActive());
        scope.exit();
        assertFalse(scope.isActive());
    }
}
