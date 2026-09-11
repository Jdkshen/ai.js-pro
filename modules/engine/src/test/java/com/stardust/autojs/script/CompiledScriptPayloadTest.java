package com.stardust.autojs.script;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 编译型脚本载荷与文件头载荷类型的单测（纯 JVM）。
 */
public class CompiledScriptPayloadTest {

    @Test
    public void payloadRoundTripKeepsClassNameAndBytes() throws Exception {
        byte[] classBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 1, 2, 3};
        String className = "org.mozilla.javascript.gen.main_js";

        byte[] encoded = new CompiledScriptPayload(className, classBytes).toBytes();
        CompiledScriptPayload decoded = CompiledScriptPayload.read(encoded);

        assertEquals(className, decoded.className);
        assertArrayEquals(classBytes, decoded.classBytes);
    }

    @Test
    public void payloadRejectsBrokenInput() {
        // 长度够但标识不对：应当报「标识」而不是被当成截断
        byte[] wrongMagic = new byte[16];
        java.util.Arrays.fill(wrongMagic, (byte) 'X');
        try {
            CompiledScriptPayload.read(wrongMagic);
            fail("载荷标识不对时应当报错");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("标识"));
        }
        try {
            CompiledScriptPayload.read(null);
            fail("空载荷应当报错");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("过短"));
        }
        try {
            CompiledScriptPayload.read(new byte[]{'A', 'S', 'C', 'P', 1});
            fail("过短的载荷应当报错");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("过短"));
        }
    }

    @Test
    public void payloadRejectsTruncatedClassBytes() throws Exception {
        byte[] encoded = new CompiledScriptPayload("gen.A", new byte[]{1, 2, 3, 4, 5}).toBytes();
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 2);
        try {
            CompiledScriptPayload.read(truncated);
            fail("类字节被截断时应当报错");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("类字节"));
        }
    }

    @Test
    public void headerCarriesPayloadTypeWithoutLosingLowByteFlags() {
        short plain = EncryptedScriptFileHeader.INSTANCE.flagsWithPayloadType(
                EncryptedScriptFileHeader.PAYLOAD_TYPE_TEXT);
        assertEquals(EncryptedScriptFileHeader.PAYLOAD_TYPE_TEXT,
                EncryptedScriptFileHeader.INSTANCE.payloadTypeOf(plain));

        short compiled = EncryptedScriptFileHeader.INSTANCE.flagsWithPayloadType(
                EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS);
        assertEquals(EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS,
                EncryptedScriptFileHeader.INSTANCE.payloadTypeOf(compiled));

        // 低字节留给执行模式：写载荷类型不能把执行模式标记冲掉。
        short withMode = EncryptedScriptFileHeader.INSTANCE.flagsWithPayloadType(
                EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS,
                EncryptedScriptFileHeader.FLAG_EXECUTION_MODE_UI);
        assertEquals(EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS,
                EncryptedScriptFileHeader.INSTANCE.payloadTypeOf(withMode));
        assertEquals(EncryptedScriptFileHeader.FLAG_EXECUTION_MODE_UI,
                (short) (withMode & 0xFF));
    }

    @Test
    public void headerFlagsCanBeReadFromMemory() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        EncryptedScriptFileHeader.INSTANCE.writeHeader(out,
                EncryptedScriptFileHeader.INSTANCE.flagsWithPayloadType(
                        EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS, (short) 1));
        byte[] bytes = out.toByteArray();

        assertEquals(EncryptedScriptFileHeader.BLOCK_SIZE, bytes.length);
        short flags = EncryptedScriptFileHeader.INSTANCE.readFlags(bytes);
        assertEquals(EncryptedScriptFileHeader.PAYLOAD_TYPE_RHINO_CLASS,
                EncryptedScriptFileHeader.INSTANCE.payloadTypeOf(flags));
        assertEquals(1, flags & 0xFF);
        assertFalse(EncryptedScriptFileHeader.INSTANCE.readFlags(new byte[]{1, 2, 3})
                != EncryptedScriptFileHeader.FLAG_INVALID_FILE);
    }
}
