package com.stardust.autojs.core.image.capture;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScreenCaptureStateTest {
    @Test public void appWindowSizeReplacesInitialScreenSize() {
        ScreenCaptureState state = new ScreenCaptureState();
        state.resize(1080, 2400, false);
        long oldFrame = state.generation();
        assertTrue(state.resize(1080, 2150, true));
        assertTrue(state.hasSize(1080, 2150));
        assertTrue(state.hasCapturedContentSize());
        assertFalse(state.canReuseFrame(oldFrame));
        assertTrue(state.canReuseFrame(state.generation()));
    }

    @Test public void equalSizeCallbackStillDisablesHostOrientationFallback() {
        ScreenCaptureState state = new ScreenCaptureState();
        state.resize(1080, 2400, false);
        long generation = state.generation();
        assertFalse(state.resize(1080, 2400, true));
        assertTrue(state.hasCapturedContentSize());
        assertEquals(generation, state.generation());
    }

    @Test public void rotationInvalidatesOldFrames() {
        ScreenCaptureState state = new ScreenCaptureState();
        state.resize(1080, 2150, true);
        long portraitFrame = state.generation();
        state.resize(2150, 1080, true);
        assertFalse(state.canReuseFrame(portraitFrame));
        assertTrue(state.hasSize(2150, 1080));
    }

    @Test public void hiddenTargetAndReturnNeverReuseOldFrame() {
        ScreenCaptureState state = new ScreenCaptureState();
        state.resize(1080, 2150, true);
        long visibleFrame = state.generation();
        assertTrue(state.setVisible(false));
        assertFalse(state.isVisible());
        assertFalse(state.canReuseFrame(visibleFrame));
        assertFalse(state.canReuseFrame(state.generation()));
        assertTrue(state.setVisible(true));
        assertFalse(state.canReuseFrame(visibleFrame));
        assertTrue(state.canReuseFrame(state.generation()));
    }

    @Test public void duplicateVisibilityCallbackKeepsFrameValid() {
        ScreenCaptureState state = new ScreenCaptureState();
        long frame = state.generation();
        assertFalse(state.setVisible(true));
        assertTrue(state.canReuseFrame(frame));
    }

    @Test public void invalidDimensionsAreIgnored() {
        ScreenCaptureState state = new ScreenCaptureState();
        assertFalse(state.resize(0, 100, true));
        assertFalse(state.resize(100, -1, true));
        assertFalse(state.hasCapturedContentSize());
    }

    @Test public void firstFrameGraceDoesNotSlowNormalCapture() {
        assertEquals(2000L, ScreenCaptureState.timeoutForCapture(100L, false));
        assertEquals(100L, ScreenCaptureState.timeoutForCapture(100L, true));
        assertEquals(0L, ScreenCaptureState.timeoutForCapture(0L, false));
        assertEquals(350L, ScreenCaptureState.timeoutForCapture(350L, false));
        assertEquals(5000L, ScreenCaptureState.timeoutForCapture(10000L, false));
        assertEquals(0L, ScreenCaptureState.timeoutForCapture(-1L, false));
    }
}
