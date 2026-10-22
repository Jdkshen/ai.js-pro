// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 宿主无障碍截图 Raw 像素格式（Phase A）。
 *
 * <p>固定 32 字节小端头 + 连续 ARGB_8888 像素：magic(4) version(4) width(4)
 * height(4) rowBytes(4) format(4) reserved(8)。像素写入/读取两侧都只用
 * {@code Bitmap.copyPixelsToBuffer/FromBuffer}，不重新解释通道顺序。
 *
 * <p>这里只做纯 JVM 的头解析/校验，不持有 Bitmap；宿主写入与 Node 读取共用同一套校验。
 */
public final class RawScreenshotFormat {

    public static final int MAGIC = 0x4E323452; // "N24R"
    public static final int VERSION = 1;
    public static final int FORMAT_ARGB_8888 = 1;
    public static final int HEADER_BYTES = 32;

    private RawScreenshotFormat() {
    }

    public static final class Header {
        public final int width;
        public final int height;
        public final int rowBytes;
        public final int format;

        Header(int width, int height, int rowBytes, int format) {
            this.width = width;
            this.height = height;
            this.rowBytes = rowBytes;
            this.format = format;
        }
    }

    /** 生成完整 32 字节头（调用方随后 flip 再写）。 */
    public static ByteBuffer newHeader(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("截图尺寸无效：" + width + "x" + height);
        }
        int rowBytes = width * 4;
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC).putInt(VERSION)
                .putInt(width).putInt(height)
                .putInt(rowBytes).putInt(FORMAT_ARGB_8888)
                .putLong(0L);
        header.flip();
        return header;
    }

    /** 解析并校验 32 字节头；非法时抛 IllegalArgumentException（消息可直接回给脚本）。 */
    public static Header parseHeader(byte[] header) {
        if (header == null || header.length < HEADER_BYTES) {
            throw new IllegalArgumentException("Raw 截图头不完整：需要 32 字节");
        }
        ByteBuffer buffer = ByteBuffer.wrap(header, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buffer.getInt();
        int version = buffer.getInt();
        int width = buffer.getInt();
        int height = buffer.getInt();
        int rowBytes = buffer.getInt();
        int format = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Raw 截图 magic 不匹配");
        }
        if (version != VERSION) {
            throw new IllegalArgumentException("Raw 截图版本不支持：" + version);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Raw 截图尺寸无效：" + width + "x" + height);
        }
        if (rowBytes != width * 4) {
            throw new IllegalArgumentException("Raw 截图 rowBytes 无效：" + rowBytes + "（期望 " + (width * 4) + "）");
        }
        if (format != FORMAT_ARGB_8888) {
            throw new IllegalArgumentException("Raw 截图格式不支持：" + format);
        }
        return new Header(width, height, rowBytes, format);
    }

    public static long pixelBytes(Header header) {
        return (long) header.rowBytes * (long) header.height;
    }

    /** 校验文件/流长度必须精确等于头 + 像素，避免读到截断或超长文件。 */
    public static void validateLength(Header header, long totalBytes) {
        long expected = HEADER_BYTES + pixelBytes(header);
        if (totalBytes != expected) {
            throw new IllegalArgumentException("Raw 截图长度无效：实际 " + totalBytes + " 字节，期望 " + expected);
        }
    }
}
