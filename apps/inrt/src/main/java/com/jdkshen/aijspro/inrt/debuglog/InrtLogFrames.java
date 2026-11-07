package com.jdkshen.aijspro.inrt.debuglog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Frame codec for the packaged (=inrt) VSCode log bridge.
 *
 * <p>Wire format, both directions: {@code [int32 BE payload length][int32 BE frame type][payload]}.
 * Type 1 is UTF-8 JSON, type 3 is gzip JSON. The host app's DevPluginFrames is the
 * compatibility authority; this class intentionally implements only the read/JSON subset
 * needed by the log-only bridge (no command or bytes frame support).
 */
public final class InrtLogFrames {

    public static final int TYPE_JSON = 1;
    public static final int TYPE_GZIP_JSON = 3;

    /** Safety cap so a malformed peer cannot force a huge allocation. */
    public static final int MAX_FRAME_LENGTH = 256 * 1024 * 1024;

    public static final class Frame {
        public final int type;
        public final byte[] payload;

        public Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private InrtLogFrames() {
    }

    public static byte[] encode(int type, byte[] payload) {
        if (payload == null) {
            payload = new byte[0];
        }
        if (payload.length > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("frame too large: " + payload.length);
        }
        byte[] frame = new byte[8 + payload.length];
        putInt32(frame, 0, payload.length);
        putInt32(frame, 4, type);
        System.arraycopy(payload, 0, frame, 8, payload.length);
        return frame;
    }

    public static byte[] encodeJson(String json) {
        return encode(TYPE_JSON, String.valueOf(json == null ? "" : json)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Reads exactly one frame; throws EOFException when the peer closed the stream cleanly. */
    public static Frame read(DataInputStream in) throws IOException {
        int length = in.readInt();
        int type = in.readInt();
        if (length < 0 || length > MAX_FRAME_LENGTH) {
            throw new IOException("invalid frame length: " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return new Frame(type, payload);
    }

    /** Parses a JSON frame (type 1 or type 3); other frame types are rejected. */
    public static String parseJson(Frame frame) throws IOException {
        if (frame == null) {
            throw new IOException("null frame");
        }
        if (frame.type == TYPE_JSON) {
            return new String(frame.payload, StandardCharsets.UTF_8);
        }
        if (frame.type == TYPE_GZIP_JSON) {
            return new String(gunzip(frame.payload), StandardCharsets.UTF_8);
        }
        throw new IOException("not a JSON frame: type " + frame.type);
    }

    public static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void putInt32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >> 24);
        target[offset + 1] = (byte) (value >> 16);
        target[offset + 2] = (byte) (value >> 8);
        target[offset + 3] = (byte) value;
    }
}
