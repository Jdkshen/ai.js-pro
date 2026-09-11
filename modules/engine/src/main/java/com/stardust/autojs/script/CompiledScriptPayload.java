package com.stardust.autojs.script;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * 编译型脚本载荷（文件头 flags 的载荷类型为 {@code PAYLOAD_TYPE_RHINO_CLASS} 时使用）。
 *
 * <p>布局（加密前的明文，打包端写入 / 运行端解密后解析）：
 * <pre>
 *   magic  4 字节 "ASCP"
 *   version 1 字节 (=1)
 *   类名长度 4 字节（大端）
 *   类名 UTF-8
 *   类字节长度 4 字节（大端）
 *   类字节
 * </pre>
 *
 * <p>带上类名是因为 Rhino 自己决定生成类名（由脚本名派生），运行时必须用同一个名字
 * 才能把类加载起来。
 */
public final class CompiledScriptPayload {

    private static final byte[] MAGIC = new byte[]{'A', 'S', 'C', 'P'};
    private static final int VERSION = 1;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    public final String className;
    public final byte[] classBytes;

    public CompiledScriptPayload(String className, byte[] classBytes) {
        this.className = className;
        this.classBytes = classBytes;
    }

    /** 序列化为可以加密的字节。 */
    public byte[] toBytes() throws IOException {
        if (className == null || className.isEmpty()) {
            throw new IOException("编译载荷缺少类名");
        }
        byte[] name = className.getBytes(UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(classBytes.length + name.length + 16);
        out.write(MAGIC);
        out.write(VERSION);
        writeInt(out, name.length);
        out.write(name);
        writeInt(out, classBytes.length);
        out.write(classBytes);
        return out.toByteArray();
    }

    /** 解析；格式不对时抛 IllegalArgumentException（调用方会转成脚本错误）。 */
    public static CompiledScriptPayload read(byte[] bytes) {
        if (bytes == null || bytes.length < MAGIC.length + 1 + 8) {
            throw new IllegalArgumentException("编译载荷过短");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                throw new IllegalArgumentException("编译载荷标识不正确");
            }
        }
        int version = bytes[MAGIC.length] & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException("不支持的编译载荷版本：" + version);
        }
        int cursor = MAGIC.length + 1;
        int nameLength = readInt(bytes, cursor);
        cursor += 4;
        if (nameLength <= 0 || cursor + nameLength > bytes.length) {
            throw new IllegalArgumentException("编译载荷类名长度不合法：" + nameLength);
        }
        String className = new String(bytes, cursor, nameLength, UTF_8);
        cursor += nameLength;
        int classLength = readInt(bytes, cursor);
        cursor += 4;
        if (classLength <= 0 || cursor + classLength > bytes.length) {
            throw new IllegalArgumentException("编译载荷类字节长度不合法：" + classLength);
        }
        byte[] classBytes = Arrays.copyOfRange(bytes, cursor, cursor + classLength);
        return new CompiledScriptPayload(className, classBytes);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readInt(byte[] bytes, int offset) {
        if (offset + 4 > bytes.length) {
            throw new IllegalArgumentException("编译载荷被截断");
        }
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
