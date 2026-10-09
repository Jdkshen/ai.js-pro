package com.stardust.autojs.core.image;

import org.junit.Test;
import static org.junit.Assert.*;

public class SiftMatcherGeometryTest {
    @Test public void acceptsRotatedConvexTarget() {
        double[][] quad = {{30, 10}, {50, 30}, {30, 50}, {10, 30}};
        assertArrayEquals(new double[]{90, 90, 90, 90}, SiftMatcher.validQuad(quad, 30, 150), 0.001);
    }
    @Test public void rejectsCrossedAndCollapsedTargets() {
        assertNull(SiftMatcher.validQuad(new double[][]{{0,0},{100,100},{0,100},{100,0}}, 0, 180));
        assertNull(SiftMatcher.validQuad(new double[][]{{0,0},{100,0},{100,0},{0,0}}, 0, 180));
    }
    @Test public void rejectsNonfiniteAndTinyTargets() {
        assertNull(SiftMatcher.validQuad(new double[][]{{Double.NaN,0},{100,0},{100,100},{0,100}}, 0, 180));
        assertNull(SiftMatcher.validQuad(new double[][]{{0,0},{2,0},{2,2},{0,2}}, 0, 180));
    }
    @Test public void enforcesPerspectiveAngleLimits() {
        double[][] quad = {{0,0},{100,0},{220,20},{120,20}};
        assertNull(SiftMatcher.validQuad(quad, 30, 150));
        assertNotNull(SiftMatcher.validQuad(quad, 0, 180));
    }
    @Test public void regionRejectsIntegerOverflowAndEmptyExtent() {
        try {
            SiftMatcher.checkedRegion(Integer.MAX_VALUE - 10, 0, 20, 10, Integer.MAX_VALUE, 100);
            fail("Overflowing region must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            SiftMatcher.checkedRegion(0, 0, 0, 10, 100, 100);
            fail("Empty region must be rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
