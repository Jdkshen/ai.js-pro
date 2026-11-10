package com.jdkshen.aijspro.autojs;

import com.stardust.autojs.engine.encryption.ScriptPayloadV2;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * task-127：打包端（ApkBuilder 的 v2 加密路径）与运行端的加密约定必须一致 ——
 * 现在是 **AES-256-GCM + 真 32 字节密钥 + 每文件随机 IV**（旧的 MD5/CBC 方案已下线）。
 *
 * <p>本测试不再复现旧公式：直接调引擎侧唯一实现 ScriptPayloadV2，验证打包端会用到的三件事：
 * ① 同明文两次加密字节不同（IV 随机，GCM 下绝不复用）；② 能解回原文；③ 换盐/换指纹＝换密钥。</p>
 */
public class ScriptEncryptionRoundTripTest {

    private static final String PACKAGE_NAME = "com.example.packaged";
    private static final String SALT = "00112233445566778899aabbccddeeff";
    private static final String FINGERPRINT =
            "1B:57:50:5E:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55";

    @Test
    public void packagedScriptCanBeDecryptedByRuntimeConvention() throws Exception {
        String script = "console.log('hello encrypted world');\nvar x = 1 + 2;\n";
        byte[] plain = script.getBytes("UTF-8");

        // 打包端 ApkBuilder.encryptedPayload() 的等价序列：ScriptPayloadV2.encrypt（头 + IV + 密文 + tag）。
        byte[] packaged = ScriptPayloadV2.encrypt(plain, key(SALT, FINGERPRINT), 0, 0);

        assertTrue("必须是 v2 载荷", ScriptPayloadV2.isV2(packaged));
        assertEquals("版本字节必须是 0x02", ScriptPayloadV2.VERSION, packaged[8]);
        assertEquals(script, new String(ScriptPayloadV2.decrypt(packaged, key(SALT, FINGERPRINT)), "UTF-8"));
    }

    /** IV 每文件随机：同明文两次加密的字节必须不同（GCM 复用 IV 是致命的）。 */
    @Test
    public void randomIvMakesEveryPayloadDifferent() throws Exception {
        byte[] plain = "plain-script".getBytes("UTF-8");
        byte[] first = ScriptPayloadV2.encrypt(plain, key(SALT, FINGERPRINT), 0, 0);
        byte[] second = ScriptPayloadV2.encrypt(plain, key(SALT, FINGERPRINT), 0, 0);
        assertFalse("两次加密必须不同（IV 随机）", java.util.Arrays.equals(first, second));
        assertArrayEquals(plain, ScriptPayloadV2.decrypt(first, key(SALT, FINGERPRINT)));
        assertArrayEquals(plain, ScriptPayloadV2.decrypt(second, key(SALT, FINGERPRINT)));
    }

    /** 密钥绑定包名/盐/指纹：换盐或换指纹都必须解不开（GCM 完整性校验失败）。 */
    @Test
    public void keyIsBoundToPackageSaltAndFingerprint() throws Exception {
        byte[] plain = "bound".getBytes("UTF-8");
        byte[] packaged = ScriptPayloadV2.encrypt(plain, key(SALT, FINGERPRINT), 0, 0);
        assertEquals(32, key(SALT, FINGERPRINT).length);
        for (byte[] other : new byte[][]{
                key("ffeeddccbbaa99887766554433221100", FINGERPRINT),
                key(SALT, FINGERPRINT.replace("1B", "1C"))}) {
            try {
                ScriptPayloadV2.decrypt(packaged, other);
                org.junit.Assert.fail("换了盐/指纹还能解密，说明密钥没绑定");
            } catch (Exception expected) {
                // 预期：GCM 完整性校验失败。
            }
        }
    }

    private static byte[] key(String salt, String fingerprint) {
        return ScriptPayloadV2.deriveKeyV2(PACKAGE_NAME, salt, fingerprint);
    }
}
