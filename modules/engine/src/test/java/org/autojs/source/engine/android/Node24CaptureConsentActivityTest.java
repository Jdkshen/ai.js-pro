package org.autojs.source.engine.android;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Pure-JVM checks for projection request options and lifecycle helpers. */
public class Node24CaptureConsentActivityTest {

    @Test public void orientationAcceptsLegacyNumbersAndNames() {
        assertEquals(0, Node24CaptureConsentActivity.normalizeOrientation(null));
        assertEquals(0, Node24CaptureConsentActivity.normalizeOrientation(JSONObject.NULL));
        assertEquals(0, Node24CaptureConsentActivity.normalizeOrientation(-1));
        assertEquals(0, Node24CaptureConsentActivity.normalizeOrientation(0));
        assertEquals(1, Node24CaptureConsentActivity.normalizeOrientation(1));
        assertEquals(2, Node24CaptureConsentActivity.normalizeOrientation(2));
        assertEquals(0, Node24CaptureConsentActivity.normalizeOrientation("auto"));
        assertEquals(1, Node24CaptureConsentActivity.normalizeOrientation("PORTRAIT"));
        assertEquals(2, Node24CaptureConsentActivity.normalizeOrientation(" landscape "));
        try {
            Node24CaptureConsentActivity.normalizeOrientation(3);
            fail("expected invalid orientation to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("orientation"));
        }
        try {
            Node24CaptureConsentActivity.normalizeOrientation("sideways");
            fail("expected invalid orientation name to fail");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test public void captureSizeUsesDisplayAndRequestedOrientationWhenUnspecified() {
        int[] auto = Node24CaptureConsentActivity.resolveCaptureSize(-1, -1, 1080, 2400, 0);
        assertArrayEquals(new int[]{1080, 2400}, auto);

        int[] portrait = Node24CaptureConsentActivity.resolveCaptureSize(-1, -1, 2400, 1080, 1);
        assertArrayEquals(new int[]{1080, 2400}, portrait);

        int[] landscape = Node24CaptureConsentActivity.resolveCaptureSize(-1, -1, 1080, 2400, 2);
        assertArrayEquals(new int[]{2400, 1080}, landscape);
    }

    @Test public void captureSizeKeepsExplicitRequestAndRejectsPartialOrHugeSizes() {
        assertArrayEquals(new int[]{1280, 720},
                Node24CaptureConsentActivity.resolveCaptureSize(1280, 720, 1080, 2400, 0));
        assertArrayEquals(new int[]{4000, 4000},
                Node24CaptureConsentActivity.resolveCaptureSize(4000, 4000, 1080, 2400, 0));
        for (int[] request : new int[][]{{1280, -1}, {-1, 720}, {0, 720}, {17000, 100},
                {5000, 4000}, {4000, 4001}}) {
            try {
                Node24CaptureConsentActivity.resolveCaptureSize(
                        request[0], request[1], 1080, 2400, 0);
                fail("expected invalid size to fail: " + request[0] + "x" + request[1]);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        try {
            Node24CaptureConsentActivity.resolveCaptureSize(0, 0, 0, 0, 0);
            fail("expected missing display size to fail");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test public void captureTimeoutIsBoundedToScreenCapturerLimit() {
        assertEquals(0L, Node24CaptureConsentActivity.normalizeCaptureTimeout(-10L));
        assertEquals(0L, Node24CaptureConsentActivity.normalizeCaptureTimeout(0L));
        assertEquals(250L, Node24CaptureConsentActivity.normalizeCaptureTimeout(250L));
        assertEquals(5000L, Node24CaptureConsentActivity.normalizeCaptureTimeout(5000L));
        assertEquals(5000L, Node24CaptureConsentActivity.normalizeCaptureTimeout(99999L));
    }

    @Test public void requestTokensAreRandomPerRequest() {
        String first = Node24CaptureConsentActivity.newToken();
        String second = Node24CaptureConsentActivity.newToken();
        assertNotNull(first);
        assertFalse(first.isEmpty());
        assertNotEquals(first, second);
        assertEquals(36, first.length());
    }

    @Test public void sessionStopAndCloseAreIdempotent() {
        Node24CaptureConsentActivity.Session session =
                new Node24CaptureConsentActivity.Session();
        assertTrue(session.stop());
        assertTrue(session.stop());
        session.cancelPending();
        session.close();
        session.close();
        session.cancelPending();
        assertTrue(session.stop());
    }

    @Test public void captureWithoutConsentFailsClearly() {
        Node24CaptureConsentActivity.Session session =
                new Node24CaptureConsentActivity.Session();
        try {
            session.capture(new Node24Images(), true, 5000L);
            fail("expected capture before consent to fail");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("尚未获得屏幕截图授权"));
        } finally {
            session.close();
        }
    }
}
