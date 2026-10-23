package com.stardust.autojs.engine.node24;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure JVM tests for NodeUiImageRequestState: no Android/Glide classes are loaded.
 */
public class NodeUiImageRequestStateTest {

    @Test
    public void newerRequestSupersedesOldAndOnlyNewResultCanApply() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        Object view = new Object();
        NodeUiImageRequestState.Begin first = state.begin(view);
        NodeUiImageRequestState.Begin second = state.begin(view);

        assertSame(first.ticket(), second.superseded());
        assertEquals(1, first.ticket().generation());
        assertEquals(2, second.ticket().generation());
        assertFalse(state.isCurrent(first.ticket()));
        assertTrue(state.isCurrent(second.ticket()));

        final AtomicInteger writes = new AtomicInteger();
        assertFalse(state.applyIfCurrent(first.ticket(), () -> {
            writes.incrementAndGet();
            return true;
        }));
        assertEquals(0, writes.get());
        assertFalse(state.failIfCurrent(first.ticket()));

        assertTrue(state.applyIfCurrent(second.ticket(), () -> {
            writes.incrementAndGet();
            return true;
        }));
        assertEquals(1, writes.get());
        assertEquals(0, state.pendingCount());
        assertFalse(state.hasPending(view));
    }

    @Test
    public void completedViewIsRemovedSoNextRequestHasNoSupersededTicket() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        Object view = new Object();
        NodeUiImageRequestState.Begin first = state.begin(view);
        assertTrue(state.applyIfCurrent(first.ticket(), () -> true));
        assertEquals(0, state.pendingCount());

        NodeUiImageRequestState.Begin next = state.begin(view);
        assertNull(next.superseded());
        assertTrue(state.isCurrent(next.ticket()));
        assertEquals(1, state.pendingCount());
    }

    @Test
    public void cancelByViewInvalidatesOnlyThatViewAndLoaderCanBeReused() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        Object firstView = new Object();
        Object secondView = new Object();
        NodeUiImageRequestState.Begin first = state.begin(firstView);
        NodeUiImageRequestState.Begin second = state.begin(secondView);

        assertSame(first.ticket(), state.cancel(firstView));
        assertFalse(state.isCurrent(first.ticket()));
        assertFalse(state.applyIfCurrent(first.ticket(), () -> true));
        assertTrue(state.isCurrent(second.ticket()));
        assertFalse(state.cancel(first.ticket()));

        NodeUiImageRequestState.Begin retry = state.begin(firstView);
        assertNull(retry.superseded());
        assertTrue(state.isCurrent(retry.ticket()));
        assertTrue(state.cancel(retry.ticket()));
        assertEquals(1, state.pendingCount());
        assertTrue(state.cancel(second.ticket()));
        assertEquals(0, state.pendingCount());
    }

    @Test
    public void closeCancelsAllRequestsRejectsNewOnesAndIsIdempotent() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        NodeUiImageRequestState.Begin first = state.begin("view-1");
        NodeUiImageRequestState.Begin second = state.begin("view-2");

        List<NodeUiImageRequestState.Ticket> cancelled = state.close();
        assertEquals(2, cancelled.size());
        assertTrue(state.isClosed());
        assertEquals(0, state.pendingCount());
        assertFalse(state.isCurrent(first.ticket()));
        assertFalse(state.isCurrent(second.ticket()));
        assertFalse(state.applyIfCurrent(first.ticket(), () -> {
            fail("closed request must not write");
            return true;
        }));
        assertFalse(state.failIfCurrent(second.ticket()));
        assertTrue(state.close().isEmpty());
        try {
            state.begin("view-3");
            fail("begin after close must throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("关闭"));
        }
    }

    @Test
    public void failRemovesCurrentRequestAndCannotBeReplayed() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        Object view = new Object();
        NodeUiImageRequestState.Begin request = state.begin(view);

        assertTrue(state.failIfCurrent(request.ticket()));
        assertFalse(state.failIfCurrent(request.ticket()));
        assertFalse(state.isCurrent(request.ticket()));
        assertFalse(state.applyIfCurrent(request.ticket(), () -> true));
        assertEquals(0, state.pendingCount());
    }

    @Test
    public void actionReturningFalseStillRemovesRequest() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        Object view = new Object();
        NodeUiImageRequestState.Begin request = state.begin(view);

        assertFalse(state.applyIfCurrent(request.ticket(), () -> false));
        assertFalse(state.isCurrent(request.ticket()));
        assertEquals(0, state.pendingCount());
    }

    @Test
    public void actionExceptionStillRemovesRequestToAvoidRetainingView() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        Object view = new Object();
        NodeUiImageRequestState.Begin request = state.begin(view);

        try {
            state.applyIfCurrent(request.ticket(), () -> {
                throw new IllegalStateException("boom");
            });
            fail("action exception must propagate");
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
        assertEquals(0, state.pendingCount());
        assertFalse(state.isCurrent(request.ticket()));
    }

    @Test
    public void reentrantBeginInsideApplyKeepsTheNewerTicket() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        Object view = new Object();
        final NodeUiImageRequestState.Begin first = state.begin(view);
        final NodeUiImageRequestState.Ticket[] newer = new NodeUiImageRequestState.Ticket[1];

        assertTrue(state.applyIfCurrent(first.ticket(), () -> {
            newer[0] = state.begin(view).ticket();
            return true;
        }));
        assertTrue(state.isCurrent(newer[0]));
        assertEquals(1, state.pendingCount());
        assertTrue(state.cancel(newer[0]));
    }

    @Test
    public void nullKeysAreRejectedOrIgnored() {
        NodeUiImageRequestState state = new NodeUiImageRequestState();
        try {
            state.begin(null);
            fail("null view must be rejected");
        } catch (NullPointerException expected) {
            // expected
        }
        assertNull(state.cancel((Object) null));
        assertFalse(state.hasPending(null));
    }
}
