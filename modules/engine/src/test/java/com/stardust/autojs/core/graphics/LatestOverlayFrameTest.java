package com.stardust.autojs.core.graphics;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class LatestOverlayFrameTest {
    @Test public void stalledRendererDoesNotBlockProducerOrQueueOldFrames() throws Exception {
        LatestOverlayFrame box = new LatestOverlayFrame();
        box.offer("first", "1");
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread renderer = new Thread(() -> {
            assertEquals("first", box.take().shapes);
            held.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        renderer.start();
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 10000; i++) assertTrue(box.offer("frame" + i, String.valueOf(i)));
            LatestOverlayFrame.Request last = box.take();
            assertEquals("frame9999", last.shapes);
            assertEquals("9999", last.stats);
            assertNull(box.take());
        } finally { release.countDown(); renderer.join(2000); }
    }
    @Test public void clearIsARealFrameAndCloseRejectsLateUpdates() {
        LatestOverlayFrame box = new LatestOverlayFrame();
        box.offer("old", "old");
        box.offer(null, null);
        LatestOverlayFrame.Request empty = box.take();
        assertEquals("[]", empty.shapes); assertEquals("", empty.stats);
        box.offer("queued", ""); box.close(); box.close();
        assertNull(box.take()); assertFalse(box.offer("late", ""));
    }
}
