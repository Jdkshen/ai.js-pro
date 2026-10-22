// SPDX-License-Identifier: MIT
package com.stardust.autojs.core.image.capture;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.autojs.source.engine.android.JvmTestAssertions.assertThrows;

/** Pure-JVM checks for AccessibilityScreenCapturer.scaleSize (缩略回读尺寸计算；不创建 Bitmap). */
public class AccessibilityScreenCapturerTest {

    @Test public void noLimitOrNegativeLimitKeepsFullResolution() {
        assertArrayEquals(new int[]{1280, 2772},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, 0, 0));
        assertArrayEquals(new int[]{1280, 2772},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, -1, -1));
        assertArrayEquals(new int[]{1280, 2772},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, -640, 0));
    }

    @Test public void widthOnlyLimitScalesProportionally() {
        assertArrayEquals(new int[]{640, 1386},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, 640, 0));
    }

    @Test public void heightOnlyLimitScalesProportionally() {
        assertArrayEquals(new int[]{640, 1386},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, 0, 1386));
    }

    @Test public void bothLimitsUseSmallerRatio() {
        // 1280x2772 限制 640x1000：高度比例更小（1000/2772≈0.3607）→ 宽 462、高 1000。
        assertArrayEquals(new int[]{462, 1000},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, 640, 1000));
    }

    @Test public void neverUpscalesBeyondOriginal() {
        assertArrayEquals(new int[]{1280, 2772},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, 4000, 4000));
        assertArrayEquals(new int[]{1280, 2772},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, 1280, 2772));
    }

    @Test public void roundsToNearestPixelAndKeepsAtLeastOnePixel() {
        assertArrayEquals(new int[]{333, 333},
                AccessibilityScreenCapturer.scaleSize(1000, 1000, 333, 333));
        assertArrayEquals(new int[]{2, 2},
                AccessibilityScreenCapturer.scaleSize(3, 3, 2, 2));
        assertArrayEquals(new int[]{2, 1},
                AccessibilityScreenCapturer.scaleSize(1000, 3, 2, 0));
        assertArrayEquals(new int[]{1, 1},
                AccessibilityScreenCapturer.scaleSize(1280, 2772, 1, 1));
    }

    @Test public void nearFullScaleUsesFullResolutionThreshold() {
        // 999/1000 = 0.999 >= 0.999f：无意义的缩略直接走全分辨率 copy。
        assertArrayEquals(new int[]{1000, 1000},
                AccessibilityScreenCapturer.scaleSize(1000, 1000, 999, 999));
    }

    @Test public void rejectsNonPositiveSourceDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> AccessibilityScreenCapturer.scaleSize(0, 10, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> AccessibilityScreenCapturer.scaleSize(-5, 10, 100, 100));
    }
}
