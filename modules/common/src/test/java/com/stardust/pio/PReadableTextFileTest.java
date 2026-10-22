package com.stardust.pio;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PReadableTextFileTest {
    @Test public void lineAndChunkReadsShareOneDecodedCursor() throws Exception {
        File file = File.createTempFile("aijs-reader-", ".txt");
        try {
            Files.write(file.toPath(), "第一行\n第二行\n".getBytes(StandardCharsets.UTF_8));
            try (PReadableTextFile reader = new PReadableTextFile(file.getPath())) {
                assertEquals("第一行", reader.readline());
                assertEquals("第二", reader.read(2));
                assertEquals("行\n", reader.read());
                assertEquals("", reader.read(1));
                assertNull(reader.readline());
            }
        } finally { assertTrue(file.delete()); }
    }

    @Test public void encodedReadlinesStopsAtEof() throws Exception {
        File file = File.createTempFile("aijs-reader-", ".txt");
        try {
            Files.write(file.toPath(), "中文\r\n末行".getBytes("GBK"));
            try (PReadableTextFile reader = new PReadableTextFile(file.getPath(), "GBK")) {
                assertArrayEquals(new String[]{"中文", "末行"}, reader.readlines());
                assertArrayEquals(new String[0], reader.readlines());
                assertEquals("", reader.read());
            }
        } finally { assertTrue(file.delete()); }
    }
}
