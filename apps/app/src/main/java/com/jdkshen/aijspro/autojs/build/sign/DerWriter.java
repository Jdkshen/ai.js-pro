package com.jdkshen.aijspro.autojs.build.sign;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Minimal ASN.1 DER encoder covering the pieces APK/JAR signing needs.
 *
 * <p>Deliberately dependency free: the app has to run this on API 21 devices, where neither
 * apksig (uses {@code java.util.function}/{@code java.util.Base64}) nor BouncyCastle
 * (adds megabytes) is a good fit.
 */
final class DerWriter {

    static final byte[] NULL = {0x05, 0x00};

    private static final String ASCII = "US-ASCII";

    private DerWriter() {
    }

    static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length <= 0xFF) {
            out.write(0x81);
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(0x82);
            out.write(length >>> 8);
            out.write(length);
        } else {
            out.write(0x83);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    /** {@code [index] EXPLICIT}. */
    static byte[] explicit(int index, byte[] content) {
        return tlv(0xA0 + index, content);
    }

    /** {@code [index] IMPLICIT SEQUENCE OF}, used for the PKCS#7 certificate bag. */
    static byte[] implicitSet(int index, byte[]... parts) {
        return tlv(0xA0 + index, concat(parts));
    }

    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    static byte[] bool(boolean value) {
        return new byte[]{0x01, 0x01, (byte) (value ? 0xFF : 0x00)};
    }

    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            byte[] buf = new byte[9];
            int n = 0;
            buf[n++] = (byte) (value & 0x7F);
            value >>>= 7;
            while (value > 0) {
                buf[n++] = (byte) (0x80 | (value & 0x7F));
                value >>>= 7;
            }
            for (int j = n - 1; j >= 0; j--) {
                body.write(buf[j]);
            }
        }
        return tlv(0x06, body.toByteArray());
    }

    /** AlgorithmIdentifier with NULL parameters. */
    static byte[] algorithm(String oid, boolean withNullParameters) {
        return withNullParameters ? sequence(oid(oid), NULL) : sequence(oid(oid));
    }

    static byte[] octetString(byte[] value) {
        return tlv(0x04, value);
    }

    static byte[] bitString(byte[] value) {
        return tlv(0x03, concat(new byte[]{0}, value));
    }

    static byte[] utf8String(String value) {
        try {
            return tlv(0x0C, value.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utcTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return tlv(0x17, format.format(date).getBytes(ASCII));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
