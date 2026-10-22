// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;
import static org.autojs.source.engine.android.JvmTestAssertions.assertThrows;

/** Pure-JVM checks for the Phase A raw screenshot format/header. */
public class RawScreenshotFormatTest {

    @Test public void headerRoundtripCarriesDimensionsAndRowBytes() {
        ByteBuffer header = RawScreenshotFormat.newHeader(1280, 2772);
        assertEquals(RawScreenshotFormat.HEADER_BYTES, header.remaining());
        byte[] bytes = new byte[RawScreenshotFormat.HEADER_BYTES];
        header.get(bytes);
        RawScreenshotFormat.Header parsed = RawScreenshotFormat.parseHeader(bytes);
        assertEquals(1280, parsed.width);
        assertEquals(2772, parsed.height);
        assertEquals(1280 * 4, parsed.rowBytes);
        assertEquals(RawScreenshotFormat.FORMAT_ARGB_8888, parsed.format);
        assertEquals((long) parsed.rowBytes * parsed.height, RawScreenshotFormat.pixelBytes(parsed));
    }

    @Test public void parseHeaderRejectsBadMagicVersionAndFormat() {
        byte[] good = headerBytes(100, 50);
        assertInvalid(good, 0);   // magic
        assertInvalid(good, 4);   // version
        assertInvalid(good, 20);  // format
    }

    @Test public void parseHeaderRejectsBadRowBytesAndDimensions() {
        byte[] rowBytes = headerBytes(100, 50);
        rowBytes[16] = (byte) 0x01; // rowBytes 100*4=400 -> 401
        assertThrows(IllegalArgumentException.class, () -> RawScreenshotFormat.parseHeader(rowBytes));

        byte[] width = headerBytes(100, 50);
        width[8] = 0; width[9] = 0; width[10] = 0; width[11] = 0;
        assertThrows(IllegalArgumentException.class, () -> RawScreenshotFormat.parseHeader(width));

        byte[] height = headerBytes(100, 50);
        height[12] = 0; height[13] = 0; height[14] = 0; height[15] = 0;
        assertThrows(IllegalArgumentException.class, () -> RawScreenshotFormat.parseHeader(height));

        assertThrows(IllegalArgumentException.class, () -> RawScreenshotFormat.parseHeader(new byte[10]));
        assertThrows(IllegalArgumentException.class, () -> RawScreenshotFormat.parseHeader(null));
    }

    @Test public void validateLengthAcceptsExactAndRejectsTruncatedOrOversized() {
        RawScreenshotFormat.Header header = RawScreenshotFormat.parseHeader(headerBytes(100, 50));
        long expected = RawScreenshotFormat.HEADER_BYTES + RawScreenshotFormat.pixelBytes(header);
        RawScreenshotFormat.validateLength(header, expected);
        assertThrows(IllegalArgumentException.class,
                () -> RawScreenshotFormat.validateLength(header, expected - 1));
        assertThrows(IllegalArgumentException.class,
                () -> RawScreenshotFormat.validateLength(header, expected + 1));
    }

    private static byte[] headerBytes(int width, int height) {
        ByteBuffer header = RawScreenshotFormat.newHeader(width, height);
        byte[] bytes = new byte[RawScreenshotFormat.HEADER_BYTES];
        header.get(bytes);
        return bytes;
    }

    private static void assertInvalid(byte[] header, int offset) {
        byte[] copy = header.clone();
        copy[offset] = (byte) (copy[offset] + 1);
        assertThrows(IllegalArgumentException.class, () -> RawScreenshotFormat.parseHeader(copy));
    }
}
