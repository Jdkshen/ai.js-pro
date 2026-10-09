package com.stardust.autojs.core.inputevent;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TouchControllerTest {
    private static class Backend implements TouchController.Backend {
        final List<String> events = new ArrayList<>();
        public void down(int id, int x, int y) throws IOException { events.add("D" + id + ":" + x + "," + y); }
        public void move(int id, int x, int y) { events.add("M" + id + ":" + x + "," + y); }
        public void up(int id, int x, int y) throws IOException { events.add("U" + id + ":" + x + "," + y); }
        public void close() { events.add("close"); }
    }
    @Test public void closesEveryFingerAtLastPositionBeforeClosingTransport() throws Exception {
        Backend backend = new Backend(); TouchController touch = new TouchController(backend, 10);
        touch.down(0, 10, 20); touch.down(4, 30, 40); touch.move(0, 50, 60);
        touch.close(); touch.close();
        assertEquals("U0:50,60", backend.events.get(3));
        assertEquals("U4:30,40", backend.events.get(4));
        assertEquals("close", backend.events.get(5));
        assertEquals(6, backend.events.size()); assertEquals(0, touch.pointerCount());
    }
    @Test public void adbSecondFingerAndDuplicateDownFailBeforeInjection() throws Exception {
        Backend backend = new Backend(); TouchController touch = new TouchController(backend, 1);
        touch.down(2, 10, 20);
        try { touch.down(2, 20, 30); fail(); } catch (IllegalStateException expected) { }
        try { touch.down(3, 20, 30); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, backend.events.size()); touch.close();
    }
    @Test public void validatesOrderIdCoordinatesAndClosedState() throws Exception {
        Backend backend = new Backend(); TouchController touch = new TouchController(backend, 10);
        try { touch.move(0, 1, 1); fail(); } catch (IllegalStateException expected) { }
        try { touch.up(0); fail(); } catch (IllegalStateException expected) { }
        try { touch.down(32, 1, 1); fail(); } catch (IllegalArgumentException expected) { }
        try { touch.down(0, -1, 1); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(backend.events.isEmpty()); touch.close();
        try { touch.down(0, 1, 1); fail(); } catch (IllegalStateException expected) { }
    }
    @Test public void releaseFailureStillLiftsOtherFingersAndClosesBackend() throws Exception {
        Backend backend = new Backend() {
            @Override public void up(int id, int x, int y) throws IOException {
                super.up(id, x, y); if (id == 0) throw new IOException("transport failure");
            }
        };
        TouchController touch = new TouchController(backend, 10);
        touch.down(0, 10, 20); touch.down(1, 30, 40);
        try { touch.close(); fail(); } catch (IOException expected) { }
        assertEquals("U1:30,40", backend.events.get(3));
        assertEquals("close", backend.events.get(4)); assertTrue(touch.isClosed());
    }
    @Test public void uncertainDownFailureStillGetsAnUpDuringCleanup() throws Exception {
        Backend backend = new Backend() {
            @Override public void down(int id, int x, int y) throws IOException { throw new IOException("unknown delivery"); }
        };
        TouchController touch = new TouchController(backend, 10);
        try { touch.down(0, 10, 20); fail(); } catch (IOException expected) { }
        touch.close(); assertEquals("U0:10,20", backend.events.get(0));
    }
}
