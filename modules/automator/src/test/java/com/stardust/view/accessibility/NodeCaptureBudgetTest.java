package com.stardust.view.accessibility;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NodeCaptureBudgetTest {

    private static final class FakeClock implements NodeCaptureBudget.NanoClock {
        private long now;

        FakeClock(long now) {
            this.now = now;
        }

        @Override
        public long nanoTime() {
            return now;
        }

        void advanceMillis(long millis) {
            advanceNanos(TimeUnit.MILLISECONDS.toNanos(millis));
        }

        void advanceNanos(long nanos) {
            now += nanos;
        }
    }

    private static void assertLimit(String limitCode, Runnable action) {
        try {
            action.run();
            fail("Expected " + limitCode + " limit");
        } catch (NodeCaptureBudget.CaptureLimitException error) {
            assertEquals(limitCode, error.getLimitCode());
            assertTrue("Limit message must start with " + limitCode + ": " + error.getMessage(),
                    error.getMessage().startsWith(limitCode + ":"));
        }
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void defaultsMatchLegacyCaptureBudget() {
        assertEquals(8000L, NodeCaptureBudget.DEFAULT_TIMEOUT_MILLIS);
        assertEquals(15000, NodeCaptureBudget.DEFAULT_MAX_NODES);
        assertEquals(256, NodeCaptureBudget.DEFAULT_MAX_DEPTH);
    }

    @Test
    public void permitsNodesAndDepthsExactlyAtTheLimit() {
        FakeClock clock = new FakeClock(10_000L);
        NodeCaptureBudget budget = new NodeCaptureBudget(1000L, 3, 2, clock);
        budget.check();
        budget.enter(0);
        budget.enter(2);
        budget.enter(1);
        assertEquals(3, budget.getVisitedCount());
        budget.check();
    }

    @Test
    public void rejectsNodeBeyondLimitWithoutCountingIt() {
        FakeClock clock = new FakeClock(10_000L);
        NodeCaptureBudget budget = new NodeCaptureBudget(1000L, 2, 5, clock);
        budget.enter(0);
        budget.enter(1);
        assertLimit("NODE_LIMIT", () -> budget.enter(1));
        assertEquals(2, budget.getVisitedCount());
    }

    @Test
    public void rejectsDepthBeyondLimitWithoutCountingIt() {
        FakeClock clock = new FakeClock(10_000L);
        NodeCaptureBudget budget = new NodeCaptureBudget(1000L, 10, 1, clock);
        budget.enter(0);
        budget.enter(1);
        assertLimit("DEPTH_LIMIT", () -> budget.enter(2));
        assertEquals(2, budget.getVisitedCount());
    }

    @Test
    public void rejectsNegativeDepth() {
        NodeCaptureBudget budget = new NodeCaptureBudget(1000L, 10, 10);
        assertIllegalArgument(() -> budget.enter(-1));
        assertEquals(0, budget.getVisitedCount());
    }

    @Test
    public void cancellationIsStickyAndDoesNotCountNodes() {
        FakeClock clock = new FakeClock(10_000L);
        NodeCaptureBudget budget = new NodeCaptureBudget(1000L, 10, 10, clock);
        budget.enter(0);
        budget.cancel();
        assertLimit("CANCELLED", budget::check);
        assertLimit("CANCELLED", () -> budget.enter(1));
        assertEquals(1, budget.getVisitedCount());
    }

    @Test
    public void timeoutFiresExactlyAtTheDeadline() {
        FakeClock clock = new FakeClock(50_000L);
        NodeCaptureBudget budget = new NodeCaptureBudget(250L, 10, 10, clock);
        clock.advanceMillis(249L);
        budget.check();
        budget.enter(0);

        clock.advanceMillis(1L);
        assertLimit("TIMEOUT", budget::check);
        assertLimit("TIMEOUT", () -> budget.enter(1));
        assertEquals(1, budget.getVisitedCount());
    }

    @Test
    public void timeoutUsesSubMillisecondNanoseconds() {
        FakeClock clock = new FakeClock(0L);
        NodeCaptureBudget budget = new NodeCaptureBudget(1L, 10, 10, clock);
        clock.advanceNanos(999_999L);
        budget.check();
        clock.advanceNanos(1L);
        assertLimit("TIMEOUT", budget::check);
    }

    @Test
    public void queuePreflightKeepsDeclaredChildrenInsideNodeBudget() {
        FakeClock clock = new FakeClock(10_000L);
        NodeCaptureBudget budget = new NodeCaptureBudget(1000L, 4, 10, clock);
        budget.enter(0);
        budget.checkChildCapacity(0, 3);
        assertLimit("NODE_LIMIT", () -> budget.checkChildCapacity(1, 3));
        assertLimit("NODE_LIMIT", () -> budget.checkChildCapacity(0, 4));
        assertIllegalArgument(() -> budget.checkChildCapacity(-1, 0));
        assertIllegalArgument(() -> budget.checkChildCapacity(0, -1));
    }

    @Test
    public void constructorRejectsInvalidArguments() {
        assertIllegalArgument(() -> new NodeCaptureBudget(0L, 1, 0));
        assertIllegalArgument(() -> new NodeCaptureBudget(-1L, 1, 0));
        assertIllegalArgument(() -> new NodeCaptureBudget(1000L, 0, 0));
        assertIllegalArgument(() -> new NodeCaptureBudget(1000L, -1, 0));
        assertIllegalArgument(() -> new NodeCaptureBudget(1000L, 1, -1));
        assertIllegalArgument(() -> new NodeCaptureBudget(1000L, 1, 0, null));
    }
}
