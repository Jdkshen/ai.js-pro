package org.autojs.source.engine.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Pure-JVM checks for the Node24 automation request normalization. */
public class Node24AutomationBridgeTest {

    @Test public void pressUsesOneStrokeWithExplicitDuration() throws Exception {
        JSONArray strokes = new JSONArray(Node24AutomationBridge.pressGestureJson(120, 240, 350L));
        assertEquals(1, strokes.length());
        JSONArray points = strokes.getJSONArray(0);
        assertEquals(2, points.length());
        assertEquals(120, points.getJSONObject(0).getInt("x"));
        assertEquals(240, points.getJSONObject(0).getInt("y"));
        assertEquals(0L, points.getJSONObject(0).getLong("t"));
        assertEquals(120, points.getJSONObject(1).getInt("x"));
        assertEquals(240, points.getJSONObject(1).getInt("y"));
        assertEquals(350L, points.getJSONObject(1).getLong("t"));
    }

    @Test public void gestureNormalizationExtractsStartTimeAndRebasesEachStroke() throws Exception {
        JSONArray strokes = new JSONArray();
        strokes.put(new JSONArray()
                .put(new JSONObject().put("x", 1).put("y", 2).put("t", 40))
                .put(new JSONObject().put("x", 3).put("y", 4).put("t", 290)));
        strokes.put(new JSONArray()
                .put(new JSONObject().put("x", 5).put("y", 6).put("t", 1000))
                .put(new JSONObject().put("x", 7).put("y", 8).put("t", 1250)));

        JSONArray normalized = new JSONArray(Node24AutomationBridge.normalizeStrokes(strokes));
        assertEquals(2, normalized.length());

        JSONArray first = normalized.getJSONArray(0);
        assertEquals(40L, first.getJSONObject(0).getLong("startTime"));
        assertEquals(0L, first.getJSONObject(0).getLong("t"));
        assertEquals(250L, first.getJSONObject(1).getLong("t"));
        assertFalse(first.getJSONObject(1).has("startTime"));

        JSONArray second = normalized.getJSONArray(1);
        assertEquals(1000L, second.getJSONObject(0).getLong("startTime"));
        assertEquals(0L, second.getJSONObject(0).getLong("t"));
        assertEquals(250L, second.getJSONObject(1).getLong("t"));
        assertEquals(5, second.getJSONObject(0).getInt("x"));
    }

    @Test public void gestureNormalizationSupportsSinglePointDelayAndZeroStart() throws Exception {
        JSONArray delayed = new JSONArray().put(new JSONArray()
                .put(new JSONObject().put("x", 9).put("y", 8).put("t", 350)));
        JSONObject point = new JSONArray(Node24AutomationBridge.normalizeStrokes(delayed))
                .getJSONArray(0).getJSONObject(0);
        assertEquals(350L, point.getLong("startTime"));
        assertEquals(0L, point.getLong("t"));

        JSONArray immediate = new JSONArray().put(new JSONArray()
                .put(new JSONObject().put("x", 1).put("y", 2)));
        JSONObject zero = new JSONArray(Node24AutomationBridge.normalizeStrokes(immediate))
                .getJSONArray(0).getJSONObject(0);
        assertEquals(0L, zero.getLong("startTime"));
        assertEquals(0L, zero.getLong("t"));

        JSONArray atLimit = singleStrokePoint(1, 2, 60_000L);
        JSONObject limit = new JSONArray(Node24AutomationBridge.normalizeStrokes(atLimit))
                .getJSONArray(0).getJSONObject(0);
        assertEquals(60_000L, limit.getLong("startTime"));
        assertEquals(0L, limit.getLong("t"));
    }

    @Test public void gestureNormalizationDropsUnknownFields() throws Exception {
        JSONArray strokes = new JSONArray();
        strokes.put(new JSONArray().put(new JSONObject()
                .put("x", 1).put("y", 2).put("t", 0).put("ignored", "yes")));
        JSONObject point = new JSONArray(Node24AutomationBridge.normalizeStrokes(strokes))
                .getJSONArray(0).getJSONObject(0);
        assertFalse(point.has("ignored"));
        assertTrue(point.has("x"));
        assertTrue(point.has("y"));
        assertTrue(point.has("t"));
    }

    @Test public void gestureRejectsEmptyTooManyAndInvalidPoints() {
        assertInvalidGesture(new JSONArray());
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 11; i++) {
            try {
                tooMany.put(new JSONArray().put(new JSONObject().put("x", i).put("y", i).put("t", 0)));
            } catch (Exception impossible) {
                fail(impossible.toString());
            }
        }
        assertInvalidGesture(tooMany);

        JSONArray missingX = new JSONArray();
        try {
            missingX.put(new JSONArray().put(new JSONObject().put("y", 1).put("t", 0)));
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        assertInvalidGesture(missingX);

        JSONArray negativeTime = new JSONArray();
        try {
            negativeTime.put(new JSONArray().put(new JSONObject()
                    .put("x", 1).put("y", 1).put("t", -1)));
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        assertInvalidGesture(negativeTime);
    }

    @Test public void gestureRejectsNegativeCoordinatesDecreasingTimeAndOverLimit() {
        assertInvalidGesture(singleStrokePoint(-1, 1, 0));
        assertInvalidGesture(singleStrokePoint(1, -1, 0));

        JSONArray decreasing = new JSONArray();
        try {
            decreasing.put(new JSONArray()
                    .put(new JSONObject().put("x", 1).put("y", 1).put("t", 100))
                    .put(new JSONObject().put("x", 2).put("y", 2).put("t", 50)));
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        assertInvalidGesture(decreasing);

        assertInvalidGesture(singleStrokePoint(1, 1, 60_001L));

        JSONArray secondStrokeOverLimit = new JSONArray();
        try {
            secondStrokeOverLimit.put(new JSONArray()
                    .put(new JSONObject().put("x", 1).put("y", 1).put("t", 1_000)));
            secondStrokeOverLimit.put(new JSONArray()
                    .put(new JSONObject().put("x", 2).put("y", 2).put("t", 60_001)));
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        assertInvalidGesture(secondStrokeOverLimit);
    }

    private static JSONArray singleStrokePoint(int x, int y, long t) {
        try {
            return new JSONArray().put(new JSONArray()
                    .put(new JSONObject().put("x", x).put("y", y).put("t", t)));
        } catch (Exception impossible) {
            fail(impossible.toString());
            return null;
        }
    }

    @Test public void accessibilityErrorsAreHonestAndDistinct() {
        assertTrue(Node24AutomationBridge.accessibilityRequirementMessage(false).contains("未开启"));
        assertTrue(Node24AutomationBridge.accessibilityRequirementMessage(true).contains("未运行"));
        assertNotEquals(Node24AutomationBridge.accessibilityRequirementMessage(false),
                Node24AutomationBridge.accessibilityRequirementMessage(true));
    }

    private static void assertInvalidGesture(JSONArray strokes) {
        try {
            Node24AutomationBridge.normalizeStrokes(strokes);
            fail("expected invalid gesture to fail");
        } catch (Exception expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
