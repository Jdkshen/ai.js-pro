package com.stardust.autojs.engine.encryption;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * task-126：v2 载荷（AES-256-GCM）离线判据。**注意：本轮不跑 gradle ⇒ 未执行**。
 */
public class ScriptPayloadV2Test {

    private static final String PKG = "com.example.app";
    private static final String SALT = "0123456789abcdef0123456789abcdef";
    private static final String FP =
            "38166a4b51f1a3992c96512324345a994c8e7331a531e6e51ce3c9d91691cc70";
    private static final byte[] PLAIN = "console.log('hi');".getBytes(StandardCharsets.UTF_8);

    /** ① v2 往返 + 头部布局逐字节符合定稿（魔数/版本/保留/IV/AAD）。 */
    @Test
    public void v2RoundTripAndHeaderLayout() throws Exception {
        byte[] key = ScriptPayloadV2.deriveKeyV2(PKG, SALT, FP);
        assertEquals("密钥必须是真 32 字节", 32, key.length);
        byte[] payload = ScriptPayloadV2.encrypt(PLAIN, key, 1, 0);
        assertEquals("魔数", "7701177f1212", hex(payload, 0, 6));
        assertEquals("flags 低/高", "0100", hex(payload, 6, 2));
        assertEquals("第 9 字节必须是 0x02", (byte) 0x02, payload[8]);
        assertEquals("3 字节保留", "000000", hex(payload, 9, 3));
        assertEquals("12 字节 IV", 12, Arrays.copyOfRange(payload, 12, 24).length);
        assertEquals("总长 = 24 + 明文 + 16", 24 + PLAIN.length + 16, payload.length);
        assertArrayEquals("往返必须一致", PLAIN, ScriptPayloadV2.decrypt(payload, key));
        assertTrue(ScriptPayloadV2.isV2(payload));
    }

    /** ② 每文件 IV 随机（两次加密的 IV 不同、密文不同），且可各自解回。 */
    @Test
    public void ivIsRandomPerFile() throws Exception {
        byte[] key = ScriptPayloadV2.deriveKeyV2(PKG, SALT, FP);
        byte[] a = ScriptPayloadV2.encrypt(PLAIN, key, 1, 0);
        byte[] b = ScriptPayloadV2.encrypt(PLAIN, key, 1, 0);
        assertFalse("IV 不得复用", Arrays.equals(Arrays.copyOfRange(a, 12, 24), Arrays.copyOfRange(b, 12, 24)));
        assertFalse("同明文两次密文不得相同", Arrays.equals(a, b));
        assertArrayEquals(PLAIN, ScriptPayloadV2.decrypt(a, key));
        assertArrayEquals(PLAIN, ScriptPayloadV2.decrypt(b, key));
    }

    /** ③ 篡改必失败且**报错可读**（GCM 完整性）：密文、tag、IV、AAD 各改一处。 */
    @Test
    public void tamperingFailsWithReadableError() throws Exception {
        byte[] key = ScriptPayloadV2.deriveKeyV2(PKG, SALT, FP);
        byte[] payload = ScriptPayloadV2.encrypt(PLAIN, key, 1, 0);
        int[][] spots = { { 24 }, { payload.length - 1 }, { 12 }, { 6 } };
        for (int[] spot : spots) {
            byte[] tampered = payload.clone();
            tampered[spot[0]] ^= 0x01;
            try {
                ScriptPayloadV2.decrypt(tampered, key);
                fail("篡改位置 " + spot[0] + " 必须解密失败");
            } catch (IllegalStateException expected) {
                assertTrue("报错必须说明完整性失败：" + expected.getMessage(),
                        expected.getMessage().contains("完整性校验失败"));
                assertTrue("报错要提示可能是旧格式误判（不误导）",
                        expected.getMessage().contains("也可能是旧格式被误判"));
            }
        }
        // 错的密钥也必须给可读错误（不是裸 AEADBadTagException）
        try {
            ScriptPayloadV2.decrypt(payload, ScriptPayloadV2.deriveKeyV2(PKG, SALT, FP.replace('3', '4')));
            fail("错密钥必须失败");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("完整性校验失败"));
        }
    }

    /** ④ 旧格式（第 9 字节不是 0x02）点名拒绝；短载荷/坏魔数各有具名错误。 */
    @Test
    public void legacyPayloadIsRejectedByName() throws Exception {
        byte[] key = ScriptPayloadV2.deriveKeyV2(PKG, SALT, FP);
        byte[] payload = ScriptPayloadV2.encrypt(PLAIN, key, 1, 0);
        byte[] legacy = payload.clone();
        legacy[8] = 0x01;
        assertTrue(ScriptPayloadV2.isLegacyPayload(legacy));
        // 1/256 歧义：v1 的第 9 字节恰好是 0x02 时，靠「保留字节必须为 0」把它判回旧格式（不误判成 v2）。
        byte[] ambiguous = payload.clone();
        ambiguous[9] = 0x01;
        assertFalse("保留字节非 0 ⇒ 不是 v2", ScriptPayloadV2.isV2(ambiguous));
        assertTrue("保留字节非 0 ⇒ 按旧格式", ScriptPayloadV2.isLegacyPayload(ambiguous));
        try {
            ScriptPayloadV2.decrypt(ambiguous, key);
            fail("歧义情形必须按旧格式点名拒绝");
        } catch (IllegalStateException expected) {
            assertEquals(ScriptPayloadV2.LEGACY_REJECTED_MESSAGE, expected.getMessage());
        }
        try {
            ScriptPayloadV2.decrypt(legacy, key);
            fail("旧格式必须点名拒绝");
        } catch (IllegalStateException expected) {
            assertEquals(ScriptPayloadV2.LEGACY_REJECTED_MESSAGE, expected.getMessage());
        }
        try {
            ScriptPayloadV2.decrypt(new byte[] { 1, 2, 3 }, key);
            fail("短载荷必须具名错误");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("太短"));
        }
        byte[] badMagic = payload.clone();
        badMagic[0] = 0x00;
        try {
            ScriptPayloadV2.decrypt(badMagic, key);
            fail("坏魔数必须具名错误");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("魔数"));
        }
    }

    /** ⑤ 跨端一致性锚点：固定 (包名,盐,指纹) 的 v2 key 必须等于这个固定值（C 侧可据此比对）。 */
    @Test
    public void v2KeyIsPinnedForCrossEndComparison() {
        byte[] key = ScriptPayloadV2.deriveKeyV2(PKG, SALT, FP);
        assertArrayEquals(hexToBytes(V2_KEY_PIN), key);
        assertEquals("原生原语不足：必须有点名文案",
                "原生解密库不支持 v2（AES-256-GCM）：v2 载荷必须走 JVM 路径；原生只认 v1。",
                ScriptPayloadV2.NATIVE_UNSUPPORTED_MESSAGE);
    }

    /** ⑥ 不得再有 MD5 派生路径（派生类 + 启动器源码钉）。 */
    @Test
    public void noMd5DerivationRemains() throws Exception {
        // 与 Lead 的修法一致：**剥注释只看代码**（KDoc 里写着「旧 MD5 方案已下线」会误判）。
        String derivation = codeOnly(read("src/main/java/com/stardust/autojs/project/ScriptKeyDerivation.java"));
        assertFalse(derivation.contains("legacyKey"));
        assertFalse(derivation.contains("legacyVector"));
        assertFalse("派生类不得再有 MD5 派生", derivation.contains("MD5") || derivation.contains("md5"));
        String launcher = codeOnly(read("../../apps/inrt/src/main/java/com/jdkshen/aijspro/inrt/launch/AssetsProjectLauncher.kt"));
        assertFalse(launcher.contains("legacyKey("));
        assertFalse(launcher.contains("legacyVector("));
    }

    private static final String V2_KEY_PIN = "E85D888ACFB88B0AED5BA201EB6BF1C5F21DF9F8FE14E7FEAE33B3128FD446D3";

    /** task-122/126：剥掉注释与字符串里的注释行，只留代码（避免 KDoc 文案造成误报）。 */
    private static String codeOnly(String source) {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            int comment = line.indexOf("//");
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return out.toString();
    }

    private static String read(String relativePath) throws Exception {
        return new String(Files.readAllBytes(Paths.get(relativePath)), StandardCharsets.UTF_8);
    }

    private static String hex(byte[] data, int offset, int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = offset; i < offset + length; i++) {
            builder.append(String.format("%02x", data[i]));
        }
        return builder.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}