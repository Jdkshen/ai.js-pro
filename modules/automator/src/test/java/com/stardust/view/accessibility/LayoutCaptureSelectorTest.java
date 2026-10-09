package com.stardust.view.accessibility;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class LayoutCaptureSelectorTest {
    private static final LayoutCaptureSelector.Readable<String> READABLE = root -> !root.startsWith("empty");
    private static LayoutCaptureSelector.Window<String> window(String root, int id, boolean application,
            boolean active, boolean focused, int layer) {
        return new LayoutCaptureSelector.Window<>(root, id, application, active, focused, layer);
    }

    @Test public void inspectorOverlayCannotReplaceApplicationRoot() {
        assertEquals("wechat", LayoutCaptureSelector.select(Arrays.asList(
                window("menu", 8, false, true, true, 10),
                window("wechat", 7, true, false, false, 2)), "menu", 8));
    }

    @Test public void keyboardCannotReplaceFocusedApplication() {
        assertEquals("chat", LayoutCaptureSelector.select(Arrays.asList(
                window("keyboard", 9, false, true, true, 20),
                window("launcher", 1, true, false, false, 0),
                window("chat", 7, true, false, true, 2)), "keyboard", 9));
    }

    @Test public void prefersFreshActiveApplicationRoot() {
        assertEquals("fresh", LayoutCaptureSelector.select(Arrays.asList(
                window("other", 3, true, false, true, 5),
                window("cached", 7, true, true, false, 2)), "fresh", 7));
    }

    @Test public void disappearingWindowRootDoesNotHideFreshRoot() {
        assertEquals("fresh", LayoutCaptureSelector.select(Collections.singletonList(
                window(null, 7, true, true, true, 2)), "fresh", 7));
    }

    @Test public void supportsRomsWithoutWindowEnumeration() {
        assertEquals("app", LayoutCaptureSelector.select(Collections.emptyList(), "app", 7));
        assertNull(LayoutCaptureSelector.select(Collections.emptyList(), null, -1));
    }

    @Test public void knownOverlayWithoutReadableApplicationIsFailure() {
        assertNull(LayoutCaptureSelector.select(Arrays.asList(
                window("menu", 8, false, true, true, 10),
                window(null, 7, true, false, false, 2)), "menu", 8));
    }

    @Test public void selectsActiveThenFocusedThenTopApplication() {
        assertEquals("active", LayoutCaptureSelector.select(Arrays.asList(
                window("active", 7, true, true, false, 1),
                window("focused", 6, true, false, true, 3)), null, -1));
        assertEquals("top", LayoutCaptureSelector.select(Arrays.asList(
                window("bottom", 1, true, false, false, 0),
                window("top", 7, true, false, false, 5)), null, -1));
    }

    @Test public void emptyActiveRootFallsBackToReadableRootOfSameWindow() {
        assertEquals("wechat", LayoutCaptureSelector.select(Collections.singletonList(
                window("wechat", 7, true, true, true, 2)), "empty-active", 7, READABLE));
    }

    @Test public void emptyHostWindowDoesNotHideReadableApplication() {
        assertEquals("wechat", LayoutCaptureSelector.select(Arrays.asList(
                window("empty-host", 8, true, true, true, 10),
                window("wechat", 7, true, false, false, 2)), "empty-host", 8, READABLE));
    }

    @Test public void emptyWindowCannotProduceSuccessfulCapture() {
        assertNull(LayoutCaptureSelector.select(Collections.singletonList(
                window("empty-app", 7, true, true, true, 2)), "empty-app", 7, READABLE));
        assertNull(LayoutCaptureSelector.select(Collections.emptyList(), "empty-app", 7, READABLE));
    }
}
