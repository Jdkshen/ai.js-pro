package com.stardust.autojs.core.ui.inflater;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads the package name from a compiled resource table, independently of its manifest. */
final class ResourceTablePackage {
    static String read(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(source);
        int type = u16(in), header = u16(in);
        long size = u32(in);
        if (type != 2 || header < 12 || size < header) throw new IOException("无效的 resources.arsc 表头");
        skip(in, header - 8);
        long remaining = size - header;
        while (remaining >= 8) {
            int chunk = u16(in), chunkHeader = u16(in);
            long chunkSize = u32(in);
            if (chunkHeader < 8 || chunkSize < chunkHeader || chunkSize > remaining) {
                throw new IOException("无效的 resources.arsc 区段");
            }
            if (chunk == 0x0200) {
                if (chunkHeader < 268) throw new IOException("资源包头过短");
                u32(in); // package id
                byte[] name = new byte[256];
                in.readFully(name);
                int end = 0;
                while (end < name.length && (name[end] != 0 || name[end + 1] != 0)) end += 2;
                String value = new String(name, 0, end, StandardCharsets.UTF_16LE);
                if (value.isEmpty()) throw new IOException("资源包名为空");
                return value;
            }
            skip(in, chunkSize - 8);
            remaining -= chunkSize;
        }
        throw new IOException("resources.arsc 中没有资源包");
    }
    private static int u16(DataInputStream in) throws IOException { return in.readUnsignedByte() | (in.readUnsignedByte() << 8); }
    private static long u32(DataInputStream in) throws IOException { return (u16(in) | ((long) u16(in) << 16)); }
    private static void skip(DataInputStream in, long count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped > 0) count -= skipped;
            else { in.readByte(); count--; }
        }
    }
    private ResourceTablePackage() {}
}
