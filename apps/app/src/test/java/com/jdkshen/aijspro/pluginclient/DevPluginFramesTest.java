package com.jdkshen.aijspro.pluginclient;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DevPluginFramesTest {

    @Test
    public void encodeUsesLengthThenTypeHeader() throws Exception {
        byte[] frame = DevPluginFrames.encode(DevPluginFrames.TYPE_JSON, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(11, frame.length);
        assertEquals(3, frame[3]);          // payload length, big endian
        assertEquals(0, frame[0]);
        assertEquals(1, frame[7]);          // frame type at offset 4..7
        assertEquals('a', frame[8]);
    }

    @Test
    public void readRoundTripsConsecutiveFrames() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(DevPluginFrames.encodeJson("{\"type\":\"hello\"}"));
        stream.write(DevPluginFrames.encode(DevPluginFrames.TYPE_BYTES, new byte[]{1, 2, 3}));

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(stream.toByteArray()));
        DevPluginFrames.Frame first = DevPluginFrames.read(in);
        DevPluginFrames.Frame second = DevPluginFrames.read(in);

        assertEquals(DevPluginFrames.TYPE_JSON, first.type);
        assertEquals("{\"type\":\"hello\"}", DevPluginFrames.parseJson(first));
        assertEquals(DevPluginFrames.TYPE_BYTES, second.type);
        assertArrayEquals(new byte[]{1, 2, 3}, DevPluginFrames.parseBytes(second));
    }

    @Test
    public void readHandlesTrickledStream() throws Exception {
        byte[] frame = DevPluginFrames.encodeJson("{\"ok\":true}");
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(frame);
        stream.write(frame);

        DataInputStream in = new DataInputStream(new TrickleInputStream(stream.toByteArray()));
        DevPluginFrames.Frame first = DevPluginFrames.read(in);
        DevPluginFrames.Frame second = DevPluginFrames.read(in);
        assertEquals("{\"ok\":true}", DevPluginFrames.parseJson(first));
        assertEquals("{\"ok\":true}", DevPluginFrames.parseJson(second));
    }

    @Test
    public void gzipFramesSurviveRoundTrip() throws Exception {
        byte[] json = "{\"type\":\"log\"}".getBytes(StandardCharsets.UTF_8);
        byte[] zippedJson = gzip(json);
        assertEquals("{\"type\":\"log\"}",
                DevPluginFrames.parseJson(new DevPluginFrames.Frame(DevPluginFrames.TYPE_GZIP_JSON, zippedJson)));

        byte[] payload = "project zip".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(payload,
                DevPluginFrames.parseBytes(new DevPluginFrames.Frame(DevPluginFrames.TYPE_GZIP_BYTES, gzip(payload))));
    }

    @Test
    public void emptyPayloadIsAllowed() throws Exception {
        DevPluginFrames.Frame frame = DevPluginFrames.read(
                new DataInputStream(new ByteArrayInputStream(DevPluginFrames.encode(DevPluginFrames.TYPE_JSON, new byte[0]))));
        assertEquals(0, frame.payload.length);
        assertEquals("", DevPluginFrames.parseJson(frame));
    }

    @Test
    public void md5HexMatchesKnownVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                DevPluginFrames.md5Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test(expected = IOException.class)
    public void parseJsonRejectsBytesFrame() throws Exception {
        DevPluginFrames.parseJson(new DevPluginFrames.Frame(DevPluginFrames.TYPE_BYTES, new byte[0]));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }

    /** Returns one byte per read() call to exercise DataInputStream.readFully buffering. */
    private static final class TrickleInputStream extends InputStream {

        private final byte[] data;
        private int position;

        TrickleInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            buffer[offset] = data[position++];
            return 1;
        }
    }
}
