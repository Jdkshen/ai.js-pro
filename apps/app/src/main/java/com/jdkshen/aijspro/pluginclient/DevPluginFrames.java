package com.jdkshen.aijspro.pluginclient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/**
 * Frame codec for the Auto.js Pro 9 "legacy" VSCode debug protocol (device side).
 *
 * <p>Wire format, both directions:
 * <pre>[int32 BE payload length][int32 BE frame type][payload]</pre>
 *
 * <p>Frame types: 1 = UTF-8 JSON, 2 = raw bytes, 3 = gzip JSON, 4 = gzip bytes.
 * The VS Code extension only compresses frames (types 3/4) when the device
 * advertised {@code server_version >= 4}; parsing accepts all four regardless.
 * Verification reference: modules 8012/5491 of the bundled extension.js of
 * {@code hyb1996.auto-js-pro-ext}.
 */
public final class DevPluginFrames {

    public static final int TYPE_JSON = 1;
    public static final int TYPE_BYTES = 2;
    public static final int TYPE_GZIP_JSON = 3;
    public static final int TYPE_GZIP_BYTES = 4;

    /** Safety cap so a malformed peer cannot force a huge allocation. */
    public static final int MAX_FRAME_LENGTH = 256 * 1024 * 1024;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static final class Frame {

        public final int type;
        public final byte[] payload;

        public Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    private DevPluginFrames() {
    }

    public static byte[] encode(int type, byte[] payload) {
        byte[] frame = new byte[8 + payload.length];
        putInt32(frame, 0, payload.length);
        putInt32(frame, 4, type);
        System.arraycopy(payload, 0, frame, 8, payload.length);
        return frame;
    }

    public static byte[] encodeJson(String json) {
        return encode(TYPE_JSON, json.getBytes(StandardCharsets.UTF_8));
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

    public static String parseJson(Frame frame) throws IOException {
        switch (frame.type) {
            case TYPE_JSON:
                return new String(frame.payload, StandardCharsets.UTF_8);
            case TYPE_GZIP_JSON:
                return new String(gunzip(frame.payload), StandardCharsets.UTF_8);
            default:
                throw new IOException("not a json frame: type " + frame.type);
        }
    }

    public static byte[] parseBytes(Frame frame) throws IOException {
        switch (frame.type) {
            case TYPE_BYTES:
                return frame.payload;
            case TYPE_GZIP_BYTES:
                return gunzip(frame.payload);
            default:
                throw new IOException("not a bytes frame: type " + frame.type);
        }
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

    public static String md5Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void putInt32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >> 24);
        target[offset + 1] = (byte) (value >> 16);
        target[offset + 2] = (byte) (value >> 8);
        target[offset + 3] = (byte) value;
    }
}
