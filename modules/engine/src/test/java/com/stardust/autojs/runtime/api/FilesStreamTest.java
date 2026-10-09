package com.stardust.autojs.runtime.api;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class FilesStreamTest {
    private static class PartialStream extends ByteArrayInputStream {
        boolean closed;
        PartialStream(byte[] bytes) { super(bytes); }
        @Override public int available() { return 0; }
        @Override public synchronized int read(byte[] buffer, int offset, int length) {
            return super.read(buffer, offset, Math.min(2, length));
        }
        @Override public void close() { closed = true; }
    }

    @Test public void readTextConsumesPartialStreamToEofAndClosesIt() {
        Files files = new Files(null);
        PartialStream stream = new PartialStream("文本内容abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("文本内容abc", files.read(stream));
        assertTrue(stream.closed);
    }

    @Test public void readBytesConsumesToEofAndLeavesOwnershipToCaller() {
        byte[] expected = {0, 1, 2, (byte) 255, 4, 5};
        PartialStream stream = new PartialStream(expected);
        assertArrayEquals(expected, new Files(null).readBytes(stream));
        assertFalse(stream.closed);
    }

    @Test public void writeSupportsOutputStreamEncodingAndClosesIt() {
        boolean[] closed = {false};
        ByteArrayOutputStream stream = new ByteArrayOutputStream() {
            @Override public void close() { closed[0] = true; }
        };
        new Files(null).write(stream, "文本", "UTF-16LE");
        assertArrayEquals("文本".getBytes(StandardCharsets.UTF_16LE), stream.toByteArray());
        assertTrue(closed[0]);
    }
}