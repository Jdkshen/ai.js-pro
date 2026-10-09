package com.stardust.autojs.core.graphics;

import java.util.function.Consumer;

/** Two owned frames: the script writes the back frame while the renderer reads the front. */
final class CanvasFrameBuffer<T> implements AutoCloseable {
    interface Factory<T> {
        T create(int width, int height);
        void dispose(T frame);
    }
    private final Factory<T> factory;
    private T front, back;
    private int frontWidth, frontHeight, backWidth, backHeight;
    private boolean writing, closed;

    CanvasFrameBuffer(Factory<T> factory) { this.factory = factory; }

    synchronized T begin(int width, int height) {
        if (closed || writing || width <= 0 || height <= 0) return null;
        if (back != null && (backWidth != width || backHeight != height)) {
            factory.dispose(back);
            back = null;
        }
        if (back == null) {
            back = factory.create(width, height);
            backWidth = width;
            backHeight = height;
        }
        writing = true;
        return back;
    }

    synchronized void finish(boolean publish) {
        if (!writing) return;
        writing = false;
        if (closed) {
            disposeBack();
        } else if (publish) {
            T previous = front;
            front = back;
            back = previous;
            int previousWidth = frontWidth, previousHeight = frontHeight;
            frontWidth = backWidth; frontHeight = backHeight;
            backWidth = previousWidth; backHeight = previousHeight;
        }
    }

    synchronized void render(Consumer<T> renderer) {
        if (!closed && front != null) renderer.accept(front);
    }

    synchronized boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        closed = true;
        if (front != null) { factory.dispose(front); front = null; }
        // The callback may close its own window. Its borrowed back frame lives until finally.
        if (!writing) disposeBack();
    }

    private void disposeBack() {
        if (back != null) { factory.dispose(back); back = null; }
    }
}
