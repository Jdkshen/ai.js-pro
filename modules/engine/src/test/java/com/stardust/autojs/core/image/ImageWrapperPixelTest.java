package com.stardust.autojs.core.image;

import com.stardust.autojs.core.opencv.Mat;
import com.stardust.util.ResourceMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class ImageWrapperPixelTest {
    private boolean monitoring;

    @Before public void disableNativeResourceMonitoring() {
        monitoring = ResourceMonitor.isEnabled();
        ResourceMonitor.setEnabled(false);
    }

    @After public void restoreMonitoring() {
        ResourceMonitor.setEnabled(monitoring);
    }

    @Test public void nonSquareMatUsesRowThenColumn() {
        PixelMat mat = new PixelMat(5, 2, new double[]{10, 20, 30, 40});
        assertEquals(0x280a141e, ImageWrapper.ofMat(mat).pixel(4, 1));
        assertEquals(1, mat.lastRow);
        assertEquals(4, mat.lastColumn);
    }

    @Test public void grayscaleAndRgbHaveOpaqueAlpha() {
        assertEquals(0xff7b7b7b, image(new double[]{123}).pixel(0, 0));
        assertEquals(0xff0b1621, image(new double[]{11, 22, 33}).pixel(0, 0));
    }

    @Test public void rgbaPreservesAlpha() {
        assertEquals(0x800b1621, image(new double[]{11, 22, 33, 128}).pixel(0, 0));
    }

    @Test public void invalidCoordinatesFailBeforeReadingMat() {
        PixelMat mat = new PixelMat(5, 2, new double[]{1, 2, 3, 4});
        ImageWrapper image = ImageWrapper.ofMat(mat);
        for (int[] point : new int[][]{{-1, 0}, {0, -1}, {5, 0}, {0, 2}}) {
            try {
                image.pixel(point[0], point[1]);
                fail("Expected a coordinate error");
            } catch (ArrayIndexOutOfBoundsException expected) {
                assertTrue(expected.getMessage().contains("out of bounds"));
            }
        }
        assertEquals(0, mat.reads);
    }

    @Test public void unsupportedChannelsFailClearly() {
        for (double[] channels : new double[][]{null, new double[0], new double[2]}) {
            try {
                image(channels).pixel(0, 0);
                fail("Expected unsupported pixel data to fail");
            } catch (IllegalStateException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    private static ImageWrapper image(double[] channels) {
        return ImageWrapper.ofMat(new PixelMat(1, 1, channels));
    }

    // A fake Mat lets the actual ImageWrapper API run without loading Android JNI.
    private static final class PixelMat extends Mat {
        private final int width;
        private final int height;
        private final double[] channels;
        int lastRow = -1;
        int lastColumn = -1;
        int reads;

        PixelMat(int width, int height, double[] channels) {
            super(1L);
            this.width = width;
            this.height = height;
            this.channels = channels;
        }

        @Override public int cols() { return width; }
        @Override public int rows() { return height; }
        @Override public double[] get(int row, int column) {
            lastRow = row;
            lastColumn = column;
            reads++;
            return channels;
        }
        @Override protected void finalize() { }
    }
}
