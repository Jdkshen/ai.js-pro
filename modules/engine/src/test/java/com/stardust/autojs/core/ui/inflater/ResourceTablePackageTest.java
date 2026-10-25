package com.stardust.autojs.core.ui.inflater;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ResourceTablePackageTest {
    private byte[] table(String name) {
        ByteBuffer b = ByteBuffer.allocate(296).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 2).putShort((short) 12).putInt(296).putInt(1);
        b.putShort((short) 0x0200).putShort((short) 284).putInt(284).putInt(0x7e);
        b.put(name.getBytes(StandardCharsets.UTF_16LE));
        return b.array();
    }
    @Test public void actualTablePackageCanDifferFromManifest() throws Exception {
        assertEquals("com.example.feature", ResourceTablePackage.read(new ByteArrayInputStream(table("com.example.feature"))));
    }
    @Test public void supportsFull128CharacterName() throws Exception {
        String name = new String(new char[128]).replace('\0', 'x');
        assertEquals(name, ResourceTablePackage.read(new ByteArrayInputStream(table(name))));
    }
    @Test(expected = IOException.class) public void rejectsTruncatedPackage() throws Exception {
        ResourceTablePackage.read(new ByteArrayInputStream(java.util.Arrays.copyOf(table("x"), 28)));
    }
    @Test(expected = IOException.class) public void rejectsOutOfBoundsChunk() throws Exception {
        byte[] data = table("x");
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1000);
        ResourceTablePackage.read(new ByteArrayInputStream(data));
    }
    @Test(expected = IOException.class) public void rejectsUncompiledXml() throws Exception {
        ResourceTablePackage.read(new ByteArrayInputStream("<resources/>".getBytes(StandardCharsets.UTF_8)));
    }
}
