package com.stardust.autojs.project;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 脚本密钥派生单测（纯 JVM）。
 *
 * <p>锁住三件事：新方案可重复、随机盐与签名指纹都会影响结果、旧方案保持不变
 * （老产物必须继续能打开）。
 */
public class ScriptKeyDerivationTest {

    private static final String PACKAGE_NAME = "com.example.packaged";
    private static final String FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
    private static final String SALT = "0123456789abcdef0123456789abcdef";

    @Test
    public void derivationIsDeterministic() {
        assertEquals(ScriptKeyDerivation.deriveKey(PACKAGE_NAME, SALT, FINGERPRINT),
                ScriptKeyDerivation.deriveKey(PACKAGE_NAME, SALT, FINGERPRINT));
        assertEquals(ScriptKeyDerivation.deriveVector(SALT, FINGERPRINT),
                ScriptKeyDerivation.deriveVector(SALT, FINGERPRINT));
        // 密钥 32 字节（64 个十六进制半字节取前 32 个字符）、向量 16 字节
        assertEquals(32, ScriptKeyDerivation.deriveKey(PACKAGE_NAME, SALT, FINGERPRINT).length());
        assertEquals(16, ScriptKeyDerivation.deriveVector(SALT, FINGERPRINT).length());
    }

    @Test
    public void saltAndFingerprintBothChangeTheKey() {
        String base = ScriptKeyDerivation.deriveKey(PACKAGE_NAME, SALT, FINGERPRINT);
        String otherSalt = ScriptKeyDerivation.deriveKey(PACKAGE_NAME,
                "ffffffffffffffffffffffffffffffff", FINGERPRINT);
        String otherFingerprint = ScriptKeyDerivation.deriveKey(PACKAGE_NAME, SALT,
                "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00");
        String otherPackage = ScriptKeyDerivation.deriveKey("com.example.other", SALT, FINGERPRINT);

        assertFalse("换盐必须换密钥", base.equals(otherSalt));
        assertFalse("换签名指纹必须换密钥（用别的证书重签就解不开）",
                base.equals(otherFingerprint));
        assertFalse("换包名必须换密钥", base.equals(otherPackage));
    }

    @Test
    public void vectorDiffersFromKeyForTheSameSalt() {
        // 同一个盐在两个用途上要派生出不同的值，否则 IV 与密钥会被关联出来
        assertFalse(ScriptKeyDerivation.deriveVector(SALT, FINGERPRINT)
                .equals(ScriptKeyDerivation.deriveKey(PACKAGE_NAME, SALT, FINGERPRINT)
                        .substring(0, 16)));
    }

    @Test
    public void randomSaltLooksLikeHex() {
        String salt = ScriptKeyDerivation.randomSaltHex();
        assertEquals(ScriptKeyDerivation.SALT_BYTES * 2, salt.length());
        assertTrue("盐必须是十六进制", salt.matches("[0-9a-f]+"));
        assertFalse("两次生成的盐不应相同",
                salt.equals(ScriptKeyDerivation.randomSaltHex()));
    }

    @Test
    public void legacyDerivationStaysUnchanged() {
        // 老产物（无盐）必须继续用旧算法：这里直接对着旧公式做断言，防止被无意改掉。
        assertEquals(com.stardust.util.MD5.md5("com.example.packaged1.0.0main.js"),
                ScriptKeyDerivation.legacyKey("com.example.packaged", "1.0.0", "main.js"));
        assertEquals(com.stardust.util.MD5.md5("BUILD-1AppName").substring(0, 16),
                ScriptKeyDerivation.legacyVector("BUILD-1", "AppName"));
    }

    @Test
    public void fingerprintUsabilityFollowsLength() {
        assertTrue(ScriptKeyDerivation.hasCertificateFingerprint(FINGERPRINT));
        assertFalse(ScriptKeyDerivation.hasCertificateFingerprint(null));
        assertFalse(ScriptKeyDerivation.hasCertificateFingerprint(""));
        assertFalse("占位/残缺值不能被当作可用指纹",
                ScriptKeyDerivation.hasCertificateFingerprint("AA:BB"));
    }
}
