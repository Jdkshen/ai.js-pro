package com.stardust.autojs.script;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * 「脚本载荷嵌进原生库」尾部格式的单测。
 *
 * <p>打包端负责写、原生代码负责读（格式定义见 {@link EmbeddedScriptFooter}），
 * 这里锁住格式与校验行为：真机上 native 的解析结果还要与这份 Java 实现逐字节一致。
 */
public class EmbeddedScriptFooterTest {

    private static final byte[] LIBRARY = "fake-elf-library".getBytes();

    @Test
    public void payloadSurvivesRoundTrip() {
        byte[] payload = "encrypted-script-payload".getBytes();
        byte[] embedded = EmbeddedScriptFooter.embed(LIBRARY, payload);

        assertTrue("嵌完后必须能读回载荷", EmbeddedScriptFooter.hasPayload(embedded));
        assertArrayEquals(payload, EmbeddedScriptFooter.payloadOf(embedded));
        // 库本体没有被改动：前面的字节还是原样，载荷只是追加在后面。
        assertArrayEquals(LIBRARY, Arrays.copyOfRange(embedded, 0, LIBRARY.length));
    }

    @Test
    public void libraryWithoutFooterHasNoPayload() {
        assertNull(EmbeddedScriptFooter.payloadOf(LIBRARY));
        assertFalse(EmbeddedScriptFooter.hasPayload(LIBRARY));
        assertNull(EmbeddedScriptFooter.payloadOf(new byte[0]));
        assertNull(EmbeddedScriptFooter.payloadOf(null));
    }

    @Test
    public void tamperedPayloadIsRejected() {
        byte[] embedded = EmbeddedScriptFooter.embed(LIBRARY, "payload-bytes".getBytes());
        // 改动载荷里的一个字节：摘要对不上，必须当作「没有载荷」，不能把半截密文交给解密。
        embedded[LIBRARY.length] ^= 0x5A;
        assertNull(EmbeddedScriptFooter.payloadOf(embedded));
    }

    @Test
    public void truncatedFileIsRejected() {
        byte[] embedded = EmbeddedScriptFooter.embed(LIBRARY, "payload-bytes".getBytes());
        byte[] truncated = Arrays.copyOf(embedded, embedded.length - 4);
        assertNull(EmbeddedScriptFooter.payloadOf(truncated));
    }

    @Test
    public void absurdLengthIsRejected() {
        byte[] embedded = EmbeddedScriptFooter.embed(LIBRARY, "payload-bytes".getBytes());
        // 把长度字段改成天文数字：不能照着它去分配内存/越界读。
        int lengthOffset = embedded.length - EmbeddedScriptFooter.SIZE + 8;
        embedded[lengthOffset] = (byte) 0xFF;
        embedded[lengthOffset + 1] = (byte) 0xFF;
        embedded[lengthOffset + 2] = (byte) 0xFF;
        embedded[lengthOffset + 3] = (byte) 0x7F;
        assertNull(EmbeddedScriptFooter.payloadOf(embedded));
    }

    @Test
    public void wrongLibraryBytesStillReadPayload() {
        // 载荷与库内容无关：换一份「库」，只要尾部格式在就应读得出来（校验只覆盖载荷）。
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        byte[] other = EmbeddedScriptFooter.embed("another-library".getBytes(), payload);
        assertArrayEquals(payload, EmbeddedScriptFooter.payloadOf(other));
    }
}
