package com.stardust.view.accessibility;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class FingerprintGestureDispatcherTest {
    private static final class Backend implements FingerprintGestureDispatcher.Backend {
        final List<Boolean> requests = new ArrayList<>();
        @Override public void setListening(boolean listening) { requests.add(listening); }
    }
    private static class Listener implements FingerprintGestureDispatcher.Listener {
        final List<String> directions = new ArrayList<>();
        final List<Boolean> availability = new ArrayList<>();
        @Override public void onFingerprintGesture(String direction) { directions.add(direction); }
        @Override public void onFingerprintGestureAvailabilityChanged(boolean value) { availability.add(value); }
    }

    @Test public void captureIsSharedAndReleasedOnlyAfterLastScript() {
        FingerprintGestureDispatcher dispatcher = new FingerprintGestureDispatcher();
        Backend backend = new Backend();
        dispatcher.attach(backend);
        assertTrue(backend.requests.isEmpty());
        Listener first = new Listener(), second = new Listener();
        dispatcher.addListener(first);
        dispatcher.addListener(first);
        dispatcher.addListener(second);
        dispatcher.availabilityChanged(backend, true);
        dispatcher.removeListener(first);
        assertEquals(Arrays.asList(true), backend.requests);
        assertTrue(dispatcher.isAvailable());
        dispatcher.removeListener(second);
        dispatcher.removeListener(second);
        assertEquals(Arrays.asList(true, false), backend.requests);
        assertFalse(dispatcher.isAvailable());
    }

    @Test public void reconnectRetainsSubscriptionsAndRejectsOldCallbacks() {
        FingerprintGestureDispatcher dispatcher = new FingerprintGestureDispatcher();
        Backend old = new Backend(), next = new Backend();
        Listener listener = new Listener();
        dispatcher.addListener(listener);
        dispatcher.attach(old);
        dispatcher.availabilityChanged(old, true);
        dispatcher.detach(old);
        dispatcher.attach(next);
        dispatcher.availabilityChanged(old, true);
        dispatcher.gestureDetected(old, 4);
        dispatcher.detach(old);
        assertFalse(dispatcher.isAvailable());
        dispatcher.availabilityChanged(next, true);
        dispatcher.gestureDetected(next, 4);
        assertEquals(Arrays.asList("up"), listener.directions);
        assertEquals(Arrays.asList(false, true, false, true), listener.availability);
        assertEquals(Arrays.asList(true, false), old.requests);
        assertEquals(Arrays.asList(true), next.requests);
    }

    @Test public void directionsUseFingerprintIdsAndIgnoreUnknownIds() {
        FingerprintGestureDispatcher dispatcher = new FingerprintGestureDispatcher();
        Backend backend = new Backend();
        Listener listener = new Listener();
        dispatcher.attach(backend);
        dispatcher.addListener(listener);
        dispatcher.availabilityChanged(backend, true);
        for (int id : new int[]{1, 2, 4, 8, 3, 0}) dispatcher.gestureDetected(backend, id);
        assertEquals(Arrays.asList("right", "left", "up", "down"), listener.directions);
    }

    @Test public void authenticationSuspensionAndRemovalPreventDelivery() {
        FingerprintGestureDispatcher dispatcher = new FingerprintGestureDispatcher();
        Backend backend = new Backend();
        Listener listener = new Listener();
        dispatcher.attach(backend);
        dispatcher.addListener(listener);
        dispatcher.availabilityChanged(backend, true);
        dispatcher.availabilityChanged(backend, false);
        dispatcher.gestureDetected(backend, 1);
        dispatcher.availabilityChanged(backend, true);
        dispatcher.gestureDetected(backend, 2);
        dispatcher.removeListener(listener);
        dispatcher.availabilityChanged(backend, true);
        dispatcher.gestureDetected(backend, 4);
        assertEquals(Arrays.asList("left"), listener.directions);
        assertFalse(dispatcher.isAvailable());
    }

    @Test public void failingListenerDoesNotBlockOtherScripts() {
        FingerprintGestureDispatcher dispatcher = new FingerprintGestureDispatcher();
        Backend backend = new Backend();
        dispatcher.attach(backend);
        dispatcher.addListener(new Listener() {
            @Override public void onFingerprintGesture(String direction) { throw new IllegalStateException(); }
        });
        Listener listener = new Listener();
        dispatcher.addListener(listener);
        dispatcher.availabilityChanged(backend, true);
        dispatcher.gestureDetected(backend, 8);
        assertEquals(Arrays.asList("down"), listener.directions);
    }
}
