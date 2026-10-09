package com.stardust.autojs.core.graphics;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class CanvasFrameBufferTest {
    private static class Frame { final int width, height; boolean disposed; Frame(int w, int h) { width = w; height = h; } }
    private static class Factory implements CanvasFrameBuffer.Factory<Frame> {
        final List<Frame> created = new ArrayList<>();
        @Override public Frame create(int w, int h) { Frame f = new Frame(w, h); created.add(f); return f; }
        @Override public void dispose(Frame f) { assertFalse("double disposal", f.disposed); f.disposed = true; }
    }
    @Test public void rendererSeesOnlyCompletedFramesAndTwoFramesAreReused() {
        Factory factory = new Factory(); CanvasFrameBuffer<Frame> buffer = new CanvasFrameBuffer<>(factory);
        Frame first = buffer.begin(10, 20);
        buffer.render(f -> fail("unfinished frame published"));
        buffer.finish(true);
        Frame second = buffer.begin(10, 20);
        assertNotSame(first, second);
        buffer.render(f -> assertSame(first, f));
        buffer.finish(true);
        assertSame(first, buffer.begin(10, 20));
        buffer.finish(true);
        assertEquals(2, factory.created.size());
        buffer.close();
        assertTrue(first.disposed); assertTrue(second.disposed);
    }
    @Test public void closeDuringCallbackDefersTheBorrowedFrameAndStopsRendering() {
        Factory factory = new Factory(); CanvasFrameBuffer<Frame> buffer = new CanvasFrameBuffer<>(factory);
        Frame first = buffer.begin(10, 20); buffer.finish(true);
        Frame borrowed = buffer.begin(10, 20);
        buffer.close(); buffer.close();
        assertTrue(first.disposed); assertFalse(borrowed.disposed);
        buffer.render(f -> fail("closed buffer rendered"));
        assertNull(buffer.begin(10, 20));
        buffer.finish(true);
        assertTrue(borrowed.disposed);
        buffer.close();
    }
    @Test public void resizeDisposesOldFramesAfterTheyAreNoLongerBorrowed() {
        Factory factory = new Factory(); CanvasFrameBuffer<Frame> buffer = new CanvasFrameBuffer<>(factory);
        Frame first = buffer.begin(10, 20); buffer.finish(true);
        Frame second = buffer.begin(10, 20); buffer.finish(true);
        Frame resized = buffer.begin(30, 40);
        assertTrue(first.disposed); assertFalse(second.disposed);
        assertEquals(30, resized.width); assertEquals(40, resized.height);
        buffer.finish(true); buffer.begin(30, 40);
        assertTrue(second.disposed);
        buffer.finish(true); buffer.close();
        for (Frame frame : factory.created) assertTrue(frame.disposed);
    }
    @Test public void failedFramesDoNotReplaceTheLastCompletedImage() {
        Factory factory = new Factory(); CanvasFrameBuffer<Frame> buffer = new CanvasFrameBuffer<>(factory);
        Frame completed = buffer.begin(10, 20); buffer.finish(true);
        Frame failed = buffer.begin(10, 20);
        assertNull(buffer.begin(10, 20));
        buffer.finish(false);
        buffer.render(f -> assertSame(completed, f));
        assertSame(failed, buffer.begin(10, 20));
        buffer.finish(false); buffer.close();
    }
}
