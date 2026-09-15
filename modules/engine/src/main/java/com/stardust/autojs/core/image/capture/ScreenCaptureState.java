package com.stardust.autojs.core.image.capture;

/** Capture geometry and frame validity, independent of Android for regression tests. */
final class ScreenCaptureState {
    static final long DEFAULT_TIMEOUT_MILLIS = 100L;
    static final long FIRST_FRAME_TIMEOUT_MILLIS = 2000L;
    static final long MAX_TIMEOUT_MILLIS = 5000L;

    private int width;
    private int height;
    private boolean capturedContentSizeKnown;
    private boolean visible = true;
    private long generation;

    synchronized boolean resize(int width, int height, boolean capturedContent) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        capturedContentSizeKnown |= capturedContent;
        if (this.width == width && this.height == height) {
            return false;
        }
        this.width = width;
        this.height = height;
        generation++;
        return true;
    }

    synchronized boolean hasSize(int width, int height) {
        return this.width == width && this.height == height;
    }

    synchronized boolean hasCapturedContentSize() {
        return capturedContentSizeKnown;
    }

    synchronized boolean setVisible(boolean visible) {
        if (this.visible == visible) {
            return false;
        }
        this.visible = visible;
        generation++;
        return true;
    }

    synchronized boolean isVisible() {
        return visible;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized boolean canReuseFrame(long frameGeneration) {
        return visible && generation == frameGeneration;
    }

    static long timeoutForCapture(long requestedTimeout, boolean hasCurrentFrame) {
        long timeout = Math.max(0L, Math.min(MAX_TIMEOUT_MILLIS, requestedTimeout));
        // Give default first-frame capture time to start, without adding latency to the
        // normal cached-frame path or overriding an explicit zero/custom timeout.
        if (!hasCurrentFrame && requestedTimeout == DEFAULT_TIMEOUT_MILLIS) {
            return FIRST_FRAME_TIMEOUT_MILLIS;
        }
        return timeout;
    }
}
