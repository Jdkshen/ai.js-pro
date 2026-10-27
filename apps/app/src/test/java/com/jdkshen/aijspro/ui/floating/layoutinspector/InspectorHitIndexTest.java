package com.jdkshen.aijspro.ui.floating.layoutinspector;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class InspectorHitIndexTest {
    private InspectorHitIndex.Item<String> box(String id, int left, int top, int right, int bottom) {
        return new InspectorHitIndex.Item<>(id, left, top, right, bottom);
    }

    @Test public void preservesRootAndChildrenOutsideParentBounds() {
        InspectorHitIndex<String> index = new InspectorHitIndex<>(Arrays.asList(
                box("root", -100, -100, 200, 200), box("parent", 0, 0, 10, 10),
                box("outside-child", 40, 40, 60, 60)));
        assertEquals("outside-child", index.find(50, 50));
        assertEquals("root", index.find(-50, -50));
        assertNull(index.find(200, 200));
    }

    @Test public void negativeAndRightBottomExclusiveBoundsRemainExact() {
        InspectorHitIndex<String> index = new InspectorHitIndex<>(Collections.singletonList(box("negative", -25, -10, 5, 20)));
        assertEquals("negative", index.find(-25, -10));
        assertEquals("negative", index.find(4, 19));
        assertNull(index.find(5, 19)); assertNull(index.find(4, 20));
        assertNull(index.find(-26, -10));
    }

    @Test public void selectsSmallestAndLaterEqualAreaAfterBvhReordering() {
        List<InspectorHitIndex.Item<String>> boxes = new ArrayList<>();
        boxes.add(box("first", 5, 5, 25, 25));
        for (int i = 0; i < 40; i++) boxes.add(box("filler" + i, i * 50, 100, i * 50 + 10, 110));
        boxes.add(box("later", 0, 0, 20, 20));
        assertEquals("later", new InspectorHitIndex<>(boxes).find(10, 10));
        boxes.add(box("small", 8, 8, 12, 12));
        assertEquals("small", new InspectorHitIndex<>(boxes).find(10, 10));
    }

    @Test public void invalidEmptyBoundsCannotWinAndEmptyIndexIsUsable() {
        assertNull(new InspectorHitIndex<String>(Collections.emptyList()).find(0, 0));
        InspectorHitIndex<String> index = new InspectorHitIndex<>(Arrays.asList(
                box("zero", 0, 0, 0, 0), box("inverted", 20, 20, -20, -20), box("valid", -2, -2, 2, 2)));
        assertEquals("valid", index.find(0, 0));
    }

    @Test public void extremeCoordinatesAndAreasDoNotOverflowOrdering() {
        InspectorHitIndex<String> index = new InspectorHitIndex<>(Arrays.asList(
                box("largest", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
                box("smaller-unsigned-area", Integer.MIN_VALUE, -2000000000, Integer.MAX_VALUE, 2000000000)));
        assertEquals("smaller-unsigned-area", index.find(0, 0));
        index = new InspectorHitIndex<>(Arrays.asList(
                box("large", -100000, -100000, 100000, 100000), box("thin", -100000, -1, 100000, 1)));
        assertEquals("thin", index.find(0, 0));
    }

    @Test public void twentyThousandGridNodesAvoidFullScanWithoutTimingAssertions() {
        List<InspectorHitIndex.Item<Integer>> boxes = new ArrayList<>();
        boxes.add(new InspectorHitIndex.Item<>(-1, 0, 0, 4000, 2000));
        for (int row = 0; row < 100; row++) for (int column = 0; column < 200; column++)
            boxes.add(new InspectorHitIndex.Item<>(row * 200 + column, column * 20, row * 20, column * 20 + 10, row * 20 + 10));
        InspectorHitIndex<Integer> index = new InspectorHitIndex<>(boxes);
        InspectorHitIndex.QueryStats stats = new InspectorHitIndex.QueryStats();
        assertEquals(Integer.valueOf(77 * 200 + 123), index.find(123 * 20 + 3, 77 * 20 + 4, stats));
        assertTrue("tested " + stats.testedItems, stats.testedItems < 200);
        System.out.println("InspectorHitIndex: 20001 boxes; testedItems=" + stats.testedItems + "; visitedBranches=" + stats.visitedBranches);
    }

    @Test public void overlappingGeometryCorrectlyDegradesAndMatchesReferenceChoice() {
        List<InspectorHitIndex.Item<Integer>> boxes = new ArrayList<>();
        for (int i = 0; i < 2000; i++) boxes.add(new InspectorHitIndex.Item<>(i, -100, -100, 100, 100));
        assertEquals(Integer.valueOf(1999), new InspectorHitIndex<>(boxes).find(0, 0));
    }

    @Test public void randomPointQueriesMatchIndependentAreaReference() {
        Random random = new Random(917301);
        List<InspectorHitIndex.Item<Integer>> boxes = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            int left = random.nextInt(2000) - 1000, top = random.nextInt(2000) - 1000;
            boxes.add(new InspectorHitIndex.Item<>(i, left, top, left + 1 + random.nextInt(500), top + 1 + random.nextInt(500)));
        }
        InspectorHitIndex<Integer> index = new InspectorHitIndex<>(boxes);
        for (int n = 0; n < 600; n++) {
            int x = random.nextInt(2500) - 1200, y = random.nextInt(2500) - 1200;
            Integer expected = null; long smallest = Long.MAX_VALUE;
            for (InspectorHitIndex.Item<Integer> b : boxes) {
                if (x >= b.left && x < b.right && y >= b.top && y < b.bottom) {
                    long area = ((long) b.right - b.left) * ((long) b.bottom - b.top);
                    if (area <= smallest) { smallest = area; expected = b.value; }
                }
            }
            assertEquals("query " + x + "," + y, expected, index.find(x, y));
        }
    }
}
