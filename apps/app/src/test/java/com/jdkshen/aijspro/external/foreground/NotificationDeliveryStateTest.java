package com.jdkshen.aijspro.external.foreground;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationDeliveryStateTest {
    @Test public void failedDeliveryCanRetryAnUnchangedTask() {
        NotificationDeliveryState state = new NotificationDeliveryState();
        long first = state.begin("one running");
        state.failed(first);
        long retry = state.begin("one running");
        assertNotEquals(0, retry);
        assertNotEquals(first, retry);
        state.succeeded(retry);
        assertEquals(0, state.begin("one running"));
    }

    @Test public void aQueuedOldTaskCannotReplaceTheNewerTask() {
        NotificationDeliveryState state = new NotificationDeliveryState();
        long old = state.begin("old task");
        long current = state.begin("new task");
        assertFalse(state.isCurrent(old));
        state.succeeded(old);
        assertTrue(state.isCurrent(current));
        state.succeeded(current);
        assertEquals(0, state.begin("new task"));
    }

    @Test public void returningToThePublishedIdleStateCancelsQueuedRunningWork() {
        NotificationDeliveryState state = new NotificationDeliveryState();
        state.succeeded(state.begin("idle"));
        long running = state.begin("running");
        assertEquals(0, state.begin("idle"));
        assertFalse(state.isCurrent(running));
        state.succeeded(running);
        assertEquals(0, state.begin("idle"));
    }

    @Test public void duplicatePendingRefreshDoesNotInvalidateItsDelivery() {
        NotificationDeliveryState state = new NotificationDeliveryState();
        long running = state.begin("running");
        assertEquals(0, state.begin("running"));
        assertTrue(state.isCurrent(running));
    }

    @Test public void aLateFailureCannotClearANewerAttempt() {
        NotificationDeliveryState state = new NotificationDeliveryState();
        long old = state.begin("old");
        long current = state.begin("current");
        state.failed(old);
        assertTrue(state.isCurrent(current));
    }

    @Test public void explicitRefreshInvalidatesQueuedWorkAndRetriesPublishedWork() {
        NotificationDeliveryState state = new NotificationDeliveryState();
        state.succeeded(state.begin("running"));
        long old = state.begin("two running");
        state.invalidate();
        assertFalse(state.isCurrent(old));
        assertNotEquals(0, state.begin("running"));
    }
}
