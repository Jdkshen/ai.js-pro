package org.autojs.source.engine.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Pure-JVM checks for Node24Images protocol parsing/search rules. Bitmap decoding itself is an
 * Android/device concern and is exercised by the integration pass, not by this unit test.
 */
public class Node24ImagesTest {

    @Test public void colorComparisonUsesMaxRgbDistanceAndIgnoresAlpha() {
        int expected = 0xFF112233;
        assertTrue(Node24Images.colorMatches(expected, 0x00122433, 3));
        assertTrue(Node24Images.colorMatches(expected, 0x00122433, 2));
        assertFalse(Node24Images.colorMatches(expected, 0x00122433, 1));
        // alpha 完全不同也不影响 RGB 比较。
        assertTrue(Node24Images.colorMatches(expected, 0x00112233, 0));
    }

    @Test public void colorParserAcceptsSignedArgbAndHex() {
        assertEquals(-1, Node24Images.parseColor(-1, "color"));
        assertEquals(-1, Node24Images.parseColor(0xFFFFFFFFL, "color"));
        assertEquals(0x80FF0000, Node24Images.parseColor("#80FF0000", "color"));
        assertEquals(0xFF123456, Node24Images.parseColor("#123456", "color"));
        try {
            Node24Images.parseColor("#12345", "color");
            fail("expected invalid hex to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("color"));
        }
        try {
            Node24Images.parseColor(null, "color");
            fail("expected null color to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("color"));
        }
    }

    @Test public void regionSupportsRemainingImageAndRectangles() {
        Node24Images.Region remaining = Node24Images.parseRegion(
                new JSONArray().put(30).put(40), 200, 100);
        assertEquals(30, remaining.x);
        assertEquals(40, remaining.y);
        assertEquals(170, remaining.width);
        assertEquals(60, remaining.height);

        Node24Images.Region array = Node24Images.parseRegion(
                new JSONArray().put(5).put(6).put(7).put(8), 200, 100);
        assertEquals(5, array.x);
        assertEquals(6, array.y);
        assertEquals(7, array.width);
        assertEquals(8, array.height);

        JSONObject object = new JSONObject();
        try {
            object.put("x", 1).put("y", 2).put("width", 3).put("height", 4);
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        Node24Images.Region rectangle = Node24Images.parseRegion(object, 200, 100);
        assertEquals(1, rectangle.x);
        assertEquals(2, rectangle.y);
        assertEquals(3, rectangle.width);
        assertEquals(4, rectangle.height);

        assertEquals(200, Node24Images.parseRegion(null, 200, 100).width);
        assertEquals(100, Node24Images.parseRegion(null, 200, 100).height);
    }

    @Test public void regionRejectsOutOfBoundsAndDegenerateRects() {
        for (Object raw : new Object[]{
                new JSONArray().put(-1).put(0),
                new JSONArray().put(0).put(-1),
                new JSONArray().put(0).put(0).put(0).put(10),
                new JSONArray().put(0).put(0).put(10).put(0),
                new JSONArray().put(95).put(0).put(10).put(10),
                new JSONArray().put(0).put(0).put(1).put(2).put(3)}) {
            try {
                Node24Images.parseRegion(raw, 100, 100);
                fail("expected invalid region to fail: " + raw);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test public void searchReturnsFirstMatchInRowMajorOrder() {
        int target = 0xFF00FF00;
        int[] pixels = {
                0xFFFF0000, 0xFF00FF00, 0xFFFF0000,
                0xFF00FF00, 0xFF00FF00, 0xFF0000FF
        };
        int[] first = Node24Images.findFirstColor(pixels, 3, 2, target, 0);
        assertNotNull(first);
        assertEquals(1, first[0]);
        assertEquals(0, first[1]);
        assertNull(Node24Images.findFirstColor(pixels, 3, 2, 0xFF123456, 0));
        assertNull(Node24Images.findFirstColor(new int[2], 3, 2, target, 0));
    }

    @Test public void thresholdAndOffsetColorsAreValidated() {
        JSONObject request = new JSONObject();
        try {
            request.put("threshold", 300);
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        try {
            Node24Images.parseThreshold(request);
            fail("expected threshold > 255 to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("threshold"));
        }

        JSONArray offsets = new JSONArray();
        try {
            offsets.put(new JSONObject().put("offsetX", 1).put("offsetY", -2)
                    .put("color", 0xFF010203));
            request.put("offsetColors", offsets);
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        Node24Images.OffsetColor[] parsed = Node24Images.parseOffsetColors(request);
        assertEquals(1, parsed.length);
        assertEquals(1, parsed[0].offsetX);
        assertEquals(-2, parsed[0].offsetY);
        assertEquals(0xFF010203, parsed[0].color);

        try {
            Node24Images.parseOffsetColors(new JSONObject());
            fail("expected empty offsetColors to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("offsetColors"));
        }
    }

    @Test public void imagePathsMustBeAbsoluteAndNulFree() {
        File absolute = new File(System.getProperty("java.io.tmpdir"), "node24-image.png");
        assertEquals(absolute, Node24Images.safeFile(absolute.getAbsolutePath()));
        for (String invalid : new String[]{"relative.png", "", absolute.getAbsolutePath() + "\0bad"}) {
            try {
                Node24Images.safeFile(invalid);
                fail("expected invalid path to fail: " + invalid);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    @Test public void configuredPixelBudgetRejectsOversizedImage() {
        Node24Images images = new Node24Images(2, 100, 200);
        images.checkDimensions(10, 10); // exactly at the budget
        try {
            images.checkDimensions(11, 10);
            fail("expected pixel budget to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("上限"));
        }
    }

    @Test public void defaultImagePixelCapIsSixteenMillion() {
        Node24Images images = new Node24Images();
        images.checkDimensions(4000, 4000); // exactly 16M
        try {
            images.checkDimensions(4001, 4000);
            fail("expected default 16M cap to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("上限"));
        }
    }

    @Test public void registerBudgetRejectsTotalPixelLimit() {
        Node24Images images = new Node24Images(64, 16_000_000L, 150L);
        images.checkRegisterBudget(20, 7); // 140 pixels, under total budget
        try {
            images.checkRegisterBudget(20, 8); // 160 pixels, over total budget
            fail("expected total pixel budget to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("总像素"));
        }
    }

    @Test public void recycleUnknownHandleIsIdempotent() {
        Node24Images images = new Node24Images();
        JSONObject request = new JSONObject();
        try {
            request.put("action", "recycle").put("id", 999);
        } catch (Exception impossible) {
            fail(impossible.toString());
        }
        JSONObject response = images.handle(request);
        assertTrue(response.optBoolean("ok"));
        assertTrue(response.optBoolean("value"));
        images.close();
        images.close();
    }
}
